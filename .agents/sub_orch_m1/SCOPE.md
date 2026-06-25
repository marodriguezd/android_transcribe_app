# Scope: Milestone 1 (Direct Asset Loading via FD)

## Architecture
- **Packaging**: Package ONNX models as uncompressed assets in the APK/AAB.
- **Java/Kotlin Layer**: Retrieve the `AssetFileDescriptor` for the model asset from the `AssetManager`. Extract its raw file descriptor (int), offset (long), and length (long).
- **JNI Layer**: Pass the raw file descriptor, offset, and length to Rust.
- **Rust Layer**: Load the ONNX model directly from the file descriptor `/proc/self/fd/<fd>` or directly via the raw fd, offset, and size, using ONNX Runtime's memory/FD/buffer loading features or standard file seeking.
- **Engine Layer**: Modify `engine.rs` and `assets.rs` to skip copying/extracting to `getFilesDir()`.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Plan & Explore (R1-Plan) | Explore codebase, locate model extraction & loading paths, and design the FD-based JNI & Rust interfaces | None | PLANNED |
| 2 | Implementation (R1-Impl) | Modify Android packaging, Java assets loading, JNI bridging, and Rust ONNX model loading | M1.1 | PLANNED |
| 3 | Code Verification (R1-Verify) | Verify that the app builds and tests pass, and that no model files are copied to the files directory | M1.2 | PLANNED |
| 4 | Adversarial & Integrity Audit | Run adversarial checks and teamwork_preview_auditor to verify zero-copy and direct FD loading | M1.3 | PLANNED |

## Interface Contracts
### JNI / Rust Interface
- Pass asset FD, offset, and size from Java to Rust to load models.
- Exact method signature TBD based on Explorer analysis.
