package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class RenameAction extends Action {
    public String pattern;

    public RenameAction(String pattern) { this.pattern = pattern; }

    @Override
    public String describe() { return "Rename: " + pattern; }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        BatchRenameManager temp = new BatchRenameManager();
        temp.setPattern(pattern);
        List<MediaFile> single = new ArrayList<>();
        single.add(file);
        List<BatchRenameManager.RenamePreview> previews = temp.preview(single);
        if (previews.isEmpty()) return false;
        BatchRenameManager.RenamePreview preview = previews.get(0);
        if (preview.hasConflict) {
            log.add("Rename conflict: " + file.getName());
            return false;
        }
        File original = new File(file.getPath());
        File renamed = new File(original.getParent(), preview.newName);
        if (original.renameTo(renamed)) {
            file.setPath(renamed.getAbsolutePath());
            log.add("Renamed: " + preview.originalName + " -> " + preview.newName);
            return true;
        } else {
            log.add("Rename failed: " + file.getName());
            return false;
        }
    }
}
