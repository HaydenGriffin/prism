package io.github.haydengriffin.prism;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RFC 6455 text client: verified TLS, masked writes, bounded frames and messages. */
final class WebSocket implements Closeable {

    static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final int MAX_HEADER = 16384;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0;
    private static final int OP_TEXT = 1;
    private static final int OP_CLOSE = 8;
    private static final int OP_PING = 9;
    private static final int OP_PONG = 10;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    private WebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Opens origin + path (for example http://ha:8123 and /api/websocket); timeoutMs bounds every step. */
    static WebSocket connect(String origin, String path, int timeoutMs) throws IOException {
        URI uri = parse(origin);
        Socket socket = open(uri, timeoutMs);
        try {
            WebSocket ws = new WebSocket(socket);
            ws.upgrade(uri.getRawAuthority(), path);
            return ws;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** 0 waits forever; the owner then decides liveness and closes the socket. */
    void readTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    void sendText(String text) throws IOException {
        frame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Next complete text message. Answers pings; a close frame or end of stream throws EOFException. */
    String readText() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int head = octet();
            int opcode = head & 15;
            boolean fin = (head & 128) != 0;
            if ((head & 0x70) != 0) throw invalid();
            byte[] data = payload(opcode, message.size());
            if (opcode == OP_CLOSE) throw closed();
            if (opcode == OP_PING) {
                frame(OP_PONG, data);
                continue;
            }
            if (opcode == OP_PONG) continue;
            if (opcode != OP_TEXT && opcode != OP_CONTINUATION) throw invalid();
            message.write(data);
            if (fin) return new String(message.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static URI parse(String origin) throws IOException {
        try {
            URI uri = new URI(origin);
            if (uri.getHost() == null) throw new IOException("Home Assistant address has no host.");
            return uri;
        } catch (URISyntaxException e) {
            throw new IOException("Home Assistant address is invalid.");
        }
    }

    private static Socket open(URI uri, int timeoutMs) throws IOException {
        boolean tls = "https".equals(uri.getScheme());
        int port = uri.getPort() < 0 ? (tls ? 443 : 80) : uri.getPort();
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(uri.getHost(), port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            raw.setTcpNoDelay(true);
            return tls ? secure(raw, uri.getHost(), port) : raw;
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private static Socket secure(Socket raw, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }

    private void upgrade(String authority, String path) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + authority
            + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
            + key + "\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String headers = readHeaders().toLowerCase(Locale.ROOT);
        String accept = "sec-websocket-accept: " + accept(key).toLowerCase(Locale.ROOT);
        if (!headers.startsWith("http/1.1 101 ") || !headers.contains(accept)) {
            throw new IOException("Home Assistant refused the WebSocket connection.");
        }
    }

    static String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readHeaders() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int last4 = 0;
        while (header.size() < MAX_HEADER) {
            int b = octet();
            header.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0d0a0d0a) return new String(header.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new IOException("Home Assistant WebSocket headers are too large.");
    }

    private byte[] payload(int opcode, int buffered) throws IOException {
        int second = octet();
        if ((second & 128) != 0) throw invalid(); // Servers must not mask.
        long length = length(second & 127);
        boolean control = opcode >= OP_CLOSE;
        if (control && length > 125) throw invalid();
        if (length > MAX_MESSAGE || buffered + length > MAX_MESSAGE) throw invalid();
        byte[] data = new byte[(int) length];
        readFully(data);
        return data;
    }

    private long length(int code) throws IOException {
        if (code == 126) return (octet() << 8) | octet();
        if (code < 126) return code;
        long length = 0;
        for (int i = 0; i < 8; i++) length = (length << 8) | octet();
        if (length < 0) throw invalid();
        return length;
    }

    private void readFully(byte[] data) throws IOException {
        int at = 0;
        while (at < data.length) {
            int got = in.read(data, at, data.length - at);
            if (got < 0) throw new EOFException();
            at += got;
        }
    }

    private int octet() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private synchronized void frame(int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 14);
        frame.write(128 | opcode);
        writeLength(frame, data.length);
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < data.length; i++) frame.write(data[i] ^ mask[i % 4]);
        frame.writeTo(out);
        out.flush();
    }

    private static void writeLength(ByteArrayOutputStream frame, int length) {
        if (length < 126) {
            frame.write(128 | length);
        } else if (length <= 0xffff) {
            frame.write(128 | 126);
            frame.write(length >> 8);
            frame.write(length);
        } else {
            frame.write(128 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((long) length >> shift));
        }
    }

    private IOException closed() {
        try {
            frame(OP_CLOSE, new byte[0]);
        } catch (IOException ignored) {
            // The peer is already going away.
        }
        return new EOFException("Home Assistant closed the WebSocket.");
    }

    private static IOException invalid() {
        return new IOException("Invalid Home Assistant WebSocket frame.");
    }
}
