# Ultragoal Brief: Floating Mode UI Adjustments & App Settings Integration

## Objective
1. Relocate `ime_pp_toggle` in `ime_layout.xml` to the top status bar, centered between `ime_status_text` (left) and `ime_cancel` (right).
2. Add a dedicated Floating Bubble Dictation & Accessibility card in `activity_main.xml` & `MainActivity.java` allowing users to toggle floating mode and grant Overlay (`SYSTEM_ALERT_WINDOW`) & Accessibility permissions.

## Constraints
- Version remains `0.1.31` (`versionCode 33`).
- Maintain i18n translation parity across all 7 locales (`python3 scripts/check_translations.py`).
- All JVM unit tests must pass (`./gradlew testDebugUnitTest`).
