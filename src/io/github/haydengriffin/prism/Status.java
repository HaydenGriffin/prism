package io.github.haydengriffin.prism;

/** The live line in every header: where the remote goes right now, in words, with a dot style to match. */
final class Status {
    /** Sky: the LG has the remote. Neutral: the Shield has it. Quiet: idle. Warn: needs a look. Alert: failing. */
    enum Tone { SKY, NEUTRAL, QUIET, WARN, ALERT }

    final String text;
    final Status.Tone tone;
    /** Whether the launcher should offer a fix (setup, permission, automatic control off). */
    final boolean needsAction;

    private Status(String text, Status.Tone tone, boolean needsAction) {
        this.text = text;
        this.tone = tone;
        this.needsAction = needsAction;
    }

    /** Before the saved setup has been read: neutral, never "Finish setup". */
    static Status starting() {
        return new Status("Prism", Status.Tone.QUIET, false);
    }

    static Status of(boolean setUp, boolean enabled, boolean bound, RemoteState state) {
        if (!setUp) return new Status("Finish setup to start", Status.Tone.WARN, true);
        if (!enabled) return new Status("Following the TV is off", Status.Tone.QUIET, true);
        if (!bound) return new Status("Remote permission is off", Status.Tone.WARN, true);
        switch (state.mode) {
            case LG: return new Status(state.detail.isEmpty() ? "On the LG" : "On the LG · " + state.detail, Status.Tone.SKY, false);
            case SHIELD: return new Status("On the Shield", Status.Tone.NEUTRAL, false);
            case OTHER_INPUT: return new Status("Another input is on · remote stays here", Status.Tone.QUIET, false);
            case TV_OFF: return new Status("The TV is off", Status.Tone.QUIET, false);
            case PAUSED: return new Status("Back on the Shield", Status.Tone.NEUTRAL, false);
            case CONNECTING: return new Status("Connecting to Home Assistant…", Status.Tone.QUIET, false);
            case ERROR:
                return new Status(state.detail.isEmpty() ? "Home Assistant is not answering" : state.detail, Status.Tone.ALERT, false);
            // The app's own checks above passed; the service is still catching up (just installed or reconnecting).
            default: return new Status("Connecting to Home Assistant…", Status.Tone.QUIET, false);
        }
    }
}
