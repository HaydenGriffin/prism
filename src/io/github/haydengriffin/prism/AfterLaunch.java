package io.github.haydengriffin.prism;

/**
 * What the launcher does once the TV shows what it opened. Prism stays open behind LG Settings and other LG menus
 * that cover only part of the screen, unless Setup says to close it. Plain Java: this class also runs on the host JVM.
 */
final class AfterLaunch {

    enum Step {
        /** Close Prism: the remote follows the LG without it. */
        CLOSE,
        /** Stay open behind the LG; the remote follows the LG all the same. */
        STAY,
        /** Stay, and say that the remote will not follow until Follow the TV is on and allowed. */
        NOT_FOLLOWING,
    }

    private AfterLaunch() {}

    /**
     * @param following Follow the TV is on and the remote permission is allowed
     * @param closeChosen Setup's "Close after opening on the LG"
     */
    static Step step(boolean following, boolean closeChosen) {
        if (!following) return Step.NOT_FOLLOWING;
        return closeChosen ? Step.CLOSE : Step.STAY;
    }
}
