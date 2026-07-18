package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;

public class StripMetadataAction extends Action {
    public boolean keepOrientation;

    public StripMetadataAction(boolean keepOrientation) {
        this.keepOrientation = keepOrientation;
    }

    @Override
    public String describe() {
        return keepOrientation ? "Strip metadata (keep orientation)" : "Strip all metadata";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        String path = file.getPath();
        String lower = path.toLowerCase();
        boolean ok;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            ok = com.mediasorter.MetadataWriter.stripJpegMetadata(path, keepOrientation);
        } else if (lower.endsWith(".png")) {
            ok = com.mediasorter.MetadataWriter.stripPngMetadata(path);
        } else {
            log.add("Unsupported format for strip: " + file.getName());
            return false;
        }
        if (ok) {
            // Clear in-memory tags since metadata was stripped
            if (file.getTags() != null) file.getTags().clear();
            log.add("Stripped metadata: " + file.getName());
        } else {
            log.add("Failed to strip metadata: " + file.getName());
        }
        return ok;
    }
}
