package com.mediasorter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThumbnailLoader {

    private static final int THUMB_SIZE = 256;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final CacheManager    cache;

    public ThumbnailLoader(Context context) {
        this.cache = new CacheManager(context);
    }

    public void load(MediaFile file, ImageView target) {
        target.setTag(file.getPath());

        executor.submit(() -> {
            Bitmap bmp = null;

            // Check cache first
            File thumbFile = cache.getThumbnailFile(file.getPath());
            if (thumbFile.exists()) {
                bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
            }

            // Generate if not cached
            if (bmp == null) {
                bmp = generate(file);
                if (bmp != null) saveThumbnail(bmp, thumbFile);
            }

            // Evict cache if needed
            cache.evictIfNeeded();

            final Bitmap finalBmp = bmp;
            mainHandler.post(() -> {
                // Make sure view hasn't been recycled
                if (file.getPath().equals(target.getTag())) {
                    if (finalBmp != null) {
                        target.setImageBitmap(finalBmp);
                    } else {
                        target.setImageResource(android.R.drawable.ic_menu_report_image);
                    }
                }
            });
        });
    }

    private Bitmap generate(MediaFile file) {
        switch (file.getType()) {
            case IMAGE:  return generateImage(file.getPath());
            case VIDEO:  return generateVideo(file.getPath());
            case AUDIO:  return null; // no thumbnail for audio
            default:     return null;
        }
    }

    private Bitmap generateImage(String path) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        opts.inSampleSize    = calculateSampleSize(opts, THUMB_SIZE, THUMB_SIZE);
        opts.inJustDecodeBounds = false;

        return BitmapFactory.decodeFile(path, opts);
    }

    private Bitmap generateVideo(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            return retriever.getFrameAtTime(0);
        } catch (Exception e) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private void saveThumbnail(Bitmap bmp, File file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
        } catch (Exception ignored) {}
    }

    private int calculateSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int inW = opts.outWidth;
        int inH = opts.outHeight;
        int sampleSize = 1;

        if (inH > reqH || inW > reqW) {
            int halfH = inH / 2;
            int halfW = inW / 2;
            while ((halfH / sampleSize) >= reqH && (halfW / sampleSize) >= reqW) {
                sampleSize *= 2;
            }
        }
        return sampleSize;
    }
}
