package com.mediasorter.organizer;

import android.content.Context;
import android.util.Log;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private volatile int runIndex;
    private final long sessionStartMillis = System.currentTimeMillis();
    private volatile boolean lastUndoPartial;

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
        return resolvePattern(pattern, file, index, -1);
    }

    /** Resolver overload used by a batch run with its allocated {seq} value. */
    public String resolvePattern(String pattern, MediaFile file, int index,
                                 int sequenceCounter) {
        if (file == null) {
            Log.w("AutoOrganizer", "Cannot resolve rule variables without a MediaFile");
            return replaceUnknownPlaceholders(pattern == null ? "" : pattern);
        }
        if (pattern == null) return "";

        String name = file.getName() == null ? "" : file.getName();
        int dot = name.lastIndexOf('.');
        String filename = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1) : "";
        File source = file.getPath() == null ? null : new File(file.getPath());
        File parent = source == null ? null : source.getParentFile();
        String dir = parent == null ? "" : parent.getName();
        String dirpath = parent == null ? "" : parent.getAbsolutePath();
        long timestamp = file.getDateAdded() > 0 ? file.getDateAdded()
                : source == null ? 0L : source.lastModified();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        Calendar nowCalendar = Calendar.getInstance();
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(calendar.getTime());
        String now = new SimpleDateFormat("yyyyMMdd", Locale.US).format(nowCalendar.getTime());
        String nowTime = new SimpleDateFormat("HHmmss", Locale.US).format(nowCalendar.getTime());
        long size = file.getSize();
        long sizeKb = Math.round(size / 1024.0d);
        long sizeMb = Math.round(size / (1024.0d * 1024.0d));
        String type = file.getType() == MediaFile.Type.IMAGE ? "image"
                : file.getType() == MediaFile.Type.VIDEO ? "video" : "unsupported";
        String ratio = aspectRatio(file.getWidth(), file.getHeight());
        List<String> tags = file.getTags() == null
                ? new ArrayList<String>() : file.getTags();
        String firstTag = tags.isEmpty() ? "" : String.valueOf(tags.get(0));
        String lastTag = tags.isEmpty() ? "" : String.valueOf(tags.get(tags.size() - 1));
        String joinedTags = joinTags(tags, "_");
        String csvTags = joinTags(tags, ",");
        String sequence = sequenceCounter >= 0
                ? RandomGenerator.sequenceLabel(sequenceCounter) : nextSequence(file);
        int sequenceIndex = sequenceCounter >= 0 ? sequenceCounter :
                RandomGenerator.nextSequenceIndex(sequencePrefix(file), existingTagNames());
        String group = linkGroup(file);
        String status = fileStatus == null ? "NONE" : fileStatus.getStatus(file.getPath()).name();
        List<MediaFile> active = MainActivity.getLatestFullList();
        int listIndex = index;
        int listTotal = active == null ? 0 : active.size();
        if (active != null) {
            for (int i = 0; i < active.size(); i++) {
                if (active.get(i) != null && file.getPath().equals(active.get(i).getPath())) {
                    listIndex = i;
                    break;
                }
            }
        }
        int pageSize = context.getSharedPreferences("window_prefs", Context.MODE_PRIVATE)
                .getInt("window_size", 20);
        pageSize = Math.max(1, pageSize);
        int page = listIndex < 0 ? 1 : (listIndex / pageSize) + 1;
        int pageTotal = listTotal == 0 ? 0 : ((listTotal + pageSize - 1) / pageSize);
        String color = file.getColorFamily();
        String uuid = String.valueOf(System.currentTimeMillis()) + "_"
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.US);

        String resolved = pattern;
        resolved = resolved.replace("{filename}", filename);
        resolved = resolved.replace("{fullname}", name);
        resolved = resolved.replace("{ext}", extension);
        resolved = resolved.replace("{path}", file.getPath() == null ? "" : file.getPath());
        resolved = resolved.replace("{dir}", dir);
        resolved = resolved.replace("{dirpath}", dirpath);
        resolved = resolved.replace("{size}", String.valueOf(size));
        resolved = resolved.replace("{size_kb}", String.valueOf(sizeKb));
        resolved = resolved.replace("{size_mb}", String.valueOf(sizeMb));
        resolved = resolved.replace("{width}", String.valueOf(file.getWidth()));
        resolved = resolved.replace("{height}", String.valueOf(file.getHeight()));
        resolved = resolved.replace("{ratio}", ratio);
        resolved = resolved.replace("{type}", type);
        resolved = resolved.replace("{date}", date);
        resolved = resolved.replace("{year}", String.format(Locale.US, "%04d", calendar.get(Calendar.YEAR)));
        resolved = resolved.replace("{month}", String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1));
        resolved = resolved.replace("{day}", String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH)));
        resolved = resolved.replace("{hour}", String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY)));
        resolved = resolved.replace("{minute}", String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE)));
        resolved = resolved.replace("{timestamp}", String.valueOf(timestamp));
        resolved = resolved.replace("{now}", now);
        resolved = resolved.replace("{now_time}", nowTime);
        resolved = resolved.replace("{seq}", sequence);
        resolved = resolved.replace("{seq_index}", String.valueOf(sequenceIndex));
        resolved = resolved.replace("{group}", group);
        resolved = resolved.replace("{manual_order}", String.valueOf(file.getManualOrder()));
        resolved = resolved.replace("{list_index}", String.valueOf(listIndex < 0 ? 0 : listIndex));
        resolved = resolved.replace("{list_total}", String.valueOf(listTotal));
        resolved = resolved.replace("{page}", String.valueOf(page));
        resolved = resolved.replace("{page_total}", String.valueOf(pageTotal));
        resolved = resolved.replace("{flagged}", String.valueOf("FLAGGED".equals(status)));
        resolved = resolved.replace("{skipped}", String.valueOf("SKIPPED".equals(status)));
        resolved = resolved.replace("{done}", String.valueOf("DONE".equals(status)));
        resolved = resolved.replace("{is_duplicate}", String.valueOf(file.isDuplicate()));
        resolved = resolved.replace("{has_metadata}", String.valueOf(file.hasMetadata()));
        resolved = resolved.replace("{color}", color);
        resolved = resolved.replace("{color_hex}", "");
        resolved = resolved.replace("{random}", RandomGenerator.randomSyllableTag());
        resolved = resolved.replace("{random_hex}", randomHex());
        resolved = resolved.replace("{random_seq}", RandomGenerator.sequenceLabel(
                RandomGenerator.nextSequenceIndex("random", existingTagNames())));
        resolved = resolved.replace("{uuid}", uuid);
        resolved = resolved.replace("{session_date}", new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date(sessionStartMillis)));
        resolved = resolved.replace("{run_index}", String.valueOf(runIndex));
        Matcher tagMatcher = Pattern.compile("\\{tag:(\\d+)\\}").matcher(resolved);
        StringBuffer buffer = new StringBuffer();
        while (tagMatcher.find()) {
            int tagIndex;
            try { tagIndex = Integer.parseInt(tagMatcher.group(1)); }
            catch (Exception ignored) { tagIndex = -1; }
            String value = tagIndex >= 0 && tagIndex < tags.size()
                    ? String.valueOf(tags.get(tagIndex)) : "";
            tagMatcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        tagMatcher.appendTail(buffer);
        resolved = buffer.toString();
        resolved = resolved.replace("{tag_count}", String.valueOf(tags.size()));
        resolved = resolved.replace("{first_tag}", firstTag);
        resolved = resolved.replace("{last_tag}", lastTag);
        resolved = resolved.replace("{tags}", joinedTags);
        resolved = resolved.replace("{tags_csv}", csvTags);
        return replaceUnknownPlaceholders(resolved);
    }

    private String replaceUnknownPlaceholders(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("\\{[^{}]+\\}").matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Log.w("AutoOrganizer", "Unknown variable: " + matcher.group());
            matcher.appendReplacement(result, "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String aspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        int a = width;
        int b = height;
        while (b != 0) {
            int temp = a % b;
            a = b;
            b = temp;
        }
        return (width / a) + ":" + (height / a);
    }

    private String joinTags(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value == null ? "" : value);
        }
        return result.toString();
    }

    private String linkGroup(MediaFile file) {
        if (file == null || file.getTags() == null) return "";
        for (String value : file.getTags()) {
            if (value == null) continue;
            String tag = value.trim();
            if (tag.startsWith("link_") || tag.startsWith("link-")) {
                String group = tag.substring(5);
                int sequence = group.indexOf("_seq_");
                return sequence > 0 ? group.substring(0, sequence) : group;
            }
        }
        return "";
    }

    private String randomHex() {
        return String.format(Locale.US, "%06X", new java.util.Random().nextInt(0x1000000));
    }

    private String sequencePrefix(MediaFile file) {
        String prefix = "seq";
        if (file != null && file.getName() != null) {
            int marker = file.getName().indexOf("_seq_");
            if (marker > 0) prefix = file.getName().substring(0, marker);
        }
        return prefix;
    }

    private Set<String> existingTagNames() {
        Set<String> existing = new HashSet<String>();
        if (tagManager != null) {
            for (com.mediasorter.models.Tag tag : tagManager.getAllTags()) existing.add(tag.getName());
        }
        return existing;
    }

    private String nextSequence(MediaFile file) {
        Set<String> existing = existingTagNames();
        return RandomGenerator.sequenceLabel(RandomGenerator.nextSequenceIndex(
                sequencePrefix(file), existing));
    }

    private int sequenceCounterFor(MediaFile file, Map<String, Integer> counters,
                                   Set<String> existingTags) {
        String prefix = sequencePrefix(file);
        Integer value = counters.get(prefix);
        if (value == null) {
            value = RandomGenerator.nextSequenceIndex(prefix, existingTags);
            counters.put(prefix, value);
        }
        return value;
    }

    private void advanceSequenceCounter(MediaFile file, Map<String, Integer> counters,
                                        int current) {
        counters.put(sequencePrefix(file), current + 1);
    }

    private boolean matchesRule(Rule rule, MediaFile file, int index) {
        return matchesRule(rule, file, index, -1);
    }

    private boolean matchesRule(Rule rule, MediaFile file, int index, int sequenceCounter) {
        if (rule == null || rule.conditions == null || rule.conditions.isEmpty()) return false;
        for (Condition condition : rule.conditions) {
            if (!resolveCondition(condition, file, index, sequenceCounter)) return false;
        }
        return true;
    }

    private boolean resolveCondition(Condition condition, MediaFile file, int index) {
        return resolveCondition(condition, file, index, -1);
    }

    private boolean resolveCondition(Condition condition, MediaFile file, int index,
                                     int sequenceCounter) {
        if (condition instanceof NameCondition) {
            NameCondition value = (NameCondition) condition;
            try {
                return new NameCondition(resolvePattern(value.pattern, file, index, sequenceCounter), value.type, value.negate)
                        .matches(file, fileStatus);
            } catch (Exception error) {
                log.add("Invalid name condition: " + error.getMessage());
                return false;
            }
        }
        if (condition instanceof TagCondition) {
            TagCondition value = (TagCondition) condition;
            List<String> add = new ArrayList<String>();
            for (String tag : value.tags) add.add(resolvePattern(tag, file, index, sequenceCounter));
            return new TagCondition(add, value.matchAny, value.negate).matches(file, fileStatus);
        }
        if (condition instanceof FolderCondition) {
            FolderCondition value = (FolderCondition) condition;
            return new FolderCondition(resolvePattern(value.folderPath, file, index, sequenceCounter), value.negate)
                    .matches(file, fileStatus);
        }
        return condition != null && condition.matches(file, fileStatus);
    }

    private Action resolveAction(Action action, MediaFile file, int index) {
        return resolveAction(action, file, index, -1);
    }

    private Action resolveAction(Action action, MediaFile file, int index,
                                 int sequenceCounter) {
        if (action instanceof MoveAction) {
            MoveAction value = (MoveAction) action;
            return new MoveAction(resolvePattern(value.destFolder, file, index, sequenceCounter), value.conflict);
        }
        if (action instanceof CopyAction) {
            CopyAction value = (CopyAction) action;
            return new CopyAction(resolvePattern(value.destFolder, file, index, sequenceCounter), value.conflict);
        }
        if (action instanceof DeleteAction) {
            DeleteAction value = (DeleteAction) action;
            return new DeleteAction(value.useTrash, resolvePattern(value.trashFolder, file, index, sequenceCounter));
        }
        if (action instanceof TagAction) {
            TagAction value = (TagAction) action;
            List<String> add = new ArrayList<String>();
            List<String> remove = new ArrayList<String>();
            for (String tag : value.tagsToAdd) add.add(resolvePattern(tag, file, index, sequenceCounter));
            for (String tag : value.tagsToRemove) remove.add(resolvePattern(tag, file, index, sequenceCounter));
            return new TagAction(add, remove);
        }
        if (action instanceof RenameAction) {
            return new RenameAction(resolvePattern(((RenameAction) action).pattern, file, index, sequenceCounter));
        }
        if (action instanceof ChangeExtensionAction) {
            return new ChangeExtensionAction(resolvePattern(
                    ((ChangeExtensionAction) action).newExtension, file, index, sequenceCounter));
        }
        if (action instanceof AffixAction) {
            AffixAction value = (AffixAction) action;
            return new AffixAction(value.position, resolvePattern(value.text, file, index, sequenceCounter));
        }
        if (action instanceof MacroCompositeAction) {
            List<Action> resolved = new ArrayList<Action>();
            for (Action step : ((MacroCompositeAction) action).getActions()) {
                resolved.add(resolveAction(step, file, index, sequenceCounter));
            }
            return new MacroCompositeAction(resolved);
        }
        return action;
    }

    /** Apply all enabled rules; callers run this on their background executor. */
    public int applyTo(List<MediaFile> files) {
        runIndex++;
        log.clear();
        if (rules == null || files == null || files.isEmpty()) return 0;
        List<UndoEntry> batch = new ArrayList<UndoEntry>();
        int affected = 0;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            // One counter map belongs to this rule's complete file batch and
            // is never reset while that rule processes its files.
            Map<String, Integer> sequenceCounters = new HashMap<String, Integer>();
            Set<String> existingTags = existingTagNames();
            for (int i = 0; i < files.size(); i++) {
                MediaFile file = files.get(i);
                if (file == null) continue;
                int sequenceCounter = sequenceCounterFor(file, sequenceCounters, existingTags);
                boolean matches = matchesRule(rule, file, i, sequenceCounter);
                if (!matches) {
                    advanceSequenceCounter(file, sequenceCounters, sequenceCounter);
                    continue;
                }
                activeIndex = i;
                UndoEntry entry = captureState(file);
                Action action = resolveAction(rule.action, file, i, sequenceCounter);
                // Allocate the next value only after every condition/action
                // variable for this file has used the current value.
                advanceSequenceCounter(file, sequenceCounters, sequenceCounter);
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
        runIndex++;
        if (rules == null || file == null) return false;
        Map<String, Integer> sequenceCounters = new HashMap<String, Integer>();
        Set<String> existingTags = existingTagNames();
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled || !rule.autoApply) continue;
            int sequenceCounter = sequenceCounterFor(file, sequenceCounters, existingTags);
            if (matchesRule(rule, file, activeIndex, sequenceCounter)) {
                Action action = resolveAction(rule.action, file, activeIndex, sequenceCounter);
                advanceSequenceCounter(file, sequenceCounters, sequenceCounter);
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
    public boolean wasLastUndoPartial() { return lastUndoPartial; }

    public int undoLastRun() {
        lastUndoPartial = false;
        if (undoStack.isEmpty()) return 0;
        List<UndoEntry> batch = undoStack.pop();
        int restored = 0;
        // Reverse the batch too: a macro/rule batch is one atomic undo entry.
        for (int i = batch.size() - 1; i >= 0; i--) {
            UndoEntry entry = batch.get(i);
            try {
                if (entry.action instanceof MacroCompositeAction) {
                    boolean macroUndoOkay = ((MacroCompositeAction) entry.action).undoCaptured(
                            entry.originalPath, entry.newPath, context, tagManager, renamer, fileStatus);
                    if (!macroUndoOkay || ((MacroCompositeAction) entry.action).wasLastUndoPartial()) {
                        lastUndoPartial = true;
                    }
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
        runIndex++;
        if (rule == null || files == null || files.isEmpty()) return -1;
        List<UndoEntry> batch = new ArrayList<UndoEntry>();
        Map<String, Integer> sequenceCounters = new HashMap<String, Integer>();
        Set<String> existingTags = existingTagNames();
        int failedStep = -1;
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (file == null) continue;
            activeIndex = i;
            int sequenceCounter = sequenceCounterFor(file, sequenceCounters, existingTags);
            UndoEntry entry = captureState(file);
            Action action = resolveAction(rule.action, file, i, sequenceCounter);
            advanceSequenceCounter(file, sequenceCounters, sequenceCounter);
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
