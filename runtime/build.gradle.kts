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

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private data class BootstrapSpec(
    val abi: String,
    val archiveName: String,
    val sha256: String
)

private val bootstrapRelease = "bootstrap-2026.09.27-r1%2Bapt.android-7"
private val bootstrapSpecs = listOf(
    BootstrapSpec("arm64-v8a", "bootstrap-aarch64.zip", "9ddc32921187c85b04556bf56c6cce94e00b813ecd9299959a2d9b7c33386994"),
    BootstrapSpec("armeabi-v7a", "bootstrap-arm.zip", "3c856821189c658446ef2a527d264ac9848772d0cae1aa318ecab16315ef732d"),
    BootstrapSpec("x86_64", "bootstrap-x86_64.zip", "d8abd8714f8aab19ce923202e647d09b137b0ea34edc25a57b9fb93db6b1c00e")
)

abstract class DownloadBootstrapTask : DefaultTask() {
    companion object {
        private val downloadLock = Any()
        private const val JNI_BOOTSTRAP_ENTRY = "libbootstrap.zip.so"
    }
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val specs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Internal abstract val cacheDirectory: DirectoryProperty
    @get:Internal abstract val lockPath: Property<String>

    @TaskAction
    fun download() {
        val outputDir = outputDirectory.get().asFile
        val cacheDir = cacheDirectory.get().asFile
        outputDir.mkdirs()
        cacheDir.mkdirs()

        for (rawSpec in specs.get()) {
            val parts = rawSpec.split('|')
            check(parts.size == 3) { "Invalid bootstrap spec: $rawSpec" }
            val abi = parts[0]
            val archiveName = parts[1]
            val expectedSha = parts[2]

            val abiDir = outputDir.resolve(abi).apply { mkdirs() }
            val archive = abiDir.resolve(JNI_BOOTSTRAP_ENTRY)
            if (archive.isFile && sha256(archive) == expectedSha) {
                logger.lifecycle("Reusing verified Termux bootstrap $abi (${archive.length()} bytes, SHA-256 $expectedSha)")
                continue
            }

            val cachedArchive = cacheDir.resolve(archiveName)
            if (cachedArchive.isFile && sha256(cachedArchive) == expectedSha) {
                Files.copy(cachedArchive.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("Reusing cached Termux bootstrap $abi (${archive.length()} bytes, SHA-256 $expectedSha)")
                continue
            }

            val url = "https://github.com/termux/termux-packages/releases/download/${releaseTag.get()}/$archiveName"
            val temporary = cacheDir.resolve("$archiveName.part")
            if (temporary.exists()) temporary.delete()
            var lastFailure: Exception? = null
            try {
                synchronized(downloadLock) {
                    val lockFile = File(lockPath.get())
                    lockFile.parentFile.mkdirs()
                    RandomAccessFile(lockFile, "rw").use { fileLock ->
                        fileLock.channel.lock().use {
                            if (cachedArchive.isFile && sha256(cachedArchive) == expectedSha) {
                                Files.copy(cachedArchive.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                logger.lifecycle("Reusing cached Termux bootstrap $abi (${archive.length()} bytes, SHA-256 $expectedSha)")
                                return@use
                            }
                            var downloaded = false
                            repeat(3) { attempt ->
                                if (downloaded) return@repeat
                                try {
                                    val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                                    connection.connectTimeout = 120_000
                                    connection.readTimeout = 300_000
                                    connection.instanceFollowRedirects = true
                                    try {
                                        connection.connect()
                                        check(connection.responseCode in 200..299) {
                                            "Bootstrap download failed for $abi: HTTP ${connection.responseCode}"
                                        }
                                        val digest = MessageDigest.getInstance("SHA-256")
                                        var total = 0L
                                        connection.inputStream.use { input ->
                                            temporary.outputStream().use { output ->
                                                val buffer = ByteArray(64 * 1024)
                                                while (true) {
                                                    val count = input.read(buffer)
                                                    if (count < 0) break
                                                    total += count
                                                    check(total <= 64L * 1024L * 1024L) {
                                                        "Bootstrap archive is unexpectedly large for $abi"
                                                    }
                                                    digest.update(buffer, 0, count)
                                                    output.write(buffer, 0, count)
                                                }
                                            }
                                        }
                                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                        check(actual == expectedSha) {
                                            "Bootstrap SHA-256 mismatch for $abi: expected $expectedSha, got $actual"
                                        }
                                        Files.move(temporary.toPath(), cachedArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                        Files.copy(cachedArchive.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                        logger.lifecycle("Verified Termux bootstrap $abi ($total bytes, SHA-256 $actual)")
                                        downloaded = true
                                    } finally {
                                        connection.disconnect()
                                    }
                                } catch (e: Exception) {
                                    lastFailure = e
                                    if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
                                }
                            }
                            if (!downloaded) {
                                throw lastFailure ?: error("Bootstrap download failed for $abi")
                            }
                        }
                    }
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cc.ptoe.messenger.runtime"
    compileSdk {
        version = release(37)
    }
    // Pinned, as everywhere else in this repo: the vendored PTY JNI
    // (src/main/cpp) is built per ABI split with CMake.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "cc.ptoe.messenger.runtime"
        minSdk = 28
        // targetSdk 28 keeps this app's process in the legacy untrusted_app_27
        // SELinux domain, which retains execute/execute_no_trans on
        // app_data_file. That is what allows executing the extracted Termux
        // bootstrap: Android 10+ W^X strips those permissions from the
        // targetSdk-29+ untrusted_app domain (the main app's domain).
        targetSdk = 28
        versionCode = rootProject.ext["versionCode"] as Int
        versionName = rootProject.ext["versionName"] as String
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // The Termux bootstrap zip is packaged under jniLibs/<abi>/libbootstrap.zip.so
            // so AGP's per-ABI split filter includes only the target ABI's archive; skip
            // llvm-strip on it since it is a zip archive, not an ELF shared object.
            keepDebugSymbols += "**/libbootstrap.zip.so"
            // The bundled ripgrep binary rides the same per-ABI jniLibs split
            // (src/main/jniLibs/<abi>/librg.so, pre-stripped at build time).
            // It is a static-pie executable, not a shared object — AGP's
            // strip/merge must leave it byte-identical.
            keepDebugSymbols += "**/librg.so"
        }
    }

    signingConfigs {
        create("messenger") {
            storeFile = rootProject.ext["keystoreFile"] as java.io.File
            storePassword = rootProject.ext["keystorePassword"] as String
            keyAlias = rootProject.ext["keyAlias"] as String
            keyPassword = rootProject.ext["keyPassword"] as String
        }
    }

    buildTypes {
        debug {
            val k = signingConfigs["messenger"]
            if (k.storePassword?.isNotBlank() == true && k.storeFile?.exists() == true) {
                signingConfig = k
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val k = signingConfigs["messenger"]
            if (k.storePassword?.isNotBlank() == true && k.storeFile?.exists() == true) {
                signingConfig = k
            }
        }
    }

    buildFeatures {
        aidl = true
    }

    lint {
        // targetSdk 28 is deliberate here (legacy untrusted_app_27 SELinux
        // domain — see the comment above), so the Google Play target-API
        // requirement cannot apply to this companion app: without the
        // exception lintVital fails every release build.
        disable += "ExpiredTargetSdkVersion"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<DownloadBootstrapTask>("download${variant.name.replaceFirstChar { it.uppercase() }}Bootstrap") {
            releaseTag.set(bootstrapRelease)
            specs.set(bootstrapSpecs.map { "${it.abi}|${it.archiveName}|${it.sha256}" })
            cacheDirectory.set(rootProject.layout.projectDirectory.dir(".gradle/bootstrap-cache"))
            lockPath.set(rootProject.file(".gradle/bootstrap-download.lock").absolutePath)
            outputDirectory.set(layout.buildDirectory.dir("generated/bootstrap/${variant.name}/jniLibs"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task) { it.outputDirectory }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // The vendored Termux terminal emulator annotates its APIs.
    implementation(libs.androidx.annotation)
}
