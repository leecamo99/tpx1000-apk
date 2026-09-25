package com.tpx1000.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * TPX1000 — WebView 外殼（支援關螢幕繼續播放）
 */
public class MainActivity extends AppCompatActivity {

    public static final String ACTION_SETTINGS = "com.tpx1000.app.SETTINGS";

    private static final String PREFS = "tpx1000_shell";
    private static final String KEY_URL = "app_url";
    private static final String OFFLINE_PAGE = "file:///android_asset/offline.html";

    private BackgroundMediaWebView web;
    private SwipeRefreshLayout refresh;
    private ProgressBar progress;
    private FrameLayout fullscreenHolder;

    private WebChromeClient chromeClient;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String> micPermissionLauncher;
    private ActivityResultLauncher<String> notifPermissionLauncher;
    private PermissionRequest pendingPermissionRequest;

    private boolean loadFailed = false;
    private boolean playbackActive = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        refresh = findViewById(R.id.refresh);
        progress = findViewById(R.id.progress);
        fullscreenHolder = findViewById(R.id.fullscreen_holder);

        registerLaunchers();
        configureWebView();
        registerBackHandler();

        // 鎖定畫面／耳機的播放暫停鍵 → 轉給網頁
        PlaybackService.setTransportCallback(() -> runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("window.__tpxToggle && window.__tpxToggle()", null);
            }
        }));

        refresh.setColorSchemeColors(0xFFC45C1A);
        refresh.setOnRefreshListener(() -> {
            loadFailed = false;
            web.reload();
        });
        refresh.setOnChildScrollUpCallback((parent, child) -> web.getScrollY() > 0);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
            return;
        }

        if (isSettingsIntent(getIntent())) promptForUrl(false);

        String url = currentUrl();
        if (isInvalid(url)) {
            promptForUrl(true);
        } else {
            loadOrOffline(url);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isSettingsIntent(intent)) promptForUrl(false);
    }

    private boolean isSettingsIntent(Intent intent) {
        return intent != null && ACTION_SETTINGS.equals(intent.getAction());
    }

    // ------------------------------------------------------------- 網址

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String currentUrl() {
        String saved = prefs().getString(KEY_URL, null);
        return TextUtils.isEmpty(saved) ? BuildConfig.TPX_URL : saved;
    }

    private boolean isInvalid(String url) {
        return TextUtils.isEmpty(url)
                || url.contains("example.github.io")
                || !(url.startsWith("http://") || url.startsWith("https://"));
    }

    private void promptForUrl(final boolean firstRun) {
        final EditText input = new EditText(this);
        input.setHint("https://你的帳號.github.io/tpx1000/");
        input.setSingleLine(true);
        String cur = currentUrl();
        if (!isInvalid(cur)) input.setText(cur);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "設定 TPX1000 網址" : "修改網址")
                .setMessage("填入你部署的 GitHub Pages 網址，結尾請保留斜線 /")
                .setView(box)
                .setCancelable(!firstRun)
                .setPositiveButton("儲存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (isInvalid(v)) {
                        Toast.makeText(this, "網址格式不正確", Toast.LENGTH_SHORT).show();
                        promptForUrl(firstRun);
                        return;
                    }
                    prefs().edit().putString(KEY_URL, v).apply();
                    loadFailed = false;
                    loadOrOffline(v);
                });

        if (!firstRun) {
            b.setNegativeButton("取消", null);
            b.setNeutralButton("清快取重載", (d, w) -> {
                web.clearCache(true);
                loadFailed = false;
                loadOrOffline(currentUrl());
            });
        }
        b.show();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void loadOrOffline(String url) {
        web.getSettings().setCacheMode(
                isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        web.loadUrl(url);
    }

    // ---------------------------------------------------------- WebView

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " TPX1000Android/" + BuildConfig.VERSION_NAME);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF0D0D0D);
        web.addJavascriptInterface(new ShellBridge(), "TPXShell");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                refresh.setRefreshing(false);
                progress.setVisibility(View.GONE);
                injectPlaybackBridge();
                if (loadFailed && !url.startsWith("file:///android_asset/")) {
                    web.loadUrl(OFFLINE_PAGE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) loadFailed = true;
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    pendingPermissionRequest = request;
                    boolean wantsAudio = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
                    }
                    if (wantsAudio) {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
                    } else {
                        request.grant(request.getResources());
                        pendingPermissionRequest = null;
                    }
                });
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                originalOrientation = getRequestedOrientation();
                fullscreenHolder.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenHolder.setVisibility(View.VISIBLE);
                refresh.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenHolder.removeView(customView);
                fullscreenHolder.setVisibility(View.GONE);
                refresh.setVisibility(View.VISIBLE);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
                setRequestedOrientation(originalOrientation);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "找不到檔案選擇器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                WebView.HitTestResult hit = view.getHitTestResult();
                String url = hit != null ? hit.getExtra() : null;
                if (url != null) openExternally(Uri.parse(url));
                return false;
            }
        };
        web.setWebChromeClient(chromeClient);

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Toast.makeText(MainActivity.this,
                            "這個檔案由網頁即時產生，請用網頁內的匯出／雲端同步功能",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimetype);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm =
                            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this, "開始下載：" + name,
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    openExternally(Uri.parse(url));
                }
            }
        });
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost();
            String baseHost = Uri.parse(currentUrl()).getHost();
            if (host != null && host.equalsIgnoreCase(baseHost)) return false;
            openExternally(uri);
            return true;
        }
        openExternally(uri);
        return true;
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "沒有 App 可以開啟這個連結", Toast.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------- JS 橋接

    private class ShellBridge {
        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> {
                loadFailed = false;
                loadOrOffline(currentUrl());
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> promptForUrl(false));
        }

        @JavascriptInterface
        public String url() {
            return currentUrl();
        }

        /** 由 playback-bridge.js 回報：現在有沒有音訊在播。 */
        @JavascriptInterface
        public void onPlaybackState(final boolean playing) {
            runOnUiThread(() -> setPlaybackActive(playing));
        }
    }

    // ---------------------------------------------------- 背景播放控制

    private void injectPlaybackBridge() {
        String js = readAsset("playback-bridge.js");
        if (js != null) web.evaluateJavascript(js, null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 有音訊在播 → 開前台服務，程序就不會在關螢幕後被收掉。
     * 停止播放 → 關掉服務，通知消失、WakeLock 釋放，回到正常耗電。
     */
    private void setPlaybackActive(boolean playing) {
        if (playbackActive == playing) return;
        playbackActive = playing;

        if (playing) {
            ensureNotificationPermission();
            PlaybackService.start(this);
        } else {
            PlaybackService.stop(this);
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        if (notifPermissionLauncher != null) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ----------------------------------------------------------- launchers

    private void registerLaunchers() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (filePathCallback == null) return;
                    Uri[] uris = WebChromeClient.FileChooserParams
                            .parseResult(result.getResultCode(), result.getData());
                    filePathCallback.onReceiveValue(uris);
                    filePathCallback = null;
                });

        notifPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    // 拒絕也不影響播放，只是看不到控制通知
                });

        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (pendingPermissionRequest == null) return;
                    if (granted) {
                        pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    } else {
                        pendingPermissionRequest.deny();
                    }
                    pendingPermissionRequest = null;
                });
    }

    // -------------------------------------------------------------- 返回鍵

    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    chromeClient.onHideCustomView();
                    return;
                }
                if (web.canGoBack()) {
                    web.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            promptForUrl(false);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------- 生命週期

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 刻意不呼叫 web.onPause()：那會凍結整個頁面（含音訊解碼與計時器），
        // 正是關螢幕就斷音的主因。背景存活交給 PlaybackService。
        if (web != null && !playbackActive) {
            web.pauseTimers();   // 沒在播的時候才省電
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    @Override
    protected void onDestroy() {
        PlaybackService.setTransportCallback(null);
        PlaybackService.stop(this);
        if (web != null) {
            web.setAllowPause(true);   // 真的要結束了，放行系統的暫停
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
