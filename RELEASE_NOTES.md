# v0.1.31 — Whisperflow floating bubble dictation & Accessibility auto-paste (2026-08-12)

`versionCode 33` — floating bubble dictation overlay, Android Accessibility auto-paste, and a fully crash-proofed floating + accessibility stack.

- **Floating Bubble Dictation (Whisperflow style):** Draggable floating bubble icon (`WindowManager` `TYPE_APPLICATION_OVERLAY`) that expands into a **full-width, IME-style dictation panel** with live streaming hypotheses, status, Cancel, AI Fix toggle and Insert action. The bubble is a fixed-size, perfectly centered element that returns to exactly where you left it.
- **Accessibility Auto-Paste:** `FloatingDictationAccessibilityService` tracks the focused input field across apps and inserts transcribed text directly (`ACTION_PASTE` / `ACTION_SET_TEXT`). When direct insertion isn't possible, the text is **copied to the clipboard** with a toast, so no transcript is ever lost (e.g. Termux, terminals, apps with custom views).
- **Crash-proof overlay & accessibility:** startup is gated on microphone + overlay permissions, and every fallible setup step degrades to a graceful stop with a guidance notification instead of a restart crash-loop. The accessibility service can no longer be killed by a single bad window event, and native session locks recover from poison instead of panicking.
- **Stop without opening the app:** the overlay's notification carries a **Stop** action, and **long-pressing the bubble** stops the overlay with a fade-out animation (toast on Android < 12).
- **Listening pulse:** the mic circles breathe while a session is recording, so the bubble always reads as "recording" even in silence, alongside the live level glow.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
