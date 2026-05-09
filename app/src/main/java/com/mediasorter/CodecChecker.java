package com.mediasorter;

import com.mediasorter.models.MediaFile;

public class CodecChecker {

    public enum Support { FULL, PARTIAL, NONE }

    public static Support check(MediaFile file) {
        String name  = file.getName().toLowerCase();
        MediaFile.Type type = file.getType();

        switch (type) {
            case IMAGE:  return checkImage(name);
            case VIDEO:  return checkVideo(name);
            case AUDIO:  return checkAudio(name);
            default:     return Support.NONE;
        }
    }

    private static Support checkImage(String name) {
        if (name.matches(".*\\.(jpg|jpeg|png|bmp|webp)")) return Support.FULL;
        if (name.matches(".*\\.gif"))                      return Support.PARTIAL; // static only
        if (name.matches(".*\\.(heic|heif|avif|svg|raw|cr2|nef|arw)")) return Support.NONE;
        return Support.NONE;
    }

    private static Support checkVideo(String name) {
        if (name.matches(".*\\.(mp4|3gp)"))             return Support.FULL;
        if (name.matches(".*\\.(avi|mov)"))             return Support.PARTIAL;
        if (name.matches(".*\\.(mkv|webm)"))            return Support.PARTIAL;
        if (name.matches(".*\\.(hevc|h265|av1|vp9)"))  return Support.NONE;
        return Support.PARTIAL;
    }

    private static Support checkAudio(String name) {
        if (name.matches(".*\\.(mp3|aac|wav|ogg|flac|m4a)")) return Support.FULL;
        if (name.matches(".*\\.(opus)"))                      return Support.PARTIAL;
        if (name.matches(".*\\.(alac|dts|ac3|eac3|wma)"))    return Support.NONE;
        return Support.NONE;
    }

    public static String getUnsupportedReason(MediaFile file) {
        String name = file.getName().toLowerCase();
        if (name.matches(".*\\.(heic|heif)"))   return "HEIF/HEIC not supported on Android 5";
        if (name.matches(".*\\.(avif)"))         return "AVIF not supported on this device";
        if (name.matches(".*\\.(hevc|h265)"))    return "H.265/HEVC requires Android 5+ with hardware support";
        if (name.matches(".*\\.(av1)"))          return "AV1 not supported on MSM8926";
        if (name.matches(".*\\.(alac)"))         return "ALAC not supported on Android 5";
        if (name.matches(".*\\.(dts|ac3|eac3)")) return "Dolby/DTS audio not supported";
        if (name.matches(".*\\.(opus)"))         return "OPUS support unreliable on this device";
        if (name.matches(".*\\.(svg)"))          return "SVG not supported as media preview";
        return "Format not supported for preview on this device";
    }
}
