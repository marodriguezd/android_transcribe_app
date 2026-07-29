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
        return MarkerFileHelper.readInt(ctx, FILE_NAME, DEFAULT_MAX_LINES);
    }

    public static void setMaxLines(Context ctx, int lines) {
        MarkerFileHelper.writeInt(ctx, FILE_NAME, lines);
    }

    private static final String OVERLAY_Y_FILE = "subtitle_overlay_y";
    /** Default: a small margin above the bottom edge. */
    public static final int DEFAULT_OVERLAY_Y = 100;

    /** Offset of the overlay above the screen bottom, set by dragging it. */
    public static int getOverlayY(Context ctx) {
        return MarkerFileHelper.readInt(ctx, OVERLAY_Y_FILE, DEFAULT_OVERLAY_Y);
    }

    public static void setOverlayY(Context ctx, int y) {
        MarkerFileHelper.writeInt(ctx, OVERLAY_Y_FILE, y);
    }
}
