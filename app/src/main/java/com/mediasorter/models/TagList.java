package com.mediasorter.models;

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
        this.tags      = new ArrayList<>(tags);
        this.isDefault = false;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getName()              { return name; }
    public void   setName(String name)   { this.name = name; }

    public List<String> getTags()        { return new ArrayList<>(tags); }
    public void setTags(List<String> t)  { this.tags = new ArrayList<>(t); }

    public boolean isDefault()           { return isDefault; }
    public void setDefault(boolean d)    { this.isDefault = d; }

    // ── Tag operations ────────────────────────────────────────────────────────

    public void addTag(String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }

    public void removeTag(String tag) {
        tags.remove(tag);
    }

    public void moveTag(int from, int to) {
        if (from < 0 || from >= tags.size()) return;
        if (to   < 0 || to   >= tags.size()) return;
        String tag = tags.remove(from);
        tags.add(to, tag);
    }

    public boolean containsTag(String tag) {
        return tags.contains(tag);
    }

    public int size() { return tags.size(); }

    @Override
    public String toString() { return name; }
}
