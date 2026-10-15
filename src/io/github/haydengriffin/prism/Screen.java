package io.github.haydengriffin.prism;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The look: a solid night ground, panel tiles, sky focus ring with a small lift, Atkinson words and
 * Lexend numerals. Sizes are for the Shield's 960×540 dp screen read from the sofa.
 */
final class Screen {
    /** Overscan-safe margins for a TV. */
    static final int EDGE_X = 48, EDGE_Y = 28, GAP = 14;
    private final Activity activity;
    private final Typeface words, wordsBold, numerals, numeralsBold;
    LinearLayout content;
    private TextView status;
    private View statusDot;
    private TextView subtitle;
    private LinearLayout header;

    Screen(Activity activity) {
        this.activity = activity;
        words = activity.getResources().getFont(R.font.atkinson_regular);
        wordsBold = activity.getResources().getFont(R.font.atkinson_semibold);
        numerals = activity.getResources().getFont(R.font.lexend_regular);
        numeralsBold = activity.getResources().getFont(R.font.lexend_semibold);
    }

    int dp(float n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    int color(int id) { return activity.getResources().getColor(id, activity.getTheme()); }

    /** A new page: title and subtitle on the left, the live status on the right. */
    void page(String title, String sub) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(EDGE_X), dp(EDGE_Y), dp(EDGE_X), dp(EDGE_Y));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content);
        activity.setContentView(scroll);

        header = new LinearLayout(activity);
        header.setTag(HEADER);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(10));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(title, 30, R.color.text, true));
        subtitle = text(sub == null ? "" : sub, 17, R.color.muted, false);
        subtitle.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout live = new LinearLayout(activity);
        live.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(activity);
        live.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        status = text("", 19, R.color.text, true);
        status.setPadding(dp(10), 0, 0, 0);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        live.addView(status);
        header.addView(live, new LinearLayout.LayoutParams(-2, -2));
        content.addView(header);
    }

    /** Tag of the page header: initial focus skips it (its icon button is reached with Up). */
    static final String HEADER = "header";

    /** A small round icon button at the header's end, after the live line (Setup on the launcher). */
    ImageView headerButton(int iconRes, String description, Runnable action) {
        ImageView b = icon(iconRes, R.color.text, 22);
        b.setBackground(oval(color(R.color.raised)));
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setContentDescription(description);
        focusable(b, 999, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMarginStart(dp(20));
        header.addView(b, lp);
        return b;
    }

    /** The live line. Dot style carries meaning with the words, never alone. */
    void status(String text, Status.Tone tone) {
        if (status == null) return;
        if (!status.getText().toString().equals(text)) status.setText(text);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        int c = color(tone == Status.Tone.SKY ? R.color.accent : tone == Status.Tone.WARN ? R.color.warn
            : tone == Status.Tone.ALERT ? R.color.error : tone == Status.Tone.NEUTRAL ? R.color.text : R.color.muted);
        if (tone == Status.Tone.WARN || tone == Status.Tone.QUIET) dot.setStroke(dp(2), c); // outlined: needs a look, or idle
        else dot.setColor(c);
        statusDot.setBackground(dot);
        status.setTextColor(color(tone == Status.Tone.ALERT ? R.color.error : tone == Status.Tone.QUIET ? R.color.muted : R.color.text));
    }

    void subtitle(String text) {
        subtitle.setText(text);
        subtitle.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Text in Atkinson with every digit in Lexend: Atkinson's slashed zero reads as
     * "Ø" from the sofa. Applies to text set later too (values, notes, the live line).
     */
    static class Words extends TextView {
        private final Typeface regular, bold;
        private Typeface numerals;

        Words(android.content.Context context, Typeface regular, Typeface bold) {
            super(context);
            this.regular = regular;
            this.bold = bold;
            numerals = regular;
            setText(getText()); // the constructor's own setText ran before the numeral faces were set
        }

        /** Digits follow the words' weight: semibold Lexend beside semibold Atkinson. */
        void weight(boolean semibold) {
            numerals = semibold ? bold : regular;
            setText(getText().toString());
        }

        @Override public void setText(CharSequence text, BufferType type) {
            super.setText(numerals == null ? text : digits(text, numerals), type == BufferType.NORMAL ? BufferType.SPANNABLE : type);
        }

        private static CharSequence digits(CharSequence text, Typeface numerals) {
            if (text == null || Build.VERSION.SDK_INT < 28) return text;
            SpannableString out = new SpannableString(text);
            for (int i = 0; i < text.length(); ) {
                if (!Character.isDigit(text.charAt(i))) { i++; continue; }
                int end = i;
                while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
                out.setSpan(new TypefaceSpan(numerals), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
            }
            return out;
        }
    }

    TextView text(String value, float sp, int colorId, boolean bold) {
        Words t = new Words(activity, numerals, numeralsBold);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color(colorId));
        t.setTypeface(bold ? wordsBold : words);
        t.weight(bold);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    /** A section label: sentence case, muted, more space above than below. */
    void section(String label) {
        TextView t = text(label, 17, R.color.muted, false);
        t.setPadding(dp(2), dp(4), 0, dp(7));
        content.addView(t);
    }

    /** A line of explanation or a message inside the page (not the live status). */
    /** A message line in the page; hidden while empty so it takes no room. */
    TextView note(String value, boolean error) {
        TextView t = new Words(activity, numerals, numeralsBold) {
            @Override public void setText(CharSequence text, BufferType type) {
                super.setText(text, type);
                setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
            }
        };
        t.setTextSize(18);
        t.setTypeface(words);
        t.setTextColor(color(error ? R.color.error : R.color.muted));
        t.setText(value);
        t.setPadding(dp(2), 0, 0, dp(12));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(t);
        return t;
    }

    /** A row of equal tiles; add tiles with tile(). Empty slots keep the grid's columns steady. */
    LinearLayout grid(int columns, float heightDp, int count) {
        LinearLayout row = new LinearLayout(activity);
        row.setClipChildren(false);
        row.setWeightSum(columns);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.bottomMargin = dp(GAP);
        content.addView(row, lp);
        return row;
    }

    /** A focusable tile: optional icon disc or app art, a label, an optional second line. */
    FrameLayout tile(LinearLayout row, String label, String detail, int iconRes, Runnable action) {
        FrameLayout tile = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        row.addView(tile, lp);
        tile.setBackground(surface(R.color.panel, 22));
        focusable(tile, 22, action);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));
        body.setGravity(Gravity.BOTTOM);
        tile.addView(body, new FrameLayout.LayoutParams(-1, -1));
        if (iconRes != 0) {
            ImageView icon = icon(iconRes, R.color.text, 26);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.START);
            ip.setMargins(dp(18), dp(16), 0, 0);
            tile.addView(icon, ip);
        }
        TextView name = text(label, 19, R.color.text, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView second = text(detail, 16, R.color.muted, false);
            second.setPadding(0, dp(4), 0, 0);
            second.setMaxLines(1);
            second.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(second);
        }
        tile.setContentDescription(detail == null ? label : label + ", " + detail);
        return tile;
    }

    /** A full-width tile: the app's art on its own colour at the start, one line of words after it. */
    FrameLayout wideAppTile(LinearLayout row, Apps.App app, String label, IconCache icons, Runnable action) {
        FrameLayout tile = new FrameLayout(activity);
        row.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
        tile.setBackground(surface(R.color.panel, 22));
        focusable(tile, 22, action);
        LinearLayout body = new LinearLayout(activity);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), dp(12), dp(24), dp(12));
        tile.addView(body, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout art = new FrameLayout(activity);
        int ground = appColor(app);
        art.setBackground(solid(ground, 16));
        TextView letter = new TextView(activity);
        letter.setText(app.initial());
        letter.setTypeface(wordsBold);
        letter.setTextSize(30);
        letter.setTextColor(color(luminance(ground) > 0.45 ? R.color.ink : R.color.text));
        letter.setGravity(Gravity.CENTER);
        art.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (app.icon != null) {
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -1);
            pp.setMargins(dp(8), dp(8), dp(8), dp(8));
            art.addView(picture, pp);
            icons.show(app.id, app.icon, picture);
            picture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                letter.setVisibility(picture.getDrawable() == null ? View.VISIBLE : View.INVISIBLE));
        }
        body.addView(art, new LinearLayout.LayoutParams(dp(72), -1));
        TextView name = text(label, 21, R.color.text, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
        np.setMarginStart(dp(18));
        body.addView(name, np);
        tile.setContentDescription(label);
        return tile;
    }

    /** A quiet line holding empty grid columns (an empty row's place), never focusable. */
    TextView quiet(LinearLayout row, String value, int columns) {
        TextView t = text(value, 17, R.color.muted, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(4), 0, dp(4), 0);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, columns);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        lp.setMarginEnd(dp(GAP * (columns - 1))); // the gaps it spans, so later tiles keep the grid's columns
        row.addView(t, lp);
        return t;
    }

    /** An app tile: the app's icon on its own LG colour, or its initial on a muted hue; title under it. */
    FrameLayout appTile(LinearLayout row, Apps.App app, boolean marked, IconCache icons, Runnable action) {
        FrameLayout tile = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        row.addView(tile, lp);
        int ground = appColor(app);
        tile.setBackground(solid(ground, 22));
        focusable(tile, 22, action);

        TextView letter = new TextView(activity);
        letter.setText(app.initial());
        letter.setTypeface(wordsBold);
        letter.setTextSize(40);
        letter.setTextColor(color(R.color.text));
        letter.setGravity(Gravity.CENTER);
        tile.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (app.icon != null) {
            ImageView art = new ImageView(activity);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -1);
            ap.setMargins(dp(14), dp(12), dp(14), dp(34));
            tile.addView(art, ap);
            icons.show(app.id, app.icon, art);
            art.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                letter.setVisibility(art.getDrawable() == null ? View.VISIBLE : View.INVISIBLE));
        }
        boolean light = luminance(ground) > 0.45;
        TextView name = text(app.title, 16, R.color.text, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        if (light) name.setTextColor(color(R.color.ink));
        else name.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        if (light) letter.setTextColor(color(R.color.ink));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(dp(10), 0, dp(10), dp(10));
        tile.addView(name, np);
        if (marked) {
            ImageView star = icon(R.drawable.ic_star, R.color.accent_ink, 20);
            star.setBackground(oval(color(R.color.accent)));
            star.setPadding(dp(5), dp(5), dp(5), dp(5));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.END);
            sp.setMargins(0, dp(10), dp(10), 0);
            tile.addView(star, sp);
        }
        tile.setContentDescription(app.title + (marked ? ", favourite" : ""));
        return tile;
    }

    /** Placeholder keeping a grid row's columns when it has fewer tiles than columns. */
    void spacer(LinearLayout row) {
        View v = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        row.addView(v, lp);
    }

    /** A full-width list row: icon disc, label, value on the right, chevron when it opens another page. */
    LinearLayout listRow(int iconRes, String label, String value, boolean opens, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(18), 0);
        row.setBackground(surface(R.color.panel, 18));
        focusable(row, 18, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.bottomMargin = dp(8);
        content.addView(row, lp);
        if (iconRes != 0) {
            ImageView icon = icon(iconRes, R.color.text, 22);
            icon.setBackground(oval(color(R.color.raised)));
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
            ip.setMarginEnd(dp(14));
            row.addView(icon, ip);
        }
        row.addView(text(label, 19, R.color.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value == null ? "" : value, 18, R.color.muted, false);
        v.setTag("value");
        row.addView(v);
        if (opens) {
            ImageView chevron = icon(R.drawable.ic_chevron, R.color.muted, 24);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(24), dp(24));
            cp.setMarginStart(dp(8));
            row.addView(chevron, cp);
        }
        row.setContentDescription(value == null || value.isEmpty() ? label : label + ", " + value);
        return row;
    }

    static void setValue(LinearLayout row, String value) {
        TextView v = row.findViewWithTag("value");
        if (v != null) v.setText(value);
    }

    /** A setting row whose value is stepped with Left/Right: chevrons either side of the value. */
    LinearLayout stepperRow(int iconRes, String label, String value, boolean numeric) {
        LinearLayout row = listRow(iconRes, label, null, false, () -> {});
        TextView old = row.findViewWithTag("value");
        row.removeView(old);
        LinearLayout stepper = new LinearLayout(activity);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.addView(icon(R.drawable.ic_chevron_left, R.color.muted, 24), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView v = text(value, 18, R.color.text, true);
        if (numeric) v.setTypeface(numeralsBold);
        v.setTag("value");
        v.setMinWidth(dp(150));
        v.setGravity(Gravity.CENTER);
        stepper.addView(v);
        stepper.addView(icon(R.drawable.ic_chevron, R.color.muted, 24), new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.addView(stepper);
        return row;
    }

    EditText field(String label, String value, boolean secret) {
        TextView caption = text(label, 17, R.color.muted, false);
        caption.setPadding(dp(2), dp(4), 0, dp(6));
        content.addView(caption);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextSize(19);
        input.setTypeface(words);
        input.setTextColor(color(R.color.text));
        input.setHintTextColor(color(R.color.muted));
        input.setBackground(surface(R.color.panel, 16));
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(value);
        input.setOnFocusChangeListener((v, focused) -> v.setBackground(focused ? ringed(R.color.panel, 16) : surface(R.color.panel, 16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(60));
        lp.bottomMargin = dp(12);
        content.addView(input, lp);
        return input;
    }

    /** A pill button for a page's one action (Connect, Choose favourites…). */
    TextView pill(ViewGroup parent, String label, Runnable action) {
        TextView b = text(label, 18, R.color.text, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(26), 0, dp(26), 0);
        b.setBackground(surface(R.color.raised, 999));
        focusable(b, 999, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(56));
        lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    // Focus: a sky ring and a small lift, eased out. The ring is drawn inside the view's own background.
    private void focusable(View v, int radius, Runnable action) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
        android.graphics.drawable.Drawable rest = v.getBackground();
        v.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.setForeground(ring(radius));
            else view.setForeground(null);
            view.animate().scaleX(focused ? 1.045f : 1f).scaleY(focused ? 1.045f : 1f)
                .translationZ(focused ? dp(10) : 0).setDuration(140).setInterpolator(new DecelerateInterpolator(2f)).start();
        });
        v.setBackground(rest);
    }

    private GradientDrawable ring(int radius) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(radius));
        ring.setStroke(dp(3), color(R.color.accent));
        return ring;
    }

    private GradientDrawable surface(int colorId, int radius) { return solid(color(colorId), radius); }

    private GradientDrawable ringed(int colorId, int radius) {
        GradientDrawable g = surface(colorId, radius);
        g.setStroke(dp(3), color(R.color.accent));
        return g;
    }

    private GradientDrawable solid(int c, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private static GradientDrawable oval(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c);
        return g;
    }

    ImageView icon(int res, int tintId, int sizeDp) {
        ImageView i = new ImageView(activity);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color(tintId)));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return i;
    }

    /** Relative luminance (WCAG) of an opaque colour, to pick dark or light words on an app's own tile colour. */
    static double luminance(int c) {
        double[] rgb = { Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0 };
        for (int i = 0; i < 3; i++) rgb[i] = rgb[i] <= 0.03928 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }

    // Muted hues for lettered tiles, dark enough for warm-white initials at 4.5:1.
    static final int[] HUES = { 0xFF2C3E5C, 0xFF3D3152, 0xFF2D4A3E, 0xFF4D3528, 0xFF23434C, 0xFF4B2F3B };

    private int appColor(Apps.App app) {
        if (app.color != null) {
            try { return Color.parseColor(app.color); } catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        return app.icon != null ? color(R.color.panel) : HUES[Math.abs(app.id.hashCode()) % HUES.length];
    }
}
