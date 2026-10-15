package io.github.haydengriffin.prism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The LG settings the Shield can change directly through Glasshouse's Home Assistant entities, so common changes
 * need no menu driving. Deliberately excluded: sound output (the AV receiver depends on it) and energy saving's
 * screen-off step (it blanks the panel).
 */
final class TvSettings {
    static final class Row {
        /** Glasshouse's entity role, as TvProfile.settings keys it. */
        final String role;
        final String label;
        final boolean number;
        Row(String role, String label, boolean number) { this.role = role; this.label = label; this.number = number; }
        boolean number() { return number; }
    }
    static final List<Row> ROWS = Collections.unmodifiableList(Arrays.asList(
        new Row("input_source", "Input", false),
        new Row("picture_mode", "Picture mode", false),
        new Row("oled_light", "OLED brightness", true),
        new Row("energy_saving", "Energy saving", false),
        new Row("sound_mode", "Sound mode", false),
        new Row("sleep_timer", "Sleep timer", false),
        new Row("oled_logo_dimming", "Logo dimming", false)));
    /** One row's choices and values: what the TV last accepted, and what the row shows while it is being changed. */
    static final class Value {
        final Row row;
        final List<String> choices;
        final int min, max;
        String confirmed, shown;
        /** Labels for particular choices (the box's own input reads its name); the choice itself is what is sent. */
        private final java.util.Map<String, String> names = new java.util.HashMap<>();
        Value(Row row, String current, List<String> options, int min, int max) {
            this.row = row; this.choices = choices(options); this.min = min; this.max = max;
            confirmed = shown = row.number() ? String.valueOf(number(current)) : current; // HA may report 80.0
        }
        String move(int direction) {
            shown = row.number() ? String.valueOf(stepNumber(number(shown), direction, min, max)) : step(choices, shown, direction);
            return shown;
        }
        String advance() {
            if (!row.number()) shown = next(choices, shown);
            else shown = String.valueOf(number(shown) >= max ? min : stepNumber(number(shown), 1, min, max));
            return shown;
        }
        boolean changed() { return !shown.equals(confirmed); }
        String text() { return names.containsKey(shown) ? names.get(shown) : display(shown); }
        Value named(String choice, String label) {
            if (choice != null && !choice.isEmpty()) names.put(choice, label);
            return this;
        }
        private static int number(String value) {
            try { return (int) Math.round(Double.parseDouble(value)); } catch (NumberFormatException e) { return 0; }
        }
    }
    static final int NUMBER_STEP = 5;
    private static final List<String> EXCLUDED = Arrays.asList("screen_off");
    private TvSettings() {}

    static Row row(String role) {
        for (Row row : ROWS) if (row.role.equals(role)) return row;
        return null;
    }
    static List<String> choices(List<String> options) {
        List<String> out = new ArrayList<>();
        for (String option : options) if (!EXCLUDED.contains(option)) out.add(option);
        return out;
    }
    /** The neighbouring choice, stopping at either end; an unknown current value starts from the first. */
    static String step(List<String> choices, String current, int direction) {
        int at = choices.indexOf(current);
        if (at < 0) return choices.isEmpty() ? current : choices.get(0);
        return choices.get(Math.max(0, Math.min(choices.size() - 1, at + direction)));
    }
    /** OK moves forward and wraps, so one button can reach every choice. */
    static String next(List<String> choices, String current) {
        int at = choices.indexOf(current);
        return choices.isEmpty() ? current : choices.get((at + 1) % choices.size());
    }
    static int stepNumber(int current, int direction, int min, int max) {
        int stepped = (int) Math.round((current + direction * NUMBER_STEP) / (double) NUMBER_STEP) * NUMBER_STEP;
        return Math.max(min, Math.min(max, stepped));
    }
    static String display(String value) {
        switch (value) {
            case "auto": return "Auto";
            case "off": return "Off";
            case "min": return "Minimum";
            case "med": return "Medium";
            case "max": return "Maximum";
            default: return value;
        }
    }
}
