package io.github.haydengriffin.prism;

import java.util.Locale;

/**
 * Optional monitoring: when to tell HA the remote is alive, by setting an input_text the user chose. At once on
 * connect and on every mode change, then every 60 s while the link is healthy, so an automation in HA can alert
 * when the value goes stale while the box is awake.
 */
final class Heartbeat {

    static final long EVERY_MS = 60_000;
    private static final int MAX_LENGTH = 100;

    private final String version;
    private String sent;
    private long sentAt;
    private boolean failureLogged;

    Heartbeat(String version) {
        this.version = version;
    }

    /** The entity id as saved (trimmed, lower case), or null unless it is an input_text entity id. */
    static String entity(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.matches("input_text\\.[a-z0-9_]+") ? id : null;
    }

    static String value(RemoteState.Mode mode, String version) {
        String value = mode.name().toLowerCase(Locale.ROOT) + " · " + version;
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    /**
     * Call only while the link is healthy. Decides only: nothing counts as sent until succeeded(), so a failed write
     * is retried on the next tick. @return the value to send now, or null
     */
    synchronized String due(RemoteState.Mode mode, long now) {
        String value = value(mode, version);
        return value.equals(sent) && now - sentAt < EVERY_MS ? null : value;
    }

    /** The link is down or replaced: the next healthy check sends at once. */
    synchronized void reset() {
        sent = null;
    }

    /** @return true for the first failure since the last success, so it is logged once */
    synchronized boolean firstFailure() {
        boolean first = !failureLogged;
        failureLogged = true;
        return first;
    }

    /** HA accepted value at now: the next one is due on a mode change or 60 s later. */
    synchronized void succeeded(String value, long now) {
        sent = value;
        sentAt = now;
        failureLogged = false;
    }
}
