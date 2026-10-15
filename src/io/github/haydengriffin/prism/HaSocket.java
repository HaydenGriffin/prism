package io.github.haydengriffin.prism;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One authenticated Home Assistant WebSocket connection. A single reader thread completes waiting requests by id
 * and hands every message (events included) to the listener.
 */
final class HaSocket implements Closeable {

    interface Listener {
        /** Every message, on the reader thread, after any waiting request has been completed. */
        void received(HaSocket socket, Map<String, Object> message);

        /** Once, on the reader thread, when the connection has ended for any reason. */
        void closed(HaSocket socket, Exception cause);
    }

    /** auth_invalid: the token is wrong or revoked. */
    static final class AuthRefused extends IOException {
        AuthRefused() {
            super("Home Assistant refused the token.");
        }
    }

    /** A request HA answered with success=false; carries HA's error code only, never the message text. */
    static final class Refused extends IOException {
        final String code;

        Refused(String code) {
            super("Home Assistant refused the request (" + code + ").");
            this.code = code;
        }
    }

    private final WebSocket ws;
    private final HealthClock clock;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
    private volatile long lastMessageAt;
    private volatile Listener listener;

    private HaSocket(WebSocket ws, HealthClock clock) {
        this.ws = ws;
        this.clock = clock;
        this.lastMessageAt = clock.now();
    }

    /** Connects and signs in synchronously (each step bounded by timeoutMs), then starts the reader. */
    static HaSocket open(String origin, String token, int timeoutMs, Listener listener) throws IOException {
        return open(origin, token, timeoutMs, listener, HealthClock.MONOTONIC);
    }

    /** As open(), with liveness (lastMessageAt) on the given clock. */
    static HaSocket open(String origin, String token, int timeoutMs, Listener listener, HealthClock clock)
        throws IOException {
        WebSocket ws = WebSocket.connect(origin, "/api/websocket", timeoutMs);
        try {
            HaSocket socket = new HaSocket(ws, clock);
            socket.authenticate(token);
            ws.readTimeout(0);
            socket.listener = listener;
            Thread reader = new Thread(socket::readLoop, "ha-socket-reader");
            reader.setDaemon(true);
            reader.start();
            return socket;
        } catch (IOException | RuntimeException e) {
            ws.close();
            throw e;
        }
    }

    /** Sends a message without waiting for its reply; fields are the JSON members after "id". */
    void send(String fields) throws IOException {
        ws.sendText(message(ids.incrementAndGet(), fields));
    }

    /** Sends a request and returns HA's "result" member; throws on refusal, close or timeout. */
    Object request(String fields, long timeoutMs) throws IOException {
        Map<String, Object> reply = call(fields, timeoutMs);
        if (!Boolean.TRUE.equals(reply.get("success"))) {
            throw new Refused(Json.text(Json.object(reply.get("error")).get("code")));
        }
        return reply.get("result");
    }

    /** HealthClock milliseconds of the most recent message from HA. */
    long lastMessageAt() {
        return lastMessageAt;
    }

    static String callService(String domain, String service, String data) {
        return "\"type\":\"call_service\",\"domain\":" + Json.quote(domain) + ",\"service\":" + Json.quote(service)
            + ",\"service_data\":" + data;
    }

    @Override
    public void close() {
        try {
            ws.close();
        } catch (IOException ignored) {
            // Closing is best effort; the reader reports the end.
        }
    }

    private Map<String, Object> call(String fields, long timeoutMs) throws IOException {
        int id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> reply = new CompletableFuture<>();
        waiting.put(id, reply);
        try {
            ws.sendText(message(id, fields));
            return reply.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new SocketTimeoutException("Home Assistant did not answer in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for Home Assistant.");
        } catch (ExecutionException e) {
            throw new EOFException("Home Assistant connection closed.");
        } finally {
            waiting.remove(id);
        }
    }

    private void authenticate(String token) throws IOException {
        if (!"auth_required".equals(type(ws.readText()))) throw new IOException("Unexpected Home Assistant handshake.");
        ws.sendText("{\"type\":\"auth\",\"access_token\":" + Json.quote(token) + "}");
        String reply = type(ws.readText());
        if ("auth_invalid".equals(reply)) throw new AuthRefused();
        if (!"auth_ok".equals(reply)) throw new IOException("Unexpected Home Assistant sign-in reply.");
    }

    private void readLoop() {
        Exception cause;
        try {
            while (true) dispatch(Json.object(Json.parse(ws.readText())));
        } catch (Exception e) {
            cause = e;
        }
        close();
        for (CompletableFuture<Map<String, Object>> reply : waiting.values()) reply.completeExceptionally(cause);
        listener.closed(this, cause);
    }

    private void dispatch(Map<String, Object> message) {
        lastMessageAt = clock.now();
        Object id = message.get("id");
        if (id instanceof Long && !"event".equals(message.get("type"))) {
            CompletableFuture<Map<String, Object>> reply = waiting.get(((Long) id).intValue());
            if (reply != null) reply.complete(message);
        }
        listener.received(this, message);
    }

    private static String type(String text) throws IOException {
        return Json.text(Json.object(Json.parse(text)).get("type"));
    }

    private static String message(int id, String fields) {
        return "{\"id\":" + id + "," + fields + "}";
    }
}
