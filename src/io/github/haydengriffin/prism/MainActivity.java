package io.github.haydengriffin.prism;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** The launcher and its pages. Routing lives in AutomaticService and works whether or not this is open. */
public final class MainActivity extends Activity implements Host {
    /** Opens a page directly: `--es page quick_settings` (from ShortcutActivity or a button mapper). */
    static final String EXTRA_PAGE = "page";
    /** How long a slow launch may take to show on the TV before it counts as failed. */
    private static final long ARRIVAL_WAIT_MS = 10_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Browse reads (catalog, history, settings values, setup lookups). */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** What the user asked the TV to do: never queued behind a slow read; in order among themselves. */
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private Screen screen;
    private IconCache icons;
    private Credentials credentials;
    private TvProfile profile;
    private Apps.Favourites favourites;
    private List<Apps.App> catalog = new ArrayList<>();
    private LauncherScreen launcher;
    private AppsScreen apps;
    private QuickSettingsScreen quickSettings;
    private SetupScreen setup;
    private RemotesScreen remotes;
    private MonitoringScreen monitoring;
    private Page page;
    private volatile int generation;
    private Status shown;
    /** Credentials and profile have been read once; until then the status says nothing about setup. */
    private boolean loaded;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            Status now = status();
            screen.status(now.text, now.tone);
            // The launcher's "fix this" row comes and goes with the status.
            if (page == Page.LAUNCHER && shown != null && shown.needsAction != now.needsAction) show(Page.LAUNCHER);
            shown = now;
            main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = new Screen(this);
        icons = new IconCache(this);
        launcher = new LauncherScreen(this);
        apps = new AppsScreen(this);
        quickSettings = new QuickSettingsScreen(this);
        setup = new SetupScreen(this, this);
        remotes = new RemotesScreen(this, this);
        monitoring = new MonitoringScreen(this, this);
        favourites = new Apps.Favourites(storedFavourites(this));
        catalog = storedCatalog(this);
        ChannelSync.request(this, "start");
        screen.page("Prism", "");
        work(() -> {
            Credentials c = null;
            try { c = Credentials.load(this); }
            catch (Exception e) { android.util.Log.w("Prism", "Saved connection unreadable: " + e.getClass().getSimpleName()); }
            TvProfile tv = TvProfile.load(this);
            Credentials saved = c;
            main.post(() -> {
                credentials = saved;
                profile = tv;
                loaded = true;
                show(startPage(getIntent()));
            });
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (credentials != null) show(startPage(intent));
    }

    @Override public void onResume() {
        super.onResume();
        main.post(statusTick);
        if (loaded) reloadProfile();
    }

    /** singleTask: AutomaticService may have followed the TV's registry since this instance read the profile. */
    private void reloadProfile() {
        work(() -> {
            TvProfile stored = TvProfile.load(this);
            main.post(() -> {
                if (isDestroyed() || stored == null || profile == null || stored.flat().equals(profile.flat())) return;
                profile = stored;
                if (page == Page.LAUNCHER) show(Page.LAUNCHER);
            });
        });
    }

    @Override public void onPause() {
        main.removeCallbacks(statusTick);
        if (page == Page.QUICK_SETTINGS) quickSettings.flush();
        super.onPause();
    }

    @Override public void onDestroy() {
        generation++;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        commands.shutdownNow();
        icons.close();
        super.onDestroy();
    }

    private Page startPage(Intent intent) {
        if (credentials == null) return Page.CONNECTION;
        if (profile == null) return Page.CHOOSE_TV;
        String wanted = intent == null ? null : intent.getStringExtra(EXTRA_PAGE);
        if ("quick_settings".equals(wanted)) return Page.QUICK_SETTINGS;
        if ("apps".equals(wanted)) return Page.APPS;
        return Page.LAUNCHER;
    }

    @Override public void show(Page next) {
        if (page == Page.QUICK_SETTINGS && next != Page.QUICK_SETTINGS) quickSettings.flush();
        generation++;
        page = next;
        secure(next == Page.CONNECTION);
        switch (next) {
            case LAUNCHER: launcher.open(status(), null); break;
            case APPS: apps.open(false, null); break;
            case FAVOURITES: apps.open(true, null); break;
            case QUICK_SETTINGS: quickSettings.open(); break;
            case SETUP: setup.open(null); break;
            case CONNECTION: setup.connection(); break;
            case CHOOSE_TV: setup.chooseTv(); break;
            case SHIELD_INPUT: setup.shieldInput(); break;
            case PERMISSION: setup.permission(); break;
            case REMOTES: remotes.open(null); break;
            case MONITORING: monitoring.open(); break;
        }
        Status now = status();
        screen.status(now.text, now.tone);
        shown = now;
    }

    @Override public void onBackPressed() {
        switch (page) {
            case LAUNCHER: super.onBackPressed(); return;
            case SHIELD_INPUT:
                if (setup.fromChooseTv()) { show(Page.CHOOSE_TV); return; }
                show(profile == null ? Page.LAUNCHER : Page.SETUP);
                return;
            case REMOTES: case MONITORING:
                show(Page.SETUP);
                return;
            case CONNECTION: case CHOOSE_TV: case PERMISSION:
                show(profile == null ? Page.LAUNCHER : Page.SETUP);
                return;
            default: show(Page.LAUNCHER);
        }
    }

    /** The token entry page blocks screenshots and the recents thumbnail; other pages don't need to. */
    private void secure(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private Status status() {
        if (!loaded) return Status.starting();
        boolean setUp = credentials != null && profile != null && !profile.shieldApp.isEmpty();
        return Status.of(setUp, AutomaticService.enabled(this), AutomaticService.live != null, RemoteState.current, box());
    }

    // Host

    @Override public Screen screen() { return screen; }
    @Override public BoxName box() { return DeviceName.read(this); }
    @Override public Handler main() { return main; }
    @Override public IconCache icons() { return icons; }

    @Override public boolean work(Runnable task) { return run(worker, task); }
    @Override public boolean command(Runnable task) { return run(commands, task); }

    private static boolean run(ExecutorService executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    @Override public int request() { return generation; }
    @Override public boolean current(int request) { return request == generation && !isDestroyed(); }

    @Override public String error(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "Home Assistant took too long to answer. Try again.";
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException)
            return "Home Assistant cannot be reached. Check the address and the network.";
        // Never show URLs, tokens or response bodies: only messages this app wrote.
        if (e instanceof HaHttp.HttpFailure || e.getClass() == java.io.IOException.class || e instanceof IllegalArgumentException)
            return e.getMessage();
        return "Something went wrong talking to Home Assistant. Try again.";
    }

    @Override public Credentials credentials() { return credentials; }
    @Override public void credentials(Credentials next) { credentials = next; }
    @Override public TvProfile profile() { return profile; }

    @Override public void profile(TvProfile next) {
        profile = next;
        if (next == null) {
            getSharedPreferences("tv", Context.MODE_PRIVATE).edit().clear().apply();
            AutomaticService.enabled(this, false);
        }
        ChannelSync.request(this, "setup");
    }

    @Override public HaHttp api() { return new HaHttp(credentials.url, credentials.token); }
    @Override public Apps.Favourites favourites() { return favourites; }

    @Override public void saveFavourites() {
        getSharedPreferences("favourites", Context.MODE_PRIVATE).edit()
            .putString("apps", new JSONObject(favourites.stored()).toString()).apply();
        ChannelSync.request(this, "favourites");
    }

    @Override public List<Apps.App> catalog() { return catalog; }

    @Override public void catalog(List<Apps.App> next) {
        catalog = next;
        JSONArray list = new JSONArray();
        try {
            for (Apps.App a : next) list.put(new JSONObject().put("id", a.id).put("title", a.title)
                .putOpt("icon", a.icon).putOpt("color", a.color));
        } catch (org.json.JSONException ignored) { /* Only strings go in. */ }
        if (list.toString().equals(prefs().getString("catalog", ""))) return;
        prefs().edit().putString("catalog", list.toString()).apply();
        ChannelSync.request(this, "catalog");
    }

    @Override public String resumeApp() { return prefs().getString("resume", null); }

    @Override public void resumeApp(String id) {
        if (id == null) prefs().edit().remove("resume").apply();
        else prefs().edit().putString("resume", id).apply();
    }

    @Override public void launch(Apps.App app) {
        screen.status("Opening " + app.title + "…", Status.Tone.SKY);
        command(() -> {
            try {
                api().select(profile.launch, titleFor(app));
                main.post(this::leaveForTv);
            } catch (java.net.SocketTimeoutException e) {
                main.post(() -> awaitTv(titleFor(app), e, SystemClock.uptimeMillis() + ARRIVAL_WAIT_MS));
            } catch (Exception e) {
                main.post(() -> screen.status(error(e), Status.Tone.ALERT));
            }
        });
    }

    @Override public void openLgHome() {
        String button = profile.buttons.get("home");
        if (button == null) {
            screen.status("LG Home is off in Home Assistant · enable its Remote Home button", Status.Tone.WARN);
            return;
        }
        screen.status("Opening LG Home…", Status.Tone.SKY);
        command(() -> {
            try {
                api().press(button);
                main.post(this::leaveForTv);
            } catch (java.net.SocketTimeoutException e) {
                main.post(() -> awaitTv("LG Home", e, SystemClock.uptimeMillis() + ARRIVAL_WAIT_MS));
            } catch (Exception e) {
                main.post(() -> screen.status(error(e), Status.Tone.ALERT));
            }
        });
    }

    /**
     * HA answered too slowly, but it usually still carries the command out (it stalls, then catches up). Keep saying
     * "Opening" and watch for the TV to show it; only call it a failure when it hasn't arrived by the deadline.
     */
    private void awaitTv(String title, Exception timeout, long deadline) {
        if (isDestroyed()) return;
        if (RemoteState.current.mode == RemoteState.Mode.LG && RemoteState.current.detail.equals(title)) {
            leaveForTv();
        } else if (SystemClock.uptimeMillis() >= deadline) {
            screen.status(error(timeout), Status.Tone.ALERT);
        } else {
            main.postDelayed(() -> awaitTv(title, timeout, deadline), 250);
        }
    }

    /** The TV now shows what was opened. Prism stays behind it unless Setup says to close. */
    private void leaveForTv() {
        boolean following = AutomaticService.enabled(this) && AutomaticService.live != null;
        switch (AfterLaunch.step(following, closesAfterOpening(this))) {
            case CLOSE:
                finish();
                break;
            case STAY:
                screen.status("Opened on the LG · the remote follows it there", Status.Tone.SKY);
                break;
            default:
                screen.status("Opened on the LG · turn on Follow the TV in Setup for the remote to follow", Status.Tone.WARN);
        }
    }

    /** Setup's "Close after opening on the LG": off unless chosen. */
    static boolean closesAfterOpening(Context c) { return launcherPrefs(c).getBoolean("close_after_opening", false); }

    static void closesAfterOpening(Context c, boolean on) { launcherPrefs(c).edit().putBoolean("close_after_opening", on).apply(); }

    private String titleFor(Apps.App app) {
        Apps.App known = Apps.find(catalog, app.id);
        return known != null ? known.title : app.title;
    }

    private SharedPreferences prefs() { return launcherPrefs(this); }

    private static SharedPreferences launcherPrefs(Context c) { return c.getSharedPreferences("launcher", Context.MODE_PRIVATE); }

    /** Favourites as saved, in the order chosen (also read by ChannelSync). */
    static Map<String, String> storedFavourites(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject j = new JSONObject(c.getSharedPreferences("favourites", Context.MODE_PRIVATE).getString("apps", "{}"));
            for (Iterator<String> i = j.keys(); i.hasNext();) {
                String id = i.next();
                out.put(id, j.getString(id));
            }
        } catch (org.json.JSONException ignored) { /* Start with none. */ }
        return out;
    }

    /** The last catalog the TV gave (also read by ChannelSync). */
    static List<Apps.App> storedCatalog(Context c) {
        List<Apps.App> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(launcherPrefs(c).getString("catalog", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                out.add(new Apps.App(a.getString("id"), a.getString("title"), a.optString("icon", null), a.optString("color", null)));
            }
        } catch (org.json.JSONException ignored) { /* Redrawn when the TV answers. */ }
        return out;
    }
}
