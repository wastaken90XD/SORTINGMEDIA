package com.mediasorter;

import android.os.FileObserver;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class FolderWatcher {

    public interface Listener {
        void onFileAdded(String path);
        void onFileDeleted(String path);
        void onFileModified(String path);
    }

    private final Map<String, FileObserver> watchers = new HashMap<>();
    private final Listener listener;

    public FolderWatcher(Listener listener) {
        this.listener = listener;
    }

    public void watch(String folderPath) {
        if (watchers.containsKey(folderPath)) return;

        FileObserver observer = new FileObserver(folderPath,
            FileObserver.CREATE  |
            FileObserver.DELETE  |
            FileObserver.MODIFY  |
            FileObserver.MOVED_TO |
            FileObserver.MOVED_FROM) {

            @Override
            public void onEvent(int event, String fileName) {
                if (fileName == null) return;
                String fullPath = folderPath + "/" + fileName;

                switch (event & FileObserver.ALL_EVENTS) {
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
        watchers.put(folderPath, observer);
    }

    public void unwatch(String folderPath) {
        FileObserver observer = watchers.remove(folderPath);
        if (observer != null) observer.stopWatching();
    }

    public void unwatchAll() {
        for (FileObserver o : watchers.values()) o.stopWatching();
        watchers.clear();
    }

    public List<String> getWatchedFolders() {
        return new ArrayList<>(watchers.keySet());
    }

    public boolean isWatching(String folderPath) {
        return watchers.containsKey(folderPath);
    }
}
