package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {
    private static final String PREFS_NAME = "transcribe_settings";
    
    private static final String KEY_POST_PROCESS_ENABLED = "post_process_enabled";
    private static final String KEY_API_URL = "api_url"; // Now stores Base URL
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL_NAME = "model_name";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_AUTO_RECORD = "auto_record";
    private static final String KEY_SELECT_TRANSCRIPTION = "select_transcription";
    private static final String KEY_PAUSE_AUDIO = "pause_audio";

    private static final String KEY_HOTWORDS = "custom_hotwords";
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_PROMPT = "<system>\n" +
            "You are a post-processing engine for ASR transcriptions.\n\n" +
            "Your task is to convert a raw transcription into natural and correctly formulated text, maintaining the style of Wispr Flow.\n\n" +
            "Objectives (in order of priority):\n" +
            "1. Preserve the meaning exactly.\n" +
            "2. Do not invent or summarize information.\n" +
            "3. Make the fewest possible modifications.\n" +
            "4. Correct only speech recognition errors.\n" +
            "5. Improve spelling, grammar, and punctuation.\n" +
            "6. Maintain the tone and style of the speaker.\n" +
            "7. CRITICAL: Maintain the original language of the input exactly. Do not translate the text under any circumstances, even if it contains a mix of languages.\n\n" +
            "Automatically correct:\n" +
            "- ASR phonetic errors\n" +
            "- accidental repetitions\n" +
            "- filler words\n" +
            "- false starts\n" +
            "- voice activity detector (VAD) errors\n" +
            "- spelling\n" +
            "- grammar\n" +
            "- punctuation\n" +
            "- accents\n" +
            "- capitalization\n" +
            "- proper nouns\n" +
            "- brands\n" +
            "- technical terminology\n" +
            "- commands\n" +
            "- code snippets\n\n" +
            "If a correction cannot be inferred with high confidence, keep the original text.\n\n" +
            "Automatically normalize technical names and commands when they are obvious.\n\n" +
            "Examples:\n" +
            "GitHub\n" +
            "Supabase\n" +
            "Flutter\n" +
            "Docker\n" +
            "Gemini CLI\n" +
            "Wispr Flow\n" +
            "Parakeet\n" +
            "git commit\n" +
            "git push\n" +
            "docker compose up\n" +
            "/goal\n\n" +
            "Return only the corrected text.\n" +
            "</system>\n\n" +
            "<examples>\n" +
            "Input:\n" +
            "I want to develop an extension for gemini cli where I type slash goal create an application with fluter.\n" +
            "Output:\n" +
            "I want to develop an extension for Gemini CLI where I type `/goal create an application with Flutter`.\n" +
            "---\n" +
            "Input:\n" +
            "We upload it later to github and supa base.\n" +
            "Output:\n" +
            "We upload it later to GitHub and Supabase.\n" +
            "---\n" +
            "Input:\n" +
            "Umm I think that that we should change that.\n" +
            "Output:\n" +
            "I think that we should change that.\n" +
            "---\n" +
            "Input:\n" +
            "The the main server has a problem.\n" +
            "Output:\n" +
            "The main server has a problem.\n" +
            "---\n" +
            "Input:\n" +
            "I want to enter the dragwear and move the version down.\n" +
            "Output:\n" +
            "I want to enter the Drawer and move the application version down.\n" +
            "---\n" +
            "Input:\n" +
            "I am trying to distill wisper flow using paraquit v three.\n" +
            "Output:\n" +
            "I am trying to distill Wispr Flow using Parakeet v3.\n" +
            "---\n" +
            "Input:\n" +
            "Create the archives organizing the table execute the commandments necessary.\n" +
            "Output:\n" +
            "Create the files, organize the tasks and execute the necessary commands.\n" +
            "---\n" +
            "Input:\n" +
            "Docker compouse ap.\n" +
            "Output:\n" +
            "docker compose up\n" +
            "</examples>\n\n" +
            "Input:\n" +
            "${output}\n\n" +
            "Output:";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isPostProcessEnabled() {
        return prefs.getBoolean(KEY_POST_PROCESS_ENABLED, false);
    }

    public void setPostProcessEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_POST_PROCESS_ENABLED, enabled).apply();
    }

    public String getApiUrl() {
        return prefs.getString(KEY_API_URL, DEFAULT_API_URL);
    }

    public void setApiUrl(String url) {
        prefs.edit().putString(KEY_API_URL, url).apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key).apply();
    }

    public String getModelName() {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL);
    }

    public void setModelName(String model) {
        prefs.edit().putString(KEY_MODEL_NAME, model).apply();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_PROMPT);
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }

    public boolean isAutoRecord() {
        return prefs.getBoolean(KEY_AUTO_RECORD, false);
    }

    public void setAutoRecord(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply();
    }

    public boolean isSelectTranscription() {
        return prefs.getBoolean(KEY_SELECT_TRANSCRIPTION, false);
    }

    public void setSelectTranscription(boolean enabled) {
        prefs.edit().putBoolean(KEY_SELECT_TRANSCRIPTION, enabled).apply();
    }

    public boolean isPauseAudio() {
        return prefs.getBoolean(KEY_PAUSE_AUDIO, false);
    }

    public void setPauseAudio(boolean enabled) {
        prefs.edit().putBoolean(KEY_PAUSE_AUDIO, enabled).apply();
    }

    public java.util.Set<String> getHotwords() {
        return prefs.getStringSet(KEY_HOTWORDS, new java.util.HashSet<>());
    }

    public void setHotwords(java.util.Set<String> words) {
        prefs.edit().putStringSet(KEY_HOTWORDS, words).apply();
    }

    public String applyDictionary(String text) {
        java.util.Set<String> words = getHotwords();
        if (words == null || words.isEmpty()) return text;
        
        String processed = text;
        for (String line : words) {
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    processed = processed.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(key) + "\\b", value);
                }
            }
        }
        return processed;
    }
}
