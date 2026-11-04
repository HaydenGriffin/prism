package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launcher logic with no Android types: favourites, recently used, Quick TV settings steps, the live line. */
public final class UiTests {
    private static int assertions;
    private static final BoxName BOX = BoxName.of("SHIELD", "SHIELD Android TV");

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        favourites();
        catalog();
        recent();
        resume();
        layout();
        themes();
        settings();
        input();
        status();
        System.out.println(assertions + " UI assertions passed");
    }

    private static Apps.App app(String id, String title) { return new Apps.App(id, title, null, null); }

    private static void favourites() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("bbc.iplayer.3.0", "BBC iPlayer");
        stored.put("com.webos.app.hdmi1", "PS5");
        stored.put("com.palm.app.settings", "Settings");
        Apps.Favourites f = new Apps.Favourites(stored);
        check(f.stored().keySet().equals(new java.util.HashSet<>(Arrays.asList("bbc.iplayer.3.0"))),
            "inputs and LG Settings never survive as favourites");
        check(f.toggle("com.fvp.itv", "ITVX") && f.toggle("demand5", "5"), "favourites are added");
        check(new ArrayList<>(f.stored().keySet()).equals(Arrays.asList("bbc.iplayer.3.0", "com.fvp.itv", "demand5")),
            "favourites keep the order chosen, with no limit of three");
        check(!f.toggle("com.fvp.itv", "ITVX") && !f.contains("com.fvp.itv"), "choosing again removes it");
        check(!f.toggle("com.webos.app.hdmi3", "Shield"), "an input cannot be added");
        List<Apps.App> catalog = Arrays.asList(new Apps.App("bbc.iplayer.3.0", "BBC iPlayer", "http://tv/icons/i.png", "#101010"));
        List<Apps.App> resolved = f.resolve(catalog);
        check(resolved.get(0).icon != null && resolved.get(1).title.equals("5"),
            "favourites take the catalog's icon, and keep their own title when the app is gone");
    }

    private static void catalog() {
        check(Apps.catalog("unavailable", false) == Apps.Catalog.TV_OFF, "unavailable select without ids: the TV is off, not old Glasshouse");
        check(Apps.catalog("unavailable", true) == Apps.Catalog.TV_OFF, "unavailable select: the TV is off even with stale ids");
        check(Apps.catalog("unknown", false) == Apps.Catalog.TV_OFF, "unknown select without ids: the TV is off");
        check(Apps.catalog("", false) == Apps.Catalog.TV_OFF, "no state: the TV is off");
        check(Apps.catalog("unknown", true) == Apps.Catalog.READY, "no current option still has its catalog");
        check(Apps.catalog("BBC iPlayer", true) == Apps.Catalog.READY, "on with ids: ready");
        check(Apps.catalog("BBC iPlayer", false) == Apps.Catalog.OLD_GLASSHOUSE, "on without ids: Glasshouse too old");
    }

    private static void recent() {
        List<Apps.App> catalog = Arrays.asList(app("bbc.iplayer.3.0", "BBC iPlayer"), app("com.fvp.itv", "ITVX"),
            app("demand5", "5"), app("bbc.sounds.1.0", "BBC Sounds"), app("com.palm.app.settings", "Settings"));
        Apps.Favourites f = new Apps.Favourites(new LinkedHashMap<>());
        f.toggle("bbc.iplayer.3.0", "BBC iPlayer");
        List<String> history = Arrays.asList("BBC Sounds", "Settings", "SHIELD Game Console (HDMI3)", "5", "Off",
            "BBC iPlayer", "com.fvp.itv", "5");
        List<Apps.App> recent = Apps.recent(history, catalog, f);
        List<String> ids = new ArrayList<>();
        for (Apps.App a : recent) ids.add(a.id);
        check(ids.equals(Arrays.asList("demand5", "com.fvp.itv", "bbc.sounds.1.0")),
            "recent: newest first, once each, by title or id; favourites, Settings and inputs left out: " + ids);
        check(Apps.recent(new ArrayList<>(), catalog, f).isEmpty(), "no history, no recent row");
        // 1 Jan 2026 00:00 UTC; HA answers one day from the start unless end_time is given.
        String path = Apps.historyPath("sensor.tv_active_app", 1767225600000L, 14);
        check(path.startsWith("history/period/2025-12-18T00:00:00Z?end_time=2026-01-01T00:00:00Z&"),
            "history covers the whole fortnight up to now: " + path);
        check(path.contains("filter_entity_id=sensor.tv_active_app") && path.contains("minimal_response"), "history filters");
        check(app("x", " itvx").initial().equals("I") && app("x", "").initial().equals("?"), "lettered tile initial");
    }

    private static void resume() {
        List<Apps.App> catalog = Arrays.asList(app("bbc.iplayer.3.0", "BBC iPlayer"), app("com.fvp.itv", "ITVX"),
            app("demand5", "5"), app("com.palm.app.settings", "Settings"));
        List<String> history = Arrays.asList("ITVX", "5", "BBC iPlayer", "SHIELD Game Console (HDMI3)", "Settings", "Off");
        Apps.App last = Apps.resume(history, catalog);
        check(last != null && last.id.equals("bbc.iplayer.3.0"),
            "Continue: the newest LG app, past inputs, Settings and the TV off, even a favourite");
        check(Apps.resume(Arrays.asList("SHIELD Game Console (HDMI3)", "Off"), catalog) == null, "no LG app in history: no tile");
        check(Apps.resume(new ArrayList<>(), catalog) == null, "no history: no tile");
        Apps.Favourites none = new Apps.Favourites(new LinkedHashMap<>());
        List<String> ids = new ArrayList<>();
        for (Apps.App a : Apps.recent(history, catalog, none, last.id)) ids.add(a.id);
        check(ids.equals(Arrays.asList("demand5", "com.fvp.itv")), "Recently used leaves out the Continue app: " + ids);
        check(Apps.recent(Arrays.asList("ITVX", "5"), catalog, none, null, 1).size() == 1, "the launcher sets its own limit");
    }

    private static void layout() {
        check(LauncherLayout.rows(true, false).equals(Arrays.asList(LauncherLayout.Row.DECK, LauncherLayout.Row.DOCK)),
            "ready: the deck and the dock");
        check(LauncherLayout.rows(true, true).equals(Arrays.asList(LauncherLayout.Row.ATTENTION, LauncherLayout.Row.DECK, LauncherLayout.Row.DOCK)),
            "the fix-this row comes and goes above them");
        check(LauncherLayout.rows(false, true).equals(Arrays.asList(LauncherLayout.Row.ATTENTION)),
            "before setup only the fix-this row");
        check(LauncherLayout.height(false) <= LauncherLayout.SCREEN && LauncherLayout.height(true) <= LauncherLayout.SCREEN,
            "the launcher fits the screen, fix-this row or not: " + LauncherLayout.height(false) + ", " + LauncherLayout.height(true));
        check(LauncherLayout.tileHeight(true) >= 120, "a grid tile still holds its art and two lines beside the fix-this row");
        check(LauncherLayout.GRID_APPS == 7, "All apps keeps the grid's eighth place");

        List<Apps.App> favourites = Arrays.asList(app("a", "A"), app("b", "B"));
        List<Apps.App> recent = Arrays.asList(app("c", "C"), app("d", "D"));
        LauncherLayout.Deck both = LauncherLayout.deck(app("c", "C"), favourites, recent);
        check(both.continues && both.hero.id.equals("c") && titles(both.grid).equals("A B D"),
            "Continue is the big tile; favourites come first; nothing shows twice: " + titles(both.grid));
        LauncherLayout.Deck favourite = LauncherLayout.deck(app("a", "A"), favourites, recent);
        check(favourite.hero.id.equals("a") && titles(favourite.grid).equals("B C D"), "a favourite on Continue leaves the grid");
        LauncherLayout.Deck noResume = LauncherLayout.deck(null, favourites, recent);
        check(!noResume.continues && noResume.hero.id.equals("a") && titles(noResume.grid).equals("B C D"),
            "with nothing to continue, the first favourite takes the big tile");
        LauncherLayout.Deck onlyRecent = LauncherLayout.deck(null, new ArrayList<>(), recent);
        check(!onlyRecent.continues && onlyRecent.hero.id.equals("c"), "or the newest recent app");
        LauncherLayout.Deck empty = LauncherLayout.deck(null, new ArrayList<>(), new ArrayList<>());
        check(empty.hero == null && empty.grid.isEmpty(), "with no apps at all the big tile offers to choose favourites");
        List<Apps.App> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(app("f" + i, "F" + i));
        LauncherLayout.Deck full = LauncherLayout.deck(null, many, recent);
        check(full.grid.size() == LauncherLayout.GRID_APPS && full.grid.get(0).id.equals("f1"),
            "favourites past the grid's room wait in All apps");
    }

    private static String titles(List<Apps.App> apps) {
        StringBuilder out = new StringBuilder();
        for (Apps.App a : apps) out.append(out.length() == 0 ? "" : " ").append(a.title);
        return out.toString();
    }

    private static void themes() {
        check(Theme.byId("nonsense") == Theme.REFRACT && Theme.byId("dusk") == Theme.DUSK, "unknown ids read as the default");
        Theme walked = Theme.REFRACT;
        for (int i = 0; i < Theme.ALL.size(); i++) walked = walked.next();
        check(walked == Theme.REFRACT, "the Setup row goes round every theme");
        check(Theme.resolve("daylight", false, true) == Theme.DAYLIGHT, "the sun changes nothing unless asked");
        check(Theme.resolve("daylight", true, false) == Theme.DAYLIGHT, "by day the chosen theme stays");
        check(Theme.resolve("daylight", true, true) == Theme.DUSK, "after sunset, Dusk");
        // Every colour an LG tile might have, including the brightest: the words stay readable at the wash's peak.
        int[] colours = { 0xFFFFFF, 0x000000, 0x808080, 0xD6E04A, 0xA5E27C, 0xFFD400, 0x00FFFF, 0xD0085A, 0xFF0000, 0x0000FF, 0x00FF00, 0xFF9D5C };
        for (Theme t : Theme.ALL) {
            int top = t.washes ? Theme.over(Theme.wash(0xFFD400), t.groundTop) : t.groundTop;
            check(Theme.contrast(t.text, Theme.over(t.panel, t.groundTop)) >= 4.5, t.name + ": words on a tile");
            check(Theme.contrast(t.muted, Theme.over(t.panel, t.groundBottom)) >= 4.5, t.name + ": muted words on a tile");
            check(Theme.contrast(t.text, t.sheet) >= 4.5 && Theme.contrast(t.muted, t.sheet) >= 4.5, t.name + ": words on the sheet");
            check(Theme.contrast(t.text, Theme.over(t.panel, t.sheet)) >= 4.5 && Theme.contrast(t.muted, Theme.over(t.panel, t.sheet)) >= 4.5,
                t.name + ": words on the sheet's rows");
            check(Theme.contrast(t.muted, t.groundBottom) >= 4.5, t.name + ": muted words on the ground");
            check(Theme.contrast(t.error, t.groundTop) >= 4.5 && Theme.contrast(t.warn, t.groundTop) >= 4.5, t.name + ": warnings on the ground");
            check(Theme.contrast(t.accent, t.groundBottom) >= 3 && Theme.contrast(t.accent, top) >= 3, t.name + ": the focus ring shows");
            check(Theme.contrast(t.accentInk, t.accent) >= 3, t.name + ": a star on the accent");
            check(Theme.contrast(t.sky, t.groundBottom) >= 3 && Theme.contrast(t.amber, t.groundBottom) >= 3, t.name + ": the beam's ends show");
        }
        for (int rgb : colours) {
            int wash = Theme.wash(rgb);
            int ground = Theme.over(wash, Theme.REFRACT.groundTop);
            check(Theme.contrast(Theme.REFRACT.muted, Theme.over(Theme.REFRACT.panel, ground)) >= 4.5,
                "muted words at the peak of the wash for " + Integer.toHexString(rgb));
            check(Theme.contrast(Theme.REFRACT.text, ground) >= 4.5, "words on the wash for " + Integer.toHexString(rgb));
        }
        check(Theme.wash(0xFFFFFF) == Theme.wash(0x000000) && Theme.wash(0xFFFFFF) == Theme.wash(0x808080),
            "white, black and grey share the quiet wash rather than greying the ground");
        check((Theme.wash(0xD0085A) & 0xFFFFFF) != (Theme.wash(0x0000FF) & 0xFFFFFF), "colours keep their own hue");
    }

    private static void input() {
        TvSettings.Row row = TvSettings.row("input_source");
        check(row != null && row.label.equals("Input") && TvSettings.ROWS.get(0) == row, "Input is the first quick setting");
        TvSettings.Value v = new TvSettings.Value(row, "HDMI 3", Arrays.asList("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4", "Live TV"),
            0, 0).named("HDMI 3", "SHIELD");
        check(v.text().equals("SHIELD") && !v.changed(), "the box's own input reads its name");
        check(v.move(1).equals("HDMI 4") && v.text().equals("HDMI 4") && v.changed(), "Right steps to the next input");
        v.move(-1);
        check(!v.changed() && v.text().equals("SHIELD"), "back to the box: nothing to send");
        check(v.move(-1).equals("HDMI 2"), "Left steps back; the option, not the label, is what gets sent");
    }

    private static void settings() {
        List<String> modes = Arrays.asList("Vivid", "Standard", "Eco");
        check(TvSettings.step(modes, "Standard", 1).equals("Eco") && TvSettings.step(modes, "Eco", 1).equals("Eco"),
            "Right steps and stops at the end");
        check(TvSettings.next(modes, "Eco").equals("Vivid"), "OK wraps");
        check(TvSettings.choices(Arrays.asList("auto", "max", "screen_off")).equals(Arrays.asList("auto", "max")),
            "energy saving never offers screen off");
        check(TvSettings.stepNumber(82, 1, 0, 100) == 85 && TvSettings.stepNumber(98, 1, 0, 100) == 100,
            "brightness steps by five within its range");
        TvSettings.Value brightness = new TvSettings.Value(TvSettings.row("oled_light"), "80.0", new ArrayList<>(), 0, 100);
        check(!brightness.changed() && brightness.text().equals("80"), "HA's 80.0 is 80, not a change");
        brightness.move(-1);
        check(brightness.changed() && brightness.shown.equals("75"), "a step is a change waiting to be sent");
        check(TvSettings.row("sound_output") == null, "sound output is never a quick setting");
        check(TvSettings.display("med").equals("Medium"), "energy-saving values read as words");
    }

    private static void status() {
        Status before = Status.starting();
        check(!before.needsAction && before.tone == Status.Tone.QUIET && !before.text.contains("setup"),
            "before the saved setup is read: neutral, no 'Finish setup' flash");
        RemoteState lg = new RemoteState(RemoteState.Mode.LG, "BBC iPlayer");
        check(Status.of(true, true, true, lg, BOX).text.equals("On the LG · BBC iPlayer")
            && Status.of(true, true, true, lg, BOX).tone == Status.Tone.SKY, "LG has the remote: sky, with the app");
        check(Status.of(false, true, true, lg, BOX).needsAction, "unfinished setup asks for action whatever the service says");
        check(Status.of(true, false, true, lg, BOX).needsAction, "following off asks to turn it on");
        check(Status.of(true, true, false, lg, BOX).tone == Status.Tone.WARN, "no permission is a warning");
        Status starting = Status.of(true, true, true, new RemoteState(RemoteState.Mode.OFF, ""), BOX);
        check(!starting.needsAction && starting.text.startsWith("Connecting"),
            "a service still starting up shows Connecting, not a false 'turn on' prompt");
        Status failing = Status.of(true, true, true, new RemoteState(RemoteState.Mode.ERROR, "Home Assistant refused the token"), BOX);
        check(failing.tone == Status.Tone.ALERT && failing.text.equals("Home Assistant refused the token"),
            "errors say the failing step");
    }
}
