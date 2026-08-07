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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.SidePanelTagAdapter;
import com.mediasorter.models.MediaFile;
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
    private ImageView    imagePreview;
    private VideoView    videoPreview;
    private View         unsupportedPreview;
    private TextView     detailFileName;
    private TextView     detailMeta;
    private TextView     unsupportedText;
    private TextView     positionCounter;
    private Button       btnSkip;
    private Button       btnFlag;
    private Button       btnDone;
    private Button       btnPrev;
    private Button       btnNext;
    private Button       btnTogglePanel;
    private Button       dpadUp;
    private Button       dpadDown;
    private Button       dpadLeft;
    private Button       dpadRight;
    private Button       dpadCenter;
    private LinearLayout tagSidePanel;
    private Spinner      tagListSpinner;
    private RecyclerView sidePanelTagList;
    private View         dpadContainer;
    private View         gestureOverlay;
    private TextView     swipeLeftLabel, swipeRightLabel, swipeUpLabel, swipeDownLabel;

    private ActionListener       actionListener;
    private FileStatus           fileStatus;
    private GestureDetector      swipeDetector;
    private ScaleGestureDetector scaleDetector;
    private SidePanelTagAdapter  sidePanelAdapter;
    private ThumbnailLoader      thumbnailLoader;

    private boolean panelVisible = false;

    // Path of the file most recently passed to load(); decode tasks that
    // finish for any older path discard their bitmap instead of showing it.
    private volatile String  currentPath = null;
    // Bitmap decoded by loadImage itself (never a cached thumbnail!) that is
    // currently owned by us; recycled once it is replaced or released.
    private Bitmap           ownBitmap   = null;
    private volatile boolean released    = false;

    // Zoom state
    private float scaleFactor = 1.0f;
    private float translateX  = 0f;
    private float translateY  = 0f;
    private float lastTouchX  = 0f;
    private float lastTouchY  = 0f;

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 8.0f;
    private static final long OVERLAY_TIMEOUT = 10000; // ms
    private final Runnable hideOverlayRunnable = () -> {
        if (gestureOverlay != null) gestureOverlay.setVisibility(View.GONE);
    };

    public PreviewManager(Context context, View previewRoot, FileStatus fileStatus) {
        this.context    = context;
        this.fileStatus = fileStatus;
        // Try to get a ThumbnailLoader if the context is MainActivity
        if (context instanceof MainActivity) {
            try {
                java.lang.reflect.Field f = MainActivity.class.getDeclaredField("thumbnailLoader");
                f.setAccessible(true);
                this.thumbnailLoader = (ThumbnailLoader) f.get(context);
            } catch (Exception ignored) {}
        }
        bindViews(previewRoot);
        setupZoom();
        setupButtons();
        setupSidePanel();
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
        btnTogglePanel     = root.findViewById(R.id.btnTogglePanel);
        dpadUp             = root.findViewById(R.id.dpadUp);
        dpadDown           = root.findViewById(R.id.dpadDown);
        dpadLeft           = root.findViewById(R.id.dpadLeft);
        dpadRight          = root.findViewById(R.id.dpadRight);
        dpadCenter         = root.findViewById(R.id.dpadCenter);
        tagSidePanel       = root.findViewById(R.id.tagSidePanel);
        tagListSpinner     = root.findViewById(R.id.tagListSpinner);
        sidePanelTagList   = root.findViewById(R.id.sidePanelTagList);
        dpadContainer      = root.findViewById(R.id.dpadContainer);
        gestureOverlay     = root.findViewById(R.id.gestureOverlay);
        swipeLeftLabel     = root.findViewById(R.id.swipeLeftLabel);
        swipeRightLabel    = root.findViewById(R.id.swipeRightLabel);
        swipeUpLabel       = root.findViewById(R.id.swipeUpLabel);
        swipeDownLabel     = root.findViewById(R.id.swipeDownLabel);
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        btnSkip.setOnClickListener(v    -> { if (actionListener != null) actionListener.onSkip(); });
        btnFlag.setOnClickListener(v    -> { if (actionListener != null) actionListener.onFlag(); });
        btnDone.setOnClickListener(v    -> { if (actionListener != null) actionListener.onDone(); });
        btnPrev.setOnClickListener(v    -> { if (actionListener != null) actionListener.onPrev(); });
        btnNext.setOnClickListener(v    -> { if (actionListener != null) actionListener.onNext(); });
        dpadUp.setOnClickListener(v     -> { if (actionListener != null) actionListener.onDpadUp(); });
        dpadDown.setOnClickListener(v   -> { if (actionListener != null) actionListener.onDpadDown(); });
        dpadLeft.setOnClickListener(v   -> { if (actionListener != null) actionListener.onDpadLeft(); });
        dpadRight.setOnClickListener(v  -> { if (actionListener != null) actionListener.onDpadRight(); });
        dpadCenter.setOnClickListener(v -> { if (actionListener != null) actionListener.onDpadCenter(); });

        btnTogglePanel.setOnClickListener(v -> togglePanel());

        // Long‑press on any swipe label to show the overlay again
        View.OnLongClickListener showOverlay = v -> {
            showGestureOverlay();
            return true;
        };
        swipeLeftLabel.setOnLongClickListener(showOverlay);
        swipeRightLabel.setOnLongClickListener(showOverlay);
        swipeUpLabel.setOnLongClickListener(showOverlay);
        swipeDownLabel.setOnLongClickListener(showOverlay);
    }

    /** Show/hide the whole floating D-pad (Settings → Main Window toggle). */
    public void setDpadVisible(boolean visible) {
        if (dpadContainer != null) {
            dpadContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // ── Panel toggle ──────────────────────────────────────────────────────────

    private void togglePanel() {
        panelVisible = !panelVisible;
        tagSidePanel.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
        btnTogglePanel.setText(panelVisible ? "✕" : "≡");
    }

    // ── Side panel ────────────────────────────────────────────────────────────

    private void setupSidePanel() {
        sidePanelAdapter = new SidePanelTagAdapter();
        sidePanelTagList.setLayoutManager(new LinearLayoutManager(context));
        sidePanelTagList.setAdapter(sidePanelAdapter);
    }

    public SidePanelTagAdapter getSidePanelAdapter() {
        return sidePanelAdapter;
    }

    public void setSidePanelTags(List<String> tags, List<String> appliedTags) {
        sidePanelAdapter.setTags(tags, appliedTags);
    }

    public void setTagListSpinner(ArrayAdapter<String> adapter, int selectedIndex) {
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
        dpadUp.setText(up.isEmpty()      ? "▲" : "▲\n" + truncate(up));
        dpadDown.setText(down.isEmpty()  ? "▼" : "▼\n" + truncate(down));
        dpadLeft.setText(left.isEmpty()  ? "◄" : "◄\n" + truncate(left));
        dpadRight.setText(right.isEmpty()? "►" : "►\n" + truncate(right));
        dpadCenter.setText(center.isEmpty()? "●" : "●\n" + truncate(center));
    }

    private String truncate(String s) {
        return s.length() > 6 ? s.substring(0, 6) + "…" : s;
    }

    // ── Gesture overlay ───────────────────────────────────────────────────────

    /**
     * Call this from MainActivity to update the swipe labels with the current
     * gesture actions.  The overlay is shown automatically for a few seconds.
     */
    public void updateGestureLabels(GestureSettings gestureSettings) {
        if (gestureSettings == null || swipeLeftLabel == null) return;

        swipeLeftLabel.setText("← " + gestureSettings.getSummary(gestureSettings.getLeft()));
        swipeRightLabel.setText("→ " + gestureSettings.getSummary(gestureSettings.getRight()));
        swipeUpLabel.setText("↑ " + gestureSettings.getSummary(gestureSettings.getUp()));
        swipeDownLabel.setText("↓ " + gestureSettings.getSummary(gestureSettings.getDown()));

        showGestureOverlay();
    }

    public void showGestureOverlay() {
        if (gestureOverlay == null) return;
        gestureOverlay.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideOverlayRunnable);
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT);
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
            boolean scaleHandled = scaleDetector.onTouchEvent(event);

            // Only forward to swipe detector when NOT zooming and NOT zoomed in.
            // This prevents gestures (flings) from registering/navigating while pinching or viewing zoomed.
            boolean shouldHandleSwipe = swipeDetector != null
                    && !scaleDetector.isInProgress()
                    && scaleFactor <= MIN_ZOOM + 0.01f;

            if (shouldHandleSwipe) {
                swipeDetector.onTouchEvent(event);
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    if (scaleFactor > 1.0f) {
                        imagePreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    }
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
                        imagePreview.setLayerType(View.LAYER_TYPE_NONE, null);
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
        scaleFactor = MIN_ZOOM;
        translateX  = 0f;
        translateY  = 0f;
        if (imagePreview != null) {
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    public void setSwipeDetector(GestureDetector d) { this.swipeDetector = d; }
    public void setActionListener(ActionListener l) { this.actionListener = l; }

    /**
     * Preferred way to hand the shared thumbnail cache to the preview.
     * The constructor's reflection-based lookup stays only as a fallback for
     * old call sites.
     */
    public void setThumbnailLoader(ThumbnailLoader loader) {
        if (loader != null) this.thumbnailLoader = loader;
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public void load(MediaFile file) {
        if (released || file == null) return;
        currentPath = file.getPath();
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

    private void loadImage(final MediaFile file) {
        // Show thumbnail immediately to avoid black preview while full image loads
        Bitmap thumb = thumbnailLoader != null
                ? thumbnailLoader.getCachedThumbnail(file)   // fast path
                : null;
        if (thumb != null && !thumb.isRecycled()) {
            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagePreview.setImageBitmap(thumb);
        }

        if (released || executor.isShutdown()) return;
        final String path = file.getPath();
        try {
            executor.submit(() -> {
                if (released) return;
                Bitmap bmp = decodeSampled(path, 1920, 1080);

                mainHandler.post(() -> {
                    // Stale task: user has already navigated to another file.
                    // Recycle instead of displaying the wrong image.
                    if (released || !path.equals(currentPath)) {
                        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                        return;
                    }
                    if (bmp != null) {
                        replaceOwnBitmap(bmp);
                        imagePreview.setVisibility(View.VISIBLE);
                        imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        imagePreview.setImageBitmap(bmp);
                        imagePreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        imagePreview.post(() -> {
                            if (imagePreview.getWidth() > 0 && imagePreview.getHeight() > 0) {
                                imagePreview.setScaleType(ImageView.ScaleType.MATRIX);
                                Matrix m = new Matrix(imagePreview.getImageMatrix());
                                imagePreview.setImageMatrix(m);
                            }
                        });
                    } else {
                        showUnsupported("Could not decode image");
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Released between the isShutdown() check and submit — harmlessly skip.
        }
    }

    /**
     * Bounds-then-sample decode with OOM defense: if even the sampled bitmap
     * does not fit, retry with a doubled sample size once before giving up.
     * Never throws OutOfMemoryError to the caller.
     */
    private Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        try {
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            opts.inSampleSize       = calcSampleSize(opts, reqW, reqH);
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            try {
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = Math.max(2, opts.inSampleSize * 2);
                return BitmapFactory.decodeFile(path, opts);
            } catch (OutOfMemoryError | Exception e2) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Take ownership of a freshly decoded preview bitmap; recycle the old one. */
    private void replaceOwnBitmap(Bitmap next) {
        Bitmap old = ownBitmap;
        ownBitmap = next;
        if (old != null && !old.isRecycled() && old != next) {
            old.recycle();
        }
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

    /**
     * Release preview resources to prevent OOM/leaks: stops video, drops the
     * decoded full-size bitmap, cancels pending UI callbacks and shuts the
     * decode executor down. Any load() after release() becomes a no-op.
     */
    public void release() {
        released = true;
        stopMedia();
        mainHandler.removeCallbacks(hideOverlayRunnable);
        Bitmap old = ownBitmap;
        ownBitmap = null;
        if (old != null && !old.isRecycled()) old.recycle();
        executor.shutdown();
    }
}
