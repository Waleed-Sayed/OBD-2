package com.waleedsayed.obd2;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.waleedsayed.obd2.security.AppLock;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Form that starts and stops {@link MockLocationService}.
 *
 * <p>Everything the service injects is chosen here; the activity is a thin controller and reads
 * the service's state back through {@link MockLocationService.Listener}.</p>
 */
public class MockLocationActivity extends AppCompatActivity
        implements MockLocationService.Listener {

    private static final int REQ_NOTIFICATIONS = 301;
    private static final int REQ_LOCATION = 302;

    private static final String PREFS = "mock_location_prefs";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_INTERVAL = "interval";

    private EditText inputLat;
    private EditText inputLon;
    private EditText inputSpeed;
    private EditText inputInterval;
    private TextView statusView;
    private TextView detailsView;
    private View statusDot;
    private View statusCard;
    private Button startButton;
    private Button stopButton;
    private TextView favoriteName;
    private Button favoritesButton;
    private FavoriteStore favorites;

    private boolean bindingFields;
    private boolean listenerAttached;

    private final ActivityResultLauncher<Intent> mapLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            return;
                        }
                        Intent data = result.getData();
                        double latitude =
                                data.getDoubleExtra(MapPickerActivity.RESULT_LAT, Double.NaN);
                        double longitude =
                                data.getDoubleExtra(MapPickerActivity.RESULT_LON, Double.NaN);
                        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                            return;
                        }
                        applyCoordinates(latitude, longitude,
                                getString(R.string.favorite_from_map));
                        toast(getString(R.string.map_picked));
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This screen is only reachable through a verified PIN. Refuse to inflate otherwise so a
        // stray intent (or a stale task entry) can never expose it.
        if (!AppLock.isUnlocked()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_mock_location);

        inputLat = findViewById(R.id.inputLat);
        inputLon = findViewById(R.id.inputLon);
        inputSpeed = findViewById(R.id.inputSpeed);
        inputInterval = findViewById(R.id.inputInterval);
        statusView = findViewById(R.id.mockStatus);
        detailsView = findViewById(R.id.mockDetails);
        statusDot = findViewById(R.id.statusDot);
        statusCard = findViewById(R.id.statusCard);
        startButton = findViewById(R.id.btnStart);
        stopButton = findViewById(R.id.btnStop);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnChangePin).setOnClickListener(v -> startActivity(
                new Intent(this, PinActivity.class).putExtra(PinActivity.EXTRA_SET, true)));
        startButton.setOnClickListener(v -> startMocking());
        stopButton.setOnClickListener(v -> stopMocking());
        findViewById(R.id.btnDevOptions).setOnClickListener(v -> openDeveloperOptions());

        favorites = new FavoriteStore(this);
        favoriteName = findViewById(R.id.favoriteName);
        favoritesButton = findViewById(R.id.btnFavorites);
        findViewById(R.id.btnSaveFavorite).setOnClickListener(v -> saveFavorite());
        favoritesButton.setOnClickListener(v -> showFavorites());
        findViewById(R.id.btnPickTarget).setOnClickListener(v -> openMapPicker());

        restoreValues();
        bindCoordinateWatchers();
        refreshFavoritesButton();

        boolean serviceRunning = MockLocationService.isRunning();
        updateButtons(serviceRunning);
        applyStatus(serviceRunning);
        statusView.setText(serviceRunning ? R.string.mock_running : R.string.mock_status_idle);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MockLocationService.addListener(this);
        listenerAttached = true;
        updateButtons(MockLocationService.isRunning());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listenerAttached) {
            MockLocationService.removeListener(this);
            listenerAttached = false;
        }
    }

    // ------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------

    private void startMocking() {
        Double latitude = parse(inputLat);
        Double longitude = parse(inputLon);
        if (latitude == null || longitude == null
                || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            toast(getString(R.string.mock_bad_number));
            return;
        }

        Double speed = parse(inputSpeed);
        Double intervalSeconds = parse(inputInterval);
        double safeSpeed = speed == null ? 0 : Math.max(0, speed);
        double safeInterval = intervalSeconds == null ? 1 : intervalSeconds;
        safeInterval = Math.max(0.5, Math.min(10.0, safeInterval));

        if (!MockLocationHelper.isMockAllowed(this)) {
            showMockAppDialog();
            return;
        }

        requestRuntimePermissions();
        persist(latitude, longitude, safeSpeed, safeInterval);

        ContextCompat.startForegroundService(this, MockLocationService.startIntent(
                this, latitude, longitude, safeSpeed, (long) (safeInterval * 1000)));
    }

    private void stopMocking() {
        startService(MockLocationService.createStopIntent(this));
    }

    // ------------------------------------------------------------------------------------
    // Target picker + favourites
    // ------------------------------------------------------------------------------------

    private void openMapPicker() {
        Double latitude = parse(inputLat);
        Double longitude = parse(inputLon);
        Intent intent = new Intent(this, MapPickerActivity.class)
                .putExtra(MapPickerActivity.EXTRA_LAT, latitude == null ? 24.7136 : latitude)
                .putExtra(MapPickerActivity.EXTRA_LON, longitude == null ? 46.6753 : longitude);
        mapLauncher.launch(intent);
    }

    /** Writes a coordinate pair into the form without the text watchers renaming it. */
    private void applyCoordinates(double latitude, double longitude, String name) {
        bindingFields = true;
        inputLat.setText(String.format(Locale.US, "%.6f", latitude));
        inputLon.setText(String.format(Locale.US, "%.6f", longitude));
        bindingFields = false;
        favoriteName.setText(name);
    }

    private void bindCoordinateWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!bindingFields) {
                    favoriteName.setText(R.string.favorite_custom);
                }
            }
        };
        inputLat.addTextChangedListener(watcher);
        inputLon.addTextChangedListener(watcher);
    }

    private void saveFavorite() {
        Double latitude = parse(inputLat);
        Double longitude = parse(inputLon);
        if (latitude == null || longitude == null
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            toast(getString(R.string.mock_bad_number));
            return;
        }
        if (favorites.isFull()) {
            toast(getString(R.string.favorites_full));
            return;
        }

        Dialog dialog = createSheet(R.string.save_favorite);

        EditText input = dialog.findViewById(R.id.sheetInput);
        input.setVisibility(View.VISIBLE);
        input.setHint(R.string.favorite_name_hint);
        String suggested = getString(R.string.favorite_default_name, favorites.count() + 1);
        input.setText(suggested);
        input.setSelection(suggested.length());

        Button positive = dialog.findViewById(R.id.sheetPositive);
        positive.setText(R.string.save);
        positive.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                name = getString(R.string.favorite_default_name, favorites.count() + 1);
            }
            if (favorites.add(name, latitude, longitude)) {
                favoriteName.setText(name);
                refreshFavoritesButton();
                toast(getString(R.string.favorite_saved, name));
                dialog.dismiss();
            } else {
                toast(getString(R.string.favorites_full));
            }
        });

        Button negative = dialog.findViewById(R.id.sheetNegative);
        negative.setText(R.string.cancel);
        negative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showFavorites() {
        final List<FavoriteStore.Favorite> items = favorites.all();
        if (items.isEmpty()) {
            toast(getString(R.string.favorites_empty));
            return;
        }

        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            FavoriteStore.Favorite item = items.get(i);
            labels[i] = item.name + "\n"
                    + String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude);
        }

        Dialog dialog = new Dialog(this, R.style.Theme_OBD2_FullscreenDialog);
        dialog.setContentView(R.layout.dialog_favorites);

        ((TextView) dialog.findViewById(R.id.favoritesCount))
                .setText(getString(R.string.favorites_count, items.size(), FavoriteStore.MAX));

        ListView list = dialog.findViewById(R.id.favoritesList);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.sheet_item, labels);
        list.setAdapter(adapter);

        dialog.findViewById(R.id.btnCloseFavorites)
                .setOnClickListener(v -> dialog.dismiss());

        list.setOnItemClickListener((parent, view, position, id) -> {
            FavoriteStore.Favorite item = items.get(position);
            applyCoordinates(item.latitude, item.longitude, item.name);
            toast(getString(R.string.favorite_loaded, item.name));
            dialog.dismiss();
        });

        // Long press removes; a normal tap applies the location.
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            confirmDelete(position, items.get(position).name);
            return true;
        });

        dialog.show();
    }

    private void confirmDelete(int index, String name) {
        Dialog dialog = createSheet(R.string.favorite_delete_title);

        TextView message = dialog.findViewById(R.id.sheetMessage);
        message.setVisibility(View.VISIBLE);
        message.setText(getString(R.string.favorite_delete_message, name));

        Button positive = dialog.findViewById(R.id.sheetPositive);
        positive.setText(R.string.delete);
        positive.setOnClickListener(v -> {
            favorites.remove(index);
            refreshFavoritesButton();
            toast(getString(R.string.favorite_deleted));
            dialog.dismiss();
            showFavorites();
        });

        Button negative = dialog.findViewById(R.id.sheetNegative);
        negative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void refreshFavoritesButton() {
        favoritesButton.setText(getString(R.string.favorites_list)
                + " (" + favorites.count() + "/" + FavoriteStore.MAX + ")");
    }

    private void showMockAppDialog() {
        Dialog dialog = createSheet(R.string.mock_need_app_title);

        TextView message = dialog.findViewById(R.id.sheetMessage);
        message.setVisibility(View.VISIBLE);
        message.setText(R.string.mock_need_app_body);

        Button positive = dialog.findViewById(R.id.sheetPositive);
        positive.setText(R.string.mock_open_dev);
        positive.setOnClickListener(v -> {
            dialog.dismiss();
            openDeveloperOptions();
        });

        Button negative = dialog.findViewById(R.id.sheetNegative);
        negative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** Dialog shell that reuses the same card background as the rest of the app. */
    private Dialog createSheet(int titleRes) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_sheet);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.65f;
            window.setAttributes(attributes);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        ((TextView) dialog.findViewById(R.id.sheetTitle)).setText(titleRes);
        return dialog;
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toast(getString(R.string.mock_unavailable));
            }
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    // ------------------------------------------------------------------------------------
    // Service status
    // ------------------------------------------------------------------------------------

    @Override
    public void onMockStatus(JSONObject status) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean running = status.optBoolean("running");
        double latitude = status.optDouble("latitude", 0);
        double longitude = status.optDouble("longitude", 0);
        long updates = status.optLong("updates", 0);
        String message = status.optString("message", "");

        runOnUiThread(() -> {
            updateButtons(running);
            applyStatus(running);
            if (running) {
                String coordinates = String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
                statusView.setText(R.string.mock_running);
                detailsView.setText(getString(R.string.mock_details_running, coordinates, updates));
            } else {
                statusView.setText(R.string.mock_status_idle);
                detailsView.setText(message);
            }
        });
    }

    /** Green while the service injects fixes, red otherwise. */
    private void applyStatus(boolean running) {
        statusView.setTextColor(ContextCompat.getColor(
                this, running ? R.color.primary : R.color.danger));
        statusDot.setBackgroundResource(
                running ? R.drawable.dot_running : R.drawable.dot_stopped);
        statusCard.setBackgroundResource(
                running ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
    }

    private void updateButtons(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    private void restoreValues() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        inputLat.setText(String.valueOf(prefs.getFloat(KEY_LAT, 24.7136f)));
        inputLon.setText(String.valueOf(prefs.getFloat(KEY_LON, 46.6753f)));
        inputSpeed.setText(String.valueOf(prefs.getFloat(KEY_SPEED, 0f)));
        inputInterval.setText(String.valueOf(prefs.getFloat(KEY_INTERVAL, 1f)));
    }

    private void persist(double latitude, double longitude, double speed, double interval) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(KEY_LAT, (float) latitude)
                .putFloat(KEY_LON, (float) longitude)
                .putFloat(KEY_SPEED, (float) speed)
                .putFloat(KEY_INTERVAL, (float) interval)
                .apply();
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private static Double parse(EditText field) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The service keeps running without notifications; nothing else to do here.
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            // Leaving the hidden screen re-arms the PIN for the next visit.
            AppLock.lock();
        }
        super.onDestroy();
    }
}
