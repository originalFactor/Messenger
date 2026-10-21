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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Shared ABI matrix (root build script): release ships arm64-v8a, debug adds
// x86_64 so Android Studio Run works on an AVD.
val releaseAbis = rootProject.ext["releaseAbis"] as List<String>
val debugAbis = rootProject.ext["debugAbis"] as List<String>

android {
    namespace = "cc.ptoe.messenger"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = rootProject.ext["buildToolsVersion"] as String

    defaultConfig {
        applicationId = "cc.ptoe.messenger"
        minSdk = 30
        targetSdk = 36
        versionCode = rootProject.ext["versionCode"] as Int
        versionName = rootProject.ext["versionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Per-build-type ABI filtering, not splits.abi: splits are global and
        // cannot be scoped to release only, which left debug — the variant
        // Android Studio Run installs — arm64-only and unusable on an AVD.
        ndk { abiFilters.addAll(releaseAbis) }
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
            ndk { abiFilters.addAll(debugAbis) }
            val k = signingConfigs["messenger"]
            if (k.storePassword?.isNotBlank() == true && k.storeFile?.exists() == true) {
                signingConfig = k
            }
        }
        release {
            // Full R8 on every release build (all Android modules): minification
            // plus resource shrinking. Rules live in proguard-rules.pro next to
            // this file; shared/android libraries contribute consumer rules
            // (:core-bindings, :renderer-android).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val k = signingConfigs["messenger"]
            if (k.storePassword?.isNotBlank() == true && k.storeFile?.exists() == true) {
                signingConfig = k
            }
        }
    }

    buildFeatures {
        compose = true
        aidl = true
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

// Per-ABI split APKs are produced via splits.abi for distribution.

dependencies {
    implementation(project(":shared"))
    implementation(project(":core-bindings"))
    implementation(project(":renderer-android"))
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
    implementation(libs.ktor.client.core)
}
