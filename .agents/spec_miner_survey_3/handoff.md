# Specification Handoff Report — Spec Miner Survey 3
**Floating Overlay UI Requirements (Whisperflow Style), i18n Translation Catalog & JVM Unit Test Suites**

- **Author**: Spec Miner Survey 3
- **Date**: 2026-08-12
- **Working Directory**: `/root/GitHub/android_transcribe_app/.agents/spec_miner_survey_3`
- **Target Feature**: Floating Bubble Dictation Overlay (Whisperflow Style) & Auto-Paste Integration (`feature/floating-bubble-dictation`)

---

## 1. Observation

Direct code and environment observations:
1. **WindowManager Overlay Handling in Existing Codebase**:
   - `LiveSubtitleService.java` (lines 231–252) demonstrates WindowManager overlay setup using `TYPE_APPLICATION_OVERLAY` for Android 8.0+ (`Build.VERSION.SDK_INT >= 26`) with fallback to `TYPE_PHONE` for legacy versions.
   - Flags used: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_KEEP_SCREEN_ON`, translucent pixel format (`PixelFormat.TRANSLUCENT`).
   - Touch drag logic in `LiveSubtitleService.java` (lines 261–291) uses `View.OnTouchListener` capturing `ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP` and updates `WindowManager.LayoutParams.y` dynamically via `WindowManager.updateViewLayout()`.
2. **Post-Processing & Marker File State Integration**:
   - `SettingsManager.java` (lines 48, 115–125) manages post-processing via marker file `pp_enabled` located in `context.getFilesDir()`.
   - `MarkerFileHelper.java` provides atomic file reads/writes (`exists()`, `setExists()`, `writeString()`, `readString()`).
   - `PostProcessor.java` (lines 208–248) inspects `settings.isPostProcessEnabled()` before invoking OpenAI-compatible `/chat/completions` API calls and supports per-owner call cancellation via `PostProcessor.cancelAllFor(owner)`.
3. **i18n Translation Catalog & Parity Verification**:
   - `scripts/check_translations.py` verifies translation completeness across 6 alternate locales (`values-de/`, `values-es/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/`) against the base locale `values/strings.xml` (English).
   - Any translatable `<string>` tag added to `values/strings.xml` without `translatable="false"` **must** be present in all 6 alternate `strings.xml` files, or `check_translations.py` exits with status 1 (CI fail).
4. **JVM Unit Test Harness**:
   - `app/build.gradle.kts` (lines 124–134) enables `isIncludeAndroidResources = true` and `isReturnDefaultValues = true`.
   - `app/build.gradle.kts` (lines 293–300) explicitly skips native `cargoNdkBuild` when running unit tests (`isUnitTestTask`), allowing `./gradlew testDebugUnitTest` to execute fast isolated JVM tests without trigger native Rust cross-compilation.

---

## 2. Features Discovered

| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Overlay UI | Floating Dictation Service | Service launching WindowManager overlay bubble staying on top of all apps | `SYSTEM_ALERT_WINDOW` permission, `Intent` start/stop | Visible draggable overlay bubble/window | Logs error if overlay permission missing or revoked | `LiveSubtitleService.java`, `ORIGINAL_REQUEST.md` |
| 2 | Overlay UI | WindowManager Overlay Layout | Configures `TYPE_APPLICATION_OVERLAY` (API 26+) or `TYPE_PHONE` with translucent format and `FLAG_NOT_FOCUSABLE` | Layout params (`WRAP_CONTENT`, gravity `TOP|START`) | Added View in WindowManager | Fallback to `TYPE_PHONE` on API < 26 | `LiveSubtitleService.java` |
| 3 | Overlay UI | Touch Drag & Tap Detection | Distinguishes dragging from click/tap using movement slop threshold (`touchSlop`) and duration | `MotionEvent` (`DOWN`, `MOVE`, `UP`) | Window movement vs state expansion toggle | Resets touch state on `ACTION_CANCEL` | `LiveSubtitleService.java` |
| 4 | Overlay UI | Collapsed Bubble View | Compact floating circular widget displaying microphone status icon | Tap gesture | Expands to full dictation panel | Displays idle/listening indicator state | `ORIGINAL_REQUEST.md` §R2 |
| 5 | Overlay UI | Expanded Panel View | Floating card displaying status, cancel button, AI Fix toggle, live streaming text window, paste action button | Tap gesture on collapsed bubble | Expanded container layout | Collapses back to bubble on tap outside or cancel | `ORIGINAL_REQUEST.md` §R2 |
| 6 | Overlay UI | Live Streaming Transcription Window | Displays partial hypotheses (`onPartialText`) while recording, updated atomically by final text (`onTextTranscribed`) | Native JNI callbacks | Real-time updating `TextView` | Shows error message if ASR fails | `RecognizeActivity.java`, `LiveSubtitleService.java` |
| 7 | Settings | AI Fix Toggle Integration | Switch control toggling `"pp_enabled"` marker file in `filesDir()` | User tap on AI Fix toggle button | `MarkerFileHelper.setExists(ctx, "pp_enabled", val)` | Bypasses post-processing if marker absent or key missing | `SettingsManager.java`, `PostProcessor.java` |
| 8 | Accessibility | Auto-Paste Text Insertion | Accessibility service inserting transcribed text into currently focused editable field (`ACTION_PASTE` or `ACTION_SET_TEXT`) | Transcribed/refined text string | Text inserted into target app input node | Fallback to System Clipboard if no focused field or service disabled | `ORIGINAL_REQUEST.md` §R1 |
| 9 | i18n Catalog | 7-Locale Translation Parity | Multi-language catalog enforcing parity across `values/`, `values-es/`, `values-de/`, `values-fr/`, `values-it/`, `values-pt/`, `values-ru/` | String resource definitions | 100% translation coverage | `check_translations.py` fails CI if any key missing | `scripts/check_translations.py` |
| 10 | Testing | Isolated JVM Unit Test Suite | JVM unit tests verifying helper classes and state logic without Rust NDK builds | JUnit 4 test runner | Pass/Fail report | `testDebugUnitTest` fails if any assertion fails | `app/build.gradle.kts` |

---

## 3. Edge Cases

| # | Feature | Input | Observed / Required Behavior |
|---|---------|-------|------------------------------|
| 1 | Overlay Dragging | Dragging bubble beyond screen boundaries | Clamp `params.x` and `params.y` within screen bounds (`DisplayMetrics.widthPixels` and `heightPixels`). |
| 2 | Touch Gesture | Finger moves 3px then released within 100ms | Movement < `touchSlop` (~8-16px) -> treat as TAP click, toggling collapse/expand state rather than moving. |
| 3 | Permission Revocation | User revokes `SYSTEM_ALERT_WINDOW` while service is active | Catch `BadTokenException` / SecurityException when adding view; display toast and stop service gracefully. |
| 4 | AI Post-Processing | Post-processing fails due to network error or missing API key | Deliver raw ASR transcript to text window and paste button immediately without blocking UI. |
| 5 | Accessibility Target | Focus is in a password field or non-editable view | `AccessibilityNodeInfo.isEditable()` is false -> fall back to copying text to Clipboard and showing toast. |
| 6 | Service Lifecycle | Service destroyed while ASR recording is active | Cancel JNI voice session, unregister callbacks, cancel `PostProcessor` requests owned by service. |
| 7 | i18n Translation Check | New string added to `values/strings.xml` without adding to `values-es/...` | `scripts/check_translations.py` fails with gap report and non-zero exit code. |

---

## 4. Architectural Specifications & Interface Contracts

### 4.1 WindowManager Overlay Architecture (`FloatingDictationService`)

- **Class Name**: `dev.notune.transcribe.FloatingDictationService` (extends `android.app.Service`)
- **Foreground Service Channel**:
  - `CHANNEL_ID`: `"FloatingDictationChannel"`
  - `NOTIFICATION_ID`: `54321`
  - `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` or `FOREGROUND_SERVICE_TYPE_MANIFEST` (API 34 compliant)
- **WindowManager.LayoutParams Configuration**:
  ```java
  int layoutType;
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
  } else {
      layoutType = WindowManager.LayoutParams.TYPE_PHONE;
  }

  WindowManager.LayoutParams params = new WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          layoutType,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                  | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                  | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
          PixelFormat.TRANSLUCENT
  );
  params.gravity = Gravity.TOP | Gravity.START;
  params.x = MarkerFileHelper.readInt(context, "floating_x", 100);
  params.y = MarkerFileHelper.readInt(context, "floating_y", 200);
  ```

- **Touch Handling & Drag/Click Distinction**:
  ```java
  overlayView.setOnTouchListener(new View.OnTouchListener() {
      private float downRawX, downRawY;
      private int downParamsX, downParamsY;
      private long downTime;
      private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

      @Override
      public boolean onTouch(View v, MotionEvent event) {
          switch (event.getAction()) {
              case MotionEvent.ACTION_DOWN:
                  downRawX = event.getRawX();
                  downRawY = event.getRawY();
                  downParamsX = params.x;
                  downParamsY = params.y;
                  downTime = System.currentTimeMillis();
                  return true;

              case MotionEvent.ACTION_MOVE:
                  float dx = event.getRawX() - downRawX;
                  float dy = event.getRawY() - downRawY;
                  if (Math.hypot(dx, dy) > touchSlop) {
                      params.x = downParamsX + (int) dx;
                      params.y = downParamsY + (int) dy;
                      clampParamsToScreen(params);
                      windowManager.updateViewLayout(overlayView, params);
                  }
                  return true;

              case MotionEvent.ACTION_UP:
                  float moveDist = (float) Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY);
                  long duration = System.currentTimeMillis() - downTime;
                  if (moveDist <= touchSlop && duration < ViewConfiguration.getTapTimeout()) {
                      toggleExpandState();
                  } else {
                      MarkerFileHelper.writeInt(context, "floating_x", params.x);
                      MarkerFileHelper.writeInt(context, "floating_y", params.y);
                  }
                  return true;
          }
          return false;
      }
  });
  ```

### 4.2 AI Fix Toggle Marker File Binding

- **Marker File**: `pp_enabled` in `context.getFilesDir()`.
- **Toggle Handling**:
  ```java
  boolean isEnabled = SettingsManager.isPostProcessEnabled(context);
  // Toggle action:
  SettingsManager.setPostProcessEnabled(context, !isEnabled);
  updateAiFixButtonUI(!isEnabled);
  ```
- **ASR Post-Processing Integration**:
  When JNI receives `onTextTranscribed(String text)`:
  If `SettingsManager.isPostProcessEnabled(context)` is true, invoke `PostProcessor.process(text, callback)`. Display "Refining with AI..." on status view. Upon completion, display refined text in live text window and enable Insert/Paste action.

### 4.3 Full Inventory of Required Translation Strings Across All 7 Locales

To pass `scripts/check_translations.py`, all 14 new keys MUST be added to all 7 `strings.xml` files.

#### 1. Base / English (`app/src/main/res/values/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Floating Dictation Overlay</string>
    <string name="floating_service_desc">Display floating bubble for dictation and quick text insertion</string>
    <string name="floating_status_listening">Listening…</string>
    <string name="floating_status_transcribing">Transcribing…</string>
    <string name="floating_status_refining">Refining with AI…</string>
    <string name="floating_status_ready">Ready to paste</string>
    <string name="floating_status_error">Dictation error</string>
    <string name="floating_action_insert">Insert</string>
    <string name="floating_action_cancel">Cancel</string>
    <string name="floating_ai_fix_on">AI Fix: ON</string>
    <string name="floating_ai_fix_off">AI Fix: OFF</string>
    <string name="floating_permission_overlay_required">Overlay permission required for floating bubble</string>
    <string name="floating_accessibility_service_label">Auto-Paste Dictation Helper</string>
    <string name="floating_accessibility_service_desc">Automatically detects focused text fields to insert voice dictations</string>
```

#### 2. Spanish (`app/src/main/res/values-es/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Burbuja Flotante de Dictado</string>
    <string name="floating_service_desc">Muestra una burbuja flotante para dictar e insertar texto rápidamente</string>
    <string name="floating_status_listening">Escuchando…</string>
    <string name="floating_status_transcribing">Transcribiendo…</string>
    <string name="floating_status_refining">Refinando con IA…</string>
    <string name="floating_status_ready">Listo para pegar</string>
    <string name="floating_status_error">Error de dictado</string>
    <string name="floating_action_insert">Insertar</string>
    <string name="floating_action_cancel">Cancelar</string>
    <string name="floating_ai_fix_on">Corrección IA: ON</string>
    <string name="floating_ai_fix_off">Corrección IA: OFF</string>
    <string name="floating_permission_overlay_required">Se requiere permiso de superposición para la burbuja flotante</string>
    <string name="floating_accessibility_service_label">Asistente de Pegado Automático</string>
    <string name="floating_accessibility_service_desc">Detecta automáticamente el campo de texto activo para insertar el dictado de voz</string>
```

#### 3. German (`app/src/main/res/values-de/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Schwebendes Diktat-Overlay</string>
    <string name="floating_service_desc">Zeigt ein schwebendes Symbol für Diktat und schnelles Einfügen von Text an</string>
    <string name="floating_status_listening">Zuhören…</string>
    <string name="floating_status_transcribing">Transkribieren…</string>
    <string name="floating_status_refining">Optimieren mit KI…</string>
    <string name="floating_status_ready">Bereit zum Einfügen</string>
    <string name="floating_status_error">Diktatfehler</string>
    <string name="floating_action_insert">Einfügen</string>
    <string name="floating_action_cancel">Abbrechen</string>
    <string name="floating_ai_fix_on">KI-Korrektur: AN</string>
    <string name="floating_ai_fix_off">KI-Korrektur: AUS</string>
    <string name="floating_permission_overlay_required">Berechtigung zum Überlagern für schwebendes Symbol erforderlich</string>
    <string name="floating_accessibility_service_label">Automatischer Einfüge-Helfer</string>
    <string name="floating_accessibility_service_desc">Erkennt automatisch fokussierte Textfelder zum Einfügen von Sprachdiktaten</string>
```

#### 4. French (`app/src/main/res/values-fr/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Bulle Flottante de Dictée</string>
    <string name="floating_service_desc">Affiche une bulle flottante pour la dictée et l\'insertion rapide de texte</string>
    <string name="floating_status_listening">Écoute…</string>
    <string name="floating_status_transcribing">Transcription…</string>
    <string name="floating_status_refining">Optimisation par IA…</string>
    <string name="floating_status_ready">Prêt à coller</string>
    <string name="floating_status_error">Erreur de dictée</string>
    <string name="floating_action_insert">Insérer</string>
    <string name="floating_action_cancel">Annuler</string>
    <string name="floating_ai_fix_on">Correction IA: ON</string>
    <string name="floating_ai_fix_off">Correction IA: OFF</string>
    <string name="floating_permission_overlay_required">Permission de superposition requise pour la bulle flottante</string>
    <string name="floating_accessibility_service_label">Assistant de Collage Automatique</string>
    <string name="floating_accessibility_service_desc">Détecte automatiquement les champs de texte actifs pour insérer les dictées vocales</string>
```

#### 5. Italian (`app/src/main/res/values-it/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Bolla Fluttuante di Dettatura</string>
    <string name="floating_service_desc">Mostra una bolla fluttuante per la dettatura e l\'inserimento rapido del testo</string>
    <string name="floating_status_listening">Ascolto…</string>
    <string name="floating_status_transcribing">Trascrizione…</string>
    <string name="floating_status_refining">Perfezionamento con IA…</string>
    <string name="floating_status_ready">Pronto da incollare</string>
    <string name="floating_status_error">Errore di dettatura</string>
    <string name="floating_action_insert">Inserisci</string>
    <string name="floating_action_cancel">Annulla</string>
    <string name="floating_ai_fix_on">Correzione IA: ON</string>
    <string name="floating_ai_fix_off">Correzione IA: OFF</string>
    <string name="floating_permission_overlay_required">Autorizzazione di sovrapposizione richiesta per la bolla fluttuante</string>
    <string name="floating_accessibility_service_label">Assistente Incolla Automatico</string>
    <string name="floating_accessibility_service_desc">Rileva automaticamente i campi di testo attivi per inserire le dettature vocali</string>
```

#### 6. Portuguese (`app/src/main/res/values-pt/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Bolha Flutuante de Ditado</string>
    <string name="floating_service_desc">Exibe uma bolha flutuante para ditado e inserção rápida de texto</string>
    <string name="floating_status_listening">Ouvindo…</string>
    <string name="floating_status_transcribing">Transcrevendo…</string>
    <string name="floating_status_refining">Refinando com IA…</string>
    <string name="floating_status_ready">Pronto para colar</string>
    <string name="floating_status_error">Erro de ditado</string>
    <string name="floating_action_insert">Inserir</string>
    <string name="floating_action_cancel">Cancelar</string>
    <string name="floating_ai_fix_on">Correção IA: ON</string>
    <string name="floating_ai_fix_off">Correção IA: OFF</string>
    <string name="floating_permission_overlay_required">Permissão de sobreposição necessária para a bolha flutuante</string>
    <string name="floating_accessibility_service_label">Assistente de Colagem Automática</string>
    <string name="floating_accessibility_service_desc">Detecta automaticamente campos de texto focados para inserir ditados de voz</string>
```

#### 7. Russian (`app/src/main/res/values-ru/strings.xml`)
```xml
    <!-- Floating Overlay UI & Accessibility Service -->
    <string name="floating_service_label">Плавающий виджет набора текста</string>
    <string name="floating_service_desc">Отображает плавающую кнопку для набора текста голосом и быстрой вставки</string>
    <string name="floating_status_listening">Слушаю…</string>
    <string name="floating_status_transcribing">Транскрибация…</string>
    <string name="floating_status_refining">Обработка ИИ…</string>
    <string name="floating_status_ready">Готово к вставке</string>
    <string name="floating_status_error">Ошибка диктовки</string>
    <string name="floating_action_insert">Вставить</string>
    <string name="floating_action_cancel">Отмена</string>
    <string name="floating_ai_fix_on">Коррекция ИИ: ВКЛ</string>
    <string name="floating_ai_fix_off">Коррекция ИИ: ВЫКЛ</string>
    <string name="floating_permission_overlay_required">Требуется разрешение на наложение поверх других окон</string>
    <string name="floating_accessibility_service_label">Автоматическая вставка текста</string>
    <string name="floating_accessibility_service_desc">Автоматически определяет активное текстовое поле для вставки распознанного текста</string>
```

---

### 4.4 Unit Testing Plan for JVM Unit Tests (`testDebugUnitTest`)

Unit tests must be co-located under `app/src/test/java/dev/notune/transcribe/`.
Because `app/build.gradle.kts` sets `isUnitTestTask = true`, running `./gradlew testDebugUnitTest` executes only JVM tests without running heavy Rust NDK compilation.

Proposed Unit Test Classes:
1. `FloatingOverlayStateTest.java`:
   - Validates state transitions of the floating overlay state machine (IDLE -> LISTENING -> TRANSCRIBING -> REFINING -> READY / ERROR).
   - Verifies toggle logic between Collapsed and Expanded states.
2. `FloatingSettingsMarkerTest.java`:
   - Uses `MarkerFileHelper` with temporary directories (`Files.createTempDirectory()`) to verify reading/writing `floating_x`, `floating_y`, and `pp_enabled` marker files.
3. `AccessibilityNodeHelperTest.java`:
   - Pure JVM unit test for helper logic evaluating whether an `AccessibilityNodeInfo` candidate node qualifies for text insertion (`isEditable() == true`, `isEnabled() == true`).

---

## 5. Logic Chain

1. **Observation**: WindowManager overlays in Android Services require permission checks (`Settings.canDrawOverlays(context)`) and type `TYPE_APPLICATION_OVERLAY` on API 26+. `LiveSubtitleService.java` shows existing drag touch handling in the codebase.
2. **Inference**: Creating `FloatingDictationService` as a foreground service with `WindowManager` allows maintaining a persistent floating bubble icon across external applications without taking active focus from target text inputs.
3. **Observation**: Tap vs drag differentiation requires touch slop evaluation (`ViewConfiguration.getScaledTouchSlop()`).
4. **Inference**: Capturing initial `ACTION_DOWN` raw coordinates and comparing total delta against `touchSlop` cleanly isolates window position dragging from single tap expand/collapse events.
5. **Observation**: Post-processing settings use marker files in `getFilesDir()`.
6. **Inference**: AI Fix toggle button on the overlay should read and write `filesDir() / "pp_enabled"` directly, allowing seamless sync with `PostProcessor.java` and main settings.
7. **Observation**: `scripts/check_translations.py` checks all 6 alternate locales against `values/strings.xml`.
8. **Inference**: All 14 proposed new UI strings must be provided in all 7 locales to guarantee CI pass on `check_translations.py`.
9. **Observation**: `app/build.gradle.kts` explicitly bypasses `cargoNdkBuild` when running tasks with `test` or `lint` in their names.
10. **Inference**: JVM unit test suite (`./gradlew testDebugUnitTest`) can be safely executed on mobile/embedded host environments without thermal/CPU overload.

---

## 6. Caveats

- **System Alert Window Permission**: User must explicitly grant "Display over other apps" permission in system settings. The app must prompt the user if `Settings.canDrawOverlays(context)` returns false.
- **Accessibility Service Enabling**: `FloatingDictationAccessibilityService` requires explicit user enablement in Android Accessibility Settings. If disabled, auto-paste will fall back to copying text to the System Clipboard.
- **No Heavy Rust Builds on Host**: The embedded host must NOT execute native `cargoNdkBuild` or full APK builds locally; validation of native APK generation relies on GitHub Actions CI.

---

## 7. Conclusion

The specification for Floating Dictation Overlay (Whisperflow Style), 7-locale i18n catalog, AI Fix marker integration, and JVM unit test suites is fully mapped and ready for implementation.

---

## 8. Verification Method

1. **Translation Catalog Verification**:
   Run:
   ```bash
   python3 scripts/check_translations.py
   ```
   Must output: `[CHECK-TRANSLATIONS] PASS: all 6 locales complete` with exit status 0.

2. **JVM Unit Test Verification**:
   Run:
   ```bash
   ./gradlew testDebugUnitTest
   ```
   Must execute cleanly and report `BUILD SUCCESSFUL` with 0 test failures.
