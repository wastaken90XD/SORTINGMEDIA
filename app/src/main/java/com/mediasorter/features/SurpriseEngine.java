package com.mediasorter.features;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final Map<String, Long> featureHashes = new HashMap<String, Long>();
    private final boolean lowMemory;
    private int deckPosition;
    private int deckWalked;
    private int stridePosition = -1;
    private int strideSize = -1;
    private int poolGeneration = -1;

    public SurpriseEngine(android.content.Context context) {
        prefs = context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE);
        android.app.ActivityManager manager = (android.app.ActivityManager)
                context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        lowMemory = manager != null && manager.getMemoryClass() < 512;
    }

    public synchronized Mode getMode(android.content.SharedPreferences settings) {
        String raw = settings == null ? "uniform" : settings.getString(KEY_MODE, "uniform");
        try { return Mode.valueOf(raw.toUpperCase(Locale.US)); }
        catch (Exception ignored) { return Mode.UNIFORM; }
    }

    public synchronized void cycleMode(android.content.SharedPreferences settings) {
        Mode current = getMode(settings);
        Mode[] values = Mode.values();
        Mode next = values[(current.ordinal() + 1) % values.length];
        if (settings != null) settings.edit().putString(KEY_MODE,
                next.name().toLowerCase(Locale.US)).apply();
    }

    public synchronized void clearSession() {
        history.clear();
        deck.clear();
        featureHashes.clear();
        deckPosition = 0;
        deckWalked = 0;
        stridePosition = -1;
        strideSize = -1;
        poolGeneration = -1;
    }

    public synchronized MediaFile pick(List<MediaFile> pool, Mode requested,
                                        FileStatus status, int generation) {
        return pick(pool, requested, status, generation, null);
    }

    /** Smart result is optional; it supplies the actual uncertainty queue. */
    public synchronized MediaFile pick(List<MediaFile> pool, Mode requested,
                                        FileStatus status, int generation,
                                        SmartSortEngine.Result smartResult) {
        if (pool == null || pool.isEmpty()) return null;
        if (generation != poolGeneration) {
            poolGeneration = generation;
            deck.clear();
            deckPosition = 0;
            deckWalked = 0;
            stridePosition = -1;
            strideSize = -1;
        }
        Mode mode = requested == null ? Mode.UNIFORM : requested;
        MediaFile result = null;
        try {
            switch (mode) {
                case DECK: result = pickDeck(pool); break;
                case STRIDE: result = pickStride(pool); break;
                case WEIGHTED: result = pickWeighted(pool, status); break;
                case DIVERSITY: result = pickDiversity(pool); break;
                case GROUP_FIRST: result = pickGroup(pool); break;
                case LEAST_SURE: result = pickLeastSure(pool, smartResult); break;
                case UNIFORM:
                default: result = pickUniform(pool); break;
            }
        } catch (Throwable ignored) {
            result = null;
        }
        // Every mode has a non-blocking fallback chain.
        if (result == null && mode != Mode.WEIGHTED) result = pickWeighted(pool, status);
        if (result == null) result = pickUniform(pool);
        remember(result.getPath());
        return result;
    }

    private MediaFile pickUniform(List<MediaFile> pool) {
        return pool.get(random.nextInt(pool.size()));
    }

    private MediaFile pickDeck(List<MediaFile> pool) {
        if (deck.size() != pool.size() || deckWalked >= deck.size()) {
            deck.clear();
            for (MediaFile file : pool) deck.add(file.getPath());
            fisherYates(deck);
            deckPosition = 0;
            deckWalked = 0;
        }
        while (deckWalked < deck.size()) {
            String path = deck.get(deckPosition);
            deckPosition = (deckPosition + 1) % deck.size();
            deckWalked++;
            MediaFile file = find(pool, path);
            if (file != null && !history.contains(path)) return file;
        }
        // If history is as large as the pool, walking the exhausted deck is
        // still deterministic; a new shuffle is allowed on the next pick.
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
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

    /** Efraimidis-Spirakis weighted key: -ln(u) / w, smallest wins. */
    private MediaFile pickWeighted(List<MediaFile> pool, FileStatus status) {
        double best = Double.MAX_VALUE;
        MediaFile selected = null;
        for (MediaFile file : pool) {
            if (history.contains(file.getPath()) && pool.size() > history.size()) continue;
            double weight = 1.0;
            if (file.getTags().isEmpty()) weight *= Math.max(1,
                    prefs.getInt(KEY_W_UNTAGGED, 3));
            if (!history.contains(file.getPath())) weight *= Math.max(1,
                    prefs.getInt(KEY_W_STALE, 2));
            if (status != null && status.isFlagged(file.getPath())) weight *= Math.max(1,
                    prefs.getInt(KEY_W_FLAGGED, 1));
            float confidence = SmartState.confidence(file.getPath());
            if (confidence > 0f && confidence < .5f) weight *= Math.max(1,
                    prefs.getInt(KEY_W_UNCERTAIN, 2));
            double u = Math.max(0.000001, random.nextDouble());
            double key = -Math.log(u) / Math.max(0.001, weight);
            if (key < best) {
                best = key;
                selected = file;
            }
        }
        return selected;
    }

    private MediaFile pickDiversity(List<MediaFile> pool) {
        int configured = Math.max(20, Math.min(1000,
                prefs.getInt(KEY_CANDIDATE_CAP, 200)));
        int cap = Math.min(pool.size(), lowMemory ? Math.min(100, configured) : configured);
        ArrayList<MediaFile> candidates = new ArrayList<MediaFile>();
        int attempts = 0;
        while (candidates.size() < cap && attempts < Math.max(cap * 4, pool.size())) {
            MediaFile file = pool.get(random.nextInt(pool.size()));
            if ((!history.contains(file.getPath()) || pool.size() <= history.size())
                    && !candidates.contains(file)) candidates.add(file);
            attempts++;
        }
        if (candidates.isEmpty()) return null;
        double total = 0.0;
        MediaFile best = null;
        double bestScore = -1.0;
        for (MediaFile file : candidates) {
            double distance = minimumHistoryDistance(file, pool);
            if (distance > bestScore) {
                bestScore = distance;
                best = file;
            }
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
        return best;
    }

    private double minimumHistoryDistance(MediaFile file, List<MediaFile> pool) {
        if (history.isEmpty()) return 1.0;
        double best = Double.MAX_VALUE;
        int checked = 0;
        for (String path : history) {
            MediaFile previous = find(pool, path);
            if (previous != null) {
                best = Math.min(best, featureDistance(file, previous));
                if (++checked >= 10) break; // exactly the last ten history files
            }
        }
        return best == Double.MAX_VALUE ? 1.0 : Math.min(1.0, best);
    }

    /** Same feature components as Smart distance, with dHash when decodable. */
    private double featureDistance(MediaFile a, MediaFile b) {
        if (a == null || b == null) return 1.0;
        double value = 0.0;
        double weight = 0.0;
        long hashA = featureHash(a);
        long hashB = featureHash(b);
        if (hashA != Long.MIN_VALUE && hashB != Long.MIN_VALUE) {
            value += Long.bitCount(hashA ^ hashB) / 64.0;
            weight += 35.0;
        }
        String folderA = parent(a.getPath());
        String folderB = parent(b.getPath());
        double folder = folderA.equals(folderB) ? 0.0
                : parent(folderA).equals(parent(folderB)) ? .5 : 1.0;
        value += folder * 15.0;
        weight += 15.0;
        Set<String> left = tokens(a.getName());
        Set<String> right = tokens(b.getName());
        Set<String> union = new java.util.HashSet<String>(left);
        union.addAll(right);
        Set<String> intersection = new java.util.HashSet<String>(left);
        intersection.retainAll(right);
        value += (union.isEmpty() ? 1.0
                : 1.0 - ((double) intersection.size() / union.size())) * 15.0;
        weight += 15.0;
        double hours = Math.abs(a.getDateAdded() - b.getDateAdded()) / 3600000.0;
        double tau = Math.max(1, prefs.getInt("smart_date_tau_hours", 24));
        value += (1.0 - Math.exp(-hours / tau)) * 15.0;
        weight += 15.0;
        if (!a.getColorFamily().isEmpty() && !b.getColorFamily().isEmpty()) {
            value += a.getColorFamily().equalsIgnoreCase(b.getColorFamily()) ? 0.0 : 1.0;
            weight += 10.0;
        }
        value += shapeDistance(a, b) * 10.0;
        weight += 10.0;
        return weight <= 0.0 ? 1.0 : Math.min(1.0, value / weight);
    }

    private double shapeDistance(MediaFile a, MediaFile b) {
        boolean mismatch = a.getType() != b.getType();
        double aspect = .5;
        if (a.getWidth() > 0 && a.getHeight() > 0
                && b.getWidth() > 0 && b.getHeight() > 0) {
            aspect = Math.min(1.0, Math.abs(Math.log(
                    (a.getWidth() / (double) a.getHeight())
                            / (b.getWidth() / (double) b.getHeight())) / Math.log(2.0)));
        }
        double size = a.getSize() <= 0 || b.getSize() <= 0 ? .5
                : Math.min(1.0, Math.abs(Math.log(a.getSize() / (double) b.getSize())
                        / Math.log(2.0)) / 6.0);
        return .5 * aspect + (mismatch ? .3 : 0.0) + .2 * size;
    }

    private long featureHash(MediaFile file) {
        if (file == null || file.getPath() == null) return Long.MIN_VALUE;
        String key = file.getPath() + "|" + file.getSize() + "|" + file.getDateAdded();
        Long cached = featureHashes.get(key);
        if (cached != null) return cached;
        if (file.getType() != MediaFile.Type.IMAGE) return Long.MIN_VALUE;
        long hash = Long.MIN_VALUE;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true);
                    long value = 0L;
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            int left = gray(scaled.getPixel(x, y));
                            int right = gray(scaled.getPixel(x + 1, y));
                            value = (value << 1) | (right > left ? 1L : 0L);
                        }
                    }
                    hash = value;
                    scaled = null;
                    bitmap = null;
                }
            }
        } catch (Throwable ignored) {}
        featureHashes.put(key, hash);
        return hash;
    }

    private int sampleSize(int width, int height) {
        int largest = Math.max(width, height);
        int sample = 1;
        while (largest / sample > 512) sample *= 2;
        return Math.max(1, sample);
    }

    private int gray(int color) {
        return (((color >> 16) & 0xFF) * 299
                + ((color >> 8) & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
    }

    private MediaFile pickGroup(List<MediaFile> pool) {
        LinkedHashMap<String, List<MediaFile>> groups =
                new LinkedHashMap<String, List<MediaFile>>();
        for (MediaFile file : pool) {
            String key = file.getTags().isEmpty() ? "Untagged" : file.getTags().get(0);
            List<MediaFile> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<MediaFile>();
                groups.put(key, group);
            }
            group.add(file);
        }
        if (groups.isEmpty()) return null;
        List<List<MediaFile>> values = new ArrayList<List<MediaFile>>(groups.values());
        List<MediaFile> group = values.get(random.nextInt(values.size()));
        return group.get(random.nextInt(group.size()));
    }

    private MediaFile pickLeastSure(List<MediaFile> pool, SmartSortEngine.Result smartResult) {
        MediaFile best = null;
        int bestRank = Integer.MAX_VALUE;
        if (smartResult != null && smartResult.ranks != null) {
            for (MediaFile file : pool) {
                Integer rank = smartResult.ranks.get(file.getPath());
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    best = file;
                }
            }
        }
        if (best != null) return best;
        float confidence = Float.MAX_VALUE;
        for (MediaFile file : pool) {
            float value = SmartState.confidence(file.getPath());
            if (value < confidence) {
                confidence = value;
                best = file;
            }
        }
        return best;
    }

    private MediaFile find(List<MediaFile> pool, String path) {
        for (MediaFile file : pool) if (path.equals(file.getPath())) return file;
        return null;
    }

    private String parent(String path) {
        if (path == null) return "";
        String parent = new File(path).getParent();
        return parent == null ? "" : parent;
    }

    private Set<String> tokens(String name) {
        Set<String> result = new java.util.HashSet<String>();
        if (name != null) {
            String[] parts = name.toLowerCase(Locale.US).split("[^a-z0-9]+");
            for (String part : parts) if (!part.isEmpty()) result.add(part);
        }
        return result;
    }

    private void remember(String path) {
        if (path == null) return;
        history.remove(path);
        history.add(path);
        int max = Math.max(5, Math.min(500, prefs.getInt(KEY_HISTORY_SIZE, 50)));
        while (history.size() > max) history.remove(history.iterator().next());
    }

    private void fisherYates(List<String> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String value = values.get(i);
            values.set(i, values.get(j));
            values.set(j, value);
        }
    }

    private int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return Math.abs(a);
    }
}
