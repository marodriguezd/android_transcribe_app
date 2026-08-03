//! Centralized JNI callbacks and helpers.
//!
//! Provides clean, reusable wrappers for calling standard Java callbacks
//! (`onStatusUpdate`, `onTextTranscribed`, `onPartialText`, `onAudioLevel`, `onSubtitleText`, `onAutoStop`)
//! safely from Rust code across all surfaces.

use jni::objects::JObject;
use jni::JNIEnv;

/// Invokes `onStatusUpdate(String)` on the target Java object.
pub fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

/// Invokes the recording-scoped status callback. The Java side rejects the
/// callback when its generation no longer matches the active recording.
pub fn notify_status_with_session(env: &mut JNIEnv, obj: &JObject, msg: &str, session_id: i32) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;I)V",
            &[(&jmsg).into(), session_id.into()],
        );
    }
}

/// Invokes `onTextTranscribed(String)` on the target Java object.
pub fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

/// Invokes the recording-scoped final-text callback.
pub fn notify_text_with_session(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;I)V",
            &[(&jtxt).into(), session_id.into()],
        );
    }
}

/// Invokes `onPartialText(String)` on the target Java object: a live partial
/// hypothesis from a streaming model while recording. Java surfaces treat it
/// as visual-only (the final text always arrives via `onTextTranscribed`).
pub fn notify_partial(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onPartialText",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

/// Invokes the recording-scoped partial-text callback.
pub fn notify_partial_with_session(env: &mut JNIEnv, obj: &JObject, text: &str, session_id: i32) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onPartialText",
            "(Ljava/lang/String;I)V",
            &[(&jtxt).into(), session_id.into()],
        );
    }
}

/// Invokes `onAudioLevel(float)` on the target Java object.
pub fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

/// Invokes the recording-scoped audio-level callback.
pub fn notify_level_with_session(env: &mut JNIEnv, obj: &JObject, level: f32, session_id: i32) {
    let _ = env.call_method(
        obj,
        "onAudioLevel",
        "(FI)V",
        &[level.into(), session_id.into()],
    );
}

/// Invokes `onSubtitleText(String, boolean)` on the target Java object.
pub fn notify_subtitle(env: &mut JNIEnv, obj: &JObject, text: &str, is_final: bool) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onSubtitleText",
            "(Ljava/lang/String;Z)V",
            &[(&jtxt).into(), is_final.into()],
        );
    }
}

/// Invokes `onAutoStop()` on the target Java object.
pub fn notify_auto_stop(env: &mut JNIEnv, obj: &JObject) {
    let _ = env.call_method(obj, "onAutoStop", "()V", &[]);
}

/// Invokes the recording-scoped auto-stop callback.
pub fn notify_auto_stop_with_session(env: &mut JNIEnv, obj: &JObject, session_id: i32) {
    let _ = env.call_method(obj, "onAutoStop", "(I)V", &[session_id.into()]);
}
