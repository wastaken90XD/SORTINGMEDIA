package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.SearchManager;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class TagCondition extends Condition {
    public List<String> tags;
    public boolean matchAny;
    public boolean negate;
    private final List<SearchManager.QueryParser> queryParsers =
            new ArrayList<SearchManager.QueryParser>();

    public TagCondition(List<String> tags, boolean matchAny, boolean negate) {
        this.tags = tags != null ? new ArrayList<String>(tags) : new ArrayList<String>();
        this.matchAny = matchAny;
        this.negate = negate;
        for (String tagQuery : this.tags) {
            if (tagQuery == null || tagQuery.trim().isEmpty()) {
                queryParsers.add(null);
                continue;
            }
            try {
                queryParsers.add(SearchManager.QueryParser.parse(tagQuery));
            } catch (SearchManager.InvalidQueryException invalid) {
                // An invalid saved rule simply cannot match; parsing is still
                // done once here, never once per file in AutoOrganizer.
                queryParsers.add(null);
            }
        }
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        List<String> fileTags = file == null ? new ArrayList<String>() : file.getTags();
        boolean result;
        if (matchAny) {
            result = false;
            for (SearchManager.QueryParser parser : queryParsers) {
                if (parser != null && parser.matchesTags(fileTags)) { result = true; break; }
            }
        } else {
            result = true;
            for (SearchManager.QueryParser parser : queryParsers) {
                if (parser == null || !parser.matchesTags(fileTags)) { result = false; break; }
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
