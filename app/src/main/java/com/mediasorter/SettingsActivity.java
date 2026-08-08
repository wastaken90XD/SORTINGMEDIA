package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import com.mediasorter.features.RandomGenerator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.mediasorter.models.Tag;
import com.mediasorter.models.TagList;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
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
        limitSeek.setOnSeekBarChangeListener(simple((progress) -> {
            int mb = Math.max(10, progress);
            cacheManager.setLimitMB(mb);
            limitLabel.setText("Cache limit: " + mb + " MB");
        }));
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
        qualitySeek.setOnSeekBarChangeListener(simple((progress) -> {
            int size = qualityFromIndex(progress);
            thumbnailLoader.setQuality(size);
            qualityLabel.setText("Quality: " + qualityName(size));
        }));
        root.addView(qualitySeek);

        TextView memLabel = makeLabel(
            "Max thumbnail memory: " + thumbnailLoader.getMaxMB() + " MB");
        root.addView(memLabel);

        SeekBar memSeek = new SeekBar(this);
        memSeek.setMax(90);
        memSeek.setProgress(thumbnailLoader.getMaxMB() - 10);
        memSeek.setOnSeekBarChangeListener(simple((progress) -> {
            int mb = progress + 10;
            thumbnailLoader.setMaxMB(mb);
            memLabel.setText("Max thumbnail memory: " + mb + " MB");
        }));
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
        windowSeek.setOnSeekBarChangeListener(simple((progress) -> {
            int size = progress + 10;
            windowPrefs.edit().putInt("window_size", size).apply();
            windowLabel.setText("Window size: " + size + " files");
        }));
        root.addView(windowSeek);

        // ── Main window UI toggles ────────────────────────────────────────────
        root.addView(makeTitle("Main Window"));
        root.addView(makeToggleRow("D-Pad control",
            gestureSettings.isDpadEnabled(),
            enabled -> gestureSettings.setDpadEnabled(enabled)));
        root.addView(makeToggleRow("Tag menus & prompts",
            tagManager.isTagsEnabled(),
            enabled -> tagManager.setTagsEnabled(enabled)));

        // ── Swipe gestures ────────────────────────────────────────────────────
        root.addView(makeTitle("Swipe Gestures"));
        root.addView(makeMultiGestureRow("Swipe Left",
            gestureSettings.getLeft(),  gestureSettings::setLeft));
        root.addView(makeMultiGestureRow("Swipe Right",
            gestureSettings.getRight(), gestureSettings::setRight));
        root.addView(makeMultiGestureRow("Swipe Up",
            gestureSettings.getUp(),    gestureSettings::setUp));
        root.addView(makeMultiGestureRow("Swipe Down",
            gestureSettings.getDown(),  gestureSettings::setDown));

        // ── D-pad gestures ────────────────────────────────────────────────────
        root.addView(makeTitle("D-Pad Gestures"));
        root.addView(makeMultiGestureRow("D-Pad Up",
            gestureSettings.getDpadUp(),     gestureSettings::setDpadUp));
        root.addView(makeMultiGestureRow("D-Pad Down",
            gestureSettings.getDpadDown(),   gestureSettings::setDpadDown));
        root.addView(makeMultiGestureRow("D-Pad Left",
            gestureSettings.getDpadLeft(),   gestureSettings::setDpadLeft));
        root.addView(makeMultiGestureRow("D-Pad Right",
            gestureSettings.getDpadRight(),  gestureSettings::setDpadRight));
        root.addView(makeMultiGestureRow("D-Pad Center",
            gestureSettings.getDpadCenter(), gestureSettings::setDpadCenter));

        // ── Macros ────────────────────────────────────────────────────────────
        root.addView(makeTitle("Gesture Macros"));
        final LinearLayout macrosContainer = new LinearLayout(this);
        macrosContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(macrosContainer);

        renderMacros(macrosContainer);

        Button btnAddMacro = makeSmallButton("+ Add Macro");
        btnAddMacro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
                if (mList.size() >= 10) {
                    Toast.makeText(SettingsActivity.this, "Maximum of 10 macros allowed", Toast.LENGTH_SHORT).show();
                    return;
                }
                GestureSettings.GestureMacro newM = new GestureSettings.GestureMacro();
                int maxId = 0;
                for (GestureSettings.GestureMacro m : mList) {
                    try {
                        int parsed = Integer.parseInt(m.id);
                        if (parsed > maxId) maxId = parsed;
                    } catch (Exception ignored) {}
                }
                newM.id = String.valueOf(maxId + 1);
                newM.name = "Macro " + newM.id;
                newM.actions = new ArrayList<>();
                mList.add(newM);
                gestureSettings.saveMacros(mList);
                renderMacros(macrosContainer);
            }
        });
        root.addView(btnAddMacro);

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

            LinearLayout nameRow = new LinearLayout(this);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView listName = makeLabel(list.getName()
                + (list.isDefault() ? " (Default)" : "")
                + "  —  " + list.size() + " tags");
            listName.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameRow.addView(listName);

            Button btnEdit = makeSmallButton("Edit");
            btnEdit.setOnClickListener(v -> showEditListDialog(idx));
            nameRow.addView(btnEdit);

            if (!list.isDefault()) {
                Button btnDel = makeSmallButton("Delete");
                btnDel.setOnClickListener(v -> {
                    tagListManager.deleteList(idx);
                    recreate();
                });
                nameRow.addView(btnDel);
            }

            listRow.addView(nameRow);

            if (!list.getTags().isEmpty()) {
                TextView tagsPreview = makeLabel(joinTags(list.getTags()));
                tagsPreview.setTextColor(0xFF888888);
                tagsPreview.setTextSize(10f);
                listRow.addView(tagsPreview);
            }

            root.addView(listRow);
        }

        Button btnNewList = makeButton("+ New Tag List");
        btnNewList.setOnClickListener(v -> showNewListDialog());
        root.addView(btnNewList);

        Button btnBulkActive = makeButton("Auto-fill active list from all tags");
btnBulkActive.setOnClickListener(v -> {
    List<Tag> allTags = tagManager.getAllTags();
    List<String> tagNames = new ArrayList<>();
    for (Tag t : allTags) tagNames.add(t.getName());
    int added = tagListManager.bulkAddToActiveList(tagNames);
    Toast.makeText(this,
        added + " tags added to " + tagListManager.getActiveList().getName(),
        Toast.LENGTH_SHORT).show();
    recreate();
});
root.addView(btnBulkActive);

        // Auto-populate from scanned files
        Button btnAutoPopulate = makeButton("Auto-populate lists from scanned files");
        btnAutoPopulate.setOnClickListener(v ->
            Toast.makeText(this,
                "Rescan files — tags auto-import on scan complete",
                Toast.LENGTH_LONG).show());
        root.addView(btnAutoPopulate);

        // ── Folders ───────────────────────────────────────────────────────────
        root.addView(makeTitle("Watched Folders"));

        List<String> folders = folderManager.getFolders();
        if (folders.isEmpty()) {
            root.addView(makeLabel("No folders added"));
        } else {
            for (String folder : folders) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

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

        // ── Export / Import ────────────────────────────────────────────────────
        root.addView(makeTitle("Backup & Restore"));

        Button btnExport = makeButton("Export Settings");
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String prefix = RandomGenerator.randomGroupPrefix(new java.util.HashSet<String>());
                java.util.Calendar cal = java.util.Calendar.getInstance();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
                String dateStr = sdf.format(cal.getTime());
                String defaultFilename = "export_" + prefix + "_" + dateStr + ".json";

                LinearLayout container = new LinearLayout(SettingsActivity.this);
                container.setOrientation(LinearLayout.VERTICAL);

                container.addView(makeLabel("Export Directory:"));
                final EditText dirEdit = new EditText(SettingsActivity.this);
                dirEdit.setText(SettingsExporter.getBackupDir(SettingsActivity.this).getAbsolutePath());
                dirEdit.setTextColor(0xFFFFFFFF);
                container.addView(dirEdit);

                container.addView(makeLabel("Filename:"));
                final EditText nameEdit = new EditText(SettingsActivity.this);
                nameEdit.setText(defaultFilename);
                nameEdit.setTextColor(0xFFFFFFFF);
                container.addView(nameEdit);

                android.widget.FrameLayout box = new android.widget.FrameLayout(SettingsActivity.this);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(pad, pad, pad, pad);
                container.setLayoutParams(lp);
                box.addView(container);

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Export Settings")
                        .setView(box)
                        .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final String directoryPath = dirEdit.getText().toString().trim();
                                String filename = nameEdit.getText().toString().trim();
                                if (directoryPath.isEmpty() || filename.isEmpty()) {
                                    Toast.makeText(SettingsActivity.this, "Fields cannot be empty", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (!filename.endsWith(".json")) {
                                    filename += ".json";
                                }

                                final String finalFilename = filename;
                                final String path = SettingsExporter.exportSettings(SettingsActivity.this, directoryPath, finalFilename);
                                if (path != null) {
                                    Toast.makeText(SettingsActivity.this, "Exported successfully to:\n" + path, Toast.LENGTH_LONG).show();

                                    // Backup rotation check
                                    File dir = new File(directoryPath);
                                    File[] files = dir.listFiles(new java.io.FilenameFilter() {
                                        @Override
                                        public boolean accept(File d, String name) {
                                            return name.startsWith("export_") && name.endsWith(".json");
                                        }
                                    });

                                    if (files != null && files.length > 5) {
                                        final int toDeleteCount = files.length - 5;
                                        // Sort ascending by lastModified to get oldest first
                                        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                                            @Override
                                            public int compare(File f1, File f2) {
                                                return Long.compare(f1.lastModified(), f2.lastModified());
                                            }
                                        });

                                        final File[] sortedFiles = files;
                                        new AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Backup Rotation")
                                                .setMessage(toDeleteCount + " old backups found. Delete the oldest to keep only 5?")
                                                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface dialogInterface, int whichButton) {
                                                        int deleted = 0;
                                                        for (int i = 0; i < toDeleteCount; i++) {
                                                            if (sortedFiles[i].delete()) {
                                                                deleted++;
                                                            }
                                                        }
                                                        Toast.makeText(SettingsActivity.this, "Deleted " + deleted + " old backups", Toast.LENGTH_SHORT).show();
                                                    }
                                                })
                                                .setNegativeButton("No", null)
                                                .show();
                                    }
                                } else {
                                    File checkDir = new File(directoryPath);
                                    if (!checkDir.exists()) {
                                        Toast.makeText(SettingsActivity.this, "Cannot write to that location", Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(SettingsActivity.this, "Export failed — storage error", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        root.addView(btnExport);

        Button btnImport = makeButton("Import Settings");
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final File[] backups = SettingsExporter.listBackups(SettingsActivity.this);
                if (backups.length == 0) {
                    Toast.makeText(SettingsActivity.this, "No backups found", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[backups.length];
                for (int i = 0; i < backups.length; i++) names[i] = backups[i].getName();
                new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Select backup to import")
                    .setItems(names, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, final int whichIdx) {
                            final File selectedFile = backups[whichIdx];
                            if (!selectedFile.canRead()) {
                                Toast.makeText(SettingsActivity.this, "Cannot read that file", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Read file and parse as JSONObject first for validation
                            String jsonStr = "";
                            java.io.FileInputStream fis = null;
                            java.io.InputStreamReader isr = null;
                            java.io.BufferedReader br = null;
                            try {
                                fis = new java.io.FileInputStream(selectedFile);
                                isr = new java.io.InputStreamReader(fis, "UTF-8");
                                br = new java.io.BufferedReader(isr);
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) {
                                    sb.append(line).append("\n");
                                }
                                jsonStr = sb.toString();
                            } catch (Exception e) {
                                Toast.makeText(SettingsActivity.this, "Cannot read that file", Toast.LENGTH_SHORT).show();
                                return;
                            } finally {
                                if (br != null) try { br.close(); } catch (Exception ignored) {}
                                if (isr != null) try { isr.close(); } catch (Exception ignored) {}
                                if (fis != null) try { fis.close(); } catch (Exception ignored) {}
                            }

                            final JSONObject rootObj;
                            try {
                                rootObj = new JSONObject(jsonStr);
                            } catch (Exception e) {
                                Toast.makeText(SettingsActivity.this, "Import failed — file is not a valid backup", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            // Validate top-level keys
                            String[] requiredKeys = {"version", "timestamp", "preferences", "folders", "gestures", "macros", "tag_presets", "rules"};
                            boolean malformed = false;
                            for (String k : requiredKeys) {
                                if (!rootObj.has(k)) {
                                    malformed = true;
                                    break;
                                }
                            }
                            if (malformed) {
                                Toast.makeText(SettingsActivity.this, "Import failed — file is not a valid backup", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            final Runnable doApplyAction = new Runnable() {
                                @Override
                                public void run() {
                                    SettingsExporter.ApplyResult res = SettingsExporter.applyImport(SettingsActivity.this, rootObj);
                                    if (res.isSuccess) {
                                        // Trigger rescan of newly imported folders
                                        FolderManager fm = new FolderManager(SettingsActivity.this);
                                        indexer.scanFolders(fm.getFolders());

                                        String summary = "Import complete — " 
                                                + res.preferencesCount + " preferences, "
                                                + res.foldersCount + " folders, "
                                                + res.rulesCount + " rules, "
                                                + res.gesturesCount + " gestures, "
                                                + res.macrosCount + " macros restored.";
                                        if (res.rulesSkipped > 0) {
                                            summary += "\n" + res.rulesSkipped + " rules skipped (unreadable).";
                                        }

                                        new AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Import Successful")
                                                .setMessage(summary)
                                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface d3, int w3) {
                                                        recreate();
                                                    }
                                                })
                                                .show();
                                    } else {
                                        new AlertDialog.Builder(SettingsActivity.this)
                                                .setTitle("Import Failed")
                                                .setMessage(res.errorMessage != null ? res.errorMessage : "Import failed.")
                                                .setPositiveButton("OK", null)
                                                .show();
                                    }
                                }
                            };

                            // Forward compatibility detection
                            int backupVersion = rootObj.optInt("version", 0);
                            if (backupVersion > BuildConfig.VERSION_CODE) {
                                new AlertDialog.Builder(SettingsActivity.this)
                                        .setTitle("Version Warning")
                                        .setMessage("This backup was made with a newer version. Some settings may not apply. Do you want to proceed?")
                                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialogInterface, int whichButton) {
                                                doApplyAction.run();
                                            }
                                        })
                                        .setNegativeButton("No", null)
                                        .show();
                            } else {
                                doApplyAction.run();
                            }
                        }
                    })
                    .show();
            }
        });
        root.addView(btnImport);

        // ── Duplicate Finder ──────────────────────────────────────────────────
        root.addView(makeTitle("Duplicate Files"));

        Button btnDupes = makeButton("Find Duplicates");
        btnDupes.setOnClickListener(v -> {
            List<MediaFile> files = MainActivity.getLatestFullList();
            if (files.isEmpty()) {
                Toast.makeText(this, "No files scanned yet", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Scanning for duplicates...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                List<DuplicateFinder.DuplicateGroup> dupes =
                        DuplicateFinder.findDuplicates(files, (scanned, total, name) -> {});
                runOnUiThread(() -> {
                    if (dupes.isEmpty()) {
                        Toast.makeText(this, "No duplicates found", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int totalDupes = 0;
                    for (DuplicateFinder.DuplicateGroup g : dupes) totalDupes += g.files.size() - 1;
                    StringBuilder sb = new StringBuilder();
                    sb.append(dupes.size()).append(" groups, ").append(totalDupes).append(" extra copies\n\n");
                    int shown = Math.min(dupes.size(), 15);
                    for (int i = 0; i < shown; i++) {
                        DuplicateFinder.DuplicateGroup g = dupes.get(i);
                        sb.append("[").append(g.files.size()).append(" files, ")
                          .append(g.size / 1024).append(" KB]\n");
                        for (MediaFile f : g.files) sb.append("  ").append(f.getName()).append("\n");
                        sb.append("\n");
                    }
                    if (dupes.size() > 15) sb.append("... and ").append(dupes.size() - 15).append(" more");
                    new AlertDialog.Builder(this)
                        .setTitle("Duplicates")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
                });
            }).start();
        });
        root.addView(btnDupes);

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

    // ── Multi-gesture row ─────────────────────────────────────────────────────

    interface MultiGestureCallback {
        void set(List<GestureSettings.GestureStep> steps);
    }

    private LinearLayout makeMultiGestureRow(String label,
            List<GestureSettings.GestureStep> current,
            MultiGestureCallback callback) {

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        colLp.bottomMargin = 16;
        col.setLayoutParams(colLp);
        col.setBackgroundColor(0xFF1A1A2E);
        col.setPadding(8, 8, 8, 8);

        TextView lbl = makeLabel(label + ": " + gestureSettings.getSummary(current));
        col.addView(lbl);

        LinearLayout stepsList = new LinearLayout(this);
        stepsList.setOrientation(LinearLayout.VERTICAL);
        col.addView(stepsList);

        List<GestureSettings.GestureStep> steps = new ArrayList<>(current);
        renderSteps(stepsList, steps, lbl, label, callback);

        Button btnAdd = makeSmallButton("+ Add Step");
        btnAdd.setOnClickListener(v -> {
            steps.add(new GestureSettings.GestureStep(
                GestureSettings.GestureAction.NOTHING, ""));
            callback.set(steps);
            renderSteps(stepsList, steps, lbl, label, callback);
        });
        col.addView(btnAdd);

        return col;
    }

    private void renderSteps(LinearLayout container,
            List<GestureSettings.GestureStep> steps,
            TextView summaryLabel,
            String gestureLabel,
            MultiGestureCallback callback) {

        container.removeAllViews();

        List<Tag> allTags = tagManager.getAllTags();
        String[]  allTagNames = new String[allTags.size() + 1];
        allTagNames[0] = "(no tag)";
        for (int i = 0; i < allTags.size(); i++) {
            allTagNames[i + 1] = allTags.get(i).getName();
        }

        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            GestureSettings.GestureStep step = steps.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = 4;
            row.setLayoutParams(rowLp);

            // Action spinner
            Spinner actionSpin = new Spinner(this);
            String[] actionLabels = gestureSettings.getAllLabels();
            ArrayAdapter<String> actionAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, actionLabels);
            actionAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
            actionSpin.setAdapter(actionAd);

            String curLabel = gestureSettings.getLabel(step.action);
            for (int j = 0; j < actionLabels.length; j++) {
                if (actionLabels[j].equals(curLabel)) {
                    actionSpin.setSelection(j);
                    break;
                }
            }

            actionSpin.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(actionSpin);

            // Tag search input
            EditText tagSearch = new EditText(this);
            tagSearch.setHint("Search tag…");
            tagSearch.setTextColor(0xFFFFFFFF);
            tagSearch.setHintTextColor(0xFF666666);
            tagSearch.setTextSize(11f);
            tagSearch.setSingleLine(true);
            tagSearch.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Tag spinner
            Spinner tagSpin = new Spinner(this);
            ArrayAdapter<String> tagAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, allTagNames);
            tagAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
            tagSpin.setAdapter(tagAd);
            tagSpin.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Set current tag
            if (!step.tag.isEmpty()) {
                for (int j = 1; j < allTagNames.length; j++) {
                    if (allTagNames[j].equals(step.tag)) {
                        tagSpin.setSelection(j);
                        break;
                    }
                }
            }

            boolean isApply =
                step.action == GestureSettings.GestureAction.APPLY_TAG;
            tagSearch.setVisibility(isApply ? View.VISIBLE : View.GONE);
            tagSpin.setVisibility(isApply ? View.VISIBLE : View.GONE);

            row.addView(tagSearch);
            row.addView(tagSpin);

            // Tag search filter
            tagSearch.addTextChangedListener(new TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
    @Override public void afterTextChanged(Editable s) {}
    @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
        String q = s.toString().toLowerCase().trim();
        List<String> filtered = new ArrayList<>();
        filtered.add("(no tag)");
        for (Tag t : allTags) {
            if (q.isEmpty() || t.getName().toLowerCase().contains(q)) {
                filtered.add(t.getName());
            }
        }
        ArrayAdapter<String> fa = new ArrayAdapter<>(
            SettingsActivity.this,
            android.R.layout.simple_spinner_item,
            filtered.toArray(new String[0]));
        fa.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        tagSpin.setAdapter(fa);
        tagSpin.setVisibility(View.VISIBLE);
    }
});

            // Remove button
            Button btnRemove = makeSmallButton("✕");
            btnRemove.setOnClickListener(v -> {
                steps.remove(idx);
                callback.set(steps);
                summaryLabel.setText(gestureLabel + ": "
                    + gestureSettings.getSummary(steps));
                renderSteps(container, steps, summaryLabel, gestureLabel, callback);
            });
            row.addView(btnRemove);

            // Action spinner listener
            actionSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    GestureSettings.GestureAction action =
                        gestureSettings.fromLabel(actionLabels[pos]);
                    boolean show = action == GestureSettings.GestureAction.APPLY_TAG;
                    tagSearch.setVisibility(show ? View.VISIBLE : View.GONE);
                    tagSpin.setVisibility(show ? View.VISIBLE : View.GONE);
                    steps.get(idx).action = action;
                    callback.set(steps);
                    summaryLabel.setText(gestureLabel + ": "
                        + gestureSettings.getSummary(steps));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });

            // Tag spinner listener
            tagSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    String tag = pos > 0 ? p.getItemAtPosition(pos).toString() : "";
                    steps.get(idx).tag = tag;
                    callback.set(steps);
                    summaryLabel.setText(gestureLabel + ": "
                        + gestureSettings.getSummary(steps));
                }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });

            container.addView(row);
        }
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

        EditText nameInput = new EditText(this);
        nameInput.setText(list.getName());
        layout.addView(makeLabel("List name:"));
        layout.addView(nameInput);

        layout.addView(makeLabel("Tags in list:"));
        LinearLayout tagRows = new LinearLayout(this);
        tagRows.setOrientation(LinearLayout.VERTICAL);

        for (String tag : list.getTags()) {
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setGravity(Gravity.CENTER_VERTICAL);

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

        layout.addView(makeLabel("Add tag from library:"));

        // Searchable tag picker
        EditText tagSearchInput = new EditText(this);
        tagSearchInput.setHint("Search tags…");
        tagSearchInput.setTextColor(0xFFFFFFFF);
        tagSearchInput.setHintTextColor(0xFF666666);
        layout.addView(tagSearchInput);

        List<Tag> allTags = tagManager.getAllTags();
        String[] tagNames = new String[allTags.size()];
        for (int i = 0; i < allTags.size(); i++) {
            tagNames[i] = allTags.get(i).getName();
        }

        Spinner tagPicker = new Spinner(this);
        ArrayAdapter<String> tagAd = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, tagNames);
        tagAd.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        tagPicker.setAdapter(tagAd);
        layout.addView(tagPicker);

        tagSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().toLowerCase();
                List<String> filtered = new ArrayList<>();
                for (Tag t : allTags) {
                    if (t.getName().toLowerCase().contains(q))
                        filtered.add(t.getName());
                }
                ArrayAdapter<String> fa = new ArrayAdapter<>(SettingsActivity.this,
                    android.R.layout.simple_spinner_item,
                    filtered.toArray(new String[0]));
                fa.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
                tagPicker.setAdapter(fa);
            }
        });

        Button btnAddToList = makeButton("Add Selected Tag");
        btnAddToList.setOnClickListener(v -> {
            if (tagPicker.getSelectedItemPosition() >= 0
                    && tagPicker.getAdapter().getCount() > 0) {
                String sel = tagPicker.getSelectedItem().toString();
                tagListManager.addTagToList(listIndex, sel);
                Toast.makeText(this, sel + " added", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnAddToList);
        
        Button btnBulkAdd = makeButton("Add all tags from scanned files");
        btnBulkAdd.setOnClickListener(v -> {
        List<Tag> scanTags = tagManager.getAllTags();
        List<String> scanNames = new ArrayList<>();
        for (Tag t : scanTags) scanNames.add(t.getName());
        int added = tagListManager.bulkAddToList(listIndex, scanNames);
        Toast.makeText(this,
            added + " tags added",
            Toast.LENGTH_SHORT).show();
    });
    layout.addView(btnBulkAdd);

        ScrollView sv = new ScrollView(this);
        sv.addView(layout);

        new AlertDialog.Builder(this)
            .setTitle("Edit: " + list.getName())
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                String newName = nameInput.getText().toString().trim();
                if (!newName.isEmpty()) tagListManager.renameList(listIndex, newName);
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

    // ── SeekBar helper ────────────────────────────────────────────────────────

    interface ProgressCallback { void onProgress(int progress); }

    private SeekBar.OnSeekBarChangeListener simple(ProgressCallback cb) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                cb.onProgress(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
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

    /** Small callback for the toggle rows in "Main Window". */
    private interface ToggleHandler {
        void onToggle(boolean enabled);
    }

    /** Label + ON/OFF button row, matching the app's flat button style. */
    private View makeToggleRow(String label, boolean initial, ToggleHandler handler) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = 4;
        row.setLayoutParams(rowLp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        Button btn = new Button(this);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(12f);
        final boolean[] state = {initial};
        btn.setText(state[0] ? "ON" : "OFF");
        btn.setBackgroundColor(state[0] ? 0xFFE94560 : 0xFF2A2A3E);
        btn.setOnClickListener(v -> {
            state[0] = !state[0];
            btn.setText(state[0] ? "ON" : "OFF");
            btn.setBackgroundColor(state[0] ? 0xFFE94560 : 0xFF2A2A3E);
            handler.onToggle(state[0]);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btn.setLayoutParams(btnLp);
        row.addView(btn);
        return row;
    }


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

    private static String joinTags(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String tag : tags) {
            if (i++ > 0) sb.append(", ");
            sb.append(tag);
        }
        return sb.toString();
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

    private void renderMacros(final LinearLayout container) {
        container.removeAllViews();
        List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
        for (int i = 0; i < mList.size(); i++) {
            final int idx = i;
            final GestureSettings.GestureMacro m = mList.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(0xFF1A1A2E);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            row.setLayoutParams(lp);
            row.setPadding(8, 8, 8, 8);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            EditText nameEdit = new EditText(this);
            nameEdit.setText(m.name);
            nameEdit.setTextColor(0xFFFFFFFF);
            nameEdit.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            nameEdit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    m.name = s.toString().trim();
                    List<GestureSettings.GestureMacro> currentList = gestureSettings.loadMacros();
                    for (GestureSettings.GestureMacro currentM : currentList) {
                        if (currentM.id.equals(m.id)) {
                            currentM.name = m.name;
                            break;
                        }
                    }
                    gestureSettings.saveMacros(currentList);
                }
            });
            header.addView(nameEdit);

            TextView stepCount = makeLabel("Steps: " + m.actions.size());
            stepCount.setPadding(8, 0, 8, 0);
            header.addView(stepCount);

            Button btnEdit = makeSmallButton("Edit");
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMacroStepBuilder(m, container);
                }
            });
            header.addView(btnEdit);

            Button btnDelete = makeSmallButton("Delete");
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<GestureSettings.GestureMacro> currentList = gestureSettings.loadMacros();
                    for (int j = 0; j < currentList.size(); j++) {
                        if (currentList.get(j).id.equals(m.id)) {
                            currentList.remove(j);
                            break;
                        }
                    }
                    gestureSettings.saveMacros(currentList);
                    renderMacros(container);
                }
            });
            header.addView(btnDelete);

            row.addView(header);
            container.addView(row);
        }
    }

    private void showMacroStepBuilder(final GestureSettings.GestureMacro macro, final LinearLayout macrosContainer) {
        final List<com.mediasorter.organizer.Action> tempActions = new ArrayList<>(macro.actions);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 16, 32, 16);

        final LinearLayout stepsContainer = new LinearLayout(this);
        stepsContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(stepsContainer);

        final Runnable renderStepsList = new Runnable() {
            @Override
            public void run() {
                stepsContainer.removeAllViews();
                for (int i = 0; i < tempActions.size(); i++) {
                    final int stepIdx = i;
                    com.mediasorter.organizer.Action act = tempActions.get(i);

                    LinearLayout stepRow = new LinearLayout(SettingsActivity.this);
                    stepRow.setOrientation(LinearLayout.HORIZONTAL);
                    stepRow.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = 4;
                    stepRow.setLayoutParams(lp);

                    TextView stepLbl = makeLabel(SettingsActivity.this, (stepIdx + 1) + ". " + act.describe());
                    stepLbl.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                    stepRow.addView(stepLbl);

                    Button btnUp = makeSmallButton("▲");
                    btnUp.setEnabled(stepIdx > 0);
                    btnUp.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            com.mediasorter.organizer.Action current = tempActions.remove(stepIdx);
                            tempActions.add(stepIdx - 1, current);
                            run();
                        }
                    });
                    stepRow.addView(btnUp);

                    Button btnDown = makeSmallButton("▼");
                    btnDown.setEnabled(stepIdx < tempActions.size() - 1);
                    btnDown.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            com.mediasorter.organizer.Action current = tempActions.remove(stepIdx);
                            tempActions.add(stepIdx + 1, current);
                            run();
                        }
                    });
                    stepRow.addView(btnDown);

                    Button btnEditStep = makeSmallButton("Edit");
                    btnEditStep.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            RulesActivity.showActionPickerDialog(SettingsActivity.this, tempActions.get(stepIdx), new RulesActivity.ActionCallback() {
                                @Override
                                public void onActionSelected(com.mediasorter.organizer.Action updatedAction) {
                                    tempActions.set(stepIdx, updatedAction);
                                    run();
                                }
                            });
                        }
                    });
                    stepRow.addView(btnEditStep);

                    Button btnDel = makeSmallButton("✕");
                    btnDel.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            tempActions.remove(stepIdx);
                            run();
                        }
                    });
                    stepRow.addView(btnDel);

                    stepsContainer.addView(stepRow);
                }
            }
        };

        renderStepsList.run();

        Button btnAddStep = makeSmallButton("+ Add Step");
        btnAddStep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tempActions.size() >= 10) {
                    Toast.makeText(SettingsActivity.this, "Maximum of 10 steps per macro allowed", Toast.LENGTH_SHORT).show();
                    return;
                }
                RulesActivity.showActionPickerDialog(SettingsActivity.this, null, new RulesActivity.ActionCallback() {
                    @Override
                    public void onActionSelected(com.mediasorter.organizer.Action action) {
                        if (action != null) {
                            tempActions.add(action);
                            renderStepsList.run();
                        }
                    }
                });
            }
        });
        mainLayout.addView(btnAddStep);

        ScrollView sv = new ScrollView(this);
        sv.addView(mainLayout);

        new AlertDialog.Builder(this)
            .setTitle("Edit Macro: " + macro.name)
            .setView(sv)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    macro.actions = tempActions;
                    List<GestureSettings.GestureMacro> mList = gestureSettings.loadMacros();
                    for (GestureSettings.GestureMacro existingM : mList) {
                        if (existingM.id.equals(macro.id)) {
                            existingM.actions = macro.actions;
                            break;
                        }
                    }
                    gestureSettings.saveMacros(mList);
                    renderMacros(macrosContainer);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
