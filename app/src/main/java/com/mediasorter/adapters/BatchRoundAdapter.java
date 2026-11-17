package com.mediasorter.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.BatchGameEngine;
import com.mediasorter.ThumbnailLoader;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RecyclerView cell renderer for one Batch tagger round. */
public final class BatchRoundAdapter extends RecyclerView.Adapter<BatchRoundAdapter.Holder> {
    public interface Listener { void onCellClicked(String path); }

    private final ThumbnailLoader thumbnailLoader;
    private final Listener listener;
    private final List<MediaFile> files = new ArrayList<MediaFile>();
    private final Map<String, Integer> positions = new HashMap<String, Integer>();
    private final Set<String> selected = new HashSet<String>();
    private final Set<String> applied = new HashSet<String>();
    private final Set<String> skipped = new HashSet<String>();
    private int cellWidth;
    private int cellHeight;

    public BatchRoundAdapter(ThumbnailLoader loader, Listener value) {
        thumbnailLoader = loader;
        listener = value;
    }

    public void setCellSize(int width, int height) {
        int nextWidth = Math.max(1, width);
        int nextHeight = Math.max(1, height);
        if (cellWidth == nextWidth && cellHeight == nextHeight) return;
        cellWidth = nextWidth;
        cellHeight = nextHeight;
        if (!files.isEmpty()) notifyDataSetChanged();
    }

    public void setRound(BatchGameEngine.Round round, int width, int height) {
        files.clear();
        positions.clear();
        selected.clear();
        applied.clear();
        skipped.clear();
        if (round != null) {
            files.addAll(round.files);
            for (int i = 0; i < files.size(); i++) {
                MediaFile file = files.get(i);
                if (file != null && file.getPath() != null) positions.put(file.getPath(), i);
            }
            applied.addAll(round.getAppliedPaths());
            skipped.addAll(round.getSkippedPaths());
        }
        cellWidth = Math.max(1, width);
        cellHeight = Math.max(1, height);
        notifyDataSetChanged();
    }

    public void setState(Set<String> selectedPaths, Set<String> appliedPaths,
                         Set<String> skippedPaths) {
        selected.clear();
        applied.clear();
        skipped.clear();
        if (selectedPaths != null) selected.addAll(selectedPaths);
        if (appliedPaths != null) applied.addAll(appliedPaths);
        if (skippedPaths != null) skipped.addAll(skippedPaths);
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPaths() { return new HashSet<String>(selected); }

    public void selectAll() {
        selected.clear();
        for (MediaFile file : files) if (file != null && !isDone(file.getPath())) selected.add(file.getPath());
        notifyDataSetChanged();
    }

    public void invertSelection() {
        Set<String> next = new HashSet<String>();
        for (MediaFile file : files) {
            if (file == null || isDone(file.getPath())) continue;
            if (!selected.contains(file.getPath())) next.add(file.getPath());
        }
        selected.clear();
        selected.addAll(next);
        notifyDataSetChanged();
    }

    public void clearSelection() { selected.clear(); notifyDataSetChanged(); }

    public void markApplied(Set<String> paths) {
        if (paths == null) return;
        applied.addAll(paths);
        selected.removeAll(applied);
        notifyChangedPaths(paths);
    }

    public void markSkipped(Set<String> paths) {
        if (paths == null) return;
        skipped.addAll(paths);
        selected.removeAll(skipped);
        notifyChangedPaths(paths);
    }

    private void notifyChangedPaths(Set<String> paths) {
        for (String path : paths) {
            Integer position = positions.get(path);
            if (position != null) notifyItemChanged(position);
        }
    }

    private boolean isDone(String path) { return applied.contains(path) || skipped.contains(path); }

    @NonNull
    @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context context = parent.getContext();
        FrameLayout root = new FrameLayout(context);
        root.setPadding(3, 3, 3, 3);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, cellHeight));
        root.setLayoutParams(params);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView caption = new TextView(context);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(9f);
        caption.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(22, cellHeight / 5), Gravity.BOTTOM);
        root.addView(caption, captionParams);
        TextView marker = new TextView(context);
        marker.setTextColor(Color.WHITE);
        marker.setTextSize(10f);
        marker.setGravity(Gravity.CENTER);
        marker.setTypeface(null, android.graphics.Typeface.BOLD);
        FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(28, 28, Gravity.TOP | Gravity.END);
        root.addView(marker, markerParams);
        return new Holder(root, image, caption, marker);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        final MediaFile file = files.get(position);
        final String path = file == null ? "" : file.getPath();
        ViewGroup.LayoutParams itemParams = holder.root.getLayoutParams();
        if (itemParams != null && itemParams.height != cellHeight) {
            itemParams.height = cellHeight;
            holder.root.setLayoutParams(itemParams);
        }
        boolean done = isDone(path);
        boolean isSelected = selected.contains(path);
        GradientDrawable background = new GradientDrawable();
        background.setColor(done ? 0xFF234D3A : isSelected ? 0xFF303078 : 0xFF242438);
        background.setStroke(isSelected ? 3 : 1, isSelected ? 0xFFE94560 : 0xFF444466);
        holder.root.setBackground(background);
        holder.caption.setText(file == null ? "" : file.getName());
        if (applied.contains(path)) holder.marker.setText("✓");
        else if (skipped.contains(path)) holder.marker.setText("↷");
        else holder.marker.setText("");
        if (thumbnailLoader != null && file != null) {
            thumbnailLoader.loadAtSize(file, holder.image, cellWidth, cellHeight);
        }
        holder.root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (isDone(path)) return;
                if (selected.contains(path)) selected.remove(path);
                else selected.add(path);
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition >= 0) notifyItemChanged(adapterPosition);
                if (listener != null) listener.onCellClicked(path);
            }
        });
    }

    @Override public int getItemCount() { return files.size(); }

    public static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final ImageView image;
        final TextView caption;
        final TextView marker;

        Holder(FrameLayout value, ImageView imageView, TextView captionView, TextView markerView) {
            super(value);
            root = value;
            image = imageView;
            caption = captionView;
            marker = markerView;
        }
    }
}
