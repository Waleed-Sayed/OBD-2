package com.waleedsayed.obd2.obd;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.waleedsayed.obd2.security.PinStore;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The only surface the WebView can reach native code through.
 *
 * <p>Every method is annotated with {@link JavascriptInterface} and therefore runs on a WebView
 * worker thread; anything touching the UI is bounced to the main thread. Native results travel
 * back the other way through {@code window.OBD.emit(event)} as JSON.</p>
 *
 * <p>The hidden screen is deliberately absent from this API: the page can only <em>ask</em> for the
 * PIN prompt via {@link #requestHidden()}, and the activity decides what happens after a correct
 * PIN. No JavaScript call can open the mock-location screen directly.</p>
 */
public final class ObdBridge implements ObdManager.Listener {

    /** Implemented by the hosting activity so the page can request the hidden flow. */
    public interface HiddenAccessHost {
        void requestHiddenAccess();
    }

    private final Activity activity;
    private final WebView webView;
    private final ObdManager obd;
    private final PinStore pinStore;
    private final HiddenAccessHost host;

    public ObdBridge(Activity activity, WebView webView, ObdManager obd, PinStore pinStore) {
        this.activity = activity;
        this.webView = webView;
        this.obd = obd;
        this.pinStore = pinStore;
        this.host = activity instanceof HiddenAccessHost ? (HiddenAccessHost) activity : null;
    }

    /** Detaches the listener; call from the activity's onDestroy. */
    public void release() {
        obd.setListener(null);
    }

    // ------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------

    @JavascriptInterface
    public String getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("hasPin", pinStore.hasPin());
            status.put("connected", obd.isConnected());
        } catch (JSONException ignored) {
            // Every key is a primitive; this cannot realistically fail.
        }
        return status.toString();
    }

    /** Pushes the same payload as {@link #getStatus()} but as an event. */
    public void pushStatus() {
        try {
            JSONObject event = new JSONObject(getStatus());
            event.put("type", "status");
            emit(event);
        } catch (JSONException ignored) {
            // As above.
        }
    }

    // ------------------------------------------------------------------------------------
    // OBD
    // ------------------------------------------------------------------------------------

    @JavascriptInterface
    public String getDevices() {
        return obd.bondedDevices().toString();
    }

    @JavascriptInterface
    public void connect(String address) {
        obd.connect(address);
    }

    @JavascriptInterface
    public void connectDemo() {
        obd.connect(ObdManager.DEMO_DEVICE);
    }

    @JavascriptInterface
    public void disconnect() {
        obd.disconnect();
    }

    @JavascriptInterface
    public void queryPid(String pid) {
        obd.queryPid(pid);
    }

    @JavascriptInterface
    public void sendRaw(String command) {
        obd.sendRaw(command);
    }

    @JavascriptInterface
    public void readDtcs() {
        obd.readDtcs();
    }

    @JavascriptInterface
    public void clearDtcs() {
        obd.clearDtcs();
    }

    // ------------------------------------------------------------------------------------
    // Hidden entry point
    // ------------------------------------------------------------------------------------

    /**
     * Asks the activity to show the PIN prompt. Wrong PINs, lockouts and the cancel button all end
     * in "nothing opens"; only a verified PIN continues to the hidden screen.
     */
    @JavascriptInterface
    public void requestHidden() {
        if (host == null) {
            return;
        }
        activity.runOnUiThread(host::requestHiddenAccess);
    }

    @JavascriptInterface
    public void toast(final String message) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------------------------
    // Native -> JS
    // ------------------------------------------------------------------------------------

    @Override
    public void onObdEvent(JSONObject event) {
        emit(event);
    }

    private void emit(final JSONObject event) {
        final String payload = event.toString();
        activity.runOnUiThread(() -> webView.evaluateJavascript(
                "window.OBD && window.OBD.emit && window.OBD.emit(" + payload + ");", null));
    }
}
