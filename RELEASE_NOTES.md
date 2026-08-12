# v0.1.30 — integrated AI Fix toggle in IME keyboard (2026-08-12)

`versionCode 32` — integrated AI post-processing toggle in the voice keyboard (IME) and status header refinement.

- **AI Fix Toggle in IME:** A dedicated toggle switch ("AI Fix") is now integrated directly into the central voice keyboard container. Enable or disable LLM post-processing on the fly at any point before, during, or after speech recognition.
- **On-the-Fly Post-Processing Decisions:** If the toggle is OFF when dictation completes, the raw transcription is inserted immediately. If toggled OFF while LLM post-processing is in-flight ("Refining..."), the HTTP request is cancelled instantly and the raw transcript is delivered without delay.
- **Header Status Layout:** Top-left clearly displays status ("Escuchando..." / "Listening...") with the Cancel button anchored on the top-right.

The complete version history remains in [`CHANGELOG.md`](CHANGELOG.md).
