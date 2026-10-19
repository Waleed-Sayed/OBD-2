package com.waleedsayed.obd2;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Thin client for Nominatim, the OpenStreetMap geocoder.
 *
 * <p>Called from a background thread only. Nominatim does not send CORS headers, so the WebView
 * cannot query it directly; both map screens go through this class instead. Requests are spaced at
 * least 1.1s apart to respect the service's usage policy.</p>
 */
final class NominatimClient {

    private static final String TAG = "Nominatim";
    private static final long MIN_GAP_MS = 1_100L;

    private static long lastRequestAt;

    private NominatimClient() {
    }

    /** Returns {@code {"results":[...]}} or {@code {"error":"..."}}. */
    static String search(String query) {
        HttpURLConnection connection = null;
        try {
            synchronized (NominatimClient.class) {
                long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastRequestAt);
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                lastRequestAt = System.currentTimeMillis();
            }

            String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6"
                    + "&accept-language=ar&q=" + URLEncoder.encode(query, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", "OBD2Map/1.0 (Android)");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return error("http " + code);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            JSONArray array = new JSONArray(body.toString());
            JSONArray results = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONObject result = new JSONObject();
                result.put("name", item.optString("display_name"));
                result.put("lat", item.optDouble("lat"));
                result.put("lon", item.optDouble("lon"));
                results.put(result);
            }

            JSONObject payload = new JSONObject();
            payload.put("results", results);
            return payload.toString();
        } catch (Exception e) {
            Log.w(TAG, "search failed", e);
            return error(e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String error(String detail) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("error", detail == null ? "unknown" : detail);
        } catch (JSONException ignored) {
            // Primitive only.
        }
        return payload.toString();
    }
}
