<!-- Improved compatibility of back to top link: See: https://github.com/othneildrew/Best-README-Template/pull/73 -->
<a id="readme-top"></a>

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
    Chat with your favourite AI models on your phone, tablet, desktop, Wear OS watch, browser, or terminal — Material 3 design, Bring Your Own Key, fully open-source and offline-capable.
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
        <li><a href="#browser-optional">Browser (optional)</a></li>
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

The same Rust agent core powers Android and desktop through a loopback REST/SSE API, a Wear OS companion through the authenticated phone bridge, and a browser client compiled to WebAssembly. Chat with your favourite AI models wherever you are — with a clean, modern interface that feels great to use. Kotlin owns UI state, rendering and platform pickers; the Rust core owns storage, chat turns, tools, models and cloud synchronization.

Key highlights:

- **Chat anywhere** — Works on your phone, tablet, Wear OS watch, desktop (JVM), and in your browser
- **In your browser too** — the web client is the same Compose UI compiled to WebAssembly on the Rust core, served from the account server at `/app`; sign in and your agents, providers and conversations sync like everywhere else
- **Bring your own key** — Use API keys from your preferred AI providers, no middleman
- **Custom AI agents** — Create and switch between different AI personas and assistants
- **Built-in terminal tool** — Agents can run model-requested commands through the consent-gated terminal tool. On Android, commands execute inside an app-private workspace using the pinned runtime packaged for the device ABI; no separate Termux installation is required.
- **Multiple providers** — Connect to various OpenAI-compatible model providers in one app
- **Streaming responses** — SSE streaming with progressive Markdown and inline / display LaTeX math rendering (powered by RaTeX)
- **Modern design** — Clean Material 3 interface that's easy on the eyes, with adaptive layouts (bottom nav on phones, navigation rail + dual-pane on tablet/desktop)
- **Open source & free** — Fully open-source, no subscriptions, no hidden costs
- **Privacy first** — Your conversations stay on your device

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
* [![Rust / Kotlin-Wasm][Wasm-badge]][Wasm-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

* JDK 17
* Android SDK (Compile SDK 37, Target SDK 36, Min SDK 30)
* Gradle wrapper is included in the repository
* Rust toolchain (Android/Desktop native core and the browser build)
  ```sh
  rustup target add wasm32-unknown-unknown                            # browser build
  cargo install wasm-bindgen-cli --version 0.2.129 --locked            # must match core/rust/Cargo.lock
  ```

### Installation

1. Clone the repo
   ```sh
   git clone https://github.com/originalFactor/Messenger.git
   ```
2. Create a `local.properties` file in the project root pointing to your Android SDK
   ```properties
   sdk.dir=/path/to/android/sdk
   ```
3. Initialize submodules
   ```sh
   git submodule update --init --recursive
   ```
4. Build the debug APK / Desktop app
   ```sh
   ./gradlew :androidApp:assembleDebug     # arm64-v8a APK
   ./gradlew :wear:assembleDebug           # Wear OS companion (armeabi-v7a)
   ./gradlew :desktopApp:run               # Desktop (JVM)
   ```
   Android builds download the pinned Termux bootstrap archives during the build and verify their SHA-256 digests before packaging them.
   Android release builds are minified (R8 + resource shrinking) and ship the `arm64-v8a` APK; the 32-bit `armeabi-v7a` and emulator `x86_64` ABIs are opt-in with `-Pmessenger.abis=armeabi-v7a` (or `ANDROID_ABIS=…`). Debug builds ship `x86_64` only (phone apps and watch app), so Android Studio Run installs on an AVD with no flags and never pays for the release ABI's Rust cross-compile; release stays `arm64-v8a` (phone) / `armeabi-v7a` (wear). Place your keystore at `keyring/messenger-release.jks` and provide the environment variables `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Native Desktop distributions are built with `./gradlew :desktopApp:packageReleaseDmg` (macOS), `:desktopApp:packageReleaseMsi` (Windows), or `:desktopApp:packageReleaseDeb` (Linux); the release build runs ProGuard.
   Android/Wear/Runtime, Desktop and Web all report the same version: the semantic version name in the repository-root `VERSION` file plus the git commit count as the version code (the Desktop MSI uses `MAJOR.MINOR.<commitCount>`, since native packages need a numeric, increasing product version). For a one-off local build both can be overridden with the `VERSION_NAME` / `VERSION_CODE` environment variables.
5. Build the browser client (optional)
   ```sh
   ./gradlew :webApp:wasmJsBrowserDevelopmentRun   # browser dev server (COOP/COEP on)
   ./gradlew :webApp:wasmJsBrowserDistribution     # production bundle under webApp/build/dist
   ./gradlew :webApp:copyWebAppDistribution        # local: serve it from server/public/app/
   ```
   The browser build compiles the Rust core to WebAssembly and generates the bindings automatically (the pinned `wasm-bindgen` CLI from the Prerequisites is what it invokes). The compiled bundle is not committed: CI uploads it as the `web-distribution` artifact on every successful run (`.github/workflows/ci.yml` → `build-all.yml` → `build-web.yml`), and the account server (`server/`) downloads it through nightly.link and serves it at `/app` during `pnpm build`. To try it against your own server checkout, run `./gradlew :webApp:copyWebAppDistribution` so the bundle lands in `server/public/app/`, then start the server with `pnpm dev`.
6. Cutting a release
   ```sh
   ./release.ps1 -Bump patch     # 1.0.0 -> 1.0.1: bumps VERSION, commits it, tags v1.0.1, pushes
   ./release.ps1                 # tag and push the version already committed in VERSION
   ./release.ps1 -NoPush         # create the tag locally, inspect it, push later
   ```
   The push is what triggers the release build. The script refuses to tag when `VERSION` is uncommitted or differs from `HEAD` (a tag names a commit, so the mismatch would publish the wrong version), when the tree is dirty, or when the tag already exists locally or on origin; `-WhatIf` previews, and `-AllowDirty` tolerates unrelated local edits. `.github/workflows/release.yml` re-checks the tag against `VERSION` at the tagged commit and fails rather than publishing a mismatch.
7. (Optional) Change the git remote URL to avoid accidental pushes to the base project
   ```sh
   git remote set-url origin originalFactor/Messenger
   git remote -v # confirm the changes
   ```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

1. Install Messenger on your phone, tablet, desktop, Wear OS watch, or browser
2. Add your API key from your preferred AI provider
3. Pick a model and start chatting
4. Create custom agents for different tasks
5. Enable **Tools** for an agent when terminal access is needed. On Android, terminal commands execute inside an app-private workspace using the pinned runtime packaged for the device ABI — no separate Termux installation is required. Read-only mode (the default) exposes inspection commands only; switch an agent to writable mode to let it edit and create files in that workspace.
6. (Optional) Install the Wear OS companion — it discovers your phone over the local network (NSD mDNS) and syncs your agents automatically over a WebSocket on TCP `18765`
7. (Optional) Sign in to a Messenger account to sync agents, providers and conversations across every client. Each client seeds its own local store and mirrors the account's data into it, so the same conversation is available on your phone, on your desktop and in your browser.

Messenger speaks the OpenAI-compatible Chat Completions API, so any provider that exposes that interface works out of the box.

### Browser (optional)

The browser client is the same UI as the desktop app, compiled to WebAssembly and backed by the same Rust agent core (its SQLite store is persisted in the browser's origin-private file system). The production bundle is served by the account server at `/app` alongside the website and console — sign in there and your agents, providers and conversations sync like everywhere else. Because it runs in a browser sandbox it has no shell or filesystem access, so tools are disabled there (remote MCP servers over SSE still work); the phone and desktop clients are the ones with tools.

### Local Rust core

Android and Desktop start the Rust core on an ephemeral loopback port. Every
non-health request carries a per-start bearer token, and all business calls use
the versioned `/api/v1` HTTP API; chat turns and core change notifications use
SSE. The OpenAPI contract lives at
`core/rust/crates/messenger-http/openapi.yaml` and the SSE contract at
`core/rust/crates/messenger-http/docs/SSE.md`.

Markdown and LaTeX on Android/Desktop are rendered in Kotlin with
[`llm-typewriter`](https://github.com/originalFactor/llm-typewriter). Web keeps
its existing wasm-bindgen boundary for this migration phase.

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

Distributed under the Apache License, Version 2.0. See `LICENSE` for more information.

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
* [syntect](https://github.com/trishume/syntect) — Sublime Text syntax engine behind code highlighting in all clients
* [wasm-bindgen](https://rustwasm.github.io/wasm-bindgen/) — Rust ↔ JavaScript boundary for the browser client

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
[Wasm-badge]: https://img.shields.io/badge/Kotlin%2Fwasm-2BDE21?style=for-the-badge&logo=kotlin&logoColor=white
[Wasm-url]: https://kotlinlang.org/docs/multiplatform.html
