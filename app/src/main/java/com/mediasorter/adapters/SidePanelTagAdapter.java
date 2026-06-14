package com.mediasorter.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.R;
import java.util.ArrayList;
import java.util.List;

public class SidePanelTagAdapter extends
        RecyclerView.Adapter<SidePanelTagAdapter.ViewHolder> {

    public interface OnTagClickListener {
        void onTagClick(String tagName, boolean applied);
    }

    private List<String> tags        = new ArrayList<>();
    private List<String> appliedTags = new ArrayList<>();
    private int          highlighted = -1;
    private OnTagClickListener listener;

    public void setTags(List<String> tags, List<String> appliedTags) {
        this.tags        = new ArrayList<>(tags);
        this.appliedTags = new ArrayList<>(appliedTags);
        notifyDataSetChanged();
    }

    public void setListener(OnTagClickListener l) { this.listener = l; }

    public void setHighlighted(int index) {
        int old     = highlighted;
        highlighted = index;
        if (old >= 0 && old < tags.size()) notifyItemChanged(old);
        if (highlighted >= 0 && highlighted < tags.size())
            notifyItemChanged(highlighted);
    }

    public void scrollHighlightUp() {
        if (tags.isEmpty()) return;
        setHighlighted(highlighted <= 0
            ? tags.size() - 1
            : highlighted - 1);
    }

    public void scrollHighlightDown() {
        if (tags.isEmpty()) return;
        setHighlighted(highlighted >= tags.size() - 1
            ? 0
            : highlighted + 1);
    }

    public String getHighlightedTag() {
        if (highlighted < 0 || highlighted >= tags.size()) return null;
        return tags.get(highlighted);
    }

    public boolean isHighlightedApplied() {
        String tag = getHighlightedTag();
        return tag != null && appliedTags.contains(tag);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_side_tag, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String  tag     = tags.get(position);
        boolean applied = appliedTags.contains(tag);
        boolean isHigh  = position == highlighted;

        holder.tagName.setText(tag);

        if (isHigh) {
            holder.itemView.setBackgroundColor(0xFF4A4A6E);
            holder.tagName.setTextColor(0xFFFFFFFF);
        } else if (applied) {
            holder.itemView.setBackgroundColor(0xFF1A1A2E);
            holder.tagName.setTextColor(0xFFE94560);
        } else {
            holder.itemView.setBackgroundColor(0x00000000);
            holder.tagName.setTextColor(0xFFCCCCCC);
        }

        // Applied indicator
        holder.appliedDot.setVisibility(applied ? View.VISIBLE : View.INVISIBLE);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onTagClick(tag, !applied);
        });
    }

    @Override
    public int getItemCount() { return tags.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tagName;
        View     appliedDot;

        ViewHolder(View v) {
            super(v);
            tagName    = v.findViewById(R.id.sideTagName);
            appliedDot = v.findViewById(R.id.sideTagDot);
        }
    }
}
