package io.github.haydengriffin.prism;

import android.view.KeyEvent;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quick TV settings: Left/Right steps a value, which is sent after a short pause so only the last step goes. */
final class QuickSettingsScreen {
    private static final Map<String, Integer> ICONS = new HashMap<>();
    static {
        ICONS.put("input_source", R.drawable.ic_input);
        ICONS.put("picture_mode", R.drawable.ic_picture);
        ICONS.put("oled_light", R.drawable.ic_brightness);
        ICONS.put("energy_saving", R.drawable.ic_leaf);
        ICONS.put("sound_mode", R.drawable.ic_sound);
        ICONS.put("sleep_timer", R.drawable.ic_sleep);
        ICONS.put("oled_logo_dimming", R.drawable.ic_logo);
    }
    private final Host host;
    private final Map<String, Runnable> pending = new HashMap<>();
    private android.widget.TextView note;

    QuickSettingsScreen(Host host) { this.host = host; }

    void open() {
        Screen s = host.screen();
        s.page("Quick TV settings", "Left and Right change a value, sent when you pause · picture settings apply to what is on screen");
        note = s.note("Reading the TV's settings…", false);
        int request = host.request();
        TvProfile tv = host.profile();
        host.work(() -> {
            List<TvSettings.Value> values = new ArrayList<>();
            String failure = null;
            try {
                for (TvSettings.Row row : TvSettings.ROWS) {
                    String entity = tv.settings.get(row.role);
                    if (entity == null) continue;
                    TvSettings.Value v = TvData.setting(host.api(), row, entity);
                    if (v != null && row.role.equals("input_source")) v.named(tv.shieldOption, host.box().label());
                    if (v != null) values.add(v);
                }
            } catch (Exception e) {
                failure = host.error(e);
            }
            String problem = failure;
            host.main().post(() -> {
                if (!host.current(request)) return;
                if (problem != null) error(problem);
                else rows(values, request);
            });
        });
    }

    /** Leaving the screen sends a value that was still waiting for its pause. */
    void flush() {
        for (Runnable waiting : new ArrayList<>(pending.values())) {
            host.main().removeCallbacks(waiting);
            waiting.run();
        }
    }

    private void rows(List<TvSettings.Value> values, int request) {
        Screen s = host.screen();
        if (values.isEmpty()) {
            error("The TV offers no settings right now. Is it on?");
            return;
        }
        note.setText("");
        for (TvSettings.Value value : values) {
            LinearLayout row = s.stepperRow(ICONS.get(value.row.role), value.row.label, value.text(), value.row.number());
            row.setOnClickListener(v -> { value.advance(); changed(row, value, request); });
            row.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    value.move(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    changed(row, value, request);
                }
                return true;
            });
        }
        LauncherScreen.firstFocusable(s.content).requestFocus();
    }

    private void changed(LinearLayout row, TvSettings.Value value, int request) {
        Screen.setValue(row, value.text());
        String entity = host.profile().settings.get(value.row.role);
        Runnable previous = pending.remove(entity);
        if (previous != null) host.main().removeCallbacks(previous);
        Runnable send = () -> { pending.remove(entity); apply(row, value, entity, request); };
        pending.put(entity, send);
        host.main().postDelayed(send, 500);
    }

    private void apply(LinearLayout row, TvSettings.Value value, String entity, int request) {
        if (!value.changed()) return;
        String target = value.shown;
        String label = value.row.label + " " + value.text(); // shown == target here: "Input SHIELD", "Energy saving Medium"
        host.command(() -> {
            try {
                host.api().setValue(entity, target);
                host.main().post(() -> {
                    value.confirmed = target;
                    if (host.current(request)) note.setText(label + " sent to the TV.");
                });
            } catch (Exception e) {
                host.main().post(() -> {
                    if (value.shown.equals(target)) value.shown = value.confirmed;
                    if (!host.current(request)) return;
                    Screen.setValue(row, value.text());
                    error("Could not change " + value.row.label.toLowerCase() + ". " + host.error(e));
                });
            }
        });
    }

    private void error(String text) {
        note.setText(text);
        note.setTextColor(host.screen().color(R.color.error));
    }
}
