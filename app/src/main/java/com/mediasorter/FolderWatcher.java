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

    // Use ConcurrentHashMap for thread‑safe access from callbacks and main thread
    private final ConcurrentHashMap<String, FileObserver> watchers = new ConcurrentHashMap<>();
    private final Listener listener;

    public FolderWatcher(Listener listener) {
        this.listener = listener;
    }

    /**
     * Starts watching a folder for file additions, deletions, and modifications.
     *
     * @param folderPath absolute path to an existing directory
     */
    public void watch(String folderPath) {
        // Normalize path to avoid duplicates (e.g. /sdcard/DCIM vs /storage/emulated/0/DCIM)
        File folder = new File(folderPath).getAbsoluteFile();
        String key = folder.getAbsolutePath();

        if (!folder.exists() || !folder.isDirectory()) {
            // Log or throw – silent failure is confusing
            return;
        }

        if (watchers.containsKey(key)) return;

        // FileObserver requires an absolute path to a directory
        FileObserver observer = new FileObserver(folder,
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

                // Mask to get base event
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

    /**
     * Note: On Android 10+ (API 29) FileObserver cannot reliably monitor
     * external storage due to scoped storage restrictions.
     * Consider using MediaStore or SAF for full reliability.
     */
}
