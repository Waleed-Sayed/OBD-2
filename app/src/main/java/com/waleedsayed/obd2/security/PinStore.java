package com.waleedsayed.obd2.security;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores and verifies the 4-digit entry PIN.
 *
 * <p>Only a PBKDF2-HMAC-SHA256 verifier is persisted: a random 16-byte salt, the derived key and
 * the iteration count. The PIN itself is never written anywhere. Five wrong attempts trigger a
 * 30 second lockout.</p>
 *
 * <p>A 4-digit PIN has a 10,000-value keyspace, so PBKDF2 slows brute force but cannot make the
 * PIN strong. The real protection is the lockout counter plus the fact that the verifier never
 * leaves the device.</p>
 *
 * <p>{@code commit()} is used on purpose instead of {@code apply()}: the verifier has to be on
 * disk before the activity finishes, otherwise a crash right after saving would lose the PIN.</p>
 */
@SuppressLint("ApplySharedPref")
public final class PinStore {

    private static final String TAG = "PinStore";
    private static final String PREFS_NAME = "obd2_security";

    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_ITERATIONS = "pin_iterations";
    private static final String KEY_FAILURES = "pin_failures";
    private static final String KEY_LOCK_UNTIL = "pin_lock_until";

    public static final int PIN_LENGTH = 4;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_MS = 30_000L;

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    public PinStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean hasPin() {
        return prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT);
    }

    /** Derives and stores a verifier for {@code pin}. */
    public void setPin(char[] pin) throws GeneralSecurityException {
        if (pin == null || pin.length != PIN_LENGTH) {
            throw new IllegalArgumentException("PIN must be " + PIN_LENGTH + " digits");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(pin, salt, ITERATIONS);
        try {
            prefs.edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putInt(KEY_ITERATIONS, ITERATIONS)
                    .putInt(KEY_FAILURES, 0)
                    .remove(KEY_LOCK_UNTIL)
                    .commit();
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    /** Constant-time verification. Records failures and arms the lockout. */
    public synchronized boolean verify(char[] pin) {
        if (!hasPin() || pin == null || pin.length != PIN_LENGTH) {
            return false;
        }
        if (lockRemainingMs() > 0) {
            return false;
        }

        byte[] salt = Base64.decode(prefs.getString(KEY_SALT, ""), Base64.NO_WRAP);
        byte[] expected = Base64.decode(prefs.getString(KEY_HASH, ""), Base64.NO_WRAP);
        int iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS);

        byte[] actual = null;
        try {
            actual = derive(pin, salt, iterations);
            boolean matches = MessageDigest.isEqual(expected, actual);
            if (matches) {
                resetFailures();
            } else {
                recordFailure();
            }
            return matches;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            Log.e(TAG, "PIN verification failed", e);
            recordFailure();
            return false;
        } finally {
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
            Arrays.fill(expected, (byte) 0);
        }
    }

    public int failuresLeft() {
        return Math.max(0, MAX_ATTEMPTS - prefs.getInt(KEY_FAILURES, 0));
    }

    public long lockRemainingMs() {
        long until = prefs.getLong(KEY_LOCK_UNTIL, 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    /** Removes the PIN and every counter. Returns whether a PIN existed. */
    public boolean clearPin() {
        boolean had = hasPin();
        prefs.edit().clear().commit();
        return had;
    }

    private void recordFailure() {
        int failures = prefs.getInt(KEY_FAILURES, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit();
        if (failures >= MAX_ATTEMPTS) {
            editor.putInt(KEY_FAILURES, 0);
            editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCKOUT_MS);
        } else {
            editor.putInt(KEY_FAILURES, failures);
        }
        editor.commit();
    }

    private void resetFailures() {
        prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCK_UNTIL).commit();
    }

    /** PBKDF2-HMAC-SHA256, 256-bit output. */
    public static byte[] derive(char[] pin, byte[] salt, int iterations)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, KEY_BITS);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
