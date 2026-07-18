use std::sync::{Arc, Mutex};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;
use zeroize::Zeroize;
use crate::engine;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    pub current_level: Arc<std::sync::atomic::AtomicU32>,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        if let Err(err) = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        ) {
            log::error!("notify_status call_method error: {}", err);
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
            log::error!("notify_text call_method error: {}", err);
            let _ = env.exception_clear();
        }
    }
}

pub fn init_session(env: &mut JNIEnv, target: JObject) -> Option<VoiceSessionState> {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            log::error!("Failed to get JavaVM: {}", e);
            return None;
        }
    };
    let vm_arc = Arc::new(vm);
    let target_ref = match env.new_global_ref(&target) {
        Ok(r) => r,
        Err(e) => {
            log::error!("Failed to ref target: {}", e);
            return None;
        }
    };

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        current_level: Arc::new(std::sync::atomic::AtomicU32::new(0)),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    Some(state)
}

pub fn start_recording(env: &mut JNIEnv, state: &mut VoiceSessionState) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap_or_else(|poisoned| {
        log::error!("audio_buffer mutex poisoned, recovering");
        poisoned.into_inner()
    }).clear();
    let buffer_clone = state.audio_buffer.clone();
    let level_clone = state.current_level.clone();

    let buffer_clone_f32 = buffer_clone.clone();
    let level_clone_f32 = level_clone.clone();
    let stream_result = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone_f32.lock().unwrap_or_else(|poisoned| {
                log::error!("audio buffer (f32) mutex poisoned, recovering");
                poisoned.into_inner()
            }).extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len() as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);
            level_clone_f32.store(level.to_bits(), std::sync::atomic::Ordering::Relaxed);
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    let stream = match stream_result {
        Ok(s) => Ok(s),
        Err(e) => {
            log::warn!("F32 stream building failed, attempting I16 fallback. Error: {}", e);
            let buffer_clone_i16 = buffer_clone.clone();
            let level_clone_i16 = level_clone.clone();
            device.build_input_stream(
                &config,
                move |data: &[i16], _: &_| {
                    let f32_data: Vec<f32> = data.iter().map(|&x| x as f32 / 32768.0).collect();
                    buffer_clone_i16.lock().unwrap_or_else(|poisoned| {
                        log::error!("audio buffer (i16) mutex poisoned, recovering");
                        poisoned.into_inner()
                    }).extend_from_slice(&f32_data);

                    let mut sum = 0.0f32;
                    for &x in &f32_data {
                        sum += x * x;
                    }
                    let rms = (sum / (f32_data.len() as f32)).sqrt();
                    let level = (rms * 6.0).clamp(0.0, 1.0);
                    level_clone_i16.store(level.to_bits(), std::sync::atomic::Ordering::Relaxed);
                },
                |e| log::error!("Stream err: {}", e),
                None,
            )
        }
    };

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(env, state.target_ref.as_obj(), "Listening...");
        }
        Err(e) => {
            notify_status(
                env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

pub fn stop_recording(env: &mut JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording
    state.stream = None;

    let buffer = state.audio_buffer.lock().unwrap_or_else(|poisoned| {
        log::error!("audio_buffer mutex poisoned, recovering");
        poisoned.into_inner()
    }).clone();

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    notify_status(env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Wait for engine if somehow still loading
        let (_variant, eng_arc) = match engine::ensure_loaded(&mut env, obj) {
            Ok(Some(engine)) => engine,
            Ok(None) => {
                notify_status(&mut env, obj, "Error: model not loaded");
                return;
            }
            Err(_) => return,
        };

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
                notify_status(&mut env, obj, "Ready");
                notify_text(&mut env, obj, &r.text);
                Zeroize::zeroize(&mut r.text);
            }
            Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
        }
    });
}

pub fn cancel_recording(env: &mut JNIEnv, state: &mut VoiceSessionState) {
    state.stream = None;
    state.audio_buffer.lock().unwrap_or_else(|poisoned| {
        log::error!("audio_buffer mutex poisoned, recovering");
        poisoned.into_inner()
    }).clear();
    notify_status(env, state.target_ref.as_obj(), "Canceled");
}
