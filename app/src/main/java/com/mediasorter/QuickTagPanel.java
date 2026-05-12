package com.mediasorter;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.List;

public class QuickTagPanel {

    public interface Listener {
        void onTagToggled(String tagName, boolean applied);
        void onDismiss();
    }

    private final Context      context;
    private final LinearLayout panel;
    private final LinearLayout row1;
    private final LinearLayout row2;

    private Listener  listener;
    private MediaFile currentFile;

    public QuickTagPanel(Context context, FrameLayout container) {
        this.context = context;

        // Outer panel — fixed 2 row height at bottom
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE0F0F1A);
        panel.setPadding(4, 4, 4, 4);

        // Row 1 — most used tags
        HorizontalScrollView scroll1 = new HorizontalScrollView(context);
        scroll1.setHorizontalScrollBarEnabled(false);
        row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        scroll1.addView(row1);

        // Row 2 — recently used tags
        HorizontalScrollView scroll2 = new HorizontalScrollView(context);
        scroll2.setHorizontalScrollBarEnabled(false);
        row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        scroll2.addView(row2);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll1.setLayoutParams(rowLp);
        scroll2.setLayoutParams(rowLp);

        panel.addView(scroll1);
        panel.addView(scroll2);

        // Add to container at bottom
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 80);
        lp.gravity = Gravity.BOTTOM;
        // Offset above action buttons (44dp) + details bar (~60dp)
        lp.bottomMargin = 104;
        container.addView(panel, lp);
    }

    public void setListener(Listener l) { this.listener = l; }

    // ── Update ────────────────────────────────────────────────────────────────

    public void setCurrentFile(MediaFile file, List<Tag> topTags, List<Tag> recentTags) {
        this.currentFile = file;
        buildRow(row1, topTags,    "▲ ");
        buildRow(row2, recentTags, "⟳ ");
    }

    private void buildRow(LinearLayout row, List<Tag> tags, String prefix) {
        row.removeAllViews();

        if (tags.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(prefix + "No tags yet");
            empty.setTextColor(0xFF666666);
            empty.setTextSize(10f);
            empty.setPadding(8, 0, 8, 0);
            row.addView(empty);
            return;
        }

        // Row label
        TextView label = new TextView(context);
        label.setText(prefix);
        label.setTextColor(0xFF666666);
        label.setTextSize(9f);
        label.setPadding(4, 0, 4, 0);
        row.addView(label);

        for (Tag tag : tags) {
            boolean applied = currentFile != null
                && currentFile.hasTag(tag.getName());

            Button btn = new Button(context);
            btn.setText(tag.getName());
            btn.setTextSize(10f);
            btn.setSingleLine(true);
            btn.setPadding(12, 0, 12, 0);
            btn.setTextColor(applied ? 0xFF121212 : 0xFFFFFFFF);
            btn.setBackgroundColor(applied ? 0xFFE94560 : 0xFF2A2A3E);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(3, 2, 3, 2);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> {
                if (currentFile == null) return;
                boolean nowApplied = currentFile.hasTag(tag.getName());
                if (listener != null) {
                    listener.onTagToggled(tag.getName(), !nowApplied);
                }
                // Toggle visual immediately
                btn.setBackgroundColor(!nowApplied ? 0xFFE94560 : 0xFF2A2A3E);
                btn.setTextColor(!nowApplied ? 0xFF121212 : 0xFFFFFFFF);
            });

            row.addView(btn);
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    public void show() {
        panel.setVisibility(View.VISIBLE);
    }

    public void hide() {
        panel.setVisibility(View.GONE);
        if (listener != null) listener.onDismiss();
    }

    public boolean isVisible() {
        return panel.getVisibility() == View.VISIBLE;
    }
}
