package com.mediasorter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import com.mediasorter.models.MediaFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ColorAnalyzer {

    public enum Mode { TAG, RENAME, GROUP, TAG_AND_RENAME, ALL }

    private static final int SAMPLE = 64; // decode to 64x64 for speed

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

    public static List<Result> analyze(List<MediaFile> files,
                                        int topN,
                                        float threshold,
                                        Mode mode,
                                        TagManager tagManager,
                                        BatchRenameManager renamer) {
        List<Result> results = new ArrayList<>();

        // Extract colors for all files
        for (MediaFile file : files) {
            Result r = new Result();
            r.path = file.getPath();
            try {
                float[][] lab = extractLabColors(file.getPath(), topN);
                for (float[] c : lab) {
                    r.colors.add(nearestName(c));
                }
                r.success = true;
            } catch (Exception ignored) {}
            results.add(r);
        }

        // Group by similarity
        if (mode == Mode.GROUP || mode == Mode.ALL) {
            assignGroups(results, threshold);
        }

        // Apply to files
        for (int i = 0; i < results.size(); i++) {
            Result r    = results.get(i);
            MediaFile f = files.get(i);
            if (!r.success || r.colors.isEmpty()) continue;

            if (mode == Mode.TAG || mode == Mode.TAG_AND_RENAME || mode == Mode.ALL) {
                for (String color : r.colors) tagManager.applyTag(f, color);
                if (r.groupId >= 0) tagManager.applyTag(f, "GRP" + r.groupId);
            }

            if (mode == Mode.RENAME || mode == Mode.TAG_AND_RENAME || mode == Mode.ALL) {
                String prefix = String.join("-", r.colors);
                if (r.groupId >= 0) prefix = "GRP" + r.groupId + "-" + prefix;
                String ext  = f.getName().contains(".")
                    ? f.getName().substring(f.getName().lastIndexOf("."))
                    : "";
                String newName = prefix + "_" + stripExt(f.getName()) + ext;
                new java.io.File(f.getPath()).renameTo(
                    new java.io.File(new java.io.File(f.getPath()).getParent(), newName));
            }
        }

        return results;
    }

    // ── Color extraction ──────────────────────────────────────────────────────

    private static float[][] extractLabColors(String path, int topN) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) throw new RuntimeException("decode failed");

        Bitmap scaled = Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, false);
        bmp.recycle();

        int[] pixels = new int[SAMPLE * SAMPLE];
        scaled.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        scaled.recycle();

        // Convert to Lab
        List<float[]> labs = new ArrayList<>();
        for (int p : pixels) {
            labs.add(rgbToLab(Color.red(p), Color.green(p), Color.blue(p)));
        }

        return medianCut(labs, topN);
    }

    // ── Median cut ────────────────────────────────────────────────────────────

    private static float[][] medianCut(List<float[]> pixels, int n) {
        List<List<float[]>> buckets = new ArrayList<>();
        buckets.add(new ArrayList<>(pixels));

        while (buckets.size() < n) {
            List<float[]> largest = Collections.max(buckets,
                (a, b) -> Float.compare(spread(a), spread(b)));
            buckets.remove(largest);
            int axis = widestAxis(largest);
            final int ax = axis;
            Collections.sort(largest, (a, b) -> Float.compare(a[ax], b[ax]));
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

    private static int widestAxis(List<float[]> bucket) {
        float rL = 0, rA = 0, rB = 0;
        float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
        float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
        float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
        for (float[] c : bucket) {
            minL = Math.min(minL, c[0]); maxL = Math.max(maxL, c[0]);
            minA = Math.min(minA, c[1]); maxA = Math.max(maxA, c[1]);
            minB = Math.min(minB, c[2]); maxB = Math.max(maxB, c[2]);
        }
        rL = maxL - minL; rA = maxA - minA; rB = maxB - minB;
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
                if (colorDistance(results.get(i).colors, results.get(j).colors) < threshold) {
                    groups[j] = groups[i];
                }
            }
        }
        for (int i = 0; i < results.size(); i++) results.get(i).groupId = groups[i];
    }

    private static float colorDistance(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE;
        // Compare first dominant color index distance
        int ia = indexOf(a.get(0));
        int ib = indexOf(b.get(0));
        return Math.abs(ia - ib) * 5f; // crude but fast
    }

    private static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return 0;
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
        float best = Float.MAX_VALUE;
        int   idx  = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            int c = PALETTE[i];
            float[] pLab = rgbToLab(
                (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            float d = labDist(lab, pLab);
            if (d < best) { best = d; idx = i; }
        }
        return NAMES[idx];
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
