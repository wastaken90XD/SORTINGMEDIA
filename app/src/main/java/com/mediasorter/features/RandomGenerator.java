package com.mediasorter.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure utility class for random generation, unique suffix tag handling,
 * sequential indexing, and linking. Safe for API 21 (no streams, no lambdas).
 */
public final class RandomGenerator {

    private static final Random RAND = new Random();
    private static final java.util.regex.Pattern CUSTOM_TOKEN =
            java.util.regex.Pattern.compile("\\{([^{}]+)\\}");
    private static final java.util.regex.Pattern TAG_TOKEN =
            java.util.regex.Pattern.compile("tag:[0-9]+");

    private static final java.util.regex.Pattern LEADING_PATTERN = java.util.regex.Pattern.compile("^(link_|tag_)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TRAILING_SEQ = java.util.regex.Pattern.compile("_seq_[a-z0-9]+$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TRAILING_HEX = java.util.regex.Pattern.compile("_[0-9A-F]{4}$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String[] SYLLABLES = {
        "ba", "co", "da", "fa", "ga", "ka", "la", "ma", "na", "ra", "sa", "ta", "za",
        "be", "de", "ge", "ke", "le", "me", "ne", "re", "se", "te", "ze",
        "bi", "di", "fi", "ki", "li", "mi", "ni", "ri", "si", "ti", "zi",
        "bo", "do", "go", "lo", "mo", "no", "ro", "so", "to", "zo"
    };

    private RandomGenerator() {}

    /**
     * Converts a 0-based index into a bijective base-26 column sequence label
     * starting at "aa", "ab", "ac" ... "ba" ... minimum 2 characters always.
     */
    public static String sequenceLabel(int index) {
        if (index < 0) index = 0;
        int shifted = index + 26; // Maps index 0 directly to column 27 ("aa")
        StringBuilder sb = new StringBuilder();
        int temp = shifted;
        while (temp >= 0) {
            sb.insert(0, (char) ('a' + (temp % 26)));
            temp = (temp / 26) - 1;
        }
        return sb.toString();
    }

    /**
     * Returns the first index whose sequenceLabel is not already taken
     * under prefix + "_seq_" + label.
     */
    public static int nextSequenceIndex(String prefix, Set<String> existingTags) {
        int idx = 0;
        while (true) {
            String label = prefix + "_seq_" + sequenceLabel(idx);
            if (existingTags == null || !existingTags.contains(label)) {
                return idx;
            }
            idx++;
        }
    }

    /**
     * Reserves count conflict-free sequence tags in order, mutates existingTags,
     * and returns the list.
     */
    public static List<String> allocateSequenceTags(String groupTag, int count, Set<String> existingTags) {
        List<String> allocated = new ArrayList<String>();
        int startIdx = nextSequenceIndex(groupTag, existingTags);
        int found = 0;
        int currentIdx = startIdx;
        while (found < count) {
            String tag = groupTag + "_seq_" + sequenceLabel(currentIdx);
            if (existingTags == null || !existingTags.contains(tag)) {
                if (existingTags != null) {
                    existingTags.add(tag);
                }
                allocated.add(tag);
                found++;
            }
            currentIdx++;
        }
        return allocated;
    }

    /**
     * Syllable triplet "xx-xx-xx" from a fixed pool, retries until not in usedPrefixes.
     */
    public static String randomGroupPrefix(Set<String> usedPrefixes) {
        int attempts = 0;
        while (attempts < 10000) {
            String p1 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
            String p2 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
            String p3 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
            String prefix = p1 + "-" + p2 + "-" + p3;
            if (usedPrefixes == null || !usedPrefixes.contains(prefix)) {
                return prefix;
            }
            attempts++;
        }
        return "link-" + (RAND.nextInt(90000) + 10000);
    }

    /**
     * Returns "tag_" + 6 uppercase hex characters.
     */
    public static String randomPlaceholderTag() {
        int val = RAND.nextInt(0xFFFFFF + 1);
        return String.format("tag_%06X", val);
    }

    /**
     * Appends "_XXXX" hex suffix to the base string, retries until not in existingTags.
     */
    public static String uniqueSuffixTag(String base, Set<String> existingTags) {
        if (base == null || base.trim().isEmpty()) {
            base = "tag";
        }
        String cleanBase = base.trim();
        int attempts = 0;
        String candidate = "";
        while (attempts < 1000) {
            int val = RAND.nextInt(0xFFFF + 1);
            candidate = String.format("%s_%04X", cleanBase, val);
            if (existingTags == null || !existingTags.contains(candidate)) {
                return candidate;
            }
            attempts++;
        }
        // Fallback: append one more random 4-char hex to make it statistically unique and avoid collision
        String lastResort = candidate + "_" + String.format(java.util.Locale.US, "%04X", RAND.nextInt(0x10000));
        return lastResort;
    }

    /**
     * Syllable triplet, no conflict check, cosmetic use only.
     */
    public static String randomSyllableTag() {
        String p1 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
        String p2 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
        String p3 = SYLLABLES[RAND.nextInt(SYLLABLES.length)];
        return p1 + "-" + p2 + "-" + p3;
    }

    /**
     * Generate the tag requested by SettingsActivity. The method is kept
     * context-aware instead of caching a Context so it is safe to call from a
     * dialog, a gesture, or a background action without leaking an Activity.
     */
    public static String randomTag(Context context, Set<String> existingTags) {
        String format = "syllable";
        String custom = "{syl}-{date}";
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(
                    "settings_prefs", Context.MODE_PRIVATE);
            format = prefs.getString("random_tag_format", "syllable");
            custom = prefs.getString("random_tag_custom_pattern", custom);
        }
        String base;
        if ("hex".equalsIgnoreCase(format)) {
            base = randomPlaceholderTag();
        } else if ("custom".equalsIgnoreCase(format)) {
            base = resolveCustomPattern(custom, existingTags);
        } else if ("random".equalsIgnoreCase(format)) {
            int choice = RAND.nextInt(3);
            if (choice == 0) base = randomSyllableTag();
            else if (choice == 1) base = randomPlaceholderTag();
            else base = resolveCustomPattern(custom, existingTags);
        } else {
            base = randomSyllableTag();
        }
        if (existingTags == null || !existingTags.contains(base)) return base;
        return uniqueSuffixTag(base, existingTags);
    }

    private static String resolveCustomPattern(String pattern, Set<String> existingTags) {
        String value = pattern == null || pattern.trim().isEmpty()
                ? "{syl}-{date}" : pattern;
        Calendar now = Calendar.getInstance();
        value = value.replace("{syl}", randomSyllableTag());
        value = value.replace("{hex}", randomHex(6));
        value = value.replace("{seq}", nextCustomSequence(existingTags));
        value = value.replace("{date}", new SimpleDateFormat(
                "yyyyMMdd", Locale.US).format(now.getTime()));
        value = value.replace("{year}", String.format(Locale.US, "%04d", now.get(Calendar.YEAR)));
        value = value.replace("{month}", String.format(Locale.US, "%02d", now.get(Calendar.MONTH) + 1));
        value = value.replace("{day}", String.format(Locale.US, "%02d", now.get(Calendar.DAY_OF_MONTH)));
        value = value.replace("{random}", randomSyllableTag());
        // These variables require a MediaFile and are therefore empty in a
        // standalone random tag pattern. They are still known placeholders.
        value = value.replace("{filename}", "");
        value = value.replace("{ext}", "");
        value = value.replace("{size}", "");
        value = value.replace("{index}", "");
        java.util.regex.Matcher tagMatcher = TAG_TOKEN.matcher(value);
        StringBuffer tagBuffer = new StringBuffer();
        while (tagMatcher.find()) tagMatcher.appendReplacement(tagBuffer, "");
        tagMatcher.appendTail(tagBuffer);
        value = tagBuffer.toString();

        java.util.regex.Matcher matcher = CUSTOM_TOKEN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            Log.w("RandomGenerator", "Unknown random tag placeholder: {" + token + "}");
            matcher.appendReplacement(buffer, "");
        }
        matcher.appendTail(buffer);
        return buffer.toString().trim();
    }

    /** Return unknown {placeholder} tokens for SettingsActivity validation. */
    public static List<String> findUnknownPlaceholders(String pattern) {
        List<String> unknown = new ArrayList<String>();
        if (pattern == null) return unknown;
        java.util.regex.Matcher matcher = CUSTOM_TOKEN.matcher(pattern);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isKnownCustomPlaceholder(token) && !unknown.contains(token)) unknown.add(token);
        }
        return unknown;
    }

    private static boolean isKnownCustomPlaceholder(String token) {
        return "syl".equals(token) || "hex".equals(token) || "seq".equals(token)
                || "date".equals(token) || "year".equals(token) || "month".equals(token)
                || "day".equals(token) || "index".equals(token) || "random".equals(token)
                || "filename".equals(token) || "ext".equals(token) || "size".equals(token)
                || TAG_TOKEN.matcher(token).matches();
    }

    private static String randomHex(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(Integer.toHexString(RAND.nextInt(16)).toUpperCase(Locale.US));
        }
        return builder.toString();
    }

    private static String nextCustomSequence(Set<String> existingTags) {
        int next = 0;
        if (existingTags != null) {
            String prefix = "seq";
            next = nextSequenceIndex(prefix, existingTags);
        }
        return sequenceLabel(next);
    }

    /** Fisher-Yates shuffle for callers that need the app's shared random source. */
    public static <T> void shuffle(List<T> values) {
        if (values == null) return;
        for (int i = values.size() - 1; i > 0; i--) {
            int j = RAND.nextInt(i + 1);
            T value = values.get(i);
            values.set(i, values.get(j));
            values.set(j, value);
        }
    }

    /**
     * Uniform random index in [0, size). Returns -1 when size <= 0.
     */
    public static int pick(int size) {
        return (size <= 0) ? -1 : RAND.nextInt(size);
    }

    public static String cleanRootWord(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        s = LEADING_PATTERN.matcher(s).replaceAll("");
        s = TRAILING_HEX.matcher(s).replaceAll("");
        s = TRAILING_SEQ.matcher(s).replaceAll("");
        return s;
    }

    public static String generateThirdCycleTag(Set<String> existingTags) {
        if (existingTags == null || existingTags.isEmpty()) {
            String base = randomSyllableTag();
            return uniqueSuffixTag(base, existingTags);
        }
        java.util.Set<String> cleanRoots = new java.util.HashSet<String>();
        // Iterate over snapshot to be safe against concurrency
        java.util.List<String> snapshot = new ArrayList<String>(existingTags);
        for (String tag : snapshot) {
            String cleaned = cleanRootWord(tag);
            if (cleaned != null && cleaned.length() >= 2) {
                cleanRoots.add(cleaned);
            }
        }
        java.util.List<String> rootsList = new java.util.ArrayList<String>(cleanRoots);
        if (rootsList.size() >= 2) {
            int idx1 = pick(rootsList.size());
            int idx2;
            int safety = 0;
            do {
                idx2 = pick(rootsList.size());
                safety++;
            } while (idx2 == idx1 && safety < 100);
            String joined = rootsList.get(idx1) + "_" + rootsList.get(idx2);
            return uniqueSuffixTag(joined, existingTags);
        } else if (rootsList.size() == 1) {
            return uniqueSuffixTag(rootsList.get(0), existingTags);
        } else {
            String base = randomSyllableTag();
            return uniqueSuffixTag(base, existingTags);
        }
    }
}
