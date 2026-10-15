package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The LG's launchable apps, the favourites chosen from them, and the recently used ones. No Android types. */
final class Apps {
    static final int RECENT_LIMIT = 3;

    static final class App {
        final String id;
        final String title;
        /** Glasshouse's LAN icon URL, or null before its icon server is on. */
        final String icon;
        /** The app's own LG tile colour as #rrggbb, or null. */
        final String color;

        App(String id, String title, String icon, String color) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.color = color;
        }

        /** The first letter for the lettered fallback tile. */
        String initial() {
            String t = title.trim();
            return t.isEmpty() ? "?" : t.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    /** Apps that never get a tile: inputs, and LG Settings, which has its own. */
    static boolean launchable(String id) {
        return !id.startsWith("com.webos.app.hdmi") && !id.equals("com.webos.app.livetv")
            && !id.equals(LaunchIds.SETTINGS);
    }

    /** What the Launch App select says about the catalog. */
    enum Catalog { READY, TV_OFF, OLD_GLASSHOUSE }

    /**
     * Unavailable (or missing) is the TV off; unknown with ids is a select with no current option, unknown without
     * them is the TV off. Only a present select without app_ids means a Glasshouse too old to give them.
     */
    static Catalog catalog(String state, boolean hasIds) {
        if (state == null || state.isEmpty() || state.equals("unavailable")) return Catalog.TV_OFF;
        if (hasIds) return Catalog.READY;
        return state.equals("unknown") ? Catalog.TV_OFF : Catalog.OLD_GLASSHOUSE;
    }

    static final class LaunchIds {
        static final String SETTINGS = "com.palm.app.settings";
        private LaunchIds() {}
    }

    /** Favourites in the order chosen, kept as id → title so a removed app still shows its name. */
    static final class Favourites {
        private final LinkedHashMap<String, String> apps = new LinkedHashMap<>();

        Favourites(Map<String, String> stored) {
            for (Map.Entry<String, String> e : stored.entrySet()) if (launchable(e.getKey())) apps.put(e.getKey(), e.getValue());
        }

        boolean contains(String id) { return apps.containsKey(id); }

        /** Adds at the end or removes; returns whether it is now a favourite. */
        boolean toggle(String id, String title) {
            if (apps.remove(id) != null) return false;
            if (!launchable(id)) return false;
            apps.put(id, title);
            return true;
        }

        Map<String, String> stored() { return Collections.unmodifiableMap(apps); }

        /** Favourites as catalog apps (fresh title, icon and colour), falling back to the stored title. */
        List<App> resolve(List<App> catalog) {
            List<App> out = new ArrayList<>();
            for (Map.Entry<String, String> e : apps.entrySet()) {
                App known = find(catalog, e.getKey());
                out.add(known != null ? known : new App(e.getKey(), e.getValue(), null, null));
            }
            return out;
        }
    }

    /**
     * Recently used apps, most recent first, from the active-app history (oldest first, as Home Assistant returns
     * it; titles or ids). Favourites and anything not launchable are left out, so the row adds something new.
     */
    static List<App> recent(List<String> history, List<App> catalog, Favourites favourites) {
        return recent(history, catalog, favourites, null);
    }

    /** As above, also leaving out the app the Continue tile already offers (skip may be null). */
    static List<App> recent(List<String> history, List<App> catalog, Favourites favourites, String skip) {
        Map<String, App> seen = new LinkedHashMap<>();
        for (int i = history.size() - 1; i >= 0 && seen.size() < RECENT_LIMIT; i--) {
            App app = match(catalog, history.get(i));
            if (app == null || !launchable(app.id) || favourites.contains(app.id) || app.id.equals(skip)) continue;
            seen.putIfAbsent(app.id, app);
        }
        return new ArrayList<>(seen.values());
    }

    /** The newest launchable LG app in the history (favourites too): the Continue tile. Null when there is none. */
    static App resume(List<String> history, List<App> catalog) {
        for (int i = history.size() - 1; i >= 0; i--) {
            App app = match(catalog, history.get(i));
            if (app != null && launchable(app.id)) return app;
        }
        return null;
    }

    static App find(List<App> catalog, String id) {
        for (App app : catalog) if (app.id.equals(id)) return app;
        return null;
    }

    private static App match(List<App> catalog, String value) {
        if (value == null) return null;
        for (App app : catalog) if (app.id.equals(value) || app.title.equalsIgnoreCase(value.trim())) return app;
        return null;
    }

    /**
     * Home Assistant's history of the TV's active app over the last `days`. Without end_time HA answers one day from
     * the start, which for a fortnight back is already purged: the list came back empty.
     */
    static String historyPath(String entity, long nowMs, int days) {
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String start = iso.format(new java.util.Date(nowMs - days * 86_400_000L));
        String end = iso.format(new java.util.Date(nowMs));
        return "history/period/" + start + "?end_time=" + end + "&filter_entity_id=" + entity
            + "&minimal_response&no_attributes";
    }

    private Apps() {}
}
