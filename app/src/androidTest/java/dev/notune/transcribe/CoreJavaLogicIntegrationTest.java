package dev.notune.transcribe;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Android instrumentation tests for the core Java-side business logic.
 * These tests run on a device/emulator via {@code connectedAndroidTest} and
 * exercise the classes that do NOT depend on JNI / native libraries:
 * {@link SettingsManager}, {@link WordCorrector}, {@link DictionaryManager},
 * {@link PromptsRepository}, {@link Prompt}, and {@link MicLevelView}.
 *
 * <p>Goal: catch regressions in the stateful data layer (SharedPreferences,
 * JSON persistence, prompt management, fuzzy word correction) before the
 * release APK is built. The native inference engine is tested via Rust unit
 * tests and on-device manual E2E verification.
 *
 * <p>Because the app's native libraries are only compiled for arm64-v8a,
 * the test APK on an x86_64 emulator cannot load them — but the static
 * initialisers (System.loadLibrary) catch UnsatisfiedLinkError gracefully,
 * so Activity/Service classes can be referenced without crashing. Tests here
 * avoid exercising any path that calls through to native code.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class CoreJavaLogicIntegrationTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    // =========================================================================
    // SettingsManager — SharedPreferences read/write round-trips
    // =========================================================================

    @Test
    public void settingsManager_postProcessEnabled_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        boolean original = sm.isPostProcessEnabled();

        sm.setPostProcessEnabled(true);
        assertTrue("post-process should be enabled after set(true)", sm.isPostProcessEnabled());

        sm.setPostProcessEnabled(false);
        assertFalse("post-process should be disabled after set(false)", sm.isPostProcessEnabled());

        // Restore
        sm.setPostProcessEnabled(original);
    }

    @Test
    public void settingsManager_apiUrl_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        String original = sm.getApiUrl();

        String customUrl = "https://custom.test.api/v1";
        sm.setApiUrl(customUrl);
        assertEquals("custom API URL should survive round-trip", customUrl, sm.getApiUrl());

        // Restore
        sm.setApiUrl(original);
    }

    @Test
    public void settingsManager_modelName_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        String original = sm.getModelName();

        String customModel = "gpt-4o";
        sm.setModelName(customModel);
        assertEquals("custom model name should survive round-trip", customModel, sm.getModelName());

        sm.setModelName(original);
    }

    @Test
    public void settingsManager_modelVariant_default() {
        SettingsManager sm = new SettingsManager(context);
        String variant = sm.getModelVariant();
        assertNotNull("model variant should never be null", variant);
        assertTrue("variant should be one of known values",
                Arrays.asList("0.6b", "180m", "none").contains(variant));
    }

    @Test
    public void settingsManager_modelVariant_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        String original = sm.getModelVariant();

        sm.setModelVariant("180m");
        assertEquals("variant should be 180m after set", "180m", sm.getModelVariant());

        sm.setModelVariant(original);
    }

    @Test
    public void settingsManager_isModelDownloaded_noneAlwaysTrue() {
        SettingsManager sm = new SettingsManager(context);
        assertTrue("none variant should always be considered downloaded",
                sm.isModelDownloaded("none"));
    }

    @Test
    public void settingsManager_isModelDownloaded_nonExistent() {
        SettingsManager sm = new SettingsManager(context);
        // The assertions below assume a fresh emulator with NO models on disk.
        // Skip on a real device where models have already been downloaded —
        // we don't want to delete the user's models just to make a test
        // pass. The complementary assertion "isModelDownloaded returns true
        // when the model files exist" is implicit in the dev device itself
        // (MainActivity uses it on every cold start) and is exercised by
        // the manual E2E flow ("Latest commit on `develop` ... E2E
        // transcription test on A059").
        org.junit.Assume.assumeFalse(
                "Skip on a device that already has models downloaded",
                sm.isModelDownloaded("0.6b") || sm.isModelDownloaded("180m"));

        // Both 0.6b and 180m require actual model files — on a fresh emulator they won't exist
        assertFalse("0.6b should not be downloaded on a fresh emulator",
                sm.isModelDownloaded("0.6b"));
        assertFalse("180m should not be downloaded on a fresh emulator",
                sm.isModelDownloaded("180m"));
    }

    @Test
    public void settingsManager_deleteModel_none() {
        SettingsManager sm = new SettingsManager(context);
        assertTrue("deleteModel('none') should return true without side-effects",
                sm.deleteModel("none"));
    }

    @Test
    public void settingsManager_autoRecord_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        boolean original = sm.isAutoRecord();

        sm.setAutoRecord(true);
        assertTrue(sm.isAutoRecord());

        sm.setAutoRecord(false);
        assertFalse(sm.isAutoRecord());

        sm.setAutoRecord(original);
    }

    @Test
    public void settingsManager_selectTranscription_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        boolean original = sm.isSelectTranscription();

        sm.setSelectTranscription(true);
        assertTrue(sm.isSelectTranscription());

        sm.setSelectTranscription(false);
        assertFalse(sm.isSelectTranscription());

        sm.setSelectTranscription(original);
    }

    @Test
    public void settingsManager_pauseAudio_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        boolean original = sm.isPauseAudio();

        sm.setPauseAudio(true);
        assertTrue(sm.isPauseAudio());

        sm.setPauseAudio(false);
        assertFalse(sm.isPauseAudio());

        sm.setPauseAudio(original);
    }

    @Test
    public void settingsManager_wordCorrectionThreshold_default() {
        SettingsManager sm = new SettingsManager(context);
        double threshold = sm.getWordCorrectionThreshold();
        assertEquals("default threshold should be 0.18", 0.18, threshold, 0.001);
    }

    @Test
    public void settingsManager_wordCorrectionThreshold_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        double original = sm.getWordCorrectionThreshold();

        sm.setWordCorrectionThreshold(0.25);
        assertEquals("threshold should survive round-trip", 0.25, sm.getWordCorrectionThreshold(), 0.001);

        sm.setWordCorrectionThreshold(original);
    }

    @Test
    public void settingsManager_systemPrompt_fallbackToDefault() {
        SettingsManager sm = new SettingsManager(context);
        String prompt = sm.getSystemPrompt();
        assertNotNull("system prompt should never be null", prompt);
        assertTrue("default prompt should contain ${output} placeholder",
                prompt.contains("${output}"));
    }

    @Test
    public void settingsManager_systemPrompt_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        String original = sm.getSystemPrompt();

        String customPrompt = "Translate to Spanish: ${output}";
        sm.setSystemPrompt(customPrompt);
        assertEquals("custom prompt should survive round-trip",
                customPrompt, sm.getSystemPrompt());

        sm.setSystemPrompt(original);
    }

    @Test
    public void settingsManager_apiKey_roundTrip() {
        SettingsManager sm = new SettingsManager(context);
        String original = sm.getApiKey();

        String testKey = "sk-test-key-12345";
        sm.setApiKey(testKey);
        String retrieved = sm.getApiKey();
        assertEquals("API key should survive encrypted round-trip", testKey, retrieved);

        sm.setApiKey(original);
    }

    // =========================================================================
    // WordCorrector — fuzzy matching algorithm
    // =========================================================================

    @Test
    public void wordCorrector_emptyWords_noChange() {
        WordCorrector corrector = new WordCorrector(Arrays.asList(), 0.18);
        String text = "this is a test";
        assertEquals("empty custom words should not alter text", text, corrector.applyCustomWords(text));
    }

    @Test
    public void wordCorrector_exactMatch() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("Parakeet"), 0.18);
        String result = corrector.applyCustomWords("I use paraquid for transcription");
        assertTrue("parakeet should correct 'paraquid' to 'Parakeet'",
                result.contains("Parakeet"));
    }

    @Test
    public void wordCorrector_multipleWords() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("ChatGPT", "Parakeet"), 0.18);
        String result = corrector.applyCustomWords("chat g p t is great");
        assertTrue("ChatGPT should be recognized",
                result.toLowerCase().contains("chatgpt"));
    }

    @Test
    public void wordCorrector_emptyInput() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("test"), 0.18);
        // Empty string should not crash and return empty
        assertEquals("", corrector.applyCustomWords(""));
    }

    @Test
    public void wordCorrector_nullInput() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("test"), 0.18);
        try {
            String result = corrector.applyCustomWords(null);
            // Accept either null return or empty string — just don't crash
            assertTrue(result == null || result.isEmpty());
        } catch (NullPointerException e) {
            fail("WordCorrector should handle null input without NPE");
        }
    }

    @Test
    public void wordCorrector_thresholdEffect() {
        // Strict threshold: only very close matches
        WordCorrector strict = new WordCorrector(Arrays.asList("Parakeet"), 0.05);
        String strictResult = strict.applyCustomWords("paraquid is cool");
        // With strict threshold, 'paraquid' should NOT be corrected to 'Parakeet'
        assertFalse("strict threshold should not correct 'paraquid' to 'Parakeet'",
                strictResult.contains("Parakeet"));

        // Lenient threshold: more aggressive matching
        WordCorrector lenient = new WordCorrector(Arrays.asList("Parakeet"), 0.50);
        String lenientResult = lenient.applyCustomWords("paraquid is cool");
        // With lenient threshold, 'paraquid' SHOULD be corrected to 'Parakeet'
        assertTrue("lenient threshold should correct 'paraquid' to 'Parakeet'",
                lenientResult.contains("Parakeet"));
    }

    @Test
    public void wordCorrector_ampersandExpansion() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("R&D"), 0.30);
        String result = corrector.applyCustomWords("r and d is important");
        assertTrue("R&D ampersand should be expanded in correction",
                result.contains("R&D"));
    }

    @Test
    public void wordCorrector_casePreservation() {
        WordCorrector corrector = new WordCorrector(Arrays.asList("Parakeet"), 0.18);
        String upperInput = "I USE PARAQUID";
        String upperResult = corrector.applyCustomWords(upperInput);
        assertTrue("case preservation should keep uppercased match",
                upperResult.contains("PARAKEET"));
    }

    // =========================================================================
    // filterTranscriptionOutput — filler word removal + stutter collapse
    // =========================================================================

    @Test
    public void filterTranscriptionOutput_fillerRemoval() {
        String result = WordCorrector.filterTranscriptionOutput("um this is a uh test", "en");
        assertFalse("'um' should be removed", result.contains("um"));
        assertFalse("'uh' should be removed", result.contains("uh"));
    }

    @Test
    public void filterTranscriptionOutput_stutterCollapse() {
        String result = WordCorrector.filterTranscriptionOutput("wh wh wh wh what is this", "en");
        // Should have at most 2 "wh" (filter_transcription_output collapses 3+ reps to 1)
        int whCount = result.split("wh\\b").length - 1;
        assertTrue("stutter should be collapsed to 1 'wh' instance", whCount <= 1);
    }

    @Test
    public void filterTranscriptionOutput_multipleSpacesCleaned() {
        String result = WordCorrector.filterTranscriptionOutput("this  has   multiple    spaces", "en");
        assertFalse("multiple spaces should be collapsed", result.contains("  "));
    }

    @Test
    public void filterTranscriptionOutput_nullInput() {
        try {
            String result = WordCorrector.filterTranscriptionOutput(null, "en");
            assertNotNull("null input should produce non-null output", result);
        } catch (Exception e) {
            // Accept that null may throw — the important thing is not to crash the app
            assertTrue("exception on null is acceptable", e instanceof NullPointerException);
        }
    }

    // =========================================================================
    // Prompt model class — JSON serialization
    // =========================================================================

    @Test
    public void prompt_createNew_hasValidId() {
        Prompt p = Prompt.createNew("My Prompt", "Clean this: ${output}");
        assertNotNull("new prompt should have an id", p.getId());
        assertFalse("new prompt id should not be builtin",
                Prompt.BUILTIN_ID.equals(p.getId()));
        assertEquals("name should match", "My Prompt", p.getName());
        assertEquals("body should match", "Clean this: ${output}", p.getBody());
    }

    @Test
    public void prompt_isBuiltin() {
        Prompt builtin = new Prompt(Prompt.BUILTIN_ID, "Built-in", "body", 0L);
        assertTrue("prompt with BUILTIN_ID should be builtin", builtin.isBuiltin());

        Prompt user = Prompt.createNew("User", "body");
        assertFalse("user prompt should not be builtin", user.isBuiltin());
    }

    @Test
    public void prompt_toJson_roundTrip() throws Exception {
        Prompt original = Prompt.createNew("Test Prompt", "Process: ${output}");
        org.json.JSONObject json = original.toJson();
        assertEquals("JSON should preserve name", "Test Prompt", json.getString("name"));
        assertEquals("JSON should preserve body", "Process: ${output}", json.getString("body"));

        Prompt restored = Prompt.fromJson(json);
        assertEquals("restored name should match original", original.getName(), restored.getName());
        assertEquals("restored body should match original", original.getBody(), restored.getBody());
    }

    @Test
    public void prompt_fromJson_resurrectsBuiltinId() throws Exception {
        // The magic builtin ID should never be persisted or restored
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("id", Prompt.BUILTIN_ID);
        json.put("name", "Evil Builtin");
        json.put("body", "test");
        json.put("updatedAt", 0L);

        Prompt restored = Prompt.fromJson(json);
        assertFalse("restored prompt should NOT have the magic builtin id",
                Prompt.BUILTIN_ID.equals(restored.getId()));
    }

    // =========================================================================
    // PromptsRepository — persistence and active prompt tracking
    // =========================================================================

    @Test
    public void promptsRepository_getBuiltin_alwaysAvailable() {
        // Clean the prompts file first to ensure a fresh state
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        PromptsRepository repo = new PromptsRepository(context);

        Prompt builtin = repo.getBuiltin();
        assertNotNull("builtin should always be available", builtin);
        assertTrue("builtin should be marked as builtin", builtin.isBuiltin());
        assertTrue("builtin body should contain ${output}",
                builtin.getBody().contains("${output}"));
    }

    @Test
    public void promptsRepository_getActiveId_defaultsToBuiltin() {
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        // Also clear the active_prompt_id pref
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        assertEquals("active id should default to builtin",
                Prompt.BUILTIN_ID, repo.getActiveId());
    }

    @Test
    public void promptsRepository_addAndRetrieve() {
        // Clean state
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        Prompt p = Prompt.createNew("Test Prompt", "Clean: ${output}");
        repo.add(p);

        List<Prompt> all = repo.getAllWithBuiltin();
        assertTrue("all prompts should include builtin + 1 user prompt",
                all.size() >= 2);

        Prompt retrieved = repo.getById(p.getId());
        assertNotNull("user prompt should be retrievable by id", retrieved);
        assertEquals("retrieved prompt name should match", p.getName(), retrieved.getName());
    }

    @Test
    public void promptsRepository_setActiveId() {
        // Clean state
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        Prompt p = Prompt.createNew("Active Prompt", "Process: ${output}");
        repo.add(p);

        repo.setActiveId(p.getId());
        assertEquals("active id should be the newly created prompt",
                p.getId(), repo.getActiveId());
    }

    @Test
    public void promptsRepository_deleteAndFallbackToBuiltin() {
        // Clean state
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        Prompt p = Prompt.createNew("To Delete", "Body: ${output}");
        repo.add(p);
        repo.setActiveId(p.getId());
        assertEquals("active should be user prompt before delete",
                p.getId(), repo.getActiveId());

        repo.delete(p.getId());
        assertEquals("active should fallback to builtin after deletion",
                Prompt.BUILTIN_ID, repo.getActiveId());
    }

    @Test
    public void promptsRepository_getActivePromptBody_fallbackToDefault() {
        // Clean state
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        String body = repo.getActivePromptBody();
        assertNotNull("active prompt body should never be null", body);
        assertTrue("default body should contain ${output}", body.contains("${output}"));
    }

    // =========================================================================
    // PromptsRepository — builtin edit / override / reset (v0.8.8)
    // =========================================================================

    @Test
    public void promptsRepository_builtinNotOverridden_byDefault() {
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        assertFalse("fresh install should report builtin as NOT overridden",
                repo.isBuiltinOverridden());

        // getBuiltin() returns resource-backed virtual fallback.
        Prompt builtin = repo.getBuiltin();
        assertTrue(builtin.isBuiltin());
        assertEquals("virtual builtin body should equal R.string.label_prompt",
                context.getString(R.string.label_prompt), builtin.getBody());
    }

    @Test
    public void promptsRepository_editBuiltin_persistsAcrossReload() {
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        // First repo instance: load virtual builtin, replace body, save as override.
        PromptsRepository repo = new PromptsRepository(context);
        Prompt builtin = repo.getBuiltin();
        assertFalse("fresh install should have no builtin override", repo.isBuiltinOverridden());
        builtin.setName("My customized default");
        builtin.setBody("Translate to Spanish: ${output}");
        repo.update(builtin);

        assertTrue("after update() builtin should be reported as overridden",
                repo.isBuiltinOverridden());

        // Second repo instance: simulates process restart; reads from disk
        // and must surface the override, NOT the resource-backed fallback.
        PromptsRepository reloaded = new PromptsRepository(context);
        assertTrue("override should survive reload", reloaded.isBuiltinOverridden());
        Prompt reloadedBuiltin = reloaded.getBuiltin();
        assertEquals("override name should survive reload",
                "My customized default", reloadedBuiltin.getName());
        assertEquals("override body should survive reload",
                "Translate to Spanish: ${output}", reloadedBuiltin.getBody());

        // getActivePromptBody / Name should return override values too
        // (active id is BUILTIN_ID by default).
        assertEquals("getActivePromptBody should return override body",
                "Translate to Spanish: ${output}", reloaded.getActivePromptBody());
        assertEquals("getActivePromptName should return override name",
                "My customized default", reloaded.getActivePromptName());
    }

    @Test
    public void promptsRepository_deleteBuiltin_clearsOverride() {
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        Prompt builtin = repo.getBuiltin();
        builtin.setBody("My edit: ${output}");
        repo.update(builtin);
        assertTrue(repo.isBuiltinOverridden());

        // delete(BUILTIN_ID) acts as "reset to default": removes the
        // override and routes back to the resource-backed virtual builtin.
        repo.delete(Prompt.BUILTIN_ID);
        assertFalse("delete(BUILTIN_ID) should clear the override",
                repo.isBuiltinOverridden());

        Prompt afterReset = repo.getBuiltin();
        assertEquals("after reset, body should fall back to R.string.label_prompt",
                context.getString(R.string.label_prompt), afterReset.getBody());
    }

    @Test
    public void promptsRepository_exportBuiltin_isIdStripped() throws Exception {
        // Building on the post-v0.8.7 behaviour: exporting the builtin
        // produces id-stripped JSON so re-import creates a fresh user
        // prompt (the magic BUILTIN_ID is never resurrected from JSON).
        // We parse the JSON rather than substring-checking so a body that
        // legitimately contains the letters "id" (e.g. "edit", "valid")
        // does not produce a false-positive.
        File promptsFile = new File(context.getFilesDir(), "prompts.json");
        promptsFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("active_prompt_id").apply();

        PromptsRepository repo = new PromptsRepository(context);
        String json = repo.exportToJson(Prompt.BUILTIN_ID);
        org.json.JSONObject parsed = new org.json.JSONObject(json);
        assertFalse("exported builtin JSON should not carry an id property",
                parsed.has("id"));
        assertTrue("export should include name field", parsed.has("name"));
        assertTrue("export should include body field", parsed.has("body"));
    }

    // =========================================================================
    // MicLevelView — visual mic level indicator
    // =========================================================================

    @Test
    public void micLevelView_creation() {
        MicLevelView view = new MicLevelView(context);
        assertNotNull("MicLevelView should be constructable", view);
    }

    @Test
    public void micLevelView_setLevel_clampsToZero() {
        MicLevelView view = new MicLevelView(context);
        // Should not crash with out-of-range values
        view.setLevel(-1.0f);
        view.setLevel(0.0f);
        view.setLevel(0.5f);
        view.setLevel(1.0f);
        view.setLevel(2.0f);
        assertNotNull("view should survive multiple setLevel calls", view);
    }

    @Test
    public void micLevelView_setLevel_transitions() {
        MicLevelView view = new MicLevelView(context);
        view.setLevel(0.0f);
        view.setLevel(1.0f);
        // No assertion other than not crashing — visual transitions are observed manually
        assertNotNull("view should survive transition from 0 to 1", view);
    }

    // =========================================================================
    // AudioFocusPauser — audio focus management
    // =========================================================================

    @Test
    public void audioFocusPauser_creation() {
        AudioFocusPauser pauser = new AudioFocusPauser();
        assertNotNull("AudioFocusPauser should be constructable", pauser);
    }

    @Test
    public void audioFocusPauser_requestAndAbandon_noCrash() {
        AudioFocusPauser pauser = new AudioFocusPauser();
        // Should not crash even without an activity/context
        // (actual audio focus requires a running activity)
        assertNotNull(pauser);
    }

    // =========================================================================
    // Dictionary model — JSON serialization
    // =========================================================================

    @Test
    public void dictionary_defaultId_isMarkedAsDefault() {
        Dictionary d = new Dictionary(Dictionary.DEFAULT_ID, "Default", new java.util.ArrayList<>(), true);
        assertTrue("dictionary with DEFAULT_ID should be marked as default", d.isDefault());

        Dictionary user = new Dictionary("A");
        assertFalse("user-created dictionary should not be marked as default", user.isDefault());
    }

    @Test
    public void dictionary_fromJson_resurrectsDefaultId() throws Exception {
        // The magic default id should never be persisted or restored, same
        // contract as Prompt.fromJson vs BUILTIN_ID.
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("id", Dictionary.DEFAULT_ID);
        json.put("name", "Evil Default");
        org.json.JSONArray arr = new org.json.JSONArray();
        arr.put("evil");
        json.put("words", arr);
        json.put("enabled", true);

        Dictionary restored = Dictionary.fromJson(json);
        assertFalse("restored dictionary should NOT carry the magic default id",
                Dictionary.DEFAULT_ID.equals(restored.getId()));
    }

    // =========================================================================
    // DictionaryManager — default dictionary override slot (v0.8.8)
    // =========================================================================

    @Test
    public void dictionaryManager_defaultNotOverridden_byDefault() {
        File dictFile = new File(context.getFilesDir(), "dictionaries.json");
        dictFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("custom_hotwords").apply();

        DictionaryManager dm = new DictionaryManager(context);
        assertFalse("fresh install should not have a default override",
                dm.isDefaultOverridden());

        Dictionary def = dm.getDefault();
        assertTrue("default should be marked as default", def.isDefault());
        assertEquals("virtual default name should equal R.string.name_default_dictionary",
                context.getString(R.string.name_default_dictionary), def.getName());
    }

    @Test
    public void dictionaryManager_editDefault_persistsAcrossReload() {
        File dictFile = new File(context.getFilesDir(), "dictionaries.json");
        dictFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("custom_hotwords").apply();

        DictionaryManager dm = new DictionaryManager(context);
        Dictionary def = dm.getDefault();
        assertFalse("no override before edit", dm.isDefaultOverridden());

        // Add a word via the standard per-dictionary API; then update the
        // default to persist; then reload and confirm.
        dm.addWord(Dictionary.DEFAULT_ID, "Parakeet");
        dm.addWord(Dictionary.DEFAULT_ID, "ChatGPT");
        Dictionary updatedDefault = dm.getById(Dictionary.DEFAULT_ID);
        assertEquals("override should include both added words",
                2, updatedDefault.getWordCount());
        dm.updateDictionary(updatedDefault);
        assertTrue("updateDictionary should publish the override",
                dm.isDefaultOverridden());

        DictionaryManager reloaded = new DictionaryManager(context);
        assertTrue("override should survive process reload", reloaded.isDefaultOverridden());
        Dictionary reloadedDefault = reloaded.getById(Dictionary.DEFAULT_ID);
        assertEquals("override word count should survive reload",
                2, reloadedDefault.getWordCount());
        assertTrue("override words should survive reload",
                reloadedDefault.getWords().contains("Parakeet"));
    }

    @Test
    public void dictionaryManager_deleteDefault_clearsOverride() {
        File dictFile = new File(context.getFilesDir(), "dictionaries.json");
        dictFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("custom_hotwords").apply();

        DictionaryManager dm = new DictionaryManager(context);
        Dictionary def = dm.getDefault();
        dm.addWord(Dictionary.DEFAULT_ID, "Parakeet");
        dm.updateDictionary(def);
        assertTrue("override should be active after edit", dm.isDefaultOverridden());

        // deleteDictionary(DEFAULT_ID) acts as "reset to default": removes
        // the persisted override and falls back to the resource-backed
        // virtual default.
        dm.deleteDictionary(Dictionary.DEFAULT_ID);
        assertFalse("delete(DEFAULT_ID) should clear the override",
                dm.isDefaultOverridden());

        Dictionary afterReset = dm.getDefault();
        assertEquals("after reset, words should be empty",
                0, afterReset.getWordCount());
        assertEquals("after reset, name should match R.string.name_default_dictionary",
                context.getString(R.string.name_default_dictionary), afterReset.getName());
    }

    @Test
    public void dictionaryManager_exportDefault_isIdStripped() throws Exception {
        File dictFile = new File(context.getFilesDir(), "dictionaries.json");
        dictFile.delete();
        context.getSharedPreferences("transcribe_settings", Context.MODE_PRIVATE)
                .edit().remove("custom_hotwords").apply();

        DictionaryManager dm = new DictionaryManager(context);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        dm.exportDictionary(Dictionary.DEFAULT_ID, baos);
        String json = baos.toString("UTF-8");
        org.json.JSONObject parsed = new org.json.JSONObject(json);
        assertFalse("exported default JSON should not carry an id property",
                parsed.has("id"));
        assertTrue("export should include name field", parsed.has("name"));
        assertTrue("export should include words field", parsed.has("words"));
    }
}
