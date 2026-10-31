package com.mediasorter.features;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cancellable, background-only learner for Smart Sort.
 *
 * The worker owns a single primitive-array feature table for a pass.  It never
 * retains bitmaps and it publishes only immutable-ish result snapshots to the
 * main thread.  The Activity can therefore request a new generation whenever
 * tags change without allowing a stale pass to reorder the UI.
 */
public class SmartSortEngine {
    public interface Listener { void onSmartResult(Result result); }

    public static class Result {
        public final Map<String, Integer> ranks = new HashMap<String, Integer>();
        public final Map<String, Float> confidence = new HashMap<String, Float>();
        public final Map<String, String> suggestions = new HashMap<String, String>();
        public final Map<String, Integer> groupPositions = new HashMap<String, Integer>();
        public final Map<String, Integer> groupTotals = new HashMap<String, Integer>();
        public final List<Group> groups = new ArrayList<Group>();
        public int labeled;
        public int suggested;
        public int unsure;
        public long durationMs;
        public int generation;
        public boolean slowedDown;
        public boolean arranged;
        public boolean lowMemoryDevice;
        public boolean incrementalNeighbors;
        public int featureCount;
        public int missingFeatureCount;
        public long featureBytes;
        public long neighborComparisons;
    }

    private static class Features {
        final long[] hashes;
        final boolean[] hashPresent;
        Features(int size) {
            hashes = new long[size];
            hashPresent = new boolean[size];
        }
    }

    private static class NeighborTable {
        final int k;
        final int[] indexes;
        final float[] distances;
        final String[] paths;
        long comparisons;

        NeighborTable(int fileCount, int neighbors) {
            k = neighbors;
            indexes = new int[Math.max(1, fileCount * neighbors)];
            distances = new float[Math.max(1, fileCount * neighbors)];
            paths = new String[Math.max(1, fileCount * neighbors)];
            Arrays.fill(indexes, -1);
            Arrays.fill(distances, Float.MAX_VALUE);
        }

        int offset(int fileIndex) { return fileIndex * k; }
    }

    private static class Prediction {
        String path;
        String tag;
        float confidence;
        int index;
        boolean suggested;
    }

    private static class GroupItem {
        MediaFile file;
        String path;
        boolean confirmed;
        float confidence;
    }

    private static class SmartGroupData {
        String label;
        final List<GroupItem> items = new ArrayList<GroupItem>();
        SmartGroupData(String value) { label = value; }
    }

    private final android.content.SharedPreferences prefs;
    private final Handler mainHandler;
    private HandlerThread thread;
    private Handler worker;
    private final Listener listener;
    private final SmartFeatureCache featureCache;
    private final boolean lowMemory;
    private final Set<String> featureFailures = new HashSet<String>();
    private final Random random = new Random();
    private volatile boolean enabled;
    private volatile boolean paused;
    private volatile boolean closed;
    private volatile boolean scanRunning;
    private volatile long lastActivity;
    private volatile int generation;
    private int consecutiveSlowPasses;
    private boolean skipDiversityOrdering;
    private boolean slowdownToastShown;
    private long sliceStarted;
    // Session-local incremental neighbour snapshot. Paths are references to
    // MediaFile strings; distances and indexes remain primitive arrays.
    private int previousNeighborK = -1;
    private String[] previousPaths;
    private String[] previousNeighborPaths;
    private float[] previousNeighborDistances;
    private Set<String> previousLabeledPaths = new HashSet<String>();
    private Map<String, String> previousFileVersions = new HashMap<String, String>();
    private boolean lastNeighborSnapshotReused;

    public SmartSortEngine(Context context, Handler main, Listener resultListener) {
        prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE);
        mainHandler = main;
        listener = resultListener;
        featureCache = new SmartFeatureCache(context.getFilesDir());
        android.app.ActivityManager manager = (android.app.ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        lowMemory = manager != null && manager.getMemoryClass() < 512;
        enabled = prefs.getBoolean("smart_enabled", false);
        paused = prefs.getBoolean("smart_paused", false);
    }

    public synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            cancel();
            clearNeighborSnapshot();
            if (thread != null) thread.quitSafely();
            thread = null;
            worker = null;
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setPaused(boolean value) { paused = value; }
    public void setScanRunning(boolean value) { scanRunning = value; }
    public void noteActivity() { lastActivity = SystemClock.uptimeMillis(); }

    private synchronized void ensureWorker() {
        if (closed || worker != null) return;
        thread = new HandlerThread("MagnusSmartSort", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** Takes one full-index snapshot per pass and never reads the WindowManager page. */
    public void request(final List<MediaFile> files) {
        if (!enabled || closed || files == null || files.isEmpty()) return;
        ensureWorker();
        final int requestGeneration = ++generation;
        int configuredCap = Math.max(500, prefs.getInt("smart_max_pool", 5000));
        int cap = lowMemory ? Math.min(1500, configuredCap) : configuredCap;
        final List<MediaFile> snapshot = files.size() <= cap
                ? new ArrayList<MediaFile>(files)
                : new ArrayList<MediaFile>(files.subList(0, cap));
        worker.post(new Runnable() {
            @Override public void run() {
                Result result;
                try {
                    result = rank(snapshot, requestGeneration);
                } catch (CancelledPassException cancelled) {
                    return;
                } catch (Throwable error) {
                    android.util.Log.w("SmartSortEngine", "Smart pass failed", error);
                    return;
                }
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

    private void clearNeighborSnapshot() {
        previousNeighborK = -1;
        previousPaths = null;
        previousNeighborPaths = null;
        previousNeighborDistances = null;
        previousLabeledPaths.clear();
        previousFileVersions.clear();
        lastNeighborSnapshotReused = false;
    }

    public void close() {
        closed = true;
        cancel();
        clearNeighborSnapshot();
        if (thread != null) thread.quitSafely();
        worker = null;
        thread = null;
    }

    private Result rank(List<MediaFile> files, int requestGeneration) {
        long started = SystemClock.uptimeMillis();
        sliceStarted = SystemClock.uptimeMillis();
        Result result = new Result();
        result.generation = requestGeneration;
        result.lowMemoryDevice = lowMemory;
        result.featureBytes = files.size() * 9L;
        ArrayList<Integer> labeled = new ArrayList<Integer>();
        ArrayList<Integer> unlabeled = new ArrayList<Integer>();
        boolean[] labeledMask = new boolean[files.size()];
        Pattern ignore = compileIgnore();
        String activePile = prefs.getString("smart_active_pile", "");
        Pattern pilePatterns = compilePatterns(prefs.getString("smart_pile_tag_patterns", ""));
        boolean scopePile = "scope".equals(prefs.getString("smart_pile_mode", "scope"))
                && !activePile.trim().isEmpty();
        for (int i = 0; i < files.size(); i++) {
            checkpoint(requestGeneration);
            MediaFile file = files.get(i);
            if (file == null) continue;
            if (scopePile && !file.hasTag(activePile)) continue;
            if (isLabeled(file, ignore, activePile, pilePatterns)) {
                labeled.add(i);
                labeledMask[i] = true;
            } else {
                unlabeled.add(i);
            }
        }
        result.labeled = labeled.size();

        Features features = buildFeatures(files, requestGeneration);
        if (features == null) return null;
        result.featureCount = files.size();
        for (boolean present : features.hashPresent) if (!present) result.missingFeatureCount++;
        NeighborTable neighbors = buildNeighborTable(files, labeled, unlabeled, features,
                requestGeneration);
        result.neighborComparisons = neighbors == null ? 0L : neighbors.comparisons;
        result.incrementalNeighbors = lastNeighborSnapshotReused;
        if (neighbors == null) return null;

        final String orderMode = prefs.getString("smart_order_mode", "auto");
        final float threshold = Math.max(.30f,
                Math.min(.95f, prefs.getInt("smart_suggest_threshold", 60) / 100f));
        final int minLabeled = Math.max(1, prefs.getInt("smart_min_labeled", 5));
        ArrayList<Prediction> predictions = new ArrayList<Prediction>();
        Map<String, Prediction> byPath = new HashMap<String, Prediction>();
        for (Integer index : unlabeled) {
            checkpoint(requestGeneration);
            MediaFile target = files.get(index);
            Prediction prediction = predict(target, index, files, neighbors, features,
                    ignore, activePile, pilePatterns);
            predictions.add(prediction);
            byPath.put(prediction.path, prediction);
            result.confidence.put(target.getPath(), prediction.confidence);
            int occurrences = prediction.tag == null ? 0
                    : countLabeledTag(prediction.tag, files, labeled, ignore, activePile, pilePatterns);
            float localThreshold = occurrences > 0 && occurrences <= 3
                    ? Math.max(.30f, threshold - .20f) : threshold;
            if (prediction.tag != null && prediction.confidence >= localThreshold) {
                prediction.suggested = true;
                result.suggestions.put(target.getPath(), prediction.tag);
                result.suggested++;
            } else {
                result.unsure++;
            }
        }

        boolean useArranged = "arranged".equals(orderMode)
                || ("auto".equals(orderMode) && unlabeled.isEmpty());
        result.arranged = useArranged;
        if (useArranged) {
            buildArrangedResult(result, files, labeledMask, predictions, byPath,
                    requestGeneration);
        } else {
            List<Prediction> ordered = predictions;
            if (labeled.size() < minLabeled && !skipDiversityOrdering) {
                ordered = farthestFirst(predictions, files, labeled, features, requestGeneration);
            } else {
                Collections.sort(ordered, uncertaintyComparator());
            }
            if ("queue".equals(orderMode) || "auto".equals(orderMode)) {
                mixRandomQueuePositions(ordered, requestGeneration);
            }
            int rank = 0;
            for (Prediction prediction : ordered) result.ranks.put(prediction.path, rank++);
            List<Integer> labeledOrder = new ArrayList<Integer>(labeled);
            Collections.sort(labeledOrder, new Comparator<Integer>() {
                @Override public int compare(Integer left, Integer right) {
                    return confirmedCompare(files.get(left), files.get(right));
                }
            });
            for (Integer index : labeledOrder) result.ranks.put(files.get(index).getPath(), rank++);
        }

        result.durationMs = SystemClock.uptimeMillis() - started;
        int budget = Math.max(200, prefs.getInt("smart_pass_budget_ms", 1500));
        if (result.durationMs > budget) consecutiveSlowPasses++;
        else consecutiveSlowPasses = 0;
        if (consecutiveSlowPasses >= 3) {
            int current = Math.max(1, prefs.getInt("smart_k_neighbors", 5));
            prefs.edit().putInt("smart_k_neighbors", Math.max(1, current / 2)).apply();
            result.slowedDown = !slowdownToastShown;
            slowdownToastShown = true;
            skipDiversityOrdering = true;
            consecutiveSlowPasses = 0;
        }
        return result;
    }

    private Features buildFeatures(List<MediaFile> files, int requestGeneration) {
        Features features = new Features(files.size());
        boolean useVideo = prefs.getBoolean("smart_use_video_frames", false) && !lowMemory;
        for (int i = 0; i < files.size(); i++) {
            checkpoint(requestGeneration);
            MediaFile file = files.get(i);
            if (file == null || file.getPath() == null) continue;
            boolean video = file.getType() == MediaFile.Type.VIDEO;
            int expectedKind = video ? (useVideo ? 2 : 0) : 1;
            if (video && !useVideo) continue;
            SmartFeatureCache.Entry cached = expectedKind == 0 ? null
                    : featureCache.get(file.getPath(), file.getSize(), file.getDateAdded(), expectedKind);
            if (cached != null) {
                features.hashes[i] = cached.hash;
                features.hashPresent[i] = cached.present;
                continue;
            }
            if (featureFailures.contains(file.getPath())) continue;

            boolean present = false;
            long hash = 0L;
            try {
                if (video) hash = videoHash(file.getPath());
                else hash = imageHash(file.getPath());
                present = hash != Long.MIN_VALUE;
            } catch (OutOfMemoryError memory) {
                // A failed file is marked missing for this session and is never retried.
                featureFailures.add(file.getPath());
                present = false;
            } catch (Throwable ignored) {
                featureFailures.add(file.getPath());
                present = false;
            }
            features.hashes[i] = hash;
            features.hashPresent[i] = present;
            featureCache.put(file.getPath(), file.getSize(), file.getDateAdded(),
                    hash, present, expectedKind);
        }
        featureCache.save();
        return features;
    }

    private long imageHash(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Long.MIN_VALUE;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return hashBitmap(bitmap);
    }

    private long videoHash(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            retriever.setDataSource(path);
            String durationText = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = 0L;
            try { durationMs = Long.parseLong(durationText == null ? "0" : durationText); }
            catch (Exception ignored) {}
            frame = retriever.getFrameAtTime(Math.max(0L, durationMs / 2L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return hashBitmap(frame);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
            frame = null;
        }
    }

    /** Computes the required 9x8 grayscale dHash and never recycles a bitmap. */
    private long hashBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return Long.MIN_VALUE;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true);
        long hash = 0L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray(scaled.getPixel(x, y));
                int right = gray(scaled.getPixel(x + 1, y));
                hash <<= 1;
                if (right > left) hash |= 1L;
            }
        }
        // Release references only. Android's bitmap pool/GC owns the memory.
        scaled = null;
        bitmap = null;
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
                + ((color >> 8) & 0xFF) * 587
                + (color & 0xFF) * 114) / 1000;
    }

    private NeighborTable buildNeighborTable(List<MediaFile> files, List<Integer> labeled,
                                             List<Integer> unlabeled, Features features,
                                             int requestGeneration) {
        int k = Math.max(1, Math.min(15, prefs.getInt("smart_k_neighbors", 5)));
        NeighborTable table = new NeighborTable(files.size(), k);
        Map<String, Integer> currentIndexes = new HashMap<String, Integer>();
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i) != null) currentIndexes.put(files.get(i).getPath(), i);
        }
        boolean reusable = canReuseNeighborSnapshot(files, currentIndexes, k);
        lastNeighborSnapshotReused = reusable;
        Set<String> currentLabeledPaths = new HashSet<String>();
        for (Integer index : labeled) currentLabeledPaths.add(files.get(index).getPath());
        Set<String> newlyLabeled = new HashSet<String>(currentLabeledPaths);
        newlyLabeled.removeAll(previousLabeledPaths);
        int maxPool = Math.max(k, prefs.getInt("smart_max_pool", 5000));
        for (Integer targetIndex : unlabeled) {
            checkpoint(requestGeneration);
            boolean rebuilt = !reusable;
            int previousRow = reusable ? indexOfPreviousPath(files.get(targetIndex).getPath()) : -1;
            if (previousRow < 0) rebuilt = true;
            if (!rebuilt) {
                int offset = table.offset(targetIndex);
                int previousOffset = previousRow * k;
                for (int slot = 0; slot < k; slot++) {
                    String neighborPath = previousNeighborPaths[previousOffset + slot];
                    if (neighborPath == null) continue;
                    Integer neighborIndex = currentIndexes.get(neighborPath);
                    String version = previousFileVersions.get(neighborPath);
                    if (neighborIndex == null || !currentLabeledPaths.contains(neighborPath)
                            || version == null || !version.equals(fileVersion(files.get(neighborIndex)))) {
                        rebuilt = true;
                        break;
                    }
                    table.indexes[offset + slot] = neighborIndex;
                    table.distances[offset + slot] = previousNeighborDistances[previousOffset + slot];
                }
                String targetVersion = previousFileVersions.get(files.get(targetIndex).getPath());
                if (targetVersion == null || !targetVersion.equals(fileVersion(files.get(targetIndex)))) {
                    rebuilt = true;
                }
            }
            if (rebuilt) {
                clearTargetNeighbors(table, targetIndex);
                List<Integer> candidates = candidateIndexes(files, labeled, targetIndex,
                        features, maxPool);
                for (Integer candidateIndex : candidates) {
                    table.comparisons++;
                    float value = distance(files.get(targetIndex), targetIndex,
                            files.get(candidateIndex), candidateIndex, features);
                    insertNeighbor(table, targetIndex, candidateIndex, value);
                }
            } else {
                // A file that just became labeled is considered immediately,
                // replacing the worst slot if it is closer.
                for (String path : newlyLabeled) {
                    Integer candidateIndex = currentIndexes.get(path);
                    if (candidateIndex == null || candidateIndex == targetIndex) continue;
                    table.comparisons++;
                    float value = distance(files.get(targetIndex), targetIndex,
                            files.get(candidateIndex), candidateIndex, features);
                    insertNeighbor(table, targetIndex, candidateIndex, value);
                }
            }
        }
        rememberNeighborSnapshot(files, labeled, unlabeled, table);
        return table;
    }

    private boolean canReuseNeighborSnapshot(List<MediaFile> files,
                                             Map<String, Integer> currentIndexes, int k) {
        if (previousNeighborK != k || previousPaths == null
                || previousNeighborPaths == null || previousNeighborDistances == null
                || previousPaths.length != files.size()) return false;
        for (String path : previousPaths) {
            if (path == null || !currentIndexes.containsKey(path)) return false;
        }
        return true;
    }

    private int indexOfPreviousPath(String path) {
        if (previousPaths == null || path == null) return -1;
        for (int i = 0; i < previousPaths.length; i++) {
            if (path.equals(previousPaths[i])) return i;
        }
        return -1;
    }

    private String fileVersion(MediaFile file) {
        return file == null ? "" : file.getSize() + ":" + file.getDateAdded();
    }

    private void clearTargetNeighbors(NeighborTable table, int targetIndex) {
        int offset = table.offset(targetIndex);
        for (int slot = 0; slot < table.k; slot++) {
            table.indexes[offset + slot] = -1;
            table.distances[offset + slot] = Float.MAX_VALUE;
            table.paths[offset + slot] = null;
        }
    }

    private void rememberNeighborSnapshot(List<MediaFile> files, List<Integer> labeled,
                                          List<Integer> unlabeled, NeighborTable table) {
        previousNeighborK = table.k;
        previousPaths = new String[files.size()];
        previousFileVersions = new HashMap<String, String>();
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (file != null) {
                previousPaths[i] = file.getPath();
                previousFileVersions.put(file.getPath(), fileVersion(file));
            }
        }
        previousLabeledPaths = new HashSet<String>();
        for (Integer index : labeled) previousLabeledPaths.add(files.get(index).getPath());
        previousNeighborPaths = new String[table.paths.length];
        previousNeighborDistances = new float[table.distances.length];
        for (int i = 0; i < table.paths.length; i++) {
            int neighborIndex = table.indexes[i];
            previousNeighborPaths[i] = neighborIndex < 0 ? null : files.get(neighborIndex).getPath();
            previousNeighborDistances[i] = table.distances[i];
        }
    }

    private List<Integer> candidateIndexes(List<MediaFile> files, List<Integer> labeled,
                                           int targetIndex, Features features, int maxPool) {
        if (files.size() <= 2000 || labeled.size() <= maxPool) return labeled;
        ArrayList<Integer> result = new ArrayList<Integer>();
        HashSet<Integer> seen = new HashSet<Integer>();
        String targetFolder = parent(files.get(targetIndex).getPath());
        int targetBucket = features.hashPresent[targetIndex]
                ? (int) ((features.hashes[targetIndex] >>> 48) & 0xFFFFL) : -1;
        for (Integer candidate : labeled) {
            int bucket = features.hashPresent[candidate]
                    ? (int) ((features.hashes[candidate] >>> 48) & 0xFFFFL) : -1;
            boolean sameBucket = targetBucket >= 0 && bucket == targetBucket;
            boolean sameFolder = targetFolder.equals(parent(files.get(candidate).getPath()));
            if ((sameBucket || sameFolder) && seen.add(candidate)) {
                result.add(candidate);
                if (result.size() >= maxPool) break;
            }
        }
        // Add a random sample so a bucket with no close labels still learns.
        Random sampleRandom = new Random(targetIndex * 1103515245L + 12345L);
        int attempts = 0;
        while (result.size() < maxPool && attempts < labeled.size() * 2) {
            Integer candidate = labeled.get(sampleRandom.nextInt(labeled.size()));
            if (seen.add(candidate)) result.add(candidate);
            attempts++;
        }
        return result.isEmpty() ? labeled.subList(0, Math.min(maxPool, labeled.size())) : result;
    }

    private void insertNeighbor(NeighborTable table, int targetIndex,
                                int candidateIndex, float distance) {
        int offset = table.offset(targetIndex);
        int worst = -1;
        float worstDistance = -1f;
        for (int slot = 0; slot < table.k; slot++) {
            int index = offset + slot;
            if (table.indexes[index] < 0) {
                worst = slot;
                break;
            }
            if (table.distances[index] > worstDistance) {
                worstDistance = table.distances[index];
                worst = slot;
            }
        }
        if (worst < 0) return;
        int position = offset + worst;
        if (table.indexes[position] < 0 || distance < table.distances[position]) {
            table.indexes[position] = candidateIndex;
            table.distances[position] = distance;
        }
    }

    private Prediction predict(MediaFile target, int targetIndex, List<MediaFile> files,
                               NeighborTable table, Features features, Pattern ignore,
                               String activePile, Pattern pilePatterns) {
        Prediction result = new Prediction();
        result.path = target.getPath();
        result.index = targetIndex;
        Map<String, Float> votes = new HashMap<String, Float>();
        float total = 0f;
        float nearest = 1f;
        int offset = table.offset(targetIndex);
        for (int slot = 0; slot < table.k; slot++) {
            int neighborIndex = table.indexes[offset + slot];
            if (neighborIndex < 0) continue;
            MediaFile neighbor = files.get(neighborIndex);
            float distance = table.distances[offset + slot];
            nearest = Math.min(nearest, distance);
            float voteWeight = 1f / (.05f + distance);
            Set<String> unique = new HashSet<String>();
            for (String raw : neighbor.getTags()) {
                String tag = raw == null ? "" : raw.trim();
                if (!isLabelTag(tag, ignore, activePile, pilePatterns) || !unique.add(tag)) continue;
                Float old = votes.get(tag);
                votes.put(tag, old == null ? voteWeight : old + voteWeight);
                total += voteWeight;
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

    private List<Prediction> farthestFirst(List<Prediction> source, List<MediaFile> files,
                                            List<Integer> labeled, Features features,
                                            int requestGeneration) {
        ArrayList<Prediction> remaining = new ArrayList<Prediction>(source);
        ArrayList<Prediction> ordered = new ArrayList<Prediction>(source.size());
        if (remaining.isEmpty()) return ordered;
        while (!remaining.isEmpty()) {
            checkpoint(requestGeneration);
            if (ordered.isEmpty() && labeled.isEmpty()) {
                ordered.add(remaining.remove(random.nextInt(remaining.size())));
                continue;
            }
            int bestPosition = 0;
            float bestDistance = -1f;
            for (int i = 0; i < remaining.size(); i++) {
                Prediction candidate = remaining.get(i);
                float nearest = labeled.isEmpty() ? 1f : Float.MAX_VALUE;
                for (Integer index : labeled) {
                    nearest = Math.min(nearest, distance(files.get(candidate.index), candidate.index,
                            files.get(index), index, features));
                }
                for (Prediction already : ordered) {
                    nearest = Math.min(nearest, distance(files.get(candidate.index), candidate.index,
                            files.get(already.index), already.index, features));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    bestPosition = i;
                }
            }
            ordered.add(remaining.remove(bestPosition));
        }
        return ordered;
    }

    private Comparator<Prediction> uncertaintyComparator() {
        return new Comparator<Prediction>() {
            @Override public int compare(Prediction left, Prediction right) {
                int confidence = Float.compare(left.confidence, right.confidence);
                return confidence != 0 ? confidence : left.path.compareToIgnoreCase(right.path);
            }
        };
    }

    private void mixRandomQueuePositions(List<Prediction> ordered, int requestGeneration) {
        int percent = Math.max(0, Math.min(50, prefs.getInt("smart_random_fraction", 15)));
        int slots = Math.round(ordered.size() * percent / 100f);
        if (slots <= 0 || ordered.size() < 2) return;
        Random mix = new Random(requestGeneration * 31L + 17L);
        HashSet<Integer> positions = new HashSet<Integer>();
        while (positions.size() < slots) positions.add(mix.nextInt(ordered.size()));
        ArrayList<Prediction> selected = new ArrayList<Prediction>();
        for (int position = 0; position < ordered.size(); position++) {
            if (positions.contains(position)) selected.add(ordered.get(position));
        }
        for (int i = selected.size() - 1; i > 0; i--) {
            int j = mix.nextInt(i + 1);
            Prediction value = selected.get(i);
            selected.set(i, selected.get(j));
            selected.set(j, value);
        }
        int selectedIndex = 0;
        for (int position = 0; position < ordered.size(); position++) {
            if (positions.contains(position)) ordered.set(position, selected.get(selectedIndex++));
        }
    }

    private void buildArrangedResult(Result result, List<MediaFile> files,
                                     boolean[] labeledMask, List<Prediction> predictions,
                                     Map<String, Prediction> byPath, int requestGeneration) {
        Map<String, SmartGroupData> groups = new LinkedHashMap<String, SmartGroupData>();
        String groupMode = prefs.getString("group_tag_mode", "all");
        int groupPosition = Math.max(1, prefs.getInt("group_tag_position", 1));
        String groupPattern = prefs.getString("group_tag_pattern", "");
        for (int i = 0; i < files.size(); i++) {
            checkpoint(requestGeneration);
            if (files.get(i) == null || (!labeledMask[i] && !byPath.containsKey(files.get(i).getPath()))) continue;
            MediaFile file = files.get(i);
            Prediction prediction = byPath.get(file.getPath());
            String label = labeledMask[i] ? primaryTag(file, groupMode, groupPosition, groupPattern) : null;
            boolean confirmed = labeledMask[i];
            float confidence = prediction == null ? 1f : prediction.confidence;
            if (!confirmed && prediction != null && prediction.suggested) label = prediction.tag;
            if (label == null || label.trim().isEmpty()) label = "Needs a tag";
            SmartGroupData data = groups.get(label);
            if (data == null) {
                data = new SmartGroupData(label);
                groups.put(label, data);
            }
            GroupItem item = new GroupItem();
            item.file = file;
            item.path = file.getPath();
            item.confirmed = confirmed;
            item.confidence = confidence;
            data.items.add(item);
        }
        ArrayList<SmartGroupData> orderedGroups = new ArrayList<SmartGroupData>(groups.values());
        Collections.sort(orderedGroups, new Comparator<SmartGroupData>() {
            @Override public int compare(SmartGroupData left, SmartGroupData right) {
                int count = Integer.compare(right.items.size(), left.items.size());
                return count != 0 ? count : left.label.compareToIgnoreCase(right.label);
            }
        });
        int rank = 0;
        for (SmartGroupData data : orderedGroups) {
            Collections.sort(data.items, new Comparator<GroupItem>() {
                @Override public int compare(GroupItem left, GroupItem right) {
                    if (left.confirmed != right.confirmed) return left.confirmed ? -1 : 1;
                    if (!left.confirmed) {
                        int confidence = Float.compare(right.confidence, left.confidence);
                        if (confidence != 0) return confidence;
                    }
                    int sequence = sequenceLabel(left.file).compareToIgnoreCase(sequenceLabel(right.file));
                    if (sequence != 0) return sequence;
                    int manual = compareManualOrder(left.file, right.file);
                    return manual != 0 ? manual
                            : left.file.getName().compareToIgnoreCase(right.file.getName());
                }
            });
            Group group = new Group(data.label, Group.GroupBy.TAG);
            group.setTotalCount(data.items.size());
            result.groups.add(group);
            int total = data.items.size();
            for (int i = 0; i < total; i++) {
                GroupItem item = data.items.get(i);
                group.addFile(item.file);
                result.ranks.put(item.path, rank++);
                result.groupPositions.put(item.path, i + 1);
                result.groupTotals.put(item.path, total);
            }
        }
    }

    private String primaryTag(MediaFile file, String mode, int position, String pattern) {
        List<String> tags = file.getTags();
        if (tags == null || tags.isEmpty()) return "";
        if ("first".equals(mode)) return tags.get(0);
        if ("last".equals(mode)) return tags.get(tags.size() - 1);
        if ("position".equals(mode)) {
            int index = position - 1;
            return index >= 0 && index < tags.size() ? tags.get(index) : "";
        }
        if ("pattern".equals(mode) && pattern != null) {
            Pattern compiled = compilePatterns(pattern);
            if (compiled != null) {
                for (String tag : tags) if (compiled.matcher(tag).matches()) return tag;
            }
            return "";
        }
        return tags.get(0); // "all" uses the first tag for Smart's single group key.
    }

    private String sequenceLabel(MediaFile file) {
        for (String tag : file.getTags()) {
            if (tag == null) continue;
            String plain = tag.trim();
            int marker = plain.indexOf("_seq_");
            if (marker >= 0) return plain.substring(marker + 5);
        }
        return "";
    }

    private int confirmedCompare(MediaFile left, MediaFile right) {
        int sequence = sequenceLabel(left).compareToIgnoreCase(sequenceLabel(right));
        if (sequence != 0) return sequence;
        int manual = compareManualOrder(left, right);
        return manual != 0 ? manual : left.getName().compareToIgnoreCase(right.getName());
    }

    private int compareManualOrder(MediaFile left, MediaFile right) {
        int a = left.getManualOrder();
        int b = right.getManualOrder();
        if (a < 0 && b < 0) return 0;
        if (a < 0) return 1;
        if (b < 0) return -1;
        return Integer.compare(a, b);
    }

    private Pattern compileIgnore() {
        return compilePatterns(prefs.getString("smart_ignore_tag_patterns", "link_*,*_seq_*"));
    }

    private Pattern compilePatterns(String raw) {
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
            expression.append("(?:").append(wildcard).append(")");
        }
        try {
            return expression.length() == 0 ? null
                    : Pattern.compile("^(?:" + expression + ")$", Pattern.CASE_INSENSITIVE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isLabeled(MediaFile file, Pattern ignore, String activePile,
                               Pattern pilePatterns) {
        if (file == null || file.getTags() == null) return false;
        for (String raw : file.getTags()) {
            String tag = raw == null ? "" : raw.trim();
            if (isLabelTag(tag, ignore, activePile, pilePatterns)) return true;
        }
        return false;
    }

    private boolean isLabelTag(String tag, Pattern ignore, String activePile,
                               Pattern pilePatterns) {
        if (tag.isEmpty()) return false;
        if (activePile != null && !activePile.isEmpty() && activePile.equals(tag)) return false;
        // A direct child of the active pile is a label while reviewing the
        // parent, even if the configured pattern also matches it.
        if (isDirectChild(tag, activePile)) return true;
        if (ignore != null && ignore.matcher(tag).matches()) return false;
        if (pilePatterns != null && pilePatterns.matcher(tag).matches()) return false;
        return true;
    }

    private boolean isDirectChild(String tag, String parent) {
        if (tag == null || parent == null || parent.isEmpty()) return false;
        String prefix = parent + "_";
        if (!tag.startsWith(prefix) || tag.length() <= prefix.length()) return false;
        for (int i = prefix.length(); i < tag.length(); i++) {
            if (!Character.isDigit(tag.charAt(i))) return false;
        }
        return true;
    }

    private int countLabeledTag(String tag, List<MediaFile> files, List<Integer> labeled,
                                Pattern ignore, String activePile, Pattern pilePatterns) {
        int count = 0;
        for (Integer index : labeled) {
            MediaFile file = files.get(index);
            if (file == null) continue;
            for (String raw : file.getTags()) {
                String value = raw == null ? "" : raw.trim();
                if (tag.equals(value) && isLabelTag(value, ignore, activePile, pilePatterns)) {
                    count++;
                    break;
                }
            }
        }
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
        if (wColor > 0 && !a.getColorFamily().isEmpty() && !b.getColorFamily().isEmpty()) {
            value += wColor * (a.getColorFamily().equalsIgnoreCase(b.getColorFamily()) ? 0f : 1f);
            weight += wColor;
        }
        if (wShape > 0) { value += wShape * shapeDistance(a, b); weight += wShape; }
        return weight == 0f ? 1f : Math.max(0f, Math.min(1f, value / weight));
    }

    private float nameDistance(MediaFile a, MediaFile b) {
        Set<String> left = tokens(a.getName());
        Set<String> right = tokens(b.getName());
        Set<String> union = new HashSet<String>(left);
        union.addAll(right);
        Set<String> intersection = new HashSet<String>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 1f : 1f - ((float) intersection.size() / union.size());
    }

    private Set<String> tokens(String name) {
        Set<String> result = new HashSet<String>();
        if (name != null) {
            String[] parts = name.toLowerCase(Locale.US).split("[^a-z0-9]+");
            for (String part : parts) if (!part.isEmpty()) result.add(part);
        }
        return result;
    }

    private float folderDistance(MediaFile a, MediaFile b) {
        String left = parent(a.getPath());
        String right = parent(b.getPath());
        if (left.equals(right)) return 0f;
        String leftParent = parent(left);
        String rightParent = parent(right);
        return leftParent.equals(rightParent) ? .5f : 1f;
    }

    private String parent(String path) {
        if (path == null) return "";
        String parent = new File(path).getParent();
        return parent == null ? "" : parent;
    }

    private float dateDistance(MediaFile a, MediaFile b) {
        double hours = Math.abs(a.getDateAdded() - b.getDateAdded()) / 3600000.0;
        double tau = Math.max(1, prefs.getInt("smart_date_tau_hours", 24));
        return (float) (1.0 - Math.exp(-hours / tau));
    }

    private float shapeDistance(MediaFile a, MediaFile b) {
        boolean typeMismatch = a.getType() != b.getType();
        float aspect = .5f;
        if (a.getWidth() > 0 && a.getHeight() > 0
                && b.getWidth() > 0 && b.getHeight() > 0) {
            double left = a.getWidth() / (double) a.getHeight();
            double right = b.getWidth() / (double) b.getHeight();
            aspect = (float) Math.min(1d, Math.abs(Math.log(left / right) / Math.log(2d)));
        }
        float extension = extension(a).equalsIgnoreCase(extension(b)) ? 0f : .3f;
        if (typeMismatch) extension = .3f;
        return .5f * aspect + extension + .2f * sizeDistance(a, b);
    }

    private String extension(MediaFile file) {
        String name = file == null ? "" : file.getName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private float sizeDistance(MediaFile a, MediaFile b) {
        if (a.getSize() <= 0 || b.getSize() <= 0) return .5f;
        double left = Math.log(a.getSize()) / Math.log(2d);
        double right = Math.log(b.getSize()) / Math.log(2d);
        return (float) Math.min(1d, Math.abs(left - right) / 6d);
    }

    private void checkpoint(int requestGeneration) {
        if (requestGeneration != generation || closed) throw new CancelledPassException();
        int chunk = Math.max(5, Math.min(50, prefs.getInt("smart_chunk_ms", 20)
                / (lowMemory ? 2 : 1)));
        while (scanRunning || paused || prefs.getBoolean("smart_paused", false)
                || SystemClock.uptimeMillis() - lastActivity
                < (long) prefs.getInt("rerank_idle_ms", 400) * (lowMemory ? 2L : 1L)) {
            SystemClock.sleep(16L);
            if (requestGeneration != generation || closed) throw new CancelledPassException();
            sliceStarted = SystemClock.uptimeMillis();
        }
        long now = SystemClock.uptimeMillis();
        if (now - sliceStarted >= chunk) {
            Thread.yield();
            sliceStarted = now;
        }
    }

    private static class CancelledPassException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
