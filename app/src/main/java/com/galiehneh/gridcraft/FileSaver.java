package com.galiehneh.gridcraft;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a finished temp file to its final destination (Downloads via MediaStore, or a SAF uri). */
class FileSaver {
    private static final String TAG = "GridCraft";
    private final Context context;

    FileSaver(Context context) {
        this.context = context.getApplicationContext();
    }

    /** API 29+. Returns the entry's final display name (MediaStore may rename duplicates). */
    @RequiresApi(Build.VERSION_CODES.Q)
    String saveToDownloads(File src, String name, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert returned null");
        try {
            copy(src, uri);
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, done, null, null) < 1) {
                throw new IOException("Cannot publish pending entry");
            }
        } catch (IOException | RuntimeException e) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ex) {
                Log.w(TAG, "Cannot remove failed entry", ex);
            }
            throw e;
        }
        String finalName = displayName(uri);
        return finalName != null ? finalName : name;
    }

    /** Copies {@code src} into a SAF document; removes the document on failure only if it was empty (newly created). */
    void saveToUri(File src, Uri uri) throws IOException {
        boolean wasEmpty = sizeOf(uri) == 0;
        try {
            copy(src, uri);
        } catch (IOException | RuntimeException e) {
            if (wasEmpty) {
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), uri);
                } catch (Exception ex) {
                    Log.w(TAG, "Cannot remove failed document", ex);
                }
            }
            throw e;
        }
    }

    /** Overwrites an existing SAF document in place ("wt"); never deletes it, even on failure. */
    void overwrite(File src, Uri uri) throws IOException {
        copy(src, uri);
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read size", e);
        }
        return -1;
    }

    String displayName(Uri uri) {
        try (Cursor c = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read display name", e);
        }
        return null;
    }

    private void copy(File src, Uri dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = context.getContentResolver().openOutputStream(dest, "wt")) {
            if (out == null) throw new IOException("openOutputStream returned null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }
}
