package com.mediasorter.models;

import com.mediasorter.TagText;
import java.util.ArrayList;
import java.util.List;

public class TagList {

    private String       name;
    private List<String> tags;
    private boolean      isDefault;

    public TagList(String name) {
        this.name      = name;
        this.tags      = new ArrayList<>();
        this.isDefault = false;
    }

    public TagList(String name, List<String> tags) {
        this.name      = name;
        this.tags      = new ArrayList<>();
        this.isDefault = false;
        setTags(tags);
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getName()              { return name; }
    public void   setName(String name)   { this.name = name; }

    public List<String> getTags()        { return new ArrayList<>(tags); }
    public void setTags(List<String> t) {
        this.tags = new ArrayList<>();
        if (t != null) {
            for (String tag : t) addTag(tag);
        }
    }

    public boolean isDefault()           { return isDefault; }
    public void setDefault(boolean d)    { this.isDefault = d; }

    // ── Tag operations ────────────────────────────────────────────────────────

    public void addTag(String tag) {
        String plain = TagText.plain(tag);
        if (!plain.isEmpty() && !tags.contains(plain)) tags.add(plain);
    }

    public void removeTag(String tag) {
        tags.remove(TagText.plain(tag));
    }

    public void moveTag(int from, int to) {
        if (from < 0 || from >= tags.size()) return;
        if (to   < 0 || to   >= tags.size()) return;
        String tag = tags.remove(from);
        tags.add(to, tag);
    }

    public boolean containsTag(String tag) {
        return tags.contains(TagText.plain(tag));
    }

    public int size() { return tags.size(); }

    @Override
    public String toString() { return name; }
}
