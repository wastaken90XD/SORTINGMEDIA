package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.MetadataWriter;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A macro is one atomic action, with reverse-order undo semantics. */
public class MacroCompositeAction extends Action {
    private final List<Action> actions;
    private int failedStepIndex = -1;
    private final Map<String, Snapshot> snapshots = new HashMap<String, Snapshot>();

    private static class Snapshot {
        String path;
        List<String> tags;
        FileStatus.Status status;
        Snapshot(MediaFile file, FileStatus fileStatus) {
            path = file.getPath();
            tags = new ArrayList<String>(file.getTags());
            status = fileStatus == null ? FileStatus.Status.NONE
                    : fileStatus.getStatus(file.getPath());
        }
    }

    public MacroCompositeAction(List<Action> values) {
        actions = values == null ? new ArrayList<Action>() : values;
    }

    public List<Action> getActions() { return actions; }
    public int getFailedStepIndex() { return failedStepIndex; }

    @Override public String describe() {
        return "Composite macro of " + actions.size() + " steps";
    }

    @Override
    public boolean execute(MediaFile file, Context context, TagManager tagManager,
                           BatchRenameManager renamer, FileStatus fileStatus) {
        failedStepIndex = -1;
        if (file == null || actions.isEmpty()) return false;
        Snapshot snapshot = new Snapshot(file, fileStatus);
        snapshots.put(snapshot.path, snapshot);
        int completed = 0;
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            if (action == null) continue;
            if (!action.execute(file, context, tagManager, renamer, fileStatus)) {
                failedStepIndex = i;
                // A partially executed macro is rolled back immediately, in
                // reverse step order, so a later failed step cannot leave a
                // half-applied selection behind.
                undoActions(file, context, tagManager, renamer, fileStatus, completed);
                restoreSnapshot(snapshot, file, context, fileStatus);
                return false;
            }
            completed++;
        }
        return completed > 0;
    }

    private void undoActions(MediaFile file, Context context, TagManager tagManager,
                              BatchRenameManager renamer, FileStatus fileStatus,
                              int completed) {
        int seen = 0;
        for (int i = actions.size() - 1; i >= 0 && seen < completed; i--) {
            Action action = actions.get(i);
            if (action != null) {
                action.undo(file, context, tagManager, renamer, fileStatus);
                seen++;
            }
        }
    }

    @Override
    public boolean undo(MediaFile file, Context context, TagManager tagManager,
                        BatchRenameManager renamer, FileStatus fileStatus) {
        if (file == null) return false;
        boolean okay = true;
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);
            if (action != null && !action.undo(file, context, tagManager, renamer, fileStatus)) {
                // A child without an explicit inverse is still followed by
                // the organizer snapshot restore; keep walking in reverse.
                okay = false;
            }
        }
        return okay;
    }

    /** Called by AutoOrganizer for the one undo entry representing the macro. */
    public boolean undoCaptured(String originalPath, String currentPath,
                                Context context, TagManager tagManager,
                                BatchRenameManager renamer, FileStatus fileStatus) {
        Snapshot snapshot = snapshots.get(originalPath);
        MediaFile current = new MediaFile(
                currentPath == null ? originalPath : currentPath, 0L);
        if (snapshot == null) return false;
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);
            if (action != null) action.undo(current, context, tagManager, renamer, fileStatus);
        }
        restoreSnapshot(snapshot, current, context, fileStatus);
        return true;
    }

    private void restoreSnapshot(Snapshot snapshot, MediaFile file,
                                 Context context, FileStatus fileStatus) {
        try {
            File current = new File(file.getPath());
            File original = new File(snapshot.path);
            if (!current.getAbsolutePath().equals(original.getAbsolutePath())
                    && current.exists() && !original.exists()) {
                current.renameTo(original);
            }
            file.setPath(snapshot.path);
            file.setTags(snapshot.tags);
            if (new File(snapshot.path).exists()) {
                MetadataWriter.writeTags(snapshot.path, snapshot.tags);
            }
            if (fileStatus != null) {
                switch (snapshot.status) {
                    case SKIPPED: fileStatus.setSkipped(snapshot.path); break;
                    case FLAGGED: fileStatus.setFlagged(snapshot.path); break;
                    case DONE: fileStatus.setDone(snapshot.path); break;
                    case NONE:
                    default: fileStatus.clearStatus(snapshot.path); break;
                }
            }
        } catch (Exception error) {
            log.add("Macro undo failed: " + error.getMessage());
        }
    }
}
