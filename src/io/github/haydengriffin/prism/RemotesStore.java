package io.github.haydengriffin.prism;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The one Remotes list for the process, shared by the service (which records devices) and Setup (which changes
 * the switches), saved in preferences whenever it changes.
 */
final class RemotesStore {
    private static final String PREFS = "remotes";
    private static Remotes shared;

    private RemotesStore() {}

    static synchronized Remotes get(Context c) {
        if (shared == null) shared = Remotes.fromJson(prefs(c).getString("devices", ""));
        return shared;
    }

    static void save(Context c) {
        prefs(c).edit().putString("devices", get(c).toJson()).apply();
    }

    /** Records the sender of a fresh key press; saves only when the list changed. */
    static void seen(Context c, KeyEvent event) {
        InputDevice d = event.getDevice();
        String name = d == null ? null : d.getName();
        if (get(c).seen(id(d), name, kind(event, d))) save(c);
    }

    /** Whether this press's device is allowed to control the LG. */
    static boolean controls(Context c, KeyEvent event) {
        InputDevice d = event.getDevice();
        return get(c).controls(id(d), kind(event, d));
    }

    static void choose(Context c, String id, boolean on) {
        get(c).choose(id, on);
        save(c);
    }

    private static String id(InputDevice d) {
        return d == null ? Remotes.id(null, null) : Remotes.id(d.getDescriptor(), d.getName());
    }

    private static RemotePolicy.Kind kind(KeyEvent event, InputDevice d) {
        return RemotePolicy.kind(d == null ? null : d.getName(), event.getDeviceId(), d == null || d.isVirtual());
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
