package com.mediasorter;

import com.mediasorter.models.MediaFile;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatchRenameManager {

    // ── Existing enums ───────────────────────────────────────────────────────
    public enum Separator  { UNDERSCORE, DASH, SPACE, NONE }
    public enum Order      { TAGS_ONLY, ORIGINAL_THEN_TAGS, TAGS_THEN_ORIGINAL }
    public enum Case       { AS_IS, LOWERCASE, UPPERCASE }

    // ── New enums ────────────────────────────────────────────────────────────
    public enum Numbering      { NONE, SEQUENTIAL, DATE }
    public enum NumberPosition { BEFORE, AFTER }

    // ── Data classes (unchanged) ─────────────────────────────────────────────
    public static class RenamePreview {
        public final String originalPath;
        public final String originalName;
        public final String newName;
        public final boolean hasConflict;

        RenamePreview(String path, String original, String newName, boolean conflict) {
            this.originalPath = path;
            this.originalName = original;
            this.newName      = newName;
            this.hasConflict  = conflict;
        }
    }

    public static class RenameResult {
        public int succeeded;
        public int failed;
        public List<String> errors = new ArrayList<>();
    }

    // ── Existing fields ──────────────────────────────────────────────────────
    private Separator separator = Separator.UNDERSCORE;
    private Order     order     = Order.TAGS_ONLY;
    private Case      caseMode  = Case.AS_IS;

    // ── New fields ───────────────────────────────────────────────────────────
    private String          prefix         = "";
    private String          suffix         = "";
    private boolean         includeFolder  = false;
    private Numbering       numbering      = Numbering.NONE;
    private int             numberStart    = 1;
    private int             numberPadding  = 3;
    private String          dateFormat     = "yyyy-MM-dd";
    private NumberPosition  numberPosition = NumberPosition.BEFORE;
    private String          numberSeparator = "_";
    private final Map<String, String> replacements = new LinkedHashMap<>();

    // ── ** NEW: Custom rename pattern (optional) ** ──────────────────────────
    private String          customPattern  = null;   // if set, overrides Order/Separator/etc.

    // Undo stack (thread‑safe)
    private final Map<String, String> undoMap = new ConcurrentHashMap<>();

    // ── Settings (existing) ──────────────────────────────────────────────────
    public void setSeparator(Separator s) { this.separator = s; }
    public void setOrder(Order o)         { this.order     = o; }
    public void setCaseMode(Case c)       { this.caseMode  = c; }

    public Separator getSeparator() { return separator; }
    public Order     getOrder()     { return order; }
    public Case      getCaseMode()  { return caseMode; }

    public String getSeparatorChar() {
        switch (separator) {
            case UNDERSCORE: return "_";
            case DASH:       return "-";
            case SPACE:      return " ";
            default:         return "";
        }
    }

    // ── Settings (new) ───────────────────────────────────────────────────────
    public void setPrefix(String prefix)                { this.prefix = prefix != null ? prefix : ""; }
    public void setSuffix(String suffix)                { this.suffix = suffix != null ? suffix : ""; }
    public void setIncludeFolder(boolean include)       { this.includeFolder = include; }
    public void setNumbering(Numbering num)             { this.numbering = num; }
    public void setNumberStart(int start)               { this.numberStart = Math.max(0, start); }
    public void setNumberPadding(int padding)           { this.numberPadding = Math.max(1, padding); }
    public void setDateFormat(String pattern)           { this.dateFormat = pattern != null ? pattern : "yyyy-MM-dd"; }
    public void setNumberPosition(NumberPosition pos)   { this.numberPosition = pos; }
    public void setNumberSeparator(String sep)          { this.numberSeparator = sep != null ? sep : "_"; }
    public void setReplacements(Map<String, String> map) {
        replacements.clear();
        if (map != null) replacements.putAll(map);
    }
    public void addReplacement(String find, String replace) {
        if (find != null) replacements.put(find, replace != null ? replace : "");
    }
    public void clearReplacements() {
        replacements.clear();
    }

    // ── ** NEW: Custom pattern getter/setter ** ─────────────────────────────
    /**
     * Sets a custom pattern for the filename. If non-null and non-empty,
     * all other ordering/placement settings (Separator, Order, Prefix,
     * Suffix, IncludeFolder, Numbering, NumberPosition, NumberSeparator)
     * are still used to generate the components, but the pattern
     * determines exactly how they are assembled.
     * <p>
     * Placeholders: {TAGS}, {ORIGINAL}, {COUNTER}, {COUNTER:5}, {DATE},
     * {FOLDER}, {PREFIX}, {SUFFIX}, {EXT}, {SEP}
     * <p>
     * Example: "{PREFIX}_{FOLDER}{COUNTER:3}_{TAGS}_{ORIGINAL}{SUFFIX}{EXT}"
     */
    public void setPattern(String pattern) { this.customPattern = pattern; }
    public String getPattern()             { return customPattern; }

    public String       getPrefix()          { return prefix; }
    public String       getSuffix()          { return suffix; }
    public boolean      isIncludeFolder()    { return includeFolder; }
    public Numbering    getNumbering()       { return numbering; }
    public int          getNumberStart()     { return numberStart; }
    public int          getNumberPadding()   { return numberPadding; }
    public String       getDateFormat()      { return dateFormat; }
    public NumberPosition getNumberPosition() { return numberPosition; }
    public String       getNumberSeparator() { return numberSeparator; }
    public Map<String, String> getReplacements() { return new LinkedHashMap<>(replacements); }

    // ── Preview (updated) ────────────────────────────────────────────────────

    public List<RenamePreview> preview(List<MediaFile> files) {
        List<RenamePreview> previews = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        int counter = numberStart;
        boolean hasCustomPattern = customPattern != null && !customPattern.isEmpty();

        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            boolean hasTags = file.getTags() != null && !file.getTags().isEmpty();
            if (!hasTags && numbering == Numbering.NONE && !hasCustomPattern) continue;

            String newName = buildName(file, counter);
            String fullNew;
            if (hasCustomPattern) {
                fullNew = newName;
            } else {
                String extension = getExtension(file.getName());
                fullNew = newName + extension;
            }

            // Never allow a completely empty filename
            if (fullNew.isEmpty()) fullNew = file.getName();

            if (fullNew.equals(file.getName())) {
                previews.add(new RenamePreview(file.getPath(), file.getName(), fullNew, false));
                if (numbering == Numbering.SEQUENTIAL) counter++;
                continue;
            }

            boolean conflict = usedNames.contains(fullNew) || targetExists(file, fullNew);
            if (!conflict) usedNames.add(fullNew);

            previews.add(new RenamePreview(
                    file.getPath(),
                    file.getName(),
                    fullNew,
                    conflict));

            if (numbering == Numbering.SEQUENTIAL) counter++;
        }
        return previews;
    }

    // ── Apply (unchanged) ────────────────────────────────────────────────────

    public RenameResult apply(List<RenamePreview> previews) {
        RenameResult result = new RenameResult();
        undoMap.clear();

        for (RenamePreview p : previews) {
            if (p.hasConflict) {
                result.failed++;
                result.errors.add("Conflict: " + p.newName);
                continue;
            }
            if (p.newName.equals(p.originalName)) {
                result.succeeded++;
                continue;
            }

            File original = new File(p.originalPath);
            File renamed  = new File(original.getParent(), p.newName);

            try {
                if (original.renameTo(renamed)) {
                    undoMap.put(renamed.getAbsolutePath(), p.originalPath);
                    result.succeeded++;
                } else {
                    result.failed++;
                    result.errors.add("Failed: " + p.originalName);
                }
            } catch (Exception e) {
                result.failed++;
                result.errors.add("Error: " + p.originalName + " — " + e.getMessage());
            }
        }
        return result;
    }

    // ── Undo (unchanged) ─────────────────────────────────────────────────────

    public RenameResult undo() {
        RenameResult result = new RenameResult();
        if (undoMap.isEmpty()) return result;

        for (Map.Entry<String, String> entry : undoMap.entrySet()) {
            File current  = new File(entry.getKey());
            File original = new File(entry.getValue());

            if (!current.exists()) {
                result.failed++;
                result.errors.add("Not found: " + entry.getKey());
                continue;
            }
            try {
                if (current.renameTo(original)) {
                    result.succeeded++;
                } else {
                    result.failed++;
                    result.errors.add("Undo failed: " + current.getName());
                }
            } catch (Exception e) {
                result.failed++;
                result.errors.add("Error: " + e.getMessage());
            }
        }
        undoMap.clear();
        return result;
    }

    public boolean canUndo() { return !undoMap.isEmpty(); }

    // ── Name builder (now with pattern support) ─────────────────────────────

    private String buildName(MediaFile file, int sequenceNumber) {
        // If a custom pattern is set, use it – otherwise fallback to old behaviour
        if (customPattern != null && !customPattern.isEmpty()) {
            return buildNameFromPattern(file, sequenceNumber);
        }

        // Original logic (unchanged)
        String sep = getSeparatorChar();
        String original = stripExtension(file.getName());

        // 1. Apply find & replace on the original name
        for (Map.Entry<String, String> r : replacements.entrySet()) {
            if (r.getKey().isEmpty()) continue;
            original = original.replace(r.getKey(), r.getValue());
        }

        // 2. Build tag part (API‑21‑safe)
        String tagPart = join(sep, file.getTags());

        // 3. Combine according to order
        String body;
        switch (order) {
            case ORIGINAL_THEN_TAGS:
                body = original + (sep.isEmpty() ? "" : sep) + tagPart;
                break;
            case TAGS_THEN_ORIGINAL:
                body = tagPart + (sep.isEmpty() ? "" : sep) + original;
                break;
            default: // TAGS_ONLY
                body = tagPart;
                break;
        }

        // 4. Apply case
        switch (caseMode) {
            case LOWERCASE: body = body.toLowerCase(Locale.US); break;
            case UPPERCASE: body = body.toUpperCase(Locale.US); break;
        }

        // 5. Folder name (optional)
        String folderPart = "";
        if (includeFolder) {
            File parent = new File(file.getPath()).getParentFile();
            if (parent != null) {
                folderPart = parent.getName() + sep;
            }
        }

        // 6. Numbering
        String numberPart = "";
        if (numbering == Numbering.SEQUENTIAL) {
            numberPart = String.format(Locale.US, "%0" + numberPadding + "d", sequenceNumber);
        } else if (numbering == Numbering.DATE) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(dateFormat, Locale.US);
                numberPart = sdf.format(new Date(file.getDateAdded()));
            } catch (Exception e) {
                numberPart = "DATE_ERROR";
            }
        }

        // 7. Assemble final name with prefix/suffix
        StringBuilder sb = new StringBuilder();
        if (!prefix.isEmpty()) sb.append(prefix).append(sep);

        sb.append(folderPart);

        if (!numberPart.isEmpty() && numberPosition == NumberPosition.BEFORE) {
            sb.append(numberPart).append(numberSeparator);
        }

        sb.append(body);

        if (!numberPart.isEmpty() && numberPosition == NumberPosition.AFTER) {
            sb.append(numberSeparator).append(numberPart);
        }

        if (!suffix.isEmpty()) sb.append(sep).append(suffix);

        String result = sb.toString();
        if (result.isEmpty()) result = original;
        return result;
    }

    // ── ** NEW: Pattern‑based name builder ** ───────────────────────────────

    private String buildNameFromPattern(MediaFile file, int sequenceNumber) {
        String sep = getSeparatorChar();
        String original = stripExtension(file.getName());

        // Apply replacements to the original part
        for (Map.Entry<String, String> r : replacements.entrySet()) {
            if (r.getKey().isEmpty()) continue;
            original = original.replace(r.getKey(), r.getValue());
        }

        // Tag part
        String tagPart = join(sep, file.getTags());

        // Folder part (just the name, no trailing separator)
        String folderName = "";
        if (includeFolder) {
            File parent = new File(file.getPath()).getParentFile();
            if (parent != null) folderName = parent.getName();
        }

        // Number part
        String numberPart = "";
        if (numbering == Numbering.SEQUENTIAL) {
            numberPart = String.format(Locale.US, "%0" + numberPadding + "d", sequenceNumber);
        } else if (numbering == Numbering.DATE) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(dateFormat, Locale.US);
                numberPart = sdf.format(new Date(file.getDateAdded()));
            } catch (Exception e) {
                numberPart = "DATE_ERROR";
            }
        }

        // Extension
        String ext = getExtension(file.getName());

        // Build result by replacing placeholders
        String result = customPattern;

        // Simple placeholder replacement (order matters because some may overlap)
        result = result.replace("{PREFIX}", prefix);
        result = result.replace("{SUFFIX}", suffix);
        result = result.replace("{FOLDER}", folderName);
        result = result.replace("{TAGS}", tagPart);
        result = result.replace("{ORIGINAL}", original);
        result = result.replace("{DATE}", numberPart);   // if using DATE numbering
        result = result.replace("{EXT}", ext);
        result = result.replace("{SEP}", sep);

        // Handle {COUNTER} and {COUNTER:pad}
        result = replaceCounter(result, numberPart);

        // Apply case mode to the whole result
        switch (caseMode) {
            case LOWERCASE: result = result.toLowerCase(Locale.US); break;
            case UPPERCASE: result = result.toUpperCase(Locale.US); break;
        }

        if (result.isEmpty()) result = original + ext;
        return result;
    }

    /**
     * Replaces {COUNTER} and {COUNTER:pad} placeholders with the given numberPart.
     * If a custom padding is specified (e.g. {COUNTER:4}), it overrides the global
     * numberPadding for that occurrence.
     */
    private String replaceCounter(String template, String currentNumberPart) {
        // Regex to match {COUNTER} or {COUNTER:5}
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{COUNTER(?::(\\d+))?\\}");
        Matcher m = p.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String paddingStr = m.group(1);
            String replacement;
            if (paddingStr != null) {
                // Override global padding for this occurrence
                try {
                    int pad = Integer.parseInt(paddingStr);
                    if (numbering == Numbering.SEQUENTIAL) {
                        // We need the raw sequence number to re-format it
                        // Unfortunately we don't have direct access to it here,
                        // but we can parse the currentNumberPart (which is already
                        // zero-padded) and re-format with new padding.
                        int seq = Integer.parseInt(currentNumberPart);
                        replacement = String.format(Locale.US, "%0" + pad + "d", seq);
                    } else {
                        replacement = currentNumberPart; // for DATE, ignore counter
                    }
                } catch (Exception e) {
                    replacement = currentNumberPart;
                }
            } else {
                replacement = currentNumberPart;
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private String stripExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private boolean targetExists(MediaFile file, String newName) {
        try {
            File parent = new File(file.getPath()).getParentFile();
            if (parent == null) return false;
            return new File(parent, newName).exists();
        } catch (SecurityException e) {
            return true;
        }
    }

    /**
     * API‑21‑safe replacement for String.join.
     */
    private static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (CharSequence item : elements) {
            if (i++ > 0) sb.append(delimiter);
            sb.append(item);
        }
        return sb.toString();
    }
}
