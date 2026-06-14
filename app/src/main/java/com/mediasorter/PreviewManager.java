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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.SidePanelTagAdapter;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.TagList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PreviewManager {

    public interface ActionListener {
        void onSkip();
        void onFlag();
        void onDone();
        void onNext();
        void onPrev();
        void onDpadUp();
        void onDpadDown();
        void onDpadLeft();
        void onDpadRight();
        void onDpadCenter();
        void onTagListChanged(int index);
    }

    private final Context         context;
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Views
    private ImageView   imagePreview;
    private VideoView   videoPreview;
    private View        unsupportedPreview;
    private TextView    detailFileName;
    private TextView    detailMeta;
    private TextView    unsupportedText;
    private TextView    positionCounter;
    private Button      btnSkip;
    private Button      btnFlag;
    private Button      btnDone;
    private Button      btnPrev;
    private Button      btnNext;
    private Button      dpadUp;
    private Button      dpadDown;
    private Button      dpadLeft;
    private Button      dpadRight;
    private Button      dpadCenter;
    private Spinner     tagListSpinner;
    private RecyclerView sidePanelTagList;

    private ActionListener      actionListener;
    private FileStatus          fileStatus;
    private GestureDetector     swipeDetector;
    private ScaleGestureDetector scaleDetector;
    private SidePanelTagAdapter sidePanelAdapter;

    // Zoom state
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
        setupButtons();
        setupSidePanel();
    }
    
    public SidePanelTagAdapter getSidePanelTagAdapter() {
    return sidePanelAdapter;
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private void bindViews(View root) {
        imagePreview       = root.findViewById(R.id.imagePreview);
        videoPreview       = root.findViewById(R.id.videoPreview);
        unsupportedPreview = root.findViewById(R.id.unsupportedPreview);
        detailFileName     = root.findViewById(R.id.detailFileName);
        detailMeta         = root.findViewById(R.id.detailMeta);
        unsupportedText    = root.findViewById(R.id.unsupportedText);
        positionCounter    = root.findViewById(R.id.positionCounter);
        btnSkip            = root.findViewById(R.id.btnSkip);
        btnFlag            = root.findViewById(R.id.btnFlag);
        btnDone            = root.findViewById(R.id.btnDone);
        btnPrev            = root.findViewById(R.id.btnPrev);
        btnNext            = root.findViewById(R.id.btnNext);
        dpadUp             = root.findViewById(R.id.dpadUp);
        dpadDown           = root.findViewById(R.id.dpadDown);
        dpadLeft           = root.findViewById(R.id.dpadLeft);
        dpadRight          = root.findViewById(R.id.dpadRight);
        dpadCenter         = root.findViewById(R.id.dpadCenter);
        tagListSpinner     = root.findViewById(R.id.tagListSpinner);
        sidePanelTagList   = root.findViewById(R.id.sidePanelTagList);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        btnSkip.setOnClickListener(v   -> { if (actionListener != null) actionListener.onSkip(); });
        btnFlag.setOnClickListener(v   -> { if (actionListener != null) actionListener.onFlag(); });
        btnDone.setOnClickListener(v   -> { if (actionListener != null) actionListener.onDone(); });
        btnPrev.setOnClickListener(v   -> { if (actionListener != null) actionListener.onPrev(); });
        btnNext.setOnClickListener(v   -> { if (actionListener != null) actionListener.onNext(); });
        dpadUp.setOnClickListener(v    -> { if (actionListener != null) actionListener.onDpadUp(); });
        dpadDown.setOnClickListener(v  -> { if (actionListener != null) actionListener.onDpadDown(); });
        dpadLeft.setOnClickListener(v  -> { if (actionListener != null) actionListener.onDpadLeft(); });
        dpadRight.setOnClickListener(v -> { if (actionListener != null) actionListener.onDpadRight(); });
        dpadCenter.setOnClickListener(v-> { if (actionListener != null) actionListener.onDpadCenter(); });
    }

    // ── Side panel ────────────────────────────────────────────────────────────

    private void setupSidePanel() {
        sidePanelAdapter = new SidePanelTagAdapter();
        sidePanelTagList.setLayoutManager(new LinearLayoutManager(context));
        sidePanelTagList.setAdapter(sidePanelAdapter);
    }

    public void setSidePanelTags(List<String> tags, List<String> appliedTags) {
        sidePanelAdapter.setTags(tags, appliedTags);
    }

    public void setTagListSpinner(android.widget.ArrayAdapter<String> adapter,
                                   int selectedIndex) {
        tagListSpinner.setAdapter(adapter);
        tagListSpinner.setSelection(selectedIndex);
        tagListSpinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent,
                        View view, int position, long id) {
                    if (actionListener != null) {
                        actionListener.onTagListChanged(position);
                    }
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
    }

    public void updateDpadLabels(String up, String down, String left,
                                  String right, String center) {
        dpadUp.setText(up.isEmpty()     ? "▲" : "▲\n" + up);
        dpadDown.setText(down.isEmpty() ? "▼" : "▼\n" + down);
        dpadLeft.setText(left.isEmpty() ? "◄" : "◄\n" + left);
        dpadRight.setText(right.isEmpty()? "►" : "►\n" + right);
        dpadCenter.setText(center.isEmpty()? "●" : "●\n" + center);
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private void setupZoom() {
        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor  = Math.max(MIN_ZOOM,
                        Math.min(scaleFactor, MAX_ZOOM));
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
                    // Double tap to reset
                    if (scaleFactor <= MIN_ZOOM) resetZoom();
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
        scaleFactor = MIN_ZOOM;
        translateX  = 0f;
        translateY  = 0f;
        if (imagePreview != null) {
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    public void setSwipeDetector(GestureDetector d) { this.swipeDetector = d; }

    public void setActionListener(ActionListener l) { this.actionListener = l; }

    // ── Load ──────────────────────────────────────────────────────────────────

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
            default:    showUnsupported("Unsupported"); break;
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
            opts.inSampleSize       = calcSampleSize(opts, 1920, 1080);
            opts.inJustDecodeBounds = false;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);

            mainHandler.post(() -> {
                if (bmp != null) {
                    imagePreview.setVisibility(View.VISIBLE);
                    // FIT_CENTER first to show full image
                    imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imagePreview.setImageBitmap(bmp);
                    // Switch to matrix after layout for zoom support
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

    // ── Video ─────────────────────────────────────────────────────────────────

    private void loadVideo(MediaFile file) {
        mainHandler.post(() -> {
            videoPreview.setVisibility(View.VISIBLE);

            MediaController mc = new MediaController(context);
            mc.setAnchorView(videoPreview);
            videoPreview.setMediaController(mc);
            videoPreview.setVideoURI(
                android.net.Uri.parse(file.getPath()));

            videoPreview.setOnPreparedListener(mp -> {
                mp.setLooping(false);
                // Keep controls always visible
                mc.show(0);
                videoPreview.start();
            });

            videoPreview.setOnErrorListener((mp, what, extra) -> {
                showUnsupported(CodecChecker.getUnsupportedReason(file));
                return true;
            });

            videoPreview.setOnCompletionListener(mp -> mc.show(0));

            videoPreview.requestFocus();
        });
    }

    // ── Details ───────────────────────────────────────────────────────────────

    private void updateDetails(MediaFile file) {
        detailFileName.setText(file.getName());
        detailMeta.setText(
            file.getFormattedSize()
            + "  •  " + file.getType().name().toLowerCase()
            + "  •  " + file.getTags().size() + " tags");
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
    }

    public void release() {
        stopMedia();
        executor.shutdown();
    }
}
