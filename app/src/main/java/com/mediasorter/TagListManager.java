package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.models.TagList;
import java.util.ArrayList;
import java.util.List;

public class TagListManager {

    private static final String PREFS        = "tag_list_prefs";
    private static final String KEY_LISTS    = "tag_lists";
    private static final String KEY_ACTIVE   = "active_list";
    private static final String KEY_COUNT    = "list_count";
    private static final String DEFAULT_NAME = "Default";

    private final SharedPreferences prefs;
    private final List<TagList>     lists  = new ArrayList<>();
    private       int               activeIndex = 0;

    public TagListManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    private void load() {
        int count = prefs.getInt(KEY_COUNT, 0);

        if (count == 0) {
            TagList def = new TagList(DEFAULT_NAME);
            def.setDefault(true);
            lists.add(def);
            activeIndex = 0;
            save();
            return;
        }

        for (int i = 0; i < count; i++) {
            String  name    = prefs.getString(KEY_LISTS + "_name_" + i, DEFAULT_NAME);
            String  tagsCsv = prefs.getString(KEY_LISTS + "_tags_" + i, "");
            boolean isDef   = prefs.getBoolean(KEY_LISTS + "_default_" + i, i == 0);

            TagList list = new TagList(name);
            list.setDefault(isDef);

            if (!tagsCsv.isEmpty()) {
                for (String t : tagsCsv.split(",")) {
                    if (!t.trim().isEmpty()) list.addTag(t.trim());
                }
            }
            lists.add(list);
        }

        activeIndex = prefs.getInt(KEY_ACTIVE, 0);
        if (activeIndex >= lists.size()) activeIndex = 0;
    }

    private void save() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt(KEY_COUNT, lists.size());
        ed.putInt(KEY_ACTIVE, activeIndex);
        for (int i = 0; i < lists.size(); i++) {
            TagList list = lists.get(i);
            ed.putString(KEY_LISTS  + "_name_" + i,    list.getName());
            ed.putBoolean(KEY_LISTS + "_default_" + i, list.isDefault());
            ed.putString(KEY_LISTS  + "_tags_" + i,
                joinTags(list.getTags()));
        }
        ed.apply();
    }

    // ── Active list ───────────────────────────────────────────────────────────

    public TagList getActiveList()       { return lists.isEmpty()
        ? new TagList(DEFAULT_NAME) : lists.get(activeIndex); }
    public int     getActiveIndex()      { return activeIndex; }

    public void setActiveIndex(int index) {
        if (index >= 0 && index < lists.size()) {
            activeIndex = index;
            save();
        }
    }

    public void setActiveByName(String name) {
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).getName().equals(name)) {
                activeIndex = i;
                save();
                return;
            }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public void createList(String name) {
        lists.add(new TagList(name));
        save();
    }

    public void deleteList(int index) {
        if (index < 0 || index >= lists.size()) return;
        if (lists.get(index).isDefault()) return;
        lists.remove(index);
        if (activeIndex >= lists.size()) activeIndex = lists.size() - 1;
        save();
    }

    public void renameList(int index, String name) {
        if (index < 0 || index >= lists.size()) return;
        lists.get(index).setName(name);
        save();
    }

    public void addTagToList(int index, String tag) {
        if (index < 0 || index >= lists.size()) return;
        lists.get(index).addTag(tag);
        save();
    }

    public void removeTagFromList(int index, String tag) {
        if (index < 0 || index >= lists.size()) return;
        lists.get(index).removeTag(tag);
        save();
    }

    public void moveTagInList(int listIndex, int from, int to) {
        if (listIndex < 0 || listIndex >= lists.size()) return;
        lists.get(listIndex).moveTag(from, to);
        save();
    }

    // ── Bulk import from scanned tags ─────────────────────────────────────────

    // Add all tags from index to active list — skips duplicates
    public int bulkAddToActiveList(List<String> tags) {
        if (lists.isEmpty()) return 0;
        TagList active = lists.get(activeIndex);
        int added = 0;
        for (String tag : tags) {
            if (!tag.isEmpty() && !active.containsTag(tag)) {
                active.addTag(tag);
                added++;
            }
        }
        if (added > 0) save();
        return added;
    }

    // Add all tags to a specific list
    public int bulkAddToList(int index, List<String> tags) {
        if (index < 0 || index >= lists.size()) return 0;
        TagList list = lists.get(index);
        int added = 0;
        for (String tag : tags) {
            if (!tag.isEmpty() && !list.containsTag(tag)) {
                list.addTag(tag);
                added++;
            }
        }
        if (added > 0) save();
        return added;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<TagList> getAllLists()  { return new ArrayList<>(lists); }
    public int           getCount()    { return lists.size(); }

    public String[] getListNames() {
        String[] names = new String[lists.size()];
        for (int i = 0; i < lists.size(); i++) names[i] = lists.get(i).getName();
        return names;
    }

    public TagList getList(int index) {
        if (index < 0 || index >= lists.size()) return null;
        return lists.get(index);
    }

    // API-21-safe replacement for String.join
    private static String joinTags(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String tag : tags) {
            if (i++ > 0) sb.append(",");
            sb.append(tag);
        }
        return sb.toString();
    }
}
