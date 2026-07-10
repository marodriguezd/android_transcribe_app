package dev.notune.transcribe;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ProgressBar;
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
    private MaterialSwitch switchEnable;
    private TextInputEditText editApiUrl;
    private TextInputEditText editApiKey;
    private MaterialAutoCompleteTextView editModelName;
    private TextInputEditText editPrompt;
    private ProgressBar progressModels;
    private ImageButton btnRefreshModels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_settings);

        settingsManager = new SettingsManager(this);
        postProcessor = new PostProcessor(settingsManager);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        switchEnable = findViewById(R.id.switch_enable);
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModelName = findViewById(R.id.edit_model_name);
        editPrompt = findViewById(R.id.edit_prompt);
        progressModels = findViewById(R.id.progress_models);
        btnRefreshModels = findViewById(R.id.btn_refresh_models);
        MaterialButton btnSave = findViewById(R.id.btn_save);

        // Load current values
        switchEnable.setChecked(settingsManager.isPostProcessEnabled());
        editApiUrl.setText(settingsManager.getApiUrl());
        editApiKey.setText(settingsManager.getApiKey());
        editModelName.setText(settingsManager.getModelName());
        editPrompt.setText(settingsManager.getSystemPrompt());

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
        settingsManager.setPostProcessEnabled(switchEnable.isChecked());
        settingsManager.setApiUrl(editApiUrl.getText().toString().trim());
        settingsManager.setApiKey(editApiKey.getText().toString().trim());
        settingsManager.setModelName(editModelName.getText().toString().trim());
        settingsManager.setSystemPrompt(editPrompt.getText().toString().trim());

        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show();
        finish();
    }
}
