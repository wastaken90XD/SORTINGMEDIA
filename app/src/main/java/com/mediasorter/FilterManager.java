package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class FilterManager {

    public enum Filter { ALL, UNTAGGED, FLAGGED, SKIPPED, DONE }

    private Filter     current    = Filter.ALL;
    private FileStatus fileStatus;

    public FilterManager(FileStatus fileStatus) {
        this.fileStatus = fileStatus;
    }

    public void setFilter(Filter f) { this.current = f; }
    public Filter getCurrent()      { return current; }

    public List<MediaFile> apply(List<MediaFile> files) {
        if (current == Filter.ALL) return new ArrayList<>(files);

        List<MediaFile> result = new ArrayList<>();
        for (MediaFile f : files) {
            switch (current) {
                case UNTAGGED:
                    if (f.getTags().isEmpty()) result.add(f);
                    break;
                case FLAGGED:
                    if (fileStatus.isFlagged(f.getPath())) result.add(f);
                    break;
                case SKIPPED:
                    if (fileStatus.isSkipped(f.getPath())) result.add(f);
                    break;
                case DONE:
                    if (fileStatus.isDone(f.getPath())) result.add(f);
                    break;
            }
        }
        return result;
    }

    public String getLabel() {
        switch (current) {
            case ALL:      return "All";
            case UNTAGGED: return "Untagged";
            case FLAGGED:  return "Flagged";
            case SKIPPED:  return "Skipped";
            case DONE:     return "Done";
            default:       return "All";
        }
    }
}
