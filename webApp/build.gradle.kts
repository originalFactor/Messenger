@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core is compiled to wasm32 outside Gradle's model; the Exec tasks
// below bridge the two, mirroring :core-bindings' cargo tasks. Whole-workspace
// input tracking matters for the same reason there: a sibling-crate edit must
// invalidate the wasm module, or Gradle silently ships a stale one.
val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("core/rust")
val wasmOutDir = layout.buildDirectory.dir("wasm")

val rustInputs = Action<Exec> {
    inputs.file(rustWorkspaceDir.file("Cargo.toml"))
    inputs.file(rustWorkspaceDir.file("Cargo.lock"))
    inputs.dir(rustWorkspaceDir.dir("crates"))
}

val rawWasm = rustWorkspaceDir.file("target/wasm32-unknown-unknown/release/messenger_wasm.wasm")

val buildRustWasm by tasks.registering(Exec::class) {
    workingDir(rustWorkspaceDir)
    commandLine(
        "cargo", "build", "--release", "--target", "wasm32-unknown-unknown",
        "-p", "messenger-wasm",
    )
    outputs.file(rawWasm)
    outputs.cacheIf { false }
    rustInputs.execute(this)
}

// wasm-bindgen turns the raw module into the JS glue the Kotlin bridge imports
// (`@JsModule("./wasm/messenger_wasm.js")`). Its output directory becomes a
// wasmJs resource root below, so `wasm/` lands next to the compiled Kotlin
// bundle and the relative module specifier resolves.
val generateWasmBindings by tasks.registering(Exec::class) {
    dependsOn(buildRustWasm)
    workingDir(rootProject.layout.projectDirectory)
    commandLine(
        "wasm-bindgen",
        "--target", "web",
        "--out-dir", wasmOutDir.get().asFile.absolutePath,
        "--out-name", "messenger_wasm",
        rawWasm.asFile.absolutePath,
    )
    // The raw module is an INPUT, not just a command-line argument: without
    // this the task stays up-to-date when only a Rust source changes, and
    // Gradle ships stale glue next to a fresh wasm binary.
    inputs.file(rawWasm)
    outputs.dir(wasmOutDir)
    outputs.cacheIf { false }
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "webApp.js" }
            // Cross-origin isolation (COOP/COEP) is required by Compose
            // Multiplatform's wasm renderer; the Kotlin DSL has no `headers`
            // field, so it is patched in through webpack.config.d (see
            // webApp/webpack.config.d).
        }
        binaries.executable()
    }

    sourceSets {
        getByName("wasmJsMain") {
            // Exposes `messenger_wasm.js` + `messenger_wasm_bg.wasm` to the
            // bundler; the alias for the bare specifier lives in the committed
            // webpack.config.d/02-wasm-bindings.js.
            resources.srcDir(wasmOutDir)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(libs.jetbrains.compose.material3)
                implementation(compose.components.resources)
                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor3)
                implementation(libs.okio)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// The Kotlin compile reads the generated glue through the resource root above.
tasks.matching { it.name.startsWith("wasmJs") && it.name.contains("Resources") }.configureEach {
    dependsOn(generateWasmBindings)
}

// Serves the production bundle from the cloud host itself: the client is
// same-origin with the API there, which is what lets it reuse the existing
// session cookie with no CORS configuration. The previous contents are removed
// first so renamed chunks do not linger.
val copyWebAppDistribution by tasks.registering(Sync::class) {
    dependsOn("wasmJsBrowserDistribution")
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    into(rootProject.layout.projectDirectory.dir("server/public/app"))
    // Sync deletes everything not present in the source, so the committed
    // placeholder is re-written afterwards to keep the directory present in a
    // fresh checkout. The build output itself is git-ignored.
    doLast {
        val keep = destinationDir.resolve(".gitkeep")
        if (!keep.exists()) {
            keep.writeText(
                "# The compiled Compose Multiplatform web client is copied here by\n" +
                    "# ':webApp:wasmJsBrowserDistribution' -> 'copyWebAppDistribution'.\n\n" +
                    "# The built output is git-ignored; this file only keeps the directory.\n"
            )
        }
    }
}
