# AGENTS.md — Guía para asistentes de IA

> Archivo de instrucciones para agentes IA que trabajen en este repositorio. Define stack, comandos, convenciones y reglas críticas que **no** deben romperse. Equivalente al `AGENTS.md` estándar (mismo rol que `CONTRIBUTING.md` tiene para humanos, pero dirigido a IAs).

> **Repo:** `android_transcribe_app` (fork de `notune/android_transcribe_app`)
> **Tipo:** App Android de transcripción de voz *offline* con opción de post-procesado con IA.
> **Última versión publicada:** 0.1.22 (`versionCode 23`, ver `app/build.gradle.kts`).

---

## 1. Resumen del Proyecto

App Android que convierte voz en texto **100 % en local**. Se ofrece al sistema de tres formas distintas (para maximizar compatibilidad con teclados/apps):

| Superficie | Cómo se dispara | Ficheros clave |
|---|---|---|
| **Popup de voz** | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (teclados tipo SwiftKey, búsqueda por voz web) | `RecognizeActivity.java` + `src/recognize.rs` |
| **Servicio de reconocimiento** | `SpeechRecognizer` (Android) — otros keyboards/apps lo usan como STT | `VoiceRecognitionService.java` + `src/recog_service.rs` |
| **IME (teclado propio)** | `BIND_INPUT_METHOD` (HeliBoard/FlorisBoard/etc.) | `RustInputMethodService.java` + `src/ime.rs` |

Funciones adicionales: **subtítulos en vivo** sobre audio del sistema (`LiveSubtitleService.java` + `src/subtitle.rs`) y **gestión de modelos GGUF** (`ModelsActivity.java` + `src/models.rs`).

Post-procesado IA (fork addition): opcional, *off-line-by-default*, refina texto con cualquier LLM compatible OpenAI (`PostProcessor.java`, settings en `PostProcessSettingsActivity.java`).

**Arquitectura general:** proceso Java principal + proceso aislado `:ime` (declarado en `AndroidManifest.xml` con `android:process=":ime"`). Toda la lógica pesada (ASR, captura audio, segmentación, JNI) está en **Rust** compilado como `cdylib` (`Cargo.toml`) y enlazado por JNI desde Java. Los procesos se sincronizan vía ficheros *marker* en `getFilesDir()` (sin `SharedPreferences`).

---

## 2. Stack Tecnológico

| Capa | Tecnología | Versión / Nota |
|---|---|---|
| Lenguaje nativo | **Rust** | edition `2021`, `crate-type = ["cdylib"]` |
| Backend ASR | [`transcribe-cpp`](https://github.com/handy-computer/transcribe.cpp) (ggml + whisper.cpp fork) | `0.1.3` |
| Audio captura | [`cpal`](https://github.com/RustAudio/cpal) | `0.15` |
| Canales Rust ⇄ Java | [`jni`](https://github.com/jni-rs/jni) | `0.21` |
| Concurrencia | `crossbeam-channel`, `once_cell`, `std::sync` (Arc/Mutex/atomic) | — |
| Logging nativo | `log` + `android_logger` | max level `Info` |
| Errores | `anyhow` (Rust), `try/catch` + `Throwable` (Java) | — |
| Lenguaje UI | **Java** (sin Kotlin) | Java 8 source/target |
| Android Gradle Plugin | `com.android.application` | **8.7.3** |
| Build tool | Gradle wrapper | ver `gradle/wrapper/gradle-wrapper.properties` |
| JDK requerido | **JDK 17** | ruta override en `gradle.properties` (`org.gradle.java.home`) |
| NDK | Android NDK | **28.0.13004108** |
| Target NDK ABIs | **`arm64-v8a` únicamente** (`abiFilters += "arm64-v8a"`) | excluidas x86 / armeabi-v7a |
| Cross-compile Rust | [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) | invocado por Gradle |
| `compileSdk` / `targetSdk` / `minSdk` | `35 / 35 / 26` | namespace y applicationId: `dev.notune.transcribe` |
| Material | Material Components for Android | `1.12.0` (Material 3 + Material You) |
| HTTP (post-procesado) | OkHttp | `4.12.0` |
| Cifrado clave API | `androidx.security:security-crypto` | `1.1.0-alpha06` (EncryptedSharedPreferences) |
| Alineación kotlin-stdlib | Forzadas a `1.8.22` (vacías) | ver bloque `constraints` en `app/build.gradle.kts` |

**Permisos críticos** (`AndroidManifest.xml`):
- `RECORD_AUDIO`, `INTERNET` (post-procesado), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`.

**i18n:** 7 locales paralelos (EN `values/`, ES `values-es/`, DE `values-de/`, FR `values-fr/`, IT `values-it/`, PT `values-pt/`, RU `values-ru/`) + `values-night/` (estilos dark).

---

## 3. Comandos Frecuentes

> Todo se ejecuta desde la raíz del proyecto. **Requiere JDK 17 + NDK 28.0.13004108 + Rust toolchain (`aarch64-linux-android`) + `cargo-ndk`.** Documentación detallada en el README.

### Instalación de dependencias (una vez)

```bash
# Toolchain
rustup target add aarch64-linux-android
cargo install cargo-ndk

# Android SDK / NDK vía sdkmanager
sdkmanager "ndk;28.0.13004108"
```

Crear `local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

### Build

```bash
# Debug APK
./gradlew assembleDebug        # salida: app/build/outputs/apk/debug/app-debug.apk

# Release APK (firmado si release.keystore + env vars presentes)
./gradlew assembleRelease      # salida: app/build/outputs/apk/release/app-release.apk

# El modelo GGUF (~209 MB) se descarga automáticamente al primer build
# (task `downloadModels`) con verificación SHA-256.

# Limpiar
./gradlew clean
./cargo clean   # si quieres purgar también target/
```

### Variables de entorno para release firmado

```bash
export KEY_ALIAS=release
export KEY_PASS=yourpassword
export STORE_PASS=yourpassword
# release.keystore en la raíz (gitignored)
```

### CMake args de la C++ core (no cambiar)

`app/build.gradle.kts` fuerza `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` para no perder los kernels cuantizados en cross-compile. Más detalles en comentarios del `cargoNdkBuild` task.

### Pruebas

**No hay suite de tests automatizados** en este repo. La validación principal es:
- Compilación limpia: `./gradlew assembleDebug`.
- Smoke test manual: instalar APK en dispositivo arm64 y usar **Try voice input** desde `MainActivity` (carga micrófono → model load → transcripción en menos de 2× tiempo real en CPUs modernas).

### Linter / formato

- **Java:** no hay Checkstyle/PMD/Spotless configurado; seguir las convenciones existentes (ver §4).
- **Rust:** no hay `rustfmt.toml` ni `clippy.toml` en el repo; sigue el estilo inline (4 espacios, `rustfmt` por defecto).

---

## 4. Convenciones de Código y Estructura

### 4.1 Arquitectura

- **Patrón:** **Puente JNI por módulo Rust ↔ Java/Kotlin Service**. Cada servicio Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`) tiene su homólogo en `src/<mod>.rs` y expone al menos `initNative` / `cleanupNative` más acciones de alto nivel (`startRecording`, `stopRecording`, `pushAudio`, ...).
- **Estado global:** Rust usa `Lazy<Mutex<..>>` (de `once_cell`) para singletons (engine, sesión IME, sesión recog, estado de subtítulos).
- **Engine compartido:** `Arc<Mutex<Engine>>` con `Condvar` para coordinar cargas concurrentes. Ver patrón completo en `src/engine.rs` (`ensure_loaded_from_thread`).
- **Modelo de procesos:** main + `:ime` (aislado). Comparten **marcadores en `getFilesDir()`** — *no* `SharedPreferences` — porque `:ime` no puede leer prefs de la app principal sin un `ContentProvider`, y los marker files son trivialmente compartibles entre procesos.

### 4.2 Naming

- **Rust:** `snake_case` para variables/funciones, `PascalCase` para tipos (`Engine`, `VoiceSessionState`, `Endpointing`). `SCREAMING_SNAKE_CASE` para constantes (`MAX_RUN_SAMPLES`, `MIN_SPEECH_LEVEL`). `Java_*` prefix en símbolos JNI exportados.
- **Java:** `PascalCase` para clases, `camelCase` para campos/métodos. Standard Android (Activity/Service/View).
- **Recursos:** `snake_case` (`voice_status_ready`, `activity_main`, `bg_card`).

### 4.3 Naming JNI (regla dura)

Todos los entry points nativos exportados siguen este formato:

```
Java_dev_notune_transcribe_<JavaClassName>_<methodName>
```

Mismas funcionesJava + mismas firmas (incluido el `JClass` aunque no se use). Ver por ejemplo `Java_dev_notune_transcribe_RecognizeActivity_initNative` en `src/recognize.rs`. **No renombrar sin actualizar la declaración nativa en Java.**

Carga de librerías en cada proceso (orden estricto, en bloque `static`):

```java
static {
    try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { /* warn */ }
    System.loadLibrary("android_transcribe_app");
}
```

### 4.4 Manejo de errores y logging

- **Logging nativo:** `log::info!`, `log::warn!`, `log::error!` (filtrado a `Info`, ver `android_logger::init_once`).
- **Logging Java:** `Log.d/i/w/e(TAG, msg, throwable)` con `TAG` por clase (`OfflineVoiceInput`, `MainActivity`, `LiveSubtitleService`...).
- **Call-backs JNI → Java (firmas estables — NO cambiarlas sin tocar todas las superficies):**
  - `onStatusUpdate(String)` — mensajes como `"Ready"`, `"Listening..."`, `"Transcribing..."`, `"Error: <msg>"`.
  - `onTextTranscribed(String)` — texto final (post-procesado aplicado si está activo).
  - `onAudioLevel(float)`/`onRmsChanged(float)` — nivel rms 0..1.
  - `onSubtitleText(String, boolean)` — parcial (false) o final (true).
  - `onAutoStop()` — disparo del monitor de endpointing.
  - `onBenchmarkResult(float audioSecs, float computeSecs, String error)`.
- **Resiliencia del engine:** `transcribe_shared` hace `panic::catch_unwind` + recuperación de `Mutex` envenenado (`unwrap_or_else(|p| p.into_inner())`). Si lo refactorizas, **mantén estas dos capas**.

### 4.5 Manejo de settings (regla del proyecto)

Los ajustes son **marker files en `filesDir()`**, no `SharedPreferences`. Ejemplos:

| Marker file | Significado |
|---|---|
| `auto_record` | presente = *Auto-start recording* ON |
| `select_transcription` | presente = selecciona el texto transcrito |
| `pause_audio` | presente = pausa audio del sistema mientras se graba |
| `stop_on_hide` | presente = *Record in background* OFF (default ON es el opuesto) |
| `auto_stop` | presente = *Auto-stop after silence* ON |
| `model_language` | contenido = locale BCP-47 (`en-US`, `es-ES`, …) o `auto` |
| `model_translate` | presente = traducir a inglés (Whisper) |
| `active_model` | contenido = nombre del GGUF importado en `models/` |
| `model_threads` | contenido = nº entero; ausente/inválido = automático |

Bindings típicos (ver `MainActivity.bindMarkerSwitch`): un `CompoundButton` cuja presencia del file representa el estado.

La **clave de API del post-procesado** sí usa `EncryptedSharedPreferences` (es la única excepción).

### 4.6 Estructura de carpetas

```
android_transcribe_app/
├── app/
│   ├── build.gradle.kts                          # Build config app module (AGP 8.7.3, ABI filters, cargo-ndk wiring, model download)
│   └── src/main/
│       ├── AndroidManifest.xml                   # Permisos, 4 activities, 3 services (uno en :ime)
│       ├── assets/bench.wav                      # Clip corto para benchmark
│       ├── java/dev/notune/transcribe/           # UI, Activities, Services, PostProcessor, Settings, *Prefs
│       ├── res/                                  # Layouts, drawables, strings (7 locales), styles, themes
│       └── jniLibs/                              # .so generado por cargo-ndk (gitignored)
├── src/                                          # Rust core (cdylib): 11 módulos
│   ├── lib.rs                                    # Declara todos los `pub mod`
│   ├── engine.rs                                 # Singleton Engine + loading coordination
│   ├── audio.rs                                  # find_quietest_split (corte de audio limpio)
│   ├── voice_session.rs                          # Estado compartido IME + recog popup (cpal + JNI)
│   ├── subtitle.rs                               # Pipeline de subtítulos (partial/final + worker + gap detect)
│   ├── recog_service.rs                          # RecognitionService (otra keyboard como STT provider)
│   ├── ime.rs / recognize.rs                     # Bridges JNI por surface
│   ├── main_activity.rs / transcribe_file.rs / models.rs / assets.rs
├── model_assets/                                 # Asset pack (install-time) para GGUF > 200 MB Play limit
│   ├── build.gradle.kts                          # assetPack { dynamicDelivery; deliveryType = "install-time" }
│   └── src/main/assets/builtin-model/            # Aquí descarga Canary 180M Flash el task `downloadModels`
├── Cargo.toml / build.rs                         # Crate type cdylib, deps, shim libpthread + libc++_shared
├── build.gradle.kts                              # Plugin AGP root (apply false)
├── settings.gradle.kts                           # :app + :model_assets, FAIL_ON_PROJECT_REPOS
├── gradle.properties                             # JVM args 4 GB, useAndroidX, kotlin-stdlib alignment, JDK 17 home
├── fastlane/metadata/android/                    # Metadatos F-Droid (textos localizadas, changelogs por versión)
├── .github/workflows/android_release.yml         # CI: jdk17 + NDK + Rust + cmake + ninja + cargo-ndk + caches
└── RELEASE_NOTES.md                              # Notas por versión (usadas por `softprops/action-gh-release`)
```

### 4.7 Rust: contratos del singleton Engine (no romper)

- **`engine::get_engine()` → `Option<Arc<Mutex<Engine>>>`**: compartido por todos los procesos Java. Garantías: panic-catching, recovery de Mutex poison.
- **`engine::ensure_loaded*`**: idempotente, multi-thread safe, espera via `Condvar` si otra thread está cargando, reintenta tras `Failed(_)`.
- **`Engine::transcribe`** divide audio largo en el punto más silencioso (`audio::find_quietest_split`); une los textos con un espacio.
- **`Engine::run` re-lee `model_language` en CADA llamada** (issue v0.1.20→21). Esto es lo que hace que el cambio de idioma en el spinner de `ModelsActivity` aplique también en el proceso `:ime` sin recarga manual. **No cachear el idioma dentro del Engine** — ese fue el bug original.

### 4.8 Subtítulos: pipeline con coste predecible

`src/subtitle.rs` es probablemente el código más sutil. Reglas:

- **Parciales:** sólo se encolan si (a) worker ocioso, (b) sin pendientes finales, (c) `segment_secs * rtf <= MAX_PARTIAL_COST_SECS` (latest-wins). Si el dispositivo transcribe más lento que el audio, las parciales se cortan — es por diseño.
- **Finales:** siempre se encolan (FIFO), *pero* se pliegan con `try_recv` hasta `MAX_MERGED_SAMPLES` (25 s) para amortiguar la ventana fija de Whisper.
- **Lag-policies (importantes, no subir los umbrales a la ligera):**
  - `MAX_FINAL_LAG_SAMPLES = 8 s` → drop con `"…"` gap.
  - `MAX_PARTIAL_LAG_SAMPLES = 3 s` → descartar.
  - `MAX_PARTIAL_COST_SECS = 2.0` → toggle on/off de parciales por coste.
- La fusión de finales es lo que permite a un dispositivo lento seguir el ritmo real sin perder texto. Si lo eliminas, la latencia crece sin cota.

### 4.9 Java UI: convenciones observables

- **Tema base:** `Theme.Material3.DayNight.NoActionBar` + `DynamicColors.applyToActivitiesIfAvailable(this)` en `App.onCreate` (Material You desde Android 12+).
- **IME:** `RustInputMethodService` no es `AppCompatActivity` (es `InputMethodService` en proceso `:ime`). Construye su propio contexto theme-aware con `ThemePrefs.wrapForNight` + `DynamicColors.wrapContextIfAvailable` para que la vista coincida con el ajuste de tema de la app principal.
- **Pantalla de voz (popup):** `AppTheme.VoicePanel` translúcido — NO pantalla completa — para que la app que invocó la voz conserve su UI detrás.
- **Estado interno del engine (`"Loading"`, `"Initializing"`, `"Waiting"`) NO se muestra al usuario:** la UI mapea siempre a `Tap to Record`, `Listening...`, `Processing...`, etc. (ver `updateUiState` en `RustInputMethodService`).
- **Pantallas han de re-pintar en cambios de tema** (ej. IME reconstruye su `inputView` si `ThemePrefs.isNight` cambia desde `onStartInputView`).

### 4.10 i18n

- **Todas las cadenas nuevas** → añadir en `app/src/main/res/values/strings.xml` y propagarlas a `values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`.
- Si una cadena **no debe traducirse** (ej. prompt de sistema por defecto), usa `translatable="false"` (ver `pp_default_prompt`).
- Si el orden de idioma importa (ej. ajustes que aparecen en una columna lateral con icono), respetar el orden existente en `MainActivity.bindMarkerSwitch`.

### 4.11 Versión y release

- **Bumps de versión:** editar `versionCode` **y** `versionName` en `app/build.gradle.kts`. `versionCode` siempre incremental.
- **Notas de release:** añadir bloque nuevo a `RELEASE_NOTES.md` en la cabecera (no al final). El CI (`android_release.yml`) usa `body_path: RELEASE_NOTES.md` para `softprops/action-gh-release@v2`.
- **Solo APK:** construir AAB está deshabilitado en CI y en comentarios. Si necesitas AAB para Play Store, recuerda que el `:model_assets` activa `dynamicDelivery` install-time (ver `model_assets/build.gradle.kts`) y el `app/build.gradle.kts` hace fallback añadiendo `model_assets/src/main/assets/` a `assets.srcDirs` solo cuando NO es bundle.

---

## 5. Reglas para Agentes de IA

### 5.1 EVITAR (cosas que romperían el proyecto)

- ❌ **No cambiar la firma de los call-backs JNI** listados en §4.4 sin actualizar cada superficie Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`).
- ❌ **No cachear el `model_language`** dentro de `Engine` o entre llamadas. El comportamiento intencional es leer el marker file en cada `Engine::run` para que el cambio aplique también en `:ime`. Ese fue el bug crítico de v0.1.20 → v0.1.21.
- ❌ **No eliminar las dos capas de resiliencia** de `transcribe_shared` (`catch_unwind` + recuperación de Mutex envenenado). Si lo haces, un único panic deja el IME bloqueado en "Processing" hasta que muera el proceso.
- ❌ **No añadir soporte para otras ABIs** (`armeabi-v7a`, `x86`, `x86_64`) sin revisar antes:
  - `check_cpu_features` requiere dotprod+fp16 (ARMv8.2 ~2018+); cualquier dispositivo antiguo fallaría en medio de la inferencia con `SIGILL`.
  - el ggml cross-compile con `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` está fijado en `app/build.gradle.kts`.
- ❌ **No romper la verificación SHA-256** ni eliminar el marker de "modelo bundled" — protege contra apps que se actualizan con un modelo nuevo y mantienen el viejo en cache.
- ❌ **No convertir la app a Kotlin** sin discutirlo antes. Es un proyecto Java a propósito (legado del upstream, evita overhead de KSP/codegen).
- ❌ **No mover ajustes a `SharedPreferences`** sin migrar también los marker files consumidos por Rust (`model_language`, `model_translate`, `active_model`, `model_threads`). El código Rust solo lee del *filesystem*, no de prefs.
- ❌ **No añadir dependencias síncronas bloqueantes** al evento del audio callback de `cpal` (en `voice_session.rs`/`recog_service.rs`). Toda escritura a `audio_buffer` debe ser O(1) bajo el mutex.
- ❌ **No cambiar el orden de carga** de las librerías nativas (siempre `c++_shared` antes de `android_transcribe_app`) ni saltarse el `try/catch UnsatisfiedLinkError` — protege contra dispositivos/ABIs mal.
- ❌ **No subir umbrales** de `MAX_FINAL_LAG_SAMPLES` / `MAX_PARTIAL_LAG_SAMPLES` / `MAX_PARTIAL_COST_SECS` en `subtitle.rs` sin probar hardware lento: esos números están calibrados para que un dispositivo medio no se quede atrás, no son free-tuning.
- ❌ **No reemplazar `transcribe_shared` por código que ignore panics.** El `panic::catch_unwind` documenta un caso real: el modelo puede panic de fondo por allocations grandes, y el efecto sin catch sería un IME congelado.
- ❌ **No romper el fallback del post-procesado:** si la llamada al LLM falla, siempre se entrega la transcripción cruda (`onError → deliverResult(text)`). Es la garantía de "no perder texto".
- ❌ **No borrar el comentario explicativo en app/build.gradle.kts sobre `assetPacks` + bundle-vs-APK.** Es la trampa que rompió v0.1.x del upstream y se documentó específicamente para evitar.
- ❌ **No introducir tests automatizados que importen el módulo entero** a través de JNI en CI: no hay emulador arm64 en el runner de GitHub Actions y los tests no-existentes ya dicen que validación = build + smoke test manual.

### 5.2 HACER (reglas positivas al añadir código)

- ✅ Si añades un call-back JNI nuevo, documéntalo con la firma exacta en este `AGENTS.md` (§4.4) y añade un stub no-op en Java por defecto para no romper builds en los que aún no has cableado el lado Java.
- ✅ Cada nuevo ajuste del usuario va como **marker file en `filesDir()`**, salvo el de la clave de API (que va cifrada en `EncryptedSharedPreferences` siguiendo el patrón de `SettingsManager`).
- ✅ Antes de añadir cadenas visibles, duplicarlas en los 7 locales. Si añades una cadena que **no** quieres traducir, márcala con `translatable="false"`.
- ✅ Si tocas el `Engine`, lee primero `src/engine.rs` completo (~370 líneas, muy comentado): decisiones sobre warm-up, re-read de idioma, fallback a modelo bundled, warnings de capabilities (`supports_translate`/`variant().contains("turbo")`), Whisper `temperature_inc`, todo está allí por una razón documentada.
- ✅ Si añades un nuevo surface (Activity/Service), recuerda:
  1. Bloque `static` con `c++_shared` + `android_transcribe_app`.
  2. Método nativo declarado en Java + implementación en Rust con prefijo `Java_dev_notune_transcribe_<ClassName>_`.
  3. Llamadas JNI con `get_java_vm`, `new_global_ref` y release apropiado en `cleanupNative`.
  4. Si es un servicio con proceso aislado, sincroniza vía marker files (no `SharedPreferences`).
- ✅ Si añades un modelo bundled:
  1. Subir el SHA-256 real, **no** inventar uno.
  2. Sumar el `ModelFile` en `modelPackFiles` (`app/build.gradle.kts`).
  3. Si el modelo es > 200 MB, mantener `model_assets` como asset pack.
- ✅ Si modificas la lógica de carga de Rust:
  - Los `outputs.upToDateWhen { false }` están puestos **a propósito** (el incremental de Cargo es fiable, los inputs/outputs de Gradle no).
  - El shim de `libpthread.a` y la librería `c++_shared` dinámica son **necesarios para el build Android**. Ver comentario en `build.rs`.
- ✅ Si tocas el benchmark, lee primero los comentarios de `MainActivity.runBenchmark`/`readWavAsset` (sólo admite 16 kHz mono 16-bit PCM).
- ✅ Bump de versión: `versionCode` siempre +1 sobre el actual; `versionName` semver-friendly; añadir entrada en cabecera de `RELEASE_NOTES.md` (no al final).
- ✅ Si rompes un idioma o un locale: la app **transcribe en el dispositivo-locale por defecto** (`App.applyDeviceLanguageIfUnset`) y es opt-out. Cualquier override del usuario debe respetar ese comportamiento (ver `ModelsActivity` + `engine::run`).
- ✅ Si añades una dependencia Gradle: respeta el bloque `constraints` que alinea `kotlin-stdlib-jdk7/jdk8` a `1.8.22`. Sin esa alineación, builds nuevos rompen con `Duplicate class` por culpa del legacy transitivo de Material.
- ✅ Si modificas el flujo de subtítulos, preserva el contrato de `onSubtitleText(text, isFinal)`: `isFinal=true` appendea, `isFinal=false` reemplaza hipótesis parcial dentro de la misma ventana.
- ✅ No añadas `unsafe` Rust innecesario fuera de los entry points JNI (`#[no_mangle] pub unsafe extern "system" fn Java_…`).

### 5.3 Plantilla para tests / nuevos componentes (resumen rápido)

Crear un nuevo módulo Rust con JNI → requiere:

1. **Rust** (`src/<mod>.rs`):
   ```rust
   use jni::objects::{JClass, JObject};
   use jni::JNIEnv;
   use once_cell::sync::Lazy;
   use std::sync::Mutex;

   pub struct FooState { /* JVM + GlobalRef al target */ }

   static STATE: Lazy<Mutex<Option<FooState>>> = Lazy::new(|| Mutex::new(None));

   #[no_mangle]
   pub unsafe extern "system" fn Java_dev_notune_transcribe_FooService_initNative(
       env: JNIEnv, _class: JClass, target: JObject,
   ) { /* new_global_ref, attach thread, etc. */ }
   ```
2. **Java** (paralelo):
   ```java
   public class FooService extends Service {
       static {
           try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { }
           System.loadLibrary("android_transcribe_app");
       }
       public void onStatusUpdate(String s) { /* runOnUiThread o mainHandler */ }
       private native void initNative(FooService self);
       private native void cleanupNative();
   }
   ```
3. **Manifest**: añadir `<service android:name=".FooService" .../>` (y `android:process=":foo"` si debe ser proceso aislado).
4. **lib.rs**: `pub mod foo;`.

Crear un nuevo ajuste toggle en UI → marker file en `filesDir()` con un nombre `snake_case`; bindearlo con `bindMarkerSwitch(...)` en `MainActivity` (patrón ya existente). Propagar cadena a los 7 locales.

---

## 6. Convenciones de commits

- Estilo observado en `git log`:
  - `feat: <qué>`, `fix: <qué>`, `chore: <qué>`, `ci: <qué>`, `docs: <qué>`, `refactor: <qué>`, `build: <qué>`.
  - Lenguaje de los mensajes: inglés, frases imperativas concisas, sin punto final.
- Para cambios visibles al usuario que merezcan release, **primero el bloque en `RELEASE_NOTES.md`**, luego el commit, luego el tag `vX.Y.Z` (el CI dispara el build firmado y crea el GitHub Release con las notas).

---

## 7. TL;DR para una IA nueva

- Lee `src/engine.rs` y `Cargo.toml` primero.
- Respeta nombres JNI (`Java_dev_notune_transcribe_<Class>_<method>`) y firmas de call-backs en §4.4.
- Ajustes = marker files en `filesDir`. Clave API cifrada en `EncryptedSharedPreferences`.
- Post-procesado: opcional, *safe-fallback* obligatorio al texto crudo.
- Idioma se re-lee en cada `Engine::run` (no cachear en memoria dentro del engine).
- Subtítulos usan un modelo de partial/final con lag-policies calibradas; subir esos números rompe el contrato.
- Bug histórico a evitar: v0.1.20 → v0.1.21 (idioma cacheado en `:ime`). La regla "re-read on every run" está escrita en muchos comentarios a propósito.
- Antes de PR: `./gradlew assembleDebug` debe pasar limpio sin warnings nuevos y las cadenas nuevas deben estar en los 7 locales.
