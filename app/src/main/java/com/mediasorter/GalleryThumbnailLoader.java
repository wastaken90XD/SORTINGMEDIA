package com.mediasorter;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thumbnail loader dedicated to gallery cells.
 *
 * It deliberately does not share the normal list thumbnail cache: gallery
 * cells have a real, measured size and their lifetime is tied to RecyclerView
 * attachment.  That makes the memory rule (visible cells plus one row on each
 * side) explicit and keeps this feature easy to remove without changing the
 * existing thumbnail path.
 */
public class GalleryThumbnailLoader {

    private static final String TAG = "GalleryThumbnail";
    private static final long MIN_HEAP_BYTES = 40L * 1024L * 1024L;
    private static final long NO_ANIMATION_HEAP_BYTES = 30L * 1024L * 1024L;

    public static final int QUALITY_LOW = 0;
    public static final int QUALITY_MEDIUM = 1;
    public static final int QUALITY_HIGH = 2;

    public interface Callback {
        void onGalleryThumbnailReady(String path);
        void onGalleryThumbnailFailed(String path);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;
    private volatile boolean scrollSuspended;
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Bitmap> retained = new HashMap<>();
    private final Set<String> allowedPaths = new HashSet<>();
    private final Set<String> visiblePaths = new HashSet<>();
    private final Set<String> dragPaths = new HashSet<>();
    private final Set<String> failedPaths = new HashSet<>();
    private final Object cacheLock = new Object();

    private final Callback callback;
    private final SharedPreferences prefs;
    private volatile int quality = QUALITY_HIGH;
    private volatile boolean lowMemoryDevice;
    private volatile boolean animate = true;
    private volatile boolean memoryWarningLogged;

    public GalleryThumbnailLoader(Callback callback) {
        this(callback, false, null);
    }

    public GalleryThumbnailLoader(Callback callback, boolean lowMemory) {
        this(callback, lowMemory, null);
    }

    public GalleryThumbnailLoader(Callback callback, boolean lowMemory,
                                  SharedPreferences preferences) {
        this.callback = callback;
        this.prefs = preferences;
        this.executor = Executors.newFixedThreadPool(lowMemory ? 1 : 2);
    }

    public void setLowMemoryDevice(boolean low) {
        lowMemoryDevice = low;
        if (low) quality = QUALITY_LOW;
    }

    public boolean isLowMemoryDevice() { return lowMemoryDevice; }

    public void setQuality(int value) {
        if (lowMemoryDevice) {
            quality = QUALITY_LOW;
        } else {
            quality = Math.max(QUALITY_LOW, Math.min(QUALITY_HIGH, value));
        }
        clearPrecache();
    }

    public int getQuality() { return effectiveQuality(); }

    public void reduceQualityForSession() {
        int current = quality;
        quality = Math.max(QUALITY_LOW, current - 1);
        clearPrecache();
    }

    private int effectiveQuality() {
        if (lowMemoryDevice) return QUALITY_LOW;
        int configured = configuredQuality();
        return Math.min(configured, quality);
    }

    private int configuredQuality() {
        if (prefs == null) return Math.max(QUALITY_LOW, Math.min(QUALITY_HIGH, quality));
        String value = prefs.getString("gallery_thumb_quality", "Low");
        if ("High".equalsIgnoreCase(value)) return QUALITY_HIGH;
        if ("Medium".equalsIgnoreCase(value)) return QUALITY_MEDIUM;
        return QUALITY_LOW;
    }

    public void setAnimate(boolean enabled) { animate = enabled; }

    public boolean isScrollSuspended() { return scrollSuspended; }

    /** Change the decode gate without mutating bitmaps during the current draw. */
    public void setScrollSuspended(boolean suspended) {
        scrollSuspended = suspended;
    }

    /**
     * Suspend from a RecyclerView scroll callback and defer cache clearing
     * until that RecyclerView has finished its current draw pass.
     */
    public void setScrollSuspended(boolean suspended, View postTarget) {
        scrollSuspended = suspended;
        if (suspended && postTarget != null) {
            postTarget.post(new Runnable() {
                @Override public void run() {
                    if (scrollSuspended) clearAfterScroll();
                }
            });
        }
    }

    /** Must be called from a posted RecyclerView callback after a frame. */
    public void clearAfterScroll() {
        cancelPendingDecodes();
    }

    public void cancelPendingDecodes() {
        for (Future<?> task : inFlight.values()) {
            if (!task.isDone()) task.cancel(false);
        }
        inFlight.clear();
        clearPrecache();
    }

    /**
     * Replaces the set of paths the gallery is allowed to retain.  The caller
     * supplies visible cells and the immediately adjacent row only.
     */
    public void setAllowedPaths(List<String> paths, List<String> visible) {
        Set<String> nextAllowed = new HashSet<>();
        Set<String> nextVisible = new HashSet<>();
        if (paths != null) nextAllowed.addAll(paths);
        if (visible != null) nextVisible.addAll(visible);

        synchronized (cacheLock) {
            nextAllowed.addAll(dragPaths);
            allowedPaths.clear();
            allowedPaths.addAll(nextAllowed);
            visiblePaths.clear();
            visiblePaths.addAll(nextVisible);

            Iterator<Map.Entry<String, Bitmap>> it = retained.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Bitmap> entry = it.next();
                if (!allowedPaths.contains(entry.getKey())) {
                    it.remove();
                }
            }
        }

        for (Map.Entry<String, Future<?>> entry : inFlight.entrySet()) {
            if (!nextAllowed.contains(entry.getKey())) {
                Future<?> task = inFlight.remove(entry.getKey());
                if (task != null) task.cancel(false);
            }
        }
    }

    public void setDragPaths(List<String> paths) {
        Set<String> next = new HashSet<>();
        Set<String> removed = new HashSet<>();
        if (paths != null) next.addAll(paths);
        synchronized (cacheLock) {
            for (String old : dragPaths) {
                if (!next.contains(old)) {
                    retained.remove(old);
                    removed.add(old);
                    if (!visiblePaths.contains(old)) allowedPaths.remove(old);
                }
            }
            dragPaths.clear();
            dragPaths.addAll(next);
            allowedPaths.addAll(next);
        }
        for (String path : removed) {
            Future<?> task = inFlight.remove(path);
            if (task != null) task.cancel(false);
        }
    }

    public void clearDragPaths() {
        Set<String> cleared = new HashSet<>();
        synchronized (cacheLock) {
            for (String path : dragPaths) {
                retained.remove(path);
                cleared.add(path);
                if (!visiblePaths.contains(path)) allowedPaths.remove(path);
            }
            dragPaths.clear();
        }
        for (String path : cleared) {
            Future<?> task = inFlight.remove(path);
            if (task != null) task.cancel(false);
        }
    }

    /**
     * Called only after a cell is attached. The visibility check is repeated
     * here so RecyclerView prefetch does not start a decode for an off-screen
     * cell.
     */
    public void loadVisible(final MediaFile file, final ImageView target,
                            final int requestedWidth, final int requestedHeight) {
        loadInternal(file, target, requestedWidth, requestedHeight, false);
    }

    /** Loads a drag-window cell even while normal scroll decoding is suspended. */
    public void loadForDrag(final MediaFile file, final ImageView target,
                            final int requestedWidth, final int requestedHeight) {
        loadInternal(file, target, requestedWidth, requestedHeight, true);
    }

    private void loadInternal(final MediaFile file, final ImageView target,
                              final int requestedWidth, final int requestedHeight,
                              final boolean dragLoad) {
        if (file == null || target == null) return;
        final String path = file.getPath();
        if (path == null || path.isEmpty()) return;
        if (!dragLoad && scrollSuspended) return;
        if (!target.isAttachedToWindow()) return;
        if (!dragLoad && !isActuallyVisible(target)) return;
        if (availableHeap() < MIN_HEAP_BYTES) {
            handleLowHeap();
            return;
        }

        synchronized (cacheLock) {
            allowedPaths.add(path);
            if (!dragLoad) visiblePaths.add(path);
            if (failedPaths.contains(path)) return;
            final Bitmap cached = retained.get(path);
            if (cached != null && !cached.isRecycled()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (path.equals(target.getTag()) && target.isAttachedToWindow()) {
                            setBitmap(target, path, cached);
                        }
                    }
                });
                return;
            }
        }
        if (inFlight.containsKey(path)) return;

        final int width = Math.max(1, requestedWidth);
        final int height = Math.max(1, requestedHeight);
        Future<?> task = executor.submit(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = decodeWithRetry(file, width, height);
                finishVisible(path, target, bitmap);
            }
        });
        inFlight.put(path, task);
    }

    /** Decode a drag-window bitmap immediately, even without an attached cell. */
    public void preloadForDrag(final MediaFile file, final int requestedWidth,
                               final int requestedHeight) {
        if (file == null || file.getPath() == null) return;
        final String path = file.getPath();
        if (inFlight.containsKey(path)) return;
        synchronized (cacheLock) {
            if (!dragPaths.contains(path) || failedPaths.contains(path)
                    || retained.containsKey(path)) return;
        }
        final int width = Math.max(1, requestedWidth);
        final int height = Math.max(1, requestedHeight);
        Future<?> task = executor.submit(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = decodeWithRetry(file, width, height);
                if (bitmap != null) {
                    synchronized (cacheLock) {
                        if (dragPaths.contains(path)) putRetained(path, bitmap);
                    }
                }
                inFlight.remove(path);
            }
        });
        inFlight.put(path, task);
    }

    /** Starts an allowed one-row pre-cache load without attaching it to a view. */
    public void precache(final MediaFile file, final int requestedWidth,
                         final int requestedHeight) {
        if (file == null || file.getPath() == null) return;
        if (scrollSuspended) return;
        final String path = file.getPath();
        synchronized (cacheLock) {
            if (!allowedPaths.contains(path) || visiblePaths.contains(path)
                    || failedPaths.contains(path) || retained.containsKey(path)) return;
        }
        if (inFlight.containsKey(path)) return;
        if (availableHeap() < MIN_HEAP_BYTES) {
            handleLowHeap();
            return;
        }

        final int width = Math.max(1, requestedWidth);
        final int height = Math.max(1, requestedHeight);
        Future<?> task = executor.submit(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = decodeWithRetry(file, width, height);
                if (bitmap != null) {
                    synchronized (cacheLock) {
                        if (allowedPaths.contains(path) && !visiblePaths.contains(path)) {
                            putRetained(path, bitmap);
                        }
                    }
                }
                inFlight.remove(path);
            }
        });
        inFlight.put(path, task);
    }

    private void finishVisible(final String path, final ImageView target,
                               Bitmap bitmap) {
        inFlight.remove(path);
        if (bitmap != null) {
            synchronized (cacheLock) {
                if (allowedPaths.contains(path)) {
                    putRetained(path, bitmap);
                }
            }
        } else {
            synchronized (cacheLock) { failedPaths.add(path); }
        }

        final Bitmap result = bitmap;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (result != null && !result.isRecycled()
                        && path.equals(target.getTag())
                        && isActuallyVisible(target)) {
                    setBitmap(target, path, result);
                    if (shouldAnimate()) animateIn(target);
                    if (callback != null) callback.onGalleryThumbnailReady(path);
                } else if (result == null && path.equals(target.getTag())
                        && callback != null) {
                    callback.onGalleryThumbnailFailed(path);
                }
            }
        });
    }

    private void setBitmap(ImageView target, String path, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (!target.isAttachedToWindow()) return;
        target.setImageBitmap(bitmap);
        target.setBackgroundColor(0x00000000);
        target.setTag(path);
        target.setAlpha(1.0f);
    }

    private boolean shouldAnimate() {
        if (availableHeap() < NO_ANIMATION_HEAP_BYTES) {
            animate = false;
            return false;
        }
        return animate;
    }

    private void animateIn(final View view) {
        view.setAlpha(0.0f);
        final Handler handler = mainHandler;
        final int[] step = new int[]{0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!view.isAttachedToWindow()) return;
                step[0]++;
                view.setAlpha(Math.min(1.0f, step[0] / 4.0f));
                if (step[0] < 4 && shouldAnimate()) {
                    handler.postDelayed(this, 25L);
                } else {
                    view.setAlpha(1.0f);
                }
            }
        }, 25L);
    }

    private Bitmap decodeWithRetry(MediaFile file, int width, int height) {
        Bitmap bitmap = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (availableHeap() < MIN_HEAP_BYTES) {
                handleLowHeap();
                return null;
            }
            try {
                bitmap = decode(file, width, height, effectiveQuality());
                if (availableHeap() < MIN_HEAP_BYTES) {
                    handleLowHeap();
                    return null;
                }
                if (bitmap != null) return bitmap;
            } catch (OutOfMemoryError oom) {
                clearPrecache();
                reduceQualityForSession();
                Log.w(TAG, "Out of memory decoding " + file.getName()
                        + "; reducing gallery thumbnail quality");
            } catch (Throwable ignored) {
                return null;
            }
            clearPrecache();
            reduceQualityForSession();
        }
        return null;
    }

    private Bitmap decode(MediaFile file, int width, int height, int qualityLevel) {
        int multiplier = qualityLevel == QUALITY_HIGH ? 2
                : qualityLevel == QUALITY_MEDIUM ? 3 : 2;
        int targetWidth = Math.max(1, width * multiplier / 2);
        int targetHeight = Math.max(1, height * multiplier / 2);
        if (file.getType() == MediaFile.Type.IMAGE) {
            return decodeImage(file.getPath(), targetWidth, targetHeight, width, height);
        }
        if (file.getType() == MediaFile.Type.VIDEO) {
            return decodeVideo(file.getPath(), targetWidth, targetHeight);
        }
        return null;
    }

    private Bitmap decodeImage(String path, int targetWidth, int targetHeight,
                               int cellWidth, int cellHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight,
                targetWidth, targetHeight);
        Log.d(TAG, "decode image cell=" + cellWidth + "x" + cellHeight
                + " source=" + bounds.outWidth + "x" + bounds.outHeight
                + " sample=" + sample);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded = BitmapFactory.decodeFile(path, options);
        if (decoded == null) return null;
        if (decoded.getWidth() <= targetWidth && decoded.getHeight() <= targetHeight) {
            return decoded;
        }

        float ratio = Math.min((float) targetWidth / decoded.getWidth(),
                (float) targetHeight / decoded.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                Math.max(1, Math.round(decoded.getWidth() * ratio)),
                Math.max(1, Math.round(decoded.getHeight() * ratio)), true);
        return scaled;
    }

    private Bitmap decodeVideo(String path, int targetWidth, int targetHeight) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            retriever.setDataSource(path);
            frame = retriever.getFrameAtTime(0);
            if (frame == null) return null;
            float ratio = Math.min((float) targetWidth / frame.getWidth(),
                    (float) targetHeight / frame.getHeight());
            if (ratio >= 1.0f) {
                Bitmap result = frame;
                frame = null;
                return result;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * ratio)),
                    Math.max(1, Math.round(frame.getHeight() * ratio)), true);
            frame = null;
            return scaled;
        } catch (OutOfMemoryError oom) {
            throw oom;
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private int calculateInSampleSize(int sourceWidth, int sourceHeight,
                                      int requestedWidth, int requestedHeight) {
        int sample = 1;
        while (sourceWidth / sample > requestedWidth
                || sourceHeight / sample > requestedHeight) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private boolean isActuallyVisible(View view) {
        if (view.getVisibility() != View.VISIBLE || !view.isAttachedToWindow()) return false;
        android.graphics.Rect rect = new android.graphics.Rect();
        return view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0;
    }

    private long availableHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    private void handleLowHeap() {
        stopPendingAndClearPrecache();
        reduceQualityForSession();
        animate = false;
        if (!memoryWarningLogged) {
            memoryWarningLogged = true;
            Log.w(TAG, "Available heap below 40MB; gallery decodes paused and quality reduced");
        }
    }

    private void stopPendingAndClearPrecache() {
        for (Future<?> task : inFlight.values()) {
            if (!task.isDone()) task.cancel(false);
        }
        inFlight.clear();
        clearPrecache();
    }

    public void clearPrecache() {
        synchronized (cacheLock) {
            Iterator<Map.Entry<String, Bitmap>> it = retained.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Bitmap> entry = it.next();
                if (!visiblePaths.contains(entry.getKey())) {
                    it.remove();
                }
            }
        }
    }

    /** Called directly from GalleryAdapter.onViewRecycled/onViewDetached. */
    public void release(String path) {
        if (path == null) return;
        Future<?> task = inFlight.remove(path);
        if (task != null) task.cancel(false);
        synchronized (cacheLock) {
            allowedPaths.remove(path);
            visiblePaths.remove(path);
            Bitmap bitmap = retained.remove(path);
        }
    }

    public void clearFailed() {
        synchronized (cacheLock) { failedPaths.clear(); }
    }

    private void putRetained(String path, Bitmap bitmap) {
        retained.put(path, bitmap);
    }

    public void shutdown() {
        for (Future<?> task : inFlight.values()) {
            if (!task.isDone()) task.cancel(false);
        }
        inFlight.clear();
        executor.shutdownNow();
        synchronized (cacheLock) {
            retained.clear();
            allowedPaths.clear();
            visiblePaths.clear();
        }
    }
}
