package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Live-subtitle preferences, stored as a small file in filesDir like the other
 * settings (readable from any process without a content provider).
 */
public final class SubtitlePrefs {
    private static final String FILE_NAME = "subtitle_lines";
    /** Default: classic caption style, last two lines visible. */
    public static final int DEFAULT_MAX_LINES = 2;

    private SubtitlePrefs() {}

    /** Returns the line limit for the subtitle overlay; 0 means unlimited. */
    public static int getMaxLines(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return DEFAULT_MAX_LINES;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            return DEFAULT_MAX_LINES;
        }
    }

    public static void setMaxLines(Context ctx, int lines) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try {
            Files.write(f.toPath(), String.valueOf(lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
