package io.github.haydengriffin.prism;

import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps the two Android TV home-screen rows (preview channels "LG apps" and "LG commands") in step with the
 * favourites, the last LG app and the TV's inputs. Idempotent: rows and cards are matched by INTERNAL_PROVIDER_ID,
 * so repeated runs write nothing new. Runs on its own low-priority thread; a failure is logged once and never
 * reaches the remote.
 */
final class ChannelSync {
    private static final String TAG = "Prism";
    private static final String PREFS = "channels";
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(HomeRows.APPS, "LG apps");
        NAMES.put(HomeRows.COMMANDS, "LG commands");
    }
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "lg-channels");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean queued = new AtomicBoolean();
    /** Worker thread only. */
    private static String lastFailure = "";

    private final Context context;
    private final ContentResolver resolver;
    private final ChannelArt art;
    private final Set<String> cards = new HashSet<>();

    private ChannelSync(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.art = new ChannelArt(context);
    }

    /** Queues one sync; requests made while one is waiting fold into it. Never throws. */
    static void request(Context context, String why) {
        Context app = context.getApplicationContext();
        if (!queued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                queued.set(false);
                runQuietly(app, why);
            });
        } catch (RejectedExecutionException e) {
            queued.set(false);
        }
    }

    static boolean commandsOn(Context c) { return prefs(c).getBoolean("commands", true); }

    static void commandsOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("commands", on).apply();
        request(c, "commands " + (on ? "on" : "off"));
    }

    static String lastApp(Context c) { return prefs(c).getString("last_app", null); }

    static void lastApp(Context c, String appId) { prefs(c).edit().putString("last_app", appId).apply(); }

    /** Setup's row for the commands channel. */
    static void setupRow(Screen s, Context c, Runnable reopen) {
        boolean on = commandsOn(c);
        s.listRow(R.drawable.ic_apps, "Commands row on the home screen", on ? "On" : "Off", false, () -> {
            commandsOn(c, !on);
            reopen.run();
        }).setTag("channels");
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static void runQuietly(Context context, String why) {
        try {
            new ChannelSync(context).run(why);
            lastFailure = "";
        } catch (Throwable e) { // Anything escaping this thread would take the process, and the remote, down.
            StackTraceElement[] trace = e.getStackTrace();
            String failure = e.getClass().getSimpleName() + " at " + (trace.length == 0 ? "unknown" : trace[0].toString());
            if (!failure.equals(lastFailure)) Log.w(TAG, "Channels: " + why + ": " + failure);
            lastFailure = failure;
        }
    }

    private void run(String why) throws Exception {
        try (ContentProviderClient tv = resolver.acquireContentProviderClient(TvContract.AUTHORITY)) {
            if (tv == null) throw new IllegalStateException("No TV provider");
        }
        Map<String, List<HomeRows.Item>> wanted = wanted();
        Map<String, String> channelNames = new LinkedHashMap<>();
        for (String key : wanted.keySet()) channelNames.put(key, NAMES.get(key) + "\n" + appLink());
        List<HomeRows.Row> current = channels();
        HomeRows.Changes changes = HomeRows.diff(current, channelNames, false);
        for (long id : changes.delete) {
            programs(id, Collections.<HomeRows.Item>emptyList()); // Never rely on the provider's cascade.
            resolver.delete(TvContract.buildChannelUri(id), null, null);
        }
        for (Map.Entry<Long, String> e : changes.update.entrySet()) {
            resolver.update(TvContract.buildChannelUri(e.getKey()), channel(e.getValue()), null, null);
        }
        for (String key : changes.insert) created(key, ContentUris.parseId(resolver.insert(TvContract.Channels.CONTENT_URI, channel(key))));
        StringBuilder log = new StringBuilder("Channels: " + why + ": rows " + changes);
        Map<String, Long> ids = ids(channels());
        for (Map.Entry<String, List<HomeRows.Item>> row : wanted.entrySet()) {
            Long id = ids.get(row.getKey());
            if (id == null) continue;
            log.append(", ").append(NAMES.get(row.getKey())).append(' ').append(row.getValue().size())
                .append(" (").append(programs(id, row.getValue())).append(')');
        }
        art.keepOnly(cards);
        Log.i(TAG, log.toString());
    }

    /** Each wanted channel's cards; none when the app is not set up (its rows are removed). */
    private Map<String, List<HomeRows.Item>> wanted() throws Exception {
        Map<String, List<HomeRows.Item>> out = new LinkedHashMap<>();
        Credentials credentials = Credentials.load(context); // A Keystore failure throws: rows stay as they are.
        TvProfile tv = TvProfile.load(context);
        if (credentials == null || tv == null) return out;
        HaHttp api = new HaHttp(credentials.url, credentials.token);
        List<Apps.App> catalog = catalog(api, tv);
        List<String> history = history(api, tv);
        String last = HomeRows.lastApp(lastApp(context), Collections.<String>emptyList(), catalog);
        if (last == null) last = HomeRows.lastApp(null, history, catalog);
        out.put(HomeRows.APPS, HomeRows.apps(last, catalog, MainActivity.storedFavourites(context)));
        if (commandsOn(context)) {
            out.put(HomeRows.COMMANDS, HomeRows.commands(tv.buttons.containsKey("home"), inputs(api, tv), tv.shieldOption,
                HomeRows.inputNames(history)));
        }
        return out;
    }

    /** The TV's catalog now, else (TV off, HA away) the launcher's last one. */
    private List<Apps.App> catalog(HaHttp api, TvProfile tv) {
        try {
            List<Apps.App> fresh = TvData.catalog(api, tv);
            if (!fresh.isEmpty()) return fresh;
        } catch (Exception e) {
            Log.i(TAG, "Channels: catalog from the cache (" + e.getClass().getSimpleName() + ")");
        }
        return MainActivity.storedCatalog(context);
    }

    private static List<String> history(HaHttp api, TvProfile tv) {
        try {
            return TvData.history(api, tv);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** The input select's options now (kept for later), else the last ones read. */
    private List<String> inputs(HaHttp api, TvProfile tv) {
        try {
            JSONArray options = new JSONObject(api.browse("states/" + tv.input)).getJSONObject("attributes").optJSONArray("options");
            if (options != null && options.length() > 0) {
                prefs(context).edit().putString("inputs", options.toString()).apply();
                return strings(options);
            }
        } catch (Exception e) {
            Log.i(TAG, "Channels: inputs from the cache (" + e.getClass().getSimpleName() + ")");
        }
        try {
            return strings(new JSONArray(prefs(context).getString("inputs", "[]")));
        } catch (org.json.JSONException e) {
            return Collections.emptyList();
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
        return out;
    }

    // Channels

    private ContentValues channel(String key) {
        ContentValues v = new ContentValues();
        v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, NAMES.get(key));
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, appLink());
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, key);
        return v;
    }

    /** A new channel: its logo, and for LG apps the one browsable request Android grants a package unasked. */
    private void created(String key, long id) {
        try (OutputStream out = resolver.openOutputStream(TvContract.buildChannelLogoUri(id))) {
            out.write(art.logo());
        } catch (Exception e) {
            Log.w(TAG, "Channels: logo not written (" + e.getClass().getSimpleName() + ")");
        }
        if (key.equals(HomeRows.APPS)) {
            try {
                TvContract.requestChannelBrowsable(context, id);
            } catch (Exception e) {
                Log.w(TAG, "Channels: browsable request refused (" + e.getClass().getSimpleName() + ")");
            }
        }
        Log.i(TAG, "Channels: created " + NAMES.get(key) + " (channel " + id + ")");
    }

    /** This app's channels (the provider shows an app only its own). */
    private List<HomeRows.Row> channels() {
        List<HomeRows.Row> out = new ArrayList<>();
        String[] columns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_APP_LINK_INTENT_URI};
        try (Cursor c = resolver.query(TvContract.Channels.CONTENT_URI, columns, null, null, null)) {
            while (c != null && c.moveToNext()) out.add(new HomeRows.Row(c.getLong(0), c.getString(1), c.getString(2) + "\n" + c.getString(3)));
        }
        return out;
    }

    /** First channel id per key, as the diff keeps them. */
    private static Map<String, Long> ids(List<HomeRows.Row> rows) {
        List<HomeRows.Row> byId = new ArrayList<>(rows);
        byId.sort((a, b) -> Long.compare(a.id, b.id));
        Map<String, Long> out = new LinkedHashMap<>();
        for (HomeRows.Row row : byId) if (row.key != null) out.putIfAbsent(row.key, row.id);
        return out;
    }

    // Programs

    /** Brings one channel's cards to the wanted ones; returns the changes made. */
    private HomeRows.Changes programs(long channel, List<HomeRows.Item> items) throws Exception {
        Map<String, ContentValues> values = new LinkedHashMap<>();
        Map<String, String> wanted = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            HomeRows.Item item = items.get(i);
            String card = art.card(item);
            cards.add(card);
            ContentValues v = program(channel, item, ArtProvider.uri(card).toString(), items.size() - i);
            values.put(item.key, v);
            wanted.put(item.key, signature(v.getAsString(TvContract.PreviewPrograms.COLUMN_TITLE),
                v.getAsString(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                v.getAsString(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                v.getAsString(TvContract.PreviewPrograms.COLUMN_WEIGHT)));
        }
        HomeRows.Changes changes = HomeRows.diff(programs(channel), wanted, true);
        for (long id : changes.delete) resolver.delete(TvContract.buildPreviewProgramUri(id), null, null);
        for (Map.Entry<Long, String> e : changes.update.entrySet()) {
            resolver.update(TvContract.buildPreviewProgramUri(e.getKey()), values.get(e.getValue()), null, null);
        }
        for (String key : changes.insert) resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values.get(key));
        return changes;
    }

    private ContentValues program(long channel, HomeRows.Item item, String poster, int weight) {
        ContentValues v = new ContentValues();
        v.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channel);
        v.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CLIP);
        v.put(TvContract.PreviewPrograms.COLUMN_TITLE, item.title);
        v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, poster);
        v.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
        v.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, shortcut(item.target));
        v.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.key);
        v.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, weight);
        return v;
    }

    private List<HomeRows.Row> programs(long channel) {
        List<HomeRows.Row> out = new ArrayList<>();
        String[] columns = {TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.PreviewPrograms.COLUMN_WEIGHT};
        Uri uri = TvContract.buildPreviewProgramsUriForChannel(channel);
        try (Cursor c = resolver.query(uri, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                out.add(new HomeRows.Row(c.getLong(0), c.getString(1), signature(c.getString(2), c.getString(3), c.getString(4), c.getString(5))));
            }
        }
        return out;
    }

    private static String signature(String title, String poster, String intent, String weight) {
        return title + "\n" + poster + "\n" + intent + "\n" + weight;
    }

    /** An explicit intent for ShortcutActivity with its target, as the launcher stores it. */
    private String shortcut(String target) {
        return new Intent().setComponent(new ComponentName(context, ShortcutActivity.class))
            .putExtra(ShortcutActivity.EXTRA_TARGET, target).toUri(Intent.URI_INTENT_SCHEME);
    }

    private String appLink() {
        return new Intent().setComponent(new ComponentName(context, MainActivity.class)).toUri(Intent.URI_INTENT_SCHEME);
    }
}
