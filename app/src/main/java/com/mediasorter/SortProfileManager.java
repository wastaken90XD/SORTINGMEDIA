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

    public void applyBuiltIn(int index) {
        index = Math.max(0, Math.min(BUILT_INS.length - 1, index));
        SharedPreferences.Editor editor = prefs.edit();
        if (index == 0) {
            editor.putInt("smart_chunk_ms", 10).putInt("smart_pass_budget_ms", 800)
                    .putInt("smart_k_neighbors", 3).putInt("smart_random_fraction", 20);
        } else if (index == 2) {
            editor.putInt("smart_chunk_ms", 30).putInt("smart_pass_budget_ms", 3000)
                    .putInt("smart_k_neighbors", 8).putInt("smart_random_fraction", 5)
                    .putInt("compare_recheck_every", 5).putInt("game_probe_every", 3);
        } else if (index == 3) {
            editor.putInt("smart_chunk_ms", 8).putInt("smart_pass_budget_ms", 700)
                    .putInt("smart_k_neighbors", 2).putInt("smart_max_pool", 1500)
                    .putBoolean("smart_use_video_frames", false)
                    .putInt("game_batch_size", 6);
        } else {
            editor.putInt("smart_chunk_ms", 20).putInt("smart_pass_budget_ms", 1500)
                    .putInt("smart_k_neighbors", 5).putInt("smart_random_fraction", 15)
                    .putInt("compare_recheck_every", 0).putInt("game_probe_every", 5);
        }
        editor.commit();
    }

    public void resetInteractiveDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("compare_layout", "auto")
                .putBoolean("compare_randomize_sides", true)
                .putInt("compare_recheck_every", 0)
                .putBoolean("compare_sync_zoom", true)
                .putBoolean("compare_show_caption", true)
                .putBoolean("compare_volume_pick", true)
                .putInt("compare_warn_questions", 100)
                .putInt("compare_undo_depth", 50)
                .putInt("game_batch_size", 6)
                .putBoolean("game_batch_adaptive", true)
                .putInt("game_decoys", 1)
                .putInt("game_probe_every", 5)
                .putInt("game_none_cooldown", 3)
                .putInt("game_none_max_returns", 2)
                .putString("game_score_mode", "progress")
                .putInt("game_timer_seconds", 0)
                .commit();
    }

    public static String[] builtInNames() { return BUILT_INS.clone(); }
}
