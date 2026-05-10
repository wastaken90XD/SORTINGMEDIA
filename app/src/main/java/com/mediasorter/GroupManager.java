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

public class GroupManager {

    private Group.GroupBy currentGroupBy = Group.GroupBy.FILE_TYPE;

    public void setGroupBy(Group.GroupBy g) {
        this.currentGroupBy = g;
    }

    public Group.GroupBy getGroupBy() {
        return currentGroupBy;
    }

    public List<Group> group(List<MediaFile> files) {
        switch (currentGroupBy) {
            case TAG:       return groupByTag(files);
            case DATE:      return groupByDate(files);
            case FILE_TYPE: return groupByType(files);
            case FOLDER:    return groupByFolder(files);
            default:        return groupByType(files);
        }
    }

    private List<Group> groupByTag(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        Group untagged = new Group("Untagged", Group.GroupBy.TAG);

        for (MediaFile f : files) {
            if (f.getTags().isEmpty()) {
                untagged.addFile(f);
            } else {
                for (String tag : f.getTags()) {
                    map.computeIfAbsent(tag,
                        k -> new Group(k, Group.GroupBy.TAG)).addFile(f);
                }
            }
        }

        List<Group> result = new ArrayList<>(map.values());
        if (untagged.getCount() > 0) result.add(untagged);
        return result;
    }

    private List<Group> groupByDate(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

        for (MediaFile f : files) {
            String date = sdf.format(new Date(f.getDateAdded()));
            map.computeIfAbsent(date,
                k -> new Group(k, Group.GroupBy.DATE)).addFile(f);
        }

        return new ArrayList<>(map.values());
    }

    private List<Group> groupByType(List<MediaFile> files) {
        Group images  = new Group("Images",  Group.GroupBy.FILE_TYPE);
        Group videos  = new Group("Videos",  Group.GroupBy.FILE_TYPE);
        Group other   = new Group("Other",   Group.GroupBy.FILE_TYPE);

        for (MediaFile f : files) {
            switch (f.getType()) {
                case IMAGE:  images.addFile(f); break;
                case VIDEO:  videos.addFile(f); break;
                default:     other.addFile(f);  break;
            }
        }

        List<Group> result = new ArrayList<>();
        if (images.getCount() > 0) result.add(images);
        if (videos.getCount() > 0) result.add(videos);
        if (other.getCount()  > 0) result.add(other);
        return result;
    }

    private List<Group> groupByFolder(List<MediaFile> files) {
        Map<String, Group> map = new LinkedHashMap<>();

        for (MediaFile f : files) {
            String folder = new File(f.getPath()).getParent();
            String label  = new File(folder).getName();
            map.computeIfAbsent(label,
                k -> new Group(k, Group.GroupBy.FOLDER)).addFile(f);
        }

        return new ArrayList<>(map.values());
    }
}
