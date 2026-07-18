package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
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
