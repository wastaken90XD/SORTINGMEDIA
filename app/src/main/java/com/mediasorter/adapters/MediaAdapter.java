package com.mediasorter.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.mediasorter.R;
import com.mediasorter.ThumbnailLoader;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(MediaFile file);
    }

    private List<MediaFile>     files    = new ArrayList<>();
    private OnFileClickListener listener;
    private ThumbnailLoader     loader;
    private String              selectedPath = null;

    public MediaAdapter(ThumbnailLoader loader, OnFileClickListener listener) {
        this.loader   = loader;
        this.listener = listener;
    }

    public void setFiles(List<MediaFile> files) {
        this.files = new ArrayList<>(files);
        notifyDataSetChanged();
    }

    public void addFile(MediaFile file) {
        files.add(file);
        notifyItemInserted(files.size() - 1);
    }

    public void removeFile(String path) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(path)) {
                files.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public void updateFile(MediaFile file) {
    for (int i = 0; i < files.size(); i++) {
        if (files.get(i).getPath().equals(file.getPath())) {
            // Replace the whole object not just notify
            files.set(i, file);
            notifyItemChanged(i);
            return;
        }
    }
}
    
    public String getSelectedPath() {
    return selectedPath;
}
    public void setSelected(String path) {
        String old = selectedPath;
        selectedPath = path;
        // Refresh old and new selection
        for (int i = 0; i < files.size(); i++) {
            String p = files.get(i).getPath();
            if (p.equals(old) || p.equals(path)) notifyItemChanged(i);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_media_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MediaFile file = files.get(position);

        holder.fileName.setText(file.getName());
        holder.fileDetails.setText(file.getFormattedSize()
            + "  •  " + file.getType().name().toLowerCase());

        String tags = file.getTags().isEmpty()
            ? "No tags"
            : String.join("  ", file.getTags());
        holder.fileTags.setText(tags);

        // Highlight selected
        holder.itemView.setBackgroundColor(
            file.getPath().equals(selectedPath) ? 0xFF1A1A4E : 0x00000000
        );

        loader.load(file, holder.thumbnail);

        holder.itemView.setOnClickListener(v -> {
            setSelected(file.getPath());
            if (listener != null) listener.onFileClick(file);
        });
    }

    @Override
    public int getItemCount() { return files.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnail;
        TextView  fileName;
        TextView  fileDetails;
        TextView  fileTags;

        ViewHolder(View v) {
            super(v);
            thumbnail   = v.findViewById(R.id.thumbnail);
            fileName    = v.findViewById(R.id.fileName);
            fileDetails = v.findViewById(R.id.fileDetails);
            fileTags    = v.findViewById(R.id.fileTags);
        }
    }
}
