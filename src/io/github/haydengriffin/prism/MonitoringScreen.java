package io.github.haydengriffin.prism;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Setup → Monitoring: an optional heartbeat into an input_text helper, so Home Assistant can tell when the remote
 * stops working. Off by default.
 */
final class MonitoringScreen {
    static final String SUGGESTED = "input_text.prism";
    private final Host host;
    private final Activity activity;

    MonitoringScreen(Host host, Activity activity) {
        this.host = host;
        this.activity = activity;
    }

    /** The entity the service should write, or null while monitoring is off. */
    static String entity(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("on", false) ? Heartbeat.entity(p.getString("entity", "")) : null;
    }

    static String summary(Context c) {
        String entity = entity(c);
        return entity == null ? "Off" : entity;
    }

    void open() {
        Screen s = host.screen();
        s.page("Monitoring", "Optional");
        s.note("Prism can write its state and version (for example \"lg · 1.0.0\") to an input_text helper in Home "
            + "Assistant: when it connects, whenever the state changes, and every minute. An automation can then warn "
            + "you when the value stops changing while the box is on. Nothing else is sent.", false);
        boolean on = entity(activity) != null;
        String saved = prefs(activity).getString("entity", "");
        EditText field = s.field("input_text entity", saved.isEmpty() ? SUGGESTED : saved, false);
        TextView note = s.note("", true);
        LinearLayout actions = new LinearLayout(activity);
        s.content.addView(actions);
        s.pill(actions, on ? "Save" : "Turn on", () -> save(field, note));
        if (on) {
            TextView off = s.pill(actions, "Turn off", this::turnOff);
            ((LinearLayout.LayoutParams) off.getLayoutParams()).setMarginStart(s.dp(12));
        }
        field.requestFocus();
    }

    private void save(EditText field, TextView note) {
        String entity = Heartbeat.entity(field.getText().toString());
        if (entity == null) {
            note.setText("Enter an input_text entity id, such as " + SUGGESTED + ".");
            return;
        }
        prefs(activity).edit().putBoolean("on", true).putString("entity", entity).apply();
        AutomaticService.refresh();
        host.show(Host.Page.SETUP);
    }

    private void turnOff() {
        prefs(activity).edit().putBoolean("on", false).apply();
        AutomaticService.refresh();
        host.show(Host.Page.SETUP);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("monitoring", Context.MODE_PRIVATE);
    }
}
