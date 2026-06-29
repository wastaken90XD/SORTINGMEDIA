package com.mediasorter;

import android.os.FileObserver;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

public class FolderWatcher {

    public interface Listener {
        void onFileAdded(String path);
        void onFileDeleted(String path);
        void onFileModified(String path);
    }

    // Thread‑safe map of watched folders
    private final ConcurrentHashMap<String, FileObserver> watchers = new ConcurrentHashMap<>();
    private final Listener listener;

    public FolderWatcher(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts watching a folder for file additions, deletions, and modifications.
     * @param folderPath absolute path to an existing directory
     */
    public void watch(String folderPath) {
        // Normalize to avoid duplicates
        File folder = new File(folderPath).getAbsoluteFile();
        final String key = folder.getAbsolutePath();

        if (!folder.exists() || !folder.isDirectory()) {
            // Silently ignore non‑existent folders
            return;
        }

        if (watchers.containsKey(key)) return;

        // Use the String constructor for API < 29 compatibility
        FileObserver observer = new FileObserver(folder.getAbsolutePath(),
                FileObserver.CREATE |
                FileObserver.DELETE |
                FileObserver.MODIFY |
                FileObserver.MOVED_TO |
                FileObserver.MOVED_FROM) {

            @Override
            public void onEvent(int event, String fileName) {
                if (fileName == null || listener == null) return;

                // Build clean full path
                String fullPath = new File(folder, fileName).getAbsolutePath();

                int baseEvent = event & FileObserver.ALL_EVENTS;
                switch (baseEvent) {
                    case FileObserver.CREATE:
                    case FileObserver.MOVED_TO:
                        listener.onFileAdded(fullPath);
                        break;
                    case FileObserver.DELETE:
                    case FileObserver.MOVED_FROM:
                        listener.onFileDeleted(fullPath);
                        break;
                    case FileObserver.MODIFY:
                        listener.onFileModified(fullPath);
                        break;
                }
            }
        };

        observer.startWatching();
        watchers.put(key, observer);
    }

    public void unwatch(String folderPath) {
        String key = new File(folderPath).getAbsoluteFile().getAbsolutePath();
        FileObserver observer = watchers.remove(key);
        if (observer != null) {
            observer.stopWatching();
        }
    }

    public void unwatchAll() {
        for (FileObserver o : watchers.values()) {
            o.stopWatching();
        }
        watchers.clear();
    }

    public List<String> getWatchedFolders() {
        return new ArrayList<>(watchers.keySet());
    }

    public boolean isWatching(String folderPath) {
        String key = new File(folderPath).getAbsoluteFile().getAbsolutePath();
        return watchers.containsKey(key);
    }

    public void pauseAll() {
        for (FileObserver o : watchers.values()) {
            o.stopWatching();
        }
    }

    public void resumeAll() {
        for (FileObserver o : watchers.values()) {
            o.startWatching();
        }
    }
}
