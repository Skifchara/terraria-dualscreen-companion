package com.terraduo.companion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands the TL Pro mod pack that travels inside this app to TL Pro (which registers for ".tl" files). Read-only; the pack is the
 * same file tools/build_tlpro.py builds, stored in the app's assets.
 */
public final class ModProvider extends ContentProvider {
    static final String AUTHORITY = "com.terraduo.companion.mod";
    static final String ASSET = "terraduo-mod.tl";

    static Uri uri(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + fileName);
    }

    /** A private copy of the pack in the app's cache (replaced when the app is updated). */
    static File cached(Context context) throws IOException {
        File file = new File(context.getCacheDir(), ASSET);
        AssetManager assets = context.getAssets();
        long size = 0;
        try (InputStream in = assets.open(ASSET)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) size += n;
        }
        if (!file.isFile() || file.length() != size) {
            try (InputStream in = assets.open(ASSET); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        }
        return file;
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        long size = -1;
        try {
            size = cached(getContext()).length();
        } catch (IOException ignored) {
            // size unknown
        }
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = uri.getLastPathSegment();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = size;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.endsWith(".tl") || mode.contains("w")) throw new FileNotFoundException(String.valueOf(uri));
        try {
            return ParcelFileDescriptor.open(cached(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
