package dev.notune.transcribe;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Settings screen for the AI post-processing layer (fork addition).
 * Lets the user toggle post-processing, and configure the OpenAI-compatible
 * base URL, API key, model name and the active system prompt.
 */
public class PostProcessSettingsActivity extends AppCompatActivity {

    private SettingsManager settings;
    private MaterialSwitch switchEnabled;
    private EditText editApiUrl;
    private EditText editApiKey;
    private EditText editModel;
    private EditText editPrompt;

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
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModel = findViewById(R.id.edit_model);
        editPrompt = findViewById(R.id.edit_prompt);

        switchEnabled.setChecked(settings.isPostProcessEnabled());
        editApiUrl.setText(settings.getApiUrl());
        editApiKey.setText(settings.getApiKey());
        editModel.setText(settings.getModelName());
        editPrompt.setText(settings.getActivePromptBody());

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save());
    }

    private void save() {
        settings.setPostProcessEnabled(switchEnabled.isChecked());
        settings.setApiUrl(editApiUrl.getText().toString().trim());
        settings.setApiKey(editApiKey.getText().toString().trim());
        settings.setModelName(editModel.getText().toString().trim());
        String prompt = editPrompt.getText().toString();
        settings.setActivePromptBody(prompt.trim().isEmpty() ? settings.getDefaultPrompt() : prompt);
        Toast.makeText(this, R.string.pp_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
