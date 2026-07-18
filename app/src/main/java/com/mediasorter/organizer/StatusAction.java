package com.mediasorter.organizer;

import android.content.Context;
import com.mediasorter.BatchRenameManager;
import com.mediasorter.FileStatus;
import com.mediasorter.TagManager;
import com.mediasorter.models.MediaFile;

public class StatusAction extends Action {
    public FileStatus.Status status;
    public boolean clear;

    public StatusAction(FileStatus.Status status, boolean clear) {
        this.status = status;
        this.clear = clear;
    }

    @Override
    public String describe() {
        return clear ? "Clear status" : "Set status: " + status.name();
    }

    @Override
    public boolean execute(MediaFile file, Context context,
            TagManager tagManager, BatchRenameManager renamer, FileStatus fileStatus) {
        if (fileStatus == null) return false;
        String path = file.getPath();
        if (clear) {
            fileStatus.clearStatus(path);
        } else {
            switch (status) {
                case SKIPPED: fileStatus.setSkipped(path); break;
                case FLAGGED: fileStatus.setFlagged(path); break;
                case DONE:    fileStatus.setDone(path);    break;
            }
        }
        return true;
    }
}
