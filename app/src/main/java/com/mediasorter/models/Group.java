package com.mediasorter.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Group {

    public enum GroupBy {
        TAG, DATE, FILE_TYPE, FOLDER, RATING,
        TAG_PREFIX, SEQUENCE_GROUP, COLOR_PROFILE, DIRECTORY_DEPTH, MANUAL_GROUP
    }

    private String          label;
    private List<MediaFile> files;
    private GroupBy         groupBy;
    private int              totalCount = -1;

    public Group(String label, GroupBy groupBy) {
        this.label   = label;
        this.groupBy = groupBy;
        this.files   = new ArrayList<>();
    }

    public String          getLabel()   { return label; }
    public GroupBy         getGroupBy() { return groupBy; }
    public List<MediaFile> getFiles()   { return Collections.unmodifiableList(files); }
    public int             getCount()   { return files.size(); }
    public int             getTotalCount() { return totalCount >= 0 ? totalCount : files.size(); }
    public void setTotalCount(int count) { totalCount = count; }

    public void addFile(MediaFile f)    { files.add(f); }
    public void removeFile(MediaFile f) { files.remove(f); }
    public void clear()                 { files.clear(); }
}
