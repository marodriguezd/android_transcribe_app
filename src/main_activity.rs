use crate::engine;
use jni::objects::{JClass, JObject, JString};
use jni::JNIEnv;
use std::sync::Arc;

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let _ = ort::init().commit();

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let activity_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_arc, &activity_ref);
    });
}

/// Switch the loaded model variant. Called from Java after the user selects
/// a different model and the download (if needed) has completed.
///
/// `variant_str` must be `"0.6b"` or `"1.1b"`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_switchModel(
    mut env: JNIEnv,
    _class: JClass,
    activity: JObject,
    variant_str: JString,
) {
    let variant: String = match env.get_string(&variant_str) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    let model_variant = match variant.as_str() {
        "1.1b" => engine::ModelVariant::V1_1b,
        _ => engine::ModelVariant::V0_6b,
    };

    match engine::switch_model(&mut env, &activity, model_variant) {
        Ok(()) => {}
        Err(e) => {
            log::error!("Failed to switch model: {}", e);
        }
    }
}
