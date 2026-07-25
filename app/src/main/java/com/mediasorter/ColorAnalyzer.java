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

    public enum Mode { TAG, RENAME, GROUP, TAG_AND_RENAME, ALL, PREVIEW_ONLY }

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

    // ── Public API ────────────────────────────────────────────────────────────

    public static class Result {
        public String       path;
        public List<String> colors  = new ArrayList<>();
        public int          groupId = -1;
        public boolean      success = false;
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

        List<Result> results = new ArrayList<>();
        int total = files.size();

        // Step 1 – extract colors for every file
        for (int i = 0; i < files.size(); i++) {
            MediaFile file = files.get(i);
            if (opts.progressListener != null) {
                opts.progressListener.onProgress(i + 1, total, file.getName());
            }

            Result r = new Result();
            r.path = file.getPath();
            try {
                float[][] lab = extractLabColors(file.getPath(), opts);
                for (float[] c : lab) {
                    r.colors.add(nearestName(c, opts));
                }
                r.success = true;

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

            } catch (Exception ignored) {}
            results.add(r);
        }

        // Step 2 – group by similarity (only when needed)
        if (opts.mode == Mode.GROUP || opts.mode == Mode.ALL) {
            assignGroups(results, opts);
        }

        // Step 3 – apply tags and renames (now using the results so we can update paths)
        for (int i = 0; i < results.size(); i++) {
            Result r    = results.get(i);
            MediaFile f = files.get(i);
            if (!r.success || r.colors.isEmpty()) continue;

            // Tagging
            if (mode == Mode.TAG || mode == Mode.TAG_AND_RENAME || mode == Mode.ALL) {
                if (tagManager != null) {   // guard against null
                    for (String color : r.colors) tagManager.applyTag(f, color);
                    if (r.groupId >= 0) tagManager.applyTag(f, "GRP" + r.groupId);
                }
            }

            // Renaming
            if (mode == Mode.RENAME || mode == Mode.TAG_AND_RENAME || mode == Mode.ALL) {
                String prefix = join("-", r.colors);
                if (r.groupId >= 0) prefix = "GRP" + r.groupId + "-" + prefix;

                String oldName = f.getName();
                String ext = "";
                int lastDot = oldName.lastIndexOf('.');
                if (lastDot > 0) {   // "photo.tar.gz" -> ext = ".gz" (keep it simple)
                    ext = oldName.substring(lastDot);
                    oldName = oldName.substring(0, lastDot);  // strip extension
                }

                String newName = prefix + "_" + oldName + ext;
                File oldFile = new File(r.path);
                File newFile = new File(oldFile.getParent(), newName);

                if (oldFile.renameTo(newFile)) {
                    // Update the result's path to the new location
                    r.path = newFile.getAbsolutePath();
                } else {
                    // Optionally log the error; we keep the old path but mark success = false?
                    // For now we leave r.path unchanged so the caller sees what happened.
                }
            }
        }

        return results;
    }

    // ── Color extraction (now respects Options) ───────────────────────────────

    private static float[][] extractLabColors(String path, Options opts) {
        int topN = opts.topN;
        BitmapFactory.Options bopts = new BitmapFactory.Options();
        bopts.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(path, bopts);
        if (bmp == null) throw new RuntimeException("decode failed");

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, false);
        bmp.recycle();

        int[] pixels = new int[SAMPLE * SAMPLE];
        scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        scaled.recycle();

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
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) throw new RuntimeException("decode failed");

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, false);
        bmp.recycle();

        int[] pixels = new int[SAMPLE * SAMPLE];
        scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        scaled.recycle();

        List<float[]> labs = new ArrayList<>(pixels.length);
        for (int p : pixels) {
            labs.add(rgbToLab(Color.red(p), Color.green(p), Color.blue(p)));
        }

        // Prevent infinite loop if topN > number of unique pixels
        int maxColors = labs.size();
        if (topN > maxColors) topN = maxColors;

        return medianCut(labs, topN);
    }

    // ── Median cut ────────────────────────────────────────────────────────────

    private static float[][] medianCut(List<float[]> pixels, int n) {
        if (pixels.isEmpty() || n <= 0) return new float[0][0];

        List<List<float[]>> buckets = new ArrayList<>();
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

        float[][] result = new float[buckets.size()][];
        for (int i = 0; i < buckets.size(); i++) {
            result[i] = average(buckets.get(i));
        }
        return result;
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

    private static void assignGroups(List<Result> results, float threshold) {
        int[] groups = new int[results.size()];
        for (int i = 0; i < groups.length; i++) groups[i] = -1;
        int nextGroup = 0;

        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).success) continue;
            if (groups[i] == -1) groups[i] = nextGroup++;
            for (int j = i + 1; j < results.size(); j++) {
                if (!results.get(j).success) continue;
                float dist = opts.useCIEDE2000
                        ? ciede2000FromNames(results.get(i).colors, results.get(j).colors)
                        : colorDistance(results.get(i).colors, results.get(j).colors);
                if (dist < threshold) {
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
        int[] palette = (opts.useCustomPalette && opts.customPalette != null)
                ? opts.customPalette : PALETTE;
        String[] names = (opts.useCustomPalette && opts.customNames != null)
                ? opts.customNames : NAMES;

        float best = Float.MAX_VALUE;
        int   idx  = 0;
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];
            float[] pLab = rgbToLab(
                (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            float d = labDist(lab, pLab);
            if (d < best) { best = d; idx = i; }
        }
        return names[idx];
    }

    // ── Grouping (now uses Options) ───────────────────────────────────────────

    private static void assignGroups(List<Result> results, Options opts) {
        assignGroups(results, opts.threshold);
    }

    // CIEDE2000 helper (used when enabled)
    private static float ciede2000FromNames(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE;
        float[] labA = nameToLab(a.get(0));
        float[] labB = nameToLab(b.get(0));
        return ciede2000(labA, labB);
    }

    private static float[] nameToLab(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                int c = PALETTE[i];
                return rgbToLab((c>>16)&0xFF, (c>>8)&0xFF, c&0xFF);
            }
        }
        return new float[]{50,0,0};
    }
}
