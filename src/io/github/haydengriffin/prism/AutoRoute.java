package io.github.haydengriffin.prism;

import java.util.HashSet;
import java.util.Set;

/**
 * Device-independent routing. A pushed target stays valid only while HA keeps talking to us: state cannot change
 * without an event, so a healthy link means the last target is still current however long ago it arrived.
 */
final class AutoRoute {

    private static final String[] SYSTEM_APPS = {
        "com.webos.app.home", "com.palm.app.settings", "com.webos.app.settings"};

    static String target(String app, String visible) {
        // Absent on older Glasshouse; empty means the TV observed no control surface.
        return visible == null ? app : visible;
    }

    static boolean systemPopup(String id) {
        return id.startsWith("com.webos.systemui");
    }

    private final long linkTimeoutMs;
    private final Set<String> apps = new HashSet<>();
    private boolean known;
    private String current = "";
    private String shield = "";
    private long heardAt;
    private long revision;
    private boolean paused;
    /** The LG's on-screen keyboard is up: it drives the LG whatever the base target says. */
    private boolean keyboard;

    /** @param linkTimeoutMs how long after the last message from HA the link still counts as healthy */
    AutoRoute(long linkTimeoutMs) {
        this.linkTimeoutMs = linkTimeoutMs;
    }

    /** Back to a new session: no catalog, no target, not paused. */
    synchronized void reset() {
        apps.clear();
        known = false;
        shield = "";
        paused = false;
        invalidate();
    }

    /** An empty catalog means the TV is off or unknown: nothing routes to the LG until one arrives. */
    synchronized void configure(Set<String> catalog, String shieldApp) {
        apps.clear();
        known = !catalog.isEmpty();
        apps.addAll(catalog);
        for (String app : SYSTEM_APPS) apps.add(app);
        shield = shieldApp;
        invalidate();
    }

    /** A target pushed by HA, no keyboard. */
    synchronized void observe(String id, long now) {
        observe(id, false, now);
    }

    /**
     * A target pushed by HA, and whether the LG keyboard is up. The keyboard alone changes no revision: it opens and
     * closes over the same app, and a press queued for that app is still wanted.
     */
    synchronized void observe(String id, boolean keyboardUp, long now) {
        keyboard = keyboardUp;
        String next = id == null ? "" : id;
        // An LG system popup ("Exit the app?") belongs to whatever had the remote: LG Home or an app keeps it, the Shield
        // keeps it. Its base app can't tell: LG Home above HDMI 3 reports HDMI 3.
        if (systemPopup(next) && !current.isEmpty()) next = current;
        if (!current.equals(next)) revision++;
        current = next;
        heardAt = now;
        if (current.equals(shield)) paused = false;
    }

    /** Any message from HA: proof the link, and so the last target, is still live. */
    synchronized void heard(long now) {
        heardAt = Math.max(heardAt, now);
    }

    synchronized boolean controlsLg(long now) {
        return !paused && known && linkHealthy(now) && (keyboard || lgApp(current));
    }

    synchronized boolean keyboard() {
        return keyboard;
    }

    synchronized boolean catalogKnown() {
        return known;
    }

    synchronized long revision() {
        return revision;
    }

    synchronized String current() {
        return current;
    }

    synchronized boolean paused() {
        return paused;
    }

    synchronized void pause() {
        paused = true;
        revision++;
    }

    /** The link dropped or went silent: nothing is known until HA pushes a full state again. */
    synchronized void invalidate() {
        current = "";
        keyboard = false;
        revision++;
    }

    private boolean linkHealthy(long now) {
        return now >= heardAt && now - heardAt <= linkTimeoutMs;
    }

    private boolean lgApp(String id) {
        if (id.equals(shield) || id.startsWith("com.webos.app.hdmi") || id.equals("com.webos.app.livetv")) return false;
        return apps.contains(id) || id.startsWith("com.webos.app.") || id.startsWith("com.palm.app.");
    }
}
