package io.github.haydengriffin.prism;

import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Every LG app as a tile. In "All apps" OK opens the app; in "Favourites" OK adds or removes it (a sky star marks
 * favourites, in the order chosen).
 */
final class AppsScreen {
    static final int COLUMNS = 5;
    private final Host host;

    AppsScreen(Host host) { this.host = host; }

    void open(boolean choosing, String focusTag) {
        Screen s = host.screen();
        List<Apps.App> apps = launchable(host.catalog());
        s.page(choosing ? "Favourites" : "All apps", subtitle(choosing, apps.size()));
        LinearLayout actions = new LinearLayout(s.content.getContext());
        s.content.addView(actions);
        if (choosing) s.pill(actions, "Done", () -> host.show(Host.Page.LAUNCHER)).setTag("done");
        else s.pill(actions, "Choose favourites", () -> host.show(Host.Page.FAVOURITES)).setTag("choose");
        View spacer = new View(s.content.getContext());
        s.content.addView(spacer, new LinearLayout.LayoutParams(1, s.dp(16)));
        android.widget.TextView reading = apps.isEmpty() ? s.note("Reading the TV's apps…", false) : null;
        grid(s, apps, choosing);
        View target = focusTag == null ? null : s.content.findViewWithTag(focusTag);
        if (target == null && !apps.isEmpty()) target = s.content.findViewWithTag(apps.get(0).id);
        if (target != null) target.requestFocus();
        if (apps.isEmpty()) load(choosing, reading);
    }

    private String subtitle(boolean choosing, int count) {
        if (!choosing) return count == 0 ? "" : count + " apps on the LG";
        int chosen = host.favourites().stored().size();
        return chosen == 0 ? "Press OK on an app to add it" : chosen + (chosen == 1 ? " favourite" : " favourites") + " · OK adds or removes";
    }

    private void grid(Screen s, List<Apps.App> apps, boolean choosing) {
        for (int start = 0; start < apps.size(); start += COLUMNS) {
            LinearLayout row = s.grid(COLUMNS, 108, 0);
            for (int i = start; i < start + COLUMNS; i++) {
                if (i >= apps.size()) { s.spacer(row); continue; }
                Apps.App app = apps.get(i);
                boolean favourite = choosing && host.favourites().contains(app.id);
                s.appTile(row, app, favourite, host.icons(), () -> {
                    if (!choosing) { host.launch(app); return; }
                    host.favourites().toggle(app.id, app.title);
                    host.saveFavourites();
                    open(true, app.id); // redraw with the star, focus stays on this tile
                }).setTag(app.id);
            }
        }
    }

    private void load(boolean choosing, android.widget.TextView reading) {
        int request = host.request();
        host.work(() -> {
            try {
                List<Apps.App> catalog = TvData.catalog(host.api(), host.profile());
                host.main().post(() -> {
                    if (!host.current(request)) return;
                    // Quiet, not an error; the cached catalog is kept for when the TV is back.
                    if (catalog.isEmpty()) { reading.setText("The TV is off. Its apps show here when it is on."); return; }
                    host.catalog(catalog);
                    open(choosing, null);
                });
            } catch (Exception e) {
                host.main().post(() -> { if (host.current(request)) host.screen().note(host.error(e), true); });
            }
        });
    }

    private static List<Apps.App> launchable(List<Apps.App> catalog) {
        List<Apps.App> out = new ArrayList<>();
        for (Apps.App a : catalog) if (Apps.launchable(a.id)) out.add(a);
        return out;
    }
}
