package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.MainActivity;
import com.mediasorter.ReplaceTagsManager;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rule/macro action using the same sequence-aware Replace Tags planner. */
public class ReplaceTagsAction extends Action {
    public List<String> replacementTags;
    public String sequenceRule;

    public ReplaceTagsAction(List<String> tags, String rule) {
        replacementTags = tags == null ? new ArrayList<String>() : new ArrayList<String>(tags);
        sequenceRule = rule == null ? "whole_group" : rule;
    }

    @Override public String describe() { return "Replace tags with " + replacementTags; }

    @Override public boolean execute(MediaFile file, Context context, TagManager tagManager,
                                     BatchRenameManager renamer, FileStatus fileStatus) {
        List<MediaFile> full = MainActivity.getLatestFullList();
        if (full == null || full.isEmpty()) full = new ArrayList<MediaFile>();
        if (full.isEmpty()) full.add(file);
        java.util.Set<String> targets = new java.util.HashSet<String>();
        targets.add(file.getPath());
        ReplaceTagsManager.SequenceRule rule;
        try { rule = ReplaceTagsManager.SequenceRule.valueOf(sequenceRule.toUpperCase(java.util.Locale.US)); }
        catch (Exception ignored) { rule = ReplaceTagsManager.SequenceRule.WHOLE_GROUP; }
        ReplaceTagsManager.apply(full, targets, replacementTags,
                "link_*,*_seq_*", "", rule, tagManager);
        return true;
    }
}
