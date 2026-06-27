package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.MediaAdapter;
import com.mediasorter.adapters.SidePanelTagAdapter;
import com.mediasorter.adapters.TagAdapter;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.TagList;
import java.util.ArrayList;
import java.util.List;
import com.mediasorter.BatchRenameManager;
import android.widget.Toast;
import com.mediasorter.ColorAnalyzer;
import com.mediasorter.models.Tag;


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

    private MediaAdapter mediaAdapter;
    private TagAdapter   tagAdapter;

    private List<MediaFile> fullList     = new ArrayList<>();
    private List<MediaFile> currentFiles = new ArrayList<>();
    private int             currentIndex = -1;

    private boolean refreshPending = false;

    private EditText searchBar;
    private TextView progressLabel;
    private Button   btnSort;
    private Button   btnFilter;
    private Button   btnScan;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
protected void onCreate(Bundle savedInstanceState) {
    CrashLogger.init(this);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    initManagers();  // ← must be first
    initAdapters();  // ← depends on thumbnailLoader from initManagers
    initViews();
}

    @Override
    protected void onResume() {
        super.onResume();
        if (!indexer.getIndex().isEmpty()) {
            for (String folder : folderManager.getFolders()) {
                indexer.rescan(folder);
            }
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
                                "Cancel"
                            },
                            (d, which) -> {
                                if (which == 0)      mediaAdapter.selectAll();
                                else if (which == 1) mediaAdapter.deselectAll();
                                else if (which == 2) showBatchTagDialog();
                                else if (which == 3) showBatchRenameDialog();
                                else if (which == 4) showColorAnalysisDialog();
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
        RecyclerView fileBrowser = findViewById(R.id.fileBrowser);
        fileBrowser.setLayoutManager(new LinearLayoutManager(this));
        fileBrowser.setAdapter(mediaAdapter);

        RecyclerView tagList = findViewById(R.id.tagList);
        tagList.setLayoutManager(new LinearLayoutManager(this));
        tagList.setAdapter(tagAdapter);

        FrameLayout previewContainer = findViewById(R.id.previewPanel);
        getLayoutInflater().inflate(R.layout.panel_preview, previewContainer, true);
        previewManager = new PreviewManager(this, previewContainer, fileStatus);

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

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                scheduleRefresh();
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

        Button btnAddTag = findViewById(R.id.btnAddTag);
        btnAddTag.setOnClickListener(v -> {
            EditText input = findViewById(R.id.newTagInput);
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            tagManager.createTag(name);
            tagAdapter.setTags(tagManager.getAllTags());
            input.setText("");
        });

        Button btnToggleTagPanel = findViewById(R.id.btnToggleTagPanel);
        LinearLayout tagPanel    = findViewById(R.id.tagPanel);
        if (btnToggleTagPanel != null && tagPanel != null) {
                tagPanel.setVisibility(View.GONE);
                btnToggleTagPanel.setOnClickListener(v -> {
        boolean visible = tagPanel.getVisibility() == View.VISIBLE;
        tagPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        btnToggleTagPanel.setText(visible ? "Tags" : "Tags ✓");
        btnToggleTagPanel.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                visible ? 0xFF2A2A3E : 0xFFE94560));
    });
}

        btnSort = findViewById(R.id.btnSort);
        btnSort.setText(sortManager.getLabel());
        btnSort.setOnClickListener(v -> showSortMenu(v));

        btnFilter = findViewById(R.id.btnFilter);
        btnFilter.setText(filterManager.getLabel());
        btnFilter.setOnClickListener(v -> showFilterMenu(v));

        btnScan = findViewById(R.id.btnScan);
        btnScan.setOnClickListener(v -> startScan());

        findViewById(R.id.btnRescan).setOnClickListener(v -> {
    for (String folder : folderManager.getFolders()) {
        indexer.rescanClean(folder);
    }
    Toast.makeText(this, "Rescanning…", Toast.LENGTH_SHORT).show();
});

        findViewById(R.id.btnGroupBy).setOnClickListener(v -> showGroupMenu(v));

        findViewById(R.id.btnDashboard).setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class)));

        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        Button btnDelete = findViewById(R.id.btnDelete);
if (btnDelete != null) {
    btnDelete.setOnClickListener(v -> deleteCurrentFile());
}

        tagAdapter.setTags(tagManager.getAllTags());
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
        MediaFile file     = fullList.get(currentIndex);
        TagList   active   = tagListManager.getActiveList();
        List<String> tags  = active.getTags();
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
        btnScan.setEnabled(false);
        btnScan.setText("…");
        for (String folder : folderManager.getFolders()) {
            indexer.scanFolder(folder);
            folderWatcher.watch(folder);
        }
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
            mediaAdapter.updateFile(file);
            refreshSidePanel();
            updateProgress();
        } else {
            executeAction(step.action);
        }
    }
}
                
    private void executeDpad(List<GestureSettings.GestureStep> steps) {
    for (GestureSettings.GestureStep step : steps) {
        if (step.action == GestureSettings.GestureAction.APPLY_TAG
                && !step.tag.isEmpty()) {
            if (currentIndex < 0 || currentIndex >= fullList.size()) continue;
            MediaFile file = fullList.get(currentIndex);
            tagManager.applyOrUndo(file, step.tag);
            fullList.set(currentIndex, file);
            mediaAdapter.updateFile(file);
            refreshSidePanel();
            updateProgress();
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

    // ── Quick actions ─────────────────────────────────────────────────────────

    private void handleSkip() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        fileStatus.setSkipped(fullList.get(currentIndex).getPath());
        navigateNext();
    }

    private void handleFlag() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        MediaFile file = fullList.get(currentIndex);
        if (fileStatus.isFlagged(file.getPath()))
            fileStatus.clearStatus(file.getPath());
        else
            fileStatus.setFlagged(file.getPath());
        previewManager.load(file);
    }

    private void handleDone() {
        if (currentIndex < 0 || currentIndex >= fullList.size()) return;
        fileStatus.setDone(fullList.get(currentIndex).getPath());
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
        mediaAdapter.updateFile(file);
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
    }

    private void navigatePrev() {
        if (fullList.isEmpty()) return;
        currentIndex = (currentIndex - 1 + fullList.size()) % fullList.size();
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
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
    }

    private void onFileSelected(MediaFile file) {
        int abs = windowManager.findAbsoluteIndex(file);
        if (abs < 0) return;
        currentIndex = abs;
        shiftWindowIfNeeded(currentIndex);
        loadFileAtIndex(currentIndex);
    }
                
        private void showBatchTagDialog() {
            List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
            if (selectedFiles.isEmpty()) return;

    List<Tag> allTags = tagManager.getAllTags();
    if (allTags.isEmpty()) {
        Toast.makeText(this, "No tags yet", Toast.LENGTH_SHORT).show();
        return;
    }

    String[]  tagNames = new String[allTags.size()];
    boolean[] checked  = new boolean[allTags.size()];
    for (int i = 0; i < allTags.size(); i++) tagNames[i] = allTags.get(i).getName();

    new AlertDialog.Builder(this)
        .setTitle("Tag " + selectedFiles.size() + " files")
        .setMultiChoiceItems(tagNames, checked,
            (d, which, isChecked) -> checked[which] = isChecked)
        .setPositiveButton("Apply", (d, w) -> {
            for (MediaFile file : selectedFiles) {
                for (int i = 0; i < tagNames.length; i++) {
                    if (checked[i]) tagManager.applyTag(file, tagNames[i]);
                }
                mediaAdapter.updateFile(file);
            }
            mediaAdapter.exitSelectMode();
            btnScan.setText("SCAN");
            btnScan.setOnClickListener(v -> startScan());
            scheduleRefresh();
            Toast.makeText(this, "Tagged " + selectedFiles.size() + " files",
                Toast.LENGTH_SHORT).show();
        })
        .setNegativeButton("Cancel", null)
        .show();
}
    private void showBatchRenameDialog() {
    List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
    if (selectedFiles.isEmpty()) return;

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(32, 16, 32, 16);

    // Separator
    TextView sepLabel = new TextView(this);
    sepLabel.setText("Separator:");
    sepLabel.setTextColor(0xFFCCCCCC);
    layout.addView(sepLabel);

    String[] sepOptions = {"Underscore (_)", "Dash (-)", "Space ( )", "None"};
    android.widget.Spinner sepSpinner = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> sepAdapter = new android.widget.ArrayAdapter<>(
        this, android.R.layout.simple_spinner_item, sepOptions);
    sepAdapter.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item);
    sepSpinner.setAdapter(sepAdapter);
    layout.addView(sepSpinner);

    // Order
    TextView ordLabel = new TextView(this);
    ordLabel.setText("Order:");
    ordLabel.setTextColor(0xFFCCCCCC);
    layout.addView(ordLabel);

    String[] ordOptions = {"Tags Only", "Original + Tags", "Tags + Original"};
    android.widget.Spinner ordSpinner = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> ordAdapter = new android.widget.ArrayAdapter<>(
        this, android.R.layout.simple_spinner_item, ordOptions);
    ordAdapter.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item);
    ordSpinner.setAdapter(ordAdapter);
    layout.addView(ordSpinner);

    // Case
    TextView caseLabel = new TextView(this);
    caseLabel.setText("Case:");
    caseLabel.setTextColor(0xFFCCCCCC);
    layout.addView(caseLabel);

    String[] caseOptions = {"As-is", "Lowercase", "Uppercase"};
    android.widget.Spinner caseSpinner = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> caseAdapter = new android.widget.ArrayAdapter<>(
        this, android.R.layout.simple_spinner_item, caseOptions);
    caseAdapter.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item);
    caseSpinner.setAdapter(caseAdapter);
    layout.addView(caseSpinner);

    // Preview
    TextView previewLabel = new TextView(this);
    previewLabel.setText("Preview:");
    previewLabel.setTextColor(0xFFCCCCCC);
    layout.addView(previewLabel);

    TextView previewText = new TextView(this);
    previewText.setTextColor(0xFF888888);
    previewText.setTextSize(10f);
    layout.addView(previewText);

    // Update preview on spinner change
    android.widget.AdapterView.OnItemSelectedListener previewUpdater =
        new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p,
                    View v, int pos, long id) {
                updateRenamePreview(batchRenameManager, selectedFiles,
                    sepSpinner, ordSpinner, caseSpinner, previewText);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };

    sepSpinner.setOnItemSelectedListener(previewUpdater);
    ordSpinner.setOnItemSelectedListener(previewUpdater);
    caseSpinner.setOnItemSelectedListener(previewUpdater);

    android.widget.ScrollView sv = new android.widget.ScrollView(this);
    sv.addView(layout);

    new AlertDialog.Builder(this)
        .setTitle("Batch Rename " + selectedFiles.size() + " files")
        .setView(sv)
        .setPositiveButton("Rename", (d, w) -> {
            applyBatchRename(batchRenameManager, selectedFiles,
                sepSpinner, ordSpinner, caseSpinner);
        })
        .setNegativeButton("Cancel", null)
        .setNeutralButton("Undo", (d, w) -> {
            if (batchRenameManager.canUndo()) {
                BatchRenameManager.RenameResult result = batchRenameManager.undo();
                android.widget.Toast.makeText(this,
                    "Undone: " + result.succeeded + " files",
                    android.widget.Toast.LENGTH_SHORT).show();
                mediaAdapter.exitSelectMode();
                scheduleRefresh();
            }
        })
        .show();
}

private void showColorAnalysisDialog() {
    List<MediaFile> selectedFiles = mediaAdapter.getSelectedFiles();
    if (selectedFiles.isEmpty()) return;

    android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
    layout.setOrientation(android.widget.LinearLayout.VERTICAL);
    layout.setPadding(32, 16, 32, 16);

    layout.addView(makeLabel("Number of colors per image (1-10):"));
android.widget.EditText colorCountInput = new android.widget.EditText(this);
colorCountInput.setText("3");
colorCountInput.setTextColor(0xFFFFFFFF);
colorCountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
colorCountInput.setBackground(null);
layout.addView(colorCountInput);

layout.addView(makeLabel("Similarity threshold (1-100, lower = stricter):"));
android.widget.EditText threshInput = new android.widget.EditText(this);
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
        "All three"
    };
    android.widget.Spinner modeSpin = makeSpinner(modes);
    layout.addView(modeSpin);

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
                    int ok = 0;
                    for (ColorAnalyzer.Result r : results) if (r.success) ok++;
                    mediaAdapter.exitSelectMode();
                    btnScan.setText("SCAN");
                    btnScan.setOnClickListener(v -> startScan());
                    scheduleRefresh();
                    Toast.makeText(this,
                        "Analyzed " + ok + " / " + selectedFiles.size() + " files",
                        Toast.LENGTH_SHORT).show();
                });
            }).start();
        })
        .setNegativeButton("Cancel", null)
        .show();
}

private android.widget.TextView makeLabel(String text) {
    android.widget.TextView tv = new android.widget.TextView(this);
    tv.setText(text);
    tv.setTextColor(0xFFCCCCCC);
    tv.setTextSize(12f);
    return tv;
}

private android.widget.Spinner makeSpinner(String[] options) {
    android.widget.Spinner sp = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
        this, android.R.layout.simple_spinner_item, options);
    ad.setDropDownViewResource(
        android.R.layout.simple_spinner_dropdown_item);
    sp.setAdapter(ad);
    return sp;
}

private void updateRenamePreview(BatchRenameManager mgr,
        List<MediaFile> files,
        android.widget.Spinner sep,
        android.widget.Spinner ord,
        android.widget.Spinner cas,
        TextView previewText) {

    mgr.setSeparator(sepFromPos(sep.getSelectedItemPosition()));
    mgr.setOrder(ordFromPos(ord.getSelectedItemPosition()));
    mgr.setCaseMode(caseFromPos(cas.getSelectedItemPosition()));

    List<BatchRenameManager.RenamePreview> previews = mgr.preview(files);
    StringBuilder sb = new StringBuilder();
    int shown = Math.min(previews.size(), 5);
    for (int i = 0; i < shown; i++) {
        BatchRenameManager.RenamePreview p = previews.get(i);
        sb.append(p.originalName)
          .append(" → ")
          .append(p.newName);
        if (p.hasConflict) sb.append(" ⚠ conflict");
        sb.append("\n");
    }
    if (previews.size() > 5) {
        sb.append("... and ").append(previews.size() - 5).append(" more");
    }
    previewText.setText(sb.toString());
}

private void applyBatchRename(BatchRenameManager mgr,
        List<MediaFile> files,
        android.widget.Spinner sep,
        android.widget.Spinner ord,
        android.widget.Spinner cas) {

    mgr.setSeparator(sepFromPos(sep.getSelectedItemPosition()));
    mgr.setOrder(ordFromPos(ord.getSelectedItemPosition()));
    mgr.setCaseMode(caseFromPos(cas.getSelectedItemPosition()));

    List<BatchRenameManager.RenamePreview> previews = mgr.preview(files);
    BatchRenameManager.RenameResult result = mgr.apply(previews);

    android.widget.Toast.makeText(this,
        "Renamed: " + result.succeeded
        + (result.failed > 0 ? "  Failed: " + result.failed : ""),
        android.widget.Toast.LENGTH_SHORT).show();

    mediaAdapter.exitSelectMode();
    btnScan.setText("SCAN");
    btnScan.setOnClickListener(v -> startScan());
    scheduleRefresh();
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
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Name A-Z": sortManager.setSortBy(SortManager.SortBy.NAME_ASC);  break;
                case "Name Z-A": sortManager.setSortBy(SortManager.SortBy.NAME_DESC); break;
                case "Size ↑":   sortManager.setSortBy(SortManager.SortBy.SIZE_ASC);  break;
                case "Size ↓":   sortManager.setSortBy(SortManager.SortBy.SIZE_DESC); break;
                case "Date ↑":   sortManager.setSortBy(SortManager.SortBy.DATE_ASC);  break;
                case "Date ↓":   sortManager.setSortBy(SortManager.SortBy.DATE_DESC); break;
                case "Type":     sortManager.setSortBy(SortManager.SortBy.TYPE);      break;
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

    private void deleteCurrentFile() {
    if (currentIndex < 0 || currentIndex >= fullList.size()) return;
    MediaFile file = fullList.get(currentIndex);

    new AlertDialog.Builder(this)
        .setTitle("Delete file?")
        .setMessage(file.getName())
        .setPositiveButton("Delete", (d, w) -> {
            boolean deleted = indexer.deleteFile(file.getPath());
            if (deleted) {
                for (int i = fullList.size() - 1; i >= 0; i--) {
            if (fullList.get(i).getPath().equals(file.getPath())) fullList.remove(i);
        }
                mediaAdapter.removeFile(file.getPath());
                thumbnailLoader.cancel(file.getPath());
                if (currentIndex >= fullList.size()) {
                    currentIndex = fullList.size() - 1;
                }
                if (currentIndex >= 0) loadFileAtIndex(currentIndex);
                updateProgress();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show();
            }
        })
        .setNegativeButton("Cancel", null)
        .show();
}
                
    // ── FolderWatcher ─────────────────────────────────────────────────────────

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
        for (int i = fullList.size() - 1; i >= 0; i--) {
            if (fullList.get(i).getPath().equals(deletedPath)) fullList.remove(i);
        }
        for (int i = currentFiles.size() - 1; i >= 0; i--) {
            if (currentFiles.get(i).getPath().equals(deletedPath)) currentFiles.remove(i);
        }
        mediaAdapter.removeFile(deletedPath);
        updateProgress();
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
    // ── MediaIndexer ──────────────────────────────────────────────────────────

    @Override
    public void onFileFound(MediaFile file) {}

    @Override
    public void onPageLoaded(List<MediaFile> page) {
        mainHandler.post(this::scheduleRefresh);
    }

    @Override
    public void onScanComplete(List<MediaFile> allFiles) {
    mainHandler.post(() -> {
        btnScan.setEnabled(true);
        btnScan.setText("SCAN");

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
}
