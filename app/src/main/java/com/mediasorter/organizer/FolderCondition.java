package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;

public class FolderCondition extends Condition {
    public String folderPath;
    public boolean negate;

    public FolderCondition(String folderPath, boolean negate) {
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
