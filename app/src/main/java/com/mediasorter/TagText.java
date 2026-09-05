package com.mediasorter;

import java.text.Normalizer;

/**
 * Normalizes values that are used as media tags.
 *
 * Tags are persisted in XMP and are also used as keys in the tag database, so
 * UI decoration must never become part of the value itself.  This keeps tag
 * values readable as plain text while leaving unrelated UI strings (including
 * button labels and Toast messages) untouched.
 */
public final class TagText {

    private TagText() {}

    /**
     * Returns a trimmed tag value with emoji code points and emoji-only
     * variation/joiner characters removed.
     */
    public static String plain(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);

        StringBuilder result = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isEmoji(codePoint)
                    || codePoint == 0x200D       // zero-width joiner
                    || codePoint == 0x20E3       // keycap combining mark
                    || codePoint == 0xFE0E       // text presentation selector
                    || codePoint == 0xFE0F) {    // emoji presentation selector
                continue;
            }
            result.appendCodePoint(codePoint);
        }
        return result.toString().trim();
    }

    /* Manual sanitization checks:
     * plain("Café")       -> "Café"       (accented Latin survives)
     * plain("Crème 你好")   -> "Crème 你好"   (CJK survives)
     * plain("مرحبا")       -> "مرحبا"       (Arabic survives)
     * plain("Beach 🏖️")     -> "Beach"       (emoji is removed)
     */

    /**
     * Covers the Unicode emoji blocks plus the legacy symbols that have emoji
     * presentation in Android fonts.  The latter includes stars, triangles,
     * check marks, and similar decoration commonly used in tag labels.
     */
    private static boolean isEmoji(int codePoint) {
        if ((codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x1FC00 && codePoint <= 0x1FFFD)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)) {
            return true;
        }

        // Miscellaneous symbols that are emoji when used as standalone
        // characters or in an emoji sequence.
        if ((codePoint >= 0x2300 && codePoint <= 0x23FF)
                || (codePoint >= 0x25A0 && codePoint <= 0x25FF)
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)) {
            return true;
        }

        switch (codePoint) {
            case 0x00A9: // copyright
            case 0x00AE: // registered
            case 0x203C:
            case 0x2049:
            case 0x2122:
            case 0x2139:
            case 0x2194:
            case 0x2195:
            case 0x2196:
            case 0x2197:
            case 0x2198:
            case 0x2199:
            case 0x21A9:
            case 0x21AA:
            case 0x3030:
            case 0x303D:
            case 0x3297:
            case 0x3299:
                return true;
            default:
                return false;
        }
    }
}
