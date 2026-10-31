package com.mediasorter.features;

import java.util.HashMap;
import java.util.Map;

/** Volatile, session-only Smart values exposed to the organizer resolver. */
public final class SmartState {
    private static final Object LOCK = new Object();
    private static Map<String, Float> confidence = new HashMap<String, Float>();
    private static Map<String, String> suggestions = new HashMap<String, String>();
    private static Map<String, Integer> groupPositions = new HashMap<String, Integer>();
    private static Map<String, Integer> groupTotals = new HashMap<String, Integer>();
    private static int generation;

    private SmartState() {}

    public static void publish(Map<String, Float> values, Map<String, String> tags, int pass) {
        synchronized (LOCK) {
            confidence = values == null ? new HashMap<String, Float>()
                    : new HashMap<String, Float>(values);
            suggestions = tags == null ? new HashMap<String, String>()
                    : new HashMap<String, String>(tags);
            groupPositions.clear();
            groupTotals.clear();
            int total = values == null ? 0 : values.size();
            if (values != null) {
                int position = 1;
                for (Map.Entry<String, Float> entry : values.entrySet()) {
                    groupPositions.put(entry.getKey(), position++);
                    groupTotals.put(entry.getKey(), total);
                }
            }
            generation = pass;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            confidence.clear();
            suggestions.clear();
            groupPositions.clear();
            groupTotals.clear();
            generation++;
        }
    }

    public static float confidence(String path) {
        synchronized (LOCK) {
            Float value = confidence.get(path);
            return value == null ? 0f : value;
        }
    }

    public static String suggestedTag(String path) {
        synchronized (LOCK) {
            String value = suggestions.get(path);
            return value == null ? "" : value;
        }
    }

    public static int groupPosition(String path) {
        synchronized (LOCK) {
            Integer value = groupPositions.get(path);
            return value == null ? 0 : value;
        }
    }

    public static int groupTotal(String path) {
        synchronized (LOCK) {
            Integer value = groupTotals.get(path);
            return value == null ? 0 : value;
        }
    }

    public static int generation() {
        synchronized (LOCK) { return generation; }
    }
}
