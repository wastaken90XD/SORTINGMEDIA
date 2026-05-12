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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThumbnailLoader {

    private static final String PREFS         = "thumb_prefs";
    private static final String KEY_QUALITY   = "thumb_quality";
    private static final String KEY_MAX_COUNT = "thumb_max_count";

    public static final int QUALITY_LOW    = 128;
    public static final int QUALITY_MEDIUM = 256;
    public static final int QUALITY_HIGH   = 512;

    private final Context         context;
    private final SharedPreferences prefs;
    private final CacheManager    diskCache;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private       ExecutorService executor;

    // In-memory LRU bitmap cache
    private Map<String, Bitmap> memCache;

    // In-flight load tracking
    private final Map<String, Future<?>> inFlight =
        Collections.synchronizedMap(new LinkedHashMap<>());

    public ThumbnailLoader(Context context) {
        this.context   = context;
        this.prefs     = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.diskCache = new CacheManager(context);
        rebuildCache();
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    public int getQuality() {
        return prefs.getInt(KEY_QUALITY, QUALITY_MEDIUM);
    }

    public void setQuality(int size) {
        prefs.edit().putInt(KEY_QUALITY, size).apply();
        clearMemCache();
    }

    public int getMaxCount() {
        return prefs.getInt(KEY_MAX_COUNT, 50);
    }

    public void setMaxCount(int count) {
        prefs.edit().putInt(KEY_MAX_COUNT, count).apply();
        rebuildCache();
    }

    private void rebuildCache() {
        cancelAll();
        final int max = getMaxCount();
        memCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(max, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    return size() > max;
                }
            });
        executor = Executors.newFixedThreadPool(2);
    }

    private void clearMemCache() {
        memCache.clear();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public void load(MediaFile file, ImageView target) {
        String path = file.getPath();
        target.setTag(path);

        // Memory cache hit
        Bitmap cached = memCache.get(path);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setImageBitmap(null);
        target.setBackgroundColor(0xFF1A1A1A);

        cancel(path);

        Future<?> task = executor.submit(() -> {
            Bitmap bmp = null;
            int quality = getQuality();

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

            if (bmp != null) memCache.put(path, bmp);
            diskCache.evictIfNeeded();

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
        switch (file.getType()) {
            case IMAGE: return generateImage(file.getPath(), size);
            case VIDEO: return generateVideo(file.getPath());
            default:    return null;
        }
    }

    private Bitmap generateImage(String path, int size) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize       = calcSampleSize(opts, size, size);
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
        cancelAll();
        executor.shutdown();
    }
}
