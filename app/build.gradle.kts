import java.io.FileInputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.notune.transcribe"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    // AGP 8.x defaults isIncludeAndroidResources = false for unit tests,
    // so Robolectric 4.11.1 cannot resolve R.string.X at runtime and
    // throws Resources$NotFoundException. Setting it true merges
    // src/main/res/ + src/test/res/ into the test classpath.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    defaultConfig {
        applicationId = "dev.notune.transcribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("STORE_PASS") ?: "password"
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASS") ?: "password"
            }
        }
    }

    buildTypes {
        // Debug installs as dev.notune.transcribe.debug so it can coexist
        // side-by-side with the release build (different applicationId ⇒
        // no signature conflict on update).
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Defensive asserts for the release build. Fail fast on configurations
    // that would silently break the app:
    //   - isMinifyEnabled=true renames JNI callback methods called from
    //     Rust by reflection (onStatusUpdate, onTextTranscribed, etc.) ->
    //     NoSuchMethodError at runtime.
    //   - signingConfig=null OR a missing keystore file would ship an
    //     unsigned APK (Play Store rejects it; sideloaded installs warn
    //     and don't auto-update).
    // AGP creates assembleRelease lazily while the android { } block
    // evaluates, so wait for the full configuration phase via
    // afterEvaluate { } before referring to it. The keystore existence
    // check is gated on a missing CI env var, because CI typically
    // provisions signing material via secrets (not a checked-in keystore).
    afterEvaluate {
        tasks.named("assembleRelease") {
            val releaseType = buildTypes.getByName("release")
            doFirst {
                require(!releaseType.isMinifyEnabled) {
                    "release.isMinifyEnabled must be false " +
                            "(JNI reflective call sites must not be obfuscated); " +
                            "current value: ${releaseType.isMinifyEnabled}"
                }
                val cfg = releaseType.signingConfig
                require(cfg != null) {
                    "release.signingConfig must not be null " +
                            "(Play Store rejects unsigned APKs); " +
                            "current value: null"
                }
                if (System.getenv("CI") != null) {
                    logger.warn(
                        "Skipping keystore existence check in CI; " +
                                "ensure signing material is provisioned via env " +
                                "or secrets store (STORE_PASS / KEY_ALIAS / KEY_PASS)."
                    )
                } else {
                    require(cfg.storeFile?.exists() == true) {
                        "release keystore file is missing or unset: " +
                                "expected at " +
                                "${cfg.storeFile?.absolutePath ?: "<null>"} " +
                                "(check release.keystore exists at the project root " +
                                "and signingConfigs.release.storeFile is assigned)"
                    }
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // extractNativeLibs=false (16KB safe)
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    // Material Components (Material 3 / Material You). Pulls in AppCompat.
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")

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

// Dedicated configuration to resolve the ORT AAR for the Rust build
val ortNative: Configuration by configurations.creating
dependencies {
    ortNative("com.microsoft.onnxruntime:onnxruntime-android:1.25.0")
}

// ---------------------------------------------------------------------------
// Task to extract ORT headers & native libs for Rust compilation
// ---------------------------------------------------------------------------

val extractOrt by tasks.registering(Copy::class) {
    description = "Extract ONNX Runtime AAR for Rust build"
    group = "build"

    from(ortNative.elements.map { fileCollection ->
        fileCollection.map { zipTree(it) }
    })
    into(layout.buildDirectory.dir("ort-extracted"))
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust native code via cargo-ndk"
    group = "build"

    dependsOn(extractOrt)

    workingDir = rootProject.projectDir   // Cargo.toml lives at project root

    // Detect NDK path from local.properties or env
    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath

    environment("ANDROID_NDK_HOME", ndkDir)

    val extractDir = layout.buildDirectory.dir("ort-extracted").get().asFile
    environment("ORT_LIB_LOCATION", File(extractDir, "jni/arm64-v8a").absolutePath)
    environment("ORT_INCLUDE_DIR", File(extractDir, "headers").absolutePath)

    val jniLibsDir = project.file("src/main/jniLibs")

    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )

    // Copy libc++_shared.so from NDK (needed because Rust links against it dynamically)
    doLast {
        val ndkPath = environment["ANDROID_NDK_HOME"] as String
        val libcpp = file("$ndkPath/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
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

// Wire the cargo-ndk build into the Android build lifecycle
tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------

data class ModelFile(val name: String, val sha256: String)

// Small metadata files stay in app/src/main/assets (always in base module)
val appAssetFiles = listOf(
    ModelFile("config.json", ""),
    ModelFile("vocab.txt", ""),
)

// Large ONNX model files go into the model_assets asset pack so the base
// module stays under the Play Store 200 MB compressed-download limit.
val modelPackFiles = listOf(
    ModelFile("encoder-model.int8.onnx",
        "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
    ModelFile("decoder_joint-model.int8.onnx",
        "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
    ModelFile("nemo128.onnx",
        "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
)

// Canary-180m-flash-int8 sibling for 0.6B. SHAs are pinned from the first
// downloadModels run; integrity is verified on subsequent fetches.
// The decoder-model.int8.onnx variant differs from parakeet's
// decoder_joint (Canary uses a non-joint decoder graph).
val modelPackFiles180m = listOf(
    ModelFile("encoder-model.int8.onnx",
        "996d1c89e6cbc891a7c88bf410884c178ffa474f7b13084522ac74a5e144cc81"),
    ModelFile("decoder-model.int8.onnx",
        "9dd9c447872088c912e916d73751f9621a54085d5bc46788454fe904db51a914"),
)
val appAssetFiles180m = listOf(
    ModelFile("vocab.txt",
        "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
)

val huggingFaceRepo = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main"
val huggingFaceRepo180m = "https://huggingface.co/istupakov/canary-180m-flash-onnx/resolve/main"

fun downloadToDir(assetsDir: File, files: List<ModelFile>, repo: String = huggingFaceRepo) {
    assetsDir.mkdirs()
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
            val downloadUrl = "$repo/${model.name}?download=true"
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
    description = "Download HuggingFace Parakeet model assets (0.6b + canary-180m-flash-int8)"
    group = "build"

    // Small metadata (config.json + vocab.txt) → base module assets. Both
    // debug and release APKs include these (tiny total: <100 KB).
    val appAssetsDir = project.file("src/main/assets/parakeet-tdt-0.6b-v3-int8")
    val appAssetsDir180m = project.file("src/main/assets/canary-180m-flash-int8")

    // Large ONNX weights → debug-only assets. Android Gradle source-set
    // merging rules: `src/debug/assets/` OVERLAYS `src/main/assets/` for
    // the debug variant only. Listing the heavy .onnx files here means:
    //   • the debug APK ships with both models inside (sideload-ready —
    //     no Wi-Fi download, ModelDownloadManager.tryCopyAsset() extracts
    //     them on first launch into `getFilesDir()/models/<variant>/`);
    //   • the release APK stays Play-Store-ready, since Play Asset
    //     Delivery caps the base module at 150 MB compressed and the
    //     asset packs carry the heavy weights via `model_assets/`.
    val debugAssetsDir = project.file("src/debug/assets/parakeet-tdt-0.6b-v3-int8")
    val debugAssetsDir180m = project.file("src/debug/assets/canary-180m-flash-int8")

    // Asset-pack files (release path). Goes through Play's install-time
    // delivery on .aab, max 2 GB per pack; identical files to debug-only
    // path (same SHA-256), so downloadToDir skips them on the second
    // call (file-exists + SHA verified).
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/parakeet-tdt-0.6b-v3-int8")
    val packAssetsDir180m = rootProject.file("model_assets/src/main/assets/canary-180m-flash-int8")

    outputs.dir(appAssetsDir)
    outputs.dir(appAssetsDir180m)
    outputs.dir(debugAssetsDir)
    outputs.dir(debugAssetsDir180m)
    outputs.dir(packAssetsDir)
    outputs.dir(packAssetsDir180m)

    doLast {
        // Small metadata — base (both variants)
        downloadToDir(appAssetsDir, appAssetFiles, huggingFaceRepo)
        downloadToDir(appAssetsDir180m, appAssetFiles180m, huggingFaceRepo180m)
        // Large ONNX — debug-only (sideload-install pipeline) + asset-pack
        // (release / Play Store .aab pipeline).
        downloadToDir(debugAssetsDir, modelPackFiles, huggingFaceRepo)
        downloadToDir(packAssetsDir, modelPackFiles, huggingFaceRepo)
        downloadToDir(debugAssetsDir180m, modelPackFiles180m, huggingFaceRepo180m)
        downloadToDir(packAssetsDir180m, modelPackFiles180m, huggingFaceRepo180m)
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModels)
}

tasks.withType<Test> {
    systemProperty("java.library.path", file("../target/release").absolutePath)
}
