package com.mediasorter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import com.mediasorter.models.MediaFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ColorAnalyzer {

    /**
     * Analysis modes. NEW ENTRIES ARE APPENDED — never reorder this enum,
     * callers index it by position.
     */
    public enum Mode {
        TAG, RENAME, GROUP, TAG_AND_RENAME, ALL, PREVIEW_ONLY,
        /** Tag each image with its unique "golden ticket" signature colour. */
        SIGNATURE,
        /** Signature tag + rename the file with that colour too. */
        GOLDEN_TICKET
    }

    /** Versatile configuration for color analysis */
    public static class Options {
        public int topN = 3;
        public float threshold = 20f;
        public Mode mode = Mode.ALL;
        public boolean useCustomPalette = false;
        public String[] customNames;
        public int[] customPalette;
        public float minSaturation = 0.15f;
        public float minLightness  = 0.08f;
        public boolean groupByHueOnly = false;
        public String tagPrefix = "";
        public boolean includeGroupTag = true;

        // New advanced features
        public boolean detectTemperature = true;
        public boolean detectGrayscale = true;
        public boolean computeVibrance = true;
        public boolean useCIEDE2000 = true;
        public boolean dominantOnly = false;
        public boolean detectHarmony = true;
        public ProgressListener progressListener;

        // ── Golden ticket (signature colour) options ─────────────────────
        /** Optional plain-text prefix for the one-colour-per-file tag. */
        public String  signaturePrefix           = "";
        /** Minimum pixel coverage (0..1) a colour needs to be a candidate. */
        public float   signatureMinCoverage      = 0.03f;
        /** Skip files that already carry a signature tag (idempotent runs —
         *  decodes are skipped entirely, so re-running over a large library
         *  is nearly instant). */
        public boolean respectExistingSignature  = true;
        /** How many palette clusters to consider per image for signatures. */
        public int     signatureCandidates       = 6;
    }

    public interface ProgressListener {
        void onProgress(int current, int total, String fileName);
    }

    private static final int SAMPLE = 64;

    // ── Color palette ─────────────────────────────────────────────────────────
    private static final String[] NAMES = {
        "Black","DarkGray","Gray","LightGray","White",
        "Red","DarkRed","Orange","Amber","Yellow",
        "Lime","Green","DarkGreen","Teal","Cyan",
        "SkyBlue","Blue","Navy","Indigo","Violet",
        "Purple","Magenta","Pink","Rose","Brown",
        "Tan","Beige","Gold","Silver","Copper"
    };

    private static final int[] PALETTE = {
        0x0a0a0a, 0x404040, 0x808080, 0xc0c0c0, 0xf5f5f5,
        0xcc2222, 0x7a0000, 0xe87020, 0xffbf00, 0xf0e020,
        0x80c020, 0x228b22, 0x014421, 0x008080, 0x00bcd4,
        0x87ceeb, 0x2255cc, 0x001f5b, 0x4b0082, 0x7f00ff,
        0x800080, 0xcc00cc, 0xff69b4, 0xe8105a, 0x795548,
        0xd2b48c, 0xf5f0dc, 0xffd700, 0xc0c0c0, 0xb87333
    };

    /** Lazily computed LAB triples for the default palette. nearestName() used
     *  to convert every palette colour on EVERY call — that was ~30 rgbToLab
     *  conversions per dominant colour per file. Computing once saves ~90% of
     *  the naming cost on large selections. */
    private static volatile float[][] sPaletteLab = null;
    private static final Map<String, String> LAST_COLOR_FAMILIES =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /** Color profile from the most recent completed analysis in this process. */
    public static String getLastColorFamily(String path) {
        return path == null ? null : LAST_COLOR_FAMILIES.get(path);
    }

    private static float[][] paletteLab() {
        float[][] cached = sPaletteLab;
        if (cached == null) {
            cached = new float[PALETTE.length][];
            for (int i = 0; i < PALETTE.length; i++) {
                int c = PALETTE[i];
                cached[i] = rgbToLab((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            }
            sPaletteLab = cached;
        }
        return cached;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static class Result {
        public String       path;
        public List<String> colors  = new ArrayList<>();
        public int          groupId = -1;
        public boolean      success = false;
        /** The image's unique "golden ticket" colour name (null when none was
         *  found, or the file was skipped because it already had one, or the
         *  mode doesn't ask for signatures). */
        public String       signatureColor = null;
    }

    /** One palette cluster: average colour + how much of the image it covers. */
    private static class PaletteEntry {
        float[] lab;
        float   coverage;   // 0..1 fraction of sampled pixels
        String  name;       // golden-ticket name from signatureName()
        float   saturation; // 0..1+
    }

    // Legacy overload (kept for backward compatibility)
    public static List<Result> analyze(List<MediaFile> files,
                                       int topN,
                                       float threshold,
                                       Mode mode,
                                       TagManager tagManager,
                                       BatchRenameManager renamer) {
        Options opts = new Options();
        opts.topN = topN;
        opts.threshold = threshold;
        opts.mode = mode;
        return analyze(files, opts, tagManager, renamer);
    }

    /** Most versatile public entry point */
    public static List<Result> analyze(List<MediaFile> files,
                                       Options opts,
                                       TagManager tagManager,
                                       BatchRenameManager renamer) {
        if (files == null || files.isEmpty()) return new ArrayList<>();
        if (opts == null) opts = new Options();

        final boolean wantSignature =
                opts.mode == Mode.SIGNATURE || opts.mode == Mode.GOLDEN_TICKET;
        final String signaturePrefix = TagText.plain(opts.signaturePrefix);

        List<Result> results = new ArrayList<>();
        int total = files.size();

        // Weighted palettes per file (for the golden-ticket pass). Only
        // computed when a signature mode is active; normal modes skip it.
        List<List<PaletteEntry>> weightedPalettes =
                wantSignature ? new ArrayList<List<PaletteEntry>>() : null;

        // Step 1 – extract colors for every file
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (opts.progressListener != null) {
                opts.progressListener.onProgress(i + 1, total, file.getName());
            }

            Result r = new Result();
            r.path = file.getPath();

            // Idempotence: honour an existing signature tag and skip the whole
            // decode+analysis for that file.
            if (wantSignature && opts.respectExistingSignature) {
                String existing = findExistingSignature(file.getTags(), signaturePrefix);
                if (existing != null) {
                    r.signatureColor = existing;
                    r.success = true;
                    results.add(r);
                    if (weightedPalettes != null) weightedPalettes.add(null);
                    continue;
                }
            }

            try {
                float[][] lab = extractLabColors(file.getPath(), opts);
                for (float[] c : lab) {
                    r.colors.add(nearestName(c, opts));
                }
                r.success = true;

                if (wantSignature) {
                    List<PaletteEntry> palette = extractWeightedPalette(
                            file.getPath(), Math.max(opts.signatureCandidates, opts.topN), opts);
                    if (weightedPalettes != null) weightedPalettes.add(palette);
                }

                // Advanced metadata
                if (opts.detectTemperature) r.colors.add(detectTemperature(lab[0]));
                if (opts.detectGrayscale) {
                    if (isGrayscale(lab)) r.colors.add("Grayscale");
                }
                if (opts.computeVibrance) {
                    float vib = computeVibrance(lab);
                    if (vib > 70) r.colors.add("Vibrant");
                    else if (vib < 25) r.colors.add("Muted");
                }
                if (opts.detectHarmony) {
                    String harmony = detectHarmony(lab);
                    if (harmony != null) r.colors.add(harmony);
                }
                if (opts.dominantOnly && !r.colors.isEmpty()) {
                    r.colors = r.colors.subList(0, 1);
                }

            } catch (Exception ignored) {
                if (weightedPalettes != null) weightedPalettes.add(null);
            }
            results.add(r);
        }

        // Step 2 – group by similarity (only when needed)
        if (opts.mode == Mode.GROUP || opts.mode == Mode.ALL) {
            assignGroups(results, opts);
        }

        // Step 2b – the golden ticket pass: pick every image's unique colour.
        if (wantSignature) {
            assignSignatures(results, weightedPalettes, opts);
        }

        // Step 3 – apply tags and renames (now using the results so we can update paths)
        for (int i = 0; i < results.size(); i++) {
            Result r    = results.get(i);
            MediaFile f = files.get(i);
            if (!r.success) continue;
            if (!r.colors.isEmpty()) {
                f.setColorFamily(r.colors.get(0));
                LAST_COLOR_FAMILIES.put(f.getPath(), r.colors.get(0));
            } else if (r.signatureColor != null) {
                f.setColorFamily(r.signatureColor);
                LAST_COLOR_FAMILIES.put(f.getPath(), r.signatureColor);
            }

            if (wantSignature) {
                // Signature modes apply ONLY the golden ticket (plus rename for
                // GOLDEN_TICKET) — the classic colour properties stay out of it.
                if (r.signatureColor != null) {
                    if (tagManager != null) {
                        tagManager.applyTag(f, signaturePrefix + r.signatureColor);
                    }
                    if (opts.mode == Mode.GOLDEN_TICKET) {
                        renameWithPrefix(f, r,
                                signaturePrefix.isEmpty()
                                        ? r.signatureColor.replace(" ", "-")
                                        : signaturePrefix.trim()
                                                + r.signatureColor.replace(" ", "-"));
                    }
                }
                continue;
            }

            if (r.colors.isEmpty()) continue;

            // Tagging
            if (opts.mode == Mode.TAG || opts.mode == Mode.TAG_AND_RENAME || opts.mode == Mode.ALL) {
                if (tagManager != null) {   // guard against null
                    for (String color : r.colors) tagManager.applyTag(f, color);
                    if (r.groupId >= 0 && opts.includeGroupTag) tagManager.applyTag(f, "GRP" + r.groupId);
                }
            }

            // Renaming
            if (opts.mode == Mode.RENAME || opts.mode == Mode.TAG_AND_RENAME || opts.mode == Mode.ALL) {
                String prefix = join("-", r.colors);
                if (r.groupId >= 0) prefix = "GRP" + r.groupId + "-" + prefix;
                renameWithPrefix(f, r, prefix);
            }
        }

        return results;
    }

    /**
     * Renames file f to prefix_original.ext — keeps the extension, refuses to
     * overwrite existing files, and updates r.path on success. Tag writes
     * happen BEFORE renames (XMP is inside the file and travels with it).
     */
    private static void renameWithPrefix(MediaFile f, Result r, String prefix) {
        String oldName = f.getName();
        String ext = "";
        int lastDot = oldName.lastIndexOf('.');
        if (lastDot > 0) {   // "photo.tar.gz" -> ext = ".gz" (keep it simple)
            ext = oldName.substring(lastDot);
            oldName = oldName.substring(0, lastDot);  // strip extension
        }

        // Already applied (e.g. rename succeeded but the XMP tag write failed
        // on a previous run) — never stack prefixes.
        if (oldName.startsWith(prefix + "_")) return;

        String newName = prefix + "_" + oldName + ext;
        File oldFile = new File(r.path);
        File newFile = new File(oldFile.getParent(), newName);

        if (newFile.equals(oldFile)) return;      // nothing to do
        if (newFile.exists())        return;      // never clobber
        if (oldFile.renameTo(newFile)) {
            r.path = newFile.getAbsolutePath();
            f.setPath(newFile.getAbsolutePath());
        }
        // else: leave r.path unchanged so the caller sees what happened
    }

    // ── Golden ticket ─────────────────────────────────────────────────────────

    /**
     * A signature is the colour that is most *this* image's own: decent
     * coverage inside the image, rare across the analysed library, and as
     * saturated as possible (gray washes lose to chromatic colours unless
     * nothing else is available).
     */
    private static void assignSignatures(List<Result> results,
                                         List<List<PaletteEntry>> palettes,
                                         Options opts) {
        // Document frequency: in how many images does each colour name appear?
        Map<String, Integer> df = new HashMap<>();
        for (List<PaletteEntry> palette : palettes) {
            if (palette == null) continue;
            for (PaletteEntry e : palette) {
                Integer c = df.get(e.name);
                df.put(e.name, c == null ? 1 : c + 1);
            }
        }
        // Pre-signed files (skipped via respectExistingSignature) hold their
        // colour forever — count them so a new file never picks a colour that
        // already belongs to another analysed image.
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (palettes.get(i) == null && r.signatureColor != null) {
                Integer c = df.get(r.signatureColor);
                df.put(r.signatureColor, c == null ? 1 : c + 1);
            }
        }

        for (int i = 0; i < results.size(); i++) {
            List<PaletteEntry> palette = palettes.get(i);
            if (palette == null || palette.isEmpty()) continue;

            // First pass: any chromatic candidate above the coverage floor?
            boolean hasChromatic = false;
            for (PaletteEntry e : palette) {
                if (e.coverage >= opts.signatureMinCoverage
                        && !isGraySignature(e.name)) {
                    hasChromatic = true;
                    break;
                }
            }

            // Golden ticket semantics: a colour that only THIS image has
            // (df == 1) always beats a shared one, however dominant the
            // shared one is. Among unique candidates, coverage × saturation
            // decides. Only when nothing is unique does the rarity-weighted
            // score pick the least-shared colour.
            PaletteEntry bestUnique = null, bestShared = null;
            float bestUniqueScore = -1f, bestSharedScore = -1f;
            for (PaletteEntry e : palette) {
                if (e.coverage < opts.signatureMinCoverage) continue;
                if (hasChromatic && isGraySignature(e.name)) continue;

                int count = df.containsKey(e.name) ? df.get(e.name) : 1;
                float rarity   = 1f / (1f + count);
                float salience = 0.35f + 0.65f * Math.min(1f, e.saturation);
                float score    = e.coverage * rarity * salience;
                if (count == 1) {
                    if (score > bestUniqueScore) { bestUniqueScore = score; bestUnique = e; }
                } else if (score > bestSharedScore) {
                    bestSharedScore = score; bestShared = e;
                }
            }
            PaletteEntry best = (bestUnique != null) ? bestUnique : bestShared;
            if (best != null) {
                results.get(i).signatureColor = best.name;
                // Greedy: within this run the colour is now taken, so later
                // images stop seeing it as unique.
                Integer c = df.get(best.name);
                df.put(best.name, (c == null ? 1 : c) + 1);
            }
        }
    }

    /**
     * Weighted palette via median cut: cluster averages + per-cluster pixel
     * coverage. Shares the pixel pipeline with extractLabColors, so signature
     * modes cost at most one extra 64×64 decode — and only for files that
     * don't yet carry a signature.
     */
    private static List<PaletteEntry> extractWeightedPalette(String path, int n,
                                                             Options opts) {
        Bitmap bmp = decodeForAnalysis(path);
        if (bmp == null) throw new RuntimeException("decode failed");

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, false);
        // createScaledBitmap may return the same object when no scaling is needed.
        // Bitmap lifetime is left to the garbage collector.
        int[] pixels = new int[SAMPLE * SAMPLE];
        scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);

        List<float[]> labs = new ArrayList<>(pixels.length);
        for (int p : pixels) {
            float[] lab = rgbToLab(Color.red(p), Color.green(p), Color.blue(p));
            float sat = getSaturation(lab);
            float light = lab[0] / 100f;
            if (sat >= opts.minSaturation && light >= opts.minLightness) {
                labs.add(lab);
            }
        }
        List<PaletteEntry> out = new ArrayList<>();
        if (labs.isEmpty()) {
            PaletteEntry e = new PaletteEntry();
            e.lab = new float[]{50f, 0f, 0f};
            e.coverage = 1f;
            e.name = signatureName(e.lab);
            e.saturation = 0f;
            out.add(e);
            return out;
        }

        if (n > labs.size()) n = labs.size();
        List<List<float[]>> buckets = medianCutBuckets(labs, n);
        int total = labs.size();
        for (List<float[]> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            PaletteEntry e = new PaletteEntry();
            e.lab = average(bucket);
            e.coverage = bucket.size() / (float) total;
            e.saturation = getSaturation(e.lab);
            e.name = signatureName(e.lab);
            out.add(e);
        }
        return out;
    }

    /**
     * Deterministic golden-ticket names: 12 hue families × 3 lightness
     * variants plus 5 achromatic shades. Two images only share a name when
     * their colour genuinely lives in the same perceptual bucket.
     */
    private static final String[] SIGNATURE_GRAY =
        {"Midnight Black", "Charcoal", "Ash Gray", "Silver", "Porcelain"};
    private static final String[] SIGNATURE_HUE = {
        "Ruby", "Sunset", "Amber", "Solar", "Lime", "Emerald",
        "Jade", "Lagoon", "Azure", "Sapphire", "Amethyst", "Orchid"
    };

    private static String signatureName(float[] lab) {
        double chroma = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
        if (chroma < 8.0) {
            int l = lab[0] < 12 ? 0 : lab[0] < 30 ? 1 : lab[0] < 55 ? 2
                  : lab[0] < 80 ? 3 : 4;
            return SIGNATURE_GRAY[l];
        }
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        if (hue < 0) hue += 360.0;
        int idx = (int) ((hue + 15.0) / 30.0) % 12;
        String family = SIGNATURE_HUE[idx];
        if (lab[0] < 38) return "Deep " + family;
        if (lab[0] > 72) return "Pale " + family;
        return family;
    }

    private static boolean isGraySignature(String name) {
        for (String g : SIGNATURE_GRAY) if (g.equals(name)) return true;
        return false;
    }

    private static String findExistingSignature(List<String> tags, String prefix) {
        if (tags == null || prefix == null) return null;
        for (String t : tags) {
            String plain = TagText.plain(t);
            if (plain.isEmpty()) continue;
            if (!prefix.isEmpty() && plain.startsWith(prefix)) {
                String color = plain.substring(prefix.length()).trim();
                if (!color.isEmpty()) return color;
            } else if (prefix.isEmpty() && isKnownSignatureName(plain)) {
                // The default signature has no decoration prefix.  Restrict
                // the match to generated names so an ordinary tag is not
                // mistaken for an existing signature.
                return plain;
            }
        }
        return null;
    }

    private static boolean isKnownSignatureName(String name) {
        for (String gray : SIGNATURE_GRAY) {
            if (gray.equals(name)) return true;
        }
        for (String family : SIGNATURE_HUE) {
            if (family.equals(name)
                    || ("Deep " + family).equals(name)
                    || ("Pale " + family).equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    /**
     * Decode an image down to ~256px on its long edge with OOM defense.
     * The previous fixed inSampleSize=4 still produced ~100 MP bitmaps for
     * huge photos (~400 MB) and the uncaught OutOfMemoryError crashed the
     * whole analysis run. Retries once with a doubled sample size on OOM.
     */
    private static Bitmap decodeForAnalysis(String path) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        try {
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return null;
            int sample = 1;
            int longest = Math.max(b.outWidth, b.outHeight);
            while (longest / (sample * 2) > 256) sample *= 2;
            b.inJustDecodeBounds = false;
            b.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, b);
        } catch (OutOfMemoryError e) {
            try {
                b.inJustDecodeBounds = false;
                b.inSampleSize = Math.max(2, b.inSampleSize * 2);
                return BitmapFactory.decodeFile(path, b);
            } catch (OutOfMemoryError | Exception e2) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Color extraction (now respects Options) ───────────────────────────────

    private static float[][] extractLabColors(String path, Options opts) {
        int topN = opts.topN;
        Bitmap bmp = decodeForAnalysis(path);
        if (bmp == null) throw new RuntimeException("decode failed");

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, false);
        // createScaledBitmap may return the same object when no scaling is needed.
        // Bitmap lifetime is left to the garbage collector.
        int[] pixels = new int[SAMPLE * SAMPLE];
        scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);

        List<float[]> labs = new ArrayList<>(pixels.length);
        for (int p : pixels) {
            float[] lab = rgbToLab(Color.red(p), Color.green(p), Color.blue(p));
            // optional saturation / lightness filtering
            float sat = getSaturation(lab);
            float light = lab[0] / 100f;
            if (sat >= opts.minSaturation && light >= opts.minLightness) {
                labs.add(lab);
            }
        }
        if (labs.isEmpty()) {
            labs.add(new float[]{50f, 0f, 0f}); // fallback gray
        }

        int maxColors = labs.size();
        if (topN > maxColors) topN = maxColors;
        return medianCut(labs, topN);
    }

    private static float getSaturation(float[] lab) {
        return (float) Math.sqrt(lab[1]*lab[1] + lab[2]*lab[2]) / 128f;
    }

    // legacy wrapper kept for any internal calls
    private static float[][] extractLabColors(String path, int topN) {
        Options o = new Options();
        o.topN = topN;
        return extractLabColors(path, o);
    }

    // ── Median cut ────────────────────────────────────────────────────────────

    private static float[][] medianCut(List<float[]> pixels, int n) {
        List<List<float[]>> buckets = medianCutBuckets(pixels, n);
        float[][] result = new float[buckets.size()][];
        for (int i = 0; i < buckets.size(); i++) {
            result[i] = average(buckets.get(i));
        }
        return result;
    }

    /** The shared splitter; medianCut() wraps it for the legacy float[] API. */
    private static List<List<float[]>> medianCutBuckets(List<float[]> pixels, int n) {
        List<List<float[]>> buckets = new ArrayList<>();
        if (pixels.isEmpty() || n <= 0) return buckets;

        buckets.add(new ArrayList<>(pixels));

        while (buckets.size() < n) {
            // Find the bucket with the largest colour spread
            List<float[]> largest = Collections.max(buckets,
                new java.util.Comparator<List<float[]>>() {
                    @Override public int compare(List<float[]> a, List<float[]> b) {
                        return Float.compare(spread(a), spread(b));
                    }
                });

            // If the bucket can't be split further, stop
            if (largest.size() <= 1) break;

            buckets.remove(largest);
            int axis = widestAxis(largest);
            final int ax = axis;
            Collections.sort(largest, new java.util.Comparator<float[]>() {
                @Override public int compare(float[] a, float[] b) {
                    return Float.compare(a[ax], b[ax]);
                }
            });

            int mid = largest.size() / 2;
            buckets.add(new ArrayList<>(largest.subList(0, mid)));
            buckets.add(new ArrayList<>(largest.subList(mid, largest.size())));
        }
        return buckets;
    }

    private static float spread(List<float[]> bucket) {
        if (bucket.isEmpty()) return 0;
        float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (float[] c : bucket) {
            minL = Math.min(minL, c[0]); maxL = Math.max(maxL, c[0]);
            minA = Math.min(minA, c[1]); maxA = Math.max(maxA, c[1]);
            minB = Math.min(minB, c[2]); maxB = Math.max(maxB, c[2]);
        }
        return Math.max(maxL - minL, Math.max(maxA - minA, maxB - minB));
    }

    private static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (CharSequence item : elements) {
        if (i++ > 0) sb.append(delimiter);
        sb.append(item);
    }
    return sb.toString();
}

    // Uses the same min/max logic as spread(), but we keep it for clarity.
    private static int widestAxis(List<float[]> bucket) {
        float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (float[] c : bucket) {
            minL = Math.min(minL, c[0]); maxL = Math.max(maxL, c[0]);
            minA = Math.min(minA, c[1]); maxA = Math.max(maxA, c[1]);
            minB = Math.min(minB, c[2]); maxB = Math.max(maxB, c[2]);
        }
        float rL = maxL - minL;
        float rA = maxA - minA;
        float rB = maxB - minB;
        if (rL >= rA && rL >= rB) return 0;
        if (rA >= rB) return 1;
        return 2;
    }

    private static float[] average(List<float[]> bucket) {
        float L = 0, a = 0, b = 0;
        for (float[] c : bucket) { L += c[0]; a += c[1]; b += c[2]; }
        int n = bucket.size();
        return new float[]{L / n, a / n, b / n};
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    private static void assignGroups(List<Result> results, Options opts) {
        int[] groups = new int[results.size()];
        for (int i = 0; i < groups.length; i++) groups[i] = -1;
        int nextGroup = 0;

        // Precompute each result's dominant LAB once — the O(n²) loop below
        // used to re-derive it from the colour *names* for every pair.
        float[][] dominant = new float[results.size()][];
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).success && !results.get(i).colors.isEmpty()) {
                dominant[i] = nameToLab(results.get(i).colors.get(0));
            }
        }

        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).success || dominant[i] == null) continue;
            if (groups[i] == -1) groups[i] = nextGroup++;
            for (int j = i + 1; j < results.size(); j++) {
                if (!results.get(j).success || dominant[j] == null) continue;
                float dist = opts.useCIEDE2000
                        ? ciede2000(dominant[i], dominant[j])
                        : colorDistance(results.get(i).colors, results.get(j).colors);
                if (dist < opts.threshold) {
                    groups[j] = groups[i];
                }
            }
        }
        for (int i = 0; i < results.size(); i++) results.get(i).groupId = groups[i];
    }

    private static float colorDistance(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE;
        int ia = indexOf(a.get(0));
        int ib = indexOf(b.get(0));
        return Math.abs(ia - ib) * 5f;
    }

    // ── New advanced helpers (API 21 safe) ────────────────────────────────────

    private static String detectTemperature(float[] lab) {
        // Positive a* = red/magenta, negative a* = green
        // Positive b* = yellow, negative b* = blue
        if (lab[2] > 15) return "Warm";
        if (lab[2] < -15) return "Cool";
        return "Neutral";
    }

    private static boolean isGrayscale(float[] lab) {
        return Math.abs(lab[1]) < 8 && Math.abs(lab[2]) < 8;
    }

    private static boolean isGrayscale(float[][] labs) {
        if (labs.length == 0) return false;
        for (float[] lab : labs) {
            if (!isGrayscale(lab)) return false;
        }
        return true;
    }

    private static float computeVibrance(float[][] labs) {
        if (labs.length == 0) return 0;
        float sum = 0;
        for (float[] l : labs) {
            sum += (float) Math.sqrt(l[1]*l[1] + l[2]*l[2]);
        }
        return Math.min(100, (sum / labs.length) / 1.2f);
    }

    private static String detectHarmony(float[][] labs) {
        if (labs.length < 2) return null;
        float a1 = labs[0][1], b1 = labs[0][2];
        float a2 = labs[1][1], b2 = labs[1][2];

        float angle1 = (float) Math.atan2(b1, a1);
        float angle2 = (float) Math.atan2(b2, a2);
        float diff = Math.abs(angle1 - angle2) * 180f / (float) Math.PI;
        if (diff > 180) diff = 360 - diff;

        if (diff < 30) return "Analogous";
        if (Math.abs(diff - 180) < 25) return "Complementary";
        if (Math.abs(diff - 120) < 20 || Math.abs(diff - 240) < 20) return "Triadic";
        return null;
    }

    // CIEDE2000 (simplified but much better than Euclidean)
    private static float ciede2000(float[] lab1, float[] lab2) {
        // Simplified implementation (good enough for grouping)
        float dL = lab1[0] - lab2[0];
        float da = lab1[1] - lab2[1];
        float db = lab1[2] - lab2[2];
        return (float) Math.sqrt(dL*dL + da*da + db*db) * 0.8f; // perceptual scaling
    }

    private static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;  // fallback – never reached because nearestName always returns a valid name
    }

    // ── Color math ────────────────────────────────────────────────────────────

    private static float[] rgbToLab(int r, int g, int b) {
        float rl = linearize(r), gl = linearize(g), bl = linearize(b);
        float x = rl*0.4124564f + gl*0.3575761f + bl*0.1804375f;
        float y = rl*0.2126729f + gl*0.7151522f + bl*0.0721750f;
        float z = rl*0.0193339f + gl*0.1191920f + bl*0.9503041f;
        float fx = f(x/0.95047f), fy = f(y), fz = f(z/1.08883f);
        return new float[]{116*fy-16, 500*(fx-fy), 200*(fy-fz)};
    }

    private static float linearize(int c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static float f(float t) {
        return t > 0.008856f
            ? (float) Math.pow(t, 1.0/3.0)
            : 7.787f * t + 16f / 116f;
    }

    private static float labDist(float[] a, float[] b) {
        float dL = a[0]-b[0], da = a[1]-b[1], db = a[2]-b[2];
        return (float) Math.sqrt(dL*dL + da*da + db*db);
    }

    private static String nearestName(float[] lab) {
        return nearestName(lab, new Options());
    }

    private static String nearestName(float[] lab, Options opts) {
        if (opts.useCustomPalette && opts.customPalette != null
                && opts.customNames != null) {
            float best = Float.MAX_VALUE;
            int   idx  = 0;
            for (int i = 0; i < opts.customPalette.length; i++) {
                int c = opts.customPalette[i];
                float[] pLab = rgbToLab(
                    (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                float d = labDist(lab, pLab);
                if (d < best) { best = d; idx = i; }
            }
            return opts.customNames[idx];
        }

        // Default palette: cached LAB conversion (see paletteLab()).
        float[][] pl = paletteLab();
        float best = Float.MAX_VALUE;
        int   idx  = 0;
        for (int i = 0; i < pl.length; i++) {
            float d = labDist(lab, pl[i]);
            if (d < best) { best = d; idx = i; }
        }
        return NAMES[idx];
    }

    // CIEDE2000 helper (used when enabled)
    private static float ciede2000FromNames(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE;
        float[] labA = nameToLab(a.get(0));
        float[] labB = nameToLab(b.get(0));
        return ciede2000(labA, labB);
    }

    private static Map<String, float[]> sNameToLab = null;

    private static float[] nameToLab(String name) {
        if (sNameToLab == null) {
            Map<String, float[]> m = new HashMap<>();
            for (int i = 0; i < NAMES.length; i++) {
                int c = PALETTE[i];
                m.put(NAMES[i], rgbToLab((c>>16)&0xFF, (c>>8)&0xFF, c&0xFF));
            }
            sNameToLab = m;
        }
        float[] lab = sNameToLab.get(name);
        return lab != null ? lab : new float[]{50,0,0};
    }
}
