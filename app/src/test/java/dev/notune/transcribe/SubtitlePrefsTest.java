package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class SubtitlePrefsTest {
    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-subtitle-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            for (File file : tempDirectory.listFiles()) {
                file.delete();
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void testDefaultSubtitleMaxLines() {
        int maxLines = MarkerFileHelper.readIntFromFile(tempDirectory, "subtitle_lines", SubtitlePrefs.DEFAULT_MAX_LINES);
        assertEquals(4, maxLines);
    }

    @Test
    public void testCustomSubtitleMaxLines() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "subtitle_lines", 4);
        int maxLines = MarkerFileHelper.readIntFromFile(tempDirectory, "subtitle_lines", SubtitlePrefs.DEFAULT_MAX_LINES);
        assertEquals(4, maxLines);
    }

    @Test
    public void testOverlayYPosition() {
        MarkerFileHelper.writeIntToFile(tempDirectory, "subtitle_overlay_y", 150);
        int y = MarkerFileHelper.readIntFromFile(tempDirectory, "subtitle_overlay_y", SubtitlePrefs.DEFAULT_OVERLAY_Y);
        assertEquals(150, y);
    }

    @Test
    public void testTranslationTargetDefaultsToAuto() {
        // Absent marker = "auto" (keep the original language; no translation).
        String target = MarkerFileHelper.readStringFromFile(
                tempDirectory, "subtitle_translation_target", SubtitleTranslationTargets.AUTO);
        assertEquals("auto", target);
    }

    @Test
    public void testTranslationTargetRoundTrip() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "subtitle_translation_target", "es-ES");
        String target = MarkerFileHelper.readStringFromFile(
                tempDirectory, "subtitle_translation_target", SubtitleTranslationTargets.AUTO);
        assertEquals("es-ES", target);
    }

    @Test
    public void testTranslationTargetBackToAutoDeletesFile() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "subtitle_translation_target", "es-ES");
        MarkerFileHelper.writeStringToFile(tempDirectory, "subtitle_translation_target", "");
        String target = MarkerFileHelper.readStringFromFile(
                tempDirectory, "subtitle_translation_target", SubtitleTranslationTargets.AUTO);
        assertEquals("auto", target);
    }
}
