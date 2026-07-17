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
        if (rules == null || files == null || files.isEmpty()) return 0;
        int affected = 0;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            for (MediaFile f : files) {
                if (f == null) continue;
                if (rule.matchesFile(f, fileStatus)) {
                    boolean ok = rule.execute(f, context, tagManager, renamer, fileStatus);
                    if (ok) {
                        affected++;
                        log.add(rule.name + " applied to " + (f.getName() != null ? f.getName() : f.getPath()));
                    } else {
                        log.add(rule.name + " failed on " + (f.getName() != null ? f.getName() : f.getPath()));
                    }
                }
            }
        }
        return affected;
    }

    /** Apply rules to a single file (for status‑change triggers). */
    public boolean applyToSingle(MediaFile file) {
        if (rules == null || file == null) return false;
        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            if (rule.matchesFile(file, fileStatus)) {
                boolean ok = rule.execute(file, context, tagManager, renamer, fileStatus);
                if (ok) {
                    log.add(rule.name + " applied to " + (file.getName() != null ? file.getName() : file.getPath()));
                } else {
                    log.add(rule.name + " failed on " + (file.getName() != null ? file.getName() : file.getPath()));
                }
                return ok;
            }
        }
        return false;
    }

    public List<String> getLog() { return log; }
}
