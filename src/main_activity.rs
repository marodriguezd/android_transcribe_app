use crate::engine;
use jni::objects::{JObject, JString};
use jni::JNIEnv;
use std::sync::Arc;
use transcribe_rs::engines::parakeet::CanaryLanguage;

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    mut env: JNIEnv,
    _class: JObject,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let (vm_arc, activity_ref) = match env.with_local_frame(16, |env| {
        let vm = env.get_java_vm()?;
        let activity_ref = env.new_global_ref(&activity)?;
        Ok::<_, jni::errors::Error>((Arc::new(vm), activity_ref))
    }) {
        Ok(r) => r,
        Err(e) => {
            log::error!("JNI initNative failed: {}", e);
            return;
        }
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_arc, &activity_ref);
    });
}

/// Switch the loaded model variant. Called from Java after the user selects
/// a different model and the download (if needed) has completed.
///
/// `variant_str` must be `"0.6b"` or `"180m"`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_switchModel(
    mut env: JNIEnv,
    _class: JObject,
    activity: JObject,
    variant_str: JString,
) {
    let variant: String = match env.with_local_frame(16, |env| {
        env.get_string(&variant_str).map(|s| s.into())
    }) {
        Ok(s) => s,
        Err(_) => return,
    };

    let model_variant = match variant.as_str() {
        "180m" => engine::ModelVariant::V180m,
        "none" => engine::ModelVariant::None,
        _ => engine::ModelVariant::V0_6b,
    };

    match engine::switch_model(&mut env, &activity, model_variant) {
        Ok(()) => {}
        Err(e) => {
            log::error!("Failed to switch model: {}", e);
        }
    }
}

/// Update the source/target language used by the Canary 180M decoder on the
/// next transcription. `lang_str` is the same string the Java preference
/// stores (`"auto"`, `"en"`, `"es"`, `"de"`, `"fr"`). Unknown values fall
/// through to `Auto` so the decoder still gets a well-formed prefix
/// rather than crashing on a malformed input.
///
/// Called from the ChipGroup listener in MainActivity whenever the user
/// taps a different language chip. Does NOT reload the ONNX session — the
/// 180M model rebuilds the prefix from cached token IDs on every
/// `transcribe_samples` call, so the switch is in-memory only (sub-ms).
///
/// On the 0.6B variant this is a no-op (the engine method logs
/// `changed=false`); the JNI bridge still returns Ok so the Java caller
/// does not have to branch on the variant.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_nativeSetLanguage(
    mut env: JNIEnv,
    _class: JObject,
    activity: JObject,
    lang_str: JString,
) {
    let lang_s: String = match env.with_local_frame(16, |env| {
        env.get_string(&lang_str).map(|s| s.into())
    }) {
        Ok(s) => s,
        Err(e) => {
            log::error!("nativeSetLanguage: failed to read lang_str: {}", e);
            return;
        }
    };
    let lang = CanaryLanguage::from_pref(&lang_s);
    if let Err(e) = engine::set_language(lang) {
        log::error!("Failed to set language: {}", e);
    }
}
