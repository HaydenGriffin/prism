package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Glasshouse TVs in HA's entity and device registries. A TV is a device with an enabled select whose
 * unique_id ends in "_app"; its other entities share that unique_id prefix on the same device.
 */
final class TvRegistry {

    /** One TV device: its name, the launch select, and its enabled entities by unique_id suffix. */
    static final class Candidate {
        final String name;
        final String device;
        final String launch;
        private final Map<String, List<String>> roles;

        Candidate(String name, String device, String launch, Map<String, List<String>> roles) {
            this.name = name;
            this.device = device;
            this.launch = launch;
            this.roles = roles;
        }

        /** The entity with this unique_id suffix in this domain, or null. */
        String role(String suffix, String domain) {
            List<String> ids = roles.get(suffix);
            if (ids == null) return null;
            for (String id : ids) if (id.startsWith(domain + ".")) return id;
            return null;
        }
    }

    private TvRegistry() {}

    static List<Candidate> candidates(List<?> entities, List<?> devices) {
        Map<String, String> names = deviceNames(devices);
        Map<String, List<Map<String, Object>>> byDevice = enabledByDevice(entities);
        List<Candidate> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> device : byDevice.entrySet()) {
            for (Map<String, Object> entity : device.getValue()) {
                String uid = Json.text(entity.get("unique_id"));
                String id = Json.text(entity.get("entity_id"));
                if (!id.startsWith("select.") || !uid.endsWith("_app")) continue;
                String prefix = uid.substring(0, uid.length() - "_app".length()) + "_";
                String name = names.containsKey(device.getKey()) ? names.get(device.getKey()) : "LG TV";
                result.add(new Candidate(name, device.getKey(), id, roles(device.getValue(), prefix)));
            }
        }
        return result;
    }

    private static Map<String, String> deviceNames(List<?> devices) {
        Map<String, String> names = new HashMap<>();
        for (Object item : devices) {
            Map<String, Object> device = Json.object(item);
            String name = Json.text(device.get("name_by_user"));
            if (name.isEmpty()) name = Json.text(device.get("name"));
            names.put(Json.text(device.get("id")), name.isEmpty() ? "LG TV" : name);
        }
        return names;
    }

    private static Map<String, List<Map<String, Object>>> enabledByDevice(List<?> entities) {
        // Insertion order keeps TVs in registry order.
        Map<String, List<Map<String, Object>>> byDevice = new LinkedHashMap<>();
        for (Object item : entities) {
            Map<String, Object> entity = Json.object(item);
            String device = Json.text(entity.get("device_id"));
            if (device.isEmpty() || entity.get("disabled_by") != null) continue;
            if (!byDevice.containsKey(device)) byDevice.put(device, new ArrayList<Map<String, Object>>());
            byDevice.get(device).add(entity);
        }
        return byDevice;
    }

    private static Map<String, List<String>> roles(List<Map<String, Object>> entities, String prefix) {
        Map<String, List<String>> roles = new HashMap<>();
        for (Map<String, Object> entity : entities) {
            String uid = Json.text(entity.get("unique_id"));
            if (!uid.startsWith(prefix)) continue;
            String suffix = uid.substring(prefix.length());
            if (!roles.containsKey(suffix)) roles.put(suffix, new ArrayList<String>());
            roles.get(suffix).add(Json.text(entity.get("entity_id")));
        }
        for (List<String> ids : roles.values()) Collections.sort(ids);
        return roles;
    }
}
