package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import android.widget.ImageButton;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;
    private static final int REQ_VOICE_TEST = 202;
    private static final int PERM_REQ_NOTIFICATIONS = 103;

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
    private TextView voiceStatusText;
    private ImageView voiceStatusIcon;
    private Button voiceGrantButton;
    private Button voiceTryButton;
    private Button startSubsButton;
    private SettingsManager settingsManager;
    private RadioButton rbModelFast;
    private RadioButton rbModelPrecise;

    private RadioGroup modelGroup;
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private ImageButton btnDeleteFast;
    private ImageButton btnDeletePrecise;
    private Button btnRetry;
    private SeekBar seekThreshold;
    private boolean firstLaunchDialogShown = false;
    private boolean modelSelectionChanging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);

        voiceGrantButton.setOnClickListener(v -> checkAndRequestPermissions());
        voiceTryButton.setOnClickListener(v -> launchVoiceTest());
        voiceHelpButton.setOnClickListener(v -> showHelpDialog());

        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        SettingsManager settingsManager = new SettingsManager(this);
        this.settingsManager = settingsManager;

        com.google.android.material.materialswitch.MaterialSwitch swAuto = findViewById(R.id.switch_auto_record);
        if (swAuto != null) {
            swAuto.setChecked(settingsManager.isAutoRecord());
            swAuto.setOnCheckedChangeListener((vw, c) -> settingsManager.setAutoRecord(c));
        }

        com.google.android.material.materialswitch.MaterialSwitch swPause = findViewById(R.id.switch_pause_audio);
        if (swPause != null) {
            swPause.setChecked(settingsManager.isPauseAudio());
            swPause.setOnCheckedChangeListener((vw, c) -> settingsManager.setPauseAudio(c));
        }

        com.google.android.material.materialswitch.MaterialSwitch swSelect = findViewById(R.id.switch_select_transcription);
        if (swSelect != null) {
            swSelect.setChecked(settingsManager.isSelectTranscription());
            swSelect.setOnCheckedChangeListener((vw, c) -> settingsManager.setSelectTranscription(c));
        }

        Button postProcessButton = findViewById(R.id.btn_post_process_settings);
        if (postProcessButton != null) {
            postProcessButton.setOnClickListener(v -> {
                startActivity(new Intent(this, PostProcessSettingsActivity.class));
            });
        }

        Button hotwordsButton = findViewById(R.id.btn_hotwords_settings);
        if (hotwordsButton != null) {
            hotwordsButton.setOnClickListener(v -> {
                startActivity(new Intent(this, DictionaryListActivity.class));
            });
        }

        seekThreshold = findViewById(R.id.seek_threshold);
        if (seekThreshold != null) {
            int initialProgress = (int) Math.round(settingsManager.getWordCorrectionThreshold() * 100);
            seekThreshold.setProgress(initialProgress);
            seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    double threshold = progress / 100.0;
                    settingsManager.setWordCorrectionThreshold(threshold);
                    updateThresholdLabel(progress);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            updateThresholdLabel(initialProgress);
        }

        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false);

        RadioGroup subsLinesGroup = findViewById(R.id.rg_subtitle_lines);
        int subsLines = SubtitlePrefs.getMaxLines(this);
        if (subsLines == 4) {
            subsLinesGroup.check(R.id.rb_subs_4);
        } else if (subsLines == 0) {
            subsLinesGroup.check(R.id.rb_subs_all);
        } else {
            subsLinesGroup.check(R.id.rb_subs_2);
        }
        subsLinesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int lines = checkedId == R.id.rb_subs_4 ? 4
                    : checkedId == R.id.rb_subs_all ? 0 : 2;
            SubtitlePrefs.setMaxLines(this, lines);
        });

        RadioGroup themeGroup = findViewById(R.id.rg_theme);
        switch (ThemePrefs.getMode(this)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                themeGroup.check(R.id.rb_theme_light);
                break;
            case AppCompatDelegate.MODE_NIGHT_YES:
                themeGroup.check(R.id.rb_theme_dark);
                break;
            default:
                themeGroup.check(R.id.rb_theme_system);
                break;
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode;
            if (checkedId == R.id.rb_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rb_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, newMode);
            }
        });

        setupModelSelection(settingsManager);

        requestNotificationPermissionIfNeeded();
        requestBatteryOptimizationExemption();
        updateVoiceInputStatus();

        new Thread(() -> initNative(this)).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVoiceInputStatus();
        reconnectDownloadCallbacks();
        updateModelSelectionUI();
        if (!firstLaunchDialogShown && modelGroup != null && settingsManager != null) {
            boolean anyDownloaded = settingsManager.isModelDownloaded("0.6b")
                    || settingsManager.isModelDownloaded("1.1b");
            if (!anyDownloaded) {
                firstLaunchDialogShown = true;
                showFirstLaunchDownloadDialog(settingsManager);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void reconnectDownloadCallbacks() {
        ModelDownloadManager dm = ((App) getApplication()).getDownloadManager();
        if (dm != null && dm.isDownloading()) {
            attachDownloadCallbacks(dm);
            // Restore current progress state
            modelProgress.setVisibility(View.VISIBLE);
            modelProgress.setProgress(dm.getCurrentPercent());
            modelStatus.setText(getString(R.string.model_status_downloading, dm.getCurrentPercent()));
            btnRetry.setVisibility(View.GONE);
        } else if (dm != null && dm.isDownloadActive()) {
            // Download just finished while we were away
            modelProgress.setVisibility(View.GONE);
            btnRetry.setVisibility(View.GONE);
            updateModelStatus(modelStatus, dm.getVariant(), settingsManager);
            updateDeleteButtons(btnDeleteFast, btnDeletePrecise, settingsManager);
        }
    }

    private void updateModelSelectionUI() {
        if (modelGroup == null || settingsManager == null) return;
        if (rbModelFast == null || rbModelPrecise == null) return;
        SettingsManager sm = settingsManager;
        String current = sm.getModelVariant();
        boolean fastChecked = rbModelFast.isChecked();
        boolean preciseChecked = rbModelPrecise.isChecked();
        boolean correctState = ("1.1b".equals(current) && preciseChecked)
                || ("0.6b".equals(current) && fastChecked);
        if (!correctState) {
            modelSelectionChanging = true;
            rbModelFast.setChecked(!"1.1b".equals(current));
            rbModelPrecise.setChecked("1.1b".equals(current));
            modelSelectionChanging = false;
        }
        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeletePrecise, sm);

        ModelDownloadManager dm = ((App) getApplication()).getDownloadManager();
        if (dm != null && dm.isDownloading()) {
            modelProgress.setVisibility(View.VISIBLE);
            modelProgress.setProgress(dm.getCurrentPercent());
            modelStatus.setText(getString(R.string.model_status_downloading, dm.getCurrentPercent()));
            btnRetry.setVisibility(View.GONE);
        }
    }

    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micGranted) {
            setVoiceStatus(false, getString(R.string.voice_status_need_mic));
            voiceGrantButton.setVisibility(View.VISIBLE);
            voiceTryButton.setEnabled(false);
            return;
        }

        voiceGrantButton.setVisibility(View.GONE);
        voiceTryButton.setEnabled(true);

        if (isOurAppDefaultRecognizer() || isOurImeEnabled()) {
            setVoiceStatus(true, getString(R.string.voice_status_ready));
        } else {
            setVoiceStatusInfo(getString(R.string.voice_status_almost));
        }
    }

    private boolean isOurImeEnabled() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (android.view.inputmethod.InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                if (imi.getPackageName().equals(getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isOurAppDefaultRecognizer() {
        Intent recog = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo resolved = getPackageManager()
                .resolveActivity(recog, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void setVoiceStatus(boolean ready, String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(ready ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ready
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
        voiceStatusIcon.setContentDescription(message);
    }

    private void setVoiceStatusInfo(String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(R.drawable.ic_error);
        int tint = themeColor(com.google.android.material.R.attr.colorPrimary);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
        voiceStatusIcon.setContentDescription(message);
    }

    private int themeColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, REQ_VOICE_TEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
            snackbar(getString(R.string.voice_test_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE_TEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String heard = (results != null && !results.isEmpty()) ? results.get(0) : null;
            snackbar(heard != null && !heard.trim().isEmpty()
                    ? getString(R.string.voice_test_ok, heard)
                    : getString(R.string.voice_test_empty));
        }
        updateVoiceInputStatus();
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_help_title)
                .setMessage(R.string.voice_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted) {
        CompoundButton sw = findViewById(switchId);
        File marker = new File(getFilesDir(), fileName);
        sw.setChecked(marker.exists() != inverted);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean shouldExist = isChecked != inverted;
            if (shouldExist) {
                try {
                    marker.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create " + fileName + " file", e);
                }
            } else {
                marker.delete();
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        PERM_REQ_NOTIFICATIONS);
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Could not request battery optimization exemption", e);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updateVoiceInputStatus();
        } else if (requestCode == PERM_REQ_NOTIFICATIONS) {
            // Permission result handled; notification will work if granted
        }
    }

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText("Status: " + status);
            if ("Ready".equals(status)) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private native void initNative(MainActivity activity);

    private native void switchModel(MainActivity activity, String variant);

    private void setupModelSelection(SettingsManager sm) {
        modelGroup = findViewById(R.id.rg_model);
        modelStatus = findViewById(R.id.text_model_status);
        modelProgress = findViewById(R.id.progress_model_download);
        btnDeleteFast = findViewById(R.id.btn_delete_model_fast);
        btnDeletePrecise = findViewById(R.id.btn_delete_model_precise);
        btnRetry = findViewById(R.id.btn_model_retry);
        rbModelFast = findViewById(R.id.rb_model_fast);
        rbModelPrecise = findViewById(R.id.rb_model_precise);

        String current = sm.getModelVariant();
        rbModelFast.setChecked(!"1.1b".equals(current));
        rbModelPrecise.setChecked("1.1b".equals(current));

        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeletePrecise, sm);

        btnDeleteFast.setOnClickListener(v -> confirmDeleteModel("0.6b", sm));
        btnDeletePrecise.setOnClickListener(v -> confirmDeleteModel("1.1b", sm));

        btnRetry.setOnClickListener(v -> {
            String variant = sm.getModelVariant();
            btnRetry.setVisibility(View.GONE);
            startDownload(variant, sm);
        });

        modelGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (modelSelectionChanging) return;
            if (checkedId == -1) return;

            String variant = checkedId == R.id.rb_model_precise ? "1.1b" : "0.6b";
            sm.setModelVariant(variant);
            btnRetry.setVisibility(View.GONE);

            if (sm.isModelDownloaded(variant)) {
                updateModelStatus(modelStatus, variant, sm);
                statusText.setText("Status: Switching model\u2026");
                new Thread(() -> switchModel(this, variant)).start();
            } else {
                startDownload(variant, sm);
            }
        });
    }

    private void startDownload(String variant, SettingsManager sm) {
        try {
            if (modelProgress != null) {
                modelProgress.setVisibility(View.VISIBLE);
                modelProgress.setProgress(0);
            }
            if (btnRetry != null) btnRetry.setVisibility(View.GONE);
            if (modelStatus != null) modelStatus.setText(getString(R.string.model_status_downloading, 0));

            ModelDownloadManager.ProgressCallback cb = createDownloadCallback(variant);
            ((App) getApplication()).startDownload(variant, cb);

            Intent intent = new Intent(this, ModelDownloadForegroundService.class);
            intent.setAction(ModelDownloadForegroundService.ACTION_START);
            intent.putExtra("variant", variant);
            try {
                startForegroundService(intent);
            } catch (Exception e) {
                Log.w(TAG, "Foreground service start failed", e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Instant crash caught in startDownload", t);
            runOnUiThread(() -> {
                if (modelStatus != null) modelStatus.setText("Error: " + t.getMessage());
                if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE);
            });
        }
    }

    private void attachDownloadCallbacks(ModelDownloadManager dm) {
        dm.setCallback(createDownloadCallback(dm.getVariant()));
    }

    private ModelDownloadManager.ProgressCallback createDownloadCallback(String variant) {
        WeakReference<MainActivity> activityRef = new WeakReference<>(this);
        return new ModelDownloadManager.ProgressCallback() {
            @Override
            public void onProgress(String fileName, int percent, long bytesDownloaded, long totalBytes) {
                MainActivity a = activityRef.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.runOnUiThread(() -> {
                    a.modelProgress.setProgress(percent);
                });
            }

            @Override
            public void onComplete() {
                MainActivity a = activityRef.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.runOnUiThread(() -> {
                    a.modelProgress.setVisibility(View.GONE);
                    a.btnRetry.setVisibility(View.GONE);
                    SettingsManager sm = a.settingsManager;
                        a.modelSelectionChanging = true;
                        if (a.rbModelFast != null) a.rbModelFast.setChecked(!"1.1b".equals(current));
                        if (a.rbModelPrecise != null) a.rbModelPrecise.setChecked("1.1b".equals(current));
                        a.modelSelectionChanging = false;
                    });
                }
            }
        };
    }

    private void confirmDeleteModel(String variant, SettingsManager sm) {
        String modelName = "0.6b".equals(variant) ? "Fast (0.6B)" : "Precise (1.1B)";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.model_delete_title)
                .setMessage(getString(R.string.model_delete_confirm, modelName))
                .setNegativeButton(R.string.btn_delete, (d, w) -> {
                    App app = (App) getApplication();
                    ModelDownloadManager dm = app.getDownloadManager();
                    if (dm != null
                            && dm.getVariant().equals(variant)
                            && dm.isDownloading()) {
                        dm.cancel();
                    }

                    sm.deleteModel(variant);

                    if (sm.getModelVariant().equals(variant)) {
                        String other = "0.6b".equals(variant) ? "1.1b" : "0.6b";
                        sm.setModelVariant(other);
                        rbModelFast.setChecked(!"1.1b".equals(other));
                        rbModelPrecise.setChecked("1.1b".equals(other));
                        if (sm.isModelDownloaded(other)) {
                            statusText.setText("Status: Switching model\u2026");
                            new Thread(() -> {
                                try {
                                    switchModel(MainActivity.this, other);
                                } catch (Exception e) {
                                    Log.e(TAG, "switchModel native call failed", e);
                                    runOnUiThread(() -> statusText.setText("Status: Failed to load model"));
                                }
                            }).start();
                        }
                    }

                    updateModelStatus(modelStatus, sm.getModelVariant(), sm);
                    updateDeleteButtons(btnDeleteFast, btnDeletePrecise, sm);
                })
                .setPositiveButton(android.R.string.cancel, null)
                .show();
    }

    private void updateDeleteButtons(ImageButton btnDeleteFast, ImageButton btnDeletePrecise,
                                     SettingsManager sm) {
        btnDeleteFast.setVisibility(sm.isModelDownloaded("0.6b") ? View.VISIBLE : View.GONE);
        btnDeletePrecise.setVisibility(sm.isModelDownloaded("1.1b") ? View.VISIBLE : View.GONE);
    }

    private void updateThresholdLabel(int progress) {
        double threshold = progress / 100.0;
        String label = "Threshold: " + String.format("%.2f", threshold)
            + (progress <= 10 ? " (Strict)" : progress >= 80 ? " (Lenient)" : "");
        TextView labelView = findViewById(R.id.label_threshold);
        if (labelView != null) labelView.setText(label);
    }

    private void showFirstLaunchDownloadDialog(SettingsManager sm) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.welcome_model_title)
                .setMessage(R.string.welcome_model_subtitle)
                .setCancelable(false)
                .setPositiveButton(R.string.welcome_btn_fast, (d, w) -> {
                    sm.setModelVariant("0.6b");
                    modelSelectionChanging = true;
                    rbModelPrecise.setChecked(false);
                    rbModelFast.setChecked(true);
                    modelSelectionChanging = false;
                    startDownload("0.6b", sm);
                })
                .setNeutralButton(R.string.welcome_btn_precise, (d, w) -> {
                    sm.setModelVariant("1.1b");
                    modelSelectionChanging = true;
                    rbModelFast.setChecked(false);
                    rbModelPrecise.setChecked(true);
                    modelSelectionChanging = false;
                    startDownload("1.1b", sm);
                })
                .setNegativeButton(R.string.welcome_btn_skip, (d, w) -> {
                    // User chose to skip
                })
                .show();
    }

    private void updateModelStatus(TextView tv, String variant, SettingsManager sm) {
        if (sm.isModelDownloaded(variant)) {
            tv.setText(R.string.model_status_downloaded);
        } else {
            tv.setText(R.string.model_status_not_downloaded);
        }
    }
}
