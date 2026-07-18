package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Exports and imports all app settings to/from a JSON file on external storage.
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

    /**
     * Export all settings to a JSON file. Returns the file path on success, null on failure.
     */
    public static String exportSettings(Context context) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("exportDate", new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));

            // For each prefs file, dump all key-value pairs
            for (String prefsName : PREFS_KEYS) {
                SharedPreferences prefs = context.getSharedPreferences(
                        prefsName, Context.MODE_PRIVATE);
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
                        // StringSet — convert to JSON array
                        JSONArray arr = new JSONArray();
                        for (Object item : (java.util.Set<?>) val) {
                            arr.put(item.toString());
                        }
                        prefsObj.put(key, arr);
                    }
                }
                root.put(prefsName, prefsObj);
            }

            // Write to file
            String timestamp = new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File exportDir = new File(context.getExternalFilesDir(null), "backups");
            if (!exportDir.exists()) exportDir.mkdirs();
            File exportFile = new File(exportDir, "mediasorter_backup_" + timestamp + ".json");

            FileWriter writer = new FileWriter(exportFile);
            writer.write(root.toString(2)); // pretty-print with indent=2
            writer.close();

            Log.d(TAG, "Exported to " + exportFile.getAbsolutePath());
            return exportFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Export failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Import settings from a JSON file. Returns true on success.
     */
    public static boolean importSettings(Context context, String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return false;

            // Read file
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());

            for (String prefsName : PREFS_KEYS) {
                if (!root.has(prefsName)) continue;
                JSONObject prefsObj = root.getJSONObject(prefsName);
                SharedPreferences.Editor editor = context.getSharedPreferences(
                        prefsName, Context.MODE_PRIVATE).edit();
                editor.clear();

                java.util.Iterator<String> keys = prefsObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = prefsObj.get(key);
                    if (val instanceof String)      editor.putString(key, (String) val);
                    else if (val instanceof Integer) editor.putInt(key, (Integer) val);
                    else if (val instanceof Long)    editor.putLong(key, (Long) val);
                    else if (val instanceof Float)   editor.putFloat(key, (Float) val);
                    else if (val instanceof Double)  {
                        // JSON numbers may come back as Double
                        // Check if it's actually a long
                        double d = (Double) val;
                        if (d == Math.floor(d) && !Double.isInfinite(d)) {
                            editor.putLong(key, (long) d);
                        } else {
                            editor.putFloat(key, (float) d);
                        }
                    }
                    else if (val instanceof Boolean) editor.putBoolean(key, (Boolean) val);
                    else if (val instanceof JSONArray) {
                        java.util.Set<String> set = new java.util.HashSet<>();
                        JSONArray arr = (JSONArray) val;
                        for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                        editor.putStringSet(key, set);
                    }
                }
                editor.apply();
            }

            Log.d(TAG, "Imported from " + filePath);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Import failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the backup directory path.
     */
    public static File getBackupDir(Context context) {
        return new File(context.getExternalFilesDir(null), "backups");
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
