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

    // Last applied tag per file path — for undo on second gesture
    private final Map<String, String> lastApplied = new HashMap<>();

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

    // ── Create ────────────────────────────────────────────────────────────────

    public void createTag(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return;
        executor.submit(() -> {
            synchronized (tagMap) {
                if (tagMap.containsKey(trimmed)) return;
                Tag tag = new Tag(trimmed);
                db.tagDao().insert(tag);
                tagMap.put(trimmed, tag);
            }
            notifyTagsChanged();
        });
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    public void applyTag(MediaFile file, String tagName) {
        if (file.hasTag(tagName)) return;
        file.addTag(tagName);
        addToRecent(tagName);
        lastApplied.put(file.getPath(), tagName);

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
        String last = lastApplied.get(file.getPath());
        if (last == null || !file.hasTag(last)) return false;
        removeTag(file, last);
        lastApplied.remove(file.getPath());
        return true;
    }

    // ── Toggle ────────────────────────────────────────────────────────────────

    public void toggleTag(MediaFile file, String tagName) {
        if (file.hasTag(tagName)) removeTag(file, tagName);
        else                      applyTag(file, tagName);
    }

    // ── Toggle with undo — second call removes last applied ───────────────────

    public void applyOrUndo(MediaFile file, String tagName) {
        String last = lastApplied.get(file.getPath());
        if (last != null && last.equals(tagName) && file.hasTag(tagName)) {
            // Second press — undo
            removeTag(file, tagName);
            lastApplied.remove(file.getPath());
        } else {
            applyTag(file, tagName);
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
                coMap.merge(other, 1, Integer::sum);
            }
        }
        return coMap;
    }
}
