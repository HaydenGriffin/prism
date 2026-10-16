package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The home-screen rows with no Android types: what each row holds, in what order, and the provider diff. */
public final class HomeRowsTests {
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        appsRow();
        lastApp();
        commandsRow();
        inputs();
        lgApp();
        diff();
        throttle();
        System.out.println(assertions + " home-row assertions passed");
    }

    private static Apps.App app(String id, String title) { return new Apps.App(id, title, null, null); }

    private static final List<Apps.App> CATALOG = Arrays.asList(app("bbc.iplayer.3.0", "BBC iPlayer"),
        app("com.fvp.itv", "ITVX"), app("demand5", "5"), app("netflix", "Netflix"), app("com.palm.app.settings", "Settings"));

    private static List<String> keys(List<HomeRows.Item> items) {
        List<String> out = new ArrayList<>();
        for (HomeRows.Item i : items) out.add(i.key);
        return out;
    }

    private static void appsRow() {
        Map<String, String> favourites = new LinkedHashMap<>();
        favourites.put("netflix", "Netflix");
        favourites.put("bbc.iplayer.3.0", "BBC iPlayer");
        favourites.put("gone.app", "Old app");
        List<HomeRows.Item> row = HomeRows.apps("com.fvp.itv", CATALOG, favourites);
        check(keys(row).equals(Arrays.asList("continue", "app:netflix", "app:bbc.iplayer.3.0", "app:gone.app")),
            "Continue first, then favourites in their saved order: " + keys(row));
        check(row.get(0).title.equals("Continue on the LG · ITVX") && row.get(0).target.equals("app:com.fvp.itv")
            && row.get(0).resume, "the Continue card opens the last LG app");
        check(row.get(1).target.equals("app:netflix") && !row.get(1).resume, "a favourite opens its own app");
        check(row.get(3).title.equals("Old app") && row.get(3).app.icon == null, "a favourite the TV no longer lists keeps its title");
        check(keys(HomeRows.apps(null, CATALOG, favourites)).get(0).equals("app:netflix"), "no last app, no Continue card");
        check(keys(HomeRows.apps("unknown.app", CATALOG, favourites)).get(0).equals("app:netflix"),
            "a last app the catalog doesn't list gets no card");
        check(keys(HomeRows.apps("com.palm.app.settings", CATALOG, favourites)).get(0).equals("app:netflix"),
            "LG Settings is never the Continue card (it is a command)");
        check(keys(HomeRows.apps("netflix", CATALOG, favourites)).equals(
            Arrays.asList("continue", "app:netflix", "app:bbc.iplayer.3.0", "app:gone.app")),
            "a favourite can also be the Continue card");
        check(HomeRows.apps(null, CATALOG, new LinkedHashMap<>()).isEmpty(), "nothing chosen, nothing used: an empty row");
    }

    private static void lastApp() {
        List<String> history = Arrays.asList("ITVX", "SHIELD (HDMI 3)", "netflix", "Settings", "HDMI 3", "Off");
        check("com.fvp.itv".equals(HomeRows.lastApp("com.fvp.itv", history, CATALOG)), "the service's last app wins");
        check("netflix".equals(HomeRows.lastApp(null, history, CATALOG)),
            "else the newest launchable app in HA history, favourites included: " + HomeRows.lastApp(null, history, CATALOG));
        check("netflix".equals(HomeRows.lastApp("removed.app", history, CATALOG)), "an observed app the TV dropped falls back to history");
        check(HomeRows.lastApp(null, new ArrayList<>(), CATALOG) == null, "no history, no last app");
        check(HomeRows.lastApp(null, history, new ArrayList<>()) == null, "no catalog, no last app");
    }

    private static final java.util.Map<String, String> NONE = java.util.Collections.emptyMap();

    private static void commandsRow() {
        List<String> options = Arrays.asList("HDMI 1", "HDMI 2", "HDMI 3", "PS5", "Live TV");
        List<HomeRows.Item> row = HomeRows.commands(true, options, "HDMI 3", NONE);
        check(keys(row).equals(Arrays.asList("cmd:home", "cmd:settings", "cmd:quick", "input:HDMI 1", "input:HDMI 2",
            "input:PS5", "input:Live TV")), "LG Home, Settings, Quick TV settings, then inputs but the Shield's: " + keys(row));
        check(row.get(5).title.equals("Switch to PS5") && row.get(5).target.equals("input:PS5"), "an input card switches to it");
        check(row.get(0).target.equals("lg_home") && row.get(1).target.equals("lg_settings") && row.get(2).target.equals("quick_settings"),
            "commands use the existing shortcut targets");
        check(keys(HomeRows.commands(false, options, "HDMI 3", NONE)).get(0).equals("cmd:settings"), "no LG Home card while its button is off");
        check(HomeRows.commands(true, new ArrayList<>(), "HDMI 3", NONE).size() == 3, "no input list: the three commands only");

        List<String> tvHistory = Arrays.asList("BBC iPlayer", "PS5 Game Console (HDMI1)", "SHIELD Game Console (HDMI3)",
            "Off", "Old Name (HDMI1)", "PS5 Game Console (HDMI1)", "unavailable");
        java.util.Map<String, String> names = HomeRows.inputNames(tvHistory);
        check("PS5 Game Console".equals(names.get("hdmi 1")) && names.get("hdmi 2") == null && !names.containsKey("live tv"),
            "names from the history, newest wins: " + names);
        List<HomeRows.Item> named = HomeRows.commands(true, Arrays.asList("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4", "Live TV"),
            "HDMI 3", names);
        check(keys(named).equals(Arrays.asList("cmd:home", "cmd:settings", "cmd:quick", "input:HDMI 1")),
            "only inputs the TV has shown lately, never the Shield's: " + keys(named));
        check(named.get(3).title.equals("Switch to PS5 Game Console") && named.get(3).target.equals("input:HDMI 1"),
            "the TV's own name on the card, the select option in the command");
        check(HomeRows.inputNames(Arrays.asList("Live TV")).containsKey("live tv"), "Live TV counts once seen");
    }

    private static void inputs() {
        check(HomeRows.inputs(Arrays.asList("HDMI 1", " hdmi 3 ", "", null, "PS5", "ps5", "unavailable"), "HDMI 3")
            .equals(Arrays.asList("HDMI 1", "PS5")), "Shield's input (any case), blanks and repeats left out");
        check(HomeRows.inputs(Arrays.asList("HDMI 1", "HDMI 2"), "").equals(Arrays.asList("HDMI 1", "HDMI 2")),
            "no Shield input chosen: every input");
    }

    private static void lgApp() {
        check(HomeRows.lgApp("netflix", "com.webos.app.hdmi3"), "an LG app counts");
        check(!HomeRows.lgApp("com.webos.app.hdmi3", "com.webos.app.hdmi3"), "the Shield's input does not");
        check(!HomeRows.lgApp("com.webos.app.hdmi1", "com.webos.app.hdmi3"), "another input does not");
        check(!HomeRows.lgApp("com.webos.app.home", "") && !HomeRows.lgApp("com.palm.app.settings", "")
            && !HomeRows.lgApp("com.webos.app.settings", ""), "LG Home and Settings do not");
        check(!HomeRows.lgApp("com.webos.systemui.notification", "") && !HomeRows.lgApp("", "") && !HomeRows.lgApp(null, ""),
            "popups and nothing do not");
    }

    private static Map<String, String> wanted(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    private static void diff() {
        Map<String, String> want = wanted("continue", "c1", "app:a", "a1", "app:b", "b1");
        HomeRows.Changes first = HomeRows.diff(new ArrayList<>(), want, true);
        check(first.insert.equals(Arrays.asList("continue", "app:a", "app:b")) && first.update.isEmpty() && first.delete.isEmpty(),
            "first run inserts every card in order");
        List<HomeRows.Row> now = Arrays.asList(new HomeRows.Row(10, "continue", "c1"), new HomeRows.Row(11, "app:a", "a1"),
            new HomeRows.Row(12, "app:b", "b1"));
        check(HomeRows.diff(now, want, true).empty(), "a second run with nothing new writes nothing (idempotent)");

        HomeRows.Changes renamed = HomeRows.diff(now, wanted("continue", "c2", "app:a", "a1", "app:b", "b1"), true);
        check(renamed.update.equals(Collections.singletonMap(10L, "continue")) && renamed.insert.isEmpty() && renamed.delete.isEmpty(),
            "a new last app updates the Continue card in place");

        HomeRows.Changes removed = HomeRows.diff(now, wanted("continue", "c1", "app:b", "b1"), true);
        check(removed.delete.equals(Arrays.asList(11L)) && removed.insert.isEmpty() && removed.update.isEmpty(),
            "a favourite removed deletes only its card");

        HomeRows.Changes added = HomeRows.diff(now, wanted("continue", "c1", "app:a", "a1", "app:b", "b1", "app:c", "c1"), true);
        check(added.insert.equals(Arrays.asList("app:c")) && added.delete.isEmpty(), "a favourite added at the end is one insert");

        HomeRows.Changes reordered = HomeRows.diff(now, wanted("continue", "c1", "app:b", "b1", "app:a", "a1"), true);
        check(reordered.delete.equals(Arrays.asList(10L, 11L, 12L))
            && reordered.insert.equals(Arrays.asList("continue", "app:b", "app:a")), "a new order rebuilds the row");

        List<HomeRows.Row> noContinue = Arrays.asList(new HomeRows.Row(11, "app:a", "a1"), new HomeRows.Row(12, "app:b", "b1"));
        HomeRows.Changes front = HomeRows.diff(noContinue, want, true);
        check(front.delete.equals(Arrays.asList(11L, 12L)) && front.insert.equals(Arrays.asList("continue", "app:a", "app:b")),
            "a Continue card arriving in front rebuilds so it is first by row id too");

        List<HomeRows.Row> messy = Arrays.asList(new HomeRows.Row(12, "app:a", "a1"), new HomeRows.Row(5, "continue", "c1"),
            new HomeRows.Row(13, "app:a", "a1"), new HomeRows.Row(14, null, "x"), new HomeRows.Row(15, "app:b", "b1"));
        HomeRows.Changes cleaned = HomeRows.diff(messy, want, true);
        check(cleaned.delete.equals(Arrays.asList(13L, 14L)) && cleaned.insert.isEmpty() && cleaned.update.isEmpty(),
            "duplicates and keyless rows are deleted, the first of each key kept: " + cleaned.delete);

        List<HomeRows.Row> channels = Arrays.asList(new HomeRows.Row(7, "lg_commands", "LG commands"),
            new HomeRows.Row(9, "lg_apps", "LG apps"));
        check(HomeRows.diff(channels, wanted("lg_apps", "LG apps", "lg_commands", "LG commands"), false).empty(),
            "channels are never re-made for their order (the launcher's settings for them would be lost)");
        HomeRows.Changes commandsOff = HomeRows.diff(channels, wanted("lg_apps", "LG apps"), false);
        check(commandsOff.delete.equals(Arrays.asList(7L)) && commandsOff.insert.isEmpty(), "the commands toggle off deletes its channel only");

        HomeRows.Changes off = HomeRows.diff(now, new LinkedHashMap<>(), true);
        check(off.delete.equals(Arrays.asList(10L, 11L, 12L)) && off.insert.isEmpty(), "nothing wanted deletes everything");
    }

    private static void throttle() {
        HomeRows.Throttle t = new HomeRows.Throttle(60_000);
        check(t.observe("netflix", 1_000) == 0, "the first LG app syncs at once");
        check(t.observe("bbc", 2_000) == -1, "a second app while one is pending waits for it");
        t.running(3_000);
        check("bbc".equals(t.seen()), "the pending sync sees the newest app");
        check(t.observe("bbc", 4_000) == -1, "the same app again needs no sync");
        check(t.observe("itv", 10_000) == 53_000, "a new app within the minute waits for the rest of it");
        check(t.observe("netflix", 11_000) == -1, "never queued twice");
        t.running(63_000);
        check(t.observe("bbc", 200_000) == 0, "after the gap, at once");
        check(t.observe(null, 200_001) == -1, "nothing seen, nothing to do");
    }
}
