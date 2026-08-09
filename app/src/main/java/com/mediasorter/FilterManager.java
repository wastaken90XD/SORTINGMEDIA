package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FilterManager {

    public enum Filter {
        ALL, UNTAGGED, FLAGGED, SKIPPED, DONE,
        TAGGED, IMAGES, VIDEOS, DUPLICATES, BY_TAG
    }

    private Filter     current    = Filter.ALL;
    private FileStatus fileStatus;
    private final Set<Filter> activeFilters = new LinkedHashSet<>();
    private final Set<String> duplicatePaths = new HashSet<>();
    private String selectedTag = "";

    public FilterManager(FileStatus fileStatus) {
        this.fileStatus = fileStatus;
        activeFilters.add(Filter.ALL);
    }

    /** Existing single-filter API remains single-filter for the old menu. */
    public synchronized void setFilter(Filter f) {
        current = f == null ? Filter.ALL : f;
        if (current != Filter.BY_TAG) selectedTag = "";
        activeFilters.clear();
        activeFilters.add(current);
    }

    public synchronized Filter getCurrent() { return current; }

    /** Gallery chip API. Filters combine with AND logic. */
    public synchronized void toggleFilter(Filter f) {
        if (f == null) return;
        if (f == Filter.ALL) {
            activeFilters.clear();
            activeFilters.add(Filter.ALL);
            current = Filter.ALL;
            return;
        }
        activeFilters.remove(Filter.ALL);
        if (activeFilters.contains(f)) activeFilters.remove(f);
        else activeFilters.add(f);
        if (activeFilters.isEmpty()) activeFilters.add(Filter.ALL);
        current = activeFilters.size() == 1
                ? activeFilters.iterator().next() : Filter.ALL;
    }

    public synchronized void clearFilters() {
        activeFilters.clear();
        activeFilters.add(Filter.ALL);
        current = Filter.ALL;
        selectedTag = "";
    }

    public synchronized boolean isActive(Filter f) {
        return activeFilters.contains(f)
                || (f == Filter.ALL && activeFilters.isEmpty());
    }

    public synchronized Set<Filter> getActiveFilters() {
        return new LinkedHashSet<>(activeFilters);
    }

    public synchronized void setTagFilter(String tag) {
        selectedTag = tag == null ? "" : tag;
        if (selectedTag.isEmpty()) {
            activeFilters.remove(Filter.BY_TAG);
        } else {
            activeFilters.remove(Filter.ALL);
            activeFilters.add(Filter.BY_TAG);
        }
        if (activeFilters.isEmpty()) activeFilters.add(Filter.ALL);
        current = activeFilters.size() == 1
                ? activeFilters.iterator().next() : Filter.ALL;
    }

    public synchronized String getTagFilter() { return selectedTag; }

    public synchronized void setDuplicatePaths(Set<String> paths) {
        duplicatePaths.clear();
        if (paths != null) duplicatePaths.addAll(paths);
    }

    public synchronized Set<String> getDuplicatePaths() {
        return new HashSet<>(duplicatePaths);
    }

    public List<MediaFile> apply(List<MediaFile> files) {
        if (files == null) return new ArrayList<>();
        Set<Filter> filters;
        String tag;
        Set<String> duplicates;
        synchronized (this) {
            filters = new LinkedHashSet<>(activeFilters);
            tag = selectedTag;
            duplicates = new HashSet<>(duplicatePaths);
        }
        if (filters.isEmpty() || filters.contains(Filter.ALL)) {
            return new ArrayList<>(files);
        }

        List<MediaFile> result = new ArrayList<>();
        for (MediaFile file : files) {
            if (file == null) continue;
            boolean keep = true;
            for (Filter filter : filters) {
                if (!matches(filter, file, tag, duplicates)) {
                    keep = false;
                    break;
                }
            }
            if (keep) result.add(file);
        }
        return result;
    }

    private boolean matches(Filter filter, MediaFile file, String tag,
                             Set<String> duplicates) {
        switch (filter) {
            case UNTAGGED:    return file.getTags().isEmpty();
            case TAGGED:      return !file.getTags().isEmpty();
            case FLAGGED:     return fileStatus.isFlagged(file.getPath());
            case SKIPPED:     return fileStatus.isSkipped(file.getPath());
            case DONE:        return fileStatus.isDone(file.getPath());
            case IMAGES:      return file.getType() == MediaFile.Type.IMAGE;
            case VIDEOS:      return file.getType() == MediaFile.Type.VIDEO;
            case DUPLICATES:  return duplicates.contains(file.getPath());
            case BY_TAG:      return !tag.isEmpty() && file.hasTag(tag);
            case ALL:
            default:          return true;
        }
    }

    public synchronized String getLabel() {
        if (activeFilters.size() <= 1) {
            switch (current) {
                case ALL:      return "All";
                case UNTAGGED: return "Untagged";
                case FLAGGED:  return "Flagged";
                case SKIPPED:  return "Skipped";
                case DONE:     return "Done";
                case TAGGED:   return "Tagged";
                case IMAGES:   return "Images";
                case VIDEOS:   return "Videos";
                case DUPLICATES: return "Duplicates";
                case BY_TAG:   return selectedTag.isEmpty() ? "By Tag" : selectedTag;
                default:       return "All";
            }
        }
        return activeFilters.size() + " filters";
    }
}
