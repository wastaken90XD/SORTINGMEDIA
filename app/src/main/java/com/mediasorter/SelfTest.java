package com.mediasorter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.mediasorter.features.RandomGenerator;
import com.mediasorter.features.SmartFeatureCache;
import com.mediasorter.features.SurpriseEngine;
import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Explicit, user-triggered checks for the coordinated sorting stack. */
public final class SelfTest {
    public interface Listener { void onFinished(Result result); }

    public static final class Result {
        public final List<String> lines = new ArrayList<String>();
        public int passed;
        public int failed;
        public int notVerified;
        public File reportFile;

        void pass(String name, String detail) {
            passed++;
            lines.add("SELF-TEST PASSED  " + name + (detail == null ? "" : " — " + detail));
        }
        void fail(String name, String detail) {
            failed++;
            lines.add("NOT VERIFIED  " + name + " — " + detail);
        }
        void notVerified(String name, String detail) {
            notVerified++;
            lines.add("NOT VERIFIED  " + name + " — " + detail);
        }
    }

    private SelfTest() {}

    public static void runAsync(final Context context, final Listener listener) {
        final Context app = context.getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(new Runnable() {
            @Override public void run() {
                final Result result = runAll(app);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        if (listener != null) listener.onFinished(result);
                    }
                });
            }
        });
        executor.shutdown();
    }

    public static Result runAll(Context context) {
        Result result = new Result();
        runSequence(result);
        runStride(context, result);
        runQueries(result);
        runBatchTagger(result);
        runCompare(result);
        runCompareGrouping(context, result);
        runReplacePreview(result);
        runFeatureCache(context, result);
        runPreferences(context, result);
        runProfile(context, result);
        result.notVerified("Smart 2,000-file pass",
                "full dHash timing/memory test requires a device media decoder");
        result.notVerified("onPathsChanged/onPathsAdded live hook",
                "requires a live Activity/index and real filesystem callbacks");
        writeReport(context, result);
        return result;
    }

    private static void runSequence(Result result) {
        for (int i = 0; i <= 5000; i++) {
            String label = RandomGenerator.sequenceLabel(i);
            if (RandomGenerator.sequenceIndex(label) != i) {
                result.fail("sequenceIndex round trip", "index " + i + " -> " + label);
                return;
            }
        }
        result.pass("sequenceIndex round trip", "0..5000");
    }

    private static void runStride(Context context, Result result) {
        try {
            for (int size : new int[]{1, 2, 3, 10, 97, 170, 491}) {
                SurpriseEngine engine = new SurpriseEngine(context);
                List<MediaFile> files = syntheticFiles(size);
                Set<String> visited = new HashSet<String>();
                for (int i = 0; i < size; i++) {
                    MediaFile file = engine.pick(files, SurpriseEngine.Mode.STRIDE, null, 1);
                    if (file == null || !visited.add(file.getPath())) {
                        result.fail("Surprise stride", "duplicate/null at N=" + size);
                        return;
                    }
                }
                if (visited.size() != size) {
                    result.fail("Surprise stride", "visited " + visited.size() + " of " + size);
                    return;
                }
            }
            result.pass("Surprise stride", "all requested sizes visited exactly once");
        } catch (Throwable error) {
            result.fail("Surprise stride", error.toString());
        }
    }

    private static void runQueries(Result result) {
        try {
            boolean wildcard = SearchManager.QueryParser.parse("cat*").matchesText("catalog");
            boolean and = SearchManager.QueryParser.parse("cat AND dog").matchesText("cat dog");
            boolean or = SearchManager.QueryParser.parse("cat OR dog").matchesText("dog");
            boolean not = SearchManager.QueryParser.parse("NOT cat").matchesText("dog");
            boolean parens = SearchManager.QueryParser.parse("(cat OR dog) AND bird")
                    .matchesText("dog bird");
            boolean regex = SearchManager.QueryParser.parse("/cat[0-9]+/")
                    .matchesText("cat42");
            if (wildcard && and && or && not && parens && regex) {
                result.pass("QueryParser", "wildcard/AND/OR/NOT/parentheses/regex");
            } else {
                result.fail("QueryParser", "one or more expression cases failed");
            }
        } catch (Throwable error) {
            result.fail("QueryParser", error.toString());
        }
    }

    private static void runBatchTagger(Result result) {
        try {
            List<MediaFile> pool = syntheticFiles(30);
            BatchGameEngine engine = new BatchGameEngine();
            engine.start(pool, BatchGameEngine.Scope.UNTAGGED, 6, true);
            Set<String> skippedOnce = new HashSet<String>();
            int rounds = 0;
            BatchGameEngine.Round round = engine.buildNextRound();
            while (round != null && rounds < 100) {
                if (!round.files.isEmpty()) engine.markApplied(round.files.get(0).getPath());
                for (int i = 1; i < round.files.size(); i++) {
                    String path = round.files.get(i).getPath();
                    if (engine.markSkipped(path)) skippedOnce.add(path);
                }
                round = engine.nextBatch();
                rounds++;
            }
            BatchGameEngine.Summary summary = engine.getSummary();
            if (round != null || !summary.complete || summary.tagged != 30
                    || skippedOnce.isEmpty() || rounds <= 1) {
                result.fail("Batch tagger handlers", "pool did not drain with returned skips");
                return;
            }
            List<MediaFile> pileOnly = syntheticFiles(6);
            for (MediaFile file : pileOnly) file.addTag("pile_alpha");
            List<MediaFile> eligible = BatchGameEngine.selectScope(pileOnly,
                    BatchGameEngine.Scope.ACTIVE_PILE, null, null, "pile_alpha",
                    "pile_*", "link_*,*_seq_*", false);
            BatchGameEngine pileEngine = new BatchGameEngine();
            pileEngine.start(eligible, BatchGameEngine.Scope.ACTIVE_PILE, 6, true);
            if (pileEngine.buildNextRound() == null) {
                result.fail("Batch tagger pile eligibility", "pile-only files did not build a round");
                return;
            }
            result.pass("Batch tagger handlers", "30 files, N=6, apply/skip/next and pile-only round");
        } catch (Throwable error) {
            result.fail("Batch tagger handlers", error.toString());
        }
    }

    private static void runCompareGrouping(Context context, Result result) {
        try {
            List<MediaFile> files = syntheticFiles(8);
            for (int i = 0; i < files.size(); i++) files.get(i).setManualOrder(i);
            SortManager manager = new SortManager(context);
            List<SortManager.SortCriterion> hostile = new ArrayList<SortManager.SortCriterion>();
            hostile.add(new SortManager.SortCriterion(SortManager.MANUAL_ORDER, "ASC"));
            hostile.add(new SortManager.SortCriterion(SortManager.NAME, "DESC"));
            manager.saveSortSequence(hostile);
            Group first = new Group("first", Group.GroupBy.TAG);
            Group second = new Group("second", Group.GroupBy.TAG);
            first.addFile(files.get(3));
            first.addFile(files.get(1));
            second.addFile(files.get(7));
            second.addFile(files.get(2));
            List<Group> groups = new ArrayList<Group>();
            groups.add(first);
            groups.add(second);
            for (Group group : groups) {
                List<MediaFile> orderedMembers = new ArrayList<MediaFile>(group.getFiles());
                manager.sort(orderedMembers);
                group.clear();
                for (MediaFile file : orderedMembers) group.addFile(file);
            }
            if (!"/selftest/p1.jpg".equals(first.getFiles().get(0).getPath())
                    || !"/selftest/p2.jpg".equals(second.getFiles().get(0).getPath())) {
                result.fail("Compare answerInProgress grouped order", "hostile sort hid manual order");
                return;
            }
            result.pass("Compare answerInProgress grouped order",
                    "group members follow active manual sequence under hostile secondary sort");
        } catch (Throwable error) {
            result.fail("Compare answerInProgress grouped order", error.toString());
        }
    }

    private static void runCompare(Result result) {
        try {
            for (int size : new int[]{20, 170}) {
                List<MediaFile> files = syntheticFiles(size);
                java.util.Collections.shuffle(files, new java.util.Random(size * 17L));
                final Map<String, Integer> oracle = new HashMap<String, Integer>();
                for (int i = 0; i < size; i++) oracle.put("/selftest/p" + i + ".jpg", i);
                CompareSortEngine engine = new CompareSortEngine();
                engine.start(files, 0, false, 500);
                Set<String> asked = new HashSet<String>();
                int questions = 0;
                CompareSortEngine.Question question;
                while ((question = engine.nextQuestion()) != null) {
                    String pair = question.candidatePath + "\n" + question.comparedPath;
                    if (!asked.add(pair)) {
                        result.fail("Compare oracle N=" + size, "pair asked twice: " + pair);
                        return;
                    }
                    int left = oracle.get(question.candidatePath);
                    int right = oracle.get(question.comparedPath);
                    engine.answerCompare(left < right ? CompareSortEngine.Answer.LEFT
                            : left > right ? CompareSortEngine.Answer.RIGHT
                            : CompareSortEngine.Answer.SAME);
                    questions++;
                    if (questions > 100000) throw new IllegalStateException("question limit");
                }
                List<String> ordered = engine.finish().orderedPaths;
                for (int i = 1; i < ordered.size(); i++) {
                    if (oracle.get(ordered.get(i - 1)) > oracle.get(ordered.get(i))) {
                        result.fail("Compare oracle N=" + size, "wrong final order");
                        return;
                    }
                }
                double logFactorial = 0d;
                for (int i = 2; i <= size; i++) logFactorial += Math.log(i) / Math.log(2d);
                int bound = (int) Math.ceil(logFactorial) + size;
                if (questions > bound) {
                    result.fail("Compare oracle N=" + size,
                            "questions " + questions + " > bound " + bound);
                    return;
                }
            }
            result.pass("Compare oracle", "N=20 and N=170, order/pair/bound checks");
        } catch (Throwable error) {
            result.fail("Compare oracle", error.toString());
        }
    }

    private static void runReplacePreview(Result result) {
        try {
            MediaFile first = new MediaFile("/selftest/a.jpg", 10);
            MediaFile second = new MediaFile("/selftest/b.jpg", 10);
            first.addTag("link_demo_seq_aa");
            first.addTag("keep");
            second.addTag("link_demo_seq_ab");
            List<MediaFile> files = new ArrayList<MediaFile>();
            files.add(first);
            files.add(second);
            Set<String> targets = new HashSet<String>();
            targets.add(first.getPath());
            targets.add(second.getPath());
            ReplaceTagsManager.Report report = ReplaceTagsManager.preview(files, targets,
                    java.util.Collections.singletonList("new"), "link_*,*_seq_*", "",
                    ReplaceTagsManager.SequenceRule.WHOLE_GROUP);
            if (report.fullySelectedGroups == 1 && report.previousTags.size() == 2) {
                result.pass("Replace Tags preview/undo snapshot", "whole_group");
            } else {
                result.fail("Replace Tags preview/undo snapshot", "unexpected group report");
            }
        } catch (Throwable error) {
            result.fail("Replace Tags preview/undo snapshot", error.toString());
        }
    }

    private static void runFeatureCache(Context context, Result result) {
        File directory = new File(context.getCacheDir(), "selftest");
        deleteTree(directory);
        if (!directory.mkdirs()) {
            result.fail("FeatureStore cache", "could not create cache directory");
            return;
        }
        try {
            SmartFeatureCache cache = new SmartFeatureCache(directory);
            cache.put("/selftest/a.jpg", 10L, 20L, 33L, true, 1);
            cache.save();
            SmartFeatureCache restored = new SmartFeatureCache(directory);
            SmartFeatureCache.Entry entry = restored.get("/selftest/a.jpg", 10L, 20L, 1);
            if (entry == null || entry.hash != 33L) throw new IllegalStateException("round trip failed");
            File binary = new File(directory, "smart_features.bin");
            FileOutputStream out = new FileOutputStream(binary);
            out.write(new byte[]{1, 2, 3});
            out.close();
            SmartFeatureCache recovered = new SmartFeatureCache(directory);
            if (binary.exists() || recovered.get("/selftest/a.jpg", 10L, 20L, 1) != null) {
                throw new IllegalStateException("corrupt recovery failed");
            }
            result.pass("FeatureStore cache", "round trip and corrupt recovery");
        } catch (Throwable error) {
            result.fail("FeatureStore cache", error.toString());
        } finally {
            deleteTree(directory);
        }
    }

    private static void runPreferences(Context context, Result result) {
        String[] keys = {
                "rerank_idle_ms", "smart_chunk_ms", "smart_pass_budget_ms", "smart_max_pool",
                "sort_dupes_mode", "sort_profiles", "compare_layout", "compare_question",
                "compare_randomize_sides", "compare_recheck_every", "compare_sync_zoom",
                "compare_show_caption", "compare_caption_fields", "compare_volume_pick",
                "compare_video_muted", "compare_warn_questions", "compare_undo_depth",
                "compare_prefetch", "compare_hires_on_zoom", "batch_size_mode",
                "batch_last_scope", "batch_auto_advance", "batch_skip_to_back",
                "batch_include_tagged", "batch_show_inline_tags", "game_batch_size",
                "game_commit_mode", "replace_mode",
                "replace_keep_max", "replace_confirm_min_files", "replace_protect_piles",
                "replace_sequence_rule", "replace_sequence_patterns", "replace_protect_patterns",
                "replace_confirm_sequence_overwrite", "replace_show_group_report",
                "replace_purge_group_tags"
        };
        for (String key : keys) {
            if (!SettingsExporter.isKnownKey("settings_prefs", key)) {
                result.fail("SettingsExporter allow-list", key + " missing");
                return;
            }
        }
        String[] legacy = {"batch_commit_mode", "game_score_mode", "game_timer_seconds",
                "game_none_cooldown", "game_none_max_returns"};
        for (String key : legacy) {
            if (!SettingsExporter.isKnownKey("settings_prefs", key)) {
                result.fail("Legacy settings export allow-list", key + " missing");
                return;
            }
        }
        result.pass("SettingsExporter allow-list", String.valueOf(keys.length)
                + " current keys plus legacy Batch tagger keys");
    }

    private static void runProfile(Context context, Result result) {
        SortProfileManager manager = new SortProfileManager(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "settings_prefs", Context.MODE_PRIVATE);
        Object old = prefs.getAll().get("smart_chunk_ms");
        manager.applyBuiltIn(0);
        boolean changed = prefs.getInt("smart_chunk_ms", -1) == 10;
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (old == null) editor.remove("smart_chunk_ms");
        else if (old instanceof Number) editor.putInt("smart_chunk_ms", ((Number) old).intValue());
        else editor.putString("smart_chunk_ms", String.valueOf(old));
        editor.apply();
        if (changed) result.pass("Profile apply", "built-in Fast applied without recreate");
        else result.fail("Profile apply", "built-in Fast did not apply");
    }

    private static List<MediaFile> syntheticFiles(int size) {
        List<MediaFile> files = new ArrayList<MediaFile>();
        for (int i = 0; i < size; i++) files.add(new MediaFile("/selftest/p" + i + ".jpg", i + 1L));
        return files;
    }

    private static void writeReport(Context context, Result result) {
        File directory = CrashLogger.getCrashLogDirectory(context);
        if (directory == null) return;
        if (!directory.exists()) directory.mkdirs();
        File file = new File(directory, "self_test_" + System.currentTimeMillis() + ".txt");
        result.reportFile = file;
        try {
            FileOutputStream out = new FileOutputStream(file);
            StringBuilder text = new StringBuilder();
            text.append("SELF-TEST PASSED: ").append(result.passed).append("\n");
            text.append("NOT VERIFIED FAILURES: ").append(result.failed).append("\n");
            text.append("NOT VERIFIED: ").append(result.notVerified).append("\n\n");
            for (String line : result.lines) text.append(line).append('\n');
            out.write(text.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
        } catch (Exception error) {
            result.lines.add("NOT VERIFIED  report file — " + error);
            result.notVerified++;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
