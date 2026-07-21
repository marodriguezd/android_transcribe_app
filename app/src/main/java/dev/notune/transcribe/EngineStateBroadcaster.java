package dev.notune.transcribe;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-component engine-state broadcaster. The Rust engine only fires
 * {@code onStatusUpdate} on the {@code activity} JObject passed to the JNI
 * entry, so model-load status updates fired from {@code MainActivity} never
 * reach {@link RustInputMethodService} without an additional channel.
 *
 * <p>This singleton is that channel. Both {@link MainActivity#onStatusUpdate}
 * and {@link RustInputMethodService#onEngineStateChanged} subscribe to it; the
 * IME listener fires when the MainActivity-driven switchModel emits
 * "Loading…", "Initializing…", "Switching model…", etc. (status strings
 * emitted by {@code src/engine.rs :: notify_status}).
 *
 * <p>Listeners are stored in a {@link CopyOnWriteArrayList} (per AGENTS.md's
 * established pattern) and dispatched via a {@link Handler} on the main
 * looper so subscribers receive callbacks on the same thread they registered
 * on. Subscribe BEFORE the very first {@code setState(...)}, or query
 * {@link #getCurrentState()} on subscribe to bootstrap. Remove on destroy to
 * avoid leaks.
 *
 * <p>Pure utility class — no public constructor.
 */
public final class EngineStateBroadcaster {
    private static final String TAG = "EngineStateBroadcaster";

    private static volatile String currentState = "Initializing...";
    private static final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface StateListener {
        void onStateChanged(String state);
    }

    private EngineStateBroadcaster() { /* utility */ }

    public static String getCurrentState() {
        return currentState;
    }

    public static void addListener(StateListener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(StateListener l) {
        if (l != null) listeners.remove(l);
    }

    /**
     * Publish a new engine state. Marshals each registered listener onto the
     * main thread (the listener callback may itself toggle UI views so the
     * thread check guards against {@code CalledFromWrongThreadException}).
     * Idempotent for null / empty input; null is stored as "".
     */
    public static void setState(String state) {
        currentState = state == null ? "" : state;
        mainHandler.post(() -> {
            for (StateListener l : listeners) {
                try {
                    l.onStateChanged(currentState);
                } catch (Throwable t) {
                    Log.e(TAG, "listener error", t);
                }
            }
        });
    }

    /** Strip the {@code "Status: "} prefix that {@link MainActivity#onStatusUpdate}
     *  prepends when relaying the raw Rust status to the IME display. Idempotent. */
    public static String stripStatusPrefix(String s) {
        if (s == null) return "";
        final String prefix = "Status: ";
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /** True if the engine is in any non-ready transitional state — loading,
     *  initializing, switching, restarting, waiting, reading (vocabulary),
     *  or decoding audio. Matches every Rust status string emitted by
     *  {@code src/engine.rs :: notify_status} while the engine is warming
     *  up (including "Reading vocabulary…", which lacks "loading" but is
     *  still a transitional emit that should show the IME progress bar),
     *  plus an immediate pre-JNI "Switching model…" event published by
     *  {@link MainActivity#switchModelAsync(String)} so the IME shows
     *  feedback instantaneously rather than waiting for Rust's first
     *  "Loading..." call. */
    public static boolean isLoading(String state) {
        if (state == null) return false;
        String s = state.toLowerCase(java.util.Locale.ROOT);
        return s.contains("loading") || s.contains("initializing")
                || s.contains("switching") || s.contains("restarting")
                || s.contains("waiting") || s.contains("reading")
                || s.contains("decoding") || s.contains("extracting");
    }

    /** True if the engine is actively producing output (transcribing,
     *  processing audio, or refining text via the post-processor). */
    public static boolean isTranscribing(String state) {
        if (state == null) return false;
        String s = state.toLowerCase(java.util.Locale.ROOT);
        return s.contains("transcribing") || s.contains("processing")
                || s.contains("refining");
    }

    /** True if the engine surfaced an error condition. Catches the
     *  "Error: …" prefix and the common failure substrings Rust emits
     *  (Model not downloaded / Failed / Cannot / etc.). */
    public static boolean isError(String state) {
        if (state == null) return false;
        String s = state.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("error") || s.contains("failed")
                || s.contains("cannot") || s.contains("not downloaded");
    }

    /** True if the engine has completed loading and is ready for input. */
    public static boolean isReady(String state) {
        if (state == null) return false;
        return "ready".equals(state.trim().toLowerCase(java.util.Locale.ROOT))
                || "ready".equals(stripStatusPrefix(state).trim().toLowerCase(java.util.Locale.ROOT));
    }
}
