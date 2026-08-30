# 🚀 Fastlane Automation Suite for Aura Transcribe

This directory contains the automated deployment, quality assurance, metadata synchronization, and packaging pipeline for **Aura Transcribe** (`com.auratranscribe.app`).

---

## 📋 Available Lanes

| Lane | Command | Description |
| :--- | :--- | :--- |
| **QA / Quality Gates** | `fastlane qa` or `fastlane test` | Runs the full verification suite: 266-string i18n parity check, math/latency benchmarks, JVM unit tests, and model SHA-256 integrity gate. |
| **Debug Build** | `fastlane build_debug` | Builds the debug APK variant (`app/build/outputs/apk/debug/`). |
| **Release Build** | `fastlane build_release` | Compiles and signs the production Release APK and validates model assets. |
| **Bundle Build** | `fastlane build_bundle` | Compiles the Android App Bundle (`.aab`) with dynamic asset pack delivery. |
| **Validate Metadata** | `fastlane validate_metadata` | Validates store descriptions, changelogs for the active versionCode, icon, and screenshots. |
| **Upload Metadata** | `fastlane upload_metadata` | Synchronizes descriptions, screenshots, and changelogs to Google Play Store using `supply`. |
| **Internal Track** | `fastlane internal` | Deploys release binary (.aab / .apk) to Google Play Internal Test Track. |
| **Promote to Production** | `fastlane promote_internal_to_production rollout:1.0` | Promotes a tested build from internal track to production with specified rollout percentage. |
| **Direct Production** | `fastlane publish_production rollout:1.0` | Directly deploys release bundle to Google Play Production track. |
| **F-Droid Export** | `fastlane fdroid_export` | Validates metadata and generates the F-Droid metadata package descriptor (`build/fdroid/com.auratranscribe.app.yml`). |
| **Clean** | `fastlane clean` | Cleans Gradle build caches and temporary build outputs. |

---

## 🔑 Environment Variables & Credentials

| Variable | Description |
| :--- | :--- |
| `SUPPLY_JSON_KEY` / `GOOGLE_PLAY_KEY_FILE` | Path to Google Play Developer API service account JSON key. |
| `KEYSTORE_BASE64` | Base64-encoded release keystore for signing release APKs and AABs. |
| `STORE_PASS` | Password for the release keystore. |
| `KEY_ALIAS` | Key alias in the release keystore. |
| `KEY_PASS` | Password for the specific key alias. |

---

## 📦 Directory Structure

```
fastlane/
├── Appfile                       # Package ID & Google Play service account config
├── Fastfile                      # Deployment lanes, QA gates & build automation
├── README.md                     # Documentation & usage guide
└── metadata/
    └── android/
        └── en-US/
            ├── title.txt
            ├── short_description.txt
            ├── full_description.txt
            ├── changelogs/
            │   ├── 41.txt        # Changelog for versionCode 41 (v0.2.2)
            │   └── ...
            └── images/
                ├── icon.png
                └── phoneScreenshots/
                    ├── 1.png
                    ├── 2.png
                    └── 3.png
```
