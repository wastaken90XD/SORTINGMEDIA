package com.mediasorter.features;
import java.util.*;
public class RandomGenerator {
    public static String tag() { return "tag_" + (int)(Math.random()*1e6); }
    public static String combo() { return "ab-ac-ad-xyz"; }
}
    public static String batchLabel() { return "batch_" + System.currentTimeMillis(); }
    public static String userTag(String input) { return input + "_rand"; }
