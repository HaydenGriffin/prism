package io.github.haydengriffin.prism;

import android.content.Intent;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Setup: one list of what is configured, each row opening its own short page. Rarely visited. */
final class SetupScreen {
    private final Host host;
    private final android.app.Activity activity;
    /** The TV picked on Choose the TV, until its Shield input is saved; null when Shield input came from Setup. */
    private TvProfile chosen;

    SetupScreen(Host host, android.app.Activity activity) {
        this.host = host;
        this.activity = activity;
    }

    void open(String focusTag) {
        Screen s = host.screen();
        chosen = null;
        s.page("Setup", "Prism " + version());
        TvProfile tv = host.profile();
        Credentials c = host.credentials();
        boolean enabled = AutomaticService.enabled(activity);
        s.listRow(R.drawable.ic_remote, "Follow the TV", enabled ? "On" : "Off", false, this::toggleFollow).setTag("follow");
        s.listRow(R.drawable.ic_permission, "Remote permission", AutomaticService.live != null ? "Allowed" : "Not allowed", true,
            () -> host.show(Host.Page.PERMISSION)).setTag("permission");
        s.listRow(R.drawable.ic_remote, "Remotes", RemotesScreen.summary(activity), true,
            () -> host.show(Host.Page.REMOTES)).setTag("remotes");
        s.listRow(R.drawable.ic_tv, "TV", tv == null ? "Choose" : tv.name, true, () -> host.show(Host.Page.CHOOSE_TV)).setTag("tv");
        s.listRow(R.drawable.ic_shield, host.box().input(), tv == null || tv.shieldOption.isEmpty() ? "Choose" : tv.shieldOption, true,
            () -> host.show(tv == null ? Host.Page.CHOOSE_TV : Host.Page.SHIELD_INPUT)).setTag("input");
        s.listRow(R.drawable.ic_server, "Home Assistant", c == null ? "Connect" : hostName(c.url), true,
            () -> host.show(Host.Page.CONNECTION)).setTag("connection");
        boolean exempt = DozeExemption.granted(activity);
        s.listRow(R.drawable.ic_sleep, "Stay connected in sleep", exempt ? "On" : "Off · needs adb", false,
            () -> open("sleep")).setTag("sleep");
        if (!exempt) {
            s.note("While the box sleeps, Android cuts this app off from Home Assistant. Android TV has no setting for "
                + "this; from a computer run: " + DozeExemption.fix(activity), true);
        }
        int chosen = host.favourites().stored().size();
        s.listRow(R.drawable.ic_star_outline, "Favourites", chosen == 0 ? "None" : String.valueOf(chosen), true,
            () -> host.show(Host.Page.FAVOURITES)).setTag("favourites");
        ChannelSync.setupRow(s, activity, () -> open("channels"));
        s.listRow(R.drawable.ic_check, "Monitoring", MonitoringScreen.summary(activity), true,
            () -> host.show(Host.Page.MONITORING)).setTag("monitoring");
        focus(focusTag);
    }

    private void toggleFollow() {
        boolean on = !AutomaticService.enabled(activity);
        AutomaticService.enabled(activity, on);
        if (on && AutomaticService.live == null) host.show(Host.Page.PERMISSION);
        else open("follow");
    }

    void permission() {
        Screen s = host.screen();
        s.page("Remote permission", "");
        s.note("Prism uses Android accessibility to receive the remote's arrows, OK, Back and play buttons while "
            + "another app is open. It forwards them to the TV only while an LG app is in front. It cannot read the "
            + "screen or anything typed.", false);
        s.note("On the box: Settings → Device Preferences → Accessibility → Prism.", false);
        LinearLayout actions = new LinearLayout(activity);
        s.content.addView(actions);
        s.pill(actions, AutomaticService.live != null ? "Allowed · open settings" : "Open Android settings", this::openAccessibility)
            .requestFocus();
    }

    private void openAccessibility() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (android.content.ActivityNotFoundException missing) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    void connection() {
        Screen s = host.screen();
        Credentials c = host.credentials();
        s.page("Home Assistant", "A local user with access to the Glasshouse TV, and its long-lived token");
        EditText address = s.field("Address", c == null ? "" : c.url, false);
        address.setHint("http://homeassistant.local:8123");
        EditText token = s.field(c == null ? "Access token" : "Access token · leave empty to keep the saved one", "", true);
        android.widget.TextView note = s.note("", false);
        LinearLayout actions = new LinearLayout(activity);
        s.content.addView(actions);
        s.pill(actions, "Connect", () -> connect(address, token, note));
        address.requestFocus();
    }

    private void connect(EditText address, EditText token, android.widget.TextView note) {
        Credentials c = host.credentials();
        Credentials next;
        try {
            String secret = token.getText().toString();
            next = new Credentials(address.getText().toString(), secret.isEmpty() && c != null ? c.token : secret);
        } catch (Exception e) {
            note.setText(host.error(e));
            return;
        }
        token.setText("");
        note.setText("Checking Home Assistant…");
        int request = host.request();
        host.work(() -> {
            try {
                new HaHttp(next.url, next.token).browse("");
                next.save(activity);
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    boolean moved = c != null && !c.url.equals(next.url);
                    host.credentials(next);
                    if (moved) host.profile(null);
                    AutomaticService.refresh();
                    host.show(Host.Page.CHOOSE_TV);
                });
            } catch (Exception e) {
                host.main().post(() -> { if (host.current(request)) note.setText(host.error(e)); });
            }
        });
    }

    void chooseTv() {
        Screen s = host.screen();
        chosen = null;
        s.page("Choose the TV", "Glasshouse TVs in Home Assistant");
        if (host.credentials() == null) { host.show(Host.Page.CONNECTION); return; }
        android.widget.TextView note = s.note("Looking for TVs…", false);
        int request = host.request();
        host.work(() -> {
            try {
                List<TvProfile> found = TvProfile.discover(host.credentials());
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    if (found.isEmpty()) {
                        note.setText("No TV found. In Home Assistant, enable Glasshouse's Launch App, Input Source, Active App and Remote buttons.");
                        return;
                    }
                    note.setText(found.size() == 1 ? "One TV found." : found.size() + " TVs found.");
                    for (TvProfile tv : found) s.listRow(R.drawable.ic_tv, tv.name, "", true, () -> choose(tv));
                    LauncherScreen.firstFocusable(s.content).requestFocus();
                });
            } catch (Exception e) {
                host.main().post(() -> { if (host.current(request)) note.setText(host.error(e)); });
            }
        });
    }

    private void choose(TvProfile tv) {
        chosen = tv;
        host.show(Host.Page.SHIELD_INPUT);
    }

    /** Whether Back from Shield input goes to Choose the TV. */
    boolean fromChooseTv() { return chosen != null; }

    /**
     * Finds the Shield's input for the TV just chosen (else the saved one): the TV's current input when this screen
     * is on it, else a choice of HDMI inputs.
     */
    void shieldInput() {
        TvProfile tv = chosen != null ? chosen : host.profile();
        if (tv == null) { host.show(Host.Page.CHOOSE_TV); return; }
        Screen s = host.screen();
        s.page(host.box().input(), tv.name);
        android.widget.TextView note = s.note("Checking which input is on screen…", false);
        int request = host.request();
        host.work(() -> {
            try {
                HaHttp api = new HaHttp(host.credentials().url, host.credentials().token);
                JSONObject input = new JSONObject(api.browse("states/" + tv.input));
                JSONObject active = new JSONObject(api.browse("states/" + tv.active));
                JSONArray options = input.getJSONObject("attributes").getJSONArray("options");
                String app = active.getJSONObject("attributes").optString("app_id");
                String current = app.matches("com\\.webos\\.app\\.hdmi[1-9][0-9]*") ? "HDMI " + app.substring("com.webos.app.hdmi".length()) : "";
                List<String> hdmi = new ArrayList<>();
                for (int i = 0; i < options.length(); i++) if (options.optString(i).matches("HDMI [1-9][0-9]*")) hdmi.add(options.optString(i));
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    note.setText(current.isEmpty()
                        ? "The TV is not showing an input now. Choose the one " + host.box().the() + " is on; nothing switches."
                        : "This screen is on " + current + ", so that is " + host.box().the() + "'s input.");
                    if (!current.isEmpty()) s.listRow(R.drawable.ic_check, "Use " + current, "On screen now", false, () -> save(tv, current));
                    for (String option : hdmi) if (!option.equals(current)) s.listRow(R.drawable.ic_shield, option, "", false, () -> save(tv, option));
                    LauncherScreen.firstFocusable(s.content).requestFocus();
                });
            } catch (Exception e) {
                host.main().post(() -> { if (host.current(request)) note.setText(host.error(e)); });
            }
        });
    }

    private void save(TvProfile tv, String option) {
        tv.shieldOption = option;
        tv.shieldApp = "com.webos.app.hdmi" + option.substring(5);
        int request = host.request();
        host.work(() -> {
            try {
                tv.save(activity);
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    chosen = null;
                    host.profile(tv);
                    AutomaticService.refresh();
                    host.show(Host.Page.LAUNCHER);
                });
            } catch (Exception e) {
                host.main().post(() -> { if (host.current(request)) host.screen().note(host.error(e), true); });
            }
        });
    }

    private void focus(String tag) {
        android.view.View v = tag == null ? null : host.screen().content.findViewWithTag(tag);
        if (v == null) v = LauncherScreen.firstFocusable(host.screen().content);
        if (v != null) v.requestFocus();
    }

    private String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String hostName(String url) {
        try {
            URI uri = new URI(url);
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
