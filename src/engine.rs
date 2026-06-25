use once_cell::sync::Lazy;
use std::sync::{Arc, Condvar, Mutex};
use transcribe_rs::engines::parakeet::ParakeetEngine;

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::assets;

/// Holds the loaded engine singleton.
static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<ParakeetEngine>>>>> =
    Lazy::new(|| Mutex::new(None));

/// Loading coordination state + condvar for waiters.
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    /// No load in progress
    Idle,
    /// A thread is currently loading the model
    Loading,
    /// Loading completed successfully
    Done,
    /// Loading failed
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<ParakeetEngine>>> {
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

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
///
/// - If already loaded, returns immediately.
/// - If another thread is loading, waits for it to finish (polling + condvar).
/// - If no one is loading, this thread takes ownership of loading.
/// - If a previous load failed, retries.
///
/// Reports status via `onStatusUpdate` JNI callback on `target`.
/// `env` and `context` are needed for asset extraction (only used if this thread loads).
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    // Fast path: already loaded
    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    // Re-check under lock
    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            // Another thread is loading — wait for it
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
            // We take ownership of loading (retry on previous failure)
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

/// Like `ensure_loaded` but for use from a background thread that has its own
/// JNIEnv obtained via `attach_current_thread`. Takes a JVM and GlobalRef
/// instead of borrowing env/context.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    // Fast path
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

fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    notify_status(env, context, "Accessing assets...");

    let asset_manager_obj = env
        .call_method(
            context,
            "getAssets",
            "()Landroid/content/res/AssetManager;",
            &[],
        )
        .and_then(|v| v.l())
        .map_err(|e| format!("Failed to get AssetManager: {}", e))?;

    notify_status(env, context, "Reading vocabulary...");
    let vocab_content = assets::read_asset_to_string(env, &asset_manager_obj, "parakeet-tdt-0.6b-v3-int8/vocab.txt")
        .map_err(|e| format!("Failed to read vocab.txt: {}", e))?;

    notify_status(env, context, "Mapping model files...");
    let encoder_mapped = assets::mmap_asset(env, &asset_manager_obj, "parakeet-tdt-0.6b-v3-int8/encoder-model.int8.onnx")
        .map_err(|e| format!("Failed to map encoder-model.int8.onnx: {}", e))?;

    let decoder_mapped = assets::mmap_asset(env, &asset_manager_obj, "parakeet-tdt-0.6b-v3-int8/decoder_joint-model.int8.onnx")
        .map_err(|e| format!("Failed to map decoder_joint-model.int8.onnx: {}", e))?;

    let preprocessor_mapped = assets::mmap_asset(env, &asset_manager_obj, "parakeet-tdt-0.6b-v3-int8/nemo128.onnx")
        .map_err(|e| format!("Failed to map nemo128.onnx: {}", e))?;

    notify_status(env, context, "Initializing engine...");

    let mut eng = ParakeetEngine::new();
    match eng.load_model_from_memory(
        encoder_mapped.as_slice(),
        decoder_mapped.as_slice(),
        preprocessor_mapped.as_slice(),
        &vocab_content,
    ) {
        Ok(_) => {
            *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(eng)));
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
