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

val hostLibName = when {
    OperatingSystem.current().isWindows -> "messenger_ffi.dll"
    OperatingSystem.current().isMacOsX -> "libmessenger_ffi.dylib"
    else -> "libmessenger_ffi.so"
}

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
    commandLine("cargo", "build", "--release", "-p", "messenger-ffi")
    outputs.file(rustWorkspaceDir.file("target/release/$hostLibName"))
    rustInputs.execute(this)
}

val generateUniFFIBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuildHost)
    workingDir(rustWorkspaceDir)
    // ktlint is not on every machine; formatting is cosmetic for generated code.
    commandLine(
        "cargo", "run", "--release", "-p", "messenger-ffi", "--bin", "uniffi-bindgen",
        "generate",
        "--library", "target/release/$hostLibName",
        "--language", "kotlin",
        "--no-format",
        "--out-dir", generatedKotlinDir.get().asFile.absolutePath,
    )
    outputs.dir(generatedKotlinDir)
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
