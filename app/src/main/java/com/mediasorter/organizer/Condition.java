package com.mediasorter.organizer;

import com.mediasorter.models.MediaFile;
import com.mediasorter.FileStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

// ── Concrete implementations (public for UI access) ────────────────────

class TagCondition extends Condition {
    public List<String> tags;
    public boolean matchAny;
    public boolean negate;

    TagCondition(List<String> tags, boolean matchAny, boolean negate) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.matchAny = matchAny;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        List<String> fileTags = file.getTags();
        if (fileTags == null) fileTags = new ArrayList<>();
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

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (negate) sb.append("NOT ");
        sb.append(matchAny ? "any tag in " : "all tags in ");
        sb.append(tags);
        return sb.toString();
    }
}

class NameCondition extends Condition {
    public String pattern;
    public Condition.MatchType type;
    public boolean negate;
    public Pattern regex;

    NameCondition(String pattern, Condition.MatchType type, boolean negate) {
        this.pattern = pattern != null ? pattern : "";
        this.type = type != null ? type : Condition.MatchType.CONTAINS;
        this.negate = negate;
        this.regex = (this.type == Condition.MatchType.REGEX && !this.pattern.isEmpty())
                ? Pattern.compile(this.pattern) : null;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        String name = file.getName();
        if (name == null) name = "";
        boolean result;
        switch (type) {
            case CONTAINS:   result = name.contains(pattern); break;
            case STARTS_WITH:result = name.startsWith(pattern); break;
            case ENDS_WITH:  result = name.endsWith(pattern); break;
            case REGEX:      result = regex != null && regex.matcher(name).find(); break;
            default:         result = false;
        }
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (negate) sb.append("NOT ");
        sb.append(type.name()).append(" ").append(pattern);
        return sb.toString();
    }
}

class TypeCondition extends Condition {
    public MediaFile.Type type;
    public boolean negate;

    TypeCondition(MediaFile.Type type, boolean negate) {
        this.type = type != null ? type : MediaFile.Type.UNSUPPORTED;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = file.getType() == type;
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        return (negate ? "NOT " : "") + "type " + type.name();
    }
}

class SizeCondition extends Condition {
    public long threshold;
    public boolean greaterThan;
    public boolean negate;

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

    @Override
    public String describe() {
        String op = greaterThan ? ">" : "<";
        long mb = threshold / (1024L * 1024L);
        return (negate ? "NOT " : "") + "size " + op + " " + mb + "MB";
    }
}

class DateCondition extends Condition {
    public int days;
    public boolean olderThan;
    public boolean negate;

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

    @Override
    public String describe() {
        String op = olderThan ? "older" : "newer";
        return (negate ? "NOT " : "") + op + " than " + days + " days";
    }
}

class StatusCondition extends Condition {
    public FileStatus.Status status;
    public boolean negate;

    StatusCondition(FileStatus.Status status, boolean negate) {
        this.status = status != null ? status : FileStatus.Status.NONE;
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

    @Override
    public String describe() {
        return (negate ? "NOT " : "") + "status " + status.name();
    }
}

class FolderCondition extends Condition {
    public String folderPath;
    public boolean negate;

    FolderCondition(String folderPath, boolean negate) {
        String safe = folderPath != null ? folderPath : "";
        this.folderPath = safe.endsWith("/") ? safe : safe + "/";
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        String path = file.getPath();
        if (path == null) path = "";
        boolean result = path.startsWith(folderPath);
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        return (negate ? "NOT " : "") + "in " + folderPath.replaceFirst("/$", "");
    }
}
