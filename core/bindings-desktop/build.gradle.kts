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

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Desktop counterpart of :core-bindings. The Rust core is platform-neutral;
// only the way its cdylib is produced and packaged differs. Android cross-
// compiles with cargo-ndk into jniLibs, Desktop builds the host library once
// and ships it as a JVM resource that UniFFI's JNA loader extracts.
//
// The bindgen profile keeps the symbol table: uniffi reads UNIFFI_META_* from
// `.symtab` on ELF, and the release profile's `strip = true` deletes it.
val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("core/rust")
val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffiKotlin")
val hostProfile = "bindgen"
val hostProfileDir = "target/$hostProfile"

val hostLibName = when {
    OperatingSystem.current().isWindows -> "messenger_ffi.dll"
    OperatingSystem.current().isMacOsX -> "libmessenger_ffi.dylib"
    else -> "libmessenger_ffi.so"
}
val exeSuffix = if (OperatingSystem.current().isWindows) ".exe" else ""
val bindgenBinName = "$hostProfileDir/uniffi-bindgen$exeSuffix"

val rustInputs = Action<Exec> {
    inputs.file(rustWorkspaceDir.file("Cargo.toml"))
    inputs.file(rustWorkspaceDir.file("Cargo.lock"))
    inputs.dir(rustWorkspaceDir.dir("crates"))
}

val cargoBuildHost by tasks.registering(Exec::class) {
    workingDir(rustWorkspaceDir)
    commandLine(
        "cargo", "build", "--profile", hostProfile, "-p", "messenger-ffi",
        "--bin", "uniffi-bindgen", "--lib",
    )
    outputs.file(rustWorkspaceDir.file("$hostProfileDir/$hostLibName"))
    outputs.file(rustWorkspaceDir.file(bindgenBinName))
    rustInputs.execute(this)
}

val generateUniFFIBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuildHost)
    workingDir(rustWorkspaceDir)
    commandLine(
        File(rustWorkspaceDir.asFile, bindgenBinName).absolutePath,
        "generate",
        "--library", "$hostProfileDir/$hostLibName",
        "--language", "kotlin",
        "--no-format",
        "--out-dir", generatedKotlinDir.get().asFile.absolutePath,
    )
    outputs.dir(generatedKotlinDir)
    val outPath = generatedKotlinDir.get().asFile.absolutePath
    doLast {
        val ktFiles = File(outPath)
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            .orEmpty()
        check(ktFiles.isNotEmpty()) {
            "UniFFI generated no Kotlin bindings into $outPath. The host library carried no " +
                "UNIFFI_META_* symbols — it must be built with the `bindgen` profile."
        }
    }
    rustInputs.execute(this)
}

/**
 * Native library shipped inside the jar. JNA's `Native.load` extracts it from
 * the classpath under `<Platform.RESOURCE_PREFIX>/`, so the resource path must
 * match JNA's own naming (`win32-x86-64`, `darwin-aarch64`, …) — a bare jar
 * root would never be found.
 */
val hostNativeDir = layout.buildDirectory.dir("generated/native")
val jnaResourcePrefix = run {
    val arch = when (System.getProperty("os.arch")) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        "x86", "i386", "i686" -> "x86"
        else -> System.getProperty("os.arch")
    }
    val os = when {
        OperatingSystem.current().isWindows -> "win32"
        OperatingSystem.current().isMacOsX -> "darwin"
        else -> "linux"
    }
    "$os-$arch"
}
val stageHostNative by tasks.registering(Copy::class) {
    dependsOn(cargoBuildHost)
    from(rustWorkspaceDir.file("$hostProfileDir/$hostLibName"))
    into(hostNativeDir.map { it.dir(jnaResourcePrefix) })
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        getByName("main") {
            kotlin.srcDir(generatedKotlinDir)
            resources.srcDir(hostNativeDir)
        }
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // UniFFI's Kotlin bindings drive the cdylib through JNA. The plain jar is
    // correct on the JVM; the @aar variant is an Android packaging detail.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}")
    implementation(libs.kotlinx.coroutines.core)
}

// :core-bindings runs the same two cargo tasks against the same workspace
// target directory. Ordering them (instead of running them concurrently) keeps
// the second invocation UP-TO-DATE and stops one module's copy from reading a
// cdylib the other is still writing.
val androidBindings = rootProject.project(":core-bindings")
cargoBuildHost.configure { mustRunAfter(androidBindings.tasks.named("cargoBuildHost")) }
generateUniFFIBindings.configure {
    mustRunAfter(androidBindings.tasks.named("generateUniFFIBindings"))
}
stageHostNative.configure { mustRunAfter(androidBindings.tasks.named("cargoBuildHost")) }

tasks.named("compileKotlin") {
    dependsOn(generateUniFFIBindings)
}

// The staged native library is a resource root, so the resource task must
// consume the copy task's output explicitly (Gradle cannot infer a dependency
// from a bare directory source).
tasks.named("processResources") {
    dependsOn(stageHostNative)
}
