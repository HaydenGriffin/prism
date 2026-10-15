package io.github.haydengriffin.prism;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** Reads the box's name from Android: Settings → Device Preferences → About → Device name. */
final class DeviceName {

    private DeviceName() {}

    static BoxName read(Context c) {
        String name = null;
        try {
            name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (RuntimeException ignored) {
            // Fall back to the model.
        }
        return BoxName.of(name, Build.MODEL);
    }
}
