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

    private static final int PAGE_SIZE     = 50;
    private static final int PAGE_DELAY_MS = 100;

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

    // ── Full scan — throttled, flat, no hashing ───────────────────────────────

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

                // Skip already indexed files
                if (manifest.containsKey(f.getAbsolutePath())) continue;

                MediaFile mf = buildLight(f);
                if (mf.getType() != MediaFile.Type.UNSUPPORTED) {
                    page.add(mf);
                    allFound.add(mf);

                    if (page.size() >= PAGE_SIZE) {
                        emitPage(new ArrayList<>(page));
                        page.clear();

                        // Throttle — give UI thread breathing room
                        try { Thread.sleep(PAGE_DELAY_MS); }
                        catch (InterruptedException ignored) {}
                    }
                }
            }

            // Emit remaining
            if (!page.isEmpty()) emitPage(new ArrayList<>(page));

            synchronized (index) { index.addAll(allFound); }

            scanning = false;

            if (listener != null) {
                listener.onScanComplete(new ArrayList<>(index));
            }
        });
    }

    private void emitPage(List<MediaFile> page) {
        if (listener == null) return;
        for (MediaFile f : page) listener.onFileFound(f);
        listener.onPageLoaded(page);
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
                    // Size same — partial hash check
                    byte[] newHash = HashScanner.partialHash(path);
                    if (!HashScanner.hashesMatch(existing.hash, newHash)) {
                        MediaFile mf = buildLight(f);
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

    // ── Full reset + rescan ───────────────────────────────────────────────────

    public void fullReset(List<String> folders) {
        executor.submit(() -> {
            synchronized (index) { index.clear(); }
            manifest.clear();
            scanning = false;
            for (String folder : folders) {
                scanFolder(folder);
            }
        });
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private MediaFile buildLight(File f) {
        MediaFile mf = new MediaFile(f.getAbsolutePath(), f.length());
        mf.setDateAdded(f.lastModified());
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
            index.removeIf(mf -> mf.getPath().equals(path));
        }
        manifest.remove(path);
    }

    public List<MediaFile> getIndex() {
        synchronized (index) { return new ArrayList<>(index); }
    }
}
