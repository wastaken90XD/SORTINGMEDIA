package com.mediasorter;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class SettingsActivity extends Activity {

    private CacheManager  cacheManager;
    private FolderManager folderManager;
    private FolderWatcher folderWatcher;
    private TextView      cacheSizeLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cacheManager  = new CacheManager(this);
        folderManager = new FolderManager(this);
        folderWatcher = new FolderWatcher(new FolderWatcher.Listener() {
            @Override public void onFileAdded(String path) {}
            @Override public void onFileDeleted(String path) {}
            @Override public void onFileModified(String path) {}
            });
        buildSettings();
    }

    private void buildSettings() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(32, 32, 32, 32);

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(makeTitle("Settings"));

        // ── Cache section ─────────────────────────────────────────────────────
        root.addView(makeTitle("Cache"));

        cacheSizeLabel = makeLabel(
            "Current size: " + cacheManager.getFormattedCacheSize());
        root.addView(cacheSizeLabel);

        TextView limitLabel = makeLabel(
            "Cache limit: " + cacheManager.getLimitMB() + " MB");
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

        Button btnClearCache = makeButton("Clear Cache Now");
        btnClearCache.setOnClickListener(v -> {
            cacheManager.clearAll();
            cacheSizeLabel.setText("Current size: " +
                cacheManager.getFormattedCacheSize());
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnClearCache);

        // ── Folders section ───────────────────────────────────────────────────
        root.addView(makeTitle("Watched Folders"));

        List<String> folders = folderManager.getFolders();
        if (folders.isEmpty()) {
            root.addView(makeLabel("No folders added yet"));
        } else {
            for (String folder : folders) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView folderLabel = makeLabel(folder);
                folderLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(folderLabel);

                Button removeBtn = makeButton("Remove");
                removeBtn.setOnClickListener(v -> {
                    folderManager.removeFolder(folder);
                    root.removeView(row);
                    Toast.makeText(this,
                        "Folder removed", Toast.LENGTH_SHORT).show();
                });
                row.addView(removeBtn);
                root.addView(row);
            }
        }

        Button btnAddFolder = makeButton("+ Add Folder");
        btnAddFolder.setOnClickListener(v -> showAddFolderDialog(root));
        root.addView(btnAddFolder);

        // ── Back ──────────────────────────────────────────────────────────────
        Button btnBack = makeButton("← Back");
        btnBack.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        backLp.topMargin = 48;
        btnBack.setLayoutParams(backLp);
        root.addView(btnBack);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void showAddFolderDialog(LinearLayout root) {
        EditText input = new EditText(this);
        input.setHint("/sdcard/DCIM");
        input.setTextColor(0xFFFFFFFF);

        new AlertDialog.Builder(this)
            .setTitle("Add Folder")
            .setView(input)
            .setPositiveButton("Add", (d, w) -> {
                String path = input.getText().toString().trim();
                if (!path.isEmpty()) {
                    folderManager.addFolder(path);
                    Toast.makeText(this,
                        "Folder added", Toast.LENGTH_SHORT).show();
                    recreate();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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
