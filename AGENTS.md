# Messenger AGENTS.md

Material 3 LLM chat app (Android-first, on-wrist focused), BYOK, offline-capable, fully open source. This file is the map plus the global rules. Per-module detail lives in each module's own `AGENTS.md` — read this first, then the module file you are touching.

## Modules

| Module | What it is | Details |
|---|---|---|
| `shared` | KMP library: shared Android/Desktop logic (Clean Architecture data/domain/presentation) | `shared/AGENTS.md` |
| `androidApp` | Android application shell | `androidApp/AGENTS.md` |
| `desktopApp` | Desktop (JVM) application shell | `desktopApp/AGENTS.md` |
| `tui` | Python/Textual terminal chat client for the local Rust Core | `tui/AGENTS.md` |
| `wear` | Wear OS chat-only companion | `wear/AGENTS.md` |
| `runtime` | Companion shell-runtime app (terminal UI + tool host) | `runtime/AGENTS.md` |
| `core/rust`, `core/bindings`, `core/bindings-desktop` | Rust Agent Core (Cargo workspace), `/api/v1` REST/SSE server, minimal native lifecycle bridge | `core/rust/AGENTS.md` |
| `server` | Next.js SaaS platform (website, web console, cloud sync, card-key billing, AI relay) | `server/AGENTS.md` |

- Package `cc.ptoe.messenger`; Kotlin + Jetpack Compose (Material 3).
- Apply this root file plus the instructions for the module being changed. For `core/bindings` and `core/bindings-desktop`, also read `core/rust/AGENTS.md` for the native lifecycle/build rules.
- `server/` is a git submodule — init with `git submodule update --init --recursive`.

## Commands

Unix Gradle launchers are pinned to LF by `.gitattributes` so Windows checkouts cannot break Linux shebangs.

Every local Gradle invocation MUST go through the `gradlew-cli` wrapper (`gradlew-cli.bat` on Windows), never bare `./gradlew`:

```bash
./gradlew-cli :androidApp:assembleDebug               # also :wear, :runtime (companion app)
./gradlew-cli :shared:allTests                        # Kotlin unit tests
./gradlew-cli :androidApp:lintDebug                   # lint
./gradlew-cli :desktopApp:run                         # Desktop app
./gradlew-cli :desktopApp:packageReleaseMsi           # native distribution (also Dmg/Deb)

cd core/rust && cargo test --workspace                 # Rust core + REST/SSE tests
cd core/rust && cargo test -p messenger-http           # API contract tests only

poetry -C tui install                                 # install locked Python dependencies
poetry -C tui run python workflow.py dev                # build and launch the terminal client
poetry -C tui run python workflow.py test               # Rust API + Python/Core + Textual tests
poetry -C tui run python workflow.py build              # build the local debug Core
poetry -C tui run python workflow.py release            # test and package for the host in tui/dist/

cd server && pnpm install && pnpm dev                  # server; checks: pnpm lint && pnpm typecheck
```

The wrapper runs Gradle with `--daemon` but under its own daemon registry (`~/.gradle/cli-daemon`), so a CLI build never queues behind Android Studio's busy daemon — that collision is why this repo used to mandate `--no-daemon`, which cost ~4 s of JVM start plus a lost VFS/Kotlin-daemon warm-up on every invocation. Do not use bare `./gradlew` for local builds, and do not add `--no-daemon`: with the isolated registry a warm daemon is both safe and ~4 s faster per run. If a cache-lock stall is ever observed anyway (both daemons share `~/.gradle/caches`), fall back to `./gradlew --no-daemon` for that one run and note it here.

Use the developer's regular Cargo cache for local builds. If sandboxed verification requires a temporary `CARGO_HOME`, pair it with a separate `CARGO_TARGET_DIR` and isolate any configured `CARGO_BUILD_BUILD_DIR` as well. Cargo fingerprints registry source paths, so sharing build artifacts across Cargo homes invalidates the developer's dependency cache.

Prerequisites: JDK 21 for the Gradle daemon (`gradle/gradle-daemon-jvm.properties` pins toolchain 21; Android/Wear CI bootstrap Java 17, Desktop CI Java 21), Android SDK, NDK `30.0.16248370` + CMake `4.1.2` + build-tools `37.0.0` on platform `android-37.0` (pinned in the root build script's `ext` block; `android.builder.sdkDownload=false` in `gradle.properties` makes any other version a hard failure instead of an auto-download), Rust toolchain with `cargo-ndk` for the Android native core. `llm-typewriter` is resolved from Maven Central by Android/Desktop builds.

## Hard Constraints

These MUST be followed at all times:

1. **Navigation route syntax**: `ProviderEdit` / `AgentEdit` routes use optional-parameter syntax (`provider_edit?providerId={providerId}`), never required-parameter syntax.
2. **Default Agent invariant**: the database always contains exactly one Agent with `isDefault=true`; it cannot be deleted.
3. **Model-required chat flow**: when an Agent's `defaultModelId` is null, `sendMessage` / `retrySend` / `regenerateMessage` prompt the user to set a model and abort — no message insert, no status change, no deletion.
4. **Error visibility**: API errors are NEVER silently retried; surface them through both a snackbar and an AI message bubble with details.
5. **IME behavior**: the chat screen keeps `android:windowSoftInputMode="adjustResize"` in the manifest.
6. **Edge-to-edge**: `MainActivity.onCreate` calls `enableEdgeToEdge()` before Compose content; `Theme.android.kt` owns system-bar colors/icon contrast. `MainScaffold` uses zero content window insets and applies/consumes its navigation-slot padding; each screen handles its own top/left/right insets via Scaffold + TopAppBar.
7. **AGP 9 built-in Kotlin**: never apply `org.jetbrains.kotlin.android` in Android modules; prefer KSP over kapt. The `shared` / `androidApp` / `desktopApp` split is irreversible (AGP 9.5.0 forbids `com.android.application` + KMP in one module). R8 runs on EVERY release build (`:androidApp`, `:wear`, `:runtime`), and Compose Desktop's ProGuard step runs on `:desktopApp` release too.
8. **Wear scope**: `wear` stays chat-only — provider/model/agent/settings management stays on the phone. `wear` is NOT a KMP module.
9. **AGENTS.md currency**: structural changes (new module/layer/entity/repository/feature, changed constraints or conventions, build or CI changes) must update the relevant AGENTS.md in the same commit.
10. **Local Core API**: Android/Desktop/TUI business calls go through Rust's `/api/v1` REST/SSE service. It binds loopback only and requires the per-start bearer token; the lifecycle bridges are UniFFI (`CoreHandle.open`, `startHttpServer`, `HttpServerHandle.stop`) and a stdio child process for the TUI. Keep `core/rust/crates/messenger-http/openapi.yaml` and `core/rust/crates/messenger-http/docs/SSE.md` current with route/event changes.
11. **Rendering ownership**: Android/Desktop Markdown, code highlighting and LaTeX are rendered in Kotlin with `cc.ptoe:llm-typewriter`; do not reintroduce native Document/Markdown/LaTeX rendering exits for those clients. There is no Rust Document/Markdown/LaTeX renderer; Android/Desktop use Kotlin `llm-typewriter`.
12. **JavaScript package manager**: This repository and all Git submodules must always use and support pnpm exclusively. Use pnpm for JavaScript setup, dependency management, script execution, builds, tests, and packaging. Commit only pnpm lockfiles for JavaScript projects. Never add npm, npx, Yarn, or Bun commands, lockfiles, or compatibility paths.

## Conventions

- **Versioning (one scheme across clients)**: version name defaults to repo-root `VERSION` (`MAJOR.MINOR.PATCH`), read by root `build.gradle.kts` and `messenger-core/build.rs`; Gradle rejects a missing/invalid VERSION unless `VERSION_NAME` overrides it. Standalone Rust currently accepts any non-empty version text and warns/falls back to `0.0.0` for an absent/empty file; this is not release validation. Gradle supports explicit `VERSION_NAME` / `VERSION_CODE` overrides (environment or root `.env`); Rust still reads `VERSION`, so do not use overrides for a cross-client release. Version code defaults to `git rev-list --count HEAD` (Gradle fallback 1, Rust fallback 0 when git is unavailable). The TUI reads the product version from Core’s `/api/v1/meta`. Release tags `vX.Y.Z` MUST equal `VERSION` (`release.yml` fails otherwise). Release via `./release.ps1 -Bump patch|minor|major` (bumps, commits, tags, pushes; `-NoPush`/`-WhatIf` available). Desktop MSI packages use `MAJOR.MINOR.<commitCount>` (jpackage needs a strictly increasing numeric product version).
- **Dependencies**: Gradle dependency versions go through `gradle/libs.versions.toml`. The Python TUI uses Poetry and `tui/poetry.lock`. Never globally exclude `com.google.guava:listenablefuture` — it is the runtime `ListenableFuture` provider that `androidx.profileinstaller` needs (excluding it crashes `:wear` at launch). JitPack is kept only for `ucrop`.
- **API error handling**: native Cloud calls are mapped by Rust to local `/api/v1` failures carrying `{code,message,detail}`; `RestCoreBridge` surfaces the HTTP status plus detail/message (or raw body). The retained direct Ktor client uses `ResponseException` (`expectSuccess = true`); the former Retrofit `HttpException` / `extractHttpErrorMessage()` path is gone. Never silently retry.
- **Cloud session expiry**: the cloud server owns session state, so a `401` on an authenticated cloud call is terminal — the app drops the local session and the user signs in again (see `shared/AGENTS.md` → "Server-rejected sessions sign the app out"). Never treat it as a retryable error.
- **SSE stream handling**: chat streams carry a `hasFinished` flag; if the stream ends without `Finished`/`Cancelled`/`Error`, emit `stream_ended` proactively. A client disconnect cancels the turn and any pending tool call.
- **Screens**: Material 3 `Scaffold` + `TopAppBar`; never add top-level padding in screens; avoid nested Scaffold double-inset issues.
- **Code style**: idiomatic Kotlin; Material 3; `collectAsState()` for Flows in Compose; `rememberCoroutineScope()` for launching from composables; composable `Modifier` parameters default to `Modifier`; descriptive names.
- **Git workflow**: main branch `main`; commit each completed task locally with a clear message (what + why); push only when explicitly requested. For submodule-only changes, commit inside the affected submodule only; do not create a main-repository commit solely to update the submodule pointer.
- **CI** (`.github/workflows/`): `ci.yml` (push/PR, path-filtered to code and build files) and `release.yml` (tag `v*`, verifies tag vs `VERSION`) both fan into `build-all.yml`, which calls the three reusable Android, Wear, and Desktop workflows in parallel. Build matrix: **android** — `:androidApp` + `:runtime` release APKs for `arm64-v8a` only; **wear** — `armeabi-v7a` only; **desktop** — Windows x86_64 (MSI) only; the Windows ARM64 app image and the Linux `.deb` entries are present but commented out. The Desktop matrix uses `fail-fast: false`; Android and Wear each run a single job. Root docs and `server/**` do NOT trigger CI, but module-local docs under the matched module globs DO. `tui/**` alone does not trigger CI and the reusable workflows do not run the TUI workflow or `cargo test`. The keystore is materialized from `KEYSTORE_BASE64` on push builds only.
- **R8 / ProGuard**: every release build is minified — `:androidApp`, `:wear`, `:runtime` run R8 with resource shrinking, and `:desktopApp` runs ProGuard (shrink + optimize, no obfuscation) inside `packageRelease*`. Keep each module's `proguard-rules.pro` tight whenever reflective/JNI/serialized/manifest entry points change, and do NOT run release builds for ordinary tasks (slow, high cost). The investigation procedure lives in `wear/AGENTS.md`.
- **Android ABI matrix**: `:androidApp`/`:runtime` filter ABIs per build type with `ndk.abiFilters` — release ships `arm64-v8a` only (`ext["releaseAbis"]`, `-Pmessenger.abis` / `ANDROID_ABIS`), debug ships `x86_64` only (`ext["debugAbis"]`, `-Pmessenger.debugAbis` / `ANDROID_DEBUG_ABIS`) because Android Studio Run installs the debug variant on an x86_64 AVD and a Rust cross-compile costs minutes: the two build types share no ABI, so each compiles exactly one target. `splits.abi` is NOT used (splits are global and AGP rejects an `abiFilters` that contradicts them); `abiFilters` live on each build type, never in `defaultConfig`, because AGP UNIONS `defaultConfig`'s list into every build type. `:wear` mirrors this: `ext["wearReleaseAbis"]` = `armeabi-v7a` (Wear OS = 64-bit kernel / 32-bit userspace), `ext["wearDebugAbis"]` = `x86_64` (Wear OS AVDs).

## Key files

- `VERSION` — semantic version name shared by all clients
- `release.ps1` — release entry point (bump, tag, push)
- `build.gradle.kts` / `settings.gradle.kts` / `gradle/libs.versions.toml` — version calculation, module includes, dependency catalog
- `core/rust/crates/messenger-http/openapi.yaml` / `core/rust/crates/messenger-http/docs/SSE.md` — local API and event contracts
- `.github/workflows/` — CI/CD (see Conventions)
