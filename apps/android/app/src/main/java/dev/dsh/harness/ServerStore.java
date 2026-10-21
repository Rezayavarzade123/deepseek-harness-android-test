package dev.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remembers recent dsh servers and the one to reconnect to on launch. */
public final class ServerStore {

    private static final String PREFS = "dsh_mobile_servers";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_LAST = "last";
    private static final int MAX_ENTRIES = 8;

    private final SharedPreferences preferences;

    public ServerStore(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Add a server to the recent list and mark it as the reconnect target. */
    public void remember(ServerTarget target) {
        if (target == null) {
            return;
        }
        JSONArray next = new JSONArray();
        next.put(row(target.baseUrl(), target.token));
        JSONArray previous = readArray();
        for (int index = 0; index < previous.length() && next.length() < MAX_ENTRIES; index++) {
            JSONObject entry = previous.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String url = entry.optString("url", "");
            if (url.isEmpty() || url.equals(target.baseUrl())) {
                continue;
            }
            next.put(entry);
        }
        preferences.edit()
                .putString(KEY_SERVERS, next.toString())
                .putString(KEY_LAST, target.baseUrl())
                .apply();
    }

    /** @return recent servers, most recently used first. */
    public List<ServerTarget> recent() {
        JSONArray rows = readArray();
        List<ServerTarget> targets = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject entry = rows.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            ServerTarget target = ServerTarget.stored(
                    entry.optString("url", ""), entry.optString("token", ""));
            if (target != null) {
                targets.add(target);
            }
        }
        return Collections.unmodifiableList(targets);
    }

    /** @return the server to reconnect to, or {@code null} when none was used yet. */
    public ServerTarget last() {
        String url = preferences.getString(KEY_LAST, null);
        if (url == null || url.isEmpty()) {
            return null;
        }
        for (ServerTarget target : recent()) {
            if (url.equals(target.baseUrl())) {
                return target;
            }
        }
        return null;
    }

    /** Drop one base URL from the recent list, including the reconnect target. */
    public void forget(String baseUrl) {
        JSONArray rows = readArray();
        JSONArray next = new JSONArray();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject entry = rows.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            if (!entry.optString("url", "").equals(baseUrl)) {
                next.put(entry);
            }
        }
        SharedPreferences.Editor editor = preferences.edit().putString(KEY_SERVERS, next.toString());
        String last = preferences.getString(KEY_LAST, null);
        if (baseUrl.equals(last)) {
            editor.remove(KEY_LAST);
        }
        editor.apply();
    }

    /** Forget the reconnect target without touching the recent list. */
    public void clearLast() {
        preferences.edit().remove(KEY_LAST).apply();
    }

    private JSONObject row(String url, String token) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("url", url);
            entry.put("token", token == null ? "" : token);
        } catch (JSONException ignored) {
            // JSONObject.put only throws for non-finite numbers and null keys.
        }
        return entry;
    }

    private JSONArray readArray() {
        String raw = preferences.getString(KEY_SERVERS, "[]");
        try {
            JSONArray parsed = new JSONArray(raw == null ? "[]" : raw);
            return parsed;
        } catch (JSONException malformed) {
            return new JSONArray();
        }
    }
}
