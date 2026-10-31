package com.mediasorter.features;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Pure, API-21-safe picker for Surprise Me modes. */
public class SurpriseEngine {
    public static final String KEY_MODE = "surprise_mode";
    public static final String KEY_HISTORY_SIZE = "surprise_history_size";
    public static final String KEY_W_UNTAGGED = "surprise_w_untagged";
    public static final String KEY_W_STALE = "surprise_w_stale";
    public static final String KEY_W_FLAGGED = "surprise_w_flagged";
    public static final String KEY_W_UNCERTAIN = "surprise_w_uncertain";
    public static final String KEY_DIVERSITY_SOFT = "surprise_diversity_soft";
    public static final String KEY_CANDIDATE_CAP = "surprise_candidate_cap";

    public enum Mode { UNIFORM, DECK, STRIDE, WEIGHTED, DIVERSITY, GROUP_FIRST, LEAST_SURE }

    private final Random random = new Random();
    private final android.content.SharedPreferences prefs;
    private final LinkedHashSet<String> history = new LinkedHashSet<String>();
    private final ArrayList<String> deck = new ArrayList<String>();
    private int deckPosition;
    private int stridePosition = -1;
    private int strideSize = -1;
    private int poolGeneration = -1;

    public SurpriseEngine(Context context) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
    }

    public synchronized Mode getMode(android.content.SharedPreferences prefs) {
        String raw = prefs == null ? "uniform" : prefs.getString(KEY_MODE, "uniform");
        try { return Mode.valueOf(raw.toUpperCase(java.util.Locale.US)); }
        catch (Exception ignored) { return Mode.UNIFORM; }
    }

    public synchronized void cycleMode(android.content.SharedPreferences prefs) {
        Mode current = getMode(prefs);
        Mode[] values = Mode.values();
        Mode next = values[(current.ordinal() + 1) % values.length];
        if (prefs != null) prefs.edit().putString(KEY_MODE, next.name().toLowerCase(java.util.Locale.US)).apply();
    }

    public synchronized void clearSession() {
        history.clear();
        deck.clear();
        deckPosition = 0;
        stridePosition = -1;
        strideSize = -1;
        poolGeneration = -1;
    }

    public synchronized MediaFile pick(List<MediaFile> pool, Mode requested,
                                        FileStatus status, int generation) {
        if (pool == null || pool.isEmpty()) return null;
        if (generation != poolGeneration) {
            poolGeneration = generation;
            deck.clear();
            deckPosition = 0;
            stridePosition = -1;
            strideSize = -1;
        }
        Mode mode = requested == null ? Mode.UNIFORM : requested;
        MediaFile result;
        switch (mode) {
            case DECK: result = pickDeck(pool); break;
            case STRIDE: result = pickStride(pool); break;
            case WEIGHTED: result = pickWeighted(pool, status); break;
            case DIVERSITY: result = pickDiversity(pool); break;
            case GROUP_FIRST: result = pickGroup(pool); break;
            case LEAST_SURE: result = pickLeastSure(pool); break;
            case UNIFORM:
            default: result = pickUniform(pool);
        }
        if (result == null) result = pickUniform(pool);
        remember(result.getPath(), statusSize(pool));
        return result;
    }

    private int statusSize(List<MediaFile> pool) { return pool == null ? 50 : pool.size(); }

    private MediaFile pickUniform(List<MediaFile> pool) {
        List<MediaFile> candidates = withoutHistory(pool);
        if (candidates.isEmpty()) candidates = pool;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private MediaFile pickDeck(List<MediaFile> pool) {
        if (deck.size() != pool.size() || deckPosition >= deck.size()) {
            deck.clear();
            for (MediaFile file : pool) deck.add(file.getPath());
            RandomGenerator.shuffle(deck);
            deckPosition = 0;
        }
        for (int i = 0; i < deck.size(); i++) {
            String path = deck.get((deckPosition + i) % deck.size());
            if (!history.contains(path)) {
                deckPosition = (deckPosition + i + 1) % deck.size();
                return find(pool, path);
            }
        }
        return pickUniform(pool);
    }

    private MediaFile pickStride(List<MediaFile> pool) {
        if (pool.size() < 2) return pool.get(0);
        if (strideSize != pool.size() || stridePosition < 0) {
            strideSize = pool.size();
            stridePosition = random.nextInt(strideSize);
        }
        int step = Math.max(1, (int) Math.round(strideSize / 1.618));
        while (gcd(step, strideSize) != 1) step++;
        MediaFile result = pool.get(stridePosition);
        stridePosition = (stridePosition + step) % strideSize;
        return result;
    }

    private MediaFile pickWeighted(List<MediaFile> pool, FileStatus status) {
        double best = Double.MAX_VALUE;
        MediaFile selected = null;
        for (MediaFile file : pool) {
            if (history.contains(file.getPath()) && pool.size() > history.size()) continue;
            double weight = 1.0;
            if (file.getTags().isEmpty()) weight += Math.max(0, prefs.getInt(KEY_W_UNTAGGED, 3) - 1);
            if (!history.contains(file.getPath())) weight += Math.max(0, prefs.getInt(KEY_W_STALE, 2) - 1);
            if (status != null && status.isFlagged(file.getPath())) {
                weight += Math.max(0, prefs.getInt(KEY_W_FLAGGED, 1) - 1);
            }
            float confidence = SmartState.confidence(file.getPath());
            if (confidence > 0f && confidence < .5f) {
                weight += Math.max(0, prefs.getInt(KEY_W_UNCERTAIN, 2) - 1);
            }
            double u = Math.max(0.000001, random.nextDouble());
            double key = -Math.log(u) / Math.max(0.001, weight);
            if (key < best) { best = key; selected = file; }
        }
        return selected == null ? pickUniform(pool) : selected;
    }

    private MediaFile pickDiversity(List<MediaFile> pool) {
        int cap = Math.min(pool.size(), Math.max(20,
                Math.min(1000, prefs.getInt(KEY_CANDIDATE_CAP, 200))));
        List<MediaFile> candidates = new ArrayList<MediaFile>();
        for (int i = 0; i < cap; i++) {
            MediaFile file = pool.get(random.nextInt(pool.size()));
            if (history.contains(file.getPath()) && pool.size() > history.size()) continue;
            if (!candidates.contains(file)) candidates.add(file);
        }
        if (candidates.isEmpty()) return pickWeighted(pool, null);
        double total = 0.0;
        MediaFile best = null;
        double bestScore = -1.0;
        for (MediaFile file : candidates) {
            double distance = minimumHistoryDistance(file, pool);
            if (distance > bestScore) { bestScore = distance; best = file; }
            total += Math.max(0.001, distance);
        }
        if (prefs.getBoolean(KEY_DIVERSITY_SOFT, true) && total > 0.0) {
            double target = random.nextDouble() * total;
            double seen = 0.0;
            for (MediaFile file : candidates) {
                seen += Math.max(0.001, minimumHistoryDistance(file, pool));
                if (seen >= target) return file;
            }
        }
        return best == null ? pickWeighted(pool, null) : best;
    }

    private double minimumHistoryDistance(MediaFile file, List<MediaFile> pool) {
        if (history.isEmpty()) return 1.0;
        double best = Double.MAX_VALUE;
        int checked = 0;
        for (String path : history) {
            MediaFile previous = find(pool, path);
            if (previous != null) {
                best = Math.min(best, featureDistance(file, previous));
                if (++checked >= 10) break;
            }
        }
        return best == Double.MAX_VALUE ? 1.0 : Math.min(1.0, best);
    }

    private double featureDistance(MediaFile a, MediaFile b) {
        if (a == null || b == null) return 1.0;
        double value = 0.0;
        int components = 0;
        if (a.getType() != null && b.getType() != null) {
            value += a.getType() == b.getType() ? 0.0 : 1.0; components++;
        }
        value += a.getTags().isEmpty() == b.getTags().isEmpty() ? 0.0 : 1.0; components++;
        value += a.getName().equalsIgnoreCase(b.getName()) ? 0.0 : .5; components++;
        value += Math.min(1.0, Math.abs(a.getSize() - (double) b.getSize())
                / Math.max(1.0, Math.max(a.getSize(), b.getSize()))); components++;
        return components == 0 ? 1.0 : value / components;
    }

    private MediaFile pickGroup(List<MediaFile> pool) {
        java.util.LinkedHashMap<String, List<MediaFile>> groups =
                new java.util.LinkedHashMap<String, List<MediaFile>>();
        for (MediaFile file : pool) {
            String key = file.getTags().isEmpty() ? "Untagged" : file.getTags().get(0);
            List<MediaFile> group = groups.get(key);
            if (group == null) { group = new ArrayList<MediaFile>(); groups.put(key, group); }
            group.add(file);
        }
        if (groups.isEmpty()) return null;
        List<List<MediaFile>> values = new ArrayList<List<MediaFile>>(groups.values());
        return pickUniform(values.get(random.nextInt(values.size())));
    }

    private MediaFile pickLeastSure(List<MediaFile> pool) { return pickWeighted(pool, null); }

    private List<MediaFile> withoutHistory(List<MediaFile> pool) {
        List<MediaFile> result = new ArrayList<MediaFile>();
        for (MediaFile file : pool) if (!history.contains(file.getPath())) result.add(file);
        return result;
    }

    private MediaFile find(List<MediaFile> pool, String path) {
        for (MediaFile file : pool) if (path.equals(file.getPath())) return file;
        return null;
    }

    private void remember(String path, int poolSize) {
        if (path == null) return;
        history.remove(path);
        history.add(path);
        int max = Math.max(5, Math.min(500,
                prefs.getInt(KEY_HISTORY_SIZE, 50)));
        while (history.size() > max) {
            String first = history.iterator().next();
            history.remove(first);
        }
    }

    private int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return Math.abs(a);
    }
}
