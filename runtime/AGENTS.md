# runtime — companion shell-runtime app (`cc.ptoe.messenger.runtime`)

The agent-tool host and terminal UI for Android. Runs under its OWN UID with **targetSdk 28** (minSdk 28) so its process stays in the legacy `untrusted_app_27` SELinux domain.

## Why it exists (do not regress)

- Android 10+ SELinux W^X strips `execute`/`execute_no_trans` on `app_data_file` from the `untrusted_app` domain that targetSdk 29+ apps run in — the main app (targetSdk 36) can never exec binaries inside its data directory. The companion's deliberate targetSdk 28 keeps exec rights (verified on-device: the main app's domain gets `denied { execute_no_trans }`).
- **`sharedUserId` with the main app is BANNED**: PackageManager derives seInfo's targetSdk from the highest-targetSdk member at install time, so installing the companion after the main app silently pulls it into the modern domain (install-order dependent, silently broken). `run-as` tests are also misleading (`runas_app` retains exec rights).
- The deliberate targetSdk disables lint's `ExpiredTargetSdkVersion` fatal check in `runtime/build.gradle.kts` (else every `lintVital` fails).

## Design

- **Pinned Termux bootstrap**: `splits.abi` (arm64-v8a/armeabi-v7a/x86_64) downloads and SHA-256-verifies the pinned bootstrap at build time into `jniLibs/<abi>/libbootstrap.zip.so`, then `TermuxRuntime` extracts it atomically (staging + backup + version/ABI/hash marker; archive-path + symlink-graph validation in `RuntimePathPolicy` — symlink targets may be directories) into its OWN `files/agent-runtime/runtime-<abi>`.
- **Prefix rewriting**: the bootstrap bakes `/data/data/com.termux/files/usr` into its scripts; `patchTermuxPrefix` rewrites it to the extracted copy for `bin/`, `libexec/`, `lib/apt/methods/` (shebang-guarded) and `etc/`. Termux-app `etc/profile.d` hooks are dropped (`DROPPED_PROFILE_HOOKS` — they would run the second stage or fail on a foreign HOME). The install marker carries a `layout` generation (`LAYOUT_VERSION`) so old extractions are redone. `writePrefixOverrides` regenerates `etc/apt/apt.conf`, the CA bundle, and `etc/messenger.bashrc` on every ensure.
- **Workspace lives here too**: `files/agent-runtime/{runtime-<abi>,workspace}`. The main app cannot read another UID's files, so workspace file operations are brokered over AIDL.
- **Access control**: `ShellService` is protected by the signature-level `cc.ptoe.messenger.runtime.permission.SHELL` and every call re-checks it via `checkCallingPermission`.

## AIDL surface (`IShellService`/`IShellCallback`, copies duplicated in `androidApp`)

- `submit` / `cancel` — agent tool commands (per-request jobs; the binding must stay alive for the WHOLE request or the cached-app freezer can suspend the companion mid-work).
- `workspace{Glob,Grep,Read,Edit,Create}(root, …)` — synchronous, return a `ToolResult` Parcelable (`WorkspaceOps`). Every method takes a leading workspace `root`; a root that is not an existing directory is REJECTED (never silently fall back to the companion's own workspace — a project created elsewhere must report "unavailable").
- `startMcpProcess` / `sendMcpInput` / `stopMcpProcess` — stdio MCP for the main app's `McpManager`.
- No session API: the terminal UI is in-process (see below).

## Terminal UI

- The companion IS the user-facing terminal: `TerminalActivity` (launcher, `singleTask`, `adjustResize`) hosts the vendored Termux `TerminalView` + `ExtraKeysBar` (Termux default extra-keys layout, one-shot CTRL/ALT) with a toolbar (title + keyboard/paste).
- Sessions run the bootstrap's `bin/bash` over a real PTY (`cpp/termux.c` JNI, `libtermux.so`, built per-ABI via NDK `29.0.14206865` + CMake `3.22.1`). Spawned as argv `[<prefix>/bin/bash, --rcfile, <prefix>/etc/messenger.bashrc, -i]` — argv[0] MUST be the program name (the JNI hands the array to `execvp`; a leading `-` makes bash a login shell and dies with status 1). The generated rc replays Termux's login sequence against the extracted prefix. No policy filtering of user commands. Sessions die in `onDestroy`; tapping a finished session starts a new one.
- The main app opens it via `openRuntimeTerminal()` (explicit component intent); when the companion is absent the main app disables agent tools entirely (no fallback shell) and shows `terminal_not_installed`.

## Vendored Termux code (Apache-2.0, not GPL)

`src/main/java/com/termux/{terminal,view}` + `src/main/cpp/termux.c` are vendored from **termux-app v0.118.3** (`terminal-emulator` + `terminal-view` are Apache-2.0, exempt from upstream GPLv3; each file keeps its attribution header). Never copy `termux-shared` (that IS GPLv3). Only local change: the two `R` imports point at `cc.ptoe.messenger.runtime.R`. Re-vendoring = re-copy from the pinned tag + re-apply the R import + keep NDK/CMake flags in sync with `build.gradle.kts`.

## Other notes

- **ripgrep for the `grep` tool**: pre-built static binaries are committed as `src/main/jniLibs/<abi>/librg.so` (AGP strips them — `keepDebugSymbols += "**/librg.so"` keeps them byte-identical in the APK). `TermuxRuntime.ripgrepBinary` copies it to `<prefix>/bin/rg` (on PATH for terminal sessions too) without requiring the bootstrap install; `WorkspaceOps.grep` runs it with the workspace as cwd.
- AGP's `CANNOT_BUILD_SELECTED_TARGET_ABI` diagnostic is suppressed in `gradle.properties` (some native deps are ARM-only while the app keeps an x86_64 split for emulators).
- Distribution: users install the matching-ABI runtime APK alongside the main APK; CI attaches `runtime-*-release.apk` per ABI.
