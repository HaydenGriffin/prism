package io.github.haydengriffin.prism;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * What one link session knows about the TV: the mirrored entities, the catalog and target they give the route, and
 * the status to show. A missing catalog (TV off, select unavailable) is a state, not a failure. Callers serialise.
 */
final class TvState {

    private final AutoRoute route;
    private final ForegroundState foreground;
    private final String shieldApp;
    private Map<String, String> catalog = Collections.emptyMap();
    /** App id to catalog title. */
    private Map<String, String> titles = Collections.emptyMap();

    TvState(AutoRoute route, String active, String launch, String shieldApp) {
        this.route = route;
        this.foreground = new ForegroundState(active, launch);
        this.shieldApp = shieldApp;
    }

    /** A new connection: nothing is known until HA sends the full state. */
    void opened() {
        foreground.clear();
        configure(Collections.<String, String>emptyMap());
    }

    void apply(Map<String, Object> event, long now) {
        foreground.apply(event);
        Map<String, String> next = foreground.catalog();
        if (!next.equals(catalog)) configure(next);
        route.observe(foreground.target(), foreground.keyboard(), now);
    }

    void lost() {
        route.invalidate();
        foreground.clear();
    }

    RemoteState status(long now) {
        return RouteStatus.route(route.current(), route.paused(), route.controlsLg(now), route.catalogKnown(), shieldApp,
            titles, route.keyboard());
    }

    private void configure(Map<String, String> next) {
        catalog = next;
        route.configure(new HashSet<>(next.values()), shieldApp);
        Map<String, String> byId = new HashMap<>();
        for (Map.Entry<String, String> app : next.entrySet()) byId.put(app.getValue(), app.getKey());
        titles = byId;
    }
}
