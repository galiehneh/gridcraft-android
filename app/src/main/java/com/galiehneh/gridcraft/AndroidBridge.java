package com.galiehneh.gridcraft;

import android.app.Activity;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** JS bridge exposed to the web bundle as {@code window.GridCraftAndroid}. */
class AndroidBridge {
    private static final String TAG = "GridCraft";
    static final String OUT_DIR = "out";
    static final int MAX_CHUNK_BASE64_CHARS = 4 * 1024 * 1024;
    static final long MAX_SESSION_BYTES = 1L << 30;
    static final int MAX_SESSIONS = 4;
    private static final int MAX_RETAINED = 5;
    private static final long RETAIN_MS = 60 * 60 * 1000L;

    /** Finished output dirs kept for open/share. */
    private static final LinkedList<Object[]> RETAINED = new LinkedList<>();

    private static synchronized void retain(String dirName) {
        RETAINED.addFirst(new Object[]{dirName, System.currentTimeMillis()});
    }

    private static synchronized List<String> prunedRetained() {
        long now = System.currentTimeMillis();
        List<String> keep = new ArrayList<>();
        Iterator<Object[]> it = RETAINED.iterator();
        while (it.hasNext()) {
            Object[] e = it.next();
            if (keep.size() >= MAX_RETAINED || now - (Long) e[1] > RETAIN_MS) {
                it.remove();
            } else {
                keep.add((String) e[0]);
            }
        }
        return keep;
    }

    static final class SavedFile {
        final File file;
        final String name;
        final String mime;

        SavedFile(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    /** Callbacks from the host page; always invoked on the UI thread. */
    interface HostListener {
        void onReady();
        void onFailed(String error);
        void onDocumentOpened(boolean opened);
        void onDirty(boolean dirty);
    }

    /** Save callbacks; may be invoked on any thread. */
    interface SaveListener {
        void onSaved(SavedFile file, String finalName);
        /** Tries to overwrite the document's source file; true when handled (success UI included). */
        boolean onSaveInPlace(SavedFile file);
        void onNeedsDestination(SavedFile file);
        void onPrint(SavedFile file);
        void onFailed(String name, String message);
    }

    private static final class Session {
        final File dir;
        final File file;
        final String name;
        final String mime;
        final boolean print;
        final FileOutputStream out;
        long written;

        Session(File dir, File file, String name, String mime, boolean print) throws IOException {
            this.dir = dir;
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.print = print;
            this.out = new FileOutputStream(file);
        }
    }

    private final Activity activity;
    private final WebView webView;
    private final FileSaver fileSaver;
    private final ExecutorService saveExecutor;
    private final HostListener host;
    private final SaveListener saveListener;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    AndroidBridge(Activity activity, WebView webView, FileSaver fileSaver, ExecutorService saveExecutor,
                  HostListener host, SaveListener saveListener) {
        this.activity = activity;
        this.webView = webView;
        this.fileSaver = fileSaver;
        this.saveExecutor = saveExecutor;
        this.host = host;
        this.saveListener = saveListener;
    }

    @JavascriptInterface
    public void appReady() {
        activity.runOnUiThread(host::onReady);
    }

    @JavascriptInterface
    public void appFailed(String error) {
        activity.runOnUiThread(() -> host.onFailed(error));
    }

    @JavascriptInterface
    public void setDirty(boolean dirty) {
        activity.runOnUiThread(() -> host.onDirty(dirty));
    }

    @JavascriptInterface
    public void showKeyboard() {
        activity.runOnUiThread(() -> {
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) {
                boolean ok = imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                if (!ok) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });
    }

    @JavascriptInterface
    public void hideKeyboard() {
        activity.runOnUiThread(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            }
        });
    }

    @JavascriptInterface
    public void setDocumentOpened(boolean opened) {
        activity.runOnUiThread(() -> host.onDocumentOpened(opened));
    }

    /** Single-call blob save (legacy fallback for the anchor-click hook in index.html). */
    @JavascriptInterface
    public void saveBlob(String dataUrl, String filename) {
        if (dataUrl == null) return;
        runSave(() -> {
            try {
                String[] safe = Mime.sanitizeForSave(null, filename);
                String mime = safe[0];
                String name = safe[1];
                int comma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl, Base64.DEFAULT);
                cleanupOldOutputs();
                File dir = new File(new File(activity.getCacheDir(), OUT_DIR), UUID.randomUUID().toString());
                if (!dir.mkdirs()) throw new IOException("Cannot create " + dir);
                retain(dir.getName());
                File file = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
                deliver(new SavedFile(file, name, mime));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "saveBlob failed", e);
                saveListener.onFailed(filename, activity.getString(R.string.save_failed));
            }
        });
    }

    private void runSave(Runnable task) {
        try {
            saveExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Save dropped, activity is shutting down");
        }
    }

    private void deliver(SavedFile saved) throws IOException {
        if (saveListener.onSaveInPlace(saved)) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            saveListener.onNeedsDestination(saved);
            return;
        }
        String finalName = fileSaver.saveToDownloads(saved.file, saved.name, saved.mime);
        saveListener.onSaved(saved, finalName);
    }

    @JavascriptInterface
    public String beginSave(String filename, String mime) {
        String[] safe = Mime.sanitizeForSave(mime, filename);
        return begin(safe[1], safe[0], false);
    }

    /** Starts a PDF upload that is handed to the system print dialog instead of being saved. */
    @JavascriptInterface
    public String beginPrint(String jobName) {
        return begin(jobName == null || jobName.isEmpty() ? "print" : jobName, "application/pdf", true);
    }

    private String begin(String name, String mime, boolean print) {
        if (sessions.size() >= MAX_SESSIONS) {
            saveListener.onFailed(name, activity.getString(R.string.save_too_many));
            return "";
        }
        try {
            cleanupOldOutputs();
            String id = UUID.randomUUID().toString();
            File dir = new File(new File(activity.getCacheDir(), OUT_DIR), id);
            if (!dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File file = new File(dir, print ? "print.pdf" : name);
            sessions.put(id, new Session(dir, file, name, mime, print));
            return id;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "beginSave failed", e);
            saveListener.onFailed(name, activity.getString(R.string.save_prepare_failed));
            return "";
        }
    }

    @JavascriptInterface
    public boolean appendChunk(String id, String base64) {
        Session s = id == null ? null : sessions.get(id);
        if (s == null || base64 == null) return false;
        if (base64.length() > MAX_CHUNK_BASE64_CHARS) {
            fail(id, activity.getString(R.string.save_limit_exceeded));
            return false;
        }
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (s.written + data.length > MAX_SESSION_BYTES) {
                fail(id, activity.getString(R.string.save_limit_exceeded));
                return false;
            }
            s.out.write(data);
            s.written += data.length;
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "appendChunk failed", e);
            fail(id, activity.getString(R.string.save_write_failed));
            return false;
        }
    }

    @JavascriptInterface
    public void finishSave(String id) {
        Session s = id == null ? null : sessions.get(id);
        if (s == null) return;
        retain(s.dir.getName());
        if (sessions.remove(id) == null) return;
        runSave(() -> {
            try {
                s.out.close();
                SavedFile saved = new SavedFile(s.file, s.name, s.mime);
                if (s.print) {
                    saveListener.onPrint(saved);
                } else {
                    deliver(saved);
                }
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "finishSave failed", e);
                deleteRecursive(s.dir);
                saveListener.onFailed(s.name, activity.getString(R.string.save_downloads_failed));
            }
        });
    }

    @JavascriptInterface
    public void abortSave(String id) {
        Session s = id == null ? null : sessions.remove(id);
        if (s == null) return;
        closeQuietly(s);
        deleteRecursive(s.dir);
    }

    void close() {
        for (String id : new ArrayList<>(sessions.keySet())) {
            Session s = sessions.remove(id);
            if (s == null) continue;
            closeQuietly(s);
            deleteRecursive(s.dir);
        }
    }

    private void fail(String id, String message) {
        Session s = sessions.remove(id);
        if (s == null) return;
        closeQuietly(s);
        deleteRecursive(s.dir);
        saveListener.onFailed(s.name, message);
    }

    private static void closeQuietly(Session s) {
        try {
            s.out.close();
        } catch (IOException ignored) {}
    }

    private void cleanupOldOutputs() {
        File[] dirs = new File(activity.getCacheDir(), OUT_DIR).listFiles();
        if (dirs == null) return;
        List<String> retained = prunedRetained();
        for (File dir : dirs) {
            if (!sessions.containsKey(dir.getName()) && !retained.contains(dir.getName())) {
                deleteRecursive(dir);
            }
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        if (!f.delete() && f.exists()) Log.w(TAG, "Cannot delete " + f);
    }
}
