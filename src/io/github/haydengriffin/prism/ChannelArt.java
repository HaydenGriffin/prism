package io.github.haydengriffin.prism;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;

/**
 * 16:9 cards for the home-screen rows, drawn like the launcher's tiles: an LG app's icon on its own tile colour, or
 * an MDI glyph on the night ground; Atkinson words with Lexend digits. Files are named by what they show, so a
 * card is drawn once and a changed card gets a new URI (launchers cache poster art by URI).
 */
final class ChannelArt {
    static final int WIDTH = 640, HEIGHT = 360;
    /** Bump when the drawing changes, so every card gets a new name. */
    private static final int STYLE = 1;
    private final Context context;
    private final File dir;
    private final IconCache icons;
    private final Typeface words, numerals;

    ChannelArt(Context context) {
        this.context = context;
        dir = dir(context);
        dir.mkdirs();
        icons = new IconCache(context);
        words = context.getResources().getFont(R.font.atkinson_semibold);
        numerals = context.getResources().getFont(R.font.lexend_semibold);
    }

    static File dir(Context context) { return new File(context.getFilesDir(), "channel-art"); }

    /** The item's card file name, drawn now unless it already exists. */
    String card(HomeRows.Item item) throws IOException {
        Bitmap icon = item.app == null ? null : icons.load(item.app.id, item.app.icon);
        int look = Arrays.hashCode(new Object[] {STYLE, item.glyph.name(), item.title, item.resume,
            item.app == null ? null : item.app.color, item.app == null ? null : item.app.icon, icon != null});
        String name = item.key.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + Integer.toHexString(look) + ".png";
        File file = new File(dir, name);
        if (!file.exists()) write(file, png(item.app != null ? appCard(item, icon) : commandCard(item)));
        return name;
    }

    /** Deletes cards no row uses any more. */
    void keepOnly(Set<String> names) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) if (!names.contains(f.getName())) f.delete();
    }

    /** The channel logo: this app's own icon. */
    byte[] logo() {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Drawable icon = context.getDrawable(R.drawable.icon);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(new Canvas(bitmap));
        return png(bitmap);
    }

    private Bitmap appCard(HomeRows.Item item, Bitmap icon) {
        Bitmap card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(card);
        int ground = ground(item.app);
        c.drawColor(ground);
        boolean light = Screen.luminance(ground) > 0.45;
        int ink = color(light ? R.color.ink : R.color.text);
        if (icon != null) {
            float box = HEIGHT * 0.5f;
            float scale = Math.min(box / icon.getWidth(), box / icon.getHeight());
            float w = icon.getWidth() * scale, h = icon.getHeight() * scale;
            float top = HEIGHT * 0.38f - h / 2;
            c.drawBitmap(icon, null, new RectF((WIDTH - w) / 2, top, (WIDTH + w) / 2, top + h), new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            text(c, item.app.initial(), WIDTH / 2f, HEIGHT * 0.5f, 120, ink, !light, WIDTH);
        }
        text(c, item.app.title, WIDTH / 2f, HEIGHT - 40, 34, ink, !light, WIDTH - 64);
        if (item.resume) pill(c, "Continue");
        return card;
    }

    private Bitmap commandCard(HomeRows.Item item) {
        Bitmap card = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(card);
        c.drawColor(color(R.color.surface));
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(color(R.color.raised));
        float cy = HEIGHT * 0.4f;
        c.drawCircle(WIDTH / 2f, cy, 72, disc);
        Drawable glyph = context.getDrawable(glyph(item.glyph)).mutate();
        glyph.setTint(color(R.color.text));
        glyph.setBounds(new Rect(WIDTH / 2 - 42, (int) cy - 42, WIDTH / 2 + 42, (int) cy + 42));
        glyph.draw(c);
        text(c, item.title, WIDTH / 2f, HEIGHT - 40, 34, color(R.color.text), false, WIDTH - 64);
        return card;
    }

    private static int glyph(HomeRows.Glyph glyph) {
        switch (glyph) {
            case HOME: return R.drawable.ic_home;
            case SETTINGS: return R.drawable.ic_settings;
            case TUNE: return R.drawable.ic_tune;
            default: return R.drawable.ic_tv;
        }
    }

    /** As the launcher's tiles: the app's own colour, else the panel under an icon, else a muted hue. */
    private int ground(Apps.App app) {
        if (app.color != null) {
            try { return Color.parseColor(app.color); } catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        return app.icon != null ? color(R.color.panel) : Screen.HUES[Math.abs(app.id.hashCode()) % Screen.HUES.length];
    }

    /** A sky label in the top-left corner. */
    private void pill(Canvas c, String label) {
        Paint p = paint(words, 24, color(R.color.accent_ink), false);
        float w = p.measureText(label) + 36;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color(R.color.accent));
        c.drawRoundRect(new RectF(24, 24, 24 + w, 68), 22, 22, fill);
        c.drawText(label, 42, 55, p);
    }

    /** Centred text, ellipsised to fit, every digit run in Lexend. */
    private void text(Canvas c, String text, float cx, float baseline, float size, int color, boolean shadow, float max) {
        Paint w = paint(words, size, color, shadow), n = paint(numerals, size, color, shadow);
        String fitted = fit(text, w, n, max);
        float x = cx - width(fitted, w, n) / 2;
        for (String run : runs(fitted)) {
            Paint p = Character.isDigit(run.charAt(0)) ? n : w;
            c.drawText(run, x, baseline, p);
            x += p.measureText(run);
        }
    }

    private static String fit(String text, Paint w, Paint n, float max) {
        if (width(text, w, n) <= max) return text;
        String base = text;
        while (base.length() > 1 && width(base + "…", w, n) > max) base = base.substring(0, base.length() - 1);
        return base.trim() + "…";
    }

    private static float width(String text, Paint w, Paint n) {
        float total = 0;
        for (String run : runs(text)) total += (Character.isDigit(run.charAt(0)) ? n : w).measureText(run);
        return total;
    }

    /** The text split into runs of digits and non-digits. */
    private static java.util.List<String> runs(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || Character.isDigit(text.charAt(i)) != Character.isDigit(text.charAt(start))) {
                out.add(text.substring(start, i));
                start = i;
            }
        }
        return out;
    }

    private static Paint paint(Typeface face, float size, int color, boolean shadow) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        if (shadow) p.setShadowLayer(8, 0, 2, 0x99000000);
        return p;
    }

    private int color(int id) { return context.getColor(id); }

    private static byte[] png(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private void write(File file, byte[] png) throws IOException {
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(png); }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
