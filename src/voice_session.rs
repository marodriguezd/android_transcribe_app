use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::engine;

// --- Optional auto-stop endpointing (same level heuristics as recog_service) --
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.05;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.04;
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread.
struct Endpointing {
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    speech_started: AtomicBool,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Command channel to the streaming pump (created per recording).
    pub stream_cmd_tx: Mutex<Option<crossbeam_channel::Sender<crate::engine::StreamCmd>>>,
    /// True while the streaming pump is actively streaming (so stop/cancel
    /// route to the pump instead of the whole-buffer path).
    pub streaming_active: Arc<AtomicBool>,
    /// Serializes the stop-vs-pump-start decision so exactly one path
    /// (streaming pump or whole-buffer) delivers the transcription.
    pub stream_lock: Arc<Mutex<()>>,
}

use crate::jni_util::{notify_level, notify_partial, notify_status, notify_text};

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        stream_cmd_tx: Mutex::new(None),
        streaming_active: Arc::new(AtomicBool::new(false)),
        stream_lock: Arc::new(Mutex::new(())),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture. With `auto_stop` set, a monitor thread watches
/// for trailing silence after speech (or a no-speech timeout) and invokes the
/// Java-side `onAutoStop()` callback, which is expected to stop the recording
/// the same way a manual tap would.
pub fn start_recording(mut env: JNIEnv, state: &mut VoiceSessionState, auto_stop: bool) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
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

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            last_voice: Mutex::new(Instant::now()),
            noise_floor: Mutex::new(0.0),
            speech_started: AtomicBool::new(false),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                let floor = *ep.noise_floor.lock().unwrap();
                let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;
                if is_speech {
                    *ep.last_voice.lock().unwrap() = Instant::now();
                    ep.speech_started.store(true, Ordering::SeqCst);
                } else {
                    // Slowly adapt the noise floor while no speech is present.
                    let mut nf = ep.noise_floor.lock().unwrap();
                    *nf = *nf * 0.95 + level * 0.05;
                }
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            // Streaming pump (Nemotron-family models): drains the audio
            // buffer into a cache-aware transcribe.cpp stream and reports
            // partial hypotheses live. For models without native streaming
            // the pump exits without touching anything and stop_recording
            // transcribes the whole buffer as before.
            let (cmd_tx, cmd_rx) = crossbeam_channel::unbounded::<crate::engine::StreamCmd>();
            *state.stream_cmd_tx.lock().unwrap() = Some(cmd_tx);
            state.streaming_active.store(false, Ordering::SeqCst);
            let jvm = state.jvm.clone();
            let target_ref = state.target_ref.clone();
            let buffer = state.audio_buffer.clone();
            let session_active = state.session_active.clone();
            let streaming_active = state.streaming_active.clone();
            let stream_lock = state.stream_lock.clone();
            std::thread::spawn(move || {
                streaming_pump(
                    jvm,
                    target_ref,
                    buffer,
                    cmd_tx,
                    cmd_rx,
                    session_active,
                    streaming_active,
                    stream_lock,
                )
            });

            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let speech = ep.speech_started.load(Ordering::SeqCst);
                    let silence = ep.last_voice.lock().unwrap().elapsed();
                    let done = (speech && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ =
                                    env.call_method(target_ref.as_obj(), "onAutoStop", "()V", &[]);
                            }
                        }
                        return;
                    }
                });
            }
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

/// Runs the cache-aware streaming transcription for one recording. Waits for
/// the engine, then streams the captured audio through the shared engine,
/// reporting partial hypotheses live. The final text is delivered with the
/// same callbacks as the whole-buffer path (`onStatusUpdate("Ready")` +
/// `onTextTranscribed`), so the Java surfaces are unchanged. When the model
/// has no native streaming (or the stream cannot start) the pump exits
/// without delivering anything and `stop_recording` falls back to the
/// whole-buffer path.
#[allow(clippy::too_many_arguments)]
fn streaming_pump(
    jvm: Arc<jni::JavaVM>,
    target_ref: GlobalRef,
    buffer: Arc<Mutex<Vec<f32>>>,
    tx: crossbeam_channel::Sender<crate::engine::StreamCmd>,
    rx: crossbeam_channel::Receiver<crate::engine::StreamCmd>,
    session_active: Arc<AtomicBool>,
    streaming_active: Arc<AtomicBool>,
    stream_lock: Arc<Mutex<()>>,
) {
    // Wait for the engine (a recording can start while it is still loading).
    let engine_arc = loop {
        if let Some(e) = engine::get_engine() {
            break e;
        }
        if !session_active.load(Ordering::SeqCst) {
            return; // session ended before the model was ready
        }
        std::thread::sleep(Duration::from_millis(100));
    };

    // Serialize the start decision with stop/cancel so exactly one path
    // delivers the transcription (see stop_recording).
    {
        let _guard = stream_lock.lock().unwrap_or_else(|p| p.into_inner());
        if !session_active.load(Ordering::SeqCst) {
            return; // stop/cancel already ran the whole-buffer path
        }
        let is_streaming = engine_arc
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .supports_streaming();
        if !is_streaming {
            return; // whole-buffer path handles transcription
        }
        streaming_active.store(true, Ordering::SeqCst);
    }

    let mut env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let obj = target_ref.as_obj();

    // Everything drained stays here so a failed/mid-stream fallback can
    // re-transcribe the whole recording offline (no text lost).
    let mut local: Vec<f32> = Vec::new();

    let result = {
        let buffer_ref = &buffer;
        let mut drain = || {
            let chunk: Vec<f32> = {
                let mut b = buffer_ref.lock().unwrap_or_else(|p| p.into_inner());
                std::mem::take(&mut *b)
            };
            local.extend_from_slice(&chunk);
            chunk
        };
        let mut partial = |text: &str| notify_partial(&mut env, &obj, text);
        engine::transcribe_stream_shared(&engine_arc, &rx, &mut drain, &mut partial)
    };
    streaming_active.store(false, Ordering::SeqCst);

    match result {
        Ok(text) if !local.is_empty() => {
            notify_status(&mut env, obj, "Ready");
            notify_text(&mut env, obj, &text);
        }
        // Nothing was ever captured (instant stop).
        Ok(_) => notify_status(
            &mut env,
            obj,
            "Error: no audio recorded. Check microphone permissions.",
        ),
        Err(msg) if msg == "Canceled" => {}
        Err(msg) => {
            // The stream could not run (e.g. begin failed): transcribe the
            // whole buffer offline so no text is lost.
            if local.is_empty() {
                notify_status(&mut env, obj, &format!("Error: {}", msg));
            } else {
                match engine::transcribe_shared(&engine_arc, local) {
                    Ok(t) => {
                        notify_status(&mut env, obj, "Ready");
                        notify_text(&mut env, obj, &t);
                    }
                    Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
                }
            }
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording; end the auto-stop monitor if running.
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;

    // If the streaming pump is (or is about to be) in charge, signal end of
    // input and let it finalize + deliver the text. The stream_lock makes
    // this decision atomic with the pump's start, so exactly one path
    // delivers.
    {
        let _guard = state.stream_lock.lock().unwrap_or_else(|p| p.into_inner());
        if state.streaming_active.load(Ordering::SeqCst) {
            if let Some(tx) = state.stream_cmd_tx.lock().unwrap().clone() {
                let _ = tx.send(crate::engine::StreamCmd::Stop);
            }
            return;
        }
    }

    let buffer = state.audio_buffer.lock().unwrap().clone();

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    notify_status(&mut env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Wait for engine if somehow still loading
        if engine::get_engine().is_none() {
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                return;
            }
        }

        if let Some(eng_arc) = engine::get_engine() {
            let res = engine::transcribe_shared(&eng_arc, buffer);

            match res {
                Ok(text) => {
                    notify_status(&mut env, obj, "Ready");
                    notify_text(&mut env, obj, &text);
                }
                Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
            }
        } else {
            notify_status(&mut env, obj, "Error: model not loaded");
        }
    });
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.audio_buffer.lock().unwrap().clear();
    // Abort the streaming pump (if any); it delivers nothing on cancel.
    if let Some(tx) = state.stream_cmd_tx.lock().unwrap().clone() {
        let _ = tx.send(crate::engine::StreamCmd::Cancel);
    }
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
