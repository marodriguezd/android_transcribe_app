use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
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
/// Trailing silence after confirmed speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// Require a short continuous speech run before arming trailing-silence
/// endpointing. This filters microphone pops/initial noise without dropping
/// samples: the complete audio buffer is still retained for transcription.
const MIN_SPEECH_SAMPLES: usize = 1_600; // 100 ms at 16 kHz
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;
/// Hard cap on one recording (~5 min): bounds the audio buffer (~19 MB at
/// 16 kHz f32) even when auto-stop is off, so a forgotten recording can never
/// grow until OOM. On expiry the Java surface receives `onAutoStop()` and
/// commits whatever was captured, so no text is lost.
const MAX_SESSION_MS: u64 = 300_000;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread. Fully lock-free atomic structures to avoid
/// lock contention and priority inversion on the real-time CPAL thread.
struct Endpointing {
    started_at: Instant,
    last_voice_ms: AtomicU64,
    noise_floor_bits: AtomicU32,
    speech_started: AtomicBool,
    speech_run_samples: AtomicUsize,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    /// Generation supplied by the Java surface for the current recording.
    pub session_id: i32,
    pub last_level_sent_ms: Arc<AtomicU64>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Per-recording invalidation flag. A normal stop leaves this false so the
    /// pump may deliver its final result; replacement/cancel sets it before
    /// waking the pump, preventing stale callbacks.
    pub session_cancelled: Arc<AtomicBool>,
    /// Command channel to the streaming pump (created per recording).
    pub stream_cmd_tx: Mutex<Option<crossbeam_channel::Sender<crate::engine::StreamCmd>>>,
    /// True while the streaming pump is actively streaming (so stop/cancel
    /// route to the pump instead of the whole-buffer path).
    pub streaming_active: Arc<AtomicBool>,
    /// Serializes the stop-vs-pump-start decision so exactly one path
    /// (streaming pump or whole-buffer) delivers the transcription.
    pub stream_lock: Arc<Mutex<()>>,
}

use crate::jni_util::{
    notify_level_with_session, notify_partial_with_session, notify_status_with_session,
    notify_text_with_session,
};

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
        session_id: 0,
        last_level_sent_ms: Arc::new(AtomicU64::new(0)),
        session_active: Arc::new(AtomicBool::new(false)),
        session_cancelled: Arc::new(AtomicBool::new(true)),
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
pub fn start_recording(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
    session_id: i32,
) {
    state.session_id = session_id;
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status_with_session(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
                state.session_id,
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    // End any previous recording before replacing its per-recording state.
    // The cancellation command is essential: session_active alone cannot wake
    // a pump already blocked inside the native streaming loop.
    {
        let _guard = state.stream_lock.lock().unwrap_or_else(|p| p.into_inner());
        state.session_active.store(false, Ordering::SeqCst);
        state.session_cancelled.store(true, Ordering::SeqCst);
        // Drop the old cpal stream before opening a replacement. Otherwise a
        // failed new device/configuration can leave the old callback alive.
        state.stream = None;
        if let Some(tx) = state.stream_cmd_tx.lock().unwrap().take() {
            let _ = tx.send(crate::engine::StreamCmd::Cancel);
        }
        // Keep old pumps isolated from the next recording. They retain the
        // previous Arcs and can finish draining/cancelling without consuming
        // new audio or changing the new session's streaming flag.
        state.audio_buffer = Arc::new(Mutex::new(Vec::new()));
        state.streaming_active = Arc::new(AtomicBool::new(false));
    }
    let buffer_clone = state.audio_buffer.clone();

    // End any previous session's monitor, then arm a fresh flag.
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();
    let session_cancelled = Arc::new(AtomicBool::new(false));
    state.session_cancelled = session_cancelled.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            started_at: Instant::now(),
            last_voice_ms: AtomicU64::new(0),
            noise_floor_bits: AtomicU32::new(0.0f32.to_bits()),
            speech_started: AtomicBool::new(false),
            speech_run_samples: AtomicUsize::new(0),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent_ms.clone();
    let endpoint_cb = endpoint.clone();
    let session_start = Instant::now();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .extend_from_slice(data);

            // Vectorized RMS via SIMD/NEON
            let rms = crate::audio::fast_rms(data);
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                let floor = f32::from_bits(ep.noise_floor_bits.load(Ordering::Relaxed));
                let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;
                if is_speech {
                    let elapsed_ms = ep.started_at.elapsed().as_millis() as u64;
                    if ep.speech_started.load(Ordering::SeqCst) {
                        // Once speech has been confirmed, every speech frame
                        // refreshes the trailing-silence clock (lock-free).
                        ep.last_voice_ms.store(elapsed_ms, Ordering::Relaxed);
                    } else {
                        // Do not arm trailing-silence endpointing for a single
                        // microphone pop or an initial noise spike. Require a
                        // continuous 100 ms speech run first.
                        let run = ep
                            .speech_run_samples
                            .fetch_add(data.len(), Ordering::SeqCst)
                            + data.len();
                        if run >= MIN_SPEECH_SAMPLES
                            && ep.speech_started.swap(true, Ordering::SeqCst) == false
                        {
                            ep.last_voice_ms.store(elapsed_ms, Ordering::Relaxed);
                        }
                    }
                } else {
                    if !ep.speech_started.load(Ordering::SeqCst) {
                        // The candidate speech run must be continuous.
                        ep.speech_run_samples.store(0, Ordering::SeqCst);
                    }
                    // Slowly adapt the noise floor while no speech is present (lock-free CAS).
                    let mut current_bits = ep.noise_floor_bits.load(Ordering::Relaxed);
                    loop {
                        let current_val = f32::from_bits(current_bits);
                        let new_val = current_val * 0.95 + level * 0.05;
                        match ep.noise_floor_bits.compare_exchange_weak(
                            current_bits,
                            new_val.to_bits(),
                            Ordering::Relaxed,
                            Ordering::Relaxed,
                        ) {
                            Ok(_) => break,
                            Err(b) => current_bits = b,
                        }
                    }
                }
            }

            // throttle updates (lock-free)
            let now_ms = session_start.elapsed().as_millis() as u64;
            let last = last_sent.load(Ordering::Relaxed);
            if now_ms >= last + 50 {
                last_sent.store(now_ms, Ordering::Relaxed);

                // Permanently attach the audio thread once instead of
                // attach/detach churn ~20 times per second (O1). The cpal
                // callback thread lives for the whole stream, so the daemon
                // attachment is reused by every subsequent block.
                if let Ok(mut env) = jvm.attach_current_thread_permanently() {
                    let obj = target_ref.as_obj();
                    notify_level_with_session(&mut env, obj, level, session_id);
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
            notify_status_with_session(
                &mut env,
                state.target_ref.as_obj(),
                "Listening...",
                state.session_id,
            );

            // Streaming pump (Nemotron-family models): drains the audio
            // buffer into a cache-aware transcribe.cpp stream and reports
            // partial hypotheses live. For models without native streaming
            // the pump exits without touching anything and stop_recording
            // transcribes the whole buffer as before.
            let (cmd_tx, cmd_rx) = crossbeam_channel::unbounded::<crate::engine::StreamCmd>();
            *state.stream_cmd_tx.lock().unwrap() = Some(cmd_tx.clone());
            state.streaming_active.store(false, Ordering::SeqCst);
            let jvm = state.jvm.clone();
            let target_ref = state.target_ref.clone();
            let buffer = state.audio_buffer.clone();
            let session_active = state.session_active.clone();
            let session_cancelled = state.session_cancelled.clone();
            let session_id = state.session_id;
            // The auto-stop monitor below also needs the flag; clone before
            // the pump closure moves the original.
            let session_active_monitor = session_active.clone();
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
                    session_cancelled,
                    session_id,
                    streaming_active,
                    stream_lock,
                )
            });

            // Always arm a session monitor (V3): with auto_stop the silence
            // heuristics run; without it only the hard session cap applies,
            // bounding the audio buffer (see MAX_SESSION_MS). Claiming via
            // session_active_monitor.swap keeps it racing-safe with a manual
            // stop, and the same onAutoStop path commits the captured audio.
            {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let ep_monitor = endpoint;
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active_monitor.load(Ordering::SeqCst) {
                        return;
                    }
                    let mut done = started_at.elapsed() >= Duration::from_millis(MAX_SESSION_MS);
                    if let Some(ep) = &ep_monitor {
                        let speech = ep.speech_started.load(Ordering::SeqCst);
                        let elapsed_ms = ep.started_at.elapsed().as_millis() as u64;
                        let last_voice_ms = ep.last_voice_ms.load(Ordering::Relaxed);
                        let silence_ms = elapsed_ms.saturating_sub(last_voice_ms);
                        done = done
                            || (speech && silence_ms >= AUTO_STOP_SILENCE_MS)
                            || (!speech && elapsed_ms >= AUTO_STOP_NO_SPEECH_MS);
                    }
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active_monitor.swap(false, Ordering::SeqCst) {
                            // Permanently attach the monitor thread (lives for
                            // the whole recording) instead of attach/detach
                            // churn every 100 ms, consistent with O1 on the
                            // audio callback thread.
                            if let Ok(mut env) = jvm.attach_current_thread_permanently() {
                                crate::jni_util::notify_auto_stop_with_session(
                                    &mut env,
                                    target_ref.as_obj(),
                                    session_id,
                                );
                            }
                        }
                        return;
                    }
                });
            }
        }
        Err(e) => {
            notify_status_with_session(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
                state.session_id,
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
    _tx: crossbeam_channel::Sender<crate::engine::StreamCmd>,
    rx: crossbeam_channel::Receiver<crate::engine::StreamCmd>,
    session_active: Arc<AtomicBool>,
    session_cancelled: Arc<AtomicBool>,
    session_id: i32,
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
    // Pre-allocate 30 seconds to prevent dynamic reallocation during streaming.
    let mut local: Vec<f32> = Vec::with_capacity(30 * 16_000);

    let result = {
        let buffer_ref = &buffer;
        let mut drain = |chunk: &mut Vec<f32>| {
            let mut b = buffer_ref.lock().unwrap_or_else(|p| p.into_inner());
            chunk.extend(b.drain(..));
            local.extend_from_slice(chunk);
        };
        let mut partial =
            |text: &str| notify_partial_with_session(&mut env, &obj, text, session_id);
        engine::transcribe_stream_shared(&engine_arc, &rx, &mut drain, &mut partial)
    };
    streaming_active.store(false, Ordering::SeqCst);

    // A normal stop sets session_active=false but still permits this pump to
    // deliver its final text. Replacement/cancel sets session_cancelled=true;
    // reject that stale result before every delivery path below.
    if session_cancelled.load(Ordering::SeqCst) {
        return;
    }

    match result {
        Ok(text) if !local.is_empty() => {
            if session_cancelled.load(Ordering::SeqCst) {
                return;
            }
            notify_status_with_session(&mut env, obj, "Ready", session_id);
            if session_cancelled.load(Ordering::SeqCst) {
                return;
            }
            notify_text_with_session(&mut env, obj, &text, session_id);
        }
        // Nothing was ever captured (instant stop).
        Ok(_) => {
            if !session_cancelled.load(Ordering::SeqCst) {
                notify_status_with_session(
                    &mut env,
                    obj,
                    "Error: no audio recorded. Check microphone permissions.",
                    session_id,
                );
            }
        }
        Err(msg) if msg == "Canceled" => {}
        Err(msg) => {
            // The stream could not run (e.g. begin failed): transcribe the
            // whole buffer offline so no text is lost.
            if session_cancelled.load(Ordering::SeqCst) {
                return;
            }
            if local.is_empty() {
                notify_status_with_session(&mut env, obj, &format!("Error: {}", msg), session_id);
            } else {
                match engine::transcribe_shared(&engine_arc, local) {
                    Ok(t) => {
                        if session_cancelled.load(Ordering::SeqCst) {
                            return;
                        }
                        notify_status_with_session(&mut env, obj, "Ready", session_id);
                        if session_cancelled.load(Ordering::SeqCst) {
                            return;
                        }
                        notify_text_with_session(&mut env, obj, &t, session_id);
                    }
                    Err(e) => {
                        if !session_cancelled.load(Ordering::SeqCst) {
                            notify_status_with_session(
                                &mut env,
                                obj,
                                &format!("Error: {}", e),
                                session_id,
                            );
                        }
                    }
                }
            }
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    let state_session_id = state.session_id;
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
            *state.stream_cmd_tx.lock().unwrap() = None;
            return;
        }
    }

    let buffer = state
        .audio_buffer
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone();
    *state.stream_cmd_tx.lock().unwrap() = None;

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status_with_session(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
            state.session_id,
        );
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let session_cancelled = state.session_cancelled.clone();

    notify_status_with_session(
        &mut env,
        target_ref.as_obj(),
        "Transcribing...",
        state.session_id,
    );

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

            if session_cancelled.load(Ordering::SeqCst) {
                return;
            }
            match res {
                Ok(text) => {
                    if session_cancelled.load(Ordering::SeqCst) {
                        return;
                    }
                    notify_status_with_session(&mut env, obj, "Ready", state_session_id);
                    if session_cancelled.load(Ordering::SeqCst) {
                        return;
                    }
                    notify_text_with_session(&mut env, obj, &text, state_session_id);
                }
                Err(e) => {
                    if !session_cancelled.load(Ordering::SeqCst) {
                        notify_status_with_session(
                            &mut env,
                            obj,
                            &format!("Error: {}", e),
                            state_session_id,
                        );
                    }
                }
            }
        } else if !session_cancelled.load(Ordering::SeqCst) {
            notify_status_with_session(&mut env, obj, "Error: model not loaded", state_session_id);
        }
    });
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.session_cancelled.store(true, Ordering::SeqCst);
    state.stream = None;
    state
        .audio_buffer
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clear();
    // Abort the streaming pump (if any); it delivers nothing on cancel.
    if let Some(tx) = state.stream_cmd_tx.lock().unwrap().clone() {
        let _ = tx.send(crate::engine::StreamCmd::Cancel);
    }
    // Clear the sender so a later recording cannot accidentally signal this
    // session's channel.
    *state.stream_cmd_tx.lock().unwrap() = None;
    notify_status_with_session(
        &mut env,
        state.target_ref.as_obj(),
        "Canceled",
        state.session_id,
    );
}
