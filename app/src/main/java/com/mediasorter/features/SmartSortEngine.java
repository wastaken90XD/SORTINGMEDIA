package com.mediasorter.features;

import android.content.Context;
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
    }

    private final android.content.SharedPreferences prefs;
    private final Handler mainHandler;
    private final HandlerThread thread = new HandlerThread("MagnusSmartSort",
            Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker;
    private final Listener listener;
    private volatile boolean enabled;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile long lastActivity;
    private int generation;

    public SmartSortEngine(Context context, Handler main, Listener resultListener) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        mainHandler = main;
        listener = resultListener;
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
    public void noteActivity() { lastActivity = SystemClock.uptimeMillis(); }

    public void request(final List<MediaFile> files) {
        if (!enabled || closed || files == null || files.isEmpty()) return;
        final int requestGeneration = ++generation;
        final List<MediaFile> snapshot = new ArrayList<MediaFile>(files);
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
        for (int i = 0; i < files.size(); i++) {
            if (isLabeled(files.get(i), ignore)) labeled.add(i); else unlabeled.add(i);
        }
        result.labeled = labeled.size();
        int rank = 0;
        for (Integer index : labeled) result.ranks.put(files.get(index).getPath(), rank++);
        final float threshold = prefs.getInt("smart_suggest_threshold", 60) / 100f;
        final int chunk = Math.max(5, Math.min(50, prefs.getInt("smart_chunk_ms", 20)));
        ArrayList<Prediction> predictions = new ArrayList<Prediction>();
        for (Integer index : unlabeled) {
            if (requestGeneration != generation || closed) return null;
            waitForSlice(chunk);
            MediaFile target = files.get(index);
            Prediction prediction = predict(target, files, labeled);
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
                return Float.compare(left.confidence, right.confidence);
            }
        });
        for (Prediction prediction : predictions) result.ranks.put(prediction.path, rank++);
        result.durationMs = SystemClock.uptimeMillis() - started;
        return result;
    }

    private void waitForSlice(int chunkMs) {
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < chunkMs) {
            if (paused || prefs.getBoolean("smart_paused", false)
                    || SystemClock.uptimeMillis() - lastActivity < prefs.getInt("rerank_idle_ms", 400)) {
                SystemClock.sleep(16);
            } else break;
        }
    }

    private boolean isLabeled(MediaFile file, Pattern ignore) {
        if (file == null || file.getTags() == null) return false;
        for (String tag : file.getTags()) {
            if (tag != null && !tag.trim().isEmpty() && (ignore == null || !ignore.matcher(tag).matches())) return true;
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
            expression.append(Pattern.quote(value).replace("\\Q*\\E", ".*"));
        }
        try { return expression.length() == 0 ? null : Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE); }
        catch (Exception ignored) { return null; }
    }

    private static class Prediction {
        String path;
        String tag;
        float confidence;
    }

    private Prediction predict(MediaFile target, List<MediaFile> files, List<Integer> labeled) {
        Prediction result = new Prediction();
        result.path = target.getPath();
        if (labeled.isEmpty()) return result;
        ArrayList<Neighbor> neighbors = new ArrayList<Neighbor>();
        for (Integer index : labeled) {
            MediaFile candidate = files.get(index);
            float distance = distance(target, candidate);
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

    private float distance(MediaFile a, MediaFile b) {
        if (a == null || b == null) return 1f;
        float value = 0f;
        float weight = 0f;
        int wName = prefs.getInt("smart_w_name", 15);
        int wFolder = prefs.getInt("smart_w_folder", 15);
        int wDate = prefs.getInt("smart_w_date", 15);
        int wShape = prefs.getInt("smart_w_shape", 10);
        int wSize = prefs.getInt("smart_w_hash", 35);
        if (wName > 0) { value += wName * nameDistance(a, b); weight += wName; }
        if (wFolder > 0) { value += wFolder * folderDistance(a, b); weight += wFolder; }
        if (wDate > 0) { value += wDate * dateDistance(a, b); weight += wDate; }
        if (wShape > 0) { value += wShape * shapeDistance(a, b); weight += wShape; }
        if (wSize > 0) { value += wSize * sizeDistance(a, b); weight += wSize; }
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
