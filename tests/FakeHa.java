package io.github.haydengriffin.prism;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-test Home Assistant WebSocket: RFC 6455 handshake, auth, subscribe_entities, ping, call_service. Written
 * against the protocol, not the client classes, apart from parsing JSON the client sends.
 */
final class FakeHa implements AutoCloseable {

    static final String TOKEN = "fixture-token";

    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger pings = new AtomicInteger();
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean answerPings = true;
    volatile boolean answerCommands = true;
    volatile String commandError;
    volatile boolean refuseSubscribe;
    volatile boolean wrongAccept;
    volatile String initialState = "{\"s\":\"on\",\"a\":{\"app_id\":\"example.video\"}}";
    volatile String entity = "sensor.tv_active_app";
    volatile String launch = "select.tv_launch_app";
    volatile String launchState = "{\"s\":\"Video\",\"a\":{\"app_ids\":{\"Video\":\"example.video\"}}}";
    /** Pads the next generic result by this many characters (large-frame tests). */
    volatile int nextResultPadding;

    private final ServerSocket server;
    private final List<Connection> open = new CopyOnWriteArrayList<>();

    FakeHa() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "fake-ha-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String origin() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Sends a raw subscription event body (the "event" member) to every subscribed connection. */
    void push(String event) {
        for (Connection c : open) c.event(event);
    }

    void dropAll() {
        for (Connection c : open) c.close();
    }

    @Override
    public void close() throws IOException {
        dropAll();
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Connection c = new Connection(server.accept());
                connections.incrementAndGet();
                open.add(c);
                Thread t = new Thread(c::serve, "fake-ha-connection");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private final class Connection {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile int subscription = -1;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void serve() {
            try {
                handshake();
                send("{\"type\":\"auth_required\",\"ha_version\":\"2026.10.0\"}");
                Map<String, Object> auth = read();
                if (!TOKEN.equals(auth.get("access_token"))) {
                    send("{\"type\":\"auth_invalid\",\"message\":\"Invalid access token or password\"}");
                    return;
                }
                send("{\"type\":\"auth_ok\",\"ha_version\":\"2026.10.0\"}");
                while (true) answer(read());
            } catch (IOException ignored) {
                // Client went away.
            } finally {
                close();
            }
        }

        void close() {
            open.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }

        void event(String event) {
            if (subscription < 0) return;
            try {
                send("{\"id\":" + subscription + ",\"type\":\"event\",\"event\":" + event + "}");
            } catch (IOException ignored) {
                close();
            }
        }

        private void answer(Map<String, Object> message) throws IOException {
            long id = (Long) message.get("id");
            String type = (String) message.get("type");
            if (type.equals("ping")) {
                pings.incrementAndGet();
                if (answerPings) send("{\"id\":" + id + ",\"type\":\"pong\"}");
            } else if (type.equals("subscribe_entities")) {
                subscribe(id, message);
            } else if (type.equals("call_service")) {
                calls.add(message.get("domain") + "." + message.get("service") + " " + message.get("service_data"));
                if (answerCommands) reply(id, commandError);
            } else {
                String padding = nextResultPadding > 0 ? String.join("", Collections.nCopies(nextResultPadding, "x")) : "";
                nextResultPadding = 0;
                send("{\"id\":" + id + ",\"type\":\"result\",\"success\":true,\"result\":[\"" + padding + "\"]}");
            }
        }

        private void subscribe(long id, Map<String, Object> message) throws IOException {
            if (refuseSubscribe) {
                reply(id, "unauthorized");
                return;
            }
            List<?> ids = Json.array(message.get("entity_ids"));
            StringBuilder added = new StringBuilder();
            for (Object requested : ids) {
                String state = requested.equals(entity) ? initialState : requested.equals(launch) ? launchState : null;
                if (state == null) throw new IOException("bad ids");
                added.append(added.length() == 0 ? "" : ",").append(Json.quote((String) requested)).append(':').append(state);
            }
            reply(id, null);
            subscription = (int) id;
            event("{\"a\":{" + added + "}}");
        }

        private void reply(long id, String error) throws IOException {
            if (error == null) {
                send("{\"id\":" + id + ",\"type\":\"result\",\"success\":true,\"result\":null}");
            } else {
                send("{\"id\":" + id + ",\"type\":\"result\",\"success\":false,\"error\":{\"code\":"
                    + Json.quote(error) + ",\"message\":\"secret detail\"}}");
            }
        }

        private void handshake() throws IOException {
            String key = null;
            for (String line : headers()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            }
            String accept = wrongAccept ? "bm90IHRoZSByaWdodCBrZXk=" : accept(key);
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private List<String> headers() throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                if (b == '\n') {
                    String text = line.toString().trim();
                    if (text.isEmpty()) return lines;
                    lines.add(text);
                    line.setLength(0);
                } else {
                    line.append((char) b);
                }
            }
        }

        private String accept(String key) {
            try {
                byte[] sha = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                return Base64.getEncoder().encodeToString(sha);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Map<String, Object> read() throws IOException {
            int head = octet();
            int second = octet();
            if ((second & 128) == 0) throw new IOException("client frames must be masked");
            if ((head & 15) == 8) throw new EOFException();
            long length = second & 127;
            if (length == 126) {
                length = (octet() << 8) | octet();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | octet();
            }
            byte[] mask = {(byte) octet(), (byte) octet(), (byte) octet(), (byte) octet()};
            byte[] data = new byte[(int) length];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (octet() ^ mask[i % 4]);
            return Json.object(Json.parse(new String(data, StandardCharsets.UTF_8)));
        }

        private int octet() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException();
            return b;
        }

        private synchronized void send(String text) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if (data.length < 126) {
                frame.write(data.length);
            } else if (data.length <= 0xffff) {
                frame.write(126);
                frame.write(data.length >> 8);
                frame.write(data.length);
            } else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((long) data.length >> shift));
            }
            frame.write(data);
            frame.writeTo(out);
            out.flush();
        }
    }
}
