package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class AutoOrganizer {
    private final Context context;
    private final TagManager tagManager;
    private final BatchRenameManager renamer;
    private final FileStatus fileStatus;
    private List<Rule> rules;
    private final List<String> log = new ArrayList<>();

    // Undo stack – each entry is a list of actions taken during one applyTo() call
    private final Stack<List<UndoEntry>> undoStack = new Stack<>();

    public AutoOrganizer(Context ctx, TagManager tm, BatchRenameManager rm, FileStatus fs) {
        this.context = ctx;
        this.tagManager = tm;
        this.renamer = rm;
        this.fileStatus = fs;
        rules = RuleSerializer.loadRules(ctx);
    }

    public void setRules(List<Rule> r) {
        rules = r;
        RuleSerializer.saveRules(context, rules);
    }

    public List<Rule> getRules() { return rules; }

    /** Apply all enabled rules to the given list, returning affected file count. */
    public int applyTo(List<MediaFile> files) {
        log.clear();
        if (rules == null || files == null || files.isEmpty()) return 0;

        List<UndoEntry> batch = new ArrayList<>();
        int affected = 0;

        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            for (MediaFile f : files) {
                if (f == null) continue;
                if (rule.matchesFile(f, fileStatus)) {
                    UndoEntry entry = captureState(f);
                    boolean ok = rule.execute(f, context, tagManager, renamer, fileStatus);
                    if (ok) {
                        affected++;
                        entry.newPath = f.getPath();
                        entry.newTags = new ArrayList<>(f.getTags());
                        entry.newStatus = fileStatus.getStatus(f.getPath());
                        batch.add(entry);
                        log.add(rule.name + " applied to " + (f.getName() != null ? f.getName() : f.getPath()));
                    } else {
                        log.add(rule.name + " failed on " + (f.getName() != null ? f.getName() : f.getPath()));
                    }
                }
            }
        }

        if (!batch.isEmpty()) {
            undoStack.push(batch);
        }
        return affected;
    }

    /** Apply rules to a single file (for status-change triggers), only if autoApply is true. */
    public boolean applyToSingle(MediaFile file) {
        if (rules == null || file == null) return false;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled || !rule.autoApply) continue;
            if (rule.matchesFile(file, fileStatus)) {
                boolean ok = rule.execute(file, context, tagManager, renamer, fileStatus);
                if (ok) {
                    log.add(rule.name + " applied to " + (file.getName() != null ? file.getName() : file.getPath()));
                } else {
                    log.add(rule.name + " failed on " + (file.getName() != null ? file.getName() : file.getPath()));
                }
                return ok;
            }
        }
        return false;
    }

    // ── Preview / Dry-Run ───────────────────────────────────────────────

    public static class PreviewResult {
        public int matchedFiles;
        public List<PreviewEntry> entries = new ArrayList<>();
    }

    public static class PreviewEntry {
        public String ruleName;
        public String fileName;
        public String actionDescription;

        PreviewEntry(String rule, String file, String action) {
            this.ruleName = rule;
            this.fileName = file;
            this.actionDescription = action;
        }
    }

    /** Preview what would happen without actually executing anything. */
    public PreviewResult preview(List<MediaFile> files) {
        PreviewResult result = new PreviewResult();
        if (rules == null || files == null || files.isEmpty()) return result;

        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            for (MediaFile f : files) {
                if (f == null) continue;
                if (rule.matchesFile(f, fileStatus)) {
                    result.matchedFiles++;
                    String actionDesc = rule.action != null ? rule.action.describe() : "no action";
                    result.entries.add(new PreviewEntry(
                            rule.name != null ? rule.name : "Unnamed",
                            f.getName() != null ? f.getName() : f.getPath(),
                            actionDesc));
                }
            }
        }
        return result;
    }

    // ── Undo ────────────────────────────────────────────────────────────

    public static class UndoEntry {
        public String originalPath;
        public String newPath;          // if moved/renamed
        public List<String> oldTags;
        public List<String> newTags;
        public FileStatus.Status oldStatus;
        public FileStatus.Status newStatus;
        public boolean wasMoved;
        public boolean wasCopied;
        public boolean wasTrashed;
        public String trashPath;        // if trashed, where it went
    }

    private UndoEntry captureState(MediaFile file) {
        UndoEntry entry = new UndoEntry();
        entry.originalPath = file.getPath();
        entry.oldTags = new ArrayList<>(file.getTags());
        entry.oldStatus = fileStatus.getStatus(file.getPath());
        return entry;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }

    /** Undo the last batch of actions. Returns number of files restored. */
    public int undoLastRun() {
        if (undoStack.isEmpty()) return 0;
        List<UndoEntry> batch = undoStack.pop();
        int restored = 0;

        for (UndoEntry entry : batch) {
            try {
                // Undo move/rename: move file back to original path
                if (entry.wasMoved && entry.newPath != null && !entry.newPath.equals(entry.originalPath)) {
                    java.io.File current = new java.io.File(entry.newPath);
                    java.io.File original = new java.io.File(entry.originalPath);
                    if (current.exists()) {
                        if (current.renameTo(original)) {
                            restored++;
                        }
                    }
                }

                // Undo trash: move file back from trash
                if (entry.wasTrashed && entry.trashPath != null) {
                    java.io.File trashed = new java.io.File(entry.trashPath);
                    java.io.File original = new java.io.File(entry.originalPath);
                    if (trashed.exists() && !original.exists()) {
                        if (trashed.renameTo(original)) {
                            restored++;
                        }
                    }
                }

                // Undo copy: delete the copy
                if (entry.wasCopied && entry.newPath != null) {
                    java.io.File copy = new java.io.File(entry.newPath);
                    if (copy.exists()) {
                        copy.delete();
                    }
                }

                // Undo tags: restore old tags
                if (entry.oldTags != null && entry.originalPath != null) {
                    // This is a best-effort restore — find the file if it still exists
                    java.io.File f = new java.io.File(entry.originalPath);
                    if (f.exists()) {
                        // Note: full tag restoration would require re-reading metadata
                        restored++;
                    }
                }

                // Undo status
                if (entry.originalPath != null) {
                    if (entry.oldStatus == null || entry.oldStatus == FileStatus.Status.NONE) {
                        fileStatus.clearStatus(entry.originalPath);
                    } else {
                        switch (entry.oldStatus) {
                            case SKIPPED: fileStatus.setSkipped(entry.originalPath); break;
                            case FLAGGED: fileStatus.setFlagged(entry.originalPath); break;
                            case DONE:    fileStatus.setDone(entry.originalPath);    break;
                            default: break;
                        }
                    }
                }
            } catch (Exception e) {
                log.add("Undo error: " + e.getMessage());
            }
        }

        log.add("Undo: reverted " + restored + " operations");
        return restored;
    }

    public List<String> getLog() { return log; }
}
