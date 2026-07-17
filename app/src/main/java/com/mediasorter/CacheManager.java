package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class CacheManager {

    private static final String PREFS         = "cache_prefs";
    private static final String KEY_LIMIT_MB  = "cache_limit_mb";
    private static final int    DEFAULT_LIMIT = 100; // MB
    private static final float  WARN_RATIO    = 0.8f;

    private final Context context;
    private final File    cacheDir;

    public CacheManager(Context context) {
        this.context  = context;
        this.cacheDir = new File(context.getCacheDir(), "media_thumbs");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    // ── Size ──────────────────────────────────────────────────────────────────

    public long getCacheSizeBytes() {
        return folderSize(cacheDir);
    }

    public long getCacheLimitBytes() {
        return (long) getLimitMB() * 1024 * 1024;
    }

    public int getLimitMB() {
        return getPrefs().getInt(KEY_LIMIT_MB, DEFAULT_LIMIT);
    }

    public void setLimitMB(int mb) {
        getPrefs().edit().putInt(KEY_LIMIT_MB, mb).apply();
    }

    public boolean isAboveWarning() {
        return getCacheSizeBytes() > (getCacheLimitBytes() * WARN_RATIO);
    }

    public boolean isAboveLimit() {
        return getCacheSizeBytes() > getCacheLimitBytes();
    }

    // ── LRU eviction ─────────────────────────────────────────────────────────

    public void evictIfNeeded() {
        if (!isAboveLimit()) return;

        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) return;

        // Sort by last accessed — oldest first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });

        long target = getCacheLimitBytes();
        long currentSize = getCacheSizeBytes();
        for (File f : files) {
            if (currentSize <= target) break;
            long removed = f.isDirectory() ? 0 : f.length();
            if (f.delete()) {
                currentSize -= removed;
            }
        }
    }

    // ── Manual clear ─────────────────────────────────────────────────────────

    public void clearAll() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    // ── Thumbnail paths ───────────────────────────────────────────────────────

    public File getThumbnailFile(String filePath) {
        String key = String.valueOf(filePath.hashCode());
        return new File(cacheDir, key + ".jpg");
    }

    public boolean hasThumbnail(String filePath) {
        return getThumbnailFile(filePath).exists();
    }

    public void invalidateThumbnail(String filePath) {
        File thumb = getThumbnailFile(filePath);
        if (thumb.exists()) thumb.delete();
    }

    // ── Formatted size ────────────────────────────────────────────────────────

    public String getFormattedCacheSize() {
        long bytes = getCacheSizeBytes();
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long folderSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            total += f.isDirectory() ? folderSize(f) : f.length();
        }
        return total;
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
