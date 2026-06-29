package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FolderManager {

    private static final String PREFS       = "folder_prefs";
    private static final String KEY_FOLDERS = "watched_folders";

    private final SharedPreferences prefs;

    public FolderManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addFolder(String path) {
        if (path == null || path.trim().isEmpty()) return;
        Set<String> folders = getFolderSet();
        if (folders.add(path)) save(folders);
    }

    public void removeFolder(String path) {
        Set<String> folders = getFolderSet();
        if (folders.remove(path)) save(folders);
    }

    public List<String> getFolders() {
        return new ArrayList<>(getFolderSet());
    }

    public boolean hasFolder(String path) {
        return getFolderSet().contains(path);
    }

    public boolean isEmpty() {
        return getFolderSet().isEmpty();
    }

    private Set<String> getFolderSet() {
        return new HashSet<>(prefs.getStringSet(KEY_FOLDERS, new HashSet<>()));
    }

    private void save(Set<String> folders) {
        prefs.edit().putStringSet(KEY_FOLDERS, folders).apply();
    }
}
