package com.mediasorter;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
    private final FrameLayout  container;
    private final LinearLayout panel;
    private final LinearLayout tagGrid;
    private final TextView     fileLabel;
    private       Listener     listener;
    private       MediaFile    currentFile;

    private float touchStartY;
    private static final int SWIPE_THRESHOLD = 80;

    public QuickTagPanel(Context context, FrameLayout container) {
        this.context   = context;
        this.container = container;

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE1A1A2E);
        panel.setPadding(16, 16, 16, 16);
        panel.setVisibility(View.GONE);

        fileLabel = new TextView(context);
        fileLabel.setTextColor(0xFFAAAAAA);
        fileLabel.setTextSize(11f);
        fileLabel.setPadding(0, 0, 0, 8);
        panel.addView(fileLabel);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("QUICK TAGS");
        title.setTextColor(0xFFE94560);
        title.setTextSize(12f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        Button dismiss = new Button(context);
        dismiss.setText("✕");
        dismiss.setTextSize(12f);
        dismiss.setTextColor(0xFFAAAAAA);
        dismiss.setBackgroundColor(0x00000000);
        dismiss.setOnClickListener(v -> hide());
        header.addView(dismiss);
        panel.addView(header);

        tagGrid = new LinearLayout(context);
        tagGrid.setOrientation(LinearLayout.VERTICAL);
        panel.addView(tagGrid);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        container.addView(panel, lp);

        container.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dy = touchStartY - event.getY();
                    if (dy > SWIPE_THRESHOLD && panel.getVisibility() == View.GONE) {
                        show();
                    } else if (dy < -SWIPE_THRESHOLD && panel.getVisibility() == View.VISIBLE) {
                        hide();
                    }
                    break;
            }
            return false;
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setCurrentFile(MediaFile file, List<Tag> tags) {
        this.currentFile = file;
        fileLabel.setText(file.getName());
        buildTagGrid(tags);
    }

    private void buildTagGrid(List<Tag> tags) {
        tagGrid.removeAllViews();

        LinearLayout row = null;
        for (int i = 0; i < tags.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = 6;
                row.setLayoutParams(rowLp);
                tagGrid.addView(row);
            }

            Tag tag = tags.get(i);
            boolean applied = currentFile != null && currentFile.hasTag(tag.getName());

            Button btn = new Button(context);
            btn.setText(tag.getName());
            btn.setTextSize(11f);
            btn.setTextColor(applied ? 0xFF121212 : 0xFFFFFFFF);
            btn.setBackgroundColor(applied ? 0xFFE94560 : 0xFF2A2A3E);
            btn.setSingleLine(true);

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            btnLp.setMargins(4, 0, 4, 0);
            btn.setLayoutParams(btnLp);

            btn.setOnClickListener(v -> {
                boolean nowApplied = currentFile != null
                    && currentFile.hasTag(tag.getName());
                if (listener != null) listener.onTagToggled(tag.getName(), !nowApplied);
                btn.setBackgroundColor(!nowApplied ? 0xFFE94560 : 0xFF2A2A3E);
                btn.setTextColor(!nowApplied ? 0xFF121212 : 0xFFFFFFFF);
            });

            if (row != null) row.addView(btn);
        }
    }

    public void show() {
        if (currentFile == null) return;
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
