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

@file:Suppress("DEPRECATION", "OPT_IN_USAGE", "UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    androidLibrary {
        namespace = "cc.ptoe.messenger.shared"
        compileSdk {
            version = release(37)
        }
        minSdk = 30
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources { enable = true }

        // Run commonTest on the Android (JVM) host target as well; silences the
        // "commonTest source directory exists, but android host tests are not enabled" warning.
        withHostTest { }
    }

    jvm("desktop")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)

                implementation(libs.androidx.datastore.preferences)

                implementation(libs.jetbrains.navigation.compose)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.jetbrains.lifecycle.runtime.compose)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.kotlinx.serialization.json)

                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor3)

                implementation(libs.kotlinx.datetime)
                implementation(libs.okio)

                // The composite build (settings.gradle.kts includeBuild) substitutes
                // cc.ptoe:llm-typewriter:1.0 with the checked-out source build regardless
                // of the requested version. String notation is used because
                // KotlinDependencyHandler.implementation does not accept Provider + Action
                // in this Kotlin Gradle Plugin version.
                // Note: llm-typewriter previously dragged in guava-18.0 via AndroidMath;
                // that path is gone after the RaTeX migration (AndroidMath removed), so no
                // per-dependency guava exclude is needed here anymore.
                implementation("cc.ptoe:llm-typewriter:1.0")
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.okhttp)

                implementation(libs.java.websocket)

                implementation(libs.ucrop)
                implementation(libs.androidx.exifinterface)
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.transition)
            }
        }

        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.resources {
    packageOfResClass = "cc.ptoe.messenger.generated.resources"
    generateResClass = always
}

// The standalone com.google.guava:listenablefuture:1.0 stub (pulled transitively
// by androidx.concurrent:concurrent-futures via androidx.core) collides with the
// full Guava that KSP/Room compiler pulls in at build-time for
// com.google.common.collect.ImmutableList. Exclude only the stub globally so
// AGP's checkDebugDuplicateClasses passes while Guava stays on the KSP classpath.
// Note: llm-typewriter previously dragged in guava-18.0 via AndroidMath; that
// path is gone after the RaTeX migration (AndroidMath removed).
configurations {
    all {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
}

dependencies {
    // Room compiler: per-target KSP registration (AGP 9 KMP library plugin pattern).
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
