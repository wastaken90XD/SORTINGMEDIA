package com.mediasorter;

import com.mediasorter.models.MediaFile;

public class CodecChecker {

    public enum Support { FULL, PARTIAL, NONE }

    // Pre-split extension tables — String.matches() compiled a regex on every
    // call, which was measurable over thousands of files on a low-end device.
    private static final String[] IMG_FULL    = {"jpg", "jpeg", "png", "bmp", "webp"};
    private static final String[] IMG_GIF     = {"gif"};
    private static final String[] IMG_NONE    = {"heic", "heif", "avif", "svg", "raw", "cr2", "nef", "arw"};

    private static final String[] VID_FULL    = {"mp4", "3gp"};
    private static final String[] VID_PARTIAL = {"avi", "mov"};
    private static final String[] VID_PARTIAL2 = {"mkv", "webm"};
    private static final String[] VID_NONE    = {"hevc", "h265", "av1", "vp9"};

    private static final String[] AUD_FULL    = {"mp3", "aac", "wav", "ogg", "flac", "m4a"};
    private static final String[] AUD_PARTIAL = {"opus"};
    private static final String[] AUD_NONE    = {"alac", "dts", "ac3", "eac3", "wma"};

    public static Support check(MediaFile file) {
        String name  = file.getName().toLowerCase(java.util.Locale.US);
        MediaFile.Type type = file.getType();

        switch (type) {
            case IMAGE:  return checkImage(name);
            case VIDEO:  return checkVideo(name);
            default:     return Support.NONE;
        }
    }

    private static boolean hasExt(String lowerName, String[] exts) {
        return MediaFile.hasExtension(lowerName, exts);
    }

    private static Support checkImage(String name) {
        if (hasExt(name, IMG_FULL))    return Support.FULL;
        if (hasExt(name, IMG_GIF))     return Support.PARTIAL; // static only
        if (hasExt(name, IMG_NONE))    return Support.NONE;
        return Support.NONE;
    }

    private static Support checkVideo(String name) {
        if (hasExt(name, VID_FULL))     return Support.FULL;
        if (hasExt(name, VID_PARTIAL))  return Support.PARTIAL;
        if (hasExt(name, VID_PARTIAL2)) return Support.PARTIAL;
        if (hasExt(name, VID_NONE))     return Support.NONE;
        return Support.PARTIAL;
    }

    private static Support checkAudio(String name) {
        if (hasExt(name, AUD_FULL))    return Support.FULL;
        if (hasExt(name, AUD_PARTIAL)) return Support.PARTIAL;
        if (hasExt(name, AUD_NONE))    return Support.NONE;
        return Support.NONE;
    }

    public static String getUnsupportedReason(MediaFile file) {
        String name = file.getName().toLowerCase(java.util.Locale.US);
        if (hasExt(name, new String[]{"heic", "heif"}))        return "HEIF/HEIC not supported on Android 5";
        if (hasExt(name, new String[]{"avif"}))                return "AVIF not supported on this device";
        if (hasExt(name, new String[]{"hevc", "h265"}))        return "H.265/HEVC requires Android 5+ with hardware support";
        if (hasExt(name, new String[]{"av1"}))                 return "AV1 not supported on MSM8926";
        if (hasExt(name, new String[]{"alac"}))                return "ALAC not supported on Android 5";
        if (hasExt(name, new String[]{"dts", "ac3", "eac3"}))  return "Dolby/DTS audio not supported";
        if (hasExt(name, new String[]{"opus"}))                return "OPUS support unreliable on this device";
        if (hasExt(name, new String[]{"svg"}))                 return "SVG not supported as media preview";
        return "Format not supported for preview on this device";
    }
}
