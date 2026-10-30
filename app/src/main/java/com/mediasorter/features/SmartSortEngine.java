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
        float threshold = prefs.getInt("smart_suggest_threshold", 60) / 100f;
        int chunk = Math.max(5, Math.min(50, prefs.getInt("smart_chunk_ms", 20)));
        for (Integer index : unlabeled) {
            if (requestGeneration != generation || closed) return null;
            waitForSlice(chunk);
            MediaFile target = files.get(index);
            String suggested = suggest(target, files, labeled);
            float confidence = suggested == null ? 0f : confidence(target, files, labeled);
            result.confidence.put(target.getPath(), confidence);
            if (suggested != null && confidence >= threshold) {
                result.suggestions.put(target.getPath(), suggested);
                result.suggested++;
            } else result.unsure++;
            // Queue order: least confident unlabeled files first. A stable
            // fallback rank keeps all files visible even before labels exist.
            result.ranks.put(target.getPath(), rank++);
        }
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

    private String suggest(MediaFile target, List<MediaFile> files, List<Integer> labeled) {
        if (labeled.isEmpty()) return null;
        float best = Float.MAX_VALUE;
        MediaFile nearest = null;
        for (Integer index : labeled) {
            MediaFile candidate = files.get(index);
            float distance = distance(target, candidate);
            if (distance < best) { best = distance; nearest = candidate; }
        }
        if (nearest == null || nearest.getTags().isEmpty()) return null;
        return nearest.getTags().get(0);
    }

    private float confidence(MediaFile target, List<MediaFile> files, List<Integer> labeled) {
        float best = Float.MAX_VALUE;
        for (Integer index : labeled) best = Math.min(best, distance(target, files.get(index)));
        return best == Float.MAX_VALUE ? 0f : Math.max(0f, 1f - Math.min(1f, best));
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
