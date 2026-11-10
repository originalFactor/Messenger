/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
@file:Suppress("DEPRECATION", "OPT_IN_USAGE", "UnstableApiUsage")

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Rebuild the pinned Maven sources with a narrow, fail-closed layout patch. No binary from the
// original library enters the app (which would otherwise duplicate every renderer class).
abstract class PatchTypewriterSources @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceArchives: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidArchive: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patches: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Internal abstract val androidRes: DirectoryProperty

    @TaskAction fun patch() {
        files.sync {
            into(output)
            sourceArchives.files.forEach { archive ->
                from(archives.zipTree(archive)) {
                    include("commonMain/**", "jvmMain/**", "androidMain/**")
                }
            }
            from(archives.zipTree(androidArchive.singleFile)) { include("res/**") }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
        val renderer = output.file("commonMain/cc/ptoe/llmtypewriter/TypewriterRenderer.kt").get().asFile
        var patched = renderer.readText()
        patches.get().asFile.listFiles()!!.filter { it.name.endsWith(".before.kt") }
            .sortedBy { it.name }.forEach { before ->
                val after = before.resolveSibling(before.name.replace(".before.kt", ".after.kt"))
                val old = before.readText()
                check(patched.split(old).size == 2) {
                    "llm-typewriter changed: review ${before.name} before upgrading"
                }
                patched = patched.replace(old, after.readText())
            }
        renderer.writeText(patched)
    }
}

val upstreamSources by configurations.creating { isTransitive = false }
val upstreamAndroid by configurations.creating { isTransitive = false }
val upstreamVersion = libs.versions.llmTypewriter.get()
dependencies {
    upstreamSources("cc.ptoe:llm-typewriter-jvm:$upstreamVersion:sources@jar")
    upstreamSources("cc.ptoe:llm-typewriter-android:$upstreamVersion:sources@jar")
    upstreamAndroid("cc.ptoe:llm-typewriter-android:$upstreamVersion@aar")
}
val patchedSources = tasks.register<PatchTypewriterSources>("patchTypewriterSources") {
    sourceArchives.from(upstreamSources)
    androidArchive.from(upstreamAndroid)
    patches.set(layout.projectDirectory.dir("patches"))
    output.set(layout.buildDirectory.dir("generated/upstream"))
    androidRes.set(output.dir("res"))
}

kotlin {
    androidLibrary {
        namespace = "cc.ptoe.llmtypewriter"
        compileSdk { version = release(37) }
        buildToolsVersion = rootProject.ext["buildToolsVersion"] as String
        minSdk = 30
        androidResources { enable = true }
    }
    jvm("desktop")
    sourceSets {
        commonMain {
            kotlin.srcDir(patchedSources.map { it.output.dir("commonMain") })
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.animation)
                api(compose.ui)
                api(libs.jetbrains.compose.material3)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.coil3.compose)
                implementation(libs.typewriter.highlights)
                implementation(libs.typewriter.ratex.asProvider().get().toString()) {
                    exclude(group = "org.jetbrains.skiko", module = "skiko-awt")
                    exclude(group = "org.jetbrains.skiko", module = "skiko")
                }
            }
        }
        getByName("androidMain") {
            kotlin.srcDir(patchedSources.map { it.output.dir("androidMain") })
            dependencies { implementation(libs.coil3.network.okhttp) }
        }
        getByName("desktopMain") {
            kotlin.srcDir(patchedSources.map { it.output.dir("jvmMain") })
            dependencies {
                implementation(libs.coil3.network.okhttp)
                runtimeOnly(libs.typewriter.ratex.windows)
                runtimeOnly(libs.typewriter.ratex.linux)
                runtimeOnly(libs.typewriter.ratex.linuxarm)
                runtimeOnly(libs.typewriter.ratex.macos)
                runtimeOnly(libs.typewriter.ratex.macosarm)
            }
        }
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(patchedSources) { it.androidRes }
    }
}
