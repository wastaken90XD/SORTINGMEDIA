package com.mediasorter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import com.mediasorter.models.MediaFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PreviewManager {

    public interface ActionListener {
        void onTags();
        void onSkip();
        void onFlag();
        void onDone();
    }

    private final Context         context;
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private ImageView imagePreview;
    private VideoView videoPreview;
    private View      unsupportedPreview;
    private TextView  detailFileName;
    private TextView  detailMeta;
    private TextView  detailPath;
    private TextView  unsupportedText;
    private TextView  positionCounter;
    private Button    btnTags;
    private Button    btnSkip;
    private Button    btnFlag;
    private Button    btnDone;

    private ActionListener       actionListener;
    private FileStatus           fileStatus;
    private GestureDetector      swipeDetector;
    private ScaleGestureDetector scaleDetector;

    private float scaleFactor = 1.0f;
    private float translateX  = 0f;
    private float translateY  = 0f;
    private float lastTouchX  = 0f;
    private float lastTouchY  = 0f;

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 8.0f;

    public PreviewManager(Context context, View previewRoot, FileStatus fileStatus) {
        this.context    = context;
        this.fileStatus = fileStatus;
        bindViews(previewRoot);
        setupZoom();
    }

    public void setSwipeDetector(GestureDetector detector) {
        this.swipeDetector = detector;
    }

    public void setActionListener(ActionListener l) {
        this.actionListener = l;
        btnTags.setOnClickListener(v -> { if (actionListener != null) actionListener.onTags(); });
        btnSkip.setOnClickListener(v -> { if (actionListener != null) actionListener.onSkip(); });
        btnFlag.setOnClickListener(v -> { if (actionListener != null) actionListener.onFlag(); });
        btnDone.setOnClickListener(v -> { if (actionListener != null) actionListener.onDone(); });
    }

    private void bindViews(View root) {
        imagePreview       = root.findViewById(R.id.imagePreview);
        videoPreview       = root.findViewById(R.id.videoPreview);
        unsupportedPreview = root.findViewById(R.id.unsupportedPreview);
        detailFileName     = root.findViewById(R.id.detailFileName);
        detailMeta         = root.findViewById(R.id.detailMeta);
        detailPath         = root.findViewById(R.id.detailPath);
        unsupportedText    = root.findViewById(R.id.unsupportedText);
        positionCounter    = root.findViewById(R.id.positionCounter);
        btnTags            = root.findViewById(R.id.btnTags);
        btnSkip            = root.findViewById(R.id.btnSkip);
        btnFlag            = root.findViewById(R.id.btnFlag);
        btnDone            = root.findViewById(R.id.btnDone);
    }

    private void setupZoom() {
        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor  = Math.max(MIN_ZOOM, Math.min(scaleFactor, MAX_ZOOM));
                    applyMatrix();
                    return true;
                }
            });

        imagePreview.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (swipeDetector != null) swipeDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && scaleFactor > 1.0f) {
                        translateX += event.getX() - lastTouchX;
                        translateY += event.getY() - lastTouchY;
                        applyMatrix();
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    if (scaleFactor <= MIN_ZOOM) {
                        resetZoom();
                    }
                    break;
            }
            return true;
        });
    }

    private void applyMatrix() {
        Matrix matrix = new Matrix();
        matrix.setScale(scaleFactor, scaleFactor,
            imagePreview.getWidth()  / 2f,
            imagePreview.getHeight() / 2f);
        matrix.postTranslate(translateX, translateY);
        imagePreview.setImageMatrix(matrix);
    }

    private void resetZoom() {
        scaleFactor = 1.0f;
        translateX  = 0f;
        translateY  = 0f;
        if (imagePreview != null) {
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    public void load(MediaFile file) {
        stopMedia();
        hideAll();
        resetZoom();
        updateDetails(file);
        updateButtonStates(file);

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

    public void setPosition(int current, int total) {
        if (positionCounter != null) {
            positionCounter.setText(current + " / " + total);
        }
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private void loadImage(MediaFile file) {
        executor.submit(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            opts.inSampleSize       = calculateSampleSize(opts, 1920, 1080);
            opts.inJustDecodeBounds = false;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);

            mainHandler.post(() -> {
                if (bmp != null) {
                    imagePreview.setVisibility(View.VISIBLE);
                    imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imagePreview.setImageBitmap(bmp);
                    imagePreview.post(() -> {
                        imagePreview.setScaleType(ImageView.ScaleType.MATRIX);
                        Matrix m = new Matrix(imagePreview.getImageMatrix());
                        imagePreview.setImageMatrix(m);
                    });
                } else {
                    showUnsupported("Could not decode image");
                }
            });
        });
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

    // ── Video ─────────────────────────────────────────────────────────────────

    private void loadVideo(MediaFile file) {
        videoPreview.setVisibility(View.VISIBLE);
        MediaController mc = new MediaController(context);
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

    private void updateButtonStates(MediaFile file) {
        if (fileStatus == null) return;
        String path = file.getPath();
        btnSkip.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                fileStatus.isSkipped(path) ? 0xFF6666AA : 0xFF444466));
        btnFlag.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                fileStatus.isFlagged(path) ? 0xFFFFAA00 : 0xFFAA6600));
        btnDone.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                fileStatus.isDone(path) ? 0xFF44AA44 : 0xFF226622));
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
        if (videoPreview != null) videoPreview.stopPlayback();
        mainHandler.removeCallbacksAndMessages(null);
    }

    public void release() {
        stopMedia();
        executor.shutdown();
    }
}
