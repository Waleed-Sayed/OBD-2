package com.waleedsayed.obd2;

import android.Manifest;
import android.content.Context;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native osmdroid variant of the target picker.
 *
 * <p>Same contract as {@link MapPickerActivity}: it returns a latitude/longitude through the result
 * intent. It exists so the map can be switched to a purely native renderer (raster
 * OpenStreetMap tiles, no WebView, no WebGL) while keeping search and "my location".</p>
 */
public class OsmPickerActivity extends AppCompatActivity {

    private static final String TAG = "OsmPicker";

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LON = "extra_lon";
    public static final String RESULT_LAT = "result_lat";
    public static final String RESULT_LON = "result_lon";

    private static final int REQ_LOCATION = 402;

    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private MapView mapView;
    private Marker marker;
    private GeoPoint chosen;

    private TextView coordsView;
    private Button confirmButton;
    private EditText searchInput;
    private ListView resultsView;

    private boolean pendingLocate;
    private final List<double[]> resultPoints = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // osmdroid reads this global config while the MapView is inflated, so configure first.
        IConfigurationProvider configuration = Configuration.getInstance();
        configuration.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        configuration.setUserAgentValue(getPackageName());
        File tileBase = new File(getCacheDir(), "osmdroid");
        configuration.setOsmdroidBasePath(tileBase);
        configuration.setOsmdroidTileCache(new File(tileBase, "tiles"));

        setContentView(R.layout.activity_osm_picker);

        double latitude = getIntent().getDoubleExtra(EXTRA_LAT, 24.7136);
        double longitude = getIntent().getDoubleExtra(EXTRA_LON, 46.6753);

        mapView = findViewById(R.id.osmMap);
        coordsView = findViewById(R.id.osmCoords);
        confirmButton = findViewById(R.id.osmConfirm);
        searchInput = findViewById(R.id.osmSearchInput);
        resultsView = findViewById(R.id.osmResults);

        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.getController().setZoom(13.0);
        mapView.getController().setCenter(new GeoPoint(latitude, longitude));

        marker = new Marker(mapView);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_dot));
        marker.setPosition(new GeoPoint(latitude, longitude));
        marker.setVisible(false);
        mapView.getOverlays().add(marker);

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint point) {
                choose(point.getLatitude(), point.getLongitude());
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint point) {
                return false;
            }
        }));

        findViewById(R.id.osmCancel).setOnClickListener(v -> cancel());
        confirmButton.setOnClickListener(v -> confirm());
        findViewById(R.id.osmSwitch).setOnClickListener(v -> cancel());
        findViewById(R.id.osmLocate).setOnClickListener(v -> locateUser());
        findViewById(R.id.osmSearchBtn).setOnClickListener(v -> runSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------------------------

    private void choose(double latitude, double longitude) {
        chosen = new GeoPoint(latitude, longitude);
        marker.setPosition(chosen);
        marker.setVisible(true);
        mapView.getController().animateTo(chosen);
        mapView.invalidate();

        coordsView.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
        confirmButton.setEnabled(true);
    }

    private void confirm() {
        if (chosen == null) {
            return;
        }
        Intent data = new Intent();
        data.putExtra(RESULT_LAT, chosen.getLatitude());
        data.putExtra(RESULT_LON, chosen.getLongitude());
        setResult(RESULT_OK, data);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    // ------------------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------------------

    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            return;
        }
        hideKeyboard();

        resultsView.setVisibility(View.VISIBLE);
        resultsView.setAdapter(new ArrayAdapter<>(this, R.layout.osm_result_item,
                new String[]{getString(R.string.searching)}));

        network.execute(() -> {
            String payload = NominatimClient.search(query);
            runOnUiThread(() -> showResults(payload));
        });
    }

    private void showResults(String payload) {
        List<String> labels = new ArrayList<>();
        List<double[]> points = new ArrayList<>();

        try {
            JSONObject object = new JSONObject(payload);
            if (object.has("error")) {
                labels.add(getString(R.string.search_failed));
            } else {
                JSONArray array = object.optJSONArray("results");
                for (int i = 0; array != null && i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    labels.add(item.optString("name"));
                    points.add(new double[]{item.optDouble("lat"), item.optDouble("lon")});
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "bad search payload", e);
            labels.add(getString(R.string.search_failed));
        }

        if (labels.isEmpty()) {
            labels.add(getString(R.string.no_results));
        }

        resultPoints.clear();
        resultPoints.addAll(points);

        float density = getResources().getDisplayMetrics().density;
        int rowHeight = Math.round(60 * density);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
        int height = Math.min(labels.size() * rowHeight, maxHeight);
        resultsView.getLayoutParams().height = Math.max(height, rowHeight);
        resultsView.requestLayout();
        resultsView.setAdapter(new ArrayAdapter<>(this, R.layout.osm_result_item, labels));
        resultsView.setVisibility(View.VISIBLE);

        resultsView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= resultPoints.size()) {
                return;
            }
            double[] point = resultPoints.get(position);
            resultsView.setVisibility(View.GONE);
            mapView.getController().setZoom(16.0);
            choose(point[0], point[1]);
        });
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
            located(null);
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
            located(best);
        }
        requestFreshLocation(manager);
    }

    @SuppressWarnings("deprecation")
    private void requestFreshLocation(LocationManager manager) {
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                located(location);
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
                                located(location);
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

    private void located(@Nullable Location location) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (location == null) {
            Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        mapView.getController().setZoom(16.0);
        choose(location.getLatitude(), location.getLongitude());
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
            located(null);
        }
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        if (mapView != null) {
            mapView.onDetach();
        }
        super.onDestroy();
    }
}
