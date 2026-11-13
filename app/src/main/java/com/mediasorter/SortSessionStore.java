package com.mediasorter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic, non-exported session persistence for Compare and Batch Game. */
public final class SortSessionStore {
    public static final String COMPARE_FILE = "compare_session.json";
    public static final String GAME_FILE = "game_session.json";
    private final File directory;

    public SortSessionStore(android.content.Context context) {
        directory = context.getFilesDir();
    }

    public synchronized void save(String name, JSONObject value) {
        if (value == null) return;
        File target = new File(directory, name);
        File temp = new File(directory, name + ".tmp");
        try {
            FileOutputStream stream = new FileOutputStream(temp);
            byte[] bytes = value.toString().getBytes("UTF-8");
            stream.write(bytes);
            stream.flush();
            stream.close();
            if (!temp.renameTo(target)) {
                if (target.exists() && !target.delete()) { temp.delete(); return; }
                if (!temp.renameTo(target)) temp.delete();
            }
        } catch (Throwable ignored) {
            temp.delete();
        }
    }

    public synchronized JSONObject load(String name) {
        File target = new File(directory, name);
        if (!target.exists()) return null;
        try {
            DataInputStream input = new DataInputStream(new FileInputStream(target));
            byte[] bytes = new byte[(int) Math.min(Integer.MAX_VALUE, target.length())];
            input.readFully(bytes);
            input.close();
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Throwable error) {
            target.delete();
            return null;
        }
    }

    public synchronized void delete(String name) {
        if (name != null) new File(directory, name).delete();
    }

    /**
     * Rewrites path values in a saved session in place. Arrays are filtered so
     * deleted files cannot leave a dead current question or game cell behind.
     * The session files contain only path strings and settings, but the walk is
     * recursive so future session fields remain safe to add.
     */
    public static JSONObject remapPaths(JSONObject object, Map<String, String> oldToNew,
                                        Set<String> removed) {
        if (object == null) return null;
        JSONArray names = object.names();
        if (names == null) return object;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            if (key.isEmpty()) continue;
            Object value = object.opt(key);
            if (value instanceof JSONObject) {
                if ("none_returns".equals(key)) {
                    remapObjectKeys((JSONObject) value, oldToNew, removed);
                }
                remapPaths((JSONObject) value, oldToNew, removed);
            } else if (value instanceof JSONArray) {
                remapArray((JSONArray) value, oldToNew, removed);
            } else if (value instanceof String) {
                String next = remapPath((String) value, oldToNew, removed);
                try { object.put(key, next == null ? "" : next); }
                catch (Exception ignored) {}
            }
        }
        return object;
    }

    private static void remapObjectKeys(JSONObject object, Map<String, String> oldToNew,
                                         Set<String> removed) {
        JSONArray names = object.names();
        if (names == null) return;
        Map<String, Object> values = new java.util.HashMap<String, Object>();
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            values.put(key, object.opt(key));
            object.remove(key);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String next = remapPath(entry.getKey(), oldToNew, removed);
            if (next == null || next.isEmpty()) continue;
            try { object.put(next, entry.getValue()); }
            catch (Exception ignored) {}
        }
    }

    private static void remapArray(JSONArray array, Map<String, String> oldToNew,
                                   Set<String> removed) {
        for (int i = array.length() - 1; i >= 0; i--) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                remapPaths((JSONObject) value, oldToNew, removed);
            } else if (value instanceof JSONArray) {
                remapArray((JSONArray) value, oldToNew, removed);
            } else if (value instanceof String) {
                String next = remapPath((String) value, oldToNew, removed);
                if (next == null || next.isEmpty()) array.remove(i);
                else {
                    try { array.put(i, next); }
                    catch (Exception ignored) {}
                }
            }
        }
    }

    private static String remapPath(String path, Map<String, String> oldToNew,
                                    Set<String> removed) {
        if (path == null || path.isEmpty()) return path;
        if (removed != null && removed.contains(path)) return null;
        if (oldToNew != null && oldToNew.containsKey(path)) return oldToNew.get(path);
        return path;
    }

    public static List<String> existingPaths(JSONArray array, Set<String> paths) {
        List<String> result = new ArrayList<String>();
        if (array == null || paths == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String path = array.optString(i, "");
            if (!path.isEmpty() && paths.contains(path) && !result.contains(path)) result.add(path);
        }
        return result;
    }
}
