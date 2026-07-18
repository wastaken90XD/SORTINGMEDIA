package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThumbnailLoader {

    private static final String PREFS           = "thumb_prefs";
    private static final String KEY_QUALITY     = "thumb_quality";
    private static final String KEY_MAX_BYTES   = "thumb_max_bytes";

    public static final int QUALITY_LOW    = 128;
    public static final int QUALITY_MEDIUM = 256;
    public static final int QUALITY_HIGH   = 512;

    // Default: 1/8 of the device's max heap – safe for low‑RAM phones
    private static final long DEFAULT_MAX_BYTES = Runtime.getRuntime().maxMemory() / 8;

    private final Context           context;
    private final SharedPreferences prefs;
    private final CacheManager      diskCache;
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService   executor    = Executors.newFixedThreadPool(2);

    // LRU bitmap cache with byte budget
    private final LinkedHashMap<String, Bitmap> memCache =
        new LinkedHashMap<String, Bitmap>(16, 0.75f, true);
    private long currentBytes = 0;

    private int maxCount = 100;

    // Thread‑safe map of in‑flight loads
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();

    public ThumbnailLoader(Context context) {
        this.context   = context;
        this.prefs     = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.diskCache = new CacheManager(context);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    public int getQuality() {
        return prefs.getInt(KEY_QUALITY, QUALITY_MEDIUM);
    }

    public void setQuality(int size) {
        prefs.edit().putInt(KEY_QUALITY, size).apply();
        clearMemCache();
    }

    public long getMaxBytes() {
        return prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES);
    }

    public void setMaxBytes(long bytes) {
        prefs.edit().putLong(KEY_MAX_BYTES, bytes).apply();
        evictToLimit();
    }

    public int getMaxMB() {
        return (int)(getMaxBytes() / (1024 * 1024));
    }

    public void setMaxMB(int mb) {
        setMaxBytes((long) mb * 1024 * 1024);
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = Math.max(10, maxCount);
        synchronized (memCache) {
            while (memCache.size() > this.maxCount) {
                removeOldest();
            }
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public void load(MediaFile file, ImageView target) {
        String path = file.getPath();
        target.setTag(path);

        // Memory hit
        synchronized (memCache) {
            Bitmap cached = memCache.get(path);
            if (cached != null) {
                target.setImageBitmap(cached);
                return;
            }
        }

        target.setImageBitmap(null);
        target.setBackgroundColor(0xFF1A1A1A);

        cancel(path);

        Future<?> task = executor.submit(() -> {
            Bitmap bmp     = null;
            int    quality = getQuality();

            // Disk cache
            File thumbFile = diskCache.getThumbnailFile(path);
            if (thumbFile.exists()) {
                bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
            }

            // Generate
            if (bmp == null) {
                bmp = generate(file, quality);
                if (bmp != null) saveToDisk(bmp, thumbFile);
            }

            if (bmp != null) putInMemCache(path, bmp);

            final Bitmap finalBmp = bmp;
            mainHandler.post(() -> {
                if (path.equals(target.getTag())) {
                    if (finalBmp != null) {
                        target.setImageBitmap(finalBmp);
                        target.setBackgroundColor(0x00000000);
                    } else {
                        target.setImageResource(
                            android.R.drawable.ic_menu_report_image);
                    }
                }
            });

            inFlight.remove(path);
        });

        inFlight.put(path, task);
    }

    // ── Memory cache ─────────────────────────────────────────────────────────

    private void putInMemCache(String path, Bitmap bmp) {
        long bmpBytes = bmp.getByteCount();
        synchronized (memCache) {
            Bitmap old = memCache.remove(path);
            if (old != null) {
                currentBytes -= old.getByteCount();
                // Do NOT recycle here – it might still be displayed elsewhere
            }

            // Evict by count first
            while (memCache.size() >= maxCount && !memCache.isEmpty()) {
                removeOldest();
            }

            // Evict by byte budget
            long limit = getMaxBytes();
            while (currentBytes + bmpBytes > limit && !memCache.isEmpty()) {
                removeOldest();
            }

            memCache.put(path, bmp);
            currentBytes += bmpBytes;
        }
    }

    /** Must be called inside synchronized(memCache).  Recycles the evicted bitmap. */
    private void removeOldest() {
        Iterator<Map.Entry<String, Bitmap>> it = memCache.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, Bitmap> oldest = it.next();
            Bitmap bmp = oldest.getValue();
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();                     // ← release native memory
            }
            currentBytes -= bmp.getByteCount();
            it.remove();
        }
    }

    private void evictToLimit() {
        synchronized (memCache) {
            long limit = getMaxBytes();
            while (currentBytes > limit && !memCache.isEmpty()) {
                removeOldest();
            }
            while (memCache.size() > maxCount) {
                removeOldest();
            }
        }
    }

    /**
     * Pre-cache thumbnails for adjacent files (e.g., previous and next)
     * so they're ready instantly when the user swipes.
     */
    public void precache(List<MediaFile> files) {
        if (files == null || files.isEmpty()) return;
        for (MediaFile file : files) {
            String path = file.getPath();
            // Skip if already in memory cache or in-flight
            synchronized (memCache) {
                if (memCache.containsKey(path)) continue;
            }
            if (inFlight.containsKey(path)) continue;

            Future<?> task = executor.submit(() -> {
                int quality = getQuality();
                File thumbFile = diskCache.getThumbnailFile(path);
                Bitmap bmp = null;

                if (thumbFile.exists()) {
                    bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                }
                if (bmp == null) {
                    bmp = generate(file, quality);
                    if (bmp != null) saveToDisk(bmp, thumbFile);
                }
                if (bmp != null) putInMemCache(path, bmp);
                inFlight.remove(path);
            });
            inFlight.put(path, task);
        }
    }

    public void clearMemCache() {
        synchronized (memCache) {
            for (Bitmap bmp : memCache.values()) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
            memCache.clear();
            currentBytes = 0;
        }
    }

    // Clear thumbnails for files outside current window
    public void evictOutsideWindow(List<String> windowPaths) {
        synchronized (memCache) {
            Iterator<Map.Entry<String, Bitmap>> it = memCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Bitmap> entry = it.next();
                if (!windowPaths.contains(entry.getKey())) {
                    Bitmap bmp = entry.getValue();
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    currentBytes -= bmp.getByteCount();
                    it.remove();
                }
            }
        }
    }

    public String getMemCacheSize() {
        long mb = currentBytes / (1024 * 1024);
        return mb + " MB / " + getMaxMB() + " MB";
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    public void cancel(String path) {
        Future<?> f = inFlight.remove(path);
        if (f != null && !f.isDone()) f.cancel(false);
    }

    public void cancelAll() {
        for (Future<?> f : inFlight.values()) {
            if (!f.isDone()) f.cancel(false);
        }
        inFlight.clear();
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private Bitmap generate(MediaFile file, int size) {
        try {
            switch (file.getType()) {
                case IMAGE: return generateImage(file.getPath(), size);
                case VIDEO: return generateVideo(file.getPath());
                default:    return null;
            }
        } catch (OutOfMemoryError e) {
            // Single bad file won't crash the whole app
            return null;
        }
    }

    private Bitmap generateImage(String path, int size) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            opts.inSampleSize       = calcSampleSize(opts, size, size);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap generateVideo(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            return r.getFrameAtTime(0);
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void saveToDisk(Bitmap bmp, File file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, fos);
        } catch (Exception ignored) {}
    }

    private int calcSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int inW = opts.outWidth;
        int inH = opts.outHeight;
        int s   = 1;
        if (inH > reqH || inW > reqW) {
            int hH = inH / 2, hW = inW / 2;
            while ((hH / s) >= reqH && (hW / s) >= reqW) s *= 2;
        }
        return s;
    }

    public void shutdown() {
        cancelAll();
        executor.shutdown();
    }
}
