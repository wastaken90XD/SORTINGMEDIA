package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Search history storage. Recent queries are a JSON array under the stable
 * search_history key so the dropdown can be shared by all search surfaces.
 * Saved searches are retained separately for compatibility with older builds.
 */
public class SearchHistory {

    private static final String PREFS = "search_history_prefs";
    public static final String KEY_HISTORY = "search_history";
    private static final String KEY_LEGACY_RECENT = "recent_searches";
    private static final String KEY_SAVED = "saved_searches";
    private static final int MAX_RECENT = 20;

    private final SharedPreferences prefs;

    public SearchHistory(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyHistory();
    }

    private void migrateLegacyHistory() {
        if (!prefs.contains(KEY_LEGACY_RECENT)) return;
        if (!prefs.contains(KEY_HISTORY)) {
            JSONArray array = new JSONArray();
            for (String value : parseLegacy(prefs.getString(KEY_LEGACY_RECENT, ""))) {
                if (array.length() >= MAX_RECENT) break;
                array.put(value);
            }
            prefs.edit().putString(KEY_HISTORY, array.toString())
                    .remove(KEY_LEGACY_RECENT).commit();
        } else {
            prefs.edit().remove(KEY_LEGACY_RECENT).commit();
        }
    }

    public synchronized List<String> getRecentSearches() {
        String raw = prefs.getString(KEY_HISTORY, "");
        List<String> result = parseJsonArray(raw);
        if (result.isEmpty() && !raw.trim().startsWith("[")) {
            // Migrate the pre-JSON newline format once it is encountered.
            String legacy = raw.trim().isEmpty()
                    ? prefs.getString(KEY_LEGACY_RECENT, "") : raw;
            result = parseLegacy(legacy);
        }
        return result;
    }

    public synchronized void addRecentSearch(String query) {
        if (query == null) return;
        String clean = query.trim();
        if (clean.isEmpty()) return;
        List<String> recent = getRecentSearches();
        while (recent.remove(clean)) {
            // Remove every duplicate before inserting the newest query.
        }
        recent.add(0, clean);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        saveJson(KEY_HISTORY, recent);
    }

    public synchronized void clearRecentSearches() {
        saveJson(KEY_HISTORY, new ArrayList<String>());
        prefs.edit().remove(KEY_LEGACY_RECENT).apply();
    }

    public synchronized List<String> getSavedSearches() {
        return parseLegacy(prefs.getString(KEY_SAVED, ""));
    }

    public synchronized void saveSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        String clean = query.trim();
        List<String> saved = getSavedSearches();
        if (saved.contains(clean)) return;
        saved.add(clean);
        prefs.edit().putString(KEY_SAVED, serializeLines(saved)).apply();
    }

    public synchronized void removeSavedSearch(String query) {
        List<String> saved = getSavedSearches();
        if (saved.remove(query)) {
            prefs.edit().putString(KEY_SAVED, serializeLines(saved)).apply();
        }
    }

    public synchronized boolean isSaved(String query) {
        return getSavedSearches().contains(query);
    }

    private void saveJson(String key, List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) array.put(value.trim());
            }
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    private static List<String> parseJsonArray(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty() && !result.contains(value)) result.add(value);
            }
        } catch (Exception ignored) {
            // A legacy newline string is handled by parseLegacy below.
        }
        while (result.size() > MAX_RECENT) result.remove(result.size() - 1);
        return result;
    }

    private static List<String> parseLegacy(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String value : raw.split("\\n")) {
            String clean = value.trim();
            if (!clean.isEmpty() && !result.contains(clean)) result.add(clean);
        }
        return result;
    }

    private static String serializeLines(List<String> values) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                if (builder.length() > 0) builder.append('\n');
                builder.append(value.trim());
            }
        }
        return builder.toString();
    }
}
