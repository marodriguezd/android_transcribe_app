use std::sync::Mutex;

use jni::objects::JObject;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::voice_session::{self, VoiceSessionState};

static RECOG_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

fn recog_lock() -> std::sync::MutexGuard<'static, Option<VoiceSessionState>> {
    RECOG_STATE.lock().unwrap_or_else(|poisoned| {
        log::error!("RECOG_STATE mutex poisoned, recovering");
        poisoned.into_inner()
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_initNative(
    mut env: JNIEnv,
    _class: JObject,
    activity: JObject,
) {
    let state: Result<Option<VoiceSessionState>, jni::errors::Error> =
        env.with_local_frame(16, |env| {
            Ok(voice_session::init_session(env, activity))
        });
    let state = match state {
        Ok(Some(s)) => s,
        _ => return,
    };
    *recog_lock() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cleanupNative(
    mut _env: JNIEnv,
    _class: JObject,
) {
    let _auto_frame = crate::AutoLocalFrame::new(&_env, 16);
    *recog_lock() = None;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_startRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = recog_lock();
        if let Some(state) = guard.as_mut() {
            voice_session::start_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_stopRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = recog_lock();
        if let Some(state) = guard.as_mut() {
            voice_session::stop_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cancelRecording(
    mut env: JNIEnv,
    _class: JObject,
) {
    let _: Result<(), jni::errors::Error> = env.with_local_frame(16, |env| {
        let mut guard = recog_lock();
        if let Some(state) = guard.as_mut() {
            crate::voice_session::cancel_recording(env, state);
        }
        Ok(())
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_getAudioLevelNative(
    mut _env: JNIEnv,
    _class: JObject,
) -> jni::sys::jfloat {
    let _auto_frame = crate::AutoLocalFrame::new(&_env, 16);
    let guard = recog_lock();
    if let Some(state) = guard.as_ref() {
        let bits = state.current_level.load(std::sync::atomic::Ordering::Relaxed);
        f32::from_bits(bits)
    } else {
        0.0
    }
}
