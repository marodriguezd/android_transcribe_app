package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
    private RadioGroup modelGroup;
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private ImageButton btnDeleteFast;
    private RadioButton rbModelFastest;
    private RadioButton rbModelNone;
    private ImageButton btnDeleteModelFastest;
    private Button btnRetry;
    private SeekBar seekThreshold;
    private boolean firstLaunchDialogShown = false;
    private boolean modelSelectionChanging = false;
    private ModelDownloadManager.ProgressCallback currentCallback;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());

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

        // Materialise any bundled model assets (debug-only) into
        // getFilesDir()/models/<variant>/ so the user (and tests) can
        // pick either variant immediately after install, no Wi-Fi wait.
        // The debug APK inlines the ONNX weights at app/src/debug/assets/;
        // ModelDownloadManager.extractBundledAssets() is idempotent on
        // already-present files. We run from onCreate (not onResume)
        // because TFA can be launched directly without ever warming
        // MainActivity.
        extractAllBundledModelsIfNeeded();

        requestNotificationPermissionIfNeeded();
        requestBatteryOptimizationExemption();
        updateVoiceInputStatus();

        WeakReference<MainActivity> initRef = new WeakReference<>(this);
        new Thread(() -> {
            MainActivity a = initRef.get();
            if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                initNative(a);
            }
        }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("firstLaunchDialogShown", firstLaunchDialogShown);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        firstLaunchDialogShown = savedInstanceState.getBoolean("firstLaunchDialogShown", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVoiceInputStatus();
        reconnectDownloadCallbacks();
        updateModelSelectionUI();
        if (!firstLaunchDialogShown && modelGroup != null && settingsManager != null) {
            boolean anyDownloaded = settingsManager.isModelDownloaded("0.6b")
                    || settingsManager.isModelDownloaded("180m");
            if (!anyDownloaded) {
                firstLaunchDialogShown = true;
                showFirstLaunchDownloadDialog(settingsManager);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentCallback != null) {
            ModelDownloadManager dm = ((App) getApplication()).getDownloadManager();
            if (dm != null) dm.removeCallback(currentCallback);
        }
        currentCallback = null;
    }

    private void reconnectDownloadCallbacks() {
        ModelDownloadManager dm = ((App) getApplication()).getDownloadManager();
        if (dm != null && dm.isDownloading()) {
            attachDownloadCallbacks(dm);
            // Restore current progress state
            if (modelProgress != null) modelProgress.setVisibility(View.VISIBLE);
            modelProgress.setProgress(dm.getCurrentPercent());
            modelStatus.setText(getString(R.string.model_status_downloading, dm.getCurrentPercent()));
            if (btnRetry != null) btnRetry.setVisibility(View.GONE);
        } else if (dm != null) {
            // Download just finished (or errored) while we were away
            if (modelProgress != null) modelProgress.setVisibility(View.GONE);
            if (btnRetry != null) btnRetry.setVisibility(View.GONE);
            settingsManager.invalidateModelCache(dm.getVariant());
            updateModelStatus(modelStatus, dm.getVariant(), settingsManager);
            updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, settingsManager);
        }
    }

    private void updateModelSelectionUI() {
        if (modelGroup == null || settingsManager == null) return;
        if (rbModelFast == null) return;
        SettingsManager sm = settingsManager;
        String current = sm.getModelVariant();
        modelSelectionChanging = true;
        if ("180m".equals(current)) {
            modelGroup.check(R.id.rb_model_fastest);
        } else if ("none".equals(current)) {
            modelGroup.check(R.id.rb_model_none);
        } else {
            modelGroup.check(R.id.rb_model_fast);
        }
        modelSelectionChanging = false;
        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);

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
            if (status != null && (status.toLowerCase().contains("error") || status.toLowerCase().contains("failed"))) {
                if (btnRetry != null) btnRetry.setVisibility(View.VISIBLE);
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
        btnRetry = findViewById(R.id.btn_model_retry);
        rbModelFast = findViewById(R.id.rb_model_fast);
        rbModelFastest = findViewById(R.id.rb_model_fastest);
        btnDeleteModelFastest = findViewById(R.id.btn_delete_model_fastest);
        rbModelNone = findViewById(R.id.rb_model_none);
        String current = sm.getModelVariant();
        modelSelectionChanging = true;
        if ("180m".equals(current)) {
            modelGroup.check(R.id.rb_model_fastest);
        } else if ("none".equals(current)) {
            modelGroup.check(R.id.rb_model_none);
        } else {
            modelGroup.check(R.id.rb_model_fast);
        }
        modelSelectionChanging = false;
        
        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);

        btnDeleteFast.setOnClickListener(v -> confirmDeleteModel("0.6b", sm));
        btnDeleteModelFastest.setOnClickListener(v -> confirmDeleteModel("180m", sm));

        btnRetry.setOnClickListener(v -> {
            String variant = sm.getModelVariant();
            btnRetry.setVisibility(View.GONE);
            startDownload(variant, sm);
        });

        modelGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (modelSelectionChanging) return;
            if (checkedId == -1) return;

            String variant;
            if (checkedId == R.id.rb_model_fastest) {
                variant = "180m";
            } else if (checkedId == R.id.rb_model_fast) {
                variant = "0.6b";
            } else {
                variant = "none";
            }
            sm.setModelVariant(variant);
            btnRetry.setVisibility(View.GONE);

            if ("none".equals(variant)) {
                updateModelStatus(modelStatus, variant, sm);
                updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
                statusText.setText(getString(R.string.model_switch_restart));
                WeakReference<MainActivity> switchRef = new WeakReference<>(this);
                new Thread(() -> {
                    MainActivity a = switchRef.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        a.switchModel(a, variant);
                    }
                }).start();
            } else if (sm.isModelDownloaded(variant)) {
                updateModelStatus(modelStatus, variant, sm);
                statusText.setText(getString(R.string.model_switch_restart));
                WeakReference<MainActivity> switchRef = new WeakReference<>(this);
                new Thread(() -> {
                    MainActivity a = switchRef.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        a.switchModel(a, variant);
                    }
                }).start();
                String switchMsg = getString(R.string.model_switch_restart);
                mainHandler.postDelayed(() -> {
                    if (switchMsg.equals(statusText.getText().toString())) {
                        statusText.setText(getString(R.string.model_switch_timeout));
                    }
                }, 30000);
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
        if (currentCallback != null) {
            dm.removeCallback(currentCallback);
        }
        currentCallback = createDownloadCallback(dm.getVariant());
        dm.setCallback(currentCallback);
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
                    a.modelStatus.setText(a.getString(R.string.model_status_downloading, percent));
                });
            }

            @Override
            public void onComplete() {
                MainActivity a = activityRef.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.runOnUiThread(() -> {
                    a.modelProgress.setVisibility(View.GONE);
                    a.btnRetry.setVisibility(View.GONE);
                    a.settingsManager.invalidateModelCache(variant);
                    a.modelSelectionChanging = true;
                    if ("180m".equals(variant)) {
                        a.modelGroup.check(R.id.rb_model_fastest);
                    } else if ("0.6b".equals(variant)) {
                        a.modelGroup.check(R.id.rb_model_fast);
                    } else if ("none".equals(variant)) {
                        a.modelGroup.check(R.id.rb_model_none);
                    }
                    a.modelSelectionChanging = false;
                    a.modelStatus.setText(a.getString(R.string.model_status_downloaded));
                    a.updateModelStatus(a.modelStatus, variant, a.settingsManager);
                    a.updateDeleteButtons(a.btnDeleteFast, a.btnDeleteModelFastest, a.settingsManager);
                    new Thread(() -> a.switchModel(a, variant)).start();
                });
            }

            @Override
            public void onRetry(String fileName, int attempt, long waitMs) {
                MainActivity a = activityRef.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.runOnUiThread(() -> {
                    a.modelStatus.setText(a.getString(R.string.model_status_waiting));
                });
            }

            @Override
            public void onError(String error, boolean retryable) {
                MainActivity a = activityRef.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                a.runOnUiThread(() -> {
                    a.modelProgress.setVisibility(View.GONE);
                    a.modelStatus.setText(a.getString(R.string.model_download_error, error));
                    if (retryable && a.btnRetry != null) {
                        a.btnRetry.setVisibility(View.VISIBLE);
                    }
                });
            }
        };
    }

    private void confirmDeleteModel(String variant, SettingsManager sm) {
        if ("none".equals(variant)) return;
        String modelName;
        if ("180m".equals(variant)) {
            modelName = getString(R.string.model_fastest);
        } else {
            modelName = getString(R.string.model_fast);
        }
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
                        stopService(new Intent(MainActivity.this, ModelDownloadForegroundService.class));
                    }

                    sm.deleteModel(variant);

                    if (sm.getModelVariant().equals(variant)) {
                        modelSelectionChanging = true;
                        if ("180m".equals(variant)) {
                            modelGroup.check(R.id.rb_model_fast);
                            sm.setModelVariant("0.6b");
                        } else {
                            modelGroup.check(R.id.rb_model_fastest);
                            sm.setModelVariant("180m");
                        }
                        modelSelectionChanging = false;
                    }

                    updateModelStatus(modelStatus, sm.getModelVariant(), sm);
                    updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
                })
                .setPositiveButton(android.R.string.cancel, null)
                .show();
    }

    private void updateDeleteButtons(ImageButton btnDeleteFast, ImageButton btnDeleteModelFastest, SettingsManager sm) {
        btnDeleteFast.setVisibility(sm.isModelDownloaded("0.6b") ? View.VISIBLE : View.GONE);
        btnDeleteModelFastest.setVisibility(sm.isModelDownloaded("180m") ? View.VISIBLE : View.GONE);
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
                .setPositiveButton(R.string.welcome_btn_fastest, (d, w) -> {
                    sm.setModelVariant("180m");
                    modelSelectionChanging = true;
                    modelGroup.check(R.id.rb_model_fastest);
                    modelSelectionChanging = false;
                    startDownload("180m", sm);
                })
                .setNeutralButton(R.string.welcome_btn_fast, (dialog, which) -> {
                    sm.setModelVariant("0.6b");
                    modelSelectionChanging = true;
                    modelGroup.check(R.id.rb_model_fast);
                    modelSelectionChanging = false;
                    startDownload("0.6b", sm);
                })
                .setNegativeButton(R.string.welcome_btn_skip, (d, w) -> {
                    sm.setModelVariant("none");
                    modelSelectionChanging = true;
                    modelGroup.check(R.id.rb_model_none);
                    modelSelectionChanging = false;
                    updateModelStatus(modelStatus, "none", sm);
                    updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
                })
                .show();
    }

    private void updateModelStatus(TextView tv, String variant, SettingsManager sm) {
        if ("none".equals(variant)) {
            tv.setText(R.string.model_status_no_model);
        } else if (sm.isModelDownloaded(variant)) {
            tv.setText(R.string.model_status_downloaded);
        } else {
            tv.setText(R.string.model_status_not_downloaded);
        }
    }

    /**
     * Materialise any models that ship with the debug APK into
     * {@code getFilesDir()/models/<variant>/}. The debug build inlines the
     * Parakeet ONNX weights at {@code app/src/debug/assets/<variant>/};
     * normally the user would tap a radio (or the welcome-dialog button)
     * to trigger {@code ModelDownloadManager.download()} → {@code
     * tryCopyAsset()}. This method short-circuits that flow on first
     * launch so tests can pick either variant immediately.
     *
     * <p>Runs on a single background thread (not the main thread) to
     * avoid ANRs during the multi-hundred-MB copy; updates
     * {@link #statusText} via {@code runOnUiThread} when done. The
     * class is intentionally a no-op when both variants are already
     * on disk; subsequent rebuilds / pm clears are unaffected.
     *
     * <p>Bound to {@code onCreate} rather than {@code onResume} so
     * TranscribeFileActivity, which can launch directly without ever
     * warming MainActivity, also benefits from the extaction.
     */
    private void extractAllBundledModelsIfNeeded() {
        if (settingsManager == null) return;
        if (settingsManager.isModelDownloaded("0.6b")
                && settingsManager.isModelDownloaded("180m")) {
            return;
        }

        if (statusText != null) {
            statusText.setText("Extracting bundled models…");
        }

        new Thread(() -> {
            boolean ok06b = false;
            boolean ok180m = false;
            try {
                ModelDownloadManager fast = new ModelDownloadManager(
                        getApplicationContext(), "0.6b");
                int c = fast.extractBundledAssets();
                ok06b = c >= 0 && fast.isModelDownloaded();
            } catch (Throwable t) {
                Log.w(TAG, "Auto-extract failed for 0.6b", t);
            }
            try {
                ModelDownloadManager fastest = new ModelDownloadManager(
                        getApplicationContext(), "180m");
                int c = fastest.extractBundledAssets();
                ok180m = c >= 0 && fastest.isModelDownloaded();
            } catch (Throwable t) {
                Log.w(TAG, "Auto-extract failed for 180m", t);
            }
            final boolean both = ok06b && ok180m;
            runOnUiThread(() -> {
                settingsManager.invalidateModelCache("0.6b");
                settingsManager.invalidateModelCache("180m");
                updateModelStatus(modelStatus,
                        settingsManager.getModelVariant(), settingsManager);
                updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest,
                        settingsManager);
                if (statusText != null) {
                    statusText.setText(both
                            ? "Models ready (bundled)"
                            : "Model extraction partial — see model status");
                }
            });
        }, "model-extract-bundled").start();
    }
}
