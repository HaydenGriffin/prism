package io.github.haydengriffin.prism;

import android.content.Context;
import android.os.PowerManager;

/**
 * Counts a forwarded press as user activity. Keys the service consumes are never dispatched, so they do not reset
 * the Shield's screen-off timer; without this it can sleep mid LG session and the next press wakes it, which makes
 * CEC switch the TV back to the Shield's input.
 */
final class UserActivity {

    private final PowerManager.WakeLock poke;

    @SuppressWarnings("deprecation") // SCREEN_DIM_WAKE_LOCK: the one lock that pokes the user-activity timer on release.
    UserActivity(Context c) {
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        poke = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "Prism:press");
        poke.setReferenceCounted(false);
    }

    /** Main thread; acquired and released at once, so ON_AFTER_RELEASE restarts the screen-off timer. */
    void press() {
        poke.acquire();
        poke.release();
    }
}
