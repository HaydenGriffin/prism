package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's deck: one big tile, a grid of apps ending in All apps, a dock of the TV's own places and one status
 * line, all on one screen with nothing to scroll. Sizes are dp on the Shield's 960×540. No Android types.
 */
final class LauncherLayout {
    static final int COLUMNS = 4, GRID_ROWS = 2;
    /** All apps always takes the grid's last place, so it never moves; the apps get the others. */
    static final int GRID_APPS = COLUMNS * GRID_ROWS - 1;
    static final int SCREEN = 540, EDGE = 28, GAP = 12;
    static final int ATTENTION = 52, DOCK = 62, STATUS = 30, FULL_DECK = 366;

    enum Row { ATTENTION, DECK, DOCK }

    private LauncherLayout() {}

    /** What the launcher shows. The fix-this row comes and goes above the deck, which gives up the room for it. */
    static List<Row> rows(boolean ready, boolean needsAction) {
        List<Row> rows = new ArrayList<>();
        if (needsAction) rows.add(Row.ATTENTION);
        if (!ready) return rows;
        rows.add(Row.DECK);
        rows.add(Row.DOCK);
        return rows;
    }

    /** The big tile and the grid together, in dp. */
    static int deckHeight(boolean attention) {
        return attention ? FULL_DECK - ATTENTION - GAP : FULL_DECK;
    }

    /** One grid tile, in dp. */
    static int tileHeight(boolean attention) {
        return (deckHeight(attention) - GAP * (GRID_ROWS - 1)) / GRID_ROWS;
    }

    /** The launcher's whole height, in dp: never more than SCREEN. */
    static int height(boolean attention) {
        return EDGE + (attention ? ATTENTION + GAP : 0) + deckHeight(attention) + GAP + DOCK + GAP + STATUS + EDGE;
    }

    /** Which app goes where. */
    static final class Deck {
        /** The big tile's app, or null when there is nothing to put there yet. */
        final Apps.App hero;
        /** Whether the big tile continues the last app, rather than offering a favourite or a recent one. */
        final boolean continues;
        /** Up to GRID_APPS apps in order: favourites first, then recently used. */
        final List<Apps.App> grid;

        private Deck(Apps.App hero, boolean continues, List<Apps.App> grid) {
            this.hero = hero;
            this.continues = continues;
            this.grid = grid;
        }
    }

    /** Favourites past the grid's room stay one press away in All apps. */
    static Deck deck(Apps.App resume, List<Apps.App> favourites, List<Apps.App> recent) {
        List<Apps.App> order = new ArrayList<>(favourites);
        order.addAll(recent);
        Apps.App hero = resume != null ? resume : order.isEmpty() ? null : order.get(0);
        List<Apps.App> grid = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        if (hero != null) placed.add(hero.id);
        for (Apps.App app : order) {
            if (grid.size() == GRID_APPS) break;
            if (placed.contains(app.id)) continue;
            placed.add(app.id);
            grid.add(app);
        }
        return new Deck(hero, resume != null, grid);
    }
}
