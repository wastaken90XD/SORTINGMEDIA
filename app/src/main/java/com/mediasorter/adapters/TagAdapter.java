package com.mediasorter.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.R;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.List;

public class TagAdapter extends RecyclerView.Adapter<TagAdapter.ViewHolder> {

    public interface OnTagToggleListener {
        void onTagToggle(String tagName, boolean applied);
    }

    private List<Tag>         tags     = new ArrayList<>();
    private MediaFile         current  = null;
    private OnTagToggleListener listener;

    public TagAdapter(OnTagToggleListener listener) {
        this.listener = listener;
    }

    public void setTags(List<Tag> tags) {
        this.tags = new ArrayList<>(tags);
        notifyDataSetChanged();
    }

    public void setCurrentFile(MediaFile file) {
        this.current = file;
        notifyDataSetChanged();
    }

    public void addTag(Tag tag) {
        tags.add(0, tag);
        notifyItemInserted(0);
    }

    public void removeTag(String name) {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).getName().equals(name)) {
                tags.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void filter(String query) {
        // Filtering handled externally via TagManager.searchTags()
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_tag, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Tag tag = tags.get(position);

        holder.tagName.setText(tag.getName());
        holder.tagCount.setText(String.valueOf(tag.getUsageCount()));

        boolean applied = current != null && current.hasTag(tag.getName());
        holder.tagCheck.setOnCheckedChangeListener(null);
        holder.tagCheck.setChecked(applied);

        holder.tagCheck.setOnCheckedChangeListener((btn, checked) -> {
            if (listener != null) listener.onTagToggle(tag.getName(), checked);
        });

        holder.itemView.setOnClickListener(v -> {
            holder.tagCheck.toggle();
        });
    }

    @Override
    public int getItemCount() { return tags.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tagName;
        TextView tagCount;
        CheckBox tagCheck;

        ViewHolder(View v) {
            super(v);
            tagName  = v.findViewById(R.id.tagName);
            tagCount = v.findViewById(R.id.tagCount);
            tagCheck = v.findViewById(R.id.tagCheck);
        }
    }
}
