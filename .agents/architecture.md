# Arquitectura — Offline Voice Input (Android)

Describe el **CÓMO** del proyecto. **Para QUÉ es el producto ver
[`spec.md`](./spec.md).** Para reglas que aplican a agentes IA al modificar
código, ver [`../AGENTS.md`](../AGENTS.md).

## Visión general

```
┌──────────────────────────────────────────────────────────────┐
│ Proceso PRINCIPAL (MainActivity, lib Rust en proceso Java)   │
│ ┌───────────────────┐    ┌────────────────────────────────┐ │
│ │ ModelsActivity    │───►│ Rust Engine (singleton)         │ │
│ │ VoiceRecognition  │    │  • transcribe-cpp 0.1.3        │ │
│ │ Service           │    │  • cpal 0.15                    │ │
│ │ LiveSubtitle      │◄──►│  • jni 0.21                     │ │
│ │ Service           │    │  • crossbeam-channel 0.5        │ │
│ │ RecognizerActivity│    │  • once_cell 1.19, anyhow 1.0   │ │
│ │                  │    │  • strsim 0.11 (corrector)      │ │
│ └───────────────────┘    └────────────────────────────────┘ │
│           │               ▲                                  │
│           ▼               │ JNI callbacks                    │
│   filesDir/ marker files: model_language, active_model,       │
│   auto_record, pause_audio, custom_words, … (:ime sync)      │
└──────────────────────────────────────────────────────────────┘
                          │
                          │ marker files (cross-process)
                          ▼
┌──────────────────────────────────────────────────────────────┐
│ Proceso AISLADO `:ime` (RustInputMethodService)              │
│  • Compose teclado (backspace / space / enter / switch)      │
│  • Captura audio → transcribe → JNI onTextTranscribed        │
│  • commitText al InputConnection activo                       │
└──────────────────────────────────────────────────────────────┘
```

## Stack por capa

| Capa | Tecnología | Por qué |
|---|---|---|
| **Core ASR** | Rust `cdylib` + `transcribe-cpp` (ggml) | ggml corre modelos Whisper/Canary/Parakeet con matmul quantizado on-device. |
| **Audio capture** | `cpal` 0.15 | Único crate de audio Rust portable a Android sin ALSA/JACK. |
| **Bridge Java↔Rust** | `jni` 0.21 con símbolos `Java_dev_notune_transcribe_*` | Naming convention estable (ver AGENTS.md §4.3). Verificar que la firma del lado Java matchea antes de renombrar. |
| **UI principal** | Java (sin Kotlin) + Material 3 + AppCompat | Legado upstream; evita KSP/codegen overhead. |
| **IME (Keyboard)** | `InputMethodService` + proceso `:ime` | Aísla crashes del IME del proceso principal. |
| **Cross-process sync** | Marker files en `filesDir()` | Sin `ContentProvider` ni `SharedPreferences` (justificación y nombres en AGENTS.md §4.5). |
| **Worker subtítulos** | `crossbeam-channel::unbounded` + `Arc<Atomic*>` | Latest-wins partial/final, drop con gap en lag-policy. |

## Flujos clave

### 1. Captura → ASR → Output (superficies 1 / 2 / 3)

```
Mic (16 kHz mono f32)
  → cpal Stream (audio callback, lock-free buffer)
  → Vec<f32> en VoiceSessionState
  → engine::transcribe_shared (engine singleton, Arc<Mutex>)
     • re-lee `model_language` en cada run (no cache)
     • degrada `de-DE` → `de` → `None` si la hint es rechazada
     • catch_unwind(engine.transcribe) → Result<String,String>
  → corrector::correct_if_enabled(text)  ← post-ASR, pre-callback
     • lee filesDir/custom_words (mtime-cached)
     • codificador fonético ES+EN → Levenshtein ≤ 2 + bigram coseno
     • propio catch_unwind → safe-fallback = texto crudo
     • cubre TODAS las superficies de golpe (IME/popup/subs/SR/archivo)
  → JNI callback `onTextTranscribed(string[, sessionId])`
  → Java: aplica PostProcessor final-only si está ON (una respuesta completa, con safe-fallback)
  → commitText único al InputConnection (IME)
  → setResult EXTRA_RESULTS (popup)
```

### 2. Subtítulos (superficie 4)

```
Audio del sistema vía MediaProjection
  → AudioRecord (16 kHz mono PCM 16)
  → pushAudio → LiveSubtitleState (segment + preroll + silencio)
  → crossbeam_channel::Sender<Job> → worker thread
  → engine::transcribe_shared  → onSubtitleText(text, isFinal)
     • partial (replacea hipótesis en ventana visible)
     • final (appendea a transcript)      • lag > 8 s → drop + "…" gap marker
      • parciales paradas si RTF > 2 s/segundo
      • ✅ P0.2 (2026-08-03): generación AtomicU32 bumpada en init/cleanup; jobs
        y worker portan la generación — jobs viejos se descartan sin transcribir
        ni entregar; el worker termina al drenar el canal y suelta su GlobalRef
  (predict-cost)
```

### 3. Cambio de modelo o idioma (cross-process)

```
ModelsActivity (Java)
  → escribe `model_language` / `active_model` / `model_threads` en filesDir
     • ✅ P1.2 (2026-08-03): toda escritura pasa por MarkerFileHelper con temp
       único por escritura + fsync + rename (nunca se observa contenido parcial)
  → engine::reset() (singleton main, espera carga en vuelo)
  → proceso `:ime` ve el cambio SIN recarga propia
  → ✅ idioma aplica en TODAS las surfaces en el siguiente run
```

## Estado de auditoría y deuda conocida (2026-08-03)

La auditoría estática integral confirmó la coherencia del diseño principal, pero
el Guantelete sigue **ABIERTO**. No confundir este documento de arquitectura con
una certificación de runtime.

Bloqueadores P0 que afectan a esta arquitectura — **implementados el 2026-08-03**
(ver [`memory/gauntlet-p0-implemented-2026-08-03.md`](./memory/gauntlet-p0-implemented-2026-08-03.md));
la validación de CI/dispositivo sigue pendiente:

1. ✅ `PostProcessor` ahora cancela por propietario (`cancelAllFor(owner)`);
   `cancelAll()` global queda sólo para shutdown real / toggle PP-off.
2. ✅ Worker de subtítulos con generación: jobs viejos nunca se transcriben ni
   entregan; el worker termina al drenar su canal.
3. ✅ La descarga runtime debug verifica SHA-256 antes de activar `active_model`.
4. ✅ Toolchain unificada: `ndkVersion 28.0.13004108` en Gradle = CI = docs;
   rutas de host resueltas con `ndkPrebuiltDir()` y límite de host declarado.

Además: ✅ P1.1 operation-id en transcripción de archivos; ✅ P1.2 markers
atómicos (temp único por escritura). La lista completa P0/P1/P2 y los criterios
de aceptación está en
[`memory/static-audit-debt-2026-08-03.md`](./memory/static-audit-debt-2026-08-03.md)
y el orden operativo en [`../GAUNTLETE_PLAN.md`](../GAUNTLETE_PLAN.md).

## Invariantes (a NO romper)

1. **`engine::run` re-lee `model_language` en CADA llamada.** Histórico:
   bug v0.1.20 → 21 donde el engine cacheaba el idioma en memoria y el
   proceso `:ime` no veía el cambio. Las firmas `Java_dev_notune_transcribe_*`
   no deben asumir idioma cacheado en absoluto. Ver AGENTS.md §4.7 y §5.1.
2. **`panic::catch_unwind` envuelve `transcribe_shared`.** Si el modelo
   panic'a por allocation o memoria, queremos un `Err("transcription failed
   unexpectedly, please try again")` limpio, no un IME colgado.
3. **`Mutex` poisoned recovery:** `unwrap_or_else(|p| p.into_inner())` —
   cualquier panic previo en el engine no debe envenenar el mutex para
   siempre.
4. **`outputs.upToDateWhen { false }` en `cargoNdkBuild`.** El incremental
   de Cargo es fiable; el matching inputs/outputs de Gradle no lo es.
   Sin este override, Gradle salta rebuilds cuando cambian sólo fuentes
   Rust. Ver AGENTS.md §3.
5. **`assets.srcDirs(...)` sólo si NO es bundle build.** Bundle dupli-
   caría assets con el asset pack `:model_assets`. Ver AGENTS.md §3 y §4.11.
6. **Carga orden: `c++_shared` antes de `android_transcribe_app`.** Saltarse
   esto o el `try/catch UnsatisfiedLinkError` deja la app con errores de
   linking silenciosos en ABIs mal.
7. **Re-lectura de `model_language` también tras un `engine::reset()`.** El
   marker file es la única verdad; nada se cachea en memoria engine-side.
8. **`corrector::correct_if_enabled` tiene su propio `catch_unwind`.** Un
   panic en el corrector (codificador fonético, cache mutex envenenado)
   devuelve el texto crudo, no escapa a JNI ni congela el IME. Esto
   complementa el `catch_unwind` del engine (invariante #2) — son dos
   capas independientes para dos componentes independientes.

## Boundaries (qué expone qué a qué)

| Expone ↔ suscribe | Vía | Riesgo si se rompe |
|---|---|---|
| Engine Rust ↔ Java service | JNI callbacks (ver AGENTS.md §4.4) | Crash en `commit` / texto perdido |
| Main process ↔ `:ime` process | Marker files en `filesDir()` | Idioma / modelo no se propaga |
| Rust audio callback ↔ `audio_buffer` | `Arc<Mutex<Vec<f32>>>` | Latencia / jitter UI |
| Worker subtitles ↔ `LiveSubtitleService` | `crossbeam_channel::unbounded` | Race en finals/partials |
| MainActivity ↔ ModelsActivity ↔ Rust | `engine::reset()` (condvar-coordinated) | Doble carga de modelo |
| CustomWordsActivity ↔ `corrector.rs` | marker file `filesDir/custom_words` | Corrección fonética no aplica o usa diccionario stale |

## Traducción de subtítulos (2026-08-04, fork addition)

El modelo bundled (Nemotron 3.5 ASR) **no traduce**; solo reconoce el idioma
hablado. La traducción de subtítulos es una segunda etapa Java-side sobre el
texto **finalizado** (nunca sobre parciales):

```
Nemotron (siempre Task::Transcribe — engine::transcribe_subtitle)
  → onSubtitleText(final, true)
  → CaptionSegment (shown = texto original al instante)
  → cola FIFO serial (máx. 8) → SourceLanguageResolver
  → OnDeviceSubtitleTranslator (ML Kit, paquetes vía Google Play Services;
    sin Play Services / sin paquete / error → fallback al texto original)
  → applyTranslation en main thread, en orden estricto, con generación de
    sesión (misma idea que el worker Rust) → re-render de la ventana
```

## Rendimiento Extremo, SIMD NEON y Aceleración de Hardware (2026-08-14)

Optimizaciones agresivas orientadas a saturar el hardware móvil ARM64 y minimizar latencias en tiempo real:

1. **Cadencia de Streaming a 80 ms (`STREAM_TICK_MS`):**
   - Sincronización del ciclo de sondeo a 80 ms (igual a la duración de frame interna del encoder de Nemotron). Ahorro de **220 ms** en la latencia de hipótesis parciales (3.75x más rápido).
   - Deduplicación con `last_emitted` en `run_stream` para no saturar el Looper de la UI de Android con JNI strings idénticos.
2. **Vectorización SIMD NEON (`src/audio.rs`):**
   - `fast_rms` y `fast_sum_squares` vectorizados con intrínsecas ARM64 NEON (`vfmaq_f32`, 4 acumuladores vectoriales de 128 bits desenrollados 16x) para saturar las unidades FMA de Cortex-A/Cortex-X.
3. **Pipeline de Memoria Zero-Allocation:**
   - Drenado de audio con `Vec::drain(..).collect()` para no destruir la capacidad del buffer de CPAL.
   - Buffer `thread_local!` reutilizable en `LiveSubtitleService.pushAudio` para eliminar `malloc` por cada frame de audio entrante.
4. **Selector de Hardware de Inferencia (`hardware_backend`):**
   - Persistencia vía marker file `hardware_backend`: `"cpu"` (ARM NEON + dotprod + fp16 - recomendado/default), `"npu"` (NNAPI/QNN), `"gpu"` (Vulkan).
   - Configurable desde la tarjeta de aceleración en `ModelsActivity`.
5. **Corrector Fonético Zero-Alloc (`src/corrector.rs`):**
   - Precomputación de bigramas y norma L2 al parsear el diccionario para desempate sin reservas en heap (aceleración 2.56x por consulta).
6. **Compilador y CI Benchmark Harness:**
   - Perfil `[profile.release]` con `lto = "fat"`, `codegen-units = 1`, `opt-level = 3`.
   - Gate automatizado en GitHub Actions vía `scripts/bench_performance.py`.

- **Marker:** `subtitle_translation_target` (`auto` = idioma original;
  `es-ES`/`en-US`/`fr-FR`/`de-DE`/`it-IT`/`pt-PT`/`ru-RU` = traducir). Lo lee
  `LiveSubtitleService` al iniciar sesión — sin reload del engine.
- **Aislamiento ASR:** `engine::transcribe_subtitle` fuerza `Task::Transcribe`;
  el switch global `model_translate` nunca aplica a subtítulos.
- **Origen automático:** sin `model_language` fijo, el origen se resuelve por
  script del texto (CJK→zh, kana→ja, hangul→ko, cirílico→ru + heurística
  latina conservadora). Si no se resuelve → se muestra el original.
- **Garantías:** resultados en orden estricto (cola serial), sin pérdida de
  texto (fallback al original), sin callbacks de sesión antigua (generación),
  cola acotada (saturación → original).

## Post-procesado AI final-only (2026-08-03)

El modelo ASR streaming y el postprocesador cumplen funciones distintas: los parciales de ASR se muestran como previsualización para conservar la sensación de tiempo real, pero no se pegan al editor. Al terminar la captura, `onTextTranscribed` recibe el transcript final y Java hace una única petición no-streaming a `PostProcessor.process()`. El editor recibe exactamente un resultado completo: el refinado si la llamada responde con contenido válido, o el transcript crudo si el postprocesado está desactivado, se cancela, falla, expira o devuelve una respuesta inválida. Esta separación elimina la carrera entre tokens SSE, revisiones del proveedor y `deleteSurroundingText`, sin modificar el pipeline de audio/ASR.

## Historia de decisiones

- **Rust + cdylib + JNI** porque un stack 100 % Java tendría que emular
  dotprod/fp16 en ARM64 — ggml aprovecha las SIMD nativas y baja los
  tiempos de inferencia a un orden de magnitud compatible con uso
  interactivo. La capa Java queda como UI/services.
- **arm64-only** porque ggml exige dotprod+fp16 (ARMv8.2 ~2018+). CPUs
  más viejas dan error explícito en load (no crash mid-inference).
- **proceso `:ime` aislado** (main + `:ime`) para aislar crashes del IME
  del proceso principal — un segfault en el teclado no debe tumbar
  el popup de voz ni `RecognizeActivity`.
- **`model_language` re-read por run** (issue v0.1.20-21). Originalmente
  se cacheaba para evitar I/O al disco (era un hot path); el trade-off se
  rompió cuando los usuarios esperaban que cambiar de idioma fuera
  inmediato. Se eligió correctness sobre 1 syscall/run.
- **Cargo + JNI bridge directo** (no C wrapper intermedio) para evitar
  dependencias innecesarias y simplificar ABI a una sola `.so`.
- **Post-filtro fonético vs `initial_prompt` (2026-07-28).** Se investigó
  cómo hace FUTO Voice Input su "Personal Dictionary": inyecta el
  diccionario como `initial_prompt` de whisper.cpp con `"(Glossary: …)"`.
  El propio código de FUTO admite en un TODO que "sólo funciona bien para
  inglés". Se eligió un **post-filtro fonético** (`corrector.rs`) en
  `transcribe_shared` porque: (a) funciona con las 16 familias de
  transcribe.cpp, no sólo Whisper; (b) es determinista (reemplazo
  garantizado) vs probabilístico (sesgo ignorable); (c) no contamina
  cada chunk de 30s del ASR; (d) cubre ES+EN con un codificador propio.
  El corrector vive en Rust, no en Java, para cubrir todas las superficies
  (incluidos subtítulos en vivo) sin cablear cada callback.

## Cambios esperados

Este fichero **cambia con frecuencia media** (días/semanas). Cualquier
refactor importante toca aquí. Cambios de alcance → [`spec.md`](./spec.md);
cambios de reglas para IAs → [`../AGENTS.md`](../AGENTS.md); cambios de estado de trabajo → [`progress.md`](./progress.md).

> **Mapping módulo ↔ clase:** la tabla canónica Rust ↔ Java (13 módulos
> Rust ↔ sus componentes Java ↔ cómo llaman al engine compartido) vive
> en [`../AGENTS.md` §4.6](../AGENTS.md) — no se duplica aquí para evitar
> drift entre ambos docs. Este doc describe los flujos y las invariantes;
> AGENTS.md §4.6 enumera qué symbol exporta cada módulo JNI.
