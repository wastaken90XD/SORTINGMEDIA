package com.mediasorter.organizer;

import com.mediasorter.models.MediaFile;
import com.mediasorter.FileStatus;
import java.util.List;

public abstract class Condition {
    public abstract boolean matches(MediaFile file, FileStatus fileStatus);

    public abstract String describe();

    // ── Static factories ────────────────────────────────────────────────

    public static Condition tagCondition(List<String> tags, boolean matchAny, boolean negate) {
        return new TagCondition(tags, matchAny, negate);
    }

    public static Condition nameCondition(String pattern, MatchType type, boolean negate) {
        return new NameCondition(pattern, type, negate);
    }

    public static Condition typeCondition(MediaFile.Type type, boolean negate) {
        return new TypeCondition(type, negate);
    }

    public static Condition sizeCondition(long threshold, boolean greaterThan, boolean negate) {
        return new SizeCondition(threshold, greaterThan, negate);
    }

    public static Condition dateCondition(int days, boolean olderThan, boolean negate) {
        return new DateCondition(days, olderThan, negate);
    }

    public static Condition statusCondition(FileStatus.Status status, boolean negate) {
        return new StatusCondition(status, negate);
    }

    public static Condition folderCondition(String folderPath, boolean negate) {
        return new FolderCondition(folderPath, negate);
    }

    public enum MatchType { CONTAINS, STARTS_WITH, ENDS_WITH, REGEX }
}
