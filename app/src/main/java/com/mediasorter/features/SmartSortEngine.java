package com.mediasorter.features;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small, cancellable background learner for Smart Sort. Tags are the only
 * labels; no learned state is persisted. The worker deliberately uses simple
 * primitive/string feature arrays and yields between chunks.
 */
public class SmartSortEngine {
    public interface Listener { void onSmartResult(Result result); }
    public static class Result {
        public final Map<String, Integer> ranks = new HashMap<String, Integer>();
        public final Map<String, Float> confidence = new HashMap<String, Float>();
        public final Map<String, String> suggestions = new HashMap<String, String>();
        public int labeled;
        public int suggested;
        public int unsure;
        public long durationMs;
        public int generation;
        public boolean slowedDown;
    }

    private static class Features {
        final long[] hashes;
        final boolean[] hashPresent;
        Features(int size) {
            hashes = new long[size];
            hashPresent = new boolean[size];
        }
    }

    private final android.content.SharedPreferences prefs;
    private final Handler mainHandler;
    private final HandlerThread thread = new HandlerThread("MagnusSmartSort",
            Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final Listener listener;
    private final SmartFeatureCache featureCache;
    private final boolean lowMemory;
    private volatile boolean enabled;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile boolean scanRunning;
    private volatile long lastActivity;
    private int generation;
    private int consecutiveSlowPasses;

    public SmartSortEngine(Context context, Handler main, Listener resultListener) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        mainHandler = main;
        listener = resultListener;
        featureCache = new SmartFeatureCache(context.getFilesDir());
        android.app.ActivityManager manager = (android.app.ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        lowMemory = manager != null && manager.getMemoryClass() < 512;
        thread.start();
        worker = new Handler(thread.getLooper());
        enabled = prefs.getBoolean("smart_enabled", false);
        paused = prefs.getBoolean("smart_paused", false);
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) cancel();
    }

    public boolean isEnabled() { return enabled; }
    public void setPaused(boolean value) { paused = value; }
    public void setScanRunning(boolean value) { scanRunning = value; }
    public void noteActivity() { lastActivity = SystemClock.uptimeMillis(); }

    public void request(final List<MediaFile> files) {
        if (!enabled || closed || files == null || files.isEmpty()) return;
        final int requestGeneration = ++generation;
        int configuredCap = Math.max(500, prefs.getInt("smart_max_pool", 5000));
        int cap = lowMemory ? Math.min(1500, configuredCap) : configuredCap;
        final List<MediaFile> snapshot = files.size() <= cap
                ? new ArrayList<MediaFile>(files)
                : new ArrayList<MediaFile>(files.subList(0, cap));
        worker.post(new Runnable() {
            @Override public void run() {
                Result result = rank(snapshot, requestGeneration);
                if (result == null || closed || requestGeneration != generation || !enabled) return;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!closed && requestGeneration == generation && listener != null) {
                            listener.onSmartResult(result);
                        }
                    }
                });
            }
        });
    }

    public void cancel() { generation++; }
    public void onPause() { paused = true; }
    public void onResume() { paused = prefs.getBoolean("smart_paused", false); }

    public void close() {
        closed = true;
        cancel();
        thread.quitSafely();
    }

    private Result rank(List<MediaFile> files, int requestGeneration) {
        long started = SystemClock.uptimeMillis();
        Result result = new Result();
        result.generation = requestGeneration;
        ArrayList<Integer> labeled = new ArrayList<Integer>();
        ArrayList<Integer> unlabeled = new ArrayList<Integer>();
        Pattern ignore = compileIgnore();
        String activePile = prefs.getString("smart_active_pile", "");
        boolean scopePile = "scope".equals(prefs.getString("smart_pile_mode", "scope"))
                && !activePile.trim().isEmpty();
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (scopePile && !file.hasTag(activePile)) continue;
            if (isLabeled(file, ignore, activePile)) labeled.add(i); else unlabeled.add(i);
        }
        result.labeled = labeled.size();
        Features features = buildFeatures(files, requestGeneration);
        if (features == null) return null;
        int rank = 0;
        final String orderMode = prefs.getString("smart_order_mode", "auto");
        final boolean unlabeledFirst = !unlabeled.isEmpty()
                && ("queue".equals(orderMode) || "auto".equals(orderMode));
        final float threshold = prefs.getInt("smart_suggest_threshold", 60) / 100f;
        final int chunk = Math.max(5, Math.min(50, prefs.getInt("smart_chunk_ms", 20)
                / (lowMemory ? 2 : 1)));
        ArrayList<Prediction> predictions = new ArrayList<Prediction>();
        for (Integer index : unlabeled) {
            if (requestGeneration != generation || closed) return null;
            waitForSlice(chunk);
            MediaFile target = files.get(index);
            Prediction prediction = predict(target, index, files, labeled, features);
            predictions.add(prediction);
            result.confidence.put(target.getPath(), prediction.confidence);
            int occurrences = prediction.tag == null ? 0 : countLabeledTag(
                    prediction.tag, files, labeled);
            float localThreshold = occurrences > 0 && occurrences <= 3
                    ? Math.max(.30f, threshold - .20f) : threshold;
            if (prediction.tag != null && prediction.confidence >= localThreshold) {
                result.suggestions.put(target.getPath(), prediction.tag);
                result.suggested++;
            } else result.unsure++;
        }
        java.util.Collections.sort(predictions, new java.util.Comparator<Prediction>() {
            @Override public int compare(Prediction left, Prediction right) {
                if ("arranged".equals(orderMode)) {
                    String a = left.tag == null ? "~" : left.tag;
                    String b = right.tag == null ? "~" : right.tag;
                    int group = a.compareToIgnoreCase(b);
                    if (group != 0) return group;
                }
                return Float.compare(left.confidence, right.confidence);
            }
        });
        for (Prediction prediction : predictions) result.ranks.put(prediction.path, rank++);
        if (unlabeledFirst) {
            for (Integer index : labeled) result.ranks.put(files.get(index).getPath(), rank++);
        }
        result.durationMs = SystemClock.uptimeMillis() - started;
        int budget = Math.max(200, prefs.getInt("smart_pass_budget_ms", 1500));
        if (result.durationMs > budget) consecutiveSlowPasses++;
        else consecutiveSlowPasses = 0;
        if (consecutiveSlowPasses >= 3) {
            int current = Math.max(1, prefs.getInt("smart_k_neighbors", 5));
            prefs.edit().putInt("smart_k_neighbors", Math.max(1, current / 2)).apply();
            result.slowedDown = true;
            consecutiveSlowPasses = 0;
        }
        return result;
    }

    private Features buildFeatures(List<MediaFile> files, int requestGeneration) {
        Features features = new Features(files.size());
        for (int i = 0; i < files.size(); i++) {
            if (requestGeneration != generation || closed) return null;
            waitForSlice(Math.max(1, prefs.getInt("smart_chunk_ms", 20)));
            MediaFile file = files.get(i);
            if (file == null || file.getPath() == null) continue;
            SmartFeatureCache.Entry cached = featureCache.get(file.getPath(), file.getSize(),
                    file.getDateAdded());
            if (cached != null) {
                features.hashes[i] = cached.hash;
                features.hashPresent[i] = cached.present;
                continue;
            }
            if (file.getType() == MediaFile.Type.IMAGE) {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getPath(), bounds);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                    if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                        long hash = 0L;
                        for (int y = 0; y < 8; y++) {
                            int previous = grayAt(bitmap, 0, y);
                            for (int x = 0; x < 8; x++) {
                                int next = grayAt(bitmap, x + 1, y);
                                hash <<= 1;
                                if (next > previous) hash |= 1L;
                                previous = next;
                            }
                        }
                        features.hashes[i] = hash;
                        features.hashPresent[i] = true;
                    }
                    // Do not recycle: release the local reference and let GC handle it.
                    bitmap = null;
                } catch (OutOfMemoryError memory) {
                    features.hashPresent[i] = false;
                } catch (Throwable ignored) {
                    features.hashPresent[i] = false;
                }
            }
            featureCache.put(file.getPath(), file.getSize(), file.getDateAdded(),
                    features.hashes[i], features.hashPresent[i]);
        }
        featureCache.save();
        return features;
    }

    private int sampleSize(int width, int height) {
        int largest = Math.max(width, height);
        int sample = 1;
        while (largest / sample > 256) sample *= 2;
        return Math.max(1, sample);
    }

    private int grayAt(Bitmap bitmap, int x, int y) {
        int safeX = Math.max(0, Math.min(bitmap.getWidth() - 1,
                x * bitmap.getWidth() / 9));
        int safeY = Math.max(0, Math.min(bitmap.getHeight() - 1,
                y * bitmap.getHeight() / 8));
        int color = bitmap.getPixel(safeX, safeY);
        return (ColorRed(color) * 299 + ColorGreen(color) * 587 + ColorBlue(color) * 114) / 1000;
    }
    private int ColorRed(int color) { return (color >> 16) & 0xFF; }
    private int ColorGreen(int color) { return (color >> 8) & 0xFF; }
    private int ColorBlue(int color) { return color & 0xFF; }

    private void waitForSlice(int chunkMs) {
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < chunkMs) {
            if (scanRunning || paused || prefs.getBoolean("smart_paused", false)
                    || SystemClock.uptimeMillis() - lastActivity < prefs.getInt("rerank_idle_ms", 400)
                            * (lowMemory ? 2 : 1)) {
                SystemClock.sleep(16);
            } else break;
        }
    }

    private boolean isLabeled(MediaFile file, Pattern ignore, String activePile) {
        if (file == null || file.getTags() == null) return false;
        for (String tag : file.getTags()) {
            if (tag == null || tag.trim().isEmpty()) continue;
            if (activePile != null && !activePile.isEmpty() && activePile.equals(tag)) continue;
            if (ignore == null || !ignore.matcher(tag).matches()) return true;
        }
        return false;
    }

    private Pattern compileIgnore() {
        String raw = prefs.getString("smart_ignore_tag_patterns", "link_*,*_seq_*");
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder expression = new StringBuilder();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            if (expression.length() > 0) expression.append("|");
            StringBuilder wildcard = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '*') wildcard.append(".*");
                else wildcard.append(Pattern.quote(String.valueOf(ch)));
            }
            expression.append(wildcard);
        }
        try { return expression.length() == 0 ? null : Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignored) { return null; }
    }

    private static class Prediction {
        String path;
        String tag;
        float confidence;
    }

    private Prediction predict(MediaFile target, int targetIndex, List<MediaFile> files,
                               List<Integer> labeled, Features features) {
        Prediction result = new Prediction();
        result.path = target.getPath();
        if (labeled.isEmpty()) return result;
        ArrayList<Neighbor> neighbors = new ArrayList<Neighbor>();
        for (Integer index : labeled) {
            MediaFile candidate = files.get(index);
            float distance = distance(target, targetIndex, candidate, index, features);
            neighbors.add(new Neighbor(candidate, distance));
        }
        java.util.Collections.sort(neighbors, new java.util.Comparator<Neighbor>() {
            @Override public int compare(Neighbor left, Neighbor right) {
                return Float.compare(left.distance, right.distance);
            }
        });
        int limit = Math.max(1, Math.min(15, prefs.getInt("smart_k_neighbors", 5)));
        Map<String, Float> votes = new HashMap<String, Float>();
        float total = 0f;
        float nearest = neighbors.isEmpty() ? 1f : neighbors.get(0).distance;
        for (int i = 0; i < neighbors.size() && i < limit; i++) {
            Neighbor neighbor = neighbors.get(i);
            float weight = 1f / (.05f + neighbor.distance);
            Set<String> unique = new HashSet<String>();
            for (String raw : neighbor.file.getTags()) {
                String tag = raw == null ? "" : raw.trim();
                if (tag.isEmpty() || !unique.add(tag)) continue;
                Float old = votes.get(tag);
                votes.put(tag, old == null ? weight : old + weight);
                total += weight;
            }
        }
        String bestTag = null;
        float bestVote = 0f;
        for (Map.Entry<String, Float> entry : votes.entrySet()) {
            if (entry.getValue() > bestVote) {
                bestVote = entry.getValue();
                bestTag = entry.getKey();
            }
        }
        result.tag = bestTag;
        result.confidence = total <= 0f ? 0f
                : (bestVote / total) * (1f - Math.min(1f, nearest));
        return result;
    }

    private static class Neighbor {
        final MediaFile file;
        final float distance;
        Neighbor(MediaFile value, float score) { file = value; distance = score; }
    }

    private int countLabeledTag(String tag, List<MediaFile> files, List<Integer> labeled) {
        int count = 0;
        for (Integer index : labeled) if (files.get(index).hasTag(tag)) count++;
        return count;
    }

    private float distance(MediaFile a, int ai, MediaFile b, int bi, Features features) {
        if (a == null || b == null) return 1f;
        float value = 0f;
        float weight = 0f;
        int wHash = prefs.getInt("smart_w_hash", 35);
        int wName = prefs.getInt("smart_w_name", 15);
        int wFolder = prefs.getInt("smart_w_folder", 15);
        int wDate = prefs.getInt("smart_w_date", 15);
        int wColor = prefs.getInt("smart_w_color", 10);
        int wShape = prefs.getInt("smart_w_shape", 10);
        if (wHash > 0 && features.hashPresent[ai] && features.hashPresent[bi]) {
            value += wHash * (Long.bitCount(features.hashes[ai] ^ features.hashes[bi]) / 64f);
            weight += wHash;
        }
        if (wName > 0) { value += wName * nameDistance(a, b); weight += wName; }
        if (wFolder > 0) { value += wFolder * folderDistance(a, b); weight += wFolder; }
        if (wDate > 0) { value += wDate * dateDistance(a, b); weight += wDate; }
        if (wColor > 0 && a.getColorFamily() != null && b.getColorFamily() != null) {
            if (!a.getColorFamily().isEmpty() && !b.getColorFamily().isEmpty()) {
                value += wColor * (a.getColorFamily().equalsIgnoreCase(b.getColorFamily()) ? 0f : 1f);
                weight += wColor;
            }
        }
        if (wShape > 0) { value += wShape * shapeDistance(a, b); weight += wShape; }
        return weight == 0f ? 1f : value / weight;
    }

    private float nameDistance(MediaFile a, MediaFile b) {
        Set<String> left = tokens(a.getName());
        Set<String> right = tokens(b.getName());
        Set<String> union = new HashSet<String>(left); union.addAll(right);
        Set<String> intersection = new HashSet<String>(left); intersection.retainAll(right);
        return union.isEmpty() ? 1f : 1f - ((float) intersection.size() / union.size());
    }
    private Set<String> tokens(String name) {
        Set<String> result = new HashSet<String>();
        if (name != null) for (String part : name.toLowerCase(Locale.US).split("[^a-z0-9]+")) if (!part.isEmpty()) result.add(part);
        return result;
    }
    private float folderDistance(MediaFile a, MediaFile b) {
        String left = new File(a.getPath()).getParent();
        String right = new File(b.getPath()).getParent();
        if (left == null || right == null) return 1f;
        if (left.equals(right)) return 0f;
        String lp = new File(left).getParent();
        String rp = new File(right).getParent();
        return lp != null && lp.equals(rp) ? .5f : 1f;
    }
    private float dateDistance(MediaFile a, MediaFile b) {
        double hours = Math.abs(a.getDateAdded() - b.getDateAdded()) / 3600000.0;
        double tau = Math.max(1, prefs.getInt("smart_date_tau_hours", 24));
        return (float) (1.0 - Math.exp(-hours / tau));
    }
    private float shapeDistance(MediaFile a, MediaFile b) {
        boolean type = a.getType() != b.getType();
        float ar = a.getWidth() > 0 && a.getHeight() > 0 && b.getWidth() > 0 && b.getHeight() > 0
                ? Math.min(1f, Math.abs((float) Math.log((a.getWidth() / (double) a.getHeight())
                / (b.getWidth() / (double) b.getHeight())))) : .5f;
        return .5f * ar + (type ? .3f : 0f) + .2f * sizeDistance(a, b);
    }
    private float sizeDistance(MediaFile a, MediaFile b) {
        if (a.getSize() <= 0 || b.getSize() <= 0) return .5f;
        return Math.min(1f, Math.abs((float) (Math.log(a.getSize()) - Math.log(b.getSize()))) / 6f);
    }
}
