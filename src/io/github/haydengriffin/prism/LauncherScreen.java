package io.github.haydengriffin.prism;

import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The first screen: Continue on the LG, favourites as big app tiles, recently used beside All apps, then the TV's own
 * places. Setup is the header's icon button. Rows keep their places (LauncherLayout).
 */
final class LauncherScreen {
    static final int COLUMNS = LauncherLayout.COLUMNS;
    private final Host host;
    private List<Apps.App> recent = new ArrayList<>();
    /** The Continue tile's app, or null. Seeded from the last one shown, so a cold start draws it at once. */
    private Apps.App resume;
    private boolean seeded;

    LauncherScreen(Host host) { this.host = host; }

    void open(Status status, String focusTag) {
        Screen s = host.screen();
        TvProfile tv = host.profile();
        if (!seeded) {
            seeded = true;
            String id = host.resumeApp();
            resume = id == null ? null : Apps.find(host.catalog(), id);
        }
        s.page("Prism", tv == null ? "" : tv.name + " · " + host.box().label() + " on " + tv.shieldOption);
        s.headerButton(R.drawable.ic_remote, "Setup", () -> host.show(Host.Page.SETUP)).setTag("setup");
        for (LauncherLayout.Row row : LauncherLayout.rows(tv != null, status.needsAction, resume != null)) {
            switch (row) {
                case ATTENTION: attention(s, status); break;
                case RESUME: resumeRow(s); break;
                case FAVOURITES: favourites(s); break;
                case RECENT: recentRow(s); break;
                case TV: tvRow(s); break;
            }
        }
        focus(s, focusTag);
        refresh();
    }

    private void resumeRow(Screen s) {
        Apps.App app = resume;
        LinearLayout row = s.grid(1, 96, 0);
        s.wideAppTile(row, app, "Continue on the LG · " + app.title, host.icons(), () -> host.launch(app)).setTag("resume");
    }

    private void attention(Screen s, Status status) {
        boolean setUp = host.credentials() != null && host.profile() != null;
        String action = !setUp ? "Set up" : status.tone == Status.Tone.WARN ? "Allow" : "Turn on";
        LinearLayout row = s.listRow(R.drawable.ic_alert, status.text, action, true,
            () -> host.show(setUp ? Host.Page.SETUP : Host.Page.CONNECTION));
        row.setTag("attention");
    }

    private void favourites(Screen s) {
        List<Apps.App> apps = host.favourites().resolve(host.catalog());
        s.section("Favourites");
        if (apps.isEmpty()) {
            LinearLayout row = s.grid(COLUMNS, 120, 1);
            s.tile(row, "Choose favourites", "Your apps, one press away", R.drawable.ic_star_outline,
                () -> host.show(Host.Page.FAVOURITES)).setTag("choose");
            while (row.getChildCount() < COLUMNS) s.spacer(row);
            return;
        }
        for (int start = 0; start < apps.size(); start += COLUMNS) {
            LinearLayout row = s.grid(COLUMNS, 120, 0);
            for (int i = start; i < start + COLUMNS; i++) {
                if (i < apps.size()) {
                    Apps.App app = apps.get(i);
                    s.appTile(row, app, false, host.icons(), () -> host.launch(app)).setTag(app.id);
                } else {
                    s.spacer(row);
                }
            }
        }
    }

    /** Always present: up to three apps, then All apps in the last column; with none, a quiet line holds the row. */
    private void recentRow(Screen s) {
        s.section("Recently used");
        LinearLayout row = s.grid(COLUMNS, 84, 0);
        for (Apps.App app : recent) s.appTile(row, app, false, host.icons(), () -> host.launch(app)).setTag("recent:" + app.id);
        int gap = LauncherLayout.recentGap(recent.size());
        if (recent.isEmpty()) s.quiet(row, "Apps you open on the LG show up here", gap);
        else for (int i = 0; i < gap; i++) s.spacer(row);
        s.tile(row, "All apps", null, R.drawable.ic_apps, () -> host.show(Host.Page.APPS)).setTag("apps");
    }

    private void tvRow(Screen s) {
        s.section("TV");
        LinearLayout row = s.grid(COLUMNS, 88, 0);
        s.tile(row, "LG Home", null, R.drawable.ic_home, host::openLgHome).setTag("home");
        s.tile(row, "LG Settings", null, R.drawable.ic_settings, this::openSettings).setTag("settings");
        s.tile(row, "Quick TV settings", null, R.drawable.ic_tune, () -> host.show(Host.Page.QUICK_SETTINGS)).setTag("quick");
        s.spacer(row);
    }

    private void openSettings() {
        Apps.App settings = Apps.find(host.catalog(), Apps.LaunchIds.SETTINGS);
        host.launch(settings != null ? settings : new Apps.App(Apps.LaunchIds.SETTINGS, "Settings", null, null));
    }

    /** Fetches the catalog and recent use; redraws once if anything visible changed, keeping focus in place. */
    private void refresh() {
        if (host.credentials() == null || host.profile() == null) return;
        int request = host.request();
        host.work(() -> {
            try {
                HaHttp api = host.api();
                List<Apps.App> catalog = TvData.catalog(api, host.profile());
                if (catalog.isEmpty()) return; // The TV is off: keep what is shown; the live line says so.
                List<String> history = TvData.history(api, host.profile());
                Apps.App last = Apps.resume(history, catalog);
                List<Apps.App> used = Apps.recent(history, catalog, host.favourites(), last == null ? null : last.id);
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    boolean changed = !ids(catalog).equals(ids(host.catalog())) || !ids(used).equals(ids(recent))
                        || !ids(single(last)).equals(ids(single(resume)));
                    host.catalog(catalog);
                    recent = used;
                    resume = last;
                    host.resumeApp(last == null ? null : last.id);
                    if (changed) redraw();
                });
            } catch (Exception e) {
                android.util.Log.w("Prism", "Launcher refresh: " + e.getClass().getSimpleName());
            }
        });
    }

    private void redraw() {
        View focused = host.screen().content.findFocus();
        Object tag = focused == null ? null : focused.getTag();
        host.show(Host.Page.LAUNCHER);
        if (tag != null) focus(host.screen(), tag.toString());
    }

    private static void focus(Screen s, String tag) {
        View target = tag == null ? null : s.content.findViewWithTag(tag);
        if (target == null) target = firstFocusable(s.content);
        if (target != null) target.requestFocus();
    }

    /** The first focusable view, skipping the page header (its icon button is reached with Up). */
    static View firstFocusable(View v) {
        if (Screen.HEADER.equals(v.getTag())) return null;
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = firstFocusable(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Apps.App> single(Apps.App app) {
        List<Apps.App> out = new ArrayList<>();
        if (app != null) out.add(app);
        return out;
    }

    private static List<String> ids(List<Apps.App> apps) {
        List<String> out = new ArrayList<>();
        for (Apps.App a : apps) out.add(a.id + "|" + a.title + "|" + a.icon);
        return out;
    }
}
