/*
 * Copyright 2026 originalFactor
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

@file:Suppress("UNCHECKED_CAST", "DEPRECATION")

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// The Rust core lives in a Cargo workspace outside Gradle's model; the Exec
// tasks below bridge the two. The host build feeds the UniFFI bindgen and the
// per-build-type cargo-ndk builds feed jniLibs; the Kotlin side is wired into
// preBuild, and each variant pulls in only its own jniLibs task.
val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("core/rust")
val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffiKotlin")

// One directory per build type: a shared one would make the two cargo-ndk tasks
// invalidate each other's output (each wipes what it owns before running).
val rustJniLibsDebugDir = objects.directoryProperty().apply {
    set(layout.buildDirectory.dir("rustJniLibs/debug"))
}
val rustJniLibsReleaseDir = objects.directoryProperty().apply {
    set(layout.buildDirectory.dir("rustJniLibs/release"))
}

// Plain snapshot of the root build script's abi -> Rust triple map: the
// configuration cache rejects task actions that read rootProject.ext directly.
val abiToRustTargetMap: Map<String, String> =
    (rootProject.ext["abiToRustTarget"] as Map<*, *>).entries.associate { it.key as String to it.value as String }
val debugAbiList: List<String> = rootProject.ext["debugAbis"] as List<String>
val releaseAbiList: List<String> = rootProject.ext["releaseAbis"] as List<String>

// Plain values captured at configuration time for the CI diagnostics below: the
// configuration cache rejects task actions that close over `project` or other
// Gradle model objects.
val generatedKotlinPath = generatedKotlinDir.get().asFile.absolutePath

val hostLibName = when {
    OperatingSystem.current().isWindows -> "messenger_ffi.dll"
    OperatingSystem.current().isMacOsX -> "libmessenger_ffi.dylib"
    else -> "libmessenger_ffi.so"
}

// The host build uses the `bindgen` profile, which is `release` with
// `strip = "none"`. uniffi-bindgen locates the UNIFFI_META_* symbols by walking
// the ELF `.symtab`, and the release profile's `strip = true` deletes it — on
// Linux bindgen then exits 0 having found no components and writes an empty
// bindings directory, which surfaced as `NO-SOURCE` on :core-bindings and
// missing lifecycle symbols. PE and Mach-O are immune
// (they read the export table and nlist), so only the Linux CI job broke.
// Cargo emits custom-profile output under target/<profile>/, which also keeps
// the host library out of target/release/ where cargo-ndk's shared state lives.
val hostProfile = "bindgen"
val hostProfileDir = "target/$hostProfile"

// Cargo emits `<name>.exe` for bins on Windows only; Unix targets get no suffix.
val exeSuffix = if (OperatingSystem.current().isWindows) ".exe" else ""
val bindgenBinName = "$hostProfileDir/uniffi-bindgen$exeSuffix"

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
    // The pinned NDK, not "whatever is newest": cargo-ndk and the AGP native
    // build must compile the same Rust/C code against the same toolchain, and
    // an unpinned directory listing silently picks up any NDK a previous
    // build downloaded.
    file(sdkDir).resolve("ndk").resolve(rootProject.ext["ndkVersion"] as String).absolutePath
}

val cargoBuildHost by tasks.registering(Exec::class) {
    workingDir(rustWorkspaceDir)
    // --profile bindgen: build the cdylib with its symbol table intact, which is
    // what uniffi-bindgen needs to find the metadata symbols (release's
    // `strip = true` removes them). Also builds the bindgen binary itself, so
    // generateUniFFIBindings can invoke it directly rather than re-entering
    // cargo via `cargo run`.
    commandLine(
        "cargo", "build", "--profile", hostProfile, "-p", "messenger-ffi",
        "--bin", "uniffi-bindgen", "--lib",
    )
    // Declared per platform, matching what cargo actually emits for
    // crate-type = ["lib", "cdylib"]: `messenger_ffi.dll` on Windows,
    // `libmessenger_ffi.dylib` on macOS, `libmessenger_ffi.so` elsewhere.
    // A wrong name here would make the task permanently out-of-date against a
    // file that never appears.
    outputs.file(rustWorkspaceDir.file("$hostProfileDir/$hostLibName"))
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
    // `cargo run`, so exactly one cargo invocation owns this task's inputs.
    // ktlint is not on every machine; formatting is cosmetic for generated code.
    commandLine(
        // Absolute path: Exec does not resolve a relative program against the
        // working directory on Windows, and the `.exe` suffix is already baked
        // into bindgenBinName.
        File(rustWorkspaceDir.asFile, bindgenBinName).absolutePath,
        "generate",
        "--library", "$hostProfileDir/$hostLibName",
        "--language", "kotlin",
        "--no-format",
        "--out-dir", generatedKotlinDir.get().asFile.absolutePath,
    )
    outputs.dir(generatedKotlinDir)
    // Fail fast instead of silently generating nothing. This task exiting 0
    // with an empty output directory made the generated Kotlin source set
    // empty and surfaced as unresolved lifecycle symbols far from the cause.
    // Two distinct ways to end up here:
    //   1. the host cdylib is missing (cargoBuildHost was skipped as UP-TO-DATE
    //      against a stale output snapshot, or the crate-type produced a
    //      differently-named library);
    //   2. bindgen ran but wrote no .kt files.
    // Distinguish them here, where the actual cargo output is still in context.
    val libPath = "$hostProfileDir/$hostLibName"
    val outPath = generatedKotlinPath
    val rustRoot = rustWorkspaceDir.asFile.absolutePath
    val bindgenBinPath = bindgenBinName
    doFirst {
        val lib = File(File(rustRoot, libPath).absolutePath)
        val bin = File(File(rustRoot, bindgenBinPath).absolutePath)
        check(bin.isFile && bin.length() > 0) {
            "UniFFI bindgen binary missing or empty: $bin (exists=${bin.isFile}). " +
                "cargoBuildHost builds it via `--bin uniffi-bindgen`; run " +
                "`cargo build --profile bindgen -p messenger-ffi --bin uniffi-bindgen --lib` in core/rust."
        }
        check(lib.isFile && lib.length() > 0) {
            "UniFFI host library missing or empty: $lib (exists=${lib.isFile}, " +
                "size=${if (lib.isFile) lib.length() else -1}). cargoBuildHost should have " +
                "produced it; run `cargo build --profile bindgen -p messenger-ffi` in core/rust to see why."
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
                "(bindgen exited 0 but wrote ${ktFiles.size} .kt files). The library carried no " +
                "UNIFFI_META_* metadata symbols — most likely it was built with `strip = true` " +
                "instead of the `bindgen` profile, whose symbol table uniffi reads."
        }
        logger.lifecycle("[uniffi] wrote ${ktFiles.size} .kt file(s) to $outDir")
        ktFiles.forEach { logger.lifecycle("[uniffi]   ${it.name} (${it.length()} bytes)") }
    }
    rustInputs.execute(this)
}

/**
 * cargo-ndk for ONE build type's ABI list.
 *
 * Deliberately one task per build type rather than a single task over the union
 * of every ABI: a Rust cross-build of this workspace costs minutes, and a union
 * made every `assembleDebug` cross-compile the release ABI too. Each task owns
 * its own output directory, so the two never invalidate each other, and each
 * variant registers only its own directory as jniLibs (see androidComponents).
 */
fun registerRustAndroidTask(buildType: String, abiList: List<String>, outDir: DirectoryProperty) =
    tasks.register<Exec>("buildRustAndroid${buildType.replaceFirstChar { it.uppercase() }}") {
        workingDir(rustWorkspaceDir)
        val rustTargets = abiList.map { abiToRustTargetMap.getValue(it) }
        commandLine(
            listOf("cargo", "ndk", "--platform", "30") +
                rustTargets.flatMap { listOf("-t", it) } +
                listOf("-o", outDir.get().asFile.absolutePath, "build", "--release", "-p", "messenger-ffi")
        )
        ndkHome?.let { environment("ANDROID_NDK_HOME", it) }
        // Wipe first: cargo-ndk only writes the ABIs it is asked for, so a stale
        // directory from an earlier ABI list would ship an outdated library.
        val jniLibsDir = outDir.get().asFile
        doFirst { jniLibsDir.deleteRecursively() }
        outputs.dir(outDir)
        // Track the WHOLE workspace: messenger-ffi depends on the sibling crates.
        inputs.file(rustWorkspaceDir.file("Cargo.toml"))
        inputs.file(rustWorkspaceDir.file("Cargo.lock"))
        inputs.dir(rustWorkspaceDir.dir("crates"))
    }

val rustAndroidDebug = registerRustAndroidTask(
    "debug",
    debugAbiList,
    rustJniLibsDebugDir,
)
val rustAndroidRelease = registerRustAndroidTask(
    "release",
    releaseAbiList,
    rustJniLibsReleaseDir,
)

android {
    namespace = "cc.ptoe.messenger.core"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = rootProject.ext["buildToolsVersion"] as String

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

// Bind each variant to its own cargo-ndk output: the debug variant only ever gets
// the debug ABIs' jniLibs, so building it never waits for (or drags in) the
// release ABI's cross-build. `addStaticSourceDirectory` keeps AGP from treating
// the directory as a task output it must snapshot, and the dependency is wired
// explicitly on the variant's own preBuild.
androidComponents {
    onVariants { variant ->
        val debug = variant.buildType == "debug"
        val dir = if (debug) rustJniLibsDebugDir else rustJniLibsReleaseDir
        variant.sources.jniLibs?.addStaticSourceDirectory(dir.get().asFile.absolutePath)
    }
}

tasks.named("preBuild") {
    dependsOn(generateUniFFIBindings)
}
// The jniLibs merge task is where the variant's native libs are collected; hook
// the matching cargo-ndk task there so `assembleDebug` never runs the release
// cross-build (and vice versa).
tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { dependsOn(rustAndroidDebug) }
tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach { dependsOn(rustAndroidRelease) }

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
