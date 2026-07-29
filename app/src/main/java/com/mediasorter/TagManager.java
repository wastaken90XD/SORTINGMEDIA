package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TagManager {

    private static final String PREFS      = "tag_recent_prefs";
    private static final String KEY_RECENT = "recent_tags";
    private static final int    MAX_RECENT = 10;

    private final TagDatabase        db;
    private final ExecutorService    executor   = Executors.newSingleThreadExecutor();
    private final Map<String, Tag>   tagMap     = new HashMap<>();
    private final LinkedList<String> recentTags;
    private final SharedPreferences  prefs;

    // Per-file stack (oldest → newest) of tags applied via applyTag() in this
    // session. A repeated swipe/dpad gesture removes *its own* tag again,
    // regardless of how many different tags were applied afterwards —
    // previously only a single "last applied" tag was remembered per file,
    // so undoing the 2nd-to-last gesture tag was impossible (bug).
    //
    // Guarded by stackLock because applyTag() can also run on background
    // threads (e.g. ColorAnalyzer) while swipe gestures mutate the same map
    // on the UI thread — an unguarded HashMap race can corrupt the table.
    private final Map<String, LinkedList<String>> appliedStack = new HashMap<>();
    private final Object stackLock = new Object();

    // Listener for tag list changes
    public interface TagChangeListener {
        void onTagsChanged();
    }
    private TagChangeListener tagChangeListener;

    public void setTagChangeListener(TagChangeListener l) {
        this.tagChangeListener = l;
    }

    private void notifyTagsChanged() {
        if (tagChangeListener != null) tagChangeListener.onTagsChanged();
    }

    public TagManager(Context context) {
        this.db         = TagDatabase.getInstance(context);
        this.prefs      = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.recentTags = loadRecentTags();
        loadTags();
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadTags() {
        executor.submit(() -> {
            List<Tag> tags = db.tagDao().getAllByUsage();
            synchronized (tagMap) {
                for (Tag t : tags) tagMap.put(t.getName(), t);
            }
        });
    }

    // ── Recent tags ───────────────────────────────────────────────────────────

    private LinkedList<String> loadRecentTags() {
        LinkedList<String> list = new LinkedList<>();
        String saved = prefs.getString(KEY_RECENT, "");
        if (!saved.isEmpty()) {
            for (String t : saved.split(",")) {
                if (!t.isEmpty()) list.add(t);
            }
        }
        return list;
    }

    private void saveRecentTags() {
        StringBuilder sb = new StringBuilder();
        for (String t : recentTags) {
            if (sb.length() > 0) sb.append(",");
            sb.append(t);
        }
        prefs.edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    private void addToRecent(String tagName) {
        recentTags.remove(tagName);
        recentTags.addFirst(tagName);
        while (recentTags.size() > MAX_RECENT) recentTags.removeLast();
        saveRecentTags();
    }

    // ── Gesture-applied stack (for swipe/dpad undo) ───────────────────────────

    private void recordApplied(String path, String tagName) {
        synchronized (stackLock) {
            LinkedList<String> stack = appliedStack.get(path);
            if (stack == null) {
                stack = new LinkedList<>();
                appliedStack.put(path, stack);
            }
            stack.remove(tagName);     // no duplicates; newest applies go last
            stack.addLast(tagName);
        }
    }

    private void unrecordApplied(String path, String tagName) {
        synchronized (stackLock) {
            LinkedList<String> stack = appliedStack.get(path);
            if (stack == null) return;
            stack.remove(tagName);
            if (stack.isEmpty()) appliedStack.remove(path);
        }
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public void createTag(String name) {
        String trimmed = name.trim();
        // Commas would corrupt the comma-separated recent-tags persistence
        if (trimmed.isEmpty() || trimmed.contains(",")) return;
        final Tag tag;
        synchronized (tagMap) {
            if (tagMap.containsKey(trimmed)) return;
            // Update the in-memory map synchronously so the new tag shows up
            // immediately (e.g. when a dialog re-opens right after creating);
            // only the DB write stays on the background executor.
            tag = new Tag(trimmed);
            tagMap.put(trimmed, tag);
        }
        executor.submit(() -> db.tagDao().insert(tag));
        notifyTagsChanged();
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    public void applyTag(MediaFile file, String tagName) {
        if (file.hasTag(tagName)) return;
        file.addTag(tagName);
        addToRecent(tagName);
        recordApplied(file.getPath(), tagName);

        executor.submit(() -> {
            synchronized (tagMap) {
                Tag tag = tagMap.get(tagName);
                if (tag == null) {
                    tag = new Tag(tagName);
                    tagMap.put(tagName, tag);
                    db.tagDao().insert(tag);
                }
                tag.incrementUsage();
                db.tagDao().update(tag);
            }
            MetadataWriter.writeTags(file.getPath(), file.getTags());
            notifyTagsChanged();
        });
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    public void removeTag(MediaFile file, String tagName) {
        if (!file.hasTag(tagName)) return;
        file.removeTag(tagName);
        unrecordApplied(file.getPath(), tagName);

        executor.submit(() -> {
            synchronized (tagMap) {
                Tag tag = tagMap.get(tagName);
                if (tag != null) {
                    tag.decrementUsage();
                    db.tagDao().update(tag);
                }
            }
            MetadataWriter.writeTags(file.getPath(), file.getTags());
            notifyTagsChanged();
        });
    }

    // ── Undo last applied tag ─────────────────────────────────────────────────

    public boolean undoLastTag(MediaFile file) {
        // Whole read-check-act under the lock; synchronized is reentrant, so
        // the nested unrecordApplied/removeTag calls are safe.
        synchronized (stackLock) {
            LinkedList<String> stack = appliedStack.get(file.getPath());
            if (stack == null || stack.isEmpty()) return false;
            String last = stack.getLast();
            if (!file.hasTag(last)) {
                // Tag was removed by other means (e.g. tags bar) — drop the record
                unrecordApplied(file.getPath(), last);
                return false;
            }
            removeTag(file, last);  // also unrecords it
            return true;
        }
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    public void toggleTag(MediaFile file, String tagName) {
        if (file.hasTag(tagName)) removeTag(file, tagName);
        else                      applyTag(file, tagName);
    }

    // ── Toggle with undo — repeating the same gesture removes the tag again ───

    /**
     * Applies the tag, or removes it again when the gesture is repeated.
     * Unlike the old single-slot implementation this works per tag: applying
     * tag A then tag B (via different gestures) and repeating both gestures
     * now removes B *and* A again — the earlier tag no longer "sticks".
     *
     * A tag the file already carried before it was applied through this
     * session (e.g. imported from metadata at scan time) is intentionally
     * left untouched, so rapid swipe-tagging never strips unknown tags.
     */
    public void applyOrUndo(MediaFile file, String tagName) {
        // Compound check-then-act under the lock (reentrant; applyTag/
        // removeTag re-acquire it in recordApplied/unrecordApplied). No other
        // monitor is taken while stackLock is held, so no lock-order issues.
        synchronized (stackLock) {
            LinkedList<String> stack = appliedStack.get(file.getPath());
            boolean tracked = stack != null && stack.contains(tagName);
            if (tracked && file.hasTag(tagName)) {
                // Repeat of the gesture that applied this tag — undo just this
                // tag and leave every other gesture-applied tag untouched.
                removeTag(file, tagName);
            } else if (!file.hasTag(tagName)) {
                applyTag(file, tagName);
            }
            // else: file already carried the tag without it being applied in
            // this session — keep the original no-op behaviour.
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteTag(String name) {
        executor.submit(() -> {
            synchronized (tagMap) {
                Tag tag = tagMap.remove(name);
                if (tag != null) db.tagDao().delete(tag);
            }
            notifyTagsChanged();
        });
    }

    // ── Import tags from index ────────────────────────────────────────────────

    // Auto-populate tagMap from tags already written to files
    public void importTagsFromFiles(List<String> tagNames) {
        executor.submit(() -> {
            synchronized (tagMap) {
                for (String name : tagNames) {
                    if (!tagMap.containsKey(name)) {
                        Tag tag = new Tag(name);
                        db.tagDao().insert(tag);
                        tagMap.put(name, tag);
                    }
                }
            }
            notifyTagsChanged();
        });
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<Tag> getAllTags() {
        synchronized (tagMap) {
            List<Tag> list = new ArrayList<>(tagMap.values());
            Collections.sort(list, (a, b) ->
                Integer.compare(b.getUsageCount(), a.getUsageCount()));
            return list;
        }
    }

    public List<Tag> getTopTags(int n) {
        List<Tag> all = getAllTags();
        return all.subList(0, Math.min(n, all.size()));
    }

    public boolean hasTagName(String name) {
        synchronized (tagMap) {
            return tagMap.containsKey(name);
        }
    }

    public List<Tag> getRecentTags(int n) {
        List<Tag> result = new ArrayList<>();
        synchronized (tagMap) {
            int count = 0;
            for (String name : recentTags) {
                if (count >= n) break;
                Tag t = tagMap.get(name);
                if (t != null) {
                    result.add(t);
                    count++;
                }
            }
        }
        return result;
    }

    public List<Tag> searchTags(String query) {
        List<Tag> result = new ArrayList<>();
        String lower = query.toLowerCase();
        synchronized (tagMap) {
            for (Tag t : tagMap.values()) {
                if (t.getName().toLowerCase().contains(lower)) result.add(t);
            }
        }
        return result;
    }

    public Map<String, Integer> computeCoOccurrences(
            String tagName, List<MediaFile> files) {
        Map<String, Integer> coMap = new HashMap<>();
        for (MediaFile f : files) {
            if (!f.hasTag(tagName)) continue;
            for (String other : f.getTags()) {
                if (other.equals(tagName)) continue;
                int count = coMap.containsKey(other) ? coMap.get(other) : 0;
                coMap.put(other, count + 1);
            }
        }
        return coMap;
    }
}
