# Spec: Fix force-close crash on first-launch model download

## Context

Android app `OfflineVoiceInput` (package `dev.notune.transcribe`). Java + Rust (JNI).  
targetSdk 35, minSdk 26, compileSdk 35.

**Problem**: On first launch, the app shows battery-optimization and notification
permission dialogs. After granting both, tapping "Fastest (180M)" in the welcome
model-download dialog crashes the app with force-close.

**Likely also affects** "Fast (0.6B)" — user only tested with Fastest.

---

## Root Cause Analysis

### A) `startForegroundService` without try-catch — CRASH CAUSE #1

`MainActivity.startDownload()` at line 479:
```java
startForegroundService(intent);
```
No try-catch. On Android 12+ (API 31), if the system considers the app to be
in the background, this throws `ForegroundServiceStartNotAllowedException`.

**Why it triggers**: `requestBatteryOptimizationExemption()` at line 180 calls
`startActivity(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)`, which
pushes MainActivity to onStop/background. When the user returns and taps a
dialog button, the system may still consider the app briefly backgrounded.

### B) `startForeground()` without try-catch in the Service — CRASH CAUSE #2

`ModelDownloadForegroundService.onStartCommand()` line 55-57:
```java
startForeground(NOTIFICATION_ID,
    createProgressNotification(0, modelName),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
```
No try-catch. Any exception here (SecurityException, foreground type mismatch,
notification permission race) kills the service and the app process.

### C) Race condition in Rust model-loading state machine — CRASH CAUSE #3

`src/engine.rs`, function `switch_model()` (lines 260-271):
- Sets `LOAD_STATE` to `Idle`, then calls `do_load_with_variant`.
- `do_load_with_variant` → `do_load_0_6b` / `do_load_180m` sets `GLOBAL_ENGINE`
  but **never updates `LOAD_STATE` to `Done` or `Failed`**.
- If `initNative` thread (spawned at `MainActivity.java:183`) is still executing
  `ensure_loaded_from_thread` concurrently, it sees `Idle` and enters the
  loading path, causing a race on `GLOBAL_ENGINE`.

Additionally, `LOAD_STATE` is never set to `Loading` during `switch_model`,
so a concurrent caller of `ensure_loaded`/`ensure_loaded_from_thread` will
not wait — it will try to load simultaneously.

### D) Dialog shown during onCreate — lifecycle issue

`showFirstLaunchDownloadDialog()` is called from `setupModelSelection()` which
is called **inside `onCreate()`** (line 177), **before** the permission dialogs
and battery-optimization intent (lines 179-180). This means the dialog exists
through multiple activity lifecycle transitions (pause→resume, stop→restart).
The dialog's button handlers capture `this` (MainActivity) and `sm`
(SettingsManager), which may become stale if the activity is recreated.

### E) Unused `encoder.int8.weights` in 180M download list

`ModelDownloadManager.java` line 54 includes `"encoder.int8.weights"` in the
180m file list, but `do_load_180m` in `engine.rs` never reads it.
This file is ~1.1 GB — wasted bandwidth and storage.

### F) `switchModel` native call not wrapped in Java try-catch

`MainActivity.java` line 504 and 546:
```java
new Thread(() -> switchModel(MainActivity.this, variant)).start();
```
The native method can crash (OOM, corrupt ONNX file, etc.) without any Java-side
protection. A native crash (SIGSEGV) takes down the entire process.

---

## Implementation Plan

### Step 1 — Protect `startForegroundService` in MainActivity.startDownload()

**File**: `app/src/main/java/dev/notune/transcribe/MainActivity.java`  
**Method**: `startDownload(String variant, SettingsManager sm)` (lines 470-480)

Wrap the `startForegroundService(intent)` call in try-catch that handles:
- `ForegroundServiceStartNotAllowedException` (API 31+)
- Generic `Exception`

On failure, fall back to starting the download directly from the Application
class (which uses WakeLock instead of foreground service):

```java
private void startDownload(String variant, SettingsManager sm) {
    modelProgress.setVisibility(View.VISIBLE);
    modelProgress.setProgress(0);
    btnRetry.setVisibility(View.GONE);
    modelStatus.setText(getString(R.string.model_status_downloading, 0));

    Intent intent = new Intent(this, ModelDownloadForegroundService.class);
    intent.setAction(ModelDownloadForegroundService.ACTION_START);
    intent.putExtra("variant", variant);
    try {
        startForegroundService(intent);
    } catch (Exception e) {
        Log.w(TAG, "Foreground service start failed, falling back to direct download", e);
        ModelDownloadManager.ProgressCallback cb = createDownloadCallback(variant);
        ((App) getApplication()).startDownload(variant, cb);
    }
}
```

Extract the callback creation from `attachDownloadCallbacks` into a reusable
method `createDownloadCallback(String variant)`.

### Step 2 — Protect `startForeground()` in ModelDownloadForegroundService

**File**: `app/src/main/java/dev/notune/transcribe/ModelDownloadForegroundService.java`  
**Method**: `onStartCommand()` (lines 33-91)

Wrap the `startForeground()` call in try-catch. On failure, still start the
download but log the error and skip the foreground notification:

```java
try {
    startForeground(NOTIFICATION_ID,
            createProgressNotification(0, modelName),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
} catch (Exception e) {
    Log.w(TAG, "startForeground failed, continuing without notification", e);
    // Fall through — still start the download
}
```

Also add try-catch around the entire `onStartCommand` body to prevent any
unexpected exception from killing the service process.

### Step 3 — Fix Rust LOAD_STATE machine in engine.rs

**File**: `src/engine.rs`  
**Functions**: `switch_model()` (lines 260-271), `do_load_with_variant()` (line 278)

Replace `switch_model` with a version that properly manages `LOAD_STATE`:

```rust
pub fn switch_model(env: &mut JNIEnv, context: &JObject, variant: ModelVariant) -> Result<(), String> {
    {
        let mut guard = GLOBAL_ENGINE.lock().unwrap();
        *guard = None;
    }
    
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();
    
    // Wait if another load is in progress
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap();
    }
    
    *state = LoadState::Loading;
    drop(state);
    
    let result = do_load_with_variant(env, context, variant);
    
    let mut state = lock.lock().unwrap();
    match &result {
        Ok(()) => *state = LoadState::Done,
        Err(msg) => *state = LoadState::Failed(msg.clone()),
    }
    cvar.notify_all();
    
    result
}
```

### Step 4 — Defer welcome dialog to onResume

**File**: `app/src/main/java/dev/notune/transcribe/MainActivity.java`

Move `showFirstLaunchDownloadDialog` call from `setupModelSelection` (called in
`onCreate`) to `onResume()`. Add a boolean flag to show it only once.

In the class body, add:
```java
private boolean firstLaunchDialogShown = false;
```

In `setupModelSelection`, remove the dialog call (lines 433-436). Instead, add
to `onResume()`:
```java
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
```

### Step 5 — Add null-safety in dialog button handlers

**File**: `app/src/main/java/dev/notune/transcribe/MainActivity.java`  
**Method**: `showFirstLaunchDownloadDialog()` (lines 563-584)

Wrap all view accesses in null checks. This is purely defensive but prevents
crashes if the activity was recreated while the dialog was alive.

### Step 6 — Remove unused `encoder.int8.weights` from 180m file list

**File**: `app/src/main/java/dev/notune/transcribe/ModelDownloadManager.java`  
**Lines 53-59**

Remove `"encoder.int8.weights"` from the array:
```java
MODEL_FILES.put("180m", new String[]{
    "encoder-model.int8.onnx",
    "decoder-model.int8.onnx",
    "vocab.txt"
});
```

Also update `SettingsManager.isModelDownloaded()` (line 219) which checks
`files.length >= 3`. For 180m, it should now be `>= 3` (was `>= 4` with 5 files,
now will be 3). This is fine — no change needed.

### Step 7 — Wrap native `switchModel` call with try-catch

**File**: `app/src/main/java/dev/notune/transcribe/MainActivity.java`  
**Lines**: 504 and 546

```java
new Thread(() -> {
    try {
        switchModel(MainActivity.this, variant);
    } catch (Exception e) {
        Log.e(TAG, "switchModel native call failed", e);
        runOnUiThread(() -> statusText.setText("Status: Failed to load model"));
    }
}).start();
```

Also at line 504 (in `attachDownloadCallbacks`'s `onComplete` callback).

---

## Files to modify (summary)

| File | Changes |
|---|---|
| `app/src/main/java/dev/notune/transcribe/MainActivity.java` | Steps 1, 4, 5, 7 |
| `app/src/main/java/dev/notune/transcribe/ModelDownloadForegroundService.java` | Step 2 |
| `src/engine.rs` | Step 3 |
| `app/src/main/java/dev/notune/transcribe/ModelDownloadManager.java` | Step 6 |

**No new files needed.** No new dependencies needed.

---

## Verification

After implementing:
1. Fresh install the app
2. Grant battery optimization when prompted
3. Grant notification permission when prompted
4. Tap "Fastest (180M)" in the welcome dialog
5. Verify: app does not crash, download starts, progress shows
6. Verify: download completes, model loads, status shows "Ready"
7. Repeat with "Fast (0.6B)"
