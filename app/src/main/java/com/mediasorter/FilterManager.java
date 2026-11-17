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
    private final Set<String> selectedTags = new LinkedHashSet<>();

    public FilterManager(FileStatus fileStatus) {
        this.fileStatus = fileStatus;
        activeFilters.add(Filter.ALL);
    }

    /** Existing single-filter API remains single-filter for the old menu. */
    public synchronized void setFilter(Filter f) {
        current = f == null ? Filter.ALL : f;
        activeFilters.clear();
        activeFilters.add(current);
        // Tag-bar chips are session state and must survive ordinary filter,
        // sort, and search changes. Re-add the AND tag criterion after the
        // single filter selected by the legacy filter menu.
        if (!selectedTags.isEmpty()) {
            activeFilters.remove(Filter.ALL);
            activeFilters.add(Filter.BY_TAG);
        }
    }

    public synchronized Filter getCurrent() { return current; }

    /** Gallery chip API. Filters combine with AND logic. */
    public synchronized void toggleFilter(Filter f) {
        if (f == null) return;
        if (f == Filter.ALL) {
            activeFilters.clear();
            activeFilters.add(Filter.ALL);
            rebuildTagFilterState();
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
        selectedTags.clear();
    }

    public synchronized boolean isActive(Filter f) {
        return activeFilters.contains(f)
                || (f == Filter.ALL && activeFilters.isEmpty());
    }

    public synchronized Set<Filter> getActiveFilters() {
        return new LinkedHashSet<>(activeFilters);
    }

    public synchronized void setTagFilter(String tag) {
        selectedTags.clear();
        String clean = tag == null ? "" : tag.trim();
        if (!clean.isEmpty()) selectedTags.add(clean);
        selectedTag = clean;
        rebuildTagFilterState();
    }

    /** Toggle one tag chip without disturbing other active chips. */
    public synchronized void toggleTagFilter(String tag) {
        String clean = tag == null ? "" : tag.trim();
        if (clean.isEmpty()) return;
        if (selectedTags.contains(clean)) selectedTags.remove(clean);
        else selectedTags.add(clean);
        selectedTag = selectedTags.isEmpty() ? "" : selectedTags.iterator().next();
        rebuildTagFilterState();
    }

    public synchronized void setTagFilters(Set<String> tags) {
        selectedTags.clear();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.trim().isEmpty()) selectedTags.add(tag.trim());
            }
        }
        selectedTag = selectedTags.isEmpty() ? "" : selectedTags.iterator().next();
        rebuildTagFilterState();
    }

    public synchronized Set<String> getTagFilters() {
        return new LinkedHashSet<>(selectedTags);
    }

    public synchronized String getTagFilter() { return selectedTag; }

    private void rebuildTagFilterState() {
        activeFilters.remove(Filter.BY_TAG);
        if (!selectedTags.isEmpty()) {
            activeFilters.remove(Filter.ALL);
            activeFilters.add(Filter.BY_TAG);
        } else if (activeFilters.isEmpty()) {
            activeFilters.add(Filter.ALL);
        }
        current = activeFilters.size() == 1
                ? activeFilters.iterator().next() : Filter.ALL;
    }

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
            case BY_TAG:
                Set<String> required = getTagFilters();
                if (required.isEmpty()) return true;
                for (String requiredTag : required) {
                    if (!file.hasTag(requiredTag)) return false;
                }
                return true;
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
