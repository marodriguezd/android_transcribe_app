import java.io.FileInputStream
import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.notune.transcribe"
    compileSdk = 34
    // Single source of truth for the NDK (P0.4): this exact version is what
    // CI installs (`.github/workflows/*.yml` -> sdkmanager ndk;28.0.13004108)
    // and what README/AGENTS document. Keep all three in sync.
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "dev.notune.transcribe"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "0.1.24"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                // Maintainer decision (2026-08-04): keep the historical
                // defaulting behaviour for local development — when the
                // keystore exists but a signing env var is missing, fall
                // back to the documented defaults instead of failing the
                // build. This MUST never be used for a public release:
                // the GitHub Actions release workflow always decodes the
                // keystore from KEYSTORE_BASE64 and exports all three
                // credentials, and the release job now verifies the APK is
                // signed before publishing. See android_release.yml.
                storeFile = ksFile
                storePassword = System.getenv("STORE_PASS") ?: "password"
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASS") ?: "password"
                if (System.getenv("STORE_PASS") == null
                    || System.getenv("KEY_ALIAS") == null
                    || System.getenv("KEY_PASS") == null) {
                    println("WARNING: release.keystore found but one or more of " +
                        "STORE_PASS/KEY_ALIAS/KEY_PASS is missing — the APK will be " +
                        "signed with default credentials. Never publish this APK.")
                }
            } else {
                println("WARNING: release.keystore not found — release builds will " +
                    "fail at signing time. Provide release.keystore + env vars or " +
                    "let the CI workflow decode KEYSTORE_BASE64.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        // BuildConfig.DEBUG is used to gate transcript/PP-error logging to
        // debug builds only (privacy, 2026-08-04). AGP 8.x disables
        // BuildConfig generation by default; without this flag the
        // referenced classes do not compile.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk.
    // The bundled speech model is included only in release builds; debug builds
    // ship without it to keep the APK under Telegram's 50 MB file-size limit.
    // The app downloads the model from Hugging Face on first run in debug mode.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // extractNativeLibs=false (16KB safe)
            keepDebugSymbols += "**/*.so"
        }
    }

    // Play Asset Delivery: large model files go into a separate asset pack
    // so the base module stays under the 200 MB Play Store limit.
    assetPacks += listOf(":model_assets")

    testOptions {
        unitTests {
            // Lets plain-JUnit tests access the merged R/assets without a device,
            // matching the Handy-Android guantelete harness (AGENTS.md §3).
            isIncludeAndroidResources = true
            // Let android.jar methods (notably android.util.Log) return default
            // values instead of throwing "Method not mocked", so the
            // PostProcessor HTTP suite (P1.3) can exercise the real error paths
            // on the JVM. No existing test relies on the "not mocked" throw.
            isReturnDefaultValues = true
        }
    }
}

// For APK builds (assemble/install), asset packs are ignored by AGP so we
// must include the asset-pack assets as an extra source directory.  For
// bundle builds the asset pack module handles delivery and we must NOT add
// the directory here (would cause duplicate-resource errors).  The release
// source set therefore only adds the model assets for release builds; debug
// builds omit them entirely.
val isBundle = gradle.startParameter.taskNames.any {
    it.contains("bundle", ignoreCase = true)
}
if (!isBundle) {
    android.sourceSets.getByName("release") {
        assets.srcDirs(rootProject.file("model_assets/src/main/assets"))
    }
}

dependencies {
    // Material Components (Material 3 / Material You). Pulls in AppCompat.
    implementation("com.google.android.material:material:1.12.0")

    // AI post-processing layer (fork addition): HTTP client for the
    // OpenAI-compatible /chat/completions endpoint.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Unit test harness
    testImplementation("junit:junit:4.13.2")
    // Controlled HTTP server for the post-processing cancellation-isolation
    // tests (P0.1) and payload/fallback tests (P1.3).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real org.json for the JVM tests: the android.jar copy is stubbed, so
    // JSONObject.toString() would return null/defaults and the PostProcessor
    // payload tests would fail (android.jar sits last on the test classpath,
    // so this shadows it).
    testImplementation("org.json:json:20240303")

    // Material/AppCompat transitively pull the legacy kotlin-stdlib-jdk7/jdk8:1.6.21
    // (via kotlinx-coroutines-android), whose classes were folded into
    // kotlin-stdlib in Kotlin 1.8 — causing duplicate-class build failures.
    // Align them with the resolved kotlin-stdlib (1.8.22), where they are empty
    // stubs. See https://kotlinlang.org/docs/whatsnew18.html#kotlin-stdlib
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

// Name of the NDK host-toolchain directory under toolchains/llvm/prebuilt.
// Resolved from the host OS/arch instead of hardcoding linux-x86_64 (P0.4),
// so the sysroot/libc++ wiring works on every officially supported NDK host:
// linux-x86_64, linux-aarch64 (where the NDK ships it), darwin-x86_64,
// darwin-arm64 and windows. An unsupported host fails fast with a clear
// message instead of silently pointing at a non-existent directory.
fun ndkPrebuiltDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.contains("mac") || os.contains("darwin") ->
            if (isArm64) "darwin-arm64" else "darwin-x86_64"
        os.contains("win") -> "windows"
        os.contains("linux") -> if (isArm64) "linux-aarch64" else "linux-x86_64"
        else -> throw GradleException("Unsupported build host: $os/$arch")
    }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust native code via cargo-ndk"
    group = "build"

    workingDir = rootProject.projectDir   // Cargo.toml lives at project root

    // Detect NDK path from local.properties or env
    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath
    val prebuiltDir = ndkPrebuiltDir()
    val prebuilt = file("$ndkDir/toolchains/llvm/prebuilt/$prebuiltDir")
    if (!prebuilt.exists()) {
        throw GradleException(
            "NDK host toolchain not found at ${prebuilt.absolutePath}. " +
            "This NDK install does not ship a '$prebuiltDir' host prebuilt " +
            "(supported hosts: linux-x86_64, darwin-x86_64, darwin-arm64, windows). " +
            "Declared build-host limit: the official NDK host for this machine " +
            "is not present; use one of the supported hosts above."
        )
    }

    environment("ANDROID_NDK_HOME", ndkDir)
    // transcribe-cpp-sys builds its C++ core through CMake, whose Android
    // platform detection needs one of these (ANDROID_NDK_HOME is not enough).
    environment("ANDROID_NDK_ROOT", ndkDir)
    environment("ANDROID_NDK", ndkDir)
    environment("CMAKE_ANDROID_NDK", ndkDir)
    environment("NDK_HOME", ndkDir)
    environment("CMAKE_TOOLCHAIN_FILE", "$ndkDir/build/cmake/android.toolchain.cmake")
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", "$ndkDir/build/cmake/android.toolchain.cmake")
    environment("CMAKE_TOOLCHAIN_FILE_aarch64-linux-android", "$ndkDir/build/cmake/android.toolchain.cmake")
    environment("CARGO_NDK_PLATFORM", "26")
    // ggml cannot autodetect the CPU when cross-compiling and falls back to
    // baseline armv8-a, losing the dotprod/fp16 kernels its quantized matmuls
    // rely on (several times slower). armv8.2-a+dotprod+fp16 is supported by
    // arm64 phones from ~2018 on; the engine refuses older CPUs with a clear
    // error at load (see check_cpu_features in src/engine.rs) instead of
    // crashing mid-inference.
    environment("TRANSCRIBE_CMAKE_ARGS", "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DANDROID_STL=c++_shared -DCMAKE_SYSROOT=$ndkDir/toolchains/llvm/prebuilt/$prebuiltDir/sysroot -DCMAKE_SYSTEM_VERSION=26 -DANDROID_PLATFORM=android-26 -DANDROID_ABI=arm64-v8a -DANDROID_NDK=$ndkDir -DCMAKE_ANDROID_NDK=$ndkDir")

    val jniLibsDir = project.file("src/main/jniLibs")

    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )

    // Copy libc++_shared.so from NDK (needed because Rust links against it
    // dynamically). Path is host-architecture aware (P0.4).
    doLast {
        val ndkPath = environment["ANDROID_NDK_HOME"] as String
        val libcpp = file("$ndkPath/toolchains/llvm/prebuilt/$prebuiltDir/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        if (libcpp.exists()) {
            val destDir = File(jniLibsDir, "arm64-v8a")
            destDir.mkdirs()
            libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
            println("Copied libc++_shared.so from NDK")
        } else {
            throw GradleException("libc++_shared.so not found in NDK at: ${libcpp.absolutePath}")
        }
    }

    outputs.dir(jniLibsDir)
    // No input tracking — always run and let cargo's own incremental build
    // decide what to recompile (a no-op cargo invocation is fast). Without
    // this, Gradle sees unchanged outputs and skips Rust rebuilds entirely.
    outputs.upToDateWhen { false }
}

// Wire the cargo-ndk build into the Android build lifecycle for APK builds
// (skipping heavy Rust compilation during unit testing/linting to conserve CPU).
val isUnitTestTask = gradle.startParameter.taskNames.any {
    it.contains("test", ignoreCase = true) || it.contains("lint", ignoreCase = true)
}
if (!isUnitTestTask) {
    tasks.named("preBuild") {
        dependsOn(cargoNdkBuild)
    }
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------
// The bundled model is only downloaded for release builds. Debug builds ship
// without it to keep the APK under Telegram's 50 MB file-size limit; the app
// downloads the model from Hugging Face on first run.

data class ModelFile(val name: String, val sha256: String)

// The bundled GGUF goes into the model_assets asset pack so the base module
// stays under the Play Store 200 MB compressed-download limit.
//
// Default model: Nemotron 3.5 ASR Streaming 0.6B in Q8_0 (the quantization
// with the best WER/quality trade-off per the handy-computer model card).
// Cache-aware streaming + native language detection (40 language-locales);
// the engine falls back to the device-locale hint for Canary-family models
// without native detection. SHA-256 is the HF LFS oid of the file.
val modelPackFiles = listOf(
    ModelFile("nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf",
        "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c"),
)

val huggingFaceRepo = "https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf/resolve/main"

fun downloadToDir(assetsDir: File, files: List<ModelFile>) {
    assetsDir.mkdirs()
    // Remove stale GGUF files not in the current list: an app upgrade can
    // swap the bundled model, and a leftover old file would otherwise be
    // picked up by the engine's single-GGUF lookup.
    assetsDir.listFiles()?.forEach { f ->
        if (f.isFile && f.extension.equals("gguf", ignoreCase = true)
            && files.none { it.name == f.name }) {
            println("  - removing stale model asset ${f.name}")
            f.delete()
        }
    }
    files.forEach { model ->
        val destFile = File(assetsDir, model.name)
        if (destFile.exists() && model.sha256.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(destFile).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == model.sha256) {
                println("  ✓ ${model.name} already downloaded and verified")
                return@forEach
            } else {
                println("  ✗ ${model.name} checksum mismatch, re-downloading...")
                destFile.delete()
            }
        }

        if (!destFile.exists()) {
            println("  ↓ Downloading ${model.name}...")
            val downloadUrl = "$huggingFaceRepo/${model.name}?download=true"
            val proc = ProcessBuilder("curl", "-L", "-f", "-o", destFile.absolutePath, downloadUrl)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download ${model.name} (curl exit code $exitCode)")
            }

            if (model.sha256.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(destFile).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != model.sha256) {
                    throw GradleException(
                        "Checksum verification failed for ${model.name}:\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                    )
                }
                println("  ✓ ${model.name} verified")
            }
        }
    }
}

val downloadModels by tasks.registering {
    description = "Download the built-in speech model (GGUF)"
    group = "build"

    // The GGUF -> asset pack (separate install-time delivery)
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    outputs.dir(packAssetsDir)

    doLast {
        downloadToDir(packAssetsDir, modelPackFiles)
    }
}

// QA gate that mirrors Handy-Android's `checkModelCatalog` (AGENTS.md §3
// "Validación y estilo"). It verifies the SHA-256 of every *present* bundled
// model asset against the hash declared in `modelPackFiles` and fails the build
// on any mismatch. When no asset is present (e.g. a plain debug assemble, where
// `downloadModels` is intentionally skipped to keep the APK small) it is a safe
// no-op and the runtime download path is responsible for fetching/verifying.
val checkModels by tasks.registering {
    description = "QA gate: verify bundled model asset SHA-256 matches declared hash"
    group = "verification"

    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    doLast {
        var checked = 0
        for (model in modelPackFiles) {
            val asset = File(packAssetsDir, model.name)
            if (!asset.exists()) continue
            checked++

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(asset).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != model.sha256) {
                throw GradleException(
                    "checkModels: checksum mismatch for ${model.name}\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                )
            }
            println("  checkModels: \u2713 ${model.name} SHA-256 verified")
        }
        if (checked == 0) {
            println("checkModels: no bundled model asset present (verification skipped; runtime downloads verify on first run)")
        }
    }
}

// Run the model-hash gate as part of `check` so CI invokes it alongside tests.
tasks.named("check") {
    dependsOn(checkModels)
}

// Only download the bundled model when the user is actually building a
// release/bundle variant. Debug builds skip the download to keep CI fast;
// the app downloads the model at runtime instead.
val isDebugBuild = gradle.startParameter.taskNames.any {
    it.contains("Debug", ignoreCase = true)
}
if (!isDebugBuild) {
    tasks.named("preBuild") {
        dependsOn(downloadModels)
    }
}
