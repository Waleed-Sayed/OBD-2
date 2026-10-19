package com.waleedsayed.obd2;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

/** Small helpers shared by the mock-location service, its activity and the JS bridge. */
public final class MockLocationHelper {

    private static final float DEFAULT_ALTITUDE_METERS = 35f;

    private MockLocationHelper() {
    }

    /**
     * Whether the system currently lets this app inject test locations.
     *
     * <p>This mirrors the "Select mock location app" developer setting. Even when this returns
     * true the provider call can still throw, so callers must also guard with try/catch.</p>
     */
    @SuppressWarnings("deprecation")
    public static boolean isMockAllowed(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) {
            return false;
        }
        try {
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Moves a coordinate {@code meters} along {@code bearingDegrees}. */
    public static double[] offset(double latitude, double longitude,
                                  double meters, double bearingDegrees) {
        double earthRadius = 6_371_000.0;
        double angular = meters / earthRadius;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(latitude);
        double lon1 = Math.toRadians(longitude);

        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
        double lon2 = lon1 + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));

        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }

    /** Builds a fully populated test location; unset fields make some clients ignore the fix. */
    public static Location buildLocation(String provider, double latitude, double longitude,
                                         double speedMetersPerSecond, double bearingDegrees,
                                         float accuracyMeters) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAltitude(DEFAULT_ALTITUDE_METERS);
        location.setAccuracy(accuracyMeters);
        location.setSpeed((float) speedMetersPerSecond);
        location.setBearing((float) bearingDegrees);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setVerticalAccuracyMeters(accuracyMeters);
        location.setSpeedAccuracyMetersPerSecond(0.5f);
        location.setBearingAccuracyDegrees(1f);
        return location;
    }
}
