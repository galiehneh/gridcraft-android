package com.galiehneh.gridcraft;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

/** Incoming files: picker intents, intent parsing, and copying into the app cache. */
class FileImporter {
    static final String DOCS_DIR = "docs";
    private static final Pattern DOC_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final long MAX_IMPORT_BYTES = 500L * 1024 * 1024;
    private final Context context;

    FileImporter(Context context) {
        this.context = context;
    }

    /** Picker intent for the given accept types (empty or unmappable means any file). */
    static Intent pickIntent(String action, String[] accept, boolean multiple) {
        String[] types = Mime.acceptToMimeTypes(accept);
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(types.length == 1 ? types[0] : "*/*");
        if (types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    /** Document id embedded in a /doc/<id>/<name> request path, or null when malformed. */
    static String docIdFromPath(String path) {
        if (path == null) return null;
        int cut = path.indexOf('/');
        String id = cut < 0 ? path : path.substring(0, cut);
        return DOC_ID.matcher(id).matches() ? id : null;
    }

    File docFile(String id, String name) {
        String ext = "xlsx";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toLowerCase();
            }
        }
        return new File(new File(context.getCacheDir(), DOCS_DIR), id + "." + ext);
    }

    static boolean isIncomingAction(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        return Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action);
    }

    /** Extracts the file URI from a VIEW or SEND intent, or null. */
    Uri uriFromIntent(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        }
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        return isAllowedSource(uri) ? uri : null;
    }

    /** Only content:// and file:// URIs, and never this app's own FileProvider. */
    boolean isAllowedSource(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            return !(context.getPackageName() + ".fileprovider").equalsIgnoreCase(uri.getAuthority());
        }
        return "file".equalsIgnoreCase(scheme);
    }

    /** Result of a chooser pick: single data URI or all clip items; null when nothing picked. */
    static Uri[] urisFromChooserResult(Intent data) {
        if (data == null) return null;
        if (data.getData() != null) {
            return new Uri[]{data.getData()};
        }
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            Uri[] results = new Uri[count];
            for (int i = 0; i < count; i++) {
                results[i] = data.getClipData().getItemAt(i).getUri();
            }
            return results;
        }
        return null;
    }

    /** Copies the URI content into cacheDir/docs/<id>.<ext> and returns the new id. Blocking I/O. */
    String copyToCache(Uri uri, String name) throws IOException {
        if (!isAllowedSource(uri)) throw new IOException("Rejected source " + uri);
        File dir = new File(context.getCacheDir(), DOCS_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        String id = UUID.randomUUID().toString();
        File dest = docFile(id, name);
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(dest)) {
            if (is == null) throw new IOException("Cannot open " + uri);
            byte[] buffer = new byte[32768];
            int read;
            long total = 0;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES) throw new IOException("File too large");
                fos.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            if (!dest.delete() && dest.exists()) dest.deleteOnExit();
            throw e;
        }
        return id;
    }

    /** Deletes imported documents older than keepId. */
    void cleanup(String keepId) {
        File[] files = new File(context.getCacheDir(), DOCS_DIR).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().startsWith(keepId) && !f.delete()) f.deleteOnExit();
        }
    }

    String displayName(Uri uri) {
        String result = Mime.DEFAULT_FILE_NAME;
        if (uri == null) return result;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        String name = cursor.getString(nameIndex);
                        if (name != null && !name.trim().isEmpty()) {
                            result = name;
                        }
                    }
                }
            } catch (Exception ignored) {}
        } else {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) {
                    result = path.substring(cut + 1);
                }
            }
        }
        return result;
    }
}
