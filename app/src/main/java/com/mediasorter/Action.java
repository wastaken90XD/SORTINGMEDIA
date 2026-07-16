package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public abstract class Action {
    protected List<String> log = new ArrayList<>();

    public abstract String describe();
    public abstract boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus);

    public List<String> getLog() { return log; }
    public void clearLog() { log.clear(); }
}

// --- MoveAction ---
public class MoveAction extends Action {
    enum Conflict { SKIP, OVERWRITE, RENAME }
    final String destFolder;
    final Conflict conflict;

    MoveAction(String destFolder, Conflict conflict) {
        this.destFolder = destFolder;
        this.conflict = conflict;
    }

    @Override
    public String describe() {
        return "Move to " + destFolder;
    }

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
            file.setPath(destFile.getAbsolutePath()); // keep MediaFile in sync
            log.add("Moved: " + src.getName() + " → " + destFile.getParent());
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

// --- CopyAction ---
public class CopyAction extends Action {
    // similar to MoveAction but using FileChannel for copy (not implemented here for brevity)
    // You can add later; for now we'll skip to keep answer concise.
    @Override
    public String describe() { return "Copy"; }
    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        log.add("Copy not yet implemented");
        return false;
    }
}

// --- DeleteAction ---
public class DeleteAction extends Action {
    final boolean useTrash;
    final String trashFolder;

    DeleteAction(boolean useTrash, String trashFolder) {
        this.useTrash = useTrash;
        this.trashFolder = trashFolder;
    }

    @Override
    public String describe() {
        return useTrash ? "Move to trash" : "Delete";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        File src = new File(file.getPath());
        if (useTrash && trashFolder != null) {
            File destDir = new File(trashFolder);
            if (!destDir.exists()) destDir.mkdirs();
            File destFile = new File(destDir, src.getName());
            if (src.renameTo(destFile)) {
                log.add("Trashed: " + src.getName());
                return true;
            } else {
                log.add("Trash failed: " + src.getName());
                return false;
            }
        } else {
            if (src.delete()) {
                log.add("Deleted: " + src.getName());
                return true;
            } else {
                log.add("Delete failed: " + src.getName());
                return false;
            }
        }
    }
}

// --- TagAction ---
public class TagAction extends Action {
    final List<String> tagsToAdd;
    final List<String> tagsToRemove;

    TagAction(List<String> add, List<String> remove) {
        this.tagsToAdd = add != null ? add : new ArrayList<String>();
        this.tagsToRemove = remove != null ? remove : new ArrayList<String>();
    }

    @Override
    public String describe() {
        return "Change tags";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        for (String t : tagsToAdd)    tagManager.applyTag(file, t);
        for (String t : tagsToRemove) tagManager.removeTag(file, t);
        log.add("Changed tags for: " + file.getName());
        return true;
    }
}

// --- StatusAction ---
public class StatusAction extends Action {
    final FileStatus.Status status;
    final boolean clear;

    StatusAction(FileStatus.Status status, boolean clear) {
        this.status = status;
        this.clear = clear;
    }

    @Override
    public String describe() {
        return clear ? "Clear status" : "Set status: " + status.name();
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (fileStatus == null) return false;
        String path = file.getPath();
        if (clear) {
            fileStatus.clearStatus(path);
        } else {
            switch (status) {
                case SKIPPED: fileStatus.setSkipped(path); break;
                case FLAGGED: fileStatus.setFlagged(path); break;
                case DONE:    fileStatus.setDone(path);    break;
            }
        }
        return true;
    }
}

// --- RenameAction ---
public class RenameAction extends Action {
    final String pattern;   // uses BatchRenameManager pattern syntax

    RenameAction(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public String describe() {
        return "Rename: " + pattern;
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        // We'll use a temporary BatchRenameManager to generate the new name
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
            log.add("Renamed: " + preview.originalName + " → " + preview.newName);
            return true;
        } else {
            log.add("Rename failed: " + file.getName());
            return false;
        }
    }
}
