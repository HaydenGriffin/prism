package io.github.haydengriffin.prism;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one HA WebSocket subscribed to a few entities (one subscription): connects, signs in, subscribes, pings, declares the link
 * dead after a silence and reconnects with backoff. Connection state and listener callbacks other than heard()
 * run on one link thread, so a callback from a replaced connection can never follow its lost() report.
 */
final class HaLink {

    enum Step { CONNECT, SUBSCRIBE, DROPPED, SILENT }

    interface Listener {
        /** After sign-in, before subscribing: forget what the previous connection said. */
        void opened();

        /** One subscribe_entities event ("a"/"c"/"r" members). */
        void event(Map<String, Object> event);

        /** Any message from HA, on the socket's reader thread. */
        void heard();

        /** The attempt failed or the live connection ended; a retry is already scheduled. */
        void lost(Step step, Exception cause);
    }

    static final class Timing {
        static final Timing STANDARD = new Timing(5000, 15000, 2000, 5000, new long[] {1000, 2000, 5000, 10000});

        final long pingMs;
        final long silenceMs;
        final long commandMs;
        final int setupMs;
        final long[] backoffMs;

        Timing(long pingMs, long silenceMs, long commandMs, int setupMs, long[] backoffMs) {
            this.pingMs = pingMs;
            this.silenceMs = silenceMs;
            this.commandMs = commandMs;
            this.setupMs = setupMs;
            this.backoffMs = backoffMs;
        }

        long backoff(int failures) {
            return backoffMs[Math.min(failures, backoffMs.length - 1)];
        }

        long watchdogMs() {
            return Math.max(20, Math.min(1000, silenceMs / 15));
        }
    }

    private final String origin;
    private final String token;
    private final List<String> entities;
    private final Timing timing;
    private final Listener listener;
    private final HealthClock clock;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
    /** Link thread only: the connection being set up or in use. */
    private HaSocket socket;
    /** The subscribed connection commands may use; null while down. */
    private volatile HaSocket live;
    private volatile boolean stopped;
    private int failures;
    /** Link thread only: the scheduled reconnect after a failure. */
    private ScheduledFuture<?> retry;
    private long pingedAt;

    HaLink(String origin, String token, List<String> entities, Timing timing, Listener listener, HealthClock clock) {
        this.clock = clock;
        this.origin = origin;
        this.token = token;
        this.entities = new ArrayList<>(entities);
        this.timing = timing;
        this.listener = listener;
    }

    void start() {
        post(this::connect);
        long every = timing.watchdogMs();
        thread.scheduleWithFixedDelay(this::tick, every, every, TimeUnit.MILLISECONDS);
    }

    /**
     * The Shield just woke (Doze held the network, the CPU was suspended). An unhealthy link (by the sleep-aware
     * clock) connects now instead of waiting out the backoff; a healthy one that has been quiet for a ping interval
     * is pinged at once, so a half-dead socket shows within silenceMs. Any thread; runs on the link thread.
     */
    void reconnectNow() {
        post(() -> {
            if (stopped) return;
            if (healthy()) {
                HaSocket s = live;
                long now = clock.now();
                if (s == socket && now - s.lastMessageAt() >= timing.pingMs) ping(s, now);
                return;
            }
            HaSocket s = socket;
            if (s != null) drop(s, Step.SILENT, new SocketTimeoutException("Home Assistant went silent."));
            if (retry != null) retry.cancel(false);
            failures = 0;
            connect();
        });
    }

    void stop() {
        stopped = true;
        thread.shutdownNow();
        HaSocket s = live;
        if (s != null) s.close();
    }

    boolean healthy() {
        HaSocket s = live;
        return s != null && clock.now() - s.lastMessageAt() <= timing.silenceMs;
    }

    void press(String button) throws IOException {
        command(HaSocket.callService("button", "press", "{\"entity_id\":" + Json.quote(button) + "}"));
    }

    void select(String select, String option) throws IOException {
        String data = "{\"entity_id\":" + Json.quote(select) + ",\"option\":" + Json.quote(option) + "}";
        command(HaSocket.callService("select", "select_option", data));
    }

    /** set_value on a text-like entity (input_text, text); the domain is the entity's own. */
    void setValue(String entity, String value) throws IOException {
        String domain = entity.substring(0, Math.max(0, entity.indexOf('.')));
        String data = "{\"entity_id\":" + Json.quote(entity) + ",\"value\":" + Json.quote(value) + "}";
        command(HaSocket.callService(domain, "set_value", data));
    }

    /** One attempt, bounded by Timing.commandMs; never retried. */
    private void command(String fields) throws IOException {
        HaSocket s = live;
        if (s == null) throw new IOException("Not connected to Home Assistant.");
        s.request(fields, timing.commandMs);
    }

    private void connect() {
        if (stopped) return;
        Step step = Step.CONNECT;
        try {
            socket = HaSocket.open(origin, token, timing.setupMs, socketListener(), clock);
            listener.opened();
            step = Step.SUBSCRIBE;
            socket.request(subscription(), timing.setupMs);
            failures = 0;
            pingedAt = clock.now();
            live = socket;
            if (stopped) socket.close();
        } catch (Exception e) {
            fail(step, e);
        }
    }

    private String subscription() {
        StringBuilder ids = new StringBuilder();
        for (String entity : entities) ids.append(ids.length() == 0 ? "" : ",").append(Json.quote(entity));
        return "\"type\":\"subscribe_entities\",\"entity_ids\":[" + ids + "]";
    }

    private void tick() {
        HaSocket s = live;
        if (s == null || s != socket) return;
        long now = clock.now();
        if (now - s.lastMessageAt() > timing.silenceMs) {
            drop(s, Step.SILENT, new SocketTimeoutException("Home Assistant went silent."));
        } else if (now - pingedAt >= timing.pingMs) {
            ping(s, now);
        }
    }

    private void ping(HaSocket s, long now) {
        pingedAt = now;
        try {
            s.send("\"type\":\"ping\"");
        } catch (IOException e) {
            drop(s, Step.DROPPED, e);
        }
    }

    /** Link thread: ends this connection if it is still the current one. */
    private void drop(HaSocket which, Step step, Exception cause) {
        if (which != socket) return;
        fail(step, cause);
    }

    private void fail(Step step, Exception cause) {
        HaSocket s = socket;
        socket = null;
        live = null;
        if (s != null) s.close();
        if (stopped) return;
        listener.lost(step, cause);
        long delay = timing.backoff(failures++);
        try {
            retry = thread.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Stopped meanwhile.
        }
    }

    private HaSocket.Listener socketListener() {
        return new HaSocket.Listener() {
            @Override
            public void received(HaSocket from, Map<String, Object> message) {
                listener.heard();
                if ("event".equals(message.get("type"))) {
                    Map<String, Object> event = Json.object(message.get("event"));
                    post(() -> {
                        if (from == live) listener.event(event);
                    });
                }
            }

            @Override
            public void closed(HaSocket from, Exception cause) {
                post(() -> drop(from, Step.DROPPED, cause));
            }
        };
    }

    private void post(Runnable task) {
        try {
            thread.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Stopped: nothing more to report.
        }
    }
}
