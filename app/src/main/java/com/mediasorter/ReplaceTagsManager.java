package com.mediasorter;

import com.mediasorter.models.MediaFile;
import com.mediasorter.models.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worker-side planner for Replace Tags. It keeps sequence groups independent
 * and never uses the WindowManager page as a membership source.
 */
public final class ReplaceTagsManager {
    public enum SequenceRule { PROTECT, WHOLE_GROUP, OVERWRITE, KEEP_FIRST }

    public static final class Report {
        public int files;
        public int removedInstances;
        public int fullySelectedGroups;
        public int partialGroups;
        public final List<String> keptGroups = new ArrayList<String>();
        public final Map<String, List<String>> previousTags =
                new LinkedHashMap<String, List<String>>();
        public final List<String> purgedGroups = new ArrayList<String>();
        public final List<String> purgedTags = new ArrayList<String>();
        public final Map<String, Integer> purgedUsage = new HashMap<String, Integer>();
        public final Map<String, Long> purgedCreatedAt = new HashMap<String, Long>();
    }

    private ReplaceTagsManager() {}

    public static Report apply(List<MediaFile> fullSnapshot, Set<String> targetPaths,
                               List<String> replacementTags, String sequencePatterns,
                               String protectPatterns, SequenceRule rule,
                               TagManager tagManager) {
        return apply(fullSnapshot, targetPaths, replacementTags, sequencePatterns,
                protectPatterns, rule, tagManager, false);
    }

    public static Report apply(List<MediaFile> fullSnapshot, Set<String> targetPaths,
                               List<String> replacementTags, String sequencePatterns,
                               String protectPatterns, SequenceRule rule,
                               TagManager tagManager, boolean purgeGroupTags) {
        Report report = new Report();
        if (fullSnapshot == null || targetPaths == null || tagManager == null) return report;
        List<SearchManager.QueryParser> sequenceQueries = compilePatterns(sequencePatterns);
        List<SearchManager.QueryParser> protectQueries = compilePatterns(protectPatterns);
        Map<String, Set<String>> groups = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> groupTags = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> fileGroups = new HashMap<String, Set<String>>();
        for (MediaFile file : fullSnapshot) {
            if (file == null || file.getPath() == null) continue;
            for (String tag : file.getTags()) {
                if (!matchesAny(tag, sequenceQueries)) continue;
                String key = groupKey(tag);
                Set<String> members = groups.get(key);
                if (members == null) {
                    members = new HashSet<String>();
                    groups.put(key, members);
                }
                members.add(file.getPath());
                Set<String> tagValues = groupTags.get(key);
                if (tagValues == null) { tagValues = new HashSet<String>(); groupTags.put(key, tagValues); }
                tagValues.add(tag);
                Set<String> keys = fileGroups.get(file.getPath());
                if (keys == null) {
                    keys = new HashSet<String>();
                    fileGroups.put(file.getPath(), keys);
                }
                keys.add(key);
            }
        }
        Map<String, Boolean> complete = new HashMap<String, Boolean>();
        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            boolean all = targetPaths.containsAll(entry.getValue());
            complete.put(entry.getKey(), all);
            if (all) report.fullySelectedGroups++;
            else {
                report.partialGroups++;
                report.keptGroups.add(entry.getKey());
            }
        }
        for (MediaFile file : fullSnapshot) {
            if (file == null || !targetPaths.contains(file.getPath())) continue;
            report.files++;
            List<String> previous = new ArrayList<String>(file.getTags());
            report.previousTags.put(file.getPath(), previous);
            Set<String> keep = new HashSet<String>();
            boolean keptFirstLabel = false;
            for (String tag : previous) {
                if (rule == SequenceRule.KEEP_FIRST && !keptFirstLabel
                        && !matchesAny(tag, sequenceQueries)) {
                    keep.add(tag);
                    keptFirstLabel = true;
                }
                if (matchesAny(tag, protectQueries)) keep.add(tag);
                Set<String> keys = fileGroups.get(file.getPath());
                if (keys != null && rule == SequenceRule.WHOLE_GROUP
                        && matchesAny(tag, sequenceQueries)) {
                    for (String key : keys) if (!Boolean.TRUE.equals(complete.get(key))) keep.add(tag);
                } else if (keys != null && rule == SequenceRule.PROTECT) {
                    if (matchesAny(tag, sequenceQueries)) keep.add(tag);
                }
            }
            for (String tag : previous) {
                if (!keep.contains(tag)) {
                    tagManager.removeTag(file, tag);
                    report.removedInstances++;
                }
            }
            if (replacementTags != null) {
                for (String tag : replacementTags) {
                    if (tag != null && !tag.trim().isEmpty() && !file.hasTag(tag)) {
                        tagManager.applyTag(file, tag);
                    }
                }
            }
            for (String tag : keep) if (!file.hasTag(tag)) tagManager.applyTag(file, tag);
        }
        if (purgeGroupTags) {
            for (Map.Entry<String, Set<String>> entry : groupTags.entrySet()) {
                boolean remains = false;
                for (MediaFile file : fullSnapshot) {
                    if (file == null) continue;
                    for (String tag : file.getTags()) {
                        if (matchesAny(tag, sequenceQueries)
                                && entry.getKey().equals(groupKey(tag))) {
                            remains = true;
                            break;
                        }
                    }
                    if (remains) break;
                }
                if (!remains) {
                    for (String tag : entry.getValue()) {
                        for (Tag catalogTag : tagManager.getAllTags()) {
                            if (catalogTag != null && tag.equals(catalogTag.getName())) {
                                report.purgedUsage.put(tag, catalogTag.getUsageCount());
                                report.purgedCreatedAt.put(tag, catalogTag.getCreatedAt());
                                break;
                            }
                        }
                        tagManager.deleteTag(tag);
                        report.purgedTags.add(tag);
                    }
                    report.purgedGroups.add(entry.getKey());
                }
            }
        }
        return report;
    }

    public static Report preview(List<MediaFile> fullSnapshot, Set<String> targetPaths,
                                 List<String> replacementTags, String sequencePatterns,
                                 String protectPatterns, SequenceRule rule) {
        Report report = new Report();
        if (fullSnapshot == null || targetPaths == null) return report;
        List<SearchManager.QueryParser> sequenceQueries = compilePatterns(sequencePatterns);
        List<SearchManager.QueryParser> protectQueries = compilePatterns(protectPatterns);
        Map<String, Set<String>> groups = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> fileGroups = new HashMap<String, Set<String>>();
        for (MediaFile file : fullSnapshot) {
            if (file == null || file.getPath() == null) continue;
            for (String tag : file.getTags()) {
                if (!matchesAny(tag, sequenceQueries)) continue;
                String key = groupKey(tag);
                Set<String> members = groups.get(key);
                if (members == null) { members = new HashSet<String>(); groups.put(key, members); }
                members.add(file.getPath());
                Set<String> keys = fileGroups.get(file.getPath());
                if (keys == null) { keys = new HashSet<String>(); fileGroups.put(file.getPath(), keys); }
                keys.add(key);
            }
        }
        Map<String, Boolean> complete = new HashMap<String, Boolean>();
        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            boolean all = targetPaths.containsAll(entry.getValue());
            complete.put(entry.getKey(), all);
            if (all) report.fullySelectedGroups++;
            else { report.partialGroups++; report.keptGroups.add(entry.getKey()); }
        }
        for (MediaFile file : fullSnapshot) {
            if (file == null || !targetPaths.contains(file.getPath())) continue;
            report.files++;
            List<String> previous = new ArrayList<String>(file.getTags());
            report.previousTags.put(file.getPath(), previous);
            Set<String> keep = new HashSet<String>();
            boolean keptFirstLabel = false;
            for (String tag : previous) {
                if (rule == SequenceRule.KEEP_FIRST && !keptFirstLabel
                        && !matchesAny(tag, sequenceQueries)) {
                    keep.add(tag);
                    keptFirstLabel = true;
                }
                if (matchesAny(tag, protectQueries)) keep.add(tag);
                Set<String> keys = fileGroups.get(file.getPath());
                if (keys != null && rule == SequenceRule.WHOLE_GROUP
                        && matchesAny(tag, sequenceQueries)) {
                    for (String key : keys) if (!Boolean.TRUE.equals(complete.get(key))) keep.add(tag);
                } else if (keys != null && rule == SequenceRule.PROTECT
                        && matchesAny(tag, sequenceQueries)) keep.add(tag);
            }
            for (String tag : previous) if (!keep.contains(tag)) report.removedInstances++;
        }
        return report;
    }

    private static List<SearchManager.QueryParser> compilePatterns(String raw) {
        List<SearchManager.QueryParser> result = new ArrayList<SearchManager.QueryParser>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            try { result.add(SearchManager.QueryParser.parse(value)); }
            catch (SearchManager.InvalidQueryException ignored) {}
        }
        return result;
    }

    private static boolean matchesAny(String value, List<SearchManager.QueryParser> queries) {
        if (value == null) return false;
        for (SearchManager.QueryParser query : queries) if (query.matchesText(value)) return true;
        return false;
    }

    public static String groupKey(String tag) {
        if (tag == null) return "";
        int marker = tag.lastIndexOf("_seq_");
        return marker > 0 ? tag.substring(0, marker) : tag;
    }
}
