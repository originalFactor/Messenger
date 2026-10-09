/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// The Rust core lives in a Cargo workspace outside Gradle's model; the Exec
// tasks below bridge the two. The host build feeds the UniFFI bindgen and the
// cargo-ndk build feeds jniLibs; both are wired into preBuild so every
// assemble of this module stays in sync with the Rust sources.
val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("core/rust")
val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffiKotlin")
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs")

// Plain values captured at configuration time for the CI diagnostics below: the
// configuration cache rejects task actions that close over `project` or other
// Gradle model objects.
val generatedKotlinPath = generatedKotlinDir.get().asFile.absolutePath

val hostLibName = when {
    OperatingSystem.current().isWindows -> "messenger_ffi.dll"
    OperatingSystem.current().isMacOsX -> "libmessenger_ffi.dylib"
    else -> "libmessenger_ffi.so"
}

// Cargo emits `<name>.exe` for bins on Windows only; Unix targets get no suffix.
val exeSuffix = if (OperatingSystem.current().isWindows) ".exe" else ""
val bindgenBinName = "target/release/uniffi-bindgen$exeSuffix"

val rustInputs = Action<Exec> {
    // Track the WHOLE workspace: messenger-ffi depends on the sibling crates
    // (store/core/llm/…), so a change outside the ffi crate must still
    // invalidate the Exec tasks — an ffi-only input set silently shipped
    // stale cdylibs after sibling-crate edits.
    inputs.file(rustWorkspaceDir.file("Cargo.toml"))
    inputs.file(rustWorkspaceDir.file("Cargo.lock"))
    inputs.dir(rustWorkspaceDir.dir("crates"))
}

/** cargo-ndk resolves the NDK from ANDROID_NDK_HOME; fall back to the SDK dir. */
val ndkHome: String? by lazy {
    System.getenv("ANDROID_NDK_HOME")?.let { return@lazy it }
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { props ->
            Properties().apply { props.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
        ?: return@lazy null
    file(sdkDir).resolve("ndk").listFiles()
        ?.maxByOrNull { it.name }?.absolutePath
}

val cargoBuildHost by tasks.registering(Exec::class) {
    workingDir(rustWorkspaceDir)
    // --bin uniffi-bindgen as well as the cdylib: generateUniFFIBindings invokes
    // this binary directly instead of `cargo run`, so a single cargo invocation
    // is the only thing that writes core/rust/target. See the comment there.
    commandLine(
        "cargo", "build", "--release", "-p", "messenger-ffi",
        "--bin", "uniffi-bindgen", "--lib",
    )
    // Declared per platform, matching what cargo actually emits for
    // crate-type = ["lib", "cdylib"]: `messenger_ffi.dll` on Windows,
    // `libmessenger_ffi.dylib` on macOS, `libmessenger_ffi.so` elsewhere.
    // A wrong name here would make the task permanently out-of-date against a
    // file that never appears.
    outputs.file(rustWorkspaceDir.file("target/release/$hostLibName"))
    // Both declared: generateUniFFIBindings depends on this binary existing,
    // so leaving it undeclared would let the task report UP-TO-DATE while the
    // executable was stale or absent.
    outputs.file(rustWorkspaceDir.file(bindgenBinName))
    rustInputs.execute(this)
}

val generateUniFFIBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuildHost)
    workingDir(rustWorkspaceDir)
    // Invoke the bindgen binary that cargoBuildHost already built instead of
    // `cargo run`. `cargo run` re-enters cargo and writes to the very same
    // core/rust/target/release directory that cargoBuildHost is writing; with
    // org.gradle.parallel=true those two raced, and the library bindgen then
    // loaded had its UniFFI metadata clobbered mid-write — the file existed and
    // was megabytes in size, but no #[uniffi::export] could be read out of it,
    // so bindgen exited 0 having written zero bindings. cargoBuildHost is now
    // the single writer of that directory.
    // ktlint is not on every machine; formatting is cosmetic for generated code.
    commandLine(
        // Absolute path: Exec does not resolve a relative program against the
        // working directory on Windows, and the `.exe` suffix is already baked
        // into bindgenBinName.
        File(rustWorkspaceDir.asFile, bindgenBinName).absolutePath,
        "generate",
        "--library", "target/release/$hostLibName",
        "--language", "kotlin",
        "--no-format",
        "--out-dir", generatedKotlinDir.get().asFile.absolutePath,
    )
    outputs.dir(generatedKotlinDir)
    // Fail fast instead of silently generating nothing. This task exiting 0
    // with an empty output directory made :core-bindings:compileReleaseKotlin
    // report NO-SOURCE and :renderer-android fail with two "Unresolved
    // reference" errors that pointed nowhere near the real cause. Two distinct
    // ways to end up here:
    //   1. the host cdylib is missing (cargoBuildHost was skipped as UP-TO-DATE
    //      against a stale output snapshot, or the crate-type produced a
    //      differently-named library);
    //   2. bindgen ran but wrote no .kt files.
    // Distinguish them here, where the actual cargo output is still in context.
    val libPath = "target/release/$hostLibName"
    val outPath = generatedKotlinPath
    val rustRoot = rustWorkspaceDir.asFile.absolutePath
    val bindgenBinPath = bindgenBinName
    doFirst {
        val lib = File(File(rustRoot, libPath).absolutePath)
        val bin = File(File(rustRoot, bindgenBinPath).absolutePath)
        check(bin.isFile && bin.length() > 0) {
            "UniFFI bindgen binary missing or empty: $bin (exists=${bin.isFile}). " +
                "cargoBuildHost builds it via `--bin uniffi-bindgen`; run " +
                "`cargo build --release -p messenger-ffi --bin uniffi-bindgen --lib` in core/rust."
        }
        check(lib.isFile && lib.length() > 0) {
            "UniFFI host library missing or empty: $lib (exists=${lib.isFile}, " +
                "size=${if (lib.isFile) lib.length() else -1}). cargoBuildHost should have " +
                "produced it; run `cargo build --release -p messenger-ffi` in core/rust to see why."
        }
        logger.lifecycle("[uniffi] host lib=$lib size=${lib.length()} bindgen=$bin")
    }
    doLast {
        val outDir = File(outPath)
        val ktFiles = if (outDir.isDirectory) {
            outDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } else {
            emptyList()
        }
        check(ktFiles.isNotEmpty()) {
            "UniFFI generated no Kotlin bindings into $outDir " +
                "(bindgen exited 0 but wrote ${ktFiles.size} .kt files). The host library " +
                "loaded without UniFFI metadata, so no #[uniffi::export] was discovered."
        }
        logger.lifecycle("[uniffi] wrote ${ktFiles.size} .kt file(s) to $outDir")
        ktFiles.forEach { logger.lifecycle("[uniffi]   ${it.name} (${it.length()} bytes)") }
    }
    rustInputs.execute(this)
}

val buildRustAndroid by tasks.registering(Exec::class) {
    workingDir(rustWorkspaceDir)
    commandLine(
        "cargo", "ndk", "--platform", "30",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", rustJniLibsDir.get().asFile.absolutePath,
        "build", "--release", "-p", "messenger-ffi",
    )
    ndkHome?.let { environment("ANDROID_NDK_HOME", it) }
    outputs.dir(rustJniLibsDir)
    rustInputs.execute(this)
}

android {
    namespace = "cc.ptoe.messenger.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            // AGP 9 built-in Kotlin compiles `kotlin` dirs; register the
            // generated bindings under both so the Kotlin task always sees them.
            java.srcDirs(generatedKotlinDir.get().asFile)
            kotlin.srcDirs(generatedKotlinDir.get().asFile)
            jniLibs.srcDir(rustJniLibsDir.get().asFile)
        }
    }
}

dependencies {
    // UniFFI-generated Kotlin drives the cdylib through JNA; the @aar variant
    // carries the Android native loader. Suspended exports pull in
    // kotlinx-coroutines primitives (CancellableContinuation).
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}

tasks.named("preBuild") {
    dependsOn(generateUniFFIBindings, buildRustAndroid)
}

// NOTE(ci): the second half of the NO-SOURCE diagnosis. Whether a Kotlin source
// directory that does not exist at snapshot time gets picked up is exactly what
// CI and a warm local build disagree about, so log the generated dir plus a
// per-directory file count at execution time. Deliberately avoids the AGP
// source-set API: on AGP 9 `android.sourceSets.getByName("main").java.srcDirs`
// throws a ClassCastException (DefaultAndroidLibrarySourceSet_Decorated cannot
// be cast to AndroidLibrarySourceSet) from this script.
tasks.configureEach {
    if (name != "compileReleaseKotlin" && name != "compileDebugKotlin") return@configureEach
    // Copy into a local so the action captures a plain String rather than a
    // reference to this build script (which the configuration cache rejects).
    val genPath = generatedKotlinPath
    doFirst {
        val f = File(genPath)
        val n = if (f.isDirectory) f.walkTopDown().count { it.isFile && it.extension == "kt" } else -1
        logger.lifecycle("[ksrc] task=$path generated=$f exists=${f.isDirectory} ktFiles=$n")
    }
}
