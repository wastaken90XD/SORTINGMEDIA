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

    // Default: 1/8 of the device's max heap – safe for low-RAM phones
    private static final long DEFAULT_MAX_BYTES = Runtime.getRuntime().maxMemory() / 8;

    private final Context           context;
    private final SharedPreferences prefs;
    private final CacheManager      diskCache;
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService   executor    = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

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
        Object raw = prefs.getAll().get(KEY_MAX_BYTES);
        if (raw instanceof Long) return ((Long) raw).longValue();
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        return DEFAULT_MAX_BYTES;
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
            if (cached != null && !cached.isRecycled()) {
                target.setImageBitmap(cached);
                return;
            }
        }

        target.setImageBitmap(null);
        target.setBackgroundColor(0xFF1A1A1A);

        cancel(path);

        Future<?> task = executor.submit(new Runnable() {
            @Override public void run() {
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
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (path.equals(target.getTag())) {
                            if (finalBmp != null && !finalBmp.isRecycled()) {
                                target.setImageBitmap(finalBmp);
                                target.setBackgroundColor(0x00000000);
                            } else {
                                target.setImageResource(
                                    android.R.drawable.ic_menu_report_image);
                            }
                        }
                    }
                });

                inFlight.remove(path);
            }
        });

        inFlight.put(path, task);
    }

    /**
     * Decode an image or a midpoint video frame for a fixed preview cell.
     * Unlike the legacy load() path this does not use the disk thumbnail size:
     * the requested cell dimensions are part of the decode decision.  The
     * request token on the ImageView gives Compare latest-wins behaviour when
     * a question is answered while a decode is still running.
     */
    public void loadAtSize(final MediaFile file, final ImageView target,
                           final int requestedWidth, final int requestedHeight) {
        if (file == null || target == null || file.getPath() == null) return;
        final String path = file.getPath();
        final String token = "compare-view:" + path + ":" + System.nanoTime();
        target.setTag(token);
        target.setImageBitmap(null);
        final int width = Math.max(1, requestedWidth);
        final int height = Math.max(1, requestedHeight);
        final String flightKey = "compare-view:" + path;
        cancel(flightKey);
        Future<?> task;
        try {
            task = executor.submit(new Runnable() {
                @Override public void run() {
                    Bitmap bitmap = generateAtSize(file, width, height);
                    if (bitmap != null) putInMemCache(path, bitmap);
                    final Bitmap result = bitmap;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (!token.equals(target.getTag())) return;
                            if (result != null && !result.isRecycled()) {
                                target.setImageBitmap(result);
                                target.setBackgroundColor(0x00000000);
                            } else {
                                target.setImageResource(android.R.drawable.ic_menu_report_image);
                            }
                        }
                    });
                    inFlight.remove(flightKey);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return;
        }
        inFlight.put(flightKey, task);
    }

    /** Preloads a fixed-size Compare cell without requiring an attached view. */
    public void precacheAtSize(final MediaFile file, final int requestedWidth,
                               final int requestedHeight) {
        if (file == null || file.getPath() == null) return;
        final String path = file.getPath();
        final String flightKey = "compare-preload:" + path;
        if (inFlight.containsKey(flightKey)) return;
        final int width = Math.max(1, requestedWidth);
        final int height = Math.max(1, requestedHeight);
        Future<?> task;
        try {
            task = executor.submit(new Runnable() {
                @Override public void run() {
                    Bitmap bitmap = generateAtSize(file, width, height);
                    if (bitmap != null) putInMemCache(path, bitmap);
                    inFlight.remove(flightKey);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return;
        }
        inFlight.put(flightKey, task);
    }

    /**
 * Pre‑load thumbnails for the given files into the memory cache.
 * Safe to call from any thread. If the file’s thumbnail is already in memory
 * or currently being loaded, it is skipped.
 */
public void precache(List<MediaFile> files) {
    for (MediaFile file : files) {
        String path = file.getPath();
        // Already in memory? skip
        synchronized (memCache) {
            if (memCache.containsKey(path)) continue;
        }
        // Already being loaded? skip
        if (inFlight.containsKey(path)) continue;

        Future<?> task = executor.submit(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                int quality = getQuality();

                // Try disk cache first
                File thumbFile = diskCache.getThumbnailFile(path);
                if (thumbFile.exists()) {
                    bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                }

                // Generate if needed
                if (bmp == null) {
                    bmp = generate(file, quality);
                    if (bmp != null) saveToDisk(bmp, thumbFile);
                }

                // Store in memory cache (does not recycle, safe)
                if (bmp != null) putInMemCache(path, bmp);

                inFlight.remove(path);
            }
        });

        inFlight.put(path, task);
    }
}
    // ── Memory cache ─────────────────────────────────────────────────────────

    /**
     * Returns an already-loaded thumbnail without starting I/O or generation.
     *
     * This is useful for callers, such as the full-screen preview, that can
     * display a cached thumbnail while their higher-resolution image is being
     * decoded. The cache lock also preserves the LRU access ordering.
     */
    public Bitmap getCachedThumbnail(MediaFile file) {
        if (file == null) return null;

        synchronized (memCache) {
            Bitmap cached = memCache.get(file.getPath());
            return cached != null && !cached.isRecycled() ? cached : null;
        }
    }

    private void putInMemCache(String path, Bitmap bmp) {
        long bmpBytes = bmp.getByteCount();
        synchronized (memCache) {
            Bitmap old = memCache.remove(path);
            if (old != null) {
                currentBytes -= old.getByteCount();
                // Do NOT recycle old – it might still be displayed
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

    /** Must be called inside synchronized(memCache).  Does NOT recycle the evicted bitmap. */
    private void removeOldest() {
        Iterator<Map.Entry<String, Bitmap>> it = memCache.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<String, Bitmap> oldest = it.next();
            Bitmap bmp = oldest.getValue();
            currentBytes -= bmp.getByteCount();
            it.remove();
            // Do NOT recycle – the bitmap might still be in an ImageView
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

    public void clearMemCache() {
        synchronized (memCache) {
            // Never recycle here: cached bitmaps may still be set on
            // ImageViews, and drawing a recycled bitmap crashes the app.
            // Dropping the references lets GC reclaim them safely.
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
                    currentBytes -= bmp.getByteCount();
                    it.remove();
                    // Do NOT recycle – the bitmap may still be displayed
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
                case VIDEO: return generateVideo(file.getPath(), size);
                default:    return null;
            }
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap generateAtSize(MediaFile file, int requestedWidth, int requestedHeight) {
        try {
            switch (file.getType()) {
                case IMAGE:
                    return generateImageAtSize(file.getPath(), requestedWidth, requestedHeight);
                case VIDEO:
                    return generateVideoAtSize(file.getPath(), requestedWidth, requestedHeight);
                default:
                    return null;
            }
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap generateImage(String path, int size) {
        return generateImageAtSize(path, size, size);
    }

    private Bitmap generateImageAtSize(String path, int requestedWidth, int requestedHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            opts.inSampleSize = calcSampleSize(opts, requestedWidth, requestedHeight);
            opts.inJustDecodeBounds = false;
            Bitmap decoded = BitmapFactory.decodeFile(path, opts);
            return applyExifOrientation(path, decoded);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Extracts a frame and scales it down to the requested thumbnail size.
     * getFrameAtTime() returns the video's FULL-RESOLUTION frame (e.g. 26 MB
     * for a 4K clip) — the previous implementation returned it unscaled,
     * spiking the heap per video thumbnail. (getScaledFrameAtTime would be
     * ideal but is API 27+; we scale manually to stay API-21 compatible.)
     */
    private Bitmap generateVideo(String path, int size) {
        return generateVideoFrame(path, size, size, false);
    }

    private Bitmap generateVideoAtSize(String path, int requestedWidth, int requestedHeight) {
        return generateVideoFrame(path, requestedWidth, requestedHeight, true);
    }

    /** Extracts a bounded midpoint frame for Compare instead of starting at t=0. */
    private Bitmap generateVideoFrame(String path, int requestedWidth, int requestedHeight,
                                      boolean midpoint) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            r.setDataSource(path);
            long timeUs = 0L;
            if (midpoint) {
                String duration = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = 0L;
                try { durationMs = Long.parseLong(duration == null ? "0" : duration); }
                catch (NumberFormatException ignored) {}
                timeUs = Math.max(0L, durationMs * 1000L / 2L);
            }
            frame = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = frame.getWidth();
            int h = frame.getHeight();
            if (w <= 0 || h <= 0) return null;
            if (w <= requestedWidth && h <= requestedHeight) return frame;
            float ratio = Math.min((float) requestedWidth / w,
                    (float) requestedHeight / h);
            int nw = Math.max(1, Math.round(w * ratio));
            int nh = Math.max(1, Math.round(h * ratio));
            Bitmap scaled = Bitmap.createScaledBitmap(frame, nw, nh, true);
            frame = null;
            return scaled;
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            // Bitmap ownership is intentionally left to the ImageView/cache;
            // never recycle a frame that may already be displayed.
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Reads the baseline JPEG orientation without requiring the API-24
     * android.media.ExifInterface class, keeping the minSdk at 21.  Malformed
     * or non-JPEG files simply use the normal orientation.
     */
    private Bitmap applyExifOrientation(String path, Bitmap bitmap) {
        if (bitmap == null) return null;
        int orientation = readJpegOrientation(path);
        if (orientation <= 1 || orientation > 8) return bitmap;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1f, 1f); break;
            case 3: matrix.setRotate(180f); break;
            case 4: matrix.setScale(1f, -1f); break;
            case 5: matrix.setRotate(90f); matrix.postScale(-1f, 1f); break;
            case 6: matrix.setRotate(90f); break;
            case 7: matrix.setRotate(-90f); matrix.postScale(-1f, 1f); break;
            case 8: matrix.setRotate(-90f); break;
            default: return bitmap;
        }
        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    matrix, true);
        } catch (OutOfMemoryError error) {
            return bitmap;
        } catch (Exception error) {
            return bitmap;
        }
    }

    private int readJpegOrientation(String path) {
        java.io.RandomAccessFile input = null;
        try {
            java.io.File source = new java.io.File(path);
            long length = Math.min(source.length(), 1024L * 1024L);
            if (length < 4) return 1;
            byte[] bytes = new byte[(int) length];
            input = new java.io.RandomAccessFile(source, "r");
            input.readFully(bytes);
            if ((bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) return 1;
            int offset = 2;
            while (offset + 4 < bytes.length) {
                if ((bytes[offset] & 0xFF) != 0xFF) { offset++; continue; }
                int marker = bytes[offset + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) break;
                int segmentLength = readUnsignedShort(bytes, offset + 2, false);
                if (segmentLength < 2 || offset + 2 + segmentLength > bytes.length) break;
                if (marker == 0xE1 && segmentLength >= 8
                        && bytes[offset + 4] == 'E' && bytes[offset + 5] == 'x'
                        && bytes[offset + 6] == 'i' && bytes[offset + 7] == 'f'
                        && bytes[offset + 8] == 0 && bytes[offset + 9] == 0) {
                    int tiff = offset + 10;
                    boolean little = bytes[tiff] == 'I' && bytes[tiff + 1] == 'I';
                    if (!little && !(bytes[tiff] == 'M' && bytes[tiff + 1] == 'M')) return 1;
                    int ifdOffset = readInt(bytes, tiff + 4, little);
                    int ifd = tiff + ifdOffset;
                    int count = readUnsignedShort(bytes, ifd, little);
                    for (int i = 0; i < count; i++) {
                        int entry = ifd + 2 + i * 12;
                        if (entry + 12 > bytes.length) return 1;
                        if (readUnsignedShort(bytes, entry, little) == 0x0112) {
                            return readUnsignedShort(bytes, entry + 8, little);
                        }
                    }
                    return 1;
                }
                offset += 2 + segmentLength;
            }
        } catch (Exception ignored) {
            return 1;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) {}
        }
        return 1;
    }

    private int readUnsignedShort(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 1 >= data.length) return 0;
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return little ? first | (second << 8) : (first << 8) | second;
    }

    private int readInt(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 3 >= data.length) return 0;
        int a = data[offset] & 0xFF;
        int b = data[offset + 1] & 0xFF;
        int c = data[offset + 2] & 0xFF;
        int d = data[offset + 3] & 0xFF;
        return little ? a | (b << 8) | (c << 16) | (d << 24)
                : (a << 24) | (b << 16) | (c << 8) | d;
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
