package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.mediasorter.features.RandomGenerator;
import com.mediasorter.models.Tag;
import com.mediasorter.organizer.Rule;
import com.mediasorter.organizer.RuleSerializer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backup / restore exporter module.
 * Covers: gestures, tag lists, organizer rules, watched folders, cache settings,
 * thumbnail settings, and file status data.
 */
public class SettingsExporter {

    private static final String TAG = "SettingsExporter";

    // SharedPreferences file names to include
    private static final String[] PREFS_KEYS = {
        "gesture_prefs",       // swipe/dpad gestures
        "tag_list_prefs",      // tag lists
        "organizer_prefs",     // organizer rules (JSON string)
        "folder_prefs",        // watched folders
        "cache_prefs",         // cache settings
        "thumb_prefs",         // thumbnail settings
        "file_status_prefs",   // skip/flag/done status
        "window_prefs",        // window size
        "search_history_prefs" // search history
    };

    public static class ApplyResult {
        public int preferencesCount = 0;
        public int foldersCount = 0;
        public int rulesCount = 0;
        public int gesturesCount = 0;
        public int macrosCount = 0;
        public int rulesSkipped = 0;
        public boolean isSuccess = false;
        public String errorMessage = null;
    }

    public static String exportSettings(Context context) {
        String prefix = RandomGenerator.randomGroupPrefix(new java.util.HashSet<String>());
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        String dateStr = sdf.format(cal.getTime());
        String defaultFilename = "export_" + prefix + "_" + dateStr + ".json";
        
        File exportDir = getBackupDir(context);
        return exportSettings(context, exportDir.getAbsolutePath(), defaultFilename);
    }

    public static String exportSettings(Context context, String directoryPath, String filename) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", BuildConfig.VERSION_CODE);
            root.put("timestamp", System.currentTimeMillis());

            // Preferences
            JSONObject prefsContainer = new JSONObject();
            List<String> allPrefsKeys = new ArrayList<>(Arrays.asList(PREFS_KEYS));
            if (!allPrefsKeys.contains("tag_preset_prefs")) {
                allPrefsKeys.add("tag_preset_prefs");
            }

            for (String prefsName : allPrefsKeys) {
                SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                JSONObject prefsObj = new JSONObject();
                Map<String, ?> all = prefs.getAll();
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String key = entry.getKey();
                    Object val = entry.getValue();
                    if (val instanceof String)        prefsObj.put(key, val);
                    else if (val instanceof Integer)   prefsObj.put(key, val);
                    else if (val instanceof Long)      prefsObj.put(key, val);
                    else if (val instanceof Float)     prefsObj.put(key, val);
                    else if (val instanceof Boolean)   prefsObj.put(key, val);
                    else if (val instanceof java.util.Set) {
                        JSONArray arr = new JSONArray();
                        for (Object item : (java.util.Set<?>) val) {
                            arr.put(item.toString());
                        }
                        prefsObj.put(key, arr);
                    }
                }
                prefsContainer.put(prefsName, prefsObj);
            }
            root.put("preferences", prefsContainer);

            // Folders
            FolderManager folderManager = new FolderManager(context);
            JSONArray foldersArr = new JSONArray();
            for (String f : folderManager.getFolders()) {
                foldersArr.put(f);
            }
            root.put("folders", foldersArr);

            // Gestures
            SharedPreferences gesturePrefs = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE);
            JSONObject gesturesObj = new JSONObject();
            String[] gestureKeys = {
                "swipe_left_v2", "swipe_right_v2", "swipe_up_v2", "swipe_down_v2",
                "dpad_up_v2", "dpad_down_v2", "dpad_left_v2", "dpad_right_v2", "dpad_center_v2"
            };
            for (String gk : gestureKeys) {
                if (gesturePrefs.contains(gk)) {
                    gesturesObj.put(gk, gesturePrefs.getString(gk, ""));
                }
            }
            root.put("gestures", gesturesObj);

            // Macros
            String macrosStr = gesturePrefs.getString("gesture_macros", "[]");
            root.put("macros", new JSONArray(macrosStr));

            // Tag Presets
            SharedPreferences presetPrefs = context.getSharedPreferences("tag_preset_prefs", Context.MODE_PRIVATE);
            JSONArray presetsArr = new JSONArray();
            for (int i = 0; i < 5; i++) {
                presetsArr.put(presetPrefs.getString("preset_" + i, ""));
            }
            root.put("tag_presets", presetsArr);

            // Rules
            SharedPreferences organizerPrefs = context.getSharedPreferences("organizer_prefs", Context.MODE_PRIVATE);
            String rulesStr = organizerPrefs.getString("rules", "[]");
            root.put("rules", new JSONArray(rulesStr));

            // Setup directories
            File exportDir = new File(directoryPath);
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                return null;
            }

            File destFile = new File(exportDir, filename);
            File tempFile = new File(exportDir, filename + ".tmp");

            // Write via FileOutputStream with a 256KB buffer (matching MetadataWriter pattern)
            java.io.FileOutputStream fos = null;
            java.io.BufferedOutputStream bos = null;
            try {
                fos = new java.io.FileOutputStream(tempFile);
                bos = new java.io.BufferedOutputStream(fos, 256 * 1024); // 256KB buffer
                byte[] bytes = root.toString(2).getBytes("UTF-8");
                bos.write(bytes);
                bos.flush();
            } finally {
                if (bos != null) try { bos.close(); } catch (Exception ignored) {}
                if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            }

            // Verify file exists and is non-zero
            if (!tempFile.exists() || tempFile.length() == 0) {
                if (tempFile.exists()) tempFile.delete();
                return null;
            }

            // Atomic rename
            if (destFile.exists()) {
                destFile.delete();
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.delete();
                return null;
            }

            Log.d(TAG, "Exported successfully to " + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Export failed: " + e.getMessage());
            return null;
        }
    }

    public static void resetToDefaults(Context context) {
        List<String> allPrefsKeys = new ArrayList<>(Arrays.asList(PREFS_KEYS));
        if (!allPrefsKeys.contains("tag_preset_prefs")) {
            allPrefsKeys.add("tag_preset_prefs");
        }
        for (String prefsName : allPrefsKeys) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    public static ApplyResult applyImport(Context context, JSONObject root) {
        ApplyResult result = new ApplyResult();
        try {
            // 1. SharedPreferences (clear existing first, then write all)
            JSONObject preferences = root.getJSONObject("preferences");
            java.util.Iterator<String> keys = preferences.keys();
            while (keys.hasNext()) {
                String prefsName = keys.next();
                JSONObject prefsObj = preferences.getJSONObject(prefsName);
                SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                
                SharedPreferences.Editor editor = prefs.edit();
                editor.clear();
                
                java.util.Iterator<String> pKeys = prefsObj.keys();
                while (pKeys.hasNext()) {
                    String pKey = pKeys.next();
                    Object pVal = prefsObj.get(pKey);
                    if (pVal instanceof String) {
                        editor.putString(pKey, (String) pVal);
                    } else if (pVal instanceof Integer) {
                        editor.putInt(pKey, (Integer) pVal);
                    } else if (pVal instanceof Long) {
                        editor.putLong(pKey, (Long) pVal);
                    } else if (pVal instanceof Double) {
                        editor.putFloat(pKey, ((Double) pVal).floatValue());
                    } else if (pVal instanceof Boolean) {
                        editor.putBoolean(pKey, (Boolean) pVal);
                    } else if (pVal instanceof JSONArray) {
                        JSONArray arr = (JSONArray) pVal;
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            set.add(arr.getString(i));
                        }
                        editor.putStringSet(pKey, set);
                    }
                    result.preferencesCount++;
                }
                
                if (!editor.commit()) {
                    result.errorMessage = "Settings may not have saved — storage full?";
                }
            }

            // 2. Watched folders (clear existing, then add all)
            JSONArray foldersArr = root.getJSONArray("folders");
            SharedPreferences folderPrefs = context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor folderEditor = folderPrefs.edit();
            folderEditor.clear();
            java.util.Set<String> folderSet = new java.util.HashSet<>();
            for (int i = 0; i < foldersArr.length(); i++) {
                folderSet.add(foldersArr.getString(i));
                result.foldersCount++;
            }
            folderEditor.putStringSet("watched_folders", folderSet);
            if (!folderEditor.commit()) {
                result.errorMessage = "Settings may not have saved — storage full?";
            }

            // 3. Rules (clear existing, then load all)
            JSONArray rulesArr = root.getJSONArray("rules");
            List<com.mediasorter.organizer.Rule> rulesList = new ArrayList<>();
            for (int i = 0; i < rulesArr.length(); i++) {
                try {
                    JSONObject ruleObj = rulesArr.getJSONObject(i);
                    JSONArray tempArr = new JSONArray();
                    tempArr.put(ruleObj);
                    List<com.mediasorter.organizer.Rule> parsed = RuleSerializer.loadRulesFromJsonStr(tempArr.toString());
                    if (parsed != null && !parsed.isEmpty()) {
                        rulesList.add(parsed.get(0));
                        result.rulesCount++;
                    } else {
                        result.rulesSkipped++;
                    }
                } catch (Exception e) {
                    result.rulesSkipped++;
                }
            }
            com.mediasorter.organizer.RuleSerializer.saveRulesDirect(context, rulesList);

            // 4. Gesture mappings (clear existing gesture_ keys, then write all)
            JSONObject gesturesObj = root.getJSONObject("gestures");
            SharedPreferences gesturePrefs = context.getSharedPreferences("gesture_prefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor gestureEditor = gesturePrefs.edit();
            Map<String, ?> allGesturePrefs = gesturePrefs.getAll();
            for (String gk : allGesturePrefs.keySet()) {
                if (gk.startsWith("swipe_") || gk.startsWith("dpad_")) {
                    gestureEditor.remove(gk);
                }
            }
            java.util.Iterator<String> gKeys = gesturesObj.keys();
            while (gKeys.hasNext()) {
                String gk = gKeys.next();
                gestureEditor.putString(gk, gesturesObj.getString(gk));
                result.gesturesCount++;
            }
            if (!gestureEditor.commit()) {
                result.errorMessage = "Settings may not have saved — storage full?";
            }

            // 5. Gesture macros (overwrite "gesture_macros" key)
            JSONArray macrosArr = root.getJSONArray("macros");
            SharedPreferences.Editor macrosEditor = gesturePrefs.edit();
            macrosEditor.putString("gesture_macros", macrosArr.toString());
            if (!macrosEditor.commit()) {
                result.errorMessage = "Settings may not have saved — storage full?";
            }
            result.macrosCount = macrosArr.length();

            // 6. Tag preset slots (overwrite all 5)
            JSONArray presetsArr = root.getJSONArray("tag_presets");
            SharedPreferences presetPrefs = context.getSharedPreferences("tag_preset_prefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor presetEditor = presetPrefs.edit();
            presetEditor.clear();
            for (int i = 0; i < Math.min(5, presetsArr.length()); i++) {
                presetEditor.putString("preset_" + i, presetsArr.getString(i));
            }
            if (!presetEditor.commit()) {
                result.errorMessage = "Settings may not have saved — storage full?";
            }

            result.isSuccess = true;
        } catch (Throwable t) {
            resetToDefaults(context);
            result.isSuccess = false;
            result.errorMessage = "Import failed — settings have been reset to defaults.";
        }
        return result;
    }

    /**
     * Get the backup directory path. getExternalFilesDir() can return null
     * when external storage is (temporarily) unavailable — fall back to
     * internal storage so export still works instead of producing garbage
     * relative paths.
     */
    public static File getBackupDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File root = external != null ? external : context.getFilesDir();
        return new File(root, "backups");
    }

    /**
     * List available backup files.
     */
    public static File[] listBackups(Context context) {
        File dir = getBackupDir(context);
        if (!dir.exists()) return new File[0];
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        return files != null ? files : new File[0];
    }
}
