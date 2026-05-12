package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaIndexer {

    private static final int PAGE_SIZE = 100;

    public interface IndexListener {
        void onFileFound(MediaFile file);
        void onScanComplete(List<MediaFile> allFiles);
        void onFileChanged(MediaFile file);
        void onFileRemoved(String path);
    }

    private static class ManifestEntry {
        String path;
        long   size;
        byte[] hash;

        ManifestEntry(String path, long size, byte[] hash) {
            this.path = path;
            this.size = size;
            this.hash = hash;
        }
    }

    private final ExecutorService            executor = Executors.newSingleThreadExecutor();
    private final Map<String, ManifestEntry> manifest = new HashMap<>();
    private final List<MediaFile>            index    = new ArrayList<>();
    private IndexListener                    listener;

    public void setListener(IndexListener l) { this.listener = l; }

    // ── Full scan ─────────────────────────────────────────────────────────────

    public void scanFolder(String folderPath) {
        executor.submit(() -> {
            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) return;

            List<MediaFile> page    = new ArrayList<>();
            List<MediaFile> allFound = new ArrayList<>();

            scanRecursive(folder, page, allFound);

            // Emit any remaining partial page
            if (!page.isEmpty() && listener != null) {
                for (MediaFile f : page) listener.onFileFound(f);
            }

            synchronized (index) {
                index.addAll(allFound);
            }

            if (listener != null) {
                listener.onScanComplete(new ArrayList<>(index));
            }
        });
    }

    private void scanRecursive(File dir, List<MediaFile> page,
                                List<MediaFile> allFound) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanRecursive(f, page, allFound);
            } else {
                MediaFile mf = buildMediaFile(f);
                if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                    page.add(mf);
                    allFound.add(mf);

                    // Emit page when full
                    if (page.size() >= PAGE_SIZE && listener != null) {
                        List<MediaFile> batch = new ArrayList<>(page);
                        for (MediaFile pf : batch) listener.onFileFound(pf);
                        page.clear();
                    }
                }
            }
        }
    }

    // ── Lightweight rescan ────────────────────────────────────────────────────

    public void rescan(String folderPath) {
        executor.submit(() -> {
            File folder = new File(folderPath);
            if (!folder.exists()) return;

            File[] files = folder.listFiles();
            if (files == null) return;

            for (File f : files) {
                if (f.isDirectory()) continue;

                String path = f.getAbsolutePath();
                long   size = f.length();

                ManifestEntry existing = manifest.get(path);

                if (existing == null) {
                    MediaFile mf = buildMediaFile(f);
                    addToIndex(mf);
                    if (listener != null) listener.onFileFound(mf);

                } else if (existing.size != size) {
                    MediaFile mf = buildMediaFile(f);
                    updateInIndex(mf);
                    if (listener != null) listener.onFileChanged(mf);

                } else {
                    byte[] newHash = HashScanner.partialHash(path);
                    if (!HashScanner.hashesMatch(existing.hash, newHash)) {
                        MediaFile mf = buildMediaFile(f);
                        updateInIndex(mf);
                        if (listener != null) listener.onFileChanged(mf);
                    }
                }
            }

            // Check deletions
            List<String> toRemove = new ArrayList<>();
            synchronized (index) {
                for (MediaFile mf : index) {
                    if (mf.getPath().startsWith(folderPath)) {
                        if (!new File(mf.getPath()).exists()) {
                            toRemove.add(mf.getPath());
                        }
                    }
                }
            }
            for (String path : toRemove) {
                removeFromIndex(path);
                if (listener != null) listener.onFileRemoved(path);
            }
        });
    }

    // ── Index helpers ─────────────────────────────────────────────────────────

    private MediaFile buildMediaFile(File f) {
        MediaFile mf   = new MediaFile(f.getAbsolutePath(), f.length());
        byte[]    hash = HashScanner.partialHash(f.getAbsolutePath());
        mf.setPartialHash(hash);
        mf.setDateAdded(f.lastModified());
        manifest.put(f.getAbsolutePath(),
            new ManifestEntry(f.getAbsolutePath(), f.length(), hash));
        return mf;
    }

    private void addToIndex(MediaFile mf) {
        synchronized (index) { index.add(mf); }
    }

    private void updateInIndex(MediaFile mf) {
        synchronized (index) {
            for (int i = 0; i < index.size(); i++) {
                if (index.get(i).getPath().equals(mf.getPath())) {
                    index.set(i, mf);
                    return;
                }
            }
            index.add(mf);
        }
    }

    private void removeFromIndex(String path) {
        synchronized (index) {
            index.removeIf(mf -> mf.getPath().equals(path));
        }
        manifest.remove(path);
    }

    public List<MediaFile> getIndex() {
        synchronized (index) { return new ArrayList<>(index); }
    }
}
