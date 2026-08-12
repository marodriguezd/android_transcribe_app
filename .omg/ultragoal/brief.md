# Ultragoal Brief: Floating Bubble Drag-to-Dismiss & Long-Press Behavior Adjustment

## Objective
1. Remove automatic long-press dismissal (`longPressStop` / `fadeOutAndStop()`) from `FloatingOverlayService.java`. Long-pressing or holding the bubble allows moving it freely without it disappearing.
2. Maintain inactivity semi-transparency behavior when unused.
3. Introduce a drag-to-dismiss "X" target zone at the bottom of the screen during drag gestures. Releasing the bubble over the "X" target completely dismisses the overlay (`fadeOutAndStop()`).

## Constraints
- Version remains `0.1.31` (`versionCode 33`).
- Maintain i18n translation parity (`python3 scripts/check_translations.py`).
- All JVM unit tests must pass (`./gradlew testDebugUnitTest`).
- Environment validation rule: do NOT attempt heavy local native builds on mobile/ARM user-space host; rely on static checks + CI push validation if needed.
