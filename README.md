<a id="readme-top"></a>

**English** | [简体中文](README.zh-CN.md)

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![Apache License][license-shield]][license-url]

<br />
<div align="center">
  <a href="https://github.com/originalFactor/Messenger">
    <img src="logo.svg" alt="Logo" width="80" height="80">
  </a>

  <h3 align="center">Messenger</h3>

  <p align="center">
    A beautifully designed, cross-platform AI chat app.
    <br />
  </p>
</div>

<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li>
      <a href="#usage">Usage</a>
      <ul>
        <li><a href="#local-rust-core">Local Rust core</a></li>
        <li><a href="#terminal-client-optional">Terminal client (optional)</a></li>
      </ul>
    </li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About The Project

Messenger is a Material-3-designed, cross-platform LLM chat application built with Kotlin Multiplatform and Jetpack Compose. It is fully open-source, free, and offline-capable, using a BYOK (Bring Your Own Key) model.

The same Rust agent core powers Android and desktop through a loopback REST/SSE API and a Wear OS companion through the authenticated phone bridge. Kotlin owns UI state, rendering and platform pickers; the Rust core owns storage, chat turns, tools, models and cloud synchronization.

Key highlights:

- **Bring your own key** — Use API keys from your preferred AI providers
- **Custom AI agents** — Create and switch between different AI personas and assistants
- **Terminal tools** — Agents can run commands through the consent-gated terminal tool. On Android, install the matching Messenger Runtime companion APK; it provides an app-private workspace and a pinned runtime, with no separate Termux installation required.
- **Multiple providers** — Connect to various OpenAI-compatible model providers in one app
- **Streaming responses** — SSE streaming with progressive Markdown and inline / display LaTeX math rendering (powered by RaTeX)
- **Modern design** — Clean Material 3 interface that's easy on the eyes, with adaptive layouts (bottom nav on phones, navigation rail + dual-pane on tablet/desktop)
- **Open source** — The client is free to use; AI providers and optional cloud services may charge for usage
- **Local storage** — Conversations are stored on your device; model requests go to your configured provider, and cloud synchronization is optional

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

* [![Kotlin][Kotlin-badge]][Kotlin-url]
* [![Jetpack Compose][Compose-badge]][Compose-url]
* [![Compose Multiplatform][CMP-badge]][CMP-url]
* [![Material 3][Material3-badge]][Material3-url]
* [![Wear Compose][Wear-badge]][Wear-url]
* [![SQLite][SQLite-badge]][SQLite-url]
* [![OkHttp][OkHttp-badge]][OkHttp-url]
* [![Rust][Rust-badge]][Rust-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

* JDK 21 (the Gradle daemon toolchain is pinned to Java 21)
* Android SDK: platform `android-37.0`, build-tools `37.0.0`, NDK `30.0.16248370`, and CMake `4.1.2` (phone/Wear apps target SDK 36 and require Android 11 / API 30 or newer)
* The repository includes the Gradle wrapper and the `gradlew-cli` launcher; on Windows, use `gradlew-cli.bat`
* Stable Rust toolchain for the Android/Desktop native core; Android builds also need `cargo-ndk` and the Rust targets for the selected ABIs

Linux Desktop builds also require `pkg-config`, GTK 3, and WebKitGTK 4.1 development packages (`libgtk-3-dev` and `libwebkit2gtk-4.1-dev` on Ubuntu). Windows interactive applications require the WebView2 Runtime. See [Desktop build notes](desktopApp/AGENTS.md).

### Installation

1. Clone the repo

   ```sh
   git clone https://github.com/originalFactor/Messenger.git
   cd Messenger
   ```

2. Create a `local.properties` file in the project root pointing to your Android SDK

   ```properties
   sdk.dir=/path/to/android/sdk
   ```

3. Initialize submodules if you also want to work on the optional cloud server (native client builds do not need it)

   ```sh
   git submodule update --init --recursive
   ```

4. Build the debug APK / Desktop app

   ```sh
   ./gradlew-cli :androidApp:assembleDebug     # x86_64 emulator APK
   ./gradlew-cli :runtime:assembleDebug        # x86_64 Runtime companion
   ./gradlew-cli :wear:assembleDebug           # x86_64 Wear OS emulator APK
   ./gradlew-cli :desktopApp:run               # Desktop (JVM)
   ```

   Runtime builds download the pinned Termux bootstrap archives and verify their SHA-256 digests before packaging them. Install the Runtime APK alongside the main Android APK to use terminal tools.

   Debug builds use `x86_64` for the phone, Runtime, and watch emulators. For a physical ARM64 phone, build both APKs with `./gradlew-cli :androidApp:assembleDebug :runtime:assembleDebug -Pmessenger.debugAbis=arm64-v8a`. For a physical Wear OS watch, use `./gradlew-cli :wear:assembleDebug -Pmessenger.wearDebugAbis=armeabi-v7a`.

   Release builds use `arm64-v8a` for Android/Runtime and `armeabi-v7a` for Wear OS. Phone/Runtime release ABIs can be overridden with `-Pmessenger.abis=armeabi-v7a` or `ANDROID_ABIS=armeabi-v7a`; debug ABIs use `-Pmessenger.debugAbis` or `ANDROID_DEBUG_ABIS`. Android release builds run R8 and resource shrinking. For release signing, place your keystore at `keyring/messenger-release.jks` and set `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

   Desktop installers are built on their target OS with `./gradlew-cli :desktopApp:packageReleaseDmg` (macOS), `:desktopApp:packageReleaseMsi` (Windows), or `:desktopApp:packageReleaseDeb` (Linux). Desktop release packaging runs ProGuard. On a Desktop-only build host without the pinned Android NDK, add `-Pmessenger.buildRuntimeNative=false` to Desktop Gradle commands.

   Android/Wear/Runtime and Desktop share the semantic version in the root `VERSION` file and use the git commit count as the version code. Desktop installers use `MAJOR.MINOR.<commitCount>` as their numeric package version. Gradle supports `VERSION_NAME` / `VERSION_CODE` overrides for local builds, but the Rust core still reads `VERSION`; use the file for releases that keep all clients on the same version.

5. Cutting a release

   ```sh
   ./release.ps1 -Bump patch     # 1.0.0 -> 1.0.1: bumps VERSION, commits it, tags v1.0.1, pushes
   ./release.ps1                 # tag and push the version already committed in VERSION
   ./release.ps1 -NoPush         # create the tag locally, inspect it, push later
   ```

   The push is what triggers the release build. The script refuses to tag when `VERSION` is uncommitted or differs from `HEAD` (a tag names a commit, so the mismatch would publish the wrong version), when the tree is dirty, or when the tag already exists locally or on origin; `-WhatIf` previews, and `-AllowDirty` tolerates unrelated local edits. `.github/workflows/release.yml` re-checks the tag against `VERSION` at the tagged commit and fails rather than publishing a mismatch.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

1. Install Messenger on your phone, tablet, or desktop
2. Add your API key from your preferred AI provider
3. Pick a model for your agent and start chatting
4. Create custom agents for different tasks
5. Enable **Tools** for an agent when terminal access is needed. On Android, install the matching Messenger Runtime companion APK. Commands execute in its app-private workspace using a pinned runtime; no separate Termux installation is required. Read-only mode (the default) exposes inspection commands only; switch an agent to writable mode to let it edit and create files in that workspace.
6. (Optional) Install the Wear OS companion — configure providers, models, and agents on the phone; the watch discovers your phone over the local network (NSD mDNS) and syncs your agents automatically over a WebSocket on TCP `18765`
7. (Optional) Sign in to a Messenger account to sync agents, providers and conversations across every client. Each client seeds its own local store and mirrors the account's data into it, so the same conversation is available on your phone and desktop.

Messenger supports providers that expose the OpenAI-compatible Chat Completions API. Available features depend on the provider and model.

Android/Desktop also include a local-first Learning workbench for courses, notes, mistakes, exercises, study plans, reviews, and generated interactive applications. See [LEARNING.md](LEARNING.md) for setup and usage.

### Local Rust core

Android, Desktop, and the terminal client start the Rust core on an ephemeral loopback port. Every
non-health request carries a per-start bearer token, and all business calls use
the versioned `/api/v1` HTTP API; chat turns and core change notifications use
SSE. The OpenAPI contract lives at
`core/rust/crates/messenger-http/openapi.yaml` and the SSE contract at
`core/rust/crates/messenger-http/docs/SSE.md`.

Markdown and LaTeX on Android/Desktop are rendered in Kotlin with
[`llm-typewriter`](https://github.com/originalFactor/llm-typewriter). The Rust
core owns agent execution, storage, tools, models and synchronization; it does
not contain a Rust document renderer.

### Terminal client (optional)

The Python TUI uses Textual for the command palette, composer, and conversation
view. It connects to the Rust Core API for model configuration, history, and
streaming chat.

From the repository root, with Python 3.11+, Poetry 2, and a current stable Rust toolchain:

```sh
poetry -C tui install
poetry -C tui run python workflow.py dev
poetry -C tui run python workflow.py test
poetry -C tui run python workflow.py build
poetry -C tui run python workflow.py release
```

The workflow reuses Cargo's regular build cache. Pass application options
after `--`, for example `poetry -C tui run python workflow.py dev -- --db ./test-chat.db`.
The example stores the test profile relative to `tui/`.

The release command bundles the optimized Core and Python dependencies into
a host archive under `tui/dist/`. Pass `--out PATH` to choose another directory.
Extract the archive and run `./messenger` on Linux/macOS or `.\messenger.cmd`
on Windows. The extracted client requires Python 3.11+. Run the launcher
with `--version` to check the packaged Core version.

Type `/` in the composer to open command suggestions and keep typing to filter
by prefix. Use the arrow keys to select a suggestion, Tab to fill it, or Enter
to run the highlighted command. Esc closes suggestions and keeps the draft.

Use `/providers` to search and manage providers. **Add provider** saves the
provider and automatically fetches and imports available models. For an
existing provider, open **Manage models** to fetch again, add a model manually,
edit it, enable or disable it, or delete it. Discovery imports new models and
refreshes metadata using the provider's extended `/models` response first and
pi-catalog for missing fields. Saved overrides survive refresh. The model editor
has **Fetch metadata** and supports display name, input modalities, reasoning
efforts, context window, max output tokens, and support for temperature, top_p,
and penalties. If fetching fails, you can still add models by ID.
Deleting a provider also deletes its models. Use `/connect` to add a provider
and choose a model in one flow. Enter sends a message,
Ctrl+O inserts a newline, and Esc cancels the current turn. Ctrl+P opens the
command palette. Use `/new`, `/sessions`, `/models`, and `/agents` to
start a session, resume one, change the model, or select an agent for a new
session. Use Page Up and Page Down to read older messages.
`/quit` and `/exit` both close the terminal client.

Messenger opens the closest local project containing your current directory.
If the directory is outside a project, choose **Trust and create project** to
create a project owned by this device, or **Continue without a project** to
start a standalone chat. New conversations belong to the active project, and
`/sessions` shows only the active project's conversations in project mode.
Use `--workspace PATH` to open a different directory.
In standalone mode, use `/project` to create a project or reopen one by its
folder. Inside a project, use `/project` to edit its name or workspace folder,
or select **Leave project** to start a standalone chat. The saved project and
its conversations remain available when you reopen it.

Use `/cloud` to sign in to Messenger Cloud, view the saved account, or sign out.
The dialog shows the configured Cloud base URL. Enter an HTTP(S) URL for a
self-hosted server and choose **Save address**, or sign in with that address.
Saving a different address signs out the current account first.
Signing in with a Cloud AI key automatically adds **Messenger Cloud AI** and
syncs its models from that server. Use `/models` to select a Cloud model, or
open the built-in entry in `/providers` to sync the catalog, view its read-only
Cloud metadata, or enable and disable individual models. Signing out removes the
Cloud provider and its models.

New standalone chats started while signed in upload to Messenger Cloud after
each turn. Run `/sync` to upload older local chats or retry an upload. Core
links uploaded chats to their Cloud server and account. TUI conversation sync
uploads local history and reports detected newer Cloud edits as conflicts.

The TUI stores chats, provider credentials, and its Cloud session in `~/.messenger/tui.db`.
Use `--db PATH` for a separate TUI profile and `--core PATH` for a custom
Core executable. Keep the TUI database separate from a running Desktop
instance. The TUI renders plain text and supports chat.

_For more details on architecture, conventions, and hard constraints, please refer to [AGENTS.md](AGENTS.md)_

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

If you have a suggestion that would make this better, please fork the repo and create a pull request. You can also simply open an issue with the tag "enhancement".

Don't forget to give the project a star! Thanks again!

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Top contributors

<a href="https://github.com/originalFactor/Messenger/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=originalFactor/Messenger" alt="contrib.rocks image" />
</a>

## License

Distributed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

originalFactor - Project Link: [https://github.com/originalFactor/Messenger](https://github.com/originalFactor/Messenger)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

* [RaTeX](https://github.com/erweixin/RaTeX) / [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP) — Pure-Rust KaTeX-compatible LaTeX engine shared across Android and JVM Desktop
* [Best-README-Template](https://github.com/othneildrew/Best-README-Template) — README template
* [Jetpack Compose](https://developer.android.com/jetpack/compose)
* [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
* [Material 3](https://m3.material.io/)
* [llm-typewriter](https://github.com/originalFactor/llm-typewriter) — streaming Markdown, code and LaTeX rendering on Android and Desktop
* [Axum](https://github.com/tokio-rs/axum) — versioned loopback HTTP/SSE API

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
[contributors-shield]: https://img.shields.io/github/contributors/originalFactor/Messenger.svg?style=for-the-badge
[contributors-url]: https://github.com/originalFactor/Messenger/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/originalFactor/Messenger.svg?style=for-the-badge
[forks-url]: https://github.com/originalFactor/Messenger/network/members
[stars-shield]: https://img.shields.io/github/stars/originalFactor/Messenger.svg?style=for-the-badge
[stars-url]: https://github.com/originalFactor/Messenger/stargazers
[issues-shield]: https://img.shields.io/github/issues/originalFactor/Messenger.svg?style=for-the-badge
[issues-url]: https://github.com/originalFactor/Messenger/issues
[license-shield]: https://img.shields.io/github/license/originalFactor/Messenger.svg?style=for-the-badge
[license-url]: https://github.com/originalFactor/Messenger/blob/main/LICENSE

[Kotlin-badge]: https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white
[Kotlin-url]: https://kotlinlang.org/
[Compose-badge]: https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white
[Compose-url]: https://developer.android.com/jetpack/compose
[CMP-badge]: https://img.shields.io/badge/Compose%20Multiplatform-7F52FF?style=for-the-badge&logo=jetpackcompose&logoColor=white
[CMP-url]: https://www.jetbrains.com/lp/compose-multiplatform/
[Material3-badge]: https://img.shields.io/badge/Material%203-7570D3?style=for-the-badge&logo=materialdesign&logoColor=white
[Material3-url]: https://m3.material.io/
[Wear-badge]: https://img.shields.io/badge/Wear%20OS-4285F4?style=for-the-badge&logo=wearos&logoColor=white
[Wear-url]: https://developer.android.com/training/wearables
[SQLite-badge]: https://img.shields.io/badge/SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white
[SQLite-url]: https://www.sqlite.org/
[Rust-badge]: https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white
[Rust-url]: https://www.rust-lang.org/
[OkHttp-badge]: https://img.shields.io/badge/OkHttp-48B883?style=for-the-badge&logo=square&logoColor=white
[OkHttp-url]: https://square.github.io/okhttp/
