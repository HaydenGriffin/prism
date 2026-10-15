package io.github.haydengriffin.prism;

import android.app.Activity;
import java.util.List;

/** Setup → Remotes: every input device that has pressed a key, each with a switch for controlling the LG. */
final class RemotesScreen {
    private final Host host;
    private final Activity activity;

    RemotesScreen(Host host, Activity activity) {
        this.host = host;
        this.activity = activity;
    }

    /** Setup's row: how many of the devices seen control the LG. */
    static String summary(Activity activity) {
        List<Remotes.Device> devices = RemotesStore.get(activity).list();
        if (devices.isEmpty()) return "None seen yet";
        int on = 0;
        for (Remotes.Device d : devices) if (d.controls()) on++;
        return on + " of " + devices.size();
    }

    void open(String focusId) {
        Screen s = host.screen();
        s.page("Remotes", "Which devices control the LG");
        List<Remotes.Device> devices = RemotesStore.get(activity).list();
        s.note(devices.isEmpty()
            ? "No remote has pressed a key since Prism's Remote permission was allowed. Press any button on a remote, then open this page again."
            : "Every device that has pressed a key. OK switches one on or off.", false);
        android.view.View focus = null;
        for (int i = devices.size() - 1; i >= 0; i--) {
            Remotes.Device d = devices.get(i);
            android.view.View row = s.listRow(R.drawable.ic_remote, d.name, kindLabel(d.kind) + " · " + (d.controls() ? "On" : "Off"),
                false, () -> toggle(d));
            if (d.id.equals(focusId)) focus = row;
        }
        s.note("Physical remotes are on until you turn them off. HDMI-CEC is the TV's own remote passed through to "
            + "this box: keep it off, or its presses would loop back to the TV. Virtual devices (phone remote apps, adb) "
            + "are off; turn one on to drive the LG from your phone.", false);
        if (focus == null) focus = LauncherScreen.firstFocusable(s.content);
        if (focus != null) focus.requestFocus();
    }

    private void toggle(Remotes.Device d) {
        RemotesStore.choose(activity, d.id, !d.controls());
        open(d.id);
    }

    private static String kindLabel(RemotePolicy.Kind kind) {
        switch (kind) {
            case CEC: return "HDMI-CEC";
            case VIRTUAL: return "Virtual";
            default: return "Physical";
        }
    }
}
