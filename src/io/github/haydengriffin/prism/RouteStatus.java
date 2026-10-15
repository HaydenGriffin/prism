package io.github.haydengriffin.prism;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/** Turns routing and link failures into the RemoteState the setup screen shows. Never includes HA's own text. */
final class RouteStatus {

    private static final Map<String, String> SYSTEM_TITLES = new HashMap<>();

    static {
        SYSTEM_TITLES.put("com.webos.app.home", "LG Home");
        SYSTEM_TITLES.put("com.palm.app.settings", "LG Settings");
        SYSTEM_TITLES.put("com.webos.app.settings", "LG Settings");
        SYSTEM_TITLES.put("com.webos.app.quicksettings", "Quick Settings");
        SYSTEM_TITLES.put("com.webos.app.tvuserguide", "User Guide");
        SYSTEM_TITLES.put("com.webos.systemui.notification", "LG message");
    }

    private RouteStatus() {}

    /** @param titles app id to catalog title */
    static RemoteState route(String target, boolean paused, boolean controlsLg, boolean catalogKnown, String shieldApp,
                             Map<String, String> titles) {
        return route(target, paused, controlsLg, catalogKnown, shieldApp, titles, false);
    }

    /** @param keyboard the LG keyboard is up: on the LG it is what the remote drives, so it names the state */
    static RemoteState route(String target, boolean paused, boolean controlsLg, boolean catalogKnown, String shieldApp,
                             Map<String, String> titles, boolean keyboard) {
        if (paused) return state(RemoteState.Mode.PAUSED, "");
        if (controlsLg) return state(RemoteState.Mode.LG, keyboard ? "LG keyboard" : title(target, titles));
        if (target.equals(shieldApp)) return state(RemoteState.Mode.SHIELD, "");
        if (target.isEmpty() || !catalogKnown) return state(RemoteState.Mode.TV_OFF, "");
        return state(RemoteState.Mode.OTHER_INPUT, "");
    }

    static String title(String id, Map<String, String> titles) {
        if (SYSTEM_TITLES.containsKey(id)) return SYSTEM_TITLES.get(id);
        String title = titles.get(id);
        return title == null || title.isEmpty() ? id : title;
    }

    /** A live connection that dropped shows CONNECTING while it retries; a failed attempt shows why. */
    static RemoteState failure(HaLink.Step step, Exception cause) {
        if (cause instanceof HaSocket.AuthRefused) return error("Home Assistant refused the token");
        switch (step) {
            case DROPPED:
            case SILENT:
                return state(RemoteState.Mode.CONNECTING, "");
            case SUBSCRIBE:
                return error(cause instanceof HaSocket.Refused
                    ? "Home Assistant refused to share the TV state"
                    : "Home Assistant did not share the TV state");
            default:
                return error(unreachable(cause)
                    ? "Cannot reach Home Assistant"
                    : "Could not connect to Home Assistant");
        }
    }

    private static boolean unreachable(Exception cause) {
        return cause instanceof ConnectException || cause instanceof SocketTimeoutException
            || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException;
    }

    private static RemoteState error(String detail) {
        return state(RemoteState.Mode.ERROR, detail);
    }

    private static RemoteState state(RemoteState.Mode mode, String detail) {
        return new RemoteState(mode, detail);
    }
}
