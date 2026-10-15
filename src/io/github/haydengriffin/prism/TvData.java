package io.github.haydengriffin.prism;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads what the launcher shows from Home Assistant: the LG's app catalog, its recent use, and setting values. */
final class TvData {
    static final int HISTORY_DAYS = 14;

    private TvData() {}

    /**
     * Every app the Launch App select offers, with Glasshouse's app id, icon URL and tile colour where known.
     * Empty while the TV is off (callers say so); only a present select without app ids asks for a Glasshouse update.
     */
    static List<Apps.App> catalog(HaHttp api, TvProfile tv) throws IOException {
        try {
            JSONObject state = new JSONObject(api.browse("states/" + tv.launch));
            JSONObject attributes = state.optJSONObject("attributes");
            if (attributes == null) attributes = new JSONObject();
            JSONArray options = attributes.optJSONArray("options");
            JSONObject ids = attributes.optJSONObject("app_ids");
            JSONObject icons = attributes.optJSONObject("app_icons");
            JSONObject colors = attributes.optJSONObject("app_colors");
            Apps.Catalog known = Apps.catalog(state.optString("state"), ids != null);
            if (known == Apps.Catalog.TV_OFF) return new ArrayList<>();
            if (known == Apps.Catalog.OLD_GLASSHOUSE) throw new IOException("Update Glasshouse: the TV's app IDs are missing.");
            List<Apps.App> apps = new ArrayList<>();
            for (int i = 0; options != null && i < options.length(); i++) {
                String title = options.getString(i);
                String id = ids.optString(title, "");
                if (id.isEmpty()) continue;
                apps.add(new Apps.App(id, title, optional(icons, id), optional(colors, id)));
            }
            return apps;
        } catch (JSONException e) {
            throw new IOException("The TV's app list was not readable.");
        }
    }

    /** Active-app values (titles or ids) over the last fortnight, oldest first. */
    static List<String> history(HaHttp api, TvProfile tv) throws IOException {
        try {
            JSONArray series = new JSONArray(api.browse(Apps.historyPath(tv.active, System.currentTimeMillis(), HISTORY_DAYS)));
            List<String> values = new ArrayList<>();
            JSONArray rows = series.length() == 0 ? new JSONArray() : series.getJSONArray(0);
            for (int i = 0; i < rows.length(); i++) values.add(rows.getJSONObject(i).optString("state"));
            return values;
        } catch (JSONException e) {
            throw new IOException("The TV's app history was not readable.");
        }
    }

    /** The current value of one Quick TV setting, or null when the TV does not offer it right now. */
    static TvSettings.Value setting(HaHttp api, TvSettings.Row row, String entity) throws IOException {
        try {
            JSONObject state = new JSONObject(api.browse("states/" + entity));
            String value = state.optString("state");
            if (value.isEmpty() || value.equals("unavailable") || value.equals("unknown")) return null;
            JSONObject attributes = state.getJSONObject("attributes");
            List<String> options = new ArrayList<>();
            JSONArray list = attributes.optJSONArray("options");
            for (int i = 0; list != null && i < list.length(); i++) options.add(list.getString(i));
            return new TvSettings.Value(row, value, options, attributes.optInt("min", 0), attributes.optInt("max", 100));
        } catch (JSONException e) {
            throw new IOException("A TV setting was not readable.");
        }
    }

    private static String optional(JSONObject map, String key) {
        if (map == null) return null;
        String value = map.optString(key, "");
        return value.isEmpty() ? null : value;
    }
}
