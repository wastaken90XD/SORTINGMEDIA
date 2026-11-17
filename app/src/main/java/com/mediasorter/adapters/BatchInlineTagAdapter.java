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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Filterable tag chips used by the Batch tagger's inline bar. */
public final class BatchInlineTagAdapter extends RecyclerView.Adapter<BatchInlineTagAdapter.Holder> {
    public interface Listener { void onTagClicked(String tag); }

    private final List<Tag> all = new ArrayList<Tag>();
    private final List<Tag> visible = new ArrayList<Tag>();
    private final Set<String> suggested = new HashSet<String>();
    private Listener listener;

    public BatchInlineTagAdapter(Listener value) { listener = value; }

    public void setData(List<Tag> tags, Set<String> suggestions) {
        all.clear();
        if (tags != null) all.addAll(tags);
        suggested.clear();
        if (suggestions != null) suggested.addAll(suggestions);
        visible.clear();
        visible.addAll(all);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        visible.clear();
        for (Tag tag : all) {
            if (tag == null) continue;
            if (needle.isEmpty() || tag.getName().toLowerCase(java.util.Locale.US).contains(needle)) {
                visible.add(tag);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context context = parent.getContext();
        TextView chip = new TextView(context);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(11f);
        chip.setGravity(Gravity.CENTER);
        int pad = Math.round(10 * context.getResources().getDisplayMetrics().density);
        chip.setPadding(pad, 0, pad, 0);
        chip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(34 * context.getResources().getDisplayMetrics().density)));
        return new Holder(chip);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Tag tag = visible.get(position);
        final String name = tag.getName();
        boolean isSuggested = suggested.contains(name);
        holder.chip.setText(isSuggested ? "★ " + name : name);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(100f);
        background.setColor(isSuggested ? 0xFF6A5A20 : 0xFF2A2A3E);
        holder.chip.setBackground(background);
        holder.chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (listener != null) listener.onTagClicked(name); }
        });
    }

    @Override public int getItemCount() { return visible.size(); }

    public static final class Holder extends RecyclerView.ViewHolder {
        final TextView chip;
        Holder(TextView value) { super(value); chip = value; }
    }
}
