package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.util.regex.Pattern;

public class NameCondition extends Condition {
    public String pattern;
    public Condition.MatchType type;
    public boolean negate;
    public Pattern regex;

    public NameCondition(String pattern, Condition.MatchType type, boolean negate) {
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
