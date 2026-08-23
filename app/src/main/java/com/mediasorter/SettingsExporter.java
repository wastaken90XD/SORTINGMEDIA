package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.mediasorter.features.RandomGenerator;
import com.mediasorter.organizer.Rule;
import com.mediasorter.organizer.RuleSerializer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backup and restore for app settings. The exporter deliberately has an
 * allow-list for preference files and keys: old/new app versions can add data
 * without making an import fail or writing arbitrary preference keys.
 */
public final class SettingsExporter {

    private static final String TAG = "SettingsExporter";

    /** Preference files that are part of a portable settings backup. */
    private static final String[] PREFS_KEYS = {
            "gesture_prefs", "tag_list_prefs", "organizer_prefs", "folder_prefs",
            "cache_prefs", "thumb_prefs", "file_status_prefs", "window_prefs",
            "settings_prefs", "tag_preset_prefs", "tag_recent_prefs"
    };

    private static final Set<String> SETTINGS_KEYS = new HashSet<String>(Arrays.asList(
            "auto_advance_flag", "auto_advance_tag", "auto_skip_dupes",
            "confirm_delete", "confirm_trash", "dpad_enabled", "volume_keys_enabled",
            "long_press_duration", "swipe_min_distance", "swipe_min_velocity",
            "show_hidden",
            "show_seq_labels", "show_tag_count", "skip_videos", "skip_images",
            "strip_on_move", "video_autoplay", "video_loop", "precache_enabled",
            "precache_radius", "max_undo_history", "metadata_backup",
            "metadata_write_immediate", "default_move_path",
            "default_zoom", "hash_algorithm", "trash_path", "info_overlay_default",
            "gallery_columns", "gallery_show_filename", "gallery_thumb_quality",
            "gallery_animate_load", "gallery_cell_spacing", "gallery_show_tag_count",
            "gallery_show_flag", "gallery_show_seq", "gallery_mode_active",
            "gallery_sort", "gallery_low_memory_notice", "sort_sequence",
            "sort_tag_rules", "show_stats_bar", "show_tag_bar", "show_search_bar",
            "show_preview", "explorer_width_percent", "random_tag_format",
            "random_tag_custom_pattern", "toolbar_slots", "manual_groups"
    ));

    private static final Set<String> GESTURE_KEYS = new HashSet<String>(Arrays.asList(
            GestureConstants.INPUT_SWIPE_LEFT, GestureConstants.INPUT_SWIPE_RIGHT,
            GestureConstants.INPUT_SWIPE_UP, GestureConstants.INPUT_SWIPE_DOWN,
            GestureConstants.INPUT_DPAD_UP, GestureConstants.INPUT_DPAD_DOWN,
            GestureConstants.INPUT_DPAD_LEFT, GestureConstants.INPUT_DPAD_RIGHT,
            GestureConstants.INPUT_DPAD_CENTER, GestureConstants.INPUT_VOLUME_UP,
            GestureConstants.INPUT_VOLUME_DOWN, GestureConstants.INPUT_VOLUME_UP_LONG,
            GestureConstants.INPUT_VOLUME_DOWN_LONG, GestureConstants.INPUT_TAP_SINGLE,
            GestureConstants.INPUT_TAP_DOUBLE, GestureConstants.INPUT_TAP_LONG,
            GestureConstants.INPUT_HARDWARE_BACK, GestureConstants.INPUT_HARDWARE_MENU,
            "tags_prompt_enabled", "gesture_macros",
            GestureSettings.KEY_LAST_MACRO
    ));

    private static final Set<String> FILE_STATUS_KEYS = new HashSet<String>(Arrays.asList(
            "skipped", "flagged", "done"));

    public static class ApplyResult {
        public int preferencesCount;
        public int foldersCount;
        public int rulesCount;
        public int gesturesCount;
        public int macrosCount;
        public int rulesSkipped;
        public int failedKeys;
        public boolean isSuccess;
        public String errorMessage;
    }

    private SettingsExporter() {}

    public static String exportSettings(Context context) {
        String prefix = RandomGenerator.randomGroupPrefix(new HashSet<String>());
        String date = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(java.util.Calendar.getInstance().getTime());
        return exportSettings(context, getBackupDir(context).getAbsolutePath(),
                "export_" + prefix + "_" + date + ".json");
    }

    public static String exportSettings(Context context, String directoryPath, String filename) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", BuildConfig.VERSION_CODE);
            root.put("timestamp", System.currentTimeMillis());

            JSONObject preferences = new JSONObject();
            for (String prefsName : PREFS_KEYS) {
                JSONObject values = new JSONObject();
                SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    if (!isKnownKey(prefsName, entry.getKey())) continue;
                    putJsonValue(values, entry.getKey(), entry.getValue());
                }
                preferences.put(prefsName, values);
            }
            root.put("preferences", preferences);

            JSONArray folders = new JSONArray();
            for (String folder : new FolderManager(context).getFolders()) folders.put(folder);
            root.put("folders", folders);

            SharedPreferences gesturePrefs = context.getSharedPreferences(
                    "gesture_prefs", Context.MODE_PRIVATE);
            JSONObject gestureObject = new JSONObject();
            for (String key : GESTURE_KEYS) {
                if (gesturePrefs.contains(key) && isKnownKey("gesture_prefs", key)) {
                    putJsonValue(gestureObject, key, gesturePrefs.getAll().get(key));
                }
            }
            root.put("gestures", gestureObject);

            String macros = gesturePrefs.getString("gesture_macros", "[]");
            root.put("macros", new JSONArray(macros));

            SharedPreferences presets = context.getSharedPreferences(
                    "tag_preset_prefs", Context.MODE_PRIVATE);
            JSONArray presetArray = new JSONArray();
            for (int i = 0; i < 5; i++) presetArray.put(presets.getString("preset_" + i, ""));
            root.put("tag_presets", presetArray);

            SharedPreferences organizer = context.getSharedPreferences(
                    "organizer_prefs", Context.MODE_PRIVATE);
            root.put("rules", new JSONArray(organizer.getString("rules", "[]")));

            File directory = new File(directoryPath);
            if (!directory.exists() && !directory.mkdirs()) return null;
            File destination = new File(directory, filename);
            File temporary = new File(directory, filename + ".tmp");
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(temporary);
                output.write(root.toString(2).getBytes("UTF-8"));
                output.flush();
            } finally {
                if (output != null) try { output.close(); } catch (Exception ignored) {}
            }
            // Never touch an existing backup until the complete replacement is
            // known to be present and non-empty.
            if (!temporary.exists() || temporary.length() == 0) {
                temporary.delete();
                Log.e(TAG, "Export failed — storage error: temp file is empty");
                return null;
            }

            boolean renamed = !destination.exists() && temporary.renameTo(destination);
            if (renamed) {
                if (destination.exists() && destination.length() > 0) {
                    Log.d(TAG, "Exported successfully to " + destination.getAbsolutePath());
                    return destination.getAbsolutePath();
                }
                destination.delete();
                Log.e(TAG, "Export failed — storage error: final file is empty");
                return null;
            }

            // renameTo can fail silently on API 21 (and across storage
            // providers). Copy to a second temporary file first, then swap it
            // into place while preserving the old backup on every failure.
            File replacement = new File(directory, filename + ".copy.tmp");
            File oldBackup = new File(directory, filename + ".old.tmp");
            try {
                if (oldBackup.exists()) oldBackup.delete();
                copyFile(temporary, replacement);
                if (!replacement.exists() || replacement.length() == 0) {
                    replacement.delete();
                    temporary.delete();
                    Log.e(TAG, "Export failed — storage error: copy is empty");
                    return null;
                }
                if (destination.exists() && !destination.renameTo(oldBackup)) {
                    replacement.delete();
                    temporary.delete();
                    Log.e(TAG, "Export failed — storage error: target could not be staged");
                    return null;
                }
                if (!replacement.renameTo(destination)) {
                    restoreBackup(oldBackup, destination);
                    replacement.delete();
                    temporary.delete();
                    Log.e(TAG, "Export failed — storage error: replacement could not be moved");
                    return null;
                }
                if (!destination.exists() || destination.length() == 0) {
                    destination.delete();
                    restoreBackup(oldBackup, destination);
                    temporary.delete();
                    Log.e(TAG, "Export failed — storage error: final file is empty");
                    return null;
                }
                oldBackup.delete();
                temporary.delete();
                Log.d(TAG, "Exported successfully to " + destination.getAbsolutePath());
                return destination.getAbsolutePath();
            } catch (Exception copyError) {
                replacement.delete();
                temporary.delete();
                if (oldBackup.exists() && !destination.exists()) restoreBackup(oldBackup, destination);
                Log.e(TAG, "Export failed — storage error", copyError);
                return null;
            }
        } catch (Exception error) {
            Log.e(TAG, "Export failed", error);
            return null;
        }
    }

    private static void copyFile(File source, File destination) throws Exception {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(destination);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
            if (output != null) try { output.close(); } catch (Exception ignored) {}
        }
    }

    private static void restoreBackup(File backup, File destination) {
        if (backup == null || !backup.exists() || destination.exists()) return;
        if (backup.renameTo(destination)) return;
        try {
            copyFile(backup, destination);
            if (destination.exists() && destination.length() > 0) backup.delete();
        } catch (Exception error) {
            Log.e(TAG, "Could not restore previous backup", error);
        }
    }

    private static void putJsonValue(JSONObject object, String key, Object value) throws Exception {
        if (value instanceof Set) {
            JSONArray array = new JSONArray();
            for (Object item : (Set<?>) value) array.put(String.valueOf(item));
            object.put(key, array);
        } else if (value instanceof String || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof Boolean) {
            object.put(key, value);
        }
    }

    /** The complete key allow-list used by both export and import. */
    public static boolean isKnownKey(String prefsName, String key) {
        if (prefsName == null || key == null) return false;
        if ("settings_prefs".equals(prefsName)) {
            return SETTINGS_KEYS.contains(key) || key.startsWith("manual_order:");
        }
        if ("gesture_prefs".equals(prefsName)) return GESTURE_KEYS.contains(key);
        if ("file_status_prefs".equals(prefsName)) return FILE_STATUS_KEYS.contains(key);
        if ("folder_prefs".equals(prefsName)) return "watched_folders".equals(key);
        if ("cache_prefs".equals(prefsName)) return "cache_limit_mb".equals(key);
        if ("thumb_prefs".equals(prefsName)) {
            return "thumb_quality".equals(key) || "thumb_max_bytes".equals(key);
        }
        if ("window_prefs".equals(prefsName)) return "window_size".equals(key);
        if ("organizer_prefs".equals(prefsName)) return "rules".equals(key);
        if ("tag_preset_prefs".equals(prefsName)) return key.matches("preset_[0-9]+");
        if ("tag_recent_prefs".equals(prefsName)) {
            return "recent_tags".equals(key) || "tags_enabled".equals(key);
        }
        if ("tag_list_prefs".equals(prefsName)) {
            return "list_count".equals(key) || "active_list".equals(key)
                    || key.matches("tag_lists_(name|default|tags)_[0-9]+");
        }
        return false;
    }

    private static boolean isKnownPrefsFile(String name) {
        for (String value : PREFS_KEYS) if (value.equals(name)) return true;
        return false;
    }

    public static void resetToDefaults(Context context) {
        for (String prefsName : PREFS_KEYS) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    /**
     * Apply in dependency order. A malformed/unknown individual key is logged
     * and skipped; only an uncaught transaction-level failure resets defaults.
     */
    public static ApplyResult applyImport(Context context, JSONObject root) {
        ApplyResult result = new ApplyResult();
        try {
            if (root == null || !root.has("preferences") || !root.has("folders")
                    || !root.has("rules") || !root.has("gestures")
                    || !root.has("macros") || !root.has("tag_presets")) {
                throw new IllegalArgumentException("Backup is missing required sections");
            }
            JSONObject preferences = root.getJSONObject("preferences");

            // 1. Portable preferences first, excluding the structured stores
            // below so folders/rules/gestures/macros/presets have one owner.
            Iterator<String> preferenceNames = preferences.keys();
            while (preferenceNames.hasNext()) {
                String prefsName = preferenceNames.next();
                if (!isKnownPrefsFile(prefsName) || isStructuredPrefs(prefsName)) continue;
                JSONObject values = preferences.optJSONObject(prefsName);
                if (values == null) continue;
                applyPreferenceObject(context, prefsName, values, result);
            }

            // 2. Folders
            JSONArray folders = root.getJSONArray("folders");
            try {
                Set<String> folderSet = new HashSet<String>();
                for (int i = 0; i < folders.length(); i++) {
                    String folder = folders.optString(i, "").trim();
                    if (!folder.isEmpty()) folderSet.add(folder);
                }
                SharedPreferences folderPrefs = context.getSharedPreferences(
                        "folder_prefs", Context.MODE_PRIVATE);
                boolean committed = folderPrefs.edit().clear()
                        .putStringSet("watched_folders", folderSet).commit();
                if (!committed || !folderPrefs.contains("watched_folders")) {
                    recordFailure(result, "folder_prefs.watched_folders");
                } else {
                    result.foldersCount = folderSet.size();
                }
            } catch (Exception error) {
                recordFailure(result, "folder_prefs.watched_folders", error);
            }

            // 3. Rules
            try {
                JSONArray inputRules = root.getJSONArray("rules");
                JSONArray validRules = new JSONArray();
                for (int i = 0; i < inputRules.length(); i++) {
                    try {
                        JSONObject ruleObject = inputRules.getJSONObject(i);
                        List<Rule> parsed = RuleSerializer.loadRulesFromJsonStr(
                                new JSONArray().put(ruleObject).toString());
                        if (parsed != null && !parsed.isEmpty()) {
                            validRules.put(ruleObject);
                            result.rulesCount++;
                        } else result.rulesSkipped++;
                    } catch (Exception error) {
                        result.rulesSkipped++;
                        Log.w(TAG, "Skipping imported rule " + i, error);
                    }
                }
                SharedPreferences rulePrefs = context.getSharedPreferences(
                        "organizer_prefs", Context.MODE_PRIVATE);
                if (!rulePrefs.edit().putString("rules", validRules.toString()).commit()
                        || !validRules.toString().equals(rulePrefs.getString("rules", ""))) {
                    recordFailure(result, "organizer_prefs.rules");
                }
            } catch (Exception error) {
                recordFailure(result, "organizer_prefs.rules", error);
            }

            // 4. Gesture mappings
            JSONObject gestures = root.getJSONObject("gestures");
            SharedPreferences gesturePrefs = context.getSharedPreferences(
                    "gesture_prefs", Context.MODE_PRIVATE);
            clearKnownValues(gesturePrefs, "gesture_prefs", result);
            Iterator<String> gestureKeys = gestures.keys();
            while (gestureKeys.hasNext()) {
                String key = gestureKeys.next();
                if (!isKnownKey("gesture_prefs", key)) continue;
                try {
                    Object value = gestures.get(key);
                    if (value instanceof String) {
                        applyOneValue(gesturePrefs, key, value, result);
                        result.gesturesCount++;
                    }
                } catch (Exception error) {
                    recordFailure(result, "gesture_prefs." + key, error);
                }
            }
            // Preserve additional known gesture values from the preferences
            // object (dpad toggle, last macro id, etc.).
            JSONObject gesturePrefsObject = preferences.optJSONObject("gesture_prefs");
            if (gesturePrefsObject != null) {
                applyPreferenceObjectWithoutClear(context, "gesture_prefs", gesturePrefsObject, result);
            }

            // 5. Macros
            try {
                JSONArray macros = root.getJSONArray("macros");
                // Parsing validates the JSON and each action. Invalid individual
                // actions are omitted by GestureSettings on the next read.
                String macroJson = macros.toString();
                new JSONArray(macroJson);
                boolean committed = gesturePrefs.edit().putString(
                        "gesture_macros", macroJson).commit();
                if (!committed || !macroJson.equals(gesturePrefs.getString("gesture_macros", ""))) {
                    recordFailure(result, "gesture_prefs.gesture_macros");
                } else result.macrosCount = macros.length();
            } catch (Exception error) {
                recordFailure(result, "gesture_prefs.gesture_macros", error);
            }

            // 6. Tag presets last; they are referenced by some gesture actions.
            try {
                JSONArray presets = root.getJSONArray("tag_presets");
                SharedPreferences presetPrefs = context.getSharedPreferences(
                        "tag_preset_prefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = presetPrefs.edit().clear();
                for (int i = 0; i < Math.min(5, presets.length()); i++) {
                    editor.putString("preset_" + i, presets.optString(i, ""));
                }
                if (!editor.commit()) recordFailure(result, "tag_preset_prefs");
                else {
                    for (int i = 0; i < Math.min(5, presets.length()); i++) {
                        verifyPreference(presetPrefs, "preset_" + i,
                                presets.optString(i, ""), result);
                    }
                }
            } catch (Exception error) {
                recordFailure(result, "tag_preset_prefs", error);
            }

            // Verify the stores explicitly before reporting success.
            verifyRequiredStores(context, root, result);
            result.isSuccess = true;
            return result;
        } catch (Throwable uncaught) {
            Log.e(TAG, "Import failed; resetting preferences", uncaught);
            resetToDefaults(context);
            result.isSuccess = false;
            result.errorMessage = "Import failed — settings reset to defaults.";
            return result;
        }
    }

    private static boolean isStructuredPrefs(String prefsName) {
        return "folder_prefs".equals(prefsName) || "organizer_prefs".equals(prefsName)
                || "gesture_prefs".equals(prefsName) || "tag_preset_prefs".equals(prefsName);
    }

    private static void clearKnownValues(SharedPreferences prefs, String prefsName,
                                          ApplyResult result) {
        try {
            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;
            for (String key : prefs.getAll().keySet()) {
                if (isKnownKey(prefsName, key)) {
                    editor.remove(key);
                    changed = true;
                }
            }
            if (changed && !editor.commit()) recordFailure(result, prefsName + ".clear");
        } catch (Exception error) {
            recordFailure(result, prefsName + ".clear", error);
        }
    }

    private static void applyPreferenceObject(Context context, String prefsName,
                                              JSONObject values, ApplyResult result) {
        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        clearKnownValues(prefs, prefsName, result);
        applyPreferenceValues(prefs, prefsName, values, result);
    }

    private static void applyPreferenceObjectWithoutClear(Context context, String prefsName,
                                                          JSONObject values, ApplyResult result) {
        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        applyPreferenceValues(prefs, prefsName, values, result);
    }

    private static void applyPreferenceValues(SharedPreferences prefs, String prefsName,
                                              JSONObject values, ApplyResult result) {
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!isKnownKey(prefsName, key)) continue;
            try {
                applyOneValue(prefs, key, values.get(key), result);
                result.preferencesCount++;
            } catch (Exception error) {
                recordFailure(result, prefsName + "." + key, error);
            }
        }
    }

    private static void applyOneValue(SharedPreferences prefs, String key, Object value,
                                       ApplyResult result) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value instanceof JSONArray) {
            Set<String> set = new HashSet<String>();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) set.add(array.optString(i, ""));
            editor.putStringSet(key, set);
        } else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Double) editor.putFloat(key, ((Double) value).floatValue());
        else if (value instanceof Number) editor.putFloat(key, ((Number) value).floatValue());
        else if (value instanceof String) editor.putString(key, (String) value);
        else throw new IllegalArgumentException("Unsupported preference value");
        if (!editor.commit()) throw new IllegalStateException("commit returned false");
        verifyPreference(prefs, key, value, result);
    }

    private static void verifyPreference(SharedPreferences prefs, String key,
                                         Object expected, ApplyResult result) {
        if (expected == null) return;
        boolean okay = false;
        Object actual = prefs.getAll().get(key);
        if (expected instanceof JSONArray) {
            if (actual instanceof Set) {
                Set<String> expectedSet = new HashSet<String>();
                JSONArray array = (JSONArray) expected;
                for (int i = 0; i < array.length(); i++) expectedSet.add(array.optString(i, ""));
                Set<String> actualSet = new HashSet<String>();
                for (Object value : (Set<?>) actual) actualSet.add(String.valueOf(value));
                okay = expectedSet.equals(actualSet);
            }
        } else if (expected instanceof Number && actual instanceof Number) {
            okay = Math.abs(((Number) expected).doubleValue()
                    - ((Number) actual).doubleValue()) < 0.001;
        } else {
            okay = expected.equals(actual);
        }
        if (!okay) {
            if (result != null) recordFailure(result, "preference." + key);
            else Log.w(TAG, "Imported value could not be verified: " + key);
        }
    }

    private static void verifyRequiredStores(Context context, JSONObject root, ApplyResult result) {
        try {
            JSONArray folders = root.optJSONArray("folders");
            SharedPreferences folderPrefs = context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE);
            if (folders != null) {
                Set<String> expectedFolders = new HashSet<String>();
                for (int i = 0; i < folders.length(); i++) {
                    String folder = folders.optString(i, "").trim();
                    if (!folder.isEmpty()) expectedFolders.add(folder);
                }
                Set<String> actualFolders = new HashSet<String>(folderPrefs.getStringSet(
                        "watched_folders", new HashSet<String>()));
                if (!expectedFolders.equals(actualFolders)) {
                    recordFailure(result, "folder_prefs.watched_folders");
                }
            }
            JSONArray expectedRules = root.optJSONArray("rules");
            if (expectedRules != null) {
                String actualRules = context.getSharedPreferences("organizer_prefs", Context.MODE_PRIVATE)
                        .getString("rules", "[]");
                if (!expectedRules.toString().equals(actualRules)) recordFailure(result, "organizer_prefs.rules");
            }
            JSONObject prefs = root.optJSONObject("preferences");
            if (prefs != null) {
                JSONObject settings = prefs.optJSONObject("settings_prefs");
                if (settings != null) {
                    Iterator<String> keys = settings.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (isKnownKey("settings_prefs", key)
                                && !context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).contains(key)) {
                            recordFailure(result, "settings_prefs." + key);
                        }
                    }
                }
            }
        } catch (Exception error) {
            recordFailure(result, "post-import verification", error);
        }
    }

    private static void recordFailure(ApplyResult result, String key) {
        result.failedKeys++;
        Log.w(TAG, "Skipping failed imported key " + key);
    }

    private static void recordFailure(ApplyResult result, String key, Exception error) {
        result.failedKeys++;
        Log.w(TAG, "Skipping failed imported key " + key, error);
    }

    public static File getBackupDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File root = external != null ? external : context.getFilesDir();
        return new File(root, "backups");
    }

    public static File[] listBackups(Context context) {
        File directory = getBackupDir(context);
        if (!directory.exists()) return new File[0];
        File[] files = directory.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return name != null && name.endsWith(".json");
            }
        });
        return files == null ? new File[0] : files;
    }
}
