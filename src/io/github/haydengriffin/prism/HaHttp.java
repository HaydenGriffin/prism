package io.github.haydengriffin.prism;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Bounded REST requests for setup and one-off actions. Commands are attempted once; redirects are refused. */
public final class HaHttp {

    static final class HttpFailure extends IOException {
        final int status;

        HttpFailure(int status) {
            super(status == 401 || status == 403
                ? "Home Assistant refused access. Check the access token."
                : "Home Assistant returned HTTP " + status + ".");
            this.status = status;
        }
    }

    private static final int COMMAND_TIMEOUT_MS = 1800;
    private static final int BROWSE_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    final String base;
    private final String token;

    public HaHttp(String base, String token) {
        this.base = normalizeBase(base);
        if (token == null || token.trim().isEmpty() || token.contains("\n") || token.contains("\r")) {
            throw new IllegalArgumentException("Enter a Home Assistant access token.");
        }
        this.token = token.trim();
    }

    public static String normalizeBase(String raw) {
        try {
            URI uri = new URI(raw.trim());
            if (!originOnly(uri)) throw new IllegalArgumentException();
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter the Home Assistant address without /api or a dashboard path.");
        }
    }

    String browse(String path) throws IOException {
        return request(path, null, BROWSE_TIMEOUT_MS);
    }

    void press(String entity) throws IOException {
        request("services/button/press", "{\"entity_id\":" + Json.quote(entity) + "}", COMMAND_TIMEOUT_MS);
    }

    void select(String entity, String option) throws IOException {
        String body = "{\"entity_id\":" + Json.quote(entity) + ",\"option\":" + Json.quote(option) + "}";
        request("services/select/select_option", body, COMMAND_TIMEOUT_MS);
    }

    /** select.* takes an option; number.* takes an integer. Any other domain is refused before any request. */
    void setValue(String entity, String value) throws IOException {
        if (entity.startsWith("select.")) {
            select(entity, value);
        } else if (entity.startsWith("number.")) {
            String body = "{\"entity_id\":" + Json.quote(entity) + ",\"value\":" + integer(value) + "}";
            request("services/number/set_value", body, COMMAND_TIMEOUT_MS);
        } else {
            throw new IllegalArgumentException("Only select and number settings can be changed.");
        }
    }

    private static long integer(String value) {
        double number;
        try {
            number = Double.parseDouble(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            number = Double.NaN;
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalArgumentException("Setting value must be a number.");
        }
        return Math.round(number);
    }

    private static boolean originOnly(URI uri) {
        boolean web = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
        boolean bare = uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        boolean noPath = uri.getPath().isEmpty() || uri.getPath().equals("/");
        return web && uri.getHost() != null && bare && noPath;
    }

    private String request(String path, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/" + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        // Shield Android 11 stalled on reused HA connections; fresh sockets recover.
        c.setRequestProperty("Connection", "close");
        try {
            if (body != null) post(c, body);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpFailure(code);
            try (InputStream in = c.getInputStream()) {
                return readBounded(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void post(HttpURLConnection c, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_RESPONSE) throw new IOException("Home Assistant response is too large.");
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
