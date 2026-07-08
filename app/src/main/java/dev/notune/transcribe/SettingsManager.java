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
    private static final String DEFAULT_PROMPT = "Eres un motor de post-procesamiento. Corrige la gramática, ortografía y puntuación del siguiente texto, manteniendo el estilo original: ${output}";

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
}
