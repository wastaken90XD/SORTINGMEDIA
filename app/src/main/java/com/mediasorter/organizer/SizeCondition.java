package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;

public class SizeCondition extends Condition {
    public long threshold;
    public boolean greaterThan;
    public boolean negate;

    public SizeCondition(long threshold, boolean greaterThan, boolean negate) {
        this.threshold = threshold;
        this.greaterThan = greaterThan;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        boolean result = greaterThan ? (file.getSize() > threshold) : (file.getSize() < threshold);
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        String op = greaterThan ? ">" : "<";
        long mb = threshold / (1024L * 1024L);
        return (negate ? "NOT " : "") + "size " + op + " " + mb + "MB";
    }
}
