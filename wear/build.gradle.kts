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

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Shared wear ABI matrix (root build script): armeabi-v7a by default, x86_64
// selectable for AVD testing.
val wearAbis = rootProject.ext["wearAbis"] as List<String>

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

    }

    splits {
        abi {
            isEnable = true
            reset()
            // Default: armeabi-v7a only — Wear OS devices run a 64-bit kernel with
            // a 32-bit userspace, and that is the single ABI CI builds and
            // publishes. ext["wearAbis"] can add x86_64 for an AVD, which is the
            // only reason that ABI exists here (see the root build script).
            include(*wearAbis.toTypedArray())
            isUniversalApk = false
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
            val messengerKeystore = signingConfigs["messenger"]
            val hasPassword = messengerKeystore.storePassword?.isNotBlank() == true
            if (hasPassword && messengerKeystore.storeFile?.exists() == true) {
                signingConfig = messengerKeystore
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val messengerKeystore = signingConfigs["messenger"]
            val hasPassword = messengerKeystore.storePassword?.isNotBlank() == true
            if (hasPassword && messengerKeystore.storeFile?.exists() == true) {
                signingConfig = messengerKeystore
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation.mobile)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
