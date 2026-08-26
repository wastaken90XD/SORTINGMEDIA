package com.mediasorter;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class CrashLogger implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashLogger";
    private static final String LOG_FILE = "crash_log.txt";
    private static final File CRASH_DIRECTORY =
            new File("/sdcard/SortingMedia/crash_logs");
    private static final Object WRITE_LOCK = new Object();
    private static volatile boolean installed = false;

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashLogger(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        synchronized (WRITE_LOCK) {
            if (installed) return;
            installed = true;
            Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(context));
        }
    }

    public static File getCrashLogDirectory() {
        return CRASH_DIRECTORY;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            long timestamp = System.currentTimeMillis();
            File directory = getCrashLogDirectory();
            if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
                Log.e(TAG, "Could not create crash log directory: " + directory.getAbsolutePath());
            } else {
                File logFile = new File(directory, "crash_" + timestamp + ".txt");
                StringWriter stackWriter = new StringWriter();
                PrintWriter stackPrinter = new PrintWriter(stackWriter);
                if (throwable != null) throwable.printStackTrace(stackPrinter);
                stackPrinter.flush();

                String dateTime = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                        .format(new Date(timestamp));
                String threadName = thread == null ? "unknown" : thread.getName();
                String exceptionClass = throwable == null
                        ? "null" : throwable.getClass().getName();
                String message = throwable == null
                        ? "null" : String.valueOf(throwable.getMessage());

                synchronized (WRITE_LOCK) {
                    FileOutputStream output = null;
                    OutputStreamWriter writer = null;
                    try {
                        output = new FileOutputStream(logFile);
                        writer = new OutputStreamWriter(output, "UTF-8");
                        writer.write("Date/time: " + dateTime + "\n");
                        writer.write("Thread: " + threadName + "\n");
                        writer.write("Exception class: " + exceptionClass + "\n");
                        writer.write("Message: " + message + "\n");
                        writer.write("Device model: " + Build.MODEL + "\n");
                        writer.write("Android SDK: " + Build.VERSION.SDK_INT + "\n");
                        writer.write("App version: " + BuildConfig.VERSION_NAME
                                + " (" + BuildConfig.VERSION_CODE + ")\n");
                        writer.write("Full stack trace:\n");
                        writer.write(stackWriter.toString());
                        writer.flush();
                    } finally {
                        if (writer != null) {
                            try { writer.close(); } catch (Exception ignored) {}
                        } else if (output != null) {
                            try { output.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable loggingError) {
            Log.e(TAG, "Could not save crash report", loggingError);
        }

        if (defaultHandler != null && defaultHandler != this) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }

    public static File[] listCrashLogs() {
        File directory = getCrashLogDirectory();
        if (!directory.exists() || !directory.isDirectory()) return new File[0];
        File[] files = directory.listFiles(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                return name != null && name.startsWith("crash_") && name.endsWith(".txt");
            }
        });
        if (files == null) return new File[0];
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File first, File second) {
                return second.getName().compareTo(first.getName());
            }
        });
        return files;
    }

    public static String readCrashLog(File file) {
        if (file == null || !file.exists() || !file.isFile()) return "Crash log is unavailable.";
        StringBuilder result = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append("\n");
            return result.toString();
        } catch (Exception error) {
            return "Could not read crash log: " + error.getMessage();
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean deleteCrashLog(File file) {
        if (file == null || !file.isFile()) return false;
        File directory = getCrashLogDirectory();
        if (file.getParentFile() == null
                || !directory.getAbsolutePath().equals(file.getParentFile().getAbsolutePath())) {
            return false;
        }
        return file.delete();
    }

    /** Read the newest crash report for the existing SettingsActivity viewer. */
    public static String readLog(Context context) {
        File[] logs = listCrashLogs();
        if (logs.length > 0) return readCrashLog(logs[0]);

        File legacy = new File(context.getFilesDir(), LOG_FILE);
        if (!legacy.exists()) return "No crashes logged.";
        try {
            StringBuilder result = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(legacy));
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append("\n");
            reader.close();
            return result.toString();
        } catch (Exception error) {
            return "Could not read log: " + error.getMessage();
        }
    }

    public static void clearLog(Context context) {
        for (File file : listCrashLogs()) deleteCrashLog(file);
        File legacy = new File(context.getFilesDir(), LOG_FILE);
        if (legacy.exists()) legacy.delete();
    }
}
