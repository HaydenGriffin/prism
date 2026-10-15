package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's rows, in order. Favourites, Recently used and TV are always there once the TV is set up, so All apps
 * (the last tile of Recently used) never moves between rows; only the fix-this and Continue rows come and go, above
 * them. Setup is the header's icon button. No Android types.
 */
final class LauncherLayout {
    static final int COLUMNS = 4;

    enum Row { ATTENTION, RESUME, FAVOURITES, RECENT, TV }

    private LauncherLayout() {}

    static List<Row> rows(boolean ready, boolean needsAction, boolean resume) {
        List<Row> rows = new ArrayList<>();
        if (needsAction) rows.add(Row.ATTENTION);
        if (!ready) return rows;
        if (resume) rows.add(Row.RESUME);
        rows.add(Row.FAVOURITES);
        rows.add(Row.RECENT);
        rows.add(Row.TV);
        return rows;
    }

    /** Columns between the recent apps and All apps: the quiet empty line, or spacers. */
    static int recentGap(int apps) {
        return COLUMNS - 1 - Math.min(apps, COLUMNS - 1);
    }
}
