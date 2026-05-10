package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.adapters.MediaAdapter;
import com.mediasorter.adapters.TagAdapter;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.List;

public class MainActivity extends Activity
        implements FolderWatcher.Listener, MediaIndexer.IndexListener {

    // Core managers
    private MediaIndexer   indexer;
    private TagManager     tagManager;
    private FolderManager  folderManager;
    private FolderWatcher  folderWatcher;
    private SearchManager  searchManager;
    private GroupManager   groupManager;
    private CacheManager   cacheManager;
    private PreviewManager previewManager;
    private ThumbnailLoader thumbnailLoader;

    // Adapters
    private MediaAdapter mediaAdapter;
    private TagAdapter   tagAdapter;

    // UI
    private EditText searchBar;
    private EditText tagSearch;
    private EditText newTagInput;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initManagers();
        initAdapters();
        initViews();
        initFolders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Lightweight rescan on resume
        for (String folder : folderManager.getFolders()) {
            indexer.rescan(folder);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        folderWatcher.unwatchAll();
        if (previewManager != null) previewManager.release();
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void initManagers() {
        indexer         = new MediaIndexer();
        tagManager      = new TagManager(this);
        folderManager   = new FolderManager(this);
        folderWatcher   = new FolderWatcher(this);
        searchManager   = new SearchManager();
        groupManager    = new GroupManager();
        cacheManager    = new CacheManager(this);
        thumbnailLoader = new ThumbnailLoader(this);

        indexer.setListener(this);
    }

    private void initAdapters() {
        mediaAdapter = new MediaAdapter(thumbnailLoader, this::onFileSelected);
        tagAdapter   = new TagAdapter(this::onTagToggled);
    }

    private void initViews() {
        // File browser
        RecyclerView fileBrowser = findViewById(R.id.fileBrowser);
        fileBrowser.setLayoutManager(new LinearLayoutManager(this));
        fileBrowser.setAdapter(mediaAdapter);

        // Tag list
        RecyclerView tagList = findViewById(R.id.tagList);
        tagList.setLayoutManager(new LinearLayoutManager(this));
        tagList.setAdapter(tagAdapter);

        // Preview panel
        FrameLayout previewContainer = findViewById(R.id.previewPanel);
        View previewPanel = getLayoutInflater().inflate(R.layout.panel_preview, previewContainer, true);
        previewManager = new PreviewManager(this, previewContainer);

        // Search bar
        searchBar = findViewById(R.id.searchBar);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                runSearch(s.toString());
            }
        });

        // Tag search
        tagSearch = findViewById(R.id.tagSearch);
        tagSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                tagAdapter.setTags(tagManager.searchTags(s.toString()));
            }
        });

        // New tag
        newTagInput = findViewById(R.id.newTagInput);
        Button btnAddTag = findViewById(R.id.btnAddTag);
        btnAddTag.setOnClickListener(v -> {
            String name = newTagInput.getText().toString().trim();
            if (!name.isEmpty()) {
                tagManager.createTag(name);
                tagAdapter.setTags(tagManager.getAllTags());
                newTagInput.setText("");
            }
        });

        // Group by button
        Button btnGroupBy = findViewById(R.id.btnGroupBy);
        btnGroupBy.setOnClickListener(v -> showGroupMenu(v));

        // Dashboard button
        Button btnDashboard = findViewById(R.id.btnDashboard);
        btnDashboard.setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class)));

        // Settings button
        Button btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        // Load tag list
        tagAdapter.setTags(tagManager.getAllTags());
    }

    private void initFolders() {
        if (folderManager.isEmpty()) {
            showAddFolderDialog();
        } else {
            for (String folder : folderManager.getFolders()) {
                indexer.scanFolder(folder);
                folderWatcher.watch(folder);
            }
        }
    }

    // ── File selection ────────────────────────────────────────────────────────

    private void onFileSelected(MediaFile file) {
        previewManager.load(file);
        tagAdapter.setCurrentFile(file);
        tagAdapter.setTags(tagManager.getAllTags());
    }

    // ── Tag toggling ──────────────────────────────────────────────────────────

    private void onTagToggled(String tagName, boolean applied) {
        // Get currently selected file from adapter
        // Tag toggle applies to currently previewed file
        List<MediaFile> files = indexer.getIndex();
        for (MediaFile f : files) {
            if (f.getPath().equals(mediaAdapter.getSelectedPath())) {
                if (applied) tagManager.applyTag(f, tagName);
                else         tagManager.removeTag(f, tagName);
                tagAdapter.setTags(tagManager.getAllTags());
                mediaAdapter.updateFile(f);
                break;
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void runSearch(String query) {
        searchManager.setFullList(indexer.getIndex());
        List<MediaFile> results = searchManager.search(query);
        mediaAdapter.setFiles(results);
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

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
        List<MediaFile> flattened = new java.util.ArrayList<>();
        for (Group g : groups) flattened.addAll(g.getFiles());
        mediaAdapter.setFiles(flattened);
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
                    indexer.scanFolder(path);
                    folderWatcher.watch(path);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── FolderWatcher.Listener ────────────────────────────────────────────────

    @Override
    public void onFileAdded(String path) {
        mainHandler.post(() -> {
            indexer.rescan(new java.io.File(path).getParent());
        });
    }

    @Override
    public void onFileDeleted(String path) {
        mainHandler.post(() -> mediaAdapter.removeFile(path));
    }

    @Override
    public void onFileModified(String path) {
        mainHandler.post(() -> {
            cacheManager.invalidateThumbnail(path);
            indexer.rescan(new java.io.File(path).getParent());
        });
    }

    // ── MediaIndexer.IndexListener ────────────────────────────────────────────

    @Override
    public void onFileFound(MediaFile file) {
        mainHandler.post(() -> mediaAdapter.addFile(file));
    }

    @Override
    public void onScanComplete(List<MediaFile> allFiles) {
        mainHandler.post(() -> {
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
        mainHandler.post(() -> mediaAdapter.removeFile(path));
    }
}
