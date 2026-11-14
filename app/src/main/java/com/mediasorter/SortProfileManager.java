package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Small profile layer for the three sorting sections. */
public final class SortProfileManager {
    private static final String KEY = "sort_profiles";
    private static final String INDEX = "sort_profile_index";
    private static final String[] BUILT_INS = {"Fast", "Balanced", "Careful", "Low memory"};
    private static final Set<String> PROFILE_KEYS = new HashSet<String>(Arrays.asList(
            "smart_chunk_ms", "smart_pass_budget_ms", "smart_k_neighbors", "smart_random_fraction",
            "smart_max_pool", "smart_use_video_frames", "rerank_idle_ms",
            "compare_layout", "compare_question", "compare_randomize_sides",
            "compare_recheck_every", "compare_sync_zoom", "compare_show_caption",
            "compare_caption_fields", "compare_volume_pick", "compare_video_muted",
            "compare_warn_questions", "compare_undo_depth", "compare_prefetch",
            "compare_hires_on_zoom", "game_batch_size", "game_batch_adaptive",
            "game_decoys", "game_probe_every", "game_none_cooldown",
            "game_none_max_returns", "game_order_min_group", "game_score_mode",
            "game_timer_seconds", "game_prompt_text", "sort_dupes_mode",
            "replace_sequence_rule", "replace_sequence_patterns", "replace_protect_patterns",
            "replace_confirm_sequence_overwrite", "replace_show_group_report",
            "replace_purge_group_tags"));
    private final SharedPreferences prefs;

    public SortProfileManager(Context context) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
    }

    public String nextBuiltIn() {
        int index = (prefs.getInt(INDEX, -1) + 1) % BUILT_INS.length;
        applyBuiltIn(index);
        prefs.edit().putInt(INDEX, index).apply();
        return BUILT_INS[index];
    }

    /** Cycles through built-ins and validated custom JSON profiles. */
    public String nextProfile() {
        JSONArray custom = getCustomProfiles();
        int total = BUILT_INS.length + custom.length();
        if (total <= 0) return "Balanced";
        int index = (prefs.getInt(INDEX, -1) + 1) % total;
        String name;
        if (index < BUILT_INS.length) {
            applyBuiltIn(index);
            name = BUILT_INS[index];
        } else {
            JSONObject profile = custom.optJSONObject(index - BUILT_INS.length);
            name = applyCustom(profile, index - BUILT_INS.length);
        }
        prefs.edit().putInt(INDEX, index).apply();
        return name;
    }

    private String applyCustom(JSONObject profile, int index) {
        if (profile == null) return "Custom " + (index + 1);
        JSONObject values = profile.optJSONObject("settings");
        if (values == null) values = profile;
        SharedPreferences.Editor editor = prefs.edit();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!PROFILE_KEYS.contains(key)) continue;
            Object value = values.opt(key);
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (number == Math.rint(number) && number >= Integer.MIN_VALUE
                        && number <= Integer.MAX_VALUE) {
                    editor.putInt(key, (int) number);
                } else {
                    editor.putFloat(key, (float) number);
                }
            } else if (value != null && value != JSONObject.NULL) {
                editor.putString(key, String.valueOf(value));
            }
        }
        editor.apply();
        String name = profile.optString("name", "").trim();
        return name.isEmpty() ? "Custom " + (index + 1) : name;
    }

    public void applyBuiltIn(int index) {
        index = Math.max(0, Math.min(BUILT_INS.length - 1, index));
        SharedPreferences.Editor editor = prefs.edit();
        if (index == 0) {
            editor.putInt("smart_chunk_ms", 10).putInt("smart_pass_budget_ms", 800)
                    .putInt("smart_k_neighbors", 3).putInt("smart_random_fraction", 20)
                    .putBoolean("compare_prefetch", false).putBoolean("compare_hires_on_zoom", false)
                    .putInt("game_batch_size", 6).putBoolean("game_batch_adaptive", false)
                    .putInt("game_probe_every", 0);
        } else if (index == 2) {
            editor.putInt("smart_chunk_ms", 30).putInt("smart_pass_budget_ms", 3000)
                    .putInt("smart_k_neighbors", 8).putInt("smart_random_fraction", 5)
                    .putInt("compare_recheck_every", 5).putInt("compare_undo_depth", 100)
                    .putBoolean("compare_prefetch", true).putBoolean("compare_hires_on_zoom", true)
                    .putBoolean("game_batch_adaptive", true).putInt("game_probe_every", 3);
        } else if (index == 3) {
            editor.putInt("smart_chunk_ms", 8).putInt("smart_pass_budget_ms", 700)
                    .putInt("smart_k_neighbors", 2).putInt("smart_max_pool", 1500)
                    .putBoolean("smart_use_video_frames", false)
                    .putBoolean("compare_prefetch", false).putBoolean("compare_hires_on_zoom", false)
                    .putInt("game_batch_size", 4).putBoolean("game_batch_adaptive", false)
                    .putInt("game_probe_every", 0);
        } else {
            editor.putInt("smart_chunk_ms", 20).putInt("smart_pass_budget_ms", 1500)
                    .putInt("smart_k_neighbors", 5).putInt("smart_random_fraction", 15)
                    .putInt("compare_recheck_every", 0).putInt("compare_undo_depth", 50)
                    .putBoolean("compare_prefetch", true).putBoolean("compare_hires_on_zoom", true)
                    .putInt("game_batch_size", 6).putBoolean("game_batch_adaptive", false)
                    .putInt("game_probe_every", 5);
        }
        editor.commit();
    }

    public void resetInteractiveDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY, "[]")
                .putInt(INDEX, -1)
                .putString("compare_layout", "auto")
                .putBoolean("compare_randomize_sides", true)
                .putInt("compare_recheck_every", 0)
                .putBoolean("compare_sync_zoom", true)
                .putBoolean("compare_show_caption", true)
                .putString("compare_question", "which goes first?")
                .putString("compare_caption_fields", "name,size")
                .putString("compare_volume_pick", "off")
                .putBoolean("compare_video_muted", true)
                .putBoolean("compare_prefetch", true)
                .putBoolean("compare_hires_on_zoom", true)
                .putInt("compare_warn_questions", 100)
                .putInt("compare_undo_depth", 50)
                .putInt("game_batch_size", 6)
                .putBoolean("game_batch_adaptive", false)
                .putInt("game_order_min_group", 4)
                .putString("game_prompt_text", "Group these files and choose a tag")
                .putInt("game_decoys", 1)
                .putInt("game_probe_every", 5)
                .putInt("game_none_cooldown", 3)
                .putInt("game_none_max_returns", 2)
                .putString("game_score_mode", "progress")
                .putInt("game_timer_seconds", 0)
                .putString("sort_dupes_mode", "ignore")
                .putString("replace_sequence_rule", "whole_group")
                .putString("replace_sequence_patterns", "link_*,*_seq_*")
                .putString("replace_protect_patterns", "")
                .putBoolean("replace_confirm_sequence_overwrite", true)
                .putBoolean("replace_show_group_report", true)
                .putBoolean("replace_purge_group_tags", false)
                .commit();
    }

    /** Returns the validated custom profile document, or an empty array. */
    public JSONArray getCustomProfiles() {
        JSONArray result = new JSONArray();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray saved = new JSONArray(raw);
            for (int i = 0; i < saved.length() && i < 10; i++) {
                JSONObject profile = saved.optJSONObject(i);
                if (profile != null) result.put(profile);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Saves at most ten object profiles; invalid JSON is rejected unchanged. */
    public boolean saveCustomProfiles(String raw) {
        try {
            JSONArray saved = new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
            JSONArray valid = new JSONArray();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject profile = saved.optJSONObject(i);
                if (profile == null) return false;
                if (valid.length() >= 10) return false;
                valid.put(profile);
            }
            prefs.edit().putString(KEY, valid.toString()).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String[] builtInNames() { return BUILT_INS.clone(); }
}
