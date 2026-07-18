package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MediaIndexer {

    private static final int PAGE_SIZE     = 50;
    private static final int PAGE_DELAY_MS = 150;

    public interface IndexListener {
        void onFileFound(MediaFile file);
        void onPageLoaded(List<MediaFile> page);
        void onScanProgress(int scanned, int total, String currentFile);
        void onScanComplete(List<MediaFile> allFiles);
        void onFileChanged(MediaFile file);
        void onFileRemoved(String path);
    }

    private static class ManifestEntry {
        final long   size;
        final byte[] hash;

        ManifestEntry(long size, byte[] hash) {
            this.size = size;
            this.hash = hash;
        }
    }

    // Thread-safe replacements for the original HashMap and boolean flag
    private final ExecutorService               executor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, ManifestEntry> manifest = new ConcurrentHashMap<>();
    private final List<MediaFile>               index    = new ArrayList<>();
    private       IndexListener                 listener;
    private final AtomicBoolean                 scanning = new AtomicBoolean(false);
    private final List<String> folderQueue = new ArrayList<>();

    public void setListener(IndexListener l) { this.listener = l; }
    public boolean isScanning()              { return scanning.get(); }

    // ── Full scan ─────────────────────────────────────────────────────────────

    public void scanFolder(String folderPath) {
    if (!scanning.compareAndSet(false, true)) return;

    executor.submit(() -> {
        try {
            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) return;

            File[] files = folder.listFiles();
            if (files == null) return;

            // Count eligible files for progress
            int totalMedia = 0;
            for (File f : files) {
                if (!f.isDirectory()) {
                    String n = f.getName().toLowerCase();
                    if (n.matches(".*\\.(jpg|jpeg|png|bmp|webp|gif)")
                            || n.matches(".*\\.(mp4|3gp|avi|mkv|mov|webm)")) {
                        totalMedia++;
                    }
                }
            }

            List<MediaFile> page     = new ArrayList<>();
            List<MediaFile> allFound = new ArrayList<>();
            int scanned = 0;

            for (File f : files) {
                if (f.isDirectory()) continue;
                String absPath = f.getAbsolutePath();
                if (manifest.containsKey(absPath)) continue;

                MediaFile mf = buildLight(f);
                if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                    scanned++;
                    if (listener != null) listener.onScanProgress(scanned, totalMedia, f.getName());

                    page.add(mf);
                    allFound.add(mf);

                    // Build hash for the manifest entry
                    byte[] hash = HashScanner.partialHash(absPath);
                    manifest.put(absPath, new ManifestEntry(f.length(), hash));

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

            if (listener != null) {
                listener.onScanComplete(new ArrayList<>(index));
            }

        } finally {
            scanNextInQueue();
            scanning.set(false);
        }
    });
}

    public void scanFolders(List<String> folders) {
        synchronized (folderQueue) {
            folderQueue.clear();
            folderQueue.addAll(folders);
        }
        scanNextInQueue();
    }

    private void scanNextInQueue() {
        String next = null;
        synchronized (folderQueue) {
            if (!folderQueue.isEmpty()) next = folderQueue.remove(0);
        }
        if (next != null) scanFolder(next);
    }

    // ── Lightweight rescan ────────────────────────────────────────────────────

    public void rescan(String folderPath) {
        if (!scanning.compareAndSet(false, true)) return;

        executor.submit(() -> {
            try {
                File folder = new File(folderPath);
                if (!folder.exists()) return;

                File[] files = folder.listFiles();
                if (files == null) return;

                // 1. Check for new/changed files
                for (File f : files) {
                    if (f.isDirectory()) continue;

                    String path = f.getAbsolutePath();
                    long   size = f.length();
                    ManifestEntry existing = manifest.get(path);

                    if (existing == null) {
                        MediaFile mf = buildLight(f);
                        addToIndex(mf);
                        byte[] hash = HashScanner.partialHash(path);
                        manifest.put(path, new ManifestEntry(size, hash));
                        if (listener != null) listener.onFileFound(mf);

                    } else if (existing.size != size || existing.hash == null) {
                        MediaFile mf = buildLight(f);
                        updateInIndex(mf);
                        byte[] hash = HashScanner.partialHash(path);
                        manifest.put(path, new ManifestEntry(size, hash));
                        if (listener != null) listener.onFileChanged(mf);

                    } else {
                        byte[] newHash = HashScanner.partialHash(path);
                        if (!HashScanner.hashesMatch(existing.hash, newHash)) {
                            MediaFile mf = buildLight(f);
                            updateInIndex(mf);
                            manifest.put(path, new ManifestEntry(size, newHash));
                            if (listener != null) listener.onFileChanged(mf);
                        }
                    }
                }

                // 2. Build complete on-disk set AFTER the loop
                Set<String> onDisk = new HashSet<>();
                for (File f : files) {
                    if (!f.isDirectory()) onDisk.add(f.getAbsolutePath());
                }

                // 3. Remove stale entries
                String normalizedFolder = folderPath.endsWith("/") ? folderPath : folderPath + "/";
                List<String> toRemove = new ArrayList<>();
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
            } finally {
                scanning.set(false);
            }
        });
    }

    // ── Clean rescan (simpler, removes all ghosts then adds new) ─────────────

    public void rescanClean(String folderPath) {
        if (!scanning.compareAndSet(false, true)) return;

        executor.submit(() -> {
            try {
                File folder = new File(folderPath);
                if (!folder.exists()) return;

                File[] files = folder.listFiles();
                if (files == null) return;

                Set<String> onDisk = new HashSet<>();
                for (File f : files) {
                    if (!f.isDirectory()) onDisk.add(f.getAbsolutePath());
                }

                String normalizedFolder = folderPath.endsWith("/") ? folderPath : folderPath + "/";
                List<String> toRemove = new ArrayList<>();
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

                for (File f : files) {
                    if (f.isDirectory()) continue;
                    if (!manifest.containsKey(f.getAbsolutePath())) {
                        MediaFile mf = buildLight(f);
                        if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                            addToIndex(mf);
                            byte[] hash = HashScanner.partialHash(f.getAbsolutePath());
                            manifest.put(f.getAbsolutePath(), new ManifestEntry(f.length(), hash));
                            if (listener != null) listener.onFileFound(mf);
                        }
                    }
                }

                if (listener != null) {
                    listener.onScanComplete(new ArrayList<>(index));
                }
            } finally {
                scanning.set(false);
            }
        });
    }

    // ── Full reset ────────────────────────────────────────────────────────────

    public void fullReset(List<String> folders) {
        synchronized (index) { index.clear(); }
        manifest.clear();
        scanning.set(false);
        for (String folder : folders) {
            scanFolder(folder);
        }
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

    // ── Build helpers ─────────────────────────────────────────────────────────

    private MediaFile buildLight(File f) {
        MediaFile mf = new MediaFile(f.getAbsolutePath(), f.length());
        mf.setDateAdded(f.lastModified());

        List<String> existingTags = XmpReader.readTags(f.getAbsolutePath());
        for (String tag : existingTags) mf.addTag(tag);

        readDimensions(mf);

        return mf;
    }

    private void readDimensions(MediaFile mf) {
        String lower = mf.getPath().toLowerCase();
        try {
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".webp")
                    || lower.endsWith(".bmp") || lower.endsWith(".gif")) {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(mf.getPath(), opts);
                mf.setWidth(opts.outWidth);
                mf.setHeight(opts.outHeight);
            } else if (lower.endsWith(".mp4") || lower.endsWith(".3gp")
                    || lower.endsWith(".mkv") || lower.endsWith(".mov")
                    || lower.endsWith(".avi") || lower.endsWith(".webm")) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(mf.getPath());
                    String w = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (w != null) mf.setWidth(Integer.parseInt(w));
                    if (h != null) mf.setHeight(Integer.parseInt(h));
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Index helpers (thread-safe) ──────────────────────────────────────────

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

    // ── Query helpers ─────────────────────────────────────────────────────────

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
