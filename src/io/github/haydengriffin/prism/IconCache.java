package io.github.haydengriffin.prism;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LG app icons from Glasshouse's LAN icon server, kept on disk so the launcher draws them instantly next time.
 * A missing icon leaves the tile's lettered fallback in place.
 */
final class IconCache {
    private static final int MAX_BYTES = 512 * 1024;
    private final File dir;
    private final Map<String, Bitmap> memory = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    IconCache(Context context) {
        dir = new File(context.getCacheDir(), "icons");
        dir.mkdirs();
    }

    /** Shows the cached icon at once if there is one, then refreshes it from the TV in the background. */
    void show(String appId, String url, ImageView view) {
        view.setTag(appId);
        Bitmap known = memory.get(appId);
        if (known != null) view.setImageBitmap(known);
        loader.execute(() -> {
            Bitmap bitmap = known != null ? known : fromDisk(appId);
            if (bitmap == null && url != null) bitmap = download(appId, url);
            if (bitmap == null) return;
            Bitmap ready = bitmap;
            main.post(() -> {
                memory.put(appId, ready);
                if (appId.equals(view.getTag())) view.setImageBitmap(ready);
            });
        });
    }

    /** The icon now, on the caller's thread: from disk, else from the TV; null when neither has it. */
    Bitmap load(String appId, String url) {
        Bitmap bitmap = fromDisk(appId);
        return bitmap != null || url == null ? bitmap : download(appId, url);
    }

    void close() { loader.shutdownNow(); }

    private File file(String appId) { return new File(dir, appId.replaceAll("[^A-Za-z0-9._-]", "_") + ".png"); }

    private Bitmap fromDisk(String appId) {
        File f = file(appId);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private Bitmap download(String appId, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            byte[] png = read(c.getInputStream());
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) return null;
            File tmp = new File(dir, appId.hashCode() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(png); }
            Files.move(tmp.toPath(), file(appId).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return bitmap;
        } catch (Exception e) {
            return null; // The lettered tile stays; the next visit tries again.
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (out.size() + n > MAX_BYTES) throw new java.io.IOException("Icon too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
