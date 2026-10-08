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

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // PREFER_SETTINGS rather than FAIL_ON_PROJECT_REPOS: the Kotlin/Wasm
    // toolchain adds its own Node.js distribution repository to the project,
    // and a hard failure there blocks every wasmJs task. Settings repositories
    // still take precedence, so dependency resolution is unchanged.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // The Kotlin/Wasm toolchain resolves its pinned Node.js distribution
        // as org.nodejs:node:<version> from this ivy repo, and its Binaryen
        // (wasm-opt) release as com.github.webassembly:binaryen. Both are
        // plain release artifacts rather than Maven modules.
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "Messenger"
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
include(":wear")
include(":runtime")
include(":core-bindings")
// The bindings module lives under core/bindings to keep the Rust core and its
// Kotlin bridge side by side; Gradle's default path would be ./core-bindings.
project(":core-bindings").projectDir = file("core/bindings")
include(":renderer-android")
