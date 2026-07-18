package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        final long   lastModified;
        final byte[] hash;

        ManifestEntry(long size, long lastModified, byte[] hash) {
            this.size = size;
            this.lastModified = lastModified;
            this.hash = hash;
        }
    }

    // Thread pools: single-thread for ordered scans, cached for rescans
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService rescanExecutor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, ManifestEntry> manifest = new ConcurrentHashMap<>();
    private final List<MediaFile>               index    = new ArrayList<>();
    private       IndexListener                 listener;
    private       Context                       appContext;
    private final AtomicBoolean                 scanning = new AtomicBoolean(false);
    private final List<String> folderQueue = new ArrayList<>();

    // Hash disk cache — persists hashes across app restarts
    private static final String HASH_PREFS = "hash_cache_prefs";
    private SharedPreferences hashPrefs;

    public void setListener(IndexListener l) { this.listener = l; }
    public boolean isScanning()              { return scanning.get(); }

    /**
     * Initialize with application context for hash persistence.
     * Call once from initManagers().
     */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.hashPrefs = appContext.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE);
        loadHashCache();
    }

    // ── Hash disk cache ──────────────────────────────────────────────────────

    /**
     * Load persisted hashes into the manifest.
     * Format: "size|lastModified|hexHash" per file path.
     */
    private void loadHashCache() {
        if (hashPrefs == null) return;
        Map<String, ?> all = hashPrefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String path = entry.getKey();
            String val = entry.getValue().toString();
            String[] parts = val.split("\\|", 3);
            if (parts.length == 3) {
                try {
                    long size = Long.parseLong(parts[0]);
                    long mod = Long.parseLong(parts[1]);
                    byte[] hash = HashScanner.hashToBytes(parts[2]);
                    if (hash != null) {
                        manifest.put(path, new ManifestEntry(size, mod, hash));
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void persistHash(String path, long size, long lastModified, byte[] hash) {
        if (hashPrefs == null) return;
        String hex = HashScanner.hashToHex(hash);
        if (hex.isEmpty()) return;
        hashPrefs.edit().putString(path, size + "|" + lastModified + "|" + hex).apply();
    }

    private void removePersistedHash(String path) {
        if (hashPrefs == null) return;
        hashPrefs.edit().remove(path).apply();
    }

    /**
     * Get or compute hash for a file, using the disk cache when possible.
     * If the file's size and lastModified match the cache, skip the 64KB read.
     */
    private byte[] getOrComputeHash(String path, long size, long lastModified) {
        ManifestEntry existing = manifest.get(path);
        if (existing != null && existing.size == size
                && existing.lastModified == lastModified && existing.hash != null) {
            // Cache hit — no disk read needed
            return existing.hash;
        }
        // Cache miss — read 64KB and compute
        return HashScanner.partialHash(path);
    }

    // ── Full scan ─────────────────────────────────────────────────────────────

    public void scanFolder(String folderPath) {
    if (!scanning.compareAndSet(false, true)) return;

    scanExecutor.submit(() -> {
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
                long size = f.length();
                long mod = f.lastModified();

                ManifestEntry existing = manifest.get(absPath);
                // Skip if already in manifest with same size+mod
                if (existing != null && existing.size == size && existing.lastModified == mod) continue;

                MediaFile mf = buildLight(f);
                if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                    scanned++;
                    if (listener != null) listener.onScanProgress(scanned, totalMedia, f.getName());

                    page.add(mf);
                    allFound.add(mf);

                    // Compute hash (uses cache if size+mod match)
                    byte[] hash = getOrComputeHash(absPath, size, mod);
                    manifest.put(absPath, new ManifestEntry(size, mod, hash));
                    persistHash(absPath, size, mod, hash);

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

    // ── Lightweight rescan (uses cached thread pool, doesn't block scans) ────

    public void rescan(String folderPath) {
        if (!scanning.compareAndSet(false, true)) return;

        rescanExecutor.submit(() -> {
            try {
                File folder = new File(folderPath);
                if (!folder.exists()) return;

                File[] files = folder.listFiles();
                if (files == null) return;

                boolean changed = false;

                // 1. Check for new/changed files
                for (File f : files) {
                    if (f.isDirectory()) continue;

                    String path = f.getAbsolutePath();
                    long   size = f.length();
                    long   mod  = f.lastModified();
                    ManifestEntry existing = manifest.get(path);

                    if (existing == null) {
                        MediaFile mf = buildLight(f);
                        addToIndex(mf);
                        byte[] hash = getOrComputeHash(path, size, mod);
                        manifest.put(path, new ManifestEntry(size, mod, hash));
                        persistHash(path, size, mod, hash);
                        if (listener != null) listener.onFileFound(mf);
                        changed = true;

                    } else if (existing.size != size || existing.lastModified != mod) {
                        // File changed — recompute hash
                        MediaFile mf = buildLight(f);
                        updateInIndex(mf);
                        byte[] hash = HashScanner.partialHash(path);
                        manifest.put(path, new ManifestEntry(size, mod, hash));
                        persistHash(path, size, mod, hash);
                        if (listener != null) listener.onFileChanged(mf);
                        changed = true;

                    } else {
                        // Size + mod match — use cached hash, only recompute
                        // if hash was somehow null
                        if (existing.hash == null) {
                            byte[] hash = HashScanner.partialHash(path);
                            manifest.put(path, new ManifestEntry(size, mod, hash));
                            persistHash(path, size, mod, hash);
                        }
                        // Otherwise: no-op, hash is cached and valid
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
                    removePersistedHash(path);
                    if (listener != null) listener.onFileRemoved(path);
                    changed = true;
                }
            } finally {
                scanning.set(false);
            }
        });
    }

    // ── Clean rescan ──────────────────────────────────────────────────────────

    public void rescanClean(String folderPath) {
        if (!scanning.compareAndSet(false, true)) return;

        rescanExecutor.submit(() -> {
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
                    removePersistedHash(path);
                    if (listener != null) listener.onFileRemoved(path);
                }

                for (File f : files) {
                    if (f.isDirectory()) continue;
                    if (!manifest.containsKey(f.getAbsolutePath())) {
                        MediaFile mf = buildLight(f);
                        if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                            addToIndex(mf);
                            long size = f.length();
                            long mod = f.lastModified();
                            byte[] hash = getOrComputeHash(f.getAbsolutePath(), size, mod);
                            manifest.put(f.getAbsolutePath(), new ManifestEntry(size, mod, hash));
                            persistHash(f.getAbsolutePath(), size, mod, hash);
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
        if (hashPrefs != null) hashPrefs.edit().clear().apply();
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
            removePersistedHash(path);
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
