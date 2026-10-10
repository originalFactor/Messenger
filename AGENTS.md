# Messenger AGENTS.md

Material 3 LLM chat app (Android-first, on-wrist focused), BYOK, offline-capable, fully open source. This file is the map plus the global rules. Per-module detail lives in each module's own `AGENTS.md` — read this first, then the module file you are touching.

## Modules

| Module | What it is | Details |
|---|---|---|
| `shared` | KMP library: all shared Android/Desktop/Web logic (Clean Architecture data/domain/presentation) | `shared/AGENTS.md` |
| `androidApp` | Android application shell | `androidApp/AGENTS.md` |
| `desktopApp` | Desktop (JVM) application shell | `desktopApp/AGENTS.md` |
| `webApp` | Compose Multiplatform Kotlin/Wasm browser shell | `webApp/AGENTS.md` |
| `wear` | Wear OS chat-only companion | `wear/AGENTS.md` |
| `runtime` | Companion shell-runtime app (terminal UI + tool host) | `runtime/AGENTS.md` |
| `core/rust`, `core/bindings` | Rust Agent Core (Cargo workspace), UniFFI bridge, native TUI | `core/rust/AGENTS.md` |
| `server` | Next.js SaaS platform (website, web console, cloud sync, card-key billing, AI relay) | `server/AGENTS.md` |

- Package `cc.ptoe.messenger`; Kotlin + Jetpack Compose (Material 3).
- `server/` is a git submodule — init with `git submodule update --init --recursive`.

## Commands

Every local Gradle invocation MUST use `--no-daemon` (the Android Studio daemon in the same Gradle user home deadlocks CLI builds; CI does not need the flag):

```bash
./gradlew --no-daemon :androidApp:assembleDebug        # also :wear, :runtime (companion app)
./gradlew --no-daemon :shared:allTests                 # Kotlin unit tests
./gradlew --no-daemon :androidApp:lintDebug            # lint
./gradlew --no-daemon :desktopApp:run                  # Desktop app
./gradlew --no-daemon :desktopApp:packageReleaseMsi    # native distribution (also Dmg/Deb)

cd core/rust && cargo test --workspace                 # Rust core + TUI tests
cd core/rust && cargo build -p messenger-tui           # TUI binary

cd server && pnpm install && pnpm dev                  # server; checks: pnpm lint && pnpm typecheck
```

Prerequisites: JDK 17, Android SDK, NDK `29.0.14206865` + CMake `3.22.1` (for `:runtime`'s PTY JNI; both pinned in `runtime/build.gradle.kts`), Rust toolchain with `cargo-ndk`, `wasm32-unknown-unknown`, and `wasm-bindgen-cli` (version pinned in `Cargo.lock`) for the core/Web paths.

## Hard Constraints

These MUST be followed at all times:

1. **Navigation route syntax**: `ProviderEdit` / `AgentEdit` routes use optional-parameter syntax (`provider_edit?providerId={providerId}`), never required-parameter syntax.
2. **Default Agent invariant**: the database always contains exactly one Agent with `isDefault=true`; it cannot be deleted.
3. **Model-required chat flow**: when an Agent's `defaultModelId` is null, `sendMessage` / `retrySend` / `regenerateMessage` prompt the user to set a model and abort — no message insert, no status change, no deletion.
4. **Error visibility**: API errors are NEVER silently retried; surface them through both a snackbar and an AI message bubble with details.
5. **IME behavior**: the chat screen keeps `android:windowSoftInputMode="adjustResize"` in the manifest.
6. **Edge-to-edge**: `Theme.kt` calls `setDecorFitsSystemWindows(window, false)`; `MainScaffold` applies only bottom padding (bottom nav); each screen handles its own top/left/right insets via Scaffold + TopAppBar.
7. **AGP 9 built-in Kotlin**: never apply `org.jetbrains.kotlin.android` in Android modules; prefer KSP over kapt. The `shared` / `androidApp` / `desktopApp` split is irreversible (AGP 9.3.1 forbids `com.android.application` + KMP in one module). R8 runs only on `:wear`; minification is off on `:androidApp` and `:desktopApp`.
8. **Wear scope**: `wear` stays chat-only — provider/model/agent/settings management stays on the phone. `wear` is NOT a KMP module.
9. **AGENTS.md currency**: structural changes (new module/layer/entity/repository/feature, changed constraints or conventions, build or CI changes) must update the relevant AGENTS.md in the same commit.

## Conventions

- **Versioning (one scheme for all five clients)**: version name = repo-root `VERSION` file (`MAJOR.MINOR.PATCH`, the single source of truth, read only by the root `build.gradle.kts` and `messenger-core/build.rs`; missing/invalid VERSION fails the build); version code = `git rev-list --count HEAD`. Release tags `vX.Y.Z` MUST equal `VERSION` (`release.yml` fails otherwise). Release via `./release.ps1 -Bump patch|minor|major` (bumps, commits, tags, pushes; `-NoPush`/`-WhatIf` available). Desktop MSI packages use `MAJOR.MINOR.<commitCount>` (jpackage needs a strictly increasing numeric product version).
- **Dependencies**: all versions go through `gradle/libs.versions.toml`. Never globally exclude `com.google.guava:listenablefuture` — it is the runtime `ListenableFuture` provider that `androidx.profileinstaller` needs (excluding it crashes `:wear` at launch). JitPack is kept only for `ucrop`.
- **API error handling**: handle `HttpException`; extract the message via `extractHttpErrorMessage()` from `error.message`.
- **SSE stream handling**: stream processing carries a `hasFinished` flag; if the stream ends without Done/Error, emit an Error proactively.
- **Screens**: Material 3 `Scaffold` + `TopAppBar`; never add top-level padding in screens; avoid nested Scaffold double-inset issues.
- **Code style**: idiomatic Kotlin; Material 3; `collectAsState()` for Flows in Compose; `rememberCoroutineScope()` for launching from composables; composable `Modifier` parameters default to `Modifier`; descriptive names.
- **Git workflow**: main branch `main`; commit each completed task locally with a clear message (what + why); push only when explicitly requested.
- **CI** (`.github/workflows/`): `ci.yml` (push/PR, path-filtered to code and build files) and `release.yml` (tag `v*`, verifies tag vs `VERSION`) both fan into `build-all.yml`, which calls the five reusable `build-*.yml` workflows in parallel (android / wear / desktop / web / tui). Docs (`AGENTS.md`, `README.md`) and `server/**` do NOT trigger CI. The keystore is materialized from `KEYSTORE_BASE64` on push builds only.
- **R8**: only `:wear` is minified. Keep `wear/proguard-rules.pro` tight whenever reflective/serialized/manifest entry points change, and do NOT run R8 builds for ordinary tasks (slow, high cost). The investigation procedure lives in `wear/AGENTS.md`.

## Key files

- `VERSION` — semantic version name shared by all clients
- `release.ps1` — release entry point (bump, tag, push)
- `build.gradle.kts` / `settings.gradle.kts` / `gradle/libs.versions.toml` — version calculation, module includes, dependency catalog
- `.github/workflows/` — CI/CD (see Conventions)
