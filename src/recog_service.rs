//! Native backend for `VoiceRecognitionService`, the `android.speech.RecognitionService`
//! implementation that lets *other* keyboards/apps (SwiftKey, Gboard, …) use this app
//! as their offline speech-to-text provider via the system `SpeechRecognizer` API.
//!
//! Unlike the IME / `RecognizeActivity` surfaces (which have their own UI and a manual
//! "tap to stop" control via `voice_session`), a `RecognitionService` has no UI of its
//! own: the calling keyboard expects *us* to decide when the user has finished speaking.
//! So this module adds trailing-silence endpointing on top of the same `engine` model,
//! and finalises automatically (it also honours an explicit `stopListening`/`cancel`).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use crate::engine;
use crate::voice_session::SendStream;

// --- Endpointing / VAD tuning -------------------------------------------------
// These are deliberately simple heuristics on the smoothed mic level. Mic gain
// varies a lot between devices, so they may need tuning; finalisation always
// transcribes whatever was captured, so a mis-tuned threshold only affects the
// auto-stop *timing*, never whether text is returned.
//
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after speech that triggers auto-finalisation.
const SILENCE_MS: u64 = 1500;
/// If no speech is ever detected, finalise after this long anyway.
const NO_SPEECH_TIMEOUT_MS: u64 = 7000;
/// Hard cap on a single utterance (the engine internally chunks long audio).
const MAX_SESSION_MS: u64 = 60000;
/// Throttle interval for `rmsChanged` UI callbacks.
const LEVEL_UPDATE_MS: u64 = 50;

// Mirror of android.speech.SpeechRecognizer error codes we report.
const ERROR_AUDIO: i32 = 3;
const ERROR_SERVER: i32 = 4;
const ERROR_NO_MATCH: i32 = 7;

/// State shared between the audio callback, the endpoint-monitor thread and the
/// finaliser. Deliberately does NOT hold the cpal stream, to avoid an Arc cycle
/// (the stream's callback holds an `Arc<Endpoint>`).
struct Endpoint {
    audio_buffer: Mutex<Vec<f32>>,
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    last_level_sent: Mutex<Instant>,
    speech_started: AtomicBool,
    finalized: AtomicBool,
    cancelled: AtomicBool,
    started_at: Instant,
    jvm: Arc<jni::JavaVM>,
    target: GlobalRef,
}

struct Session {
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
}

static SESSION: Lazy<Mutex<Option<Session>>> = Lazy::new(|| Mutex::new(None));

fn session_lock() -> std::sync::MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|poisoned| {
        log::error!("SESSION mutex poisoned, recovering");
        poisoned.into_inner()
    })
}

// --- JNI callbacks into VoiceRecognitionService -------------------------------

fn call_void(env: &mut JNIEnv, obj: &JObject, method: &str) {
    if let Err(err) = env.call_method(obj, method, "()V", &[]) {
        log::error!("call_void({}) error: {}", method, err);
        let _ = env.exception_clear();
    }
}

fn call_rms(env: &mut JNIEnv, obj: &JObject, rms_db: f32) {
    if let Err(err) = env.call_method(obj, "onRmsChanged", "(F)V", &[rms_db.into()]) {
        log::error!("call_rms error: {}", err);
        let _ = env.exception_clear();
    }
}

fn call_error(env: &mut JNIEnv, obj: &JObject, code: i32) {
    if let Err(err) = env.call_method(obj, "onError", "(I)V", &[code.into()]) {
        log::error!("call_error({}) error: {}", code, err);
        let _ = env.exception_clear();
    }
}

fn call_results(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        if let Err(err) = env.call_method(
            obj,
            "onResults",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        ) {
            log::error!("call_results error: {}", err);
            let _ = env.exception_clear();
        }
    }
}

// --- JNI entry points ---------------------------------------------------------

/// Called from `onCreate`. Warms up the model in the background so the first
/// recognition after a cold bind is as fast as possible.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_initNative(
    mut env: JNIEnv,
    _class: JObject,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let (jvm, target_ref) = match env.with_local_frame(16, |env| {
        let vm = env.get_java_vm()?;
        let target_ref = env.new_global_ref(&service)?;
        Ok::<_, jni::errors::Error>((Arc::new(vm), target_ref))
    }) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
    });
}

/// Called from `onStartListening`. Begins microphone capture and arms the
/// silence-based endpoint monitor.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListening(
    mut env: JNIEnv,
    _class: JObject,
    service: JObject,
) {
    let (jvm, target) = match env.with_local_frame(16, |env| {
        let vm = env.get_java_vm()?;
        let target = env.new_global_ref(&service)?;
        Ok::<_, jni::errors::Error>((Arc::new(vm), target))
    }) {
        Ok(r) => r,
        Err(_) => return,
    };

    // Tear down any session that is still around (e.g. the keyboard called
    // startListening twice without cancel) so its monitor/finaliser can never
    // deliver stale results to this new session.
    {
        let mut guard = session_lock();
        if let Some(old) = guard.take() {
            old.shared.cancelled.store(true, Ordering::SeqCst);
            old.shared.finalized.store(true, Ordering::SeqCst);
            *old.stream.lock().unwrap_or_else(|poisoned| {
                log::error!("stream mutex poisoned, recovering");
                poisoned.into_inner()
            }) = None;
        }
    }

    let now = Instant::now();
    let shared = Arc::new(Endpoint {
        audio_buffer: Mutex::new(Vec::new()),
        last_voice: Mutex::new(now),
        noise_floor: Mutex::new(0.0),
        last_level_sent: Mutex::new(now),
        speech_started: AtomicBool::new(false),
        finalized: AtomicBool::new(false),
        cancelled: AtomicBool::new(false),
        started_at: now,
        jvm: jvm.clone(),
        target,
    });
    let stream_holder: Arc<Mutex<Option<SendStream>>> = Arc::new(Mutex::new(None));

    // Tell the keyboard we're ready to receive speech.
    {
        let mut env2 = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        call_void(&mut env2, shared.target.as_obj(), "onReadyForSpeech");
    }

    // Open the microphone (16 kHz mono, matching the model + voice_session).
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            let mut env2 = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => return,
            };
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            return;
        }
    };
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    let cb_shared = shared.clone();
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| audio_callback(&cb_shared, data),
        |e| log::error!("RecognitionService stream error: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            *stream_holder.lock().unwrap_or_else(|poisoned| {
                log::error!("stream_holder mutex poisoned, recovering");
                poisoned.into_inner()
            }) = Some(SendStream(s));
        }
        Err(e) => {
            log::error!("Failed to open microphone: {}", e);
            let mut env2 = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => return,
            };
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            return;
        }
    }

    // Endpoint monitor.
    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));

    *session_lock() = Some(Session {
        shared,
        stream: stream_holder,
    });
}

/// Called from `onStopListening`: the keyboard asked us to finish now. Finalise
/// with whatever we've captured so far.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_stopListening(
    _env: JNIEnv,
    _class: JObject,
) {
    let session = session_lock().as_ref().map(|s| (s.shared.clone(), s.stream.clone()));
    if let Some((shared, stream)) = session {
        std::thread::spawn(move || finalize(shared, stream));
    }
}

/// Called from `onCancel`: discard everything, return nothing.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(
    _env: JNIEnv,
    _class: JObject,
) {
    let mut guard = session_lock();
    if let Some(session) = guard.as_ref() {
        session.shared.cancelled.store(true, Ordering::SeqCst);
        session.shared.finalized.store(true, Ordering::SeqCst);
        *session.stream.lock().unwrap_or_else(|poisoned| {
            log::error!("session stream mutex poisoned, recovering");
            poisoned.into_inner()
        }) = None;
    }
    *guard = None;
}

/// Called from `onDestroy`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_destroyNative(
    env: JNIEnv,
    class: JObject,
) {
    Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(env, class);
}

// --- Audio + endpointing ------------------------------------------------------

fn audio_callback(shared: &Arc<Endpoint>, data: &[f32]) {
    if shared.finalized.load(Ordering::SeqCst) {
        return;
    }

    shared.audio_buffer.lock().unwrap_or_else(|poisoned| {
        log::error!("audio_buffer mutex poisoned, recovering");
        poisoned.into_inner()
    }).extend_from_slice(data);

    // RMS -> smoothed level in 0..1 (same scaling as voice_session).
    let mut sum = 0.0f32;
    for &x in data {
        sum += x * x;
    }
    let rms = (sum / (data.len().max(1) as f32)).sqrt();
    let level = (rms * 6.0).clamp(0.0, 1.0);

    let floor = *shared.noise_floor.lock().unwrap_or_else(|poisoned| {
        log::error!("noise_floor mutex poisoned, recovering");
        poisoned.into_inner()
    });
    let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;

    if is_speech {
        *shared.last_voice.lock().unwrap_or_else(|poisoned| {
            log::error!("last_voice mutex poisoned, recovering");
            poisoned.into_inner()
        }) = Instant::now();
        // First detected speech -> notify beginningOfSpeech exactly once.
        if shared
            .speech_started
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            if let Ok(mut env) = shared.jvm.attach_current_thread() {
                call_void(&mut env, shared.target.as_obj(), "onBeginningOfSpeech");
            }
        }
    } else {
        // Slowly adapt the noise floor while no speech is present.
        let mut nf = shared.noise_floor.lock().unwrap_or_else(|poisoned| {
            log::error!("noise_floor mutex poisoned, recovering");
            poisoned.into_inner()
        });
        *nf = *nf * 0.95 + level * 0.05;
    }

    // Throttled mic-level updates for the keyboard's waveform UI.
    let mut last = shared.last_level_sent.lock().unwrap_or_else(|poisoned| {
        log::error!("last_level_sent mutex poisoned, recovering");
        poisoned.into_inner()
    });
    if last.elapsed() >= Duration::from_millis(LEVEL_UPDATE_MS) {
        *last = Instant::now();
        drop(last);
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            call_rms(&mut env, shared.target.as_obj(), level * 10.0);
        }
    }
}

fn endpoint_monitor(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    loop {
        std::thread::sleep(Duration::from_millis(100));

        if shared.cancelled.load(Ordering::SeqCst) || shared.finalized.load(Ordering::SeqCst) {
            return;
        }

        let elapsed = shared.started_at.elapsed();
        let speech = shared.speech_started.load(Ordering::SeqCst);
        let silence = shared.last_voice.lock().unwrap_or_else(|poisoned| {
            log::error!("last_voice mutex poisoned, recovering");
            poisoned.into_inner()
        }).elapsed();

        let done = (speech && silence >= Duration::from_millis(SILENCE_MS))
            || elapsed >= Duration::from_millis(MAX_SESSION_MS)
            || (!speech && elapsed >= Duration::from_millis(NO_SPEECH_TIMEOUT_MS));

        if done {
            finalize(shared, stream);
            return;
        }
    }
}

/// Stop capture, run the model on the buffered audio and deliver results/error.
/// Idempotent: only the first caller (monitor or explicit stop) does the work.
fn finalize(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return; // already finalised/cancelled
    }

    // Stop the microphone (also drops the audio callback's Arc<Endpoint>).
    *stream.lock().unwrap_or_else(|poisoned| {
        log::error!("stream mutex poisoned, recovering");
        poisoned.into_inner()
    }) = None;

    let buffer = shared.audio_buffer.lock().unwrap_or_else(|poisoned| {
        log::error!("audio_buffer mutex poisoned, recovering");
        poisoned.into_inner()
    }).clone();
    let speech = shared.speech_started.load(Ordering::SeqCst);

    let mut env = match shared.jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    let target = shared.target.as_obj();

    if speech {
        call_void(&mut env, target, "onEndOfSpeech");
    }

    // ~0.2s minimum of audio to bother transcribing.
    if buffer.len() < 3200 {
        call_error(&mut env, target, ERROR_NO_MATCH);
        clear_session(&shared);
        return;
    }

    let (_variant, eng_arc) = match engine::ensure_loaded(&mut env, target) {
        Ok(Some(engine)) => engine,
        _ => {
            call_error(&mut env, target, ERROR_SERVER);
            clear_session(&shared);
            return;
        }
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
        Ok(r) if !r.text.trim().is_empty() => call_results(&mut env, target, &r.text),
        Ok(_) => call_error(&mut env, target, ERROR_NO_MATCH),
        Err(e) => {
            log::error!("Transcription failed: {}", e);
            call_error(&mut env, target, ERROR_SERVER);
        }
    }

    clear_session(&shared);
}

/// Clear the global session, but only if it is still *this* session — a newer
/// `startListening` may already have installed a fresh one.
fn clear_session(shared: &Arc<Endpoint>) {
    let mut guard = session_lock();
    if let Some(s) = guard.as_ref() {
        if Arc::ptr_eq(&s.shared, shared) {
            *guard = None;
        }
    }
}
