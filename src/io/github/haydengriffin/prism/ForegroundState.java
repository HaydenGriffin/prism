package io.github.haydengriffin.prism;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mirror of the two TV entities the link subscribes to, rebuilt from HA's compressed subscribe_entities events:
 * the active-app sensor gives the route target, the launch select's app_ids give the app catalog.
 */
final class ForegroundState {

    /** One entity as HA last described it in full; state null until added (or after removal). */
    private static final class Mirror {
        final Map<String, Object> attributes = new HashMap<>();
        String state;

        void apply(String id, Map<String, Object> event) {
            Map<String, Object> added = Json.object(Json.object(event.get("a")).get(id));
            if (!added.isEmpty()) add(added);
            Map<String, Object> changed = Json.object(Json.object(event.get("c")).get(id));
            if (!changed.isEmpty()) change(changed);
            if (Json.array(event.get("r")).contains(id)) clear();
        }

        void clear() {
            state = null;
            attributes.clear();
        }

        boolean unavailable() {
            return state == null || state.equals("unavailable");
        }

        private void add(Map<String, Object> full) {
            state = Json.text(full.get("s"));
            attributes.clear();
            attributes.putAll(Json.object(full.get("a")));
        }

        private void change(Map<String, Object> diff) {
            // A diff for an entity we never saw in full would leave attributes half-known; wait for a full add.
            if (state == null) return;
            Map<String, Object> plus = Json.object(diff.get("+"));
            if (plus.containsKey("s")) state = Json.text(plus.get("s"));
            attributes.putAll(Json.object(plus.get("a")));
            for (Object key : Json.array(Json.object(diff.get("-")).get("a"))) attributes.remove(key);
        }
    }

    private final String activeId;
    private final String launchId;
    private final Mirror active = new Mirror();
    private final Mirror launch = new Mirror();

    ForegroundState(String activeId, String launchId) {
        this.activeId = activeId;
        this.launchId = launchId;
    }

    void apply(Map<String, Object> event) {
        active.apply(activeId, event);
        launch.apply(launchId, event);
    }

    void clear() {
        active.clear();
        launch.clear();
    }

    /**
     * Whether an LG panel (search, LG Home, Settings, a popup) is in front while the TV's input underneath is already
     * `input`: then selecting that input changes nothing, and only the LG's Back takes the panel away.
     */
    boolean overlayOn(String input) {
        if (!on() || input == null || input.isEmpty()) return false;
        String base = Json.text(active.attributes.get("app_id"));
        String front = target();
        return base.equals(input) && !front.isEmpty() && !front.equals(input);
    }

    /** Empty when the TV is off or unknown; otherwise the visible control surface, as AutoRoute expects. */
    String target() {
        if (!on()) return "";
        Map<String, Object> a = active.attributes;
        String control = a.containsKey("control_app_id") ? Json.text(a.get("control_app_id")) : null;
        return AutoRoute.target(Json.text(a.get("app_id")), control);
    }

    /**
     * Glasshouse's keyboard_visible: the LG's on-screen keyboard is up. The keyboard has no window of its own for
     * control_app_id, so it is a separate flag. Only a real boolean counts; a TV that is off has none.
     */
    boolean keyboard() {
        return on() && Boolean.TRUE.equals(active.attributes.get("keyboard_visible"));
    }

    private boolean on() {
        String state = active.state;
        return state != null && !state.equals("unavailable") && !state.equals("unknown") && !state.equalsIgnoreCase("off");
    }

    /** Catalog title to app id from the launch select; empty while the TV is off, unknown or the ids are missing. */
    Map<String, String> catalog() {
        if (launch.unavailable()) return Collections.emptyMap();
        Map<String, Object> ids = Json.object(launch.attributes.get("app_ids"));
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Object> app : ids.entrySet()) result.put(app.getKey(), Json.text(app.getValue()));
        return result;
    }
}
