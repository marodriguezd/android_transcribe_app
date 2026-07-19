package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

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
    private static final String KEY_MODEL_VARIANT = "model_variant";
    private static final String KEY_WORD_CORRECTION_THRESHOLD = "word_correction_threshold";
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private final SharedPreferences prefs;
    private final Context prefs_context;
    private final java.util.Map<String, Boolean> downloadCache = new java.util.HashMap<>();

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.prefs_context = context;
    }

    public Context getContext() {
        return prefs_context.getApplicationContext();
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
        try {
            MasterKey masterKey = new MasterKey.Builder(prefs_context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    prefs_context,
                    PREFS_NAME + "_encrypted",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return encryptedPrefs.getString(KEY_API_KEY, "");
        } catch (Exception e) {
            Log.e("SettingsManager", "Failed to read encrypted API key", e);
            return prefs.getString(KEY_API_KEY, ""); // fallback
        }
    }

    public void setApiKey(String key) {
        try {
            MasterKey masterKey = new MasterKey.Builder(prefs_context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    prefs_context,
                    PREFS_NAME + "_encrypted",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            encryptedPrefs.edit().putString(KEY_API_KEY, key).apply();
        } catch (Exception e) {
            Log.e("SettingsManager", "Failed to write encrypted API key", e);
            prefs.edit().putString(KEY_API_KEY, key).apply(); // fallback
        }
    }

    public String getModelName() {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL);
    }

    public void setModelName(String model) {
        prefs.edit().putString(KEY_MODEL_NAME, model).apply();
    }

    public String getSystemPrompt() {
        // Single source of truth for the default system prompt lives in
        // app/src/main/res/values/strings.xml (`label_prompt`).
        // prefs_context is the Application context, so getString(R.string.label_prompt)
        // can safely resolve without leaking an Activity. Keep the strings.xml entry
        // byte-equivalent (decoded) to whatever was previously inlined in DEFAULT_PROMPT
        // to avoid silent drift across copy/edit surfaces.
        return prefs.getString(KEY_SYSTEM_PROMPT, prefs_context.getString(R.string.label_prompt));
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

    public double getWordCorrectionThreshold() {
        return prefs.getFloat(KEY_WORD_CORRECTION_THRESHOLD, 0.18f);
    }

    public void setWordCorrectionThreshold(double threshold) {
        prefs.edit().putFloat(KEY_WORD_CORRECTION_THRESHOLD, (float) threshold).apply();
    }

    public String applyDictionary(String text) {
        java.util.List<String> words = new DictionaryManager(prefs_context).getActiveWordsList();
        if (words == null || words.isEmpty()) return text;
        double threshold = getWordCorrectionThreshold();
        WordCorrector corrector = new WordCorrector(words, threshold);
        return corrector.applyCustomWords(text);
    }

    public String getModelVariant() {
        return prefs.getString(KEY_MODEL_VARIANT, "0.6b");
    }

    public void setModelVariant(String variant) {
        prefs.edit().putString(KEY_MODEL_VARIANT, variant).apply();
    }

    public boolean isModelDownloaded(String variant) {
        if ("none".equals(variant)) return true;
        if (downloadCache.containsKey(variant)) {
            return downloadCache.get(variant);
        }
        java.io.File dir;
        String[] requiredFiles;
        if ("180m".equals(variant)) {
            dir = new java.io.File(prefs_context.getFilesDir(), "models/canary-180m-flash-int8");
            requiredFiles = new String[]{"encoder-model.int8.onnx", "decoder-model.int8.onnx", "vocab.txt"};
        } else {
            dir = new java.io.File(prefs_context.getFilesDir(), "models/parakeet-tdt-" + variant + "-v3-int8");
            requiredFiles = new String[]{"encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", "nemo128.onnx", "vocab.txt"};
        }
        if (!dir.exists()) {
            downloadCache.put(variant, false);
            return false;
        }
        java.util.HashSet<String> actual = new java.util.HashSet<>();
        java.io.File[] files = dir.listFiles();
        if (files == null) {
            downloadCache.put(variant, false);
            return false;
        }
        for (java.io.File f : files) {
            actual.add(f.getName());
        }
        for (String req : requiredFiles) {
            if (!actual.contains(req)) {
                downloadCache.put(variant, false);
                return false;
            }
        }
        downloadCache.put(variant, true);
        return true;
    }

    public void invalidateModelCache(String variant) {
        downloadCache.remove(variant);
    }

    public boolean deleteModel(String variant) {
        if ("none".equals(variant)) return true;
        java.io.File dir;
        if ("180m".equals(variant)) {
            dir = new java.io.File(prefs_context.getFilesDir(), "models/canary-180m-flash-int8");
        } else {
            dir = new java.io.File(prefs_context.getFilesDir(), "models/parakeet-tdt-" + variant + "-v3-int8");
        }
        invalidateModelCache(variant);
        if (!dir.exists()) return true;
        return deleteRecursive(dir);
    }

    private boolean deleteRecursive(java.io.File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            java.io.File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    if (!deleteRecursive(child)) {
                        return false;
                    }
                }
            }
        }
        return fileOrDir.delete();
    }

    public java.io.File getModelDir(String variant) {
        if ("180m".equals(variant)) {
            return new java.io.File(prefs_context.getFilesDir(), "models/canary-180m-flash-int8");
        }
        return new java.io.File(prefs_context.getFilesDir(), "models/parakeet-tdt-" + variant + "-v3-int8");
    }
}
