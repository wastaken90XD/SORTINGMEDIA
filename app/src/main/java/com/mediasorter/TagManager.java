package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TagManager {

    private static final String PREFS            = "tag_recent_prefs";
    private static final String KEY_RECENT       = "recent_tags";
    private static final String KEY_TAGS_ENABLED = "tags_enabled";
    private static final int    MAX_RECENT       = 10;

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
        executor.submit(new Runnable() {
            @Override public void run() {
                List<Tag> tags = db.tagDao().getAllByUsage();
                synchronized (tagMap) {
                    for (Tag t : tags) {
                        String plain = TagText.plain(t.getName());
                        if (plain.isEmpty()) continue;
                        if (!plain.equals(t.getName())) t.setName(plain);
                        tagMap.put(plain, t);
                    }
                }
                notifyTagsChanged();
            }
        });
    }

    // ── Recent tags ───────────────────────────────────────────────────────────

    private LinkedList<String> loadRecentTags() {
        LinkedList<String> list = new LinkedList<>();
        String saved = prefs.getString(KEY_RECENT, "");
        if (!saved.isEmpty()) {
            for (String t : saved.split(",")) {
                String plain = TagText.plain(t);
                if (!plain.isEmpty() && !list.contains(plain)) list.add(plain);
            }
        }
        return list;
    }

    public synchronized void reloadRecentTags() {
        recentTags.clear();
        recentTags.addAll(loadRecentTags());
    }

    private synchronized void saveRecentTags() {
        StringBuilder sb = new StringBuilder();
        for (String t : recentTags) {
            if (sb.length() > 0) sb.append(",");
            sb.append(t);
        }
        prefs.edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    private synchronized void addToRecent(String tagName) {
        String plain = TagText.plain(tagName);
        if (plain.isEmpty()) return;
        recentTags.remove(plain);
        recentTags.addFirst(plain);
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
        String trimmed = TagText.plain(name);
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
        executor.submit(new Runnable() {
            @Override public void run() { db.tagDao().insert(tag); }
        });
        notifyTagsChanged();
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    public void applyTag(MediaFile file, String tagName) {
        String plain = TagText.plain(tagName);
        if (plain.isEmpty() || file.hasTag(plain)) return;
        file.addTag(plain);
        addToRecent(plain);
        recordApplied(file.getPath(), plain);

        executor.submit(new Runnable() {
            @Override public void run() {
                synchronized (tagMap) {
                    Tag tag = tagMap.get(plain);
                    if (tag == null) {
                        tag = new Tag(plain);
                        tagMap.put(plain, tag);
                        db.tagDao().insert(tag);
                    }
                    tag.incrementUsage();
                    db.tagDao().update(tag);
                }
                MetadataWriter.writeTags(file.getPath(), file.getTags());
                notifyTagsChanged();
            }
        });
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    public void removeTag(MediaFile file, String tagName) {
        String plain = TagText.plain(tagName);
        if (plain.isEmpty() || !file.hasTag(plain)) return;
        file.removeTag(plain);
        unrecordApplied(file.getPath(), plain);

        executor.submit(new Runnable() {
            @Override public void run() {
                synchronized (tagMap) {
                    Tag tag = tagMap.get(plain);
                    if (tag != null) {
                        tag.decrementUsage();
                        db.tagDao().update(tag);
                    }
                }
                MetadataWriter.writeTags(file.getPath(), file.getTags());
                notifyTagsChanged();
            }
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
        String plain = TagText.plain(tagName);
        if (plain.isEmpty()) return;
        // Compound check-then-act under the lock (reentrant; applyTag/
        // removeTag re-acquire it in recordApplied/unrecordApplied). No other
        // monitor is taken while stackLock is held, so no lock-order issues.
        synchronized (stackLock) {
            LinkedList<String> stack = appliedStack.get(file.getPath());
            boolean tracked = stack != null && stack.contains(plain);
            if (tracked && file.hasTag(plain)) {
                // Repeat of the gesture that applied this tag — undo just this
                // tag and leave every other gesture-applied tag untouched.
                removeTag(file, plain);
            } else if (!file.hasTag(plain)) {
                applyTag(file, plain);
            }
            // else: file already carried the tag without it being applied in
            // this session — keep the original no-op behaviour.
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteTag(String name) {
        String plain = TagText.plain(name);
        if (plain.isEmpty()) return;
        final Tag removed;
        synchronized (tagMap) {
            removed = tagMap.remove(plain);
        }
        notifyTagsChanged();
        executor.submit(new Runnable() {
            @Override public void run() {
                Tag stored = removed != null ? removed : db.tagDao().getByName(plain);
                if (stored != null) db.tagDao().delete(stored);
                notifyTagsChanged();
            }
        });
    }

    // ── Import tags from index ────────────────────────────────────────────────

    // Auto-populate tagMap from tags already written to files
    public void importTagsFromFiles(List<String> tagNames) {
        executor.submit(new Runnable() {
            @Override public void run() {
                if (tagNames == null) return;
                synchronized (tagMap) {
                    for (String name : tagNames) {
                        String plain = TagText.plain(name);
                        if (plain.isEmpty() || tagMap.containsKey(plain)) continue;
                        Tag tag = new Tag(plain);
                        db.tagDao().insert(tag);
                        tagMap.put(plain, tag);
                    }
                }
                notifyTagsChanged();
            }
        });
    }

    /**
     * Synchronizes the global tag catalog with every indexed file. Tags found
     * in XMP or another external source are first-class global tags just like
     * tags created in the app. The usage count is rebuilt from the complete
     * index so sorting and every tag picker agree on the same library state.
     * Existing zero-use tags are retained because they may be user-created
     * library tags that are not currently assigned to a file.
     */
    public void syncTagsFromFiles(List<MediaFile> files) {
        final Map<String, Integer> usage = new HashMap<String, Integer>();
        if (files != null) {
            for (MediaFile file : files) {
                if (file == null || file.getTags() == null) continue;
                Set<String> seenInFile = new HashSet<String>();
                for (String raw : file.getTags()) {
                    String plain = TagText.plain(raw);
                    if (plain.isEmpty() || !seenInFile.add(plain)) continue;
                    Integer count = usage.get(plain);
                    usage.put(plain, count == null ? 1 : count + 1);
                }
            }
        }

        executor.submit(new Runnable() {
            @Override public void run() {
                synchronized (tagMap) {
                    for (Map.Entry<String, Integer> entry : usage.entrySet()) {
                        String name = entry.getKey();
                        int count = entry.getValue() == null ? 0 : entry.getValue();
                        Tag tag = tagMap.get(name);
                        boolean created = false;
                        if (tag == null) {
                            tag = new Tag(name);
                            tagMap.put(name, tag);
                            created = true;
                        }
                        if (tag.getUsageCount() != count) tag.setUsageCount(count);
                        if (created) db.tagDao().insert(tag);
                        else db.tagDao().update(tag);
                    }
                    for (Tag tag : tagMap.values()) {
                        if (!usage.containsKey(tag.getName()) && tag.getUsageCount() != 0) {
                            tag.setUsageCount(0);
                            db.tagDao().update(tag);
                        }
                    }
                }
                notifyTagsChanged();
            }
        });
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<Tag> getAllTags() {
        synchronized (tagMap) {
            List<Tag> list = new ArrayList<>(tagMap.values());
            Collections.sort(list, new java.util.Comparator<Tag>() {
                @Override public int compare(Tag a, Tag b) {
                    return Integer.compare(b.getUsageCount(), a.getUsageCount());
                }
            });
            return list;
        }
    }

    public List<Tag> getTopTags(int n) {
        List<Tag> all = getAllTags();
        return all.subList(0, Math.min(n, all.size()));
    }

    public boolean hasTagName(String name) {
        String plain = TagText.plain(name);
        if (plain.isEmpty()) return false;
        synchronized (tagMap) {
            return tagMap.containsKey(plain);
        }
    }

    public synchronized List<Tag> getRecentTags(int n) {
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

    // ── Global "tags UI enabled" toggle ───────────────────────────────────────
    // When disabled, main windows hide tag prompts, quick-tag popups and the
    // tag side panel. Tags already on files are untouched and the organizer
    // keeps working — this only gates interactive tag UI.

    public boolean isTagsEnabled() {
        return prefs.getBoolean(KEY_TAGS_ENABLED, true);
    }

    public void setTagsEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_TAGS_ENABLED, on).apply();
        notifyTagsChanged();
    }
}
