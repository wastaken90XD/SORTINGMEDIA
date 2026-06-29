package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaIndexer {

    private static final int PAGE_SIZE     = 50;
    private static final int PAGE_DELAY_MS = 150;

    public interface IndexListener {
        void onFileFound(MediaFile file);
        void onPageLoaded(List<MediaFile> page);
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
    private       IndexListener              listener;
    private       boolean                    scanning = false;

    public void setListener(IndexListener l) { this.listener = l; }
    public boolean isScanning()              { return scanning; }

    // ── Full scan ─────────────────────────────────────────────────────────────

    public void scanFolder(String folderPath) {
        if (scanning) return;
        scanning = true;

        executor.submit(() -> {
            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) {
                scanning = false;
                return;
            }

            File[] files = folder.listFiles();
            if (files == null) {
                scanning = false;
                return;
            }

            List<MediaFile> page     = new ArrayList<>();
            List<MediaFile> allFound = new ArrayList<>();

            for (File f : files) {
                if (f.isDirectory()) continue;
                if (manifest.containsKey(f.getAbsolutePath())) continue;

                MediaFile mf = buildLight(f);
                if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                    page.add(mf);
                    allFound.add(mf);

                    if (page.size() >= PAGE_SIZE) {
                        final List<MediaFile> batch = new ArrayList<>(page);
                        if (listener != null) listener.onPageLoaded(batch);
                        page.clear();
                        try { Thread.sleep(PAGE_DELAY_MS); }
                        catch (InterruptedException ignored) {}
                    }
                }
            }

            if (!page.isEmpty() && listener != null) {
                listener.onPageLoaded(new ArrayList<>(page));
            }

            synchronized (index) { index.addAll(allFound); }
            scanning = false;

            if (listener != null) {
                listener.onScanComplete(new ArrayList<>(index));
            }
        });
    }

    // ── Lightweight rescan ────────────────────────────────────────────────────

    public void rescan(String folderPath) {
        if (scanning) return;

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
                    MediaFile mf = buildLight(f);
                    addToIndex(mf);
                    if (listener != null) listener.onFileFound(mf);

                } else if (existing.size != size) {
                    MediaFile mf = buildLight(f);
                    updateInIndex(mf);
                    if (listener != null) listener.onFileChanged(mf);

                } else {
                    byte[] newHash = HashScanner.partialHash(path);
                    if (!HashScanner.hashesMatch(existing.hash, newHash)) {
                        MediaFile mf = buildLight(f);
                        updateInIndex(mf);
                        if (listener != null) listener.onFileChanged(mf);
                    }
                }
            }

            List<String> toRemove = new ArrayList<>();
            String normalizedFolder = folderPath.endsWith("/")
                ? folderPath
                : folderPath + "/";

            synchronized (index) {
                for (MediaFile mf : index) {
                    if (mf.getPath().startsWith(normalizedFolder)
                            && !onDisk.contains(mf.getPath())) {
                        toRemove.add(mf.getPath());
                    }
                }
            }
            for (String path : toRemove) {
                removeFromIndex(path);
                if (listener != null) listener.onFileRemoved(path);
            }
        });
    }

    // ── Clean rescan — removes ghost files ────────────────────────────────────

    public void rescanClean(String folderPath) {
        executor.submit(() -> {
            File folder = new File(folderPath);
            if (!folder.exists()) return;

            File[] files = folder.listFiles();
            if (files == null) return;

            // Build set of files on disk
            Set<String> onDisk = new HashSet<>();
            for (File f : files) {
                if (!f.isDirectory()) onDisk.add(f.getAbsolutePath());
            }

            // Remove ghosts
            List<String> toRemove = new ArrayList<>();
            synchronized (index) {
                for (MediaFile mf : index) {
                    if (mf.getPath().startsWith(folderPath)
                            && !onDisk.contains(mf.getPath())) {
                        toRemove.add(mf.getPath());
                    }
                }
            }
            
            for (String path : toRemove) {
                removeFromIndex(path);
                manifest.remove(path);
                if (listener != null) listener.onFileRemoved(path);
            }

            // Add new files
            for (File f : files) {
                if (f.isDirectory()) continue;
                if (!manifest.containsKey(f.getAbsolutePath())) {
                    MediaFile mf = buildLight(f);
                    if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                        addToIndex(mf);
                        if (listener != null) listener.onFileFound(mf);
                    }
                }
            }

            if (listener != null) {
                listener.onScanComplete(new ArrayList<>(index));
            }
        });
    }

    // ── Full reset ────────────────────────────────────────────────────────────

    public void fullReset(List<String> folders) {
        synchronized (index) { index.clear(); }
        manifest.clear();
        scanning = false;
        for (String folder : folders) scanFolder(folder);
    }

    // ── Delete file ───────────────────────────────────────────────────────────

    public boolean deleteFile(String path) {
        File f = new File(path);
        boolean deleted = f.exists() && f.delete();
        if (deleted) {
            removeFromIndex(path);
            if (listener != null) listener.onFileRemoved(path);
        }
        return deleted;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private MediaFile buildLight(File f) {
        MediaFile mf = new MediaFile(f.getAbsolutePath(), f.length());
        mf.setDateAdded(f.lastModified());

        List<String> existingTags = XmpReader.readTags(f.getAbsolutePath());
        for (String tag : existingTags) mf.addTag(tag);

        manifest.put(f.getAbsolutePath(),
            new ManifestEntry(f.getAbsolutePath(), f.length(), null));
        return mf;
    }

    // ── Index helpers ─────────────────────────────────────────────────────────

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
        for (int i = index.size() - 1; i >= 0; i--) {
            if (index.get(i).getPath().equals(path)) {
                index.remove(i);
                break;
            }
        }
    }
    manifest.remove(path);
}

    public List<String> getAllTagsFromIndex() {
        List<String> result = new ArrayList<>();
        synchronized (index) {
            for (MediaFile mf : index) {
                for (String tag : mf.getTags()) {
                    if (!result.contains(tag)) result.add(tag);
                }
            }
        }
        return result;
    }

    public List<MediaFile> getIndex() {
        synchronized (index) { return new ArrayList<>(index); }
    }
}
