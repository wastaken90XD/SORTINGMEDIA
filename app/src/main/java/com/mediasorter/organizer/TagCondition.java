package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class TagCondition extends Condition {
    public List<String> tags;
    public boolean matchAny;
    public boolean negate;

    public TagCondition(List<String> tags, boolean matchAny, boolean negate) {
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
