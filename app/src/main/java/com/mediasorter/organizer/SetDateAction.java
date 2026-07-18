package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;

public class SetDateAction extends Action {
    public String mode;  // "ABSOLUTE" or "OFFSET"
    public long value;   // timestamp for ABSOLUTE, days for OFFSET

    public SetDateAction(String mode, long value) {
        this.mode = mode != null ? mode : "OFFSET";
        this.value = value;
    }

    @Override
    public String describe() {
        if ("ABSOLUTE".equals(mode)) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return "Set date: " + sdf.format(new java.util.Date(value));
        } else {
            return "Set date: " + (value >= 0 ? "+" : "") + value + " days";
        }
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        File f = new File(file.getPath());
        if (!f.exists()) {
            log.add("File not found: " + file.getName());
            return false;
        }
        long newTime;
        if ("ABSOLUTE".equals(mode)) {
            newTime = value;
        } else {
            // OFFSET: add/subtract days from current lastModified
            long current = f.lastModified();
            newTime = current + (value * 24L * 60L * 60L * 1000L);
        }
        if (f.setLastModified(newTime)) {
            file.setDateAdded(newTime);
            log.add("Set date: " + file.getName() + " -> " + new java.util.Date(newTime));
            return true;
        } else {
            log.add("Failed to set date: " + file.getName());
            return false;
        }
    }
}
