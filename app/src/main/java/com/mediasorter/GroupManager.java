package com.mediasorter;

import com.mediasorter.models.Group;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the grouping views used by the list and gallery. */
public class GroupManager {

    private volatile Group.GroupBy current = Group.GroupBy.FILE_TYPE;
    private volatile String watchedRoot = "";
    private final Map<String, String> manualAssignments =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private volatile String lastError = "";

    public void setGroupBy(Group.GroupBy value) {
        current = value == null ? Group.GroupBy.FILE_TYPE : value;
        lastError = "";
    }

    public Group.GroupBy getCurrent() { return current; }
    public String getLastError() { return lastError; }

    public void setWatchedRoot(String root) { watchedRoot = root == null ? "" : root; }

    public void setManualAssignments(Map<String, String> assignments) {
        manualAssignments.clear();
        if (assignments != null) manualAssignments.putAll(assignments);
    }

    public void assignManualGroup(String path, String groupName) {
        if (path == null || groupName == null || groupName.trim().isEmpty()) return;
        manualAssignments.put(path, groupName.trim());
    }

    public void removeManualAssignment(String path) {
        if (path != null) manualAssignments.remove(path);
    }

    public void moveManualAssignment(String oldPath, String newPath) {
        if (oldPath == null || newPath == null) return;
        String group = manualAssignments.remove(oldPath);
        if (group != null) manualAssignments.put(newPath, group);
    }

    public Map<String, String> getManualAssignments() {
        return new java.util.HashMap<String, String>(manualAssignments);
    }

    public boolean hasColorProfiles(List<MediaFile> files) {
        if (files == null || files.isEmpty()) return false;
        for (MediaFile file : files) {
            if (file != null && !file.getColorFamily().isEmpty()) return true;
        }
        return false;
    }

    public List<Group> group(List<MediaFile> files) {
        lastError = "";
        if (files == null || files.isEmpty()) return new ArrayList<Group>();
        try {
            switch (current) {
                case FILE_TYPE: return groupByType(files);
                case TAG: return groupByTag(files);
                case DATE: return groupByDate(files);
                case FOLDER: return groupByFolder(files);
                case TAG_PREFIX: return groupByTagPrefix(files);
                case SEQUENCE_GROUP: return groupBySequence(files);
                case COLOR_PROFILE: return groupByColor(files);
                case DIRECTORY_DEPTH: return groupByDepth(files);
                case MANUAL_GROUP: return groupByManual(files);
                default: return groupByType(files);
            }
        } catch (Exception error) {
            lastError = error.getMessage() == null ? "Unable to group files" : error.getMessage();
            List<Group> fallback = new ArrayList<Group>();
            Group all = new Group("All", Group.GroupBy.FILE_TYPE);
            for (MediaFile file : files) if (file != null) all.addFile(file);
            fallback.add(all);
            return fallback;
        }
    }

    private List<Group> groupByType(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            if (file == null) continue;
            String key = file.getType() == null ? "UNKNOWN" : file.getType().name();
            add(map, key, Group.GroupBy.FILE_TYPE, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByTag(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        Group untagged = new Group("Untagged", Group.GroupBy.TAG);
        for (MediaFile file : files) {
            if (file == null) continue;
            List<String> tags = file.getTags();
            if (tags == null || tags.isEmpty()) untagged.addFile(file);
            else for (String tag : tags) add(map, tag, Group.GroupBy.TAG, file);
        }
        List<Group> result = new ArrayList<Group>(map.values());
        if (untagged.getCount() > 0) result.add(untagged);
        return result;
    }

    private List<Group> groupByDate(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM", Locale.US);
        for (MediaFile file : files) {
            if (file == null) continue;
            String key;
            try { key = format.format(new Date(file.getDateAdded())); }
            catch (Exception ignored) { key = "Unknown"; }
            add(map, key, Group.GroupBy.DATE, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByFolder(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            if (file == null) continue;
            File parent = new File(file.getPath()).getParentFile();
            add(map, parent == null ? "Unknown" : parent.getName(), Group.GroupBy.FOLDER, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByTagPrefix(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            String first = firstTag(file);
            int underscore = first.indexOf('_');
            int hyphen = first.indexOf('-');
            int cut = underscore < 0 ? hyphen : hyphen < 0 ? underscore : Math.min(underscore, hyphen);
            String key = cut > 0 ? first.substring(0, cut) : (first.isEmpty() ? "Untagged" : first);
            add(map, key, Group.GroupBy.TAG_PREFIX, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupBySequence(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            String key = sequenceGroup(file);
            if (key.isEmpty()) key = "No sequence group";
            add(map, key, Group.GroupBy.SEQUENCE_GROUP, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByColor(List<MediaFile> files) {
        if (!hasColorProfiles(files)) {
            lastError = "Run color analysis first.";
            return new ArrayList<Group>();
        }
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            String key = file.getColorFamily();
            if (key.isEmpty()) key = "Unknown";
            add(map, key, Group.GroupBy.COLOR_PROFILE, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByDepth(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            String key = String.valueOf(directoryDepth(file));
            add(map, key, Group.GroupBy.DIRECTORY_DEPTH, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private List<Group> groupByManual(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        for (MediaFile file : files) {
            String key = manualAssignments.get(file.getPath());
            if (key == null || key.trim().isEmpty()) key = "Unassigned";
            add(map, key, Group.GroupBy.MANUAL_GROUP, file);
        }
        return new ArrayList<Group>(map.values());
    }

    private void add(Map<String, Group> map, String key, Group.GroupBy mode, MediaFile file) {
        if (key == null || key.isEmpty()) key = "Unknown";
        Group group = map.get(key);
        if (group == null) {
            group = new Group(key, mode);
            map.put(key, group);
        }
        group.addFile(file);
    }

    private String firstTag(MediaFile file) {
        if (file == null || file.getTags() == null || file.getTags().isEmpty()) return "";
        return file.getTags().get(0) == null ? "" : file.getTags().get(0).trim();
    }

    private String sequenceGroup(MediaFile file) {
        if (file == null || file.getTags() == null) return "";
        for (String value : file.getTags()) {
            if (value == null) continue;
            String tag = value.trim();
            if (tag.startsWith("link_") || tag.startsWith("link-")) {
                String group = tag.substring(5);
                int sequence = group.indexOf("_seq_");
                return sequence > 0 ? group.substring(0, sequence) : group;
            }
        }
        return "";
    }

    private int directoryDepth(MediaFile file) {
        if (file == null || file.getPath() == null) return 0;
        String root = watchedRoot == null ? "" : watchedRoot.replace('\\', '/');
        String path = file.getPath().replace('\\', '/');
        if (!root.isEmpty() && path.startsWith(root)) {
            String relative = path.substring(root.length());
            while (relative.startsWith("/")) relative = relative.substring(1);
            int depth = 0;
            String[] parts = relative.split("/");
            for (int i = 0; i < parts.length - 1; i++) if (!parts[i].isEmpty()) depth++;
            return depth;
        }
        return path.split("/").length - 1;
    }
}
