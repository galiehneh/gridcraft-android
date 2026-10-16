package com.galiehneh.gridcraft;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.print.PrintManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MainActivity extends ComponentActivity {
    private static final String TAG = "GridCraft";
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String BASE_URL = ORIGIN + "/android/host.html";
    private static final long OVERLAY_TIMEOUT_MS = 15000;
    private static final long PAGE_FINISHED_FALLBACK_MS = 8000;
    private static final long SPLASH_MAX_MS = 2000;
    private static final long BACK_CLOSE_WINDOW_MS = 2000;
    private static final String STATE_DOC_ID = "docId";
    private static final String STATE_DOC_NAME = "docName";
    private static final String STATE_SAVE_AS_FILE = "saveAsFile";
    private static final String STATE_SAVE_AS_NAME = "saveAsName";
    private static final String STATE_SAVE_AS_MIME = "saveAsMime";
    private static final String STATE_SAVE_AS_INITIAL = "saveAsInitial";
    private static final String STATE_SOURCE_URI = "sourceUri";
    private static final String STATE_DIRTY = "dirty";
    private static final long AFTER_SAVE_TIMEOUT_MS = 15000;
    private static final long AFTER_SAVE_BEGUN_TIMEOUT_MS = AndroidBridge.SESSION_IDLE_MS;
    private static final long OVERWRITE_CONFIRM_TIMEOUT_S = 120;
    private static final int RENDERER_RECOVERY_MAX = 2;
    private static final long RENDERER_RECOVERY_WINDOW_MS = 30000;
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
            + "worker-src 'self' blob:; connect-src 'self' blob: data:; frame-src 'self' blob:; "
            + "object-src 'none'; base-uri 'none'";
    private static final int SHORTCUT_COUNT = 3;
    private static final String FAB_MENU_LABEL = "\u22EE";
    private static final int MENU_OPEN = 1;
    private static final int MENU_SAVE = 2;
    private static final int MENU_UNDO = 3;
    private static final int MENU_REDO = 4;
    private static final int MENU_FIND = 5;
    private static final int MENU_PASTE = 6;
    private static final int MENU_CLOSE = 7;
    private static final int MENU_RECENT_BASE = 100;

    private static final String[] SPREADSHEET_MIMES = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroEnabled.12",
            "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv",
            "text/tab-separated-values",
            "text/plain",
            "application/json"
    };

    private FrameLayout rootLayout;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private FileImporter importer;
    private ValueCallback<Uri[]> filePathCallback;
    private volatile String currentDocumentId = null;
    private String currentDocumentName = null;
    private boolean dirty;
    private volatile Source source;
    /** Document whose source file the user already agreed to overwrite. */
    private volatile String overwriteConfirmedDocId;
    private final RateLimit rendererRecoveries = new RateLimit(RENDERER_RECOVERY_MAX, RENDERER_RECOVERY_WINDOW_MS);
    private Runnable afterSave;
    private RecentFiles recents;
    private SaveBar saveBar;
    private boolean importing;
    private boolean splashHeld = true;
    private TextView fab;
    private long backPressedTime = 0;
    private OnBackPressedCallback backCallback;
    private FileSaver fileSaver;
    private AndroidBridge bridge;
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOverlay = () -> {
        if (!importing) setOverlayVisible(false);
    };
    private final Runnable releaseSplash = () -> splashHeld = false;
    private final Runnable clearAfterSave = () -> afterSave = null;
    private View loadingOverlay;
    private View loadingProgress;
    private TextView loadingText;
    private Button retryButton;

    private final ActivityResultLauncher<Intent> chooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> onChooserResult(r.getResultCode(), r.getData()));

    private final ActivityResultLauncher<Intent> directPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Uri uri = r.getResultCode() == RESULT_OK && r.getData() != null ? r.getData().getData() : null;
                if (uri != null) replaceDocument(() -> handleSpreadsheetUri(uri, hasWriteGrant(r.getData())));
            });

    private final ActivityResultLauncher<Intent> saveAsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> onSaveAsResult(r.getResultCode(), r.getData()));

    private AndroidBridge.SavedFile pendingSaveAs;
    private boolean pendingSaveAsInitial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this).setKeepOnScreenCondition(() -> splashHeld);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        mainHandler.postDelayed(releaseSplash, SPLASH_MAX_MS);

        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        applyWindowInsets(rootLayout);

        webView = createWebView();
        rootLayout.addView(webView, 0);

        fab = createFabButton();
        rootLayout.addView(fab);

        saveBar = new SaveBar(this);
        rootLayout.addView(saveBar);

        loadingOverlay = createLoadingOverlay();
        rootLayout.addView(loadingOverlay);

        applyThemeColors();
        setContentView(rootLayout);

        importer = new FileImporter(this);
        fileSaver = new FileSaver(this);
        recents = new RecentFiles(this);
        configureWebView();

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            Intent intent = getIntent();
            if (intent != null && FileImporter.isIncomingAction(intent)) {
                handleIncomingIntent(intent);
            } else {
                loadUrlWithOverlay(BASE_URL);
            }
        }
    }

    private boolean isDocumentOpen() {
        return currentDocumentName != null;
    }

    private void updateBackCallback() {
        if (backCallback != null) backCallback.setEnabled(isDocumentOpen());
    }

    private void handleIncomingIntent(Intent intent) {
        Uri targetUri = importer.uriFromIntent(intent);
        String text = targetUri == null ? FileImporter.textFromIntent(intent) : null;
        if (targetUri != null) {
            boolean granted = hasWriteGrant(intent);
            replaceDocument(() -> handleSpreadsheetUri(targetUri, granted));
        } else if (text != null) {
            replaceDocument(() -> handleSharedText(text));
        } else if (!isDocumentOpen()) {
            loadUrlWithOverlay(BASE_URL);
        }
    }

    private static boolean hasWriteGrant(Intent intent) {
        return intent != null && (intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
    }

    /** Runs {@code open} now, or after confirmation when the current document has unsaved changes. */
    private void replaceDocument(Runnable open) {
        if (!dirty || !isDocumentOpen()) {
            open.run();
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.replace_doc_title))
                .setMessage(getString(R.string.close_doc_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.action_open), (d, w) -> open.run())
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DOC_ID, currentDocumentId);
        outState.putString(STATE_DOC_NAME, currentDocumentName);
        AndroidBridge.SavedFile pending = pendingSaveAs;
        if (pending != null) {
            outState.putString(STATE_SAVE_AS_FILE, pending.file.getAbsolutePath());
            outState.putString(STATE_SAVE_AS_NAME, pending.name);
            outState.putString(STATE_SAVE_AS_MIME, pending.mime);
            outState.putBoolean(STATE_SAVE_AS_INITIAL, pendingSaveAsInitial);
        }
        Source src = source;
        if (src != null) outState.putString(STATE_SOURCE_URI, src.uri.toString());
        outState.putBoolean(STATE_DIRTY, dirty);
    }

    private void restoreInstanceState(Bundle state) {
        String file = state.getString(STATE_SAVE_AS_FILE);
        if (file != null && new File(file).isFile()) {
            pendingSaveAs = new AndroidBridge.SavedFile(new File(file),
                    state.getString(STATE_SAVE_AS_NAME, "Workbook.xlsx"),
                    state.getString(STATE_SAVE_AS_MIME, Mime.OCTET_STREAM));
            pendingSaveAsInitial = state.getBoolean(STATE_SAVE_AS_INITIAL);
        }
        currentDocumentId = state.getString(STATE_DOC_ID);
        currentDocumentName = state.getString(STATE_DOC_NAME);
        String sourceUri = state.getString(STATE_SOURCE_URI);
        String ext = Mime.extensionOf(currentDocumentName);
        source = sourceUri != null && ext != null && currentDocumentId != null
                ? new Source(Uri.parse(sourceUri), ext, currentDocumentId, persistedWriteUris().contains(sourceUri))
                : null;
        if (state.getBoolean(STATE_DIRTY) && currentDocumentName != null) {
            Toast.makeText(this, getString(R.string.unsaved_lost), Toast.LENGTH_LONG).show();
        }
        reloadCurrent();
    }

    /** Reopens the current document if its cached copy still exists, else shows home. */
    private void reloadCurrent() {
        if (currentDocumentId != null && currentDocumentName != null
                && importer.docFile(currentDocumentId, currentDocumentName).exists()) {
            openDocument(currentDocumentId, currentDocumentName);
        } else {
            closeDocument();
        }
    }

    private View createLoadingOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            box.setElevation(dpToPx(16));
        }
        loadingProgress = new ProgressBar(this);
        box.addView(loadingProgress);
        loadingText = new TextView(this);
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), 0);
        box.addView(loadingText);
        retryButton = new Button(this);
        retryButton.setText(R.string.retry);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> reloadCurrent());
        box.addView(retryButton);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private void setOverlayVisible(boolean visible) {
        mainHandler.removeCallbacks(hideOverlay);
        if (loadingOverlay == null) return;
        loadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        loadingOverlay.setClickable(visible);
        loadingProgress.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        if (visible) {
            mainHandler.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS);
        } else {
            loadingText.setText("");
        }
    }

    private void showLoadFailure(String message) {
        mainHandler.removeCallbacks(hideOverlay);
        splashHeld = false;
        loadingText.setText(message);
        loadingProgress.setVisibility(View.GONE);
        retryButton.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingOverlay.setClickable(true);
    }

    private void loadUrlWithOverlay(String url) {
        if (bridge != null) bridge.close();
        setOverlayVisible(true);
        webView.loadUrl(url);
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return view;
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        destroyWebView(webView);
        webView = null;
        mainHandler.removeCallbacks(hideOverlay);
        mainHandler.removeCallbacks(releaseSplash);
        mainHandler.removeCallbacks(clearAfterSave);
        if (bridge != null) {
            if (isFinishing()) bridge.clearOutputs();
            else bridge.close();
        }
        if (isFinishing() && importer != null) importer.deleteAll();
        saveExecutor.shutdown();
        importExecutor.shutdown();
        super.onDestroy();
    }

    private void destroyWebView(WebView view) {
        if (view == null) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
        view.stopLoading();
        view.destroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && FileImporter.isIncomingAction(intent)) {
            handleIncomingIntent(intent);
        }
    }

    private TextView createFabButton() {
        TextView button = new TextView(this);
        button.setText(R.string.open_spreadsheet);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);

        int padH = dpToPx(20);
        int padV = dpToPx(14);
        button.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(28));
        button.setBackground(bg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setElevation(dpToPx(8));
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.bottomMargin = dpToPx(24);
        params.rightMargin = dpToPx(20);
        button.setLayoutParams(params);

        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            onFabClick(v);
        });
        return button;
    }

    private void applyWindowInsets(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void applyThemeColors() {
        getWindow().setBackgroundDrawable(new ColorDrawable(getColor(R.color.window_background)));
        if (webView != null) webView.setBackgroundColor(getColor(R.color.window_background));
        if (loadingOverlay != null) loadingOverlay.setBackgroundColor(getColor(R.color.window_background));
        if (loadingText != null) loadingText.setTextColor(getColor(R.color.overlay_text));
        if (fab != null) {
            fab.setTextColor(getColor(R.color.fab_text));
            ((GradientDrawable) fab.getBackground()).setColor(getColor(R.color.fab_background));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        EdgeToEdge.enable(this);
        applyThemeColors();
    }

    private static boolean isAppOrigin(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost());
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        ));
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus(View.FOCUS_DOWN);

        webView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_UP:
                    if (!v.hasFocus()) v.requestFocus();
                    break;
            }
            return false;
        });

        bridge = new AndroidBridge(this, webView, fileSaver, saveExecutor, hostListener, saveListener);
        webView.addJavascriptInterface(bridge, "GridCraftAndroid");

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .setHttpAllowed(false)
                .addPathHandler("/doc/", this::handleDocument)
                .addPathHandler("/android/", path -> openAsset("android/" + path))
                .addPathHandler("/", path -> openAsset("web/" + (path.isEmpty() ? "index.html" : path)))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAppOrigin(uri)) return false;
                String scheme = uri.getScheme();
                boolean web = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
                if (web && request.isForMainFrame()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException e) {
                        Log.w(TAG, "No browser for " + uri);
                    }
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return null;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                        && !HOST.equalsIgnoreCase(uri.getHost())) {
                    return notFound();
                }
                return assetLoader.shouldInterceptRequest(uri);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!importing && loadingOverlay.getVisibility() == View.VISIBLE
                        && retryButton.getVisibility() != View.VISIBLE) {
                    mainHandler.removeCallbacks(hideOverlay);
                    mainHandler.postDelayed(hideOverlay, PAGE_FINISHED_FALLBACK_MS);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Main frame load failed: " + error.getDescription());
                    showLoadFailure(getString(R.string.app_load_failed));
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "Renderer process gone");
                if (view != webView) return true;
                recoverFromRendererLoss();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                String[] accept = params != null ? params.getAcceptTypes() : null;
                boolean multiple = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                if (!launchPicker(chooserLauncher, accept, multiple)) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });
    }

    private void recoverFromRendererLoss() {
        if (bridge != null) bridge.close();
        destroyWebView(webView);
        webView = createWebView();
        rootLayout.addView(webView, 0);
        configureWebView();
        applyThemeColors();
        dirty = false;
        if (!rendererRecoveries.tryAcquire(SystemClock.uptimeMillis())) {
            showLoadFailure(getString(R.string.app_load_failed));
            return;
        }
        reloadCurrent();
        Toast.makeText(this, getString(R.string.renderer_reloaded), Toast.LENGTH_LONG).show();
    }

    private boolean launchPicker(ActivityResultLauncher<Intent> launcher, String[] accept, boolean multiple) {
        try {
            launcher.launch(FileImporter.pickIntent(Intent.ACTION_OPEN_DOCUMENT, accept, multiple)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                launcher.launch(Intent.createChooser(
                        FileImporter.pickIntent(Intent.ACTION_GET_CONTENT, accept, multiple), getString(R.string.choose_file)));
                return true;
            } catch (ActivityNotFoundException ex) {
                Log.e(TAG, "No picker activity available", ex);
                return false;
            }
        }
    }

    private void onChooserResult(int resultCode, Intent data) {
        ValueCallback<Uri[]> callback = filePathCallback;
        filePathCallback = null;
        if (callback == null) return;
        callback.onReceiveValue(resultCode == RESULT_OK ? FileImporter.urisFromChooserResult(data) : null);
    }

    private WebResourceResponse openAsset(String assetPath) {
        if (!Mime.isSafeAssetPath(assetPath)) return notFound();
        try {
            String mime = Mime.fromFileName(assetPath);
            // Hashed web bundle files never change under the same name; host page and html may.
            boolean volatileAsset = assetPath.startsWith("android/") || assetPath.endsWith(".html");
            Map<String, String> headers = new HashMap<>(volatileAsset ? noCacheHeaders() : Collections.emptyMap());
            if (assetPath.endsWith(".html")) headers.put("Content-Security-Policy", CSP);
            return new WebResourceResponse(mime, encodingFor(mime), 200, "OK",
                    headers, getAssets().open(assetPath));
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + assetPath);
            return notFound();
        }
    }

    private static String encodingFor(String mime) {
        boolean text = mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("svg");
        return text ? "UTF-8" : null;
    }

    private WebResourceResponse handleDocument(String path) {
        String id = FileImporter.docIdFromPath(path);
        File cachedFile = id == null ? null : importer.docFile(id, path);
        if (cachedFile != null && cachedFile.exists()) {
            try {
                // Served as opaque bytes: user documents must never be interpreted as scripts or pages.
                Map<String, String> headers = new HashMap<>(noCacheHeaders());
                headers.put("X-Content-Type-Options", "nosniff");
                return new WebResourceResponse(Mime.OCTET_STREAM, null, 200, "OK",
                        headers, new FileInputStream(cachedFile));
            } catch (IOException e) {
                Log.e(TAG, "Cannot stream cached document", e);
            }
        }
        return notFound();
    }

    private static Map<String, String> noCacheHeaders() {
        return Collections.singletonMap("Cache-Control", "no-cache");
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
    }

    private final AndroidBridge.HostListener hostListener = new AndroidBridge.HostListener() {
        @Override
        public void onReady() {
            splashHeld = false;
            setOverlayVisible(false);
        }

        @Override
        public void onFailed(String error) {
            Log.e(TAG, "App failed to start: " + error);
            showLoadFailure(getString(R.string.app_load_failed));
        }

        @Override
        public void onDocumentOpened(boolean opened) {
            updateFab();
        }

        @Override
        public void onDirty(boolean value) {
            dirty = value;
        }
    };

    private void openDirectSpreadsheetPicker() {
        if (!launchPicker(directPickerLauncher, SPREADSHEET_MIMES, false)) {
            Toast.makeText(this, getString(R.string.no_picker_app), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSpreadsheetUri(Uri uri, boolean grantedWrite) {
        boolean persisted = persistPermission(uri);
        boolean writable = grantedWrite || persistedWriteUris().contains(uri.toString());
        startImport(() -> {
            String name = importer.displayName(uri);
            return new Imported(importer.copyToCache(uri, name), name, uri, persisted, writable);
        });
    }

    private void handleSharedText(String text) {
        startImport(() -> {
            String name = FileImporter.sharedTextName(text);
            return new Imported(importer.writeText(text, name), name, null, false, false);
        });
    }

    private interface ImportTask {
        Imported run() throws IOException;
    }

    private record Imported(String id, String name, Uri source, boolean persisted, boolean writable) {}

    private void startImport(ImportTask task) {
        importing = true;
        setOverlayVisible(true);
        loadingText.setText(getString(R.string.opening_spreadsheet));
        importExecutor.execute(() -> {
            try {
                Imported done = task.run();
                importer.cleanup(done.id());
                runOnUiThread(() -> onImported(done));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Import failed", e);
                int message = e instanceof FileImporter.FileTooLargeException
                        ? R.string.file_too_large : R.string.open_spreadsheet_failed;
                runOnUiThread(() -> {
                    importing = false;
                    setOverlayVisible(false);
                    if (webView != null && webView.getUrl() == null) loadUrlWithOverlay(BASE_URL);
                    Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Keeps read (and write when granted) access to the source across restarts; false when unavailable. */
    private boolean persistPermission(Uri uri) {
        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, read | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, read);
                return true;
            } catch (SecurityException | IllegalArgumentException ex) {
                return persistedReadUris().contains(uri.toString());
            }
        }
    }

    private Set<String> persistedReadUris() {
        Set<String> uris = new HashSet<>();
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.isReadPermission()) uris.add(p.getUri().toString());
        }
        return uris;
    }

    private Set<String> persistedWriteUris() {
        Set<String> uris = new HashSet<>();
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.isWritePermission()) uris.add(p.getUri().toString());
        }
        return uris;
    }

    private void onImported(Imported done) {
        importing = false;
        if (isFinishing() || isDestroyed() || webView == null) return;
        String ext = Mime.extensionOf(done.name());
        source = done.source() != null && ext != null
                ? new Source(done.source(), ext, done.id(), done.writable()) : null;
        if (done.persisted() && done.source() != null) {
            updateShortcuts(recents.add(done.source().toString(), done.name()));
        }
        openDocument(done.id(), done.name());
        getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        Toast.makeText(this, getString(R.string.opening_spreadsheet_name, done.name()), Toast.LENGTH_SHORT).show();
    }

    private void updateShortcuts(List<RecentFiles.Entry> entries) {
        try {
            int max = Math.min(SHORTCUT_COUNT, ShortcutManagerCompat.getMaxShortcutCountPerActivity(this));
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
            for (int i = 0; i < entries.size() && i < max; i++) {
                RecentFiles.Entry e = entries.get(i);
                Intent view = new Intent(this, MainActivity.class)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(e.uri()));
                shortcuts.add(new ShortcutInfoCompat.Builder(this, "recent-" + Integer.toHexString(e.uri().hashCode()))
                        .setShortLabel(e.name())
                        .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(view)
                        .build());
            }
            ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot update shortcuts", e);
        }
    }

    private void updateFab() {
        if (fab != null) fab.setText(isDocumentOpen() ? FAB_MENU_LABEL : getString(R.string.open_spreadsheet));
    }

    private void onFabClick(View anchor) {
        List<RecentFiles.Entry> entries = recents.prune(persistedReadUris());
        if (!isDocumentOpen() && entries.isEmpty()) {
            openDirectSpreadsheetPicker();
            return;
        }
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        if (isDocumentOpen()) {
            menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, R.string.action_save);
            menu.add(Menu.NONE, MENU_UNDO, Menu.NONE, R.string.menu_undo);
            menu.add(Menu.NONE, MENU_REDO, Menu.NONE, R.string.menu_redo);
            menu.add(Menu.NONE, MENU_FIND, Menu.NONE, R.string.menu_find);
            menu.add(Menu.NONE, MENU_PASTE, Menu.NONE, R.string.menu_paste);
            menu.add(Menu.NONE, MENU_CLOSE, Menu.NONE, R.string.close);
        }
        menu.add(Menu.NONE, MENU_OPEN, Menu.NONE, R.string.menu_open_file);
        for (int i = 0; i < entries.size(); i++) {
            menu.add(Menu.NONE, MENU_RECENT_BASE + i, Menu.NONE, entries.get(i).name());
        }
        popup.setOnMenuItemClickListener(item -> onMenuItem(item.getItemId(), entries));
        popup.show();
    }

    private boolean onMenuItem(int id, List<RecentFiles.Entry> entries) {
        switch (id) {
            case MENU_OPEN: replaceDocument(this::openDirectSpreadsheetPicker); break;
            case MENU_SAVE: requestSave(); break;
            case MENU_UNDO: evalHost("gcKey('ctrl+z')"); break;
            case MENU_REDO: evalHost("gcKey('ctrl+y')"); break;
            case MENU_FIND: evalHost("gcKey('ctrl+f')"); break;
            case MENU_PASTE: pasteFromClipboard(); break;
            case MENU_CLOSE: requestCloseDocument(); break;
            default:
                int index = id - MENU_RECENT_BASE;
                if (index < 0 || index >= entries.size()) return false;
                Uri uri = Uri.parse(entries.get(index).uri());
                replaceDocument(() -> handleSpreadsheetUri(uri, false));
        }
        return true;
    }

    private void evalHost(String script) {
        if (webView != null) webView.evaluateJavascript("window." + script, null);
    }

    /** Sends the app's Ctrl+S; clears a pending close and reports when the app did not take the key. */
    private void requestSave() {
        if (webView == null) return;
        webView.evaluateJavascript("window.gcKey && window.gcKey('ctrl+s')", result -> {
            if ("true".equals(result)) return;
            cancelAfterSave();
            Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_LONG).show();
        });
    }

    private void cancelAfterSave() {
        afterSave = null;
        mainHandler.removeCallbacks(clearAfterSave);
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (text == null || text.length() == 0) {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        evalHost("gcPaste(" + JSONObject.quote(text.toString()) + ")");
    }

    private void openDocument(String id, String name) {
        currentDocumentId = id;
        currentDocumentName = name;
        dirty = false;
        backPressedTime = 0;
        cancelAfterSave();
        updateBackCallback();
        updateFab();
        String file = Uri.encode("/doc/" + id + "/" + Uri.encode(name));
        loadUrlWithOverlay(BASE_URL + "?file=" + file + "&t=" + System.currentTimeMillis());
    }

    private void closeDocument() {
        currentDocumentId = null;
        currentDocumentName = null;
        dirty = false;
        backPressedTime = 0;
        cancelAfterSave();
        updateBackCallback();
        source = null;
        overwriteConfirmedDocId = null;
        importer.deleteAll();
        if (pendingSaveAs == null && bridge != null) bridge.clearOutputs();
        updateFab();
        if (webView != null) {
            loadUrlWithOverlay(BASE_URL);
            webView.clearHistory();
        }
    }

    private final AndroidBridge.SaveListener saveListener = new AndroidBridge.SaveListener() {
        @Override
        public Source currentSource() {
            return source;
        }

        @Override
        public void onSaveBegun() {
            runOnUiThread(() -> {
                if (afterSave == null) return;
                mainHandler.removeCallbacks(clearAfterSave);
                mainHandler.postDelayed(clearAfterSave, AFTER_SAVE_BEGUN_TIMEOUT_MS);
            });
        }

        @Override
        public void onSaved(AndroidBridge.SavedFile file, String finalName) {
            runOnUiThread(() -> onSaveSucceeded(file, getString(R.string.saved_to_downloads, finalName)));
        }

        @Override
        public boolean onSaveInPlace(AndroidBridge.SavedFile file) {
            return saveInPlace(file);
        }

        @Override
        public void onNeedsDestination(AndroidBridge.SavedFile file) {
            runOnUiThread(() -> launchSaveAs(file, true));
        }

        @Override
        public void onPrint(AndroidBridge.SavedFile file) {
            runOnUiThread(() -> printPdf(file));
        }

        @Override
        public void onFailed(String name, String message) {
            runOnUiThread(() -> onSaveFailed(name, message));
        }
    };

    private void printPdf(AndroidBridge.SavedFile file) {
        if (isFinishing() || isDestroyed()) return;
        PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(this, getString(R.string.print_failed), Toast.LENGTH_LONG).show();
            return;
        }
        String job = currentDocumentName != null ? currentDocumentName : getString(R.string.app_name);
        printManager.print(job, new PdfPrintAdapter(file.file, job), null);
    }

    /**
     * Overwrites the source file captured when the save started, when the saved type matches, the
     * document is still the open one, write access remains, and the user confirmed once per document.
     */
    private boolean saveInPlace(AndroidBridge.SavedFile file) {
        Source src = file.source;
        if (src == null || !src.writable || src.docId == null || !src.docId.equals(currentDocumentId)
                || !src.ext.equals(Mime.extensionOf(file.name))) {
            return false;
        }
        if (checkUriPermission(src.uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!src.docId.equals(overwriteConfirmedDocId) && !confirmOverwrite(src)) return false;
        try {
            fileSaver.overwrite(file.file, src.uri);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "In-place save failed, falling back", e);
            return false;
        }
        String name = fileSaver.displayName(src.uri);
        String shown = name != null ? name : file.name;
        runOnUiThread(() -> onSaveSucceeded(file, getString(R.string.saved_to, shown)));
        return true;
    }

    /** Asks once per document whether its original file may be overwritten; blocks the save thread. */
    private boolean confirmOverwrite(Source src) {
        String shown = fileSaver.displayName(src.uri);
        String label = shown != null ? shown : currentDocumentName;
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        AlertDialog[] dialog = new AlertDialog[1];
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                answer.complete(false);
                return;
            }
            dialog[0] = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.overwrite_title))
                    .setMessage(getString(R.string.overwrite_message, label))
                    .setPositiveButton(getString(R.string.overwrite), (d, w) -> answer.complete(true))
                    .setNegativeButton(getString(R.string.save_copy), (d, w) -> answer.complete(false))
                    .setOnDismissListener(d -> answer.complete(false))
                    .show();
        });
        try {
            boolean ok = answer.get(OVERWRITE_CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
            if (ok) overwriteConfirmedDocId = src.docId;
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            runOnUiThread(() -> {
                if (dialog[0] != null) dialog[0].dismiss();
            });
            return false;
        }
    }

    private void onSaveSucceeded(AndroidBridge.SavedFile file, String message) {
        if (isFinishing() || isDestroyed()) return;
        Runnable then = afterSave;
        cancelAfterSave();
        if (then != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            then.run();
            return;
        }
        getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        List<SaveBar.Action> actions = new ArrayList<>();
        if (!Mime.isOctetStream(file.mime)) {
            actions.add(new SaveBar.Action(getString(R.string.action_open),
                    () -> startSaveAction(SaveActions.view(this, file.file, file.mime))));
        }
        actions.add(new SaveBar.Action(getString(R.string.action_share),
                () -> startSaveAction(SaveActions.share(this, file.file, file.mime))));
        actions.add(new SaveBar.Action(getString(R.string.action_save_as), () -> launchSaveAs(file, false)));
        saveBar.show(message, actions.toArray(new SaveBar.Action[0]));
    }

    private void onSaveFailed(String name, String message) {
        cancelAfterSave();
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startSaveAction(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "No handler for saved file action", e);
            Toast.makeText(this, getString(R.string.no_handler_app), Toast.LENGTH_LONG).show();
        }
    }

    private void launchSaveAs(AndroidBridge.SavedFile file, boolean initial) {
        if (isFinishing() || isDestroyed()) return;
        pendingSaveAs = file;
        pendingSaveAsInitial = initial;
        // No timer while the picker is open; the result (or cancel) settles a pending close.
        mainHandler.removeCallbacks(clearAfterSave);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(file.mime)
                .putExtra(Intent.EXTRA_TITLE, file.name);
        try {
            saveAsLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No activity for ACTION_CREATE_DOCUMENT", e);
            cancelAfterSave();
            Toast.makeText(this, getString(R.string.no_save_location_app), Toast.LENGTH_LONG).show();
        }
    }

    private void onSaveAsResult(int resultCode, Intent data) {
        AndroidBridge.SavedFile file = pendingSaveAs;
        pendingSaveAs = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || file == null) {
            cancelAfterSave();
            if (pendingSaveAsInitial) {
                Toast.makeText(this, getString(R.string.save_cancelled), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Uri targetUri = data.getData();
        saveExecutor.execute(() -> {
            try {
                fileSaver.saveToUri(file.file, targetUri);
                String name = fileSaver.displayName(targetUri);
                runOnUiThread(() -> onSaveSucceeded(file, getString(R.string.saved_to, name != null ? name : file.name)));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "saveToUri failed", e);
                runOnUiThread(() -> onSaveFailed(file.name, getString(R.string.save_failed)));
            }
        });
    }

    /** Only called while a document is open (the callback is disabled otherwise). */
    private void onBack() {
        if (webView == null) return;
        webView.evaluateJavascript("window.gcBack && window.gcBack()", null);
        long now = SystemClock.uptimeMillis();
        if (backPressedTime != 0 && now - backPressedTime < BACK_CLOSE_WINDOW_MS) {
            backPressedTime = 0;
            requestCloseDocument();
        } else {
            backPressedTime = now;
        }
    }

    private void requestCloseDocument() {
        if (dirty) {
            confirmCloseDocument();
        } else {
            closeDocument();
        }
    }

    private void confirmCloseDocument() {
        if (isFinishing() || isDestroyed() || !isDocumentOpen()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_doc_title))
                .setMessage(getString(R.string.close_doc_message))
                .setPositiveButton(getString(R.string.action_save), (d, w) -> saveThenClose())
                .setNegativeButton(getString(R.string.discard), (d, w) -> closeDocument())
                .setNeutralButton(getString(R.string.cancel), null)
                .show();
    }

    /** Triggers the app's Ctrl+S; the document closes once the resulting save succeeds. */
    private void saveThenClose() {
        afterSave = this::closeDocument;
        mainHandler.removeCallbacks(clearAfterSave);
        mainHandler.postDelayed(clearAfterSave, AFTER_SAVE_TIMEOUT_MS);
        requestSave();
    }
}
