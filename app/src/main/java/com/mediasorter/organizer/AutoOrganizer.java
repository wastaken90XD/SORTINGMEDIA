package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.MetadataWriter;
import com.mediasorter.TagManager;
import com.mediasorter.features.RandomGenerator;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes rules and macros off the UI thread and resolves rule variables late. */
public class AutoOrganizer {
    private final Context context;
    private final TagManager tagManager;
    private final BatchRenameManager renamer;
    private final FileStatus fileStatus;
    private List<Rule> rules;
    private final List<String> log = new ArrayList<String>();
    private final Stack<List<UndoEntry>> undoStack = new Stack<List<UndoEntry>>();
    private volatile int activeIndex = -1;

    public AutoOrganizer(Context ctx, TagManager tm, BatchRenameManager rm, FileStatus fs) {
        context = ctx;
        tagManager = tm;
        renamer = rm;
        fileStatus = fs;
        rules = RuleSerializer.loadRules(ctx);
    }

    public void setRules(List<Rule> value) {
        rules = value == null ? new ArrayList<Rule>() : value;
        RuleSerializer.saveRules(context, rules);
    }

    public List<Rule> getRules() { return rules; }
    public void reloadRules() { rules = RuleSerializer.loadRules(context); }
    public void setActiveListIndex(int index) { activeIndex = index; }

    /**
     * Resolve all supported placeholders at execution time. This overload is
     * useful to rule previews/tests; execution uses the overload with index.
     */
    public String resolvePattern(String pattern, MediaFile file) {
        return resolvePattern(pattern, file, activeIndex);
    }

    public String resolvePattern(String pattern, MediaFile file, int index) {
        if (pattern == null || file == null) return pattern == null ? "" : pattern;
        String name = file.getName() == null ? "" : file.getName();
        int dot = name.lastIndexOf('.');
        String filename = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
        long timestamp = file.getDateAdded() > 0 ? file.getDateAdded()
                : new File(file.getPath()).lastModified();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(calendar.getTime());

        String resolved = pattern;
        resolved = resolved.replace("{filename}", filename);
        resolved = resolved.replace("{ext}", extension);
        resolved = resolved.replace("{date}", date);
        resolved = resolved.replace("{year}", String.format(Locale.US, "%04d", calendar.get(Calendar.YEAR)));
        resolved = resolved.replace("{month}", String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1));
        resolved = resolved.replace("{day}", String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH)));
        resolved = resolved.replace("{size}", String.valueOf(file.getSize()));
        resolved = resolved.replace("{index}", String.valueOf(index < 0 ? 0 : index));
        resolved = resolved.replace("{seq}", nextSequence(file));
        resolved = resolved.replace("{random}", RandomGenerator.randomSyllableTag());

        Matcher tagMatcher = Pattern.compile("\\{tag:(\\d+)\\}").matcher(resolved);
        StringBuffer buffer = new StringBuffer();
        while (tagMatcher.find()) {
            int tagIndex;
            try { tagIndex = Integer.parseInt(tagMatcher.group(1)); }
            catch (Exception ignored) { tagIndex = -1; }
            String value = tagIndex >= 0 && tagIndex < file.getTags().size()
                    ? file.getTags().get(tagIndex) : "";
            tagMatcher.appendReplacement(buffer, Matcher.quoteReplacement(value == null ? "" : value));
        }
        tagMatcher.appendTail(buffer);
        return buffer.toString();
    }

    private String nextSequence(MediaFile file) {
        String prefix = "seq";
        String name = file.getName() == null ? "" : file.getName();
        int marker = name.indexOf("_seq_");
        if (marker > 0) prefix = name.substring(0, marker);
        Set<String> existing = new HashSet<String>();
        if (tagManager != null) {
            for (com.mediasorter.models.Tag tag : tagManager.getAllTags()) existing.add(tag.getName());
        }
        return RandomGenerator.sequenceLabel(RandomGenerator.nextSequenceIndex(prefix, existing));
    }

    private boolean matchesRule(Rule rule, MediaFile file, int index) {
        if (rule == null || rule.conditions == null || rule.conditions.isEmpty()) return false;
        for (Condition condition : rule.conditions) {
            if (!resolveCondition(condition, file, index)) return false;
        }
        return true;
    }

    private boolean resolveCondition(Condition condition, MediaFile file, int index) {
        if (condition instanceof NameCondition) {
            NameCondition value = (NameCondition) condition;
            try {
                return new NameCondition(resolvePattern(value.pattern, file, index), value.type, value.negate)
                        .matches(file, fileStatus);
            } catch (Exception error) {
                log.add("Invalid name condition: " + error.getMessage());
                return false;
            }
        }
        if (condition instanceof TagCondition) {
            TagCondition value = (TagCondition) condition;
            List<String> add = new ArrayList<String>();
            for (String tag : value.tags) add.add(resolvePattern(tag, file, index));
            return new TagCondition(add, value.matchAny, value.negate).matches(file, fileStatus);
        }
        if (condition instanceof FolderCondition) {
            FolderCondition value = (FolderCondition) condition;
            return new FolderCondition(resolvePattern(value.folderPath, file, index), value.negate)
                    .matches(file, fileStatus);
        }
        return condition != null && condition.matches(file, fileStatus);
    }

    private Action resolveAction(Action action, MediaFile file, int index) {
        if (action instanceof MoveAction) {
            MoveAction value = (MoveAction) action;
            return new MoveAction(resolvePattern(value.destFolder, file, index), value.conflict);
        }
        if (action instanceof CopyAction) {
            CopyAction value = (CopyAction) action;
            return new CopyAction(resolvePattern(value.destFolder, file, index), value.conflict);
        }
        if (action instanceof DeleteAction) {
            DeleteAction value = (DeleteAction) action;
            return new DeleteAction(value.useTrash, resolvePattern(value.trashFolder, file, index));
        }
        if (action instanceof TagAction) {
            TagAction value = (TagAction) action;
            List<String> add = new ArrayList<String>();
            List<String> remove = new ArrayList<String>();
            for (String tag : value.tagsToAdd) add.add(resolvePattern(tag, file, index));
            for (String tag : value.tagsToRemove) remove.add(resolvePattern(tag, file, index));
            return new TagAction(add, remove);
        }
        if (action instanceof RenameAction) {
            return new RenameAction(resolvePattern(((RenameAction) action).pattern, file, index));
        }
        if (action instanceof ChangeExtensionAction) {
            return new ChangeExtensionAction(resolvePattern(
                    ((ChangeExtensionAction) action).newExtension, file, index));
        }
        if (action instanceof AffixAction) {
            AffixAction value = (AffixAction) action;
            return new AffixAction(value.position, resolvePattern(value.text, file, index));
        }
        if (action instanceof MacroCompositeAction) {
            List<Action> resolved = new ArrayList<Action>();
            for (Action step : ((MacroCompositeAction) action).getActions()) {
                resolved.add(resolveAction(step, file, index));
            }
            return new MacroCompositeAction(resolved);
        }
        return action;
    }

    /** Apply all enabled rules; callers run this on their background executor. */
    public int applyTo(List<MediaFile> files) {
        log.clear();
        if (rules == null || files == null || files.isEmpty()) return 0;
        List<UndoEntry> batch = new ArrayList<UndoEntry>();
        int affected = 0;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            for (int i = 0; i < files.size(); i++) {
                MediaFile file = files.get(i);
                if (file == null || !matchesRule(rule, file, i)) continue;
                activeIndex = i;
                UndoEntry entry = captureState(file);
                Action action = resolveAction(rule.action, file, i);
                boolean okay = action != null && action.execute(file, context, tagManager, renamer, fileStatus);
                if (okay) {
                    checkStripOnMove(rule, file);
                    affected++;
                    completeEntry(entry, file, action);
                    batch.add(entry);
                    log.add(rule.name + " applied to " + safeName(file));
                } else {
                    log.add(rule.name + " failed on " + safeName(file));
                }
            }
        }
        if (!batch.isEmpty()) {
            undoStack.push(batch);
            trimUndoStack();
        }
        return affected;
    }

    private String safeName(MediaFile file) {
        return file.getName() != null ? file.getName() : file.getPath();
    }

    private void completeEntry(UndoEntry entry, MediaFile file, Action action) {
        entry.newPath = file.getPath();
        entry.newTags = new ArrayList<String>(file.getTags());
        entry.newStatus = fileStatus.getStatus(file.getPath());
        entry.action = action;
        if (!entry.newPath.equals(entry.originalPath)) {
            if (entry.newPath.contains("/.trash/")) {
                entry.wasTrashed = true;
                entry.trashPath = entry.newPath;
            } else entry.wasMoved = true;
        }
    }

    private void trimUndoStack() {
        int maxUndo = 20;
        try {
            maxUndo = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                    .getInt("max_undo_history", 20);
        } catch (Exception ignored) {}
        maxUndo = Math.max(1, Math.min(100, maxUndo));
        while (undoStack.size() > maxUndo) undoStack.remove(0);
    }

    /** Apply the first matching auto-apply rule to a status-triggered file. */
    public boolean applyToSingle(MediaFile file) {
        if (rules == null || file == null) return false;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled || !rule.autoApply) continue;
            if (matchesRule(rule, file, activeIndex)) {
                Action action = resolveAction(rule.action, file, activeIndex);
                boolean okay = action != null && action.execute(file, context, tagManager, renamer, fileStatus);
                if (okay) {
                    checkStripOnMove(rule, file);
                    log.add(rule.name + " applied to " + safeName(file));
                } else log.add(rule.name + " failed on " + safeName(file));
                return okay;
            }
        }
        return false;
    }

    private void checkStripOnMove(Rule rule, MediaFile file) {
        if (rule == null || file == null) return;
        boolean stripOnMove = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                .getBoolean("strip_on_move", false);
        if (stripOnMove && rule.action instanceof MoveAction) {
            new StripMetadataAction(true).execute(file, context, tagManager, renamer, fileStatus);
        }
    }

    public static class PreviewResult {
        public int matchedFiles;
        public List<PreviewEntry> entries = new ArrayList<PreviewEntry>();
    }

    public static class PreviewEntry {
        public String ruleName;
        public String fileName;
        public String actionDescription;
        PreviewEntry(String rule, String file, String action) {
            ruleName = rule;
            fileName = file;
            actionDescription = action;
        }
    }

    public PreviewResult preview(List<MediaFile> files) {
        PreviewResult result = new PreviewResult();
        if (rules == null || files == null || files.isEmpty()) return result;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            for (int i = 0; i < files.size(); i++) {
                MediaFile file = files.get(i);
                if (file != null && matchesRule(rule, file, i)) {
                    result.matchedFiles++;
                    result.entries.add(new PreviewEntry(
                            rule.name == null ? "Unnamed" : rule.name,
                            safeName(file), rule.action == null ? "no action" : rule.action.describe()));
                }
            }
        }
        return result;
    }

    public static class UndoEntry {
        public String originalPath;
        public String newPath;
        public List<String> oldTags;
        public List<String> newTags;
        public FileStatus.Status oldStatus;
        public FileStatus.Status newStatus;
        public boolean wasMoved;
        public boolean wasCopied;
        public boolean wasTrashed;
        public String trashPath;
        public Action action;
    }

    private UndoEntry captureState(MediaFile file) {
        UndoEntry entry = new UndoEntry();
        entry.originalPath = file.getPath();
        entry.oldTags = new ArrayList<String>(file.getTags());
        entry.oldStatus = fileStatus.getStatus(file.getPath());
        return entry;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }

    public int undoLastRun() {
        if (undoStack.isEmpty()) return 0;
        List<UndoEntry> batch = undoStack.pop();
        int restored = 0;
        // Reverse the batch too: a macro/rule batch is one atomic undo entry.
        for (int i = batch.size() - 1; i >= 0; i--) {
            UndoEntry entry = batch.get(i);
            try {
                if (entry.action instanceof MacroCompositeAction) {
                    ((MacroCompositeAction) entry.action).undoCaptured(entry.originalPath,
                            entry.newPath, context, tagManager, renamer, fileStatus);
                }
                if (entry.wasMoved && entry.newPath != null
                        && !entry.newPath.equals(entry.originalPath)) {
                    File current = new File(entry.newPath);
                    File original = new File(entry.originalPath);
                    if (current.exists() && current.renameTo(original)) restored++;
                }
                if (entry.wasTrashed && entry.trashPath != null) {
                    File trashed = new File(entry.trashPath);
                    File original = new File(entry.originalPath);
                    if (trashed.exists() && !original.exists() && trashed.renameTo(original)) restored++;
                }
                if (entry.wasCopied && entry.newPath != null) {
                    File copy = new File(entry.newPath);
                    if (copy.exists() && copy.delete()) restored++;
                }
                if (entry.oldTags != null) {
                    String restorePath = entry.originalPath;
                    File file = new File(restorePath);
                    if (file.exists()) {
                        MetadataWriter.writeTags(restorePath, entry.oldTags);
                        restored++;
                    }
                }
                restoreStatus(entry.originalPath, entry.oldStatus);
            } catch (Exception error) {
                log.add("Undo error: " + error.getMessage());
            }
        }
        log.add("Undo: reverted " + restored + " operations");
        return restored;
    }

    private void restoreStatus(String path, FileStatus.Status status) {
        if (path == null) return;
        if (status == null || status == FileStatus.Status.NONE) fileStatus.clearStatus(path);
        else if (status == FileStatus.Status.SKIPPED) fileStatus.setSkipped(path);
        else if (status == FileStatus.Status.FLAGGED) fileStatus.setFlagged(path);
        else if (status == FileStatus.Status.DONE) fileStatus.setDone(path);
    }

    /** Execute a synthetic rule such as a macro on the supplied selection. */
    public int execute(Rule rule, List<MediaFile> files) {
        if (rule == null || files == null || files.isEmpty()) return -1;
        List<UndoEntry> batch = new ArrayList<UndoEntry>();
        int failedStep = -1;
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (file == null) continue;
            activeIndex = i;
            UndoEntry entry = captureState(file);
            Action action = resolveAction(rule.action, file, i);
            boolean okay = false;
            if (action instanceof MacroCompositeAction) {
                MacroCompositeAction composite = (MacroCompositeAction) action;
                okay = composite.execute(file, context, tagManager, renamer, fileStatus);
                if (!okay) failedStep = composite.getFailedStepIndex();
            } else if (action != null) {
                okay = action.execute(file, context, tagManager, renamer, fileStatus);
            }
            if (okay) {
                completeEntry(entry, file, action);
                batch.add(entry);
                log.add(rule.name + " applied to " + safeName(file));
            } else {
                if (failedStep < 0) failedStep = 0;
                log.add(rule.name + " failed on " + safeName(file));
                break;
            }
        }
        if (!batch.isEmpty()) undoStack.push(batch);
        return failedStep;
    }

    public List<String> getLog() { return log; }
}
