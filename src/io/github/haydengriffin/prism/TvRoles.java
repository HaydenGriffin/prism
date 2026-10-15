package io.github.haydengriffin.prism;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TV profile as flat strings ("active", "button.up", "setting.picture_mode", ...), resolved from a registry
 * candidate, and the refresh rule: roles follow the registry, the Shield input stays as chosen. No Android types.
 */
final class TvRoles {

    static final String NAME = "name";
    static final String DEVICE = "device";
    static final String ACTIVE = "active";
    static final String LAUNCH = "launch";
    static final String INPUT = "input";
    static final String NOTICE = "notice";
    /** Glasshouse's keyboard_text entity (set_value types into the focused LG field), or empty. */
    static final String KEYBOARD = "keyboard";
    static final String SHIELD_OPTION = "shieldOption";
    static final String SHIELD_APP = "shieldApp";
    static final String BUTTON = "button.";
    static final String SETTING = "setting.";

    private static final List<String> REQUIRED_BUTTONS = Arrays.asList("up", "down", "left", "right", "ok", "back");
    private static final String[] REMOTE_BUTTONS = {"up", "down", "left", "right", "ok", "back", "home"};
    private static final String[] MEDIA_BUTTONS = {"play", "pause", "play_pause", "rewind", "fast_forward"};
    private static final String[] KEYBOARD_BUTTONS = {"keyboard_enter", "keyboard_backspace"};
    /** Quick TV settings: role (= unique_id suffix) and the domain it must have. */
    private static final String[][] SETTINGS = {
        {"input_source", "select"},
        {"picture_mode", "select"},
        {"oled_light", "number"},
        {"energy_saving", "select"},
        {"sound_mode", "select"},
        {"sleep_timer", "select"},
        {"oled_logo_dimming", "select"},
    };

    private TvRoles() {}

    /** The candidate's roles, Shield input not yet chosen; null if it lacks the active app, input or arrows. */
    static Map<String, String> resolve(TvRegistry.Candidate c) {
        String active = c.role("active_app", "sensor");
        String input = c.role("input_source", "select");
        if (active == null || input == null) return null;
        Map<String, String> p = new LinkedHashMap<>();
        p.put(NAME, c.name);
        p.put(DEVICE, c.device);
        p.put(ACTIVE, active);
        p.put(LAUNCH, c.launch);
        p.put(INPUT, input);
        String notice = c.role("screen_notification", "text");
        p.put(NOTICE, notice == null ? "" : notice);
        String keyboard = c.role("keyboard_text", "text");
        p.put(KEYBOARD, keyboard == null ? "" : keyboard);
        p.put(SHIELD_OPTION, "");
        p.put(SHIELD_APP, "");
        for (String key : REMOTE_BUTTONS) putIfFound(p, BUTTON + key, c.role("remote_" + key, "button"));
        for (String key : MEDIA_BUTTONS) putIfFound(p, BUTTON + key, c.role(key, "button"));
        for (String key : KEYBOARD_BUTTONS) putIfFound(p, BUTTON + key, c.role(key, "button"));
        for (String[] setting : SETTINGS) putIfFound(p, SETTING + setting[0], c.role(setting[0], setting[1]));
        for (String key : REQUIRED_BUTTONS) if (!p.containsKey(BUTTON + key)) return null;
        return p;
    }

    /** The saved profile with the registry's current roles; the Shield input kept. Null if the TV is now unusable. */
    static Map<String, String> refresh(Map<String, String> saved, TvRegistry.Candidate c) {
        Map<String, String> next = resolve(c);
        if (next == null) return null;
        next.put(SHIELD_OPTION, value(saved, SHIELD_OPTION));
        next.put(SHIELD_APP, value(saved, SHIELD_APP));
        return next;
    }

    /** The subscription must follow: the active-app sensor or launch select is a different entity now. */
    static boolean subscriptionChanged(Map<String, String> saved, Map<String, String> next) {
        return !value(saved, ACTIVE).equals(value(next, ACTIVE)) || !value(saved, LAUNCH).equals(value(next, LAUNCH));
    }

    private static String value(Map<String, String> p, String key) {
        String v = p.get(key);
        return v == null ? "" : v;
    }

    private static void putIfFound(Map<String, String> map, String key, String entity) {
        if (entity != null) map.put(key, entity);
    }
}
