package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;

public class MoveAction extends Action {
    public String destFolder;
    public Action.Conflict conflict;

    public MoveAction(String destFolder, Action.Conflict conflict) {
        this.destFolder = destFolder;
        this.conflict = conflict;
    }

    @Override
    public String describe() { return "Move to " + destFolder; }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        File src = new File(file.getPath());
        File destDir = new File(destFolder);
        if (!destDir.exists()) destDir.mkdirs();
        File destFile = new File(destDir, src.getName());
        if (destFile.exists()) {
            switch (conflict) {
                case SKIP:
                    log.add("Skipped (exists): " + src.getName());
                    return false;
                case OVERWRITE:
                    if (!destFile.delete()) {
                        log.add("Failed to overwrite: " + src.getName());
                        return false;
                    }
                    break;
                case RENAME:
                    destFile = findAvailable(destFile);
                    break;
            }
        }
        if (src.renameTo(destFile)) {
            file.setPath(destFile.getAbsolutePath());
            log.add("Moved: " + src.getName() + " -> " + destFile.getParent());
            return true;
        } else {
            log.add("Failed to move: " + src.getName());
            return false;
        }
    }

    private File findAvailable(File f) {
        String base = f.getName();
        int dot = base.lastIndexOf('.');
        String name = dot > 0 ? base.substring(0, dot) : base;
        String ext  = dot > 0 ? base.substring(dot) : "";
        int i = 1;
        File candidate;
        do {
            candidate = new File(f.getParent(), name + "(" + i + ")" + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }
}
