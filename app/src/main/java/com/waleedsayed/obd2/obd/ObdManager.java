package com.waleedsayed.obd2.obd;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bluetooth transport for an ELM327-compatible adapter.
 *
 * <p>All socket work happens on a single daemon thread so the UI and WebView threads never block.
 * Results are published as JSON events through {@link Listener}; nothing is returned synchronously.
 * Events are emitted in this order: {@code state}, {@code pid}, {@code dtc}, {@code raw},
 * {@code error}.</p>
 *
 * <p>{@link #connect(String)} accepts the literal {@code "DEMO"} to run the whole pipeline against
 * simulated data, which is what the WebView UI uses when no adapter is paired.</p>
 */
public final class ObdManager {

    private static final String TAG = "ObdManager";

    public static final String DEMO_DEVICE = "DEMO";

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long COMMAND_TIMEOUT_MS = 3_000L;
    private static final String[] INIT_SEQUENCE = {"ATZ", "ATE0", "ATL0", "ATS0", "ATSP0"};

    public interface Listener {
        void onObdEvent(JSONObject event);
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "obd-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Random demoRandom = new Random();

    private volatile Listener listener;
    private volatile BluetoothSocket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile boolean demo;
    private volatile String deviceLabel = "";

    public ObdManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    public JSONArray bondedDevices() {
        JSONArray result = new JSONArray();
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return result;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return result;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Log.w(TAG, "Bonded devices need BLUETOOTH_CONNECT", e);
            return result;
        }
        if (bonded == null) {
            return result;
        }
        for (BluetoothDevice device : bonded) {
            try {
                JSONObject item = new JSONObject();
                item.put("name", safeName(device));
                item.put("address", device.getAddress());
                result.put(item);
            } catch (JSONException | SecurityException e) {
                Log.w(TAG, "Skipping device", e);
            }
        }
        return result;
    }

    public void connect(final String address) {
        if (address == null || address.trim().isEmpty()) {
            emitError("لم يتم اختيار جهاز", null);
            return;
        }
        io.execute(() -> {
            if (connected.get() || socket != null) {
                connected.set(false);
                demo = false;
                closeQuietly();
            }
            connectBlocking(address.trim());
        });
    }

    public void disconnect() {
        io.execute(() -> {
            connected.set(false);
            demo = false;
            closeQuietly();
            deviceLabel = "";
            emitState("disconnected", "");
        });
    }

    public void queryPid(String pid) {
        final String code = normalizePid(pid);
        if (code.length() != 2) {
            emitError("معرّف PID غير صالح", pid);
            return;
        }
        io.execute(() -> {
            try {
                if (demo) {
                    dispatch(demoReading(code));
                } else if (!connected.get()) {
                    emitError("غير متصل", "اتصل بالمحوّل أولًا");
                } else {
                    dispatch(readPid(code));
                }
            } catch (Exception e) {
                emitError("تعذّرت قراءة " + code, e.getMessage());
            }
        });
    }

    public void sendRaw(final String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        io.execute(() -> {
            try {
                String raw = demo ? demoRaw(command.trim()) : runCommand(command.trim());
                JSONObject event = new JSONObject();
                event.put("type", "raw");
                event.put("command", command.trim());
                event.put("raw", raw);
                dispatch(event);
            } catch (Exception e) {
                emitError("فشل تنفيذ الأمر", e.getMessage());
            }
        });
    }

    public void readDtcs() {
        io.execute(() -> {
            try {
                JSONArray codes = new JSONArray();
                if (demo) {
                    for (String code : DEMO_DTCS) {
                        codes.put(code);
                    }
                } else {
                    String raw = clean(runCommand("03"));
                    if (!raw.isEmpty()) {
                        int index = raw.indexOf("43");
                        if (index < 0) {
                            throw new IOException("استجابة غير مفهومة");
                        }
                        int[] bytes = parseBytes(raw.substring(index + 2));
                        List<String> decoded = PidTable.decodeDtcs(bytes);
                        for (String code : decoded) {
                            codes.put(code);
                        }
                    }
                }
                JSONObject event = new JSONObject();
                event.put("type", "dtc");
                event.put("codes", codes);
                dispatch(event);
            } catch (Exception e) {
                emitError("تعذّرت قراءة الأعطال", e.getMessage());
            }
        });
    }

    public void clearDtcs() {
        io.execute(() -> {
            try {
                if (!demo) {
                    runCommand("04");
                }
                JSONObject event = new JSONObject();
                event.put("type", "dtcCleared");
                dispatch(event);
            } catch (Exception e) {
                emitError("تعذّر مسح الأعطال", e.getMessage());
            }
        });
    }

    public void shutdown() {
        listener = null;
        io.execute(() -> {
            connected.set(false);
            demo = false;
            closeQuietly();
        });
        io.shutdown();
    }

    // ------------------------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------------------------

    private void connectBlocking(String address) {
        emitState("connecting", "");

        if (DEMO_DEVICE.equalsIgnoreCase(address)) {
            demo = true;
            deviceLabel = "وضع المحاكاة";
            connected.set(true);
            emitState("connected", "بيانات تجريبية");
            return;
        }

        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            fail("هذا الجهاز لا يدعم البلوتوث");
            return;
        }
        if (!adapter.isEnabled()) {
            fail("البلوتوث غير مفعّل");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            fail("إذن البلوتوث مرفوض");
            return;
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            deviceLabel = safeName(device);
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
        } catch (Exception e) {
            fail("تعذّر الوصول إلى الجهاز");
            return;
        }

        adapter.cancelDiscovery();
        try {
            socket.connect();
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            Log.w(TAG, "Socket connect failed", e);
            closeQuietly();
            fail("فشل الاتصال بـ " + deviceLabel);
            return;
        }

        try {
            for (String command : INIT_SEQUENCE) {
                sendBlocking(command, "ATZ".equals(command) ? 2_500L : 1_500L);
            }
        } catch (IOException e) {
            Log.w(TAG, "Adapter did not answer the init sequence", e);
            closeQuietly();
            fail("المحوّل لا يستجيب");
            return;
        }

        demo = false;
        connected.set(true);
        emitState("connected", "");
    }

    private void fail(String message) {
        connected.set(false);
        demo = false;
        closeQuietly();
        emitState("error", message);
    }

    // ------------------------------------------------------------------------------------
    // Command / response
    // ------------------------------------------------------------------------------------

    private String runCommand(String command) throws IOException {
        return sendBlocking(command, COMMAND_TIMEOUT_MS);
    }

    private String sendBlocking(String command, long timeoutMs) throws IOException {
        OutputStream out = output;
        InputStream in = input;
        if (out == null || in == null) {
            throw new IOException("القناة مغلقة");
        }
        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return readUntilPrompt(in, timeoutMs);
    }

    private String readUntilPrompt(InputStream in, long timeoutMs) throws IOException {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, available));
                if (read < 0) {
                    break;
                }
                response.append(new String(buffer, 0, read, StandardCharsets.US_ASCII));
                if (response.indexOf(">") >= 0) {
                    break;
                }
            } else {
                try {
                    Thread.sleep(15L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return response.toString();
    }

    private JSONObject readPid(String pid) throws IOException, JSONException {
        String raw = runCommand("01" + pid);
        String hex = clean(raw);
        String prefix = "41" + pid;
        int index = hex.indexOf(prefix);
        if (index < 0) {
            throw new IOException("لا استجابة للـ PID " + pid);
        }
        int[] data = parseBytes(hex.substring(index + prefix.length()));

        JSONObject event = new JSONObject();
        event.put("type", "pid");
        event.put("pid", pid);
        event.put("raw", hex);
        PidTable.Value value = PidTable.decode(pid, data);
        if (value != null) {
            event.put("label", value.label);
            event.put("value", value.number);
            event.put("unit", value.unit);
        } else {
            event.put("label", PidTable.label(pid));
            event.put("rawOnly", true);
        }
        return event;
    }

    // ------------------------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------------------------

    /** Strips echo, prompts and whitespace, leaving uppercase hex only. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String upper = raw.toUpperCase(Locale.US)
                .replace("SEARCHING...", "")
                .replace("SEARCHING", "")
                .replace("BUS INIT: ...OK", "")
                .replace("BUS INIT: OK", "")
                .replace("NO DATA", "");
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                hex.append(c);
            }
        }
        return hex.toString();
    }

    private static int[] parseBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new int[0];
        }
        int length = hex.length() / 2;
        int[] bytes = new int[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String normalizePid(String pid) {
        if (pid == null) {
            return "";
        }
        String code = pid.trim().toUpperCase(Locale.US).replace(" ", "");
        if (code.length() == 4 && code.startsWith("01")) {
            code = code.substring(2);
        }
        return code;
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    @SuppressWarnings("deprecation")
    private BluetoothAdapter adapter() {
        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    private void closeQuietly() {
        closeQuietly(input);
        input = null;
        closeQuietly(output);
        output = null;
        BluetoothSocket open = socket;
        socket = null;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // Nothing useful to do while tearing down.
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Nothing useful to do while tearing down.
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------------------------

    private static final String[] DEMO_DTCS = {"P0300", "P0171"};

    private JSONObject demoReading(String pid) throws JSONException {
        JSONObject event = new JSONObject();
        event.put("type", "pid");
        event.put("pid", pid);
        event.put("label", PidTable.label(pid));
        switch (pid) {
            case "0C":
                event.put("value", 780 + demoRandom.nextInt(320) / 4.0 * 4);
                event.put("unit", "rpm");
                break;
            case "0D":
                event.put("value", demoRandom.nextInt(80));
                event.put("unit", "km/h");
                break;
            case "05":
                event.put("value", 86 + demoRandom.nextInt(6));
                event.put("unit", "°C");
                break;
            case "04":
                event.put("value", 24 + demoRandom.nextInt(30));
                event.put("unit", "%");
                break;
            case "11":
                event.put("value", 12 + demoRandom.nextInt(14));
                event.put("unit", "%");
                break;
            case "10":
                event.put("value", 2 + demoRandom.nextInt(40) / 10.0);
                event.put("unit", "g/s");
                break;
            case "2F":
                event.put("value", 40 + demoRandom.nextInt(45));
                event.put("unit", "%");
                break;
            case "42":
                event.put("value", 13.8 + demoRandom.nextInt(8) / 10.0);
                event.put("unit", "V");
                break;
            case "0F":
                event.put("value", 28 + demoRandom.nextInt(8));
                event.put("unit", "°C");
                break;
            default:
                event.put("rawOnly", true);
                event.put("raw", "41" + pid + "00");
                break;
        }
        return event;
    }

    private String demoRaw(String command) {
        return "OK " + command;
    }

    // ------------------------------------------------------------------------------------
    // Event dispatch
    // ------------------------------------------------------------------------------------

    private void emitState(String state, String message) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "state");
            event.put("state", state);
            event.put("device", deviceLabel);
            event.put("message", message == null ? "" : message);
            event.put("connected", connected.get());
        } catch (JSONException ignored) {
            return;
        }
        dispatch(event);
    }

    private void emitError(String message, String detail) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "error");
            event.put("message", detail == null || detail.isEmpty()
                    ? message
                    : message + ": " + detail);
        } catch (JSONException ignored) {
            return;
        }
        dispatch(event);
    }

    private void dispatch(JSONObject event) {
        Listener current = listener;
        if (current != null) {
            current.onObdEvent(event);
        }
    }
}
