package com.mediasorter;

import com.mediasorter.models.MediaFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Finger/galloping comparison sorter.  The engine contains no Android UI
 * dependencies, so a Compare session can be driven by buttons, hardware keys,
 * or a test without changing its ordering semantics.
 */
public final class CompareSortEngine {
    public enum Answer { LEFT, RIGHT, SAME, SKIP }

    public static final class Question {
        public final String candidatePath;
        public final String comparedPath;
        public final boolean candidateOnLeft;
        public final int questionNumber;
        public final int totalFiles;
        public final boolean recheck;
        Question(String candidate, String compared, boolean onLeft, int number, int total,
                 boolean isRecheck) {
            candidatePath = candidate;
            comparedPath = compared;
            candidateOnLeft = onLeft;
            questionNumber = number;
            totalFiles = total;
            recheck = isRecheck;
        }
    }

    public static final class UncertainPair {
        public final String candidatePath;
        public final String comparedPath;
        UncertainPair(String candidate, String compared) {
            candidatePath = candidate;
            comparedPath = compared;
        }
    }

    public static final class Result {
        public final List<String> orderedPaths;
        public final List<String> unsurePaths;
        public final List<UncertainPair> unsurePairs;
        public final int inconsistencies;
        Result(List<String> ordered, List<String> unsure, List<UncertainPair> pairs,
               int errors) {
            orderedPaths = ordered;
            unsurePaths = unsure;
            unsurePairs = pairs;
            inconsistencies = errors;
        }

        /** Kept for callers compiled against the earlier result shape. */
        Result(List<String> ordered, List<String> unsure, int errors) {
            this(ordered, unsure, new ArrayList<UncertainPair>(), errors);
        }
    }

    private static final class AnswerRecord {
        String candidate;
        String compared;
        int low;
        int high;
        int mid;
        Answer answer;
        boolean inserted;
        boolean galloping;
    }

    private final Random random = new Random();
    private final ArrayList<String> pool = new ArrayList<String>();
    private final ArrayList<String> sorted = new ArrayList<String>();
    private final ArrayList<AnswerRecord> undo = new ArrayList<AnswerRecord>();
    private final ArrayList<String> unsure = new ArrayList<String>();
    private final ArrayList<UncertainPair> unsurePairs = new ArrayList<UncertainPair>();
    private final Map<String, Integer> recheckCount = new HashMap<String, Integer>();
    private String current;
    private int low;
    private int high;
    private int questionCount;
    private int recheckEvery;
    private int inconsistencies;
    private int maxUndoDepth = 50;
    private boolean randomizeSides = true;
    private AnswerRecord pendingRecheck;
    private boolean rechecking;
    private boolean leftIsCandidate;
    private boolean initialized;

    // Galloping state.  A new candidate starts near the previous insertion;
    // answers that keep moving in one direction expand exponentially, then
    // fall back to ordinary binary search at the discovered bracket.
    private int insertionHint = -1;
    private int nextProbe;
    private int gallopDirection;
    private int gallopStep;
    private boolean galloping;

    public void start(List<MediaFile> files, int recheck) {
        start(files, recheck, true, 50);
    }

    public void start(List<MediaFile> files, int recheck, boolean randomize,
                      int undoDepth) {
        configure(randomize, undoDepth);
        pool.clear();
        if (files != null) for (MediaFile file : files) {
            if (file != null && file.getPath() != null) pool.add(file.getPath());
        }
        sorted.clear();
        undo.clear();
        unsure.clear();
        unsurePairs.clear();
        recheckCount.clear();
        current = null;
        low = high = 0;
        questionCount = 0;
        recheckEvery = Math.max(0, recheck);
        inconsistencies = 0;
        pendingRecheck = null;
        rechecking = false;
        leftIsCandidate = true;
        insertionHint = -1;
        galloping = false;
        initialized = true;
    }

    /** Applies settings without resetting an in-progress session. */
    public void configure(boolean randomize, int undoDepth) {
        randomizeSides = randomize;
        maxUndoDepth = Math.max(1, Math.min(500, undoDepth));
        while (undo.size() > maxUndoDepth) undo.remove(0);
    }

    public boolean isStarted() { return initialized; }
    public boolean isFinished() { return initialized && current == null && pool.isEmpty(); }
    public int questionCount() { return questionCount; }
    public int totalFiles() { return sorted.size() + pool.size() + (current == null ? 0 : 1); }
    public List<String> getSortedPaths() { return new ArrayList<String>(sorted); }
    public List<String> getUnsurePaths() { return new ArrayList<String>(unsure); }
    public List<UncertainPair> getUnsurePairs() {
        return new ArrayList<UncertainPair>(unsurePairs);
    }

    /** Paths most likely to be displayed by the next few questions. */
    public List<String> getPrefetchPaths(int maximum) {
        ArrayList<String> result = new ArrayList<String>();
        int limit = Math.max(0, Math.min(3, maximum));
        if (limit == 0) return result;
        for (String path : pool) {
            if (result.size() >= limit) break;
            if (!result.contains(path)) result.add(path);
        }
        return result;
    }

    public Question nextQuestion() {
        if (!initialized || isFinished()) return null;
        if (!rechecking && pendingRecheck != null) {
            rechecking = true;
            leftIsCandidate = randomizeSides && random.nextBoolean();
            questionCount++;
            return new Question(pendingRecheck.candidate, pendingRecheck.compared,
                    leftIsCandidate, questionCount, totalFiles(), true);
        }
        if (current == null) beginNext();
        if (current == null) return null;
        if (sorted.isEmpty()) {
            finishCurrent(0);
            if (pool.isEmpty()) return null;
            beginNext();
        }
        if (current == null || sorted.isEmpty()) return null;
        int mid = comparisonIndex();
        String compared = sorted.get(Math.max(0, Math.min(sorted.size() - 1, mid)));
        leftIsCandidate = !randomizeSides || random.nextBoolean();
        questionCount++;
        return new Question(current, compared, leftIsCandidate, questionCount, totalFiles(), false);
    }

    public boolean answer(Answer answer) {
        if (answer == null) return false;
        if (rechecking && pendingRecheck != null) {
            Answer mapped = mapAnswer(answer);
            if (mapped != pendingRecheck.answer) {
                inconsistencies++;
                pendingRecheck.answer = mapped;
            }
            pendingRecheck = null;
            rechecking = false;
            return true;
        }
        if (current == null || sorted.isEmpty()) return false;
        int mid = comparisonIndex();
        mid = Math.max(0, Math.min(sorted.size() - 1, mid));
        AnswerRecord record = new AnswerRecord();
        record.candidate = current;
        record.compared = sorted.get(mid);
        record.low = low;
        record.high = high;
        record.mid = mid;
        record.galloping = galloping;

        Answer mapped = mapAnswer(answer);
        record.answer = mapped;
        undo.add(record);
        trimUndo();

        if (mapped == Answer.LEFT) {
            high = mid;
            continueGallop(-1, mid);
        } else if (mapped == Answer.RIGHT) {
            low = mid + 1;
            continueGallop(1, mid);
        } else if (mapped == Answer.SAME) {
            low = mid;
            high = mid;
            galloping = false;
        } else {
            addUnsure(record.candidate, record.compared);
            finishCurrent(mid);
            record.inserted = true;
        }
        if (current != null && (high <= low || sorted.size() == 0)) {
            int insertion = low;
            finishCurrent(insertion);
            record.mid = insertion;
            record.inserted = true;
        } else if (recheckEvery > 0 && questionCount % recheckEvery == 0
                && undo.size() > 1) {
            AnswerRecord earlier = undo.get(random.nextInt(undo.size() - 1));
            pendingRecheck = new AnswerRecord();
            pendingRecheck.candidate = earlier.candidate;
            pendingRecheck.compared = earlier.compared;
            pendingRecheck.answer = earlier.answer;
            String pair = earlier.candidate + "\n" + earlier.compared;
            Integer count = recheckCount.get(pair);
            recheckCount.put(pair, count == null ? 1 : count + 1);
        }
        return true;
    }

    private Answer mapAnswer(Answer answer) {
        if (!leftIsCandidate && answer != Answer.SAME && answer != Answer.SKIP) {
            return answer == Answer.LEFT ? Answer.RIGHT : Answer.LEFT;
        }
        return answer;
    }

    private void beginNext() {
        if (pool.isEmpty()) return;
        current = pool.remove(0);
        low = 0;
        high = sorted.size();
        if (sorted.isEmpty() || insertionHint < 0) {
            galloping = false;
            nextProbe = 0;
        } else {
            nextProbe = Math.max(0, Math.min(sorted.size() - 1, insertionHint));
            gallopDirection = 0;
            gallopStep = 1;
            galloping = true;
        }
    }

    private int comparisonIndex() {
        if (sorted.isEmpty()) return 0;
        if (galloping) return Math.max(low, Math.min(high - 1, nextProbe));
        return low + ((high - low) / 2);
    }

    private void continueGallop(int direction, int comparedIndex) {
        if (!galloping) return;
        if (gallopDirection == 0) {
            gallopDirection = direction;
            gallopStep = 1;
        } else if (gallopDirection != direction) {
            galloping = false;
            return;
        } else {
            gallopStep = Math.min(1 << 20, Math.max(1, gallopStep * 2));
        }
        nextProbe = comparedIndex + direction * gallopStep;
        if (nextProbe < low || nextProbe >= high) {
            galloping = false;
        }
    }

    private void finishCurrent(int position) {
        int safe = Math.max(0, Math.min(sorted.size(), position));
        sorted.add(safe, current);
        insertionHint = safe;
        current = null;
        low = high = 0;
        galloping = false;
    }

    private void addUnsure(String candidate, String compared) {
        if (candidate == null) return;
        if (!unsure.contains(candidate)) unsure.add(candidate);
        for (UncertainPair pair : unsurePairs) {
            if (candidate.equals(pair.candidatePath)
                    && (compared == null ? pair.comparedPath == null
                    : compared.equals(pair.comparedPath))) return;
        }
        unsurePairs.add(new UncertainPair(candidate, compared));
    }

    private void trimUndo() {
        while (undo.size() > maxUndoDepth) undo.remove(0);
    }

    public boolean undo() {
        if (rechecking) {
            pendingRecheck = null;
            rechecking = false;
        }
        if (undo.isEmpty()) return false;
        AnswerRecord record = undo.remove(undo.size() - 1);
        if (record.inserted) {
            int position = Math.max(0, Math.min(sorted.size() - 1, record.mid));
            if (position < sorted.size() && record.candidate.equals(sorted.get(position))) {
                sorted.remove(position);
                current = record.candidate;
                low = record.low;
                high = record.high;
                galloping = false;
                insertionHint = position;
                if (record.answer == Answer.SKIP) removeUnsure(record.candidate, record.compared);
                return true;
            }
            return false;
        }
        if (current == null || !record.candidate.equals(current)) return false;
        low = record.low;
        high = record.high;
        galloping = false;
        if (record.answer == Answer.SKIP) removeUnsure(record.candidate, record.compared);
        return true;
    }

    private void removeUnsure(String candidate, String compared) {
        unsure.remove(candidate);
        for (int i = unsurePairs.size() - 1; i >= 0; i--) {
            UncertainPair pair = unsurePairs.get(i);
            if (candidate.equals(pair.candidatePath)
                    && (compared == null ? pair.comparedPath == null
                    : compared.equals(pair.comparedPath))) unsurePairs.remove(i);
        }
    }

    /** Resolve one skipped pair during the completion review dialog. */
    public synchronized boolean resolveUnsurePair(int pairIndex, Answer answer) {
        if (pairIndex < 0 || pairIndex >= unsurePairs.size() || answer == null) return false;
        UncertainPair pair = unsurePairs.get(pairIndex);
        int candidateIndex = sorted.indexOf(pair.candidatePath);
        int comparedIndex = sorted.indexOf(pair.comparedPath);
        if (candidateIndex < 0 || comparedIndex < 0) return false;
        if (answer != Answer.SKIP) {
            sorted.remove(candidateIndex);
            comparedIndex = sorted.indexOf(pair.comparedPath);
            int insertion = comparedIndex;
            if (answer == Answer.RIGHT) insertion++;
            sorted.add(Math.max(0, Math.min(sorted.size(), insertion)), pair.candidatePath);
        }
        unsurePairs.remove(pairIndex);
        boolean stillUncertain = false;
        for (UncertainPair other : unsurePairs) {
            if (pair.candidatePath.equals(other.candidatePath)) {
                stillUncertain = true;
                break;
            }
        }
        if (!stillUncertain) unsure.remove(pair.candidatePath);
        return true;
    }

    public Result finish() {
        if (current != null) finishCurrent(low);
        return new Result(new ArrayList<String>(sorted), new ArrayList<String>(unsure),
                new ArrayList<UncertainPair>(unsurePairs), inconsistencies);
    }

    public JSONObjectState state() {
        return new JSONObjectState(new ArrayList<String>(pool), new ArrayList<String>(sorted),
                current, low, high, questionCount, new ArrayList<String>(unsure),
                new ArrayList<UncertainPair>(unsurePairs), inconsistencies);
    }

    public void restore(JSONObjectState state) {
        pool.clear();
        sorted.clear();
        unsure.clear();
        unsurePairs.clear();
        undo.clear();
        pendingRecheck = null;
        rechecking = false;
        if (state == null) { initialized = false; return; }
        pool.addAll(state.pool);
        sorted.addAll(state.sorted);
        current = state.current;
        low = state.low;
        high = state.high;
        questionCount = state.questions;
        inconsistencies = state.inconsistencies;
        unsure.addAll(state.unsure);
        unsurePairs.addAll(state.unsurePairs);
        insertionHint = sorted.size();
        galloping = false;
        initialized = true;
    }

    public synchronized JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("pool", toJsonArray(pool));
            object.put("sorted", toJsonArray(sorted));
            object.put("current", current == null ? "" : current);
            object.put("low", low);
            object.put("high", high);
            object.put("questions", questionCount);
            object.put("inconsistencies", inconsistencies);
            object.put("recheck_every", recheckEvery);
            object.put("randomize_sides", randomizeSides);
            object.put("undo_depth", maxUndoDepth);
            object.put("unsure", toJsonArray(unsure));
            JSONArray pairs = new JSONArray();
            for (UncertainPair pair : unsurePairs) {
                JSONObject value = new JSONObject();
                value.put("candidate", pair.candidatePath);
                value.put("compared", pair.comparedPath == null ? "" : pair.comparedPath);
                pairs.put(value);
            }
            object.put("unsure_pairs", pairs);
        } catch (Exception ignored) {}
        return object;
    }

    private JSONArray toJsonArray(List<String> values) {
        JSONArray result = new JSONArray();
        if (values != null) for (String value : values) result.put(value);
        return result;
    }

    public synchronized void restoreJson(JSONObject object, Set<String> existingPaths) {
        if (object == null || existingPaths == null) return;
        List<String> restoredPool = SortSessionStore.existingPaths(
                object.optJSONArray("pool"), existingPaths);
        List<String> restoredSorted = SortSessionStore.existingPaths(
                object.optJSONArray("sorted"), existingPaths);
        String restoredCurrent = object.optString("current", "");
        if (!restoredCurrent.isEmpty() && !existingPaths.contains(restoredCurrent)) restoredCurrent = "";
        ArrayList<UncertainPair> restoredPairs = new ArrayList<UncertainPair>();
        JSONArray pairArray = object.optJSONArray("unsure_pairs");
        if (pairArray != null) for (int i = 0; i < pairArray.length(); i++) {
            JSONObject pair = pairArray.optJSONObject(i);
            if (pair == null) continue;
            String candidate = pair.optString("candidate", "");
            String compared = pair.optString("compared", "");
            if (existingPaths.contains(candidate)
                    && (compared.isEmpty() || existingPaths.contains(compared))) {
                restoredPairs.add(new UncertainPair(candidate,
                        compared.isEmpty() ? null : compared));
            }
        }
        JSONObjectState state = new JSONObjectState(restoredPool, restoredSorted,
                restoredCurrent, object.optInt("low", 0), object.optInt("high", 0),
                object.optInt("questions", 0), SortSessionStore.existingPaths(
                        object.optJSONArray("unsure"), existingPaths), restoredPairs,
                object.optInt("inconsistencies", 0));
        recheckEvery = Math.max(0, object.optInt("recheck_every", recheckEvery));
        randomizeSides = object.optBoolean("randomize_sides", randomizeSides);
        maxUndoDepth = Math.max(1, Math.min(500, object.optInt("undo_depth", maxUndoDepth)));
        restore(state);
    }

    /** Remap a live session after an organizer rename or delete. */
    public synchronized void remapPaths(Map<String, String> oldToNew, Set<String> removed) {
        remapList(pool, oldToNew, removed);
        remapList(sorted, oldToNew, removed);
        remapList(unsure, oldToNew, removed);
        if (current != null) {
            if (removed != null && removed.contains(current)) current = null;
            else if (oldToNew != null && oldToNew.containsKey(current)) current = oldToNew.get(current);
        }
        for (int i = unsurePairs.size() - 1; i >= 0; i--) {
            UncertainPair pair = unsurePairs.get(i);
            String candidate = remapPath(pair.candidatePath, oldToNew, removed);
            String compared = remapPath(pair.comparedPath, oldToNew, removed);
            if (candidate == null || compared == null) unsurePairs.remove(i);
            else if (!candidate.equals(pair.candidatePath) || !same(compared, pair.comparedPath)) {
                unsurePairs.set(i, new UncertainPair(candidate, compared));
            }
        }
        remapAnswerRecords(undo, oldToNew, removed);
        if (pendingRecheck != null) {
            pendingRecheck.candidate = remapPath(pendingRecheck.candidate, oldToNew, removed);
            pendingRecheck.compared = remapPath(pendingRecheck.compared, oldToNew, removed);
            if (pendingRecheck.candidate == null || pendingRecheck.compared == null) {
                pendingRecheck = null;
                rechecking = false;
            }
        }
    }

    private void remapAnswerRecords(List<AnswerRecord> records,
                                    Map<String, String> oldToNew, Set<String> removed) {
        for (int i = records.size() - 1; i >= 0; i--) {
            AnswerRecord record = records.get(i);
            record.candidate = remapPath(record.candidate, oldToNew, removed);
            record.compared = remapPath(record.compared, oldToNew, removed);
            if (record.candidate == null || record.compared == null) records.remove(i);
        }
    }

    private void remapList(List<String> values, Map<String, String> oldToNew,
                           Set<String> removed) {
        for (int i = values.size() - 1; i >= 0; i--) {
            String next = remapPath(values.get(i), oldToNew, removed);
            if (next == null) values.remove(i);
            else values.set(i, next);
        }
    }

    private String remapPath(String path, Map<String, String> oldToNew, Set<String> removed) {
        if (path == null) return null;
        if (removed != null && removed.contains(path)) return null;
        return oldToNew != null && oldToNew.containsKey(path) ? oldToNew.get(path) : path;
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    public static final class JSONObjectState {
        public final List<String> pool;
        public final List<String> sorted;
        public final String current;
        public final int low;
        public final int high;
        public final int questions;
        public final List<String> unsure;
        public final List<UncertainPair> unsurePairs;
        public final int inconsistencies;
        JSONObjectState(List<String> p, List<String> s, String c, int l, int h,
                        int q, List<String> u, List<UncertainPair> pairs, int errors) {
            pool = p;
            sorted = s;
            current = c;
            low = l;
            high = h;
            questions = q;
            unsure = u;
            unsurePairs = pairs == null ? new ArrayList<UncertainPair>() : pairs;
            inconsistencies = errors;
        }
    }
}
