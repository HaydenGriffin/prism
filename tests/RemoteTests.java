package io.github.haydengriffin.prism;

import com.sun.net.httpserver.HttpServer;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-JVM suite for everything below the Android service. Run through tools/test.py. */
public final class RemoteTests {

    private static final long HOUR = 3_600_000;
    private static final List<String> TV_ENTITIES = Arrays.asList("sensor.tv_active_app", "select.tv_launch_app");
    private static final HaLink.Timing FAST = new HaLink.Timing(50, 800, 250, 2000, new long[] {50, 100, 200});

    private static int assertions;

    private interface Throwing {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1); // Link threads must not keep a failed run alive.
        }
        System.out.println(assertions + " assertions passed");
        System.exit(0);
    }

    private static void run() throws Exception {
        json();
        foregroundState();
        routeRules();
        routeFreshness();
        keyQueue();
        remotePolicy();
        routeStatus();
        heartbeat();
        tvCue();
        registry();
        roleRefresh();
        keyboard();
        haHttp();
        haSocket();
        haLink();
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void refused(Throwing task, String message) throws Exception {
        try {
            task.run();
        } catch (IOException | IllegalArgumentException e) {
            assertions++;
            return;
        }
        throw new AssertionError(message);
    }

    private static <T extends Exception> T thrown(Class<T> type, Throwing task, String message) throws Exception {
        try {
            task.run();
        } catch (Exception e) {
            if (!type.isInstance(e)) throw new AssertionError(message + ": got " + e.getClass().getSimpleName(), e);
            assertions++;
            return type.cast(e);
        }
        throw new AssertionError(message + ": nothing thrown");
    }

    private static Map<String, Object> obj(String json) throws IOException {
        return Json.object(Json.parse(json));
    }

    // ---- JSON ----

    private static void json() throws Exception {
        Map<String, Object> m = obj("{\"a\":[1,2.5,true,null,\"x\\n\\u00e9\"],\"b\":{\"c\":-3e2}}");
        List<?> a = Json.array(m.get("a"));
        check(a.get(0).equals(1L) && a.get(1).equals(2.5) && a.get(2).equals(Boolean.TRUE), "json scalars");
        check(a.get(3) == null && a.get(4).equals("x\né"), "json null and escapes");
        check(Json.object(m.get("b")).get("c").equals(-300.0), "json exponent");
        check(Json.text(m.get("missing")).isEmpty() && Json.object("str").isEmpty(), "json helpers tolerate wrong types");
        for (String bad : new String[] {"{", "{\"a\":}", "[1,]", "tru", "\"open", "{} x", "{'a':1}"}) {
            refused(() -> Json.parse(bad), "invalid json rejected: " + bad);
        }
        String deep = String.join("", Collections.nCopies(100, "[")) + String.join("", Collections.nCopies(100, "]"));
        refused(() -> Json.parse(deep), "nesting is bounded");
        check(Json.quote("a\"b\\c\n").equals("\"a\\\"b\\\\c\\u000a\""), "quote escapes");
        check(Json.parse(Json.quote("x\u0001\"y")).equals("x\u0001\"y"), "quote round trip");
    }

    // ---- subscribe_entities diffs ----

    private static void foregroundState() throws Exception {
        String e = "sensor.tv";
        ForegroundState s = new ForegroundState(e, "select.tv_app");
        check(s.target().isEmpty(), "nothing known before the first event");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"on\",\"a\":{\"app_id\":\"example.video\"}}}}}"));
        check(s.target().isEmpty(), "a diff before the full add is ignored");
        s.apply(obj("{\"a\":{\"sensor.tv\":{\"s\":\"on\",\"a\":{\"app_id\":\"example.video\"},\"c\":\"ctx\",\"lc\":1}}}"));
        check(s.target().equals("example.video"), "full add sets target");
        s.apply(obj("{\"a\":{\"sensor.other\":{\"s\":\"on\",\"a\":{\"app_id\":\"other\"}}}}"));
        check(s.target().equals("example.video"), "other entities ignored");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"a\":{\"control_app_id\":\"com.palm.app.settings\"}}}}}"));
        check(s.target().equals("com.palm.app.settings"), "added attribute: control surface wins");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"a\":{\"control_app_id\":\"\"}}}}}"));
        check(s.target().isEmpty(), "empty control surface does not fall back to the base app");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"-\":{\"a\":[\"control_app_id\"]}}}}"));
        check(s.target().equals("example.video"), "removed attribute: older Glasshouse fallback");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"com.webos.app.hdmi2\",\"a\":{\"app_id\":\"com.webos.app.hdmi2\"},\"lc\":2}}}}"));
        check(s.target().equals("com.webos.app.hdmi2"), "changed attribute");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"lu\":3}}}}"));
        check(s.target().equals("com.webos.app.hdmi2"), "timestamp-only diff keeps state");
        for (String state : new String[] {"unavailable", "unknown", "off", "Off"}) {
            s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"" + state + "\"}}}}"));
            check(s.target().isEmpty(), "TV state " + state + " means no target");
        }
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"on\"}}}}"));
        check(s.target().equals("com.webos.app.hdmi2"), "attributes survive a state-only diff");
        s.apply(obj("{\"r\":[\"sensor.tv\"]}"));
        check(s.target().isEmpty(), "removed entity means no target");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"on\"}}}}"));
        check(s.target().isEmpty(), "diff after removal waits for a full add");
        s.apply(obj("{\"a\":{\"sensor.tv\":{\"s\":\"on\",\"a\":{\"app_id\":\"example.music\",\"control_app_id\":null}}}}"));
        check(s.target().isEmpty(), "null control surface is treated as none");
        s.apply(obj("{\"a\":{\"sensor.tv\":{\"s\":\"on\",\"a\":{\"app_id\":\"example.music\"}}}}"));
        check(s.target().equals("example.music"), "full add replaces every attribute");
        s.clear();
        check(s.target().isEmpty(), "clear forgets state");

        check(s.catalog().isEmpty(), "no catalog before the launch select is added");
        s.apply(obj("{\"a\":{\"select.tv_app\":{\"s\":\"unavailable\",\"a\":{\"app_ids\":{\"Video\":\"example.video\"}}}}}"));
        check(s.catalog().isEmpty(), "unavailable launch select (TV off) means no catalog");
        s.apply(obj("{\"c\":{\"select.tv_app\":{\"+\":{\"s\":\"Video\",\"a\":{\"app_ids\":{\"Video\":\"example.video\",\"Music\":\"example.music\"}}}}}}"));
        check(s.catalog().size() == 2 && "example.music".equals(s.catalog().get("music")),
            "catalog from the pushed app_ids, titles case-insensitive");
        check(s.target().isEmpty(), "the catalog does not invent a target");
        s.apply(obj("{\"c\":{\"select.tv_app\":{\"-\":{\"a\":[\"app_ids\"]}}}}"));
        check(s.catalog().isEmpty(), "missing app_ids means no catalog");
        s.apply(obj("{\"a\":{\"select.tv_app\":{\"s\":\"unknown\",\"a\":{\"app_ids\":{\"Video\":\"example.video\"}}}}}"));
        check(s.catalog().size() == 1, "a select with no current option still has its catalog");
        s.apply(obj("{\"r\":[\"select.tv_app\"]}"));
        check(s.catalog().isEmpty(), "removed launch select means no catalog");
    }

    // ---- routing ----

    private static AutoRoute configuredRoute() {
        AutoRoute route = new AutoRoute(15000);
        Set<String> apps = new HashSet<>(
            Arrays.asList("example.video", "example.music", "com.webos.app.hdmi1", "com.webos.app.livetv"));
        route.configure(apps, "com.webos.app.hdmi2");
        return route;
    }

    private static void routeRules() {
        AutoRoute route = configuredRoute();
        check(!route.controlsLg(0), "no routing before first observation");
        for (String id : new String[] {
            "example.video", "example.music", "com.webos.app.home", "com.palm.app.settings", "com.webos.app.tvuserguide"}) {
            route.observe(id, 100);
            check(route.controlsLg(100), "route catalog and system app: " + id);
        }
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.palm.app.settings"), 100);
        check(route.controlsLg(100), "Settings overlay owns remote above Shield input");
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.app.hdmi2"), 100);
        check(!route.controlsLg(100), "dismissing overlay releases remote to Shield");
        route.observe("example.video", 100);
        route.observe(AutoRoute.target("example.video", "com.webos.systemui.notification"), 100);
        check(route.controlsLg(100), "LG system popup above an LG app keeps the remote on the LG");
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.app.home"), 100);
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.systemui.notification"), 100);
        check(route.controlsLg(100), "LG system popup above LG Home (base HDMI) keeps the remote on the LG");
        route.observe("com.webos.app.hdmi2", 100);
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.systemui.notification"), 100);
        check(!route.controlsLg(100), "LG system popup above the Shield leaves the remote with the Shield");
        route.observe(AutoRoute.target("example.video", ""), 100);
        check(!route.controlsLg(100), "empty visible surface cannot use stale background app");
        route.observe(AutoRoute.target("example.video", null), 100);
        check(route.controlsLg(100), "older Glasshouse retains base-app fallback");
        for (String id : new String[] {
            null, "", "unknown", "off", "unavailable", "not.in.catalog", "com.webos.app.hdmi2", "com.webos.app.hdmi1",
            "com.webos.app.livetv"}) {
            route.observe(id, 200);
            check(!route.controlsLg(200), "no capture on input or unknown state: " + id);
        }
        route.observe("example.video", 300);
        long rev = route.revision();
        route.observe("example.video", 350);
        check(route.revision() == rev, "same foreground preserves in-flight revision");
        route.observe("example.music", 400);
        check(route.revision() != rev, "different app invalidates pending press");
        route.pause();
        check(!route.controlsLg(400), "escape releases locally");
        route.observe("example.music", 450);
        check(!route.controlsLg(450), "a push cannot rearm escape");
        route.observe("com.webos.app.hdmi1", 500);
        route.observe("example.video", 550);
        check(!route.controlsLg(550), "other HDMI cannot rearm escape");
        route.observe("com.webos.app.hdmi2", 600);
        route.observe("example.video", 650);
        check(route.controlsLg(650), "selected Shield input rearms automatically");
        route.pause();
        route.configure(Collections.singleton("example.video"), "com.webos.app.hdmi2");
        route.observe("example.video", 660);
        check(!route.controlsLg(660), "a reconnect (new catalog) keeps an escape paused");
        route.reset();
        route.configure(Collections.singleton("new.app"), "com.webos.app.hdmi4");
        route.observe("example.video", 700);
        check(!route.controlsLg(700), "new TV cannot retain old app catalog");
        route.observe("new.app", 800);
        check(route.controlsLg(800), "a new session starts unpaused; arbitrary device/app works");
        route.configure(Collections.<String>emptySet(), "com.webos.app.hdmi4");
        for (String id : new String[] {"new.app", "com.webos.app.home", "com.palm.app.settings"}) {
            route.observe(id, 900);
            check(!route.controlsLg(900) && !route.catalogKnown(), "no catalog (TV off/unknown): nothing to the LG: " + id);
        }
    }

    private static void routeFreshness() {
        AutoRoute route = configuredRoute();
        route.observe("example.video", 1000);
        check(route.controlsLg(1000 + 15000), "pushed target valid up to the link timeout");
        check(!route.controlsLg(1000 + 15001), "silent link: routing unknown");
        for (long t = 1000; t <= 1000 + HOUR; t += 5000) route.heard(t);
        check(route.controlsLg(1000 + HOUR + 1), "healthy link: target still valid after an hour without changes");
        check(!route.controlsLg(999), "clock moving backwards releases remote");
        route.invalidate();
        check(!route.controlsLg(1000 + HOUR + 2), "link down: routing unknown at once");
        route.heard(1000 + HOUR + 3);
        check(!route.controlsLg(1000 + HOUR + 3), "messages after a drop do not revive the old target");
        route.observe("example.video", 1000 + HOUR + 4);
        check(route.controlsLg(1000 + HOUR + 4), "a fresh full state restores routing");
        long rev = route.revision();
        route.invalidate();
        check(route.revision() != rev, "link drop invalidates queued presses");
    }

    private static void keyQueue() {
        KeyQueue keys = new KeyQueue();
        long a = keys.admit("right", false, 100);
        long b = keys.admit("ok", false, 110);
        long c = keys.admit("down", false, 120);
        check(a != 0 && b != 0 && c != 0, "three pending taps allowed");
        check(keys.admit("left", false, 130) == 0, "bounded queue");
        check(keys.shouldSend(a, 700) && !keys.shouldSend(a, 701), "expiry boundary");
        keys.done(a, false);
        keys.done(b, true);
        check(!keys.shouldSend(c, 140), "uncertain command flushes queued taps");
        keys.clear();
        long fresh = keys.admit("right", false, 200);
        keys.done(b, true);
        check(keys.shouldSend(fresh, 210), "old completion cannot flush new generation");
        keys.done(fresh, false);
        long repeat = keys.admit("right", true, 300);
        check(repeat != 0, "arrow repeat while idle");
        keys.release("right");
        check(!keys.shouldSend(repeat, 310), "release cancels pending repeat");
        keys.done(repeat, false);
        check(keys.admit("right", true, 320) == 0, "held-arrow throttle");
        check(keys.admit("ok", true, 500) == 0, "held OK never repeats");
        repeat = keys.admit("right", true, 500);
        keys.done(repeat, true);
        check(keys.admit("right", true, 700) == 0, "failed hold stays blocked");
        check(keys.admit("right", false, 800) != 0, "fresh tap recovers");
        keys.clear();
        check(keys.pending() == 0, "disable clears queue");
    }

    private static void remotePolicy() {
        for (int code : new int[] {3, 24, 25, 26, 84, 219}) {
            check(RemotePolicy.key(code) == null, "native system key passes through: " + code);
        }
        check("fast_forward".equals(RemotePolicy.key(90)) && "rewind".equals(RemotePolicy.key(89)),
            "fast-forward and rewind forwarded like play/pause");
        check(RemotePolicy.observeOnly(3), "Home is observed");
        for (int code : new int[] {4, 19, 23, 24, 26, 85, 89, 90}) {
            check(!RemotePolicy.observeOnly(code), "only Home is observe-only: " + code);
        }
        check(RemotePolicy.homeLeavesLg(3, true, 0, true, true), "Home from an allowed remote on the LG returns to the box");
        check(!RemotePolicy.homeLeavesLg(3, true, 1, true, true), "a held Home's repeats do nothing more");
        check(!RemotePolicy.homeLeavesLg(3, false, 0, true, true), "Home up does nothing");
        check(!RemotePolicy.homeLeavesLg(3, true, 0, false, true), "Home from a remote that doesn't control the LG ignored");
        check(!RemotePolicy.homeLeavesLg(3, true, 0, true, false), "Home on the box's own input is Android's alone");
        check(!RemotePolicy.homeLeavesLg(4, true, 0, true, true), "Back is not Home");
    }

    // ---- status contract ----

    private static void routeStatus() {
        Map<String, String> titles = new HashMap<>();
        titles.put("example.video", "Video");
        titles.put("com.webos.app.home", "Home Dashboard");
        String shield = "com.webos.app.hdmi2";
        checkState(RouteStatus.route("example.video", false, true, true, shield, titles), RemoteState.Mode.LG, "Video");
        checkState(RouteStatus.route("com.webos.app.home", false, true, true, shield, titles), RemoteState.Mode.LG, "LG Home");
        checkState(RouteStatus.route("com.palm.app.settings", false, true, true, shield, titles), RemoteState.Mode.LG,
            "LG Settings");
        checkState(RouteStatus.route("com.webos.app.quicksettings", false, true, true, shield, titles), RemoteState.Mode.LG,
            "Quick Settings");
        checkState(RouteStatus.route("com.example.unlisted", false, true, true, shield, titles), RemoteState.Mode.LG,
            "com.example.unlisted");
        checkState(RouteStatus.route(shield, false, false, true, shield, titles), RemoteState.Mode.SHIELD, "");
        checkState(RouteStatus.route("", false, false, true, shield, titles), RemoteState.Mode.TV_OFF, "");
        checkState(RouteStatus.route("com.webos.app.hdmi4", false, false, true, shield, titles), RemoteState.Mode.OTHER_INPUT,
            "");
        checkState(RouteStatus.route("example.video", true, false, true, shield, titles), RemoteState.Mode.PAUSED, "");
        checkState(RouteStatus.route("example.video", false, false, false, shield, titles), RemoteState.Mode.TV_OFF, "");
        checkState(RouteStatus.route(shield, false, false, false, shield, titles), RemoteState.Mode.SHIELD, "");

        checkState(RouteStatus.failure(HaLink.Step.CONNECT, new HaSocket.AuthRefused()), RemoteState.Mode.ERROR,
            "Home Assistant refused the token");
        checkState(RouteStatus.failure(HaLink.Step.CONNECT, new IOException("token=abc secret body")),
            RemoteState.Mode.ERROR, "Could not connect to Home Assistant");
        checkState(RouteStatus.failure(HaLink.Step.CONNECT, new java.net.ConnectException("10.0.0.1 secret")),
            RemoteState.Mode.ERROR, "Cannot reach Home Assistant");
        checkState(RouteStatus.failure(HaLink.Step.SUBSCRIBE, new HaSocket.Refused("unauthorized")),
            RemoteState.Mode.ERROR, "Home Assistant refused to share the TV state");
        checkState(RouteStatus.failure(HaLink.Step.SILENT, new SocketTimeoutException()), RemoteState.Mode.CONNECTING,
            "");
        checkState(RouteStatus.failure(HaLink.Step.DROPPED, new EOFException()), RemoteState.Mode.CONNECTING, "");
    }

    private static void checkState(RemoteState state, RemoteState.Mode mode, String detail) {
        check(state.mode == mode && state.detail.equals(detail),
            "expected " + mode + "/" + detail + " got " + state.mode + "/" + state.detail);
    }

    // ---- heartbeat ----

    /** What the service does: due, then a write that succeeds. */
    private static String sent(Heartbeat beat, RemoteState.Mode mode, long now) {
        String value = beat.due(mode, now);
        if (value != null) beat.succeeded(value, now);
        return value;
    }

    private static void heartbeat() {
        check(Heartbeat.value(RemoteState.Mode.LG, "0.6.0").equals("lg \u00b7 0.6.0"), "value: mode lower-case · version");
        check(Heartbeat.value(RemoteState.Mode.TV_OFF, "0.6.0").equals("tv_off \u00b7 0.6.0"), "value for TV_OFF");
        String long_ = String.join("", Collections.nCopies(120, "9"));
        check(Heartbeat.value(RemoteState.Mode.LG, long_).length() == 100, "value capped at 100 characters");
        Heartbeat beat = new Heartbeat("0.6.0");
        check("shield \u00b7 0.6.0".equals(sent(beat, RemoteState.Mode.SHIELD, 1000)), "first healthy check sends at once");
        check(beat.due(RemoteState.Mode.SHIELD, 1000 + 59_999) == null, "nothing new inside 60 s");
        check(sent(beat, RemoteState.Mode.LG, 2000) != null, "a mode change sends at once");
        check(beat.due(RemoteState.Mode.LG, 2000 + 59_999) == null, "the mode change restarted the minute");
        check(sent(beat, RemoteState.Mode.LG, 2000 + 60_000) != null, "every 60 s while healthy");
        beat.reset();
        check(sent(beat, RemoteState.Mode.LG, 2000 + 60_001) != null, "after a drop the next connect sends at once");
        check(beat.firstFailure() && !beat.firstFailure(), "a refusal is logged once");
        beat.succeeded("lg \u00b7 0.6.0", 2000 + 60_002);
        check(beat.firstFailure(), "logged again after a success");
        Heartbeat failing = new Heartbeat("0.6.0");
        check(failing.due(RemoteState.Mode.TV_OFF, 5000) != null, "due before a write");
        check(failing.due(RemoteState.Mode.TV_OFF, 10_000) != null, "a failed write is retried on the next tick, not 60 s later");
        failing.succeeded("tv_off \u00b7 0.6.0", 10_000);
        check(failing.due(RemoteState.Mode.TV_OFF, 15_000) == null, "only a confirmed write starts the minute");
    }

    private static void tvCue() {
        TvCue cue = new TvCue();
        check(cue.firstPress(), "first captured press of an LG session shows the cue");
        check(!cue.firstPress(), "later presses do not");
        cue.route(true);
        check(!cue.firstPress(), "moving between LG apps is the same session");
        cue.route(false);
        check(cue.firstPress() && !cue.firstPress(), "leaving the LG starts a new session: once again");
        cue.reset();
        check(cue.firstPress(), "a link drop starts a new session");
        check(TvCue.text(BoxName.of(null, null)).equals("Remote on the LG \u00b7 hold Back for this box"), "cue text");
    }

    // ---- registry discovery ----

    private static Map<String, Object> entity(String id, String uid, String device, Object disabledBy) {
        Map<String, Object> e = new HashMap<>();
        e.put("entity_id", id);
        e.put("unique_id", uid);
        e.put("device_id", device);
        e.put("disabled_by", disabledBy);
        return e;
    }

    private static Map<String, Object> device(String id, String name, String byUser) {
        Map<String, Object> d = new HashMap<>();
        d.put("id", id);
        d.put("name", name);
        d.put("name_by_user", byUser);
        return d;
    }

    private static void registry() {
        List<Object> entities = new ArrayList<>();
        entities.add(entity("select.lounge_app", "lg1_app", "dev1", null));
        entities.add(entity("sensor.lounge_active", "lg1_active_app", "dev1", null));
        entities.add(entity("sensor.lounge_picture", "lg1_picture_mode", "dev1", null));
        entities.add(entity("select.lounge_picture", "lg1_picture_mode", "dev1", null));
        entities.add(entity("button.lounge_up", "lg1_remote_up", "dev1", null));
        entities.add(entity("button.disabled_down", "lg1_remote_down", "dev1", "user"));
        entities.add(entity("button.elsewhere_ok", "lg1_remote_ok", "dev2", null));
        entities.add(entity("select.second_tv_app", "lg2_app", "dev2", null));
        entities.add(entity("select.second_tv_hidden_app", "lg3_app", "dev2", "integration"));
        entities.add(entity("select.orphan_app", "lg4_app", null, null));
        List<Object> devices = Arrays.<Object>asList(device("dev1", "LG C1", "Lounge TV"), device("dev2", "LG B9", null));
        List<TvRegistry.Candidate> found = TvRegistry.candidates(entities, devices);
        check(found.size() == 2, "one candidate per enabled launch select with a device: " + found.size());
        TvRegistry.Candidate lounge = found.get(0);
        check(lounge.name.equals("Lounge TV") && lounge.launch.equals("select.lounge_app"), "user name preferred");
        check(found.get(1).name.equals("LG B9"), "device name fallback");
        check("sensor.lounge_active".equals(lounge.role("active_app", "sensor")), "role by suffix and domain");
        check("select.lounge_picture".equals(lounge.role("picture_mode", "select")), "select wins over same-suffix sensor");
        check("sensor.lounge_picture".equals(lounge.role("picture_mode", "sensor")), "sensor still addressable");
        check(lounge.role("active_app", "select") == null, "wrong domain never matches");
        check(lounge.role("remote_down", "button") == null, "disabled entity skipped");
        check(lounge.role("remote_ok", "button") == null, "same prefix on another device is not this TV's");
        check(found.get(1).role("remote_ok", "button") == null, "other prefix on the same device is not this TV's");
    }

    /** Registry roles from "<domain>.tv_<suffix>" entity ids. */
    private static Map<String, List<String>> roles(String... ids) {
        Map<String, List<String>> roles = new HashMap<>();
        for (String id : ids) roles.computeIfAbsent(id.substring(id.indexOf('.') + 4), k -> new ArrayList<>()).add(id);
        return roles;
    }

    private static TvRegistry.Candidate tv(String name, String... ids) {
        return new TvRegistry.Candidate(name, "dev1", "select.tv_app", roles(ids));
    }

    private static final String[] BASE = {"sensor.tv_active_app", "select.tv_input_source", "button.tv_remote_up",
        "button.tv_remote_down", "button.tv_remote_left", "button.tv_remote_right", "button.tv_remote_ok",
        "button.tv_remote_back", "button.tv_play", "select.tv_picture_mode"};

    private static String[] with(String... more) {
        List<String> all = new ArrayList<>(Arrays.asList(BASE));
        all.addAll(Arrays.asList(more));
        return all.toArray(new String[0]);
    }

    private static void roleRefresh() {
        Map<String, String> saved = TvRoles.resolve(tv("LG C1", BASE));
        check(saved != null && saved.get(TvRoles.NOTICE).isEmpty(), "base TV resolves, no notice");
        saved.put(TvRoles.SHIELD_OPTION, "HDMI 3");
        saved.put(TvRoles.SHIELD_APP, "com.webos.app.hdmi3");

        Map<String, String> same = TvRoles.refresh(saved, tv("LG C1", BASE));
        check(same.equals(saved), "unchanged registry: equal profile, so no save");
        check(!TvRoles.subscriptionChanged(saved, same), "unchanged: subscription kept");

        Map<String, String> grown = TvRoles.refresh(saved,
            tv("LG C1", with("button.tv_rewind", "button.tv_fast_forward", "text.tv_screen_notification")));
        check("button.tv_rewind".equals(grown.get("button.rewind"))
            && "button.tv_fast_forward".equals(grown.get("button.fast_forward")), "new optional buttons appear");
        check("text.tv_screen_notification".equals(grown.get(TvRoles.NOTICE)), "the notice appears");
        check(grown.get(TvRoles.SHIELD_OPTION).equals("HDMI 3") && grown.get(TvRoles.SHIELD_APP).equals("com.webos.app.hdmi3"),
            "the chosen Shield input is kept");
        check(!grown.equals(saved) && !TvRoles.subscriptionChanged(saved, grown), "changed roles: save, same subscription");

        List<String> fewer = new ArrayList<>(Arrays.asList(BASE));
        fewer.remove("button.tv_play");
        fewer.remove("select.tv_picture_mode");
        Map<String, String> shrunk = TvRoles.refresh(grown, tv("LG C1", fewer.toArray(new String[0])));
        check(!shrunk.containsKey("button.play") && !shrunk.containsKey("setting.picture_mode")
            && !shrunk.containsKey("button.rewind") && shrunk.get(TvRoles.NOTICE).isEmpty(), "removed roles disappear");
        check(shrunk.get(TvRoles.SHIELD_APP).equals("com.webos.app.hdmi3"), "Shield input kept when roles shrink");

        Map<String, List<String>> moved = roles(BASE);
        moved.put("active_app", new ArrayList<>(Collections.singletonList("sensor.lounge_now_playing")));
        check(TvRoles.subscriptionChanged(saved,
            TvRoles.refresh(saved, new TvRegistry.Candidate("LG C1", "dev1", "select.tv_app", moved))),
            "renamed active-app sensor: the subscription must follow");
        check(TvRoles.subscriptionChanged(saved,
            TvRoles.refresh(saved, new TvRegistry.Candidate("LG C1", "dev1", "select.lounge_app", roles(BASE)))),
            "renamed launch select: the subscription must follow");

        List<String> broken = new ArrayList<>(Arrays.asList(BASE));
        broken.remove("button.tv_remote_ok");
        check(TvRoles.refresh(saved, tv("LG C1", broken.toArray(new String[0]))) == null,
            "a TV missing required buttons keeps the saved profile");
    }

    // ---- LG on-screen keyboard ----

    private static void keyboard() throws Exception {
        keyboardState();
        keyboardRoute();
        keyboardStatus();
        keyboardKeys();
        micPassesThrough();
        keyboardRoles();
    }

    private static void keyboardState() throws Exception {
        ForegroundState s = new ForegroundState("sensor.tv", "select.tv_app");
        check(!s.keyboard(), "no keyboard before the first event");
        s.apply(obj("{\"a\":{\"sensor.tv\":{\"s\":\"HDMI 3\",\"a\":{\"app_id\":\"com.webos.app.hdmi3\",\"keyboard_visible\":true}}}}"));
        check(s.keyboard(), "keyboard_visible true: the LG keyboard is up");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"a\":{\"keyboard_visible\":false}}}}}"));
        check(!s.keyboard(), "keyboard_visible false: down");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"a\":{\"keyboard_visible\":true}}}}}"));
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"-\":{\"a\":[\"keyboard_visible\"]}}}}"));
        check(!s.keyboard(), "older Glasshouse without the attribute: no keyboard");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"a\":{\"keyboard_visible\":\"true\"}}}}}"));
        check(!s.keyboard(), "only a real boolean counts");
        s.apply(obj("{\"c\":{\"sensor.tv\":{\"+\":{\"s\":\"off\",\"a\":{\"keyboard_visible\":true}}}}}"));
        check(!s.keyboard(), "a TV that is off shows no keyboard, whatever the attribute says");
        s.clear();
        check(!s.keyboard(), "clear forgets the keyboard");
    }

    private static void keyboardRoute() {
        AutoRoute route = configuredRoute();
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.app.hdmi2"), true, 100);
        check(route.controlsLg(100) && route.keyboard(), "keyboard above a base the Shield owns: the remote drives the LG");
        route.observe(AutoRoute.target("com.webos.app.hdmi2", "com.webos.app.hdmi2"), false, 110);
        check(!route.controlsLg(110) && !route.keyboard(), "keyboard closed: back to the Shield");
        route.observe("example.video", true, 120);
        long rev = route.revision();
        route.observe("example.video", false, 130);
        check(route.controlsLg(130) && route.revision() == rev, "keyboard closing over an LG app keeps the remote and queued presses");
        route.observe("com.webos.app.hdmi1", true, 140);
        check(route.controlsLg(140), "the keyboard wins over any base input");
        check(!route.controlsLg(140 + 15001), "a silent link releases even with the keyboard up");
        route.observe("com.webos.app.hdmi1", true, 150);
        route.pause();
        check(!route.controlsLg(150), "held Back still hands the remote to the Shield");
        route.reset();
        route.configure(Collections.<String>emptySet(), "com.webos.app.hdmi2");
        route.observe("com.webos.app.hdmi2", true, 160);
        check(!route.controlsLg(160), "no catalog (TV off): the keyboard cannot route");
        route.configure(Collections.singleton("example.video"), "com.webos.app.hdmi2");
        route.observe("com.webos.app.hdmi2", true, 170);
        route.invalidate();
        check(!route.keyboard() && !route.controlsLg(170), "a link drop forgets the keyboard");
    }

    private static void keyboardStatus() {
        Map<String, String> titles = new HashMap<>();
        titles.put("example.video", "Video");
        String shield = "com.webos.app.hdmi2";
        checkState(RouteStatus.route(shield, false, true, true, shield, titles, true), RemoteState.Mode.LG, "LG keyboard");
        checkState(RouteStatus.route("example.video", false, true, true, shield, titles, false), RemoteState.Mode.LG, "Video");
        checkState(RouteStatus.route(shield, false, false, true, shield, titles, true), RemoteState.Mode.SHIELD, "");
    }

    private static void keyboardKeys() {
        check("keyboard_backspace".equals(RemotePolicy.key(67, true)) && "keyboard_enter".equals(RemotePolicy.key(160, true)),
            "with the keyboard up, Delete and keypad Enter go to its keys");
        check(RemotePolicy.key(67, false) == null && RemotePolicy.key(160, false) == null, "without it they stay with the Shield");
        check("ok".equals(RemotePolicy.key(66, true)) && "up".equals(RemotePolicy.key(19, true)),
            "Enter stays OK and arrows stay arrows on the keyboard");
    }

    /** The mic stays Android's: never forwarded, never observed, keyboard up or not. */
    private static void micPassesThrough() {
        for (int code : new int[] {84, 219, 231}) {
            check(RemotePolicy.key(code, true) == null && RemotePolicy.key(code, false) == null,
                "the mic key is never a forwarded button: " + code);
            check(!RemotePolicy.observeOnly(code), "the mic key is never observed: " + code);
        }
    }

    private static void keyboardRoles() {
        Map<String, String> plain = TvRoles.resolve(tv("LG C1", BASE));
        check(plain.get(TvRoles.KEYBOARD).isEmpty() && !plain.containsKey("button.keyboard_enter"),
            "no keyboard entities: none resolved");
        Map<String, String> saved = new HashMap<>(plain);
        saved.put(TvRoles.SHIELD_OPTION, "HDMI 3");
        saved.put(TvRoles.SHIELD_APP, "com.webos.app.hdmi3");
        Map<String, String> next = TvRoles.refresh(saved,
            tv("LG C1", with("text.tv_keyboard_text", "button.tv_keyboard_enter", "button.tv_keyboard_backspace")));
        check("text.tv_keyboard_text".equals(next.get(TvRoles.KEYBOARD)), "keyboard text entity by role and domain");
        check("button.tv_keyboard_enter".equals(next.get("button.keyboard_enter"))
            && "button.tv_keyboard_backspace".equals(next.get("button.keyboard_backspace")), "keyboard buttons resolved");
        check(!next.equals(saved) && !TvRoles.subscriptionChanged(saved, next), "the registry refresh picks them up");
        check(TvRoles.resolve(tv("LG C1", with("sensor.tv_keyboard_text"))).get(TvRoles.KEYBOARD).isEmpty(),
            "wrong domain is not the keyboard");
        check("select.tv_input_source".equals(plain.get(TvRoles.SETTING + "input_source")),
            "the input select is also a Quick TV setting");
    }

    // ---- REST ----

    private static void haHttp() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        AtomicReference<String> lastPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/api/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestMethod().equals("POST")) {
                    writes.incrementAndGet();
                    payload.set(body);
                    lastPath.set(path);
                }
                if (path.endsWith("slow") || body.contains("slow")) sleep(2100);
                int code = path.contains("services/button") ? 500
                    : path.endsWith("denied") ? 403
                    : path.endsWith("redirect") ? 302
                    : 200;
                if (code == 302) exchange.getResponseHeaders().set("Location", "/api/ok");
                byte[] data = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, data.length);
                exchange.getResponseBody().write(data);
            } catch (IOException ignored) {
                // Client gave up.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HaHttp api = new HaHttp("http://127.0.0.1:" + server.getAddress().getPort(), "fixture");
            check(api.browse("ok").equals("{}"), "state read works");
            refused(() -> api.browse("denied"), "authorization refusal");
            refused(() -> api.browse("redirect"), "redirect refused");
            refused(() -> api.press("button.any_tv_remote_up"), "command failure reported");
            check(writes.get() == 1, "uncertain command never replayed");
            api.select("select.renamed_input", "HDMI 4");
            check(payload.get().contains("select.renamed_input") && payload.get().contains("HDMI 4"),
                "configured entity and input used");
            long started = System.nanoTime();
            refused(() -> api.select("select.slow", "x"), "commands have bounded latency");
            check(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2050), "command deadline under 2 s");
            check(api.browse("slow").equals("{}"), "setup tolerates slow response without relaxing command deadline");
            api.setValue("number.tv_oled_light", "42.4");
            check(lastPath.get().endsWith("services/number/set_value")
                && payload.get().equals("{\"entity_id\":\"number.tv_oled_light\",\"value\":42}"), "number set_value");
            api.setValue("select.tv_picture_mode", "Cinema");
            check(lastPath.get().endsWith("services/select/select_option") && payload.get().contains("Cinema"),
                "select setting uses select_option");
            int before = writes.get();
            refused(() -> api.setValue("light.lounge", "on"), "other domains refused");
            refused(() -> api.setValue("number.tv_oled_light", "bright"), "non-numeric number refused");
            refused(() -> api.setValue("number.tv_oled_light", "NaN"), "NaN refused");
            check(writes.get() == before, "refused settings send nothing");
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
        for (String bad : new String[] {
            "file:///tmp/x", "https://user:pass@ha.example", "https://ha.example/api", "https://ha.example?x=y",
            "http://ha.example/#x"}) {
            refused(() -> new HaHttp(bad, "fixture"), "bad origin");
        }
        refused(() -> new HaHttp("http://ha.example", "bad\r\nheader"), "header injection");
        check(HaHttp.normalizeBase("http://ha.example:8123/").equals("http://ha.example:8123"), "normalize origin");
    }

    // ---- WebSocket connection ----

    private static final HaSocket.Listener QUIET = new HaSocket.Listener() {
        @Override
        public void received(HaSocket socket, Map<String, Object> message) {}

        @Override
        public void closed(HaSocket socket, Exception cause) {}
    };

    private static void haSocket() throws Exception {
        try (FakeHa ha = new FakeHa()) {
            thrown(HaSocket.AuthRefused.class, () -> HaSocket.open(ha.origin(), "wrong", 2000, QUIET), "bad token");
            ha.wrongAccept = true;
            refused(() -> HaSocket.open(ha.origin(), FakeHa.TOKEN, 2000, QUIET), "handshake accept verified");
            ha.wrongAccept = false;

            BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
            BlockingQueue<Exception> closed = new LinkedBlockingQueue<>();
            HaSocket.Listener listener = new HaSocket.Listener() {
                @Override
                public void received(HaSocket socket, Map<String, Object> message) {
                    received.add(message);
                }

                @Override
                public void closed(HaSocket socket, Exception cause) {
                    closed.add(cause);
                }
            };
            HaSocket socket = HaSocket.open(ha.origin(), FakeHa.TOKEN, 2000, listener);
            Object result = socket.request("\"type\":\"config/entity_registry/list\"", 2000);
            check(Json.array(result).size() == 1, "request returns result");
            ha.nextResultPadding = 70000;
            check(Json.text(Json.array(socket.request("\"type\":\"big\"", 2000)).get(0)).length() == 70000,
                "64-bit frame length");
            socket.request("\"type\":\"subscribe_entities\",\"entity_ids\":[\"sensor.tv_active_app\"]", 2000);
            Map<String, Object> event = poll(received, m -> "event".equals(m.get("type")));
            check(event != null && Json.object(Json.object(event.get("event")).get("a")).containsKey("sensor.tv_active_app"),
                "subscription event delivered");
            long before = socket.lastMessageAt();
            sleep(20);
            socket.send("\"type\":\"ping\"");
            check(poll(received, m -> "pong".equals(m.get("type"))) != null, "pong delivered");
            check(socket.lastMessageAt() > before, "pong refreshes liveness");
            socket.request(HaSocket.callService("button", "press", "{\"entity_id\":\"button.tv_up\"}"), 2000);
            check(ha.calls.contains("button.press {entity_id=button.tv_up}"), "call_service sent");
            ha.commandError = "service_validation_error";
            HaSocket.Refused refusal = thrown(HaSocket.Refused.class,
                () -> socket.request(HaSocket.callService("button", "press", "{}"), 2000), "refused command");
            check(refusal.code.equals("service_validation_error") && !refusal.getMessage().contains("secret"),
                "refusal carries the code, never HA's message");
            ha.commandError = null;
            ha.answerCommands = false;
            long started = System.nanoTime();
            thrown(SocketTimeoutException.class,
                () -> socket.request(HaSocket.callService("button", "press", "{}"), 300), "command timeout");
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(waited >= 290 && waited < 1000, "timeout honoured: " + waited + " ms");
            ha.answerCommands = true;
            AtomicReference<Exception> pending = new AtomicReference<>();
            ha.answerCommands = false;
            Thread waiter = new Thread(() -> {
                try {
                    socket.request(HaSocket.callService("button", "press", "{}"), 5000);
                } catch (Exception e) {
                    pending.set(e);
                }
            });
            waiter.start();
            sleep(100);
            started = System.nanoTime();
            ha.dropAll();
            waiter.join(2000);
            check(pending.get() instanceof EOFException, "server close fails waiting requests at once");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "no waiting out the timeout");
            check(closed.poll(2, TimeUnit.SECONDS) != null, "server close reported");
            ha.answerCommands = true;
        }
    }

    private interface Match {
        boolean test(Map<String, Object> message);
    }

    private static Map<String, Object> poll(BlockingQueue<Map<String, Object>> queue, Match match) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            Map<String, Object> m = queue.poll(50, TimeUnit.MILLISECONDS);
            if (m != null && match.test(m)) return m;
        }
        return null;
    }

    // ---- long-lived link ----

    /** Like elapsedRealtime: sleep() moves it on without the JVM's monotonic clock (CPU suspended). */
    private static final class SleepClock implements HealthClock {
        private final java.util.concurrent.atomic.AtomicLong slept = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public long now() {
            return MONOTONIC.now() + slept.get();
        }

        void sleep(long ms) {
            slept.addAndGet(ms);
        }
    }

    /** Records link callbacks, mirroring what the service does with them. */
    private static final class Recorder implements HaLink.Listener {
        final BlockingQueue<String> log = new LinkedBlockingQueue<>();
        final SleepClock clock = new SleepClock();
        final AutoRoute route = new AutoRoute(FAST.silenceMs);
        final TvState tv = new TvState(route, "sensor.tv_active_app", "select.tv_launch_app", "com.webos.app.hdmi2");
        final AtomicInteger heard = new AtomicInteger();
        volatile Exception lastCause;

        @Override
        public synchronized void opened() {
            tv.opened();
            log.add("opened");
        }

        @Override
        public synchronized void event(Map<String, Object> event) {
            tv.apply(event, clock.now());
            log.add("event " + route.current());
        }

        @Override
        public void heard() {
            heard.incrementAndGet();
            route.heard(clock.now());
        }

        @Override
        public synchronized void lost(HaLink.Step step, Exception cause) {
            tv.lost();
            lastCause = cause;
            log.add("lost " + step);
        }

        synchronized RemoteState status() {
            return tv.status(clock.now());
        }

        String next(long ms) throws InterruptedException {
            String entry = log.poll(ms, TimeUnit.MILLISECONDS);
            return entry == null ? "(nothing)" : entry;
        }

        void expect(String entry, long ms, String why) throws InterruptedException {
            String got = next(ms);
            check(got.equals(entry), why + ": expected '" + entry + "' got '" + got + "'");
        }
    }

    private static void haLink() throws Exception {
        long[] standard = HaLink.Timing.STANDARD.backoffMs;
        check(Arrays.equals(standard, new long[] {1000, 2000, 5000, 10000}), "standard backoff steps");
        check(HaLink.Timing.STANDARD.backoff(0) == 1000 && HaLink.Timing.STANDARD.backoff(9) == 10000, "backoff caps");
        check(HaLink.Timing.STANDARD.pingMs == 5000 && HaLink.Timing.STANDARD.silenceMs == 15000
            && HaLink.Timing.STANDARD.commandMs == 2000, "standard heartbeat and command deadline");

        HaLink idle = new HaLink("http://127.0.0.1:9", FakeHa.TOKEN, TV_ENTITIES, FAST, new Recorder(), HealthClock.MONOTONIC);
        refused(() -> idle.press("button.before"), "no commands before the link is subscribed");

        try (FakeHa ha = new FakeHa()) {
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), FakeHa.TOKEN, TV_ENTITIES, FAST, r, r.clock);
            link.start();
            r.expect("opened", 2000, "catalog hook before subscribing");
            r.expect("event example.video", 2000, "initial full state");
            check(r.route.controlsLg(r.clock.now()) && r.status().detail.equals("Video"),
                "catalog and target from one subscription");
            ha.push("{\"c\":{\"sensor.tv_active_app\":{\"+\":{\"s\":\"com.webos.app.hdmi2\",\"a\":{\"app_id\":\"com.webos.app.hdmi2\"}}}}}");
            r.expect("event com.webos.app.hdmi2", 2000, "pushed change");

            link.press("button.tv_up");
            check(ha.calls.contains("button.press {entity_id=button.tv_up}"), "press over the socket");
            link.setValue("input_text.prism", "lg \u00b7 0.6.0");
            check(ha.calls.contains("input_text.set_value {entity_id=input_text.prism, value=lg \u00b7 0.6.0}"),
                "heartbeat over the socket");
            link.setValue("text.tv_screen_notification", "hello");
            check(ha.calls.contains("text.set_value {entity_id=text.tv_screen_notification, value=hello}"),
                "domain taken from the entity");
            ha.commandError = "not_found";
            HaSocket.Refused missing = thrown(HaSocket.Refused.class,
                () -> link.setValue("input_text.prism", "x"), "missing helper refused");
            check(missing.code.equals("not_found") && link.healthy(), "a refusal leaves the link up");
            ha.commandError = null;
            link.select("select.tv_input", "HDMI 2");
            check(ha.calls.contains("select.select_option {entity_id=select.tv_input, option=HDMI 2}"),
                "select over the socket");
            ha.answerCommands = false;
            int calls = ha.calls.size();
            long started = System.nanoTime();
            thrown(SocketTimeoutException.class, () -> link.press("button.tv_ok"), "unanswered press fails");
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(waited >= FAST.commandMs - 10 && waited < FAST.commandMs + 500, "press deadline: " + waited);
            sleep(200);
            check(ha.calls.size() == calls + 1, "a failed press is never retried");
            ha.answerCommands = true;

            int pings = ha.pings.get();
            sleep(FAST.silenceMs * 4);
            check(r.log.isEmpty(), "answered pings keep the link up through long idle: " + r.log);
            check(link.healthy(), "healthy while pongs arrive");
            check(ha.pings.get() - pings >= 8, "pings every pingMs: " + (ha.pings.get() - pings));
            check(r.heard.get() > 8, "every message reported as heard");

            ha.answerPings = false;
            r.expect("lost SILENT", FAST.silenceMs + 500, "silence declares the link dead");
            check(!link.healthy(), "unhealthy once silent");
            ha.answerPings = true;
            r.expect("opened", 2000, "reconnect after silence");
            r.expect("event example.video", 2000, "full state again after reconnect");
            check(ha.connections.get() == 2, "one new connection");

            ha.dropAll();
            r.expect("lost DROPPED", 2000, "server close noticed");
            r.expect("opened", 2000, "reconnect after server close");
            r.expect("event example.video", 2000, "resubscribed");
            check(ha.connections.get() == 3, "reconnected once more");

            link.stop();
            ha.dropAll();
            sleep(400);
            check(r.log.isEmpty(), "stopped link stays down: " + r.log);
            check(ha.connections.get() == 3, "no reconnect after stop");
            refused(() -> link.press("button.tv_up"), "no commands after stop");
        }

        tvOff();
        reconnectNow();
        deepSleep();

        try (FakeHa ha = new FakeHa()) {
            ha.refuseSubscribe = true;
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), FakeHa.TOKEN, TV_ENTITIES, FAST, r, r.clock);
            link.start();
            r.expect("opened", 2000, "signed in");
            r.expect("lost SUBSCRIBE", 2000, "refused subscription reported");
            check(r.lastCause instanceof HaSocket.Refused, "refusal kept as cause");
            long started = System.nanoTime();
            r.expect("opened", 2000, "retried");
            long gap = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(gap >= 40, "retry waits for backoff: " + gap);
            r.expect("lost SUBSCRIBE", 2000, "still refused");
            link.stop();
        }

        try (FakeHa ha = new FakeHa()) {
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), "wrong", TV_ENTITIES, FAST, r, r.clock);
            link.start();
            r.expect("lost CONNECT", 2000, "bad token");
            check(r.lastCause instanceof HaSocket.AuthRefused, "auth refusal kept as cause");
            link.stop();
        }
    }

    /** TV off at connect: both entities unavailable. A state, not a failure: no reconnects, no ERROR. */
    private static void tvOff() throws Exception {
        try (FakeHa ha = new FakeHa()) {
            ha.initialState = "{\"s\":\"unavailable\",\"a\":{}}";
            ha.launchState = "{\"s\":\"unavailable\",\"a\":{\"friendly_name\":\"TV Launch App\"}}";
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), FakeHa.TOKEN, TV_ENTITIES, FAST, r, r.clock);
            link.start();
            r.expect("opened", 2000, "TV off: signed in");
            r.expect("event ", 2000, "TV off: subscribed, empty target");
            sleep(FAST.silenceMs * 3);
            check(r.log.isEmpty(), "TV off: no reconnect churn: " + r.log);
            check(ha.connections.get() == 1 && link.healthy(), "TV off: one healthy connection");
            checkState(r.status(), RemoteState.Mode.TV_OFF, "");

            ha.push("{\"c\":{\"sensor.tv_active_app\":{\"+\":{\"s\":\"on\",\"a\":{\"app_id\":\"example.video\"}}}}}");
            r.expect("event example.video", 2000, "TV on: target pushed");
            check(!r.route.controlsLg(r.clock.now()), "an LG app before the catalog arrives is not routed");
            checkState(r.status(), RemoteState.Mode.TV_OFF, "");
            ha.push("{\"c\":{\"select.tv_launch_app\":{\"+\":{\"s\":\"Video\",\"a\":{\"app_ids\":{\"Video\":\"example.video\"}}}}}}");
            r.expect("event example.video", 2000, "catalog pushed later");
            check(r.route.controlsLg(r.clock.now()), "catalog arrives later: routing works");
            checkState(r.status(), RemoteState.Mode.LG, "Video");
            ha.push("{\"c\":{\"select.tv_launch_app\":{\"+\":{\"s\":\"unavailable\"}},"
                + "\"sensor.tv_active_app\":{\"+\":{\"s\":\"unavailable\"}}}}");
            r.expect("event ", 2000, "TV off again");
            checkState(r.status(), RemoteState.Mode.TV_OFF, "");
            check(r.log.isEmpty() && ha.connections.get() == 1, "TV off again: still one connection");
            link.stop();
        }
    }

    /** Screen on after Doze: skip the backoff wait, but never disturb a healthy link. */
    private static void reconnectNow() throws Exception {
        HaLink.Timing slow = new HaLink.Timing(50, 800, 250, 2000, new long[] {3000});
        try (FakeHa ha = new FakeHa()) {
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), FakeHa.TOKEN, TV_ENTITIES, slow, r, r.clock);
            link.start();
            r.expect("opened", 2000, "first connect");
            r.expect("event example.video", 2000, "first state");
            link.reconnectNow();
            sleep(200);
            check(r.log.isEmpty() && ha.connections.get() == 1, "healthy link: reconnectNow does nothing");
            ha.dropAll();
            r.expect("lost DROPPED", 2000, "drop noticed");
            long started = System.nanoTime();
            link.reconnectNow();
            r.expect("opened", 1000, "reconnects at once instead of after the 3 s backoff");
            r.expect("event example.video", 1000, "resubscribed");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "reconnected well inside the backoff");
            sleep(3300);
            check(r.log.isEmpty() && ha.connections.get() == 2, "the skipped backoff retry never connects again: "
                + ha.connections.get() + " " + r.log);
            link.stop();
            link.reconnectNow();
            sleep(200);
            check(ha.connections.get() == 2, "stopped link ignores reconnectNow");
        }
    }

    /**
     * The Shield's CPU suspended for an hour: the old monotonic clock stood still, the sleep-aware one did not. The
     * link must not look healthy, routing must stop, and screen-on must reconnect at once.
     */
    private static void deepSleep() throws Exception {
        HaLink.Timing standard = new HaLink.Timing(5000, 15000, 250, 2000, new long[] {3000});
        try (FakeHa ha = new FakeHa()) {
            Recorder r = new Recorder();
            HaLink link = new HaLink(ha.origin(), FakeHa.TOKEN, TV_ENTITIES, standard, r, r.clock);
            link.start();
            r.expect("opened", 2000, "awake: connected");
            r.expect("event example.video", 2000, "awake: LG app in front");
            check(link.healthy() && r.route.controlsLg(r.clock.now()), "awake: healthy, LG has the remote");

            int pings = ha.pings.get();
            r.clock.sleep(6000);
            link.reconnectNow();
            sleep(300);
            check(ha.pings.get() > pings && ha.connections.get() == 1, "screen on after a short sleep: ping at once");

            r.clock.sleep(3_600_000);
            check(!link.healthy(), "after an hour asleep the link is not healthy");
            check(!r.route.controlsLg(r.clock.now()), "after an hour asleep the pre-sleep target is not acted on");
            link.reconnectNow();
            r.expect("lost SILENT", 1500, "screen on: the dead link is dropped");
            r.expect("opened", 1500, "screen on: reconnects at once, not after the backoff");
            r.expect("event example.video", 1500, "fresh state after waking");
            check(link.healthy() && r.route.controlsLg(r.clock.now()) && ha.connections.get() == 2,
                "healthy again on the new connection");
            link.stop();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
