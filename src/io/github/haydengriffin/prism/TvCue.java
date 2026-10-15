package io.github.haydengriffin.prism;

/**
 * The on-TV cue (Glasshouse screen_notification): once per LG session, on its first captured press. A session ends
 * when the route leaves the LG or the link drops.
 */
final class TvCue {

    static final String TEXT = "Shield remote on the LG · hold Back for the Shield";

    private boolean shown;

    /** @return true for the first press of this session only */
    synchronized boolean firstPress() {
        boolean first = !shown;
        shown = true;
        return first;
    }

    /** After every route update. */
    synchronized void route(boolean controlsLg) {
        if (!controlsLg) shown = false;
    }

    /** The link dropped or the session was replaced. */
    synchronized void reset() {
        shown = false;
    }
}
