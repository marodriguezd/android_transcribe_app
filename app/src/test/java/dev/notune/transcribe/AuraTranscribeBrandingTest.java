package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM unit test validating Aura Transcribe core branding, constants,
 * audio routing modes, and AI provider configurations.
 */
public class AuraTranscribeBrandingTest {

    @Test
    public void testAudioInputModesConstants() {
        assertEquals("auto", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals("bluetooth", AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY);
        assertEquals("builtin", AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
    }

    @Test
    public void testPostProcessorProvidersIntegrity() {
        assertNotNull(SettingsManager.PROVIDERS);
        assertTrue(SettingsManager.PROVIDERS.length >= 4);

        // Verify Local S1 provider preset
        SettingsManager.Provider localS1 = SettingsManager.providerById(SettingsManager.PROVIDER_LOCAL_S1);
        assertNotNull(localS1);
        assertEquals(SettingsManager.PROVIDER_LOCAL_S1, localS1.id);
        assertEquals("s1-mini-q4_k_m.gguf", localS1.defaultModel);

        // Verify Groq provider preset
        SettingsManager.Provider groq = SettingsManager.providerById("groq");
        assertNotNull(groq);
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl);
    }

    @Test
    public void testPostProcessorPresets() {
        assertEquals("clean", SettingsManager.PRESET_CLEAN);
        assertEquals("formal", SettingsManager.PRESET_FORMAL);
        assertEquals("casual", SettingsManager.PRESET_CASUAL);
        assertEquals("verbatim", SettingsManager.PRESET_VERBATIM);
    }
}
