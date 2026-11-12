package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
    private final java.util.Map<String, Integer> noneReturns =
            new java.util.HashMap<String, Integer>();

    public synchronized Round buildRound(List<MediaFile> pool, int batchSize, int decoys,
                                          Map<String, Float> confidence,
                                          String activePile, int probeEvery) {
        List<MediaFile> candidates = new ArrayList<MediaFile>();
        if (pool != null) {
            for (MediaFile file : pool) {
                if (file == null) continue;
                if (activePile != null && !activePile.isEmpty() && !file.hasTag(activePile)) continue;
                if (file.getTags().isEmpty()) candidates.add(file);
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
        int targetSize = Math.max(4, Math.min(12, batchSize));
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
    public synchronized void reset() { round = 0; noneCooldown = 0; noneReturns.clear(); }
    public synchronized void noteNone(String path) {
        if (path == null) return;
        noneReturns.put(path, getNoneReturns(path) + 1);
        noneCooldown = 3;
    }
    public synchronized int getNoneReturns(String path) {
        Integer value = noneReturns.get(path);
        return value == null ? 0 : value;
    }
    public synchronized boolean canReturn(String path, int cooldown, int maxReturns) {
        return noneCooldown <= 0 && getNoneReturns(path) < Math.max(1, maxReturns);
    }
    public synchronized void nextRound() { if (noneCooldown > 0) noneCooldown--; }
}
