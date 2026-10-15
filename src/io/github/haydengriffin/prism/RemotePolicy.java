package io.github.haydengriffin.prism;

import java.util.Locale;

/** Navigation keys only. Android keeps power, volume, Home and voice; Home is only observed. */
public final class RemotePolicy {

    private RemotePolicy() {}

    /** Android KeyEvent codes as literals: this class also runs on the host JVM. */
    public static String key(int code) {
        switch (code) {
            case 19:
                return "up";
            case 20:
                return "down";
            case 21:
                return "left";
            case 22:
                return "right";
            case 23:
            case 66:
                return "ok";
            case 4:
                return "back";
            case 85:
                return "play_pause";
            case 126:
                return "play";
            case 127:
                return "pause";
            case 90:
                return "fast_forward";
            case 89:
                return "rewind";
            default:
                return null;
        }
    }

    /**
     * With the LG keyboard up, a keyboard's Delete (67) and keypad Enter (160) go to its own keys. Enter (66) stays
     * OK, which presses the focused key, as the D-pad centre does.
     */
    public static String key(int code, boolean keyboard) {
        if (keyboard && code == 67) return "keyboard_backspace";
        if (keyboard && code == 160) return "keyboard_enter";
        return key(code);
    }

    /** Keys watched but never forwarded or consumed: Home (3) still takes the Shield home. */
    public static boolean observeOnly(int code) {
        return code == 3;
    }

    /** A fresh Home press from a remote that controls the LG, while it does, also hands the TV back to the box. */
    public static boolean homeLeavesLg(int code, boolean down, int repeat, boolean fromRemote, boolean controlsLg) {
        return code == 3 && down && repeat == 0 && fromRemote && controlsLg;
    }

    /** What sent a key: a remote in the hand, software (phone remote apps, adb), or the TV's own remote over HDMI-CEC. */
    public enum Kind { PHYSICAL, VIRTUAL, CEC }

    public static Kind kind(String name, int deviceId, boolean virtual) {
        if (cecDevice(name)) return Kind.CEC;
        if (virtual || deviceId < 0 || name == null || name.toLowerCase(Locale.ROOT).contains("virtual")) return Kind.VIRTUAL;
        return Kind.PHYSICAL;
    }

    /**
     * Until the user chooses: physical remotes control the LG; CEC never does, because its presses come from the TV
     * and would loop back to it; virtual devices are off, and may be turned on for a phone remote app.
     */
    public static boolean controlsByDefault(Kind kind) {
        return kind == Kind.PHYSICAL;
    }

    static boolean cecDevice(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("cec");
    }
}
