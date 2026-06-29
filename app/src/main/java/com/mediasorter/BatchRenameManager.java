package com.mediasorter;

import android.content.Context;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BatchRenameManager {

    public enum Separator  { UNDERSCORE, DASH, SPACE, NONE }
    public enum Order      { TAGS_ONLY, ORIGINAL_THEN_TAGS, TAGS_THEN_ORIGINAL }
    public enum Case       { AS_IS, LOWERCASE, UPPERCASE }

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

    private Separator separator = Separator.UNDERSCORE;
    private Order     order     = Order.TAGS_ONLY;
    private Case      caseMode  = Case.AS_IS;

    // Thread‑safe undo map
    private final Map<String, String> undoMap = new ConcurrentHashMap<>();

    // ── Settings ──────────────────────────────────────────────────────────────

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

    // ── Preview ───────────────────────────────────────────────────────────────

    public List<RenamePreview> preview(List<MediaFile> files) {
        List<RenamePreview> previews = new ArrayList<>();
        List<String> usedNames = new ArrayList<>();

        for (MediaFile file : files) {
            if (file.getTags().isEmpty()) continue;

            String newName   = buildName(file);
            String extension = getExtension(file.getName());
            String fullNew   = newName + extension;

            // If the new name is exactly the original name, skip and mark as no conflict
            if (fullNew.equals(file.getName())) {
                previews.add(new RenamePreview(file.getPath(), file.getName(), fullNew, false));
                continue;
            }

            boolean conflict = usedNames.contains(fullNew) || targetExists(file, fullNew);
            if (!conflict) usedNames.add(fullNew);

            previews.add(new RenamePreview(
                    file.getPath(),
                    file.getName(),
                    fullNew,
                    conflict));
        }

        return previews;
    }

    // Helper to safely check if a target file exists
    private boolean targetExists(MediaFile file, String newName) {
        try {
            File parent = new File(file.getPath()).getParentFile();
            if (parent == null) return false;
            return new File(parent, newName).exists();
        } catch (SecurityException e) {
            // If we can’t read the directory, treat it as a conflict to be safe
            return true;
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    public RenameResult apply(List<RenamePreview> previews) {
        RenameResult result = new RenameResult();
        undoMap.clear();

        for (RenamePreview p : previews) {
            if (p.hasConflict) {
                result.failed++;
                result.errors.add("Conflict: " + p.newName);
                continue;
            }

            // No‑op if the name wouldn’t change
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

    // ── Undo ──────────────────────────────────────────────────────────────────

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

    // ── Name builder ──────────────────────────────────────────────────────────

    private String buildName(MediaFile file) {
        String sep      = getSeparatorChar();
        String original = stripExtension(file.getName());
        List<String> tags = file.getTags();

        String tagPart = String.join(sep, tags);

        String name;
        switch (order) {
            case ORIGINAL_THEN_TAGS:
                name = original + (sep.isEmpty() ? "" : sep) + tagPart;
                break;
            case TAGS_THEN_ORIGINAL:
                name = tagPart + (sep.isEmpty() ? "" : sep) + original;
                break;
            default: // TAGS_ONLY
                name = tagPart;
                break;
        }

        switch (caseMode) {
            case LOWERCASE: return name.toLowerCase();
            case UPPERCASE: return name.toUpperCase();
            default:        return name;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }
}
