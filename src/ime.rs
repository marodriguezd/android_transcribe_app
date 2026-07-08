use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *IME_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *IME_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::start_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_setHotwords(
    mut env: JNIEnv,
    _class: JClass,
    string_array: jni::objects::JObjectArray,
) {
    let mut words = Vec::new();
    let length = env.get_array_length(&string_array).unwrap_or(0);
    for i in 0..length {
        if let Ok(jstr_obj) = env.get_object_array_element(&string_array, i) {
            let string_val = {
                let jstr: jni::objects::JString = jstr_obj.into();
                env.get_string(&jstr).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
            };
            if !string_val.is_empty() {
                words.push(string_val);
            }
        }
    }
    
    // Pass words to the parakeet engine
    if let Some(engine) = crate::engine::get_engine() {
        use transcribe_rs::TranscriptionEngine;
        if let Ok(mut eng) = engine.lock() {
            eng.set_hotwords(words);
        }
    }
}
