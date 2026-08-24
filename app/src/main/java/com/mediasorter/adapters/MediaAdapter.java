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
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private int                         absoluteWindowStart;
    private boolean                    selectMode   = false;
    private final LinkedHashSet<String> selected     = new LinkedHashSet<>();

    private static final int TYPE_FILE = 0;
    private static final int TYPE_GROUP_HEADER = 1;

    private static class DisplayItem {
        Group group;
        MediaFile file;
        boolean header;
        DisplayItem(Group value) { group = value; header = true; }
        DisplayItem(Group value, MediaFile valueFile) {
            group = value;
            file = valueFile;
            header = false;
        }
    }

    private final List<DisplayItem> displayItems = new ArrayList<>();
    private List<Group> groupedGroups = new ArrayList<>();
    private final Set<String> collapsedGroups = new HashSet<>();
    private boolean groupedMode;

    public MediaAdapter(ThumbnailLoader loader, OnFileClickListener listener) {
        this.loader   = loader;
        this.listener = listener;
    }

    public void setFileStatus(FileStatus status) { this.fileStatus = status; }
    public void setAbsoluteWindowStart(int start) { absoluteWindowStart = Math.max(0, start); }
    public void setHighlightProvider(HighlightProvider provider) {
        this.highlightProvider = provider;
    }

    /** Rebind only the current-row visual state after navigation. */
    public void notifyHighlightChanged() {
        int count = getItemCount();
        if (count > 0) notifyItemRangeChanged(0, count, "highlight");
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
        notifyFileAppearances(file.getPath(), "status");
    }

    /** Immediate status notification for MainActivity's absolute index. */
    public void notifyFlagChanged(int currentIndex) {
        if (groupedMode) {
            if (highlightProvider != null) {
                for (int i = 0; i < displayItems.size(); i++) {
                    DisplayItem item = displayItems.get(i);
                    if (!item.header && highlightProvider.isHighlighted(item.file)) {
                        notifyItemChanged(i, "status");
                    }
                }
            }
            return;
        }
        if (currentIndex >= 0 && currentIndex < files.size()
                && absoluteWindowStart == 0) {
            notifyItemChanged(currentIndex, "status");
            return;
        }
        int local = currentIndex - absoluteWindowStart;
        if (local >= 0 && local < files.size()) notifyItemChanged(local, "status");
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
        if (groupedMode) {
            List<String> groupedSelections = new ArrayList<String>();
            for (String key : selected) if (key.contains("#group#")) groupedSelections.add(key);
            selected.removeAll(groupedSelections);
        }
        groupedMode = false;
        groupedGroups = new ArrayList<Group>();
        collapsedGroups.clear();
        displayItems.clear();
        this.files = newFiles == null ? new ArrayList<MediaFile>() : new ArrayList<>(newFiles);
        notifyDataSetChanged();
    }

    /** Explorer model with one independent appearance for each group member. */
    public void setGroupedGroups(List<Group> groups) {
        Set<String> previousSelected = selectedActualPaths();
        groupedMode = true;
        groupedGroups = groups == null ? new ArrayList<Group>() : new ArrayList<>(groups);
        collapsedGroups.clear();
        rebuildGroupedDisplay();
        selected.clear();
        for (String path : previousSelected) selected.add(appearanceKeyForPath(path));
        notifyDataSetChanged();
    }

    private void rebuildGroupedDisplay() {
        displayItems.clear();
        files = new ArrayList<MediaFile>();
        Set<String> seen = new HashSet<String>();
        if (groupedGroups == null) return;
        for (Group group : groupedGroups) {
            if (group == null || group.getCount() == 0) continue;
            displayItems.add(new DisplayItem(group));
            boolean collapsed = collapsedGroups.contains(group.getLabel());
            for (MediaFile file : group.getFiles()) {
                if (file == null) continue;
                if (seen.add(file.getPath())) files.add(file);
                if (!collapsed) displayItems.add(new DisplayItem(group, file));
            }
        }
    }

    public boolean isGroupedMode() { return groupedMode; }

    private DisplayItem displayItem(int position) {
        if (!groupedMode || position < 0 || position >= displayItems.size()) return null;
        return displayItems.get(position);
    }

    private String appearanceKey(DisplayItem item) {
        if (item == null || item.file == null) return "";
        if (!groupedMode || item.group == null) return item.file.getPath();
        return item.group.getLabel() + "#group#" + item.file.getPath();
    }

    private String appearanceKeyForPath(String path) {
        if (!groupedMode) return path;
        for (DisplayItem item : displayItems) {
            if (!item.header && item.file != null && path.equals(item.file.getPath())) {
                return appearanceKey(item);
            }
        }
        return path;
    }

    private void toggleGroup(int position) {
        DisplayItem item = displayItem(position);
        if (item == null || !item.header || item.group == null) return;
        String label = item.group.getLabel();
        if (collapsedGroups.contains(label)) collapsedGroups.remove(label);
        else collapsedGroups.add(label);
        rebuildGroupedDisplay();
        notifyDataSetChanged();
    }

    public void addFile(MediaFile file) {
        if (groupedMode || file == null) return;
        for (MediaFile f : files) {
            if (f.getPath().equals(file.getPath())) return;
        }
        files.add(file);
        notifyItemInserted(files.size() - 1);
    }

    public void removeFile(String path) {
        if (path == null) return;
        if (groupedMode) {
            for (int i = displayItems.size() - 1; i >= 0; i--) {
                DisplayItem item = displayItems.get(i);
                if (!item.header && item.file != null && path.equals(item.file.getPath())) {
                    displayItems.remove(i);
                    notifyItemRemoved(i);
                }
            }
            for (int i = files.size() - 1; i >= 0; i--) {
                if (path.equals(files.get(i).getPath())) files.remove(i);
            }
            selected.remove(path);
            List<String> keys = new ArrayList<String>();
            for (String key : selected) if (key.endsWith("#group#" + path)) keys.add(key);
            selected.removeAll(keys);
            return;
        }
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
        if (file == null) return;
        if (groupedMode) {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getPath().equals(file.getPath())) files.set(i, file);
            }
            notifyFileAppearances(file.getPath(), null);
            return;
        }
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(file.getPath())) {
                files.set(i, file);
                notifyItemChanged(i);
                return;
            }
        }
    }

    private void notifyFileAppearances(String path, Object payload) {
        if (path == null) return;
        if (groupedMode) {
            for (int i = 0; i < displayItems.size(); i++) {
                DisplayItem item = displayItems.get(i);
                if (!item.header && item.file != null && path.equals(item.file.getPath())) {
                    if (payload == null) notifyItemChanged(i);
                    else notifyItemChanged(i, payload);
                }
            }
        } else {
            for (int i = 0; i < files.size(); i++) {
                if (path.equals(files.get(i).getPath())) {
                    if (payload == null) notifyItemChanged(i);
                    else notifyItemChanged(i, payload);
                }
            }
        }
    }

    /**
     * Partial update — only rebinds tags text, not the thumbnail.
     * Called during rapid tagging to avoid re-decoding bitmaps.
     */
    public void updateFileTags(MediaFile file) {
        if (file == null) return;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(file.getPath())) files.set(i, file);
        }
        if (groupedMode) {
            notifyFileAppearances(file.getPath(), "tags");
        } else {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getPath().equals(file.getPath())) {
                    notifyItemChanged(i, "tags");
                    return;
                }
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
        String key = appearanceKeyForPath(path);
        if (selected.contains(key)) selected.remove(key);
        else selected.add(key);
        notifySelectionChangedVisually();
        if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedCount());
    }

    public void selectPath(String path) {
        if (path == null) return;
        String key = appearanceKeyForPath(path);
        if (selected.add(key)) {
            notifySelectionChangedVisually();
            if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedCount());
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

    public boolean isSelectMode() { return selectMode; }
    public int getSelectedCount() { return selectedActualPaths().size(); }

    private Set<String> selectedActualPaths() {
        Set<String> paths = new LinkedHashSet<String>();
        if (!groupedMode) {
            paths.addAll(selected);
            return paths;
        }
        for (String key : selected) {
            int marker = key.indexOf("#group#");
            paths.add(marker >= 0 ? key.substring(marker + 7) : key);
        }
        return paths;
    }

    public List<MediaFile> getSelectedFiles() {
        List<MediaFile> result = new ArrayList<MediaFile>();
        for (String path : selectedActualPaths()) {
            for (MediaFile file : files) {
                if (file != null && path.equals(file.getPath())) {
                    result.add(file);
                    break;
                }
            }
        }
        return result;
    }

    public void selectAll() {
        if (groupedMode) {
            for (DisplayItem item : displayItems) {
                if (!item.header && item.file != null) selected.add(appearanceKey(item));
            }
        } else {
            for (MediaFile file : files) selected.add(file.getPath());
        }
        notifySelectionChangedVisually();
        if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedCount());
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
        int count = getItemCount();
        if (count > 0) notifyItemRangeChanged(0, count, "selection");
    }

    private void toggleSelection(String path, String selectionKey, ViewHolder holder) {
        if (selected.contains(selectionKey)) {
            selected.remove(selectionKey);
            holder.itemView.setBackgroundColor(0x00000000);
            holder.checkBox.setChecked(false);
        } else {
            selected.add(selectionKey);
            holder.itemView.setBackgroundColor(0xFF2A2A6E);
            holder.checkBox.setChecked(true);
        }
        if (selectionListener != null) selectionListener.onSelectionChanged(getSelectedCount());
        notifySelectionChangedVisually();
    }

    private int getSelectionOrder(String selectionKey) {
        int order = 1;
        for (String key : selected) {
            if (key.equals(selectionKey)) return order;
            order++;
        }
        return -1;
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        DisplayItem item = displayItem(position);
        return groupedMode && item != null && item.header
                ? TYPE_GROUP_HEADER : TYPE_FILE;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_GROUP_HEADER) {
            TextView header = new TextView(parent.getContext());
            float density = parent.getContext().getResources().getDisplayMetrics().density;
            int padding = Math.round(10 * density);
            header.setPadding(padding, padding / 2, padding, padding / 2);
            header.setTextColor(colorAccent);
            header.setTextSize(13f);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(0xFF1A1A2E);
            header.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(40 * density)));
            return new ViewHolder(header, true);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media_file, parent, false);
        return new ViewHolder(view, false);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        DisplayItem item = displayItem(position);
        if (groupedMode && item != null && item.header) {
            bindGroupHeader(holder, item, position);
            return;
        }
        MediaFile file = item == null ? files.get(position) : item.file;
        String key = item == null ? file.getPath() : appearanceKey(item);
        if (!payloads.isEmpty()) {
            boolean handled = false;
            for (Object payload : payloads) {
                if ("tags".equals(payload)) {
                    bindTags(holder, file);
                    handled = true;
                } else if ("selection".equals(payload) || "highlight".equals(payload)) {
                    bindSelectionVisual(holder, file, key);
                    handled = true;
                } else if ("status".equals(payload)) {
                    bindSelectionVisual(holder, file, key);
                    bindFlagIndicator(holder, file, key);
                    handled = true;
                }
            }
            if (handled) return;
        }
        bindFileViewHolder(holder, file, key);
    }

    private void bindGroupHeader(ViewHolder holder, DisplayItem item, final int position) {
        TextView header = (TextView) holder.itemView;
        final Group group = item.group;
        header.setText(group.getLabel() + " (" + group.getTotalCount() + ")");
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleGroup(position); }
        });
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DisplayItem item = displayItem(position);
        if (groupedMode && item != null && item.header) {
            bindGroupHeader(holder, item, position);
            return;
        }
        MediaFile file = item == null ? files.get(position) : item.file;
        bindFileViewHolder(holder, file, item == null ? file.getPath() : appearanceKey(item));
    }

    private void bindFileViewHolder(@NonNull final ViewHolder holder,
                                    final MediaFile file, final String selectionKey) {
        holder.fileName.setText(file.getName());
        holder.fileDetails.setText(
                file.getFormattedSize()
                        + "  •  " + file.getType().name().toLowerCase(java.util.Locale.US));
        bindTags(holder, file);
        bindSelectionVisual(holder, file, selectionKey);
        bindFlagIndicator(holder, file, selectionKey);
        loader.load(file, holder.thumbnail);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (selectMode) toggleSelection(file.getPath(), selectionKey, holder);
                else if (listener != null) listener.onFileClick(file);
            }
        });
        holder.checkBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (selectMode) toggleSelection(file.getPath(), selectionKey, holder);
            }
        });
        holder.fileTags.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (selectMode && selected.isEmpty()) {
                    toggleSelection(file.getPath(), selectionKey, holder);
                } else if (longClickListener != null) {
                    longClickListener.onFileLongClick(file, view);
                }
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                if (!selectMode) enterSelectMode();
                if (!selected.contains(selectionKey)) {
                    toggleSelection(file.getPath(), selectionKey, holder);
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
    private void bindSelectionVisual(ViewHolder holder, MediaFile file, String selectionKey) {
        boolean isSel = selected.contains(selectionKey);
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
                int order = getSelectionOrder(selectionKey);
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
        bindFlagIndicator(holder, file, selectionKey);
    }

    /** Accent-tinted row makes a flagged file visible without an icon. */
    private void bindFlagIndicator(ViewHolder holder, MediaFile file, String selectionKey) {
        boolean flagged = fileStatus != null && fileStatus.isFlagged(file.getPath());
        boolean highlighted = highlightProvider != null && highlightProvider.isHighlighted(file);
        boolean selectedFile = selectMode && selected.contains(selectionKey);
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
        if (holder.thumbnail != null && holder.thumbnail.getTag() != null) {
            loader.cancel(holder.thumbnail.getTag().toString());
            holder.thumbnail.setImageBitmap(null);
        }
    }

    @Override
    public int getItemCount() {
        return groupedMode ? displayItems.size() : files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView  fileName;
        TextView  fileDetails;
        TextView  fileTags;
        CheckBox  checkBox;
        TextView  selectionOrder;
        boolean groupHeader;

        ViewHolder(View v) { this(v, false); }

        ViewHolder(View v, boolean header) {
            super(v);
            groupHeader = header;
            if (!header) {
                thumbnail      = v.findViewById(R.id.thumbnail);
                fileName       = v.findViewById(R.id.fileName);
                fileDetails    = v.findViewById(R.id.fileDetails);
                fileTags       = v.findViewById(R.id.fileTags);
                checkBox       = v.findViewById(R.id.fileCheckbox);
                selectionOrder = v.findViewById(R.id.selectionOrder);
            }
        }
    }
}


