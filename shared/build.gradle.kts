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

    // Compose Multiplatform web target. The browser bundle's entry point lives in
    // :webApp (which calls `binaries.executable()`); this library target only needs
    // to compile, so no executable binary is declared here.
    wasmJs {
        browser()
        compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    }

    sourceSets {
        // Shared by Android + Desktop but NOT wasmJs: Room, DataStore-with-okio-paths,
        // okio's FileSystem.SYSTEM and the Room-backed repositories all live here.
        val jvmSharedMain by creating { dependsOn(getByName("commonMain")) }

        // Shared Android + Desktop tests. They are NOT in commonTest on
        // purpose: they use org.junit + runBlocking, which do not exist on
        // wasmJs, and a commonTest directory would force a wasmJs test
        // compilation of them.
        val jvmSharedTest by creating { dependsOn(getByName("commonTest")) }

        getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(libs.jetbrains.compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)

                implementation(libs.kotlinx.coroutines.core)

                implementation(libs.androidx.datastore.preferences.core)
                implementation(libs.androidx.datastore.core.okio)

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
            }
        }

        getByName("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(project(":core-bindings"))
                implementation(project(":renderer-android"))
                implementation(libs.androidx.recyclerview)
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

        getByName("desktopTest") { dependsOn(jvmSharedTest) }

        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
            }
        }

        // Room + bundled SQLite: JVM/native only (no wasmJs artifact exists).
        getByName("jvmSharedMain") {
            dependencies {
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
            }
        }

        getByName("wasmJsMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.jetbrains.compose.material3)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.okio)
                implementation(libs.kotlinx.browser)

                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor3)

                implementation(libs.jetbrains.navigation.compose)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
            }
        }

        getByName("desktopTest") {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(kotlin("test"))
            }
        }

        jvmSharedTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "cc.ptoe.messenger.generated.resources"
    generateResClass = always
}

dependencies {
    // Room compiler: per-target KSP registration (AGP 9 KMP library plugin pattern).
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
