# GAUNTLETE_PLAN.md — Estado del Guantelete QA en `android_transcribe_app`

> **Propósito:** auditoría y plan de mano-izquierda para el siguiente agente.
> Documenta qué del *Guantelete de Uncle Bob* (AGENTS.md §3) ya está operativo en
> este fork y qué falta por pulir antes de subir al repositorio.
> No modifica lógica de JNI, post-procesado ni el rewrite de `PLAN.md`
> (SettingsManager → marker files), que están fuera del alcance de este work-item.

---

## 1. Contraste origen (por qué existe este archivo)

`Handy-Android` tenía el Guantelete *implementado*; en `android_transcribe_app`
sólo existía como texto normativo en `AGENTS.md §3`/`§5.1` ("validación = build
+ smoke test manual"). Tras el contraste, se decidió operativizarlo. Lo operativo
se resume en la sección 2; la deuda pendiente en la 3.

## 2. Qué ya está operativo (verificado en host)

| Gate | Cómo | Evidencia empírica obtenida |
|---|---|---|
| `testDebugUnitTest` (hard, CI debug+release) | app/build.gradle.kts `testOptions.isIncludeAndroidResources=true` + `testImplementation junit`; 14 tests JVM | `./gradlew testDebugUnitTest checkModels` → **BUILD SUCCESSFUL**; XML: 7+4+3 = **14 tests, 0 failures, 0 errors** |
| `checkModels` (verificación SHA-256) | nueva task en app/build.gradle.kts, registrada en `check` | asset ausente → `verification skipped` (PASS); asset alterado → `checksum mismatch`, **BUILD FAILED exit 1** |
| Paridad i18n | `scripts/check_translations.py` (XML, excluye `translatable="false"`) | **PASS**: 6 locales × 124 strings translatables cada uno |
| Tests Rust `#[cfg(test)]` | `src/audio.rs` (5 tests) + `src/corrector.rs` (15 tests: 9 existentes + 6 nuevos) | Verificados vía crate espejo sin deps nativas (`cargo test` → **20 passed, 0 failed**) |
| `lintDebug` (hard gate, debug CI) | 0 errores; `continue-on-error` eliminado de `debug_telegram.yml` | **0 errors / 103 warnings** tras pagar los 20 legacy (ver §3.1) |
| `rustfmt` | mis adiciones limpias | `rustfmt --check src/audio.rs` clean; aportes en `corrector.rs` no aparecen en el diff |

### Archivos cambiados
```
M  .github/workflows/android_release.yml      # +testDebugUnitTest + checkModels
M  .github/workflows/debug_telegram.yml       # gates: testDebugUnitTest y assembleDebug en invocaciones SEPARADAS (fundirlas salta cargoNdkBuild por el guard isUnitTestTask → APK sin .so); lint hard gate (sin continue-on-error)
M  AGENTS.md                                  # §3 documenta los gates implementados
M  app/build.gradle.kts                       # testOptions + checkModels task + check dep
M  app/src/main/res/values/strings.xml        # translatable="false" en app_name/model_builtin/recognition_service_label
M  app/src/main/AndroidManifest.xml           # xmlns:tools + tools:ignore AppLinkUrlError (VIEW audio/*)
M  app/src/main/java/dev/notune/transcribe/RustInputMethodService.java  # switchToPreviousInputMethodSafe + ContextCompat.registerReceiver
M  app/src/main/java/dev/notune/transcribe/MainActivity.java            # super.onRequestPermissionsResult
M  app/src/main/java/dev/notune/transcribe/LiveSubtitleService.java     # @TargetApi(29) + guard SDK_INT + SuppressLint justificado
M  app/src/main/res/layout/ime_layout.xml     # AppCompatImageView + app:tint (4×)
M  app/src/main/res/layout/service_subtitle.xml  # AppCompatImageButton + app:tint
M  src/audio.rs                               # #[cfg(test)] mod tests (find_quietest_split)
M  src/corrector.rs                           # +6 tests edge-case al módulo existente
?? app/src/test/java/dev/notune/transcribe/MarkerFileHelperPersistenceTest.java
?? scripts/check_translations.py
```

### Evidencia de los 14 tests JVM
```
TEST-dev.notune.transcribe.MarkerFileHelperPersistenceTest.xml  tests=7  failures=0
TEST-dev.notune.transcribe.MarkerFileHelperTest.xml             tests=4  failures=0
TEST-dev.notune.transcribe.SubtitlePrefsTest.xml                tests=3  failures=0
```

## 3. Deuda pendiente (para el siguiente agente)

### 3.1 ✅ RESUELTO (2026-08-03): `lintDebug` promovido a hard gate (0 errores)
Se pagaron los 20 errores legacy y se quitó el `continue-on-error` de
`debug_telegram.yml` (`Lint (hard gate)` → `./gradlew lintDebug`).
`./gradlew lintDebug` → **BUILD SUCCESSFUL, 0 errors / 103 warnings**
(reportes en `app/build/reports/lint-results-debug.html`).

Fix por hallazgo (ninguno toca JNI, callbacks, umbrales ni ABI):
- `RustInputMethodService.java` (5× `NewApi`, `switchToPreviousInputMethod` API 28):
  helper `switchToPreviousInputMethodSafe()` con `@TargetApi(28)` + guard `SDK_INT >= 28`
  (no-op en API < 28, donde el método no existe y antes habría crasheado).
- `RustInputMethodService.java:140` (`UnspecifiedRegisterReceiverFlag`):
  `ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`
  — mismo comportamiento (flags-aware en 33+, plain en el resto); nadie salvo el
  proceso principal (mismo uid) puede disparar una cancelación de PP.
- `MainActivity.java:363` (`MissingSuperCall`): añadido `super.onRequestPermissionsResult(...)`.
- `LiveSubtitleService.java:247-270` (6× `NewApi`, AudioPlaybackCapture API 29):
  `@TargetApi(29)` en `startAudioCapture()` + guard `SDK_INT >= 29` en
  `startSubtitleSession()` con `stopSelf()` limpio en API < 29 (antes habría
  crasheado con NoClassDefFoundError). `@SuppressLint("MissingPermission")`
  justificado: la captura de audio del sistema vía MediaProjection NO requiere
  `RECORD_AUDIO` (el consentimiento de proyección es el gate), y el try/catch
  existente cubre ROMs que lo exijan igualmente.
- `ime_layout.xml` (4×) + `service_subtitle.xml` (`UseAppTint`): `android:tint` →
  `app:tint` y los widgets pasan a `AppCompatImageView`/`AppCompatImageButton`
  explícitos. Obligatorio porque el IME y el overlay de subtítulos se inflan sin
  factory AppCompat (Service plain / `createConfigurationContext`); el widget
  AppCompat explícito resuelve `app:tint` en su constructor en cualquier contexto.
- `AndroidManifest.xml:105` (`AppLinkUrlError`): `tools:ignore="AppLinkUrlError"`
  documentado en el intent-filter VIEW `audio/*` de `TranscribeFileActivity` — es
  un handler "Open with" de ficheros, no un app link; no hay URL/dominio que
  verificar, y `autoVerify` mentiría un dominio.

Restan 103 warnings (SetTextI18n, UnusedResources, Untranslatable, etc.) —
ninguno bloquea el gate.

### 3.2 Tests Rust no ejecutables en CI (bug de upstream)
`cargo test` del crate completo se bloquea en build de `transcribe-cpp-sys v0.1.3`
por un bug de empaquetado de CMake (`add_subdirectory examples`/`tests` no
existen). Afecta al upstream también. Opciones:
- Añadir workflow `cargo-test.yml` con `cargo test` (fallará mientras persiste el
  bug, o hasta pin a un `transcribe-cpp-sys` sin el bug).
- Hasta entonces, los tests Rust están verificados mediante el espejo (§4.3) y
  pasarán en runners x86_64 con una versión corregida.

### 3.3 Cobertura JVM adicional (opcional)
`PostProcessor` (URL-building + safe-fallback) es difícil de testear sin refactors;
AGENTS §5.1 prohíbe tocar `PostProcessor.java` dentro del scope de PLAN.md, así
que se deja. Se podrían añadir suites para `VoiceSession*`, `ModelsActivity`
(listado de modelos) y `ThemePrefs` si se introducen seams purables.

### 3.4 Verificación final antes del PR
```bash
./gradlew clean testDebugUnitTest checkModels assembleDebug   # hard gates
python3 scripts/check_translations.py                          # i18n
rustfmt --check --edition 2021 src/audio.rs src/corrector.rs
```
(No se ha hecho commit. Los commits deben ser convencionales `feat:`/`fix:`/`ci:` y
ese PR sigue las plantillas de `.github/PULL_REQUEST_TEMPLATE.md`.)

## 4. Notas de entorno (host donde se verificó)

- Host físico: dispositivo Android; capa Termux → PRoot Ubuntu (aarch64). No systemd.
- Toolchains: `cargo 1.97.1`, `rustc 1.97.1`, JDK 17; `cc`=gcc-15 (host) vs
  `c++`=clang-21 (Termux) — el mismatch híbrido es lo que rompe el build de C++ de
  ggml (ver AGENTS.md §3 AAPT2/ARM64 caveats). Por eso `cargo test` del cdylib
  completo no corre aquí; el espejo dep-free sí.
- Gradle cache caliente → `./gradlew ... --offline` funciona para `testDebugUnitTest`.
- SDK/NDK en `/root/android-sdk` (NDK 27). `cargo-ndk`/`curl` disponibles.

## 5. Equivalencias Handy → android_transcribe_app (para referencia rápida)
| Handy | android_transcribe_app |
|---|---|
| `checkModelCatalog` task | `checkModels` task |
| `scripts/check-translations.ts` | `scripts/check_translations.py` |
| 17 suites / 78 JVM + Robolectric | 3 suites / 14 JVM (junit) |
| `tests/app.spec.ts` Playwright | pendiente |
| `lintDebug` hard gate (clean) | `lintDebug` hard gate (0 errores — §3.1) |
