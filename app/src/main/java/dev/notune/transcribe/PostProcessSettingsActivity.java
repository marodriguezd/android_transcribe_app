package dev.notune.transcribe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.Toast;

public class PostProcessSettingsActivity extends Activity {

    private SettingsManager settingsManager;
    private Switch switchEnable;
    private EditText editApiUrl;
    private EditText editApiKey;
    private EditText editModelName;
    private EditText editPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_settings);

        settingsManager = new SettingsManager(this);

        switchEnable = findViewById(R.id.switch_enable);
        editApiUrl = findViewById(R.id.edit_api_url);
        editApiKey = findViewById(R.id.edit_api_key);
        editModelName = findViewById(R.id.edit_model_name);
        editPrompt = findViewById(R.id.edit_prompt);
        Button btnSave = findViewById(R.id.btn_save);
        ImageButton btnBack = findViewById(R.id.btn_back);

        // Load current values
        switchEnable.setChecked(settingsManager.isPostProcessEnabled());
        editApiUrl.setText(settingsManager.getApiUrl());
        editApiKey.setText(settingsManager.getApiKey());
        editModelName.setText(settingsManager.getModelName());
        editPrompt.setText(settingsManager.getSystemPrompt());

        btnSave.setOnClickListener(v -> saveSettings());
        btnBack.setOnClickListener(v -> finish());
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
