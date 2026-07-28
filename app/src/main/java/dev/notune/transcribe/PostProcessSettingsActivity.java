package dev.notune.transcribe;

import android.os.Bundle;
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
    private EditText editApiUrl;
    private EditText editApiKey;
    private AutoCompleteTextView editModel;
    private EditText editPrompt;
    private Button btnRefreshModels;

    /** Provider id currently selected in the dropdown. */
    private String selectedProviderId;

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

        updateProviderUi(current, false);

        btnRefreshModels.setOnClickListener(v -> fetchModels());

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save(true));
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

        if (userInitiated && p.defaultModel != null && !p.defaultModel.isEmpty()) {
            String currentModel = editModel.getText().toString().trim();
            boolean isAnotherDefault = false;
            for (SettingsManager.Provider q : SettingsManager.PROVIDERS) {
                if (currentModel.equals(q.defaultModel)) {
                    isAnotherDefault = true;
                    break;
                }
            }
            if (currentModel.isEmpty() || isAnotherDefault) {
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
        new PostProcessor(settings).fetchModels(new PostProcessor.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                runOnUiThread(() -> {
                    btnRefreshModels.setEnabled(true);
                    editModel.setAdapter(new ArrayAdapter<>(
                            PostProcessSettingsActivity.this,
                            android.R.layout.simple_list_item_1, models));
                    Toast.makeText(PostProcessSettingsActivity.this,
                            getString(R.string.pp_models_loaded) + " (" + models.size() + ")",
                            Toast.LENGTH_SHORT).show();
                    editModel.showDropDown();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnRefreshModels.setEnabled(true);
                    Toast.makeText(PostProcessSettingsActivity.this,
                            getString(R.string.pp_models_error) + ": " + error,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void save(boolean closeAfter) {
        boolean wasEnabled = settings.isPostProcessEnabled();
        boolean nowEnabled = switchEnabled.isChecked();
        settings.setPostProcessEnabled(nowEnabled);
        settings.setProviderId(selectedProviderId);
        settings.setApiUrl(editApiUrl.getText().toString().trim());
        settings.setApiKey(editApiKey.getText().toString().trim());
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
        // calls so the next transcription starts fresh.
        if (wasEnabled && !nowEnabled) {
            PostProcessor.cancelAll();
        }

        // If the user just enabled post-processing, warm up the encrypted API
        // key store so the first transcription does not stall on Keystore init.
        // Run off the UI thread because Keystore init can be slow.
        if (!wasEnabled && nowEnabled) {
            new Thread(() -> SettingsManager.prewarmApiKey(this)).start();
        }

        if (closeAfter) {
            Toast.makeText(this, R.string.pp_saved, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
