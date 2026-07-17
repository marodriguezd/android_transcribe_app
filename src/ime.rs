use jni::objects::JObject;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

fn ime_lock() -> std::sync::MutexGuard<'static, Option<VoiceSessionState>> {
    IME_STATE.lock().unwrap_or_else(|poisoned| {
        log::error!("IME_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    mut env: JNIEnv,
    _class: JObject,
    service: JObject,
) {
    let state: Result<Option<VoiceSessionState>, jni::errors::Error> =
        env.with_local_frame(16, |env| {
            Ok(voice_session::init_session(env, service))
        });
    if let Some(state) = state.unwrap_or(None) {
        *ime_lock() = Some(state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JObject,
) {
    *ime_lock() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = ime_lock();
        if let Some(state) = guard.as_mut() {
            voice_session::start_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = ime_lock();
        if let Some(state) = guard.as_mut() {
            voice_session::stop_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = ime_lock();
        if let Some(state) = guard.as_mut() {
            voice_session::cancel_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_setHotwords(
    mut env: JNIEnv,
    _class: JObject,
    string_array: jni::objects::JObjectArray,
) {
    let mut words = Vec::new();
    let result: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let length = env.get_array_length(&string_array)?;
        for i in 0..length {
            // Each iteration gets its own local frame to prevent ref accumulation
            let s: Result<String, jni::errors::Error> = env.with_local_frame(16, |env| {
                let jstr_obj = env.get_object_array_element(&string_array, i)?;
                let jstr: jni::objects::JString = jstr_obj.into();
                let s = env.get_string(&jstr)?;
                Ok(s.to_string_lossy().into_owned())
            });
            if let Ok(s) = s {
                if !s.is_empty() {
                    words.push(s);
                }
            }
        }
        Ok(())
    });
    if result.is_err() {
        return;
    }

    // Pass words to the parakeet engine
    if let Some((_variant, engine)) = crate::engine::get_engine() {
        if let Ok(mut eng) = engine.lock() {
            eng.set_hotwords(words);
        }
    }
}
