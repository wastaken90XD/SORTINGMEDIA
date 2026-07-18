package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WindowManager {

    private static final int DEFAULT_WINDOW = 20;
    private static final int MIN_WINDOW     = 10;

    private final Object lock = new Object();

    private List<MediaFile> fullIndex   = new ArrayList<>();
    private int             windowSize;
    private int             windowStart = 0;

    public WindowManager(int windowSize) {
        setWindowSize(windowSize);   // ensures min size
    }

    public void setWindowSize(int size) {
        synchronized (lock) {
            this.windowSize = Math.max(MIN_WINDOW, size);
        }
    }

    public int getWindowSize() {
        synchronized (lock) { return windowSize; }
    }

    public void setFullIndex(List<MediaFile> index) {
        synchronized (lock) {
            this.fullIndex   = index;
            this.windowStart = 0;
        }
    }

    public int getTotalSize() {
        synchronized (lock) { return fullIndex.size(); }
    }

    // ── Get window ────────────────────────────────────────────────────────────

    public List<MediaFile> getWindow() {
        synchronized (lock) {
            if (fullIndex.isEmpty()) return Collections.emptyList();
            int start = Math.max(0, windowStart);
            int end   = Math.min(fullIndex.size(), start + windowSize);
            // Return an unmodifiable view — no copy, no GC pressure.
            // Callers must not mutate the list; individual MediaFile objects
            // are still mutable (tags, path, etc.) which is what we want.
            return Collections.unmodifiableList(fullIndex.subList(start, end));
        }
    }

    // ── Center window on index ────────────────────────────────────────────────

    public void centerOn(int absoluteIndex) {
        synchronized (lock) {
            int half = windowSize / 2;
            int maxStart = Math.max(0, fullIndex.size() - windowSize);
            windowStart = Math.max(0, Math.min(absoluteIndex - half, maxStart));
        }
    }

    // ── Check if near edge ────────────────────────────────────────────────────

    public boolean nearStart(int absoluteIndex) {
        synchronized (lock) {
            return absoluteIndex <= windowStart + (windowSize / 4);
        }
    }

    public boolean nearEnd(int absoluteIndex) {
        synchronized (lock) {
            return absoluteIndex >= windowStart + (windowSize * 3 / 4);
        }
    }

    // ── Shift window ─────────────────────────────────────────────────────────

    public void shiftForward() {
        synchronized (lock) {
            int maxStart = Math.max(0, fullIndex.size() - windowSize);
            windowStart = Math.min(windowStart + (windowSize / 2), maxStart);
        }
    }

    public void shiftBack() {
        synchronized (lock) {
            windowStart = Math.max(0, windowStart - (windowSize / 2));
        }
    }

    // ── Convert between window and absolute index ─────────────────────────────

    public int toAbsolute(int windowIndex) {
        synchronized (lock) {
            return windowStart + windowIndex;
        }
    }

    public int toWindow(int absoluteIndex) {
        synchronized (lock) {
            return absoluteIndex - windowStart;
        }
    }

    public boolean isInWindow(int absoluteIndex) {
        synchronized (lock) {
            return absoluteIndex >= windowStart
                && absoluteIndex < windowStart + windowSize;
        }
    }

    // ── Get file by absolute index ────────────────────────────────────────────

    public MediaFile getFile(int absoluteIndex) {
        synchronized (lock) {
            if (absoluteIndex < 0 || absoluteIndex >= fullIndex.size()) return null;
            return fullIndex.get(absoluteIndex);
        }
    }

    public int findAbsoluteIndex(MediaFile file) {
        synchronized (lock) {
            for (int i = 0; i < fullIndex.size(); i++) {
                if (fullIndex.get(i).getPath().equals(file.getPath())) return i;
            }
            return -1;
        }
    }
}
