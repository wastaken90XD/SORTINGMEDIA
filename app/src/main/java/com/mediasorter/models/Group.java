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
    // Number of files from this group that precede the visible page/window.
    // It is presentation metadata, not a stored file position.
    private int              fileOffset;

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
    public int             getFileOffset() { return fileOffset; }
    public void setFileOffset(int offset) { fileOffset = Math.max(0, offset); }

    public void addFile(MediaFile f)    { files.add(f); }
    public void removeFile(MediaFile f) { files.remove(f); }
    public void clear()                 { files.clear(); }
}
