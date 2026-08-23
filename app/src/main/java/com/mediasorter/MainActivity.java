package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.DialogInterface;
import com.mediasorter.features.RandomGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.CheckBox;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.GalleryAdapter;
import com.mediasorter.adapters.MediaAdapter;
import com.mediasorter.adapters.SidePanelTagAdapter;
import com.mediasorter.adapters.TagAdapter;
import com.mediasorter.adapters.TagBarAdapter;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.TagList;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.widget.Spinner;
import com.mediasorter.organizer.AutoOrganizer;
import com.mediasorter.RulesActivity;

public class MainActivity extends Activity
        implements FolderWatcher.Listener, MediaIndexer.IndexListener {

    private BatchRenameManager batchRenameManager = new BatchRenameManager();
    private MediaIndexer    indexer;
    private TagManager      tagManager;
    private TagListManager  tagListManager;
    private FolderManager   folderManager;
    private FolderWatcher   folderWatcher;
    private SearchManager   searchManager;
    private GroupManager    groupManager;
    private CacheManager    cacheManager;
    private PreviewManager  previewManager;
    private ThumbnailLoader thumbnailLoader;
    private SortManager     sortManager;
    private FileStatus      fileStatus;
    private FilterManager   filterManager;
    private GestureSettings gestureSettings;
    private WindowManager   windowManager;
    private AutoOrganizer autoOrganizer;
    private SearchHistory searchHistory;
    private MediaAdapter mediaAdapter;
    private TagAdapter   tagAdapter;
    private TagBarAdapter tagBarAdapter;
    private TextView statsBar;
    private EditText tagBarSearch;
    private Button tagBarSort;
    private RecyclerView tagBarList;
    private LinearLayout tagBarContainer;
    private final java.util.LinkedHashSet<String> activeTagFilters =
            new java.util.LinkedHashSet<String>();
    private TagBarSort tagBarSortMode = TagBarSort.USAGE;

    private enum TagBarSort { USAGE, ALPHABETICAL, RECENT }

    private List<MediaFile> fullList     = new ArrayList<>();
    private final List<MediaFile> currentFiles = new ArrayList<>();
    private static List<MediaFile> sLatestFullList = new ArrayList<>();
    private static List<Tag>       sLatestTagList  = new ArrayList<>();
    private int             currentIndex = -1;

    private boolean refreshPending = false;

    private EditText searchBar;
    private TextView progressLabel;
    private Button   btnSort;
    private Button   btnFilter;
    private Button   btnScan;
    private ProgressBar scanProgress;

    private RecyclerView fileBrowser;   // reference for scrolling to keep list in sync with preview

    // Gallery mode is an additive presentation over the existing file browser.
    private boolean galleryModeActive;
    private boolean galleryLowMemory;
    private boolean galleryDragging;
    private int galleryDragFrom = -1;
    private int galleryDragTo = -1;
    private String galleryDraggedPath;
    private List<String> galleryDragOriginalOrder = new ArrayList<>();
    private List<MediaFile> galleryDragOriginalFiles = new ArrayList<>();
    private FrameLayout galleryRoot;
    private RecyclerView galleryBrowser;
    private GridLayoutManager galleryLayoutManager;
    private GalleryAdapter galleryAdapter;
    private GalleryThumbnailLoader galleryThumbnailLoader;
    private TextView galleryCountLabel;
    private HorizontalScrollView galleryFilterScroll;
    private LinearLayout galleryFilterRow;
    private Button galleryToggleButton;
    private Button toolbarSearchToggle;
    private Button toolbarOverflowButton;
    private LinearLayout toolbarActionContainer;
    private String toolbarSlotsSnapshot = "";
    private boolean galleryFastScrolling;
    private boolean galleryScrollSettled = true;
    private long galleryLastScrollTime;
    private ItemTouchHelper galleryItemTouchHelper;
    private ScaleGestureDetector galleryScaleDetector;
    private float galleryLastScale = 1.0f;
    private float galleryLastTouchX;
    private float galleryLastTouchY;
    private final java.util.Stack<List<MediaFile>> galleryManualUndo =
            new java.util.Stack<List<MediaFile>>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService refreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService statsExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private long refreshSequence;
    private long statsSequence;
    private long volumeDownTime;
    private long volumeUpTime;
    private boolean infoOverlayVisible;
    private android.widget.PopupWindow searchHistoryPopup;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Use Application context to avoid memory leak
        CrashLogger.init(getApplicationContext());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initManagers();  // ← must be first
        initAdapters();  // ← depends on thumbnailLoader from initManagers
        initViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyUiToggles();
        if (tagListManager != null) {
            tagListManager = new TagListManager(this);
            if (previewManager != null) refreshTagListSpinner();
        }
        if (gestureSettings != null) lastRunMacroId = gestureSettings.getLastRunMacroId();
        rebuildToolbarIfNeeded();
        if (previewManager != null && gestureSettings != null) updateDpadLabels();
        if (indexer.isScanning()) return;
        if (!indexer.getIndex().isEmpty()) {
            for (String folder : folderManager.getFolders()) {
                indexer.rescan(folder);
            }
        }
    }

    // ── UI toggles from Settings → "Main Window" ─────────────────────────────
    // Applied on create and every resume so returning from SettingsActivity is
    // instant: D-pad visibility and the whole tag panel follow the toggles.
    private void applyUiToggles() {
        if (previewManager != null && gestureSettings != null) {
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            boolean dpadOn = sp.getBoolean("dpad_enabled", true) && gestureSettings.isDpadEnabled();
            previewManager.setDpadVisible(dpadOn);
        }
        applyTagsGate();
        android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        if (searchBar != null) {
            searchBar.setVisibility(sp.getBoolean("show_search_bar", true) ? View.VISIBLE : View.GONE);
        }
        if (tagBarContainer != null) {
            tagBarContainer.setVisibility(sp.getBoolean("show_tag_bar", true) ? View.VISIBLE : View.GONE);
        }
        TextView stats = statsBar;
        if (stats != null) stats.setVisibility(sp.getBoolean("show_stats_bar", true)
                ? View.VISIBLE : View.GONE);
        FrameLayout preview = findViewById(R.id.previewPanel);
        if (preview != null) {
            boolean showPreview = sp.getBoolean("show_preview", true);
            preview.setVisibility(showPreview ? View.VISIBLE : View.GONE);
            applyExplorerWidth(sp.getInt("explorer_width_percent", 40), showPreview);
        }
    }

    private void applyExplorerWidth(int percent, boolean previewVisible) {
        View browser = fileBrowser;
        if (browser == null || !(browser.getParent() instanceof ViewGroup)) return;
        ViewGroup explorer = (ViewGroup) browser.getParent();
        if (!(explorer.getParent() instanceof LinearLayout)) return;
        LinearLayout.LayoutParams explorerParams = (LinearLayout.LayoutParams) explorer.getLayoutParams();
        explorerParams.weight = previewVisible ? Math.max(20, Math.min(80, percent)) : 1f;
        explorerParams.width = 0;
        explorer.setLayoutParams(explorerParams);
        View preview = findViewById(R.id.previewPanel);
        if (preview != null && preview.getParent() instanceof LinearLayout && previewVisible) {
            LinearLayout.LayoutParams previewParams = (LinearLayout.LayoutParams) preview.getLayoutParams();
            previewParams.weight = Math.max(1, 100 - explorerParams.weight);
            previewParams.width = 0;
            preview.setLayoutParams(previewParams);
        }
    }

    /** Force-hides the tag panel and marks the toggle when tags are disabled. */
    private void applyTagsGate() {
        Button btnToggle = findViewById(R.id.btnToggleTagPanel);
        LinearLayout tagPanel = findViewById(R.id.tagPanel);
        boolean enabled = tagManager == null || tagManager.isTagsEnabled();
        if (!enabled && tagPanel != null) {
            tagPanel.setVisibility(View.GONE);
        }
        if (btnToggle != null) {
            syncTagToggleButton(btnToggle,
                    enabled && tagPanel != null
                            && tagPanel.getVisibility() == View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        folderWatcher.unwatchAll();
        if (previewManager != null) previewManager.release();
        thumbnailLoader.shutdown();
        if (galleryThumbnailLoader != null) galleryThumbnailLoader.shutdown();
        if (searchHistoryPopup != null) searchHistoryPopup.dismiss();
        refreshExecutor.shutdownNow();
        statsExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (gestureSettings != null) {
            List<GestureSettings.GestureStep> hardware = gestureSettings.getSteps(
                    GestureConstants.INPUT_HARDWARE_BACK);
            if (hasAssignedGesture(hardware)) {
                executeGestureSteps(hardware);
                return;
            }
        }
        if (galleryModeActive) {
            if (galleryInfoPopup != null) {
                dismissGalleryInfoPopup();
                return;
            }
            if (galleryAdapter != null && galleryAdapter.isSelectMode()) {
                galleryAdapter.exitSelectMode();
                updateGallerySelectionToolbar(0);
            } else {
                setGalleryMode(false, true);
            }
            return;
        }
        if (mediaAdapter.isSelectMode()) {
            exitActiveSelectMode();
            btnScan.setText("SCAN");
            btnScan.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startScan(); }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU && gestureSettings != null) {
            List<GestureSettings.GestureStep> hardware = gestureSettings.getSteps(
                    GestureConstants.INPUT_HARDWARE_MENU);
            if (hasAssignedGesture(hardware)) {
                executeGestureSteps(hardware);
                return true;
            }
        }
        if (isDpadKey(keyCode)) {
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            boolean dpadEnabled = sp.getBoolean("dpad_enabled", true) && (gestureSettings == null || gestureSettings.isDpadEnabled());
            if (!dpadEnabled) return false;

            List<GestureSettings.GestureStep> steps = getDpadStepsForKey(keyCode);
            boolean isUnassigned = (steps == null || steps.isEmpty() ||
                    (steps.size() == 1 && steps.get(0).action == GestureSettings.GestureAction.NOTHING));
            if (!isUnassigned && gestureSettings != null) {
                String summary = gestureSettings.getSummary(steps);
                if (summary != null && !summary.isEmpty() && !"Nothing".equalsIgnoreCase(summary.trim())) {
                    if (previewManager != null) previewManager.showHintLabel(summary);
                } else {
                    if (previewManager != null) {
                        previewManager.hideHintLabel();
                        previewManager.flashPreviewRoot();
                    }
                }
            } else {
                if (previewManager != null) {
                    previewManager.hideHintLabel();
                    previewManager.flashPreviewRoot();
                }
            }
            return true;
        }

        if (isVolumeKey(keyCode)) {
            if (isTextInputFocused()) return super.onKeyDown(keyCode, event);
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            if (!sp.getBoolean("volume_keys_enabled", true)) return super.onKeyDown(keyCode, event);
            long now = event == null ? System.currentTimeMillis() : event.getEventTime();
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) volumeUpTime = now;
            else volumeDownTime = now;
            List<GestureSettings.GestureStep> steps = getVolumeSteps(keyCode, false);
            if (previewManager != null) previewManager.showHintLabel(gestureSettings.getSummary(steps));
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (isDpadKey(keyCode)) {
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            boolean dpadEnabled = sp.getBoolean("dpad_enabled", true) && (gestureSettings == null || gestureSettings.isDpadEnabled());
            if (!dpadEnabled) return false;

            if (previewManager != null) previewManager.hideHintLabel();
            List<GestureSettings.GestureStep> steps = getDpadStepsForKey(keyCode);
            executeDpad(steps);
            return true;
        }

        if (isVolumeKey(keyCode)) {
            if (isTextInputFocused()) return super.onKeyUp(keyCode, event);
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            if (!sp.getBoolean("volume_keys_enabled", true)) return super.onKeyUp(keyCode, event);
            long now = event == null ? System.currentTimeMillis() : event.getEventTime();
            long down = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                    ? volumeUpTime : volumeDownTime;
            long duration = down <= 0 ? 0 : now - down;
            int threshold = sp.getInt("long_press_duration", 500);
            List<GestureSettings.GestureStep> steps = getVolumeSteps(keyCode, duration >= threshold);
            if (previewManager != null) previewManager.hideHintLabel();
            executeGestureSteps(steps);
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    private boolean isDpadKey(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == android.view.KeyEvent.KEYCODE_ENTER;
    }

    private boolean isVolumeKey(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private List<GestureSettings.GestureStep> getDpadStepsForKey(int keyCode) {
        if (gestureSettings == null) return new ArrayList<>();
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:    return gestureSettings.getDpadUp();
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:  return gestureSettings.getDpadDown();
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:  return gestureSettings.getDpadLeft();
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return gestureSettings.getDpadRight();
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:     return gestureSettings.getDpadCenter();
            default:                                       return new ArrayList<>();
        }
    }

    private List<GestureSettings.GestureStep> getVolumeSteps(int keyCode, boolean longPress) {
        if (gestureSettings == null) return new ArrayList<GestureSettings.GestureStep>();
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            return longPress ? gestureSettings.getVolumeUpLong() : gestureSettings.getVolumeUp();
        }
        return longPress ? gestureSettings.getVolumeDownLong() : gestureSettings.getVolumeDown();
    }

    private boolean isTextInputFocused() {
        View focused = getCurrentFocus();
        return focused instanceof EditText;
    }

    private boolean hasAssignedGesture(List<GestureSettings.GestureStep> steps) {
        return steps != null && !steps.isEmpty() && !(steps.size() == 1
                && steps.get(0).action == GestureSettings.GestureAction.NOTHING);
    }

    public static List<MediaFile> getLatestFullList() {
            return sLatestFullList;
    }

    /** Latest tag snapshot for DashboardActivity (avoids giant Intent extras). */
    public static List<Tag> getLatestTagList() {
            return sLatestTagList;
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void initManagers() {
        indexer         = new MediaIndexer();
        tagManager      = new TagManager(this);
        tagListManager  = new TagListManager(this);
        folderManager   = new FolderManager(this);
        folderWatcher   = new FolderWatcher(this);
        searchManager   = new SearchManager();
        groupManager    = new GroupManager();
        if (!folderManager.getFolders().isEmpty()) groupManager.setWatchedRoot(folderManager.getFolders().get(0));
        cacheManager    = new CacheManager(this);
        thumbnailLoader = new ThumbnailLoader(this);
        sortManager     = new SortManager(this);
        fileStatus      = new FileStatus(this);
        sortManager.setFileStatus(fileStatus);
        filterManager   = new FilterManager(fileStatus);
        gestureSettings = new GestureSettings(this);
        lastRunMacroId = gestureSettings.getLastRunMacroId();
        windowManager   = new WindowManager(getWindowSize());
        autoOrganizer = new AutoOrganizer(this, tagManager, batchRenameManager, fileStatus);
        searchHistory  = new SearchHistory(this);
        indexer.init(this);
        indexer.setListener(this);

        // Auto-refresh tag list on any change
        tagManager.setTagChangeListener(new TagManager.TagChangeListener() {
            @Override public void onTagsChanged() {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (tagAdapter != null) tagAdapter.setTags(tagManager.getAllTags());
                        refreshTagBar();
                        updateStatsBarAsync();
                    }
                });
            }
        });
        fileStatus.setStatusChangeListener(new FileStatus.StatusChangeListener() {
            @Override public void onStatusChanged(final String path) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        MediaFile changed = findFileByPath(path);
                        if (changed != null && mediaAdapter != null) {
                            mediaAdapter.updateFileStatus(changed);
                        }
                        if (galleryAdapter != null) galleryAdapter.notifyDataSetChanged();
                        if (previewManager != null && currentIndex >= 0
                                && currentIndex < fullList.size()
                                && fullList.get(currentIndex) != null
                                && path.equals(fullList.get(currentIndex).getPath())) {
                            previewManager.updateStatusIndicator(fullList.get(currentIndex));
                        }
                        updateStatsBarAsync();
                    }
                });
            }
        });
    }

    private int getWindowSize() {
        return getSharedPreferences("window_prefs", MODE_PRIVATE)
                .getInt("window_size", 20);
    }

    private void initAdapters() {
        mediaAdapter = new MediaAdapter(thumbnailLoader, new MediaAdapter.OnFileClickListener() {
            @Override public void onFileClick(MediaFile file) { onFileSelected(file); }
        });
        mediaAdapter.setFileStatus(fileStatus);
        mediaAdapter.setHighlightProvider(new MediaAdapter.HighlightProvider() {
            @Override public boolean isHighlighted(MediaFile file) {
                return currentIndex >= 0 && currentIndex < fullList.size()
                        && file != null && fullList.get(currentIndex) != null
                        && file.getPath().equals(fullList.get(currentIndex).getPath());
            }
        });
        tagAdapter   = new TagAdapter(new TagAdapter.OnTagToggleListener() {
            @Override public void onTagToggle(String tagName, boolean applied) {
                onTagToggled(tagName, applied);
            }
        });

        // Tapping the tags line in the file list shows the quick tag popup;
        // with an active selection it targets every selected file at once.
        mediaAdapter.setOnFileLongClickListener(new MediaAdapter.OnFileLongClickListener() {
            @Override public void onFileLongClick(MediaFile file, View anchor) {
                if (mediaAdapter.isSelectMode() && mediaAdapter.getSelectedCount() > 0) {
                    showQuickTagPopup(new ArrayList<>(getActiveSelectedFiles()));
                } else {
                    List<MediaFile> single = new ArrayList<>();
                    single.add(file);
                    showQuickTagPopup(single);
                }
            }
        });

        mediaAdapter.setSelectionListener(new MediaAdapter.OnSelectionChangedListener() {
            @Override public void onSelectionChanged(final int count) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (count > 0 || (mediaAdapter != null && mediaAdapter.isSelectMode())) {
                            btnScan.setText(count > 0 ? count + " selected" : "Selection");
                            btnScan.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("Batch action")
                                            .setItems(
                                                    new String[]{
                                                            "Select all",
                                                            "Deselect all",
                                                            "Tag selected",
                                                            "Rename selected",
                                                            "Analyze colors",
                                                            "Delete selected",
                                                            "Auto-Link Sequential",
                                                            "Cancel"
                                                    },
                                                    new DialogInterface.OnClickListener() {
                                                        @Override public void onClick(DialogInterface d, int which) {
                                                            if (which == 0) mediaAdapter.selectAll();
                                                            else if (which == 1) mediaAdapter.deselectAll();
                                                            else if (which == 2) showBatchTagDialog();
                                                            else if (which == 3) showBatchRenameDialog();
                                                            else if (which == 4) showColorAnalysisDialog();
                                                            else if (which == 5) showBatchDeleteDialog();
                                                            else if (which == 6) showAutoLinkSequentialDialog();
                                                            else exitActiveSelectMode();
                                                        }
                                                    })
                                            .show();
                                }
                            });
                        } else {
                            btnScan.setText("SCAN");
                            btnScan.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) { startScan(); }
                            });
                        }
                    }
                });
            }
        });
    }

    private void initViews() {
        fileBrowser = findViewById(R.id.fileBrowser);
        fileBrowser.setLayoutManager(new LinearLayoutManager(this));
        fileBrowser.setAdapter(mediaAdapter);
        fileBrowser.setHasFixedSize(true);
        fileBrowser.setItemViewCacheSize(30);

        RecyclerView tagList = findViewById(R.id.tagList);
        tagList.setLayoutManager(new LinearLayoutManager(this));
        tagList.setAdapter(tagAdapter);
        tagList.setHasFixedSize(true);
        setupStatsAndTagBar();

        FrameLayout previewContainer = findViewById(R.id.previewPanel);
        getLayoutInflater().inflate(R.layout.panel_preview, previewContainer, true);
        previewManager = new PreviewManager(this, previewContainer, fileStatus);
        previewManager.setThumbnailLoader(thumbnailLoader);
        applyUiToggles();

        previewManager.setActionListener(new PreviewManager.ActionListener() {
            @Override public void onSkip()   { handleSkip(); }
            @Override public void onFlag()   { handleFlag(); }
            @Override public void onNext()   { navigateNext(); }
            @Override public void onPrev()   { navigatePrev(); }
            @Override public void onDpadUp()     { executeDpad(gestureSettings.getDpadUp()); }
            @Override public void onDpadDown()   { executeDpad(gestureSettings.getDpadDown()); }
            @Override public void onDpadLeft()   { executeDpad(gestureSettings.getDpadLeft()); }
            @Override public void onDpadRight()  { executeDpad(gestureSettings.getDpadRight()); }
            @Override public void onDpadCenter() { executeDpad(gestureSettings.getDpadCenter()); }
            @Override public void onTagListChanged(int index) {
                tagListManager.setActiveIndex(index);
                refreshSidePanel();
            }
        });

        // Side panel tag list click
        previewManager.getSidePanelAdapter().setListener(new SidePanelTagAdapter.OnTagClickListener() {
            @Override public void onTagClick(String tagName, boolean applied) {
                applyTagToCurrentFile(tagName, applied);
            }
        });

        // Tag list spinner
        refreshTagListSpinner();

        // Swipe gesture
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vX, float vY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > Math.abs(dy)) {
                            if (Math.abs(dx) > 100) {
                                executeSwipe(dx < 0
                                        ? gestureSettings.getLeft()
                                        : gestureSettings.getRight());
                                return true;
                            }
                        } else {
                            if (Math.abs(dy) > 100) {
                                executeSwipe(dy < 0
                                        ? gestureSettings.getUp()
                                        : gestureSettings.getDown());
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                        executeGestureSteps(gestureSettings.getSteps(GestureConstants.INPUT_TAP_SINGLE));
                        return true;
                    }

                    @Override public boolean onDoubleTap(MotionEvent event) {
                        executeGestureSteps(gestureSettings.getSteps(GestureConstants.INPUT_TAP_DOUBLE));
                        return true;
                    }

                    @Override public void onLongPress(MotionEvent event) {
                        executeGestureSteps(gestureSettings.getSteps(GestureConstants.INPUT_TAP_LONG));
                    }
                });

        previewManager.setSwipeDetector(gestureDetector);

        progressLabel = findViewById(R.id.progressLabel);
        searchBar     = findViewById(R.id.searchBar);
        if (searchBar != null) {
            searchBar.setHint("Search " + RandomGenerator.randomSyllableTag() + "…");
        }

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (searchBar != null && s.toString().trim().isEmpty()) {
                    searchBar.setHint("Search " + RandomGenerator.randomSyllableTag() + "…");
                }
                scheduleRefresh();
            }
        });

        // Focus opens the plain history dropdown; long press retains the
        // older saved-search dialog as an additional path.
        searchBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) showSearchHistoryPopup();
                else if (searchHistoryPopup != null) searchHistoryPopup.dismiss();
            }
        });
        searchBar.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showSearchHistoryDialog();
                return true;
            }
        });

        ((EditText) findViewById(R.id.tagSearch)).addTextChangedListener(
                new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                    @Override public void afterTextChanged(Editable s) {}
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                        tagAdapter.setTags(tagManager.searchTags(s.toString()));
                    }
                });

        // Tag auto-suggest
        EditText newTagInput = findViewById(R.id.newTagInput);
        final TextView tagSuggestView = new TextView(this);
        tagSuggestView.setTextColor(0xFF888888);
        tagSuggestView.setTextSize(11f);
        tagSuggestView.setPadding(8, 4, 8, 4);
        LinearLayout tagPanel = findViewById(R.id.tagPanel);
        if (tagPanel != null) {
            int btnIdx = tagPanel.indexOfChild(findViewById(R.id.btnAddTag));
            if (btnIdx >= 0) tagPanel.addView(tagSuggestView, btnIdx);
        }

        newTagInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim().toLowerCase();
                if (q.isEmpty()) {
                    tagSuggestView.setText("");
                    return;
                }
                List<Tag> matches = tagManager.searchTags(q);
                StringBuilder sb = new StringBuilder("Suggest: ");
                int shown = 0;
                for (Tag t : matches) {
                    if (shown >= 5) { sb.append("..."); break; }
                    if (shown > 0) sb.append(", ");
                    sb.append(t.getName());
                    shown++;
                }
                tagSuggestView.setText(shown > 0 ? sb.toString() : "No matches (Enter to create)");
            }
        });

        Button btnAddTag = findViewById(R.id.btnAddTag);
        btnAddTag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = newTagInput.getText().toString().trim();
                if (name.isEmpty()) return;
                tagManager.createTag(name);
                tagAdapter.setTags(tagManager.getAllTags());
                newTagInput.setText("");
                tagSuggestView.setText("");
            }
        });

        // Tag panel toggle with initialisation fix
        setupTagPanelToggle();

        btnSort = findViewById(R.id.btnSort);
        btnSort.setText(sortManager.getLabel());
        btnSort.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showSortMenu(view); }
        });

        btnFilter = findViewById(R.id.btnFilter);
        btnFilter.setText(filterManager.getLabel());
        btnFilter.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFilterMenu(v); }
        });

        btnScan = findViewById(R.id.btnScan);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startScan(); }
        });

        // Scan progress bar (initially hidden)
        scanProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);
        scanProgress.setVisibility(View.GONE);
        // Add it below the button bar
        LinearLayout buttonBar = (LinearLayout) btnScan.getParent();
        if (buttonBar != null) {
            buttonBar.addView(scanProgress, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 8));
        }

        View btnRescanView = findViewById(R.id.btnRescan);
        btnRescanView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (folderManager.isEmpty()) {
                    Toast.makeText(MainActivity.this, "No folder set", Toast.LENGTH_SHORT).show();
                    return;
                }
                // If a scan is already running, our new indexer will queue the rescan
                // instead of dropping it or crashing.
                boolean wasScanning = indexer.isScanning();
                for (String folder : folderManager.getFolders()) {
                    indexer.rescanClean(folder);
                }
                Toast.makeText(MainActivity.this,
                        wasScanning ? "Scan in progress — rescan queued" : "Rescanning…",
                        Toast.LENGTH_SHORT).show();
            }
        });
        // Long-press to repair a folder that got corrupted by the old bug
        // (manifest contains file but index missing, folder appears empty).
        btnRescanView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (folderManager.isEmpty()) return false;
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Repair folder?")
                    .setMessage("A previous crash could leave a folder with cached hashes but no visible files. Repair clears the stale cache for its folders and forces a full rescan.")
                    .setPositiveButton("Repair", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            for (String folder : folderManager.getFolders()) {
                                indexer.repairFolder(folder);
                            }
                            Toast.makeText(MainActivity.this, "Repairing…", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            }
        });

        findViewById(R.id.btnGroupBy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGroupMenu(v);
            }
        });

        findViewById(R.id.btnDashboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // No Intent extras: the full index can be thousands of Serializable
                // MediaFiles, which overflows the binder transaction limit
                // (TransactionTooLargeException). DashboardActivity reads the static
                // snapshots instead; extras stay as a compatibility fallback there.
                startActivity(new Intent(MainActivity.this, DashboardActivity.class));
            }
        });

        findViewById(R.id.btnSurprise).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fullList == null || fullList.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Nothing to pick from", Toast.LENGTH_SHORT).show();
                    return;
                }

                RecyclerView.LayoutManager lm = galleryModeActive && galleryBrowser != null
                        ? galleryBrowser.getLayoutManager() : fileBrowser.getLayoutManager();
                int firstVisible = -1;
                if (lm instanceof LinearLayoutManager) {
                    firstVisible = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                } else if (lm instanceof GridLayoutManager) {
                    firstVisible = ((GridLayoutManager) lm).findFirstVisibleItemPosition();
                }
                final int previousVisiblePos = firstVisible;

                int pickedIndex = RandomGenerator.pick(fullList.size());
                if (pickedIndex < 0) return;
                if (pickedIndex >= fullList.size()) {
                    pickedIndex = fullList.size() - 1;
                }

                final MediaFile pickedFile = fullList.get(pickedIndex);
                java.io.File fileObj = new java.io.File(pickedFile.getPath());
                if (!fileObj.exists() || !fileObj.canRead()) {
                    if (previousVisiblePos >= 0) {
                        fileBrowser.scrollToPosition(previousVisiblePos);
                    }
                    Toast.makeText(MainActivity.this, "Failed to load picked file", Toast.LENGTH_SHORT).show();
                    return;
                }

                currentIndex = pickedIndex;
                loadFileAtIndex(currentIndex);

                centerScrollToPosition(currentIndex, previousVisiblePos);
                Toast.makeText(MainActivity.this, "Surprise! Picked a random file.", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnOrganizer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RulesActivity.class));
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        Button btnDelete = findViewById(R.id.btnDelete);
        if (btnDelete != null) {
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { deleteCurrentFile(); }
            });
        }

        // Tapping the "1 / N" position counter opens the details dialog for
        // the file currently shown in the preview.
        TextView posCounter = findViewById(R.id.positionCounter);
        if (posCounter != null) {
            posCounter.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showFileDetailsDialog(); }
            });
        }

        tagAdapter.setTags(tagManager.getAllTags());
        setupGalleryMode();
    }

    private void setupStatsAndTagBar() {
        statsBar = findViewById(R.id.statsBar);
        tagBarContainer = findViewById(R.id.tagBarContainer);
        tagBarSearch = findViewById(R.id.tagBarSearch);
        tagBarSort = findViewById(R.id.tagBarSort);
        tagBarList = findViewById(R.id.tagBarList);
        if (tagBarList != null) {
            tagBarList.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, false));
            tagBarAdapter = new TagBarAdapter(new TagBarAdapter.Listener() {
                @Override public void onTagClicked(String tag) {
                    if (activeTagFilters.contains(tag)) activeTagFilters.remove(tag);
                    else activeTagFilters.add(tag);
                    filterManager.setTagFilters(activeTagFilters);
                    refreshTagBar();
                    scheduleRefresh();
                }

                @Override public void onTagLongPressed(String tag, View anchor) {
                    showTagBarContextMenu(tag, anchor);
                }
            });
            tagBarList.setAdapter(tagBarAdapter);
        }
        if (tagBarSearch != null) {
            tagBarSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    refreshTagBar();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
        if (tagBarSort != null) {
            tagBarSort.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (tagBarSortMode == TagBarSort.USAGE) tagBarSortMode = TagBarSort.ALPHABETICAL;
                    else if (tagBarSortMode == TagBarSort.ALPHABETICAL) tagBarSortMode = TagBarSort.RECENT;
                    else tagBarSortMode = TagBarSort.USAGE;
                    tagBarSort.setText(tagBarSortMode == TagBarSort.USAGE ? "Usage"
                            : tagBarSortMode == TagBarSort.ALPHABETICAL ? "A-Z" : "Recent");
                    refreshTagBar();
                }
            });
        }
        refreshTagBar();
        updateStatsBarAsync();
    }

    private void refreshTagBar() {
        if (tagBarAdapter == null || tagManager == null) return;
        String query = tagBarSearch == null ? "" : tagBarSearch.getText().toString().trim().toLowerCase();
        List<Tag> all = tagManager.getAllTags();
        if (tagBarSortMode == TagBarSort.ALPHABETICAL) {
            java.util.Collections.sort(all, new java.util.Comparator<Tag>() {
                @Override public int compare(Tag left, Tag right) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
        } else if (tagBarSortMode == TagBarSort.RECENT) {
            final List<Tag> recent = tagManager.getRecentTags(all.size());
            final Map<String, Integer> order = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < recent.size(); i++) order.put(recent.get(i).getName(), i);
            java.util.Collections.sort(all, new java.util.Comparator<Tag>() {
                @Override public int compare(Tag left, Tag right) {
                    Integer a = order.get(left.getName());
                    Integer b = order.get(right.getName());
                    if (a == null && b == null) return left.getName().compareToIgnoreCase(right.getName());
                    if (a == null) return 1;
                    if (b == null) return -1;
                    return a.compareTo(b);
                }
            });
        } else {
            java.util.Collections.sort(all, new java.util.Comparator<Tag>() {
                @Override public int compare(Tag left, Tag right) {
                    int result = Integer.compare(right.getUsageCount(), left.getUsageCount());
                    return result != 0 ? result : left.getName().compareToIgnoreCase(right.getName());
                }
            });
        }
        List<Tag> visible = new ArrayList<>();
        for (Tag tag : all) {
            if (query.isEmpty() || tag.getName().toLowerCase().contains(query)) visible.add(tag);
        }
        Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        List<MediaFile> indexed = indexer == null ? new ArrayList<MediaFile>() : indexer.getIndex();
        for (MediaFile file : indexed) {
            for (String tag : file.getTags()) {
                Integer old = counts.get(tag);
                counts.put(tag, old == null ? 1 : old + 1);
            }
        }
        tagBarAdapter.setData(visible, counts, activeTagFilters);
        if (tagBarContainer != null) {
            boolean show = getSharedPreferences("settings_prefs", MODE_PRIVATE)
                    .getBoolean("show_tag_bar", true);
            tagBarContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showTagBarContextMenu(final String tag, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename tag");
        menu.getMenu().add("Delete tag");
        menu.getMenu().add("Select all files with this tag");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                String title = item.getTitle().toString();
                if ("Rename tag".equals(title)) showRenameTagDialog(tag);
                else if ("Delete tag".equals(title)) deleteTagFromFiles(tag);
                else if ("Select all files with this tag".equals(title)) selectFilesWithTag(tag);
                return true;
            }
        });
        menu.show();
    }

    private void showRenameTagDialog(final String oldName) {
        final EditText input = new EditText(this);
        input.setText(oldName);
        input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle("Rename tag").setView(input)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String next = TagText.plain(input.getText().toString());
                        if (next.isEmpty() || next.equals(oldName)) return;
                        if (tagManager.hasTagName(next)) {
                            Toast.makeText(MainActivity.this, "Tag already exists", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        List<MediaFile> files = indexer.getIndex();
                        for (MediaFile file : files) {
                            if (file.hasTag(oldName)) {
                                tagManager.removeTag(file, oldName);
                                tagManager.applyTag(file, next);
                            }
                        }
                        tagManager.deleteTag(oldName);
                        tagManager.createTag(next);
                        activeTagFilters.remove(oldName);
                        activeTagFilters.add(next);
                        filterManager.setTagFilters(activeTagFilters);
                        refreshTagBar();
                        scheduleRefresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void deleteTagFromFiles(final String tag) {
        new AlertDialog.Builder(this).setTitle("Delete tag?")
                .setMessage(tag)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        for (MediaFile file : indexer.getIndex()) {
                            if (file.hasTag(tag)) tagManager.removeTag(file, tag);
                        }
                        tagManager.deleteTag(tag);
                        activeTagFilters.remove(tag);
                        filterManager.setTagFilters(activeTagFilters);
                        refreshTagBar();
                        scheduleRefresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void selectFilesWithTag(String tag) {
        if (galleryModeActive && galleryAdapter != null) {
            galleryAdapter.enterSelectMode();
            for (MediaFile file : fullList) {
                if (file.hasTag(tag)) galleryAdapter.selectPath(file.getPath());
            }
        } else if (mediaAdapter != null) {
            mediaAdapter.enterSelectMode();
            for (MediaFile file : fullList) {
                if (file.hasTag(tag)) mediaAdapter.selectPath(file.getPath());
            }
        }
    }

    private MediaFile findFileByPath(String path) {
        if (path == null) return null;
        for (MediaFile file : fullList) if (file != null && path.equals(file.getPath())) return file;
        return indexer == null ? null : findInList(indexer.getIndex(), path);
    }

    private MediaFile findInList(List<MediaFile> files, String path) {
        if (files == null) return null;
        for (MediaFile file : files) if (file != null && path.equals(file.getPath())) return file;
        return null;
    }

    private void updateStatsBarAsync() {
        if (statsBar == null || statsExecutor.isShutdown()) return;
        final long request = ++statsSequence;
        final List<MediaFile> totalSnapshot = indexer == null
                ? new ArrayList<MediaFile>() : indexer.getIndex();
        final List<MediaFile> filteredSnapshot = new ArrayList<>(fullList);
        statsExecutor.submit(new Runnable() {
            @Override public void run() {
                int tagged = 0;
                int completed = 0;
                int flagged = 0;
                for (MediaFile file : filteredSnapshot) {
                    if (file == null) continue;
                    if (!file.getTags().isEmpty()) tagged++;
                    if (fileStatus != null && fileStatus.isDone(file.getPath())) completed++;
                    if (fileStatus != null && fileStatus.isFlagged(file.getPath())) flagged++;
                }
                final String text = "Total: " + totalSnapshot.size()
                        + "  Filtered: " + filteredSnapshot.size()
                        + "  Tagged: " + tagged
                        + "  Completed: " + completed
                        + "  Flagged: " + flagged;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (request == statsSequence && statsBar != null) statsBar.setText(text);
                    }
                });
            }
        });
    }

    private void setupTagPanelToggle() {
        Button btnToggle = findViewById(R.id.btnToggleTagPanel);
        LinearLayout tagPanel = findViewById(R.id.tagPanel);
        if (btnToggle == null || tagPanel == null) return;

        // Initial state: hidden
        tagPanel.setVisibility(View.GONE);
        syncTagToggleButton(btnToggle, false);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tagManager != null && !tagManager.isTagsEnabled()) {
                    Toast.makeText(MainActivity.this,
                            "Tags are disabled (Settings → Main Window)",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean visible = tagPanel.getVisibility() == View.VISIBLE;
                if (visible) {
                    tagPanel.setVisibility(View.GONE);
                } else {
                    tagPanel.setVisibility(View.VISIBLE);
                }
                syncTagToggleButton(btnToggle, !visible);
            }
        });
    }

    private void syncTagToggleButton(Button btn, boolean panelVisible) {
        btn.setText(panelVisible ? "Tags ✓" : "Tags");
        btn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        panelVisible ? 0xFFE94560 : 0xFF2A2A3E));
    }

    // ── Tag list spinner ──────────────────────────────────────────────────────

    private void refreshTagListSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                tagListManager.getListNames());
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        previewManager.setTagListSpinner(adapter,
                tagListManager.getActiveIndex());
    }

    private void refreshSidePanel() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);
        if (file == null) return;
        TagList active = tagListManager.getActiveList();
        if (active == null) return;
        List<String> tags = active.getTags();
        previewManager.setSidePanelTags(tags, file.getTags());
        updateDpadLabels();
    }

    private void updateDpadLabels() {
        previewManager.updateDpadLabels(
                gestureSettings.getSummary(gestureSettings.getDpadUp()),
                gestureSettings.getSummary(gestureSettings.getDpadDown()),
                gestureSettings.getSummary(gestureSettings.getDpadLeft()),
                gestureSettings.getSummary(gestureSettings.getDpadRight()),
                gestureSettings.getSummary(gestureSettings.getDpadCenter())
        );
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    private void scheduleRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        int delay = indexer != null && indexer.getIndex().size() > 500 ? 300 : 150;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { executeRefresh(); }
        }, delay);
    }

    private void executeRefresh() {
        refreshPending = false;

        final String query = searchBar != null
                ? searchBar.getText().toString().trim()
                : "";
        if (!query.isEmpty()) saveSearchToHistory(query);
        final String currentPathBeforeRefresh = currentIndex >= 0
                && currentIndex < fullList.size()
                && fullList.get(currentIndex) != null
                ? fullList.get(currentIndex).getPath() : null;

        final List<MediaFile> initialBase = indexer.getIndex();
        final long sequence = ++refreshSequence;
        refreshExecutor.submit(new Runnable() {
            @Override public void run() {
                List<MediaFile> base = initialBase == null
                        ? new ArrayList<MediaFile>() : new ArrayList<>(initialBase);
                if (!query.isEmpty()) {
                    searchManager.setFullList(base);
                    base = searchManager.search(query);
                }

                List<MediaFile> flattened = new ArrayList<>();
                try {
                    List<Group> groups = groupManager.group(base);
                    if (groups != null) {
                        for (Group group : groups) {
                            if (group != null && group.getFiles() != null) {
                                flattened.addAll(group.getFiles());
                            }
                        }
                    }
                } catch (Exception e) {
                    flattened = new ArrayList<>(base);
                }

                flattened = filterManager.apply(flattened);
                sortManager.sort(flattened);
                final List<MediaFile> result = flattened;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (sequence != refreshSequence || isFinishing()) return;
                        fullList = result;
                        // Recalculate the highlight by path after every filter,
                        // search, and sort. A numeric index from the old list is
                        // never reused for a different file.
                        currentIndex = findIndexByPath(fullList, currentPathBeforeRefresh);
                        windowManager.setFullIndex(fullList);
                        sLatestFullList = windowManager.getFullIndexSnapshot();
                        sLatestTagList  = new ArrayList<>(tagManager.getAllTags());
                        if (currentIndex >= 0) windowManager.centerOn(currentIndex);
                        updateWindow();
                        updateGalleryData();
                        updateProgress();
                        refreshTagBar();
                        updateStatsBarAsync();
                    }
                });
            }
        });
    }

    // ── Window ────────────────────────────────────────────────────────────────

    private void updateWindow() {
        List<MediaFile> windowSnapshot = windowManager.getWindowSnapshot();
        synchronized (currentFiles) {
            currentFiles.clear();
            currentFiles.addAll(windowSnapshot);
        }
        List<MediaFile> safeCurrentFiles = getCurrentFilesSnapshot();

        List<String> windowPaths = new ArrayList<>();
        for (MediaFile f : safeCurrentFiles) windowPaths.add(f.getPath());
        thumbnailLoader.evictOutsideWindow(windowPaths);

        mediaAdapter.setFiles(safeCurrentFiles);

        // If we have a current preview file, re-select it in the (new) window
        if (currentIndex >= 0 && currentIndex < fullList.size()) {
            // The provider reads MainActivity.currentIndex; this notification
            // only rebinds the old and new highlight visuals.
            mediaAdapter.notifyHighlightChanged();
            scrollFileListToCurrent(currentIndex);
        } else if (mediaAdapter != null) {
            mediaAdapter.notifyHighlightChanged();
        }
        if (galleryModeActive) {
            TextView empty = findViewById(R.id.emptyStateView);
            if (empty != null) empty.setVisibility(View.GONE);
        } else {
            updateEmptyState();
        }
    }

    private void shiftWindowIfNeeded(int absoluteIndex) {
        if (windowManager.nearEnd(absoluteIndex)) {
            windowManager.shiftForward();
            updateWindow();
        } else if (windowManager.nearStart(absoluteIndex)) {
            windowManager.shiftBack();
            updateWindow();
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private void startScan() {
        if (folderManager.isEmpty()) {
            showAddFolderDialog();
            return;
        }
        if (indexer.isScanning()) {
            Toast.makeText(this, "Scan already in progress…", Toast.LENGTH_SHORT).show();
            return;
        }
        btnScan.setEnabled(false);
        btnScan.setText("…");
        for (String folder : folderManager.getFolders()) {
            folderWatcher.watch(folder);
        }
        indexer.scanFolders(folderManager.getFolders());
    }

    // ── Gesture execution ─────────────────────────────────────────────────────

    private void executeSwipe(List<GestureSettings.GestureStep> steps) {
        executeGestureSteps(steps);
    }

    private void executeDpad(List<GestureSettings.GestureStep> steps) {
        if (gestureSettings != null && !gestureSettings.isDpadEnabled()) return;
        executeGestureSteps(steps);
    }

    private void executeGestureSteps(List<GestureSettings.GestureStep> steps) {
        if (steps == null) return;
        for (GestureSettings.GestureStep step : steps) {
            if (step == null) continue;
            if (step.action == GestureSettings.GestureAction.APPLY_TAG
                    && step.tag != null && !step.tag.isEmpty()) {
                applyQuickTagToCurrent(step.tag);
            } else if (step.action == GestureSettings.GestureAction.MACRO) {
                executeMacro(step.tag);
            } else if (step.action == GestureSettings.GestureAction.REPEAT_LAST_MACRO) {
                executeRepeatLastMacro();
            } else {
                executeAction(step.action);
            }
        }
    }

    private void executeAction(GestureSettings.GestureAction action) {
        if (action == null) return;
        switch (action) {
            case NEXT_FILE: navigateNext(); break;
            case PREV_FILE: navigatePrev(); break;
            case SKIP: handleSkip(); break;
            case FLAG: handleFlag(); break;
            case DONE: handleDone(); break; // legacy mappings only; not a default/picker action
            case FILTER_CYCLE: cycleFilter(); break;
            case QUICK_TAGS: openQuickTagAction(); break;
            case QUICK_RANDOM_TAG: applyQuickRandomTag(); break;
            case SURPRISE_ME:
                View surprise = findViewById(R.id.btnSurprise);
                if (surprise != null) surprise.performClick();
                break;
            case OPEN_GALLERY: setGalleryMode(true, true); break;
            case TOGGLE_GALLERY: setGalleryMode(!galleryModeActive, true); break;
            case OPEN_DASHBOARD: openDashboard(); break;
            case OPEN_RULES: openRules(); break;
            case OPEN_COLOR_ANALYZER: showColorAnalysisDialog(); break;
            case OPEN_DUPLICATE_FINDER: showDuplicateFinderDialog(); break;
            case OPEN_SETTINGS: startActivity(new Intent(this, SettingsActivity.class)); break;
            case EXPORT_SETTINGS: exportSettingsFromOverflow(); break;
            case TRIGGER_RESCAN:
            case SCAN: requestRescan(); break;
            case CYCLE_TAG_PRESETS: cycleTagPreset(); break;
            case NEXT_PAGE: navigateByPage(1); break;
            case PREVIOUS_PAGE: navigateByPage(-1); break;
            case JUMP_FIRST: jumpToFile(0); break;
            case JUMP_LAST: jumpToFile(fullList.size() - 1); break;
            case TOGGLE_STATS_BAR: toggleStatsBar(); break;
            case TOGGLE_INFO_OVERLAY: toggleInfoOverlay(); break;
            case TOGGLE_SELECTION_CURRENT: toggleSelectionOnCurrent(); break;
            case SWEEP_SELECT_FORWARD: sweepSelect(1); break;
            case SWEEP_SELECT_BACKWARD: sweepSelect(-1); break;
            case SELECT_ALL: selectAllActiveFiles(); break;
            case DESELECT_ALL: deselectAllActiveFiles(); break;
            case SORT_PICKER: showSortBuilder(); break;
            case FILTER_PICKER: showFilterMenu(btnFilter); break;
            case GROUP_PICKER: showGroupMenu(findViewById(R.id.btnGroupBy)); break;
            case UNDO: undoLastAction(); break;
            case DELETE: deleteCurrentFile(); break;
            case TOGGLE_TAG_PANEL: findViewById(R.id.btnToggleTagPanel).performClick(); break;
            case CYCLE_TAG_BAR_SORT: cycleTagBarSort(); break;
            case NOTHING: break;
            default: break;
        }
    }

    private void applyQuickTagToCurrent(String tag) {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);
        tagManager.applyOrUndo(file, tag);
        mediaAdapter.updateFileTags(file);
        if (galleryAdapter != null) galleryAdapter.notifyDataSetChanged();
        refreshSidePanel();
        refreshTagBar();
        updateProgress();
        updateStatsBarAsync();
    }

    private void openQuickTagAction() {
        if (!tagManager.isTagsEnabled()) return;
        List<MediaFile> selected = getActiveSelectedFiles();
        if (!selected.isEmpty()) showBatchTagDialog();
        else if (currentIndex >= 0 && currentIndex < fullList.size()) {
            List<MediaFile> one = new ArrayList<MediaFile>();
            one.add(fullList.get(currentIndex));
            showQuickTagPopup(one);
        } else Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
    }

    /** The same one-tap action is used by the toolbar and gesture dispatcher. */
    private void applyQuickRandomTag() {
        if (!tagManager.isTagsEnabled()) return;
        List<MediaFile> targets = getActiveSelectedFiles();
        if (targets.isEmpty() && currentIndex >= 0 && currentIndex < fullList.size()) {
            targets.add(fullList.get(currentIndex));
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> existing = new java.util.HashSet<String>();
        for (Tag tag : tagManager.getAllTags()) existing.add(tag.getName());
        String tag = RandomGenerator.uniqueSuffixTag(
                RandomGenerator.randomSyllableTag(), existing);
        tagManager.createTag(tag);
        for (MediaFile file : targets) {
            tagManager.applyTag(file, tag);
            mediaAdapter.updateFileTags(file);
        }
        syncUiAfterTagging(targets);
        refreshTagBar();
        Toast.makeText(this, "Tagged " + targets.size() + " files as " + tag,
                Toast.LENGTH_SHORT).show();
    }

    private void openDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
    }

    private void openRules() {
        startActivity(new Intent(this, RulesActivity.class));
    }

    private void requestRescan() {
        View rescan = findViewById(R.id.btnRescan);
        if (rescan != null) rescan.performClick();
    }

    private void cycleTagPreset() {
        if (tagListManager == null || tagListManager.getCount() == 0) return;
        int next = (tagListManager.getActiveIndex() + 1) % tagListManager.getCount();
        tagListManager.setActiveIndex(next);
        refreshTagListSpinner();
        refreshSidePanel();
    }

    private void navigateByPage(int direction) {
        if (fullList.isEmpty()) return;
        int page = getSharedPreferences("settings_prefs", MODE_PRIVATE)
                .getInt("page_size", getWindowSize());
        int next = Math.max(0, Math.min(fullList.size() - 1,
                currentIndex + (direction * Math.max(1, page))));
        loadFileAtIndex(next);
    }

    private void jumpToFile(int index) {
        if (index < 0 || index >= fullList.size()) return;
        loadFileAtIndex(index);
    }

    private void toggleStatsBar() {
        boolean show = getSharedPreferences("settings_prefs", MODE_PRIVATE)
                .getBoolean("show_stats_bar", true);
        getSharedPreferences("settings_prefs", MODE_PRIVATE).edit()
                .putBoolean("show_stats_bar", !show).apply();
        if (statsBar != null) statsBar.setVisibility(!show ? View.VISIBLE : View.GONE);
    }

    private void toggleInfoOverlay() {
        infoOverlayVisible = !infoOverlayVisible;
        if (infoOverlayVisible && currentIndex >= 0 && currentIndex < fullList.size()) {
            previewManager.showHintLabel(fullList.get(currentIndex).getName());
        } else if (previewManager != null) previewManager.hideHintLabel();
    }

    private void undoLastAction() {
        int undone = 0;
        if (autoOrganizer != null && autoOrganizer.canUndo()) undone = autoOrganizer.undoLastRun();
        else if (batchRenameManager.canUndo()) undone = batchRenameManager.undo().succeeded;
        Toast.makeText(this, undone > 0 ? "Undone: " + undone + " operations" : "Nothing to undo",
                Toast.LENGTH_SHORT).show();
        scheduleRefresh();
    }

    private String lastRunMacroId = "";

    private void toggleSelectionOnCurrent() {
        if (currentIndex < 0 || currentIndex >= fullList.size() || mediaAdapter == null) return;
        if (!mediaAdapter.isSelectMode()) mediaAdapter.enterSelectMode();
        mediaAdapter.togglePath(fullList.get(currentIndex).getPath());
    }

    private void sweepSelect(int direction) {
        if (fullList.isEmpty()) return;
        if (mediaAdapter == null) return;
        if (!mediaAdapter.isSelectMode()) mediaAdapter.enterSelectMode();
        int next = currentIndex;
        if (next < 0) next = direction > 0 ? 0 : fullList.size() - 1;
        else next = Math.max(0, Math.min(fullList.size() - 1, next + (direction > 0 ? 1 : -1)));
        mediaAdapter.selectPath(fullList.get(next).getPath());
        loadFileAtIndex(next);
    }

    private void executeMacro(String id) {
        if (id == null || id.isEmpty()) return;
        GestureSettings.GestureMacro macro = gestureSettings.getMacro(id);
        if (macro == null) {
            Toast.makeText(this, "Macro not found", Toast.LENGTH_SHORT).show();
            return;
        }
        if (macro.actions == null || macro.actions.isEmpty()) {
            Toast.makeText(this, "Macro '" + macro.name + "' has no steps", Toast.LENGTH_SHORT).show();
            return;
        }

        lastRunMacroId = id;
        gestureSettings.setLastRunMacroId(id);

        List<MediaFile> targets = new ArrayList<>();
        List<MediaFile> selectedTargets = getActiveSelectedFiles();
        if (!selectedTargets.isEmpty()) {
            targets.addAll(selectedTargets);
        } else if (currentIndex >= 0 && currentIndex < fullList.size()) {
            targets.add(fullList.get(currentIndex));
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
            return;
        }

        com.mediasorter.organizer.Rule syntheticRule = new com.mediasorter.organizer.Rule();
        syntheticRule.name = macro.name;
        syntheticRule.enabled = true;
        syntheticRule.conditions = new ArrayList<>();
        syntheticRule.action = new com.mediasorter.organizer.MacroCompositeAction(macro.actions);

        int failedStep = autoOrganizer.execute(syntheticRule, targets);

        if (failedStep == -1) {
            Toast.makeText(this, "Macro '" + macro.name + "' applied", Toast.LENGTH_SHORT).show();
            for (MediaFile f : targets) {
                mediaAdapter.updateFile(f);
            }
            scheduleRefresh();
        } else {
            autoOrganizer.undoLastRun();
            Toast.makeText(this, "Macro '" + macro.name + "' failed at step " + (failedStep + 1), Toast.LENGTH_SHORT).show();
            scheduleRefresh();
        }
    }

    private void executeRepeatLastMacro() {
        if (lastRunMacroId == null || lastRunMacroId.isEmpty()) {
            Toast.makeText(this, "No macro run yet.", Toast.LENGTH_SHORT).show();
        } else {
            executeMacro(lastRunMacroId);
        }
    }

    // ── Quick actions ─────────────────────────────────────────────────────────

    private void handleSkip() {
    if (currentIndex < 0 || currentIndex >= fullList.size()) return;
    MediaFile file = fullList.get(currentIndex);
    fileStatus.setSkipped(file.getPath());
    autoOrganizer.applyToSingle(file);   // <-- now file exists
    navigateNext();
}
    private void handleFlag() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);
        if (fileStatus.isFlagged(file.getPath())) fileStatus.clearStatus(file.getPath());
        else fileStatus.setFlagged(file.getPath());
        // Update both visible indicators in the same UI turn; no refresh is
        // needed to make a gesture/button flag visible.
        if (mediaAdapter != null) mediaAdapter.updateFileStatus(file);
        if (previewManager != null) previewManager.updateStatusIndicator(file);
        if (galleryAdapter != null) galleryAdapter.notifyDataSetChanged();
        autoOrganizer.setActiveListIndex(currentIndex);
        autoOrganizer.applyToSingle(file);
        updateStatsBarAsync();
    }

    private void handleDone() {
    if (currentIndex < 0 || currentIndex >= fullList.size()) return;
    MediaFile file = fullList.get(currentIndex);
    fileStatus.setDone(file.getPath());
    autoOrganizer.applyToSingle(file); 
    navigateNext();
}
    private void cycleFilter() {
        FilterManager.Filter[] filters = {
                FilterManager.Filter.ALL, FilterManager.Filter.UNTAGGED,
                FilterManager.Filter.FLAGGED, FilterManager.Filter.SKIPPED,
                FilterManager.Filter.DONE
        };
        int next = 0;
        for (int i = 0; i < filters.length; i++) {
            if (filters[i] == filterManager.getCurrent()) {
                next = (i + 1) % filters.length;
                break;
            }
        }
        filterManager.setFilter(filters[next]);
        btnFilter.setText(filterManager.getLabel());
        scheduleRefresh();
    }

    // ── Tag application ───────────────────────────────────────────────────────

    private void applyTagToCurrentFile(String tagName, boolean applied) {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);

        if (applied) tagManager.applyTag(file, tagName);
        else         tagManager.removeTag(file, tagName);

        fullList.set(currentIndex, file);
        // Partial update — only rebind tags text, skip thumbnail reload
        mediaAdapter.updateFileTags(file);
        if (galleryAdapter != null) galleryAdapter.notifyDataSetChanged();
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        refreshSidePanel();
        updateProgress();
    }

    private void onTagToggled(String tagName, boolean applied) {
        applyTagToCurrentFile(tagName, applied);
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateNext() {
        if (fullList.isEmpty()) return;
        currentIndex = (currentIndex + 1) % fullList.size();
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
        // Defer precaching slightly to avoid jank during rapid file switching
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { precacheAdjacent(); }
        }, 120);
    }

    private void navigatePrev() {
        if (fullList.isEmpty()) return;
        currentIndex = (currentIndex - 1 + fullList.size()) % fullList.size();
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { precacheAdjacent(); }
        }, 120);
    }

    /** Pre-cache thumbnails for the previous and next files. */
    private void precacheAdjacent() {
        if (fullList.isEmpty()) return;
        List<MediaFile> toCache = new ArrayList<>();
        int nextIdx = (currentIndex + 1) % fullList.size();
        int prevIdx = (currentIndex - 1 + fullList.size()) % fullList.size();
        if (nextIdx != currentIndex && nextIdx < fullList.size()) toCache.add(fullList.get(nextIdx));
        if (prevIdx != currentIndex && prevIdx < fullList.size()) toCache.add(fullList.get(prevIdx));
        if (!toCache.isEmpty()) thumbnailLoader.precache(toCache);
    }

    private void loadFileAtIndex(int absoluteIndex) {
        if (absoluteIndex < 0 || absoluteIndex >= fullList.size()) {
            currentIndex = -1;
            if (mediaAdapter != null) mediaAdapter.notifyHighlightChanged();
            return;
        }
        // MainActivity.currentIndex is the single source of truth. Update the
        // adapter's provider-driven highlight before any preview work.
        currentIndex = absoluteIndex;
        if (mediaAdapter != null) mediaAdapter.notifyHighlightChanged();
        MediaFile file = fullList.get(absoluteIndex);
        previewManager.load(file);
        previewManager.setPosition(absoluteIndex + 1, fullList.size());
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        refreshSidePanel();

        // Keep the file list in sync with the preview (bidirectional)
        scrollFileListToCurrent(absoluteIndex);
        if (galleryModeActive && galleryBrowser != null) {
            galleryBrowser.scrollToPosition(absoluteIndex);
            updateGalleryMemoryWindow();
        }
    }

    private List<MediaFile> getCurrentFilesSnapshot() {
        synchronized (currentFiles) {
            return new ArrayList<>(currentFiles);
        }
    }

    private int findIndexByPath(List<MediaFile> files, String path) {
        if (files == null || path == null) return -1;
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (file != null && path.equals(file.getPath())) return i;
        }
        return -1;
    }

    /**
     * Scroll the file browser after the current layout pass. The adapter is a
     * window, so the absolute index is translated to the current window copy
     * before the posted LinearLayoutManager call.
     */
    private void scrollFileListToCurrent(int absoluteIndex) {
        if (fileBrowser == null || absoluteIndex < 0 || absoluteIndex >= fullList.size()) return;
        final List<MediaFile> windowCopy = getCurrentFilesSnapshot();
        if (windowCopy.isEmpty()) return;
        final String targetPath = fullList.get(absoluteIndex).getPath();
        final int windowPos = findIndexByPath(windowCopy, targetPath);
        if (windowPos < 0) return;

        fileBrowser.post(new Runnable() {
            @Override public void run() {
                if (fileBrowser == null) return;
                android.view.ViewGroup.LayoutParams params = fileBrowser.getLayoutParams();
                LinearLayoutManager manager = (LinearLayoutManager) fileBrowser.getLayoutManager();
                if (manager == null) return;
                int itemHeight = 0;
                View child = manager.findViewByPosition(windowPos);
                if (child != null) itemHeight = child.getHeight();
                if (itemHeight <= 0) {
                    itemHeight = Math.max(1, galleryDp(72));
                }
                int offset = Math.max(0, (fileBrowser.getHeight() - itemHeight) / 2);
                manager.scrollToPositionWithOffset(windowPos, offset);
            }
        });
    }

    private void onFileSelected(MediaFile file) {
        int abs = windowManager.findAbsoluteIndex(file);
        if (abs < 0) return;
        currentIndex = abs;
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
    }

    // ── Batch dialogs ─────────────────────────────────────────────────────────

    private void showBatchTagDialog() {
        showBatchTagDialog((Map<String, Integer>) null);
    }

    private void showBatchTagDialog(final Map<String, Integer> pendingStates) {
        if (tagManager != null && !tagManager.isTagsEnabled()) {
            Toast.makeText(this,
                    "Tags are disabled (Settings → Main Window)",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final List<MediaFile> selectedFiles = getActiveSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        List<Tag> allTags = tagManager.getAllTags();
        final List<QuickTagItem> items = new ArrayList<>();
        for (Tag choice : allTags) {
            String name = choice.getName();
            int count = choice.getUsageCount();
            int hasCount = 0;
            for (MediaFile f : selectedFiles) {
                if (f.hasTag(name)) {
                    hasCount++;
                }
            }
            int initialType;
            if (hasCount == selectedFiles.size()) {
                initialType = 1; // all have it
            } else if (hasCount == 0) {
                initialType = 0; // none have it
            } else {
                initialType = 2; // some have it (mixed)
            }
            
            QuickTagItem item = new QuickTagItem(name, count, initialType);
            if (pendingStates != null && pendingStates.containsKey(name)) {
                item.currentType = pendingStates.get(name);
            }
            items.add(item);
        }

        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF161616); // match background color of dark theme
        listView.setDivider(new android.graphics.drawable.ColorDrawable(0xFF2A2A3E));
        listView.setDividerHeight((int) (1 * getResources().getDisplayMetrics().density));
        listView.setAdapter(new QuickTagListAdapter(items));

        LinearLayout batchTagContent = new LinearLayout(this);
        batchTagContent.setOrientation(LinearLayout.VERTICAL);
        final Button randomTagButton = makeSmallButton("🎲");
        randomTagButton.setContentDescription("Apply quick random tag");
        batchTagContent.addView(randomTagButton);
        batchTagContent.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final AlertDialog batchTagDialog = new AlertDialog.Builder(this)
                .setTitle("Tag " + selectedFiles.size() + " files")
                .setView(batchTagContent)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (QuickTagItem item : items) {
                            if (item.currentType == item.initialType) continue;
                            for (MediaFile file : selectedFiles) {
                                if (item.currentType == 1) {
                                    tagManager.applyTag(file, item.name);
                                } else if (item.currentType == 0) {
                                    tagManager.removeTag(file, item.name);
                                }
                            }
                        }
                        for (MediaFile file : selectedFiles) mediaAdapter.updateFile(file);
                        exitActiveSelectMode();
                        btnScan.setText("SCAN");
                        btnScan.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { startScan(); }
                        });
                        scheduleRefresh();
                        Toast.makeText(MainActivity.this,
                                "Tagged " + selectedFiles.size() + " files",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("＋ New tag", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        // Snapshot the current states
                        Map<String, Integer> edits = new java.util.HashMap<>();
                        for (QuickTagItem item : items) edits.put(item.name, item.currentType);
                        showNewTagDialog(selectedFiles, new Runnable() {
                            @Override public void run() { showBatchTagDialog(edits); }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        randomTagButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Set<String> existing = new java.util.HashSet<String>();
                for (Tag tag : tagManager.getAllTags()) existing.add(tag.getName());
                String generated = RandomGenerator.uniqueSuffixTag(
                        RandomGenerator.randomSyllableTag(), existing);
                tagManager.createTag(generated);
                for (MediaFile file : selectedFiles) {
                    tagManager.applyTag(file, generated);
                    mediaAdapter.updateFileTags(file);
                }
                syncUiAfterTagging(selectedFiles);
                Toast.makeText(MainActivity.this,
                        "Tagged " + selectedFiles.size() + " files as " + generated,
                        Toast.LENGTH_SHORT).show();
                batchTagDialog.dismiss();
            }
        });
        batchTagDialog.show();
    }

    private void showBatchRenameDialog() {
    List<MediaFile> selectedFiles = getActiveSelectedFiles();
    if (selectedFiles.isEmpty()) return;

    // Save current settings to restore if cancelled
    final BatchRenameManager.Separator oldSep = batchRenameManager.getSeparator();
    final BatchRenameManager.Order oldOrd = batchRenameManager.getOrder();
    final BatchRenameManager.Case oldCase = batchRenameManager.getCaseMode();
    final String oldPrefix = batchRenameManager.getPrefix();
    final String oldSuffix = batchRenameManager.getSuffix();
    final boolean oldIncludeFolder = batchRenameManager.isIncludeFolder();
    final BatchRenameManager.Numbering oldNum = batchRenameManager.getNumbering();
    final int oldNumStart = batchRenameManager.getNumberStart();
    final int oldNumPad = batchRenameManager.getNumberPadding();
    final String oldDateFormat = batchRenameManager.getDateFormat();
    final BatchRenameManager.NumberPosition oldNumPos = batchRenameManager.getNumberPosition();
    final String oldNumSep = batchRenameManager.getNumberSeparator();
    final Map<String, String> oldReplacements = batchRenameManager.getReplacements();
    final String oldPattern = batchRenameManager.getPattern();

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 16, 32, 16);

    // --- Separator ---
    layout.addView(makeLabel("Separator:"));
    String[] sepOptions = {"Underscore (_)", "Dash (-)", "Space ( )", "None"};
    android.widget.Spinner sepSpinner = makeSpinner(sepOptions);
    sepSpinner.setSelection(batchRenameManager.getSeparator().ordinal());
    layout.addView(sepSpinner);

    // --- Order ---
    layout.addView(makeLabel("Order:"));
    String[] ordOptions = {"Tags Only", "Original + Tags", "Tags + Original"};
    android.widget.Spinner ordSpinner = makeSpinner(ordOptions);
    ordSpinner.setSelection(batchRenameManager.getOrder().ordinal());
    layout.addView(ordSpinner);

    // --- Case ---
    layout.addView(makeLabel("Case:"));
    String[] caseOptions = {"As-is", "Lowercase", "Uppercase"};
    android.widget.Spinner caseSpinner = makeSpinner(caseOptions);
    caseSpinner.setSelection(batchRenameManager.getCaseMode().ordinal());
    layout.addView(caseSpinner);

    // --- Prefix ---
    layout.addView(makeLabel("Prefix (optional):"));
    EditText prefixEdit = new EditText(this);
    prefixEdit.setText(batchRenameManager.getPrefix());
    prefixEdit.setTextColor(0xFFFFFFFF);
    layout.addView(prefixEdit);

    // --- Suffix ---
    layout.addView(makeLabel("Suffix (optional):"));
    EditText suffixEdit = new EditText(this);
    suffixEdit.setText(batchRenameManager.getSuffix());
    suffixEdit.setTextColor(0xFFFFFFFF);
    layout.addView(suffixEdit);

    // --- Include folder ---
    android.widget.CheckBox includeFolderCheck = new android.widget.CheckBox(this);
    includeFolderCheck.setText("Include folder name");
    includeFolderCheck.setTextColor(0xFFCCCCCC);
    includeFolderCheck.setChecked(batchRenameManager.isIncludeFolder());
    layout.addView(includeFolderCheck);

    // --- Numbering ---
    layout.addView(makeLabel("Numbering:"));
    String[] numOptions = {"None", "Sequential", "Date"};
    android.widget.Spinner numSpinner = makeSpinner(numOptions);
    numSpinner.setSelection(batchRenameManager.getNumbering().ordinal());
    layout.addView(numSpinner);

    // Number position & separator
    layout.addView(makeLabel("Number position:"));
    String[] posOptions = {"Before name", "After name"};
    android.widget.Spinner numPosSpinner = makeSpinner(posOptions);
    numPosSpinner.setSelection(batchRenameManager.getNumberPosition().ordinal());
    layout.addView(numPosSpinner);

    EditText numSepEdit = new EditText(this);
    numSepEdit.setText(batchRenameManager.getNumberSeparator());
    numSepEdit.setHint("Separator for number");
    numSepEdit.setTextColor(0xFFFFFFFF);
    layout.addView(numSepEdit);

    // Sequential settings
    layout.addView(makeLabel("Sequential start:"));
    EditText numStartEdit = new EditText(this);
    numStartEdit.setText(String.valueOf(batchRenameManager.getNumberStart()));
    numStartEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    numStartEdit.setTextColor(0xFFFFFFFF);
    layout.addView(numStartEdit);

    layout.addView(makeLabel("Sequential padding (digits):"));
    EditText numPadEdit = new EditText(this);
    numPadEdit.setText(String.valueOf(batchRenameManager.getNumberPadding()));
    numPadEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    numPadEdit.setTextColor(0xFFFFFFFF);
    layout.addView(numPadEdit);

    // Date format
    layout.addView(makeLabel("Date format:"));
    EditText dateFmtEdit = new EditText(this);
    dateFmtEdit.setText(batchRenameManager.getDateFormat());
    dateFmtEdit.setTextColor(0xFFFFFFFF);
    layout.addView(dateFmtEdit);

    // --- Find & Replace ---
    layout.addView(makeLabel("Find & Replace (old=new, one per line):"));
    EditText replacementsEdit = new EditText(this);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : batchRenameManager.getReplacements().entrySet()) {
        if (sb.length() > 0) sb.append("\n");
        sb.append(e.getKey()).append("=").append(e.getValue());
    }
    replacementsEdit.setText(sb.toString());
    replacementsEdit.setTextColor(0xFFFFFFFF);
    replacementsEdit.setMinLines(2);
    layout.addView(replacementsEdit);

    // --- CUSTOM PATTERN ---
    layout.addView(makeLabel("Custom pattern (optional):"));
    EditText patternEdit = new EditText(this);
    patternEdit.setText(batchRenameManager.getPattern() != null ? batchRenameManager.getPattern() : "");
    patternEdit.setHint("{PREFIX}_{FOLDER}{COUNTER:3}_{TAGS}_{ORIGINAL}{SUFFIX}{EXT}");
    patternEdit.setTextColor(0xFFFFFFFF);
    patternEdit.setMinLines(1);
    layout.addView(patternEdit);

    // Preview text
    layout.addView(makeLabel("Preview:"));
    final TextView previewText = new TextView(this);
    previewText.setTextColor(0xFF888888);
    previewText.setTextSize(10f);
    layout.addView(previewText);

    // --- Runnable that updates preview after reading UI values ---
    final Runnable refreshPreview = new Runnable() {
        @Override public void run() {
            // Apply all UI values to manager
            batchRenameManager.setSeparator(sepFromPos(sepSpinner.getSelectedItemPosition()));
            batchRenameManager.setOrder(ordFromPos(ordSpinner.getSelectedItemPosition()));
            batchRenameManager.setCaseMode(caseFromPos(caseSpinner.getSelectedItemPosition()));
            batchRenameManager.setPrefix(prefixEdit.getText().toString().trim());
            batchRenameManager.setSuffix(suffixEdit.getText().toString().trim());
            batchRenameManager.setIncludeFolder(includeFolderCheck.isChecked());

            switch (numSpinner.getSelectedItemPosition()) {
                case 0: batchRenameManager.setNumbering(BatchRenameManager.Numbering.NONE); break;
                case 1: batchRenameManager.setNumbering(BatchRenameManager.Numbering.SEQUENTIAL); break;
                case 2: batchRenameManager.setNumbering(BatchRenameManager.Numbering.DATE); break;
            }
            batchRenameManager.setNumberPosition(
                    numPosSpinner.getSelectedItemPosition() == 0
                    ? BatchRenameManager.NumberPosition.BEFORE
                    : BatchRenameManager.NumberPosition.AFTER);
            batchRenameManager.setNumberSeparator(numSepEdit.getText().toString());

            try { batchRenameManager.setNumberStart(Integer.parseInt(numStartEdit.getText().toString())); }
            catch (Exception e) { /* keep old */ }
            try { batchRenameManager.setNumberPadding(Integer.parseInt(numPadEdit.getText().toString())); }
            catch (Exception e) { /* keep old */ }
            batchRenameManager.setDateFormat(dateFmtEdit.getText().toString().trim());

            // Replacements
            batchRenameManager.clearReplacements();
            String replText = replacementsEdit.getText().toString().trim();
            if (!replText.isEmpty()) {
                for (String line : replText.split("\n")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        batchRenameManager.addReplacement(parts[0], parts[1]);
                    }
                }
            }

            // --- PATTERN ---
            String patternText = patternEdit.getText().toString().trim();
            if (!patternText.isEmpty()) {
                batchRenameManager.setPattern(patternText);
            } else {
                batchRenameManager.setPattern(null);
            }

            // Build preview
            List<BatchRenameManager.RenamePreview> previews = batchRenameManager.preview(selectedFiles);
            StringBuilder psb = new StringBuilder();
            int shown = Math.min(previews.size(), 5);
            for (int i = 0; i < shown; i++) {
                BatchRenameManager.RenamePreview p = previews.get(i);
                psb.append(i + 1).append(". ").append(p.originalName).append(" → ").append(p.newName);
                if (p.hasConflict) psb.append(" ⚠ conflict");
                psb.append("\n");
            }
            if (previews.size() > 5) psb.append("... and ").append(previews.size() - 5).append(" more");
            previewText.setText(psb.toString());
        }
    };

    // --- Listeners ---
    android.widget.AdapterView.OnItemSelectedListener spinnerListener = new android.widget.AdapterView.OnItemSelectedListener() {
        @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            refreshPreview.run();
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    };

    TextWatcher tw = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { refreshPreview.run(); }
    };

    sepSpinner.setOnItemSelectedListener(spinnerListener);
    ordSpinner.setOnItemSelectedListener(spinnerListener);
    caseSpinner.setOnItemSelectedListener(spinnerListener);
    numSpinner.setOnItemSelectedListener(spinnerListener);
    numPosSpinner.setOnItemSelectedListener(spinnerListener);

    prefixEdit.addTextChangedListener(tw);
    suffixEdit.addTextChangedListener(tw);
    numStartEdit.addTextChangedListener(tw);
    numPadEdit.addTextChangedListener(tw);
    dateFmtEdit.addTextChangedListener(tw);
    numSepEdit.addTextChangedListener(tw);
    replacementsEdit.addTextChangedListener(tw);
    patternEdit.addTextChangedListener(tw);  // monitor pattern changes

    includeFolderCheck.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
        @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            refreshPreview.run();
        }
    });

    // Initial preview
    mainHandler.post(refreshPreview);

    android.widget.ScrollView sv = new android.widget.ScrollView(this);
    sv.addView(layout);

    new AlertDialog.Builder(this)
        .setTitle("Batch Rename " + selectedFiles.size() + " files")
        .setView(sv)
        .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                // The preview updater has already applied the settings, so just commit
                List<BatchRenameManager.RenamePreview> previews = batchRenameManager.preview(selectedFiles);
                BatchRenameManager.RenameResult result = batchRenameManager.apply(previews);
                Toast.makeText(MainActivity.this, "Renamed: " + result.succeeded
                    + (result.failed > 0 ? "  Failed: " + result.failed : ""), Toast.LENGTH_SHORT).show();
                exitActiveSelectMode();
                btnScan.setText("SCAN");
                btnScan.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { startScan(); }
                });
                scheduleRefresh();
            }
        })
        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                // Restore original settings
                batchRenameManager.setSeparator(oldSep);
                batchRenameManager.setOrder(oldOrd);
                batchRenameManager.setCaseMode(oldCase);
                batchRenameManager.setPrefix(oldPrefix);
                batchRenameManager.setSuffix(oldSuffix);
                batchRenameManager.setIncludeFolder(oldIncludeFolder);
                batchRenameManager.setNumbering(oldNum);
                batchRenameManager.setNumberStart(oldNumStart);
                batchRenameManager.setNumberPadding(oldNumPad);
                batchRenameManager.setDateFormat(oldDateFormat);
                batchRenameManager.setNumberPosition(oldNumPos);
                batchRenameManager.setNumberSeparator(oldNumSep);
                batchRenameManager.setReplacements(oldReplacements);
                batchRenameManager.setPattern(oldPattern);
            }
        })
        .setNeutralButton("Undo", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (batchRenameManager.canUndo()) {
                    BatchRenameManager.RenameResult result = batchRenameManager.undo();
                    Toast.makeText(MainActivity.this,
                            "Undone: " + result.succeeded + " files", Toast.LENGTH_SHORT).show();
                    exitActiveSelectMode();
                    scheduleRefresh();
                }
            }
        })
        .show();
}
                
// Helper to create a label TextView
private TextView makeLabel(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(0xFFCCCCCC);
    tv.setTextSize(12f);
    return tv;
}

// Helper to create a Spinner
private Spinner makeSpinner(String[] options) {
    Spinner sp = new Spinner(this);
    ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
    ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    sp.setAdapter(ad);
    return sp;
}

    private BatchRenameManager.Separator sepFromPos(int pos) {
        switch (pos) {
            case 1:  return BatchRenameManager.Separator.DASH;
            case 2:  return BatchRenameManager.Separator.SPACE;
            case 3:  return BatchRenameManager.Separator.NONE;
            default: return BatchRenameManager.Separator.UNDERSCORE;
        }
    }

    private BatchRenameManager.Order ordFromPos(int pos) {
        switch (pos) {
            case 1:  return BatchRenameManager.Order.ORIGINAL_THEN_TAGS;
            case 2:  return BatchRenameManager.Order.TAGS_THEN_ORIGINAL;
            default: return BatchRenameManager.Order.TAGS_ONLY;
        }
    }

    private BatchRenameManager.Case caseFromPos(int pos) {
        switch (pos) {
            case 1:  return BatchRenameManager.Case.LOWERCASE;
            case 2:  return BatchRenameManager.Case.UPPERCASE;
            default: return BatchRenameManager.Case.AS_IS;
        }
    }

    // ── Color analysis dialog ────────────────────────────────────────────────

    private void showColorAnalysisDialog() {
        List<MediaFile> colorTargets = getActiveSelectedFiles();
        if (colorTargets.isEmpty() && currentIndex >= 0 && currentIndex < fullList.size()) {
            colorTargets = new ArrayList<MediaFile>();
            colorTargets.add(fullList.get(currentIndex));
        }
        if (colorTargets.isEmpty()) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<MediaFile> selectedFiles = colorTargets;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        layout.addView(makeLabel("Number of colors per image (1-10):"));
        EditText colorCountInput = new EditText(this);
        colorCountInput.setText("3");
        colorCountInput.setTextColor(0xFFFFFFFF);
        colorCountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        colorCountInput.setBackground(null);
        layout.addView(colorCountInput);

        layout.addView(makeLabel("Similarity threshold (1-100, lower = stricter):"));
        EditText threshInput = new EditText(this);
        threshInput.setText("20");
        threshInput.setTextColor(0xFFFFFFFF);
        threshInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        threshInput.setBackground(null);
        layout.addView(threshInput);

        layout.addView(makeLabel("Mode:"));
        String[] modes = {
                "Tag with colors",
                "Rename by color",
                "Group similar",
                "Tag + Rename",
                "All three",
                "Signature tag (golden ticket)",
                "Golden ticket (tag + rename)"
        };
        android.widget.Spinner modeSpin = makeSpinner(modes);
        layout.addView(modeSpin);

        TextView goldenHint = new TextView(this);
        goldenHint.setText(
                "Golden ticket: every image gets the ONE colour that is rarest\n"
                + "across the analysed set but meaningful inside the image —\n"
                + "its own signature (e.g. \"Deep Lagoon\"). Re-runs skip\n"
                + "files that already carry a signature tag, so they're fast.");
        goldenHint.setTextColor(0xFF888888);
        goldenHint.setTextSize(11f);
        goldenHint.setPadding(0, 8, 0, 0);
        layout.addView(goldenHint);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle("Color analysis — " + selectedFiles.size() + " files")
                .setView(sv)
                .setPositiveButton("Analyze", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int topN;
                        float threshold;
                        try {
                            topN = Integer.parseInt(colorCountInput.getText().toString().trim());
                            topN = Math.max(1, Math.min(10, topN));
                        } catch (Exception e) { topN = 3; }

                        try {
                            threshold = Float.parseFloat(threshInput.getText().toString().trim());
                            threshold = Math.max(1f, Math.min(100f, threshold));
                        } catch (Exception e) { threshold = 20f; }
                        ColorAnalyzer.Mode mode;
                        switch (modeSpin.getSelectedItemPosition()) {
                            case 0:  mode = ColorAnalyzer.Mode.TAG;            break;
                            case 1:  mode = ColorAnalyzer.Mode.RENAME;         break;
                            case 2:  mode = ColorAnalyzer.Mode.GROUP;          break;
                            case 3:  mode = ColorAnalyzer.Mode.TAG_AND_RENAME; break;
                            case 5:  mode = ColorAnalyzer.Mode.SIGNATURE;      break;
                            case 6:  mode = ColorAnalyzer.Mode.GOLDEN_TICKET;  break;
                            default: mode = ColorAnalyzer.Mode.ALL;            break;
                        }
                        final ColorAnalyzer.Mode finalMode = mode;
                        final int finalTopN = topN;
                        final float finalThreshold = threshold;

                        folderWatcher.pauseAll();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final List<ColorAnalyzer.Result> results =
                                        ColorAnalyzer.analyze(selectedFiles, finalTopN,
                                                finalThreshold, finalMode, tagManager, batchRenameManager);
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        folderWatcher.resumeAll();
                                        int ok = 0, signed = 0;
                                        java.util.Set<String> touchedFolders = new java.util.LinkedHashSet<>();
                                        for (ColorAnalyzer.Result r : results) {
                                            if (r.success) ok++;
                                            if (r.signatureColor != null) signed++;
                                            String p = (r.path != null) ? r.path : null;
                                            int slash = (p == null) ? -1 : p.lastIndexOf('/');
                                            if (slash > 0) touchedFolders.add(p.substring(0, slash));
                                        }
                                        // Watchers were paused while files were renamed —
                                        // reconcile the index so renamed entries don't linger.
                                        for (String folder : touchedFolders) indexer.rescan(folder);
                                        boolean golden = finalMode == ColorAnalyzer.Mode.SIGNATURE
                                                || finalMode == ColorAnalyzer.Mode.GOLDEN_TICKET;
                                        exitActiveSelectMode();
                                        btnScan.setText("SCAN");
                                        btnScan.setOnClickListener(new View.OnClickListener() {
                                            @Override public void onClick(View v) { startScan(); }
                                        });
                                        scheduleRefresh();
                                        Toast.makeText(MainActivity.this,
                                                golden
                                                    ? "★ Golden tickets: " + signed + " / "
                                                        + selectedFiles.size() + " files"
                                                    : "Analyzed " + ok + " / "
                                                        + selectedFiles.size() + " files",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    // ── Sort / Filter / Group ─────────────────────────────────────────────────

    private void showSortMenu(View anchor) {
        showSortBuilder();
    }

    private void showSortBuilder() {
        final List<SortManager.SortCriterion> working = new ArrayList<>();
        for (SortManager.SortCriterion criterion : sortManager.getSortSequence()) {
            working.add(criterion.copy());
        }
        final List<String> workingTagRules = sortManager.getTagRules();

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20, 8, 20, 4);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sort order")
                .setView(scroll)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Cancel", null)
                .create();

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override public void run() {
                renderSortBuilderRows(rows, working, render[0]);
            }
        };
        render[0].run();

        Button add = new Button(this);
        add.setText("Add Criterion");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showSortCriterionPicker(working, workingTagRules, render[0]);
            }
        });
        content.addView(add);

        Button clear = new Button(this);
        clear.setText("Clear All");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                working.clear();
                render[0].run();
            }
        });
        content.addView(clear);

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                Button apply = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                apply.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        sortManager.saveSortSequence(working);
                        sortManager.saveTagRules(workingTagRules);
                        btnSort.setText(sortManager.getLabel());
                        scheduleRefresh();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private void renderSortBuilderRows(final LinearLayout rows,
                                       final List<SortManager.SortCriterion> working,
                                       final Runnable render) {
        rows.removeAllViews();
        if (working.isEmpty()) {
            TextView empty = makeLabel("No criteria. Apply uses Name A-Z.");
            rows.addView(empty);
            return;
        }
        for (int i = 0; i < working.size(); i++) {
            addSortBuilderRow(rows, working, i, render);
        }
    }

    private void addSortBuilderRow(LinearLayout rows,
                                   final List<SortManager.SortCriterion> working,
                                   final int index,
                                   final Runnable render) {
        final SortManager.SortCriterion criterion = working.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 3, 0, 3);

        TextView name = makeLabel((index + 1) + ". "
                + SortManager.criterionLabel(criterion.id));
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        Button direction = makeSortBuilderButton(
                SortManager.directionLabel(criterion.id, criterion.direction));
        direction.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                criterion.direction = SortManager.toggleDirection(
                        criterion.id, criterion.direction);
                render.run();
            }
        });
        row.addView(direction);

        Button up = makeSortBuilderButton("Up");
        up.setEnabled(index > 0);
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (index <= 0) return;
                SortManager.SortCriterion previous = working.get(index - 1);
                working.set(index - 1, criterion);
                working.set(index, previous);
                render.run();
            }
        });
        row.addView(up);

        Button down = makeSortBuilderButton("Down");
        down.setEnabled(index < working.size() - 1);
        down.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (index >= working.size() - 1) return;
                SortManager.SortCriterion next = working.get(index + 1);
                working.set(index + 1, criterion);
                working.set(index, next);
                render.run();
            }
        });
        row.addView(down);

        Button remove = makeSortBuilderButton("Remove");
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                working.remove(index);
                render.run();
            }
        });
        row.addView(remove);
        rows.addView(row);
    }

    private Button makeSortBuilderButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(10f);
        button.setAllCaps(false);
        return button;
    }

    private void showSortCriterionPicker(final List<SortManager.SortCriterion> working,
                                         final List<String> tagRules,
                                         final Runnable render) {
        final String[] labels = {
                "Name", "Date", "Size", "File type", "Tag count",
                "First tag value", "Tag rule match", "Flagged status",
                "Skip status", "Done status", "Manual order", "Random shuffle",
                "Path depth", "Color family", "Sequence group", "Random within group",
                "Duplicate status", "Metadata presence", "Filename word count"
        };
        new AlertDialog.Builder(this)
                .setTitle("Add criterion")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String id = sortCriterionId(which);
                        if (SortManager.RANDOM.equals(id)
                                && containsSortCriterion(working, SortManager.RANDOM)) {
                            return;
                        }
                        final SortManager.SortCriterion added = new SortManager.SortCriterion(
                                id, SortManager.defaultDirection(id));
                        working.add(added);
                        if (SortManager.TAG_RULE_MATCH.equals(id)) {
                            showSortTagRulePicker(tagRules, render);
                        } else {
                            render.run();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String sortCriterionId(int index) {
        switch (index) {
            case 1:  return SortManager.DATE;
            case 2:  return SortManager.SIZE;
            case 3:  return SortManager.TYPE;
            case 4:  return SortManager.TAG_COUNT;
            case 5:  return SortManager.FIRST_TAG;
            case 6:  return SortManager.TAG_RULE_MATCH;
            case 7:  return SortManager.FLAGGED;
            case 8:  return SortManager.SKIPPED;
            case 9:  return SortManager.DONE;
            case 10: return SortManager.MANUAL_ORDER;
            case 11: return SortManager.RANDOM;
            case 12: return SortManager.PATH_DEPTH;
            case 13: return SortManager.COLOR_FAMILY;
            case 14: return SortManager.SEQUENCE_GROUP;
            case 15: return SortManager.RANDOM_WITHIN_GROUP;
            case 16: return SortManager.DUPLICATE_STATUS;
            case 17: return SortManager.METADATA_PRESENCE;
            case 18: return SortManager.WORD_COUNT;
            default: return SortManager.NAME;
        }
    }

    private boolean containsSortCriterion(List<SortManager.SortCriterion> list, String id) {
        for (SortManager.SortCriterion criterion : list) {
            if (criterion != null && id.equals(criterion.id)) return true;
        }
        return false;
    }

    private void showSortTagRulePicker(final List<String> parentRules,
                                       final Runnable renderBuilder) {
        final List<String> rules = new ArrayList<>(parentRules);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20, 8, 20, 4);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);

        Button addTag = new Button(this);
        addTag.setText("Add tag from picker");
        content.addView(addTag);
        Button clear = new Button(this);
        clear.setText("Clear tag rule");
        content.addView(clear);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tag rule order")
                .setView(content)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override public void run() {
                renderSortTagRuleRows(rows, rules, render[0]);
            }
        };
        render[0].run();

        addTag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showSortTagChoice(rules, render[0]);
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                rules.clear();
                render[0].run();
            }
        });
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View view) {
                                parentRules.clear();
                                parentRules.addAll(rules);
                                renderBuilder.run();
                                dialog.dismiss();
                            }
                        });
            }
        });
        dialog.show();
    }

    private void renderSortTagRuleRows(LinearLayout rows, final List<String> rules,
                                       final Runnable render) {
        rows.removeAllViews();
        if (rules.isEmpty()) {
            rows.addView(makeLabel("No tag rules. Unmatched files rank last."));
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = makeLabel((i + 1) + ". " + rules.get(i));
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);
            Button up = makeSortBuilderButton("Up");
            up.setEnabled(i > 0);
            up.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (index == 0) return;
                    String previous = rules.get(index - 1);
                    rules.set(index - 1, rules.get(index));
                    rules.set(index, previous);
                    render.run();
                }
            });
            row.addView(up);
            Button down = makeSortBuilderButton("Down");
            down.setEnabled(i < rules.size() - 1);
            down.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (index >= rules.size() - 1) return;
                    String next = rules.get(index + 1);
                    rules.set(index + 1, rules.get(index));
                    rules.set(index, next);
                    render.run();
                }
            });
            row.addView(down);
            Button remove = makeSortBuilderButton("Remove");
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    rules.remove(index);
                    render.run();
                }
            });
            row.addView(remove);
            rows.addView(row);
        }
    }

    private void showSortTagChoice(final List<String> rules, final Runnable render) {
        List<Tag> tags = tagManager.getAllTags();
        final String[] names = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) names[i] = tags.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("Add tag to rule")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < names.length && !rules.contains(names[which])) {
                            rules.add(names[which]);
                            render.run();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFilterMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("All");
        menu.getMenu().add("Untagged");
        menu.getMenu().add("Flagged");
        menu.getMenu().add("Skipped");
        menu.getMenu().add("Done");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getTitle().toString()) {
                    case "All":      filterManager.setFilter(FilterManager.Filter.ALL);      break;
                    case "Untagged": filterManager.setFilter(FilterManager.Filter.UNTAGGED); break;
                    case "Flagged":  filterManager.setFilter(FilterManager.Filter.FLAGGED);  break;
                    case "Skipped":  filterManager.setFilter(FilterManager.Filter.SKIPPED);  break;
                    case "Done":     filterManager.setFilter(FilterManager.Filter.DONE);     break;
                }
                btnFilter.setText(filterManager.getLabel());
                scheduleRefresh();
                return true;
            }
        });
        menu.show();
    }

    private void showGroupMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("By File Type");
        menu.getMenu().add("By Tag");
        menu.getMenu().add("By Date");
        menu.getMenu().add("By Folder");
        menu.getMenu().add("By Tag Prefix");
        menu.getMenu().add("By Sequence Group");
        menu.getMenu().add("By Color Profile");
        menu.getMenu().add("By Directory Depth");
        menu.getMenu().add("By Manual Group");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getTitle().toString()) {
                    case "By File Type": groupManager.setGroupBy(Group.GroupBy.FILE_TYPE); break;
                    case "By Tag":       groupManager.setGroupBy(Group.GroupBy.TAG);       break;
                    case "By Date":      groupManager.setGroupBy(Group.GroupBy.DATE);      break;
                    case "By Folder":    groupManager.setGroupBy(Group.GroupBy.FOLDER);    break;
                    case "By Tag Prefix": groupManager.setGroupBy(Group.GroupBy.TAG_PREFIX); break;
                    case "By Sequence Group": groupManager.setGroupBy(Group.GroupBy.SEQUENCE_GROUP); break;
                    case "By Color Profile":
                        if (!groupManager.hasColorProfiles(fullList)) {
                            Toast.makeText(MainActivity.this, "Run color analysis first.", Toast.LENGTH_SHORT).show();
                            return true;
                        }
                        groupManager.setGroupBy(Group.GroupBy.COLOR_PROFILE);
                        break;
                    case "By Directory Depth": groupManager.setGroupBy(Group.GroupBy.DIRECTORY_DEPTH); break;
                    case "By Manual Group": groupManager.setGroupBy(Group.GroupBy.MANUAL_GROUP); break;
                }
                scheduleRefresh();
                return true;
            }
        });
        menu.show();
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private void updateProgress() {
        int total  = fullList.size();
        int tagged = 0;
        for (MediaFile f : fullList) {
            if (!f.getTags().isEmpty()) tagged++;
        }
        int pct = total > 0 ? (tagged * 100 / total) : 0;
        if (progressLabel != null) {
            progressLabel.setText(tagged + " / " + total + "  (" + pct + "%)");
        }
        updateStatsBarAsync();
    }

    // ── Folder dialog ─────────────────────────────────────────────────────────

    private void showAddFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("/sdcard/DCIM");
        new AlertDialog.Builder(this)
                .setTitle("Add folder to watch")
                .setView(input)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String path = input.getText().toString().trim();
                        if (!path.isEmpty()) {
                            folderManager.addFolder(path);
                            startScan();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Delete file (now uses full refresh for consistency) ──────────────────

    private void deleteCurrentFile() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);

        new AlertDialog.Builder(this)
                .setTitle("Delete file?")
                .setMessage(file.getName())
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        boolean deleted = indexer.deleteFile(file.getPath());
                        if (deleted) {
                            // Full refresh rebuilds everything consistently
                            scheduleRefresh();
                            Toast.makeText(MainActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Could not delete", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Batch delete ─────────────────────────────────────────────────────────

    private void showBatchDeleteDialog() {
        List<MediaFile> selectedFiles = getActiveSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        new AlertDialog.Builder(this)
            .setTitle("Delete " + selectedFiles.size() + " files?")
            .setMessage("\"Move to trash\u00a0\" moves them to a .trash folder "
                    + "inside your first watched folder, so you can restore "
                    + "them later with any file manager.\n\n"
                    + "\"Delete permanently\" cannot be undone.")
            .setNeutralButton("Move to trash", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    int moved = moveSelectionToTrash(selectedFiles);
                    exitActiveSelectMode();
                    btnScan.setText("SCAN");
                    btnScan.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { startScan(); }
                    });
                    scheduleRefresh();
                    Toast.makeText(MainActivity.this,
                            moved > 0 ? moved + " file(s) moved to trash"
                                      : "Trash failed (no watched folder?)",
                            Toast.LENGTH_SHORT).show();
                }
            })
            .setPositiveButton("Delete permanently", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    int deleted = 0;
                    for (MediaFile file : selectedFiles) {
                        if (indexer.deleteFile(file.getPath())) deleted++;
                    }
                    exitActiveSelectMode();
                    btnScan.setText("SCAN");
                    btnScan.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { startScan(); }
                    });
                    scheduleRefresh();
                    Toast.makeText(MainActivity.this,
                            "Deleted " + deleted + " files", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Shows name/path/size/dimensions/date/status/tags for the current file. */
    private void showFileDetailsDialog() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) {
            Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaFile file = fullList.get(currentIndex);
        if (file == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Name:  ").append(file.getName()).append("\n");
        sb.append("Path:  ").append(file.getPath()).append("\n");
        sb.append("Size:  ").append(file.getFormattedSize()).append("\n");
        if (file.getWidth() > 0 && file.getHeight() > 0) {
            sb.append("Dimensions:  ")
              .append(file.getWidth()).append(" × ")
              .append(file.getHeight()).append("\n");
        }
        sb.append("Type:  ").append(file.getType().name().toLowerCase()).append("\n");
        if (file.getDateAdded() > 0) {
            sb.append("Modified:  ").append(
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault())
                    .format(new java.util.Date(file.getDateAdded()))).append("\n");
        }
        if (fileStatus != null) {
            sb.append("Status:  ")
              .append(fileStatus.getStatus(file.getPath()).name().toLowerCase())
              .append("\n");
        }
        List<String> tags = file.getTags();
        sb.append("Tags (").append(tags.size()).append("):  ");
        if (tags.isEmpty()) {
            sb.append("—");
        } else {
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tags.get(i));
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("File details")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Rename", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showFileRenameDialog(file);
                }
            })
            .show();
    }

    /** Moves each selected file into <first watched folder>/.trash or configured trash_path. */
    private int moveSelectionToTrash(List<MediaFile> selectedFiles) {
        List<String> folders = folderManager.getFolders();
        if (folders.isEmpty()) return 0;
        android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
        String customTrash = sp.getString("trash_path", "");
        java.io.File trashDir;
        if (customTrash != null && !customTrash.trim().isEmpty()) {
            trashDir = new java.io.File(customTrash.trim());
        } else {
            trashDir = new java.io.File(folders.get(0), ".trash");
        }
        if (!trashDir.exists() && !trashDir.mkdirs()) return 0;

        int moved = 0;
        for (MediaFile file : selectedFiles) {
            java.io.File src = new java.io.File(file.getPath());
            if (!src.exists()) continue;
            java.io.File dst = new java.io.File(trashDir, src.getName());
            int n = 1;
            String base = src.getName();
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext  = dot > 0 ? base.substring(dot) : "";
            while (dst.exists()) {
                dst = new java.io.File(trashDir, stem + "(" + n + ")" + ext);
                n++;
            }
            if (src.renameTo(dst)) {
                // File already sits in .trash — drop it from the index/UI
                // without deleteFile(), which would touch the disk again.
                indexer.removeFromIndexOnly(file.getPath());
                fileStatus.clearStatus(file.getPath());
                moved++;
            }
        }
        return moved;
    }

    // ── Search history / saved searches ─────────────────────────────────────

    private void showSearchHistoryPopup() {
        if (searchBar == null || searchHistory == null) return;
        if (searchHistoryPopup != null) searchHistoryPopup.dismiss();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ListView list = new ListView(this);
        final List<String> values = searchHistory.getRecentSearches();
        if (values.isEmpty()) {
            TextView empty = makeLabel("No search history");
            content.addView(empty);
        } else {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, values);
            list.setAdapter(adapter);
            list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                                  int position, long id) {
                    if (position < 0 || position >= values.size()) return;
                    searchBar.setText(values.get(position));
                    searchBar.setSelection(searchBar.length());
                    if (searchHistoryPopup != null) searchHistoryPopup.dismiss();
                }
            });
            content.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        Button clear = new Button(this);
        clear.setText("Clear history");
        clear.setAllCaps(false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                searchHistory.clearRecentSearches();
                if (searchHistoryPopup != null) searchHistoryPopup.dismiss();
            }
        });
        content.addView(clear);
        int width = Math.max(searchBar.getWidth(), galleryDp(220));
        searchHistoryPopup = new android.widget.PopupWindow(content, width,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        searchHistoryPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF2A2A3E));
        searchHistoryPopup.setOutsideTouchable(true);
        searchHistoryPopup.showAsDropDown(searchBar);
    }

    private void showSearchHistoryDialog() {
        List<String> recent = searchHistory.getRecentSearches();
        List<String> saved  = searchHistory.getSavedSearches();

        List<String> items = new ArrayList<>();
        List<String> types = new ArrayList<>(); // "recent" or "saved"

        if (!saved.isEmpty()) {
            items.add("--- Saved Searches ---");
            types.add("header");
            for (String s : saved) {
                items.add("★ " + s);
                types.add("saved");
            }
        }
        if (!recent.isEmpty()) {
            items.add("--- Recent ---");
            types.add("header");
            for (String s : recent) {
                items.add(s);
                types.add("recent");
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "No search history yet", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Search History")
            .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    if (which >= types.size()) return;
                    String type = types.get(which);
                    if ("header".equals(type)) return;

                    String itemText = items.get(which);
                    final String query = itemText.startsWith("★ ")
                            ? itemText.substring(2) : itemText;

                    if ("saved".equals(type)) {
                        // Long-press-like: offer to remove or re-run
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(query)
                            .setItems(new String[]{"Run search", "Remove from saved"},
                                    new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d2, int w2) {
                                            if (w2 == 0) {
                                                searchBar.setText(query);
                                            } else {
                                                searchHistory.removeSavedSearch(query);
                                                Toast.makeText(MainActivity.this,
                                                        "Removed", Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    })
                            .show();
                    } else {
                        searchBar.setText(query);
                    }
                }
            })
            .setNeutralButton("Save current", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String current = searchBar.getText().toString().trim();
                    if (!current.isEmpty()) {
                        searchHistory.saveSearch(current);
                        Toast.makeText(MainActivity.this,
                                "Search saved", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Save search to history when user submits (presses enter or refreshes). */
    private void saveSearchToHistory(String query) {
        if (query != null && !query.trim().isEmpty()) {
            searchHistory.addRecentSearch(query.trim());
        }
    }

    // ── Duplicate finder ────────────────────────────────────────────────────

    private void showDuplicateFinderDialog() {
        List<MediaFile> files = indexer.getIndex();
        if (files.isEmpty()) {
            Toast.makeText(this, "No files to check", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Scanning for duplicates...", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override public void run() {
                final List<DuplicateFinder.DuplicateGroup> dupes =
                        DuplicateFinder.findDuplicates(files, new DuplicateFinder.ProgressCallback() {
                            @Override public void onProgress(int scanned, int total, String name) {}
                        });
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        for (MediaFile file : fullList) file.setDuplicate(false);
                        for (DuplicateFinder.DuplicateGroup group : dupes) {
                            for (MediaFile file : group.files) file.setDuplicate(true);
                        }
                        showDuplicateResults(dupes);
                    }
                });
            }
        }).start();
    }

    private void showDuplicateResults(List<DuplicateFinder.DuplicateGroup> groups) {
        if (groups.isEmpty()) {
            Toast.makeText(this, "No duplicates found", Toast.LENGTH_SHORT).show();
            return;
        }

        int totalDupes = 0;
        for (DuplicateFinder.DuplicateGroup g : groups) totalDupes += g.files.size() - 1;

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(groups.size()).append(" duplicate groups");
        sb.append(" (").append(totalDupes).append(" extra copies)\n\n");

        int shown = Math.min(groups.size(), 10);
        for (int i = 0; i < shown; i++) {
            DuplicateFinder.DuplicateGroup g = groups.get(i);
            long mb = g.size / (1024 * 1024);
            sb.append("Group ").append(i + 1).append(": ").append(mb).append(" MB (")
              .append(g.files.size()).append(" files)\n");
            for (MediaFile f : g.files) {
                sb.append("  ").append(f.getName()).append("\n");
            }
            sb.append("\n");
        }
        if (groups.size() > 10) sb.append("... and ").append(groups.size() - 10).append(" more groups");

        new AlertDialog.Builder(this)
            .setTitle("Duplicates Found")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    // ── Quick tag toggle from file list (tags-tap popup) ────────────────────

    /** Tags offered in the quick-tag popup: most recent first, then most used. */
    private List<Tag> getQuickTagChoices() {
        Map<String, Tag> merged = new java.util.LinkedHashMap<>();
        for (Tag t : tagManager.getRecentTags(6)) merged.put(t.getName(), t);
        for (Tag t : tagManager.getTopTags(10)) {
            if (!merged.containsKey(t.getName())) merged.put(t.getName(), t);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Quick tag popup for one or more files (a file tapped in the browser, or
     * the whole multi-selection). A tag is pre-checked only when *every* target
     * already carries it; Apply then sets the exact checked state on all of
     * them. New tags can be created without leaving the dialog — and pending
     * checkbox edits survive the detour into the create dialog.
     */
    private void showQuickTagPopup(final List<MediaFile> targets) {
        showQuickTagPopup(targets, (Map<String, Integer>) null);
    }

    private void showQuickTagPopup(final List<MediaFile> targets,
                                   final Map<String, Integer> pendingStates) {
        if (targets == null || targets.isEmpty()) return;
        if (tagManager != null && !tagManager.isTagsEnabled()) {
            Toast.makeText(this,
                    "Tags are disabled (Settings → Main Window)",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final List<Tag> choices = getQuickTagChoices();
        if (choices.isEmpty()) {
            // Nothing to choose from yet — go straight to creating the first
            // tag. Only come back to the popup once a tag actually exists,
            // otherwise "Back" would loop between the two dialogs forever.
            showNewTagDialog(targets, new Runnable() {
                @Override public void run() {
                    if (!tagManager.getAllTags().isEmpty()) {
                        showQuickTagPopup(targets);
                    } else {
                        Toast.makeText(MainActivity.this,
                                "No tags yet", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return;
        }

        final String title = targets.size() == 1
                ? "Quick tags — " + targets.get(0).getName()
                : "Quick tags — " + targets.size() + " files";

        final List<QuickTagItem> items = new ArrayList<>();
        for (Tag choice : choices) {
            String name = choice.getName();
            int count = choice.getUsageCount();
            int hasCount = 0;
            for (MediaFile f : targets) {
                if (f.hasTag(name)) {
                    hasCount++;
                }
            }
            int initialType;
            if (hasCount == targets.size()) {
                initialType = 1; // all have it
            } else if (hasCount == 0) {
                initialType = 0; // none have it
            } else {
                initialType = 2; // some have it (mixed)
            }
            
            QuickTagItem item = new QuickTagItem(name, count, initialType);
            if (pendingStates != null && pendingStates.containsKey(name)) {
                item.currentType = pendingStates.get(name);
            }
            items.add(item);
        }

        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF161616); // match background color of dark theme
        listView.setDivider(new android.graphics.drawable.ColorDrawable(0xFF2A2A3E));
        listView.setDividerHeight((int) (1 * getResources().getDisplayMetrics().density));
        listView.setAdapter(new QuickTagListAdapter(items));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(listView)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        for (QuickTagItem item : items) {
                            if (item.currentType == item.initialType) continue; // untouched
                            for (MediaFile f : targets) {
                                if (item.currentType == 1) {
                                    tagManager.applyTag(f, item.name);
                                } else if (item.currentType == 0) {
                                    tagManager.removeTag(f, item.name);
                                }
                            }
                        }
                        for (MediaFile f : targets) mediaAdapter.updateFileTags(f);
                        syncUiAfterTagging(targets);
                        if (mediaAdapter.isSelectMode()) {
                            exitActiveSelectMode();
                            btnScan.setText("SCAN");
                            btnScan.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) { startScan(); }
                            });
                        }
                        Toast.makeText(MainActivity.this,
                                targets.size() == 1 ? "Tags updated"
                                                    : "Tagged " + targets.size() + " files",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("＋ New tag", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Map<String, Integer> edits = new java.util.HashMap<>();
                        for (QuickTagItem item : items) edits.put(item.name, item.currentType);
                        showNewTagDialog(targets, new Runnable() {
                            @Override public void run() { showQuickTagPopup(targets, edits); }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Dialog that creates a brand-new tag and applies it to the targets. */
    private void showNewTagDialog(final List<MediaFile> targets, final Runnable onDone) {
        showNewTagDialog(targets, onDone, "");
    }

    private void showNewTagDialog(final List<MediaFile> targets, final Runnable onDone, final String prefilledName) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(this);
        input.setHint("Tag name");
        input.setTextColor(0xFFFFFFFF);
        if (prefilledName != null && !prefilledName.isEmpty()) {
            input.setText(prefilledName);
            input.setSelection(prefilledName.length());
        }
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        input.setLayoutParams(inputLp);

        Button btnRand = new Button(this);
        btnRand.setText("🎲");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRand.setLayoutParams(btnLp);

        container.addView(input);
        container.addView(btnRand);

        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, 0, pad, 0);
        container.setLayoutParams(lp);
        box.addView(container);

        String title = "Create tag";
        if (targets != null && !targets.isEmpty()) {
            title += targets.size() == 1
                    ? " — " + targets.get(0).getName()
                    : " — applied to " + targets.size() + " files";
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Create", null)
                .setNegativeButton("Back", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onDone != null) onDone.run();
                    }
                })
                .create();

        dialog.show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    if (name.contains(",")) {
                        Toast.makeText(MainActivity.this, "Tag names can't contain commas",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean existed = tagManager.hasTagName(name);
                    tagManager.createTag(name);
                    if (targets != null) {
                        for (MediaFile f : targets) {
                            tagManager.applyTag(f, name);
                            mediaAdapter.updateFileTags(f);
                        }
                        syncUiAfterTagging(targets);
                    }
                    Toast.makeText(MainActivity.this,
                            existed ? "Tag \"" + name + "\" applied"
                                    : "Tag \"" + name + "\" created",
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onDone != null) onDone.run();
                } else {
                    Toast.makeText(MainActivity.this, "Please enter a tag name", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnRand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input == null) return;
                String currentInput = input.getText().toString().trim();
                java.util.Set<String> existingTags = new java.util.HashSet<String>();
                java.util.List<Tag> allTagsSnapshot = new java.util.ArrayList<Tag>(tagManager.getAllTags());
                for (Tag t : allTagsSnapshot) existingTags.add(t.getName());
                String generated = currentInput.isEmpty()
                        ? RandomGenerator.randomTag(MainActivity.this, existingTags)
                        : RandomGenerator.uniqueSuffixTag(currentInput, existingTags);
                input.setText(generated);
                input.setSelection(generated.length());
            }
        });
    }

    private void showAutoLinkSequentialDialog() {
        final List<MediaFile> selectedFiles = getActiveSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView countView = new TextView(this);
        countView.setText("Selected files to link: " + selectedFiles.size());
        countView.setTextColor(0xFFFFFFFF);
        countView.setTextSize(16);
        container.addView(countView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText prefixInput = new EditText(this);
        final java.util.Set<String> usedPrefixes = new java.util.HashSet<String>();
        for (Tag t : tagManager.getAllTags()) {
            if (t.getName().startsWith("link_")) {
                usedPrefixes.add(t.getName().substring(5));
            }
        }
        String initPrefix = RandomGenerator.randomGroupPrefix(usedPrefixes);
        prefixInput.setText(initPrefix);
        prefixInput.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        prefixInput.setLayoutParams(inputLp);

        Button btnRand = new Button(this);
        btnRand.setText("🎲");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRand.setLayoutParams(btnLp);

        row.addView(prefixInput);
        row.addView(btnRand);
        container.addView(row);

        final TextView previewView = new TextView(this);
        previewView.setTextColor(0xFFAAAAAA);
        previewView.setPadding(0, 10, 0, 10);
        container.addView(previewView);

        final Runnable updatePreview = new Runnable() {
            @Override
            public void run() {
                String prefix = prefixInput.getText().toString().trim();
                if (prefix.isEmpty()) {
                    previewView.setText("Preview: enter a prefix");
                    return;
                }
                String groupTag = "link_" + prefix;
                StringBuilder sb = new StringBuilder();
                sb.append("Group Tag: ").append(groupTag).append("\nSequence Preview:\n");

                int limit = Math.min(3, selectedFiles.size());
                java.util.Set<String> tempSet = new java.util.HashSet<String>();
                for (Tag t : tagManager.getAllTags()) {
                    tempSet.add(t.getName());
                }
                java.util.List<String> previewTags = RandomGenerator.allocateSequenceTags(groupTag, selectedFiles.size(), tempSet);
                for (int i = 0; i < limit; i++) {
                    sb.append(" - ").append(previewTags.get(i)).append("\n");
                }
                if (selectedFiles.size() > 3) {
                    sb.append(" - ...\n");
                }
                sb.append("Total sequence tags to apply: ").append(selectedFiles.size());
                previewView.setText(sb.toString());
            }
        };

        prefixInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
        });

        updatePreview.run();

        btnRand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String current = prefixInput.getText().toString().trim();
                if (!current.isEmpty()) {
                    usedPrefixes.add(current);
                }
                String nextPrefix = RandomGenerator.randomGroupPrefix(usedPrefixes);
                prefixInput.setText(nextPrefix);
                prefixInput.setSelection(nextPrefix.length());
            }
        });

        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad, pad, pad);
        container.setLayoutParams(lp);
        box.addView(container);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sequential Auto-Link")
                .setView(box)
                .setPositiveButton("Link Files", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String prefix = prefixInput.getText().toString().trim();
                if (prefix.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Prefix cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (prefix.contains(",")) {
                    Toast.makeText(MainActivity.this, "Prefix cannot contain commas", Toast.LENGTH_SHORT).show();
                    return;
                }

                final String groupTag = "link_" + prefix;
                tagManager.createTag(groupTag);

                java.util.Set<String> existingTags = new java.util.HashSet<String>();
                for (Tag t : tagManager.getAllTags()) {
                    existingTags.add(t.getName());
                }

                java.util.List<String> sequenceTags = RandomGenerator.allocateSequenceTags(groupTag, selectedFiles.size(), existingTags);

                for (int i = 0; i < selectedFiles.size(); i++) {
                    MediaFile file = selectedFiles.get(i);
                    String seqTag = sequenceTags.get(i);

                    tagManager.createTag(seqTag);

                    tagManager.applyTag(file, groupTag);
                    tagManager.applyTag(file, seqTag);
                }

                for (MediaFile file : selectedFiles) {
                    mediaAdapter.updateFile(file);
                }
                exitActiveSelectMode();
                btnScan.setText("SCAN");
                btnScan.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v2) {
                        startScan();
                    }
                });
                scheduleRefresh();

                dialog.dismiss();

                if (prefix == null || prefix.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "Operation complete", Toast.LENGTH_LONG).show();
                    return;
                }

                if (MainActivity.this.isFinishing()) {
                    return;
                }

                Toast.makeText(MainActivity.this, "Linked " + selectedFiles.size() + " files as link_" + prefix, Toast.LENGTH_LONG).show();

                final String searchPrefix = groupTag;
                final java.lang.ref.WeakReference<MainActivity> activityRef = new java.lang.ref.WeakReference<MainActivity>(MainActivity.this);

                new AlertDialog.Builder(MainActivity.this)
                        .setPositiveButton("View", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                MainActivity act = activityRef.get();
                                if (act != null && !act.isFinishing()) {
                                    if (act.searchBar != null) {
                                        act.searchBar.setText(searchPrefix);
                                    } else {
                                        android.util.Log.w("MainActivity", "searchBar is null when View action fired");
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Dismiss", null)
                        .show();
            }
        });
    }

    private void showFileRenameDialog(final MediaFile file) {
        if (file == null) return;
        final String origName = file.getName();
        final int dot = origName.lastIndexOf('.');
        final String ext = dot >= 0 ? origName.substring(dot) : "";

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        final EditText nameEdit = new EditText(this);
        nameEdit.setText(origName);
        nameEdit.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        nameEdit.setLayoutParams(inputLp);

        Button btnRand = new Button(this);
        btnRand.setText("🎲");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRand.setLayoutParams(btnLp);

        container.addView(nameEdit);
        container.addView(btnRand);

        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, 0, pad, 0);
        container.setLayoutParams(lp);
        box.addView(container);

        btnRand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputVal = nameEdit.getText().toString().trim();
                java.io.File parent = new java.io.File(file.getPath()).getParentFile();
                java.util.Set<String> existingFilenames = new java.util.HashSet<String>();
                if (parent != null && parent.exists() && parent.isDirectory()) {
                    String[] files = parent.list();
                    if (files != null) {
                        for (String f : files) {
                            existingFilenames.add(f);
                        }
                    }
                }

                if (inputVal.isEmpty()) {
                    String generated = RandomGenerator.randomSyllableTag() + ext;
                    nameEdit.setText(generated);
                    nameEdit.setSelection(generated.length());
                } else {
                    String generated = RandomGenerator.uniqueSuffixTag(inputVal, existingFilenames);
                    nameEdit.setText(generated);
                    nameEdit.setSelection(generated.length());
                }
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Rename File")
                .setView(box)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String newName = nameEdit.getText().toString().trim();
                        if (!newName.isEmpty() && !newName.equals(origName)) {
                            java.io.File src = new java.io.File(file.getPath());
                            java.io.File dst = new java.io.File(src.getParent(), newName);
                            if (dst.exists()) {
                                Toast.makeText(MainActivity.this, "File already exists", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (src.renameTo(dst)) {
                                file.setPath(dst.getAbsolutePath());
                                scheduleRefresh();
                                Toast.makeText(MainActivity.this, "File renamed successfully", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "Rename failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameSequenceDialog() {
        final List<MediaFile> selectedFiles = getActiveSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView countView = new TextView(this);
        countView.setText("Files to rename: " + selectedFiles.size());
        countView.setTextColor(0xFFFFFFFF);
        countView.setTextSize(16);
        container.addView(countView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText prefixInput = new EditText(this);
        final java.util.Set<String> usedPrefixes = new java.util.HashSet<String>();
        String initPrefix = RandomGenerator.randomGroupPrefix(usedPrefixes);
        prefixInput.setText(initPrefix);
        prefixInput.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        prefixInput.setLayoutParams(inputLp);

        Button btnRand = new Button(this);
        btnRand.setText("🎲");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRand.setLayoutParams(btnLp);

        row.addView(prefixInput);
        row.addView(btnRand);
        container.addView(row);

        final TextView previewView = new TextView(this);
        previewView.setTextColor(0xFFAAAAAA);
        previewView.setPadding(0, 10, 0, 10);
        container.addView(previewView);

        final Runnable updatePreview = new Runnable() {
            @Override
            public void run() {
                String prefix = prefixInput.getText().toString().trim();
                if (prefix.isEmpty()) {
                    previewView.setText("Preview: enter a prefix");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Rename Preview:\n");
                int limit = Math.min(3, selectedFiles.size());
                for (int i = 0; i < limit; i++) {
                    MediaFile file = selectedFiles.get(i);
                    String origName = file.getName();
                    int dot = origName.lastIndexOf('.');
                    String ext = dot >= 0 ? origName.substring(dot) : "";
                    sb.append(" - ").append(origName).append(" → ").append(prefix).append("_seq_").append(RandomGenerator.sequenceLabel(i)).append(ext).append("\n");
                }
                if (selectedFiles.size() > 3) {
                    sb.append(" - ...\n");
                }
                sb.append("Total files: ").append(selectedFiles.size());
                previewView.setText(sb.toString());
            }
        };

        prefixInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
        });

        updatePreview.run();

        btnRand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String current = prefixInput.getText().toString().trim();
                if (!current.isEmpty()) {
                    usedPrefixes.add(current);
                }
                String nextPrefix = RandomGenerator.randomGroupPrefix(usedPrefixes);
                prefixInput.setText(nextPrefix);
                prefixInput.setSelection(nextPrefix.length());
            }
        });

        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad, pad, pad);
        container.setLayoutParams(lp);
        box.addView(container);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename Sequence")
                .setView(box)
                .setPositiveButton("Rename", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String prefix = prefixInput.getText().toString().trim();
                if (prefix.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Prefix cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }

                int successCount = 0;
                for (int i = 0; i < selectedFiles.size(); i++) {
                    MediaFile file = selectedFiles.get(i);
                    java.io.File src = new java.io.File(file.getPath());
                    java.io.File parent = src.getParentFile();

                    java.util.Set<String> existingFilenames = new java.util.HashSet<String>();
                    if (parent != null && parent.exists() && parent.isDirectory()) {
                        String[] list = parent.list();
                        if (list != null) {
                            for (String s : list) {
                                existingFilenames.add(s);
                            }
                        }
                    }

                    String name = src.getName();
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot) : "";

                    int idx = i;
                    String newName;
                    while (true) {
                        String seqLabel = RandomGenerator.sequenceLabel(idx);
                        newName = prefix + "_seq_" + seqLabel + ext;
                        if (!existingFilenames.contains(newName)) {
                            break;
                        }
                        idx++;
                    }

                    java.io.File dst = new java.io.File(parent, newName);
                    if (src.renameTo(dst)) {
                        file.setPath(dst.getAbsolutePath());
                        successCount++;
                    }
                }

                exitActiveSelectMode();
                btnScan.setText("SCAN");
                btnScan.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v2) {
                        startScan();
                    }
                });
                scheduleRefresh();

                dialog.dismiss();

                if (prefix == null || prefix.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "Operation complete", Toast.LENGTH_LONG).show();
                    return;
                }

                if (MainActivity.this.isFinishing()) {
                    return;
                }

                Toast.makeText(MainActivity.this, "Sequence renamed: " + successCount + " files with prefix " + prefix, Toast.LENGTH_LONG).show();

                final String searchPrefix = prefix;
                final java.lang.ref.WeakReference<MainActivity> activityRef = new java.lang.ref.WeakReference<MainActivity>(MainActivity.this);

                new AlertDialog.Builder(MainActivity.this)
                        .setPositiveButton("View", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                MainActivity act = activityRef.get();
                                if (act != null && !act.isFinishing()) {
                                    if (act.searchBar != null) {
                                        act.searchBar.setText(searchPrefix);
                                    } else {
                                        android.util.Log.w("MainActivity", "searchBar is null when View action fired");
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Dismiss", null)
                        .show();
            }
        });
    }

    private void centerScrollToPosition(final int pickedIndex, final int previousVisiblePos) {
        if (galleryModeActive && galleryBrowser != null) {
            galleryBrowser.scrollToPosition(pickedIndex);
            return;
        }
        if (fileBrowser == null) return;

        if (fileBrowser.isAnimating()) {
            final java.lang.ref.WeakReference<RecyclerView> rvRef = new java.lang.ref.WeakReference<RecyclerView>(fileBrowser);
            fileBrowser.postDelayed(new Runnable() {
                @Override
                public void run() {
                    RecyclerView rv = rvRef.get();
                    if (rv != null) {
                        centerScrollToPosition(pickedIndex, previousVisiblePos);
                    }
                }
            }, 50);
            return;
        }

        final int rvHeight = fileBrowser.getHeight();
        if (rvHeight == 0) {
            final java.lang.ref.WeakReference<RecyclerView> rvRef = new java.lang.ref.WeakReference<RecyclerView>(fileBrowser);
            fileBrowser.post(new Runnable() {
                @Override
                public void run() {
                    RecyclerView rv = rvRef.get();
                    if (rv == null) return;
                    int secondTryHeight = rv.getHeight();
                    if (secondTryHeight == 0) {
                        rv.scrollToPosition(pickedIndex);
                    } else {
                        executeCenteredScroll(pickedIndex, secondTryHeight);
                    }
                }
            });
        } else {
            executeCenteredScroll(pickedIndex, rvHeight);
        }
    }

    private void executeCenteredScroll(final int pickedIndex, int rvHeight) {
        if (fileBrowser == null) return;
        RecyclerView.LayoutManager lm = fileBrowser.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            fileBrowser.scrollToPosition(pickedIndex);
            return;
        }

        LinearLayoutManager llm = (LinearLayoutManager) lm;

        int estimatedItemHeight = 0;
        int firstCompletelyVisible = llm.findFirstCompletelyVisibleItemPosition();
        if (firstCompletelyVisible != RecyclerView.NO_POSITION) {
            RecyclerView.ViewHolder vh = fileBrowser.findViewHolderForAdapterPosition(firstCompletelyVisible);
            if (vh != null && vh.itemView != null) {
                estimatedItemHeight = vh.itemView.getHeight();
            }
        }

        if (estimatedItemHeight == 0) {
            int firstPartiallyVisible = llm.findFirstVisibleItemPosition();
            if (firstPartiallyVisible != RecyclerView.NO_POSITION) {
                RecyclerView.ViewHolder vh = fileBrowser.findViewHolderForAdapterPosition(firstPartiallyVisible);
                if (vh != null && vh.itemView != null) {
                    estimatedItemHeight = vh.itemView.getHeight();
                }
            }
        }

        if (estimatedItemHeight == 0) {
            float density = getResources().getDisplayMetrics().density;
            estimatedItemHeight = (int) (72 * density);
        }

        int offset = (rvHeight / 2) - (estimatedItemHeight / 2);
        if (offset < 0) {
            offset = 0;
        }

        llm.scrollToPositionWithOffset(pickedIndex, offset);

        final int targetIndex = pickedIndex;
        final java.lang.ref.WeakReference<RecyclerView> rvRef = new java.lang.ref.WeakReference<RecyclerView>(fileBrowser);
        fileBrowser.postDelayed(new Runnable() {
            @Override
            public void run() {
                RecyclerView rv = rvRef.get();
                if (rv == null) return;
                RecyclerView.LayoutManager currentLm = rv.getLayoutManager();
                if (currentLm != null) {
                    final View pickedView = currentLm.findViewByPosition(targetIndex);
                    if (pickedView != null) {
                        // Store the current background drawable
                        final android.graphics.drawable.Drawable originalBg = pickedView.getBackground();

                        // Derive highlight tint from selection color 0xFF1A1A4E
                        int baseColor = 0xFF1A1A4E;
                        if (originalBg instanceof android.graphics.drawable.ColorDrawable) {
                            int color = ((android.graphics.drawable.ColorDrawable) originalBg).getColor();
                            if (color != 0) {
                                baseColor = color;
                            }
                        }

                        int r = android.graphics.Color.red(baseColor);
                        int g = android.graphics.Color.green(baseColor);
                        int b = android.graphics.Color.blue(baseColor);
                        int highlightTint = android.graphics.Color.argb(100, r, g, b);

                        // Highlight using derived tint
                        pickedView.setBackgroundColor(highlightTint);

                        // Restore original background drawable after 600ms
                        pickedView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (pickedView != null) {
                                    pickedView.setBackground(originalBg);
                                }
                            }
                        }, 600);
                    }
                }
            }
        }, 150);
    }

    private void updateEmptyState() {
        TextView emptyView = findViewById(R.id.emptyStateView);
        if (emptyView == null) return;

        if (fullList == null || fullList.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);

            final String suggestedTag = RandomGenerator.randomSyllableTag();
            String fullText = "No files found.\n\nSuggested tag: " + suggestedTag + "\n(Tap to create)";

            android.text.SpannableString ss = new android.text.SpannableString(fullText);
            int start = fullText.indexOf(suggestedTag);
            int end = start + suggestedTag.length();

            if (start >= 0) {
                ss.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        showNewTagDialogPreFilled(suggestedTag);
                    }

                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(0xFFE94560);
                        ds.setUnderlineText(true);
                    }
                }, start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            emptyView.setText(ss);
            emptyView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void showNewTagDialogPreFilled(final String prefilledName) {
        showNewTagDialog(null, null, prefilledName);
    }

    /** Keeps preview, side panel and progress in sync after (batch) tagging. */
    private void syncUiAfterTagging(List<MediaFile> targets) {
        if (currentIndex >= 0 && currentIndex < fullList.size()) {
            String currentPath = fullList.get(currentIndex).getPath();
            for (MediaFile f : targets) {
                if (f.getPath().equals(currentPath)) {
                    tagAdapter.setCurrentFile(f);
                    refreshSidePanel();
                    break;
                }
            }
        }
        if (galleryAdapter != null && galleryModeActive) galleryAdapter.notifyDataSetChanged();
        updateProgress();
    }

    // ── FolderWatcher callbacks ──────────────────────────────────────────────

    @Override
    public void onFileAdded(String path) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!indexer.isScanning()) {
                    indexer.rescan(new java.io.File(path).getParent());
                }
            }
        });
    }

    @Override
    public void onFileDeleted(String path) {
        final String deletedPath = path;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                // Remove from adapter immediately for responsiveness,
                // then do a full refresh to synchronise all data structures.
                mediaAdapter.removeFile(deletedPath);
                scheduleRefresh();
            }
        });
    }

    @Override
    public void onFileModified(String path) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!indexer.isScanning()) {
                    cacheManager.invalidateThumbnail(path);
                    indexer.rescan(new java.io.File(path).getParent());
                }
            }
        });
    }

    // ── MediaIndexer callbacks ───────────────────────────────────────────────

    @Override
    public void onFileFound(MediaFile file) {}

    @Override
    public void onScanProgress(int scanned, int total, String currentFile) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (scanProgress != null) {
                    scanProgress.setVisibility(View.VISIBLE);
                    scanProgress.setMax(total > 0 ? total : 100);
                    scanProgress.setProgress(scanned);
                }
                if (btnScan != null) {
                    btnScan.setText(scanned + "/" + total);
                }
            }
        });
    }

    @Override
    public void onPageLoaded(List<MediaFile> page) {
        mainHandler.post(new Runnable() {
            @Override public void run() { scheduleRefresh(); }
        });
    }

    @Override
    public void onScanComplete(List<MediaFile> allFiles) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                btnScan.setEnabled(true);
                btnScan.setText("SCAN");
                if (scanProgress != null) scanProgress.setVisibility(View.GONE);

                // Import all tags found in scanned files into TagManager
                List<String> allTagsFromFiles = indexer.getAllTagsFromIndex();
                if (!allTagsFromFiles.isEmpty()) {
                    tagManager.importTagsFromFiles(allTagsFromFiles);
                }

                executeRefresh();
            }
        });
    }

    @Override
    public void onFileChanged(MediaFile file) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < fullList.size(); i++) {
                    if (fullList.get(i).getPath().equals(file.getPath())) {
                        fullList.set(i, file);
                        break;
                    }
                }
                mediaAdapter.updateFile(file);
                if (galleryAdapter != null) galleryAdapter.setFiles(fullList);
            }
        });
    }

    @Override
    public void onFileRemoved(String path) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                for (int i = fullList.size() - 1; i >= 0; i--) {
                    if (fullList.get(i).getPath().equals(path)) fullList.remove(i);
                }
                mediaAdapter.removeFile(path);
                if (galleryAdapter != null) galleryAdapter.setFiles(fullList);
                updateGalleryCount();
                updateProgress();
            }
        });
    }

    // ── Gallery mode (additive presentation) ──────────────────────────────────

    private void setupGalleryMode() {
        galleryLowMemory = isGalleryLowMemoryDevice();

        galleryThumbnailLoader = new GalleryThumbnailLoader(
                new GalleryThumbnailLoader.Callback() {
                    @Override public void onGalleryThumbnailReady(String path) {
                        // The loader updates the attached ImageView itself. This
                        // callback is intentionally lightweight.
                    }

                    @Override public void onGalleryThumbnailFailed(String path) {
                        // The adapter already leaves the cell's deterministic
                        // placeholder visible after a failed decode.
                    }
                }, galleryLowMemory, galleryPrefs());
        galleryThumbnailLoader.setLowMemoryDevice(galleryLowMemory);

        galleryAdapter = new GalleryAdapter(this, galleryThumbnailLoader,
                fileStatus, new GalleryAdapter.Listener() {
                    @Override public void onGalleryFileClick(MediaFile file) {
                        onFileSelected(file);
                    }

                    @Override public void onGallerySelectionChanged(int count) {
                        updateGallerySelectionToolbar(count);
                    }

                    @Override public void onGalleryLongPress(GalleryAdapter.ViewHolder holder) {
                        beginGalleryDrag(holder);
                    }
                }, galleryLowMemory);

        ViewParent parent = fileBrowser.getParent();
        if (parent instanceof FrameLayout) {
            FrameLayout listRoot = (FrameLayout) parent;
            galleryRoot = new FrameLayout(this);
            galleryRoot.setBackgroundColor(0xFF161616);
            galleryRoot.setVisibility(View.GONE);
            listRoot.addView(galleryRoot, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            LinearLayout galleryContent = new LinearLayout(this);
            galleryContent.setOrientation(LinearLayout.VERTICAL);
            galleryContent.setBackgroundColor(0xFF161616);
            galleryRoot.addView(galleryContent, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            galleryCountLabel = new TextView(this);
            galleryCountLabel.setTextColor(0xFFAAAAAA);
            galleryCountLabel.setTextSize(11f);
            galleryCountLabel.setPadding(galleryDp(8), galleryDp(6), galleryDp(8), galleryDp(6));
            galleryContent.addView(galleryCountLabel, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            galleryBrowser = new RecyclerView(this);
            galleryBrowser.setBackgroundColor(0xFF161616);
            galleryBrowser.setClipToPadding(false);
            galleryBrowser.setPadding(galleryDp(2), galleryDp(2), galleryDp(2), galleryDp(2));
            galleryLayoutManager = new GridLayoutManager(this, galleryColumns());
            galleryBrowser.setLayoutManager(galleryLayoutManager);
            galleryBrowser.setAdapter(galleryAdapter);
            galleryBrowser.setHasFixedSize(false);
            galleryContent.addView(galleryBrowser, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

            galleryBrowser.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@androidx.annotation.NonNull RecyclerView recyclerView,
                                       int dx, int dy) {
                    updateGalleryScrollVelocity(dx, dy);
                    updateGalleryMemoryWindow();
                }

                @Override
                public void onScrollStateChanged(@androidx.annotation.NonNull RecyclerView recyclerView,
                                                 int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        galleryScrollSettled = true;
                        if (galleryFastScrolling) setGalleryFastScrolling(false);
                        galleryAdapter.reloadVisibleThumbnails(recyclerView);
                    } else {
                        galleryScrollSettled = false;
                    }
                }
            });

            galleryScaleDetector = new ScaleGestureDetector(this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                            galleryLastScale = 1.0f;
                            return true;
                        }

                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            float factor = detector.getScaleFactor();
                            galleryLastScale *= factor;
                            if (galleryLastScale < 0.92f) {
                                changeGalleryColumns(galleryAdapter.getColumns() + 1);
                                galleryLastScale = 1.0f;
                            } else if (galleryLastScale > 1.08f) {
                                changeGalleryColumns(galleryAdapter.getColumns() - 1);
                                galleryLastScale = 1.0f;
                            }
                            return true;
                        }
                    });
            galleryBrowser.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View view, MotionEvent event) {
                    galleryLastTouchX = event.getX();
                    galleryLastTouchY = event.getY();
                    if (galleryScaleDetector != null) {
                        galleryScaleDetector.onTouchEvent(event);
                    }
                    return false;
                }
            });

            galleryBrowser.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(RecyclerView recyclerView,
                                                      MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        dismissGalleryInfoPopup();
                    }
                    if (galleryAdapter != null && galleryAdapter.isSelectMode()
                            && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        View child = recyclerView.findChildViewUnder(event.getX(), event.getY());
                        if (child != null) {
                            int position = recyclerView.getChildAdapterPosition(child);
                            MediaFile file = galleryAdapter.getFile(position);
                            if (file != null && !galleryAdapter.isSelected(file.getPath())) {
                                galleryAdapter.selectPath(file.getPath());
                            }
                        }
                    }
                    return false;
                }
            });

            ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN
                            | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
                @Override
                public boolean onMove(RecyclerView recyclerView,
                                       RecyclerView.ViewHolder source,
                                       RecyclerView.ViewHolder target) {
                    if (!(source instanceof GalleryAdapter.ViewHolder)
                            || !(target instanceof GalleryAdapter.ViewHolder)) return false;
                    int from = source.getAdapterPosition();
                    int to = target.getAdapterPosition();
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                    if (galleryDragFrom < 0) galleryDragFrom = from;
                    View targetView = target.itemView;
                    float centerX = targetView.getLeft() + targetView.getWidth() / 2.0f;
                    float centerY = targetView.getTop() + targetView.getHeight() / 2.0f;
                    boolean horizontal = Math.abs(galleryLastTouchX - centerX)
                            >= Math.abs(galleryLastTouchY - centerY);
                    boolean after = horizontal
                            ? galleryLastTouchX > centerX
                            : galleryLastTouchY > centerY;
                    int insertion = to;
                    if (after && from < to) insertion = to;
                    else if (after) insertion = to + 1;
                    else if (!after && from < to) insertion = to - 1;
                    insertion = Math.max(0, Math.min(galleryAdapter.getItemCount() - 1, insertion));
                    if (insertion == from) return false;
                    galleryDragTo = insertion;
                    galleryAdapter.moveItem(from, insertion);
                    updateGalleryDragThumbnailWindow();
                    return true;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {}

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                    super.onSelectedChanged(holder, actionState);
                    if (holder instanceof GalleryAdapter.ViewHolder) {
                        galleryAdapter.setDragging((GalleryAdapter.ViewHolder) holder,
                                actionState == ItemTouchHelper.ACTION_STATE_DRAG);
                    }
                }

                @Override
                public void clearView(RecyclerView recyclerView,
                                      RecyclerView.ViewHolder holder) {
                    super.clearView(recyclerView, holder);
                    if (!(holder instanceof GalleryAdapter.ViewHolder)) return;
                    galleryAdapter.setDragging((GalleryAdapter.ViewHolder) holder, false);
                    if (galleryDragging) finishGalleryDrag((GalleryAdapter.ViewHolder) holder);
                }
            };
            galleryItemTouchHelper = new ItemTouchHelper(callback);
            galleryItemTouchHelper.attachToRecyclerView(galleryBrowser);
        }

        addGalleryToolbarControls();
        addGalleryFilterRow();

        int initialColumns = galleryColumns();
        if (galleryLowMemory) initialColumns = Math.min(3, initialColumns);
        galleryAdapter.setColumns(initialColumns);
        galleryAdapter.setSpacingDp(gallerySpacing());
        galleryThumbnailLoader.setQuality(galleryQuality());
        galleryThumbnailLoader.setAnimate(galleryAnimate());

        boolean persistedMode = galleryPrefs().getBoolean("gallery_mode_active", false);
        if (persistedMode) setGalleryMode(true, false);
        else setGalleryMode(false, false);
    }

    private void addGalleryToolbarControls() {
        View toolbarView = findViewById(R.id.toolbar);
        if (!(toolbarView instanceof LinearLayout)) return;
        final LinearLayout toolbar = (LinearLayout) toolbarView;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            toolbar.getChildAt(i).setVisibility(View.GONE);
        }
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        toolbarSearchToggle = new Button(this);
        toolbarSearchToggle.setText("Search");
        toolbarSearchToggle.setAllCaps(false);
        toolbarSearchToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (searchBar == null) return;
                boolean show = searchBar.getVisibility() != View.VISIBLE;
                boolean allowed = getSharedPreferences("settings_prefs", MODE_PRIVATE)
                        .getBoolean("show_search_bar", true);
                searchBar.setVisibility(show && allowed ? View.VISIBLE : View.GONE);
                if (show && allowed) searchBar.requestFocus();
            }
        });
        toolbar.addView(toolbarSearchToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        galleryToggleButton = new Button(this);
        galleryToggleButton.setText("Gallery");
        galleryToggleButton.setAllCaps(false);
        galleryToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setGalleryMode(!galleryModeActive, true); }
        });
        toolbar.addView(galleryToggleButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        toolbarActionContainer = new LinearLayout(this);
        toolbarActionContainer.setOrientation(LinearLayout.HORIZONTAL);
        toolbarActionContainer.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(toolbarActionContainer, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        toolbarOverflowButton = new Button(this);
        toolbarOverflowButton.setText("⋮");
        toolbarOverflowButton.setContentDescription("More actions");
        toolbarOverflowButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showToolbarOverflow(view); }
        });
        toolbar.addView(toolbarOverflowButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        rebuildCustomToolbar();
    }

    private List<String> getToolbarSlotIds() {
        List<String> result = new ArrayList<String>();
        String raw = galleryPrefs().getString("toolbar_slots", "");
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                org.json.JSONArray array = new org.json.JSONArray(raw);
                for (int i = 0; i < array.length() && result.size() < 5; i++) {
                    String id = array.optString(i, "");
                    if (GestureConstants.isKnownAction(id)
                            && !GestureConstants.ACTION_DONE.equals(id)
                            && !GestureConstants.ACTION_NOTHING.equals(id)
                            && !result.contains(id)) result.add(id);
                }
            }
        } catch (Exception error) {
            android.util.Log.w("MainActivity", "Invalid toolbar_slots", error);
        }
        if (result.isEmpty()) {
            result.add(GestureConstants.ACTION_FLAG);
            result.add(GestureConstants.ACTION_QUICK_TAGS);
            result.add(GestureConstants.ACTION_SURPRISE_ME);
            result.add(GestureConstants.ACTION_UNDO);
            result.add(GestureConstants.ACTION_SORT_PICKER);
        }
        return result;
    }

    private void rebuildToolbarIfNeeded() {
        if (toolbarActionContainer == null) return;
        String raw = galleryPrefs().getString("toolbar_slots", "");
        List<String> slots = getToolbarSlotIds();
        String signature = slots.toString();
        if (!signature.equals(toolbarSlotsSnapshot)) rebuildCustomToolbar();
    }

    private void rebuildCustomToolbar() {
        if (toolbarActionContainer == null) return;
        toolbarActionContainer.removeAllViews();
        List<String> slots = getToolbarSlotIds();
        toolbarSlotsSnapshot = slots.toString();
        for (String action : slots) {
            Button button = new Button(this);
            button.setText(GestureConstants.label(action));
            button.setAllCaps(false);
            button.setTextSize(10f);
            final String actionId = action;
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { performToolbarAction(actionId); }
            });
            toolbarActionContainer.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    private void performToolbarAction(String actionId) {
        if (actionId == null) return;
        if (GestureConstants.ACTION_FLAG.equals(actionId)) handleFlag();
        else if (GestureConstants.ACTION_QUICK_TAGS.equals(actionId)) openQuickTagAction();
        else if (GestureConstants.ACTION_SURPRISE_ME.equals(actionId)) {
            View view = findViewById(R.id.btnSurprise);
            if (view != null) view.performClick();
        } else if (GestureConstants.ACTION_UNDO.equals(actionId)) undoLastAction();
        else if (GestureConstants.ACTION_SORT_PICKER.equals(actionId)) showSortBuilder();
        else {
            try { executeAction(GestureSettings.GestureAction.valueOf(actionId)); }
            catch (Exception error) { android.util.Log.w("MainActivity", "No toolbar handler for " + actionId); }
        }
    }

    private void showToolbarOverflow(final View anchor) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        final android.view.Menu menu = popup.getMenu();
        final Set<String> visible = new java.util.HashSet<String>(getToolbarSlotIds());
        final Map<String, String> actionByTitle = new java.util.LinkedHashMap<String, String>();

        addOverflowHeader(menu, "View");
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_SORT_PICKER);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_FILTER_PICKER);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_GROUP_PICKER);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_GALLERY);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_TOGGLE_GALLERY);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_TOGGLE_STATS_BAR);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_TOGGLE_INFO_OVERLAY);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_TOGGLE_TAG_PANEL);

        addOverflowHeader(menu, "Actions");
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_SELECT_ALL);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_DESELECT_ALL);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_SURPRISE_ME);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_QUICK_RANDOM_TAG);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_UNDO);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_RULES);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_TRIGGER_RESCAN);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_DELETE);

        addOverflowHeader(menu, "Tools");
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_DUPLICATE_FINDER);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_COLOR_ANALYZER);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_DASHBOARD);

        addOverflowHeader(menu, "App");
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_OPEN_SETTINGS);
        addOverflowAction(menu, actionByTitle, visible, GestureConstants.ACTION_EXPORT_SETTINGS);
        android.view.MenuItem about = menu.add("About");
        actionByTitle.put("About", "ABOUT");

        // Keep every other gesture/toolbar action reachable when it is not in
        // one of the five visible slots.
        for (String id : GestureConstants.getToolbarActionIds()) {
            if (GestureConstants.ACTION_NOTHING.equals(id)
                    || GestureConstants.ACTION_DONE.equals(id)) continue;
            addOverflowAction(menu, actionByTitle, visible, id);
        }

        int selected = activeSelectionCount();
        for (Map.Entry<String, String> entry : actionByTitle.entrySet()) {
            android.view.MenuItem item = findMenuItem(menu, entry.getKey());
            if (item == null) continue;
            String id = entry.getValue();
            if (GestureConstants.ACTION_SELECT_ALL.equals(id)) item.setEnabled(!fullList.isEmpty());
            else if (GestureConstants.ACTION_DESELECT_ALL.equals(id)) item.setEnabled(selected > 0);
            else if (GestureConstants.ACTION_QUICK_RANDOM_TAG.equals(id)) item.setEnabled(selected > 0 || currentIndex >= 0);
            else if (GestureConstants.ACTION_DELETE.equals(id)) item.setEnabled(selected > 0 || currentIndex >= 0);
            else if (GestureConstants.ACTION_OPEN_COLOR_ANALYZER.equals(id)) item.setEnabled(selected > 0);
            else if (GestureConstants.ACTION_OPEN_DUPLICATE_FINDER.equals(id)) item.setEnabled(!fullList.isEmpty());
            else if (GestureConstants.ACTION_TRIGGER_RESCAN.equals(id)) item.setEnabled(!folderManager.isEmpty());
        }

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                String id = actionByTitle.get(item.getTitle().toString());
                if ("ABOUT".equals(id)) showAboutFromOverflow();
                else if (id != null) performToolbarAction(id);
                else return false;
                return true;
            }
        });
        popup.show();
    }

    private void addOverflowAction(android.view.Menu menu, Map<String, String> actionByTitle,
                                   Set<String> visible, String actionId) {
        if (actionId == null || visible.contains(actionId)
                || !GestureConstants.isKnownAction(actionId)) return;
        String title = GestureConstants.label(actionId);
        if (actionByTitle.containsKey(title)) return;
        menu.add(title);
        actionByTitle.put(title, actionId);
    }

    private android.view.MenuItem findMenuItem(android.view.Menu menu, String title) {
        for (int i = 0; i < menu.size(); i++) {
            android.view.MenuItem item = menu.getItem(i);
            if (title.equals(item.getTitle().toString())) return item;
        }
        return null;
    }

    private void addOverflowHeader(android.view.Menu menu, String title) {
        android.view.MenuItem header = menu.add(title);
        header.setEnabled(false);
    }

    private void performToolbarButton(int id) {
        View button = findViewById(id);
        if (button != null) button.performClick();
    }

    private int activeSelectionCount() {
        if (galleryModeActive && galleryAdapter != null) return galleryAdapter.getSelectedCount();
        return mediaAdapter == null ? 0 : mediaAdapter.getSelectedCount();
    }

    private void selectAllActiveFiles() {
        if (galleryModeActive && galleryAdapter != null) galleryAdapter.selectAll();
        else if (mediaAdapter != null) mediaAdapter.selectAll();
    }

    private void deselectAllActiveFiles() {
        if (galleryModeActive && galleryAdapter != null) galleryAdapter.deselectAll();
        else if (mediaAdapter != null) mediaAdapter.deselectAll();
    }

    private void exportSettingsFromOverflow() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String path = SettingsExporter.exportSettings(MainActivity.this);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                path == null ? "Export failed" : "Exported successfully",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void showAboutFromOverflow() {
        new AlertDialog.Builder(this)
                .setTitle("About MediaSorter")
                .setMessage("MediaSorter")
                .setPositiveButton("OK", null)
                .show();
    }

    private void addGalleryFilterRow() {
        View rootView = findViewById(android.R.id.content);
        if (!(rootView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) rootView;
        if (content.getChildCount() == 0) return;
        View activityRoot = content.getChildAt(0);
        if (!(activityRoot instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) activityRoot;

        galleryFilterScroll = new HorizontalScrollView(this);
        galleryFilterScroll.setHorizontalScrollBarEnabled(false);
        galleryFilterScroll.setVisibility(View.GONE);
        galleryFilterRow = new LinearLayout(this);
        galleryFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        galleryFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        galleryFilterScroll.addView(galleryFilterRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        root.addView(galleryFilterScroll, 1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, galleryDp(40)));
    }

    private void setGalleryMode(boolean active, boolean userInitiated) {
        if (active && !userInitiated) loadGallerySortPreference();
        galleryModeActive = active;
        galleryPrefs().edit().putBoolean("gallery_mode_active", active).apply();
        TextView empty = findViewById(R.id.emptyStateView);

        if (active) {
            galleryScrollSettled = true;
            galleryFastScrolling = false;
            if (galleryThumbnailLoader != null) galleryThumbnailLoader.setScrollSuspended(false);
            if (fileBrowser != null) fileBrowser.setVisibility(View.GONE);
            if (galleryRoot != null) galleryRoot.setVisibility(View.VISIBLE);
            if (galleryFilterScroll != null) galleryFilterScroll.setVisibility(View.VISIBLE);
            if (empty != null) empty.setVisibility(View.GONE);
            if (galleryToggleButton != null) galleryToggleButton.setText("List");
            if (galleryAdapter != null) {
                if (mediaAdapter != null && mediaAdapter.isSelectMode()) {
                    List<String> selectedPaths = new ArrayList<>();
                    for (MediaFile file : mediaAdapter.getSelectedFiles()) {
                        selectedPaths.add(file.getPath());
                    }
                    galleryAdapter.setSelectedPaths(selectedPaths);
                }
                galleryAdapter.setFiles(fullList);
                galleryAdapter.setColumns(galleryColumns());
                galleryAdapter.setSpacingDp(gallerySpacing());
            }
            if (galleryBrowser != null) galleryBrowser.scrollToPosition(0);
            refreshGalleryFilterChips();
            updateGalleryCount();
            updateGalleryMemoryWindow();
            if (galleryLowMemory && userInitiated && !galleryPrefs().getBoolean(
                    "gallery_low_memory_notice", false)) {
                galleryPrefs().edit().putBoolean("gallery_low_memory_notice", true).apply();
                Toast.makeText(this, "Low memory device — gallery optimized automatically.",
                        Toast.LENGTH_LONG).show();
            }
            if (!userInitiated) scheduleRefresh();
        } else {
            if (galleryRoot != null) galleryRoot.setVisibility(View.GONE);
            if (fileBrowser != null) fileBrowser.setVisibility(View.VISIBLE);
            if (galleryFilterScroll != null) galleryFilterScroll.setVisibility(View.GONE);
            if (galleryToggleButton != null) galleryToggleButton.setText("Gallery");
            dismissGalleryInfoPopup();
            if (galleryThumbnailLoader != null) {
                galleryThumbnailLoader.setAllowedPaths(new ArrayList<String>(),
                        new ArrayList<String>());
            }
            if (empty != null) updateEmptyState();
        }
    }

    private void loadGallerySortPreference() {
        sortManager.reloadSequence();
        if (btnSort != null) btnSort.setText(sortManager.getLabel());
    }

    private void updateGalleryData() {
        if (galleryCountLabel != null) updateGalleryCount();
        if (galleryAdapter != null) galleryAdapter.setFiles(fullList);
        if (galleryModeActive) {
            refreshGalleryFilterChips();
            updateGalleryMemoryWindow();
        }
    }

    private void updateGalleryCount() {
        if (galleryCountLabel == null) return;
        galleryCountLabel.setText(fullList.size() + " files");
    }

    private void updateGalleryDragThumbnailWindow() {
        if (!galleryDragging || galleryBrowser == null || galleryAdapter == null) return;
        List<MediaFile> files = galleryAdapter.getFiles();
        if (files.isEmpty()) return;
        int center = Math.max(0, Math.min(files.size() - 1, galleryDragTo));
        int start = Math.max(0, center - 3);
        int end = Math.min(files.size() - 1, center + 3);
        List<String> paths = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            paths.add(files.get(i).getPath());
        }
        galleryAdapter.updateDragThumbnailWindow(galleryBrowser, paths, galleryDraggedPath);
    }

    private void updateGalleryScrollVelocity(int dx, int dy) {
        long now = System.currentTimeMillis();
        long elapsed = galleryLastScrollTime == 0 ? 0 : now - galleryLastScrollTime;
        int offset = Math.abs(dx) + Math.abs(dy);
        float density = getResources().getDisplayMetrics().density;
        float velocity = elapsed <= 0 ? 0.0f
                : (offset / density) / (elapsed / 1000.0f);
        galleryLastScrollTime = now;
        if (velocity > 2000.0f && !galleryFastScrolling) {
            setGalleryFastScrolling(true);
        } else if (galleryFastScrolling && velocity < 500.0f) {
            setGalleryFastScrolling(false);
        }
    }

    private void setGalleryFastScrolling(boolean fast) {
        if (galleryFastScrolling == fast && galleryThumbnailLoader != null
                && galleryThumbnailLoader.isScrollSuspended() == fast) return;
        galleryFastScrolling = fast;
        if (galleryThumbnailLoader != null) {
            galleryThumbnailLoader.setScrollSuspended(fast);
        }
        if (galleryAdapter != null && galleryLayoutManager != null
                && galleryBrowser != null) {
            final boolean targetFast = fast;
            galleryBrowser.post(new Runnable() {
                @Override public void run() {
                    if (galleryAdapter == null || galleryLayoutManager == null) return;
                    int first = galleryLayoutManager.findFirstVisibleItemPosition();
                    int last = galleryLayoutManager.findLastVisibleItemPosition();
                    galleryAdapter.setFastScrolling(targetFast, first, last);
                }
            });
        }
        if (!fast) updateGalleryMemoryWindow();
    }

    private void updateGalleryMemoryWindow() {
        if (!galleryModeActive || galleryDragging || galleryBrowser == null
                || galleryLayoutManager == null || galleryThumbnailLoader == null
                || galleryAdapter == null) return;
        int first = galleryLayoutManager.findFirstVisibleItemPosition();
        int last = galleryLayoutManager.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;
        int columns = Math.max(1, galleryAdapter.getColumns());
        int firstRow = first / columns;
        int lastRow = last / columns;
        int start = firstRow * columns;
        int visibleEnd = Math.min(galleryAdapter.getItemCount(), (lastRow + 1) * columns);
        int end = galleryLowMemory || galleryFastScrolling
                ? visibleEnd
                : Math.min(galleryAdapter.getItemCount(), visibleEnd + columns);
        List<String> allowed = new ArrayList<>();
        List<String> visible = new ArrayList<>();
        for (int i = start; i < end; i++) {
            MediaFile file = galleryAdapter.getFile(i);
            if (file == null) continue;
            allowed.add(file.getPath());
            if (i >= first && i <= last) visible.add(file.getPath());
        }
        galleryThumbnailLoader.setAllowedPaths(allowed, visible);

        int width = galleryBrowser.getWidth() / columns;
        int height = Math.max(galleryDp(56), Math.round(width * 0.72f));
        if (!galleryLowMemory && !galleryFastScrolling) {
            for (int i = visibleEnd; i < end; i++) {
                MediaFile file = galleryAdapter.getFile(i);
                if (file != null) galleryThumbnailLoader.precache(file, width, height);
            }
        }
    }

    private void changeGalleryColumns(int requested) {
        int next = Math.max(1, Math.min(galleryLowMemory ? 3 : 6, requested));
        galleryPrefs().edit().putInt("gallery_columns", next).apply();
        if (galleryLayoutManager != null) galleryLayoutManager.setSpanCount(next);
        if (galleryAdapter != null) galleryAdapter.setColumns(next);
        updateGalleryMemoryWindow();
    }

    private boolean isGalleryLowMemoryDevice() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        return manager != null && manager.getMemoryClass() < 512;
    }

    private android.content.SharedPreferences galleryPrefs() {
        // Keep every gallery key in settings_prefs. SettingsExporter already
        // serializes that entire preference file, so new gallery settings are
        // automatically included in full exports/imports.
        return getSharedPreferences("settings_prefs", MODE_PRIVATE);
    }

    private int galleryColumns() {
        int value = galleryPrefs().getInt("gallery_columns", 3);
        return Math.max(1, Math.min(galleryLowMemory ? 3 : 6, value));
    }

    private int gallerySpacing() {
        if (galleryLowMemory) return 2;
        return galleryPrefs().getInt("gallery_cell_spacing", 4);
    }

    private int galleryQuality() {
        if (galleryLowMemory) return GalleryThumbnailLoader.QUALITY_LOW;
        String value = galleryPrefs().getString("gallery_thumb_quality", "Low");
        if ("High".equalsIgnoreCase(value)) return GalleryThumbnailLoader.QUALITY_HIGH;
        if ("Medium".equalsIgnoreCase(value)) return GalleryThumbnailLoader.QUALITY_MEDIUM;
        return GalleryThumbnailLoader.QUALITY_LOW;
    }

    private boolean galleryAnimate() {
        return !galleryLowMemory
                && galleryPrefs().getBoolean("gallery_animate_load", true);
    }

    private int galleryDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshGalleryFilterChips() {
        if (galleryFilterRow == null) return;
        galleryFilterRow.removeAllViews();
        addGalleryFilterChip("All", FilterManager.Filter.ALL);
        addGalleryFilterChip("Flagged", FilterManager.Filter.FLAGGED);
        addGalleryFilterChip("Skipped", FilterManager.Filter.SKIPPED);
        addGalleryFilterChip("Done", FilterManager.Filter.DONE);
        addGalleryFilterChip("Untagged", FilterManager.Filter.UNTAGGED);
        addGalleryFilterChip("Tagged", FilterManager.Filter.TAGGED);
        addGalleryFilterChip("Images", FilterManager.Filter.IMAGES);
        addGalleryFilterChip("Videos", FilterManager.Filter.VIDEOS);
        addGalleryFilterChip("Duplicates", FilterManager.Filter.DUPLICATES);
        addGalleryFilterChip(filterManager.getTagFilter().isEmpty()
                ? "By tag" : filterManager.getTagFilter(), FilterManager.Filter.BY_TAG);
        addGalleryClearChip();
    }

    private void addGalleryFilterChip(String label, final FilterManager.Filter filter) {
        final TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(10f);
        chip.setTextColor(0xFFFFFFFF);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(galleryDp(10), 0, galleryDp(10), 0);
        chip.setBackgroundColor(filterManager.isActive(filter)
                ? getAccentColor() : 0xFF2A2A3E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, galleryDp(30));
        lp.setMargins(galleryDp(2), galleryDp(4), galleryDp(2), galleryDp(4));
        galleryFilterRow.addView(chip, lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (filter == FilterManager.Filter.BY_TAG) {
                    if (filterManager.isActive(filter)) {
                        filterManager.setTagFilter("");
                        refreshGalleryFilterChips();
                        scheduleRefresh();
                    } else {
                        showGalleryTagFilterPicker();
                    }
                } else {
                    filterManager.toggleFilter(filter);
                    refreshGalleryFilterChips();
                    scheduleRefresh();
                }
            }
        });
    }

    private void addGalleryClearChip() {
        TextView chip = new TextView(this);
        chip.setText("Clear all");
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(10f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(galleryDp(10), 0, galleryDp(10), 0);
        chip.setBackgroundColor(0xFF444466);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, galleryDp(30));
        lp.setMargins(galleryDp(2), galleryDp(4), galleryDp(6), galleryDp(4));
        galleryFilterRow.addView(chip, lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                filterManager.clearFilters();
                refreshGalleryFilterChips();
                scheduleRefresh();
            }
        });
    }

    private int getAccentColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(R.attr.colorAccent, value, true)) return value.data;
        return 0xFFE94560;
    }

    private void showGalleryTagFilterPicker() {
        List<Tag> tags = tagManager.getAllTags();
        if (tags.isEmpty()) return;
        String[] names = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) names[i] = tags.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("Filter by tag")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= names.length) return;
                        filterManager.setTagFilter(names[which]);
                        refreshGalleryFilterChips();
                        scheduleRefresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void beginGalleryDrag(GalleryAdapter.ViewHolder holder) {
        if (!galleryScrollSettled || galleryFastScrolling) return;
        if (galleryItemTouchHelper == null || holder == null) return;
        galleryDragging = true;
        galleryDragFrom = holder.getAdapterPosition();
        galleryDragTo = galleryDragFrom;
        MediaFile draggedFile = galleryAdapter.getFile(galleryDragFrom);
        galleryDraggedPath = draggedFile == null ? null : draggedFile.getPath();
        galleryDragOriginalOrder.clear();
        galleryDragOriginalFiles = galleryAdapter.getFiles();
        for (MediaFile file : galleryDragOriginalFiles) {
            galleryDragOriginalOrder.add(file.getPath());
        }
        galleryItemTouchHelper.startDrag(holder);
        updateGalleryDragThumbnailWindow();
    }

    private void finishGalleryDrag(GalleryAdapter.ViewHolder holder) {
        boolean moved = galleryDragFrom >= 0 && galleryDragTo >= 0
                && galleryDragFrom != galleryDragTo;
        if (!moved && holder != null) {
            MediaFile file = galleryAdapter.getFile(holder.getAdapterPosition());
            if (file != null) {
                galleryAdapter.enterSelectMode();
                galleryAdapter.toggleSelection(file);
                showGalleryInfoPopup(holder.itemView, file);
            }
        } else if (moved) {
            galleryManualUndo.push(new ArrayList<>(galleryDragOriginalFiles));
            List<MediaFile> reordered = galleryAdapter.getFiles();
            indexer.updateManualOrder(reordered);
            sortManager.setSingleCriterion(SortManager.MANUAL_ORDER, "ASC");
            btnSort.setText(sortManager.getLabel());
            final List<MediaFile> affected = new ArrayList<>();
            int low = Math.min(galleryDragFrom, galleryDragTo);
            int high = Math.max(galleryDragFrom, galleryDragTo);
            for (int i = low; i <= high && i < reordered.size(); i++) {
                affected.add(reordered.get(i));
            }
            renameGalleryManualRange(affected);
        }
        if (galleryAdapter != null) {
            galleryAdapter.clearDragThumbnailWindow();
            galleryAdapter.reloadVisibleThumbnails(galleryBrowser);
        }
        galleryDraggedPath = null;
        galleryDragging = false;
        galleryDragFrom = -1;
        galleryDragTo = -1;
        galleryDragOriginalOrder.clear();
        galleryDragOriginalFiles.clear();
        updateGalleryMemoryWindow();
    }

    private String uniqueManualGroupPrefix(final List<MediaFile> affected) {
        java.util.Map<String, java.util.Set<String>> namesByDirectory =
                new java.util.HashMap<String, java.util.Set<String>>();
        java.util.Set<String> usedPrefixes = new java.util.HashSet<String>();

        for (MediaFile file : affected) {
            java.io.File source = new java.io.File(file.getPath());
            java.io.File directory = source.getParentFile();
            if (directory == null) continue;
            String directoryPath = directory.getAbsolutePath();
            java.util.Set<String> names = namesByDirectory.get(directoryPath);
            if (names == null) {
                names = new java.util.HashSet<String>();
                String[] existing = directory.list();
                if (existing != null) {
                    for (String name : existing) {
                        names.add(name);
                        usedPrefixes.add(name);
                        int sequenceMarker = name.indexOf("_seq_");
                        if (sequenceMarker > 0) {
                            usedPrefixes.add(name.substring(0, sequenceMarker));
                        }
                    }
                }
                namesByDirectory.put(directoryPath, names);
            }
        }

        // These source names move to temporary names first, so they are not
        // conflicts for this operation's generated destinations.
        for (MediaFile file : affected) {
            java.io.File source = new java.io.File(file.getPath());
            java.io.File directory = source.getParentFile();
            if (directory != null) {
                java.util.Set<String> names = namesByDirectory.get(directory.getAbsolutePath());
                if (names != null) names.remove(source.getName());
            }
        }

        for (int attempt = 0; attempt < 1000; attempt++) {
            String prefix = com.mediasorter.features.RandomGenerator
                    .randomGroupPrefix(usedPrefixes);
            boolean conflict = false;
            java.util.Set<String> reserved = new java.util.HashSet<String>();
            for (int i = 0; i < affected.size(); i++) {
                MediaFile file = affected.get(i);
                java.io.File source = new java.io.File(file.getPath());
                java.io.File directory = source.getParentFile();
                if (directory == null) continue;
                String name = source.getName();
                int dot = name.lastIndexOf('.');
                String extension = dot >= 0 ? name.substring(dot) : "";
                String destination = prefix + "_seq_"
                        + com.mediasorter.features.RandomGenerator.sequenceLabel(i)
                        + extension;
                java.util.Set<String> names = namesByDirectory.get(directory.getAbsolutePath());
                if ((names != null && names.contains(destination)) || !reserved.add(
                        directory.getAbsolutePath() + "\n" + destination)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) return prefix;
            usedPrefixes.add(prefix);
        }
        return "manual-" + System.currentTimeMillis();
    }

    private void renameGalleryManualRange(final List<MediaFile> affected) {
        if (affected == null || affected.isEmpty()) return;
        Toast.makeText(this, "Updating manual order…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                int success = 0;
                String prefix = uniqueManualGroupPrefix(affected);
                java.util.List<java.io.File> sources = new ArrayList<>();
                java.util.List<java.io.File> temporary = new ArrayList<>();
                java.util.List<MediaFile> renamedFiles = new ArrayList<>();
                long stamp = System.currentTimeMillis();
                for (int i = 0; i < affected.size(); i++) {
                    MediaFile file = affected.get(i);
                    java.io.File source = new java.io.File(file.getPath());
                    if (!source.exists()) continue;
                    java.io.File temp = new java.io.File(source.getParentFile(),
                            ".gallery_order_" + stamp + "_" + i + source.getName());
                    if (source.renameTo(temp)) {
                        sources.add(source);
                        temporary.add(temp);
                        renamedFiles.add(file);
                    }
                }
                for (int i = 0; i < temporary.size(); i++) {
                    java.io.File temp = temporary.get(i);
                    java.io.File original = sources.get(i);
                    MediaFile file = renamedFiles.get(i);
                    String originalName = original.getName();
                    int dot = originalName.lastIndexOf('.');
                    String ext = dot >= 0 ? originalName.substring(dot) : "";
                    java.io.File destination = new java.io.File(original.getParentFile(),
                            prefix + "_seq_" + com.mediasorter.features.RandomGenerator.sequenceLabel(
                                    renamedFiles.indexOf(file)) + ext);
                    if (temp.renameTo(destination)) {
                        String oldPath = file.getPath();
                        file.setPath(destination.getAbsolutePath());
                        android.content.SharedPreferences.Editor editor =
                                getSharedPreferences("settings_prefs", MODE_PRIVATE).edit();
                        int manual = file.getManualOrder();
                        editor.remove("manual_order:" + oldPath);
                        editor.putInt("manual_order:" + file.getPath(), manual);
                        editor.apply();
                        success++;
                    } else {
                        temp.renameTo(original);
                    }
                }
                final int renamed = success;
                final String completedPrefix = prefix;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        for (MediaFile file : affected) indexer.rescan(new java.io.File(file.getPath()).getParent());
                        scheduleRefresh();
                        Toast.makeText(MainActivity.this,
                                "Manual order updated: " + renamed + " files ("
                                        + completedPrefix + ")",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void showGalleryInfoPopup(View anchor, MediaFile file) {
        if (galleryRoot == null || anchor == null || file == null) return;
        dismissGalleryInfoPopup();
        TextView popup = new TextView(this);
        popup.setTextColor(0xFFFFFFFF);
        popup.setTextSize(11f);
        popup.setPadding(galleryDp(10), galleryDp(8), galleryDp(10), galleryDp(8));
        popup.setBackgroundColor(0xEE202030);
        StringBuilder text = new StringBuilder();
        text.append(file.getName()).append("\n");
        text.append(file.getFormattedSize()).append("  ")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(file.getDateAdded())))
                .append("\nTags: ").append(file.getTags().isEmpty() ? "none" : file.getTags());
        String sequence = findGallerySequence(file);
        if (!sequence.isEmpty()) text.append("\nSequence: ").append(sequence);
        text.append("\nStatus: ").append(fileStatus.getStatus(file.getPath()).name());
        popup.setText(text.toString());
        int[] anchorLocation = new int[2];
        int[] rootLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        galleryRoot.getLocationOnScreen(rootLocation);
        int left = Math.max(0, anchorLocation[0] - rootLocation[0]);
        int top = Math.max(0, anchorLocation[1] - rootLocation[1]);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(galleryDp(260), Math.max(galleryDp(160), galleryRoot.getWidth() - galleryDp(12))),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        galleryRoot.addView(popup, lp);
        galleryInfoPopup = popup;
    }

    private TextView galleryInfoPopup;

    private String findGallerySequence(MediaFile file) {
        for (String tag : file.getTags()) {
            int marker = tag.indexOf("_seq_");
            if (marker >= 0) return tag.substring(marker + 5);
        }
        return "";
    }

    private void dismissGalleryInfoPopup() {
        if (galleryInfoPopup != null && galleryRoot != null) {
            galleryRoot.removeView(galleryInfoPopup);
            galleryInfoPopup = null;
        }
    }

    private void showGallerySettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(galleryDp(20), galleryDp(8), galleryDp(20), galleryDp(4));

        EditText columns = galleryNumberInput(String.valueOf(galleryColumns()), "Columns 1-6");
        form.addView(galleryLabel("Default column count (1-6)"));
        form.addView(columns);

        Spinner quality = new Spinner(this);
        String[] qualities = {"Low", "Medium", "High"};
        ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, qualities);
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        quality.setAdapter(qualityAdapter);
        quality.setSelection(galleryQuality());
        form.addView(galleryLabel("Thumbnail quality"));
        form.addView(quality);

        CheckBox showFilename = galleryCheck("Show filename below thumbnail",
                galleryPrefs().getBoolean("gallery_show_filename", true));
        CheckBox showTag = galleryCheck("Show tag count badge",
                galleryPrefs().getBoolean("gallery_show_tag_count", true));
        CheckBox showFlag = galleryCheck("Show flag indicator",
                galleryPrefs().getBoolean("gallery_show_flag", true));
        CheckBox showSequence = galleryCheck("Show sequence label",
                galleryPrefs().getBoolean("gallery_show_seq", true));
        CheckBox animate = galleryCheck("Animate thumbnail load", galleryAnimate());
        EditText spacing = galleryNumberInput(String.valueOf(gallerySpacing()), "Cell spacing 0-16dp");
        form.addView(showFilename);
        form.addView(showTag);
        form.addView(showFlag);
        form.addView(showSequence);
        form.addView(galleryLabel("Cell spacing (0-16dp)"));
        form.addView(spacing);
        form.addView(animate);

        TextView note = galleryLabel("");
        if (galleryLowMemory) {
            note.setText("Some options are fixed on this device due to available memory.");
            showTag.setChecked(false);
            showFlag.setChecked(false);
            showSequence.setChecked(false);
            animate.setChecked(false);
            showTag.setEnabled(false);
            showFlag.setEnabled(false);
            showSequence.setEnabled(false);
            animate.setEnabled(false);
            quality.setEnabled(false);
            spacing.setText("2");
            spacing.setEnabled(false);
            int current = galleryColumns();
            columns.setText(String.valueOf(Math.min(3, current)));
            columns.setEnabled(false);
        }
        form.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Gallery settings")
                .setView(form)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        int columnValue = parseGalleryNumber(columns.getText().toString(), 3, 1, 6);
                        int spacingValue = parseGalleryNumber(spacing.getText().toString(), 4, 0, 16);
                        if (galleryLowMemory) {
                            columnValue = Math.min(3, columnValue);
                            spacingValue = 2;
                        }
                        String qualityValue = qualities[Math.max(0, Math.min(2,
                                quality.getSelectedItemPosition()))];
                        android.content.SharedPreferences.Editor editor = galleryPrefs().edit();
                        editor.putInt("gallery_columns", columnValue);
                        editor.putString("gallery_thumb_quality", qualityValue);
                        editor.putBoolean("gallery_show_filename", showFilename.isChecked());
                        editor.putBoolean("gallery_show_tag_count", galleryLowMemory ? false : showTag.isChecked());
                        editor.putBoolean("gallery_show_flag", galleryLowMemory ? false : showFlag.isChecked());
                        editor.putBoolean("gallery_show_seq", galleryLowMemory ? false : showSequence.isChecked());
                        editor.putInt("gallery_cell_spacing", spacingValue);
                        editor.putBoolean("gallery_animate_load", galleryLowMemory ? false : animate.isChecked());
                        editor.apply();
                        if (galleryLayoutManager != null) galleryLayoutManager.setSpanCount(columnValue);
                        if (galleryAdapter != null) {
                            galleryAdapter.setColumns(columnValue);
                            galleryAdapter.setSpacingDp(spacingValue);
                            galleryAdapter.refreshBadgeSettings();
                        }
                        if (galleryThumbnailLoader != null) {
                            galleryThumbnailLoader.setQuality(galleryQuality());
                            galleryThumbnailLoader.setAnimate(galleryAnimate());
                        }
                        updateGalleryMemoryWindow();
                    }
                })
                .setNeutralButton("Undo manual order", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        undoLastGalleryManualOrder();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView galleryLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFCCCCCC);
        view.setTextSize(12f);
        view.setPadding(0, galleryDp(4), 0, galleryDp(4));
        return view;
    }

    private CheckBox galleryCheck(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(0xFFCCCCCC);
        box.setChecked(checked);
        return box;
    }

    private EditText galleryNumberInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setTextColor(0xFFFFFFFF);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private int parseGalleryNumber(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<MediaFile> getActiveSelectedFiles() {
        List<MediaFile> result;
        if (galleryModeActive && galleryAdapter != null) {
            result = galleryAdapter.getSelectedFiles();
        } else {
            result = mediaAdapter != null
                    ? mediaAdapter.getSelectedFiles() : new ArrayList<MediaFile>();
        }
        if (sortManager != null && sortManager.isManualOrderActive()) {
            sortManager.sort(result);
        }
        return result;
    }

    private boolean isActiveSelectMode() {
        return galleryModeActive ? galleryAdapter != null && galleryAdapter.isSelectMode()
                : mediaAdapter != null && mediaAdapter.isSelectMode();
    }

    private void exitActiveSelectMode() {
        if (galleryModeActive && galleryAdapter != null) {
            galleryAdapter.exitSelectMode();
            updateGallerySelectionToolbar(0);
        } else if (mediaAdapter != null) {
            mediaAdapter.exitSelectMode();
        }
    }

    private void updateGallerySelectionToolbar(final int count) {
        if (btnScan == null) return;
        if (count > 0 && galleryModeActive) {
            btnScan.setText(count + " selected");
            btnScan.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    showGallerySelectionMenu();
                }
            });
        } else if (galleryModeActive) {
            btnScan.setText("SCAN");
            btnScan.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { startScan(); }
            });
        }
    }

    private void showGallerySelectionMenu() {
        if (galleryAdapter == null || galleryAdapter.getSelectedCount() == 0) return;
        final String[] actions = {
                "Select all", "Deselect all", "Invert selection", "Select untagged",
                "Select flagged", "Select by tag", "Select duplicates", "Tag selected",
                "Rename selected", "Rename sequence", "Auto-Link Sequential", "Flag selected",
                "Skip selected", "Move selected", "Copy selected", "Delete / trash",
                "Strip metadata", "Run macro", "Cancel"
        };
        new AlertDialog.Builder(this)
                .setTitle(galleryAdapter.getSelectedCount() + " selected")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: galleryAdapter.selectAll(); break;
                            case 1: galleryAdapter.deselectAll(); break;
                            case 2: galleryAdapter.invertSelection(); break;
                            case 3: galleryAdapter.selectMatching(galleryUntaggedFiles()); break;
                            case 4: galleryAdapter.selectMatching(galleryFlaggedFiles()); break;
                            case 5: showGalleryTagPickerForSelection(); break;
                            case 6: startGalleryDuplicateSelection(); break;
                            case 7: showBatchTagDialog(); break;
                            case 8: showBatchRenameDialog(); break;
                            case 9: showRenameSequenceDialog(); break;
                            case 10: showAutoLinkSequentialDialog(); break;
                            case 11: setGalleryStatus(FileStatus.Status.FLAGGED); break;
                            case 12: setGalleryStatus(FileStatus.Status.SKIPPED); break;
                            case 13: showGalleryCopyMoveDialog(false); break;
                            case 14: showGalleryCopyMoveDialog(true); break;
                            case 15: showBatchDeleteDialog(); break;
                            case 16: stripGalleryMetadata(); break;
                            case 17: showGalleryMacroPicker(); break;
                            default: break;
                        }
                    }
                })
                .show();
    }

    private void showGalleryTagPickerForSelection() {
        List<Tag> tags = tagManager.getAllTags();
        String[] names = new String[tags.size()];
        for (int i = 0; i < tags.size(); i++) names[i] = tags.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("Select by tag")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= names.length) return;
                        List<MediaFile> matches = new ArrayList<>();
                        for (MediaFile file : fullList) {
                            if (file.hasTag(names[which])) matches.add(file);
                        }
                        galleryAdapter.selectMatching(matches);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<MediaFile> galleryUntaggedFiles() {
        List<MediaFile> result = new ArrayList<>();
        for (MediaFile file : fullList) if (file.getTags().isEmpty()) result.add(file);
        return result;
    }

    private List<MediaFile> galleryFlaggedFiles() {
        List<MediaFile> result = new ArrayList<>();
        for (MediaFile file : fullList) if (fileStatus.isFlagged(file.getPath())) result.add(file);
        return result;
    }

    private void startGalleryDuplicateSelection() {
        final List<MediaFile> snapshot = galleryAdapter.getFiles();
        Toast.makeText(this, "Finding duplicates…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<DuplicateFinder.DuplicateGroup> groups =
                        DuplicateFinder.findDuplicates(snapshot, null);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        Set<String> paths = new java.util.HashSet<>();
                        List<MediaFile> matches = new ArrayList<>();
                        for (MediaFile file : snapshot) file.setDuplicate(false);
                        for (DuplicateFinder.DuplicateGroup group : groups) {
                            for (MediaFile file : group.files) {
                                paths.add(file.getPath());
                                matches.add(file);
                            }
                        }
                        filterManager.setDuplicatePaths(paths);
                        galleryAdapter.selectMatching(matches);
                        Toast.makeText(MainActivity.this,
                                "Duplicate selection complete", Toast.LENGTH_SHORT).show();
                        refreshGalleryFilterChips();
                    }
                });
            }
        }).start();
    }

    private void setGalleryStatus(FileStatus.Status status) {
        List<MediaFile> selected = galleryAdapter.getSelectedFiles();
        for (MediaFile file : selected) {
            if (status == FileStatus.Status.FLAGGED) fileStatus.setFlagged(file.getPath());
            else if (status == FileStatus.Status.SKIPPED) fileStatus.setSkipped(file.getPath());
            else if (status == FileStatus.Status.DONE) fileStatus.setDone(file.getPath());
        }
        galleryAdapter.notifyDataSetChanged();
        scheduleRefresh();
    }

    private void stripGalleryMetadata() {
        final List<MediaFile> selected = galleryAdapter.getSelectedFiles();
        new Thread(new Runnable() {
            @Override public void run() {
                for (MediaFile file : selected) {
                    String lower = file.getPath().toLowerCase(java.util.Locale.US);
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        MetadataWriter.stripJpegMetadata(file.getPath(), true);
                    } else if (lower.endsWith(".png")) {
                        MetadataWriter.stripPngMetadata(file.getPath());
                    }
                    file.getTags().clear();
                }
                mainHandler.post(new Runnable() {
                    @Override public void run() { scheduleRefresh(); }
                });
            }
        }).start();
    }

    private void showGalleryMacroPicker() {
        final List<GestureSettings.GestureMacro> macros = gestureSettings.loadMacros();
        if (macros.isEmpty()) return;
        String[] names = new String[macros.size()];
        for (int i = 0; i < macros.size(); i++) names[i] = macros.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Run macro")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which < 0 || which >= macros.size()) return;
                        executeMacro(macros.get(which).id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGalleryCopyMoveDialog(final boolean copy) {
        final EditText input = new EditText(this);
        input.setHint("Destination folder");
        input.setTextColor(0xFFFFFFFF);
        new AlertDialog.Builder(this)
                .setTitle(copy ? "Copy selected files" : "Move selected files")
                .setView(input)
                .setPositiveButton("Run", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        final String destination = input.getText().toString().trim();
                        final List<MediaFile> selected = galleryAdapter.getSelectedFiles();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                int count = copyOrMoveGalleryFiles(selected, destination, copy);
                                final int result = count;
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        scheduleRefresh();
                                        Toast.makeText(MainActivity.this,
                                                (copy ? "Copied " : "Moved ") + result + " files",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int copyOrMoveGalleryFiles(List<MediaFile> selected, String destination,
                                       boolean copy) {
        if (selected == null || destination == null || destination.isEmpty()) return 0;
        java.io.File dir = new java.io.File(destination);
        if (!dir.exists() && !dir.mkdirs()) return 0;
        int count = 0;
        for (MediaFile file : selected) {
            java.io.File source = new java.io.File(file.getPath());
            java.io.File target = new java.io.File(dir, source.getName());
            if (target.exists()) continue;
            try {
                if (copy) {
                    java.io.FileInputStream in = new java.io.FileInputStream(source);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                    in.close();
                    out.close();
                    count++;
                } else if (source.renameTo(target)) {
                    file.setPath(target.getAbsolutePath());
                    indexer.removeFromIndexOnly(source.getAbsolutePath());
                    count++;
                }
            } catch (Exception ignored) {}
        }
        return count;
    }

    private void undoLastGalleryManualOrder() {
        if (galleryManualUndo.empty()) return;
        List<MediaFile> reordered = galleryManualUndo.pop();
        if (reordered == null || reordered.isEmpty()) return;
        galleryAdapter.setFiles(reordered);
        indexer.updateManualOrder(reordered);
        scheduleRefresh();
    }

    private static class QuickTagItem {
        String name;
        int count;
        int initialType; // 0 = none, 1 = all, 2 = some (mixed)
        int currentType; // 0 = none, 1 = all, 2 = some (mixed)

        QuickTagItem(String name, int count, int initialType) {
            this.name = name;
            this.count = count;
            this.initialType = initialType;
            this.currentType = initialType;
        }
    }

    private class QuickTagListAdapter extends android.widget.BaseAdapter {
        private final List<QuickTagItem> items;
        private final android.view.LayoutInflater inflater;

        QuickTagListAdapter(List<QuickTagItem> items) {
            this.items = items;
            this.inflater = android.view.LayoutInflater.from(MainActivity.this);
        }

        @Override
        public int getCount() { return items.size(); }

        @Override
        public Object getItem(int position) { return items.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_tag, parent, false);
            }

            final QuickTagItem item = items.get(position);
            android.widget.TextView tagName = convertView.findViewById(R.id.tagName);
            android.widget.TextView tagCount = convertView.findViewById(R.id.tagCount);
            android.widget.CheckBox tagCheck = convertView.findViewById(R.id.tagCheck);

            tagCount.setText(String.valueOf(item.count));

            tagCheck.setOnCheckedChangeListener(null);
            if (item.currentType == 1) {
                tagCheck.setChecked(true);
                tagCheck.setAlpha(1.0f);
                if (item.initialType == 1) {
                    tagName.setText(item.name);
                } else {
                    tagName.setText(item.name + " (add to all)");
                }
            } else if (item.currentType == 0) {
                tagCheck.setChecked(false);
                tagCheck.setAlpha(1.0f);
                if (item.initialType == 1 || item.initialType == 2) {
                    tagName.setText(item.name + " (remove from all)");
                } else {
                    tagName.setText(item.name);
                }
            } else { // mixed (currentType == 2)
                tagCheck.setChecked(false);
                tagCheck.setAlpha(0.5f);
                tagName.setText(item.name + " (some files)");
            }

            android.view.View.OnClickListener clickListener = new android.view.View.OnClickListener() {
                @Override
                public void onClick(android.view.View v) {
                    if (item.initialType == 0) {
                        item.currentType = (item.currentType == 0) ? 1 : 0;
                    } else if (item.initialType == 1) {
                        item.currentType = (item.currentType == 1) ? 0 : 1;
                    } else { // mixed
                        if (item.currentType == 2) {
                            item.currentType = 1;
                        } else if (item.currentType == 1) {
                            item.currentType = 0;
                        } else {
                            item.currentType = 2;
                        }
                    }
                    notifyDataSetChanged();
                }
            };

            convertView.setOnClickListener(clickListener);
            tagCheck.setOnClickListener(clickListener);

            return convertView;
        }
    }
}
