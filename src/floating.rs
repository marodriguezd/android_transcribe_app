use std::sync::Mutex;

use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::voice_session::{self, VoiceSessionState};

static FLOATING_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *FLOATING_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *FLOATING_STATE.lock().unwrap() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_startRecording(
    env: JNIEnv,
    _class: JClass,
    auto_stop: jni::sys::jboolean,
    session_id: jni::sys::jint,
) {
    let mut guard = FLOATING_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::start_recording(env, state, auto_stop != 0, session_id as i32);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = FLOATING_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FloatingOverlayService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = FLOATING_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}
