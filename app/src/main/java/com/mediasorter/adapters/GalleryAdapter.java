package com.mediasorter.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.FileStatus;
import com.mediasorter.GalleryThumbnailLoader;
import com.mediasorter.R;
import com.mediasorter.TagText;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** RecyclerView cell presentation for MainActivity's gallery mode. */
public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {

    public interface Listener {
        void onGalleryFileClick(MediaFile file);
        void onGallerySelectionChanged(int count);
        void onGalleryLongPress(ViewHolder holder);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final FrameLayout cell;
        public final ImageView thumbnail;
        public final TextView filename;
        public final TextView tagBadge;
        public final TextView statusBadge;
        public final TextView sequenceBadge;
        public final CheckBox checkBox;

        ViewHolder(FrameLayout cell, ImageView thumbnail, TextView filename,
                   TextView tagBadge, TextView statusBadge,
                   TextView sequenceBadge, CheckBox checkBox) {
            super(cell);
            this.cell = cell;
            this.thumbnail = thumbnail;
            this.filename = filename;
            this.tagBadge = tagBadge;
            this.statusBadge = statusBadge;
            this.sequenceBadge = sequenceBadge;
            this.checkBox = checkBox;
        }
    }

    private final Context context;
    private final GalleryThumbnailLoader loader;
    private final FileStatus fileStatus;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> dragThumbnailPaths = new HashSet<>();
    private final Map<String, Integer> groupBorderColors = new HashMap<>();
    private final int accentColor;
    private List<MediaFile> files = new ArrayList<>();
    private boolean selectMode;
    private boolean lowMemory;
    private boolean fastScrolling;
    private boolean dragThumbnailActive;
    private String draggedThumbnailPath;
    private boolean showFilenameBadge = true;
    private boolean showTagBadge = true;
    private boolean showFlagBadge = true;
    private boolean showSequenceBadge = true;
    private int spacingDp = 4;
    private int columns = 3;

    public GalleryAdapter(Context context, GalleryThumbnailLoader loader,
                          FileStatus fileStatus, Listener listener,
                          boolean lowMemory) {
        this.context = context;
        this.loader = loader;
        this.fileStatus = fileStatus;
        this.listener = listener;
        this.lowMemory = lowMemory;
        this.prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);

        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.colorAccent, value, true)) {
            accentColor = value.data;
        } else {
            accentColor = 0xFFE94560;
        }
        refreshBadgeSettings();
    }

    public void setLowMemory(boolean low) {
        lowMemory = low;
        refreshBadgeSettings();
        notifyDataSetChanged();
    }

    public void refreshBadgeSettings() {
        showFilenameBadge = prefs.getBoolean("gallery_show_filename", true);
        showTagBadge = !lowMemory && prefs.getBoolean("gallery_show_tag_count", true);
        showFlagBadge = !lowMemory && prefs.getBoolean("gallery_show_flag", true);
        showSequenceBadge = !lowMemory && prefs.getBoolean("gallery_show_seq", true);
        notifyDataSetChanged();
    }

    public void setFastScrolling(boolean fast, int first, int last) {
        fastScrolling = fast;
    }

    public boolean isFastScrolling() { return fastScrolling; }

    public void reloadVisibleThumbnails(RecyclerView recyclerView) {
        if (recyclerView == null || fastScrolling) return;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder raw = recyclerView.getChildViewHolder(child);
            if (!(raw instanceof ViewHolder)) continue;
            ViewHolder holder = (ViewHolder) raw;
            int width = Math.max(1, holder.thumbnail.getWidth());
            int height = Math.max(1, holder.thumbnail.getHeight());
            int position = holder.getAdapterPosition();
            if (position != RecyclerView.NO_POSITION && position < files.size()) {
                MediaFile file = files.get(position);
                if (dragThumbnailActive && (dragThumbnailPaths.contains(file.getPath())
                        || file.getPath().equals(draggedThumbnailPath))) {
                    loader.loadForDrag(file, holder.thumbnail, width, height);
                } else {
                    loader.loadVisible(file, holder.thumbnail, width, height);
                }
            }
        }
    }

    public void updateDragThumbnailWindow(RecyclerView recyclerView,
                                           List<String> paths,
                                           String draggedPath) {
        dragThumbnailActive = true;
        draggedThumbnailPath = draggedPath;
        dragThumbnailPaths.clear();
        if (paths != null) dragThumbnailPaths.addAll(paths);
        loader.setDragPaths(paths);
        if (recyclerView == null) return;
        Set<String> attachedPaths = new HashSet<>();

        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder raw = recyclerView.getChildViewHolder(child);
            if (!(raw instanceof ViewHolder)) continue;
            ViewHolder holder = (ViewHolder) raw;
            Object tag = holder.thumbnail.getTag();
            String path = tag == null ? null : tag.toString();
            if (path != null) attachedPaths.add(path);
            boolean keep = path != null && (dragThumbnailPaths.contains(path)
                    || path.equals(draggedPath));
            MediaFile file = findFile(path);
            if (!keep) {
                holder.thumbnail.setImageDrawable(null);
                if (file != null) holder.thumbnail.setBackgroundColor(placeholderColor(file));
                continue;
            }
            if (file != null) {
                loader.loadForDrag(file, holder.thumbnail,
                        Math.max(1, holder.thumbnail.getWidth()),
                        Math.max(1, holder.thumbnail.getHeight()));
            }
        }

        int cellWidth = Math.max(1, recyclerView.getWidth() / Math.max(1, columns));
        int cellHeight = Math.max(dp(56), Math.round(cellWidth * 0.72f));
        for (String path : dragThumbnailPaths) {
            if (attachedPaths.contains(path)) continue;
            MediaFile file = findFile(path);
            if (file != null) loader.preloadForDrag(file, cellWidth, cellHeight);
        }
    }

    public void clearDragThumbnailWindow() {
        dragThumbnailActive = false;
        draggedThumbnailPath = null;
        dragThumbnailPaths.clear();
        loader.clearDragPaths();
    }

    private MediaFile findFile(String path) {
        if (path == null) return null;
        for (MediaFile file : files) {
            if (path.equals(file.getPath())) return file;
        }
        return null;
    }

    public void setColumns(int value) {
        columns = Math.max(1, Math.min(6, value));
        notifyDataSetChanged();
    }

    public int getColumns() { return columns; }

    public void setSpacingDp(int value) {
        spacingDp = Math.max(0, Math.min(16, value));
        notifyDataSetChanged();
    }

    public void setFiles(List<MediaFile> value) {
        files = value == null ? new ArrayList<MediaFile>() : new ArrayList<>(value);
        notifyDataSetChanged();
    }

    public List<MediaFile> getFiles() { return new ArrayList<>(files); }

    public MediaFile getFile(int position) {
        if (position < 0 || position >= files.size()) return null;
        return files.get(position);
    }

    public void moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= files.size() || to >= files.size()
                || from == to) return;
        MediaFile file = files.remove(from);
        files.add(to, file);
        notifyItemMoved(from, to);
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    public void enterSelectMode() {
        selectMode = true;
        notifyDataSetChanged();
    }

    public void exitSelectMode() {
        selectMode = false;
        selected.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public boolean isSelectMode() { return selectMode; }
    public int getSelectedCount() { return selected.size(); }

    public void setSelectedPaths(List<String> paths) {
        selected.clear();
        if (paths != null) selected.addAll(paths);
        selectMode = !selected.isEmpty();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public List<MediaFile> getSelectedFiles() {
        List<MediaFile> result = new ArrayList<>();
        for (String path : selected) {
            for (MediaFile file : files) {
                if (path.equals(file.getPath())) {
                    result.add(file);
                    break;
                }
            }
        }
        return result;
    }

    public List<String> getSelectedPaths() {
        return new ArrayList<>(selected);
    }

    public boolean isSelected(String path) {
        return path != null && selected.contains(path);
    }

    public void selectPath(String path) {
        if (path == null) return;
        selectMode = true;
        if (selected.add(path)) {
            notifyDataSetChanged();
            notifySelectionChanged();
        }
    }

    public void toggleSelection(MediaFile file) {
        if (file == null) return;
        selectMode = true;
        if (selected.contains(file.getPath())) selected.remove(file.getPath());
        else selected.add(file.getPath());
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void selectAll() {
        selectMode = true;
        for (MediaFile file : files) selected.add(file.getPath());
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void deselectAll() {
        selected.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void invertSelection() {
        selectMode = true;
        for (MediaFile file : files) {
            if (selected.contains(file.getPath())) selected.remove(file.getPath());
            else selected.add(file.getPath());
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void selectMatching(List<MediaFile> matches) {
        selectMode = true;
        if (matches != null) {
            for (MediaFile file : matches) selected.add(file.getPath());
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    private void notifySelectionChanged() {
        if (listener != null) listener.onGallerySelectionChanged(selected.size());
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout cell = new FrameLayout(context);
        cell.setFocusable(true);
        cell.setClickable(true);
        int pad = dp(spacingDp);
        cell.setPadding(pad, pad, pad, pad);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        cell.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(0xFF252538);
        content.addView(thumbnail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(100)));

        TextView filename = new TextView(context);
        filename.setTextColor(0xFFFFFFFF);
        filename.setTextSize(11f);
        filename.setGravity(Gravity.CENTER_HORIZONTAL);
        filename.setSingleLine(true);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        filename.setPadding(dp(2), dp(3), dp(2), dp(2));
        content.addView(filename, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tagBadge = makeBadge();
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(dp(24), dp(22));
        tagLp.gravity = Gravity.TOP | Gravity.START;
        cell.addView(tagBadge, tagLp);

        TextView statusBadge = makeBadge();
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(22));
        statusLp.gravity = Gravity.TOP | Gravity.END;
        cell.addView(statusBadge, statusLp);

        TextView sequenceBadge = makeBadge();
        FrameLayout.LayoutParams seqLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(22));
        seqLp.gravity = Gravity.BOTTOM | Gravity.START;
        cell.addView(sequenceBadge, seqLp);

        CheckBox checkBox = new CheckBox(context);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(accentColor));
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        checkLp.gravity = Gravity.TOP | Gravity.END;
        cell.addView(checkBox, checkLp);

        return new ViewHolder(cell, thumbnail, filename, tagBadge,
                statusBadge, sequenceBadge, checkBox);
    }

    private TextView makeBadge() {
        TextView badge = new TextView(context);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(10f);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(3), 0, dp(3), 0);
        badge.setBackgroundColor(0xAA202030);
        badge.setVisibility(View.GONE);
        return badge;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            for (Object payload : payloads) {
                if ("gallery_scroll".equals(payload)) {
                    MediaFile file = files.get(position);
                    holder.thumbnail.setImageDrawable(null);
                    holder.thumbnail.setAlpha(1.0f);
                    holder.thumbnail.setBackgroundColor(placeholderColor(file));
                    return;
                }
            }
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final MediaFile file = files.get(position);
        holder.itemView.setTag(file.getPath());
        holder.thumbnail.setTag(file.getPath());
        boolean keepDragBitmap = dragThumbnailActive
                && file.getPath().equals(draggedThumbnailPath);
        if (!keepDragBitmap) holder.thumbnail.setImageDrawable(null);
        holder.thumbnail.setAlpha(1.0f);
        holder.thumbnail.setBackgroundColor(placeholderColor(file));
        holder.filename.setText(file.getName());
        holder.filename.setVisibility(showFilenameBadge ? View.VISIBLE : View.GONE);

        int width = holder.itemView.getWidth();
        if (width <= 0) width = dp(120);
        ViewGroup.LayoutParams imageLp = holder.thumbnail.getLayoutParams();
        imageLp.height = Math.max(dp(56), Math.round(width * 0.72f));
        holder.thumbnail.setLayoutParams(imageLp);

        bindBadges(holder, file);
        bindSelection(holder, file);
        applySpacing(holder);
        applyGroupBorder(holder, file);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (selectMode) {
                    toggleSelection(file);
                } else if (listener != null) {
                    listener.onGalleryFileClick(file);
                }
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (listener != null) listener.onGalleryLongPress(holder);
                return true;
            }
        });
        holder.checkBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleSelection(file);
            }
        });
    }

    private void applySpacing(ViewHolder holder) {
        int pad = dp(spacingDp);
        holder.cell.setPadding(pad, pad, pad, pad);
    }

    private void bindBadges(ViewHolder holder, MediaFile file) {
        if (showTagBadge && !file.getTags().isEmpty()) {
            holder.tagBadge.setText(String.valueOf(file.getTags().size()));
            holder.tagBadge.setVisibility(View.VISIBLE);
        } else {
            holder.tagBadge.setVisibility(View.GONE);
        }

        FileStatus.Status status = fileStatus.getStatus(file.getPath());
        if (showFlagBadge && status != FileStatus.Status.NONE) {
            holder.statusBadge.setText(status.name());
            holder.statusBadge.setVisibility(View.VISIBLE);
        } else {
            holder.statusBadge.setVisibility(View.GONE);
        }

        String sequence = findSequenceLabel(file);
        if (showSequenceBadge && !sequence.isEmpty()) {
            holder.sequenceBadge.setText(sequence);
            holder.sequenceBadge.setVisibility(View.VISIBLE);
        } else {
            holder.sequenceBadge.setVisibility(View.GONE);
        }
    }

    private String findSequenceLabel(MediaFile file) {
        for (String tag : file.getTags()) {
            String plain = TagText.plain(tag);
            int marker = plain.indexOf("_seq_");
            if (marker >= 0 && marker + 5 < plain.length()) {
                return plain.substring(marker + 5);
            }
        }
        return "";
    }

    private void bindSelection(ViewHolder holder, MediaFile file) {
        boolean checked = selected.contains(file.getPath());
        holder.checkBox.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        holder.checkBox.setChecked(checked);
        if (checked) {
            if (!lowMemory && availableHeap() >= 30L * 1024L * 1024L) {
                holder.cell.setScaleX(1.03f);
                holder.cell.setScaleY(1.03f);
            }
        } else {
            holder.cell.setScaleX(1.0f);
            holder.cell.setScaleY(1.0f);
        }
    }

    private void applyGroupBorder(ViewHolder holder, MediaFile file) {
        String group = null;
        for (String tag : file.getTags()) {
            if (tag != null && tag.startsWith("link_")) {
                group = tag;
                break;
            }
        }
        int fill = selected.contains(file.getPath()) ? 0x552A2A6E : placeholderColor(file);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        if (group != null) {
            drawable.setStroke(dp(lowMemory ? 2 : 3), groupBorderColor(group));
        }
        holder.cell.setBackground(drawable);
    }

    private int groupBorderColor(String group) {
        Integer cached = groupBorderColors.get(group);
        if (cached != null) return cached;
        float[] hsv = new float[]{Math.abs(group.hashCode()) % 360, 0.65f, 0.9f};
        int color = Color.HSVToColor(hsv);
        groupBorderColors.put(group, color);
        return color;
    }

    public void setDragging(ViewHolder holder, boolean dragging) {
        if (holder == null) return;
        if (!dragging) {
            holder.cell.setScaleX(1.0f);
            holder.cell.setScaleY(1.0f);
            int position = holder.getAdapterPosition();
            if (position != RecyclerView.NO_POSITION && position < files.size()) {
                applyGroupBorder(holder, files.get(position));
                bindSelection(holder, files.get(position));
            }
            return;
        }
        if (lowMemory || availableHeap() < 30L * 1024L * 1024L) {
            holder.cell.setBackgroundColor(withAlpha(accentColor, 110));
            holder.cell.setScaleX(1.0f);
            holder.cell.setScaleY(1.0f);
        } else {
            holder.cell.setBackgroundColor(withAlpha(accentColor, 70));
            holder.cell.setScaleX(1.05f);
            holder.cell.setScaleY(1.05f);
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int placeholderColor(MediaFile file) {
        int hash = file.getPath() == null ? 0 : file.getPath().hashCode();
        int base = Math.abs(hash);
        int red = 28 + (base & 0x3F);
        int green = 28 + ((base >> 6) & 0x3F);
        int blue = 38 + ((base >> 12) & 0x3F);
        if (file.getType() == MediaFile.Type.VIDEO) {
            blue = Math.min(150, blue + 18);
        }
        return Color.rgb(red, green, blue);
    }

    private long availableHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    @Override
    public void onViewAttachedToWindow(@NonNull final ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.itemView.post(new Runnable() {
            @Override
            public void run() {
                int position = holder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= files.size()) return;
                MediaFile file = files.get(position);
                boolean keepDragBitmap = dragThumbnailActive
                        && file.getPath().equals(draggedThumbnailPath);
                if ((!keepDragBitmap && fastScrolling) || !holder.itemView.isShown()) return;
                int actualWidth = holder.itemView.getWidth();
                if (actualWidth > 0) {
                    ViewGroup.LayoutParams imageLp = holder.thumbnail.getLayoutParams();
                    imageLp.height = Math.max(dp(56), Math.round(actualWidth * 0.72f));
                    holder.thumbnail.setLayoutParams(imageLp);
                }
                int width = actualWidth > 0 ? actualWidth : Math.max(1, holder.thumbnail.getWidth());
                int height = actualWidth > 0
                        ? Math.max(dp(56), Math.round(actualWidth * 0.72f))
                        : Math.max(1, holder.thumbnail.getHeight());
                if (keepDragBitmap) {
                    loader.loadForDrag(file, holder.thumbnail, width, height);
                } else {
                    loader.loadVisible(file, holder.thumbnail, width, height);
                }
            }
        });
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        Object tag = holder.thumbnail.getTag();
        String path = tag == null ? null : tag.toString();
        boolean keepDragged = dragThumbnailActive && path != null
                && path.equals(draggedThumbnailPath);
        if (!keepDragged) {
            holder.thumbnail.setImageDrawable(null);
            if (path != null) loader.release(path);
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Object tag = holder.thumbnail.getTag();
        String path = tag == null ? null : tag.toString();
        boolean keepDragged = dragThumbnailActive && path != null
                && path.equals(draggedThumbnailPath);
        if (!keepDragged) {
            holder.thumbnail.setImageDrawable(null);
            if (path != null) loader.release(path);
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() { return files.size(); }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
