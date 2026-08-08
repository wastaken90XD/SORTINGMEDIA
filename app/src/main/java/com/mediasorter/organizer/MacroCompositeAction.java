package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.models.MediaFile;
import java.util.List;

public class MacroCompositeAction extends Action {
    private final List<Action> actions;
    private int failedStepIndex = -1;

    public MacroCompositeAction(List<Action> actions) {
        this.actions = actions;
    }

    public List<Action> getActions() {
        return actions;
    }

    public int getFailedStepIndex() {
        return failedStepIndex;
    }

    @Override
    public String describe() {
        return "Composite macro of " + actions.size() + " steps";
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        failedStepIndex = -1;
        for (int i = 0; i < actions.size(); i++) {
            Action act = actions.get(i);
            if (act != null) {
                boolean ok = act.execute(file, context, tagManager, renamer, fileStatus);
                if (!ok) {
                    failedStepIndex = i;
                    return false;
                }
            }
        }
        return true;
    }
}
