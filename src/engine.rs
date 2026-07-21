use once_cell::sync::Lazy;
use std::sync::{Arc, Condvar, Mutex};
use transcribe_rs::engines::parakeet::{CanaryLanguage, Parakeet180mModel, ParakeetEngine};
use transcribe_rs::TranscriptionEngine;

use jni::objects::{GlobalRef, JObject, JString};
use jni::JNIEnv;


/// Which model variant is loaded.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ModelVariant {
    V0_6b,
    V180m,
    None,
}

/// Wraps either a 0.6B or 180M engine behind a common interface.
pub enum EngineWrapper {
    V0_6b(ParakeetEngine),
    V180m(Parakeet180mModel),
}

impl EngineWrapper {
    pub fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
    ) -> Result<transcribe_rs::TranscriptionResult, Box<dyn std::error::Error>> {
        match self {
            EngineWrapper::V0_6b(eng) => eng.transcribe_samples(samples, None),
            EngineWrapper::V180m(m) => {
                let result = m.transcribe_samples(samples)?;
                Ok(transcribe_rs::TranscriptionResult {
                    text: result.text,
                    segments: None,
                })
            }
        }
    }

    pub fn set_hotwords(&mut self, words: Vec<String>) {
        match self {
            EngineWrapper::V0_6b(eng) => eng.set_hotwords(words),
            EngineWrapper::V180m(_) => { /* hotwords not supported for 180M yet */ }
        }
    }

    /// Update the language the next transcription will use. Only the Canary
    /// 180M variant tokenises source/target language explicitly in its
    /// decoder prefix; the 0.6B Parakeet TDT path is language-agnostic and
    /// auto-detects from the audio, so the renderer call is a no-op there.
    /// Returns true if the call caused a state change, false otherwise
    /// (useful for the JNI bridge to log "noop for v0_6b" without silent drops).
    pub fn set_language(&mut self, lang: CanaryLanguage) -> bool {
        match self {
            EngineWrapper::V0_6b(_) => false,
            EngineWrapper::V180m(m) => {
                m.set_language(lang);
                true
            }
        }
    }

    /// Current language the Canary variant will use for the next call.
    /// 0.6B Parakeet is a CTC transducer with no explicit language state,
    /// so we return `Auto` (matches the actual model behaviour — v3 is
    /// auto-detecting multilingual).
    pub fn current_language(&self) -> CanaryLanguage {
        match self {
            EngineWrapper::V0_6b(_) => CanaryLanguage::Auto,
            EngineWrapper::V180m(m) => m.current_language(),
        }
    }
}

/// Holds the loaded engine singleton + which variant is active.
type EngineState = Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)>;
static GLOBAL_ENGINE: Lazy<Mutex<EngineState>> =
    Lazy::new(|| Mutex::new(None));

/// Loading coordination state + condvar for waiters.
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    Idle,
    Loading,
    Done,
    Failed(String),
}

pub fn get_engine() -> Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)> {
    GLOBAL_ENGINE.lock().unwrap_or_else(|poisoned| {
        log::error!("GLOBAL_ENGINE mutex poisoned, recovering");
        poisoned.into_inner()
    }).clone()
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        if let Err(err) = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        ) {
            log::error!("engine notify_status error: {}", err);
            let _ = env.exception_clear();
        }
    }
}

/// Read the selected model variant from Java SharedPreferences.
fn read_model_variant(env: &mut JNIEnv, context: &JObject) -> ModelVariant {
    let prefs_name = match env.new_string("transcribe_settings") {
        Ok(s) => s,
        Err(_) => return ModelVariant::V0_6b,
    };
    let prefs = match env.call_method(
        context,
        "getSharedPreferences",
        "(Ljava/lang/String;I)Landroid/content/SharedPreferences;",
        &[(&prefs_name).into(), jni::objects::JValue::Int(0)],
    ) {
        Ok(v) => match v.l() {
            Ok(o) => o,
            Err(_) => return ModelVariant::V0_6b,
        },
        Err(_) => return ModelVariant::V0_6b,
    };

    let key = match env.new_string("model_variant") {
        Ok(s) => s,
        Err(_) => return ModelVariant::V0_6b,
    };
    let default = match env.new_string("0.6b") {
        Ok(s) => s,
        Err(_) => return ModelVariant::V0_6b,
    };

    let result = env.call_method(
        &prefs,
        "getString",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        &[(&key).into(), (&default).into()],
    );

    match result.and_then(|v| v.l()) {
        Ok(jobj) => {
            let jstr = JString::from(jobj);
            let variant_str: String = env
                .get_string(&jstr)
                .map(|s| s.into())
                .unwrap_or_else(|_| "0.6b".to_string());
            match variant_str.as_str() {
                "180m" => ModelVariant::V180m,
                "none" => ModelVariant::None,
                _ => ModelVariant::V0_6b,
            }
        }
        Err(_) => ModelVariant::V0_6b,
    }
}

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
/// Returns the loaded engine directly, eliminating the TOCTOU race between
/// a separate `ensure_loaded` + `get_engine` call pair.
pub fn ensure_loaded(
    env: &mut JNIEnv,
    context: &JObject,
) -> Result<Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)>, String> {
    if let Some(engine) = get_engine() {
        notify_status(env, context, "Ready");
        return Ok(Some(engine));
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap_or_else(|poisoned| {
        log::error!("LOAD_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    });

    if let Some(engine) = get_engine() {
        notify_status(env, context, "Ready");
        return Ok(Some(engine));
    }

    match &*state {
        LoadState::Loading => {
            notify_status(env, context, "Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap_or_else(|poisoned| {
                    log::error!("LOAD_STATE condvar poisoned, recovering");
                    poisoned.into_inner()
                });
            }
            drop(state);
            match get_engine() {
                Some(engine) => {
                    notify_status(env, context, "Ready");
                    Ok(Some(engine))
                }
                None => {
                    let msg = "Model failed to load".to_string();
                    notify_status(env, context, &format!("Error: {}", msg));
                    Err(msg)
                }
            }
        }
        LoadState::Done => {
            notify_status(env, context, "Ready");
            match get_engine() {
                Some(engine) => Ok(Some(engine)),
                None => Err("Model failed to load".to_string()),
            }
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| do_load(env, context))) {
                Ok(r) => r,
                Err(panic) => {
                    let msg = panic.downcast_ref::<&str>()
                        .map(|s| format!("Model loading panicked: {}", s))
                        .or_else(|| panic.downcast_ref::<String>().map(|s| format!("Model loading panicked: {}", s)))
                        .unwrap_or_else(|| "Model loading panicked".to_string());
                    log::error!("{}", msg);
                    Err(msg)
                }
            };

            let mut state = lock.lock().unwrap_or_else(|poisoned| {
                log::error!("LOAD_STATE mutex poisoned, recovering");
                poisoned.into_inner()
            });
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            drop(state);
            match result {
                Ok(()) => match get_engine() {
                    Some(engine) => Ok(Some(engine)),
                    None => Err("Model failed to load".to_string()),
                },
                Err(e) => Err(e),
            }
        }
    }
}

/// Like `ensure_loaded` but for use from a background thread.
/// Returns the loaded engine directly, eliminating the TOCTOU race between
/// a separate `ensure_loaded_from_thread` + `get_engine` call pair.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<Option<(ModelVariant, Arc<Mutex<EngineWrapper>>)>, String> {
    let mut env = jvm.attach_current_thread()
        .map_err(|e| format!("Failed to attach JNI thread: {}", e))?;
    let obj = target_ref.as_obj();

    if let Some(engine) = get_engine() {
        notify_status(&mut env, obj, "Ready");
        return Ok(Some(engine));
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap_or_else(|poisoned| {
        log::error!("LOAD_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    });

    if let Some(engine) = get_engine() {
        notify_status(&mut env, obj, "Ready");
        return Ok(Some(engine));
    }

    match &*state {
        LoadState::Loading => {
            notify_status(&mut env, obj, "Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap_or_else(|poisoned| {
                    log::error!("LOAD_STATE condvar poisoned, recovering");
                    poisoned.into_inner()
                });
            }
            drop(state);
            match get_engine() {
                Some(engine) => {
                    notify_status(&mut env, obj, "Ready");
                    Ok(Some(engine))
                }
                None => {
                    let msg = "Model failed to load".to_string();
                    notify_status(&mut env, obj, &format!("Error: {}", msg));
                    Err(msg)
                }
            }
        }
        LoadState::Done => {
            notify_status(&mut env, obj, "Ready");
            match get_engine() {
                Some(engine) => Ok(Some(engine)),
                None => Err("Model failed to load".to_string()),
            }
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| do_load(&mut env, obj))) {
                Ok(r) => r,
                Err(panic) => {
                    let msg = panic.downcast_ref::<&str>()
                        .map(|s| format!("Model loading panicked: {}", s))
                        .or_else(|| panic.downcast_ref::<String>().map(|s| format!("Model loading panicked: {}", s)))
                        .unwrap_or_else(|| "Model loading panicked".to_string());
                    log::error!("{}", msg);
                    Err(msg)
                }
            };

            let mut state = lock.lock().unwrap_or_else(|poisoned| {
                log::error!("LOAD_STATE mutex poisoned, recovering");
                poisoned.into_inner()
            });
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            drop(state);
            match result {
                Ok(()) => match get_engine() {
                    Some(engine) => Ok(Some(engine)),
                    None => Err("Model failed to load".to_string()),
                },
                Err(e) => Err(e),
            }
        }
    }
}

/// Unload the current engine and reload with the specified variant.
pub fn switch_model(env: &mut JNIEnv, context: &JObject, variant: ModelVariant) -> Result<(), String> {
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap_or_else(|poisoned| {
        log::error!("LOAD_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    });

    // Wait if another load is in progress
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap_or_else(|poisoned| {
            log::error!("LOAD_STATE condvar poisoned, recovering");
            poisoned.into_inner()
        });
    }

    *state = LoadState::Loading;
    drop(state);

    let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| do_load_with_variant(env, context, variant))) {
        Ok(r) => r,
        Err(panic) => {
            let msg = panic.downcast_ref::<&str>()
                .map(|s| format!("Model loading panicked: {}", s))
                .or_else(|| panic.downcast_ref::<String>().map(|s| format!("Model loading panicked: {}", s)))
                .unwrap_or_else(|| "Model loading panicked".to_string());
            log::error!("{}", msg);
            Err(msg)
        }
    };

    let mut state = lock.lock().unwrap_or_else(|poisoned| {
        log::error!("LOAD_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    });
    match &result {
        Ok(()) => *state = LoadState::Done,
        Err(msg) => *state = LoadState::Failed(msg.clone()),
    }
    cvar.notify_all();

    result
}

/// Update the source/target language on the currently loaded Canary 180M
/// engine. No-op when the loaded engine is the 0.6B Parakeet (does not
/// expose a language context — it is CTC + auto-detect). Returns an error
/// if no engine is loaded yet; the caller is expected to retry on the
/// next engine-load event (e.g. the user picks Canary from the variant
/// radio). The 180M load path (`do_load_180m`) applies the persisted
/// preference on construction, so a fresh download picks up the user's
/// last selection without needing this call.
pub fn set_language(lang: CanaryLanguage) -> Result<(), String> {
    let guard = GLOBAL_ENGINE.lock().unwrap_or_else(|poisoned| {
        log::error!("GLOBAL_ENGINE mutex poisoned, recovering");
        poisoned.into_inner()
    });
    let (_, eng_arc) = guard
        .as_ref()
        .ok_or_else(|| "No engine loaded".to_string())?;
    let mut eng = eng_arc.lock().unwrap_or_else(|poisoned| {
        log::error!("engine mutex poisoned, recovering");
        poisoned.into_inner()
    });
    let changed = eng.set_language(lang);
    log::info!("engine set_language({:?}) changed={}", lang, changed);
    Ok(())
}

fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let variant = read_model_variant(env, context);
    do_load_with_variant(env, context, variant)
}

fn do_load_with_variant(env: &mut JNIEnv, context: &JObject, variant: ModelVariant) -> Result<(), String> {
    match variant {
        ModelVariant::V0_6b => do_load_0_6b(env, context),
        ModelVariant::V180m => do_load_180m(env, context),
        ModelVariant::None => {
            *GLOBAL_ENGINE.lock().unwrap_or_else(|poisoned| {
                log::error!("GLOBAL_ENGINE mutex poisoned, recovering");
                poisoned.into_inner()
            }) = None;
            notify_status(env, context, "No model loaded");
            Ok(())
        }
    }
}

/// Read the `transcription_language` preference from Java SharedPreferences.
/// Defaults to `"auto"` (so the Canary decoder gets `<|unklang|>` on the
/// source slot + `<|en|>` on the target slot — encoder auto-detects input
/// language, decoder outputs a deterministic English transcript). Unknown /
/// missing values are coerced to `Auto` by [`CanaryLanguage::from_pref`].
fn read_transcription_language(env: &mut JNIEnv, context: &JObject) -> CanaryLanguage {
    let prefs_name = match env.new_string("transcribe_settings") {
        Ok(s) => s,
        Err(_) => return CanaryLanguage::Auto,
    };
    let prefs = match env.call_method(
        context,
        "getSharedPreferences",
        "(Ljava/lang/String;I)Landroid/content/SharedPreferences;",
        &[(&prefs_name).into(), jni::objects::JValue::Int(0)],
    ) {
        Ok(v) => match v.l() {
            Ok(o) => o,
            Err(_) => return CanaryLanguage::Auto,
        },
        Err(_) => return CanaryLanguage::Auto,
    };
    let key = match env.new_string("transcription_language") {
        Ok(s) => s,
        Err(_) => return CanaryLanguage::Auto,
    };
    let default = match env.new_string("auto") {
        Ok(s) => s,
        Err(_) => return CanaryLanguage::Auto,
    };
    let result = env.call_method(
        &prefs,
        "getString",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        &[(&key).into(), (&default).into()],
    );
    let lang_str = match result.and_then(|v| v.l()) {
        Ok(jobj) => {
            let jstr = JString::from(jobj);
            env.get_string(&jstr)
                .map(|s| s.into())
                .unwrap_or_else(|_| "auto".to_string())
        }
        Err(_) => "auto".to_string(),
    };
    let parsed = CanaryLanguage::from_pref(&lang_str);
    log::info!(
        "read_transcription_language: pref={} (auto => unklang_src + en_target)",
        lang_str
    );
    parsed
}

fn do_load_0_6b(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    notify_status(env, context, "Loading fast model (0.6B)...");

    let files_dir = env
        .call_method(context, "getFilesDir", "()Ljava/io/File;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("Failed to get filesDir: {}", e))?;

    let files_path: String = {
        let jobj = env
            .call_method(&files_dir, "getAbsolutePath", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .map_err(|e| format!("Failed to get files path: {}", e))?;
        let jstr = JString::from(jobj);
        env.get_string(&jstr)
            .map(|s| s.into())
            .map_err(|e| format!("Failed to get files path: {}", e))?
    };

    let model_dir = format!("{}/models/parakeet-tdt-0.6b-v3-int8", files_path);

    notify_status(env, context, "Reading vocabulary...");
    let vocab_path = format!("{}/vocab.txt", model_dir);
    let     vocab_content = std::fs::read_to_string(&vocab_path)
        .map_err(|e| format!("Model 0.6B not downloaded: {}", e))?;

    notify_status(env, context, "Loading encoder...");
    let encoder_path = format!("{}/encoder-model.int8.onnx", model_dir);
    let encoder_bytes = std::fs::read(&encoder_path)
        .map_err(|e| format!("Model 0.6B not downloaded: {}", e))?;

    notify_status(env, context, "Loading decoder...");
    let decoder_path = format!("{}/decoder_joint-model.int8.onnx", model_dir);
    let decoder_bytes = std::fs::read(&decoder_path)
        .map_err(|e| format!("Model 0.6B not downloaded: {}", e))?;

    notify_status(env, context, "Loading preprocessor...");
    let preprocessor_path = format!("{}/nemo128.onnx", model_dir);
    let preprocessor_bytes = std::fs::read(&preprocessor_path)
        .map_err(|e| format!("Model 0.6B not downloaded: {}", e))?;

    notify_status(env, context, "Initializing engine...");

    let mut eng = ParakeetEngine::new();
    match eng.load_model_from_memory(
        &encoder_bytes,
        &decoder_bytes,
        &preprocessor_bytes,
        &vocab_content,
    ) {
        Ok(_) => {
            *GLOBAL_ENGINE.lock().unwrap_or_else(|poisoned| {
                log::error!("GLOBAL_ENGINE mutex poisoned, recovering");
                poisoned.into_inner()
            }) = Some((ModelVariant::V0_6b, Arc::new(Mutex::new(EngineWrapper::V0_6b(eng)))));
            notify_status(env, context, "Ready");
            Ok(())
        }
        Err(e) => {
            let msg = format!("Model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}

fn do_load_180m(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    notify_status(env, context, "Loading fastest model (180M)...");

    let files_dir = env
        .call_method(context, "getFilesDir", "()Ljava/io/File;", &[])
        .and_then(|v| v.l())
        .map_err(|e| format!("Failed to get filesDir: {}", e))?;

    let files_path: String = {
        let jobj = env
            .call_method(&files_dir, "getAbsolutePath", "()Ljava/lang/String;", &[])
            .and_then(|v| v.l())
            .map_err(|e| format!("Failed to get files path: {}", e))?;
        let jstr = JString::from(jobj);
        env.get_string(&jstr)
            .map(|s| s.into())
            .map_err(|e| format!("Failed to get files path: {}", e))?
    };

    let model_dir = format!("{}/models/canary-180m-flash-int8", files_path);

    notify_status(env, context, "Reading vocabulary...");
    let vocab_path = format!("{}/vocab.txt", model_dir);
    let vocab_content = std::fs::read_to_string(&vocab_path)
        .map_err(|e| format!("Model 180M not downloaded: {}", e))?;

    notify_status(env, context, "Loading encoder...");
    let encoder_path = format!("{}/encoder-model.int8.onnx", model_dir);
    let encoder_bytes = std::fs::read(&encoder_path)
        .map_err(|e| format!("Model 180M not downloaded: {}", e))?;

    notify_status(env, context, "Loading decoder...");
    let decoder_path = format!("{}/decoder-model.int8.onnx", model_dir);
    let decoder_bytes = std::fs::read(&decoder_path)
        .map_err(|e| format!("Model 180M not downloaded: {}", e))?;

    notify_status(env, context, "Initializing fastest engine...");

    // Read the persisted language preference BEFORE constructing the model
    // so its first `transcribe_samples` already uses the user's choice.
    // After construction we set the language on the loaded instance too so
    // the JNI bridge sees a consistent field (defensive against any caller
    // path that reads current_language() right after load).
    let initial_lang = read_transcription_language(env, context);

    match Parakeet180mModel::from_memory(
        &encoder_bytes,
        &decoder_bytes,
        &vocab_content,
    ) {
        Ok(mut model) => {
            model.set_language(initial_lang);
            *GLOBAL_ENGINE.lock().unwrap_or_else(|poisoned| {
                log::error!("GLOBAL_ENGINE mutex poisoned, recovering");
                poisoned.into_inner()
            }) = Some((ModelVariant::V180m, Arc::new(Mutex::new(EngineWrapper::V180m(model)))));
            notify_status(env, context, "Ready");
            Ok(())
        }
        Err(e) => {
            let msg = format!("180M model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
