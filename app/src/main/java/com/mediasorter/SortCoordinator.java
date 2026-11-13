package com.mediasorter;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

/**
 * Shared worker coordinator for interactive sorting modes. Tasks are generation
 * stamped and are allowed to stop only at their own checkpoints, so an older
 * result can never be applied after a newer mode/session request.
 */
public final class SortCoordinator {
    public enum Mode { OFF, SMART, COMPARE, GAME }

    public interface Task {
        void run(TaskContext context) throws Throwable;
    }

    public interface Listener {
        void onTaskError(Throwable error);
    }

    public final class TaskContext {
        private final int taskGeneration;
        private long sliceStarted;
        TaskContext(int value) {
            taskGeneration = value;
            sliceStarted = SystemClock.uptimeMillis();
        }
        public int generation() { return taskGeneration; }
        public boolean isCurrent() { return taskGeneration == generation && !closed; }
        public void checkpoint() {
            if (!isCurrent()) throw new CancelledTaskException();
            int chunk = Math.max(5, Math.min(50,
                    prefs.getInt("smart_chunk_ms", 20) / (lowMemory ? 2 : 1)));
            while (paused || scanRunning || interactivePause || scrolling || volumeHeld || lowHeap()
                    || SystemClock.uptimeMillis() - lastActivity
                    < (long) prefs.getInt("rerank_idle_ms", 400) * (lowMemory ? 2L : 1L)) {
                SystemClock.sleep(16L);
                if (!isCurrent()) throw new CancelledTaskException();
                sliceStarted = SystemClock.uptimeMillis();
            }
            long now = SystemClock.uptimeMillis();
            if (now - sliceStarted >= chunk) {
                Thread.yield();
                sliceStarted = now;
            }
        }
    }

    private static final class CancelledTaskException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final android.content.SharedPreferences prefs;
    private final Handler mainHandler;
    private final HandlerThread workerThread;
    private final Handler worker;
    private final Listener listener;
    private final boolean lowMemory;
    private final android.app.ActivityManager activityManager;
    private volatile boolean paused;
    private volatile boolean scanRunning;
    private volatile boolean interactivePause;
    private volatile boolean scrolling;
    private volatile boolean volumeHeld;
    private volatile boolean closed;
    private volatile long lastActivity;
    private volatile int generation;
    private volatile Mode mode = Mode.OFF;

    public SortCoordinator(android.content.Context context, Handler main,
                           Listener errorListener) {
        prefs = context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE);
        mainHandler = main;
        listener = errorListener;
        activityManager = (android.app.ActivityManager)
                context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        lowMemory = activityManager != null && (activityManager.getMemoryClass() < 512
                || activityManager.isLowRamDevice());
        workerThread = new HandlerThread("MagnusSortCoordinator",
                Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    public Mode getMode() { return mode; }

    public synchronized int setMode(Mode next) {
        Mode value = next == null ? Mode.OFF : next;
        if (mode == value) return generation;
        mode = value;
        return ++generation;
    }

    public int newGeneration() { return ++generation; }
    public void setPaused(boolean value) { paused = value; }
    public void setScanRunning(boolean value) { scanRunning = value; }
    public void setInteractivePause(boolean value) { interactivePause = value; }
    public void setScrolling(boolean value) { scrolling = value; }
    public void setVolumeHeld(boolean value) { volumeHeld = value; }
    public void noteActivity() { lastActivity = SystemClock.uptimeMillis(); }
    public void cancel() { generation++; }

    public int post(final Task task) {
        if (task == null || closed) return generation;
        final int taskGeneration = generation;
        worker.post(new Runnable() {
            @Override public void run() {
                try {
                    task.run(new TaskContext(taskGeneration));
                } catch (CancelledTaskException ignored) {
                    // Superseded work is intentionally silent.
                } catch (Throwable error) {
                    if (listener != null && taskGeneration == generation && !closed) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                if (!closed && listener != null) listener.onTaskError(error);
                            }
                        });
                    }
                }
            }
        });
        return taskGeneration;
    }

    private boolean lowHeap() {
        if (activityManager == null) return false;
        android.app.ActivityManager.MemoryInfo info =
                new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(info);
        return info.lowMemory;
    }

    public void close() {
        closed = true;
        generation++;
        workerThread.quitSafely();
    }
}
