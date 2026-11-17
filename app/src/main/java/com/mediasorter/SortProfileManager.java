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
            "smart_max_pool", "smart_use_video_frames", "smart_accept_replaces", "rerank_idle_ms",
            "compare_layout", "compare_question", "compare_randomize_sides", "compare_auto_apply",
            "compare_show_order_strip", "compare_button_height_dp", "compare_tap_to_pick",
            "batch_auto_advance", "batch_skip_to_back", "batch_include_tagged",
            "batch_show_inline_tags", "batch_apply_all_if_none", "game_commit_mode", "batch_last_scope",
            "compare_show_order_strip", "compare_button_height_dp", "compare_tap_to_pick",
            "compare_recheck_every", "compare_sync_zoom", "compare_show_caption",
            "compare_caption_fields", "compare_volume_pick", "compare_video_muted",
            "compare_warn_questions", "compare_undo_depth", "compare_prefetch",
            "compare_hires_on_zoom", "mode_timing", "game_batch_size", "sort_dupes_mode", 
            "replace_mode", "replace_keep_max", "replace_confirm_min_files", "replace_protect_piles",
            "replace_sequence_rule", "replace_sequence_patterns", "replace_protect_patterns",
            "replace_confirm_sequence_overwrite", "replace_show_group_report",
            "replace_purge_group_tags"));
    private static final Set<String> INTEGER_PROFILE_KEYS = new HashSet<String>(Arrays.asList(
            "smart_chunk_ms", "smart_pass_budget_ms", "smart_k_neighbors", "smart_random_fraction",
            "smart_max_pool", "rerank_idle_ms", "compare_recheck_every", "compare_warn_questions",
            "compare_undo_depth", "compare_button_height_dp", "game_batch_size",
            "replace_keep_max", "replace_confirm_min_files"));
    private final SharedPreferences prefs;

    public SortProfileManager(Context context) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
    }

    /** One-time sorting defaults migration; callers run this on a worker. */
    public static void migrateSpecDefaults(Context context) {
        migrateSpecDefaults(context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE));
    }

    static void migrateSpecDefaults(SharedPreferences prefs) {
        if (prefs.getInt("prefs_migration_version", 0) >= 2) {
            if (prefs.contains("batch_commit_mode")) {
                SharedPreferences.Editor legacyEditor = prefs.edit();
                if (!prefs.contains("game_commit_mode")) {
                    legacyEditor.putString("game_commit_mode",
                            prefs.getString("batch_commit_mode", "add"));
                }
                legacyEditor.remove("batch_commit_mode").commit();
            }
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("compare_recheck_every")) editor.putInt("compare_recheck_every", 25);
        if (!prefs.contains("compare_warn_questions")) editor.putInt("compare_warn_questions", 300);
        if (!prefs.contains("compare_caption_fields")) {
            editor.putString("compare_caption_fields", "name,size,dims,date");
        }
        if (!prefs.contains("compare_question")) editor.putString("compare_question", "Which goes first?");
        Object volume = prefs.getAll().get("compare_volume_pick");
        if (volume == null || (volume instanceof String && "off".equals(volume))) {
            editor.remove("compare_volume_pick").putBoolean("compare_volume_pick", true);
        }
        editor.remove("batch_size_mode").remove("compare_size_mode");
        if (!prefs.contains("batch_auto_advance")) editor.putBoolean("batch_auto_advance", true);
        if (!prefs.contains("batch_skip_to_back")) editor.putBoolean("batch_skip_to_back", true);
        if (!prefs.contains("batch_include_tagged")) editor.putBoolean("batch_include_tagged", false);
        if (!prefs.contains("batch_show_inline_tags")) editor.putBoolean("batch_show_inline_tags", true);
        if (!prefs.contains("game_batch_size")) editor.putInt("game_batch_size", 6);
        if (!prefs.contains("game_commit_mode")) {
            String legacy = prefs.getString("batch_commit_mode", "add");
            editor.putString("game_commit_mode", legacy);
        }
        // The old key is read only for this migration and is never part of a
        // profile or the live settings surface again.
        editor.remove("batch_commit_mode");
        editor.remove("game_batch_adaptive").remove("game_decoys").remove("game_probe_every")
                .remove("game_order_min_group").remove("game_prompt_text")
                .remove("game_none_cooldown").remove("game_none_max_returns")
                .remove("game_score_mode").remove("game_timer_seconds");
        if (!prefs.contains("replace_mode")) editor.putString("replace_mode", "replace_with");
        if (!prefs.contains("replace_keep_max")) editor.putInt("replace_keep_max", 2);
        if (!prefs.contains("replace_confirm_min_files")) editor.putInt("replace_confirm_min_files", 2);
        if (!prefs.contains("replace_protect_piles")) editor.putBoolean("replace_protect_piles", false);
        editor.putInt("prefs_migration_version", 2).commit();
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
                if (INTEGER_PROFILE_KEYS.contains(key)) {
                    editor.putInt(key, (int) number);
                } else if (number == Math.rint(number) && number >= Integer.MIN_VALUE
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
                    .putInt("game_batch_size", 6);
        } else if (index == 2) {
            editor.putInt("smart_chunk_ms", 30).putInt("smart_pass_budget_ms", 3000)
                    .putInt("smart_k_neighbors", 8).putInt("smart_random_fraction", 5)
                    .putInt("compare_recheck_every", 5).putInt("compare_undo_depth", 100)
                    .putBoolean("compare_prefetch", true).putBoolean("compare_hires_on_zoom", true);
        } else if (index == 3) {
            editor.putInt("smart_chunk_ms", 8).putInt("smart_pass_budget_ms", 700)
                    .putInt("smart_k_neighbors", 2).putInt("smart_max_pool", 1500)
                    .putBoolean("smart_use_video_frames", false)
                    .putBoolean("compare_prefetch", false).putBoolean("compare_hires_on_zoom", false)
                    .putInt("game_batch_size", 4);
        } else {
            editor.putInt("smart_chunk_ms", 20).putInt("smart_pass_budget_ms", 1500)
                    .putInt("smart_k_neighbors", 5).putInt("smart_random_fraction", 15)
                    .putInt("compare_recheck_every", 0).putInt("compare_undo_depth", 50)
                    .putBoolean("compare_prefetch", true).putBoolean("compare_hires_on_zoom", true)
                    .putInt("game_batch_size", 6);
        }
        editor.commit();
    }

    public void resetInteractiveDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("batch_commit_mode").remove("game_batch_adaptive")
                .remove("game_decoys").remove("game_probe_every")
                .remove("game_none_cooldown").remove("game_none_max_returns")
                .remove("game_order_min_group").remove("game_score_mode")
                .remove("game_timer_seconds").remove("game_prompt_text");
        editor.putString(KEY, "[]")
                .putInt(INDEX, -1)
                .putBoolean("smart_accept_replaces", false)
                .putString("compare_layout", "auto")
                .putInt("compare_button_height_dp", 64)
                .putBoolean("compare_tap_to_pick", true)
                .putBoolean("mode_timing", false)
                .putBoolean("compare_show_order_strip", false)
                .putBoolean("compare_auto_apply", true)
                .putBoolean("batch_auto_advance", true)
                .putBoolean("batch_skip_to_back", true)
                .putBoolean("batch_include_tagged", false)
                .putBoolean("batch_show_inline_tags", true)
                .putBoolean("batch_apply_all_if_none", false)
                .putString("batch_last_scope", "UNTAGGED")
                .putBoolean("compare_show_order_strip", false)
                .putBoolean("compare_tap_to_pick", true)
                .putBoolean("compare_randomize_sides", true)
                .putInt("compare_recheck_every", 25)
                .putInt("compare_warn_questions", 300)
                .putBoolean("compare_sync_zoom", true)
                .putBoolean("compare_show_caption", false)
                .putString("compare_question", "Which goes first?")
                .putString("compare_caption_fields", "name,size,dims,date")
                .putBoolean("compare_volume_pick", true)
                .putBoolean("compare_video_muted", true)
                .putBoolean("compare_prefetch", true)
                .putBoolean("compare_hires_on_zoom", true)
                .putInt("compare_undo_depth", 50)
                .putInt("game_batch_size", 6)
                .putString("game_commit_mode", "add")
                .putString("sort_dupes_mode", "ignore")
                .putString("replace_mode", "replace_with")
                .putInt("replace_keep_max", 2)
                .putInt("replace_confirm_min_files", 2)
                .putBoolean("replace_protect_piles", false)
                .putString("replace_sequence_rule", "whole_group")
                .putString("replace_sequence_patterns", "link_*,*_seq_*")
                .putString("replace_protect_patterns", "")
                .putBoolean("replace_confirm_sequence_overwrite", true)
                .putBoolean("replace_show_group_report", true)
                .putBoolean("replace_purge_group_tags", false)
                .commit();
    }

    /** Captures current allow-listed sorting settings as a custom profile. */
    public JSONObject captureCurrentProfile(String name) {
        JSONObject profile = new JSONObject();
        try {
            profile.put("name", name == null || name.trim().isEmpty() ? "Custom" : name.trim());
            JSONObject settings = new JSONObject();
            for (String key : PROFILE_KEYS) {
                Object value = prefs.getAll().get(key);
                if (value instanceof java.util.Set) {
                    org.json.JSONArray array = new org.json.JSONArray();
                    for (Object item : (java.util.Set<?>) value) array.put(String.valueOf(item));
                    settings.put(key, array);
                } else if (value != null) {
                    settings.put(key, value);
                }
            }
            profile.put("settings", settings);
        } catch (Exception ignored) {}
        return profile;
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
