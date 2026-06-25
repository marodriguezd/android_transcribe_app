package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Switch;
import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load dependencies (c++_shared or onnxruntime)", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private TextView statusText;
    private Button grantButton;
    private View permsCard;
    private Button startSubsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        permsCard = findViewById(R.id.card_permissions);
        grantButton = findViewById(R.id.btn_grant_perms);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);

        grantButton.setOnClickListener(v -> checkAndRequestPermissions());
        
        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        Button postProcessButton = findViewById(R.id.btn_post_process_settings);
        postProcessButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, PostProcessSettingsActivity.class);
            startActivity(intent);
        });

        SettingsManager settingsManager = new SettingsManager(this);

        Switch autoRecordSwitch = findViewById(R.id.switch_auto_record);
        autoRecordSwitch.setChecked(settingsManager.isAutoRecord());
        autoRecordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setAutoRecord(isChecked);
        });

        Switch selectTranscriptionSwitch = findViewById(R.id.switch_select_transcription);
        selectTranscriptionSwitch.setChecked(settingsManager.isSelectTranscription());
        selectTranscriptionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setSelectTranscription(isChecked);
        });

        Switch pauseAudioSwitch = findViewById(R.id.switch_pause_audio);
        pauseAudioSwitch.setChecked(settingsManager.isPauseAudio());
        pauseAudioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settingsManager.setPauseAudio(isChecked);
        });

        // Initial check
        updatePermissionUI();
        
        // Start init
        initNative(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUI();
    }

    private void updatePermissionUI() {
        boolean hasAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (hasAudio) {
            permsCard.setVisibility(View.GONE);
        } else {
            permsCard.setVisibility(View.VISIBLE);
        }
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updatePermissionUI();
        }
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText("Status: " + status);
            if ("Ready".equals(status)) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private native void initNative(MainActivity activity);
}
