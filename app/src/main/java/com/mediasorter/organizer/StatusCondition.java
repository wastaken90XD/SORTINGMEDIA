package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;

public class StatusCondition extends Condition {
    public FileStatus.Status status;
    public boolean negate;

    public StatusCondition(FileStatus.Status status, boolean negate) {
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
