# agent_prompt.md — Instrucciones para el siguiente agente

> **Rol:** Continúas operativizando y limpiando el *Guantelete de Uncle Bob* en
> `android_transcribe_app`. Lee primero `AGENTS.md` (el contrato canónico),
> `GAUNTLETE_PLAN.md` (estado actual + evidencia), y este archivo. No toques
> lógica de JNI ni el rewrite de `PLAN.md` (SettingsManager).

## Prioridad A: promover `lintDebug` a hard gate (más importante)

`lintDebug` está como `continue-on-error` hasta que se paguen los 20 errores
legacy. Objetivo: que `./gradlew lintDebug` salga VERDE y quitarse el
`continue-on-error` del workflow `debug_telegram.yml`. **No tocar JNI ni firmas
de callbacks (AGENTS.md §4.3, §5.1); preferir `@RequiresApi`/`@TargetApi`, guards
de `Build.VERSION`, `app:tint`, flags `RECEIVER_EXPORTED`, y `lint:disable`
locales justicamente documentados.**

Los 20 errores (ver también `app/build/.../lint-results-debug.txt`):

| # | Archivo:Error | IssueId | Fix sugerido |
|---|---|---|---|
| 5 | `RustInputMethodService.java:185,478,535,606,631` | `NewApi` (API 28 `switchToPreviousInputMethod`) | guard `SDK_INT >= 28` o `@TargetApi(28)` |
| 1 | `RustInputMethodService.java:140` | `UnspecifiedRegisterReceiverFlag` (CANCEL_PP) | add `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` en `registerReceiver` |
| 1 | `MainActivity.java:363` | `MissingSuperCall` | `super.onRequestPermissionsResult(...)` |
| 6 | `LiveSubtitleService.java:247-270` | `NewApi` (API 29 `AudioPlaybackCapture*Builder`) | guard `SDK_INT >= 29` o anotar |
| 1 | `LiveSubtitleService.java:267` | `MissingPermission` (MediaProjection) | `checkPermission` o `try/catch SecurityException` |
| 5 | `ime_layout.xml:91,105,118,131` + `service_subtitle.xml:36` | `UseAppTint` | `android:tint`→`app:tint` (+ xmlns `app`) |
| 1 | `AndroidManifest.xml:105` | `AppLinkUrlError` | añadir URL/asset `android:autoVerify` |

## Prioridad B: CI de tests Rust (cuando sea posible)

Una vez arreglado/pinneado el build de `transcribe-cpp-sys`, añade`.github/workflows/cargo-test.yml`
que corra `cargo test` en ubuntu-latest x86_64. Mientras tanto los tests Rust están
verificados vía el espejo en `/tmp/gauntlet_rust` (ver GAUNTLETE_PLAN §2).

## Prioridad C: pulir la evidencia

- Re-confirma los 14 JVM tests con `./gradlew testDebugUnitTest checkModels`.
- Ejecuta `python3 scripts/check_translations.py` tras cada cambio de strings.
- `rustfmt --check --edition 2021 src/audio.rs src/corrector.rs` (mis aportes ya son clean).

## Reglas de estilo y convenciones (no negociables)
- **Commits:** convencionales (`feat:`, `ci:`, `fix:`, …), imperativo, sin punto
  final. **No commitear sin PR abierta** siguiendo `.github/PULL_REQUEST_TEMPLATE.md`.
- **i18n:** toda cadena visible → `values/strings.xml` + 6 locales; si no se traduce
  → `translatable="false"`. El gate `check_translations.py` debe seguir PASS.
- **Settings:** marker files en `filesDir()`, nunca `SharedPreferences` para los
  que Rust consume (AGENTS.md §4.5).
- **JNI:** firma `Java_dev_notune_transcribe_<Class>_<method>` inamovible;
  callbacks `onStatusUpdate/onTextTranscribed/onSubtitleText/onAutoStop/...`.
- **Engine:** re-leer `model_language` en cada `run`; preservar `catch_unwind` +
  recovery de Mutex (AGENTS.md §4.7/5.1).
- **Entorno host (esto):** ARM64 Termux; `cargo test` del cdylib bloqueado por CMake
  de `transcribe-cpp-sys`; usa el espejo para pruebas puras.

## Qué NO tocar (bloqueos explícitos)
- `PostProcessor.java`, ningún `.rs`, layouts ni strings del rewrite de PLAN.md.
- Firmas JNI / callbacks / orden de `System.loadLibrary` (siempre `c++_shared`
  antes de `android_transcribe_app`).
- Umbrales de `subtitle.rs` (`MAX_FINAL_LAG_SAMPLES`, etc.) sin validar en HW lento.
- `abiFilters` (solo `arm64-v8a`), ni `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`.
- `.bashrc`/`.profile` del host; no `systemd`/`systemctl`.

## Verificación rápida de sanity al arrancar
```bash
./gradlew testDebugUnitTest checkModels --offline
python3 scripts/check_translations.py
rustfmt --check --edition 2021 src/audio.rs
```
