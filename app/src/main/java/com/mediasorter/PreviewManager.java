package com.mediasorter;

import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.mediasorter.models.MediaFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PreviewManager {

    private final Context         context;
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private boolean     isPlaying = false;

    // Views
    private ImageView imagePreview;
    private VideoView videoPreview;
    private View      unsupportedPreview;
    private ImageView albumArt;
    private TextView  btnPlayPause;
    private TextView  detailFileName;
    private TextView  detailMeta;
    private TextView  detailPath;
    private TextView  unsupportedText;

    public PreviewManager(Context context, View previewRoot) {
        this.context = context;
        bindViews(previewRoot);
    }


    private void bindViews(View root) {
        imagePreview       = root.findViewById(R.id.imagePreview);
        videoPreview       = root.findViewById(R.id.videoPreview);
        unsupportedPreview = root.findViewById(R.id.unsupportedPreview);
        detailFileName     = root.findViewById(R.id.detailFileName);
        detailMeta         = root.findViewById(R.id.detailMeta);
        detailPath         = root.findViewById(R.id.detailPath);
        unsupportedText    = root.findViewById(R.id.unsupportedText);
}

    // ── Load file ─────────────────────────────────────────────────────────────

    public void load(MediaFile file) {
        stopMedia();
        hideAll();
        updateDetails(file);

        CodecChecker.Support support = CodecChecker.check(file);

        if (support == CodecChecker.Support.NONE) {
            showUnsupported(CodecChecker.getUnsupportedReason(file));
            return;
        }

        switch (file.getType()) {
            case IMAGE: loadImage(file); break;
            case VIDEO: loadVideo(file); break;
            default:    showUnsupported("Unsupported file type"); break;
        }
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private void loadImage(MediaFile file) {
        executor.submit(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            opts.inSampleSize    = 1;
            opts.inJustDecodeBounds = false;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);

            mainHandler.post(() -> {
                if (bmp != null) {
                    imagePreview.setImageBitmap(bmp);
                    imagePreview.setVisibility(View.VISIBLE);
                } else {
                    showUnsupported("Could not decode image");
                }
            });
        });
    }

    // ── Video ─────────────────────────────────────────────────────────────────

   private void loadVideo(MediaFile file) {
    videoPreview.setVisibility(View.VISIBLE);
    android.widget.MediaController mc =
        new android.widget.MediaController(context);
    mc.setAnchorView(videoPreview);
    videoPreview.setMediaController(mc);
    videoPreview.setVideoURI(android.net.Uri.parse(file.getPath()));
    videoPreview.setOnPreparedListener(mp -> {
        mp.setLooping(false);
        videoPreview.start();
        mc.show(3000);
    });
    videoPreview.setOnErrorListener((mp, what, extra) -> {
        showUnsupported(CodecChecker.getUnsupportedReason(file));
        return true;
    });
    videoPreview.requestFocus();
}

    // ── Details ───────────────────────────────────────────────────────────────

    private void updateDetails(MediaFile file) {
        detailFileName.setText(file.getName());
        detailMeta.setText(file.getFormattedSize()
            + "  •  " + file.getType().name()
            + "  •  " + file.getTags().size() + " tags");
        detailPath.setText(file.getPath());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showUnsupported(String reason) {
        mainHandler.post(() -> {
            unsupportedPreview.setVisibility(View.VISIBLE);
            unsupportedText.setText(reason);
        });
    }

    private void hideAll() {
        imagePreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);
        unsupportedPreview.setVisibility(View.GONE);
    }

    public void stopMedia() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
            isPlaying   = false;
        }
        if (videoPreview != null) videoPreview.stopPlayback();
        mainHandler.removeCallbacksAndMessages(null);
    }

    public void release() {
        stopMedia();
        executor.shutdown();
    }
}
