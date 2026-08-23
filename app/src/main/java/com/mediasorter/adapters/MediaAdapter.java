package com.mediasorter.adapters;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.R;
import com.mediasorter.FileStatus;
import com.mediasorter.TagText;
import com.mediasorter.ThumbnailLoader;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.ViewHolder> {

    private int colorAccent = 0xFFE94560; // default fallback

    public interface OnFileClickListener {
        void onFileClick(MediaFile file);
    }

    public interface OnFileLongClickListener {
        void onFileLongClick(MediaFile file, View anchor);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    /** MainActivity owns the highlighted absolute index. The adapter only asks
     * this provider while binding and never stores a second current index. */
    public interface HighlightProvider {
        boolean isHighlighted(MediaFile file);
    }

    private List<MediaFile>            files     = new ArrayList<>();
    private OnFileClickListener        listener;
    private OnFileLongClickListener    longClickListener;
    private OnSelectionChangedListener selectionListener;
    private ThumbnailLoader            loader;
    private FileStatus                 fileStatus;
    private HighlightProvider           highlightProvider;
    private boolean                    selectMode   = false;
    private final LinkedHashSet<String> selected     = new LinkedHashSet<>();

    public MediaAdapter(ThumbnailLoader loader, OnFileClickListener listener) {
        this.loader   = loader;
        this.listener = listener;
    }

    public void setFileStatus(FileStatus status) { this.fileStatus = status; }
    public void setHighlightProvider(HighlightProvider provider) {
        this.highlightProvider = provider;
    }

    /** Rebind only the current-row visual state after navigation. */
    public void notifyHighlightChanged() {
        if (!files.isEmpty()) notifyItemRangeChanged(0, files.size(), "highlight");
    }

    /**
     * Compatibility-facing name for navigation callers. The absolute index is
     * owned by MainActivity; this adapter only re-reads its provider.
     */
    public void setHighlightedPosition(int ignoredAbsoluteIndex) {
        notifyHighlightChanged();
    }

    /** Rebind one row immediately after a status toggle. */
    public void updateFileStatus(MediaFile file) {
        if (file == null) return;
        for (int i = 0; i < files.size(); i++) {
            if (file.getPath().equals(files.get(i).getPath())) {
                notifyItemChanged(i, "status");
                return;
            }
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        TypedValue typedValue = new TypedValue();
        if (recyclerView.getContext().getTheme().resolveAttribute(R.attr.colorAccent, typedValue, true)) {
            colorAccent = typedValue.data;
        }
    }

    public void setSelectionListener(OnSelectionChangedListener l) {
        this.selectionListener = l;
    }

    public void setOnFileLongClickListener(OnFileLongClickListener l) {
        this.longClickListener = l;
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    public void setFiles(List<MediaFile> newFiles) {
        this.files = new ArrayList<>(newFiles);
        notifyDataSetChanged();
    }

    public void addFile(MediaFile file) {
        for (MediaFile f : files) {
            if (f.getPath().equals(file.getPath())) return;
        }
        files.add(file);
        notifyItemInserted(files.size() - 1);
    }

    public void removeFile(String path) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(path)) {
                files.remove(i);
                selected.remove(path);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void updateFile(MediaFile file) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(file.getPath())) {
                files.set(i, file);
                notifyItemChanged(i);
                return;
            }
        }
    }

    /**
     * Partial update — only rebinds tags text, not the thumbnail.
     * Called during rapid tagging to avoid re-decoding bitmaps.
     */
    public void updateFileTags(MediaFile file) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(file.getPath())) {
                files.set(i, file);
                notifyItemChanged(i, "tags");
                return;
            }
        }
    }

    /**
     * Directly toggle a single tag on a file and re-bind just the tags line.
     * MainActivity routes through TagManager so changes also persist to XMP;
     * this in-memory edit is a lightweight path for callers that already
     * handled persistence themselves.
     */
    public void editFileTags(MediaFile f, String tag) {
        if (f == null || tag == null) return;
        String t = tag.trim();
        if (t.isEmpty()) return;
        if (f.hasTag(t)) f.removeTag(t);
        else             f.addTag(t);
        updateFileTags(f);
    }

    /**
     * Compatibility hook for older callers. Highlight state is owned by
     * MainActivity; this method deliberately does not store the path.
     */
    public void setSelected(String path) {
        notifyHighlightChanged();
    }

    public String getSelectedPath() {
        if (highlightProvider == null) return "";
        for (MediaFile file : files) {
            if (highlightProvider.isHighlighted(file)) return file.getPath();
        }
        return "";
    }

    public void togglePath(String path) {
        if (path == null) return;
        if (selected.contains(path)) selected.remove(path);
        else selected.add(path);
        notifySelectionChangedVisually();
        if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
    }

    public void selectPath(String path) {
        if (path == null) return;
        if (selected.add(path)) {
            notifySelectionChangedVisually();
            if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
        }
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    public void enterSelectMode() {
        selectMode = true;
        selected.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
    }

    public void exitSelectMode() {
        selectMode = false;
        selected.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
    }

    public boolean isSelectMode()          { return selectMode; }
    public int     getSelectedCount()      { return selected.size(); }

    public List<MediaFile> getSelectedFiles() {
        List<MediaFile> result = new ArrayList<>();
        // Use selection order from LinkedHashSet for deterministic ordering
        for (String path : selected) {
            for (MediaFile f : files) {
                if (f.getPath().equals(path)) {
                    result.add(f);
                    break;
                }
            }
        }
        return result;
    }

    public void selectAll() {
        for (MediaFile f : files) selected.add(f.getPath());
        notifySelectionChangedVisually();
        if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
    }

    public void deselectAll() {
        selected.clear();
        notifySelectionChangedVisually();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
    }

    /**
     * Rebind only the selection visuals of visible rows (checkbox, highlight,
     * order badge) via payload — a full notifyDataSetChanged() here forced a
     * thumbnail re-decode of every visible row on each tap.
     */
    private void notifySelectionChangedVisually() {
        if (!files.isEmpty()) notifyItemRangeChanged(0, files.size(), "selection");
    }

    private void toggleSelection(String path, ViewHolder holder) {
        if (selected.contains(path)) {
            selected.remove(path);
            holder.itemView.setBackgroundColor(0x00000000);
            holder.checkBox.setChecked(false);
        } else {
            selected.add(path);
            holder.itemView.setBackgroundColor(0xFF2A2A6E);
            holder.checkBox.setChecked(true);
        }
        if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
        // Refresh other visible rows so selection order badges update
        notifySelectionChangedVisually();
    }

    /**
     * Returns the 1-based selection order for the given path, or -1 if not selected.
     */
    private int getSelectionOrder(String path) {
        int order = 1;
        for (String p : selected) {
            if (p.equals(path)) return order;
            order++;
        }
        return -1;
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_media_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                  @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // Partial updates only — skip the (expensive) thumbnail reload.
            // RecyclerView may merge several payloads into one list.
            boolean handled = false;
            MediaFile file = files.get(position);
            for (Object p : payloads) {
                if ("tags".equals(p)) {
                    bindTags(holder, file);
                    handled = true;
                } else if ("selection".equals(p) || "highlight".equals(p)) {
                    bindSelectionVisual(holder, file);
                    handled = true;
                } else if ("status".equals(p)) {
                    bindSelectionVisual(holder, file);
                    bindFlagIndicator(holder, file);
                    handled = true;
                }
            }
            if (handled) return;
        }
        // Full bind
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaFile file = files.get(position);

        holder.fileName.setText(file.getName());
        holder.fileDetails.setText(
            file.getFormattedSize()
            + "  •  " + file.getType().name().toLowerCase(java.util.Locale.US));

        bindTags(holder, file);
        bindSelectionVisual(holder, file);
        bindFlagIndicator(holder, file);

        loader.load(file, holder.thumbnail);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectMode) {
                    toggleSelection(file.getPath(), holder);
                } else {
                    if (listener != null) listener.onFileClick(file);
                }
            }
        });
        holder.checkBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectMode) toggleSelection(file.getPath(), holder);
            }
        });

        // Quick tags live on the tag text so long-press remains dedicated to
        // multi-selection (the batch workflow users expect). Tapping the tag
        // text while a selection is active opens the quick-tag popup for the
        // whole selection — the fastest way to tag many files at once.
        holder.fileTags.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectMode && selected.isEmpty()) {
                    toggleSelection(file.getPath(), holder);
                } else if (longClickListener != null) {
                    longClickListener.onFileLongClick(file, holder.fileTags);
                }
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!selectMode) enterSelectMode();
                if (!selected.contains(file.getPath())) {
                    toggleSelection(file.getPath(), holder);
                }
                return true;
            }
        });
    }

    /**
     * Checkbox / row highlight / selection-order badge — everything except
     * text rows and the thumbnail. Extracted so the "selection" payload path
     * can rebind just this without touching the bitmap pipeline.
     */
    private void bindSelectionVisual(ViewHolder holder, MediaFile file) {
        boolean isSel = selected.contains(file.getPath());
        boolean highlighted = highlightProvider != null && highlightProvider.isHighlighted(file);
        if (selectMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(isSel);
            holder.itemView.setBackgroundColor(isSel ? 0xFF2A2A6E
                    : highlighted ? 0xFF1A1A4E : 0x00000000);

            // Show selection order badge if enabled
            boolean showSeqLabels = holder.itemView.getContext()
                    .getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                    .getBoolean("show_seq_labels", true);

            if (isSel && showSeqLabels) {
                int order = getSelectionOrder(file.getPath());
                holder.selectionOrder.setVisibility(View.VISIBLE);
                holder.selectionOrder.setText(String.valueOf(order));
                // Make it circular
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(colorAccent);
                holder.selectionOrder.setBackground(bg);
            } else {
                holder.selectionOrder.setVisibility(View.GONE);
            }
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.selectionOrder.setVisibility(View.GONE);
            holder.itemView.setBackgroundColor(highlighted ? 0xFF1A1A4E : 0x00000000);
        }
        // Highlight payloads must also preserve the flagged-row indicator.
        bindFlagIndicator(holder, file);
    }

    /** Accent-tinted row makes a flagged file visible without an icon. */
    private void bindFlagIndicator(ViewHolder holder, MediaFile file) {
        boolean flagged = fileStatus != null && fileStatus.isFlagged(file.getPath());
        boolean highlighted = highlightProvider != null && highlightProvider.isHighlighted(file);
        boolean selectedFile = selectMode && selected.contains(file.getPath());
        if (flagged) {
            int alpha = selectedFile || highlighted ? 150 : 85;
            int tint = Color.argb(alpha, Color.red(colorAccent),
                    Color.green(colorAccent), Color.blue(colorAccent));
            holder.itemView.setBackgroundColor(tint);
        } else if (selectedFile) {
            holder.itemView.setBackgroundColor(0xFF2A2A6E);
        } else if (highlighted) {
            holder.itemView.setBackgroundColor(0xFF1A1A4E);
        } else {
            holder.itemView.setBackgroundColor(0x00000000);
        }
    }

    /** Extracted so partial-update can call just this. */
    private void bindTags(ViewHolder holder, MediaFile file) {
        List<String> tags = new ArrayList<>();
        for (String tag : file.getTags()) {
            String plain = TagText.plain(tag);
            if (!plain.isEmpty()) tags.add(plain);
        }
        boolean showTagCount = holder.itemView.getContext()
                .getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("show_tag_count", true);

        if (tags.isEmpty()) {
            holder.fileTags.setText("No tags");
            holder.fileTags.setTextColor(0xFF666666);
        } else {
            String text = join("  ", tags);
            if (showTagCount) {
                text = "(" + tags.size() + ") " + text;
            }
            holder.fileTags.setText(text);
            holder.fileTags.setTextColor(colorAccent);
        }
    }

    private static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (CharSequence item : elements) {
        if (i++ > 0) sb.append(delimiter);
        sb.append(item);
    }
    return sb.toString();
}

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.thumbnail.getTag() != null) {
            loader.cancel(holder.thumbnail.getTag().toString());
            holder.thumbnail.setImageBitmap(null);
        }
    }

    @Override
    public int getItemCount() { return files.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView  fileName;
        TextView  fileDetails;
        TextView  fileTags;
        CheckBox  checkBox;
        TextView  selectionOrder;

        ViewHolder(View v) {
            super(v);
            thumbnail      = v.findViewById(R.id.thumbnail);
            fileName       = v.findViewById(R.id.fileName);
            fileDetails    = v.findViewById(R.id.fileDetails);
            fileTags       = v.findViewById(R.id.fileTags);
            checkBox       = v.findViewById(R.id.fileCheckbox);
            selectionOrder = v.findViewById(R.id.selectionOrder);
        }
    }
}


