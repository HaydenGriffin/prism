package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launcher logic with no Android types: favourites, recently used, Quick TV settings steps, the live line. */
public final class UiTests {
    private static int assertions;

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
    }

    private static void layout() {
        check(LauncherLayout.rows(true, false, false).equals(Arrays.asList(
            LauncherLayout.Row.FAVOURITES, LauncherLayout.Row.RECENT, LauncherLayout.Row.TV)),
            "ready, nothing recent: Recently used still has its row");
        check(LauncherLayout.rows(true, true, true).equals(Arrays.asList(LauncherLayout.Row.ATTENTION,
            LauncherLayout.Row.RESUME, LauncherLayout.Row.FAVOURITES, LauncherLayout.Row.RECENT, LauncherLayout.Row.TV)),
            "attention and Continue come and go above the stable rows");
        check(LauncherLayout.rows(false, true, true).equals(Arrays.asList(LauncherLayout.Row.ATTENTION)),
            "before setup only the fix-this row (Setup is in the header)");
        for (int apps = 0; apps <= Apps.RECENT_LIMIT; apps++) {
            check(apps + LauncherLayout.recentGap(apps) + 1 == LauncherLayout.COLUMNS,
                "All apps closes the Recently used row with " + apps + " apps");
        }
    }

    private static void input() {
        TvSettings.Row row = TvSettings.row("input_source");
        check(row != null && row.label.equals("Input") && TvSettings.ROWS.get(0) == row, "Input is the first quick setting");
        TvSettings.Value v = new TvSettings.Value(row, "HDMI 3", Arrays.asList("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4", "Live TV"),
            0, 0).named("HDMI 3", "Shield");
        check(v.text().equals("Shield") && !v.changed(), "the Shield's own input reads Shield");
        check(v.move(1).equals("HDMI 4") && v.text().equals("HDMI 4") && v.changed(), "Right steps to the next input");
        v.move(-1);
        check(!v.changed() && v.text().equals("Shield"), "back to the Shield: nothing to send");
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
        check(Status.of(true, true, true, lg).text.equals("On the LG · BBC iPlayer")
            && Status.of(true, true, true, lg).tone == Status.Tone.SKY, "LG has the remote: sky, with the app");
        check(Status.of(false, true, true, lg).needsAction, "unfinished setup asks for action whatever the service says");
        check(Status.of(true, false, true, lg).needsAction, "following off asks to turn it on");
        check(Status.of(true, true, false, lg).tone == Status.Tone.WARN, "no permission is a warning");
        Status starting = Status.of(true, true, true, new RemoteState(RemoteState.Mode.OFF, ""));
        check(!starting.needsAction && starting.text.startsWith("Connecting"),
            "a service still starting up shows Connecting, not a false 'turn on' prompt");
        Status failing = Status.of(true, true, true, new RemoteState(RemoteState.Mode.ERROR, "Home Assistant refused the token"));
        check(failing.tone == Status.Tone.ALERT && failing.text.equals("Home Assistant refused the token"),
            "errors say the failing step");
    }
}
