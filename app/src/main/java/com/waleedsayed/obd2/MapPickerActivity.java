package com.waleedsayed.obd2;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen map used to pick a target point.
 *
 * <p>The map is a bundled MapLibre GL page in {@code assets/map/picker.html} rendering OpenFreeMap
 * vector tiles (no API key, no dependency on Google). Search is proxied through native code because
 * Nominatim does not send CORS headers, and the "current location" button uses Android's location
 * providers directly.</p>
 */
public class MapPickerActivity extends AppCompatActivity {

    private static final String TAG = "MapPicker";

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LON = "extra_lon";
    public static final String RESULT_LAT = "result_lat";
    public static final String RESULT_LON = "result_lon";

    private static final int REQ_LOCATION = 401;

    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private WebView webView;
    private ProgressBar progress;

    private boolean pendingLocate;
    private double chosenLat;
    private double chosenLon;

    /** Opens the native osmdroid screen; a confirmed point comes back as our own result. */
    private final ActivityResultLauncher<Intent> osmLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            return;
                        }
                        double latitude = result.getData()
                                .getDoubleExtra(OsmPickerActivity.RESULT_LAT, Double.NaN);
                        double longitude = result.getData()
                                .getDoubleExtra(OsmPickerActivity.RESULT_LON, Double.NaN);
                        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
                            finishWith(latitude, longitude);
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_picker);

        webView = findViewById(R.id.mapWebView);
        progress = findViewById(R.id.mapProgress);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setUserAgentString(settings.getUserAgentString() + " OBD2Map/1.0");

        progress.setVisibility(View.VISIBLE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });
        webView.addJavascriptInterface(new Bridge(), "MapBridge");

        double latitude = getIntent().getDoubleExtra(EXTRA_LAT, 24.7136);
        double longitude = getIntent().getDoubleExtra(EXTRA_LON, 46.6753);
        chosenLat = latitude;
        chosenLon = longitude;

        webView.loadUrl("file:///android_asset/map/picker.html?lat=" + latitude + "&lon=" + longitude);
    }

    private void finishWith(double latitude, double longitude) {
        Intent data = new Intent();
        data.putExtra(RESULT_LAT, latitude);
        data.putExtra(RESULT_LON, longitude);
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("MapBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------
    // Current location
    // ------------------------------------------------------------------------------------

    private void locateUser() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocate = true;
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            sendLocation(null);
            return;
        }

        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null
                        && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Provider missing or not permitted: try the next one.
            }
        }
        if (best != null) {
            sendLocation(best);
        }
        requestFreshLocation(manager);
    }

    @SuppressWarnings("deprecation")
    private void requestFreshLocation(LocationManager manager) {
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                sendLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, null,
                        getMainExecutor(), location -> {
                            if (location != null) {
                                sendLocation(location);
                            }
                        });
            } else {
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener,
                        Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "No fresh fix available", e);
        }
    }

    private void sendLocation(@Nullable Location location) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String script = location == null
                ? "window.onNativeLocation(0,0,false);"
                : "window.onNativeLocation(" + location.getLatitude() + ","
                        + location.getLongitude() + ",true);";
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION || !pendingLocate) {
            return;
        }
        pendingLocate = false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            locateUser();
        } else {
            sendLocation(null);
        }
    }

    /** Only the bundled page can reach this, and it can only return a coordinate or ask for a fix. */
    private final class Bridge {

        @JavascriptInterface
        public void pick(final double latitude, final double longitude) {
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return;
            }
            runOnUiThread(() -> finishWith(latitude, longitude));
        }

        @JavascriptInterface
        public void cancel() {
            runOnUiThread(MapPickerActivity.this::cancel);
        }

        @JavascriptInterface
        public void search(final String query) {
            if (query == null || query.trim().isEmpty()) {
                return;
            }
            final String cleaned = query.trim();
            network.execute(() -> {
                final String payload = NominatimClient.search(cleaned);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        webView.evaluateJavascript("window.onSearchResults(" + payload + ");", null);
                    }
                });
            });
        }

        @JavascriptInterface
        public void locate() {
            runOnUiThread(MapPickerActivity.this::locateUser);
        }

        /** Keeps the native side aware of the point currently marked in the WebView. */
        @JavascriptInterface
        public void selectionChanged(double latitude, double longitude) {
            if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
                chosenLat = latitude;
                chosenLon = longitude;
            }
        }

        @JavascriptInterface
        public void openOsm() {
            runOnUiThread(MapPickerActivity.this::openOsmPicker);
        }
    }

    private void openOsmPicker() {
        Intent intent = new Intent(this, OsmPickerActivity.class)
                .putExtra(OsmPickerActivity.EXTRA_LAT, chosenLat)
                .putExtra(OsmPickerActivity.EXTRA_LON, chosenLon);
        osmLauncher.launch(intent);
    }
}
