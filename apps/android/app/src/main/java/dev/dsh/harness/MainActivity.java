package dev.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Single-screen mobile client for a {@code dsh web} server.
 *
 * <p>The harness itself runs on a computer; this activity connects to it,
 * keeps the browser session cookie, and wraps the Web UI in native affordances:
 * a connection screen with LAN discovery, an unreachable/expired-login screen,
 * uploads, downloads, and microphone access for voice input.</p>
 */
public class MainActivity extends Activity implements LanScanner.Listener {

    private static final String TAG = "DshMobile";
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_MICROPHONE = 1002;
    private static final int REQUEST_STORAGE = 1003;

    private static final int MENU_RELOAD = 1;
    private static final int MENU_CONNECTION = 2;
    private static final int MENU_SCAN = 3;
    private static final int MENU_BROWSER = 4;
    private static final int MENU_ABOUT = 5;

    private WebView webView;
    private View setupPanel;
    private View errorPanel;
    private ImageButton menuButton;
    private ProgressBar progress;
    private EditText serverInput;
    private EditText tokenInput;
    private TextView setupStatus;
    private TextView scanHeader;
    private TextView errorTitle;
    private TextView errorDetail;
    private LinearLayout scanResults;
    private LinearLayout savedList;
    private TextView savedHeader;
    private Button scanButton;
    private Button errorRetry;

    private ServerStore store;
    private LanScanner scanner;
    private ServerTarget target;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private PendingDownload pendingDownload;

    private boolean loadedWithToken;
    private int lastScanPort = ServerTarget.DEFAULT_HTTP_PORT;
    private long lastBackPressed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new ServerStore(this);
        bindViews();
        configureWebView();

        ServerTarget previous = store.last();
        if (previous != null) {
            // Prefer the stored session cookie; the token is only replayed if the
            // server restarted and answers 401.
            open(previous, false);
        } else {
            showSetup();
        }
    }

    // ---------------------------------------------------------------- views

    private void bindViews() {
        webView = findViewById(R.id.web_view);
        setupPanel = findViewById(R.id.setup_panel);
        errorPanel = findViewById(R.id.error_panel);
        menuButton = findViewById(R.id.menu_button);
        progress = findViewById(R.id.progress);
        serverInput = findViewById(R.id.server_input);
        tokenInput = findViewById(R.id.token_input);
        setupStatus = findViewById(R.id.setup_status);
        scanHeader = findViewById(R.id.scan_header);
        scanResults = findViewById(R.id.scan_results);
        savedHeader = findViewById(R.id.saved_header);
        savedList = findViewById(R.id.saved_list);
        errorTitle = findViewById(R.id.error_title);
        errorDetail = findViewById(R.id.error_detail);
        scanButton = findViewById(R.id.scan_button);
        errorRetry = findViewById(R.id.error_retry);

        ((Button) findViewById(R.id.connect_button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectFromSetup();
            }
        });
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan();
            }
        });
        ((Button) findViewById(R.id.paste_button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pasteFromClipboard();
            }
        });
        findViewById(R.id.command_text).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyCommand();
            }
        });
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        ((Button) findViewById(R.id.error_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSetup();
            }
        });
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " DshMobile/" + versionName());
        settings.setAllowFileAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(getColor(R.color.bg));
        webView.setWebViewClient(new HarnessClient());
        webView.setWebChromeClient(new HarnessChrome());
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                handleDownload(url, contentDisposition, mimeType);
            }
        });

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
    }

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "dev" : name;
        } catch (PackageManager.NameNotFoundException unknown) {
            return "dev";
        }
    }

    // ------------------------------------------------------------- connection

    private void connectFromSetup() {
        String address = serverInput.getText().toString();
        String token = tokenInput.getText().toString();
        ServerTarget parsed = ServerTarget.parse(address, token);
        if (parsed == null) {
            showStatus(getString(R.string.status_invalid_address), true);
            serverInput.requestFocus();
            return;
        }
        serverInput.setText(parsed.baseUrl());
        tokenInput.setText(parsed.token == null ? "" : parsed.token);
        hideKeyboard();
        store.remember(parsed);
        open(parsed, parsed.hasToken());
    }

    private void open(ServerTarget next, boolean useToken) {
        target = next;
        loadedWithToken = useToken && next.hasToken();
        showWeb();
        progress.setProgress(5);
        progress.setVisibility(View.VISIBLE);
        webView.loadUrl(loadedWithToken ? next.tokenUrl() : next.baseUrl());
    }

    private void retry() {
        if (target == null) {
            showSetup();
        } else {
            open(target, target.hasToken());
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            showStatus(getString(R.string.status_clipboard_empty), true);
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            showStatus(getString(R.string.status_clipboard_empty), true);
            return;
        }
        CharSequence coerced = clip.getItemAt(0).coerceToText(this);
        String value = coerced == null ? "" : coerced.toString().trim();
        if (value.isEmpty()) {
            showStatus(getString(R.string.status_clipboard_empty), true);
            return;
        }
        ServerTarget parsed = ServerTarget.parse(value, null);
        if (parsed != null) {
            serverInput.setText(parsed.baseUrl());
            if (parsed.hasToken()) {
                tokenInput.setText(parsed.token);
            }
            showStatus(getString(R.string.status_pasted), false);
        } else {
            serverInput.setText(value);
            showStatus(getString(R.string.status_invalid_address), true);
        }
    }

    private void copyCommand() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.app_name), getString(R.string.setup_command)));
        }
        toast(getString(R.string.status_command_copied));
    }

    // ------------------------------------------------------------------ scan

    private void startScan() {
        int port = ServerTarget.DEFAULT_HTTP_PORT;
        ServerTarget typed = ServerTarget.parse(serverInput.getText().toString(), null);
        if (typed != null) {
            port = typed.port;
        }
        if (scanner != null) {
            scanner.cancel();
        }
        lastScanPort = port;
        scanner = new LanScanner();
        scanButton.setEnabled(false);
        scanButton.setText(R.string.scanning);
        showStatus(getString(R.string.status_scanning, port), false);
        scanner.start(port, 400, this);
    }

    @Override
    public void onScanFinished(List<String> addresses) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        scanButton.setEnabled(true);
        scanButton.setText(R.string.scan_network);
        renderScanResults(addresses, lastScanPort);
    }

    private void renderScanResults(List<String> addresses, final int port) {
        scanResults.removeAllViews();
        if (addresses.isEmpty()) {
            scanResults.setVisibility(View.GONE);
            scanHeader.setVisibility(View.GONE);
            showStatus(getString(R.string.status_scan_empty, port), true);
            return;
        }
        for (final String address : addresses) {
            View row = getLayoutInflater().inflate(R.layout.row_server, scanResults, false);
            ((TextView) row.findViewById(R.id.row_title)).setText(address + ":" + port);
            ((TextView) row.findViewById(R.id.row_subtitle)).setText(R.string.row_tap_to_use);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    serverInput.setText(address + ":" + port);
                    scanResults.setVisibility(View.GONE);
                    scanHeader.setVisibility(View.GONE);
                    showStatus(getString(R.string.status_scan_selected), false);
                    tokenInput.requestFocus();
                }
            });
            scanResults.addView(row);
        }
        scanHeader.setVisibility(View.VISIBLE);
        scanResults.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.status_scan_found), false);
    }

    // ------------------------------------------------------------------- web

    private void showWeb() {
        setupPanel.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        menuButton.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
    }

    private void showSetup() {
        setupPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        menuButton.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        if (target != null) {
            serverInput.setText(target.baseUrl());
            tokenInput.setText(target.token == null ? "" : target.token);
        }
        refreshRecent();
    }

    private void refreshRecent() {
        savedList.removeAllViews();
        List<ServerTarget> recent = store.recent();
        savedHeader.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        for (final ServerTarget entry : recent) {
            View row = getLayoutInflater().inflate(R.layout.row_server, savedList, false);
            ((TextView) row.findViewById(R.id.row_title)).setText(entry.display());
            ((TextView) row.findViewById(R.id.row_subtitle)).setText(
                    entry.hasToken() ? R.string.row_has_token : R.string.row_no_token);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    serverInput.setText(entry.baseUrl());
                    tokenInput.setText(entry.token == null ? "" : entry.token);
                    connectFromSetup();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    confirmForget(entry);
                    return true;
                }
            });
            savedList.addView(row);
        }
    }

    private void confirmForget(final ServerTarget entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.row_remove_title)
                .setMessage(getString(R.string.row_remove_body, entry.display()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.forget(entry.baseUrl());
                        refreshRecent();
                    }
                })
                .show();
    }

    private void showStatus(String message, boolean warning) {
        setupStatus.setText(message);
        setupStatus.setTextColor(getColor(warning ? R.color.danger : R.color.muted));
        setupStatus.setVisibility(View.VISIBLE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    // ----------------------------------------------------------- error states

    private void showFailure(String title, String detail, boolean loginProblem) {
        errorTitle.setText(title);
        errorDetail.setText(detail);
        setupPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        menuButton.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        if (loginProblem) {
            errorRetry.setText(R.string.update_token);
            errorRetry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showSetup();
                    tokenInput.requestFocus();
                    tokenInput.selectAll();
                }
            });
        } else {
            errorRetry.setText(R.string.retry);
            errorRetry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    retry();
                }
            });
        }
    }

    private void showNetworkFailure(int code, String description, Uri uri) {
        String message;
        if (code == WebViewClient.ERROR_HOST_LOOKUP) {
            message = getString(R.string.err_dns);
        } else if (code == WebViewClient.ERROR_CONNECT) {
            message = getString(R.string.err_refused);
        } else if (code == WebViewClient.ERROR_TIMEOUT) {
            message = getString(R.string.err_timeout);
        } else {
            message = getString(R.string.err_generic);
        }
        String endpoint = uri == null ? "" : String.valueOf(uri.getHost());
        if (uri != null && uri.getPort() > 0) {
            endpoint = endpoint + ":" + uri.getPort();
        }
        StringBuilder detail = new StringBuilder(message);
        if (!endpoint.isEmpty()) {
            detail.append("\n\n").append(endpoint);
        }
        if (description != null && !description.isEmpty()) {
            detail.append("\n").append(description);
        }
        showFailure(getString(R.string.err_unreachable_title), detail.toString(), false);
    }

    private void handleLoginFailure() {
        if (target == null) {
            showSetup();
            return;
        }
        if (!loadedWithToken && target.hasToken()) {
            loadedWithToken = true;
            final String loginUrl = target.tokenUrl();
            progress.setVisibility(View.VISIBLE);
            webView.post(new Runnable() {
                @Override
                public void run() {
                    webView.loadUrl(loginUrl);
                }
            });
            return;
        }
        showFailure(getString(R.string.err_auth_title), getString(R.string.err_auth_detail), true);
    }

    // -------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_RELOAD, 0, R.string.menu_reload);
        menu.getMenu().add(0, MENU_CONNECTION, 1, R.string.menu_connection);
        menu.getMenu().add(0, MENU_SCAN, 2, R.string.menu_scan);
        if (target != null) {
            menu.getMenu().add(0, MENU_BROWSER, 3, R.string.menu_open_browser);
        }
        menu.getMenu().add(0, MENU_ABOUT, 4, R.string.menu_about);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_RELOAD:
                        if (webView != null) {
                            tokenInput.clearFocus();
                            webView.reload();
                        }
                        return true;
                    case MENU_CONNECTION:
                        showSetup();
                        return true;
                    case MENU_SCAN:
                        showSetup();
                        startScan();
                        return true;
                    case MENU_BROWSER:
                        if (target != null) {
                            openExternally(Uri.parse(target.tokenUrl()));
                        }
                        return true;
                    case MENU_ABOUT:
                        showAbout();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException missing) {
            toast(getString(R.string.err_no_handler));
        }
    }

    // ------------------------------------------------------------ downloads

    private void handleDownload(String url, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new PendingDownload(url, contentDisposition, mimeType);
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        enqueueDownload(url, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String contentDisposition, String mimeType) {
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            toast(getString(R.string.status_download_failed));
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) {
                request.setMimeType(mimeType);
            }
            request.setTitle(fileName);
            request.setDescription(getString(R.string.app_name));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            String agent = webView == null ? null : webView.getSettings().getUserAgentString();
            if (agent != null) {
                request.addRequestHeader("User-Agent", agent);
            }
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            manager.enqueue(request);
            toast(getString(R.string.status_download_started, fileName));
        } catch (Exception failed) {
            Log.w(TAG, "download failed", failed);
            toast(getString(R.string.status_download_failed));
        }
    }

    // ---------------------------------------------------------- permissions

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_MICROPHONE) {
            PermissionRequest pending = pendingPermission;
            pendingPermission = null;
            if (pending != null) {
                if (granted) {
                    pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pending.deny();
                }
            }
        } else if (requestCode == REQUEST_STORAGE) {
            PendingDownload pending = pendingDownload;
            pendingDownload = null;
            if (pending != null) {
                if (granted) {
                    enqueueDownload(pending.url, pending.contentDisposition, pending.mimeType);
                } else {
                    toast(getString(R.string.status_download_failed));
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE) {
            return;
        }
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback != null) {
            callback.onReceiveValue(result);
        }
    }

    // -------------------------------------------------------------- back

    @Override
    public void onBackPressed() {
        if (errorPanel.getVisibility() == View.VISIBLE) {
            showSetup();
            return;
        }
        if (setupPanel.getVisibility() == View.VISIBLE) {
            if (target != null) {
                showWeb();
            } else {
                super.onBackPressed();
            }
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressed < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressed = now;
        toast(getString(R.string.back_to_exit));
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onDestroy() {
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            android.view.ViewParent parent = webView.getParent();
            if (parent instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) parent).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // --------------------------------------------------------------- webview

    private final class HarnessClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return interceptNavigation(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return interceptNavigation(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (progress != null) {
                progress.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request == null || !request.isForMainFrame() || error == null) {
                return;
            }
            CharSequence description = error.getDescription();
            showNetworkFailure(error.getErrorCode(),
                    description == null ? "" : description.toString(), request.getUrl());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request == null || !request.isForMainFrame() || response == null) {
                return;
            }
            int status = response.getStatusCode();
            if (status == 401 || status == 403) {
                handleLoginFailure();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.w(TAG, "web renderer exited");
            toast(getString(R.string.err_renderer));
            recreate();
            return true;
        }
    }

    private boolean interceptNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (isSameServer(uri, scheme)) {
                return false;
            }
            openExternally(uri);
            return true;
        }
        if ("file".equals(scheme) || "javascript".equals(scheme) || "about".equals(scheme)
                || "data".equals(scheme) || "blob".equals(scheme)) {
            return false;
        }
        openExternally(uri);
        return true;
    }

    private boolean isSameServer(Uri uri, String scheme) {
        if (target == null || uri.getHost() == null || !target.scheme.equalsIgnoreCase(scheme)) {
            return false;
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return target.host.equalsIgnoreCase(uri.getHost()) && port == target.port;
    }

    private final class HarnessChrome extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progress != null) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (!(resultMsg.obj instanceof WebView.WebViewTransport)) {
                return false;
            }
            final WebView bridge = new WebView(MainActivity.this);
            bridge.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    openExternally(request.getUrl());
                    bridge.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            bridge.destroy();
                        }
                    }, 500);
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView ignored, String url) {
                    openExternally(Uri.parse(url));
                    bridge.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            bridge.destroy();
                        }
                    }, 500);
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(bridge);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQUEST_FILE);
                return true;
            } catch (ActivityNotFoundException missing) {
                fileCallback = null;
                toast(getString(R.string.err_no_file_picker));
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            boolean wantsMicrophone = false;
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    wantsMicrophone = true;
                }
            }
            if (!wantsMicrophone) {
                request.deny();
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPermission = request;
                requestPermissions(
                        new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermission == request) {
                pendingPermission = null;
            }
        }
    }

    /** A download intercepted before the storage permission was answered. */
    private static final class PendingDownload {
        final String url;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String contentDisposition, String mimeType) {
            this.url = url;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }
}
