package com.waleedsayed.obd2;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that keeps a mocked GPS/NETWORK fix alive while the app is in the background.
 *
 * <p>It registers the platform test providers and rewrites their location on a timer. Injection
 * only succeeds while the user has selected this app under <em>Developer options → Select mock
 * location app</em>; otherwise {@code addTestProvider} throws and the service shuts itself down
 * with an error status.</p>
 */
public class MockLocationService extends Service {

    private static final String TAG = "MockLocationService";

    public static final String ACTION_START = "com.waleedsayed.obd2.action.MOCK_START";
    public static final String ACTION_STOP = "com.waleedsayed.obd2.action.MOCK_STOP";

    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_INTERVAL = "interval";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIFICATION_ID = 4201;
    private static final long MIN_INTERVAL_MS = 500L;
    private static final long MAX_INTERVAL_MS = 10_000L;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean running;

    public interface Listener {
        void onMockStatus(JSONObject status);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private double latitude = 24.7136;
    private double longitude = 46.6753;
    private double speed;
    private double bearing = 0;
    private long intervalMs = 1_000L;
    private long updates;

    public static boolean isRunning() {
        return running;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static Intent startIntent(Context context, double latitude, double longitude,
                                     double speed, long intervalMs) {
        return new Intent(context, MockLocationService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LATITUDE, latitude)
                .putExtra(EXTRA_LONGITUDE, longitude)
                .putExtra(EXTRA_SPEED, speed)
                .putExtra(EXTRA_INTERVAL, intervalMs);
    }

    public static Intent createStopIntent(Context context) {
        return new Intent(context, MockLocationService.class).setAction(ACTION_STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START:
                latitude = intent.getDoubleExtra(EXTRA_LATITUDE, latitude);
                longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, longitude);
                speed = Math.max(0, intent.getDoubleExtra(EXTRA_SPEED, 0));
                intervalMs = clampInterval(intent.getLongExtra(EXTRA_INTERVAL, 1_000L));
                startInForeground();
                startMocking();
                return START_NOT_STICKY;
            case ACTION_STOP:
                stopMocking();
                stopSelf();
                return START_NOT_STICKY;
            default:
                return START_NOT_STICKY;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMocking();
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------
    // Foreground + notification
    // ------------------------------------------------------------------------------------

    private void startInForeground() {
        Notification notification = buildNotification();
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.mock_title), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.mock_notification_title));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MockLocationActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, createStopIntent(this),
                PendingIntent.FLAG_IMMUTABLE);

        // The coordinates are the notification's headline; no descriptive phrase.
        String coordinates = String.format(Locale.US, "%.5f, %.5f", latitude, longitude);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(coordinates)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.mock_notification_stop), stopIntent)
                .build();
    }

    private void refreshNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    // ------------------------------------------------------------------------------------
    // Injection
    // ------------------------------------------------------------------------------------

    private void startMocking() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            stopSelf();
            return;
        }

        try {
            ensureProvider(LocationManager.GPS_PROVIDER, Criteria.ACCURACY_FINE);
            ensureProvider(LocationManager.NETWORK_PROVIDER, Criteria.ACCURACY_COARSE);
        } catch (SecurityException e) {
            Log.w(TAG, "Not selected as the mock location app", e);
            notifyStatus(false, "اختر التطبيق من «تحديد تطبيق الموقع الوهمي»");
            stopSelf();
            return;
        }

        running = true;
        updates = 0;
        handler.removeCallbacks(pushRunnable);
        handler.post(pushRunnable);
        notifyStatus(true, null);
    }

    @SuppressLint("WrongConstant")
    private void ensureProvider(String provider, int accuracy) {
        // Criteria.POWER_LOW / Criteria.ACCURACY_* are numerically identical to the
        // ProviderProperties.POWER_USAGE_* / ACCURACY_* values this API actually expects;
        // the provider constants only exist from API 31, so the older ones stay here.
        try {
            locationManager.removeTestProvider(provider);
        } catch (RuntimeException ignored) {
            // No such provider yet: expected on the first run.
        }
        locationManager.addTestProvider(provider,
                false, false, false, false, true, true, true,
                Criteria.POWER_LOW, accuracy);
        locationManager.setTestProviderEnabled(provider, true);
    }

    private final Runnable pushRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            pushLocation();
            handler.postDelayed(this, intervalMs);
        }
    };

    private void pushLocation() {
        if (speed > 0) {
            double meters = speed * (intervalMs / 1000.0);
            double[] next = MockLocationHelper.offset(latitude, longitude, meters, bearing);
            latitude = next[0];
            longitude = next[1];
            bearing = (bearing + 2.5) % 360;
        }

        updates++;
        Location location = MockLocationHelper.buildLocation(
                LocationManager.GPS_PROVIDER, latitude, longitude, speed, bearing, 3f);

        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
            locationManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, location);
        } catch (RuntimeException e) {
            Log.w(TAG, "Mock injection stopped", e);
            notifyStatus(false, "توقّف الحقن — تحقق من اختيار التطبيق في خيارات المطوّر");
            stopMocking();
            stopSelf();
            return;
        }

        if (updates % 5 == 0) {
            refreshNotification();
        }
        notifyStatus(true, null);
    }

    private void stopMocking() {
        boolean wasRunning = running;
        running = false;
        handler.removeCallbacks(pushRunnable);

        if (locationManager != null) {
            for (String provider : new String[]{
                    LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    locationManager.setTestProviderEnabled(provider, false);
                } catch (RuntimeException ignored) {
                    // Provider may already be gone.
                }
                try {
                    locationManager.removeTestProvider(provider);
                } catch (RuntimeException ignored) {
                    // Provider may already be gone.
                }
            }
        }

        if (wasRunning) {
            notifyStatus(false, null);
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
    }

    // ------------------------------------------------------------------------------------
    // Status broadcast
    // ------------------------------------------------------------------------------------

    private void notifyStatus(boolean active, String message) {
        JSONObject status = new JSONObject();
        try {
            status.put("type", "mock");
            status.put("running", active);
            status.put("latitude", latitude);
            status.put("longitude", longitude);
            status.put("speed", speed);
            status.put("updates", updates);
            status.put("message", message == null ? "" : message);
        } catch (JSONException ignored) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onMockStatus(status);
            } catch (RuntimeException e) {
                Log.w(TAG, "Mock listener failed", e);
            }
        }
    }

    private static long clampInterval(long interval) {
        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
    }
}
