package com.waleedsayed.obd2;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.waleedsayed.obd2.security.AppLock;
import com.waleedsayed.obd2.security.PinStore;

import java.security.GeneralSecurityException;

/**
 * 4-digit PIN gate.
 *
 * <p>Owns both flows: creating a PIN on first launch (enter twice) and verifying it afterwards.
 * Verification happens against the PBKDF2 verifier in {@link PinStore}; the activity only ever
 * holds the digits typed in the current session.</p>
 */
public class PinActivity extends AppCompatActivity {

    private static final String TAG = "PinActivity";

    /** When true the caller wants to set/change the PIN instead of verifying it. */
    public static final String EXTRA_SET = "extra_set";

    private static final int PIN_LENGTH = 4;
    private static final long SUBMIT_DELAY_MS = 140L;
    private static final long TICK_MS = 400L;

    private final StringBuilder entry = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private PinStore store;
    private boolean settingMode;
    private boolean confirmStage;
    private String firstEntry;

    private TextView titleView;
    private TextView subtitleView;
    private TextView errorView;
    private View[] dots;

    private final Runnable lockTicker = new Runnable() {
        @Override
        public void run() {
            long left = store.lockRemainingMs();
            if (left <= 0) {
                errorView.setText("");
                return;
            }
            errorView.setText(getString(R.string.pin_locked, (left + 999) / 1000));
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the PIN entry out of the recents thumbnail and screenshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_pin);

        store = new PinStore(this);
        settingMode = getIntent().getBooleanExtra(EXTRA_SET, false) || !store.hasPin();

        titleView = findViewById(R.id.pinTitle);
        subtitleView = findViewById(R.id.pinSubtitle);
        errorView = findViewById(R.id.pinError);
        dots = new View[]{
                findViewById(R.id.dot0),
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3)
        };

        wireKeypad();

        View cancel = findViewById(R.id.pinCancel);
        cancel.setOnClickListener(v -> cancel());

        applyMode();
        renderDots();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                cancel();
            }
        });
    }

    private void applyMode() {
        if (settingMode) {
            titleView.setText(R.string.pin_create);
            subtitleView.setText(R.string.pin_hint_set);
        } else {
            titleView.setText(R.string.pin_enter);
            subtitleView.setText(R.string.pin_hint_verify);
        }
    }

    private void wireKeypad() {
        int[] digitIds = {
                R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
                R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9
        };
        for (int i = 0; i < digitIds.length; i++) {
            final char digit = (char) ('0' + i);
            findViewById(digitIds[i]).setOnClickListener(v -> onDigit(digit));
        }
        findViewById(R.id.keyDelete).setOnClickListener(v -> onDelete());
        findViewById(R.id.keyClear).setOnClickListener(v -> onClear());
    }

    private void onDigit(char digit) {
        if (!settingMode && store.lockRemainingMs() > 0) {
            startLockTicker();
            return;
        }
        if (entry.length() >= PIN_LENGTH) {
            return;
        }
        entry.append(digit);
        renderDots();
        if (entry.length() == PIN_LENGTH) {
            ui.postDelayed(this::submit, SUBMIT_DELAY_MS);
        }
    }

    private void onDelete() {
        if (entry.length() > 0) {
            entry.setLength(entry.length() - 1);
            renderDots();
        }
    }

    private void onClear() {
        entry.setLength(0);
        renderDots();
    }

    private void renderDots() {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setBackgroundResource(
                    i < entry.length() ? R.drawable.pin_dot_filled : R.drawable.pin_dot);
        }
    }

    private void submit() {
        if (entry.length() != PIN_LENGTH) {
            return;
        }
        String pin = entry.toString();
        entry.setLength(0);
        renderDots();

        if (settingMode) {
            handleSetup(pin);
        } else {
            handleVerify(pin);
        }
    }

    private void handleSetup(String pin) {
        if (!confirmStage) {
            firstEntry = pin;
            confirmStage = true;
            subtitleView.setText(R.string.pin_confirm);
            errorView.setText("");
            return;
        }
        if (!pin.equals(firstEntry)) {
            confirmStage = false;
            firstEntry = null;
            subtitleView.setText(R.string.pin_hint_set);
            errorView.setText(R.string.pin_mismatch);
            return;
        }
        try {
            store.setPin(pin.toCharArray());
            AppLock.unlock();
            Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK, new Intent());
            finish();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Cannot store PIN", e);
            errorView.setText(R.string.pin_save_error);
        }
    }

    private void handleVerify(String pin) {
        if (store.lockRemainingMs() > 0) {
            startLockTicker();
            return;
        }
        if (store.verify(pin.toCharArray())) {
            AppLock.unlock();
            setResult(RESULT_OK, new Intent());
            finish();
            return;
        }

        long locked = store.lockRemainingMs();
        if (locked > 0) {
            startLockTicker();
            return;
        }
        int left = Math.max(store.failuresLeft(), 1);
        errorView.setText(getString(R.string.pin_wrong, left));
        shake();
    }

    private void startLockTicker() {
        ui.removeCallbacks(lockTicker);
        ui.post(lockTicker);
    }

    private void shake() {
        View root = findViewById(R.id.pinTitle);
        root.setTranslationX(0f);
        root.animate().translationX(18f).setDuration(60).withEndAction(() ->
                root.animate().translationX(-18f).setDuration(60).withEndAction(() ->
                        root.animate().translationX(0f).setDuration(60).start()).start()).start();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
