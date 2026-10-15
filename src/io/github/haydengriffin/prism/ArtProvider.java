package io.github.haydengriffin.prism;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only card images for the home-screen rows: content://io.github.haydengriffin.prism.art/<card>.png. Exported so the
 * launcher can load poster art; it serves only ChannelArt's PNGs (app titles and icons, nothing private).
 */
public final class ArtProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.haydengriffin.prism.art";
    private static final String NAME = "[A-Za-z0-9._-]+\\.png";

    static Uri uri(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("Read only");
        String name = uri.getPathSegments().size() == 1 ? uri.getLastPathSegment() : null;
        if (name == null || !name.matches(NAME)) throw new FileNotFoundException("No such card");
        File file = new File(ChannelArt.dir(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException("No such card");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
