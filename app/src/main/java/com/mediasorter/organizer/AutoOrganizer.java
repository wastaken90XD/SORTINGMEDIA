package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class AutoOrganizer {
    private final Context context;
    private final TagManager tagManager;
    private final BatchRenameManager renamer;
    private final FileStatus fileStatus;
    private List<Rule> rules;
    private final List<String> log = new ArrayList<>();

    public AutoOrganizer(Context ctx, TagManager tm, BatchRenameManager rm, FileStatus fs) {
        this.context = ctx;
        this.tagManager = tm;
        this.renamer = rm;
        this.fileStatus = fs;
        rules = RuleSerializer.loadRules(ctx);
    }

    public void setRules(List<Rule> r) {
        rules = r;
        RuleSerializer.saveRules(context, rules);
    }

    public List<Rule> getRules() { return rules; }

    /** Apply all enabled rules to the given list, returning affected file count. */
    public int applyTo(List<MediaFile> files) {
        log.clear();
        int affected = 0;
        for (Rule rule : rules) {
            if (!rule.enabled) continue;
            for (MediaFile f : files) {
                if (rule.matchesFile(f, fileStatus)) {
                    if (rule.execute(f, context, tagManager, renamer, fileStatus)) {
                        affected++;
                    }
                }
            }
        }
        return affected;
    }

    /** Apply rules to a single file (for status‑change triggers). */
    public boolean applyToSingle(MediaFile file) {
        for (Rule rule : rules) {
            if (!rule.enabled) continue;
            if (rule.matchesFile(file, fileStatus)) {
                return rule.execute(file, context, tagManager, renamer, fileStatus);
            }
        }
        return false;
    }

    public List<String> getLog() { return log; }
}
