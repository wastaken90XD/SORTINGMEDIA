package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.List;

public class WindowManager {

    private static final int DEFAULT_WINDOW = 20;

    private List<MediaFile> fullIndex   = new ArrayList<>();
    private int             windowSize;
    private int             windowStart = 0;

    public WindowManager(int windowSize) {
        this.windowSize = windowSize;
    }

    public void setWindowSize(int size) {
        this.windowSize = Math.max(10, size);
    }

    public int getWindowSize() { return windowSize; }

    public void setFullIndex(List<MediaFile> index) {
        this.fullIndex   = index;
        this.windowStart = 0;
    }

    public int getTotalSize() { return fullIndex.size(); }

    // ── Get window ────────────────────────────────────────────────────────────

    public List<MediaFile> getWindow() {
        if (fullIndex.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, windowStart);
        int end   = Math.min(fullIndex.size(), start + windowSize);
        return new ArrayList<>(fullIndex.subList(start, end));
    }

    // ── Center window on index ────────────────────────────────────────────────

    public void centerOn(int absoluteIndex) {
        int half  = windowSize / 2;
        windowStart = Math.max(0,
            Math.min(absoluteIndex - half,
                fullIndex.size() - windowSize));
    }

    // ── Check if near edge ────────────────────────────────────────────────────

    public boolean nearStart(int absoluteIndex) {
        return absoluteIndex <= windowStart + (windowSize / 4);
    }

    public boolean nearEnd(int absoluteIndex) {
        return absoluteIndex >= windowStart + (windowSize * 3 / 4);
    }

    // ── Shift window ─────────────────────────────────────────────────────────

    public void shiftForward() {
        windowStart = Math.min(
            windowStart + (windowSize / 2),
            Math.max(0, fullIndex.size() - windowSize));
    }

    public void shiftBack() {
        windowStart = Math.max(0, windowStart - (windowSize / 2));
    }

    // ── Convert between window and absolute index ─────────────────────────────

    public int toAbsolute(int windowIndex) {
        return windowStart + windowIndex;
    }

    public int toWindow(int absoluteIndex) {
        return absoluteIndex - windowStart;
    }

    public boolean isInWindow(int absoluteIndex) {
        return absoluteIndex >= windowStart
            && absoluteIndex < windowStart + windowSize;
    }

    // ── Get file by absolute index ────────────────────────────────────────────

    public MediaFile getFile(int absoluteIndex) {
        if (absoluteIndex < 0 || absoluteIndex >= fullIndex.size()) return null;
        return fullIndex.get(absoluteIndex);
    }

    public int findAbsoluteIndex(MediaFile file) {
        for (int i = 0; i < fullIndex.size(); i++) {
            if (fullIndex.get(i).getPath().equals(file.getPath())) return i;
        }
        return -1;
    }
}
