package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The RTSP addresses of the cameras on Home (CameraPanel), kept in the launcher's own preferences. They are
 * typed in the panel's "Edit" dialog, so no address ever has to live in the repo or in config.xml.
 */
final class CameraStore {
    /** Four tiles (2 x 2) is what a TV with 1 GB of RAM and a couple of hardware decoders can play at once. */
    static final int MAX = 4;

    private static final String FILE = "cameras";
    private static final String KEY_URLS = "urls";

    private CameraStore() {
    }

    /** The saved addresses in order, never more than MAX, never blank. */
    static List<String> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return parse(prefs.getString(KEY_URLS, ""));
    }

    static void save(Context context, List<String> urls) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_URLS, join(clean(urls))).apply();
    }

    /** Blank lines go, surrounding spaces go, and only the first MAX stay. */
    static List<String> clean(List<String> urls) {
        List<String> out = new ArrayList<>();
        if (urls == null) return out;
        for (String url : urls) {
            if (url == null) continue;
            String trimmed = url.trim();
            if (trimmed.isEmpty()) continue;
            out.add(trimmed);
            if (out.size() == MAX) break;
        }
        return out;
    }

    static List<String> parse(String stored) {
        List<String> lines = new ArrayList<>();
        if (stored == null) return lines;
        for (String line : stored.split("\n")) lines.add(line);
        return clean(lines);
    }

    static String join(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(url);
        }
        return sb.toString();
    }
}
