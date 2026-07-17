package com.mediasorter.organizer;

import com.mediasorter.models.MediaFile;
import com.mediasorter.FileStatus;
import java.util.List;
import java.util.regex.Pattern;

public abstract class Condition {
    public abstract boolean matches(MediaFile file, FileStatus fileStatus);

    // ── Static factories (hide subclass types) ────────────────────────────

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

// ── Concrete implementations (package‑private) ──────────────────────────

class TagCondition extends Condition {
    final List<String> tags;
    final boolean matchAny;
    final boolean negate;

    TagCondition(List<String> tags, boolean matchAny, boolean negate) {
        this.tags = tags;
        this.matchAny = matchAny;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        List<String> fileTags = file.getTags();
        boolean result;
        if (matchAny) {
            result = false;
            for (String t : tags) {
                if (fileTags.contains(t)) { result = true; break; }
            }
        } else {
            result = true;
            for (String t : tags) {
                if (!fileTags.contains(t)) { result = false; break; }
            }
        }
        return negate ? !result : result;
    }
}

class NameCondition extends Condition {
    final String pattern;
    final MatchType type;
    final boolean negate;
    final Pattern regex;

    NameCondition(String pattern, MatchType type, boolean negate) {
        this.pattern = pattern;
        this.type = type;
        this.negate = negate;
        this.regex = type == MatchType.REGEX ? Pattern.compile(pattern) : null;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        String name = file.getName();
        boolean result;
        switch (type) {
            case CONTAINS:   result = name.contains(pattern); break;
            case STARTS_WITH:result = name.startsWith(pattern); break;
            case ENDS_WITH:  result = name.endsWith(pattern); break;
            case REGEX:      result = regex.matcher(name).find(); break;
            default:         result = false;
        }
        return negate ? !result : result;
    }
}

class TypeCondition extends Condition {
    final MediaFile.Type type;
    final boolean negate;

    TypeCondition(MediaFile.Type type, boolean negate) {
        this.type = type;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = file.getType() == type;
        return negate ? !result : result;
    }
}

class SizeCondition extends Condition {
    final long threshold;
    final boolean greaterThan;
    final boolean negate;

    SizeCondition(long threshold, boolean greaterThan, boolean negate) {
        this.threshold = threshold;
        this.greaterThan = greaterThan;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = greaterThan ? (file.getSize() > threshold) : (file.getSize() < threshold);
        return negate ? !result : result;
    }
}

class DateCondition extends Condition {
    final int days;
    final boolean olderThan;
    final boolean negate;

    DateCondition(int days, boolean olderThan, boolean negate) {
        this.days = days;
        this.olderThan = olderThan;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        long fileTime = file.getDateAdded();
        long now = System.currentTimeMillis();
        long diffDays = (now - fileTime) / (1000L * 60 * 60 * 24);
        boolean result = olderThan ? (diffDays > days) : (diffDays < days);
        return negate ? !result : result;
    }
}

class StatusCondition extends Condition {
    final FileStatus.Status status;
    final boolean negate;

    StatusCondition(FileStatus.Status status, boolean negate) {
        this.status = status;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        if (fileStatus == null) return negate;
        String path = file.getPath();
        boolean result;
        switch (status) {
            case SKIPPED: result = fileStatus.isSkipped(path); break;
            case FLAGGED: result = fileStatus.isFlagged(path); break;
            case DONE:    result = fileStatus.isDone(path);    break;
            default:      result = false;
        }
        return negate ? !result : result;
    }
}

class FolderCondition extends Condition {
    final String folderPath;
    final boolean negate;

    FolderCondition(String folderPath, boolean negate) {
        this.folderPath = folderPath.endsWith("/") ? folderPath : folderPath + "/";
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = file.getPath().startsWith(folderPath);
        return negate ? !result : result;
    }
}
