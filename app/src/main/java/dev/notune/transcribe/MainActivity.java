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
    // Language picker UI. Visible for both Canary 180M and Parakeet 0.6B
    // v3; the engine is loaded for both variants and the picker is the
    // primary way the user selects the source/target language. The Auto
    // chip within the picker is gated per variant: visible for 0.6B (CTC
    // auto-detects natively), hidden for 180M because per hotfix #3 the
    // (unklang-source, en-target) prefix path that "Auto" would map to
    // is the only in-distribution output for Canary — making Auto a
    // misleading UX option there. Hidden entirely for "Use without
    // model" since there is no engine to apply the setting to. See
    // updateLanguagePickerVisibility.
    private View containerLanguagePicker;
    private com.google.android.material.chip.ChipGroup chipGroupLanguage;
    private com.google.android.material.chip.Chip chipLanguageAuto;
    private com.google.android.material.chip.Chip chipLanguageEn;
    private com.google.android.material.chip.Chip chipLanguageEs;
    private com.google.android.material.chip.Chip chipLanguageDe;
    private com.google.android.material.chip.Chip chipLanguageFr;
    private boolean languageSelectionChanging = false;
    // Neutral "what this model covers" subtitle. Always visible so the
    // user sees the model’s language coverage regardless of the
    // ChipGroup picker state (the picker is only relevant for Canary
    // 180M since 0.6B auto-detects via CTC).
    private TextView textModelLanguages;
    private SeekBar seekThreshold;
    private boolean firstLaunchDialogShown = false;
    private boolean modelSelectionChanging = false;
    private ModelDownloadManager.ProgressCallback currentCallback;
    // Tracked so onPause() can dismiss the welcome dialog if a share intent or
    // any other Activity transition pushes us behind another window while the
    // dialog is up — setCancelable(false) blocks user-initiated back-button
    // dismissal, so without an explicit onPause dismiss() the dialog stays
    // visible-and-alive with leaky lambda captures until process death.
    private androidx.appcompat.app.AlertDialog welcomeDialog;
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
        setupLanguagePicker(settingsManager);
        textModelLanguages = findViewById(R.id.text_model_languages);

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
                // Set firstLaunchDialogShown AFTER showFirstLaunchDownloadDialog
                // returns successfully: if dialog.show() throws (e.g. the
                // StaticLayout crash we hit with multi-line button text)
                // or the Activity is paused (system permission/optimisation
                // prompts) before the dialog attaches, the flag stays false
                // and the dialog can re-fire on the next onResume.
                showFirstLaunchDownloadDialog(settingsManager);
                firstLaunchDialogShown = true;
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
        // See comment on welcomeDialog field. setCancelable(false) on the
        // dialog means user-initiated back-button dismissal is blocked; if
        // we get pushed behind another Activity (e.g. a share intent), we
        // need to dispose the dialog ourselves or it will leak through the
        // lambda captures of the click listeners.
        if (welcomeDialog != null && welcomeDialog.isShowing()) {
            welcomeDialog.dismiss();
        }
        welcomeDialog = null;
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
        selectRadioButton(current);
        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
        // selectRadioButton() above already calls updateLanguagePickerVisibility
        // and updateLanguagesSubtitle, so they stay in lockstep with the
        // selected radio from any caller path (download completion,
        // confirm-delete auto-fallback, welcome dialog buttons).

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
        // Publish to the cross-component broadcaster FIRST so the IME
        // listener can react instantly (its callback is marshalled onto
        // the main thread by EngineStateBroadcaster.setState via a
        // CopyOnWriteArrayList of registered listeners). The Rust engine
        // only fires this onStatusUpdate on the MainActivity JObject — so
        // without this relay, the IME has no way to know when the engine
        // is warming up or has just become ready. Preferring the broadcast
        // over a direct IPC keeps the Rust-side toy simple (single JObject
        // target per notify_status call) and gives us a clean place to
        // centralise the loading/transcribing predicate heuristics
        // (see EngineStateBroadcaster.isLoading et al.).
        EngineStateBroadcaster.setState(status);
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

    private native void nativeSetLanguage(MainActivity activity, String lang);

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
        selectRadioButton(current);

        updateModelStatus(modelStatus, current, sm);
        updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);

        btnDeleteFast.setOnClickListener(v -> confirmDeleteModel("0.6b", sm));
        btnDeleteModelFastest.setOnClickListener(v -> confirmDeleteModel("180m", sm));

        btnRetry.setOnClickListener(v -> {
            String variant = sm.getModelVariant();
            btnRetry.setVisibility(View.GONE);
            startDownload(variant, sm);
        });

        // Per-button OnCheckedChangeListener, NOT
        // modelGroup.setOnCheckedChangeListener. RadioGroup's internal
        // CheckedStateTracker is only wired to its DIRECT children (it
        // uses addView() to find them); because each MaterialRadioButton
        // sits inside its own horizontal LinearLayout row (so the
        // per-row delete ImageButton can sit beside it), RadioGroup can
        // neither see state changes from user taps nor propagate the
        // "auto-uncheck sibling" behavior across the wrappers. The
        // user-tap listener on the RadioGroup is therefore dead code
        // (it never fires for taps on rb_model_fastest / rb_model_fast /
        // rb_model_none) and even when invoked artificially via
        // setChecked, the previously-checked radio stays visually
        // selected. We bypass RadioGroup entirely: each radio carries
        // its own checked-change listener routed through
        // onVariantSelectedByUser, which calls selectRadioButton() to
        // enforce "exactly one radio visually checked" before dispatch.
        attachModelRadioListener(rbModelFastest, "180m");
        attachModelRadioListener(rbModelFast,    "0.6b");
        attachModelRadioListener(rbModelNone,    "none");
    }

    /**
     * Wire a single {@link RadioButton} (one of the three model-selection
     * radios) such that a user tap on it routes through
     * {@link #onVariantSelectedByUser}. Two early-returns keep the
     * listener quiet during the {@code setChecked} cascade invoked by
     * {@link #selectRadioButton}: {@code !isChecked} drops sibling
     * clears (only the user-driven tap that turns a radio from
     * unchecked to checked should propagate) and
     * {@code modelSelectionChanging} drops the programmatic sync
     * windows (initial setup, onResume rehydrate, post-download
     * completion, confirm-delete auto-fallback, welcome-dialog
     * buttons). Without the flag the listener would re-enter the
     * dispatch on every programmatic clear and we'd double-fire
     * switchModel + startDownload for a single user action.
     */
    private void attachModelRadioListener(RadioButton rb, String variant) {
        if (rb == null) return;
        rb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) return;
            if (modelSelectionChanging) return;
            onVariantSelectedByUser(variant);
        });
    }

    /**
     * Dispatch a user-driven request to switch the active model variant:
     * persist the prefs, force the radio visual state to converge on
     * the chosen button (closes the "two radios appear selected"
     * regression — see {@link #selectRadioButton}), then either
     * kick off a download if the variant isn't present on disk, fire
     * a switch to a downloaded variant, or signal that the engine
     * will be unloaded. Replaces the inline dispatch logic that
     * used to live in a {@code modelGroup.setOnCheckedChangeListener}
     * callback — that callback never fired because RadioGroup only
     * tracks its direct children. Per-button listeners are the
     * actual fix; this helper centralizes the dispatch so every
     * entry point (user tap, future programmatic caller) goes
     * through exactly one code path.
     */
    private void onVariantSelectedByUser(String variant) {
        SettingsManager sm = settingsManager;
        if (sm == null) return;
        sm.setModelVariant(variant);
        if (btnRetry != null) btnRetry.setVisibility(View.GONE);

        // Force "exactly one radio visually checked" — see comment on
        // selectRadioButton(). Without this re-sync a user tap on a
        // different radio leaves the previously-checked one visually
        // selected (RadioGroup's direct-child auto-uncheck cannot see
        // across the LinearLayout wrappers). Safe to call synchronously
        // from inside a dispatch because selectRadioButton() sets
        // modelSelectionChanging=true across its setChecked cascade,
        // and every sibling listener re-entry is filtered by that flag
        // — see the javadoc on selectRadioButton for the full chain.
        selectRadioButton(variant);

        if ("none".equals(variant)) {
            updateModelStatus(modelStatus, variant, sm);
            updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
            statusText.setText(getString(R.string.model_switch_restart));
            switchModelAsync(variant);
        } else if (sm.isModelDownloaded(variant)) {
            updateModelStatus(modelStatus, variant, sm);
            statusText.setText(getString(R.string.model_switch_restart));
            switchModelAsync(variant);
            String switchMsg = getString(R.string.model_switch_restart);
            mainHandler.postDelayed(() -> {
                if (switchMsg.equals(statusText.getText().toString())) {
                    statusText.setText(getString(R.string.model_switch_timeout));
                }
            }, 30000);
        } else {
            startDownload(variant, sm);
        }
    }

    private void switchModelAsync(String variant) {
        // Fire an immediate "Switching model…" on the broadcaster so the
        // IME listener reacts without waiting for Rust's first
        // notify_status("Loading fast/fastest model...") — that is fired
        // asynchronously on a JNI worker thread and may not reach the
        // bridge-to-broadcaster hop for a few hundred ms after we
        // dispatch the JNI switchModel call. With this pre-fire the IME
        // status view + record-button-disabled state appear instantly.
        EngineStateBroadcaster.setState("Switching model\u2026");
        WeakReference<MainActivity> switchRef = new WeakReference<>(this);
        new Thread(() -> {
            MainActivity a = switchRef.get();
            if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                a.switchModel(a, variant);
            }
        }).start();
    }

    /**
     * Programmatic equivalent of tapping one of the three model radios.
     * <p>
     * The three {@code MaterialRadioButton}s sit inside {@code LinearLayouts}
     * (so the delete button can sit beside the radio on the same row), which
     * means {@code RadioGroup}'s direct-child auto-uncheck traversal is
     * unreliable across Android versions and after rapid programmatic
     * switches it can leave more than one radio visually selected. We fix
     * that defensively by explicitly {@code clearCheck()}-ing the group
     * and calling {@code setChecked} on every RadioButton ourselves,
     * with the {@link #modelSelectionChanging} flag suppressing the
     * {@code onCheckedChange} listener so we don't recursively trigger
     * an engine switch from our own state update.
     */
    private void selectRadioButton(String variant) {
        if (rbModelFastest == null || rbModelFast == null || rbModelNone == null
                || modelGroup == null) {
            return;
        }
        boolean prev = modelSelectionChanging;
        modelSelectionChanging = true;
        modelGroup.clearCheck();
        rbModelFastest.setChecked(false);
        rbModelFast.setChecked(false);
        rbModelNone.setChecked(false);
        if ("180m".equals(variant)) {
            rbModelFastest.setChecked(true);
        } else if ("none".equals(variant)) {
            rbModelNone.setChecked(true);
        } else {
            rbModelFast.setChecked(true);
        }
        modelSelectionChanging = prev;
        // Keep the picker visibility AND the languages subtitle in lockstep
        // with whatever radio is now checked. Catches the cases where
        // selectRadioButton is called from outside the user-tap path
        // (download completion, confirm-delete auto-fallback, welcome
        // dialog buttons).
        updateLanguagePickerVisibility(variant);
        updateLanguagesSubtitle(variant);
    }

    /**
     * Populate the persistent "Languages: …" subtitle with the coverage
     * list for the currently-selected variant. Always visible regardless
     * of the ChipGroup picker state. Defensive against null view in case
     * onCreate never bound it (e.g. early crash path).
     */
    private void updateLanguagesSubtitle(String variant) {
        if (textModelLanguages == null) return;
        if ("180m".equals(variant)) {
            textModelLanguages.setText(R.string.model_card_languages_canary_compact);
            textModelLanguages.setVisibility(View.VISIBLE);
        } else if ("0.6b".equals(variant)) {
            textModelLanguages.setText(R.string.model_card_languages_parakeet_compact);
            textModelLanguages.setVisibility(View.VISIBLE);
        } else {
            // "none" — no engine loaded, no coverage to advertise.
            textModelLanguages.setVisibility(View.GONE);
        }
    }

    /**
     * Show the language picker for both Canary 180M and Parakeet 0.6B v3
     * (and hide it for "none"). The native set_language call is a no-op
     * on 0.6B but the UI picker is still surfaced so the user has a
     * consistent place to record their intent — the picker state is
     * preserved across a 180m ↔ 0.6b round-trip on the same session.
     *
     * <p>Auto-chip gating per variant: visible for 0.6B (CTC auto-detects
     * natively), hidden for 180M because per hotfix #3 the
     * (unklang-source, en-target) decoder-prefix path — the only thing
     * "Auto" could map to — produces English output regardless of the
     * audio language. Forcing the user to pick a real language keeps
     * the pref honest.
     *
     * <p>Side effect: when switching TO 180m and the user's last saved
     * language was "auto", promote the preference to "en" so the
     * ChipGroup (selectionRequired=true) always has at least one valid
     * visible chip checked. The chip-group mutation is wrapped in
     * languageSelectionChanging so the OnCheckedStateChangeListener
     * does not bounce through another pref write + nativeSetLanguage
     * round-trip — the Rust engine will read the now-stored "en" from
     * SharedPreferences on the next 180m load.
     */
    private void updateLanguagePickerVisibility(String variant) {
        if (containerLanguagePicker == null) return;

        boolean showPicker = !"none".equals(variant);
        containerLanguagePicker.setVisibility(showPicker ? View.VISIBLE : View.GONE);

        // Hide Auto only for 180m. All five chips remain visible for 0.6b
        // (Auto is the actual model default for CTC auto-detection).
        if (chipLanguageAuto != null) {
            chipLanguageAuto.setVisibility("180m".equals(variant) ? View.GONE : View.VISIBLE);
        }

        if (showPicker && "180m".equals(variant) && settingsManager != null
                && chipGroupLanguage != null && chipLanguageEn != null) {
            String saved = settingsManager.getTranscriptionLanguage();
            if (saved == null) saved = "auto";
            if ("auto".equals(saved)) {
                settingsManager.setTranscriptionLanguage("en");
                // Wrap selection so the listener does not re-fire after
                // we just wrote the pref ourselves (would otherwise
                // produce an extra nativeSetLanguage round-trip and a
                // redundant Snackbar).
                boolean prev = languageSelectionChanging;
                languageSelectionChanging = true;
                chipGroupLanguage.clearCheck();
                chipLanguageEn.setChecked(true);
                languageSelectionChanging = prev;
            }
        }
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
                    a.selectRadioButton(variant);
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
                        // Auto-fallback to the *other* downloaded variant when
                        // the user deletes the currently-active one. We update
                        // the prefs *before* the radio selection so the helper
                        // sees the canonical variant name.
                        String targetVariant = "180m".equals(variant) ? "0.6b" : "180m";
                        sm.setModelVariant(targetVariant);
                        selectRadioButton(targetVariant);
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
        // Use a custom view instead of setPositive/Neutral/Negative buttons
        // so we can lock the L→R order to Fastest → Fast → Use without
        // model. The default MaterialAlertDialog button bar paints NEG | NEU
        // | POS from left to right — which would render as
        // Use without model | Fast | Fastest — and that doesn't match the
        // top→bottom order of the model selector card in activity_main.xml
        // (which the user expects to be the same "viñeta").
        View view = getLayoutInflater().inflate(R.layout.dialog_welcome_model, null);
        com.google.android.material.button.MaterialButton btnFastest =
                view.findViewById(R.id.button_fastest);
        com.google.android.material.button.MaterialButton btnFast =
                view.findViewById(R.id.button_fast);
        com.google.android.material.button.MaterialButton btnSkip =
                view.findViewById(R.id.button_skip);

        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.welcome_model_title)
                .setMessage(R.string.welcome_model_subtitle)
                .setCancelable(false)
                .setView(view)
                .show();
        // Track so onPause() can dispose the dialog when MainActivity is
        // paused while the dialog is up — setCancelable(false) blocks
        // back-button dismissal, so we need an explicit Activity-lifecycle hook.
        welcomeDialog = dialog;

        btnFastest.setOnClickListener(v -> {
            sm.setModelVariant("180m");
            selectRadioButton("180m");
            startDownload("180m", sm);
            dialog.dismiss();
            welcomeDialog = null;
        });
        btnFast.setOnClickListener(v -> {
            sm.setModelVariant("0.6b");
            selectRadioButton("0.6b");
            startDownload("0.6b", sm);
            dialog.dismiss();
            welcomeDialog = null;
        });
        btnSkip.setOnClickListener(v -> {
            sm.setModelVariant("none");
            selectRadioButton("none");
            updateModelStatus(modelStatus, "none", sm);
            updateDeleteButtons(btnDeleteFast, btnDeleteModelFastest, sm);
            dialog.dismiss();
            welcomeDialog = null;
        });
    }

    /**
     * Mirror of {@link #setupModelSelection} for the Canary 180M language
     * picker ChipGroup. Resolves all chip views, sets whichever chip is
     * currently selected from the prefs (default {@code "auto"} maps to
     * the Auto chip), and wires the {@code OnCheckedChange} listener so
     * each tap persists the pref via {@link SettingsManager#setTranscriptionLanguage}
     * and pushes the change into the loaded engine via
     * {@link #nativeSetLanguage}. The native call is a no-op if the
     * current engine is 0.6B, so we don't have to branch on the variant
     * here — visibility is already gated by
     * {@link #updateLanguagePickerVisibility}.
     */
    private void setupLanguagePicker(SettingsManager sm) {
        containerLanguagePicker = findViewById(R.id.container_language_picker);
        chipGroupLanguage = findViewById(R.id.chip_group_language);
        chipLanguageAuto = findViewById(R.id.chip_language_auto);
        chipLanguageEn = findViewById(R.id.chip_language_en);
        chipLanguageEs = findViewById(R.id.chip_language_es);
        chipLanguageDe = findViewById(R.id.chip_language_de);
        chipLanguageFr = findViewById(R.id.chip_language_fr);

        // Null-guard every view: if any chip or container is missing the
        // row simply doesn't participate. The visibility toggle in
        // updateLanguagePickerVisibility already short-circuits on null.
        if (containerLanguagePicker == null || chipGroupLanguage == null
                || chipLanguageAuto == null || chipLanguageEn == null
                || chipLanguageEs == null || chipLanguageDe == null
                || chipLanguageFr == null) {
            return;
        }

        // Restore the persisted selection without firing the listener.
        // The flag pattern mirrors selectRadioButton() — the listener
        // side-effect writes prefs and pushes to native; we don't want
        // to fire it during cold-start repopulation.
        String saved = sm.getTranscriptionLanguage();
        if (saved == null) saved = "auto";
        // If the user landed here on the 180m variant with a stale
        // "auto" preference (carry-over from a previous install, from a
        // prior 0.6b session, or default on a fresh install where the
        // pref was never changed), promote it to "en" before checking
        // the chip. The Auto chip is hidden in 180m mode and the
        // ChipGroup has selectionRequired=true, so an unchecked state
        // would render as a broken row. updateLanguagePickerVisibility
        // applies the same promotion during onResume re-snapshots so
        // hot variant switches after cold start also stay consistent.
        String currentVariant = sm.getModelVariant();
        if ("180m".equals(currentVariant) && "auto".equals(saved)) {
            saved = "en";
            sm.setTranscriptionLanguage("en");
        }
        // No listener is attached yet during this restore (it is wired
        // immediately below), so the programmatic setChecked calls here
        // cannot bounce through the chip listener. No flag needed.
        switch (saved) {
            case "en":
                chipLanguageEn.setChecked(true);
                break;
            case "es":
                chipLanguageEs.setChecked(true);
                break;
            case "de":
                chipLanguageDe.setChecked(true);
                break;
            case "fr":
                chipLanguageFr.setChecked(true);
                break;
            default:
                chipLanguageAuto.setChecked(true);
                break;
        }

        // Note: we deliberately do NOT push `saved` to the native engine
        // here. The engine load path (`do_load_180m`) reads the same
        // `transcription_language` preference and applies it on
        // construction, so a fresh download already picks up the user’s
        // last selection. Calling nativeSetLanguage now would hit
        // GLOBAL_ENGINE == None on cold start and just log a
        // "no engine loaded" error — harmless but noisy in logcat.

        chipGroupLanguage.setOnCheckedStateChangeListener((group, checkedIds) -> {
            // Flag is unused at present (listener attached after restore),
            // but kept as a defensive guard so any future caller that
            // programmatically setChecked before this listener fires does
            // not trigger a pref write / native round-trip.
            if (languageSelectionChanging) return;
            if (checkedIds == null || checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            String lang;
            if (checkedId == R.id.chip_language_auto) {
                lang = "auto";
            } else if (checkedId == R.id.chip_language_en) {
                lang = "en";
            } else if (checkedId == R.id.chip_language_es) {
                lang = "es";
            } else if (checkedId == R.id.chip_language_de) {
                lang = "de";
            } else if (checkedId == R.id.chip_language_fr) {
                lang = "fr";
            } else {
                return;
            }
            sm.setTranscriptionLanguage(lang);
            nativeSetLanguage(this, lang);
            // Confirm in UI so the user knows the next dictation will use
            // their selection. Map the pref code to the readable chip
            // label so the user sees "Language: Spanish" not "Language: ES".
            String label;
            switch (lang) {
                case "en": label = getString(R.string.language_english); break;
                case "es": label = getString(R.string.language_spanish); break;
                case "de": label = getString(R.string.language_german); break;
                case "fr": label = getString(R.string.language_french); break;
                default:   label = getString(R.string.language_auto); break;
            }
            snackbar(getString(R.string.msg_language_set, label));
        });
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
