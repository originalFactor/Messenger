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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

// The per-ABI flavors keep producing split APKs for distribution; the shell
// runtime itself is the Android system shell, so nothing is bundled per-ABI.

// The guava/listenablefuture duplicate-class collision is resolved at :shared
// (per-dependency exclude of guava from llm-typewriter). androidApp inherits the
// cleaned runtime classpath transitively, plus the global listenablefuture stub
// exclude from the root build.gradle.kts as a safety net.
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.ktor.client.core)
}
