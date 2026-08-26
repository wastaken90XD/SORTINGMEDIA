package com.mediasorter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
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
        void onNext();
        void onPrev();
        void onDpadUp();
        void onDpadDown();
        void onDpadLeft();
        void onDpadRight();
        void onDpadCenter();
        void onTagListChanged(int index);
    }

    public interface GestureInputListener {
        void onInput(String inputId);
    }

    private final Context         context;
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Views
    private FrameLayout  previewMediaRoot;
    private ImageView    imagePreview;
    private VideoView    videoPreview;
    private View         unsupportedPreview;
    private TextView     detailFileName;
    private TextView     detailMeta;
    private TextView     unsupportedText;
    private TextView     positionCounter;
    private TextView     flagState;
    private Button       btnSkip;
    private Button       btnFlag;
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

    // Runtime centered gesture hint view & flash tint overlay
    private TextView        hintTextView;
    private View            tintOverlay;
    private GestureSettings gestureSettings;

    // Swipe tracking for runtime hint
    private float   downX = 0f;
    private float   downY = 0f;
    private boolean hasFlashedForCurrentSwipe = false;

    private ActionListener       actionListener;
    private GestureInputListener gestureInputListener;
    private FileStatus           fileStatus;
    private GestureDetector      swipeDetector;
    private ScaleGestureDetector scaleDetector;
    private SidePanelTagAdapter  sidePanelAdapter;
    private ThumbnailLoader      thumbnailLoader;

    private boolean panelVisible = false;

    // Path of the file most recently passed to load()
    private volatile String  currentPath = null;
    private Bitmap           ownBitmap   = null;
    private volatile boolean released    = false;

    // Zoom state
    private float scaleFactor = 1.0f;
    private float translateX  = 0f;
    private float translateY  = 0f;
    private float lastTouchX  = 0f;
    private float lastTouchY  = 0f;
    private float multiFingerDownX = 0f;
    private float multiFingerDownY = 0f;
    private boolean multiFingerTouch;
    private boolean multiFingerSwipeSent;

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 8.0f;

    private final Runnable hideTintRunnable = new Runnable() {
        @Override
        public void run() {
            if (tintOverlay != null) {
                tintOverlay.setVisibility(View.GONE);
            }
        }
    };

    public PreviewManager(Context context, View previewRoot, FileStatus fileStatus) {
        this.context    = context;
        this.fileStatus = fileStatus;
        if (context instanceof MainActivity) {
            try {
                java.lang.reflect.Field f = MainActivity.class.getDeclaredField("thumbnailLoader");
                f.setAccessible(true);
                this.thumbnailLoader = (ThumbnailLoader) f.get(context);
            } catch (Exception ignored) {}
            try {
                java.lang.reflect.Field f = MainActivity.class.getDeclaredField("gestureSettings");
                f.setAccessible(true);
                this.gestureSettings = (GestureSettings) f.get(context);
            } catch (Exception ignored) {}
        }
        bindViews(previewRoot);
        setupZoom();
        setupButtons();
        setupSidePanel();
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private void bindViews(View root) {
        previewMediaRoot   = root.findViewById(R.id.previewMediaRoot);
        imagePreview       = root.findViewById(R.id.imagePreview);
        videoPreview       = root.findViewById(R.id.videoPreview);
        unsupportedPreview = root.findViewById(R.id.unsupportedPreview);
        detailFileName     = root.findViewById(R.id.detailFileName);
        detailMeta         = root.findViewById(R.id.detailMeta);
        flagState           = root.findViewById(R.id.flagState);
        unsupportedText    = root.findViewById(R.id.unsupportedText);
        positionCounter    = root.findViewById(R.id.positionCounter);
        btnSkip            = root.findViewById(R.id.btnSkip);
        btnFlag            = root.findViewById(R.id.btnFlag);
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

        // Create runtime overlay & hint text view in previewMediaRoot
        if (previewMediaRoot != null) {
            TypedValue typedValue = new TypedValue();
            context.getTheme().resolveAttribute(R.attr.colorAccent, typedValue, true);
            int colorAccent = typedValue.data;
            
            tintOverlay = new View(context);
            FrameLayout.LayoutParams tintLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            tintOverlay.setLayoutParams(tintLp);
            
            // Derive flash color (20% alpha) from colorAccent
            int flashColor = (colorAccent & 0x00FFFFFF) | 0x33000000;
            tintOverlay.setBackgroundColor(flashColor);
            tintOverlay.setClickable(false);
            tintOverlay.setFocusable(false);
            tintOverlay.setVisibility(View.GONE);

            hintTextView = new TextView(context);
            FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            hintTextView.setLayoutParams(hintLp);
            hintTextView.setClickable(false);
            hintTextView.setFocusable(false);
            hintTextView.setFocusableInTouchMode(false);
            hintTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            hintTextView.setTextColor(colorAccent);
            hintTextView.setBackground(null);
            hintTextView.setVisibility(View.GONE);

            previewMediaRoot.addView(tintOverlay);
            previewMediaRoot.addView(hintTextView);
        }
    }

    // ── Runtime Hint Overlay & Flash ──────────────────────────────────────────

    public void showHintLabel(String label) {
        if (hintTextView == null) return;
        if (label != null && !label.isEmpty() && !"Nothing".equalsIgnoreCase(label.trim())) {
            hintTextView.setText(label);
            hintTextView.setVisibility(View.VISIBLE);
        } else {
            hideHintLabel();
        }
    }

    public void hideHintLabel() {
        if (hintTextView != null) {
            hintTextView.setVisibility(View.GONE);
        }
    }

    public void flashPreviewRoot() {
        if (tintOverlay == null) return;
        tintOverlay.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideTintRunnable);
        mainHandler.postDelayed(hideTintRunnable, 400);
    }

    public void setGestureSettings(GestureSettings gs) {
        this.gestureSettings = gs;
    }

    // ── Single-finger Swipe Hint Tracking ─────────────────────────────────────

    private void trackSwipeForHint(MotionEvent event) {
        if (gestureSettings == null) return;
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        if (pointerCount > 1) {
            hideHintLabel();
            return;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getRawX();
            downY = event.getRawY();
            hasFlashedForCurrentSwipe = false;
            hideHintLabel();
        } else if (action == MotionEvent.ACTION_MOVE && pointerCount == 1 && scaleFactor <= MIN_ZOOM + 0.01f) {
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            float thresholdPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 20, context.getResources().getDisplayMetrics());

            if (Math.abs(dx) >= thresholdPx || Math.abs(dy) >= thresholdPx) {
                boolean isHorizontal = Math.abs(dx) > Math.abs(dy);
                List<GestureSettings.GestureStep> steps;
                if (isHorizontal) {
                    steps = (dx < 0) ? gestureSettings.getLeft() : gestureSettings.getRight();
                } else {
                    steps = (dy < 0) ? gestureSettings.getUp() : gestureSettings.getDown();
                }

                boolean isUnassigned = (steps == null || steps.isEmpty() ||
                        (steps.size() == 1 && steps.get(0).action == GestureSettings.GestureAction.NOTHING));

                if (!isUnassigned) {
                    String summary = gestureSettings.getSummary(steps);
                    if (summary != null && !summary.isEmpty() && !"Nothing".equalsIgnoreCase(summary.trim())) {
                        showHintLabel(summary);
                    } else {
                        hideHintLabel();
                        if (!hasFlashedForCurrentSwipe) {
                            hasFlashedForCurrentSwipe = true;
                            flashPreviewRoot();
                        }
                    }
                } else {
                    hideHintLabel();
                    if (!hasFlashedForCurrentSwipe) {
                        hasFlashedForCurrentSwipe = true;
                        flashPreviewRoot();
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            hideHintLabel();
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private void setupButtons() {
        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (actionListener != null) actionListener.onSkip(); }
        });
        btnFlag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (actionListener != null) actionListener.onFlag(); }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (actionListener != null) actionListener.onPrev(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (actionListener != null) actionListener.onNext(); }
        });

        // Software D-Pad button touches
        bindDpadButton(dpadUp, new Runnable() {
            @Override public void run() { if (actionListener != null) actionListener.onDpadUp(); }
        }, new DpadStepGetter() {
            @Override public List<GestureSettings.GestureStep> get() { return gestureSettings != null ? gestureSettings.getDpadUp() : null; }
        });

        bindDpadButton(dpadDown, new Runnable() {
            @Override public void run() { if (actionListener != null) actionListener.onDpadDown(); }
        }, new DpadStepGetter() {
            @Override public List<GestureSettings.GestureStep> get() { return gestureSettings != null ? gestureSettings.getDpadDown() : null; }
        });

        bindDpadButton(dpadLeft, new Runnable() {
            @Override public void run() { if (actionListener != null) actionListener.onDpadLeft(); }
        }, new DpadStepGetter() {
            @Override public List<GestureSettings.GestureStep> get() { return gestureSettings != null ? gestureSettings.getDpadLeft() : null; }
        });

        bindDpadButton(dpadRight, new Runnable() {
            @Override public void run() { if (actionListener != null) actionListener.onDpadRight(); }
        }, new DpadStepGetter() {
            @Override public List<GestureSettings.GestureStep> get() { return gestureSettings != null ? gestureSettings.getDpadRight() : null; }
        });

        bindDpadButton(dpadCenter, new Runnable() {
            @Override public void run() { if (actionListener != null) actionListener.onDpadCenter(); }
        }, new DpadStepGetter() {
            @Override public List<GestureSettings.GestureStep> get() { return gestureSettings != null ? gestureSettings.getDpadCenter() : null; }
        });

        btnTogglePanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanel(); }
        });
    }

    private interface DpadStepGetter {
        List<GestureSettings.GestureStep> get();
    }

    private void bindDpadButton(final Button btn, final Runnable onClick, final DpadStepGetter getter) {
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    List<GestureSettings.GestureStep> steps = getter.get();
                    boolean isUnassigned = (steps == null || steps.isEmpty() ||
                            (steps.size() == 1 && steps.get(0).action == GestureSettings.GestureAction.NOTHING));
                    if (!isUnassigned) {
                        String summary = gestureSettings != null ? gestureSettings.getSummary(steps) : "";
                        if (summary != null && !summary.isEmpty() && !"Nothing".equalsIgnoreCase(summary.trim())) {
                            showHintLabel(summary);
                        } else {
                            hideHintLabel();
                            flashPreviewRoot();
                        }
                    } else {
                        hideHintLabel();
                        flashPreviewRoot();
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    hideHintLabel();
                    if (action == MotionEvent.ACTION_UP) {
                        onClick.run();
                    }
                }
                return true;
            }
        });
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
        boolean enabled = gestureSettings == null || gestureSettings.isDpadEnabled();
        android.content.SharedPreferences sp = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        boolean dpadEnabled = sp.getBoolean("dpad_enabled", true);
        if (!enabled || !dpadEnabled) {
            dpadUp.setText("▲");
            dpadDown.setText("▼");
            dpadLeft.setText("◄");
            dpadRight.setText("►");
            dpadCenter.setText("●");
            return;
        }
        dpadUp.setText(up.isEmpty()      ? "▲" : "▲\n" + truncate(up));
        dpadDown.setText(down.isEmpty()  ? "▼" : "▼\n" + truncate(down));
        dpadLeft.setText(left.isEmpty()  ? "◄" : "◄\n" + truncate(left));
        dpadRight.setText(right.isEmpty()? "►" : "►\n" + truncate(right));
        dpadCenter.setText(center.isEmpty()? "●" : "●\n" + truncate(center));
    }

    private String truncate(String s) {
        return s.length() > 6 ? s.substring(0, 6) + "…" : s;
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private void emitGestureInput(String inputId) {
        if (gestureInputListener != null) gestureInputListener.onInput(inputId);
    }

    private void setupZoom() {
        scaleDetector = new ScaleGestureDetector(context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    emitGestureInput(GestureConstants.INPUT_SCALE_PREVIEW);
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleFactor *= detector.getScaleFactor();
                    scaleFactor  = Math.max(MIN_ZOOM,
                        Math.min(scaleFactor, MAX_ZOOM));
                    applyMatrix();
                    return true;
                }
            });

        imagePreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Runtime hint tracking — never consumes touch event
                trackSwipeForHint(event);

                boolean scaleHandled = scaleDetector.onTouchEvent(event);

                boolean shouldHandleSwipe = swipeDetector != null
                        && event.getPointerCount() <= 1
                        && !scaleDetector.isInProgress()
                        && scaleFactor <= MIN_ZOOM + 0.01f;

                if (shouldHandleSwipe) {
                    swipeDetector.onTouchEvent(event);
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        multiFingerTouch = false;
                        multiFingerSwipeSent = false;
                        if (scaleFactor > 1.0f) {
                            imagePreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (event.getPointerCount() > 1) {
                            multiFingerTouch = true;
                            multiFingerSwipeSent = false;
                            multiFingerDownX = event.getX();
                            multiFingerDownY = event.getY();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (multiFingerTouch && !multiFingerSwipeSent
                                && event.getPointerCount() > 1) {
                            float multiDx = event.getX() - multiFingerDownX;
                            float multiDy = event.getY() - multiFingerDownY;
                            if (Math.abs(multiDx) >= 100 || Math.abs(multiDy) >= 100) {
                                if (Math.abs(multiDx) > Math.abs(multiDy)) {
                                    emitGestureInput(multiDx < 0
                                            ? GestureConstants.INPUT_SWIPE_LEFT_TWO_FINGER_PREVIEW
                                            : GestureConstants.INPUT_SWIPE_RIGHT_TWO_FINGER_PREVIEW);
                                } else {
                                    emitGestureInput(multiDy < 0
                                            ? GestureConstants.INPUT_SWIPE_UP_TWO_FINGER_PREVIEW
                                            : GestureConstants.INPUT_SWIPE_DOWN_TWO_FINGER_PREVIEW);
                                }
                                multiFingerSwipeSent = true;
                            }
                        }
                        if (!scaleDetector.isInProgress() && scaleFactor > 1.0f) {
                            translateX += event.getX() - lastTouchX;
                            translateY += event.getY() - lastTouchY;
                            applyMatrix();
                        }
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        multiFingerTouch = false;
                        multiFingerSwipeSent = false;
                        if (scaleFactor <= MIN_ZOOM) {
                            resetZoom();
                            imagePreview.setLayerType(View.LAYER_TYPE_NONE, null);
                        }
                        break;
                }
                return true;
            }
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
    public void setGestureInputListener(GestureInputListener l) { this.gestureInputListener = l; }

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

    /** Clear stale preview state when filtering removes the current file. */
    public void clearCurrent() {
        currentPath = null;
        stopMedia();
        hideAll();
        if (detailFileName != null) detailFileName.setText("");
        if (detailMeta != null) detailMeta.setText("");
        if (flagState != null) flagState.setVisibility(View.GONE);
        setPosition(0, 0);
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private void loadImage(final MediaFile file) {
        Bitmap thumb = thumbnailLoader != null
                ? thumbnailLoader.getCachedThumbnail(file)
                : null;
        if (thumb != null && !thumb.isRecycled()) {
            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagePreview.setImageBitmap(thumb);
        }

        if (released || executor.isShutdown()) return;
        final String path = file.getPath();
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    if (released) return;
                    final Bitmap bmp = decodeSampled(path, 1920, 1080);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released || !path.equals(currentPath)) {
                                return;
                            }
                            if (bmp != null) {
                                replaceOwnBitmap(bmp);
                                imagePreview.setVisibility(View.VISIBLE);
                                imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
                                imagePreview.setImageBitmap(bmp);
                                imagePreview.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                                imagePreview.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (imagePreview.getWidth() > 0 && imagePreview.getHeight() > 0) {
                                            imagePreview.setScaleType(ImageView.ScaleType.MATRIX);
                                            Matrix m = new Matrix(imagePreview.getImageMatrix());
                                            imagePreview.setImageMatrix(m);
                                        }
                                    }
                                });
                            } else {
                                showUnsupported("Could not decode image");
                            }
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

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

    private void replaceOwnBitmap(Bitmap next) {
        ownBitmap = next;
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

    private void loadVideo(final MediaFile file) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                videoPreview.setVisibility(View.VISIBLE);
                final MediaController mc = new MediaController(context);
                mc.setAnchorView(videoPreview);
                videoPreview.setMediaController(mc);
                videoPreview.setVideoURI(android.net.Uri.parse(file.getPath()));
                videoPreview.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(android.media.MediaPlayer mp) {
                        mp.setLooping(false);
                        mc.show(0);
                        videoPreview.start();
                    }
                });
                videoPreview.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                        showUnsupported(CodecChecker.getUnsupportedReason(file));
                        return true;
                    }
                });
                videoPreview.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(android.media.MediaPlayer mp) {
                        mc.show(0);
                    }
                });
                videoPreview.requestFocus();
            }
        });
    }

    // ── Details ───────────────────────────────────────────────────────────────

    private void updateDetails(MediaFile file) {
        detailFileName.setText(file.getName());
        detailMeta.setText(
            file.getFormattedSize()
            + "  •  " + file.getType().name().toLowerCase()
            + "  •  " + file.getTags().size() + " tags");
        updateStatusIndicator(file);
    }

    /** Refresh the visible flag indicator without reloading media or layout. */
    public void updateStatusIndicator(MediaFile file) {
        if (file == null || flagState == null || fileStatus == null) return;
        boolean flagged = fileStatus.isFlagged(file.getPath());
        flagState.setText(flagged ? "FLAGGED" : "Not flagged");
        flagState.setVisibility(View.VISIBLE);
        flagState.setTextColor(flagged ? 0xFFFFAA00 : 0xFFAAAAAA);
    }

    /** Repaint status controls without reloading the preview bitmap/video. */
    public void updateStatus(MediaFile file) {
        if (file == null) return;
        updateButtonStates(file);
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
        updateStatusIndicator(file);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showUnsupported(final String reason) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                unsupportedPreview.setVisibility(View.VISIBLE);
                unsupportedText.setText(reason);
            }
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
        released = true;
        stopMedia();
        mainHandler.removeCallbacks(hideTintRunnable);
        ownBitmap = null;
        executor.shutdown();
    }
}
