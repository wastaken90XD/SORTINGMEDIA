package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.Collections;
import java.util.List;

public class SortManager {

    public enum SortBy {
        NAME_ASC, NAME_DESC,
        SIZE_ASC, SIZE_DESC,
        DATE_ASC, DATE_DESC,
        TYPE
    }

    private SortBy current = SortBy.NAME_ASC;

    public void setSortBy(SortBy s) { this.current = s; }
    public SortBy getCurrent()      { return current; }

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
        }
    }

    public String getLabel() {
        switch (current) {
            case NAME_ASC:  return "Name A-Z";
            case NAME_DESC: return "Name Z-A";
            case SIZE_ASC:  return "Size ↑";
            case SIZE_DESC: return "Size ↓";
            case DATE_ASC:  return "Date ↑";
            case DATE_DESC: return "Date ↓";
            case TYPE:      return "Type";
            default:        return "Sort";
        }
    }
}
