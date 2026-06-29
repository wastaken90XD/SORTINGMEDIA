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
        List<String> usedNames = new ArrayList<>();
        int counter = numberStart;   // sequential counter resets each preview call

        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (file.getTags().isEmpty() && numbering == Numbering.NONE) continue;

            String newName = buildName(file, counter);
            String extension = getExtension(file.getName());
            String fullNew = newName + extension;

            // If no actual change, skip conflict check
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

    // ── Name builder (enhanced) ──────────────────────────────────────────────

    private String buildName(MediaFile file, int sequenceNumber) {
        String sep = getSeparatorChar();
        String original = stripExtension(file.getName());

        // 1. Apply find & replace on the original name
        for (Map.Entry<String, String> r : replacements.entrySet()) {
            if (r.getKey().isEmpty()) continue;
            original = original.replace(r.getKey(), r.getValue());
        }

        // 2. Build tag part
        List<String> tags = file.getTags();
        String tagPart = String.join(sep, tags);

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
            case LOWERCASE: body = body.toLowerCase(); break;
            case UPPERCASE: body = body.toUpperCase(); break;
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

        // Number before or after body
        if (!numberPart.isEmpty() && numberPosition == NumberPosition.BEFORE) {
            sb.append(numberPart).append(numberSeparator);
        }

        sb.append(body);

        if (!numberPart.isEmpty() && numberPosition == NumberPosition.AFTER) {
            sb.append(numberSeparator).append(numberPart);
        }

        if (!suffix.isEmpty()) sb.append(sep).append(suffix);

        return sb.toString();
    }

    // ── Helpers (unchanged) ──────────────────────────────────────────────────
    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private String stripExtension(String filename) {
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
}
