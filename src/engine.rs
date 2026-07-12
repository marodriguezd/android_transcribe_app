use once_cell::sync::Lazy;
use std::sync::{Arc, Condvar, Mutex};
use transcribe_rs::engines::parakeet::{Parakeet1_1bModel, ParakeetEngine};
use transcribe_rs::TranscriptionEngine;

use jni::objects::{GlobalRef, JObject, JString};
use jni::JNIEnv;


/// Which model variant is loaded.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ModelVariant {
    V0_6b,
    V1_1b,
}

/// Wraps either a 0.6B or 1.1B engine behind a common interface.
pub enum EngineWrapper {
    V0_6b(ParakeetEngine),
    V1_1b(Parakeet1_1bModel),
}

impl EngineWrapper {
    pub fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
    ) -> Result<transcribe_rs::TranscriptionResult, Box<dyn std::error::Error>> {
        match self {
            EngineWrapper::V0_6b(eng) => eng.transcribe_samples(samples, None),
            EngineWrapper::V1_1b(m) => {
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
            EngineWrapper::V1_1b(_) => { /* hotwords not supported for 1.1B yet */ }
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
    GLOBAL_ENGINE.lock().unwrap().clone()
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
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
                "1.1b" => ModelVariant::V1_1b,
                _ => ModelVariant::V0_6b,
            }
        }
        Err(_) => ModelVariant::V0_6b,
    }
}

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            notify_status(env, context, "Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);
            if is_engine_loaded() {
                notify_status(env, context, "Ready");
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                notify_status(env, context, &format!("Error: {}", msg));
                Err(msg)
            }
        }
        LoadState::Done => {
            notify_status(env, context, "Ready");
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = do_load(env, context);

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

/// Like `ensure_loaded` but for use from a background thread.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    if is_engine_loaded() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), "Ready");
        }
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    if is_engine_loaded() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), "Ready");
        }
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            if let Ok(mut env) = jvm.attach_current_thread() {
                notify_status(&mut env, target_ref.as_obj(), "Waiting for model...");
            }
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);
            if is_engine_loaded() {
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_status(&mut env, target_ref.as_obj(), "Ready");
                }
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_status(&mut env, target_ref.as_obj(), &format!("Error: {}", msg));
                }
                Err(msg)
            }
        }
        LoadState::Done => {
            if let Ok(mut env) = jvm.attach_current_thread() {
                notify_status(&mut env, target_ref.as_obj(), "Ready");
            }
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = if let Ok(mut env) = jvm.attach_current_thread() {
                let obj = target_ref.as_obj();
                do_load(&mut env, obj)
            } else {
                Err("Failed to attach JNI thread".to_string())
            };

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

/// Unload the current engine and reload with the specified variant.
pub fn switch_model(env: &mut JNIEnv, context: &JObject, variant: ModelVariant) -> Result<(), String> {
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    // Wait if another load is in progress
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap();
    }

    // Clear the engine while holding the load lock so no other thread
    // can observe the engine as loaded while we are about to reload.
    *GLOBAL_ENGINE.lock().unwrap() = None;

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

fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let variant = read_model_variant(env, context);
    do_load_with_variant(env, context, variant)
}

fn do_load_with_variant(env: &mut JNIEnv, context: &JObject, variant: ModelVariant) -> Result<(), String> {
    match variant {
        ModelVariant::V0_6b => do_load_0_6b(env, context),
        ModelVariant::V1_1b => do_load_1_1b(env, context),
    }
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
    let vocab_content = std::fs::read_to_string(&vocab_path)
        .map_err(|_e| format!("Model 0.6B not downloaded. Please download it from Settings."))?;

    notify_status(env, context, "Loading encoder...");
    let encoder_path = format!("{}/encoder-model.int8.onnx", model_dir);
    let encoder_bytes = std::fs::read(&encoder_path)
        .map_err(|_e| format!("Model 0.6B not downloaded. Please download it from Settings."))?;

    notify_status(env, context, "Loading decoder...");
    let decoder_path = format!("{}/decoder_joint-model.int8.onnx", model_dir);
    let decoder_bytes = std::fs::read(&decoder_path)
        .map_err(|_e| format!("Model 0.6B not downloaded. Please download it from Settings."))?;

    notify_status(env, context, "Loading preprocessor...");
    let preprocessor_path = format!("{}/nemo128.onnx", model_dir);
    let preprocessor_bytes = std::fs::read(&preprocessor_path)
        .map_err(|_e| format!("Model 0.6B not downloaded. Please download it from Settings."))?;

    notify_status(env, context, "Initializing engine...");

    let mut eng = ParakeetEngine::new();
    match eng.load_model_from_memory(
        &encoder_bytes,
        &decoder_bytes,
        &preprocessor_bytes,
        &vocab_content,
    ) {
        Ok(_) => {
            *GLOBAL_ENGINE.lock().unwrap() = Some((ModelVariant::V0_6b, Arc::new(Mutex::new(EngineWrapper::V0_6b(eng)))));
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

fn do_load_1_1b(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    notify_status(env, context, "Loading precise model (1.1B)...");

    // 1.1B model files are stored in internal storage (downloaded on demand).
    // Path: getFilesDir()/models/parakeet-tdt-1.1b-v3-int8/
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

    let model_dir = format!("{}/models/parakeet-tdt-1.1b-v3-int8", files_path);

    notify_status(env, context, "Reading vocabulary...");
    let vocab_path = format!("{}/tokens.txt", model_dir);
    let vocab_content = std::fs::read_to_string(&vocab_path)
        .map_err(|e| format!("Failed to read {}: {}", vocab_path, e))?;

    notify_status(env, context, "Loading encoder...");
    let encoder_path = format!("{}/encoder.int8.onnx", model_dir);
    let encoder_bytes = std::fs::read(&encoder_path)
        .map_err(|e| format!("Failed to read {}: {}", encoder_path, e))?;

    notify_status(env, context, "Loading decoder...");
    let decoder_path = format!("{}/decoder.int8.onnx", model_dir);
    let decoder_bytes = std::fs::read(&decoder_path)
        .map_err(|e| format!("Failed to read {}: {}", decoder_path, e))?;

    notify_status(env, context, "Loading joiner...");
    let joiner_path = format!("{}/joiner.int8.onnx", model_dir);
    let joiner_bytes = std::fs::read(&joiner_path)
        .map_err(|e| format!("Failed to read {}: {}", joiner_path, e))?;

    notify_status(env, context, "Initializing precise engine...");

    match Parakeet1_1bModel::from_memory(
        &encoder_bytes,
        &decoder_bytes,
        &joiner_bytes,
        &vocab_content,
    ) {
        Ok(model) => {
            *GLOBAL_ENGINE.lock().unwrap() = Some((ModelVariant::V1_1b, Arc::new(Mutex::new(EngineWrapper::V1_1b(model)))));
            notify_status(env, context, "Ready");
            Ok(())
        }
        Err(e) => {
            let msg = format!("1.1B model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
