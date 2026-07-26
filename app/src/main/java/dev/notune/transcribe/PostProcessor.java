package dev.notune.transcribe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI post-processing over an OpenAI-compatible /chat/completions endpoint.
 * Minimal port on top of upstream v0.1.18: takes the raw transcript, sends
 * it with the active system prompt, returns the refined text.
 *
 * The custom-dictionary / hotword hint injection from the old fork is
 * intentionally omitted here and can be layered back later.
 */
public class PostProcessor {
    private static final String TAG = "PostProcessor";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final SettingsManager settings;

    public PostProcessor(SettingsManager settings) {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.settings = settings;
    }

    public void process(String rawText, final PostProcessCallback callback) {
        if (rawText == null || rawText.trim().isEmpty()) {
            callback.onSuccess(rawText != null ? rawText : "");
            return;
        }

        String apiUrl = settings.getApiUrl() != null ? settings.getApiUrl().trim() : "";
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        String completionUrl = apiUrl;
        if (!completionUrl.endsWith("/chat/completions")) {
            completionUrl += "/chat/completions";
        }

        String apiKey = settings.getApiKey();
        String model = settings.getModelName();
        String systemInstruction = settings.getActivePromptBody();

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);

            JSONArray messages = new JSONArray();
            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemInstruction.trim());
                messages.put(sysMsg);
            }
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", rawText);
            messages.put(userMsg);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
                    .post(body);
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            client.newCall(requestBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "API call failed: " + e.getMessage());
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "API error: code=" + response.code());
                        callback.onError("API Error " + response.code());
                        return;
                    }
                    try {
                        String responseData = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseData);
                        JSONArray choices = jsonResponse.getJSONArray("choices");
                        if (choices.length() > 0) {
                            String resultText = choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            callback.onSuccess(resultText.trim());
                        } else {
                            callback.onError("Empty response from AI");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse API response: " + e.getMessage());
                        callback.onError("Parse error: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create request: " + e.getMessage());
            callback.onError(e.getMessage());
        }
    }

    public void fetchModels(final ModelsCallback callback) {
        String baseUrl = settings.getApiUrl() != null ? settings.getApiUrl().trim() : "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String modelsUrl = baseUrl;
        if (!modelsUrl.endsWith("/models")) {
            modelsUrl += "/models";
        }

        String apiKey = settings.getApiKey();
        Request.Builder requestBuilder = new Request.Builder().url(modelsUrl).get();
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("Error " + response.code());
                    return;
                }
                try {
                    String data = response.body().string();
                    JSONObject json = new JSONObject(data);
                    JSONArray array = json.getJSONArray("data");
                    java.util.List<String> models = new java.util.ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        models.add(array.getJSONObject(i).getString("id"));
                    }
                    java.util.Collections.sort(models);
                    callback.onSuccess(models);
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    public interface PostProcessCallback {
        void onSuccess(String refinedText);
        void onError(String error);
    }

    public interface ModelsCallback {
        void onSuccess(java.util.List<String> models);
        void onError(String error);
    }
}
