<a id="readme-top"></a>

[English](README.md) | **简体中文**

[![贡献者][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stars][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![Apache 许可证][license-shield]][license-url]

<br />
<div align="center">
  <a href="https://github.com/originalFactor/Messenger">
    <img src="logo.svg" alt="Messenger 标志" width="80" height="80">
  </a>
  <h3 align="center">Messenger</h3>
  <p align="center">设计精美的跨平台 AI 聊天应用。</p>
</div>

<details>
  <summary>目录</summary>
  <ol>
    <li><a href="#about">项目介绍</a>
      <ul><li><a href="#built-with">技术栈</a></li></ul>
    </li>
    <li><a href="#getting-started">开始使用</a>
      <ul>
        <li><a href="#prerequisites">环境要求</a></li>
        <li><a href="#installation">安装与构建</a></li>
      </ul>
    </li>
    <li><a href="#usage">使用方法</a>
      <ul>
        <li><a href="#local-core">本地 Rust Core</a></li>
        <li><a href="#terminal-client">终端客户端（可选）</a></li>
      </ul>
    </li>
    <li><a href="#contributing">参与贡献</a></li>
    <li><a href="#license">许可证</a></li>
    <li><a href="#contact">联系与项目地址</a></li>
    <li><a href="#acknowledgments">致谢</a></li>
  </ol>
</details>

<a id="about"></a>

## 项目介绍

Messenger 是一款采用 Material 3 设计的跨平台大语言模型聊天应用，基于 Kotlin Multiplatform 和 Jetpack Compose 构建。项目完全开源，客户端免费，支持离线使用本地数据，并采用 BYOK（Bring Your Own Key，自备 API 密钥）模式。

Android 和桌面端通过本地回环 REST/SSE API 使用同一个 Rust Agent Core；Wear OS 配套应用则通过经过身份验证的手机桥接服务连接。Kotlin 负责界面状态、内容渲染和平台文件选择器；Rust Core 负责存储、对话执行、工具、模型和云同步。

主要特性：

- **自备密钥**：使用你选择的 AI 服务商的 API 密钥。
- **自定义智能体**：创建并切换不同角色和用途的 AI 助手。
- **终端工具**：智能体可在用户授权后执行命令。Android 需要安装匹配的 Messenger Runtime 配套 APK，由它提供应用私有工作区和固定版本的运行环境，无需单独安装 Termux。
- **多服务商支持**：在一个应用中连接多个兼容 OpenAI API 的模型服务商。
- **流式回复**：通过 SSE 实时接收回复，逐步渲染 Markdown，以及由 RaTeX 支持的行内和独立公式。
- **现代界面**：采用 Material 3 和自适应布局；手机使用底部导航，平板和桌面使用侧边导航栏与双栏布局。
- **开源客户端**：客户端可免费使用；AI 服务商和可选云服务可能收取使用费用。
- **本地存储**：对话保存在设备上；模型请求发送给配置的服务商，云同步按需启用。

<p align="right">(<a href="#readme-top">返回顶部</a>)</p>

<a id="built-with"></a>

### 技术栈

* [![Kotlin][Kotlin-badge]][Kotlin-url]
* [![Jetpack Compose][Compose-badge]][Compose-url]
* [![Compose Multiplatform][CMP-badge]][CMP-url]
* [![Material 3][Material3-badge]][Material3-url]
* [![Wear Compose][Wear-badge]][Wear-url]
* [![SQLite][SQLite-badge]][SQLite-url]
* [![OkHttp][OkHttp-badge]][OkHttp-url]
* [![Rust][Rust-badge]][Rust-url]

<a id="getting-started"></a>

## 开始使用

按照以下步骤在本地构建和运行项目。

<a id="prerequisites"></a>

### 环境要求

* JDK 21（Gradle 守护进程的工具链固定为 Java 21）。
* Android SDK：平台 `android-37.0`、build-tools `37.0.0`、NDK `30.0.16248370` 和 CMake `4.1.2`。手机和 Wear 应用的目标 SDK 为 36，最低要求 Android 11 / API 30。
* 仓库已提供 Gradle wrapper 和 `gradlew-cli` 启动脚本；Windows 使用 `gradlew-cli.bat`。
* 稳定版 Rust 工具链，用于 Android/桌面原生 Core。Android 构建还需要 `cargo-ndk` 以及所选 ABI 对应的 Rust 编译目标。

Linux 桌面构建还需要 `pkg-config`、GTK 3 和 WebKitGTK 4.1 开发包（Ubuntu 上为 `libgtk-3-dev` 和 `libwebkit2gtk-4.1-dev`）。Windows 上的交互式应用需要 WebView2 Runtime。详见[桌面构建说明](desktopApp/AGENTS.md)。

<a id="installation"></a>

### 安装与构建

1. 克隆仓库并进入目录：

   ```sh
   git clone https://github.com/originalFactor/Messenger.git
   cd Messenger
   ```

2. 在项目根目录创建 `local.properties`，指定 Android SDK 路径：

   ```properties
   sdk.dir=/path/to/android/sdk
   ```

3. 如果还要开发可选的云服务端，初始化子模块。原生客户端构建不需要此步骤：

   ```sh
   git submodule update --init --recursive
   ```

4. 构建调试 APK 或运行桌面应用：

   ```sh
   ./gradlew-cli :androidApp:assembleDebug     # x86_64 手机模拟器 APK
   ./gradlew-cli :runtime:assembleDebug        # x86_64 Runtime 配套 APK
   ./gradlew-cli :wear:assembleDebug           # x86_64 Wear OS 模拟器 APK
   ./gradlew-cli :desktopApp:run               # 桌面应用（JVM）
   ```

   Runtime 构建会下载固定版本的 Termux bootstrap 压缩包，并校验 SHA-256 摘要后再打包。需要终端工具时，请在安装 Android 主应用的同时安装 Runtime APK。

   手机、Runtime 和手表的调试构建默认均为 `x86_64`，用于模拟器。ARM64 实体手机可使用 `./gradlew-cli :androidApp:assembleDebug :runtime:assembleDebug -Pmessenger.debugAbis=arm64-v8a` 构建两个 APK。实体 Wear OS 手表使用 `./gradlew-cli :wear:assembleDebug -Pmessenger.wearDebugAbis=armeabi-v7a`。

   发布构建中，Android/Runtime 默认使用 `arm64-v8a`，Wear OS 默认使用 `armeabi-v7a`。手机/Runtime 的发布 ABI 可通过 `-Pmessenger.abis=armeabi-v7a` 或 `ANDROID_ABIS=armeabi-v7a` 覆盖；调试 ABI 使用 `-Pmessenger.debugAbis` 或 `ANDROID_DEBUG_ABIS`。Android 发布构建启用 R8 和资源压缩。发布签名需要将密钥库放在 `keyring/messenger-release.jks`，并设置 `KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`。

   桌面安装包需要在目标系统上构建：macOS 使用 `./gradlew-cli :desktopApp:packageReleaseDmg`，Windows 使用 `:desktopApp:packageReleaseMsi`，Linux 使用 `:desktopApp:packageReleaseDeb`。桌面发布打包会运行 ProGuard。如果只构建桌面端，且主机未安装固定版本的 Android NDK，请在桌面 Gradle 命令后添加 `-Pmessenger.buildRuntimeNative=false`。

   Android、Wear、Runtime 和桌面端共用根目录 `VERSION` 中的语义版本号，以 git 提交数量作为版本代码。桌面安装包使用 `MAJOR.MINOR.<commitCount>` 作为数字包版本。Gradle 支持通过 `VERSION_NAME` / `VERSION_CODE` 覆盖本地构建版本，但 Rust Core 仍读取 `VERSION`；跨客户端发布时请统一修改该文件。

5. 创建发布版本：

   ```sh
   ./release.ps1 -Bump patch     # 1.0.0 -> 1.0.1：更新 VERSION、提交、打 v1.0.1 标签并推送
   ./release.ps1                 # 为 VERSION 中已提交的版本打标签并推送
   ./release.ps1 -NoPush         # 仅在本地创建标签，检查后再推送
   ```

   推送会触发发布构建。若 `VERSION` 未提交或与 `HEAD` 不同、工作区有未提交改动，或本地/远程已存在同名标签，脚本会拒绝打标签。`-WhatIf` 可预览操作，`-AllowDirty` 可允许无关的本地改动。`.github/workflows/release.yml` 会再次检查标签是否与该提交中的 `VERSION` 一致，不一致时终止发布。

<p align="right">(<a href="#readme-top">返回顶部</a>)</p>

<a id="usage"></a>

## 使用方法

1. 在手机、平板或桌面设备上安装 Messenger。
2. 添加所选 AI 服务商的 API 密钥。
3. 为智能体选择模型，然后开始聊天。
4. 按任务需求创建自定义智能体。
5. 需要终端访问时，为智能体启用 **Tools（工具）**。Android 上需安装匹配的 Messenger Runtime 配套 APK；命令在它的应用私有工作区内通过固定版本的运行环境执行，无需单独安装 Termux。默认的只读模式仅提供检查命令；切换为可写模式后，智能体才可编辑和创建工作区文件。
6. （可选）安装 Wear OS 配套应用。服务商、模型和智能体在手机上配置；手表通过局域网发现手机（NSD mDNS），并通过 TCP `18765` 上的 WebSocket 自动同步智能体。
7. （可选）登录 Messenger 账号，在客户端之间同步智能体、服务商和对话。每个客户端均保留自己的本地存储，并将账号数据同步到其中，让同一对话可在手机和桌面上使用。

Messenger 支持提供 OpenAI 兼容 Chat Completions API 的服务商，具体可用功能取决于服务商和模型。

Android/桌面端还提供本地优先的学习工作台，支持课程、笔记、错题、练习、学习计划、复习和生成的交互式应用。配置与使用方法见 [LEARNING.md](LEARNING.md)。

<a id="local-core"></a>

### 本地 Rust Core

Android、桌面端和终端客户端在随机分配的本地回环端口启动 Rust Core。除健康检查外，每次请求都携带本次启动专用的 bearer token。所有业务调用使用版本化的 `/api/v1` HTTP API；对话流和 Core 数据变更通知使用 SSE。

API 契约见 [openapi.yaml](core/rust/crates/messenger-http/openapi.yaml)，SSE 契约见 [SSE.md](core/rust/crates/messenger-http/docs/SSE.md)。

Android/桌面端的 Markdown 和 LaTeX 由 Kotlin 侧的 [`llm-typewriter`](https://github.com/originalFactor/llm-typewriter) 渲染。Rust Core 负责智能体执行、存储、工具、模型和同步，不包含 Rust 文档渲染器。

<a id="terminal-client"></a>

### 终端客户端（可选）

Python TUI 使用 Textual 构建命令面板、输入区和对话视图，通过 Rust Core API 管理模型配置、历史记录和流式聊天。

安装 Python 3.11+、Poetry 2 和当前稳定版 Rust 工具链后，在仓库根目录执行：

```sh
poetry -C tui install
poetry -C tui run python workflow.py dev
poetry -C tui run python workflow.py test
poetry -C tui run python workflow.py build
poetry -C tui run python workflow.py release
```

工作流复用常规 Cargo 构建缓存。应用参数放在 `--` 后，例如 `poetry -C tui run python workflow.py dev -- --db ./test-chat.db`。此例中的测试数据路径相对于 `tui/`。

`release` 命令将优化后的 Core 和 Python 依赖打包为当前平台的压缩包，输出到 `tui/dist/`；可用 `--out PATH` 更改输出目录。解压后，Linux/macOS 运行 `./messenger`，Windows 运行 `.\messenger.cmd`。解压后的客户端需要 Python 3.11+。启动时传入 `--version` 可查看打包的 Core 版本。

在输入区键入 `/` 打开命令建议，继续输入可按前缀筛选。方向键选择建议，Tab 补全，Enter 执行高亮命令，Esc 关闭建议并保留草稿。

使用 `/providers` 搜索和管理服务商。**Add provider（添加服务商）** 会保存服务商并自动获取、导入可用模型。对已有服务商，打开 **Manage models（管理模型）** 可重新获取、手动添加、编辑、启用、禁用或删除模型。发现模型时，优先读取服务商扩展的 `/models` 响应，缺失字段由 pi-catalog 补充；刷新会保留已保存的覆盖设置。模型编辑器提供 **Fetch metadata（获取元数据）**，支持显示名称、输入模态、推理强度、上下文窗口、最大输出 token 数，以及 temperature、top_p 和惩罚参数的支持情况。获取失败时仍可按模型 ID 手动添加。

删除服务商也会删除其模型。使用 `/connect` 可一次完成添加服务商和选择模型。Enter 发送消息，Ctrl+O 换行，Esc 取消当前回复，Ctrl+P 打开命令面板。使用 `/new`、`/sessions`、`/models` 和 `/agents` 可新建会话、恢复会话、切换模型或为新会话选择智能体。Page Up / Page Down 翻阅消息；`/quit` 和 `/exit` 均可退出终端客户端。

Messenger 会打开包含当前目录的最近一级本地项目。如果当前目录不属于任何项目，可选择 **Trust and create project（信任并创建项目）**，创建归当前设备所有的项目，或选择 **Continue without a project（不使用项目继续）**，开始独立聊天。新对话属于当前项目；项目模式下，`/sessions` 只显示当前项目的对话。使用 `--workspace PATH` 可打开其他目录。

独立模式下，可使用 `/project` 创建项目或按文件夹重新打开项目。项目内可通过 `/project` 编辑名称或工作区目录，或选择 **Leave project（离开项目）** 开始独立聊天。项目及其对话仍会保留，重新打开后即可继续访问。

使用 `/cloud` 登录 Messenger Cloud、查看已保存的账号或退出登录。对话框会显示已配置的 Cloud 基础 URL。自托管服务可输入 HTTP(S) URL 后选择 **Save address（保存地址）**，或直接使用该地址登录。保存不同地址时会先退出当前账号。

使用 Cloud AI 密钥登录会自动添加 **Messenger Cloud AI** 并从该服务器同步模型。可用 `/models` 选择云端模型，或在 `/providers` 打开内置条目，同步模型目录、查看只读云端元数据，以及启用或禁用单个模型。退出登录会移除云端服务商及其模型。

登录期间新建的独立聊天会在每轮结束后上传到 Messenger Cloud。使用 `/sync` 可上传较早的本地聊天或重试上传。Core 会记录已上传聊天对应的云服务器和账号。TUI 对话同步会上传本地历史；检测到云端有更新的编辑时会报告冲突。

TUI 将聊天、服务商凭据和云会话保存在 `~/.messenger/tui.db`。使用 `--db PATH` 可指定独立的数据配置，`--core PATH` 可指定自定义 Core 可执行文件。请将 TUI 数据库与正在运行的桌面实例的数据库分开。TUI 以纯文本呈现内容，支持聊天。

_架构、约定与硬性约束详见 [AGENTS.md](AGENTS.md)。_

<p align="right">(<a href="#readme-top">返回顶部</a>)</p>

<a id="contributing"></a>

## 参与贡献

欢迎提交改进建议、问题反馈和 Pull Request。功能建议可在 Issue 中使用 `enhancement` 标签。喜欢本项目也可以点一颗 Star。

1. Fork 本项目。
2. 创建功能分支（`git checkout -b feature/AmazingFeature`）。
3. 提交改动（`git commit -m 'Add some AmazingFeature'`）。
4. 推送分支（`git push origin feature/AmazingFeature`）。
5. 发起 Pull Request。

### 贡献者

<a href="https://github.com/originalFactor/Messenger/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=originalFactor/Messenger" alt="项目贡献者" />
</a>

<a id="license"></a>

## 许可证

本项目采用 Apache License 2.0，详见 [LICENSE](LICENSE)。

<a id="contact"></a>

## 联系与项目地址

originalFactor — [Messenger 项目主页](https://github.com/originalFactor/Messenger)

<a id="acknowledgments"></a>

## 致谢

* [RaTeX](https://github.com/erweixin/RaTeX) / [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP)：Android 和 JVM 桌面端共用的纯 Rust、兼容 KaTeX 的 LaTeX 引擎。
* [Best-README-Template](https://github.com/othneildrew/Best-README-Template)：README 模板。
* [Jetpack Compose](https://developer.android.com/jetpack/compose)。
* [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)。
* [Material 3](https://m3.material.io/)。
* [llm-typewriter](https://github.com/originalFactor/llm-typewriter)：Android 和桌面端的流式 Markdown、代码和 LaTeX 渲染。
* [Axum](https://github.com/tokio-rs/axum)：版本化的本地回环 HTTP/SSE API。

<p align="right">(<a href="#readme-top">返回顶部</a>)</p>

<!-- 链接与徽章 -->
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
