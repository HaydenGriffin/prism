package io.github.haydengriffin.prism;

import android.content.Context;
import android.os.PowerManager;

/**
 * Whether Doze leaves this app's network alone while the Shield sleeps. Shield Android TV has no battery
 * optimisation screen, so the only fix is adb's device-idle allowlist (it persists).
 */
final class DozeExemption {

    private DozeExemption() {}

    static boolean granted(Context c) {
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static String fix(Context c) {
        return "adb shell dumpsys deviceidle whitelist +" + c.getPackageName();
    }
}
