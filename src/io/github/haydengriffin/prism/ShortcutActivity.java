package io.github.haydengriffin.prism;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.List;

/**
 * Shortcuts for a button mapper: no screen of its own. Each alias in the manifest names
 * one target; `--es target app:<lg app id>` opens any LG app, `input:<option>` switches the TV's input (the home-screen
 * rows use both). Prism maps no buttons itself; the mapper stays the one place buttons are mapped.
 */
public final class ShortcutActivity extends Activity {
    static final String EXTRA_TARGET = "target";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String target = target();
        if (target.equals("quick_settings") || target.equals("apps")) {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String problem = run(target);
            main.post(() -> {
                if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "lg-shortcut").start();
    }

    /** The alias's own target, or the intent's extra. */
    private String target() {
        String alias = getIntent().getComponent() == null ? "" : getIntent().getComponent().getShortClassName();
        if (alias.endsWith("OpenLgHome")) return "lg_home";
        if (alias.endsWith("OpenLgSettings")) return "lg_settings";
        if (alias.endsWith("OpenQuickSettings")) return "quick_settings";
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        return extra == null ? "" : extra.trim();
    }

    /** Returns null on success, else a short message for a toast. */
    private String run(String target) {
        try {
            Credentials c = Credentials.load(this);
            TvProfile tv = TvProfile.load(this);
            if (c == null || tv == null) return "Prism is not set up yet";
            HaHttp api = new HaHttp(c.url, c.token);
            if (target.equals("lg_home")) {
                String home = tv.buttons.get("home");
                if (home == null) return "LG Home is off in Home Assistant";
                api.press(home);
                return null;
            }
            if (target.startsWith("input:")) {
                String option = target.substring("input:".length()).trim();
                if (option.isEmpty()) return "Unknown Prism shortcut";
                api.select(tv.input, option);
                return null;
            }
            String id = target.equals("lg_settings") ? Apps.LaunchIds.SETTINGS
                : target.startsWith("app:") ? target.substring(4) : null;
            if (id == null) return "Unknown Prism shortcut";
            List<Apps.App> catalog = TvData.catalog(api, tv);
            if (catalog.isEmpty()) return "The TV is off";
            for (Apps.App app : catalog) {
                if (app.id.equals(id)) {
                    api.select(tv.launch, app.title);
                    return null;
                }
            }
            return "That app is not on the LG";
        } catch (Exception e) {
            return "Home Assistant did not answer";
        }
    }
}
