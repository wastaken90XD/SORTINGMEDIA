package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;

public class TypeCondition extends Condition {
    public MediaFile.Type type;
    public boolean negate;

    public TypeCondition(MediaFile.Type type, boolean negate) {
        this.type = type != null ? type : MediaFile.Type.UNSUPPORTED;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = file.getType() == type;
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        return (negate ? "NOT " : "") + "type " + type.name();
    }
}
