use std::sync::{Arc, Mutex};

use jni::objects::{JFloatArray, JObject};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use zeroize::Zeroize;

use crate::engine;

struct TranscribeFileState {
    jvm: Arc<jni::JavaVM>,
    target_ref: jni::objects::GlobalRef,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

fn state_lock() -> std::sync::MutexGuard<'static, Option<TranscribeFileState>> {
    STATE.lock().unwrap_or_else(|poisoned| {
        log::error!("STATE mutex poisoned, recovering");
        poisoned.into_inner()
    })
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        if let Err(err) = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        ) {
            log::error!("transcribe_file notify_status error: {}", err);
            let _ = env.exception_clear();
        }
    }
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        if let Err(err) = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        ) {
            log::error!("transcribe_file notify_text error: {}", err);
            let _ = env.exception_clear();
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    mut env: JNIEnv,
    _class: JObject,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let (vm_arc, target_ref) = match env.with_local_frame(16, |env| {
        let vm = env.get_java_vm()?;
        let target_ref = env.new_global_ref(&activity)?;
        Ok::<_, jni::errors::Error>((Arc::new(vm), target_ref))
    }) {
        Ok(r) => r,
        Err(e) => {
            log::error!("JNI initNative failed: {}", e);
            return;
        }
    };

    let state = TranscribeFileState {
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
    };
    *state_lock() = Some(state);

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    mut _env: JNIEnv,
    _class: JObject,
) {
    let _auto_frame = crate::AutoLocalFrame::new(&_env, 16);
    *state_lock() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_transcribeAudio(
    mut env: JNIEnv,
    _class: JObject,
    samples_array: JFloatArray,
    length: jint,
) {
    let guard = state_lock();
    let state = match guard.as_ref() {
        Some(s) => s,
        None => return,
    };

    let len = length as usize;
    if len == 0 {
        log::warn!("transcribeAudio called with empty buffer");
        let jvm = state.jvm.clone();
        let target_ref = state.target_ref.clone();
        drop(guard);
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(
                &mut env,
                target_ref.as_obj(),
                "Error: no audio data to transcribe",
            );
        }
        return;
    }

    let mut buffer = vec![0.0f32; len];
    let read_ok: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        env.get_float_array_region(&samples_array, 0, &mut buffer)
    });
    if read_ok.is_err() {
        log::error!("Failed to read float array from Java");
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    // Drop the lock before spawning the thread
    drop(guard);

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Wait for engine if somehow still loading
        let (_variant, eng_arc) = match engine::ensure_loaded(&mut env, obj) {
            Ok(Some(engine)) => engine,
            _ => {
                notify_status(&mut env, obj, "Error: model not loaded");
                return;
            }
        };

        notify_status(&mut env, obj, "Transcribing...");

        let res = {
            let mut eng = match eng_arc.lock() {
                Ok(g) => g,
                Err(poisoned) => {
                    log::error!("engine mutex poisoned, recovering");
                    poisoned.into_inner()
                }
            };
            eng.transcribe_samples(buffer)
        };

        match res {
            Ok(mut r) => {
                notify_text(&mut env, obj, &r.text);
                Zeroize::zeroize(&mut r.text);
            }
            Err(e) => {
                notify_status(&mut env, obj, &format!("Error: {}", e));
            }
        }
    });
}
