package com.mediasorter;

import com.mediasorter.features.RandomGenerator;
import com.mediasorter.models.MediaFile;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SortManager {

    public enum SortBy {
        NAME_ASC, NAME_DESC,
        SIZE_ASC, SIZE_DESC,
        DATE_ASC, DATE_DESC,
        TYPE, SHUFFLE,
        MANUAL_ORDER, TAG_COUNT_DESC, TAG_COUNT_ASC,
        FLAGGED_FIRST, UNTAGGED_FIRST
    }

    private volatile SortBy current = SortBy.NAME_ASC;
    private FileStatus fileStatus;

    public void setSortBy(SortBy s) { this.current = s == null ? SortBy.NAME_ASC : s; }
    public SortBy getCurrent()      { return current; }
    public void setFileStatus(FileStatus status) { this.fileStatus = status; }

    public void sort(List<MediaFile> files) {
        switch (current) {
            case NAME_ASC:
                Collections.sort(files, (a, b) ->
                    a.getName().compareToIgnoreCase(b.getName()));
                break;
            case NAME_DESC:
                Collections.sort(files, (a, b) ->
                    b.getName().compareToIgnoreCase(a.getName()));
                break;
            case SIZE_ASC:
                Collections.sort(files, (a, b) ->
                    Long.compare(a.getSize(), b.getSize()));
                break;
            case SIZE_DESC:
                Collections.sort(files, (a, b) ->
                    Long.compare(b.getSize(), a.getSize()));
                break;
            case DATE_ASC:
                Collections.sort(files, (a, b) ->
                    Long.compare(a.getDateAdded(), b.getDateAdded()));
                break;
            case DATE_DESC:
                Collections.sort(files, (a, b) ->
                    Long.compare(b.getDateAdded(), a.getDateAdded()));
                break;
            case TYPE:
                Collections.sort(files, (a, b) ->
                    a.getType().name().compareTo(b.getType().name()));
                break;
            case MANUAL_ORDER:
                Collections.sort(files, new Comparator<MediaFile>() {
                    @Override public int compare(MediaFile a, MediaFile b) {
                        int ao = a.getManualOrder();
                        int bo = b.getManualOrder();
                        if (ao < 0 && bo < 0) return 0;
                        if (ao < 0) return 1;
                        if (bo < 0) return -1;
                        return Integer.compare(ao, bo);
                    }
                });
                break;
            case TAG_COUNT_DESC:
                Collections.sort(files, new Comparator<MediaFile>() {
                    @Override public int compare(MediaFile a, MediaFile b) {
                        return Integer.compare(b.getTags().size(), a.getTags().size());
                    }
                });
                break;
            case TAG_COUNT_ASC:
                Collections.sort(files, new Comparator<MediaFile>() {
                    @Override public int compare(MediaFile a, MediaFile b) {
                        return Integer.compare(a.getTags().size(), b.getTags().size());
                    }
                });
                break;
            case FLAGGED_FIRST:
                Collections.sort(files, new Comparator<MediaFile>() {
                    @Override public int compare(MediaFile a, MediaFile b) {
                        boolean af = fileStatus != null && fileStatus.isFlagged(a.getPath());
                        boolean bf = fileStatus != null && fileStatus.isFlagged(b.getPath());
                        return Boolean.compare(bf, af);
                    }
                });
                break;
            case UNTAGGED_FIRST:
                Collections.sort(files, new Comparator<MediaFile>() {
                    @Override public int compare(MediaFile a, MediaFile b) {
                        return Boolean.compare(!a.getTags().isEmpty(), !b.getTags().isEmpty());
                    }
                });
                break;
            case SHUFFLE:
                shuffle(files);
                break;
        }
    }

    /** Fisher–Yates shuffle driven by RandomGenerator so the order is
     *  different every time "Shuffle" is picked from the sort menu. */
    private void shuffle(List<MediaFile> files) {
        RandomGenerator.shuffle(files);
    }

    public String getLabel() {
        switch (current) {
            case NAME_ASC:       return "Name A-Z";
            case NAME_DESC:      return "Name Z-A";
            case SIZE_ASC:       return "Size ↑";
            case SIZE_DESC:      return "Size ↓";
            case DATE_ASC:       return "Date ↑";
            case DATE_DESC:      return "Date ↓";
            case TYPE:           return "Type";
            case MANUAL_ORDER:   return "Manual order";
            case TAG_COUNT_DESC: return "Tag count most";
            case TAG_COUNT_ASC:  return "Tag count least";
            case FLAGGED_FIRST:  return "Flagged first";
            case UNTAGGED_FIRST: return "Untagged first";
            case SHUFFLE:        return "Shuffle";
            default:             return "Sort";
        }
    }
}
