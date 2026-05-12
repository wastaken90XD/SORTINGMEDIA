package com.mediasorter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThumbnailLoader {

    private static final int   THUMB_SIZE   = 256;
    private static final int   CACHE_SIZE   = 100; // max bitmaps in memory

    // LRU bitmap cache — evicts oldest when full
    private final Map<String, Bitmap> memCache = Collections.synchronizedMap(
        new LinkedHashMap<String, Bitmap>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    // Track in-flight loads so we can cancel them
    private final Map<String, Future<?>> inFlight =
        Collections.synchronizedMap(new LinkedHashMap<>());

    private final ExecutorService executor    = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final CacheManager    diskCache;

    public ThumbnailLoader(android.content.Context context) {
        this.diskCache = new CacheManager(context);
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public void load(MediaFile file, ImageView target) {
        String path = file.getPath();
        target.setTag(path);

        // 1. Memory cache hit — instant
        Bitmap cached = memCache.get(path);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        // 2. Clear stale image while loading
        target.setImageBitmap(null);
        target.setBackgroundColor(0xFF1A1A1A);

        // 3. Cancel any existing load for this view
        cancel(path);

        // 4. Submit load task
        Future<?> task = executor.submit(() -> {
            Bitmap bmp = null;

            // Disk cache
            File thumbFile = diskCache.getThumbnailFile(path);
            if (thumbFile.exists()) {
                bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
            }

            // Generate if not cached
            if (bmp == null) {
                bmp = generate(file);
                if (bmp != null) saveToDisk(bmp, thumbFile);
            }

            if (bmp != null) memCache.put(path, bmp);
            diskCache.evictIfNeeded();

            final Bitmap finalBmp = bmp;
            mainHandler.post(() -> {
                // Only set if view hasn't been recycled to another file
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

    // ── Cancel ────────────────────────────────────────────────────────────────

    public void cancel(String path) {
        Future<?> existing = inFlight.remove(path);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
    }

    public void cancelAll() {
        for (Future<?> f : inFlight.values()) {
            if (!f.isDone()) f.cancel(false);
        }
        inFlight.clear();
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private Bitmap generate(MediaFile file) {
        switch (file.getType()) {
            case IMAGE: return generateImage(file.getPath());
            case VIDEO: return generateVideo(file.getPath());
            default:    return null;
        }
    }

    private Bitmap generateImage(String path) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize       = calcSampleSize(opts, THUMB_SIZE, THUMB_SIZE);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
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
        executor.shutdown();
    }
}
