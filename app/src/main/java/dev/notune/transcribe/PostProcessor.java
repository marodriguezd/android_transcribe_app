package dev.notune.transcribe;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PostProcessor {
    private static final String TAG = "PostProcessor";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final SettingsManager settings;

    public PostProcessor(SettingsManager settings) {
        this.client = new OkHttpClient();
        this.settings = settings;
    }

    public void process(String rawText, final PostProcessCallback callback) {
        String apiUrl = settings.getApiUrl();
        // Append chat completions endpoint if not present
        String completionUrl = apiUrl;
        if (!completionUrl.endsWith("/chat/completions")) {
            if (completionUrl.endsWith("/")) {
                completionUrl += "chat/completions";
            } else {
                completionUrl += "/chat/completions";
            }
        }

        String apiKey = settings.getApiKey();
        String model = settings.getModelName();
        String promptTemplate = settings.getSystemPrompt();

        String fullPrompt = promptTemplate.replace("${output}", rawText);

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", fullPrompt);
            messages.put(message);
            
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
                    .post(body);

            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Request request = requestBuilder.build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "API call failed", e);
                    callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        Log.e(TAG, "API error: " + response.code() + " - " + errorBody);
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
                        Log.e(TAG, "Failed to parse API response", e);
                        callback.onError("Parse error: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to create request", e);
            callback.onError(e.getMessage());
        }
    }

    public void fetchModels(final ModelsCallback callback) {
        String baseUrl = settings.getApiUrl();
        String modelsUrl = baseUrl;
        if (!modelsUrl.endsWith("/models")) {
            if (modelsUrl.endsWith("/")) {
                modelsUrl += "models";
            } else {
                modelsUrl += "/models";
            }
        }

        String apiKey = settings.getApiKey();

        Request.Builder requestBuilder = new Request.Builder()
                .url(modelsUrl)
                .get();

        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        client.newCall(request).enqueue(new Callback() {
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
                    // Sort models alphabetically
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
