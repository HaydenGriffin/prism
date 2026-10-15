package io.github.haydengriffin.prism;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** A selected HA device, with registry-resolved entities; no hard-coded IDs or guessed entity names. */
final class TvProfile {

    private static final int REGISTRY_TIMEOUT_MS = 10000;
    final String name;
    final String device;
    final String active;
    final String launch;
    final String input;
    String shieldOption = "";
    String shieldApp = "";
    /** Glasshouse's screen_notification text entity, or empty (it is often disabled in HA). */
    String notice = "";
    /** Glasshouse's keyboard_text entity, or empty (older Glasshouse). */
    String keyboard = "";
    final Map<String, String> buttons = new LinkedHashMap<>();
    final Map<String, String> settings = new LinkedHashMap<>();

    TvProfile(String name, String device, String active, String launch, String input) {
        this.name = name;
        this.device = device;
        this.active = active;
        this.launch = launch;
        this.input = input;
    }

    JSONObject json() throws JSONException {
        return new JSONObject()
            .put("name", name)
            .put("device", device)
            .put("active", active)
            .put("launch", launch)
            .put("input", input)
            .put("shieldOption", shieldOption)
            .put("shieldApp", shieldApp)
            .put("notice", notice)
            .put("keyboard", keyboard)
            .put("buttons", new JSONObject(buttons))
            .put("settings", new JSONObject(settings));
    }

    void save(Context c) throws Exception {
        boolean saved = c.getSharedPreferences("tv", 0).edit().putString("profile", json().toString()).commit();
        if (!saved) throw new IOException("Could not save TV.");
    }

    static TvProfile load(Context c) {
        try {
            return parse(new JSONObject(c.getSharedPreferences("tv", 0).getString("profile", "")));
        } catch (Exception e) {
            return null;
        }
    }

    static TvProfile parse(JSONObject j) throws JSONException {
        TvProfile p = new TvProfile(
            j.getString("name"), j.getString("device"), j.getString("active"), j.getString("launch"),
            j.getString("input"));
        p.shieldOption = j.optString("shieldOption");
        p.shieldApp = j.optString("shieldApp");
        p.notice = j.optString("notice");
        p.keyboard = j.optString("keyboard");
        copy(j.getJSONObject("buttons"), p.buttons);
        JSONObject settings = j.optJSONObject("settings");
        if (settings != null) copy(settings, p.settings);
        return p;
    }

    static List<TvProfile> discover(Credentials credentials) throws Exception {
        List<TvProfile> result = new ArrayList<>();
        for (TvRegistry.Candidate candidate : candidates(credentials)) {
            Map<String, String> roles = TvRoles.resolve(candidate);
            if (roles != null) result.add(fromFlat(roles));
        }
        return result;
    }

    /**
     * This TV re-read from the registries, Shield input kept: null when unchanged, gone or no longer usable.
     * Throws when HA cannot be read.
     */
    TvProfile refreshed(Credentials credentials) throws Exception {
        for (TvRegistry.Candidate candidate : candidates(credentials)) {
            if (!candidate.device.equals(device)) continue;
            Map<String, String> next = TvRoles.refresh(flat(), candidate);
            if (next != null && !next.equals(flat())) return fromFlat(next);
        }
        return null;
    }

    /** The profile as TvRoles' flat strings. */
    Map<String, String> flat() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(TvRoles.NAME, name);
        p.put(TvRoles.DEVICE, device);
        p.put(TvRoles.ACTIVE, active);
        p.put(TvRoles.LAUNCH, launch);
        p.put(TvRoles.INPUT, input);
        p.put(TvRoles.NOTICE, notice);
        p.put(TvRoles.KEYBOARD, keyboard);
        p.put(TvRoles.SHIELD_OPTION, shieldOption);
        p.put(TvRoles.SHIELD_APP, shieldApp);
        for (Map.Entry<String, String> b : buttons.entrySet()) p.put(TvRoles.BUTTON + b.getKey(), b.getValue());
        for (Map.Entry<String, String> e : settings.entrySet()) p.put(TvRoles.SETTING + e.getKey(), e.getValue());
        return p;
    }

    static TvProfile fromFlat(Map<String, String> f) {
        TvProfile p = new TvProfile(f.get(TvRoles.NAME), f.get(TvRoles.DEVICE), f.get(TvRoles.ACTIVE),
            f.get(TvRoles.LAUNCH), f.get(TvRoles.INPUT));
        p.notice = text(f.get(TvRoles.NOTICE));
        p.keyboard = text(f.get(TvRoles.KEYBOARD));
        p.shieldOption = text(f.get(TvRoles.SHIELD_OPTION));
        p.shieldApp = text(f.get(TvRoles.SHIELD_APP));
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (e.getKey().startsWith(TvRoles.BUTTON)) p.buttons.put(e.getKey().substring(TvRoles.BUTTON.length()), e.getValue());
            if (e.getKey().startsWith(TvRoles.SETTING)) p.settings.put(e.getKey().substring(TvRoles.SETTING.length()), e.getValue());
        }
        return p;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<TvRegistry.Candidate> candidates(Credentials credentials) throws Exception {
        List<?> entities;
        List<?> devices;
        try (HaSocket socket = HaSocket.open(credentials.url, credentials.token, REGISTRY_TIMEOUT_MS, IGNORE)) {
            entities = registry(socket, "config/entity_registry/list");
            devices = registry(socket, "config/device_registry/list");
        }
        return TvRegistry.candidates(entities, devices);
    }

    private static List<?> registry(HaSocket socket, String type) throws IOException {
        try {
            return Json.array(socket.request("\"type\":" + Json.quote(type), REGISTRY_TIMEOUT_MS));
        } catch (HaSocket.Refused e) {
            throw new IOException("Home Assistant could not list TV devices. Check this user's permissions.");
        }
    }

    private static void copy(JSONObject from, Map<String, String> to) throws JSONException {
        for (Iterator<String> it = from.keys(); it.hasNext(); ) {
            String key = it.next();
            to.put(key, from.getString(key));
        }
    }

    private static final HaSocket.Listener IGNORE = new HaSocket.Listener() {
        @Override
        public void received(HaSocket socket, Map<String, Object> message) {}

        @Override
        public void closed(HaSocket socket, Exception cause) {}
    };
}
