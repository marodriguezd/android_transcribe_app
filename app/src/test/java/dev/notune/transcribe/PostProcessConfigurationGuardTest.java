package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM test suite for AI post-processing configuration guards.
 * Ensures AI post-processing cannot be enabled or executed when no valid API key
 * or local model is configured.
 */
public class PostProcessConfigurationGuardTest {

    static class TestSettings implements PostProcessor.PostProcessorSettings {
        boolean enabled = false;
        String provider = "groq";
        String apiKey = "";
        boolean localModelInstalled = false;

        @Override
        public boolean isPostProcessEnabled() {
            return enabled && isPostProcessConfigured();
        }

        @Override
        public String getEffectiveApiUrl() {
            return "https://api.groq.com/openai/v1";
        }

        @Override
        public String getApiKey() {
            return apiKey;
        }

        @Override
        public String getModelName() {
            return "llama-3.3-70b-versatile";
        }

        @Override
        public String getActivePromptBody() {
            return "";
        }

        @Override
        public String getProviderId() {
            return provider;
        }

        @Override
        public String getPostProcessPreset() {
            return "clean";
        }

        @Override
        public File getLocalS1ModelFile() {
            return new File("/dev/null");
        }

        @Override
        public boolean isLocalS1ModelInstalled() {
            return localModelInstalled;
        }
    }

    @Test
    public void testCloudProviderRequiresApiKey() {
        TestSettings settings = new TestSettings();
        settings.provider = "groq";
        settings.apiKey = "";
        assertFalse("Cloud provider without API key must not be configured", settings.isPostProcessConfigured());

        settings.apiKey = "   ";
        assertFalse("Cloud provider with whitespace API key must not be configured", settings.isPostProcessConfigured());

        settings.apiKey = "gsk_validKey123";
        assertTrue("Cloud provider with valid API key must be configured", settings.isPostProcessConfigured());
    }

    @Test
    public void testLocalProviderRequiresModelInstalled() {
        TestSettings settings = new TestSettings();
        settings.provider = SettingsManager.PROVIDER_LOCAL_S1;
        settings.localModelInstalled = false;
        assertFalse("Local provider without model installed must not be configured", settings.isPostProcessConfigured());

        settings.localModelInstalled = true;
        assertTrue("Local provider with model installed must be configured", settings.isPostProcessConfigured());
    }

    @Test
    public void testEnabledReturnsFalseWhenNotConfigured() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = "openai";
        settings.apiKey = "";

        assertFalse("Even if enabled is true, isPostProcessEnabled must return false when not configured",
                settings.isPostProcessEnabled());

        settings.apiKey = "sk-valid-key";
        assertTrue("isPostProcessEnabled returns true when enabled and key is present",
                settings.isPostProcessEnabled());
    }

    @Test
    public void testPostProcessorBypassesWhenNotConfigured() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = "groq";
        settings.apiKey = ""; // Missing API key

        PostProcessor processor = new PostProcessor(settings);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicBoolean errorCalled = new AtomicBoolean(false);

        processor.process("Hola mundo de prueba", new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                resultRef.set(refinedText);
            }

            @Override
            public void onError(String error) {
                errorCalled.set(true);
            }
        });

        assertFalse("Should not report error when not configured; it should deliver raw text seamlessly",
                errorCalled.get());
        assertEquals("Hola mundo de prueba", resultRef.get());
    }
}
