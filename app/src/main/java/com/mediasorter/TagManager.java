package com.mediasorter;

import android.content.Context;
import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TagManager {

    private final TagDatabase     db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // In-memory tag map for fast access
    private final Map<String, Tag> tagMap = new HashMap<>();

    public TagManager(Context context) {
        this.db = TagDatabase.getInstance(context);
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

    // ── Create ────────────────────────────────────────────────────────────────

    public void createTag(String name) {
        executor.submit(() -> {
            Tag tag = new Tag(name.trim());
            db.tagDao().insert(tag);
            synchronized (tagMap) { tagMap.put(name, tag); }
        });
    }

    // ── Apply / Remove ────────────────────────────────────────────────────────

public void applyTag(MediaFile file, String tagName) {
    if (file.hasTag(tagName)) return;
    file.addTag(tagName);

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
        // Write to file metadata
        MetadataWriter.writeTags(file.getPath(), file.getTags());
    });
}

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
        // Write updated tags to file metadata
        MetadataWriter.writeTags(file.getPath(), file.getTags());
    });
}

    public void toggleTag(MediaFile file, String tagName) {
        if (file.hasTag(tagName)) removeTag(file, tagName);
        else                      applyTag(file, tagName);
    }

    // ── Delete tag ────────────────────────────────────────────────────────────

    public void deleteTag(String name) {
        executor.submit(() -> {
            synchronized (tagMap) {
                Tag tag = tagMap.remove(name);
                if (tag != null) db.tagDao().delete(tag);
            }
        });
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<Tag> getAllTags() {
        synchronized (tagMap) {
            return new ArrayList<>(tagMap.values());
        }
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

    // ── Co-occurrence (simplified relationship graph) ─────────────────────────

    public Map<String, Integer> getCoOccurrences(String tagName) {
        Map<String, Integer> coMap = new HashMap<>();
        // Populated externally by passing the full file index
        return coMap;
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
