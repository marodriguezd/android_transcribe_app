# v0.1.32 — Floating bubble remembers its spot & panel follows the bubble (2026-08-12)

`versionCode 34` — the floating bubble keeps its position across restarts, and the dictation panel now opens right where the bubble is.

- **Bubble position memory:** drag the bubble anywhere — its position is saved to a marker file and restored exactly there the next time the overlay starts (clamped to the current screen so a position saved in another orientation can never leave it off-screen).
- **Panel follows the bubble:** the expanded dictation panel no longer pins under the status bar — it opens vertically centered on the bubble instead, clamped so it always fits on screen (status-bar and nav-bar aware, using the panel's measured height).
- **Robust persistence:** the position is also saved when a drag is interrupted (touch stolen mid-drag) and on every stop path, so the bubble reliably returns to where you left it.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).

# v0.1.31 — Whisperflow floating bubble dictation & Accessibility auto-paste (2026-08-12)

`versionCode 33` — floating bubble dictation overlay, Android Accessibility auto-paste, and a fully crash-proofed floating + accessibility stack.

- **Floating Bubble Dictation (Whisperflow style):** Draggable floating bubble icon (`WindowManager` `TYPE_APPLICATION_OVERLAY`) that expands into a **full-width, IME-style dictation panel** with live streaming hypotheses, status, Cancel, AI Fix toggle and Insert action. The bubble is a fixed-size, perfectly centered element that returns to exactly where you left it.
- **Accessibility Auto-Paste:** `FloatingDictationAccessibilityService` tracks the focused input field across apps and inserts transcribed text directly (`ACTION_PASTE` / `ACTION_SET_TEXT`). When direct insertion isn't possible, the text is **copied to the clipboard** with a toast, so no transcript is ever lost (e.g. Termux, terminals, apps with custom views).
- **Crash-proof overlay & accessibility:** startup is gated on microphone + overlay permissions, and every fallible setup step degrades to a graceful stop with a guidance notification instead of a restart crash-loop. The accessibility service can no longer be killed by a single bad window event, and native session locks recover from poison instead of panicking.
- **Stop without opening the app:** the overlay's notification carries a **Stop** action, and **long-pressing the bubble** stops the overlay with a fade-out animation (toast on Android < 12).
- **Listening pulse:** the mic circles breathe while a session is recording, so the bubble always reads as "recording" even in silence, alongside the live level glow.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
