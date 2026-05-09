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
    private View      audioPreview;
    private View      unsupportedPreview;
    private ImageView albumArt;
    private SeekBar   audioSeekBar;
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
        audioPreview       = root.findViewById(R.id.audioPreview);
        unsupportedPreview = root.findViewById(R.id.unsupportedPreview);
        albumArt           = root.findViewById(R.id.albumArt);
        audioSeekBar       = root.findViewById(R.id.audioSeekBar);
        btnPlayPause       = root.findViewById(R.id.btnPlayPause);
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
            case AUDIO: loadAudio(file); break;
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
        videoPreview.setVideoURI(Uri.parse(file.getPath()));
        videoPreview.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            videoPreview.start();
        });
        videoPreview.setOnErrorListener((mp, what, extra) -> {
            showUnsupported(CodecChecker.getUnsupportedReason(file));
            return true;
        });
        videoPreview.requestFocus();
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private void loadAudio(MediaFile file) {
        audioPreview.setVisibility(View.VISIBLE);
        loadAlbumArt(file);

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getPath());
            mediaPlayer.prepare();

            audioSeekBar.setMax(mediaPlayer.getDuration());

            btnPlayPause.setOnClickListener(v -> togglePlayPause());
            startSeekBarUpdater();

        } catch (Exception e) {
            showUnsupported(CodecChecker.getUnsupportedReason(file));
        }
    }

    private void loadAlbumArt(MediaFile file) {
        executor.submit(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(file.getPath());
                byte[] art = mmr.getEmbeddedPicture();
                if (art != null) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(art, 0, art.length);
                    mainHandler.post(() -> albumArt.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        });
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (isPlaying) {
            mediaPlayer.pause();
            btnPlayPause.setText("▶");
        } else {
            mediaPlayer.start();
            btnPlayPause.setText("⏸");
        }
        isPlaying = !isPlaying;
    }

    private void startSeekBarUpdater() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    audioSeekBar.setProgress(mediaPlayer.getCurrentPosition());
                }
                mainHandler.postDelayed(this, 500);
            }
        });

        audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) mediaPlayer.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
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
        audioPreview.setVisibility(View.GONE);
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
