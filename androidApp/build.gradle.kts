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
import java.net.HttpURLConnection
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private data class BootstrapSpec(
    val flavor: String,
    val abi: String,
    val archiveName: String,
    val sha256: String
)

private val bootstrapRelease = "bootstrap-2026.09.27-r1%2Bapt.android-7"
private val bootstrapSpecs = listOf(
    BootstrapSpec("arm64V8a", "arm64-v8a", "bootstrap-aarch64.zip", "9ddc32921187c85b04556bf56c6cce94e00b813ecd9299959a2d9b7c33386994"),
    BootstrapSpec("armeabiV7a", "armeabi-v7a", "bootstrap-arm.zip", "3c856821189c658446ef2a527d264ac9848772d0cae1aa318ecab16315ef732d"),
    BootstrapSpec("x86", "x86", "bootstrap-i686.zip", "100ab4fa85cb90459cb83771602f88007c3296f25098de656979f063e162abd2"),
    BootstrapSpec("x86_64", "x86_64", "bootstrap-x86_64.zip", "d8abd8714f8aab19ce923202e647d09b137b0ea34edc25a57b9fb93db6b1c00e")
)

abstract class DownloadBootstrapTask : DefaultTask() {
    companion object {
        private val downloadLock = Any()
    }
    @get:Input abstract val url: org.gradle.api.provider.Property<String>
    @get:Input abstract val expectedSha256: org.gradle.api.provider.Property<String>
    @get:Input abstract val abi: org.gradle.api.provider.Property<String>
    @get:OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @get:Internal abstract val lockPath: org.gradle.api.provider.Property<String>

    @TaskAction
    fun download() {
        val outputDir = outputDirectory.get().asFile
        outputDir.mkdirs()
        val archiveDir = outputDir.resolve("agent-runtime")
        archiveDir.mkdirs()
        val archive = archiveDir.resolve("bootstrap.zip")
        val temporary = archiveDir.resolve("bootstrap.zip.part")
        if (temporary.exists()) temporary.delete()
        val legacyArchive = outputDir.resolve("bootstrap.zip")
        if (legacyArchive.exists()) legacyArchive.delete()
        if (archive.isFile && sha256(archive) == expectedSha256.get()) {
            logger.lifecycle("Reusing verified Termux bootstrap ${abi.get()} (${archive.length()} bytes, SHA-256 ${expectedSha256.get()})")
            return
        }
        archive.delete()
        archiveDir.mkdirs()
        var lastFailure: Exception? = null
        try {
        synchronized(downloadLock) {
            val lockFile = File(lockPath.get())
            lockFile.parentFile.mkdirs()
            RandomAccessFile(lockFile, "rw").use { fileLock ->
                fileLock.channel.lock().use {
                    repeat(3) { attempt ->
                        try {
                            val connection = URI.create(url.get()).toURL().openConnection() as HttpURLConnection
                            connection.connectTimeout = 120_000
                            connection.readTimeout = 300_000
                            connection.instanceFollowRedirects = true
                            try {
                                connection.connect()
                                check(connection.responseCode in 200..299) {
                                    "Bootstrap download failed for ${abi.get()}: HTTP ${connection.responseCode}"
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
                                                "Bootstrap archive is unexpectedly large for ${abi.get()}"
                                            }
                                            digest.update(buffer, 0, count)
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                }
                                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                check(actual == expectedSha256.get()) {
                                    "Bootstrap SHA-256 mismatch for ${abi.get()}: expected ${expectedSha256.get()}, got $actual"
                                }
                                if (archive.exists()) archive.delete()
                                check(temporary.renameTo(archive)) {
                                    "Cannot publish verified bootstrap for ${abi.get()}"
                                }
                                logger.lifecycle("Verified Termux bootstrap ${abi.get()} ($total bytes, SHA-256 $actual)")
                                return
                            } finally {
                                connection.disconnect()
                            }
                        } catch (e: Exception) {
                            lastFailure = e
                            if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
                        }
                    }
                }
            }
        }
            throw lastFailure ?: error("Bootstrap download failed for ${abi.get()}")
        } finally {
            if (temporary.exists()) temporary.delete()
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
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cc.ptoe.messenger"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cc.ptoe.messenger"
        minSdk = 30
        targetSdk = 36
        versionCode = rootProject.ext["versionCode"] as Int
        versionName = rootProject.ext["versionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "runtimeAbi"
    productFlavors {
        create("arm64V8a") {
            dimension = "runtimeAbi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("armeabiV7a") {
            dimension = "runtimeAbi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("x86") {
            dimension = "runtimeAbi"
            ndk { abiFilters += "x86" }
        }
        create("x86_64") {
            dimension = "runtimeAbi"
            ndk { abiFilters += "x86_64" }
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
            // R8 / minification disabled for androidApp — keep wear's R8 intact.
            // AGENTS.md's R8 chapter still applies to :wear; androidApp ships unminified.
            isMinifyEnabled = false
            isShrinkResources = false
            val k = signingConfigs["messenger"]
            if (k.storePassword?.isNotBlank() == true && k.storeFile?.exists() == true) {
                signingConfig = k
            }
        }
    }

    buildFeatures {
        compose = true
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
        val spec = bootstrapSpecs.single { it.flavor == variant.flavorName }
        val task = tasks.register<DownloadBootstrapTask>("download${spec.flavor.replaceFirstChar { it.uppercase() }}${variant.name.replaceFirstChar { it.uppercase() }}Bootstrap") {
            url.set("https://github.com/termux/termux-packages/releases/download/$bootstrapRelease/${spec.archiveName}")
            expectedSha256.set(spec.sha256)
            abi.set(spec.abi)
            lockPath.set(rootProject.file(".gradle/bootstrap-download.lock").absolutePath)
            outputDirectory.set(layout.buildDirectory.dir("generated/bootstrap/${variant.name}/assets"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task) { it.outputDirectory }
    }
}

// The guava/listenablefuture duplicate-class collision is resolved at :shared
// (per-dependency exclude of guava from llm-typewriter). androidApp inherits the
// cleaned runtime classpath transitively, plus the global listenablefuture stub
// exclude from the root build.gradle.kts as a safety net.
dependencies {
    implementation(project(":shared"))
    implementation(libs.ucrop)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Transitively-needed compile-classpath deps: MessengerApplication.kt directly
    // references coil3 / okio / Room / Ktor types that are `implementation` (not
    // `api`) in :shared, so they don't propagate to consumers. Declare them here
    // so androidApp's Kotlin compilation resolves the symbols.
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.ktor3)
    implementation(libs.okio)
    implementation(libs.androidx.room.runtime)
    implementation(libs.ktor.client.core)
}
