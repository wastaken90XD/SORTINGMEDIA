package com.mediasorter;

import com.mediasorter.models.MediaFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Finger-search comparison sorter. It has no Android UI dependencies. */
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

    public static final class Result {
        public final List<String> orderedPaths;
        public final List<String> unsurePaths;
        public final int inconsistencies;
        Result(List<String> ordered, List<String> unsure, int errors) {
            orderedPaths = ordered;
            unsurePaths = unsure;
            inconsistencies = errors;
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
    }

    private final Random random = new Random();
    private final ArrayList<String> pool = new ArrayList<String>();
    private final ArrayList<String> sorted = new ArrayList<String>();
    private final ArrayList<AnswerRecord> undo = new ArrayList<AnswerRecord>();
    private final ArrayList<String> unsure = new ArrayList<String>();
    private final Map<String, Integer> recheckCount = new HashMap<String, Integer>();
    private String current;
    private int low;
    private int high;
    private int questionCount;
    private int recheckEvery;
    private int inconsistencies;
    private AnswerRecord pendingRecheck;
    private boolean rechecking;
    private boolean leftIsCandidate;
    private boolean initialized;

    public void start(List<MediaFile> files, int recheck) {
        pool.clear();
        if (files != null) for (MediaFile file : files) {
            if (file != null && file.getPath() != null) pool.add(file.getPath());
        }
        sorted.clear();
        undo.clear();
        unsure.clear();
        recheckCount.clear();
        current = null;
        low = high = 0;
        questionCount = 0;
        recheckEvery = Math.max(0, recheck);
        inconsistencies = 0;
        pendingRecheck = null;
        rechecking = false;
        initialized = true;
    }

    public boolean isStarted() { return initialized; }
    public boolean isFinished() { return initialized && current == null && pool.isEmpty(); }
    public int questionCount() { return questionCount; }
    public int totalFiles() { return sorted.size() + pool.size() + (current == null ? 0 : 1); }
    public List<String> getSortedPaths() { return new ArrayList<String>(sorted); }

    public Question nextQuestion() {
        if (!initialized || isFinished()) return null;
        if (!rechecking && pendingRecheck != null) {
            rechecking = true;
            leftIsCandidate = random.nextBoolean();
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
        int mid = low + ((high - low) / 2);
        String compared = sorted.get(Math.max(0, Math.min(sorted.size() - 1, mid)));
        leftIsCandidate = random.nextBoolean();
        questionCount++;
        return new Question(current, compared, leftIsCandidate, questionCount, totalFiles(), false);
    }

    public boolean answer(Answer answer) {
        if (answer == null) return false;
        if (rechecking && pendingRecheck != null) {
            Answer mapped = answer;
            if (!leftIsCandidate && answer != Answer.SAME && answer != Answer.SKIP) {
                mapped = answer == Answer.LEFT ? Answer.RIGHT : Answer.LEFT;
            }
            if (mapped != pendingRecheck.answer) {
                inconsistencies++;
                pendingRecheck.answer = mapped;
            }
            pendingRecheck = null;
            rechecking = false;
            return true;
        }
        if (current == null || sorted.isEmpty()) return false;
        int mid = low + ((high - low) / 2);
        mid = Math.max(0, Math.min(sorted.size() - 1, mid));
        AnswerRecord record = new AnswerRecord();
        record.candidate = current;
        record.compared = sorted.get(mid);
        record.low = low;
        record.high = high;
        record.mid = mid;
        record.answer = answer;
        undo.add(record);

        Answer mapped = answer;
        if (!leftIsCandidate && answer != Answer.SAME && answer != Answer.SKIP) {
            mapped = answer == Answer.LEFT ? Answer.RIGHT : Answer.LEFT;
        }
        if (mapped == Answer.LEFT) high = mid;
        else if (mapped == Answer.RIGHT) low = mid + 1;
        else if (mapped == Answer.SAME) {
            low = mid;
            high = mid;
        } else {
            if (!unsure.contains(current)) unsure.add(current);
            record.mid = mid;
            finishCurrent(mid);
            record.inserted = true;
        }
        if (current != null && (high <= low || sorted.size() == 0)) {
            int insertion = low;
            finishCurrent(insertion);
            record.mid = insertion;
            record.inserted = true;
        }
        else if (recheckEvery > 0 && questionCount % recheckEvery == 0
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

    private void beginNext() {
        if (pool.isEmpty()) return;
        current = pool.remove(0);
        low = 0;
        high = sorted.size();
    }

    private void finishCurrent(int position) {
        int safe = Math.max(0, Math.min(sorted.size(), position));
        sorted.add(safe, current);
        current = null;
        low = high = 0;
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        AnswerRecord record = undo.remove(undo.size() - 1);
        if (record.inserted) {
            int position = Math.max(0, Math.min(sorted.size() - 1, record.mid));
            if (position < sorted.size() && record.candidate.equals(sorted.get(position))) {
                sorted.remove(position);
                current = record.candidate;
                low = record.low;
                high = record.high;
                if (record.answer == Answer.SKIP) unsure.remove(current);
                return true;
            }
            return false;
        }
        if (current == null || !record.candidate.equals(current)) return false;
        low = record.low;
        high = record.high;
        if (record.answer == Answer.SKIP) unsure.remove(current);
        return true;
    }

    public Result finish() {
        if (current != null) finishCurrent(low);
        return new Result(new ArrayList<String>(sorted), new ArrayList<String>(unsure), inconsistencies);
    }

    public JSONObjectState state() {
        return new JSONObjectState(new ArrayList<String>(pool), new ArrayList<String>(sorted),
                current, low, high, questionCount, new ArrayList<String>(unsure));
    }

    public void restore(JSONObjectState state) {
        pool.clear(); sorted.clear(); unsure.clear(); undo.clear();
        if (state == null) { initialized = false; return; }
        pool.addAll(state.pool);
        sorted.addAll(state.sorted);
        current = state.current;
        low = state.low;
        high = state.high;
        questionCount = state.questions;
        unsure.addAll(state.unsure);
        initialized = true;
    }

    public synchronized JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("pool", new JSONArray(pool));
            object.put("sorted", new JSONArray(sorted));
            object.put("current", current == null ? "" : current);
            object.put("low", low);
            object.put("high", high);
            object.put("questions", questionCount);
            object.put("unsure", new JSONArray(unsure));
        } catch (Exception ignored) {}
        return object;
    }

    public synchronized void restoreJson(JSONObject object, Set<String> existingPaths) {
        if (object == null) return;
        List<String> restoredPool = SortSessionStore.existingPaths(
                object.optJSONArray("pool"), existingPaths);
        List<String> restoredSorted = SortSessionStore.existingPaths(
                object.optJSONArray("sorted"), existingPaths);
        String restoredCurrent = object.optString("current", "");
        if (!restoredCurrent.isEmpty() && !existingPaths.contains(restoredCurrent)) restoredCurrent = "";
        JSONObjectState state = new JSONObjectState(restoredPool, restoredSorted,
                restoredCurrent, object.optInt("low", 0), object.optInt("high", 0),
                object.optInt("questions", 0), SortSessionStore.existingPaths(
                        object.optJSONArray("unsure"), existingPaths));
        restore(state);
    }

    public static final class JSONObjectState {
        public final List<String> pool;
        public final List<String> sorted;
        public final String current;
        public final int low;
        public final int high;
        public final int questions;
        public final List<String> unsure;
        JSONObjectState(List<String> p, List<String> s, String c, int l, int h,
                        int q, List<String> u) {
            pool = p; sorted = s; current = c; low = l; high = h;
            questions = q; unsure = u;
        }
    }
}
