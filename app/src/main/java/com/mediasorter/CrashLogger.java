package com.mediasorter;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashLogger implements Thread.UncaughtExceptionHandler {

    private static final String LOG_FILE = "crash_log.txt";
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashLogger(Context context) {
        this.context        = context;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));

            String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            String entry = "\n--- CRASH " + timestamp + " ---\n"
                + "Thread: " + thread.getName() + "\n"
                + sw.toString()
                + "\n";

            File logFile = new File(context.getFilesDir(), LOG_FILE);
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(entry);
            fw.close();

        } catch (Exception ignored) {}

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        }
    }

    public static String readLog(Context context) {
        File logFile = new File(context.getFilesDir(), LOG_FILE);
        if (!logFile.exists()) return "No crashes logged.";

        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(logFile));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "Could not read log: " + e.getMessage();
        }
    }

    public static void clearLog(Context context) {
        File logFile = new File(context.getFilesDir(), LOG_FILE);
        if (logFile.exists()) logFile.delete();
    }
}
