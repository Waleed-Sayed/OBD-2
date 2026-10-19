package com.waleedsayed.obd2;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.waleedsayed.obd2.obd.ObdBridge;
import com.waleedsayed.obd2.obd.ObdManager;
import com.waleedsayed.obd2.security.PinStore;

/**
 * Hosts the single-page WebView UI in {@code assets/obd/index.html}.
 *
 * <p>Java does the Bluetooth, mock-location and PIN work; the HTML/JS layer is only a view plus a
 * controller that talks to {@code NativeBridge} and listens to {@code window.OBD.emit(...)}.</p>
 */
public class MainActivity extends AppCompatActivity implements ObdBridge.HiddenAccessHost {

    private static final String TAG = "MainActivity";
    private static final String HOME_URL = "file:///android_asset/obd/index.html";
    private static final int REQ_BLUETOOTH = 200;

    /**
     * Runs when the PIN prompt closes. Nothing happens unless the PIN was verified, so this is the
     * only path that ever opens the hidden screen.
     */
    private final ActivityResultLauncher<Intent> hiddenLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            startActivity(new Intent(this, MockLocationActivity.class));
                        }
                    });

    private WebView webView;
    private ProgressBar progress;
    private ObdManager obd;
    private ObdBridge bridge;
    private PinStore pinStore;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pinStore = new PinStore(this);
        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);

        configureWebView();

        obd = new ObdManager(getApplicationContext());
        bridge = new ObdBridge(this, webView, obd, pinStore);
        obd.setListener(bridge);
        webView.addJavascriptInterface(bridge, "NativeBridge");

        webView.loadUrl(HOME_URL);

        requestBluetoothPermission();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(
                        "(window.OBD && typeof window.OBD.handleBack === 'function')"
                                + " ? window.OBD.handleBack() : false",
                        value -> {
                            if (!"true".equals(value)) {
                                finish();
                            }
                        });
            }
        });
    }

    /**
     * Entry point for the hidden flow. The page calls this through
     * {@code NativeBridge.requestHidden()}; the PIN prompt is always shown first, and the first
     * ever run asks the user to create the PIN instead of verifying one.
     */
    @Override
    public void requestHiddenAccess() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Intent intent = new Intent(this, PinActivity.class);
        intent.putExtra(PinActivity.EXTRA_SET, !pinStore.hasPin());
        hiddenLauncher.launch(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bridge != null) {
            bridge.pushStatus();
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.release();
        }
        if (obd != null) {
            obd.shutdown();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("NativeBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------
    // WebView setup
    // ------------------------------------------------------------------------------------

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.w(TAG, "No activity for " + uri, e);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                bridge.pushStatus();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("WebView", consoleMessage.message()
                        + " @" + consoleMessage.lineNumber());
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
    }
}
