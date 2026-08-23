package com.mediasorter;

import android.content.Context;
import android.content.SharedPreferences;
import com.mediasorter.features.RandomGenerator;
import com.mediasorter.models.MediaFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sort sequence storage and execution for both list and gallery presentations.
 * All callers may use the same instance; sort() itself is deliberately free of
 * UI work so MainActivity can run it on its refresh executor.
 */
public class SortManager {

    public enum SortBy {
        NAME_ASC, NAME_DESC,
        SIZE_ASC, SIZE_DESC,
        DATE_ASC, DATE_DESC,
        TYPE, SHUFFLE,
        MANUAL_ORDER, TAG_COUNT_DESC, TAG_COUNT_ASC,
        FLAGGED_FIRST, UNTAGGED_FIRST
    }

    public static final String KEY_SORT_SEQUENCE = "sort_sequence";
    public static final String KEY_TAG_RULES = "sort_tag_rules";

    public static final String NAME = "NAME";
    public static final String DATE = "DATE";
    public static final String SIZE = "SIZE";
    public static final String TYPE = "TYPE";
    public static final String TAG_COUNT = "TAG_COUNT";
    public static final String FIRST_TAG = "FIRST_TAG";
    public static final String TAG_RULE_MATCH = "TAG_RULE_MATCH";
    public static final String FLAGGED = "FLAGGED";
    public static final String SKIPPED = "SKIPPED";
    public static final String DONE = "DONE";
    public static final String MANUAL_ORDER = "MANUAL_ORDER";
    public static final String RANDOM = "RANDOM";
    public static final String PATH_DEPTH = "PATH_DEPTH";
    public static final String COLOR_FAMILY = "COLOR_FAMILY";
    public static final String SEQUENCE_GROUP = "SEQUENCE_GROUP";
    public static final String RANDOM_WITHIN_GROUP = "RANDOM_WITHIN_GROUP";
    public static final String DUPLICATE_STATUS = "DUPLICATE_STATUS";
    public static final String METADATA_PRESENCE = "METADATA_PRESENCE";
    public static final String WORD_COUNT = "WORD_COUNT";

    public static class SortCriterion {
        public String id;
        public String direction;

        public SortCriterion(String id, String direction) {
            this.id = id;
            this.direction = direction;
        }

        public SortCriterion copy() {
            return new SortCriterion(id, direction);
        }
    }

    private volatile SortBy legacyCurrent = SortBy.NAME_ASC;
    private FileStatus fileStatus;
    private SharedPreferences prefs;

    public SortManager() {}

    public SortManager(Context context) {
        setPreferences(context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE));
    }

    public void setPreferences(SharedPreferences preferences) {
        this.prefs = preferences;
    }

    public void setFileStatus(FileStatus status) { this.fileStatus = status; }

    public List<SortCriterion> getSortSequence() {
        List<SortCriterion> result = new ArrayList<>();
        if (prefs != null) {
            String raw = prefs.getString(KEY_SORT_SEQUENCE, "");
            if (raw != null && !raw.trim().isEmpty()) {
                try {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject entry = array.optJSONObject(i);
                        if (entry == null) continue;
                        String id = entry.optString("id", "");
                        if (!isCriterionId(id)) continue;
                        String direction = normalizeDirection(entry.optString("direction", defaultDirection(id)));
                        result.add(new SortCriterion(id, direction));
                    }
                } catch (Exception ignored) {}
            }
        }
        if (result.isEmpty()) {
            result.add(new SortCriterion(NAME, "ASC"));
        }
        return result;
    }

    public void saveSortSequence(List<SortCriterion> sequence) {
        JSONArray array = new JSONArray();
        if (sequence != null) {
            boolean randomAdded = false;
            for (SortCriterion criterion : sequence) {
                if (criterion == null || !isCriterionId(criterion.id)) continue;
                if (RANDOM.equals(criterion.id) && randomAdded) continue;
                if (RANDOM.equals(criterion.id)) randomAdded = true;
                JSONObject entry = new JSONObject();
                try {
                    entry.put("id", criterion.id);
                    entry.put("direction", normalizeDirection(criterion.direction));
                    array.put(entry);
                } catch (Exception ignored) {}
            }
        }
        if (prefs != null) prefs.edit().putString(KEY_SORT_SEQUENCE, array.toString()).apply();
    }

    public void reloadSequence() {
        // Sequence values are read on every sort. This method exists so the
        // Activity can refresh its button label after returning from Settings.
    }

    public List<String> getTagRules() {
        List<String> result = new ArrayList<>();
        if (prefs == null) return result;
        String raw = prefs.getString(KEY_TAG_RULES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String tag = array.optString(i, "").trim();
                if (!tag.isEmpty() && !result.contains(tag)) result.add(tag);
            }
        } catch (Exception ignored) {}
        return result;
    }

    public void saveTagRules(List<String> rules) {
        JSONArray array = new JSONArray();
        if (rules != null) {
            for (String tag : rules) {
                if (tag != null && !tag.trim().isEmpty() && !containsJsonString(array, tag.trim())) {
                    array.put(tag.trim());
                }
            }
        }
        if (prefs != null) prefs.edit().putString(KEY_TAG_RULES, array.toString()).apply();
    }

    private boolean containsJsonString(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    public boolean isManualOrderActive() {
        List<SortCriterion> sequence = getSortSequence();
        for (SortCriterion criterion : sequence) {
            if (MANUAL_ORDER.equals(criterion.id)) return true;
        }
        return false;
    }

    public void setSingleCriterion(String id, String direction) {
        List<SortCriterion> sequence = new ArrayList<>();
        sequence.add(new SortCriterion(id, direction));
        saveSortSequence(sequence);
    }

    /** Legacy API retained for callers outside MainActivity. */
    public void setSortBy(SortBy sort) {
        if (sort == null) sort = SortBy.NAME_ASC;
        legacyCurrent = sort;
        String id = NAME;
        String direction = "ASC";
        switch (sort) {
            case NAME_DESC:      id = NAME; direction = "DESC"; break;
            case SIZE_ASC:       id = SIZE; direction = "ASC"; break;
            case SIZE_DESC:      id = SIZE; direction = "DESC"; break;
            case DATE_ASC:       id = DATE; direction = "ASC"; break;
            case DATE_DESC:      id = DATE; direction = "DESC"; break;
            case TYPE:           id = TYPE; direction = "ASC"; break;
            case SHUFFLE:        id = RANDOM; direction = "ASC"; break;
            case MANUAL_ORDER:   id = MANUAL_ORDER; direction = "ASC"; break;
            case TAG_COUNT_DESC: id = TAG_COUNT; direction = "DESC"; break;
            case TAG_COUNT_ASC:  id = TAG_COUNT; direction = "ASC"; break;
            case FLAGGED_FIRST:  id = FLAGGED; direction = "DESC"; break;
            case UNTAGGED_FIRST: id = FLAGGED; direction = "ASC"; break;
            case NAME_ASC:
            default:             id = NAME; direction = "ASC"; break;
        }
        setSingleCriterion(id, direction);
    }

    public SortBy getCurrent() {
        List<SortCriterion> sequence = getSortSequence();
        if (sequence.isEmpty()) return SortBy.NAME_ASC;
        SortCriterion first = sequence.get(0);
        boolean desc = "DESC".equalsIgnoreCase(first.direction);
        if (NAME.equals(first.id)) return desc ? SortBy.NAME_DESC : SortBy.NAME_ASC;
        if (SIZE.equals(first.id)) return desc ? SortBy.SIZE_DESC : SortBy.SIZE_ASC;
        if (DATE.equals(first.id)) return desc ? SortBy.DATE_DESC : SortBy.DATE_ASC;
        if (TYPE.equals(first.id)) return desc ? SortBy.TYPE : SortBy.TYPE;
        if (RANDOM.equals(first.id)) return SortBy.SHUFFLE;
        if (MANUAL_ORDER.equals(first.id)) return SortBy.MANUAL_ORDER;
        if (TAG_COUNT.equals(first.id)) return desc ? SortBy.TAG_COUNT_DESC : SortBy.TAG_COUNT_ASC;
        if (FLAGGED.equals(first.id)) return desc ? SortBy.FLAGGED_FIRST : SortBy.UNTAGGED_FIRST;
        return legacyCurrent;
    }

    public String getLabel() {
        List<SortCriterion> sequence = getSortSequence();
        if (sequence.size() == 1) {
            SortCriterion criterion = sequence.get(0);
            return criterionLabel(criterion.id) + " ("
                    + directionLabel(criterion.id, criterion.direction) + ")";
        }
        return "Sort (" + sequence.size() + ")";
    }

    public static String criterionLabel(String id) {
        if (DATE.equals(id)) return "Date";
        if (SIZE.equals(id)) return "Size";
        if (TYPE.equals(id)) return "File type";
        if (TAG_COUNT.equals(id)) return "Tag count";
        if (FIRST_TAG.equals(id)) return "First tag value";
        if (TAG_RULE_MATCH.equals(id)) return "Tag rule match";
        if (FLAGGED.equals(id)) return "Flagged status";
        if (SKIPPED.equals(id)) return "Skip status";
        if (DONE.equals(id)) return "Done status";
        if (MANUAL_ORDER.equals(id)) return "Manual order";
        if (RANDOM.equals(id)) return "Random shuffle";
        if (PATH_DEPTH.equals(id)) return "Path depth";
        if (COLOR_FAMILY.equals(id)) return "Color family";
        if (SEQUENCE_GROUP.equals(id)) return "Sequence group";
        if (RANDOM_WITHIN_GROUP.equals(id)) return "Random within group";
        if (DUPLICATE_STATUS.equals(id)) return "Duplicate status";
        if (METADATA_PRESENCE.equals(id)) return "Metadata presence";
        if (WORD_COUNT.equals(id)) return "Filename word count";
        return "Name";
    }

    public static String directionLabel(String id, String direction) {
        boolean desc = "DESC".equalsIgnoreCase(direction);
        if (RANDOM.equals(id)) return "Always last";
        if (DATE.equals(id)) return desc ? "Newest" : "Oldest";
        if (SIZE.equals(id)) return desc ? "Largest" : "Smallest";
        if (TAG_COUNT.equals(id)) return desc ? "Most" : "Least";
        if (TAG_RULE_MATCH.equals(id)) return desc ? "Reverse rule" : "Rule order";
        if (FLAGGED.equals(id)) return desc ? "Flagged first" : "Unflagged first";
        if (SKIPPED.equals(id)) return desc ? "Skipped first" : "Unskipped first";
        if (DONE.equals(id)) return desc ? "Done first" : "Undone first";
        if (MANUAL_ORDER.equals(id)) return desc ? "Descending" : "Ascending";
        if (PATH_DEPTH.equals(id)) return desc ? "Deeper first" : "Shallower first";
        if (RANDOM_WITHIN_GROUP.equals(id)) return "Within groups";
        if (DUPLICATE_STATUS.equals(id)) return desc ? "Duplicates first" : "Unique first";
        if (METADATA_PRESENCE.equals(id)) return desc ? "Metadata first" : "No metadata first";
        if (WORD_COUNT.equals(id)) return desc ? "More words" : "Fewer words";
        return desc ? "Z-A" : "A-Z";
    }

    public static String toggleDirection(String id, String direction) {
        if (RANDOM.equals(id)) return "ASC";
        return "DESC".equalsIgnoreCase(direction) ? "ASC" : "DESC";
    }

    public static String defaultDirection(String id) {
        if (DATE.equals(id) || SIZE.equals(id) || TAG_COUNT.equals(id)
                || FLAGGED.equals(id) || SKIPPED.equals(id) || DONE.equals(id)
                || PATH_DEPTH.equals(id) || DUPLICATE_STATUS.equals(id)
                || METADATA_PRESENCE.equals(id) || WORD_COUNT.equals(id)) {
            return "DESC";
        }
        return "ASC";
    }

    public static boolean isCriterionId(String id) {
        return NAME.equals(id) || DATE.equals(id) || SIZE.equals(id)
                || TYPE.equals(id) || TAG_COUNT.equals(id) || FIRST_TAG.equals(id)
                || TAG_RULE_MATCH.equals(id) || FLAGGED.equals(id) || SKIPPED.equals(id)
                || DONE.equals(id) || MANUAL_ORDER.equals(id) || RANDOM.equals(id)
                || PATH_DEPTH.equals(id) || COLOR_FAMILY.equals(id)
                || SEQUENCE_GROUP.equals(id) || RANDOM_WITHIN_GROUP.equals(id)
                || DUPLICATE_STATUS.equals(id) || METADATA_PRESENCE.equals(id)
                || WORD_COUNT.equals(id);
    }

    private String normalizeDirection(String direction) {
        return "DESC".equalsIgnoreCase(direction) ? "DESC" : "ASC";
    }

    public void sort(List<MediaFile> files) {
        if (files == null || files.size() < 2) return;
        List<SortCriterion> sequence = getSortSequence();
        final List<String> tagRules = getTagRules();
        Comparator<MediaFile> chain = null;
        boolean random = false;
        boolean randomWithinGroup = false;

        for (SortCriterion criterion : sequence) {
            if (criterion == null || !isCriterionId(criterion.id)) continue;
            if (RANDOM.equals(criterion.id)) {
                random = true;
                continue;
            }
            if (RANDOM_WITHIN_GROUP.equals(criterion.id)) {
                randomWithinGroup = true;
                continue;
            }
            final String id = criterion.id;
            final boolean descending = "DESC".equalsIgnoreCase(criterion.direction);
            final Comparator<MediaFile> next = new Comparator<MediaFile>() {
                @Override public int compare(MediaFile left, MediaFile right) {
                    int result = compareCriterion(left, right, id, tagRules);
                    return descending ? -result : result;
                }
            };
            chain = append(chain, next);
        }

        if (chain == null) {
            chain = new Comparator<MediaFile>() {
                @Override public int compare(MediaFile left, MediaFile right) {
                    return compareNames(left, right);
                }
            };
        } else {
            final Comparator<MediaFile> existing = chain;
            chain = append(existing, new Comparator<MediaFile>() {
                @Override public int compare(MediaFile left, MediaFile right) {
                    return compareNames(left, right);
                }
            });
        }

        Collections.sort(files, chain);
        if (randomWithinGroup) shuffleWithinGroups(files);
        if (random) RandomGenerator.shuffle(files);
    }

    /** Shuffle each contiguous group without moving files between groups. */
    private void shuffleWithinGroups(List<MediaFile> files) {
        int start = 0;
        while (start < files.size()) {
            String key = groupingKey(files.get(start));
            int end = start + 1;
            while (end < files.size() && key.equals(groupingKey(files.get(end)))) end++;
            if (end - start > 1) {
                List<MediaFile> chunk = new ArrayList<>(files.subList(start, end));
                RandomGenerator.shuffle(chunk);
                for (int i = 0; i < chunk.size(); i++) files.set(start + i, chunk.get(i));
            }
            start = end;
        }
    }

    private String groupingKey(MediaFile file) {
        String sequence = sequenceGroup(file);
        if (!sequence.isEmpty()) return sequence;
        String parent = new java.io.File(file.getPath()).getParent();
        return parent == null ? "" : parent;
    }

    private Comparator<MediaFile> append(final Comparator<MediaFile> first,
                                         final Comparator<MediaFile> second) {
        if (first == null) return second;
        return new Comparator<MediaFile>() {
            @Override public int compare(MediaFile left, MediaFile right) {
                int result = first.compare(left, right);
                return result != 0 ? result : second.compare(left, right);
            }
        };
    }

    private int compareCriterion(MediaFile left, MediaFile right, String id,
                                 List<String> tagRules) {
        if (DATE.equals(id)) return Long.compare(left.getDateAdded(), right.getDateAdded());
        if (SIZE.equals(id)) return Long.compare(left.getSize(), right.getSize());
        if (TYPE.equals(id)) return left.getType().name().compareToIgnoreCase(right.getType().name());
        if (TAG_COUNT.equals(id)) return Integer.compare(left.getTags().size(), right.getTags().size());
        if (FIRST_TAG.equals(id)) return firstTag(left).compareToIgnoreCase(firstTag(right));
        if (TAG_RULE_MATCH.equals(id)) {
            return Integer.compare(tagRuleRank(left, tagRules), tagRuleRank(right, tagRules));
        }
        if (FLAGGED.equals(id)) return Boolean.compare(fileStatus != null && fileStatus.isFlagged(left.getPath()),
                fileStatus != null && fileStatus.isFlagged(right.getPath()));
        if (SKIPPED.equals(id)) return Boolean.compare(fileStatus != null && fileStatus.isSkipped(left.getPath()),
                fileStatus != null && fileStatus.isSkipped(right.getPath()));
        if (DONE.equals(id)) return Boolean.compare(fileStatus != null && fileStatus.isDone(left.getPath()),
                fileStatus != null && fileStatus.isDone(right.getPath()));
        if (PATH_DEPTH.equals(id)) return Integer.compare(pathDepth(left), pathDepth(right));
        if (COLOR_FAMILY.equals(id)) return left.getColorFamily().compareToIgnoreCase(right.getColorFamily());
        if (SEQUENCE_GROUP.equals(id)) return sequenceGroup(left).compareToIgnoreCase(sequenceGroup(right));
        if (DUPLICATE_STATUS.equals(id)) return Boolean.compare(left.isDuplicate(), right.isDuplicate());
        if (METADATA_PRESENCE.equals(id)) return Boolean.compare(left.hasMetadata(), right.hasMetadata());
        if (WORD_COUNT.equals(id)) return Integer.compare(wordCount(left.getName()), wordCount(right.getName()));
        if (MANUAL_ORDER.equals(id)) return compareManualOrder(left, right);
        return compareNames(left, right);
    }

    private int compareNames(MediaFile left, MediaFile right) {
        return left.getName().compareToIgnoreCase(right.getName());
    }

    private String firstTag(MediaFile file) {
        return file.getTags().isEmpty() ? "" : file.getTags().get(0);
    }

    private int tagRuleRank(MediaFile file, List<String> rules) {
        for (int i = 0; i < rules.size(); i++) {
            if (file.hasTag(rules.get(i))) return i;
        }
        return rules.size();
    }

    private int pathDepth(MediaFile file) {
        if (file == null || file.getPath() == null) return 0;
        String path = file.getPath().replace('\\', '/');
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
    }

    private String sequenceGroup(MediaFile file) {
        if (file == null || file.getTags() == null) return "";
        for (String tag : file.getTags()) {
            if (tag == null) continue;
            String plain = tag.trim();
            if (plain.startsWith("link_") || plain.startsWith("link-")) {
                String group = plain.substring(5);
                int marker = group.indexOf("_seq_");
                return marker > 0 ? group.substring(0, marker) : group;
            }
        }
        return "";
    }

    private int wordCount(String name) {
        if (name == null || name.trim().isEmpty()) return 0;
        String stem = name;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String[] words = stem.split("[\\s_\\-.]+");
        int count = 0;
        for (String word : words) if (!word.isEmpty()) count++;
        return count;
    }

    private int compareManualOrder(MediaFile left, MediaFile right) {
        int leftOrder = left.getManualOrder();
        int rightOrder = right.getManualOrder();
        if (leftOrder < 0 && rightOrder < 0) return 0;
        if (leftOrder < 0) return 1;
        if (rightOrder < 0) return -1;
        return Integer.compare(leftOrder, rightOrder);
    }
}
