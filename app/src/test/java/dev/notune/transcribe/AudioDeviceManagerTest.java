package dev.notune.transcribe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM test suite for {@link AudioDeviceManager} modes and marker persistence.
 * Exercises constants, 3-way input routing mode persistence, and null-safety contracts
 * without requiring Android device/framework emulation.
 */
public class AudioDeviceManagerTest {

    private File tempDirectory;

    @Before
    public void setUp() throws IOException {
        tempDirectory = Files.createTempDirectory("transcribe-audio-test").toFile();
    }

    @After
    public void tearDown() {
        if (tempDirectory != null && tempDirectory.exists()) {
            File[] files = tempDirectory.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tempDirectory.delete();
        }
    }

    @Test
    public void testMicModeConstants() {
        assertEquals("auto", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals("bluetooth", AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY);
        assertEquals("builtin", AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
    }

    @Test
    public void testMicModeDefaultToAutoWhenMissing() {
        String mode = MarkerFileHelper.readStringFromFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals(AudioDeviceManager.MIC_MODE_AUTO, mode);
    }

    @Test
    public void testMicModePersistenceRoundTrip() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY);
        String mode = MarkerFileHelper.readStringFromFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals(AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY, mode);

        MarkerFileHelper.writeStringToFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
        mode = MarkerFileHelper.readStringFromFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals(AudioDeviceManager.MIC_MODE_BUILTIN_ONLY, mode);

        MarkerFileHelper.writeStringToFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        mode = MarkerFileHelper.readStringFromFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals(AudioDeviceManager.MIC_MODE_AUTO, mode);
    }

    @Test
    public void testAcquireAndReleaseMicrophoneNullSafety() {
        // Must never throw with null Context across all preference modes
        AudioDeviceManager.acquireMicrophone(null, AudioDeviceManager.MIC_MODE_AUTO);
        AudioDeviceManager.acquireMicrophone(null, AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY);
        AudioDeviceManager.acquireMicrophone(null, AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
        AudioDeviceManager.acquireMicrophone(null, "unknown_mode");
        AudioDeviceManager.acquireMicrophone(null, null);

        // Multiple sequential and redundant releases must never throw
        AudioDeviceManager.releaseMicrophone(null);
        AudioDeviceManager.releaseMicrophone(null);
        assertFalse(AudioDeviceManager.isRoutingActive());
    }

    @Test
    public void testBluetoothQueryNullSafety() {
        assertFalse(AudioDeviceManager.isBluetoothConnected(null));
        List<String> devices = AudioDeviceManager.getConnectedInputDevices(null);
        assertNotNull(devices);
        assertTrue(devices.isEmpty());
    }

    @Test
    public void testMicModeFallbackOnEmptyOrCorruptString() {
        MarkerFileHelper.writeStringToFile(tempDirectory, "mic_mode", "   ");
        String mode = MarkerFileHelper.readStringFromFile(tempDirectory, "mic_mode", AudioDeviceManager.MIC_MODE_AUTO);
        // Trims to empty string; callers (like sm.getMicMode()) normalize to AUTO
        assertTrue(mode.isEmpty() || AudioDeviceManager.MIC_MODE_AUTO.equals(mode));
    }
}
