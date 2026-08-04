# Live-subtitle on-device translation (implemented 2026-08-04)

## Decision (user-confirmed)

Live subtitles gain an optional translation mode with a target-language
selector. The **`Auto` option means "keep the original language"** (user
decision — no automatic translation by default); explicit targets
`EN / ES / FR / DE / IT / PT / RU` activate on-device translation.

## Research conclusion (why not a model flag)

The bundled model **Nemotron 3.5 ASR Streaming 0.6B** is an ASR-only model:

- `supports_language_detect = true`, `supports_streaming = true`
- `supports_translate = false` → no internal translation of any kind.
  The Chinese output the user saw on Red Note is expected behavior
  (detect `zh-CN`, transcribe in that language).

`transcribe-cpp 0.1.3` exposes generic `Task::Translate` +
`RunOptions.target_language`, but those only work for models that declare
translation support; Whisper-family models translate **only to English**
(`chinese → english`), never to arbitrary targets. Sources:
`docs/models/nemotron-3.5-asr-streaming-0.6b.md` ("Translation /
diarization / VAD: not supported") and the model card.

## Architecture: cascade (ASR → text-to-text)

```text
system audio
   → Nemotron 3.5 ASR (existing engine, chunked transcribe_shared)
   → detected/transcribed text
   → on-device text translator (Google ML Kit, offline after pack download)
   → subtitles in the target language
```

- Live subtitles keep the **chunked `transcribe_shared`** path (yields the
  engine mutex between segments). `Engine::run_stream` was NOT adopted: it
  pumps the whole stream and would hold the process-wide `GLOBAL_ENGINE`
  mutex, freezing IME/popup dictation for the whole session.
- The engine call for subtitles now goes through a new
  `transcribe_subtitle` helper that **forces `Task::Transcribe`**: the
  global `model_translate` marker (Whisper → English) can never silently
  translate subtitle text behind the translator's back.

## New files

- `SubtitleTranslationTargets.java` — valid targets (`auto` +
  EN/ES/FR/DE/IT/PT/RU) and mapping to ML Kit `TranslateLanguage` codes.
- `SourceLanguageResolver.java` — pure-Java source-language resolution:
  fixed `model_language` marker wins; otherwise script detection
  (kana → ja, hangul → ko, CJK → zh, Cyrillic → ru) with a conservative
  Latin heuristic using only *distinctive* diacritics (ñ/¿/¡ → es, ß → de,
  œ → fr, â/ê/î/ô/û/ç → fr, ä/ö/ü → de, ì/ò/ù → it, ã/õ → pt). Unresolved
  → original text shown (never a wrong translation).
- `SubtitleTranslator.java` — interface (`translate`, `cancelAll`, callback
  with `onUnavailable`).
- `OnDeviceSubtitleTranslator.java` — ML Kit implementation
  (`com.google.mlkit:translate:17.0.2`): per-pair `Translator` cache,
  download-on-demand with failure → fallback to original, translators
  closed on cancel.

## Modified

- `SubtitlePrefs` — marker `subtitle_translation_target` (default `auto`).
- `LiveSubtitleService` — ordered segment pipeline (pending → translated →
  fallback), serial FIFO translation queue (max 8, head-of-line ordering
  enforced), per-session generation to drop late callbacks, single
  user-facing Toast when translation is unavailable.
- `MainActivity` + `activity_main.xml` — target-language spinner on the
  live-subtitle card; strings propagated to all 7 locales.
- `app/build.gradle.kts` — ML Kit translate dependency.
- `src/engine.rs` / `src/subtitle.rs` — `transcribe_subtitle` forces
  `Task::Transcribe` for the subtitle path.
- JVM tests: `SourceLanguageResolverTest`, `SubtitleTranslationTargetsTest`,
  `SubtitlePrefsTest` extended.

## Gates (local, 2026-08-04)

- `./gradlew testDebugUnitTest` → BUILD SUCCESSFUL, **71 tests**, 0 failures
  (34 previous + 37 new/expanded).
- `python3 scripts/check_translations.py` → PASS.
- `cargo fmt --all -- --check` → clean.
- `./gradlew lintDebug` → BUILD SUCCESSFUL.

## Pending (device-only)

- Real latency with a language pack; memory footprint of ML Kit alongside
  the engine; Play Services pack download UX; full end-to-end with Chinese
  audio from Red Note → Spanish/English subtitles; devices without Google
  Play Services (must fall back to original + notice).
- CI confirmation of the new tests/gates on a pushed commit.

## Trade-off flagged to the user

ML Kit is a Google dependency: language packs download via Play Services
(translation itself is local/offline afterwards). Devices without Play
Services fall back to original text. The alternative (native Rust
translation engine) was deferred as heavier and more complex.
