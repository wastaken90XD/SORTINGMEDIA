package com.mediasorter;

import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GroupManager {

    private volatile Group.GroupBy current = Group.GroupBy.FILE_TYPE;

    public void setGroupBy(Group.GroupBy g) { this.current = g; }
    public Group.GroupBy getCurrent()        { return current; }

    public List<Group> group(List<MediaFile> files) {
        if (files == null || files.isEmpty()) return new ArrayList<>();
        try {
            switch (current) {
                case FILE_TYPE: return groupByType(files);
                case TAG:       return groupByTag(files);
                case DATE:      return groupByDate(files);
                case FOLDER:    return groupByFolder(files);
                default:        return groupByType(files);
            }
        } catch (Exception e) {
            List<Group> fallback = new ArrayList<>();
            Group g = new Group("All", Group.GroupBy.FILE_TYPE);
            for (MediaFile f : files) g.addFile(f);
            fallback.add(g);
            return fallback;
        }
    }

    private List<Group> groupByType(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (MediaFile f : files) {
            if (f == null) continue;
            String key = f.getType() != null ? f.getType().name() : "UNKNOWN";
            if (!map.containsKey(key)) {
                map.put(key, new Group(key, Group.GroupBy.FILE_TYPE));
            }
            map.get(key).addFile(f);
        }
        return new ArrayList<>(map.values());
    }

    private List<Group> groupByTag(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        Group untagged = new Group("Untagged", Group.GroupBy.TAG);
        for (MediaFile f : files) {
            if (f == null) continue;
            List<String> tags = f.getTags();
            if (tags == null || tags.isEmpty()) {
                untagged.addFile(f);
            } else {
                for (String tag : tags) {
                    if (!map.containsKey(tag)) {
                        map.put(tag, new Group(tag, Group.GroupBy.TAG));
                    }
                    map.get(tag).addFile(f);
                }
            }
        }
        List<Group> result = new ArrayList<>(map.values());
        if (!untagged.getFiles().isEmpty()) result.add(untagged);
        return result;
    }

    private List<Group> groupByDate(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM", Locale.getDefault());
        for (MediaFile f : files) {
            if (f == null) continue;
            String key;
            try {
                key = sdf.format(new Date(f.getDateAdded()));
            } catch (Exception e) {
                key = "Unknown";
            }
            if (!map.containsKey(key)) {
                map.put(key, new Group(key, Group.GroupBy.DATE));
            }
            map.get(key).addFile(f);
        }
        return new ArrayList<>(map.values());
    }

    private List<Group> groupByFolder(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (MediaFile f : files) {
            if (f == null) continue;
            String path = f.getPath();
            if (path == null) continue;
            java.io.File file = new java.io.File(path);
            String folder = file.getParentFile() != null
                ? file.getParentFile().getName() : "Unknown";
            if (!map.containsKey(folder)) {
                map.put(folder, new Group(folder, Group.GroupBy.FOLDER));
            }
            map.get(folder).addFile(f);
        }
        return new ArrayList<>(map.values());
    }
}
