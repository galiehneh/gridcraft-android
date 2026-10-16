package com.galiehneh.gridcraft;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private String currentDocumentId = null;
    private String currentDocumentName = null;
    private boolean dirty;
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
    private final Runnable hideOverlay = () -> setOverlayVisible(false);
    private final Runnable releaseSplash = () -> splashHeld = false;
    private View loadingOverlay;
    private View loadingProgress;
    private TextView loadingText;
    private Button retryButton;

    private final ActivityResultLauncher<Intent> chooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> onChooserResult(r.getResultCode(), r.getData()));

    private final ActivityResultLauncher<Intent> directPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Uri uri = r.getResultCode() == RESULT_OK && r.getData() != null ? r.getData().getData() : null;
                if (uri != null) handleSpreadsheetUri(uri);
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

        loadingOverlay = createLoadingOverlay();
        rootLayout.addView(loadingOverlay);

        applyThemeColors();
        setContentView(rootLayout);

        importer = new FileImporter(this);
        fileSaver = new FileSaver(this);
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
        if (targetUri == null) {
            if (!isDocumentOpen()) loadUrlWithOverlay(BASE_URL);
        } else if (dirty && isDocumentOpen()) {
            confirmReplaceDocument(targetUri);
        } else {
            handleSpreadsheetUri(targetUri);
        }
    }

    private void confirmReplaceDocument(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.replace_doc_title))
                .setMessage(getString(R.string.close_doc_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.action_open), (d, w) -> handleSpreadsheetUri(uri))
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
        if (bridge != null) bridge.close();
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
            openDirectSpreadsheetPicker();
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
        reloadCurrent();
        Toast.makeText(this, getString(R.string.renderer_reloaded), Toast.LENGTH_LONG).show();
    }

    private boolean launchPicker(ActivityResultLauncher<Intent> launcher, String[] accept, boolean multiple) {
        try {
            launcher.launch(FileImporter.pickIntent(Intent.ACTION_OPEN_DOCUMENT, accept, multiple));
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
        try {
            String mime = Mime.fromFileName(assetPath);
            // Hashed web bundle files never change under the same name; host page and html may.
            boolean volatileAsset = assetPath.startsWith("android/") || assetPath.endsWith(".html");
            return new WebResourceResponse(mime, encodingFor(mime), 200, "OK",
                    volatileAsset ? noCacheHeaders() : Collections.emptyMap(), getAssets().open(assetPath));
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
                return new WebResourceResponse(Mime.fromFileName(path), null, 200, "OK",
                        noCacheHeaders(), new FileInputStream(cachedFile));
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
            if (fab != null) {
                fab.setVisibility(opened ? View.GONE : View.VISIBLE);
            }
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

    private void handleSpreadsheetUri(Uri uri) {
        importing = true;
        setOverlayVisible(true);
        loadingText.setText(getString(R.string.opening_spreadsheet));
        importExecutor.execute(() -> {
            try {
                String name = importer.displayName(uri);
                String id = importer.copyToCache(uri, name);
                importer.cleanup(id);
                runOnUiThread(() -> onImported(id, name));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Error handling spreadsheet URI: " + uri, e);
                runOnUiThread(() -> {
                    importing = false;
                    setOverlayVisible(false);
                    if (webView != null && webView.getUrl() == null) loadUrlWithOverlay(BASE_URL);
                    Toast.makeText(this, getString(R.string.open_spreadsheet_failed), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void onImported(String id, String name) {
        importing = false;
        if (isFinishing() || isDestroyed() || webView == null) return;
        openDocument(id, name);
        getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        Toast.makeText(this, getString(R.string.opening_spreadsheet_name, name), Toast.LENGTH_SHORT).show();
    }

    private void openDocument(String id, String name) {
        currentDocumentId = id;
        currentDocumentName = name;
        dirty = false;
        updateBackCallback();
        if (fab != null) fab.setVisibility(View.GONE);
        String file = Uri.encode("/doc/" + id + "/" + Uri.encode(name));
        loadUrlWithOverlay(BASE_URL + "?file=" + file + "&t=" + System.currentTimeMillis());
    }

    private void closeDocument() {
        currentDocumentId = null;
        currentDocumentName = null;
        dirty = false;
        updateBackCallback();
        if (fab != null) fab.setVisibility(View.VISIBLE);
        if (webView != null) {
            loadUrlWithOverlay(BASE_URL);
            webView.clearHistory();
        }
    }

    private final AndroidBridge.SaveListener saveListener = new AndroidBridge.SaveListener() {
        @Override
        public void onSaved(AndroidBridge.SavedFile file, String finalName) {
            runOnUiThread(() -> onSaveSucceeded(file, finalName, true));
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

    private void onSaveSucceeded(AndroidBridge.SavedFile file, String name, boolean inDownloads) {
        if (isFinishing() || isDestroyed()) return;
        getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.saved_title))
                .setMessage(getString(R.string.saved_message, name)
                        + (inDownloads ? "\n" + getString(R.string.saved_location_downloads) : ""))
                .setNeutralButton(getString(R.string.action_share), (d, w) -> startSaveAction(SaveActions.share(this, file.file, file.mime)))
                .setNegativeButton(getString(R.string.action_save_as), (d, w) -> launchSaveAs(file, false));
        if (!Mime.isOctetStream(file.mime)) {
            dialog.setPositiveButton(getString(R.string.action_open), (d, w) -> startSaveAction(SaveActions.view(this, file.file, file.mime)));
        }
        dialog.show();
    }

    private void onSaveFailed(String name, String message) {
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
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(file.mime)
                .putExtra(Intent.EXTRA_TITLE, file.name);
        try {
            saveAsLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No activity for ACTION_CREATE_DOCUMENT", e);
            Toast.makeText(this, getString(R.string.no_save_location_app), Toast.LENGTH_LONG).show();
        }
    }

    private void onSaveAsResult(int resultCode, Intent data) {
        AndroidBridge.SavedFile file = pendingSaveAs;
        pendingSaveAs = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || file == null) {
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
                runOnUiThread(() -> onSaveSucceeded(file, name != null ? name : file.name, false));
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
            if (dirty) {
                confirmCloseDocument();
            } else {
                closeDocument();
            }
        } else {
            backPressedTime = now;
        }
    }

    private void confirmCloseDocument() {
        if (isFinishing() || isDestroyed() || !isDocumentOpen()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_doc_title))
                .setMessage(getString(R.string.close_doc_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.close), (d, w) -> closeDocument())
                .show();
    }
}
