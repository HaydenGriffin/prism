package io.github.haydengriffin.prism;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Captures navigation keys from the remotes allowed in Setup → Remotes while Home Assistant's pushed TV state says
 * an LG app is in front, and forwards them over the same WebSocket. Without a healthy link nothing is captured: keys
 * stay with the box.
 */
public final class AutomaticService extends AccessibilityService {

    static volatile AutomaticService live;

    private static final String TAG = "Prism";
    private static final long BACK_HOLD_MS = 800;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Link and route health: counts deep sleep. Key ages stay on uptimeMillis, KeyEvent's own time base. */
    private final HealthClock clock = SystemClock::elapsedRealtime;
    private final AutoRoute route = new AutoRoute(HaLink.Timing.STANDARD.silenceMs);
    private final KeyQueue keys = new KeyQueue();
    /** Guards session swaps against callbacks from the session being replaced. */
    private final Object lock = new Object();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor commands =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));
    private final ExecutorService escapes = Executors.newSingleThreadExecutor();
    /** Optional heartbeat and TV cue: HA calls that must never wait behind, or hold up, a press. */
    private final ScheduledExecutorService side = Executors.newSingleThreadScheduledExecutor();
    private volatile Heartbeat heartbeat;
    private final TvCue cue = new TvCue();
    /** Registry re-reads: slow (whole registries), so never on the link, key or side threads. */
    private final ExecutorService registry = Executors.newSingleThreadExecutor();
    private volatile boolean registryFailureLogged;
    private volatile Session session;
    /** Main thread only. */
    private final Set<Integer> captured = new HashSet<>();
    private long backAt = -1;
    private final Runnable heldBack = () -> {
        if (backAt >= 0) leaveLg();
    };
    /** Screen on: Doze may have held the network while the box slept; reconnect now, not after the backoff. */
    private final BroadcastReceiver woke = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Session s = session;
            if (s != null) s.link.reconnectNow();
        }
    };
    private boolean listening;
    /** Main thread only; set when the service connects. */
    private UserActivity activity;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("automatic", 0).getBoolean("enabled", false);
    }

    static void enabled(Context c, boolean enabled) {
        c.getSharedPreferences("automatic", 0).edit().putBoolean("enabled", enabled).apply();
        refresh();
    }

    static void refresh() {
        AutomaticService service = live;
        if (service != null) service.reload();
    }

    @Override
    protected void onServiceConnected() {
        live = this;
        if (activity == null) activity = new UserActivity(this);
        if (!listening) {
            IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
            wake.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(woke, wake);
            listening = true;
            heartbeat = new Heartbeat(versionName());
            side.scheduleWithFixedDelay(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
        }
        reload();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) RemotesStore.seen(this, event);
        if (RemotePolicy.observeOnly(code)) return observed(event);
        // A keyboard key we captured stays its keyboard key until released, even if the keyboard closed meanwhile.
        String key = RemotePolicy.key(code, route.keyboard() || captured.contains(code));
        if (key == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) return keyUp(event, key);
        if (captured.contains(event.getKeyCode())) return keyHeld(event, key);
        return keyDown(event, key);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        keys.clear();
    }

    @Override
    public void onDestroy() {
        live = null;
        if (listening) unregisterReceiver(woke);
        listening = false;
        main.removeCallbacksAndMessages(null);
        lifecycle.execute(() -> swap(null, new RemoteState(RemoteState.Mode.NO_PERMISSION, "")));
        lifecycle.shutdown();
        commands.shutdownNow();
        escapes.shutdownNow();
        side.shutdownNow();
        registry.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        Session s = session;
        RemoteState state = RemoteState.current;
        writer.println("state=" + state.mode + (state.detail.isEmpty() ? "" : " · " + state.detail));
        writer.println("target=" + route.current());
        writer.println("controlsLg=" + controlsLg());
        writer.println("keyboard=" + route.keyboard() + " keyboardText=" + (s != null && !s.tv.keyboard.isEmpty()));
        writer.println("linkHealthy=" + (s != null && s.link.healthy()));
        writer.println("enabled=" + enabled(this));
        writer.println("dozeExempt=" + DozeExemption.granted(this));
    }

    private void reload() {
        try {
            lifecycle.execute(this::restart);
        } catch (RejectedExecutionException ignored) {
            // Destroyed.
        }
    }

    /** Lifecycle thread: replaces the session from the saved settings. */
    private void restart() {
        if (!enabled(this)) {
            swap(null, new RemoteState(RemoteState.Mode.OFF, ""));
            return;
        }
        try {
            Session next = newSession();
            swap(next, new RemoteState(next == null ? RemoteState.Mode.SETUP : RemoteState.Mode.CONNECTING, ""));
            if (next != null) next.link.start();
        } catch (Exception e) {
            logFailure("saved connection", e);
            swap(null, new RemoteState(RemoteState.Mode.ERROR, "Could not read the saved connection"));
        }
    }

    private Session newSession() throws Exception {
        Credentials credentials = Credentials.load(this);
        TvProfile tv = TvProfile.load(this);
        if (credentials == null || tv == null || tv.shieldApp.isEmpty()) return null;
        return new Session(credentials, tv, MonitoringScreen.entity(this));
    }

    private void swap(Session next, RemoteState state) {
        Session old;
        synchronized (lock) {
            old = session;
            session = next;
            route.reset();
            dropQueued();
            RemoteState.current = state;
        }
        Heartbeat beat = heartbeat;
        if (beat != null) beat.reset();
        cue.reset();
        main.post(this::releaseHeld);
        if (old != null) old.link.stop();
    }

    /** Main thread, after a swap: a held Back or key from the old session never acts on the new one. */
    private void releaseHeld() {
        main.removeCallbacks(heldBack);
        backAt = -1;
        captured.clear();
    }

    private void dropQueued() {
        keys.clear();
        commands.getQueue().clear();
    }

    private boolean controlsLg() {
        return route.controlsLg(clock.now());
    }

    private boolean keyUp(KeyEvent event, String key) {
        int code = event.getKeyCode();
        if (!captured.remove(code)) return false;
        keys.release(key);
        if (code == KeyEvent.KEYCODE_BACK) backReleased(event);
        return true;
    }

    private void backReleased(KeyEvent event) {
        main.removeCallbacks(heldBack);
        if (backAt >= 0 && !event.isCanceled()) {
            if (event.getEventTime() - backAt >= BACK_HOLD_MS) leaveLg();
            else if (controlsLg()) send("back", false);
        }
        backAt = -1;
    }

    /** A key we captured stays ours until released; its repeats go to the LG only while routing still says so. */
    private boolean keyHeld(KeyEvent event, String key) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK && controlsLg()) send(key, true);
        return true;
    }

    private boolean keyDown(KeyEvent event, String key) {
        // Repeats of a hold whose first DOWN passed to the Shield stay with the Shield.
        if (event.getRepeatCount() > 0 || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        Session s = session;
        if (s == null || !s.tv.buttons.containsKey(key) || !RemotesStore.controls(this, event) || !controlsLg()) return false;
        captured.add(event.getKeyCode());
        showCue(s);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            backAt = event.getEventTime();
            main.postDelayed(heldBack, BACK_HOLD_MS);
        } else {
            send(key, false);
        }
        return true;
    }

    /** Home is never consumed (Android still goes home); on the LG it also does what held Back does. */
    private boolean observed(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (RemotePolicy.homeLeavesLg(event.getKeyCode(), down, event.getRepeatCount(), RemotesStore.controls(this, event), controlsLg())) {
            leaveLg();
        }
        return false;
    }

    private void send(String key, boolean repeat) {
        Session s = session;
        String button = s == null ? null : s.tv.buttons.get(key);
        if (button == null) return;
        long seq = keys.admit(key, repeat, SystemClock.uptimeMillis());
        if (seq == 0) return;
        if (activity != null) activity.press();
        long revision = route.revision();
        try {
            commands.execute(() -> press(s, button, seq, revision));
        } catch (RejectedExecutionException e) {
            keys.done(seq, true);
        }
    }

    /** Command thread: one attempt; anything but a confirmed press flushes what is queued behind it. */
    private void press(Session s, String button, long seq, long revision) {
        boolean failed = true;
        try {
            if (!stillWanted(s, seq, revision)) return;
            s.link.press(button);
            failed = false;
        } catch (IOException e) {
            Log.w(TAG, "press not confirmed: " + e.getClass().getSimpleName());
        } finally {
            keys.done(seq, failed);
        }
    }

    private boolean stillWanted(Session s, long seq, long revision) {
        return s == session && revision == route.revision() && keys.shouldSend(seq, SystemClock.uptimeMillis())
            && route.controlsLg(clock.now());
    }

    /** Held Back or Home: release the remote locally at once, then ask the LG for the Shield input. */
    private void leaveLg() {
        backAt = -1;
        route.pause();
        dropQueued();
        Session s = session;
        if (s == null) return;
        s.publishRoute();
        try {
            escapes.execute(s::selectShield);
        } catch (RejectedExecutionException ignored) {
            // Destroyed.
        }
    }

    /** Main thread, on a captured press: fire-and-forget; a refusal or a missing entity is silent. */
    private void showCue(Session s) {
        if (s.tv.notice.isEmpty() || !cue.firstPress()) return;
        try {
            side.execute(() -> {
                try {
                    s.link.setValue(s.tv.notice, TvCue.TEXT);
                } catch (Exception ignored) {
                    // Optional cue.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Destroyed.
        }
    }

    /**
     * Registry thread, once per successful connect: follow added, removed or renamed TV entities without a trip to
     * Setup. The Shield input stays as chosen; a new active or launch entity restarts the session so the
     * subscription follows. A failed read keeps the saved profile.
     */
    private void refreshProfile(Session s) {
        try {
            TvProfile next = s.tv.refreshed(s.credentials);
            registryFailureLogged = false;
            if (next == null || s != session) return;
            TvProfile stored = TvProfile.load(this);
            // Setup saved a different TV meanwhile: its reload wins.
            if (stored == null || !stored.flat().equals(s.tv.flat())) return;
            next.save(this);
            Log.i(TAG, "TV entities refreshed from the registry");
            if (TvRoles.subscriptionChanged(s.tv.flat(), next.flat())) reload();
            else s.tv = next;
        } catch (Exception e) {
            if (!registryFailureLogged) logFailure("TV registry refresh", e);
            registryFailureLogged = true;
        }
    }

    private void beat() {
        try {
            side.execute(this::sendHeartbeat);
        } catch (RejectedExecutionException ignored) {
            // Destroyed.
        }
    }

    /** Side thread. Only when monitoring is on; a refusal (helper missing) is logged once and never affects routing. */
    private void sendHeartbeat() {
        Heartbeat beat = heartbeat;
        Session s = session;
        if (beat == null || (s != null && s.monitored == null)) return;
        if (s == null || !s.link.healthy()) {
            beat.reset();
            return;
        }
        String value = beat.due(RemoteState.current.mode, clock.now());
        if (value == null) return;
        try {
            s.link.setValue(s.monitored, value);
            beat.succeeded(value, clock.now());
        } catch (Exception e) { // Anything escaping would cancel the periodic task.
            if (beat.firstFailure()) Log.w(TAG, "heartbeat not accepted: " + reason(e));
        }
    }

    private static String reason(Exception e) {
        return e instanceof HaSocket.Refused ? ((HaSocket.Refused) e).code : e.getClass().getSimpleName();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static void logFailure(String step, Exception e) {
        // Never log credentials, response bodies or exception messages.
        StackTraceElement[] trace = e.getStackTrace();
        String at = trace.length == 0 ? "unknown" : trace[0].toString();
        Log.w(TAG, step + ": " + e.getClass().getSimpleName() + " at " + at);
    }

    /** One TV and one HA link, from one reload. Callbacks from a replaced session are ignored. */
    private final class Session implements HaLink.Listener {
        /** Replaced in place when a registry refresh changes only buttons, settings, notice, keyboard or input. */
        volatile TvProfile tv;
        final Credentials credentials;
        final HaLink link;
        /** The monitoring input_text, or null while monitoring is off. */
        final String monitored;
        /** Guarded by lock. */
        private final TvState state;
        private String lastFailure = "";
        /** Guarded by lock: the registry re-read is due on this connection's first event. */
        private boolean refreshDue;

        Session(Credentials credentials, TvProfile tv, String monitored) {
            this.tv = tv;
            this.credentials = credentials;
            this.monitored = monitored;
            this.state = new TvState(route, tv.active, tv.launch, tv.shieldApp);
            this.link = new HaLink(credentials.url, credentials.token, Arrays.asList(tv.active, tv.launch),
                HaLink.Timing.STANDARD, this, clock);
        }

        @Override
        public void opened() {
            synchronized (lock) {
                if (session != this) return;
                state.opened();
                refreshDue = true;
            }
        }

        @Override
        public void event(Map<String, Object> event) {
            synchronized (lock) {
                if (session != this) return;
                state.apply(event, clock.now());
                publishRoute();
                ChannelWatch.observe(AutomaticService.this, route.current(), tv.shieldApp);
                if (refreshDue) {
                    refreshDue = false;
                    refresh();
                }
                if (!lastFailure.isEmpty()) Log.i(TAG, "TV link recovered");
                lastFailure = "";
            }
        }

        @Override
        public void heard() {
            route.heard(clock.now());
        }

        @Override
        public void lost(HaLink.Step step, Exception cause) {
            synchronized (lock) {
                if (session != this) return;
                state.lost();
                cue.reset();
                Heartbeat beat = heartbeat;
                if (beat != null) beat.reset();
                dropQueued();
                RemoteState.current = RouteStatus.failure(step, cause);
                String failure = step + ": " + cause.getClass().getSimpleName();
                if (!failure.equals(lastFailure)) logFailure(step.toString(), cause);
                lastFailure = failure;
            }
        }

        void publishRoute() {
            synchronized (lock) {
                if (session != this) return;
                RemoteState.current = state.status(clock.now());
                cue.route(route.controlsLg(clock.now()));
            }
            beat();
        }

        private void refresh() {
            try {
                registry.execute(() -> refreshProfile(this));
            } catch (RejectedExecutionException ignored) {
                // Destroyed.
            }
        }

        void selectShield() {
            try {
                link.select(tv.input, tv.shieldOption);
            } catch (IOException e) {
                Log.w(TAG, "Box input not confirmed: " + e.getClass().getSimpleName());
            }
        }
    }
}
