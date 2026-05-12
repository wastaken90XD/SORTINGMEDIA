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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.MediaAdapter;
import com.mediasorter.adapters.TagAdapter;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements FolderWatcher.Listener, MediaIndexer.IndexListener {

    private MediaIndexer    indexer;
    private TagManager      tagManager;
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

    private MediaAdapter  mediaAdapter;
    private TagAdapter    tagAdapter;
    private QuickTagPanel quickTagPanel;

    private List<MediaFile> currentFiles = new ArrayList<>();
    private int             currentIndex = -1;

    private EditText searchBar;
    private EditText tagSearch;
    private EditText newTagInput;
    private TextView progressLabel;
    private Button   btnSort;
    private Button   btnFilter;
    private Button   btnScan;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLogger.init(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initManagers();
        initAdapters();
        initViews();
        // No auto scan — user initiates
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only rescan if already indexed
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

    private void initManagers() {
        indexer          = new MediaIndexer();
        tagManager       = new TagManager(this);
        folderManager    = new FolderManager(this);
        folderWatcher    = new FolderWatcher(this);
        searchManager    = new SearchManager();
        groupManager     = new GroupManager();
        cacheManager     = new CacheManager(this);
        thumbnailLoader  = new ThumbnailLoader(this);
        sortManager      = new SortManager();
        fileStatus       = new FileStatus(this);
        filterManager    = new FilterManager(fileStatus);
        gestureSettings  = new GestureSettings(this);
        indexer.setListener(this);
    }

    private void initAdapters() {
        mediaAdapter = new MediaAdapter(thumbnailLoader, this::onFileSelected);
        tagAdapter   = new TagAdapter(this::onTagToggled);
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
            @Override public void onTags() { quickTagPanel.show(); }
            @Override public void onSkip() { handleSkip(); }
            @Override public void onFlag() { handleFlag(); }
            @Override public void onDone() { handleDone(); }
        });

        quickTagPanel = new QuickTagPanel(this, previewContainer);
        quickTagPanel.setListener(new QuickTagPanel.Listener() {
            @Override
            public void onTagToggled(String tagName, boolean applied) {
                if (currentIndex < 0 || currentIndex >= currentFiles.size()) return;
                MediaFile file = currentFiles.get(currentIndex);
                if (applied) tagManager.applyTag(file, tagName);
                else         tagManager.removeTag(file, tagName);
                tagAdapter.setCurrentFile(file);
                tagAdapter.setTags(tagManager.getAllTags());
                mediaAdapter.updateFile(file);
                updateProgress();
            }
            @Override public void onDismiss() {}
        });

        // Gesture detector — reads from GestureSettings
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
                            executeGesture(dx < 0
                                ? gestureSettings.getLeft()
                                : gestureSettings.getRight());
                            return true;
                        }
                    } else {
                        if (Math.abs(dy) > 100) {
                            executeGesture(dy < 0
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

        searchBar = findViewById(R.id.searchBar);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                runSearch(s.toString());
            }
        });

        tagSearch = findViewById(R.id.tagSearch);
        tagSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                tagAdapter.setTags(tagManager.searchTags(s.toString()));
            }
        });

        newTagInput = findViewById(R.id.newTagInput);
        Button btnAddTag = findViewById(R.id.btnAddTag);
        btnAddTag.setOnClickListener(v -> {
            String name = newTagInput.getText().toString().trim();
            if (name.isEmpty()) return;
            tagManager.createTag(name);
            tagAdapter.setTags(tagManager.getAllTags());
            newTagInput.setText("");
        });

        btnSort = findViewById(R.id.btnSort);
        btnSort.setText(sortManager.getLabel());
        btnSort.setOnClickListener(v -> showSortMenu(v));

        btnFilter = findViewById(R.id.btnFilter);
        btnFilter.setText(filterManager.getLabel());
        btnFilter.setOnClickListener(v -> showFilterMenu(v));

        btnScan = findViewById(R.id.btnScan);
        btnScan.setOnClickListener(v -> startScan());

        Button btnRescan = findViewById(R.id.btnRescan);
        btnRescan.setOnClickListener(v -> {
            for (String folder : folderManager.getFolders()) {
                indexer.rescan(folder);
            }
        });

        Button btnGroupBy = findViewById(R.id.btnGroupBy);
        btnGroupBy.setOnClickListener(v -> showGroupMenu(v));

        Button btnDashboard = findViewById(R.id.btnDashboard);
        btnDashboard.setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class)));

        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        tagAdapter.setTags(tagManager.getAllTags());
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private void startScan() {
        if (folderManager.isEmpty()) {
            showAddFolderDialog();
            return;
        }
        btnScan.setEnabled(false);
        btnScan.setText("Scanning…");
        for (String folder : folderManager.getFolders()) {
            indexer.scanFolder(folder);
            folderWatcher.watch(folder);
        }
    }

    // ── Gesture execution ─────────────────────────────────────────────────────

    private void executeGesture(GestureSettings.GestureAction action) {
        switch (action) {
            case NEXT_FILE:    navigateNext();    break;
            case PREV_FILE:    navigatePrev();    break;
            case QUICK_TAGS:   quickTagPanel.show(); break;
            case SKIP:         handleSkip();      break;
            case FLAG:         handleFlag();      break;
            case DONE:         handleDone();      break;
            case FILTER_CYCLE: cycleFilter();     break;
            case NOTHING:      break;
        }
    }

    // ── Quick actions ─────────────────────────────────────────────────────────

    private void handleSkip() {
        if (currentIndex < 0 || currentIndex >= currentFiles.size()) return;
        fileStatus.setSkipped(currentFiles.get(currentIndex).getPath());
        navigateNext();
    }

    private void handleFlag() {
        if (currentIndex < 0 || currentIndex >= currentFiles.size()) return;
        MediaFile file = currentFiles.get(currentIndex);
        if (fileStatus.isFlagged(file.getPath()))
            fileStatus.clearStatus(file.getPath());
        else
            fileStatus.setFlagged(file.getPath());
        previewManager.load(file);
    }

    private void handleDone() {
        if (currentIndex < 0 || currentIndex >= currentFiles.size()) return;
        fileStatus.setDone(currentFiles.get(currentIndex).getPath());
        navigateNext();
    }

    private void cycleFilter() {
        FilterManager.Filter[] filters = FilterManager.Filter.values();
        int next = (filterManager.getCurrent().ordinal() + 1) % filters.length;
        filterManager.setFilter(filters[next]);
        btnFilter.setText(filterManager.getLabel());
        applyGrouping();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateNext() {
        if (currentFiles.isEmpty()) return;
        currentIndex = (currentIndex + 1) % currentFiles.size();
        loadFileAtIndex(currentIndex);
    }

    private void navigatePrev() {
        if (currentFiles.isEmpty()) return;
        currentIndex = (currentIndex - 1 + currentFiles.size()) % currentFiles.size();
        loadFileAtIndex(currentIndex);
    }

    private void loadFileAtIndex(int index) {
        if (index < 0 || index >= currentFiles.size()) return;
        MediaFile file = currentFiles.get(index);
        previewManager.load(file);
        previewManager.setPosition(index + 1, currentFiles.size());
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        mediaAdapter.setSelected(file.getPath());
        quickTagPanel.setCurrentFile(file, tagManager.getAllTags());
    }

    private void onFileSelected(MediaFile file) {
        currentIndex = currentFiles.indexOf(file);
        if (currentIndex < 0) {
            currentFiles.add(file);
            currentIndex = currentFiles.size() - 1;
        }
        previewManager.load(file);
        previewManager.setPosition(currentIndex + 1, currentFiles.size());
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        quickTagPanel.setCurrentFile(file, tagManager.getAllTags());
    }

    private void onTagToggled(String tagName, boolean applied) {
        if (currentIndex < 0 || currentIndex >= currentFiles.size()) return;
        MediaFile file = currentFiles.get(currentIndex);
        if (applied) tagManager.applyTag(file, tagName);
        else         tagManager.removeTag(file, tagName);
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
        mediaAdapter.updateFile(file);
        updateProgress();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void runSearch(String query) {
        searchManager.setFullList(indexer.getIndex());
        List<MediaFile> results = searchManager.search(query);
        results = filterManager.apply(results);
        sortManager.sort(results);
        currentFiles = results;
        currentIndex = -1;
        mediaAdapter.setFiles(results);
        updateProgress();
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

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
            applyGrouping();
            return true;
        });
        menu.show();
    }

    // ── Filter ────────────────────────────────────────────────────────────────

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
            applyGrouping();
            return true;
        });
        menu.show();
    }

    // ── Group ─────────────────────────────────────────────────────────────────

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
            applyGrouping();
            return true;
        });
        menu.show();
    }

    private void applyGrouping() {
        List<Group>     groups    = groupManager.group(indexer.getIndex());
        List<MediaFile> flattened = new ArrayList<>();
        for (Group g : groups) flattened.addAll(g.getFiles());
        flattened = filterManager.apply(flattened);
        sortManager.sort(flattened);
        currentFiles = flattened;
        currentIndex = -1;
        mediaAdapter.setFiles(flattened);
        updateProgress();
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private void updateProgress() {
        int total  = currentFiles.size();
        int tagged = 0;
        for (MediaFile f : currentFiles) {
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

    // ── FolderWatcher ─────────────────────────────────────────────────────────

    @Override
    public void onFileAdded(String path) {
        mainHandler.post(() ->
            indexer.rescan(new java.io.File(path).getParent()));
    }

    @Override
    public void onFileDeleted(String path) {
        mainHandler.post(() -> {
            mediaAdapter.removeFile(path);
            currentFiles.removeIf(f -> f.getPath().equals(path));
            updateProgress();
        });
    }

    @Override
    public void onFileModified(String path) {
        mainHandler.post(() -> {
            cacheManager.invalidateThumbnail(path);
            indexer.rescan(new java.io.File(path).getParent());
        });
    }

    // ── MediaIndexer ──────────────────────────────────────────────────────────

    @Override
    public void onFileFound(MediaFile file) {
        mainHandler.post(() -> mediaAdapter.addFile(file));
    }

    @Override
    public void onPageLoaded(List<MediaFile> page) {
        mainHandler.post(this::applyGrouping);
    }

    @Override
    public void onScanComplete(List<MediaFile> allFiles) {
        mainHandler.post(() -> {
            btnScan.setEnabled(true);
            btnScan.setText("SCAN");
            searchManager.setFullList(allFiles);
            applyGrouping();
        });
    }

    @Override
    public void onFileChanged(MediaFile file) {
        mainHandler.post(() -> mediaAdapter.updateFile(file));
    }

    @Override
    public void onFileRemoved(String path) {
        mainHandler.post(() -> {
            mediaAdapter.removeFile(path);
            currentFiles.removeIf(f -> f.getPath().equals(path));
            updateProgress();
        });
    }
}
