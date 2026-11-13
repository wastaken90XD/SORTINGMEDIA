package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
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
        Round(int value, List<MediaFile> members, String seed) {
            number = value;
            files = members;
            seedPath = seed;
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
            if (candidates.isEmpty()) {
                for (MediaFile file : pool) {
                    if (file != null && file.getTags().isEmpty()) candidates.add(file);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        round++;
        MediaFile seed = leastConfident(candidates, confidence);
        List<MediaFile> result = new ArrayList<MediaFile>();
        result.add(seed);
        List<MediaFile> nearest = new ArrayList<MediaFile>(candidates);
        nearest.remove(seed);
        java.util.Collections.sort(nearest, new java.util.Comparator<MediaFile>() {
            @Override public int compare(MediaFile left, MediaFile right) {
                return Float.compare(distance(seed, left), distance(seed, right));
            }
        });
        int targetSize = adaptive ? adaptiveBatchSize(batchSize, candidates, confidence)
                : Math.max(4, Math.min(12, batchSize));
        for (MediaFile file : nearest) {
            if (result.size() >= targetSize) break;
            result.add(file);
        }
        int decoyCount = Math.max(0, Math.min(3, decoys));
        for (int i = 0; i < decoyCount && result.size() < targetSize && !candidates.isEmpty(); i++) {
            MediaFile decoy = candidates.get(random.nextInt(candidates.size()));
            if (!result.contains(decoy)) result.add(decoy);
        }
        if (probeEvery > 0 && round % probeEvery == 0 && result.size() < targetSize) {
            for (MediaFile file : pool) {
                if (!file.getTags().isEmpty() && !result.contains(file)) {
                    result.add(file);
                    if (result.size() >= targetSize) break;
                }
            }
        }
        return new Round(round, result, seed.getPath());
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
            if (current != null) for (MediaFile file : current.files) {
                if (file != null) paths.put(file.getPath());
            }
            object.put("paths", paths);
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
        round = object.optInt("round", round);
        noneCooldown = object.optInt("none_cooldown", noneCooldown);
        JSONObject returns = object.optJSONObject("none_returns");
        if (returns != null) {
            java.util.Iterator<String> keys = returns.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                if (!path.isEmpty()) noneReturns.put(path, returns.optInt(path, 0));
            }
        }
        return new Round(round, files, files.get(0).getPath());
    }
}
