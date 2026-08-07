package com.mediasorter.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Small random-pick utilities.
 *
 * Used for:
 *  - suggesting a random tag from the existing tag pool,
 *  - building a random tag combo (e.g. "ab-ac-ad"),
 *  - random file picking ("shuffle"/surprise-me browsing),
 *  - collision-free labels for batch/test operations.
 *
 * All methods are thread-safe enough for UI use; Random is shared.
 */
public final class RandomGenerator {

    private static final Random RAND = new Random();
    private static final String[] WORDS = {
        "ab", "ac", "ad", "be", "ka", "lo", "mi", "ne", "ra", "su", "te", "vo"
    };

    private RandomGenerator() {}

    /** A random tag id, e.g. "tag_482913" — handy for placeholders/tests. */
    public static String tag() {
        return "tag_" + (RAND.nextInt(900000) + 100000);
    }

    /** Pick a random tag from an existing pool; null-safe. "" when empty. */
    public static String tag(List<String> pool) {
        if (pool == null || pool.isEmpty()) return "";
        return pool.get(RAND.nextInt(pool.size()));
    }

    /**
     * Random 3-part combo like "ab-ac-ad". With a pool supplied, the parts
     * are distinct pool entries joined with '-' (pool repeats allowed when
     * it has fewer than 3 entries).
     */
    public static String combo() {
        return WORDS[RAND.nextInt(WORDS.length)] + "-"
             + WORDS[RAND.nextInt(WORDS.length)] + "-"
             + WORDS[RAND.nextInt(WORDS.length)];
    }

    public static String combo(List<String> pool) {
        if (pool == null || pool.isEmpty()) return combo();
        List<String> parts = new ArrayList<>();
        List<String> copy  = new ArrayList<>(pool);
        for (int i = 0; i < 3 && !copy.isEmpty(); i++) {
            parts.add(copy.remove(RAND.nextInt(copy.size())));
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('-');
            sb.append(p);
        }
        return sb.toString();
    }

    /** Unique-ish label for batch operations. */
    public static String batchLabel() {
        return "batch_" + System.currentTimeMillis() + "_"
             + (RAND.nextInt(900) + 100);
    }

    /**
     * Deterministic user tag with a random suffix, guaranteeing the name is
     * free in the given pool (e.g. "holiday_a4f2").
     */
    public static String userTag(String input) {
        return userTag(input, null);
    }

    public static String userTag(String input, List<String> existingPool) {
        String base = input == null ? "" : input.trim();
        if (base.isEmpty()) base = "tag";
        if (existingPool == null || !existingPool.contains(base)) return base;
        StringBuilder sb = new StringBuilder(base).append('_');
        for (int i = 0; i < 4; i++) {
            int n = RAND.nextInt(36);
            sb.append((char) (n < 10 ? '0' + n : 'a' + (n - 10)));
        }
        return sb.toString();
    }

    /** Random index into a list of size n; -1 when n <= 0. */
    public static int pick(int n) {
        return n <= 0 ? -1 : RAND.nextInt(n);
    }
}
