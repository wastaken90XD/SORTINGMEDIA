package com.mediasorter.organizer;

import com.mediasorter.FileStatus;
import com.mediasorter.models.MediaFile;

public class DateCondition extends Condition {
    public int days;
    public boolean olderThan;
    public boolean negate;

    public DateCondition(int days, boolean olderThan, boolean negate) {
        this.days = days;
        this.olderThan = olderThan;
        this.negate = negate;
    }

    @Override
    public boolean matches(MediaFile file, FileStatus fileStatus) {
        long fileTime = file.getDateAdded();
        long now = System.currentTimeMillis();
        long diffDays = (now - fileTime) / (1000L * 60 * 60 * 24);
        boolean result = olderThan ? (diffDays > days) : (diffDays < days);
        return negate ? !result : result;
    }

    @Override
    public String describe() {
        String op = olderThan ? "older" : "newer";
        return (negate ? "NOT " : "") + op + " than " + days + " days";
    }
}
