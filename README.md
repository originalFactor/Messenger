<!-- Improved compatibility of back to top link: See: https://github.com/othneildrew/Best-README-Template/pull/73 -->
<a id="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![Apache License][license-shield]][license-url]

<br />
<div align="center">
  <a href="https://github.com/ECSDevs/Messenger">
    <img src="logo.svg" alt="Logo" width="80" height="80">
  </a>

  <h3 align="center">Messenger</h3>

  <p align="center">
    A beautifully designed, cross-platform AI chat app.
    <br />
    Chat with your favourite AI models on your phone, tablet, desktop, or Wear OS watch — Material 3 design, Bring Your Own Key, fully open-source and offline-capable.
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
    <li><a href="#usage">Usage</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About The Project

Messenger is a Material-3-designed, cross-platform LLM chat application built with Kotlin Multiplatform and Jetpack Compose. It is fully open-source, free, and offline-capable, using a BYOK (Bring Your Own Key) model.

Chat with your favourite AI models on your phone, tablet, or desktop — with a clean, modern interface that feels great to use. A shared Kotlin Multiplatform core keeps Android and Desktop in lockstep, and the Wear OS companion keeps chat simple by syncing your agents from mobile and using your phone as the configured AI backend.

Key highlights:

- **Chat anywhere** — Works on your phone, tablet, Wear OS watch, and desktop (JVM)
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
* [![Room][Room-badge]][Room-url]
* [![Retrofit][Retrofit-badge]][Retrofit-url]
* [![OkHttp][OkHttp-badge]][OkHttp-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites

* JDK 17
* Android SDK (Compile SDK 37, Target SDK 36, Min SDK 30)
* Gradle wrapper is included in the repository

### Installation

1. Clone the repo
   ```sh
   git clone https://github.com/ECSDevs/Messenger.git
   ```
2. Create a `local.properties` file in the project root pointing to your Android SDK
   ```properties
   sdk.dir=/path/to/android/sdk
   ```
3. Initialize submodules (the `llm-typewriter` source build is required)
   ```sh
   git submodule update --init --recursive
   ```
4. Build the debug APK / Desktop app
   ```sh
   ./gradlew :androidApp:assembleDebug     # ABI-specific arm64-v8a/armeabi-v7a/x86/x86_64 APKs
   ./gradlew :wear:assembleDebug           # Wear OS companion
   ./gradlew :desktopApp:run               # Desktop (JVM)
   ```
   Android builds download the pinned Termux bootstrap archives during the build and verify their SHA-256 digests before packaging them.
   Android release builds produce the same four ABI-specific APKs; place your keystore at `keyring/messenger-release.jks` and provide the environment variables `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Version code and name can be overridden with the `VERSION_CODE` and `VERSION_NAME` environment variables. Native Desktop distributions are built with `./gradlew :desktopApp:packageReleaseDmg` (macOS), `:desktopApp:packageReleaseMsi` (Windows), or `:desktopApp:packageReleaseDeb` (Linux).
6. (Optional) Change the git remote URL to avoid accidental pushes to the base project
   ```sh
   git remote set-url origin ECSDevs/Messenger
   git remote -v # confirm the changes
   ```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

1. Install Messenger on your phone, tablet, desktop, or Wear OS watch
2. Add your API key from your preferred AI provider
3. Pick a model and start chatting
4. Create custom agents for different tasks
5. Enable **Tools** for an agent when terminal access is needed. In manual mode, Messenger asks for confirmation before each command; Android runs accepted commands only from its app-private workspace.
6. (Optional) Install the Wear OS companion — it discovers your phone over the local network (NSD mDNS) and syncs your agents automatically over a WebSocket on TCP `18765`

Messenger speaks the OpenAI-compatible Chat Completions API, so any provider that exposes that interface works out of the box.

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

<a href="https://github.com/ECSDevs/Messenger/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ECSDevs/Messenger" alt="contrib.rocks image" />
</a>

## License

Distributed under the Apache License, Version 2.0. See `LICENSE` for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

ECSDevs - Project Link: [https://github.com/ECSDevs/Messenger](https://github.com/ECSDevs/Messenger)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

* [llm-typewriter](https://github.com/ECSDevs/llm-typewriter) — Progressive Markdown / LaTeX streaming renderer for AI bubbles
* [RaTeX](https://github.com/erweixin/RaTeX) / [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP) — Pure-Rust KaTeX-compatible LaTeX engine shared across Android and JVM Desktop
* [Best-README-Template](https://github.com/othneildrew/Best-README-Template) — README template
* [Jetpack Compose](https://developer.android.com/jetpack/compose)
* [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
* [Material 3](https://m3.material.io/)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
[contributors-shield]: https://img.shields.io/github/contributors/ECSDevs/Messenger.svg?style=for-the-badge
[contributors-url]: https://github.com/ECSDevs/Messenger/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/ECSDevs/Messenger.svg?style=for-the-badge
[forks-url]: https://github.com/ECSDevs/Messenger/network/members
[stars-shield]: https://img.shields.io/github/stars/ECSDevs/Messenger.svg?style=for-the-badge
[stars-url]: https://github.com/ECSDevs/Messenger/stargazers
[issues-shield]: https://img.shields.io/github/issues/ECSDevs/Messenger.svg?style=for-the-badge
[issues-url]: https://github.com/ECSDevs/Messenger/issues
[license-shield]: https://img.shields.io/github/license/ECSDevs/Messenger.svg?style=for-the-badge
[license-url]: https://github.com/ECSDevs/Messenger/blob/main/LICENSE

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
[Room-badge]: https://img.shields.io/badge/Room-6DB33F?style=for-the-badge&logo=sqlite&logoColor=white
[Room-url]: https://developer.android.com/jetpack/androidx/releases/room
[Retrofit-badge]: https://img.shields.io/badge/Retrofit-48B883?style=for-the-badge&logo=square&logoColor=white
[Retrofit-url]: https://square.github.io/retrofit/
[OkHttp-badge]: https://img.shields.io/badge/OkHttp-48B883?style=for-the-badge&logo=square&logoColor=white
[OkHttp-url]: https://square.github.io/okhttp/
