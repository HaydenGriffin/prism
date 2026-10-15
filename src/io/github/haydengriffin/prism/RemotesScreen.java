package io.github.haydengriffin.prism;

import android.app.Activity;
import java.util.List;

/**
 * Setup → Remotes: every input device that has pressed a key, each with a switch for controlling the LG. The TV's
 * own remote over HDMI-CEC is listed without one: it can never control the TV.
 */
final class RemotesScreen {
    static final String CEC_NOTE = "TV remote (HDMI-CEC): can't control the TV, it would loop";
    private final Host host;
    private final Activity activity;

    RemotesScreen(Host host, Activity activity) {
        this.host = host;
        this.activity = activity;
    }

    /** Setup's row: how many of the switchable devices seen control the LG. */
    static String summary(Activity activity) {
        int on = 0, all = 0;
        for (Remotes.Device d : RemotesStore.get(activity).list()) {
            if (!RemotePolicy.switchable(d.kind)) continue;
            all++;
            if (d.controls()) on++;
        }
        return all == 0 ? "None seen yet" : on + " of " + all;
    }

    void open(String focusId) {
        Screen s = host.screen();
        s.page("Remotes", "Which devices control the LG");
        List<Remotes.Device> devices = RemotesStore.get(activity).list();
        s.note(devices.isEmpty()
            ? "No remote has pressed a key since Prism's Remote permission was allowed. Press any button on a remote, then open this page again."
            : "Every device that has pressed a key. OK switches one on or off.", false);
        android.view.View focus = null;
        boolean cec = false;
        for (int i = devices.size() - 1; i >= 0; i--) {
            Remotes.Device d = devices.get(i);
            if (!RemotePolicy.switchable(d.kind)) {
                cec = true;
                continue;
            }
            android.view.View row = s.listRow(R.drawable.ic_remote, d.name, kindLabel(d.kind) + " · " + (d.controls() ? "On" : "Off"),
                false, () -> toggle(d));
            if (d.id.equals(focusId)) focus = row;
        }
        if (cec) s.note(CEC_NOTE, false);
        s.note("Physical remotes are on until you turn them off. Virtual devices (phone remote apps, adb) are off; turn "
            + "one on to drive the LG from your phone.", false);
        if (focus == null) focus = LauncherScreen.firstFocusable(s.content);
        if (focus != null) focus.requestFocus();
    }

    private void toggle(Remotes.Device d) {
        RemotesStore.choose(activity, d.id, !d.controls());
        open(d.id);
    }

    private static String kindLabel(RemotePolicy.Kind kind) {
        return kind == RemotePolicy.Kind.VIRTUAL ? "Virtual" : "Physical";
    }
}
