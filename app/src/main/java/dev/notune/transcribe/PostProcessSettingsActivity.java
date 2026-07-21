package dev.notune.transcribe;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class PostProcessSettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;
    private PostProcessor postProcessor;
    private PromptsRepository promptsRepository;
    private MaterialSwitch switchEnable;
    private TextInputEditText editApiUrl;
    private TextInputEditText editApiKey;
    private MaterialAutoCompleteTextView editModelName;
    private TextView textActivePromptName;
    private TextView textActivePromptBody;
    private ProgressBar progressModels;
    private ImageButton btnRefreshModels;
    // Only the chip group itself is held; the per-chip view references
    // are intentionally absent — the OnCheckedStateChangeListener
    // hands us the checked chip id as an int via checkedIds, which
    // urlForProviderChip(int) maps directly to the preset URL. The
    // layout XML already establishes android:id="@+id/chip_openai" etc.
    // so uiautomator-style introspection keeps working without the
    // Java-side references.
    private com.google.android.material.chip.ChipGroup chipGroupProviders;
    // Set during programmatic chip-preselect (so the listener does not
    // write to editApiUrl when we are just reflecting the existing
    // saved value back into the chip state). Mirrors the
    // modelSelectionChanging / languageSelectionChanging patterns used
    // elsewhere in this app.
    private boolean providerSelectionChanging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_settings);

        settingsManager = new SettingsManager(this);
        postProcessor = new PostProcessor(settingsManager);
        promptsRepository = settingsManager.getPromptsRepository();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchEnable = findViewById(R.id.switch_enable);
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModelName = findViewById(R.id.edit_model_name);
        textActivePromptName = findViewById(R.id.text_active_prompt_name);
        textActivePromptBody = findViewById(R.id.text_active_prompt_body);
        MaterialButton btnManagePrompts = findViewById(R.id.btn_manage_prompts);
        progressModels = findViewById(R.id.progress_models);
        btnRefreshModels = findViewById(R.id.btn_refresh_models);
        MaterialButton btnSave = findViewById(R.id.btn_save);
        chipGroupProviders = findViewById(R.id.chip_group_providers);

        switchEnable.setChecked(settingsManager.isPostProcessEnabled());
        editApiUrl.setText(settingsManager.getApiUrl());
        editApiKey.setText(settingsManager.getApiKey());
        editModelName.setText(settingsManager.getModelName());

        // Wire the provider preset chips: tapping one overwrites the
        // API URL below in the edit-text field, so the user does not
        // have to remember or type the per-provider base URL by hand.
        // The chip group has app:selectionRequired="false" so tapping a
        // chip a second time clears the selection (preserves the
        // user-typed URL if they want to opt back out of a preset).
        preselectProviderChip();
        if (chipGroupProviders != null) {
            chipGroupProviders.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (providerSelectionChanging) return;
                if (checkedIds == null || checkedIds.isEmpty()) return;
                int checkedId = checkedIds.get(0);
                String url = urlForProviderChip(checkedId);
                if (url != null && editApiUrl != null) {
                    editApiUrl.setText(url);
                    // Park the caret at the end so the user can keep
                    // typing if they want to override the trailing /
                    // or append a path.
                    editApiUrl.setSelection(url.length());
                }
            });
        }

        btnManagePrompts.setOnClickListener(v ->
                startActivity(new Intent(this, PostProcessPromptsListActivity.class)));

        btnSave.setOnClickListener(v -> saveSettings());
        btnRefreshModels.setOnClickListener(v -> refreshModels());

        // Show dropdown on focus
        editModelName.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && editModelName.getAdapter() != null) {
                editModelName.showDropDown();
            }
        });
        editModelName.setOnClickListener(v -> {
            if (editModelName.getAdapter() != null) {
                editModelName.showDropDown();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshActivePromptSubtitle();
    }

    private void refreshActivePromptSubtitle() {
        if (textActivePromptName == null || textActivePromptBody == null) return;
        String name = promptsRepository.getActivePromptName();
        String body = promptsRepository.getActivePromptBody();
        textActivePromptName.setText(getString(R.string.subtitle_active_named, name));
        textActivePromptBody.setText(body);
    }

    /**
     * Reflect the persisted {@link SettingsManager#getApiUrl()} value
     * back into the provider chip group so the chip the user picked
     * last time is still visibly checked when they reopen the screen.
     * If the saved URL does not match any of the four preset endpoints
     * (custom URL, OpenAI without trailing slash, etc.) no chip is
     * checked — the user falls back to the free-text edit-text field.
     * Wrapped in {@link #providerSelectionChanging} so the
     * {@code setOnCheckedStateChangeListener} does not echo the URL
     * back into the field (it would just round-trip the same string).
     */
    private void preselectProviderChip() {
        if (chipGroupProviders == null || settingsManager == null) return;
        String current = normalizeUrlForCompare(settingsManager.getApiUrl());
        int matchedId;
        if (current.equals(normalizeUrlForCompare(getString(R.string.pref_openai_url)))) {
            matchedId = R.id.chip_openai;
        } else if (current.equals(normalizeUrlForCompare(getString(R.string.pref_groq_url)))) {
            matchedId = R.id.chip_groq;
        } else if (current.equals(normalizeUrlForCompare(getString(R.string.pref_ollama_url)))) {
            matchedId = R.id.chip_ollama;
        } else if (current.equals(normalizeUrlForCompare(getString(R.string.pref_lmstudio_url)))) {
            matchedId = R.id.chip_lmstudio;
        } else {
            return;
        }
        providerSelectionChanging = true;
        try {
            chipGroupProviders.check(matchedId);
        } finally {
            providerSelectionChanging = false;
        }
    }

    /**
     * Map a provider chip id to the canonical preset base URL string
     * (defined as string resources for easy editing). Returns null if
     * the id is not one of the four presets so callers can no-op.
     */
    private String urlForProviderChip(int chipId) {
        if (chipId == R.id.chip_openai)    return getString(R.string.pref_openai_url);
        if (chipId == R.id.chip_groq)      return getString(R.string.pref_groq_url);
        if (chipId == R.id.chip_ollama)    return getString(R.string.pref_ollama_url);
        if (chipId == R.id.chip_lmstudio)  return getString(R.string.pref_lmstudio_url);
        return null;
    }

    /**
     * Trim trailing slashes off a base URL so two strings that only
     * differ in whether the user (or the default) added a closing
     * slash still compare equal. Used by both the on-open preselect
     * (so e.g. {@code https://api.openai.com/v1} and
     * {@code https://api.openai.com/v1/} both light up the OpenAI chip)
     * and any future "did the user type a preset URL" matcher we add.
     */
    private static String normalizeUrlForCompare(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void refreshModels() {
        // Temporary save API URL and Key to use them for fetching
        settingsManager.setApiUrl(editApiUrl.getText().toString().trim());
        settingsManager.setApiKey(editApiKey.getText().toString().trim());

        btnRefreshModels.setVisibility(View.GONE);
        progressModels.setVisibility(View.VISIBLE);

        postProcessor.fetchModels(new PostProcessor.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                runOnUiThread(() -> {
                    progressModels.setVisibility(View.GONE);
                    btnRefreshModels.setVisibility(View.VISIBLE);

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            PostProcessSettingsActivity.this,
                            android.R.layout.simple_dropdown_item_1line,
                            models
                    );
                    editModelName.setAdapter(adapter);
                    editModelName.showDropDown();

                    Toast.makeText(PostProcessSettingsActivity.this,
                            R.string.msg_models_ready, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    progressModels.setVisibility(View.GONE);
                    btnRefreshModels.setVisibility(View.VISIBLE);
                    Toast.makeText(PostProcessSettingsActivity.this,
                            "Error: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveSettings() {
        // Note: the active prompt is no longer saved here — it is managed via
        // PostProcessPromptsListActivity. The legacy `system_prompt` prefs
        // key would be a no-op write now (and the next migration run would
        // resurrect it as a saved prompt), so we deliberately omit it.
        settingsManager.setPostProcessEnabled(switchEnable.isChecked());
        settingsManager.setApiUrl(editApiUrl.getText().toString().trim());
        settingsManager.setApiKey(editApiKey.getText().toString().trim());
        settingsManager.setModelName(editModelName.getText().toString().trim());

        Toast.makeText(this, R.string.post_process_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
