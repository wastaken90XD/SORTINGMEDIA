package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public abstract class Action {
    public List<String> log = new ArrayList<>();

    public abstract String describe();
    public abstract boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus);

    public List<String> getLog() { return log; }
    public void clearLog() { log.clear(); }

    // ── Static factories ────────────────────────────────────────────────

    public static Action moveAction(String destFolder, Conflict conflict) {
        return new MoveAction(destFolder, conflict);
    }

    public static Action copyAction(String destFolder, Conflict conflict) {
        return new CopyAction(destFolder, conflict);
    }

    public static Action deleteAction(boolean useTrash, String trashFolder) {
        return new DeleteAction(useTrash, trashFolder);
    }

    public static Action tagAction(List<String> tagsToAdd, List<String> tagsToRemove) {
        return new TagAction(tagsToAdd, tagsToRemove);
    }

    public static Action statusAction(FileStatus.Status status, boolean clear) {
        return new StatusAction(status, clear);
    }

    public static Action renameAction(String pattern) {
        return new RenameAction(pattern);
    }

    public static Action setDateAction(String mode, long value) {
        return new SetDateAction(mode, value);
    }

    public static Action changeExtensionAction(String newExtension) {
        return new ChangeExtensionAction(newExtension);
    }

    public static Action affixAction(String position, String text) {
        return new AffixAction(position, text);
    }

    public static Action stripMetadataAction(boolean keepOrientation) {
        return new StripMetadataAction(keepOrientation);
    }

    public enum Conflict { SKIP, OVERWRITE, RENAME }
}

// ── Concrete implementations (public for UI access) ────────────────────

class MoveAction extends Action {
    public String destFolder;
    public Action.Conflict conflict;

    MoveAction(String destFolder, Action.Conflict conflict) {
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

class CopyAction extends Action {
    public String destFolder;
    public Action.Conflict conflict;

    CopyAction(String destFolder, Action.Conflict conflict) {
        this.destFolder = destFolder;
        this.conflict = conflict;
    }

    @Override
    public String describe() { return "Copy to " + destFolder; }

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
                    log.add("Copy skipped (exists): " + src.getName());
                    return false;
                case OVERWRITE:
                    if (!destFile.delete()) {
                        log.add("Failed to overwrite for copy: " + src.getName());
                        return false;
                    }
                    break;
                case RENAME:
                    destFile = findAvailable(destFile);
                    break;
            }
        }
        try {
            copyFile(src, destFile);
            log.add("Copied: " + src.getName() + " to " + destFile.getParent());
            return true;
        } catch (IOException e) {
            log.add("Copy failed: " + src.getName());
            return false;
        }
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dest).getChannel()) {
            in.transferTo(0, in.size(), out);
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

class DeleteAction extends Action {
    public boolean useTrash;
    public String trashFolder;

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
        if (useTrash && trashFolder != null && !trashFolder.isEmpty()) {
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

class TagAction extends Action {
    public List<String> tagsToAdd;
    public List<String> tagsToRemove;

    TagAction(List<String> add, List<String> remove) {
        this.tagsToAdd = add != null ? add : new ArrayList<String>();
        this.tagsToRemove = remove != null ? remove : new ArrayList<String>();
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("Tags: ");
        if (!tagsToAdd.isEmpty()) sb.append("+").append(tagsToAdd);
        if (!tagsToRemove.isEmpty()) sb.append(" -").append(tagsToRemove);
        return sb.toString();
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

class StatusAction extends Action {
    public FileStatus.Status status;
    public boolean clear;

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

class RenameAction extends Action {
    public String pattern;

    RenameAction(String pattern) { this.pattern = pattern; }

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

// ── New action types ───────────────────────────────────────────────────

class SetDateAction extends Action {
    public String mode;  // "ABSOLUTE" or "OFFSET"
    public long value;   // timestamp for ABSOLUTE, days for OFFSET

    SetDateAction(String mode, long value) {
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

class ChangeExtensionAction extends Action {
    public String newExtension;

    ChangeExtensionAction(String newExtension) {
        // Normalize: strip leading dot if present
        if (newExtension != null && newExtension.startsWith(".")) {
            newExtension = newExtension.substring(1);
        }
        this.newExtension = newExtension != null ? newExtension : "";
    }

    @Override
    public String describe() { return "Change extension to ." + newExtension; }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (newExtension.isEmpty()) {
            log.add("No extension specified");
            return false;
        }
        File original = new File(file.getPath());
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String newName = baseName + "." + newExtension;

        if (newName.equals(name)) {
            log.add("Extension unchanged: " + name);
            return true; // already has the target extension
        }

        File renamed = new File(original.getParent(), newName);
        if (renamed.exists()) {
            log.add("Cannot change extension, target exists: " + newName);
            return false;
        }
        if (original.renameTo(renamed)) {
            file.setPath(renamed.getAbsolutePath());
            log.add("Changed extension: " + name + " -> " + newName);
            return true;
        } else {
            log.add("Failed to change extension: " + name);
            return false;
        }
    }
}

class AffixAction extends Action {
    public String position; // "PREFIX" or "SUFFIX"
    public String text;

    AffixAction(String position, String text) {
        this.position = position != null ? position : "PREFIX";
        this.text = text != null ? text : "";
    }

    @Override
    public String describe() {
        return "PREFIX".equals(position) ? "Add prefix '" + text + "'" : "Add suffix '" + text + "'";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (text.isEmpty()) {
            log.add("No affix text specified");
            return false;
        }
        File original = new File(file.getPath());
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";

        String newName;
        if ("PREFIX".equals(position)) {
            newName = text + baseName + ext;
        } else {
            newName = baseName + text + ext;
        }

        if (newName.equals(name)) {
            log.add("Affix unchanged: " + name);
            return true;
        }

        File renamed = new File(original.getParent(), newName);
        if (renamed.exists()) {
            log.add("Cannot add affix, target exists: " + newName);
            return false;
        }
        if (original.renameTo(renamed)) {
            file.setPath(renamed.getAbsolutePath());
            log.add(("PREFIX".equals(position) ? "Prefixed: " : "Suffixed: ") + name + " -> " + newName);
            return true;
        } else {
            log.add("Failed to add affix: " + name);
            return false;
        }
    }
}

class StripMetadataAction extends Action {
    public boolean keepOrientation;

    StripMetadataAction(boolean keepOrientation) {
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
