package io.github.haydengriffin.prism;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
 * The look, in the colours of the active Theme: panel tiles over the theme's ground, a focus ring with a small lift,
 * Atkinson words and Lexend numerals. Sizes are for the Shield's 960×540 dp screen read from the sofa.
 */
final class Screen {
    /** Overscan-safe margins for a TV. */
    static final int EDGE_X = 48, EDGE_Y = LauncherLayout.EDGE, GAP = LauncherLayout.GAP;
    private static final int ROW = 50, ROW_GAP = 6, SHEET_WIDTH = 480;
    private static final float TILE_LIFT = 1.045f, BIG_LIFT = 1.02f;
    /** Light letters for a dark app colour, whatever the theme. */
    private static final int LETTER = 0xFFF4F2EE;
    private final Activity activity;
    private final Typeface words, wordsBold, numerals, numeralsBold;
    private Theme theme;
    private GroundView ground;
    private FrameLayout root;
    private ScrollView scroll;
    LinearLayout content;
    private TextView status;
    private Status.Tone statusTone;
    private BeamView beam;
    private TextView subtitle;
    private LinearLayout[] columns;
    private int nextColumn;
    /** While the sheet is up, what to slide away with the next frame. */
    private FrameLayout sheet;
    private View scrim;

    Screen(Activity activity, Theme theme) {
        this.activity = activity;
        words = activity.getResources().getFont(R.font.atkinson_regular);
        wordsBold = activity.getResources().getFont(R.font.atkinson_semibold);
        numerals = activity.getResources().getFont(R.font.lexend_regular);
        numeralsBold = activity.getResources().getFont(R.font.lexend_semibold);
        theme(theme);
    }

    Theme theme() { return theme; }

    /** Changes the look; pages drawn from now on wear it. */
    void theme(Theme next) {
        theme = next;
        ground = new GroundView(activity, next);
        activity.getWindow().setBackgroundDrawable(new ColorDrawable(next.groundTop));
    }

    int dp(float n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }

    /** A colour token in the active theme. */
    int color(int id) {
        if (id == R.color.surface) return theme.groundTop;
        if (id == R.color.panel) return theme.panel;
        if (id == R.color.raised) return theme.raised;
        if (id == R.color.text) return theme.text;
        if (id == R.color.muted) return theme.muted;
        if (id == R.color.accent) return theme.accent;
        if (id == R.color.accent_ink) return theme.accentInk;
        if (id == R.color.warn) return theme.warn;
        if (id == R.color.error) return theme.error;
        return activity.getResources().getColor(id, activity.getTheme());
    }

    /** A clean frame: the ground, and an empty scrolling column that only scrolls when a page outgrows the screen. */
    private void frame() {
        FrameLayout leaving = sheet;
        View fading = scrim;
        root = new FrameLayout(activity);
        if (ground.getParent() != null) ((ViewGroup) ground.getParent()).removeView(ground);
        root.addView(ground, new FrameLayout.LayoutParams(-1, -1));
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(28));
        scroll.setClipToPadding(false);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(EDGE_X), dp(EDGE_Y), dp(EDGE_X), dp(EDGE_Y));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(root);
        status = null;
        statusTone = null;
        beam = null;
        subtitle = null;
        columns = null;
        sheet = null;
        scrim = null;
        if (leaving != null) slideAway(leaving, fading);
    }

    /** The sheet over the page drawn under it: it goes on sliding while the page below is already fresh. */
    private void slideAway(FrameLayout leaving, View fading) {
        ((ViewGroup) fading.getParent()).removeView(fading);
        ((ViewGroup) leaving.getParent()).removeView(leaving);
        leaving.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        leaving.setFocusable(false);
        FrameLayout under = root;
        under.addView(fading, new FrameLayout.LayoutParams(-1, -1));
        under.addView(leaving, new FrameLayout.LayoutParams(dp(SHEET_WIDTH), -1, Gravity.END));
        fading.animate().alpha(0).setDuration(160).start();
        leaving.animate().translationX(dp(SHEET_WIDTH)).setDuration(160).setInterpolator(new DecelerateInterpolator())
            .withEndAction(() -> {
                under.removeView(leaving);
                under.removeView(fading);
            }).start();
    }

    /** A new page: title and subtitle on the left, the live status on the right. */
    void page(String title, String sub) {
        frame();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(title, 26, R.color.text, true));
        subtitle = text(sub == null ? "" : sub, 16, R.color.muted, false);
        subtitle.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout live = new LinearLayout(activity);
        live.setGravity(Gravity.CENTER_VERTICAL);
        beam = new BeamView(activity, theme.amber, theme.sky, theme.text, theme.groundTop);
        live.addView(beam, new LinearLayout.LayoutParams(dp(64), dp(20)));
        status = text("", 18, R.color.text, true);
        status.setPadding(dp(10), 0, 0, 0);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        live.addView(status);
        header.addView(live, new LinearLayout.LayoutParams(-2, -2));
        content.addView(header);
    }

    /** The launcher's page: no header, so the deck has the screen; statusLine() goes at its foot. */
    void deckPage() {
        frame();
    }

    /** The one line of chrome under the deck: the beam, where the remote goes in words, and which TV this is. */
    void statusLine(String box, String where) {
        View push = new View(activity);
        content.addView(push, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView from = text(box, 15, R.color.text, true);
        from.setTextColor(theme.amber);
        line.addView(from);
        beam = new BeamView(activity, theme.amber, theme.sky, theme.text, theme.groundTop);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(96), dp(20));
        bp.setMarginStart(dp(8));
        bp.setMarginEnd(dp(8));
        line.addView(beam, bp);
        TextView to = text("LG", 15, R.color.text, true);
        to.setTextColor(theme.sky);
        line.addView(to);
        status = text("", 17, R.color.text, true);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1);
        sp.setMarginStart(dp(18));
        line.addView(status, sp);
        TextView place = text(where, 16, R.color.muted, false);
        place.setMaxLines(1);
        place.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.setMarginStart(dp(18));
        line.addView(place, pp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(LauncherLayout.STATUS));
        lp.topMargin = dp(GAP);
        content.addView(line, lp);
    }

    /** The live line. The beam carries meaning with the words, never alone. */
    void status(String text, Status.Tone tone) {
        if (status == null) return;
        // Called twice a second: with nothing new to say, the page must not redraw (or stop being idle).
        if (tone == statusTone && status.getText().toString().equals(text)) return;
        statusTone = tone;
        status.setText(text);
        if (beam != null) beam.show(tone == Status.Tone.SKY ? BeamView.End.LG : tone == Status.Tone.NEUTRAL ? BeamView.End.BOX : BeamView.End.NONE);
        status.setTextColor(color(tone == Status.Tone.ALERT ? R.color.error : tone == Status.Tone.QUIET ? R.color.muted : R.color.text));
    }

    /** The tag of whatever has the focus, to put it back after a redraw. */
    String focusedTag() {
        View focused = content.findFocus();
        return focused == null || focused.getTag() == null ? null : focused.getTag().toString();
    }

    /**
     * A sheet from the right over the page, for settings you want to see working: the page stays lit behind it and
     * cannot take the focus. Rows added to `content` go in the sheet, until the next page is drawn and it slides away.
     */
    void openSheet(String title) {
        scrim = new View(activity);
        scrim.setBackgroundColor(Theme.luminance(theme.groundTop) > 0.5 ? 0x26000000 : 0x59000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sheet = new FrameLayout(activity);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(theme.sheet);
        float round = dp(28);
        plate.setCornerRadii(new float[] { round, round, 0, 0, 0, 0, round, round });
        sheet.setBackground(plate);
        sheet.setClipChildren(false);
        sheet.setFocusable(true); // holds the focus until its rows arrive, so OK cannot reach the page
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(EDGE_X), dp(16));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        sheet.addView(content, new FrameLayout.LayoutParams(-1, -1));
        TextView heading = text(title, 24, R.color.text, true);
        heading.setPadding(0, 0, 0, dp(8));
        content.addView(heading);
        root.addView(sheet, new FrameLayout.LayoutParams(dp(SHEET_WIDTH), -1, Gravity.END));
        sheet.requestFocus();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scrim.setAlpha(0);
        scrim.animate().alpha(1).setDuration(200).start();
        sheet.setTranslationX(dp(SHEET_WIDTH));
        sheet.animate().translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator(2.5f)).start();
    }

    /** The sheet's rows are up and one has the focus: the sheet itself stops being a place to land. */
    void sheetReady() {
        if (sheet != null) sheet.setFocusable(false);
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

    /** A message line in the page (not the live status); hidden while empty so it takes no room. */
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
        t.setPadding(0, 0, 0, dp(12));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(t);
        return t;
    }

    /** A row of equal tiles. Empty slots keep the grid's columns steady. */
    LinearLayout grid(int columns, float heightDp) {
        return grid(content, columns, heightDp);
    }

    /** A row of equal tiles in a column of its own (the launcher's grid, beside the big tile). */
    LinearLayout grid(ViewGroup parent, int columns, float heightDp) {
        LinearLayout row = new LinearLayout(activity);
        row.setClipChildren(false);
        row.setWeightSum(columns);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.bottomMargin = dp(GAP);
        parent.addView(row, lp);
        return row;
    }

    /** The deck's row: the big tile on the left, and the column of grid rows this returns on its right. */
    LinearLayout deckRow(float heightDp, java.util.function.Consumer<LinearLayout> bigTile) {
        LinearLayout row = new LinearLayout(activity);
        row.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
        lp.bottomMargin = dp(GAP);
        content.addView(row, lp);
        bigTile.accept(row);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
        cp.setMarginStart(dp(GAP));
        row.addView(column, cp);
        return column;
    }

    private static final int BIG_TILE_WIDTH = 300;

    /** The big tile: an app's art at its own size and colour, what pressing it does, and its name. */
    FrameLayout bigAppTile(LinearLayout row, Apps.App app, String label, IconCache icons, int artDp, Runnable action) {
        FrameLayout tile = bigTile(row, action, appColor(app));
        LinearLayout body = (LinearLayout) tile.getChildAt(0);
        body.addView(chip(app, icons, artDp, Math.round(artDp * 0.48f)), 0, new LinearLayout.LayoutParams(dp(artDp), dp(artDp)));
        bigWords(tile, body, label, app.title);
        return tile;
    }

    /** The big tile with a glyph instead of an app, when there is no app to offer yet. */
    FrameLayout bigGlyphTile(LinearLayout row, String title, String detail, int iconRes, int artDp, Runnable action) {
        FrameLayout tile = bigTile(row, action, 0);
        LinearLayout body = (LinearLayout) tile.getChildAt(0);
        body.addView(glyph(iconRes, artDp), 0, new LinearLayout.LayoutParams(dp(artDp), dp(artDp)));
        bigWords(tile, body, detail, title);
        return tile;
    }

    private FrameLayout bigTile(LinearLayout row, Runnable action, int wash) {
        FrameLayout tile = new FrameLayout(activity);
        row.addView(tile, new LinearLayout.LayoutParams(dp(BIG_TILE_WIDTH), -1));
        tile.setBackground(surface(R.color.panel, 24));
        focusable(tile, 24, BIG_LIFT, wash, action);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(22));
        tile.addView(body, new FrameLayout.LayoutParams(-1, -1));
        return tile;
    }

    private void bigWords(FrameLayout tile, LinearLayout body, String small, String big) {
        body.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1));
        TextView what = text(small, 18, R.color.muted, false);
        what.setMaxLines(2);
        what.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(what);
        TextView name = text(big, 34, R.color.text, true);
        name.setPadding(0, dp(4), 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        tile.setContentDescription(small + ", " + big);
    }

    /** A dock tile: a line glyph and a word or two, for the TV's own places. */
    FrameLayout dockTile(LinearLayout row, String label, int iconRes, Runnable action) {
        FrameLayout tile = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        row.addView(tile, lp);
        tile.setBackground(surface(R.color.panel, 20));
        focusable(tile, 20, TILE_LIFT, 0, action);
        LinearLayout body = new LinearLayout(activity);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(16), 0, dp(10), 0);
        tile.addView(body, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = icon(iconRes, R.color.text, 26);
        body.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(label, 17, R.color.text, true);
        name.setPadding(dp(12), 0, 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        tile.setContentDescription(label);
        return tile;
    }

    /** A grid tile for a place rather than an app (All apps, Choose favourites): a glyph where the app's art would be. */
    FrameLayout glyphTile(LinearLayout row, String label, int iconRes, int chipDp, float nameSp, Runnable action) {
        FrameLayout tile = gridTile(row, 0, action);
        LinearLayout body = (LinearLayout) tile.getChildAt(0);
        body.addView(glyph(iconRes, chipDp), new LinearLayout.LayoutParams(dp(chipDp), dp(chipDp)));
        tileName(body, label, nameSp);
        tile.setContentDescription(label);
        return tile;
    }

    private FrameLayout gridTile(LinearLayout row, int wash, Runnable action) {
        FrameLayout tile = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.setMarginStart(dp(GAP));
        row.addView(tile, lp);
        tile.setBackground(surface(R.color.panel, 20));
        focusable(tile, 20, TILE_LIFT, wash, action);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(8), dp(4), dp(8), dp(4));
        tile.addView(body, new FrameLayout.LayoutParams(-1, -1));
        return tile;
    }

    /** An app's name under its art, on up to two lines so it is never cut to its first word. */
    private void tileName(LinearLayout body, String label, float sp) {
        TextView name = text(label, sp, R.color.text, true);
        name.setPadding(0, dp(4), 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        body.addView(name, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A rounded square holding a line glyph, the same size as an app's art. */
    private FrameLayout glyph(int iconRes, int sizeDp) {
        FrameLayout chip = new FrameLayout(activity);
        chip.setBackground(surface(R.color.raised, Math.round(sizeDp * 0.2f)));
        int inner = Math.round(sizeDp * 0.5f);
        ImageView icon = icon(iconRes, R.color.text, inner);
        chip.addView(icon, new FrameLayout.LayoutParams(dp(inner), dp(inner), Gravity.CENTER));
        return chip;
    }

    /** An app's art on the app's own colour; its initial until the icon arrives, or if it never does. */
    private FrameLayout chip(Apps.App app, IconCache icons, int sizeDp, int letterSp) {
        FrameLayout chip = new FrameLayout(activity);
        int colour = appColor(app);
        chip.setBackground(solid(colour, Math.round(sizeDp * 0.2f)));
        TextView letter = new TextView(activity);
        letter.setText(app.initial());
        letter.setTypeface(wordsBold);
        letter.setTextSize(letterSp);
        letter.setTextColor(Theme.luminance(Theme.over(colour, theme.groundTop)) > 0.45 ? color(R.color.ink) : LETTER);
        letter.setGravity(Gravity.CENTER);
        chip.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (app.icon != null) {
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -1);
            int inset = dp(sizeDp * 0.08f);
            pp.setMargins(inset, inset, inset, inset);
            chip.addView(picture, pp);
            int round = dp(sizeDp * 0.14f);
            picture.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), round);
                }
            });
            picture.setClipToOutline(true);
            icons.show(app.id, app.icon, picture);
            picture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                letter.setVisibility(picture.getDrawable() == null ? View.VISIBLE : View.INVISIBLE));
        }
        return chip;
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

    /** An app tile: the app's art on its own LG colour, and its name under it. */
    FrameLayout appTile(LinearLayout row, Apps.App app, boolean marked, IconCache icons, int chipDp, float nameSp, Runnable action) {
        FrameLayout tile = gridTile(row, appColor(app), action);
        LinearLayout body = (LinearLayout) tile.getChildAt(0);
        body.addView(chip(app, icons, chipDp, Math.round(chipDp * 0.48f)), new LinearLayout.LayoutParams(dp(chipDp), dp(chipDp)));
        tileName(body, app.title, nameSp);
        if (marked) {
            ImageView star = icon(R.drawable.ic_star, R.color.accent_ink, 20);
            star.setBackground(oval(color(R.color.accent)));
            star.setPadding(dp(5), dp(5), dp(5), dp(5));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.END);
            sp.setMargins(0, dp(8), dp(8), 0);
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

    /** Rows from here until endColumns() fill two columns, so a long list fits on one screen. */
    void twoColumns() {
        LinearLayout both = new LinearLayout(activity);
        both.setClipChildren(false);
        columns = new LinearLayout[2];
        for (int i = 0; i < 2; i++) {
            columns[i] = new LinearLayout(activity);
            columns[i].setOrientation(LinearLayout.VERTICAL);
            columns[i].setClipChildren(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i == 1) lp.setMarginStart(dp(GAP));
            both.addView(columns[i], lp);
        }
        nextColumn = 0;
        content.addView(both);
    }

    void endColumns() { columns = null; }

    /** A list row: icon disc, label, value on the right, chevron when it opens another page. */
    LinearLayout listRow(int iconRes, String label, String value, boolean opens, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(14), 0);
        row.setBackground(surface(R.color.panel, 16));
        focusable(row, 16, BIG_LIFT, null, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(ROW));
        lp.bottomMargin = dp(ROW_GAP);
        if (columns == null) content.addView(row, lp);
        else columns[nextColumn++ % 2].addView(row, lp);
        if (iconRes != 0) {
            ImageView icon = icon(iconRes, R.color.text, 20);
            icon.setBackground(oval(color(R.color.raised)));
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(12));
            row.addView(icon, ip);
        }
        TextView name = text(label, 18, R.color.text, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value == null ? "" : value, 17, R.color.muted, false);
        v.setTag("value");
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(10), 0, 0, 0);
        row.addView(v);
        if (opens) {
            ImageView chevron = icon(R.drawable.ic_chevron, R.color.muted, 24);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(24), dp(24));
            cp.setMarginStart(dp(6));
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
        stepper.addView(icon(R.drawable.ic_chevron_left, R.color.muted, 22), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView v = text(value, 17, R.color.text, true);
        if (numeric) v.setTypeface(numeralsBold);
        v.setTag("value");
        v.setMinWidth(dp(132));
        v.setMaxWidth(dp(170)); // a long input name gives way before the row's label does
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setGravity(Gravity.CENTER);
        stepper.addView(v);
        stepper.addView(icon(R.drawable.ic_chevron, R.color.muted, 22), new LinearLayout.LayoutParams(dp(22), dp(22)));
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
        input.setDefaultFocusHighlightEnabled(false);
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
        focusable(b, 999, TILE_LIFT, null, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(56));
        lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    // Focus: a ring in the theme's accent and a small lift, eased out. The ring is drawn inside the view's own background.
    // A wash is the RGB colour the ground's light turns (0 for its quiet neutral); null leaves the light where it is.
    private void focusable(View v, int radius, float lift, Integer wash, Runnable action) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setDefaultFocusHighlightEnabled(false); // it would show as a square through a translucent tile
        v.setOnClickListener(x -> action.run());
        GroundView light = ground;
        v.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.setForeground(ring(radius));
            else view.setForeground(null);
            if (focused && wash != null) light.follow(view, wash);
            view.animate().scaleX(focused ? lift : 1f).scaleY(focused ? lift : 1f)
                .translationZ(focused ? dp(10) : 0).setDuration(140).setInterpolator(new DecelerateInterpolator(2f)).start();
        });
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

    // Muted hues for lettered tiles, dark enough for warm-white initials at 4.5:1.
    static final int[] HUES = { 0xFF2C3E5C, 0xFF3D3152, 0xFF2D4A3E, 0xFF4D3528, 0xFF23434C, 0xFF4B2F3B };

    private int appColor(Apps.App app) {
        if (app.color != null) {
            try { return Color.parseColor(app.color); } catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        return app.icon != null ? color(R.color.panel) : HUES[Math.abs(app.id.hashCode()) % HUES.length];
    }
}
