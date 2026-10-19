package com.waleedsayed.obd2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Named locations kept for the mock-location screen.
 *
 * <p>Stored as a JSON array in a private {@link SharedPreferences} file, capped at {@link #MAX}
 * entries. Nothing leaves the device.</p>
 */
public final class FavoriteStore {

    public static final int MAX = 20;

    private static final String PREFS = "mock_favorites";
    private static final String KEY_ITEMS = "items";

    public static final class Favorite {
        public final String name;
        public final double latitude;
        public final double longitude;

        Favorite(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private final SharedPreferences prefs;

    public FavoriteStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Favorite> all() {
        List<Favorite> result = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                result.add(new Favorite(
                        item.optString("name", "موقع"),
                        item.optDouble("lat", 0),
                        item.optDouble("lon", 0)));
            }
        } catch (JSONException ignored) {
            // Corrupt payload: behave like an empty list rather than crashing.
        }
        return result;
    }

    public int count() {
        return all().size();
    }

    public boolean isFull() {
        return count() >= MAX;
    }

    public boolean add(String name, double latitude, double longitude) {
        List<Favorite> items = all();
        if (items.size() >= MAX) {
            return false;
        }
        items.add(new Favorite(name, latitude, longitude));
        persist(items);
        return true;
    }

    public void remove(int index) {
        List<Favorite> items = all();
        if (index < 0 || index >= items.size()) {
            return;
        }
        items.remove(index);
        persist(items);
    }

    private void persist(List<Favorite> items) {
        JSONArray array = new JSONArray();
        for (Favorite favorite : items) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", favorite.name);
                item.put("lat", favorite.latitude);
                item.put("lon", favorite.longitude);
                array.put(item);
            } catch (JSONException ignored) {
                // Only primitives are written; this cannot fail in practice.
            }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
