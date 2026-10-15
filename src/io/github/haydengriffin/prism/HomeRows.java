package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the two home-screen rows (Android TV preview channels) should hold, and how to get there from what the TV
 * provider has now. No Android types: ChannelSync does the provider work.
 */
final class HomeRows {
    static final String APPS = "lg_apps";
    static final String COMMANDS = "lg_commands";
    static final String CONTINUE = "continue";

    /** The card's picture: an LG app on its tile colour, or an MDI glyph on the night ground. */
    enum Glyph { APP, HOME, SETTINGS, TUNE, INPUT }

    /** One program (card) in a row. The key is its INTERNAL_PROVIDER_ID; the target is a ShortcutActivity target. */
    static final class Item {
        final String key;
        final String title;
        final String target;
        final Glyph glyph;
        /** The LG app for APP cards, else null. */
        final Apps.App app;
        /** The "Continue" card: the same app card with a Continue label. */
        final boolean resume;

        Item(String key, String title, String target, Glyph glyph, Apps.App app, boolean resume) {
            this.key = key;
            this.title = title;
            this.target = target;
            this.glyph = glyph;
            this.app = app;
            this.resume = resume;
        }

        @Override public String toString() { return key; }
    }

    /**
     * "LG apps": the last LG app first ("Continue on the LG · …"), then the favourites in their saved order. A
     * favourite the TV no longer lists keeps its stored title (lettered card).
     */
    static List<Item> apps(String lastAppId, List<Apps.App> catalog, Map<String, String> storedFavourites) {
        List<Item> out = new ArrayList<>();
        Apps.App last = lastAppId == null ? null : Apps.find(catalog, lastAppId);
        if (last != null && Apps.launchable(last.id)) {
            out.add(new Item(CONTINUE, "Continue on the LG · " + last.title, "app:" + last.id, Glyph.APP, last, true));
        }
        for (Apps.App app : new Apps.Favourites(storedFavourites).resolve(catalog)) {
            out.add(new Item("app:" + app.id, app.title, "app:" + app.id, Glyph.APP, app, false));
        }
        return out;
    }

    /** "LG commands": LG Home (when its button is on), LG Settings, Quick TV settings, then every other input. */
    static List<Item> commands(boolean hasHome, List<String> inputOptions, String shieldOption) {
        List<Item> out = new ArrayList<>();
        if (hasHome) out.add(new Item("cmd:home", "LG Home", "lg_home", Glyph.HOME, null, false));
        out.add(new Item("cmd:settings", "LG Settings", "lg_settings", Glyph.SETTINGS, null, false));
        out.add(new Item("cmd:quick", "Quick TV settings", "quick_settings", Glyph.TUNE, null, false));
        for (String option : inputs(inputOptions, shieldOption)) {
            out.add(new Item("input:" + option, "Switch to " + option, "input:" + option, Glyph.INPUT, null, false));
        }
        return out;
    }

    /** The input select's options without the Shield's own input, blanks or repeats, in the select's order. */
    static List<String> inputs(List<String> options, String shieldOption) {
        String shield = shieldOption == null ? "" : shieldOption.trim().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : options) {
            String option = raw == null ? "" : raw.trim();
            String folded = option.toLowerCase(Locale.ROOT);
            if (option.isEmpty() || folded.equals(shield) || folded.equals("unavailable") || folded.equals("unknown")) continue;
            if (seen.add(folded)) out.add(option);
        }
        return out;
    }

    /**
     * The last LG app the TV showed: the one the remote service saw in front when the catalog still lists it, else
     * the newest launchable app in HA's active-app history (titles or ids), else null.
     */
    static String lastApp(String observed, List<String> history, List<Apps.App> catalog) {
        if (observed != null && Apps.find(catalog, observed) != null && Apps.launchable(observed)) return observed;
        List<Apps.App> recent = Apps.recent(history, catalog, new Apps.Favourites(Collections.<String, String>emptyMap()));
        return recent.isEmpty() ? null : recent.get(0).id;
    }

    /** Whether a route target the service saw is an LG app worth a "Continue" card (not an input, menu or popup). */
    static boolean lgApp(String target, String shieldApp) {
        if (target == null || target.isEmpty() || target.equals(shieldApp)) return false;
        if (AutoRoute.systemPopup(target) || target.equals("com.webos.app.home") || target.equals("com.webos.app.settings")) return false;
        return Apps.launchable(target);
    }

    /** A row the provider holds now: its row id, key (INTERNAL_PROVIDER_ID) and the fields that matter, flattened. */
    static final class Row {
        final long id;
        final String key;
        final String signature;

        Row(long id, String key, String signature) {
            this.id = id;
            this.key = key;
            this.signature = signature;
        }
    }

    /** Provider writes that turn the current rows into the wanted ones. */
    static final class Changes {
        /** Keys to insert, in the wanted order. */
        final List<String> insert = new ArrayList<>();
        /** Row id → key whose fields changed. */
        final Map<Long, String> update = new LinkedHashMap<>();
        final List<Long> delete = new ArrayList<>();

        boolean empty() { return insert.isEmpty() && update.isEmpty() && delete.isEmpty(); }

        @Override public String toString() { return "+" + insert.size() + " ~" + update.size() + " -" + delete.size(); }
    }

    /**
     * Idempotent diff by key. Rows without a key, repeats of a key and keys no longer wanted are deleted; changed
     * rows updated; new keys inserted. When ordered and keeping rows would leave them out of the wanted order
     * (launchers may order by row id), everything is deleted and inserted again. Channels are not ordered: a
     * re-made channel would lose what the user set for it in the launcher.
     *
     * @param wanted key → signature, in the wanted order
     */
    static Changes diff(List<Row> current, Map<String, String> wanted, boolean ordered) {
        Changes out = new Changes();
        List<Row> byId = new ArrayList<>(current);
        byId.sort((a, b) -> Long.compare(a.id, b.id));
        Map<String, Row> kept = new LinkedHashMap<>();
        for (Row row : byId) {
            if (row.key == null || !wanted.containsKey(row.key) || kept.containsKey(row.key)) out.delete.add(row.id);
            else kept.put(row.key, row);
        }
        List<String> order = new ArrayList<>(kept.keySet());
        for (String key : wanted.keySet()) if (!kept.containsKey(key)) order.add(key);
        if (ordered && !order.equals(new ArrayList<>(wanted.keySet()))) {
            for (Row row : kept.values()) out.delete.add(row.id);
            out.insert.addAll(wanted.keySet());
            return out;
        }
        for (Row row : kept.values()) if (!row.signature.equals(wanted.get(row.key))) out.update.put(row.id, row.key);
        for (String key : wanted.keySet()) if (!kept.containsKey(key)) out.insert.add(key);
        return out;
    }

    /**
     * The service's trigger: a sync when a different LG app comes to the front, at most one per gap, never queued
     * twice (the pending one reads the newest app when it runs). Thread-safe.
     */
    static final class Throttle {
        private final long gapMs;
        private String seen;
        private boolean ran;
        private long lastRun;
        private boolean pending;

        Throttle(long gapMs) { this.gapMs = gapMs; }

        /** Returns the delay before a sync should run, or -1 when this observation needs none. */
        synchronized long observe(String app, long now) {
            if (app == null || app.equals(seen)) return -1;
            seen = app;
            if (pending) return -1;
            pending = true;
            return ran ? Math.max(0, lastRun + gapMs - now) : 0;
        }

        /** The pending sync is running now. */
        synchronized void running(long now) {
            pending = false;
            ran = true;
            lastRun = now;
        }

        synchronized String seen() { return seen; }
    }

    private HomeRows() {}
}
