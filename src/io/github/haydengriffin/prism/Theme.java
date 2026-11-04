package io.github.haydengriffin.prism;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The looks Prism can wear. Plain ARGB ints and no Android types, so contrast can be tested on the host. */
final class Theme {
    static final Theme REFRACT = new Theme("refract", "Refract", true, 0xFF0E0F12, 0xFF0E0F12, 0x13FFFFFF, 0x24FFFFFF, 0xFF17191E,
        0xFFF8F1E8, 0xFFD8C9B8, 0xFFFFFFFF, 0xFF0E0F12, 0xFFFFB27A, 0xFFFF8A80, 0xFF80D8FF, 0xFFFF9D5C);
    static final Theme OLED_NIGHT = new Theme("oled", "OLED Night", false, 0xFF000000, 0xFF000000, 0xFF121317, 0xFF1D1F25, 0xFF0D0E11,
        0xFFF4F2EE, 0xFFB9B4AC, 0xFF80D8FF, 0xFF04222E, 0xFFFF9D5C, 0xFFFF8A80, 0xFF80D8FF, 0xFFFF9D5C);
    static final Theme DUSK = new Theme("dusk", "Dusk", false, 0xFF1B0F18, 0xFF351A12, 0xFF3A221C, 0xFF4A2D25, 0xFF2A1714,
        0xFFF8ECE0, 0xFFD9BFB0, 0xFFFF9D5C, 0xFF2A1206, 0xFFFFC36B, 0xFFFF8A80, 0xFF80D8FF, 0xFFFF9D5C);
    static final Theme DAYLIGHT = new Theme("daylight", "Daylight", false, 0xFFEBE8E1, 0xFFEBE8E1, 0xFFFFFFFF, 0xFFDFDBD2, 0xFFF6F4EF,
        0xFF16181D, 0xFF5D5B55, 0xFF1D8AC0, 0xFFFFFFFF, 0xFF9E4A1A, 0xFFB3261E, 0xFF1D8AC0, 0xFFD4632A);
    static final List<Theme> ALL = Collections.unmodifiableList(Arrays.asList(REFRACT, OLED_NIGHT, DUSK, DAYLIGHT));

    /** The brightest a wash may make the ground, as WCAG luminance, so every word on it stays readable. */
    static final double WASH_CEILING = 0.085;
    private static final double WASH_ALPHA = 0.5;
    /** Used when the focused tile has no colour of its own, or a white, black or grey one. */
    private static final int NEUTRAL_WASH = 0xFF2F6F8F;

    final String id, name;
    /** The ground takes a soft light in the focused tile's own colour. */
    final boolean washes;
    final int groundTop, groundBottom, panel, raised, sheet, text, muted, accent, accentInk, warn, error, sky, amber;

    private Theme(String id, String name, boolean washes, int groundTop, int groundBottom, int panel, int raised, int sheet, int text,
                  int muted, int accent, int accentInk, int warn, int error, int sky, int amber) {
        this.id = id;
        this.name = name;
        this.washes = washes;
        this.groundTop = groundTop;
        this.groundBottom = groundBottom;
        this.panel = panel;
        this.raised = raised;
        this.sheet = sheet;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.accentInk = accentInk;
        this.warn = warn;
        this.error = error;
        this.sky = sky;
        this.amber = amber;
    }

    /** The saved theme, unknown ids reading as the default. */
    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return REFRACT;
    }

    /** The theme after this one in the Setup row, wrapping round. */
    Theme next() { return ALL.get((ALL.indexOf(this) + 1) % ALL.size()); }

    /** What to wear now: Dusk once the sun is known to be down, when Setup asks for that. */
    static Theme resolve(String id, boolean duskAfterSunset, boolean sunDown) {
        return duskAfterSunset && sunDown ? DUSK : byId(id);
    }

    /**
     * The wash for an app's colour, with the alpha to draw it at. Neutral colours would only grey the ground, so they
     * share one quiet wash; bright colours are drawn fainter, so the brightest point stays under WASH_CEILING.
     */
    static int wash(int rgb) {
        double[] hsv = hsv(rgb);
        int colour = hsv[1] < 0.25 || hsv[2] < 0.2 ? NEUTRAL_WASH : fromHsv(hsv[0], Math.min(hsv[1], 0.85), Math.min(hsv[2], 0.8));
        double alpha = Math.min(WASH_ALPHA, WASH_CEILING / Math.max(luminance(colour), 0.001));
        return ((int) Math.round(alpha * 255) << 24) | (colour & 0xFFFFFF);
    }

    /** `top` (with its alpha) over the opaque `under`. */
    static int over(int top, int under) {
        double a = (top >>> 24) / 255.0;
        int out = 0xFF;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int mixed = (int) Math.round(((top >> shift) & 0xFF) * a + ((under >> shift) & 0xFF) * (1 - a));
            out = (out << 8) | mixed;
        }
        return out;
    }

    /** WCAG contrast of two opaque colours. */
    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG relative luminance of a colour, ignoring its alpha. */
    static double luminance(int c) {
        double[] rgb = { ((c >> 16) & 0xFF) / 255.0, ((c >> 8) & 0xFF) / 255.0, (c & 0xFF) / 255.0 };
        for (int i = 0; i < 3; i++) rgb[i] = rgb[i] <= 0.03928 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    private static double[] hsv(int c) {
        double r = ((c >> 16) & 0xFF) / 255.0, g = ((c >> 8) & 0xFF) / 255.0, b = (c & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), span = max - min, h = 0;
        if (span > 0) {
            if (max == r) h = (g - b) / span;
            else if (max == g) h = 2 + (b - r) / span;
            else h = 4 + (r - g) / span;
            h = (h * 60 + 360) % 360;
        }
        return new double[] { h, max == 0 ? 0 : span / max, max };
    }

    private static int fromHsv(double h, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double[][] sextant = { { c, x, 0 }, { x, c, 0 }, { 0, c, x }, { 0, x, c }, { x, 0, c }, { c, 0, x } };
        double[] rgb = sextant[(int) (h / 60) % 6];
        return 0xFF000000 | channel(rgb[0] + m) << 16 | channel(rgb[1] + m) << 8 | channel(rgb[2] + m);
    }

    private static int channel(double v) { return (int) Math.round(v * 255); }
}
