pub mod assets;
pub mod engine;
pub mod ime;
pub mod main_activity;
pub mod recog_service;
pub mod recognize;
pub mod subtitle;
pub mod transcribe_file;
pub mod voice_session;

/// RAII guard that pushes a JNI local frame on construction and pops it on drop.
/// Mirrors the `auto_local_frame` pattern from newer jni crate versions.
pub struct AutoLocalFrame<'a> {
    env: &'a jni::JNIEnv<'a>,
}

impl<'a> AutoLocalFrame<'a> {
    pub fn new(env: &'a jni::JNIEnv<'a>, capacity: i32) -> Self {
        env.push_local_frame(capacity).expect("push_local_frame");
        AutoLocalFrame { env }
    }
}

impl Drop for AutoLocalFrame<'_> {
    fn drop(&mut self) {
        unsafe { let _ = self.env.pop_local_frame(&jni::objects::JObject::null()); }
    }
}
