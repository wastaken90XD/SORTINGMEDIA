package com.mediasorter.adapters;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Horizontal, plain-Android tag filter chips used by MainActivity. */
public class TagBarAdapter extends RecyclerView.Adapter<TagBarAdapter.ViewHolder> {

    public interface Listener {
        void onTagClicked(String tag);
        void onTagLongPressed(String tag, View anchor);
    }

    private final List<Tag> tags = new ArrayList<>();
    private final Map<String, Integer> fileCounts = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private Listener listener;
    private int accent = 0xFFE94560;

    public TagBarAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setListener(Listener value) { listener = value; }

    public void setData(List<Tag> values, Map<String, Integer> counts,
                        Set<String> activeTags) {
        tags.clear();
        if (values != null) tags.addAll(values);
        fileCounts.clear();
        if (counts != null) fileCounts.putAll(counts);
        active.clear();
        if (activeTags != null) active.addAll(activeTags);
        notifyDataSetChanged();
    }

    public List<Tag> getTags() { return new ArrayList<>(tags); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context context = parent.getContext();
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
        chip.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(32 * context.getResources().getDisplayMetrics().density));
        lp.setMargins(pad / 4, pad / 4, pad / 4, pad / 4);
        chip.setLayoutParams(lp);

        TextView name = new TextView(context);
        name.setTextColor(Color.WHITE);
        name.setTextSize(11f);
        chip.addView(name);
        TextView count = new TextView(context);
        count.setTextColor(accent);
        count.setTextSize(10f);
        count.setPadding(pad / 2, 0, 0, 0);
        chip.addView(count);
        return new ViewHolder(chip, name, count);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Tag tag = tags.get(position);
        final String name = tag.getName();
        holder.name.setText(name);
        Integer count = fileCounts.get(name);
        holder.count.setText(String.valueOf(count == null ? 0 : count));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(100f);
        background.setColor(active.contains(name) ? accent : 0xFF2A2A3E);
        holder.itemView.setBackground(background);
        holder.count.setTextColor(active.contains(name) ? Color.WHITE : accent);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (listener != null) listener.onTagClicked(name);
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                if (listener != null) listener.onTagLongPressed(name, view);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() { return tags.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;

        ViewHolder(View item, TextView name, TextView count) {
            super(item);
            this.name = name;
            this.count = count;
        }
    }
}
