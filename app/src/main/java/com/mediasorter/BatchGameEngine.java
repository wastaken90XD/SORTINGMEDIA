package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Worker-friendly state machine for the Batch tagger.
 *
 * The legacy class name is retained because GAME_* gesture ids and old session
 * files are part of the on-device format.  There is only one batch engine: it
 * owns scope eligibility, queue order, round completion and pause state.
 */
public final class BatchGameEngine {
    public enum Scope {
        UNTAGGED("Untagged"),
        ACTIVE_PILE("Active pile"),
        SELECTION("Selection"),
        FILTERED("Current filtered list");

        public final String label;
        Scope(String value) { label = value; }

        public static Scope fromStored(String value) {
            if (value == null) return UNTAGGED;
            for (Scope scope : values()) if (scope.name().equalsIgnoreCase(value)
                    || scope.label.equalsIgnoreCase(value)) return scope;
            return UNTAGGED;
        }
    }

    public static final class Round {
        public final int number;
        public final List<MediaFile> files;
        private final Set<String> appliedPaths = new LinkedHashSet<String>();
        private final Set<String> skippedPaths = new LinkedHashSet<String>();

        Round(int numberValue, List<MediaFile> values) {
            number = numberValue;
            files = Collections.unmodifiableList(new ArrayList<MediaFile>(values));
        }

        public synchronized boolean isDone(String path) {
            return appliedPaths.contains(path) || skippedPaths.contains(path);
        }

        public synchronized boolean isApplied(String path) { return appliedPaths.contains(path); }
        public synchronized boolean isSkipped(String path) { return skippedPaths.contains(path); }
        public synchronized int doneCount() { return appliedPaths.size() + skippedPaths.size(); }
        public synchronized Set<String> getAppliedPaths() {
            return new LinkedHashSet<String>(appliedPaths);
        }
        public synchronized Set<String> getSkippedPaths() {
            return new LinkedHashSet<String>(skippedPaths);
        }

        private synchronized void restore(Set<String> applied, Set<String> skipped) {
            appliedPaths.clear();
            skippedPaths.clear();
            if (applied != null) appliedPaths.addAll(applied);
            if (skipped != null) skippedPaths.addAll(skipped);
        }
    }

    public static final class Summary {
        public final int tagged;
        public final int skipped;
        public final int remaining;
        public final boolean complete;

        Summary(int taggedValue, int skippedValue, int remainingValue, boolean completeValue) {
            tagged = taggedValue;
            skipped = skippedValue;
            remaining = remainingValue;
            complete = completeValue;
        }
    }

    private final Object lock = new Object();
    private final ArrayDeque<MediaFile> queue = new ArrayDeque<MediaFile>();
    private final Map<String, MediaFile> filesByPath = new LinkedHashMap<String, MediaFile>();
    private final Set<String> queuedPaths = new HashSet<String>();
    private final Set<String> taggedPaths = new LinkedHashSet<String>();
    private final Set<String> skippedPaths = new LinkedHashSet<String>();
    private Scope scope = Scope.UNTAGGED;
    private Round current;
    private int batchSize = 6;
    private boolean skipToBack = true;
    private int roundNumber;

    /**
     * A file needs a tag when every existing tag is either a configured pile
     * tag or an explicitly ignored tag.  An empty tag list therefore needs a
     * tag.  Patterns are comma-separated glob expressions (* and ?).
     */
    public static boolean needsTag(MediaFile file, String pilePatterns,
                                   String ignorePatterns) {
        return needsTag(file, "", pilePatterns, ignorePatterns);
    }

    public static boolean needsTag(MediaFile file, String activePile,
                                   String pilePatterns, String ignorePatterns) {
        if (file == null) return false;
        for (String tag : file.getTags()) {
            if (tag == null || tag.trim().isEmpty()) continue;
            if ((activePile != null && !activePile.trim().isEmpty()
                    && activePile.trim().equalsIgnoreCase(tag.trim()))
                    || matchesAny(tag, pilePatterns) || matchesAny(tag, ignorePatterns)) continue;
            return false;
        }
        return true;
    }

    public static boolean belongsToActivePile(MediaFile file, String activePile,
                                              String pilePatterns) {
        if (file == null || activePile == null || activePile.trim().isEmpty()) return false;
        String pile = activePile.trim();
        if (file.hasTag(pile)) return true;
        // A configured pattern is also a valid pile membership declaration for
        // imported settings that did not persist the active pile name exactly.
        return matchesAny(pile, pilePatterns);
    }

    public static List<MediaFile> selectScope(List<MediaFile> fullIndex, Scope selectedScope,
                                              Set<String> selectionPaths,
                                              List<MediaFile> filteredFiles,
                                              String activePile, String pilePatterns,
                                              String ignorePatterns, boolean includeTagged) {
        Map<String, MediaFile> fullByPath = new LinkedHashMap<String, MediaFile>();
        if (fullIndex != null) {
            for (MediaFile file : fullIndex) {
                if (file != null && file.getPath() != null) fullByPath.put(file.getPath(), file);
            }
        }
        List<MediaFile> source = new ArrayList<MediaFile>();
        if (selectedScope == Scope.FILTERED && filteredFiles != null) {
            for (MediaFile file : filteredFiles) {
                if (file != null && fullByPath.containsKey(file.getPath())) {
                    source.add(fullByPath.get(file.getPath()));
                }
            }
        } else {
            source.addAll(fullByPath.values());
        }
        Set<String> allowed = selectionPaths == null
                ? Collections.<String>emptySet() : selectionPaths;
        LinkedHashMap<String, MediaFile> unique = new LinkedHashMap<String, MediaFile>();
        for (MediaFile file : source) {
            if (file == null || file.getPath() == null || unique.containsKey(file.getPath())) continue;
            if (selectedScope == Scope.SELECTION && !allowed.contains(file.getPath())) continue;
            if (selectedScope == Scope.ACTIVE_PILE
                    && !belongsToActivePile(file, activePile, pilePatterns)) continue;
            if (!includeTagged && !needsTag(file, activePile, pilePatterns, ignorePatterns)) continue;
            unique.put(file.getPath(), file);
        }
        return new ArrayList<MediaFile>(unique.values());
    }

    public static String noFilesReason(Scope selectedScope, String activePile,
                                       String pilePatterns, String ignorePatterns,
                                       boolean includeTagged) {
        if (selectedScope == Scope.ACTIVE_PILE
                && (activePile == null || activePile.trim().isEmpty())) {
            return "No active pile is selected. Choose another scope or select a Smart pile first.";
        }
        if (includeTagged) return "There are no files in that scope.";
        return "No files need tags in that scope. Files with a tag other than pile or ignored tags are excluded.";
    }

    public void start(List<MediaFile> pool, Scope selectedScope, int requestedBatchSize,
                      boolean moveSkippedToBack) {
        synchronized (lock) {
            queue.clear();
            filesByPath.clear();
            queuedPaths.clear();
            taggedPaths.clear();
            skippedPaths.clear();
            current = null;
            roundNumber = 0;
            scope = selectedScope == null ? Scope.UNTAGGED : selectedScope;
            batchSize = Math.max(1, requestedBatchSize);
            skipToBack = moveSkippedToBack;
            appendUnique(pool);
        }
    }

    private void appendUnique(List<MediaFile> pool) {
        if (pool == null) return;
        for (MediaFile file : pool) {
            if (file == null || file.getPath() == null || filesByPath.containsKey(file.getPath())) continue;
            filesByPath.put(file.getPath(), file);
            queue.addLast(file);
            queuedPaths.add(file.getPath());
        }
    }

    /** Builds one round from the queue. The caller normally invokes this on a worker. */
    public Round buildNextRound() {
        synchronized (lock) {
            if (current != null && !isRoundCompleteLocked()) return current;
            if (current != null) finishRoundLocked();
            if (queue.isEmpty()) return null;
            List<MediaFile> values = new ArrayList<MediaFile>();
            while (values.size() < batchSize && !queue.isEmpty()) {
                MediaFile file = queue.removeFirst();
                queuedPaths.remove(file.getPath());
                values.add(file);
            }
            current = new Round(++roundNumber, values);
            return current;
        }
    }

    /** The explicit Next batch action finalizes unfinished cells as skipped. */
    public Round nextBatch() {
        synchronized (lock) {
            if (current != null) {
                for (MediaFile file : current.files) {
                    if (file != null && !current.isDone(file.getPath())) current.skippedPaths.add(file.getPath());
                }
                finishRoundLocked();
            }
            return buildNextRound();
        }
    }

    public boolean markApplied(String path) {
        synchronized (lock) {
            if (current == null || !containsCurrent(path) || current.isDone(path)) return false;
            current.appliedPaths.add(path);
            current.skippedPaths.remove(path);
            skippedPaths.remove(path);
            taggedPaths.add(path);
            return true;
        }
    }

    public boolean markSkipped(String path) {
        synchronized (lock) {
            if (current == null || !containsCurrent(path) || current.isDone(path)) return false;
            current.skippedPaths.add(path);
            current.appliedPaths.remove(path);
            taggedPaths.remove(path);
            skippedPaths.add(path);
            return true;
        }
    }

    public void markAppliedWithoutRound(String path) {
        synchronized (lock) {
            if (path == null) return;
            taggedPaths.add(path);
            skippedPaths.remove(path);
        }
    }

    public boolean isRoundComplete() {
        synchronized (lock) { return isRoundCompleteLocked(); }
    }

    private boolean isRoundCompleteLocked() {
        if (current == null) return false;
        return current.doneCount() >= current.files.size();
    }

    private boolean containsCurrent(String path) {
        if (path == null || current == null) return false;
        for (MediaFile file : current.files) {
            if (file != null && path.equals(file.getPath())) return true;
        }
        return false;
    }

    private void finishRoundLocked() {
        if (current == null) return;
        for (MediaFile file : current.files) {
            if (file == null || file.getPath() == null) continue;
            String path = file.getPath();
            if (current.appliedPaths.contains(path)) {
                taggedPaths.add(path);
                skippedPaths.remove(path);
            } else if (current.skippedPaths.contains(path)) {
                skippedPaths.add(path);
                if (skipToBack && !queuedPaths.contains(path)) {
                    queue.addLast(file);
                    queuedPaths.add(path);
                }
            }
        }
        current = null;
    }

    public Round getCurrentRound() {
        synchronized (lock) { return current; }
    }

    public List<MediaFile> getQueueSnapshot() {
        synchronized (lock) { return new ArrayList<MediaFile>(queue); }
    }

    public Scope getScope() { synchronized (lock) { return scope; } }
    public int getBatchSize() { synchronized (lock) { return batchSize; } }
    public boolean isSkipToBack() { synchronized (lock) { return skipToBack; } }

    public Summary getSummary() {
        synchronized (lock) {
            int remaining = queue.size();
            if (current != null) remaining += current.files.size() - current.doneCount();
            return new Summary(taggedPaths.size(), skippedPaths.size(), remaining, remaining == 0);
        }
    }

    public Set<String> getTaggedPaths() {
        synchronized (lock) { return new LinkedHashSet<String>(taggedPaths); }
    }

    public Set<String> getSkippedPaths() {
        synchronized (lock) { return new LinkedHashSet<String>(skippedPaths); }
    }

    public List<MediaFile> filesForPaths(Set<String> paths) {
        synchronized (lock) {
            List<MediaFile> result = new ArrayList<MediaFile>();
            if (paths == null) return result;
            for (String path : paths) {
                MediaFile file = filesByPath.get(path);
                if (file != null) result.add(file);
            }
            return result;
        }
    }

    public void remapPaths(Map<String, String> oldToNew, Set<String> removed) {
        synchronized (lock) {
            if ((oldToNew == null || oldToNew.isEmpty()) && (removed == null || removed.isEmpty())) return;
            Map<String, MediaFile> remapped = new LinkedHashMap<String, MediaFile>();
            for (Map.Entry<String, MediaFile> entry : filesByPath.entrySet()) {
                String oldPath = entry.getKey();
                if (removed != null && removed.contains(oldPath)) continue;
                String next = oldToNew == null ? oldPath : oldToNew.get(oldPath);
                MediaFile file = entry.getValue();
                if (next != null && !next.equals(oldPath) && file != null) file.setPath(next);
                remapped.put(next == null ? oldPath : next, file);
            }
            filesByPath.clear();
            filesByPath.putAll(remapped);
            remapSet(taggedPaths, oldToNew, removed);
            remapSet(skippedPaths, oldToNew, removed);
            if (current != null) {
                remapSet(current.appliedPaths, oldToNew, removed);
                remapSet(current.skippedPaths, oldToNew, removed);
            }
            if (removed != null && !removed.isEmpty()) {
                java.util.Iterator<MediaFile> iterator = queue.iterator();
                while (iterator.hasNext()) {
                    MediaFile file = iterator.next();
                    if (file != null && removed.contains(file.getPath())) iterator.remove();
                }
            }
            queuedPaths.clear();
            for (MediaFile file : queue) if (file != null && file.getPath() != null) queuedPaths.add(file.getPath());
        }
    }

    private void remapSet(Set<String> values, Map<String, String> oldToNew, Set<String> removed) {
        Set<String> copy = new LinkedHashSet<String>(values);
        values.clear();
        for (String oldPath : copy) {
            if (removed != null && removed.contains(oldPath)) continue;
            String next = oldToNew == null ? oldPath : oldToNew.get(oldPath);
            values.add(next == null ? oldPath : next);
        }
    }

    /** Restore a round-level undo by restoring the queue and current state. */
    public void restoreFromJson(JSONObject object, List<MediaFile> available) {
        synchronized (lock) {
            queue.clear();
            filesByPath.clear();
            queuedPaths.clear();
            taggedPaths.clear();
            skippedPaths.clear();
            current = null;
            if (object == null || available == null) return;
            Map<String, MediaFile> availableByPath = new HashMap<String, MediaFile>();
            for (MediaFile file : available) if (file != null && file.getPath() != null) {
                availableByPath.put(file.getPath(), file);
            }
            scope = Scope.fromStored(object.optString("scope", Scope.UNTAGGED.name()));
            batchSize = Math.max(1, object.optInt("batch_size", 6));
            skipToBack = object.optBoolean("skip_to_back", true);
            roundNumber = object.optInt("round_number", 0);
            restorePathSet(object.optJSONArray("tagged"), taggedPaths);
            restorePathSet(object.optJSONArray("skipped"), skippedPaths);
            appendPaths(object.optJSONArray("queue"), availableByPath);
            JSONArray currentArray = object.optJSONArray("current");
            List<MediaFile> cells = new ArrayList<MediaFile>();
            if (currentArray != null) for (int i = 0; i < currentArray.length(); i++) {
                MediaFile file = availableByPath.get(currentArray.optString(i, ""));
                if (file != null) {
                    filesByPath.put(file.getPath(), file);
                    cells.add(file);
                }
            }
            if (!cells.isEmpty()) {
                current = new Round(Math.max(1, roundNumber), cells);
                restorePathSet(object.optJSONArray("current_applied"), current.appliedPaths);
                restorePathSet(object.optJSONArray("current_skipped"), current.skippedPaths);
            }
        }
    }

    private void appendPaths(JSONArray array, Map<String, MediaFile> available) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            MediaFile file = available.get(array.optString(i, ""));
            if (file == null || queuedPaths.contains(file.getPath())) continue;
            filesByPath.put(file.getPath(), file);
            queue.addLast(file);
            queuedPaths.add(file.getPath());
        }
    }

    private void restorePathSet(JSONArray array, Set<String> destination) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String path = array.optString(i, "");
            if (!path.isEmpty()) destination.add(path);
        }
    }

    public JSONObject toJson() {
        synchronized (lock) {
            JSONObject result = new JSONObject();
            try {
                result.put("scope", scope.name());
                result.put("batch_size", batchSize);
                result.put("skip_to_back", skipToBack);
                result.put("round_number", roundNumber);
                result.put("queue", paths(queue));
                result.put("tagged", paths(taggedPaths));
                result.put("skipped", paths(skippedPaths));
                result.put("current", current == null ? new JSONArray() : paths(current.files));
                result.put("current_applied", current == null ? new JSONArray() : paths(current.appliedPaths));
                result.put("current_skipped", current == null ? new JSONArray() : paths(current.skippedPaths));
            } catch (Exception ignored) {}
            return result;
        }
    }

    private JSONArray paths(Iterable<MediaFile> values) {
        JSONArray array = new JSONArray();
        for (MediaFile file : values) if (file != null && file.getPath() != null) array.put(file.getPath());
        return array;
    }

    private JSONArray paths(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String path : values) if (path != null && !path.isEmpty()) array.put(path);
        return array;
    }

    private static boolean matchesAny(String value, String rawPatterns) {
        if (value == null || rawPatterns == null || rawPatterns.trim().isEmpty()) return false;
        String[] parts = rawPatterns.split(",");
        for (String raw : parts) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) continue;
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') regex.append(".*");
                else if (c == '?') regex.append('.');
                else regex.append(Pattern.quote(String.valueOf(c)));
            }
            regex.append('$');
            if (Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches()) return true;
        }
        return false;
    }
}
