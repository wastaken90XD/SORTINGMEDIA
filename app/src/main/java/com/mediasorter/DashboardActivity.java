package com.mediasorter;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.List;

public class DashboardActivity extends Activity {

    private MediaIndexer indexer;
    private TagManager   tagManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        indexer    = new MediaIndexer();
        tagManager = new TagManager(this);
        buildDashboard();
    }

    private void buildDashboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(32, 32, 32, 32);

        root.addView(makeTitle("Dashboard"));

        List<MediaFile> files = indexer.getIndex();
        List<Tag>       tags  = tagManager.getAllTags();

        // ── File stats ────────────────────────────────────────────────────────
        root.addView(makeTitle("Files"));

        int images = 0, videos = 0, unsupported = 0, tagged = 0;
        long totalSize = 0;
        for (MediaFile f : files) {
            switch (f.getType()) {
                case IMAGE: images++;       break;
                case VIDEO: videos++;       break;
                default:    unsupported++;  break;
            }
            if (!f.getTags().isEmpty()) tagged++;
            totalSize += f.getSize();
        }

        root.addView(makeLabel("Total files:       " + files.size()));
        root.addView(makeLabel("Images:            " + images));
        root.addView(makeLabel("Videos:            " + videos));
        root.addView(makeLabel("Unsupported:       " + unsupported));
        root.addView(makeLabel("Tagged:            " + tagged));
        root.addView(makeLabel("Untagged:          " + (files.size() - tagged)));
        root.addView(makeLabel("Total size:        " + formatSize(totalSize)));

        if (files.size() > 0) {
            int pct = tagged * 100 / files.size();
            root.addView(makeLabel("Progress:          " + pct + "%"));

            // Simple text progress bar
            StringBuilder bar = new StringBuilder("[");
            int filled = pct / 5;
            for (int i = 0; i < 20; i++) bar.append(i < filled ? "█" : "░");
            bar.append("]");
            root.addView(makeLabel(bar.toString()));
        }

        // ── Tag stats ─────────────────────────────────────────────────────────
        root.addView(makeTitle("Tags"));
        root.addView(makeLabel("Total tags:        " + tags.size()));

        if (!tags.isEmpty()) {
            root.addView(makeLabel("Most used:"));
            int shown = Math.min(10, tags.size());
            for (int i = 0; i < shown; i++) {
                Tag t = tags.get(i);
                root.addView(makeLabel(
                    "  " + (i + 1) + ". " + t.getName()
                    + "  (" + t.getUsageCount() + " uses)"));
            }
        }

        // ── File size breakdown ───────────────────────────────────────────────
        root.addView(makeTitle("Size Breakdown"));
        int under1mb = 0, under5mb = 0, under20mb = 0, over20mb = 0;
        for (MediaFile f : files) {
            long mb = f.getSize() / (1024 * 1024);
            if      (mb < 1)  under1mb++;
            else if (mb < 5)  under5mb++;
            else if (mb < 20) under20mb++;
            else              over20mb++;
        }
        root.addView(makeLabel("Under 1MB:         " + under1mb));
        root.addView(makeLabel("1MB - 5MB:         " + under5mb));
        root.addView(makeLabel("5MB - 20MB:        " + under20mb));
        root.addView(makeLabel("Over 20MB:         " + over20mb));

        // ── Back ──────────────────────────────────────────────────────────────
        android.widget.Button btnBack = new android.widget.Button(this);
        btnBack.setText("← Back");
        btnBack.setTextColor(0xFFFFFFFF);
        btnBack.setBackgroundColor(0xFF1A1A2E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 48;
        btnBack.setLayoutParams(lp);
        btnBack.setOnClickListener(v -> finish());
        root.addView(btnBack);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return (bytes / 1024) + " KB";
        if (bytes < 1024*1024*1024)   return (bytes / (1024*1024)) + " MB";
        return (bytes / (1024*1024*1024)) + " GB";
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
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 4;
        tv.setLayoutParams(lp);
        return tv;
    }
}
