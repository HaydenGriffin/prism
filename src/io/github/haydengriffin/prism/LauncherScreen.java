package io.github.haydengriffin.prism;

import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The first screen, all of it on one screen: Continue as the big tile, favourites and recent apps in the grid beside
 * it with All apps always last, the TV's own places in the dock, and the beam at the foot. See LauncherLayout.
 */
final class LauncherScreen {
    private static final int COLUMNS = LauncherLayout.COLUMNS;
    private static final int CHIP = 84, SHORT_CHIP = 66, BIG_ART = 150, SHORT_BIG_ART = 100;
    private static final float NAME = 17;
    /** The Quick TV settings tile, which gets the focus back when its sheet closes. */
    static final String QUICK = "quick";
    /** The big tile, where the focus starts: opening an app is what the launcher is for, even with a fix-this row up. */
    private static final String BIG = "big";
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
        s.deckPage();
        for (LauncherLayout.Row row : LauncherLayout.rows(tv != null, status.needsAction)) {
            switch (row) {
                case ATTENTION: attention(s, status); break;
                case DECK: deck(s, status); break;
                case DOCK: dock(s); break;
            }
        }
        s.statusLine(host.box().label(), tv == null ? "" : tv.name + " · " + host.box().label() + " on " + tv.shieldOption);
        focus(focusTag);
        refresh();
    }

    private void attention(Screen s, Status status) {
        boolean setUp = host.credentials() != null && host.profile() != null;
        boolean failing = status.tone == Status.Tone.ALERT, blocked = status.tone == Status.Tone.WARN;
        String action = !setUp ? "Set up" : failing ? "Check" : blocked ? "Allow" : "Turn on";
        Host.Page fix = !setUp || failing ? Host.Page.CONNECTION : blocked ? Host.Page.PERMISSION : Host.Page.SETUP;
        LinearLayout row = s.listRow(R.drawable.ic_alert, status.text, action, true, () -> host.show(fix));
        row.setTag("attention");
    }

    private void deck(Screen s, Status status) {
        boolean attention = status.needsAction;
        List<Apps.App> favourites = host.favourites().resolve(host.catalog());
        LauncherLayout.Deck deck = LauncherLayout.deck(resume, favourites, recent, host.catalog());
        int art = attention ? SHORT_BIG_ART : BIG_ART;
        LinearLayout column = s.deckRow(LauncherLayout.deckHeight(attention), row -> bigTile(s, row, deck, art));
        boolean choose = favourites.isEmpty() && deck.hero != null;
        int chip = attention ? SHORT_CHIP : CHIP;
        int next = 0;
        for (int r = 0; r < LauncherLayout.GRID_ROWS; r++) {
            LinearLayout row = s.grid(column, COLUMNS, LauncherLayout.tileHeight(attention));
            if (r == LauncherLayout.GRID_ROWS - 1) ((LinearLayout.LayoutParams) row.getLayoutParams()).bottomMargin = 0;
            for (int c = 0; c < COLUMNS; c++) {
                if (r == LauncherLayout.GRID_ROWS - 1 && c == COLUMNS - 1) {
                    s.glyphTile(row, "All apps", R.drawable.ic_apps, chip, NAME, () -> host.show(Host.Page.APPS)).setTag("apps");
                } else if (r == 0 && c == 0 && choose) {
                    s.glyphTile(row, "Choose favourites", R.drawable.ic_star_outline, chip, NAME, () -> host.show(Host.Page.FAVOURITES)).setTag("choose");
                } else if (next < deck.grid.size()) {
                    Apps.App app = deck.grid.get(next++);
                    s.appTile(row, app, false, host.icons(), chip, NAME, () -> host.launch(app)).setTag(app.id);
                } else if (deck.grid.isEmpty() && r == 0 && status.tone != Status.Tone.ALERT) {
                    s.quiet(row, "Apps you open on the LG show up here", COLUMNS - c);
                    break;
                } else {
                    s.spacer(row);
                }
            }
        }
    }

    private void bigTile(Screen s, LinearLayout row, LauncherLayout.Deck deck, int art) {
        if (deck.hero == null) {
            s.bigGlyphTile(row, "Choose favourites", "Your apps, one press away", R.drawable.ic_star_outline, art,
                () -> host.show(Host.Page.FAVOURITES)).setTag(BIG);
            return;
        }
        Apps.App app = deck.hero;
        String label = deck.continues ? "Continue on the LG" : host.favourites().contains(app.id) ? "Favourite" : "Recently used";
        s.bigAppTile(row, app, label, host.icons(), art, () -> host.launch(app)).setTag(BIG);
    }

    private void dock(Screen s) {
        LinearLayout row = s.grid(COLUMNS, LauncherLayout.DOCK);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).bottomMargin = 0;
        s.dockTile(row, "LG Home", R.drawable.ic_home, host::openLgHome).setTag("home");
        s.dockTile(row, "LG Settings", R.drawable.ic_settings, this::openSettings).setTag("settings");
        s.dockTile(row, "Quick TV settings", R.drawable.ic_tune, () -> host.show(Host.Page.QUICK_SETTINGS)).setTag(QUICK);
        s.dockTile(row, "Setup", R.drawable.ic_remote, () -> host.show(Host.Page.SETUP)).setTag("setup");
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
                List<Apps.App> used = Apps.recent(history, catalog, host.favourites(), last == null ? null : last.id, LauncherLayout.GRID_APPS);
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

    /** Draws the launcher again with the focus where it was. */
    void redraw() {
        String tag = host.screen().focusedTag();
        host.show(Host.Page.LAUNCHER);
        if (tag != null) focus(tag);
    }

    private void focus(String tag) {
        Screen s = host.screen();
        View target = s.content.findViewWithTag(tag == null ? BIG : tag);
        if (target == null) target = firstFocusable(s.content);
        if (target != null) target.requestFocus();
    }

    /** The first focusable view on the page. */
    static View firstFocusable(View v) {
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
