package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Worker-friendly round builder and session model for Batch Game. */
public final class BatchGameEngine {
    public static final class Round {
        public final int number;
        public final List<MediaFile> files;
        public final String seedPath;
        /** Files that form the group the player is expected to select. */
        public final Set<String> targetPaths;
        /** Deliberate non-group distractors. */
        public final Set<String> decoyPaths;
        /** Tagged probe/control files inserted on probe rounds. */
        public final Set<String> probePaths;

        Round(int value, List<MediaFile> members, String seed) {
            this(value, members, seed, new HashSet<String>(),
                    new HashSet<String>(), new HashSet<String>());
        }

        Round(int value, List<MediaFile> members, String seed,
              Set<String> targets, Set<String> decoys, Set<String> probes) {
            number = value;
            files = Collections.unmodifiableList(new ArrayList<MediaFile>(members));
            seedPath = seed;
            targetPaths = Collections.unmodifiableSet(new HashSet<String>(targets));
            decoyPaths = Collections.unmodifiableSet(new HashSet<String>(decoys));
            probePaths = Collections.unmodifiableSet(new HashSet<String>(probes));
        }
    }

    public static final class RoundScore {
        public final int expected;
        public final int selected;
        public final int correct;
        public final int falsePositive;
        public final int missed;
        public final int points;
        public final boolean perfect;
        public final boolean none;

        RoundScore(int expectedValue, int selectedValue, int correctValue,
                   int falseValue, int missedValue, int pointValue,
                   boolean perfectValue, boolean noneValue) {
            expected = expectedValue;
            selected = selectedValue;
            correct = correctValue;
            falsePositive = falseValue;
            missed = missedValue;
            points = pointValue;
            perfect = perfectValue;
            none = noneValue;
        }
    }

    private final Random random = new Random();
    private int round;
    private int noneCooldown;
    private int noneCooldownRounds = 3;
    private int noneMaxReturns = 2;
    private final java.util.Map<String, Integer> noneReturns =
            new java.util.HashMap<String, Integer>();

    public synchronized Round buildRound(List<MediaFile> pool, int batchSize, int decoys,
                                          Map<String, Float> confidence,
                                          String activePile, int probeEvery) {
        return buildRound(pool, batchSize, decoys, confidence, activePile, probeEvery, false);
    }

    public synchronized Round buildRound(List<MediaFile> pool, int batchSize, int decoys,
                                          Map<String, Float> confidence,
                                          String activePile, int probeEvery,
                                          boolean adaptive) {
        List<MediaFile> candidates = new ArrayList<MediaFile>();
        if (pool != null) {
            for (MediaFile file : pool) {
                if (file == null) continue;
                if (activePile != null && !activePile.isEmpty() && !file.hasTag(activePile)) continue;
                if (file.getTags().isEmpty() && noneCooldown <= 0
                        && getNoneReturns(file.getPath()) < noneMaxReturns) candidates.add(file);
            }
        }
        if (candidates.isEmpty()) return null;

        round++;
        MediaFile seed = leastConfident(candidates, confidence);
        int requestedSize = adaptive ? adaptiveBatchSize(batchSize, candidates, confidence)
                : Math.max(4, Math.min(12, batchSize));
        int requestedDecoys = Math.max(0, Math.min(3, decoys));
        List<MediaFile> nearest = new ArrayList<MediaFile>(candidates);
        nearest.remove(seed);
        Collections.sort(nearest, new Comparator<MediaFile>() {
            @Override public int compare(MediaFile left, MediaFile right) {
                return Float.compare(distance(seed, left), distance(seed, right));
            }
        });

        boolean probeRound = probeEvery > 0 && round % probeEvery == 0;
        List<MediaFile> probes = new ArrayList<MediaFile>();
        if (probeRound && pool != null) {
            for (MediaFile file : pool) {
                if (file != null && !file.getTags().isEmpty() && !file.equals(seed)) {
                    probes.add(file);
                }
            }
            Collections.sort(probes, new Comparator<MediaFile>() {
                @Override public int compare(MediaFile left, MediaFile right) {
                    return Float.compare(distance(seed, right), distance(seed, left));
                }
            });
        }

        int probeCount = probes.isEmpty() ? 0 : 1;
        int groupCount = Math.max(1, requestedSize - requestedDecoys - probeCount);
        List<MediaFile> result = new ArrayList<MediaFile>();
        Set<String> targets = new HashSet<String>();
        Set<String> decoyPaths = new HashSet<String>();
        Set<String> probePaths = new HashSet<String>();
        result.add(seed);
        targets.add(seed.getPath());
        for (MediaFile file : nearest) {
            if (targets.size() >= groupCount) break;
            result.add(file);
            targets.add(file.getPath());
        }

        // Farthest candidates make stable, useful distractors rather than
        // simply filling the remaining cells with more likely group members.
        List<MediaFile> farthest = new ArrayList<MediaFile>(nearest);
        Collections.reverse(farthest);
        for (MediaFile file : farthest) {
            if (result.size() >= requestedSize - probeCount) break;
            if (result.contains(file)) continue;
            result.add(file);
            decoyPaths.add(file.getPath());
        }
        if (probeCount > 0 && result.size() < requestedSize) {
            MediaFile probe = probes.get(0);
            result.add(probe);
            probePaths.add(probe.getPath());
        }
        return new Round(round, result, seed.getPath(), targets, decoyPaths, probePaths);
    }

    /** Scores the frozen round before tags are committed. */
    public RoundScore score(Round round, Set<String> selectedPaths, boolean noneOfThese) {
        if (round == null) return new RoundScore(0, 0, 0, 0, 0, 0, false, noneOfThese);
        Set<String> selected = selectedPaths == null
                ? new HashSet<String>() : new HashSet<String>(selectedPaths);
        int correct = 0;
        for (String path : selected) if (round.targetPaths.contains(path)) correct++;
        int falsePositive = selected.size() - correct;
        int missed = Math.max(0, round.targetPaths.size() - correct);
        // A None answer is useful for a future no-group round, but a normal
        // frozen group round should make that choice visibly score zero.
        boolean perfect = !noneOfThese && falsePositive == 0 && missed == 0
                && !round.targetPaths.isEmpty();
        int points = perfect ? 100 : correct * 20 - falsePositive * 15 - missed * 10;
        if (noneOfThese) points = 0;
        return new RoundScore(round.targetPaths.size(), selected.size(), correct,
                falsePositive, missed, points, perfect, noneOfThese);
    }

    private int adaptiveBatchSize(int requested, List<MediaFile> candidates,
                                  Map<String, Float> confidence) {
        int size = Math.max(4, Math.min(12, requested));
        if (confidence == null || confidence.isEmpty()) return size;
        float total = 0f;
        int counted = 0;
        for (MediaFile file : candidates) {
            Float value = confidence.get(file.getPath());
            if (value == null) continue;
            total += value;
            counted++;
        }
        if (counted == 0) return size;
        float average = total / counted;
        if (average < .35f) size += 2;
        else if (average < .55f) size += 1;
        else if (average > .85f) size -= 1;
        return Math.max(4, Math.min(12, size));
    }

    private MediaFile leastConfident(List<MediaFile> files, Map<String, Float> confidence) {
        MediaFile best = files.get(0);
        float score = confidence == null || !confidence.containsKey(best.getPath())
                ? 0f : confidence.get(best.getPath());
        for (MediaFile file : files) {
            float value = confidence == null || !confidence.containsKey(file.getPath())
                    ? 0f : confidence.get(file.getPath());
            if (value < score) { score = value; best = file; }
        }
        return best;
    }

    private float distance(MediaFile left, MediaFile right) {
        String leftFolder = new java.io.File(left.getPath()).getParent();
        String rightFolder = new java.io.File(right.getPath()).getParent();
        float folder = leftFolder == null || !leftFolder.equals(rightFolder) ? .5f : 0f;
        float size = left.getSize() <= 0 || right.getSize() <= 0 ? .5f
                : Math.min(1f, Math.abs((float) Math.log(left.getSize() / (double) right.getSize())) / 6f);
        return folder + size;
    }

    public synchronized int getRound() { return round; }
    public synchronized void setNonePolicy(int cooldown, int maxReturns) {
        noneCooldownRounds = Math.max(0, cooldown);
        noneMaxReturns = Math.max(1, maxReturns);
    }
    public synchronized void reset() { round = 0; noneCooldown = 0; noneReturns.clear(); }
    public synchronized void noteNone(String path) {
        if (path == null) return;
        noneReturns.put(path, getNoneReturns(path) + 1);
        noneCooldown = noneCooldownRounds;
    }
    public synchronized int getNoneReturns(String path) {
        Integer value = noneReturns.get(path);
        return value == null ? 0 : value;
    }
    public synchronized boolean canReturn(String path, int cooldown, int maxReturns) {
        return noneCooldown <= 0 && getNoneReturns(path) < Math.max(1, maxReturns);
    }
    public synchronized void nextRound() { if (noneCooldown > 0) noneCooldown--; }

    /** Remap cooldown bookkeeping after a file rename or removal. */
    public synchronized void remapPaths(Map<String, String> oldToNew, Set<String> removed) {
        Map<String, Integer> remapped = new java.util.HashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : noneReturns.entrySet()) {
            String path = entry.getKey();
            if (removed != null && removed.contains(path)) continue;
            String next = oldToNew == null ? path : oldToNew.get(path);
            remapped.put(next == null ? path : next, entry.getValue());
        }
        noneReturns.clear();
        noneReturns.putAll(remapped);
    }

    public synchronized JSONObject toJson(Round current) {
        JSONObject object = new JSONObject();
        try {
            object.put("round", round);
            object.put("none_cooldown", noneCooldown);
            JSONObject returns = new JSONObject();
            for (Map.Entry<String, Integer> entry : noneReturns.entrySet()) {
                returns.put(entry.getKey(), entry.getValue());
            }
            object.put("none_returns", returns);
            JSONArray paths = new JSONArray();
            JSONArray targets = new JSONArray();
            JSONArray decoys = new JSONArray();
            JSONArray probes = new JSONArray();
            if (current != null) {
                for (MediaFile file : current.files) if (file != null) paths.put(file.getPath());
                for (String path : current.targetPaths) targets.put(path);
                for (String path : current.decoyPaths) decoys.put(path);
                for (String path : current.probePaths) probes.put(path);
            }
            object.put("paths", paths);
            object.put("target_paths", targets);
            object.put("decoy_paths", decoys);
            object.put("probe_paths", probes);
        } catch (Exception ignored) {}
        return object;
    }

    public synchronized void restoreState(JSONObject object) {
        if (object == null) return;
        round = object.optInt("round", round);
        noneCooldown = object.optInt("none_cooldown", noneCooldown);
        noneReturns.clear();
        JSONObject returns = object.optJSONObject("none_returns");
        if (returns == null) return;
        java.util.Iterator<String> keys = returns.keys();
        while (keys.hasNext()) {
            String path = keys.next();
            if (!path.isEmpty()) noneReturns.put(path, returns.optInt(path, 0));
        }
    }

    public synchronized Round restore(JSONObject object, List<MediaFile> pool) {
        if (object == null || pool == null) return null;
        Set<String> paths = new HashSet<String>();
        JSONArray saved = object.optJSONArray("paths");
        if (saved != null) for (int i = 0; i < saved.length(); i++) paths.add(saved.optString(i, ""));
        List<MediaFile> files = new ArrayList<MediaFile>();
        for (MediaFile file : pool) if (file != null && paths.contains(file.getPath())) files.add(file);
        if (files.isEmpty()) return null;
        restoreState(object);
        Set<String> targets = existingPathSet(object.optJSONArray("target_paths"), paths);
        Set<String> decoys = existingPathSet(object.optJSONArray("decoy_paths"), paths);
        Set<String> probes = existingPathSet(object.optJSONArray("probe_paths"), paths);
        if (targets.isEmpty()) {
            for (String path : paths) if (!decoys.contains(path) && !probes.contains(path)) targets.add(path);
        }
        String seed = files.get(0).getPath();
        return new Round(round, files, seed, targets, decoys, probes);
    }

    private Set<String> existingPathSet(JSONArray array, Set<String> available) {
        Set<String> result = new HashSet<String>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String path = array.optString(i, "");
            if (!path.isEmpty() && available.contains(path)) result.add(path);
        }
        return result;
    }
}
