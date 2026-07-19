package com.mediasorter.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.R;
import com.mediasorter.ThumbnailLoader;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(MediaFile file);
    }

    public interface OnFileLongClickListener {
        void onFileLongClick(MediaFile file, View anchor);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    private List<MediaFile>            files     = new ArrayList<>();
    private OnFileClickListener        listener;
    private OnFileLongClickListener    longClickListener;
    private OnSelectionChangedListener selectionListener;
    private ThumbnailLoader            loader;
    private String                     selectedPath = null;
    private boolean                    selectMode   = false;
    private final Set<String>          selected     = new HashSet<>();

    public MediaAdapter(ThumbnailLoader loader, OnFileClickListener listener) {
        this.loader   = loader;
        this.listener = listener;
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

    public void setSelected(String path) {
        String old   = selectedPath;
        selectedPath = path;
        for (int i = 0; i < files.size(); i++) {
            String p = files.get(i).getPath();
            if (p.equals(old) || p.equals(path)) notifyItemChanged(i);
        }
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    public void enterSelectMode() {
        selectMode = true;
        selected.clear();
        notifyDataSetChanged();
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
        for (MediaFile f : files) {
            if (selected.contains(f.getPath())) result.add(f);
        }
        return result;
    }

    public void selectAll() {
        for (MediaFile f : files) selected.add(f.getPath());
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(selected.size());
    }

    public void deselectAll() {
        selected.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
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
        if (!payloads.isEmpty() && "tags".equals(payloads.get(0))) {
            // Partial update — only rebind tags text, skip thumbnail reload
            MediaFile file = files.get(position);
            bindTags(holder, file);
            return;
        }
        // Full bind
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaFile file    = files.get(position);
        boolean   isSel   = selected.contains(file.getPath());

        holder.fileName.setText(file.getName());
        holder.fileDetails.setText(
            file.getFormattedSize()
            + "  •  " + file.getType().name().toLowerCase());

        bindTags(holder, file);

        if (selectMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(isSel);
            holder.itemView.setBackgroundColor(isSel ? 0xFF2A2A6E : 0x00000000);
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.itemView.setBackgroundColor(
                file.getPath().equals(selectedPath) ? 0xFF1A1A4E : 0x00000000);
        }

        loader.load(file, holder.thumbnail);

        holder.itemView.setOnClickListener(v -> {
            if (selectMode) {
                toggleSelection(file.getPath(), holder);
            } else {
                setSelected(file.getPath());
                if (listener != null) listener.onFileClick(file);
            }
        });
        holder.checkBox.setOnClickListener(v -> {
            if (selectMode) toggleSelection(file.getPath(), holder);
        });

        // Quick tags live on the tag text so long-press remains dedicated to
        // multi-selection (the batch workflow users expect).
        holder.fileTags.setOnClickListener(v -> {
            if (selectMode) {
                toggleSelection(file.getPath(), holder);
            } else if (longClickListener != null) {
                longClickListener.onFileLongClick(file, holder.fileTags);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectMode) enterSelectMode();
            if (!selected.contains(file.getPath())) {
                toggleSelection(file.getPath(), holder);
            }
            return true;
        });
    }

    /** Extracted so partial-update can call just this. */
    private void bindTags(ViewHolder holder, MediaFile file) {
        List<String> tags = file.getTags();
        if (tags.isEmpty()) {
            holder.fileTags.setText("No tags");
            holder.fileTags.setTextColor(0xFF666666);
        } else {
            holder.fileTags.setText(join("  ", tags));
            holder.fileTags.setTextColor(0xFFE94560);
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

        ViewHolder(View v) {
            super(v);
            thumbnail   = v.findViewById(R.id.thumbnail);
            fileName    = v.findViewById(R.id.fileName);
            fileDetails = v.findViewById(R.id.fileDetails);
            fileTags    = v.findViewById(R.id.fileTags);
            checkBox    = v.findViewById(R.id.fileCheckbox);
        }
    }
}


