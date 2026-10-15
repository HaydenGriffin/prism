package io.github.haydengriffin.prism;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input devices seen pressing keys, and whether each one controls the LG. Keyed by Android's input device
 * descriptor (stable across reboots), or by name when there is none. No Android types: runs on the host JVM.
 */
final class Remotes {
    /** Oldest-seen devices beyond this are forgotten, so a stream of new virtual devices can't grow the list. */
    static final int MAX = 24;

    static final class Device {
        final String id;
        final String name;
        final RemotePolicy.Kind kind;
        /** The user's choice, or null for the kind's default. */
        final Boolean chosen;

        Device(String id, String name, RemotePolicy.Kind kind, Boolean chosen) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.chosen = chosen;
        }

        boolean controls() {
            return chosen != null ? chosen : RemotePolicy.controlsByDefault(kind);
        }
    }

    private final Map<String, Device> devices = new LinkedHashMap<>();

    /** The descriptor when Android gives one, else the name; never empty. */
    static String id(String descriptor, String name) {
        if (descriptor != null && !descriptor.isEmpty()) return descriptor;
        return "name:" + (name == null || name.isEmpty() ? "unnamed" : name);
    }

    /** Records a key press from a device. @return true when the list changed and should be saved */
    synchronized boolean seen(String id, String name, RemotePolicy.Kind kind) {
        String label = name == null || name.isEmpty() ? "Unnamed device" : name;
        Device known = devices.get(id);
        if (known != null && known.name.equals(label) && known.kind == kind) return false;
        devices.put(id, new Device(id, label, kind, known == null ? null : known.chosen));
        while (devices.size() > MAX) {
            Iterator<String> oldest = devices.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Whether a press from this device may go to the LG; a device not seen before gets its kind's default. */
    synchronized boolean controls(String id, RemotePolicy.Kind kind) {
        Device known = devices.get(id);
        if (known == null || known.chosen == null) return RemotePolicy.controlsByDefault(kind);
        return known.chosen;
    }

    synchronized void choose(String id, boolean on) {
        Device known = devices.get(id);
        if (known != null) devices.put(id, new Device(id, known.name, known.kind, on));
    }

    /** Most recently added last. */
    synchronized List<Device> list() {
        return new ArrayList<>(devices.values());
    }

    synchronized String toJson() {
        StringBuilder out = new StringBuilder("[");
        for (Device d : devices.values()) {
            if (out.length() > 1) out.append(',');
            out.append("{\"id\":").append(Json.quote(d.id))
                .append(",\"name\":").append(Json.quote(d.name))
                .append(",\"kind\":").append(Json.quote(d.kind.name()));
            if (d.chosen != null) out.append(",\"on\":").append(d.chosen);
            out.append('}');
        }
        return out.append(']').toString();
    }

    /** Tolerant: an unreadable list starts empty, an unreadable entry is skipped. */
    static Remotes fromJson(String text) {
        Remotes remotes = new Remotes();
        if (text == null || text.isEmpty()) return remotes;
        try {
            for (Object item : Json.array(Json.parse(text))) {
                Map<String, Object> entry = Json.object(item);
                String id = Json.text(entry.get("id"));
                String name = Json.text(entry.get("name"));
                RemotePolicy.Kind kind = kind(Json.text(entry.get("kind")));
                if (id.isEmpty() || kind == null) continue;
                Object on = entry.get("on");
                remotes.devices.put(id, new Device(id, name.isEmpty() ? "Unnamed device" : name, kind,
                    on instanceof Boolean ? (Boolean) on : null));
            }
        } catch (IOException | RuntimeException e) {
            return new Remotes();
        }
        return remotes;
    }

    private static RemotePolicy.Kind kind(String name) {
        for (RemotePolicy.Kind k : RemotePolicy.Kind.values()) if (k.name().equals(name)) return k;
        return null;
    }
}
