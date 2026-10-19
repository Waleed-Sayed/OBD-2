package com.waleedsayed.obd2.security;

/**
 * Session-scoped unlock flag.
 *
 * <p>The flag lives in memory only: killing the process (or the user leaving the app for long
 * enough for Android to reclaim it) locks the app again. {@link PinActivity} owns the transition
 * to {@code unlocked} after a successful PBKDF2 verification.</p>
 */
public final class AppLock {

    private static volatile boolean unlocked;

    private AppLock() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void unlock() {
        unlocked = true;
    }

    public static void lock() {
        unlocked = false;
    }
}
