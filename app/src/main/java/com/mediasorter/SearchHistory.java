package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists recent searches and saved (bookmarked) searches.
 */
public class SearchHistory {

    private static final String PREFS       = "search_history_prefs";
    private static final String KEY_RECENT  = "recent_searches";
    private static final String KEY_SAVED   = "saved_searches";
    private static final int    MAX_RECENT  = 20;

    private final SharedPreferences prefs;

    public SearchHistory(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Recent searches ─────────────────────────────────────────────────

    public List<String> getRecentSearches() {
        return deserialize(prefs.getString(KEY_RECENT, ""));
    }

    public void addRecentSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        List<String> recent = getRecentSearches();
        recent.remove(query); // remove duplicate
        recent.add(0, query); // add to front
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        prefs.edit().putString(KEY_RECENT, serialize(recent)).apply();
    }

    public void clearRecentSearches() {
        prefs.edit().putString(KEY_RECENT, "").apply();
    }

    // ── Saved (bookmarked) searches ─────────────────────────────────────

    public List<String> getSavedSearches() {
        return deserialize(prefs.getString(KEY_SAVED, ""));
    }

    public void saveSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        List<String> saved = getSavedSearches();
        if (saved.contains(query)) return;
        saved.add(query);
        prefs.edit().putString(KEY_SAVED, serialize(saved)).apply();
    }

    public void removeSavedSearch(String query) {
        List<String> saved = getSavedSearches();
        if (saved.remove(query)) {
            prefs.edit().putString(KEY_SAVED, serialize(saved)).apply();
        }
    }

    public boolean isSaved(String query) {
        return getSavedSearches().contains(query);
    }

    // ── Serialization ───────────────────────────────────────────────────

    private static String serialize(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(s);
        }
        return sb.toString();
    }

    private static List<String> deserialize(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String s : raw.split("\n")) {
            if (!s.trim().isEmpty()) result.add(s.trim());
        }
        return result;
    }
}
