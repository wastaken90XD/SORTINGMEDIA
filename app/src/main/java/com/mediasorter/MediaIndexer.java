package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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

    // Single thread for ALL indexing operations – serialized, no race
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, ManifestEntry> manifest = new ConcurrentHashMap<>();
    private final List<MediaFile> index = new ArrayList<>();
    private IndexListener listener;
    private Context appContext;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final LinkedList<String> folderQueue = new LinkedList<>();

    // Hash disk cache — persists hashes across app restarts
    private static final String HASH_PREFS = "hash_cache_prefs";
    private SharedPreferences hashPrefs;

    public void setListener(IndexListener l) { this.listener = l; }
    public boolean isScanning()              { return scanning.get(); }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.hashPrefs = appContext.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE);
        loadHashCache();
    }

    // ── Hash disk cache ──────────────────────────────────────────────────────

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
        try {
            hashPrefs.edit().putString(path, size + "|" + lastModified + "|" + hex).apply();
        } catch (Exception ignored) {}
    }

    private void removePersistedHash(String path) {
        if (hashPrefs == null) return;
        try { hashPrefs.edit().remove(path).apply(); } catch (Exception ignored) {}
    }

    private byte[] getOrComputeHash(String path, long size, long lastModified) {
        ManifestEntry existing = manifest.get(path);
        if (existing != null && existing.size == size
                && existing.lastModified == lastModified && existing.hash != null) {
            return existing.hash;
        }
        return HashScanner.partialHash(path);
    }

    // ── Public API – queue based, never drops folders ───────────────────────

    public void scanFolders(List<String> folders) {
        if (folders == null || folders.isEmpty()) return;
        synchronized (folderQueue) {
            // Avoid duplicate entries but keep order
            for (String f : folders) {
                if (f != null && !folderQueue.contains(f)) {
                    folderQueue.add(f);
                }
            }
        }
        scheduleNext();
    }

    public void scanFolder(String folderPath) {
        if (folderPath == null) return;
        synchronized (folderQueue) {
            if (!folderQueue.contains(folderPath)) {
                folderQueue.add(folderPath);
            }
        }
        scheduleNext();
    }

    /**
     * Try to start next scan from queue if not already scanning.
     * This is the only place that sets scanning=true and submits a full scan.
     */
    private void scheduleNext() {
        if (scanning.get()) return; // busy – will be rescheduled when current finishes

        String next = null;
        synchronized (folderQueue) {
            if (!folderQueue.isEmpty()) {
                next = folderQueue.poll();
            }
        }
        if (next == null) return;

        scanning.set(true);
        final String folderToScan = next;
        scanExecutor.submit(() -> {
            try {
                doFullScan(folderToScan);
            } catch (Throwable t) {
                t.printStackTrace();
                try {
                    // Ensure we still notify completion to unblock UI
                    if (listener != null) {
                        listener.onScanComplete(new ArrayList<>(getIndex()));
                    }
                } catch (Exception ignored) {}
            } finally {
                scanning.set(false);
                // Schedule next in queue on same executor thread to avoid stack overflow
                // post a new check
                scheduleNext();
            }
        });
    }

    // ── Full scan (internal, runs inside scanExecutor) ──────────────────────

    private void doFullScan(String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) return;

        File[] files = folder.listFiles();
        if (files == null) return;

        // Count eligible for progress
        int totalMedia = 0;
        for (File f : files) {
            if (!f.isDirectory() && isMediaFile(f.getName())) totalMedia++;
        }

        List<MediaFile> page = new ArrayList<>();
        int scanned = 0;

        for (File f : files) {
            if (f.isDirectory()) continue;
            if (!isMediaFile(f.getName())) continue;

            String absPath = f.getAbsolutePath();
            long size = f.length();
            long mod = f.lastModified();

            // FIX: manifest alone must NOT cause skip. If index doesn't contain file,
            // we must re-add (recovers from previous crash where manifest was written
            // but index.addAll never happened).
            ManifestEntry existingManifest = manifest.get(absPath);
            MediaFile existingIndex = findInIndex(absPath);

            if (existingIndex != null && existingManifest != null
                    && existingManifest.size == size
                    && existingManifest.lastModified == mod) {
                // Already indexed and up to date – skip
                continue;
            }

            try {
                MediaFile mf = buildLight(f);
                if (mf.getType() == MediaFile.Type.UNSUPPORTED) continue;

                scanned++;
                if (listener != null) {
                    try { listener.onScanProgress(scanned, totalMedia, f.getName()); }
                    catch (Exception ignored) {}
                }

                byte[] hash = getOrComputeHash(absPath, size, mod);
                // Update both index and manifest atomically from this thread's perspective
                if (existingIndex != null) {
                    updateInIndex(mf);
                } else {
                    addToIndex(mf);
                }
                manifest.put(absPath, new ManifestEntry(size, mod, hash));
                persistHash(absPath, size, mod, hash);

                page.add(mf);

                if (page.size() >= PAGE_SIZE) {
                    final List<MediaFile> batch = new ArrayList<>(page);
                    if (listener != null) {
                        try { listener.onPageLoaded(batch); } catch (Exception ignored) {}
                    }
                    page.clear();
                    try { Thread.sleep(PAGE_DELAY_MS); } catch (InterruptedException ignored) {}
                }
            } catch (Throwable t) {
                t.printStackTrace();
                // Continue with next file – don't abort whole folder because one file failed
            }
        }

        if (!page.isEmpty() && listener != null) {
            try { listener.onPageLoaded(new ArrayList<>(page)); } catch (Exception ignored) {}
        }

        if (listener != null) {
            try { listener.onScanComplete(new ArrayList<>(getIndex())); }
            catch (Exception ignored) {}
        }
    }

    private boolean isMediaFile(String name) {
        String n = name.toLowerCase();
        return n.matches(".*\\.(jpg|jpeg|png|bmp|webp|gif)")
                || n.matches(".*\\.(mp4|3gp|avi|mkv|mov|webm)");
    }

    // ── Rescan (lightweight) – serialized through same executor, never dropped ─

    public void rescan(String folderPath) {
        if (folderPath == null) return;
        scanExecutor.submit(() -> {
            // If a full scan is queued, we still run rescan; set scanning flag for UI
            boolean prev = scanning.getAndSet(true);
            try {
                doRescan(folderPath);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                scanning.set(false);
                scheduleNext();
            }
        });
    }

    private void doRescan(String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files == null) return;

        // 1. New / changed / recovered (manifest but not in index) files
        for (File f : files) {
            if (f.isDirectory()) continue;
            if (!isMediaFile(f.getName())) continue;

            String path = f.getAbsolutePath();
            long size = f.length();
            long mod = f.lastModified();
            ManifestEntry existing = manifest.get(path);
            boolean inIndex = isInIndex(path);

            boolean needsAdd = (existing == null)
                    || (existing.size != size || existing.lastModified != mod)
                    || !inIndex; // RECOVERY: was in manifest but missing from index

            if (needsAdd) {
                try {
                    MediaFile mf = buildLight(f);
                    if (mf.getType() == MediaFile.Type.UNSUPPORTED) continue;

                    if (inIndex) {
                        updateInIndex(mf);
                        if (listener != null) listener.onFileChanged(mf);
                    } else {
                        addToIndex(mf);
                        if (listener != null) listener.onFileFound(mf);
                    }

                    byte[] hash;
                    if (existing != null && existing.size == size && existing.lastModified == mod && existing.hash != null) {
                        hash = existing.hash;
                    } else {
                        hash = HashScanner.partialHash(path);
                    }
                    manifest.put(path, new ManifestEntry(size, mod, hash));
                    persistHash(path, size, mod, hash);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } else {
                if (existing.hash == null) {
                    byte[] hash = HashScanner.partialHash(path);
                    manifest.put(path, new ManifestEntry(size, mod, hash));
                    persistHash(path, size, mod, hash);
                }
            }
        }

        // 2. Deleted files
        Set<String> onDisk = new HashSet<>();
        for (File f : files) {
            if (!f.isDirectory()) onDisk.add(f.getAbsolutePath());
        }

        String normalizedFolder = folderPath.endsWith("/") ? folderPath : folderPath + "/";
        List<String> toRemove = new ArrayList<>();
        synchronized (index) {
            for (MediaFile mf : index) {
                if (mf.getPath().startsWith(normalizedFolder) && !onDisk.contains(mf.getPath())) {
                    toRemove.add(mf.getPath());
                }
            }
        }
        for (String path : toRemove) {
            removeFromIndex(path);
            removePersistedHash(path);
            if (listener != null) {
                try { listener.onFileRemoved(path); } catch (Exception ignored) {}
            }
        }
    }

    // ── Clean rescan ──────────────────────────────────────────────────────────

    public void rescanClean(String folderPath) {
        if (folderPath == null) return;
        scanExecutor.submit(() -> {
            scanning.getAndSet(true);
            try {
                doRescanClean(folderPath);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                scanning.set(false);
                scheduleNext();
            }
        });
    }

    private void doRescanClean(String folderPath) {
        File folder = new File(folderPath);
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files == null) return;

        Set<String> onDisk = new HashSet<>();
        for (File f : files) {
            if (!f.isDirectory()) onDisk.add(f.getAbsolutePath());
        }

        String normalizedFolder = folderPath.endsWith("/") ? folderPath : folderPath + "/";

        // Remove stale
        List<String> toRemove = new ArrayList<>();
        synchronized (index) {
            for (MediaFile mf : index) {
                if (mf.getPath().startsWith(normalizedFolder) && !onDisk.contains(mf.getPath())) {
                    toRemove.add(mf.getPath());
                }
            }
        }
        for (String path : toRemove) {
            removeFromIndex(path);
            removePersistedHash(path);
            if (listener != null) {
                try { listener.onFileRemoved(path); } catch (Exception ignored) {}
            }
        }

        // Add missing – recovery fix: check both manifest AND index
        for (File f : files) {
            if (f.isDirectory()) continue;
            if (!isMediaFile(f.getName())) continue;
            String abs = f.getAbsolutePath();
            boolean inIndex = isInIndex(abs);
            // If not in index, force re-add even if manifest says it exists
            if (!inIndex || !manifest.containsKey(abs)) {
                try {
                    MediaFile mf = buildLight(f);
                    if (mf.getType() == MediaFile.Type.UNSUPPORTED) continue;
                    addToIndex(mf);
                    long size = f.length();
                    long mod = f.lastModified();
                    byte[] hash = getOrComputeHash(abs, size, mod);
                    manifest.put(abs, new ManifestEntry(size, mod, hash));
                    persistHash(abs, size, mod, hash);
                    if (listener != null) listener.onFileFound(mf);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        if (listener != null) {
            try { listener.onScanComplete(new ArrayList<>(getIndex())); }
            catch (Exception ignored) {}
        }
    }

    // ── Full reset (clears corrupted state) ───────────────────────────────────

    public void fullReset(List<String> folders) {
        // Stop queue
        synchronized (folderQueue) { folderQueue.clear(); }
        scanExecutor.submit(() -> {
            scanning.set(true);
            try {
                synchronized (index) { index.clear(); }
                manifest.clear();
                if (hashPrefs != null) {
                    try { hashPrefs.edit().clear().apply(); } catch (Exception ignored) {}
                }
            } finally {
                scanning.set(false);
            }
            // Re-queue folders after clearing
            if (folders != null) {
                synchronized (folderQueue) {
                    folderQueue.addAll(folders);
                }
                scheduleNext();
            }
        });
    }

    /**
     * Force repair a specific folder that got stuck due to manifest/index desync.
     * Clears manifest entries for that folder and forces re-scan.
     */
    public void repairFolder(String folderPath) {
        if (folderPath == null) return;
        scanExecutor.submit(() -> {
            scanning.set(true);
            try {
                String norm = folderPath.endsWith("/") ? folderPath : folderPath + "/";
                // Remove manifest entries for this folder
                List<String> keysToRemove = new ArrayList<>();
                for (String key : manifest.keySet()) {
                    if (key.startsWith(norm)) keysToRemove.add(key);
                }
                for (String k : keysToRemove) {
                    manifest.remove(k);
                    removePersistedHash(k);
                }
                // Remove index entries for this folder (will be re-added)
                List<String> idxToRemove = new ArrayList<>();
                synchronized (index) {
                    for (MediaFile mf : index) {
                        if (mf.getPath().startsWith(norm)) idxToRemove.add(mf.getPath());
                    }
                }
                for (String p : idxToRemove) removeFromIndex(p);

                // Now do a clean scan
                doFullScan(folderPath);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                scanning.set(false);
                scheduleNext();
            }
        });
    }

    // ── Delete file ───────────────────────────────────────────────────────────

    public boolean deleteFile(String path) {
        File f = new File(path);
        boolean deleted = f.exists() && f.delete();
        if (deleted) {
            removeFromIndex(path);
            removePersistedHash(path);
            if (listener != null) {
                try { listener.onFileRemoved(path); } catch (Exception ignored) {}
            }
        }
        return deleted;
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    private MediaFile buildLight(File f) {
        MediaFile mf = new MediaFile(f.getAbsolutePath(), f.length());
        mf.setDateAdded(f.lastModified());
        try {
            List<String> existingTags = XmpReader.readTags(f.getAbsolutePath());
            for (String tag : existingTags) mf.addTag(tag);
        } catch (Exception ignored) {}
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
        synchronized (index) {
            // Avoid duplicates
            for (MediaFile existing : index) {
                if (existing.getPath().equals(mf.getPath())) return;
            }
            index.add(mf);
        }
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

    private MediaFile findInIndex(String path) {
        synchronized (index) {
            for (MediaFile mf : index) {
                if (mf.getPath().equals(path)) return mf;
            }
        }
        return null;
    }

    private boolean isInIndex(String path) {
        return findInIndex(path) != null;
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
