package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.mediasorter.models.TagList;
import java.util.List;

public class SettingsActivity extends Activity {

    private CacheManager    cacheManager;
    private FolderManager   folderManager;
    private ThumbnailLoader thumbnailLoader;
    private GestureSettings gestureSettings;
    private TagListManager  tagListManager;
    private TagManager      tagManager;
    private MediaIndexer    indexer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cacheManager    = new CacheManager(this);
        folderManager   = new FolderManager(this);
        thumbnailLoader = new ThumbnailLoader(this);
        gestureSettings = new GestureSettings(this);
        tagListManager  = new TagListManager(this);
        tagManager      = new TagManager(this);
        indexer         = new MediaIndexer();
        buildSettings();
    }

    private void buildSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(32, 32, 32, 32);

        root.addView(makeTitle("Settings"));

        // ── Cache ─────────────────────────────────────────────────────────────
        root.addView(makeTitle("Cache"));

        TextView cacheSizeLabel = makeLabel(
            "Current: " + cacheManager.getFormattedCacheSize()
            + " / " + cacheManager.getLimitMB() + " MB");
        root.addView(cacheSizeLabel);

        TextView limitLabel = makeLabel("Cache limit: " + cacheManager.getLimitMB() + " MB");
        root.addView(limitLabel);

        SeekBar limitSeek = new SeekBar(this);
        limitSeek.setMax(500);
        limitSeek.setProgress(cacheManager.getLimitMB());
        limitSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int mb = Math.max(10, progress);
                cacheManager.setLimitMB(mb);
                limitLabel.setText("Cache limit: " + mb + " MB");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(limitSeek);

        Button btnClear = makeButton("Clear Cache");
        btnClear.setOnClickListener(v -> {
            cacheManager.clearAll();
            cacheSizeLabel.setText("Current: " + cacheManager.getFormattedCacheSize());
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnClear);

        // ── Thumbnails ────────────────────────────────────────────────────────
        root.addView(makeTitle("Thumbnails"));

        TextView qualityLabel = makeLabel(
            "Quality: " + qualityName(thumbnailLoader.getQuality()));
        root.addView(qualityLabel);

        SeekBar qualitySeek = new SeekBar(this);
        qualitySeek.setMax(2);
        qualitySeek.setProgress(qualityIndex(thumbnailLoader.getQuality()));
        qualitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int size = qualityFromIndex(progress);
                thumbnailLoader.setQuality(size);
                qualityLabel.setText("Quality: " + qualityName(size));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(qualitySeek);

        TextView memLabel = makeLabel(
            "Max thumbnail memory: " + thumbnailLoader.getMaxMB() + " MB");
        root.addView(memLabel);

        SeekBar memSeek = new SeekBar(this);
        memSeek.setMax(90);
        memSeek.setProgress(thumbnailLoader.getMaxMB() - 10);
        memSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int mb = progress + 10;
                thumbnailLoader.setMaxMB(mb);
                memLabel.setText("Max thumbnail memory: " + mb + " MB");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(memSeek);

        // ── Memory window ─────────────────────────────────────────────────────
        root.addView(makeTitle("Memory Window"));

        SharedPreferences windowPrefs =
            getSharedPreferences("window_prefs", MODE_PRIVATE);
        int currentWindow = windowPrefs.getInt("window_size", 20);

        TextView windowLabel = makeLabel("Window size: " + currentWindow + " files");
        root.addView(windowLabel);

        SeekBar windowSeek = new SeekBar(this);
        windowSeek.setMax(90);
        windowSeek.setProgress(currentWindow - 10);
        windowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int size = progress + 10;
                windowPrefs.edit().putInt("window_size", size).apply();
                windowLabel.setText("Window size: " + size + " files");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(windowSeek);

        
        // ── Swipe gestures ────────────────────────────────────────────────────────
        root.addView(makeTitle("Swipe Gestures"));
        root.addView(makeMultiGestureRow("Swipe Left",  gestureSettings.getLeft(),  gestureSettings::setLeft));
        root.addView(makeMultiGestureRow("Swipe Right", gestureSettings.getRight(), gestureSettings::setRight));
        root.addView(makeMultiGestureRow("Swipe Up",    gestureSettings.getUp(),    gestureSettings::setUp));
        root.addView(makeMultiGestureRow("Swipe Down",  gestureSettings.getDown(),  gestureSettings::setDown));

        // ── D-pad gestures ────────────────────────────────────────────────────────
        root.addView(makeTitle("D-Pad Gestures"));
        root.addView(makeMultiGestureRow("D-Pad Up",     gestureSettings.getDpadUp(),     gestureSettings::setDpadUp));
        root.addView(makeMultiGestureRow("D-Pad Down",   gestureSettings.getDpadDown(),   gestureSettings::setDpadDown));
        root.addView(makeMultiGestureRow("D-Pad Left",   gestureSettings.getDpadLeft(),   gestureSettings::setDpadLeft));
        root.addView(makeMultiGestureRow("D-Pad Right",  gestureSettings.getDpadRight(),  gestureSettings::setDpadRight));
        root.addView(makeMultiGestureRow("D-Pad Center", gestureSettings.getDpadCenter(), gestureSettings::setDpadCenter));

        // ── Tag lists ─────────────────────────────────────────────────────────
        root.addView(makeTitle("Tag Lists"));

        List<TagList> allLists = tagListManager.getAllLists();
        for (int i = 0; i < allLists.size(); i++) {
            TagList list = allLists.get(i);
            final int idx = i;

            LinearLayout listRow = new LinearLayout(this);
            listRow.setOrientation(LinearLayout.VERTICAL);
            listRow.setBackgroundColor(0xFF1A1A2E);
            LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            listLp.bottomMargin = 8;
            listRow.setLayoutParams(listLp);
            listRow.setPadding(8, 8, 8, 8);

            // List name row
            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView listName = makeLabel(list.getName()
                + (list.isDefault() ? " (Default)" : "")
                + "  —  " + list.size() + " tags");
            listName.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameRow.addView(listName);

            // Edit button
            Button btnEdit = makeSmallButton("Edit");
            btnEdit.setOnClickListener(v -> showEditListDialog(idx));
            nameRow.addView(btnEdit);

            // Delete button (not for default)
            if (!list.isDefault()) {
                Button btnDel = makeSmallButton("Delete");
                btnDel.setOnClickListener(v -> {
                    tagListManager.deleteList(idx);
                    recreate();
                });
                nameRow.addView(btnDel);
            }

            listRow.addView(nameRow);

            // Tags preview
            if (!list.getTags().isEmpty()) {
                TextView tagsPreview = makeLabel(
                    String.join(", ", list.getTags()));
                tagsPreview.setTextColor(0xFF888888);
                tagsPreview.setTextSize(10f);
                listRow.addView(tagsPreview);
            }

            root.addView(listRow);
        }

        Button btnNewList = makeButton("+ New Tag List");
        btnNewList.setOnClickListener(v -> showNewListDialog());
        root.addView(btnNewList);

        // ── Folders ───────────────────────────────────────────────────────────
        root.addView(makeTitle("Watched Folders"));

        List<String> folders = folderManager.getFolders();
        if (folders.isEmpty()) {
            root.addView(makeLabel("No folders added"));
        } else {
            for (String folder : folders) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView lbl = makeLabel(folder);
                lbl.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(lbl);

                Button rm = makeButton("Remove");
                rm.setOnClickListener(v -> {
                    folderManager.removeFolder(folder);
                    root.removeView(row);
                });
                row.addView(rm);
                root.addView(row);
            }
        }

        Button btnAdd = makeButton("+ Add Folder");
        btnAdd.setOnClickListener(v -> showAddFolderDialog());
        root.addView(btnAdd);

        Button btnFullRescan = makeButton("Full Rescan");
        btnFullRescan.setOnClickListener(v -> {
            indexer.fullReset(folderManager.getFolders());
            Toast.makeText(this, "Full rescan started", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnFullRescan);

        // ── Crash log ─────────────────────────────────────────────────────────
        root.addView(makeTitle("Crash Log"));

        Button btnLog = makeButton("View Crash Log");
        btnLog.setOnClickListener(v -> {
            String log = CrashLogger.readLog(this);
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText(log);
            tv.setTextColor(0xFFCCCCCC);
            tv.setTextSize(10f);
            tv.setPadding(16, 16, 16, 16);
            sv.addView(tv);
            new AlertDialog.Builder(this)
                .setTitle("Crash Log")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash", log));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Clear", (d, w) -> CrashLogger.clearLog(this))
                .setNeutralButton("Close", null)
                .show();
        });
        root.addView(btnLog);

        // ── Back ──────────────────────────────────────────────────────────────
        Button btnBack = makeButton("← Back");
        btnBack.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.topMargin = 48;
        btnBack.setLayoutParams(backLp);
        root.addView(btnBack);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ── Gesture row ───────────────────────────────────────────────────────────

    interface GestureCallback {
        void set(GestureSettings.GestureAction action, String tag);
    }

    private LinearLayout makeGestureRow(String label,
            GestureSettings.GestureAction current,
            String currentTag,
            GestureCallback callback) {

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.bottomMargin = 12;
        col.setLayoutParams(colLp);

        col.addView(makeLabel(label));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Action spinner
        Spinner spinner = new Spinner(this);
        String[] labels = gestureSettings.getAllLabels();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String currentLabel = gestureSettings.getLabel(current);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(currentLabel)) {
                spinner.setSelection(i);
                break;
            }
        }

        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        spinner.setLayoutParams(spinLp);
        row.addView(spinner);

        // Tag spinner — only shown when APPLY_TAG selected
        Spinner tagSpinner = new Spinner(this);
        List<com.mediasorter.models.Tag> allTags = tagManager.getAllTags();
        String[] tagNames = new String[allTags.size() + 1];
        tagNames[0] = "(select tag)";
        for (int i = 0; i < allTags.size(); i++) {
            tagNames[i + 1] = allTags.get(i).getName();
        }
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, tagNames);
        tagAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        tagSpinner.setAdapter(tagAdapter);

        // Set current tag selection
        if (!currentTag.isEmpty()) {
            for (int i = 1; i < tagNames.length; i++) {
                if (tagNames[i].equals(currentTag)) {
                    tagSpinner.setSelection(i);
                    break;
                }
            }
        }

        tagSpinner.setVisibility(
            current == GestureSettings.GestureAction.APPLY_TAG
                ? android.view.View.VISIBLE
                : android.view.View.GONE);

        LinearLayout.LayoutParams tagSpinLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tagSpinLp.setMarginStart(8);
        tagSpinner.setLayoutParams(tagSpinLp);
        row.addView(tagSpinner);

        // Wire up listeners
        spinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent,
                        android.view.View view, int position, long id) {
                    GestureSettings.GestureAction action =
                        gestureSettings.fromLabel(labels[position]);
                    tagSpinner.setVisibility(
                        action == GestureSettings.GestureAction.APPLY_TAG
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE);
                    String tag = tagSpinner.getSelectedItemPosition() > 0
                        ? tagNames[tagSpinner.getSelectedItemPosition()]
                        : "";
                    callback.set(action, tag);
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });

        tagSpinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent,
                        android.view.View view, int position, long id) {
                    GestureSettings.GestureAction action =
                        gestureSettings.fromLabel(
                            labels[spinner.getSelectedItemPosition()]);
                    String tag = position > 0 ? tagNames[position] : "";
                    callback.set(action, tag);
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });

        col.addView(row);
        return col;
    }

    // ── Tag list dialogs ──────────────────────────────────────────────────────

    private void showNewListDialog() {
        EditText input = new EditText(this);
        input.setHint("List name");
        new AlertDialog.Builder(this)
            .setTitle("New Tag List")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    tagListManager.createList(name);
                    recreate();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showEditListDialog(int listIndex) {
        TagList list = tagListManager.getList(listIndex);
        if (list == null) return;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        // Name field
        EditText nameInput = new EditText(this);
        nameInput.setText(list.getName());
        layout.addView(makeLabel("List name:"));
        layout.addView(nameInput);

        // Current tags
        layout.addView(makeLabel("Tags in list:"));
        LinearLayout tagRows = new LinearLayout(this);
        tagRows.setOrientation(LinearLayout.VERTICAL);

        for (String tag : list.getTags()) {
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tagLbl = makeLabel(tag);
            tagLbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tagRow.addView(tagLbl);

            Button rm = makeSmallButton("✕");
            rm.setOnClickListener(v -> {
                tagListManager.removeTagFromList(listIndex, tag);
                tagRows.removeView(tagRow);
            });
            tagRow.addView(rm);
            tagRows.addView(tagRow);
        }
        layout.addView(tagRows);

        // Add tag from library
        layout.addView(makeLabel("Add tag from library:"));
        List<com.mediasorter.models.Tag> allTags = tagManager.getAllTags();
        String[] tagNames = new String[allTags.size()];
        for (int i = 0; i < allTags.size(); i++) {
            tagNames[i] = allTags.get(i).getName();
        }

        Spinner tagPicker = new Spinner(this);
        ArrayAdapter<String> tagAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, tagNames);
        tagAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        tagPicker.setAdapter(tagAdapter);
        layout.addView(tagPicker);

        Button btnAddToList = makeButton("Add Selected Tag");
        btnAddToList.setOnClickListener(v -> {
            if (tagPicker.getSelectedItemPosition() >= 0) {
                String selected = tagNames[tagPicker.getSelectedItemPosition()];
                tagListManager.addTagToList(listIndex, selected);
                Toast.makeText(this, selected + " added", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnAddToList);

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("Edit: " + list.getName())
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                String newName = nameInput.getText().toString().trim();
                if (!newName.isEmpty()) {
                    tagListManager.renameList(listIndex, newName);
                }
                recreate();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Folder dialog ─────────────────────────────────────────────────────────

    private void showAddFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("/sdcard/DCIM");
        new AlertDialog.Builder(this)
            .setTitle("Add Folder")
            .setView(input)
            .setPositiveButton("Add", (d, w) -> {
                String path = input.getText().toString().trim();
                if (!path.isEmpty()) {
                    folderManager.addFolder(path);
                    Toast.makeText(this, "Folder added", Toast.LENGTH_SHORT).show();
                    recreate();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Quality helpers ───────────────────────────────────────────────────────

    private String qualityName(int size) {
        if (size <= ThumbnailLoader.QUALITY_LOW)    return "Low (128px)";
        if (size <= ThumbnailLoader.QUALITY_MEDIUM) return "Medium (256px)";
        return "High (512px)";
    }

    private int qualityIndex(int size) {
        if (size <= ThumbnailLoader.QUALITY_LOW)    return 0;
        if (size <= ThumbnailLoader.QUALITY_MEDIUM) return 1;
        return 2;
    }

    private int qualityFromIndex(int index) {
        switch (index) {
            case 0:  return ThumbnailLoader.QUALITY_LOW;
            case 2:  return ThumbnailLoader.QUALITY_HIGH;
            default: return ThumbnailLoader.QUALITY_MEDIUM;
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────

    private TextView makeTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE94560);
        tv.setTextSize(16f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin    = 24;
        lp.bottomMargin = 8;
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 6;
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button makeButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        btn.setLayoutParams(lp);
        return btn;
    }

    private Button makeSmallButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF2A2A3E);
        btn.setTextSize(11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(4);
        btn.setLayoutParams(lp);
        return btn;
    }
}
