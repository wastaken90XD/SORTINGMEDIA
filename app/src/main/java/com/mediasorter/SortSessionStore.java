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
