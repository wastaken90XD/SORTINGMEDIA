package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.MediaAdapter;
import com.mediasorter.adapters.SidePanelTagAdapter;
import com.mediasorter.adapters.TagAdapter;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.TagList;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private List<MediaFile> fullList     = new ArrayList<>();
    private List<MediaFile> currentFiles = new ArrayList<>();
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());           

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
    }

    @Override
    public void onBackPressed() {
        if (mediaAdapter.isSelectMode()) {
            mediaAdapter.exitSelectMode();
            btnScan.setText("SCAN");
            btnScan.setOnClickListener(v -> startScan());
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
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
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            boolean volumeEnabled = sp.getBoolean("volume_keys_enabled", true);
            if (!volumeEnabled) return false;

            String label = (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) ? "Next File" : "Prev File";
            if (previewManager != null) previewManager.showHintLabel(label);
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
            android.content.SharedPreferences sp = getSharedPreferences("settings_prefs", MODE_PRIVATE);
            boolean volumeEnabled = sp.getBoolean("volume_keys_enabled", true);
            if (!volumeEnabled) return false;

            if (previewManager != null) previewManager.hideHintLabel();
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) navigateNext();
            else navigatePrev();
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
        cacheManager    = new CacheManager(this);
        thumbnailLoader = new ThumbnailLoader(this);
        sortManager     = new SortManager();
        fileStatus      = new FileStatus(this);
        filterManager   = new FilterManager(fileStatus);
        gestureSettings = new GestureSettings(this);
        windowManager   = new WindowManager(getWindowSize());
        autoOrganizer = new AutoOrganizer(this, tagManager, batchRenameManager, fileStatus);
        searchHistory  = new SearchHistory(this);
        indexer.init(this);
        indexer.setListener(this);

        // Auto-refresh tag list on any change
        tagManager.setTagChangeListener(() ->
                mainHandler.post(() ->
                        tagAdapter.setTags(tagManager.getAllTags())));
    }

    private int getWindowSize() {
        return getSharedPreferences("window_prefs", MODE_PRIVATE)
                .getInt("window_size", 20);
    }

    private void initAdapters() {
        mediaAdapter = new MediaAdapter(thumbnailLoader, this::onFileSelected);
        tagAdapter   = new TagAdapter(this::onTagToggled);

        // Tapping the tags line in the file list shows the quick tag popup;
        // with an active selection it targets every selected file at once.
        mediaAdapter.setOnFileLongClickListener((file, anchor) -> {
            if (mediaAdapter.isSelectMode() && mediaAdapter.getSelectedCount() > 0) {
                showQuickTagPopup(new ArrayList<>(mediaAdapter.getSelectedFiles()));
            } else {
                List<MediaFile> single = new ArrayList<>();
                single.add(file);
                showQuickTagPopup(single);
            }
        });

        mediaAdapter.setSelectionListener(count -> {
            mainHandler.post(() -> {
                if (count > 0) {
                    btnScan.setText(count + " selected");
                    btnScan.setOnClickListener(v ->
                            new AlertDialog.Builder(this)
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
                                            (d, which) -> {
                                                if (which == 0)      mediaAdapter.selectAll();
                                                else if (which == 1) mediaAdapter.deselectAll();
                                                else if (which == 2) showBatchTagDialog();
                                                else if (which == 3) showBatchRenameDialog();
                                                else if (which == 4) showColorAnalysisDialog();
                                                else if (which == 5) showBatchDeleteDialog();
                                                else if (which == 6) showAutoLinkSequentialDialog();
                                                else                 mediaAdapter.exitSelectMode();
                                            })
                                    .show());
                } else {
                    btnScan.setText("SCAN");
                    btnScan.setOnClickListener(v -> startScan());
                }
            });
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

        FrameLayout previewContainer = findViewById(R.id.previewPanel);
        getLayoutInflater().inflate(R.layout.panel_preview, previewContainer, true);
        previewManager = new PreviewManager(this, previewContainer, fileStatus);
        previewManager.setThumbnailLoader(thumbnailLoader);
        applyUiToggles();

        previewManager.setActionListener(new PreviewManager.ActionListener() {
            @Override public void onSkip()   { handleSkip(); }
            @Override public void onFlag()   { handleFlag(); }
            @Override public void onDone()   { handleDone(); }
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
        previewManager.getSidePanelAdapter().setListener((tagName, applied) ->
                applyTagToCurrentFile(tagName, applied));

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

        // Long-press search bar to show search history
        searchBar.setOnLongClickListener(v -> {
            showSearchHistoryDialog();
            return true;
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
        btnAddTag.setOnClickListener(v -> {
            String name = newTagInput.getText().toString().trim();
            if (name.isEmpty()) return;
            tagManager.createTag(name);
            tagAdapter.setTags(tagManager.getAllTags());
            newTagInput.setText("");
            tagSuggestView.setText("");
        });

        // Tag panel toggle with initialisation fix
        setupTagPanelToggle();

        btnSort = findViewById(R.id.btnSort);
        btnSort.setText(sortManager.getLabel());
        btnSort.setOnClickListener(v -> showSortMenu(v));

        btnFilter = findViewById(R.id.btnFilter);
        btnFilter.setText(filterManager.getLabel());
        btnFilter.setOnClickListener(v -> showFilterMenu(v));

        btnScan = findViewById(R.id.btnScan);
        btnScan.setOnClickListener(v -> startScan());

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
        btnRescanView.setOnClickListener(v -> {
            if (folderManager.isEmpty()) {
                Toast.makeText(this, "No folder set", Toast.LENGTH_SHORT).show();
                return;
            }
            // If a scan is already running, our new indexer will queue the rescan
            // instead of dropping it or crashing.
            boolean wasScanning = indexer.isScanning();
            for (String folder : folderManager.getFolders()) {
                indexer.rescanClean(folder);
            }
            Toast.makeText(this,
                    wasScanning ? "Scan in progress — rescan queued" : "Rescanning…",
                    Toast.LENGTH_SHORT).show();
        });
        // Long-press to repair a folder that got corrupted by the old bug
        // (manifest contains file but index missing, folder appears empty).
        btnRescanView.setOnLongClickListener(v -> {
            if (folderManager.isEmpty()) return false;
            new AlertDialog.Builder(this)
                .setTitle("Repair folder?")
                .setMessage("A previous crash could leave a folder with cached hashes but no visible files. Repair clears the stale cache for its folders and forces a full rescan.")
                .setPositiveButton("Repair", (d, w) -> {
                    for (String folder : folderManager.getFolders()) {
                        indexer.repairFolder(folder);
                    }
                    Toast.makeText(this, "Repairing…", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
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

                RecyclerView.LayoutManager lm = fileBrowser.getLayoutManager();
                int firstVisible = -1;
                if (lm instanceof LinearLayoutManager) {
                    firstVisible = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
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
            btnDelete.setOnClickListener(v -> deleteCurrentFile());
        }

        // Tapping the "1 / N" position counter opens the details dialog for
        // the file currently shown in the preview.
        TextView posCounter = findViewById(R.id.positionCounter);
        if (posCounter != null) {
            posCounter.setOnClickListener(v -> showFileDetailsDialog());
        }

        tagAdapter.setTags(tagManager.getAllTags());
    }

    private void setupTagPanelToggle() {
        Button btnToggle = findViewById(R.id.btnToggleTagPanel);
        LinearLayout tagPanel = findViewById(R.id.tagPanel);
        if (btnToggle == null || tagPanel == null) return;

        // Initial state: hidden
        tagPanel.setVisibility(View.GONE);
        syncTagToggleButton(btnToggle, false);

        btnToggle.setOnClickListener(v -> {
            if (tagManager != null && !tagManager.isTagsEnabled()) {
                Toast.makeText(this,
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
        mainHandler.postDelayed(this::executeRefresh, 150);
    }

    private void executeRefresh() {
        refreshPending = false;

        String query = searchBar != null
                ? searchBar.getText().toString().trim()
                : "";

        // Save non-empty searches to history
        if (!query.isEmpty()) saveSearchToHistory(query);

        List<MediaFile> base = indexer.getIndex();
        if (base == null) base = new ArrayList<>();

        if (!query.isEmpty()) {
            searchManager.setFullList(base);
            base = searchManager.search(query);
        }

        List<MediaFile> flattened = new ArrayList<>();
        try {
            List<Group> groups = groupManager.group(base);
            if (groups != null) {
                for (Group g : groups) {
                    if (g != null && g.getFiles() != null) {
                        flattened.addAll(g.getFiles());
                    }
                }
            }
        } catch (Exception e) {
            flattened = new ArrayList<>(base);
        }

        flattened = filterManager.apply(flattened);
        sortManager.sort(flattened);

        fullList = flattened;
        sLatestFullList = new ArrayList<>(fullList);
        sLatestTagList  = new ArrayList<>(tagManager.getAllTags());
        windowManager.setFullIndex(fullList);

        if (currentIndex >= 0 && currentIndex < fullList.size()) {
            windowManager.centerOn(currentIndex);
        }

        updateWindow();
        updateProgress();
    }

    // ── Window ────────────────────────────────────────────────────────────────

    private void updateWindow() {
        currentFiles = windowManager.getWindow();

        List<String> windowPaths = new ArrayList<>();
        for (MediaFile f : currentFiles) windowPaths.add(f.getPath());
        thumbnailLoader.evictOutsideWindow(windowPaths);

        mediaAdapter.setFiles(currentFiles);

        // If we have a current preview file, re-select it in the (new) window
        if (currentIndex >= 0 && currentIndex < fullList.size()) {
            MediaFile current = fullList.get(currentIndex);
            mediaAdapter.setSelected(current.getPath());
            // Also try to keep it visible in the list
            scrollFileListToCurrent(currentIndex);
        }
        updateEmptyState();
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
        for (GestureSettings.GestureStep step : steps) {
            if (step.action == GestureSettings.GestureAction.APPLY_TAG
                    && !step.tag.isEmpty()) {
                if (currentIndex < 0 || currentIndex >= fullList.size()) continue;
                MediaFile file = fullList.get(currentIndex);
                tagManager.applyOrUndo(file, step.tag);
                fullList.set(currentIndex, file);
                mediaAdapter.updateFileTags(file);
                refreshSidePanel();
                updateProgress();
            } else if (step.action == GestureSettings.GestureAction.MACRO) {
                executeMacro(step.tag);
            } else if (step.action == GestureSettings.GestureAction.REPEAT_LAST_MACRO) {
                executeRepeatLastMacro();
            } else {
                executeAction(step.action);
            }
        }
    }

    private void executeDpad(List<GestureSettings.GestureStep> steps) {
        if (gestureSettings != null && !gestureSettings.isDpadEnabled()) return;
        for (GestureSettings.GestureStep step : steps) {
            if (step.action == GestureSettings.GestureAction.APPLY_TAG
                    && !step.tag.isEmpty()) {
                if (currentIndex < 0 || currentIndex >= fullList.size()) continue;
                MediaFile file = fullList.get(currentIndex);
                tagManager.applyOrUndo(file, step.tag);
                fullList.set(currentIndex, file);
                mediaAdapter.updateFileTags(file);
                refreshSidePanel();
                updateProgress();
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
        switch (action) {
            case NEXT_FILE:    navigateNext();  break;
            case PREV_FILE:    navigatePrev();  break;
            case SKIP:         handleSkip();    break;
            case FLAG:         handleFlag();    break;
            case DONE:         handleDone();    break;
            case FILTER_CYCLE: cycleFilter();   break;
            case NOTHING:      break;
        }
    }

    private String lastRunMacroId = "";

    private void executeMacro(String id) {
        if (id == null || id.isEmpty()) return;
        GestureSettings.GestureMacro macro = gestureSettings.getMacro(id);
        if (macro == null) {
            Toast.makeText(this, "Macro not found", Toast.LENGTH_SHORT).show();
            return;
        }

        lastRunMacroId = id;

        List<MediaFile> targets = new ArrayList<>();
        if (mediaAdapter != null && mediaAdapter.isSelectMode() && !mediaAdapter.getSelectedFiles().isEmpty()) {
            targets.addAll(mediaAdapter.getSelectedFiles());
        } else {
            if (currentIndex >= 0 && currentIndex < fullList.size()) {
                targets.add(fullList.get(currentIndex));
            }
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
    if (fileStatus.isFlagged(file.getPath()))
        fileStatus.clearStatus(file.getPath());
    else
        fileStatus.setFlagged(file.getPath());
    autoOrganizer.applyToSingle(file);
    previewManager.load(file);
}

    private void handleDone() {
    if (currentIndex < 0 || currentIndex >= fullList.size()) return;
    MediaFile file = fullList.get(currentIndex);
    fileStatus.setDone(file.getPath());
    autoOrganizer.applyToSingle(file); 
    navigateNext();
}
    private void cycleFilter() {
        FilterManager.Filter[] filters = FilterManager.Filter.values();
        int next = (filterManager.getCurrent().ordinal() + 1) % filters.length;
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
        mainHandler.postDelayed(this::precacheAdjacent, 120);
    }

    private void navigatePrev() {
        if (fullList.isEmpty()) return;
        currentIndex = (currentIndex - 1 + fullList.size()) % fullList.size();
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
        mainHandler.postDelayed(this::precacheAdjacent, 120);
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
        if (absoluteIndex < 0 || absoluteIndex >= fullList.size()) return;
        MediaFile file = fullList.get(absoluteIndex);
        previewManager.load(file);
        previewManager.setPosition(absoluteIndex + 1, fullList.size());
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        mediaAdapter.setSelected(file.getPath());
        refreshSidePanel();

        // Keep the file list in sync with the preview (bidirectional)
        scrollFileListToCurrent(absoluteIndex);
    }

    /** Scrolls the file browser so the currently previewed file is visible. */
    private void scrollFileListToCurrent(int absoluteIndex) {
        if (fileBrowser == null) return;

        // Defensive copy to avoid ConcurrentModificationException during rapid switching / refresh
        List<MediaFile> windowCopy = new ArrayList<>(currentFiles);
        if (windowCopy.isEmpty()) return;

        // Find the position of this file inside the *current window* (currentFiles)
        int windowPos = -1;
        String targetPath = fullList.get(absoluteIndex).getPath();
        for (int i = 0; i < windowCopy.size(); i++) {
            if (windowCopy.get(i).getPath().equals(targetPath)) {
                windowPos = i;
                break;
            }
        }

        if (windowPos >= 0) {
            // Smooth scroll so the item is nicely centered
            LinearLayoutManager llm = (LinearLayoutManager) fileBrowser.getLayoutManager();
            if (llm != null) {
                int first = llm.findFirstVisibleItemPosition();
                int last  = llm.findLastVisibleItemPosition();
                int center = (first + last) / 2;

                if (Math.abs(windowPos - center) > 2) {
                    fileBrowser.smoothScrollToPosition(windowPos);
                } else {
                    // Already near the center — just ensure it's visible
                    fileBrowser.scrollToPosition(windowPos);
                }
            }
        }
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
        final List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        List<Tag> allTags = tagManager.getAllTags();
        if (allTags.isEmpty()) {
            // Nothing to choose from yet — offer creating the first tag.
            // Only re-open this dialog once a tag exists, otherwise "Back"
            // would loop between the two dialogs forever.
            showNewTagDialog(selectedFiles, () -> {
                if (!tagManager.getAllTags().isEmpty()) showBatchTagDialog();
            });
            return;
        }

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

        new AlertDialog.Builder(this)
                .setTitle("Tag " + selectedFiles.size() + " files")
                .setView(listView)
                .setPositiveButton("Apply", (d, w) -> {
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
                    mediaAdapter.exitSelectMode();
                    btnScan.setText("SCAN");
                    btnScan.setOnClickListener(v -> startScan());
                    scheduleRefresh();
                    Toast.makeText(this, "Tagged " + selectedFiles.size() + " files",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("＋ New tag", (d, w) -> {
                    // Snapshot the current states
                    Map<String, Integer> edits = new java.util.HashMap<>();
                    for (QuickTagItem item : items) edits.put(item.name, item.currentType);
                    showNewTagDialog(selectedFiles, () -> showBatchTagDialog(edits));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBatchRenameDialog() {
    List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
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
        .setPositiveButton("Rename", (d, w) -> {
            // The preview updater has already applied the settings, so just commit
            List<BatchRenameManager.RenamePreview> previews = batchRenameManager.preview(selectedFiles);
            BatchRenameManager.RenameResult result = batchRenameManager.apply(previews);
            Toast.makeText(this, "Renamed: " + result.succeeded
                + (result.failed > 0 ? "  Failed: " + result.failed : ""), Toast.LENGTH_SHORT).show();
            mediaAdapter.exitSelectMode();
            btnScan.setText("SCAN");
            btnScan.setOnClickListener(v -> startScan());
            scheduleRefresh();
        })
        .setNegativeButton("Cancel", (d, w) -> {
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
        })
        .setNeutralButton("Undo", (d, w) -> {
            if (batchRenameManager.canUndo()) {
                BatchRenameManager.RenameResult result = batchRenameManager.undo();
                Toast.makeText(this, "Undone: " + result.succeeded + " files", Toast.LENGTH_SHORT).show();
                mediaAdapter.exitSelectMode();
                scheduleRefresh();
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
        List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) return;

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
                "★ Signature tag (golden ticket)",
                "★ Golden ticket (tag + rename)"
        };
        android.widget.Spinner modeSpin = makeSpinner(modes);
        layout.addView(modeSpin);

        TextView goldenHint = new TextView(this);
        goldenHint.setText(
                "Golden ticket: every image gets the ONE colour that is rarest\n"
                + "across the analysed set but meaningful inside the image —\n"
                + "its own signature (e.g. \"★ Deep Lagoon\"). Re-runs skip\n"
                + "files that already carry a ★ tag, so they're fast.");
        goldenHint.setTextColor(0xFF888888);
        goldenHint.setTextSize(11f);
        goldenHint.setPadding(0, 8, 0, 0);
        layout.addView(goldenHint);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle("Color analysis — " + selectedFiles.size() + " files")
                .setView(sv)
                .setPositiveButton("Analyze", (d, w) -> {
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
                    new Thread(() -> {
                        List<ColorAnalyzer.Result> results =
                                ColorAnalyzer.analyze(selectedFiles, finalTopN,
                                        finalThreshold, finalMode, tagManager, batchRenameManager);
                        mainHandler.post(() -> {
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
                            mediaAdapter.exitSelectMode();
                            btnScan.setText("SCAN");
                            btnScan.setOnClickListener(v -> startScan());
                            scheduleRefresh();
                            Toast.makeText(this,
                                    golden
                                        ? "★ Golden tickets: " + signed + " / "
                                            + selectedFiles.size() + " files"
                                        : "Analyzed " + ok + " / "
                                            + selectedFiles.size() + " files",
                                    Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    // ── Sort / Filter / Group ─────────────────────────────────────────────────

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Name A-Z");
        menu.getMenu().add("Name Z-A");
        menu.getMenu().add("Size ↑");
        menu.getMenu().add("Size ↓");
        menu.getMenu().add("Date ↑");
        menu.getMenu().add("Date ↓");
        menu.getMenu().add("Type");
        menu.getMenu().add("Shuffle");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Name A-Z": sortManager.setSortBy(SortManager.SortBy.NAME_ASC);  break;
                case "Name Z-A": sortManager.setSortBy(SortManager.SortBy.NAME_DESC); break;
                case "Size ↑":   sortManager.setSortBy(SortManager.SortBy.SIZE_ASC);  break;
                case "Size ↓":   sortManager.setSortBy(SortManager.SortBy.SIZE_DESC); break;
                case "Date ↑":   sortManager.setSortBy(SortManager.SortBy.DATE_ASC);  break;
                case "Date ↓":   sortManager.setSortBy(SortManager.SortBy.DATE_DESC); break;
                case "Type":     sortManager.setSortBy(SortManager.SortBy.TYPE);      break;
                case "Shuffle":  sortManager.setSortBy(SortManager.SortBy.SHUFFLE);   break;
            }
            btnSort.setText(sortManager.getLabel());
            scheduleRefresh();
            return true;
        });
        menu.show();
    }

    private void showFilterMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("All");
        menu.getMenu().add("Untagged");
        menu.getMenu().add("Flagged");
        menu.getMenu().add("Skipped");
        menu.getMenu().add("Done");
        menu.setOnMenuItemClickListener(item -> {
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
        });
        menu.show();
    }

    private void showGroupMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("By File Type");
        menu.getMenu().add("By Tag");
        menu.getMenu().add("By Date");
        menu.getMenu().add("By Folder");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "By File Type": groupManager.setGroupBy(Group.GroupBy.FILE_TYPE); break;
                case "By Tag":       groupManager.setGroupBy(Group.GroupBy.TAG);       break;
                case "By Date":      groupManager.setGroupBy(Group.GroupBy.DATE);      break;
                case "By Folder":    groupManager.setGroupBy(Group.GroupBy.FOLDER);    break;
            }
            scheduleRefresh();
            return true;
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
    }

    // ── Folder dialog ─────────────────────────────────────────────────────────

    private void showAddFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("/sdcard/DCIM");
        new AlertDialog.Builder(this)
                .setTitle("Add folder to watch")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String path = input.getText().toString().trim();
                    if (!path.isEmpty()) {
                        folderManager.addFolder(path);
                        startScan();
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
                .setPositiveButton("Delete", (d, w) -> {
                    boolean deleted = indexer.deleteFile(file.getPath());
                    if (deleted) {
                        // Full refresh rebuilds everything consistently
                        scheduleRefresh();
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Batch delete ─────────────────────────────────────────────────────────

    private void showBatchDeleteDialog() {
        List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) return;

        new AlertDialog.Builder(this)
            .setTitle("Delete " + selectedFiles.size() + " files?")
            .setMessage("\"Move to trash\u00a0\" moves them to a .trash folder "
                    + "inside your first watched folder, so you can restore "
                    + "them later with any file manager.\n\n"
                    + "\"Delete permanently\" cannot be undone.")
            .setNeutralButton("Move to trash", (d, w) -> {
                int moved = moveSelectionToTrash(selectedFiles);
                mediaAdapter.exitSelectMode();
                btnScan.setText("SCAN");
                btnScan.setOnClickListener(v -> startScan());
                scheduleRefresh();
                Toast.makeText(this,
                        moved > 0 ? moved + " file(s) moved to trash"
                                  : "Trash failed (no watched folder?)",
                        Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("Delete permanently", (d, w) -> {
                int deleted = 0;
                for (MediaFile file : selectedFiles) {
                    if (indexer.deleteFile(file.getPath())) deleted++;
                }
                mediaAdapter.exitSelectMode();
                btnScan.setText("SCAN");
                btnScan.setOnClickListener(v -> startScan());
                scheduleRefresh();
                Toast.makeText(this, "Deleted " + deleted + " files", Toast.LENGTH_SHORT).show();
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
            .setItems(items.toArray(new String[0]), (d, which) -> {
                if (which >= types.size()) return;
                String type = types.get(which);
                if ("header".equals(type)) return;

                String itemText = items.get(which);
                final String query = itemText.startsWith("★ ") ? itemText.substring(2) : itemText;

                if ("saved".equals(type)) {
                    // Long-press-like: offer to remove or re-run
                    new AlertDialog.Builder(this)
                        .setTitle(query)
                        .setItems(new String[]{"Run search", "Remove from saved"}, (d2, w2) -> {
                            if (w2 == 0) {
                                searchBar.setText(query);
                            } else {
                                searchHistory.removeSavedSearch(query);
                                Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
                } else {
                    searchBar.setText(query);
                }
            })
            .setNeutralButton("Save current", (d, w) -> {
                String current = searchBar.getText().toString().trim();
                if (!current.isEmpty()) {
                    searchHistory.saveSearch(current);
                    Toast.makeText(this, "Search saved", Toast.LENGTH_SHORT).show();
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

        new Thread(() -> {
            List<DuplicateFinder.DuplicateGroup> dupes =
                    DuplicateFinder.findDuplicates(files, (scanned, total, name) -> {});
            mainHandler.post(() -> showDuplicateResults(dupes));
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
            showNewTagDialog(targets, () -> {
                if (!tagManager.getAllTags().isEmpty()) {
                    showQuickTagPopup(targets);
                } else {
                    Toast.makeText(this, "No tags yet", Toast.LENGTH_SHORT).show();
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
                .setPositiveButton("Apply", (dialog, which) -> {
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
                        mediaAdapter.exitSelectMode();
                        btnScan.setText("SCAN");
                        btnScan.setOnClickListener(v -> startScan());
                    }
                    Toast.makeText(this,
                            targets.size() == 1 ? "Tags updated"
                                                : "Tagged " + targets.size() + " files",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("＋ New tag", (dialog, which) -> {
                    Map<String, Integer> edits = new java.util.HashMap<>();
                    for (QuickTagItem item : items) edits.put(item.name, item.currentType);
                    showNewTagDialog(targets, () -> showQuickTagPopup(targets, edits));
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

        final int[] cycleState = new int[]{0};

        btnRand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input == null) return;
                String currentInput = input.getText().toString().trim();
                java.util.Set<String> existingTags = new java.util.HashSet<String>();
                java.util.List<Tag> allTagsSnapshot = new java.util.ArrayList<Tag>(tagManager.getAllTags());
                for (Tag t : allTagsSnapshot) {
                    existingTags.add(t.getName());
                }

                if (currentInput.isEmpty()) {
                    String generated = "";
                    if (cycleState[0] == 0) {
                        generated = RandomGenerator.randomPlaceholderTag();
                        cycleState[0] = 1;
                    } else if (cycleState[0] == 1) {
                        generated = RandomGenerator.randomSyllableTag();
                        cycleState[0] = 2;
                    } else {
                        generated = RandomGenerator.generateThirdCycleTag(existingTags);
                        cycleState[0] = 0;
                    }
                    if (input != null) {
                        input.setText(generated);
                        input.setSelection(generated.length());
                    }
                } else {
                    String generated = RandomGenerator.uniqueSuffixTag(currentInput, existingTags);
                    if (input != null) {
                        input.setText(generated);
                        input.setSelection(generated.length());
                    }
                }
            }
        });
    }

    private void showAutoLinkSequentialDialog() {
        final List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
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
                mediaAdapter.exitSelectMode();
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
        final List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
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

                mediaAdapter.exitSelectMode();
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
        updateProgress();
    }

    // ── FolderWatcher callbacks ──────────────────────────────────────────────

    @Override
    public void onFileAdded(String path) {
        mainHandler.post(() -> {
            if (!indexer.isScanning()) {
                indexer.rescan(new java.io.File(path).getParent());
            }
        });
    }

    @Override
    public void onFileDeleted(String path) {
        final String deletedPath = path;
        mainHandler.post(() -> {
            // Remove from adapter immediately for responsiveness,
            // then do a full refresh to synchronise all data structures.
            mediaAdapter.removeFile(deletedPath);
            scheduleRefresh();
        });
    }

    @Override
    public void onFileModified(String path) {
        mainHandler.post(() -> {
            if (!indexer.isScanning()) {
                cacheManager.invalidateThumbnail(path);
                indexer.rescan(new java.io.File(path).getParent());
            }
        });
    }

    // ── MediaIndexer callbacks ───────────────────────────────────────────────

    @Override
    public void onFileFound(MediaFile file) {}

    @Override
    public void onScanProgress(int scanned, int total, String currentFile) {
        mainHandler.post(() -> {
            if (scanProgress != null) {
                scanProgress.setVisibility(View.VISIBLE);
                scanProgress.setMax(total > 0 ? total : 100);
                scanProgress.setProgress(scanned);
            }
            if (btnScan != null) {
                btnScan.setText(scanned + "/" + total);
            }
        });
    }

    @Override
    public void onPageLoaded(List<MediaFile> page) {
        mainHandler.post(this::scheduleRefresh);
    }

    @Override
    public void onScanComplete(List<MediaFile> allFiles) {
        mainHandler.post(() -> {
            btnScan.setEnabled(true);
            btnScan.setText("SCAN");
            if (scanProgress != null) scanProgress.setVisibility(View.GONE);

            // Import all tags found in scanned files into TagManager
            List<String> allTagsFromFiles = indexer.getAllTagsFromIndex();
            if (!allTagsFromFiles.isEmpty()) {
                tagManager.importTagsFromFiles(allTagsFromFiles);
            }

            executeRefresh();
        });
    }

    @Override
    public void onFileChanged(MediaFile file) {
        mainHandler.post(() -> {
            for (int i = 0; i < fullList.size(); i++) {
                if (fullList.get(i).getPath().equals(file.getPath())) {
                    fullList.set(i, file);
                    break;
                }
            }
            mediaAdapter.updateFile(file);
        });
    }

    @Override
    public void onFileRemoved(String path) {
        mainHandler.post(() -> {
            for (int i = fullList.size() - 1; i >= 0; i--) {
                if (fullList.get(i).getPath().equals(path)) fullList.remove(i);
            }
            mediaAdapter.removeFile(path);
            updateProgress();
        });
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
