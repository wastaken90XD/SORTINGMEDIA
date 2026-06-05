package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class SettingsActivity extends Activity {

    private CacheManager    cacheManager;
    private FolderManager   folderManager;
    private ThumbnailLoader thumbnailLoader;
    private GestureSettings gestureSettings;
    private MediaIndexer    indexer;
    private TextView        cacheSizeLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cacheManager    = new CacheManager(this);
        folderManager   = new FolderManager(this);
        thumbnailLoader = new ThumbnailLoader(this);
        gestureSettings = new GestureSettings(this);
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

        cacheSizeLabel = makeLabel("Current: " + cacheManager.getFormattedCacheSize()
            + " / " + cacheManager.getLimitMB() + " MB"
            + (cacheManager.isAboveWarning() ? "  ⚠" : ""));
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

        TextView qualityLabel = makeLabel("Quality: " + qualityName(thumbnailLoader.getQuality()));
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

        TextView countLabel = makeLabel("Max loaded: " + thumbnailLoader.getMaxCount());
        root.addView(countLabel);

        SeekBar countSeek = new SeekBar(this);
        countSeek.setMax(190);
        countSeek.setProgress(thumbnailLoader.getMaxCount() - 10);
        countSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int count = progress + 10;
                thumbnailLoader.setMaxCount(count);
                countLabel.setText("Max loaded: " + count);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(countSeek);
        // ── Window size ───────────────────────────────────────────────────────

        root.addView(makeTitle("Memory Window"));
        root.addView(makeLabel("Files loaded in memory at once"));
        SharedPreferences windowPrefs = getSharedPreferences("window_prefs", MODE_PRIVATE);
        int currentWindow = windowPrefs.getInt("window_size", 20);
        TextView windowLabel = makeLabel("Window size: " + currentWindow + " files");
        root.addView(windowLabel);

        SeekBar windowSeek = new SeekBar(this);
        windowSeek.setMax(90);  // 10 to 100
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
        
        // ── Gestures ──────────────────────────────────────────────────────────
        root.addView(makeTitle("Gestures"));
        root.addView(makeGestureRow("Swipe Left",  gestureSettings.getLeft(),
            a -> gestureSettings.setLeft(a)));
        root.addView(makeGestureRow("Swipe Right", gestureSettings.getRight(),
            a -> gestureSettings.setRight(a)));
        root.addView(makeGestureRow("Swipe Up",    gestureSettings.getUp(),
            a -> gestureSettings.setUp(a)));
        root.addView(makeGestureRow("Swipe Down",  gestureSettings.getDown(),
            a -> gestureSettings.setDown(a)));

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
        void set(GestureSettings.GestureAction action);
    }

    private LinearLayout makeGestureRow(String label,
            GestureSettings.GestureAction current, GestureCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        row.setLayoutParams(lp);

        TextView lbl = makeLabel(label);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lbl);

        Spinner spinner = new Spinner(this);
        String[] labels = gestureSettings.getAllLabels();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Set current selection
        String currentLabel = gestureSettings.getLabel(current);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(currentLabel)) {
                spinner.setSelection(i);
                break;
            }
        }

        spinner.setOnItemSelectedListener(
            new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent,
                        android.view.View view, int position, long id) {
                    callback.set(gestureSettings.fromLabel(labels[position]));
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });

        row.addView(spinner);
        return row;
    }

    // ── Folder dialog ─────────────────────────────────────────────────────────

    private void showAddFolderDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
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
}
