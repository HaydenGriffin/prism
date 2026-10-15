package io.github.haydengriffin.prism;

/** What the background service is doing, for the setup screen to show. Written by the service only. */
final class RemoteState {
    enum Mode {
        /** Automatic control is switched off in this app. */
        OFF,
        /** Connection, TV or Shield input still to choose. */
        SETUP,
        /** Android has not bound the accessibility service. */
        NO_PERMISSION,
        CONNECTING,
        /** An LG app or menu is in front: the remote drives the LG. */
        LG,
        /** The Shield's input is in front: the remote drives the Shield. */
        SHIELD,
        /** Another input (PS5, Live TV): the remote stays on the Shield. */
        OTHER_INPUT,
        TV_OFF,
        /** Held Back returned to the Shield; waiting for its input to appear. */
        PAUSED,
        /** Home Assistant unreachable or refusing; detail says which step. */
        ERROR
    }

    final Mode mode;
    /** The LG app's title for LG, the failing step for ERROR, otherwise empty. */
    final String detail;

    RemoteState(Mode mode, String detail) {
        this.mode = mode;
        this.detail = detail == null ? "" : detail;
    }

    static volatile RemoteState current = new RemoteState(Mode.OFF, "");
}
