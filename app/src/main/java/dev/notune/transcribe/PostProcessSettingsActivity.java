package dev.notune.transcribe;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/**
 * Settings screen for the AI post-processing layer (fork addition).
 * Lets the user toggle post-processing, pick a provider preset (Groq,
 * OpenAI, Cerebras, ... or Custom), and configure API key, model name and
 * the active system prompt. The base-URL field is only shown for Custom;
 * presets carry their endpoint template.
 *
 * The model field is an editable dropdown: the refresh button next to it
 * calls the provider's /models endpoint with the current key and fills the
 * dropdown with the available model ids.
 */
public class PostProcessSettingsActivity extends AppCompatActivity {

    private SettingsManager settings;
    private MaterialSwitch switchEnabled;
    private AutoCompleteTextView dropdownProvider;
    private TextInputLayout layoutApiUrl;
    private TextInputLayout layoutApiKey;
    private EditText editApiUrl;
    private EditText editApiKey;
    private AutoCompleteTextView editModel;
    private EditText editPrompt;
    private Button btnRefreshModels;

    /** Provider id currently selected in the dropdown. */
    private String selectedProviderId;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel this screen's in-flight model-list fetch when it is destroyed
        // (owner-scoped, P0.1), so its UI callbacks cannot touch a torn-down
        // window (e.g. calling editModel.showDropDown() after the activity is
        // gone would throw WindowManager.BadTokenException). A dictation from
        // another surface is left untouched.
        PostProcessor.cancelAllFor(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_settings);

        settings = new SettingsManager(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        switchEnabled = findViewById(R.id.switch_pp_enabled);
        dropdownProvider = findViewById(R.id.dropdown_provider);
        layoutApiUrl = findViewById(R.id.layout_api_url);
        layoutApiKey = findViewById(R.id.layout_api_key);
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModel = findViewById(R.id.edit_model);
        editPrompt = findViewById(R.id.edit_prompt);
        btnRefreshModels = findViewById(R.id.btn_refresh_models);

        // Provider dropdown
        String[] labels = new String[SettingsManager.PROVIDERS.length];
        for (int i = 0; i < SettingsManager.PROVIDERS.length; i++) {
            labels[i] = SettingsManager.PROVIDERS[i].label;
        }
        dropdownProvider.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels));

        selectedProviderId = settings.getProviderId();
        SettingsManager.Provider current = SettingsManager.providerById(selectedProviderId);
        dropdownProvider.setText(current.label, false);

        dropdownProvider.setOnItemClickListener((parent, view, position, id) -> {
            SettingsManager.Provider p = SettingsManager.PROVIDERS[position];
            selectedProviderId = p.id;
            updateProviderUi(p, true);
        });

        switchEnabled.setChecked(settings.isPostProcessEnabled());
        editApiUrl.setText(settings.getApiUrl());
        editApiKey.setText(settings.getApiKey());
        editModel.setText(settings.getModelName());
        editPrompt.setText(settings.getActivePromptBody());
        if (settings.isPostProcessEnabled() && settings.getApiKey().isEmpty()) {
            layoutApiKey.setError(getString(R.string.pp_api_key_required));
        }

        // Align the model field with the selected provider. A provider change
        // leaves the old provider's model behind (e.g. switching OpenAI ->
        // Groq kept "gpt-4o-mini"), which the new provider's API rejects,
        // silently defeating post-processing. Replacing another provider's
        // default (or an empty/unknown model) with the current provider's
        // default keeps the stored model valid for the endpoint in use.
        updateProviderUi(current, false);

        btnRefreshModels.setOnClickListener(v -> fetchModels());

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save(true));

        Button testConnection = findViewById(R.id.btn_test_connection);
        testConnection.setOnClickListener(v -> testConnection(testConnection));
    }

    /**
     * Shows/hides the base-URL field (Custom only) and, on user-initiated
     * provider switches, pre-fills the model field with the provider's
     * default model when the field is empty or still holds another
     * provider's default (avoids clobbering a hand-edited model).
     */
    private void updateProviderUi(SettingsManager.Provider p, boolean userInitiated) {
        boolean isCustom = p.baseUrl == null;
        layoutApiUrl.setVisibility(isCustom ? android.view.View.VISIBLE : android.view.View.GONE);

        // Keep the model field consistent with the selected provider.
        //  - userInitiated (provider switch): always adopt the new provider's
        //    default model. A stale model from the previous provider (e.g.
        //    "gpt-4o-mini" after switching OpenAI -> Groq, or any fetched
        //    model like "openai/gpt-oss-120b") is rejected by the new
        //    provider's API, silently defeating post-processing.
        //  - Initial load: only replace a clearly foreign default (another
        //    provider's known default model) or an empty field, so a model
        //    the user hand-typed/customised is preserved.
        if (p.defaultModel != null && !p.defaultModel.isEmpty()) {
            String currentModel = editModel.getText().toString().trim();
            boolean belongsToAnotherProvider = false;
            if (!currentModel.isEmpty()) {
                for (SettingsManager.Provider q : SettingsManager.PROVIDERS) {
                    if (!q.id.equals(p.id) && currentModel.equals(q.defaultModel)) {
                        belongsToAnotherProvider = true;
                        break;
                    }
                }
            }
            if (userInitiated) {
                editModel.setText(p.defaultModel);
            } else if (currentModel.isEmpty() || belongsToAnotherProvider) {
                editModel.setText(p.defaultModel);
            }
        }
    }

    /**
     * Fetches the /models list from the currently selected provider using
     * the values in the form (persisting them first, since PostProcessor
     * reads from SettingsManager), and fills the model dropdown.
     */
    private void fetchModels() {
        save(false);
        btnRefreshModels.setEnabled(false);
        new PostProcessor(settings, new Handler(Looper.getMainLooper()),
                () -> !isFinishing() && !isDestroyed(), this)
                .fetchModels(new PostProcessor.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                btnRefreshModels.setEnabled(true);
                editModel.setAdapter(new ArrayAdapter<>(
                        PostProcessSettingsActivity.this,
                        android.R.layout.simple_list_item_1, models));
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_models_loaded) + " (" + models.size() + ")",
                        Toast.LENGTH_SHORT).show();
                editModel.showDropDown();
            }

            @Override
            public void onError(String error) {
                btnRefreshModels.setEnabled(true);
                String detail = PostProcessor.MISSING_API_KEY_ERROR.equals(error)
                        ? getString(R.string.pp_api_key_required) : error;
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_models_error) + ": " + detail,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void testConnection(Button button) {
        save(false);
        button.setEnabled(false);
        new PostProcessor(settings, new Handler(Looper.getMainLooper()),
                () -> !isFinishing() && !isDestroyed(), this)
                .testConnection(new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                button.setEnabled(true);
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_test_success), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String error) {
                button.setEnabled(true);
                String detail = PostProcessor.MISSING_API_KEY_ERROR.equals(error)
                        ? getString(R.string.pp_api_key_required) : error;
                Toast.makeText(PostProcessSettingsActivity.this,
                        getString(R.string.pp_test_error, detail), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void save(boolean closeAfter) {
        boolean wasEnabled = settings.isPostProcessEnabled();
        boolean nowEnabled = switchEnabled.isChecked();
        settings.setPostProcessEnabled(nowEnabled);
        settings.setProviderId(selectedProviderId);
        settings.setApiUrl(editApiUrl.getText().toString().trim());
        String apiKey = editApiKey.getText().toString().trim();
        settings.setApiKey(apiKey);
        layoutApiKey.setError(apiKey.isEmpty() && nowEnabled
                ? getString(R.string.pp_api_key_required) : null);
        settings.setModelName(editModel.getText().toString().trim());

        // Only persist the prompt if the user actually changed it; otherwise
        // leave the marker empty so future updates to the default prompt keep
        // applying automatically.
        String prompt = editPrompt.getText().toString().trim();
        String defaultPrompt = settings.getDefaultPrompt();
        if (prompt.isEmpty() || prompt.equals(defaultPrompt)) {
            settings.setActivePromptBody("");
        } else {
            settings.setActivePromptBody(prompt);
        }

        // If the user just disabled post-processing, cancel any in-flight LLM
        // calls in this process and broadcast to the IME (":ime") process so
        // it cancels its own calls immediately instead of waiting for them to
        // time out.
        if (wasEnabled && !nowEnabled) {
            PostProcessor.cancelAll();
            Intent cancelIntent = new Intent(PostProcessor.CANCEL_ACTION);
            cancelIntent.setPackage(getPackageName());
            sendBroadcast(cancelIntent);
        }


        if (closeAfter) {
            Toast.makeText(this, R.string.pp_saved, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
