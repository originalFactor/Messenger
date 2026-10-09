# Messenger AGENTS.md

## Project Overview

Messenger is a Material 3 designed LLM chat application for Android, focused on on-wrist experience and ease of use. It is fully open-source, free, and offline-capable, using a BYOK (Bring Your Own Key) model.

- **Package name**: `cc.ptoe.messenger`
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 36
- **Compile SDK**: 37
- **Language**: Kotlin
- **UI**: Jetpack Compose (Material 3) + Wear Compose
- **Architecture**: Clean Architecture (data/domain/presentation layers)
- **Modules**: `shared` (KMP library: shared Android/Desktop/Web logic), `androidApp` (Android application shell), `desktopApp` (Desktop application shell), `webApp` (Compose Multiplatform Kotlin/Wasm browser shell), `wear` (Wear OS), `runtime` (companion shell-runtime app: own UID + targetSdk 28 for the legacy SELinux domain, hosts the Termux bootstrap), `server` (Next.js SaaS platform — Vercel or self-hosted: official website, web console, cloud sync, card-key billing, AI API relay), `core/rust` + `core/bindings` (Rust Agent Core), `renderer-android` (Android View chat renderer: RecyclerView + MessageView + DocumentView per `TARGET.md`)

## Project Structure

```
Messenger/
├── core/                       # TARGET.md migration: Rust Agent Core + its Kotlin bridge
│   ├── rust/                   # Cargo workspace (resolver = 2, release LTO + strip)
│   │   └── crates/
│   │       └── messenger-ffi/  # UniFFI boundary crate (cdylib `messenger_ffi`; Kotlin package
│   │                           #   cc.ptoe.messenger.core via uniffi.toml; src/bin/uniffi-bindgen.rs
│   │                           #   regenerates bindings). M0 walking skeleton: corePing/coreVersion +
│   │                           #   echoEvents streaming AgentEvent through the AgentEventSink callback
│   │       └── messenger-tui/  # Native Rust terminal client (TARGET.md Phase 6): hand-rolled crossterm
│   │                           #   0.28 renderer (no widget framework) binary + lib; links the core
│   │                           #   crates directly (no UniFFI)
│   └── bindings/               # Gradle module :core-bindings (projectDir mapped from core/bindings)
│       ├── build.gradle.kts    # com.android.library; Exec tasks cargoBuildHost → generateUniFFIBindings
│       │                       #   (bindgen --no-format) and buildRustAndroid (cargo-ndk, 3 ABIs,
│       │                       #   --platform 30) wired into preBuild; NDK resolved from
│       │                       #   ANDROID_NDK_HOME else $sdk.dir/ndk/<highest>
│       └── proguard-rules.pro  # consumer rules: keep cc.ptoe.messenger.core.** + JNA (R8 on :wear)
├── .github/workflows/          # GitHub Actions CI/CD (split into 6 files)
│   ├── build-android.yml       # Reusable workflow: androidApp ABI release APKs
│   ├── build-wear.yml          # Reusable workflow: wear release APK
│   ├── build-desktop.yml       # Reusable workflow: Desktop MSI distribution
│   ├── build-all.yml           # Reusable aggregator: 3 explicit parallel jobs calling build-*.yml
│   ├── ci.yml                  # Push/PR CI: calls build-all.yml
│   └── release.yml             # Tag-triggered (v*): calls build-all.yml + GitHub Release
├── gradle/
│   ├── libs.versions.toml      # Version catalog for dependencies
│   └── wrapper/                # Gradle wrapper
├── keyring/                    # Signing keystore
├── shared/                     # KMP library: shared Android/Desktop logic
│   ├── build.gradle.kts        # kotlin.multiplatform + com.android.kotlin.multiplatform.library
│   ├── proguard-rules.pro      # R8/ProGuard rules for shared
│   └── src/
│       ├── commonMain/         # All shared code (domain/data/presentation/DI)
│       │   ├── composeResources/
│       │   │   ├── values/strings.xml
│       │   │   └── values-zh-rCN/strings.xml
│       │   └── kotlin/cc/ptoe/messenger/
│       │       ├── data/
│       │       │   ├── local/          # Room database, DataStore preferences
│       │       │   │   ├── dao/
│       │       │   │   │   ├── AgentDao.kt
│       │       │   │   │   ├── ConversationDao.kt
│       │       │   │   │   ├── MessageDao.kt
│       │       │   │   │   ├── ModelDao.kt
│       │       │   │   │   └── ProviderDao.kt
│       │       │   │   ├── entity/
│       │       │   │   │   ├── AgentEntity.kt
│       │       │   │   │   ├── ConversationEntity.kt
│       │       │   │   │   ├── MessageEntity.kt
│       │       │   │   │   ├── ModelEntity.kt
│       │       │   │   │   └── ProviderEntity.kt
│       │       │   │   ├── AppPreferences.kt
│       │       │   │   ├── ChatImageStore.kt
│       │       │   │   ├── ContentPartCodec.kt
│       │       │   │   ├── DataStoreModule.kt
│       │       │   │   ├── MessengerDatabase.kt
│       │       │   │   └── ThemePreferences.kt
│       │       │   ├── cloud/          # Messenger account auth & cloud sync
│       │       │   │   ├── CloudApiClient.kt
│       │       │   │   ├── CloudModels.kt
│       │       │   │   └── CloudSyncRepository.kt
│       │       │   ├── remote/         # Network layer (OpenAI-compatible API)
│       │       │   │   ├── api/
│       │       │   │   │   └── OpenAiClient.kt
│       │       │   │   ├── dto/        # Chat & model DTOs
│       │       │   │   ├── sse/        # Server-Sent Events parsing
│       │       │   │   │   ├── ChatStreamEvent.kt
│       │       │   │   │   ├── ChatStreamParser.kt
│       │       │   │   │   └── SSEParser.kt
│       │       │   │   ├── NetworkClient.kt
│       │       │   │   └── PlatformHttpClient.kt
│       │       │   ├── repository/     # Repository implementations
│       │       │   │   ├── AgentRepositoryImpl.kt
│       │       │   │   ├── ApiRepositoryImpl.kt
│       │       │   │   ├── ConversationRepositoryImpl.kt
│       │       │   │   ├── CurrentAgentRepositoryImpl.kt
│       │       │   │   ├── MessageRepositoryImpl.kt
│       │       │   │   ├── ModelRepositoryImpl.kt
│       │       │   │   └── ProviderRepositoryImpl.kt
│       │       │   └── util/
│       │       │       ├── FileKit.kt
│       │       │       ├── Logger.kt
│       │       │       ├── ToolsConfigCodec.kt
│       │       │       └── Uuid.kt
│       │       ├── di/
│       │       │   └── AppContainer.kt # Manual DI container (replaces MessengerApplication.initRepositories())
│       │       ├── domain/
│       │       │   ├── model/          # Domain models (pure Kotlin data classes)
│       │       │   │   ├── Agent.kt
│       │       │   │   ├── ChatModel.kt
│       │       │   │   ├── ContentPart.kt
│       │       │   │   ├── Conversation.kt
│       │       │   │   ├── Message.kt
│       │       │   │   ├── MessageRole.kt
│       │       │   │   ├── Project.kt
│       │       │   │   └── Provider.kt
│       │       │   ├── repository/     # Repository interfaces
│       │       │   │   ├── AgentRepository.kt
│       │       │   │   ├── ApiRepository.kt
│       │       │   │   ├── ConversationRepository.kt
│       │       │   │   ├── CurrentAgentRepository.kt
│       │       │   │   ├── MessageRepository.kt
│       │       │   │   ├── ModelRepository.kt
│       │       │   │   ├── ProjectRepository.kt
│       │       │   │   └── ProviderRepository.kt
│       │       │   └── tool/           # Built-in tool-calling domain (OpenAI function calling)
│       │       │       ├── ChatTool.kt          # Tool interface + ToolExecutionResult
│       │       │       ├── TerminalTool.kt       # Built-in read-only terminal tool (schema / args parsing / output truncation)
│       │       │       ├── ShellExecutor.kt      # expect: platform shell execution
│       │       │       ├── WorkspaceOperation.kt  # Shared workspace operations contract
│       │       │       ├── WorkspaceTool.kt       # Bounded glob/grep/read/edit/create tools
│       │       │       └── PlatformTools.kt       # expect: platform tool registry
│       │       └── presentation/
│       │           ├── navigation/     # Navigation Compose setup
│       │           │   ├── BottomLevelRoutes.kt
│       │           │   ├── NavGraph.kt
│       │           │   └── Screen.kt
│       │           ├── platform/       # expect declarations (BackHandler, ImagePicker, PlatformServices)
│       │           │   ├── BackHandler.kt
│       │           │   ├── ImagePicker.kt
│       │           │   └── PlatformServices.kt
│       │           ├── theme/
│       │           │   └── Theme.kt
│       │           ├── ui/             # Compose screens & components
│       │           │   ├── agents/
│       │           │   │   ├── AgentConversationsScreen.kt  # Agent-scoped conversation list (Agents tap target)
│       │           │   │   ├── AgentEditScreen.kt
│       │           │   │   ├── AgentMarketScreen.kt
│       │           │   │   └── AgentsScreen.kt
│       │           │   ├── chat/
│       │           │   │   ├── ChatInputBar.kt
│       │           │   │   ├── ChatScreen.kt
│       │           │   │   ├── MessageActionMenu.kt
│       │           │   │   └── UserMessageBubble.kt
│       │           │   ├── components/
│       │           │   │   ├── AgentAvatar.kt
│       │           │   │   ├── BottomNavBar.kt
│       │           │   │   ├── ConfirmationDialog.kt
│       │           │   │   ├── ConversationListItem.kt
│       │           │   │   ├── EmptyState.kt
│       │           │   │   ├── InputDialog.kt
│       │           │   │   ├── ListItem.kt
│       │           │   │   ├── LoadingIndicator.kt
│       │           │   │   ├── MainScaffold.kt
│       │           │   │   ├── SectionHeader.kt
│       │           │   │   ├── ProjectListItem.kt   # Projects section + project row (chat lists)
│       │           │   │   └── SingleChoiceDialog.kt
│       │           │   ├── conversations/
│       │           │   │   ├── ConversationSettingsScreen.kt
│       │           │   │   ├── ConversationsDualPaneScreen.kt
│       │           │   │   └── ConversationsScreen.kt
│       │           │   ├── projects/
│       │           │   │   ├── ProjectConversationsScreen.kt  # Second-level page: a project's conversations
│       │           │   │   └── ProjectEditorDialog.kt        # Create/edit project (name + optional workspace folder)
│       │           │   ├── providers/
│       │           │   │   ├── ProviderDetailScreen.kt
│       │           │   │   ├── ProviderEditScreen.kt
│       │           │   │   └── ProvidersScreen.kt
│       │           │   └── settings/
│       │           │       ├── CloudServerScreen.kt
│       │           │       ├── CloudSettingsScreen.kt
│       │           │       ├── CloudSyncChoiceDialog.kt
│       │           │       ├── LicensesScreen.kt
│       │           │       ├── SettingsScreen.kt
│       │           │       └── ThemePickerDialog.kt
│       │           ├── utils/
│       │           │   ├── DateTimeUtils.kt
│       │           │   ├── FormatUtils.kt
│       │           │   ├── ThinkBlockUtils.kt
│       │           │   └── WindowSizeClass.kt
│       │           └── viewmodel/
│       │               ├── AgentConversationsViewModel.kt
│       │               ├── AgentEditViewModel.kt
│       │               ├── AgentMarketViewModel.kt
│       │               ├── AgentsViewModel.kt
│       │               ├── ChatViewModel.kt
│       │               ├── ConversationSettingsViewModel.kt
│       │               ├── ConversationsViewModel.kt
│       │               ├── ProjectConversationsViewModel.kt
│       │               ├── ProjectsViewModel.kt
│       │               ├── ProviderDetailViewModel.kt
│       │               ├── ProviderEditViewModel.kt
│       │               ├── ProvidersViewModel.kt
│       │               ├── SettingsViewModel.kt
│       ├── androidMain/         # Android-only platform `actual` implementations
│       │   ├── AndroidManifest.xml     # Minimal <manifest /> root only
│       │   └── kotlin/cc/ptoe/messenger/
│       │       ├── data/
│       │       │   ├── local/
│       │       │   │   ├── AndroidChatImageStore.kt
│       │       │   │   └── DatabaseBuilder.android.kt
│       │       │   ├── remote/
│       │       │   │   └── PlatformHttpClient.android.kt
│       │       │   ├── util/
│       │       │   │   ├── Logger.android.kt
│       │       │   │   └── Uuid.androidMain.kt
│       │       │   └── wear/           # Phone-side Wear bridge (HTTP server + WebSocket sync)
│       │       │       ├── MobileHttpServer.kt
│       │       │       └── MobileWearSyncManager.kt
│       │       ├── domain/
│       │       │   └── tool/
│       │       │   ├── ShellExecutor.android.kt   # System shell (/system/bin/sh) + app-private workspace
│       │       │   └── PlatformTools.android.kt   # Registers read-only TerminalTool + WorkspaceTool
│       │       └── presentation/
│       │           ├── platform/
│       │           │   ├── ImagePicker.android.kt
│       │           │   └── PlatformServices.android.kt   # AndroidContextHolder
│       │           └── theme/
│       │               └── Theme.android.kt
│       ├── desktopMain/        # Desktop-only platform `actual` implementations
│       │   └── kotlin/cc/ptoe/messenger/
│       │       ├── data/
│       │       │   ├── local/
│       │       │   │   ├── DatabaseBuilder.desktop.kt
│       │       │   │   └── DesktopChatImageStore.kt
│       │       │   ├── remote/
│       │       │   │   └── PlatformHttpClient.desktop.kt
│       │       │   └── util/
│       │       │       ├── Logger.desktop.kt
│       │       │       └── Uuid.desktopMain.kt
│       │       ├── domain/
│       │       │   └── tool/
│       │       │       ├── ShellExecutor.desktop.kt   # PowerShell (Windows, UTF-8) / /bin/sh; timeout + tail-truncation
│       │       │       ├── PlatformTools.desktop.kt   # Registers TerminalTool + WorkspaceTool
│       │       │       └── WorkspaceTools.desktop.kt  # Desktop workspace file operations
│       │       └── presentation/
│       │           ├── platform/
│       │           │   ├── ImagePicker.desktop.kt        # FileDialog-based
│       │           │   └── PlatformServices.desktop.kt
│       │           └── theme/
│       │               └── Theme.desktop.kt
│       ├── commonTest/
│           └── kotlin/cc/ptoe/messenger/
│               ├── ExampleUnitTest.kt
│               ├── data/local/ContentPartCodecToolTest.kt
│               ├── data/remote/sse/ChatStreamParserToolCallTest.kt
│               ├── domain/tool/WorkspaceToolTest.kt
│               ├── domain/tool/TerminalToolTest.kt
│               └── presentation/utils/StripThinkBlockTest.kt
│       └── desktopTest/        # Desktop-only tests (real PowerShell/rg executors, Compose UI tests)
│           └── kotlin/cc/ptoe/messenger/
│               ├── domain/tool/DesktopToolsTest.kt   # workspace cwd, grep colon rows, timeout kill
│               └── presentation/ui/agents/AgentToolsPageMasterSwitchTest.kt
├── androidApp/                 # Android application shell (com.android.application)
│   ├── build.gradle.kts        # AGP 9 built-in Kotlin + kotlin.compose + kotlin.serialization
│   ├── proguard-rules.pro      # R8/ProGuard rules for androidApp
│   └── src/main/
│       ├── AndroidManifest.xml # Full application manifest (<application>/<activity>/<service>/<provider>)
│       ├── aidl/cc/ptoe/messenger/runtime/  # AIDL copies of IShellService/IShellCallback (same FQN as :runtime)
│       ├── kotlin/cc/ptoe/messenger/
│       │   ├── MainActivity.kt
│       │   ├── MessengerApplication.kt
│       │   └── RuntimeShellClient.kt  # AIDL client implementing shared ShellRuntimeBridge
│       └── res/                # Application-level resources
│           ├── drawable/
│           ├── mipmap-*/       # Launcher icons
│           ├── values/
│           ├── values-night/
│           └── xml/            # backup_rules, data_extraction_rules, file_paths
├── desktopApp/                 # Desktop application shell (org.jetbrains.kotlin.jvm + compose.multiplatform)
│   ├── build.gradle.kts        # compose.desktop { application { mainClass = "cc.ptoe.messenger.MainKt"; ... } }
│   └── src/main/kotlin/cc/ptoe/messenger/
│       └── Main.kt             # Desktop entry point (constructs AppContainer directly)
├── server/                     # Git submodule: Next.js SaaS platform (website, console, sync, billing, AI API)
│   ├── app/
│   │   ├── console/            # Shared web console (user + admin sections) under /console
│   │   ├── login/ /register/   # Web login & registration pages
│   │   ├── v1/                 # OpenAI-compatible AI API proxy (/v1/models, /v1/chat/completions)
│   │   └── api/
│   │       ├── admin/          # Admin-only JSON APIs (overview/plans/cards/models/upstreams/users)
│   │       ├── agents/[id]/
│   │       ├── auth/login/ / register/ / logout/ / me/ / password/ / account/
│   │       ├── avatars/user/ / agents/[agentId]/
│   │       ├── console/        # User console APIs (overview/redeem/redemptions/api-key, cards/preview)
│   │       ├── conversations/[id]/
│   │       ├── market/agents/ / agents/[id]/avatar/
│   │       ├── plans/          # Public plan list for the website pricing section
│   │       ├── providers/[id]/
│   │       └── sync/
│   ├── components/             # Website/console UI components (RSC + client islands)
│   ├── lib/                    # Auth, storage, billing/quota, AI-proxy helpers, validation, types
│   ├── package.json
│   └── README.md
├── wear/                       # Wear OS companion module
│   ├── proguard-rules.pro      # R8/ProGuard rules for wear
│   └── src/main/java/cc/ptoe/messenger/
│       ├── data/
│       │   ├── WearBridgeClient.kt
│       │   ├── WearChatModels.kt
│       │   ├── WearChatPreferences.kt
│       │   ├── WearChatRepository.kt
│       │   └── WearNetworkBridge.kt
│       ├── presentation/
│       │   ├── MainActivity.kt
│       │   ├── theme/
│       │   │   └── Theme.kt
│       │   ├── ui/
│       │   │   ├── chat/
│       │   │   │   └── ChatScreen.kt
│       │   │   ├── chatlist/
│       │   │   │   ├── ChatListScreen.kt
│       │   │   │   └── NewChatScreen.kt
│       │   │   └── components/
│       │   │       └── ChatComponents.kt
│       │   └── viewmodel/
│       │       └── WearChatViewModel.kt
│       └── WearMessengerApplication.kt
├── runtime/                    # Companion shell-runtime app (own UID, targetSdk 28 → untrusted_app_27 domain)
│   ├── build.gradle.kts        # com.android.application + splits.abi that downloads/verifies the pinned bootstrap into jniLibs/<abi>/libbootstrap.zip.so + NDK/CMake (libtermux.so)
│   └── src/main/
│       ├── AndroidManifest.xml # signature SHELL permission + exported ShellService + TerminalActivity (launcher)
│       ├── aidl/cc/ptoe/messenger/runtime/  # IShellService / IShellCallback (copies live in androidApp too)
│       ├── cpp/               # CMakeLists.txt + termux.c: PTY JNI (createSubprocess/waitFor/close)
│       ├── java/com/termux/    # Vendored terminal-emulator + terminal-view sources (Apache-2.0, termux-app v0.118.3)
│       ├── res/                # terminal layout/toolbar/extra-key resources + platform Material theme
│       └── kotlin/cc/ptoe/messenger/runtime/
│           ├── TerminalActivity.kt    # Termux-style terminal UI: TerminalView + toolbar + extra-keys bar
│           ├── ExtraKeysBar.kt        # shortcut bar (Termux's default extra-keys layout, one-shot CTRL/ALT)
│           ├── ShellService.kt        # AIDL facade: same-UID check, per-request jobs, cancel
│           ├── TermuxRuntime.kt       # pinned bootstrap install (atomic, marker-validated) + command + PTY sessions
│           ├── WorkspaceOps.kt        # workspace glob/grep/read/edit/create served over AIDL (paths relative to the
│                                      #   workspace unless absolute — the runtime's own UID is the sandbox, no path checks)
│           └── RuntimePathPolicy.kt   # archive path + symlink-graph validation (directory targets allowed)
├── .env                        # Local environment variables (gitignored)
├── .gitmodules                 # Submodule definitions
├── AGENTS.md                   # This file
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── licenserc.toml
├── LICENSE
├── README.md
├── logo.png
└── logo.svg
```

## Architecture

### Layered Architecture

The project follows Clean Architecture with three main layers:

1. **Domain Layer** (`domain/`)
   - Pure Kotlin, no Android dependencies
   - Contains business models and repository interfaces
   - Domain models: `Agent`, `ChatModel`, `ContentPart`, `Conversation`, `Message`, `MessageRole`, `Provider`

2. **Data Layer** (`data/`)
   - Implements domain repository interfaces
   - Local data source: Room database + DataStore preferences
   - Remote data source: Retrofit + OkHttp + SSE
   - Entity mapping: Room entities (`*Entity.kt`) map to domain models

3. **Presentation Layer** (`presentation/`)
   - Jetpack Compose UI with Material 3
   - MVVM pattern with ViewModels
   - Navigation Compose for routing

### Module Split (KMP)

The project is split into three modules to share code between Android and Desktop while satisfying AGP 9.3.1 constraints:

- `shared` is a KMP library using `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` plugins. Contains all shared code in `commonMain`, platform-specific `actual` implementations in `androidMain`, `desktopMain` and `wasmJsMain`, plus the `jvmSharedMain` intermediate source set (Android + Desktop but NOT wasmJs: Room, `OkioStorage`, `FileSystem.SYSTEM`, the Room-backed repositories and the cloud facade) and `jvmSharedTest` (the Android/Desktop tests, kept out of `commonTest` because they use `org.junit`/`runBlocking`, which do not exist on wasmJs). Room KSP registration is per-target: `add("kspAndroid", libs.androidx.room.compiler)` + `add("kspDesktop", libs.androidx.room.compiler)`. The `compose.resources` block stays in `shared`.
- `androidApp` is the Android application shell using `com.android.application` (AGP 9 built-in Kotlin — does NOT apply `kotlin-android` or `kotlin.multiplatform`) + `kotlin.compose` + `kotlin.serialization`. Hosts `applicationId`, `versionCode`, `versionName`, `signingConfigs`, `buildTypes`, `buildFeatures { compose = true }`, `MainActivity`, `MessengerApplication`, full `AndroidManifest.xml`, application-level `res/`.
- `desktopApp` is the Desktop application shell using `org.jetbrains.kotlin.jvm` + `compose.multiplatform` + `kotlin.compose`. Hosts `Main.kt` (Desktop entry point) and `compose.desktop { application { ... } }` configuration with `Dmg`/`Msi`/`Deb` native distribution formats.
- The split was forced by AGP 9.3.1 which does not support `com.android.application` combined with `org.jetbrains.kotlin.multiplatform` in the same module. Path B+C was chosen (split both Android and Desktop into separate application shells).

### Wear Companion Architecture

- The `wear` module is a phone-backed companion experience with no settings UI (tiny mobile chat-only surface)
- Wear UI is a two-screen chat flow: **chat list** (mobile conversations with agent avatars + last-message previews) and **chat screen** (messages + input with agent/user avatars)
- Navigation is state-based (`WearScreen.ChatList` / `WearScreen.Chat`) without Navigation Compose
- **WebSocket sync over the watch's tether network** (phone → watch): the phone runs a foreground service `MobileHttpServer` that listens on TCP `18765` and registers an NSD (`_messenger._tcp`) mDNS service; the watch discovers it via `WearNetworkBridge` and opens a WebSocket using OkHttp. Wear OS watches tether their network to the phone via Bluetooth PAN, so the watch and phone are always on the same L2 network — no Bluetooth pairing or runtime permissions are required. The previous DataLayer and Bluetooth RFCOMM paths were abandoned because GMS for Wear OS is missing on Samsung China-region Galaxy Watches *and* the Bluetooth path was unreliable. The same line-delimited JSON protocol (`sync` / `chat` / `new_conversation`, all with `requestId` correlation) is spoken on top of WebSocket text frames
- The `MobileHttpServer` and `MobileWearSyncManager` Wear bridge classes live in `shared/src/androidMain/kotlin/cc/ptoe/messenger/data/wear/`. They are declared in `androidApp/src/main/AndroidManifest.xml` via the fully-qualified `<service android:name="cc.ptoe.messenger.data.wear.MobileHttpServer">` (the class lives in `shared`, not `androidApp`, so the FQN is required).
- Chat actions use the same WebSocket: the watch sends a `chat` or `new_conversation` JSON request and the phone replies inline. The same `MobileWearChatHandler` that used to back the DataLayer path is reused here, so business logic is identical
- Wear caches the latest synced snapshot in DataStore for a fast resume path

### Dependency Injection

The project uses a manual dependency injection approach via an `AppContainer`:
- All repositories and data sources are initialized inside `AppContainer` (located in `shared/src/commonMain/kotlin/cc/ptoe/messenger/di/AppContainer.kt`)
- On Android, `MessengerApplication.onCreate()` (in `androidApp/src/main/kotlin/cc/ptoe/messenger/MessengerApplication.kt`) constructs and owns the `AppContainer` instance; access is via the `MessengerApplication.instance` singleton pattern
- On Desktop, `Main.kt` (in `desktopApp/src/main/kotlin/cc/ptoe/messenger/Main.kt`) constructs `AppContainer` directly
- The `wear` module mirrors this approach with `WearMessengerApplication`

### Database

- **Room Database**: `MessengerDatabase` (in `shared/src/jvmSharedMain/kotlin/cc/ptoe/messenger/data/local/MessengerDatabase.kt`) with 6 entities
  - `ProviderEntity` - API providers
  - `ModelEntity` - Available models per provider
  - `AgentEntity` - AI agent configurations
  - `ProjectEntity` - Projects (a project IS a workspace; owns a set of conversations)
  - `ConversationEntity` - Chat conversations (`projectId` FK → `projects`, `ON DELETE SET NULL`)
  - `MessageEntity` - Chat messages (`partsJson` column stores multimodal `ContentPart` payloads)
- Database version: 21 (with `fallbackToDestructiveMigration`). v12 added `ModelEntity.contextWindow` and the conversation auto-summary columns (`ConversationEntity.contextSummary` / `contextSummaryUntil` / `contextTokens` / `contextTokensAt`); v13 added `ModelEntity.inputRate` / `outputRate` (null = provider metadata unknown, 0 = free); v14 added the `ModelEntity` capability columns (`inputModalities` / `outputModalities` / `supportsToolCalling` / `supportsThinking` / `supportsJsonOutput` / `supportsTemperature`); v15 added `AgentEntity.role` (`TEXT NOT NULL DEFAULT 'chat'`); v16 added `AgentEntity.toolsEnabled` (`INTEGER NOT NULL DEFAULT 0`); v17 added `AgentEntity.toolsFollowDefault` (`INTEGER NOT NULL DEFAULT 0`) and `AgentEntity.toolsConfig` (`TEXT`, JSON `Map<String, Boolean>` of tool function name → enabled, `''` = all on); v18 added the per-conversation settings columns `ConversationEntity.overrideToolsEnabled` (`INTEGER`, null = follow the Agent's effective tools switch) and `ConversationEntity.writable` (`INTEGER NOT NULL DEFAULT 0` — the conversation-level Agent read-only/writable mode that replaced the former global `AppPreferences.agentWritable`); v19 added `ConversationEntity.overrideToolsConfig` (`TEXT`, JSON `Map<String, Boolean>` of tool function name → enabled, null = follow the Agent's per-tool config); v20 added `AgentEntity.description` (`TEXT NOT NULL DEFAULT ''` — the user-facing Agent description, display-only); v21 added the `projects` table + `ConversationEntity.projectId` (`MIGRATION_20_21`).
- Room compiler is registered per-target via KSP in `shared/build.gradle.kts`: `add("kspAndroid", libs.androidx.room.compiler)` + `add("kspDesktop", libs.androidx.room.compiler)`

### Navigation

- Uses Navigation Compose
- Routes defined in `Screen.kt` sealed class (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/Screen.kt`)
- NavGraph defined in `NavGraph.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/NavGraph.kt`); uses `backStackEntry.arguments?.read { getStringOrNull("key") }` for Compose Multiplatform SavedState API compatibility
- Bottom navigation routes defined in `BottomLevelRoutes.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/BottomLevelRoutes.kt`)
- **Page transitions**: `NavGraph` wires `NavHost` `enterTransition`/`exitTransition`/`popEnterTransition`/`popExitTransition` from the shared helpers in `presentation/navigation/PageTransitions.kt`. Each `Screen` has a linear page order (`RouteOrders`, grouped by bottom-nav branch Conversations → Agents → Settings, children after their parent): navigating toward a *later* page slides the new page in from the right (Compact) / bottom (non-Compact rail layout) and the old page out the opposite side; navigating toward an *earlier* page reverses the direction. Pop transitions add a fade (slide-out + fade), and on Android the Navigation library drives the pop transition with the predictive-back gesture (`SeekableTransitionState`), so system back gesture plays the same slide-out + fade following the finger. `MainScaffold` passes its computed `WindowSizeClass` into `NavGraph` to choose horizontal vs. vertical slide.
- **In-screen sub-page predictive back**: in-screen secondary pages (e.g. `CloudServerScreen` hosted inside `CloudSettingsScreen` via state, no nav route) register `presentation/platform/BackHandler` (expect/actual: `androidx.activity.compose.PredictiveBackHandler` on Android — the wrapped content follows the gesture with a slide-out + fade animation, sliding toward the gesture's starting edge while fading out; requires the manifest's `enableOnBackInvokedCallback`; no-op passthrough on Desktop) so the system back gesture returns to the host page instead of popping the route.
- **Adaptive layout (M3 window size classes)**: `MainScaffold.kt` uses `BoxWithConstraints` + `windowSizeClassFor(maxWidth)` (from `presentation/utils/WindowSizeClass.kt`) to switch between a bottom `NavigationBar` (Compact width < 600 dp, phones) and a left `NavigationRailBar` (Medium/Expanded width ≥ 840 dp, tablet landscape / desktop). The `NavigationRailBar` composable lives next to `BottomNavBar` in `BottomNavBar.kt`.
- **Bottom-nav clearance (`LocalBottomNavClearance`)**: the floating bottom-navigation pill exists ONLY in the Compact branch of `MainScaffold`, so the 92 dp clearance (80 dp bar + 12 dp gap) that top-level screens must keep free at their own bottom is published through the `LocalBottomNavClearance` composition local from `components/MainScaffold.kt` (default 0 dp). `MainScaffold` provides 92 dp inside the Compact `Scaffold` content; `ConversationsScreen` / `AgentsScreen` / `SettingsScreen` consume it for both their FAB offset and their list `contentPadding`. The rail layout (Medium/Expanded) therefore leaves the FAB at the Scaffold's standard 16 dp inset instead of floating ~92 dp above the pane bottom.
- **List-Detail dual-pane (non-Compact width)**: when `WindowSizeClass != Compact` (Medium/Expanded), `NavGraph.kt` renders a `*DualPaneScreen` instead of pushing the detail route — left pane is the list, right pane is the detail surface. This applies to `Conversations` (`ConversationsDualPaneScreen` — left pane is the 360 dp conversation list, right pane is `ChatScreen` with `showBackButton = false`), `Agents` (`AgentsDualPaneScreen` — right pane hosts the selected Agent's conversation list (`AgentConversationsScreen`), with in-pane switches to `AgentEditScreen` via the list's settings icon or the row's edit menu, and to `ChatScreen` for a tapped conversation), `Providers` (`ProvidersDualPaneScreen`), and `Settings` (`SettingsDualPaneScreen`). Selection state is `rememberSaveable`. On Compact width the existing single-pane + push flow is preserved. The dual-pane trigger threshold was unified to `!= Compact` (previously `Expanded` for Conversations only).
- **Context menus (non-Compact width)**: list items and message bubbles expose right-click context menus via `Modifier.onContextMenu` + `CursorDropdownMenu` when `WindowSizeClass != Compact`. The shared helpers live in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/ContextMenu.kt`.
- **Multi-select mode**: `ConversationsScreen` and `AgentsScreen` support long-press multi-select (mirroring the existing `ProviderDetailScreen` implementation). The shared selection-mode TopAppBar lives in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/MultiSelectTopBar.kt`.
- **Hover state (non-Compact width)**: list items transition their background to `surfaceContainerHigh` on hover when `WindowSizeClass != Compact`.
- **Tooltips (non-Compact width)**: `NavigationRailBar` items and TopAppBar primary action icons are wrapped in M3 `TooltipBox` when `WindowSizeClass != Compact`.
- **Important**: `ProviderEdit` and `AgentEdit` routes use optional parameter syntax (`provider_edit?providerId={providerId}`)
- `AgentMarket` and `AgentMarketDetail` are Cloud-authenticated routes entered from the Agent list FAB; the FAB exposes the market only while a Cloud user is signed in.

### API / Network

- OpenAI-compatible API via Retrofit
- SSE (Server-Sent Events) streaming for chat completions
- Auth header handling is folded into `PlatformHttpClient.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/PlatformHttpClient.kt`) with platform `actual` implementations in `androidMain` and `desktopMain`; the standalone `AuthInterceptor.kt` no longer exists
- `OpenAiClient.kt` (renamed from `OpenAiApi.kt`) lives in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/api/`
- `NetworkClient.kt` lives in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/`
- SSE parser files (`ChatStreamEvent.kt`, `ChatStreamParser.kt`, `SSEParser.kt`) live in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/sse/`
- **推理内容显示（reasoning formats）**: `ChatStreamParser` 把两种推理增量统一包进 `<think>` 块（渲染为可折叠思考卡）：`delta.reasoning_content`（DeepSeek 等完整思维链，事件 `ReasoningDetected(format = "reasoning_content")`）和 `delta.reasoning`（GPT 等推理模型的 Reasoning Summary —— 思维链加密不可见仅回传摘要，事件 `format = "reasoning_summary"`，OpenRouter 与 Responses→Chat Completions 网关的标准字段；同块同至时 `reasoning_content` 优先）。首次检测到的格式持久化到 `Conversation.reasoningFormat` 并随云同步镜像；`buildRequestMessages` 按格式回发历史 —— `reasoning_content` 把 think 标签还原为 `reasoning_content` 字段，`think_tag` 保留标签原样，`reasoning_summary` 剥离思考后仅发正文、不回传任何推理字段（其思维链不可回传，严格网关会拒绝未知字段）。非流式 `createChatCompletion` 同样读取 `message.reasoning_content` / `message.reasoning` 包裹为 think 块
- DTOs live in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/dto/`
- Gson converter for JSON serialization
- Wear chat requests are forwarded to the phone over a WebSocket on TCP `18765` (`MobileHttpServer` / `WearNetworkBridge`, discovered via NSD mDNS) instead of calling providers directly from the watch
- **Multimodal messages**: `ChatMessageDto.content` is a `JsonElement` so a single DTO carries both the legacy text-string shape and the OpenAI `[{type,text|image_url}]` array shape. `ApiRepositoryImpl` picks the wire format based on `Message.hasImages`. Picked images are downscaled to 1568px on the longest side, EXIF-rotated, cached as PNGs under `filesDir/chat_images/`, and the cache is reaped on message delete. The local DB keeps the bitmap path/URI stable so the cloud sync layer and the UI never depend on a content:// URI re-issuing.
- **Multimodal image cloud sync**: each image part is stored in `partsJson` as `{type:"image", dataUri, localPath}` where `dataUri` embeds the full base64 bitmap, so pushing a conversation to the cloud carries the image bytes inside the message document — no separate image upload endpoint exists. On the pull side, `CloudSyncRepository.rehydrateChatImages` runs before the Room write: any image part whose `localPath` does not exist on this device (fresh install / another device) is decoded from its `dataUri` into `filesDir/chat_images/sync_{sha256(messageId|partIndex|dataUri)}.{ext}` and the part's `localPath` is rewritten to the local copy, keeping Coil rendering purely file-based. The deterministic file name makes repeated syncs idempotent, and per-message files preserve the delete-reaping convention of `ChatImageStore`.

### Cloud SaaS Platform (server/)

- `server/` is a standalone Next.js App Router SaaS project in a git submodule, deployable on Vercel or self-hosted Node. It provides the official website (`/`, with a public pricing section backed by `GET /api/plans`), web login/registration (`/login`, `/register`), and a shared web console (`/console`)
- **Roles**: the FIRST registered user is automatically promoted to `admin` (enforced by a unique `system_bootstrap` marker inside the registration transaction; an idempotent startup migration in `lib/mongo.ts` promotes the earliest user of pre-SaaS deployments). Admins and users share one `messenger_session` JWT cookie; every admin-only route/page re-checks `role === "admin"` against the database via `requireAdminUser()` (stale-token privilege escalation is impossible). The old `ADMIN_PASSWORD` / `/admin` backend is deleted
- **Console**: users get 概览 (quota + usage + API key) and 财务 (card redemption + history); admins additionally get 全站概览, 套餐管理, 开卡, 上游管理, and 用户管理 (user list with email search over aggregated multi-entitlement quota, per-grant quota entitlements with optional validity days, first-expiring-first deduction for negative deltas, expiry extension across unexpired entitlements, and role change — self role change is blocked; plus a per-user detail page at `/console/admin/users/[id]` listing that user's quota entitlements with source/plan/remaining balance/expiry/status) in the same sidebar. Console pages are RSC with small client islands; the sidebar role comes from the database, not the JWT
- **Card-key billing**: admins define `plans` (quota tokens + validity days) and batch-issue `card_keys` (codes `MS-XXXXX-XXXXX-XXXXX`, cards embed a creation-time plan snapshot so later plan edits/deletes never invalidate outstanding cards). Users can hold MULTIPLE quota entitlements at once (`user_quotas` collection — one doc per redemption/admin grant/migration, each with its own `balance` and `expiresAt`, `expiresAt: null` = unlimited). Redeeming a card via `POST /api/console/redeem` atomically claims it (`unused → redeemed`), inserts a new entitlement (`balance = card.quotaTokens`, `expiresAt = redeemedAt + validityDays` — each plan's validity is independent), and writes the `redemptions` record. Consumption (`consumeQuota`/`deductUserQuota`) draws down unexpired entitlements first-expiring-first inside a transaction (floored at 0). Quota reads (`/api/auth/me`, console overview, `/v1/*` 402 gating, admin user list) always aggregate live: balance = sum of unexpired entitlements, expiry = latest among them. A startup migration converts each legacy user's single `quotaBalance`/`quotaExpiresAt` pair into one `source: "migrated"` entitlement and zeroes the legacy fields
- **AI API (OpenAI-compatible proxy)**: `/v1/models` and `/v1/chat/completions` authenticate with a per-user API key (`Authorization: Bearer sk-…`, regenerable in the console). Available models = the union of models served by enabled `upstreams` (no global catalog); `/v1/models` enriches each entry with non-standard `context_window` (tokens, 0 = unlimited; multi-upstream max) and `input_rate` / `output_rate` (multi-upstream min, 0 = free) fields resolved with the exact `resolveModelMeta` semantics via `getModelPlaza()` (override ON → custom value with models.dev fallback, OFF → models.dev). Requests relay to the highest-priority enabled upstream (failover on connect error/5xx before first byte); streaming responses inject `stream_options.include_usage` and pass upstream SSE bytes through verbatim while sniffing usage; after completion the server deducts `ceil(promptTokens × inputRate + completionTokens × outputRate)` from the user's unexpired quota entitlements (first-expiring-first) and writes a `usage_logs` document. Per-model metadata (context window + rates) resolves per model — `modelMeta[modelId].override` ON uses that entry's custom values, OFF uses live models.dev metadata (rates normalized against deepseek-v4.1-flash); zero rates = the call is free, zero context = unlimited. 402 (quota exhausted/expired), 401, 404, and 502 all use OpenAI `{"error":{...}}` bodies so the app's `extractHttpErrorMessage` displays them
- **Built-in provider model auto-sync (client)**: `CloudSyncRepository.ensureBuiltinProvider` now also auto-syncs the builtin provider's model list from `GET {server}/v1/models` (`syncBuiltinProviderModels`). The replacement is idempotent and preserves existing rows' Room `id` (so Agent model bindings survive) and `isEnabled` (user enable/disable choices); removed models disappear, new models get deterministic IDs (`"$BUILTIN_PROVIDER_ID:{modelId}"`), and `context_window` lands in `ModelEntity.contextWindow`. Sync is forced when the provider is (re)seeded/reconfigured or its model table is empty, otherwise throttled to once per hour in-process (`BUILTIN_MODELS_SYNC_INTERVAL_MS`); failures log a warning and never block login/sync
- MongoDB stores user documents plus versioned `agents`, `conversations`, and `providers` documents; conversations embed messages and providers embed models. Conversation documents additionally carry the 80% auto-summary state (`contextSummary` / `contextSummaryUntil` / `contextTokens` / `contextTokensAt`, all optional so legacy clients keep working) and provider model embeds carry optional `contextWindow` — mirrored in `conversationSchema` / `modelSchema` (`lib/validation.ts`), `ConversationDoc` / `ModelEmbed` (`lib/types.ts`), and `upsertConversation` (`lib/storage.ts`)
- Public Agent Market entries live separately in `market_agents`; they contain only portable Agent snapshots (name, avatar, prompt, and sampling parameters), never providers, model bindings, or API keys.
- Each user has a monotonically increasing `syncVersion`. Entity writes atomically increment it and stamp the changed document's `version`; deletes are `deleted: true` tombstones returned by `GET /api/sync?since=N`
- Server registration seeds the one required default Agent in the same transaction as user creation, so the cloud data preserves the default-agent invariant
- Avatar blobs live behind a pluggable storage layer (`server/lib/blob-store.ts`): the `vercel`
  backend (`lib/blob-vercel.ts`, vercel-blob-nonvercel pass-through — unchanged behavior for Vercel
  deployments) or the self-hosted filesystem backend (`lib/blob-fs.ts`, `BLOB_STORAGE_DIR`, default
  `./.blobs`, which replaced the removed `vercel-blob-emu` emulator). Selection via `BLOB_BACKEND`
  (`auto` = Vercel when `BLOB_READ_WRITE_TOKEN` is set, else `fs`). Both backends share the stable
  logical pathnames `avatars/users/{userId}.{ext}` and `avatars/agents/{agentId}.{ext}`.
  Replacements snapshot the prior blob, remove prefix-matched files, and restore the prior avatar
  if the new upload fails. Authenticated avatar GET routes stream content to mobile clients
  through ETag-conditional responses
- Authenticated entity APIs are `PUT`/`DELETE` `/api/agents/{id}`, `/api/conversations/{id}`, and
  `/api/providers/{id}`. Avatar APIs use `GET`/`PUT`/`DELETE` `/api/avatars/user` and
  `/api/avatars/agents/{agentId}`; GET requests authenticate the user and proxy private Blob content
- Account APIs include `PUT /api/auth/password` for authenticated password changes and `DELETE /api/auth/account` for permanent account deletion (now also cascades `redemptions` and `usage_logs`)
- MongoDB must be deployed as Atlas or a replica set because server writes (sync clock, redemptions, admin bootstrap) use transactions
- **Built-in cloud AI provider (client)**: on every login/`/me` refresh the client persists the user's `aiApiKey` and idempotently seeds/updates a local Provider with the reserved ID `BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"` (`CloudSyncRepository.kt`) pointing at `{serverUrl}/v1`. It is excluded from cloud sync on both the push side (`pushLocalSnapshot` + `AppContainer` change hooks) and the pull side (`applyDelta`), so devices never resurrect each other's copy; `hasLocalData()` ignores it so logins keep the auto-restore flow; logout/account deletion/server-URL change removes it; `ProvidersScreen` hides edit/delete for it. Room schema reuses the `providers` table via the fixed string ID (v12 also added `ModelEntity.contextWindow` for the auto-synced metadata)
- **Cloud settings UI (client)**: `CloudSettingsScreen` shows the account card, a 套餐 (plan) card (aggregate balance + latest expiry from `CloudUser.quotaBalance/quotaExpiresAt`, plus per-entitlement rows — plan name, or localized source label for admin grants / card redemptions / migrated quotas — from the `quotaEntitlements` list `/api/auth/me` now returns), an in-app 兑换卡密 (card-key redemption) card — mirroring the web console flow: `previewRedeemCard` (`POST /api/console/cards/preview`) first shows the card snapshot (code/plan/quota/validity) in a confirmation dialog, then `redeemCard` (`POST /api/console/redeem`) commits and refreshes the user via `/api/auth/me` so the plan card reflects the new entitlement — and the sync/security sections. The cloud instance URL is no longer edited inline: a settings icon in the Cloud settings TopAppBar opens the `CloudServerScreen` secondary page (URL field + use-default + save via `SettingsViewModel.setCloudServerUrl`), which both layouts share via in-screen state (no nav route). The sub-page registers `presentation/platform/BackHandler` (expect/actual: `androidx.activity.compose.PredictiveBackHandler` on Android — the wrapped content follows the gesture with a slide-out + fade animation, sliding toward the gesture's starting edge while fading out; requires the manifest's `enableOnBackInvokedCallback`; no-op passthrough on Desktop) so the system back gesture returns to the Cloud settings page instead of popping the route
- The `CloudSyncRepository` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/cloud/CloudSyncRepository.kt`) uses the session cookie and a per-account DataStore cursor to pull `GET /api/sync?since=N`; it applies tombstones transactionally, flattens provider models and conversation messages into Room, and pushes complete entity snapshots to the corresponding `PUT` endpoints. Its companions `CloudModels.kt` and `CloudApiClient.kt` live in the same `shared/.../data/cloud/` directory. `CloudUser` carries optional SaaS fields (`role`, `aiApiKey`, `quotaBalance`, `quotaExpiresAt`) with defaults for backward compatibility
- Multimodal message images ride inside `messages[].partsJson` (base64 `dataUri` per image part) in both directions; the server stores the string verbatim and the client rehydrates missing local files from the `dataUri` after a pull (see "Multimodal image cloud sync" above)
- Mobile local repository mutations are debounced into cloud synchronization requests; deleted entities are retained as account-scoped pending-delete markers until the server tombstone write succeeds
- User and agent avatars are uploaded as multipart `file` parts to the dedicated avatar endpoints;
  authenticated GET endpoints proxy private Blob content. Mobile avatar URLs are downloaded during
  Cloud Sync into `filesDir/cloud_avatars`; Room/DataStore store local avatar paths so `AgentAvatar`
  never depends on a network request
- Market list/detail, publish, update, and unpublish APIs require a Messenger session. Market avatars remain private Blobs and are streamed through authenticated `/api/market/agents/{id}/avatar` routes.
- Imported Agents retain their market entry/version link in Room and private cloud sync metadata, but always clear the model binding and follow-default flags. Market updates require explicit user confirmation and preserve the local model binding.

### Context window auto-summarization (80%)

- `ChatViewModel` (mobile + desktop chat flow) auto-summarizes context before sending when the active model declares a `contextWindow` (> 0): the projected context (exact last-round usage from `Conversation.contextTokens`/`contextTokensAt` plus estimates for newer messages, or a pure token estimate when no usage has been recorded — CJK ≈ 1 token/char, others ≈ 4 chars/token, ~800 tokens per image) reaching **80% of the context window** triggers folding all SENT messages older than the last `SUMMARY_KEEP_COUNT` (10) into a summary via a non-stream `createChatCompletion` call
- The folded summary is stored on the conversation (`contextSummary` + `contextSummaryUntil` timestamp cutoff) and injected at request time as a leading SYSTEM message (`buildApiContextMessages`); messages at/after the cutoff are sent verbatim. The chat history UI is never modified — only the API payload is compressed. The final `takeLast(20)` history cap stays as the fallback for models without a context window
- Token usage per round is recorded after each stream `Done`: the builtin cloud provider's usage chunk (server passes `stream_options.include_usage` results through verbatim; parsed as `ChatCompletionChunkDto.usage` → `ChatStreamEvent.Done.usage`) gives exact prompt+completion tokens; other providers fall back to estimates. Reasoning-format writes re-fetch the conversation row so they never clobber concurrently-written summary state
- Summarization failures surface a snackbar error (`error_context_summarize_failed`) but never block the send. The Wear chat path (phone-side `MobileWearSyncManager`) does not summarize

### Agent roles and the built-in title agent

- **Agent page navigation (per-Agent chat lists)**: tapping an Agent on the Agents page opens `AgentConversationsScreen` (route `agent_conversations/{agentId}`, `shared/.../presentation/ui/agents/AgentConversationsScreen.kt`) — that Agent's conversation list (FAB creates a new chat bound to the Agent; rows reuse the shared `ConversationListItem` from `presentation/ui/components/` with rename/clone/switch-agent/delete menus; no multi-select here). The top bar shows the Agent name and a right-edge settings icon that pushes `AgentEdit`; title-role agents still open the editor directly on tap. On non-Compact widths the same content is hosted inside the `AgentsDualPaneScreen` right pane (list ⇄ editor ⇄ chat, no back-stack pushes). The home Conversations list is now FIXED to all conversations — the former top-bar Agent filter dropdown was removed (`ConversationsViewModel.conversations` is a plain `getAll()` stream, the FAB always opens the agent picker); per-Agent filtering lives only in `AgentConversationsScreen` (`AgentConversationsViewModel`)
- **Agent description**: `Agent` carries a user-facing `description: String` field (DB v20 `agents.description`, default `''`); it is display-only (Agents list rows show it instead of the system-prompt preview when present, and `AgentEditScreen` has a Description field under the name) and is NEVER sent to the model. It mirrors through cloud sync end-to-end (`CloudAgentDocument`/`CloudAgentRequest` + server `agentSchema`/`AgentDoc`/`AgentUpsertInput`/`upsertAgent`, pull-side default `''` for old payloads; `isDefaultAgentBaseline` counts a non-blank description as touched local data) but is NOT part of the Agent Market snapshot
- `Agent` carries an internal `role: String` field (`Agent.ROLE_CHAT = "chat"` default / `Agent.ROLE_TITLE = "title"`, DB v15 column `agents.role`); the "default" role is the pre-existing `isDefault` boolean. Roles are surfaced through a **user-facing role picker** in `AgentEditScreen` with three options: Regular / Default / Title Generator. Default and Title Generator are **single-holder roles**: their pickers are locked on the current holder, and the only way a role moves is another Agent claiming it — at save time (`AgentEditViewModel.save()`, `pendingRole`) the previous holder automatically falls back to a regular Agent (default claim swaps `isDefault`, title claim swaps `role`), keeping the invariants "exactly one default Agent" and "exactly one title generator". The `role` field is mirrored through cloud sync end-to-end (client `CloudAgentDocument`/`CloudAgentRequest` + server `agentSchema` `z.enum(["chat","title"])` / `AgentDoc` / `AgentUpsertInput` / `upsertAgent`); pull-side mapping falls back to `chat` when the field is absent
- The **title generator holder** (found by `role == ROLE_TITLE`, not by fixed ID) powers LLM conversation titles. To guarantee one always exists, the built-in seed agent (`Agent.BUILTIN_TITLE_AGENT_ID = "builtin-title-agent"`, display name "标题生成" hardcoded like "默认 Agent" — baselines compare literals, no localization) is seeded **only when no title-role holder exists at all** via `CloudSyncRepository.ensureBuiltinTitleAgent()` — called from `AppContainer.initializeLocalAndCloudData()` at startup (offline included), after every full-sync `replaceLocal`, and from `clearAllDataAndReinit()`. Once the user transfers the title role to a regular Agent, the built-in row is not re-seeded (the synced holder takes over). The built-in agent itself never participates in cloud sync (push/pull/`hasLocalData` exclusions replicate the builtin cloud provider pattern), and `applyDelta` additionally demotes other local title holders when a synced title-role agent arrives and protects the local holder from remote delete tombstones
- **UI protection**: `AgentRepositoryImpl.delete` blocks deleting the built-in ID, the default agent, and the title-role holder (roles move only by transfer, so nothing is lost); `clone` resets `role` to chat. `AgentsScreen` shows a `agents_builtin_badge` ("内置"/"Built-in") badge on the built-in agent and a `agents_title_badge` ("标题生成器"/"Title Generator") badge on the holder; title-role agents open the editor on tap (never switchAgent), and Clone/Delete/long-press multi-select are hidden or disabled for the default, the built-in, and the holder. `AgentEditScreen` locks the name only for the default agent, hides the follow-default toggles for the title holder (the generator always uses its own system prompt/model), and hides the Agent Market section for the default agent. All "Select Agent" pickers (mobile switch dialogs, Wear new-chat) filter out title-role agents (`ConversationsScreen` `selectableAgents`, `MobileHttpServer.handleSyncRequest`)
- **Title generation flow**: the send-time naive truncation title was removed. On the first non-blank stream `Done` (mobile `ChatViewModel.generateResponse` + `retrySend`, and the phone-side Wear handler alike), `ConversationTitleGenerator.launchGenerateIfNeeded` (fire-and-forget on the app scope, Mutex-serialized) runs when the conversation is still "untitled" (blank / "新对话" / "New Chat"): it calls non-stream `createChatCompletion` with the holder's systemPrompt and its own model (falling back to the chat turn's provider/model pair), strips think blocks / wrapping quotes / newlines and caps length (pure helpers unit-tested in `ConversationTitleGeneratorTest`), and re-reads the conversation before writing (never clobbers a manual rename or concurrent summary state). On failure (API exception or empty result) it **surfaces a localized error** (`error_title_generate_failed` / `error_title_generate_failed_detail`, shown as the chat snackbar via the `onError` callback) **and still falls back** to truncating the first user message; the Wear path passes no callback (logs only)

### Projects (a project IS a workspace)

- A `Project` is a named workspace owning a set of conversations. **Only a conversation that belongs to a project may call the workspace-bound tools** — the terminal and the five workspace tools (glob/grep/read/edit/create). MCP tools are not workspace-scoped and stay available everywhere. A plain conversation declares no tool at all for them, which the model must see rather than discover through a failed call
- **Storage**: `projects` table + `conversations.projectId` (FK, `ON DELETE SET NULL`) in BOTH stores — Rust `messenger-store` schema v2 (`StoredProject`, `StoredConversation.project_id`; the v1→v2 `ALTER TABLE` is guarded by `schema::has_project_id_column` because SQLite has no `ADD COLUMN IF NOT EXISTS`) and Room v21 (`ProjectEntity`/`ProjectDao` + `MIGRATION_20_21`, registered in `LocalStores.jvmShared.kt`). Deleting a project KEEPS its conversations, which become plain ones (and the store emits a `Conversation` event for the orphans so reactive chat lists re-group)
- **Enforcement is a declaration gate, not an argument policy**: `BuiltinTool.workspace_required` marks the workspace-bound tools, and `resolve_request_tools(..., workspace_available)` drops them when there is none. Both boundaries apply it again (`messenger-ffi`'s `build_turn_request` and `messenger-wasm`'s) off `TurnConfig.has_workspace`, so the declared list can never offer a tool whose cwd does not exist. The TUI passes `workspace.is_some()` from the conversation's project
- **Workspace resolution**: `createBuiltinChatTools(workspaceRoot)` takes the project's directory; `null` means the conversation belongs to no project and registers NOTHING (there is deliberately no fallback to the platform default — that would hand the conversation a directory it never asked for). `TerminalTool` passes it as the shell cwd, `WorkspaceTool.allFor(root)` binds the five file tools to it, and `executeWorkspaceOperation(operation, root)` carries it down to the platform layer. Desktop falls back to `~/.messenger/agent-runtime/workspace` only when a configured root is not a directory. `ChatViewModel.resolveWorkspaceFor(conv)` reads a FRESH database row (never the `WhileSubscribed` `project` StateFlow, which resets after 5 s without subscribers) because the workspace decides whether tools can execute at all
- **Android**: the companion `:runtime` runs under its own UID, so the workspace root is passed per call over AIDL — `IShellService.workspace{Glob,Grep,Read,Edit,Create}` each take a leading `String root`, and `ShellService.ifCallerAllowed(root, …)` rejects a root that is not an existing directory instead of silently falling back to the companion's own workspace (a project created on another device must report "unavailable", not operate somewhere else). `TerminalActivity`'s own session is unaffected
- **The model is told the cwd**: `TurnRequest.workspace_note` (a localized `chat_workspace_note`) is appended to the system prompt by `with_workspace_note`, because nothing else in the request payload carries the directory
- **Creating a project**: the workspace folder may be given explicitly or left blank; when blank the name is normalized into a directory name (`Project.workspaceFolderName` → `normalizeWorkspaceFolderName`, "Messenger UI" → `messenger-ui`, falls back to `project`) and shown to the user before saving. **In the TUI the default workspace is the process CWD** (`config::current_dir_string`) — a terminal client is normally launched inside the tree the user wants to work on — and the session CREATES that CWD project automatically on startup (`store_ops::ensure_cwd_project`, see "Agentic entry point"), so a fresh terminal is immediately usable without any project setup. A blank project-name field in the TUI form falls back to the workspace directory's own name.
- **UI**: both chat lists (`ConversationsScreen` and `AgentConversationsScreen`) render a Projects section (`projectSection` in `ui/components/ProjectListItem.kt`) above a Recent Conversations section, and tapping a project opens the second-level page `ProjectConversationsScreen` (route `project_conversations/{projectId}?agentId={agentId}`; the `agentId` argument is set when entering from an Agent chat list, which then shows only that Agent's conversations in that project). Recent lists every recent conversation regardless of project, so a non-project chat is one tap away. `ConversationsViewModel`/`AgentConversationsViewModel` expose `projects`, `projectConversationCounts` and `recentConversations` (`RECENT_CONVERSATION_LIMIT = 10`)
- **Sync**: `projectId` rides the conversation document (`CloudConversationDocument.projectId` / `CloudConversationRequest.projectId`, skipped when absent) and projects sync as their own collection (`CloudProjectDocument`/`CloudProjectRequest`, `PUT`/`DELETE /api/projects/{id}`, `projectSchema`/`ProjectDoc`/`upsertProject`/`softDeleteProject`, `collection=projects` on `/api/sync`, account deletion cascades). Projects are applied BEFORE conversations on pull — the FK would otherwise reject a conversation referencing a not-yet-created project. All fields are optional/defaulted so old clients keep working

### Tool calling (function calling) and the built-in terminal tool

- **Built-in terminal and workspace tools**: `domain/tool/TerminalTool.kt` (function name `terminal`, single `command` string argument) runs through the expect/actual `executeShellCommand` (`domain/tool/ShellExecutor.kt`). Desktop uses Windows PowerShell (UTF-8) or `/bin/sh`, and — like the Android runtime — defaults the process cwd to the agent workspace (`~/.messenger/agent-runtime/workspace`) so a terminal command sees files the workspace tools created there; the 60-second timeout and caller cancellation both TERMINATE the process (a live process holds its stdout pipe open, so a blocking wait hung the turn). Android routes execution through the `:runtime` companion app (see "Android agent runtime" below) — there is deliberately NO fallback shell; when the companion is not installed the agent tool list is empty (feature disabled) and 设置 → 高级 → 终端 reports that the companion app is missing. Commands start with a clean environment from the workspace inside the companion's own data directory; workspace file operations are brokered over AIDL. Commands have a 60-second timeout, bounded output drain, and 10,000-character tool-result truncation. **Tool arguments are NEVER pre-screened** — the former `ShellCommandPolicy` (shell operators, substitutions, interpreters, redirection, absolute-path and traversal rejection) was removed entirely on both the Kotlin and Rust sides. Enforcement is SANDBOX-BASED: the sandbox confines what an execution can actually touch (Android: the companion runtime's own UID + data directory; desktop: the user process), reads are unrestricted in both modes, and only writes are gated by the mode (edit/create simply not declared in read-only mode). Shared `WorkspaceTool` operations resolve paths relative to the agent workspace unless absolute and accept any path the sandbox can access (glob patterns match workspace-relative paths; absolute patterns match absolute paths) — resource bounds only (4 MiB file cap + 20k-file scan cap for glob/read/edit/create; grep is delegated to the bundled ripgrep with no per-file/scan caps, bounded by `maxResults`). `ChatTool` gained a `writeAccess` property (default false) — `WorkspaceTool` overrides it for `edit`/`create` so request-time filtering can classify write tools.
- **Bundled ripgrep for the grep tool**: the `grep` workspace tool delegates to pinned ripgrep 14.1.1 on BOTH platforms — no more hand-rolled Java-regex line scanner. The grep `path` argument scopes the search to any file or directory (absolute or workspace-relative, default `.` = the whole workspace) and a `fixedString` boolean switches the pattern to literal matching. Output keeps the `path:line:text` contract with `(results truncated at N)` — rg runs with `--null` so the path field is NUL-terminated and a colon inside a matched line survives (plain `--no-heading -n` emits `path:line:text`, where any later colon reads as a field separator and every post-processing heuristic silently ate the text before it); hidden files/directories are searched (`--hidden --no-ignore -g '!.git'`), binary files are skipped by rg's detection. **Desktop**: the four `rg` binaries (windows-msvc x86_64, linux-gnu x86_64/aarch64, darwin x86_64/aarch64) ship as bare files under `shared/src/desktopMain/resources/ripgrep/` (downloaded from the official GitHub release, SHA-256 verified against the release `.sha256` sidecars); `domain/tool/DesktopRipgrep.kt` extracts the host-triple binary to `~/.messenger/agent-runtime/bin/rg` on first use and falls back to an `rg` on PATH when the bundled asset is missing. **Android**: the three cross-compiled (cargo-ndk, NDK 29, `--platform 30`) and pre-stripped binaries are committed as `runtime/src/main/jniLibs/<abi>/librg.so` (arm64-v8a/armeabi-v7a/x86_64) — a static executable riding AGP's per-ABI jniLibs split (`keepDebugSymbols += "**/librg.so"` keeps AGP's strip task off it, verified byte-identical inside the APK); `TermuxRuntime.ripgrepBinary` copies `librg.so` from `nativeLibraryDir`/APK split entry to `<prefix>/bin/rg` (on PATH for terminal sessions too) without requiring the bootstrap install, and `WorkspaceOps.grep` runs it with the workspace as cwd (`--path-separator /`, output normalized to workspace-relative paths; a single-file search root is prefixed back since rg omits the filename for file arguments). The AIDL signature is `workspaceGrep(root, pattern, path, fileGlob, caseSensitive, fixedString, maxResults)` (every workspace method takes a leading workspace `root`, see the Projects section) (IShellService.aidl, duplicated in androidApp).
- **Agent mode (read-only vs writable)**: the chat input bar's `+` button toggles a WeChat-style bottom panel (`ChatInputBar`) — the input row (and the message list above it) shifts up while the panel expands in place between the list and the input row (`AnimatedVisibility` expand/shrink from the bottom edge + fade, no-bouncy spring; the `+` tints primary while open, focusing the text field or tapping `+` again collapses it, and a `presentation/platform/BackHandler` intercepts system back so it collapses instead of popping the route). The panel is a WeChat-style tile grid (rounded-square icon tile + small label below, 4 fixed quarter-width slots per row, entries left-aligned; `FunctionPanelEntry`): 添加图片 (photo picker entry) and a single 只读模式/可写模式 click-to-toggle tile (icon reflects the current mode: Lock vs LockOpen) — this mode REPLACED the old manual/auto execution toggle, which was removed entirely along with the `AppPreferences.toolAutoConfirm` preference. These entries are gated on the PLATFORM only (`viewModel.toolsAvailable`) — deliberately NOT on the agent's tools switch, so the control stays reachable before the agent enables tools (it just has no request effect until it does). The mode is stored PER-CONVERSATION (`Conversation.writable`, DB v18; formerly a global `AppPreferences.agentWritable` DataStore key, now deleted) and exposed as `ChatViewModel.agentWritable` / `setAgentWritable` (which updates the conversation row without touching `updatedAt`). The mode only decides which tools are declared: tool calls execute automatically in BOTH modes — there is no per-call confirmation dialog (the former consent gate, `ChatTool.requiresUserConfirmation`, and the `ToolConfirmDialog` were removed entirely). In read-only mode `launchChatTurn` excludes `writeAccess` tools (`edit`/`create`) from the request's `tools` and keeps the terminal's read-only declaration (inspection-steering description); in writable mode the request re-instantiates `TerminalTool(readOnly = false)` — swapping the model-facing description to the unrestricted variant — and re-declares the write tools. Execution is identical in both modes: arguments are never pre-screened, the sandbox confines actual operations (Android: companion UID; desktop: the user process). The turn-time mode decisions read the conversation row from the database (the send-time snapshot for the request build, a fresh read per tool execution), NOT the `agentWritable` StateFlow — a `stateIn(WhileSubscribed(5000))` UI flow that resets to `false` when the app is backgrounded for over 5 seconds. MCP tools are mode-agnostic (their write nature is not classified) and remain governed by the per-tool agent config.
- **User-facing terminal (companion app)**: 设置 → 高级 → 终端 no longer hosts a terminal in the main app — the item calls `openRuntimeTerminal()` (`presentation/platform/PlatformServices.kt` expect/actual: Android starts the companion's `TerminalActivity` with an explicit component intent + `FLAG_ACTIVITY_NEW_TASK` after a `resolveActivity` check, Desktop returns false) and shows a toast with `terminal_not_installed` when the companion is absent. `runtimeTerminalSupported` gates the item (Android true, Desktop false — the platform has no runtime app, so the item is hidden and the in-app `TerminalScreen`/`TerminalViewModel` were deleted). The companion app IS the terminal: `TerminalActivity` (launcher activity, `singleTask`, `adjustResize`) hosts the vendored Termux `TerminalView` + `ExtraKeysBar` (Termux's default `extra-keys` layout, one-shot CTRL/ALT, long-press `-` → `|`) with a toolbar showing the session title plus keyboard/paste actions. `TermuxRuntime.createTerminalSession` runs the bootstrap's `bin/bash` over a real pseudoterminal (`cpp/termux.c` JNI, `libtermux.so`): job control, curses UIs, signals and window resizing all work (`stty size` reports the real viewport). Because the shipped bash resolves `etc/profile`/`etc/bash.bashrc` at compile time to the canonical Termux prefix (and long options must precede short ones), the session is spawned as argv `[<prefix>/bin/bash, --rcfile, <prefix>/etc/messenger.bashrc, -i]` — argv[0] MUST be the program name because the JNI hands the Java array straight to `execvp`, and a leading `-` in argv[0] makes bash treat itself as a login shell and shift the remaining arguments (the rc file then gets executed as a script and the session dies with status 1). That generated rc file replays Termux's login sequence (etc/profile → profile.d → bash.bashrc → `~/.bashrc`) against the extracted prefix. User-typed commands are never policy-filtered (neither are agent tool calls — there is no argument policy anymore; the sandbox is the companion's own process). The session lives as long as the terminal activity, is killed in `onDestroy`, and a tap on a finished session starts a new one.
- **Agent tool configuration (per-tool switches + follow default)**: `AgentEditScreen` replaces the old single `toolsEnabled` toggle with a「工具」entry row (hidden for the title-role holder) that opens an in-screen tool config sub-page (overlay + `presentation/platform/BackHandler`, same pattern as the Cloud settings server sub-page). The sub-page hosts the master `toolsEnabled` switch (DB v16 column, default false) and one switch per registered tool. Following the default Agent (`Agent.toolsFollowDefault`, DB v17 — non-default Agents can mirror the default Agent's whole tool config, hidden for the default Agent itself and the title-role holder) uses the SAME left-right swipe takeover gesture as the model/sampling follow fields (`FollowableTarget`): swipe right over the config area to take over the default Agent's tool config (the whole master + per-tool area gets the read-only takeover mask with the 已接管 hint), swipe left to restore custom config — the dedicated follow switch row was removed. Per-tool state lives in `Agent.toolsConfig` (DB v17 JSON `Map<String, Boolean>`, encoded/decoded by `data/util/ToolsConfigCodec.kt`); 「默认全开」is expressed by ABSENT keys — the map only records explicitly disabled tools, so newly added tools are automatically enabled. Effective resolution helpers live on `Agent` (`effectiveToolsEnabled` / `effectiveToolEnabled`); `ChatViewModel.resolveEffectiveAgent` merges the follow fields so `launchChatTurn` filters the registry with `agent.toolsConfig`. The conversation can override the Agent's whole tool config per conversation: `Conversation.overrideToolsEnabled` + `Conversation.overrideToolsConfig` (DB v18/v19, edited in `ConversationSettingsScreen`'s parameter-override section via a 「工具」 entry row that opens an in-screen tools sub-page — same overlay + `presentation/platform/BackHandler` + `ToolToggleCard` pattern as the Agent editor's; the entry row itself sits in a swipe-override target like every other override row: swipe right to take over with the Agent's current values as the starting point, swipe left to restore the Agent's config, and the sub-page is a plain master + per-tool editor reachable only while the override is on). Both fields mirror through cloud sync (`overrideToolsEnabled` / `overrideToolsConfig` alongside `writable` in `CloudConversationDocument`/`CloudConversationRequest` + server `conversationSchema`/`ConversationDoc`/`ConversationUpsertInput`/`upsertConversation`) and `resolveEffectiveAgent` applies them after the follow merge (`conversation.overrideToolsEnabled ?: agentWithDefault.toolsEnabled`, `conversation.overrideToolsConfig ?: agentWithDefault.toolsConfig`). All three fields (`toolsEnabled` / `toolsFollowDefault` / `toolsConfig`) mirror through cloud sync end-to-end (`CloudAgentDocument`/`CloudAgentRequest` + server `agentSchema`/`AgentDoc`/`AgentUpsertInput`/`upsertAgent`, pull-side defaults false/empty). A request carries `tools` when the effective master switch is on AND the platform registry is non-empty — `supportsToolCalling` metadata is deliberately NOT a gate (models.dev metadata missing → false would silently disable the feature; unsupported providers surface a visible API error instead)
- **Agent loop** (`ChatViewModel.launchChatTurn`, shared by `generateResponse` and `retrySend` after de-duplicating their previously identical stream loops): on a `Done` with tool calls the round's text + `ContentPart.ToolCall` parts persist as a NEW assistant row; each call then executes automatically — there is NO confirmation dialog (the consent mechanism was removed; tools run in both modes without asking) — persisting a `role=TOOL` row (first as `SENDING` = "running" card, then updated with the result; cancellation mid-run writes an interrupted marker via `NonCancellable`). The loop rebuilds context and continues until a final text round (which lands in the original placeholder row and triggers title generation), an API error, or user cancellation — tool rounds are unbounded (no round-count cap). Unknown tool names and malformed arguments are returned to the model as error results for self-correction
- **Persistence**: tool turns round-trip through `partsJson` with NO Room schema change — `ContentPart` gained `ToolCall(callId, name, arguments)` and `ToolResult(callId, name, output, isError)` subtypes encoded by `ContentPartCodec` as `"tool_call"` / `"tool_result"` part types (older clients drop unknown types; the server treats `partsJson` as an opaque string, so cloud sync carries them verbatim). `buildRequestMessages` re-sends an assistant row with ToolCall parts as an assistant message with `tool_calls`, and a TOOL row as `role:"tool"` + `tool_call_id`; `buildApiContextMessages` drops leading orphan TOOL messages after its takeLast trim (OpenAI rejects unpaired tool messages)
- **UI**: `ChatScreen.buildChatItems` 把一个完整的代理回合收编为一个 `ChatListItem.ToolGroupItem`（连续的 工具轮 assistant 行 → TOOL 结果行 → 最终文本行）。回合进行中 Rust 循环在回合开始就落库承载最终文本的占位行（时间戳早于工具轮行，取消的回合也保留该行序），因此收编同样接受「占位行在前」形态：占位行后紧邻工具轮行时作为回合最终文本行并入，否则流式期间会拆成两个气泡且新气泡不跟随滚动（`collapseAgentTurn` 两种形态共用）。在 Android 端由 `:renderer-android` 原生 View 渲染（`MessageView` + `DocumentView`，带动画折叠工具卡），在 Desktop 端由 `DesktopDocumentBubble` + `DesktopDocumentView` 渲染为同属一个气泡的 Document 结构块与工具卡片。占位行流式中同样并入回合气泡，正文在卡片之下原地续写（读 `streamingContent`，DB 行在 Done 前不更新）。ERROR 占位行不并入回合（独立错误气泡保留重试入口）；回合气泡挂与普通消息一致的长按/右键菜单（复制/重新生成/删除，锚定最终文本行，无最终文本时锚定最后一个工具轮行）。孤儿 TOOL 行（无 assistant 轮可挂的历史异常）仍以独立卡片兜底渲染。The chat input bar's old standalone manual/auto execution toggle was REMOVED — the read-only/writable Agent mode in the `+` panel subsumes it (see the "Agent mode" bullet above). The leftmost `+` button is NOT a direct photo-picker trigger anymore — it toggles the bottom function panel described in the "Agent mode" bullet. In the Agent edit tool-config sub-page the built-in `terminal` row shows a localized neutral description (`tool_terminal_desc`) instead of the model-facing English policy text. The Wear chat pipeline (`MobileWearChatHandler`) bypasses ChatViewModel and does NOT execute tools
- **Tools Settings and MCP (Model Context Protocol)**: 设置页提供「MCP 服务器」(MCP Servers) 设置页面（`ToolsSettingsScreen`，路由 `Screen.ToolsSettings`；原「工具」页面在全局内置工具开关移除后改为 MCP 专属，入口标题 `settings_tools`，页面内不再有分区小标题），支持：
  - **MCP 服务器管理**：支持添加、编辑、删除以及单个启用/禁用 MCP 服务器（配置持久化在 DataStore `mcp_servers_json` 中，由 `McpManager` 统一管理）。
  - **传输模式**：
    - **Command (stdio)**：在桌面端通过 `ProcessBuilder` 启动命令；在 Android 端通过 AIDL 委托给 `:runtime` 伴随应用（`IShellService.startMcpProcess` / `sendMcpInput` / `stopMcpProcess`），在 targetSdk 28 环境中执行并使用 stdin/stdout 进行 JSON-RPC 2.0 通信。
    - **SSE / HTTP**：通过 Ktor HTTP/SSE 客户端连接远程 MCP 端点。
  - **运行时聚合**：`AppContainer.availableTools` 统一聚合内置工具及活跃 MCP 服务器所暴露的工具（内置工具不再有全局开关，改由每个 Agent 的工具配置子页控制，见上文「Agent tool configuration」）。

### Chat bubble rendering (Android & Desktop)

- 聊天气泡渲染已全面告别 `llm-typewriter`，统一采用 TARGET.md Document AST 模型：
  - **Android 端**：由 `:renderer-android` 的原生 View 渲染（`RecyclerView` + `MessageView` + `DocumentView`），直接消费 Rust Core 的 `DiffBatch` 增量流（零 Recomposition 开销），LaTeX 公式由纯 Rust RaTeX-CMP 引擎离线绘制，代码块高亮由 syntect 引擎驱动。
  - **Desktop 端**：由 `DesktopDocumentBubble` 与 `DesktopDocumentView` 以 Compose 结构化 Block（Paragraph, Heading, Code, Math, Think, ToolCall, Quote, Table, ListBlock, Divider）进行渲染，通过 `key(block.id)` 保证 finalized 块在流式生成时不发生 Recomposition；桌面端具备内置的高性能纯 Kotlin Markdown-to-Blocks AST 解析器，无需外部打字机依赖。
- `ChatViewModel` exposes `streamingContent: StateFlow<String?>` plus `streamingMessageId: StateFlow<String?>`。每个 SSE `Content` 事件追加至 `currentContent` 并通过 `_streamingContent` 发送，完成时入库后重置。
- JitPack 仓库 (`https://jitpack.io`) 现在仅为 `ucrop` (`com.github.Yalantis:ucrop`) 保留。

## Hard Constraints

These constraints MUST be followed at all times:

1. **Navigation route syntax**: `ProviderEdit` and `AgentEdit` routes in `Screen.kt` MUST use Navigation Compose optional parameter syntax: `provider_edit?providerId={providerId}` and `agent_edit?agentId={agentId}`. Do NOT use required parameter syntax `{parameter}`.

2. **Default Agent invariant**: The database MUST always contain exactly one Agent with `isDefault=true`. This default Agent CANNOT be deleted.

3. **Model-required chat flow**: When an Agent's `defaultModelId` is null (model not set), all chat operations (`sendMessage`, `retrySend`, `regenerateMessage`) MUST:
   - Prompt the user to set a model first
   - Abort the send flow (do NOT insert user message, do NOT modify message status, do NOT delete any message)

4. **Error visibility**: API errors MUST NOT be silently retried. Errors MUST be displayed via both:
   - Snackbar notification
   - AI message bubble showing error details

5. **IME behavior**: Chat screen requires `android:windowSoftInputMode="adjustResize"` in AndroidManifest to avoid IME input box position issues.

6. **Edge-to-edge**: The app implements edge-to-edge design:
   - `Theme.kt` calls `WindowCompat.setDecorFitsSystemWindows(window, false)`
   - `themes.xml` configures transparent status bar
   - `MainScaffold.kt` only applies bottom padding (for bottom nav), letting each screen handle top/left/right insets via its own Scaffold+TopAppBar

7. **Built-in Kotlin migration**: AGP 9 built-in Kotlin is enabled. Do not apply `org.jetbrains.kotlin.android` or `kotlin("android")` in Android modules. Prefer `com.google.devtools.ksp` for supported processors like Room; use `com.android.legacy-kapt` only if annotation processors cannot yet move to KSP. The `mobile` module has been split into `shared` (KMP library using `com.android.kotlin.multiplatform.library`) + `androidApp` (Android application shell using `com.android.application` with AGP 9 built-in Kotlin) + `desktopApp` (Desktop application shell using `org.jetbrains.kotlin.jvm`). This split is irreversible — do NOT attempt to merge them back into a single `mobile` module. AGP 9.3.1 does not support `com.android.application` combined with `org.jetbrains.kotlin.multiplatform` in the same module. R8 / minification is disabled on `:androidApp` and ProGuard is disabled on `:desktopApp`; R8 only runs on `:wear` (see "R8 Troubleshooting").

8. **Wear companion scope**: The Wear app MUST stay focused on chat only. Agents are synced from mobile, and provider/model/settings management stays on mobile. The `wear` module is unaffected by the KMP split — it remains Android-only using `com.android.application` + `kotlin-compose` (AGP 9 built-in Kotlin) and is NOT a KMP module.

## Engineering Conventions

1. **API error handling**: All API calls must handle `HttpException` and extract error messages using `extractHttpErrorMessage()` from the response body's `error.message` field.

2. **SSE stream handling**: SSE stream processing must include a `hasFinished` flag. If the stream ends without receiving a Done or Error event, an Error event must be emitted proactively.

3. **Screen structure**: Each screen uses Material 3 `Scaffold` with `TopAppBar` to properly handle status bar insets. Do NOT add top-level padding in screens — the Scaffold handles it.

4. **Nested Scaffold caution**: Avoid nested Scaffold double-inset issues. The outer `MainScaffold` only applies bottom padding for the bottom navigation bar.

5. **Versioning**: Version code is derived from git commit count (or `VERSION_CODE` env var). Version name is `v{yyyyMMdd}` from the latest commit's date for reproducibility (or `VERSION_NAME` env var). Release tags follow `v*` pattern.

6. **Dependency management**: Use version catalog (`gradle/libs.versions.toml`) for all dependency versions. `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, and `desktopApp/build.gradle.kts` all consume the version catalog. Do NOT globally exclude the standalone `com.google.guava:listenablefuture:1.0` stub (pulled by `androidx.concurrent:concurrent-futures` via `androidx.core`/`androidx.profileinstaller`): it IS the runtime provider of the `com.google.common.util.concurrent.ListenableFuture` interface that `androidx.profileinstaller`'s App Startup initializer (pulled in by Compose on every app module, `:wear` included) extends — a former global exclude crashed the wear app on launch with `NoClassDefFoundError` in `ProfileInstallerInitializer`. The stub collides with nothing: full Guava only ever appears on build-time tooling classpaths (KSP/Room, lint), which `checkDuplicateClasses` never compares; the historical collision (guava-18.0 via AndroidMath) is gone after the RaTeX migration. JitPack 仓库现在仅为 `ucrop` (`com.github.Yalantis:ucrop`) 保留，不再为 AndroidMath。

7. **Settings entry format**: Every entry (`ListItem`) in `SettingsScreen` (and settings-section lists generally) MUST carry BOTH a `title` and a `subtitle`. The `title` is the feature name ONLY — never append explanations or qualifiers to it. The `subtitle` (desc) states what the entry does in purpose-only terms — NO usage-irrelevant detail such as implementation or styling notes (e.g. the Terminal entry's desc was trimmed from "Opens the Messenger Runtime app: a Termux-style interactive shell …" to "Opens the Messenger Runtime companion app"; the "Termux-style" detail belongs in docs, not the settings UI).

## Editing Guidelines

### Adding a New Screen

1. Add route to `Screen.kt` sealed class with `createRoute()` helper (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/Screen.kt`)
2. Add composable destination in `NavGraph.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/NavGraph.kt`)
3. Create screen composable in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/<feature>/`
4. Create ViewModel in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/viewmodel/` if needed
5. If it's a bottom-level route, add to `BottomLevelRoutes.kt`

### Adding a New Database Entity

1. Create entity in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/entity/`
2. Create DAO in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/dao/`
3. Add entity to `MessengerDatabase` entities array and add DAO abstract function (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/MessengerDatabase.kt`)
4. Create domain model in `shared/src/commonMain/kotlin/cc/ptoe/messenger/domain/model/`
5. Create repository interface in `shared/src/commonMain/kotlin/cc/ptoe/messenger/domain/repository/`
6. Create repository implementation in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/repository/`
7. Initialize repository in `AppContainer` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/di/AppContainer.kt`) — NOT `MessengerApplication.initRepositories()` anymore

### Adding a New API Endpoint

1. Add DTOs in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/dto/`
2. Add method to `OpenAiClient` (note: file is now `OpenAiClient.kt`, not `OpenAiApi.kt`) in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/remote/api/`
3. Add repository method in domain repository interface (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/domain/repository/`)
4. Implement in `ApiRepositoryImpl` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/repository/ApiRepositoryImpl.kt`)
5. Handle `HttpException` and extract error messages properly

### Updating AGENTS.md

**AGENTS.md MUST be updated whenever structural changes are made to the project**, including but not limited to:

- Adding new modules or directories
- Adding new architectural layers or patterns
- Changing navigation structure significantly
- Adding new database entities or repositories
- Adding new features that affect the project structure
- Changing hard constraints or engineering conventions
- Updating build configuration or CI/CD pipelines

If a change makes any section of AGENTS.md outdated or incomplete, update it in the same commit as the structural change.

### Code Style

- Use Kotlin idiomatic patterns
- Follow Material 3 design guidelines
- Use `collectAsState()` for observing Flow in Compose
- Use `rememberCoroutineScope()` for launching coroutines from composables
- Prefer `Modifier` parameter with default value for composables
- Use descriptive naming for composables and functions

### Design language (Material 3 Expressive)

- The app follows **Material Design 3 Expressive** (the expressive direction of the M3 spec: bolder typography, generous rounded/polygonal shapes, springy emphasized motion, dynamic color). [InstallerX-Revived](https://github.com/wxxsfxyzm/InstallerX-Revived) is the visual reference project for this style.
- Theming lives in `presentation/theme/Theme.kt`: static light/dark schemes plus Material You dynamic color, an `ExpressiveShapes` scale (10–32 dp radii) and `ExpressiveTypography` (bold display/headline, semibold titles), wrapped in `MaterialExpressiveTheme` + `MotionScheme.expressive()` (marked `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` — the expressive APIs carry that marker, NOT `ExperimentalMaterial3Api`). The shared loading indicator is material3's expressive `ContainedLoadingIndicator`. Push transitions (`PageTransitions.kt` enter/exit + in-screen sub-page overlays) use non-bouncy springs (`Spring.StiffnessMediumLow`); pop transitions stay tween-based because Android's predictive back drives them via `seekTo`.
- Rules for new or changed UI:
  - Consume theme tokens — `MaterialTheme.shapes.*`, `MaterialTheme.colorScheme.*` (surface-container family for cards/lists) and `MaterialTheme.typography.*`. Do NOT introduce ad-hoc hard-coded `RoundedCornerShape` values when a shape token fits; hard-coded values are allowed only for genuinely one-off shapes (e.g. chat bubble tails).
  - Prefer spring-based motion over fixed-duration linear tweens for spatial transitions (Expressive motion spec); the predictive-back-driven pop transitions (`PageTransitions.kt` + `presentation/platform/BackHandler`) stay as implemented.
  - Prefer the expressive component variants where material3 provides them (e.g. contained loading indicator, `MaterialShapes` polygons for distinctive surfaces like FABs/empty states) over hand-rolled equivalents.

## Web platform (Compose Multiplatform / Kotlin/Wasm)

- **CJK/text rendering**: Compose Multiplatform on web has NO font fallback before 1.12.0 — Skia-on-wasm ships a Latin-only default font, so every CJK glyph rendered as tofu (□) while ASCII looked fine. The project therefore tracks **CMP 1.12.x**, which added automatic fallback: unresolved characters trigger an on-demand Noto font-subset download (the correct CJK variant is picked from `navigator.language`), and the affected text recomposes once it arrives. That is why `composeMultiplatform`/`jbComposeMaterial3` must not be downgraded below 1.12. Note the tofu can briefly reappear on first paint, and the fallback needs network access to `fonts.gstatic.com`.
- **`:shared` disables CMP's `checkComposeUiTestConfigurationForWasmJs`**: from 1.12 the plugin requires `binaries.executable()` on a wasmJs target that has Compose UI tests, so Skiko can be bundled by webpack. The check fires on `:shared` because `wasmJsMain` depends on Compose UI, but that module is a LIBRARY with zero wasmJs test sources (the shared tests live in `jvmSharedTest`, which never compiles for wasm) — and a library target must not declare an executable binary (`:webApp` owns the web entry point). The inapplicable check is turned off in `shared/build.gradle.kts`.


The Web client is a third Compose Multiplatform target of the same KMP app — not a second UI — so the browser runs the identical screens, repositories and design system.

- **Targets**: `shared` declares `wasmJs { browser() }`; the browser entry point lives in `:webApp` (`webApp/src/wasmJsMain/kotlin/cc/ptoe/messenger/web/Main.kt`), which installs the bridge, builds the `AppContainer`, then calls `ComposeViewport(document.body)`. `:webApp` owns `binaries.executable()` — `:shared` is a library and must not.
- **Source sets**: `commonMain` is target-agnostic; `jvmSharedMain` (Android + Desktop only) holds Room, `OkioStorage`, `okio.FileSystem.SYSTEM`, the Room-backed `*RepositoryImpl`s, `CloudSyncRepository`, and `ModelsDevRepositoryImpl`; `wasmJsMain` supplies the web `actual`s. `commonMain` must never reference Room or `java.*` — the Room artifact does not exist for wasmJs, so such a reference breaks the whole web target, not just a screen.
- **Rust core over wasm-bindgen**: `core/rust/crates/messenger-wasm` mirrors `messenger-ffi`'s `CoreHandle` method for method (JSON in / JSON out, plus `Promise`-returning async calls). UniFFI cannot target wasm and the KMP wrapper emits stubs for wasmJs, so the boundary is duplicated by design; add a method to **both** boundaries (`messenger-ffi` + `messenger-wasm`), to `CoreBridge`, and to `AndroidCoreBridge` + `WasmCoreBridge` in the same change. `messenger-store` compiles SQLite itself to wasm (`sqlite-wasm-rs` via rusqlite's `ffi-sqlite-wasm-rs`), keeps the live database in memory and mirrors it into the browser's origin-private file system after each successful write (`Store::snapshot` → `wasm::stage_snapshot`, a single coalescing writer); `messenger_store::prepare_store(name)` must be awaited **before** `Store::open`, and on web the store path is a logical database name, not a filesystem path.
- **Cloud facade split**: the UI depends on `domain/repository/CloudFacade`, never on a concrete class. Android/Desktop use `CloudSyncRepository` (Room + Ktor, `jvmSharedMain`); web uses `RustCloudFacade`, which delegates every call to `CoreBridge` and keeps only what Rust does not own — the 750 ms sync debounce scheduler, the `syncError` display channel, the local-cleanup follow-ups around login/logout/server change, and the cookie-aware Ktor client Coil uses. Both are selected per target in `createLocalStores` (`di/LocalStores.kt`), never by "is there a bridge".
- **Same-origin hosting**: the compiled bundle is served from `server/public/app/` at `/app`, so `/api/*` and `/v1/*` resolve against the page's own origin and the existing `messenger_session` cookie works with **no CORS configuration and no server API change**. `server/next.config.ts` sends `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: require-corp` for `/app/:path*` only (COEP breaks third-party embeds site-wide) and rewrites `/app` → `/app/index.html` (Next.js serves no directory index from `public/`, and `/app/` is normalized back to `/app`); the dev server gets the same two headers from `webApp/webpack.config.d/01-devserver.js`. The Rust sync engine's own default server URL is the production host, not the origin, so `RustCloudFacade` pins it to `window.location.origin` at construction.
- **The bundle is never committed** — it is a ~28 MB build product. `server/` is a separate repository (submodule) and cannot run the Kotlin/Wasm toolchain, so the artifact is the interface: `ci.yml`'s `publish-web-client` job rebuilds the client on every push to `main` and republishes it to a ROLLING GitHub Release tagged `web-client` (asset `messenger-web-main.zip`). `server/scripts/fetch-web-client.mjs` (wired into `pnpm build` as `pnpm web:client`) downloads that asset, unpacks it into `public/app/`, and injects `<base href="/app/">` so the client's relative asset paths resolve under the mount path. It reads that exact tag, NOT `/releases/latest` — the latter follows whichever release was published most recently, so the next versioned `v*` tag (which carries no web asset) would silently break the client. A download failure warns and lets the build continue (the site, console and API are unaffected; set `WEB_CLIENT_REQUIRED=1` to make it fatal). `server/eslint.config.mjs` ignores `public/app/**`, since linting generated emscripten glue produces bogus findings.
- `:webApp:copyWebAppDistribution` still exists for LOCAL use (it syncs the bundle straight into `server/public/app/` without a download, which is the fast loop while developing the client); it is not part of the deploy path.
- **Deliberate web degradations** (browser sandbox, not unfinished work): no terminal/workspace tools (`createBuiltinChatTools()` is empty → requests carry no `tools` array), no stdio MCP (remote MCP over SSE still works), no companion runtime app (`runtimeTerminalSupported = false`, so Settings hides the terminal row), and no models.dev metadata (`createModelsDevRepository` returns nulls). Images ride as `data:` URIs (no filesystem), and Coil must never receive an `okio.Path` on web — its js/wasm `defaultFileSystem()` throws.

## Terminal client (TUI)

- `core/rust/crates/messenger-tui` is the TARGET.md §16/§22 Phase 6 client: a **native Rust** terminal app (`Rust Core → Document Model → Terminal Renderer → ANSI/VT`). It links `messenger-core`/`-store`/`-tools`/`-document`/`-markdown`/`-highlight`/`-sync`/`-mcp` directly — there is NO UniFFI and NO Kotlin in this path, so a terminal build never compiles the Kotlin/UniFFI stack. It is a bin + a lib (the lib exists so `tests/headless.rs` can drive the real app state machine and assert on `ui::compose`'s frames).
- **Rendering is hand-rolled crossterm — there is NO widget framework**: `ratatui` was removed. Three layers with no framework between them: `src/text.rs` owns the terminal's own `Line`/`Span`/`Style`/`Color`/`Modifier` (so `render.rs` and `highlight.rs` contain no terminal-framework code at all — they only speak these types, and `Style::add_modifier` maps onto crossterm's SGR `Attribute` set at paint time), `src/ui.rs::compose(app, w, h) -> Frame` assembles a frame of plain lines plus the caret's cell, and `src/screen.rs::Screen` turns that into escape sequences. `compose` never touches the terminal, which is what lets the headless tests assert on a frame directly.
- **Main-screen differential rendering with native scrollback**: the client draws on the MAIN screen — never the alternate screen, and never a per-frame `Clear(All)` (either would destroy the user's scrollback and repaint every row on every keystroke). `Screen::render` rewrites only the rows whose text changed, issuing `Clear(CurrentLine)` per changed row and resetting the style after every run so a colour never bleeds into the next one. `compose` returns exactly `height` rows (the chrome is pinned to the bottom, the transcript fills the rest and its oldest rows are cut) and reports the cut in `Frame::scrolled`, which `Screen` turns into real terminal scrolls — that is what puts history into the user's scrollback, reachable with the mouse wheel or Shift+PgUp at zero cost to the app. A resize invalidates the recorded frame and forces one full repaint (the only legitimate one); the recorded "previous frame" is plain text per row, since that is all a differential repaint compares.
- **One surface + popups (pi-style)**: there is NO view enum, no tab strip, and no per-view key handler. The frame is always the chat and everything else is a `Popup` on `App::popups`. `handle_key` has exactly two branches — the topmost popup consumes input, otherwise the chat editor does — so a modal can never leak a keypress into the transcript and Esc peels exactly one layer. The stack is `Popup::{Commands, Select, Form, Confirm, Help}` (`src/popup.rs`); their bodies are rendered in `src/ui.rs`. The removed `ui/{agents,conversations,mcp,providers,settings}.rs` views, the F1–F6 keys and the hint line are gone; function keys are ordinary unhandled input.
- **Hand-laid boxes**: the editor, the `/` palette and the modals are drawn by `box_lines`, which returns EXACTLY the row budget it is given — borders included — because budgeting arithmetically and then drawing to that budget is how a box ends up a row taller than its space (which pushed the prompt off a 20-row terminal). A box needs 2 rows minimum (two borders) and 3 to be meaningful (border, a row of text, border), so the editor keeps `MIN_EDITOR_ROWS` on a cramped terminal and the CONTEXT LINE is dropped before the prompt is squeezed. Space is allocated bottom-up with a fixed priority: editor > footer > context line > palette > transcript. Every emitted row is clipped to the terminal width in display cells (`unicode-width`, so CJK counts 2) and the mode badge on the context line is PINNED while the details degrade from the right — it is the one thing that says whether the agent may write, so it must never be the thing that gets clipped. The `/` palette renders directly ABOVE the editor (it belongs to the editor, not over the transcript) and shows its filter buffer in the editor box itself; every other popup is a centred 70%-width modal composited over the middle rows.
- **Agentic entry point (Codex-style)**: `App::bootstrap_session` (called from `main` right after `App::new`, NOT from the constructor so tests can drive it explicitly) makes the session agent-first. `store_ops::ensure_cwd_project` looks up the project whose workspace is the process CWD (matching on the canonicalized absolute path, so `C:\repo`, `c:\repo` and `C:/repo/` are one project) and CREATES it on first use, naming it after the directory. The session then opens a fresh conversation in that project with `store_ops::current_agent` (the stored selection, falling back to the default Agent). Because a project IS a workspace, this is also what gives the agent's `terminal`/workspace tools a real working directory: `resolve_turn` returns the project directory on `ResolvedTurn.workspace` and `Engine::start_turn` hands THAT (not `config.workspace_dir`) to `NativeToolHost`. Windows `canonicalize` returns the `\\?\` verbatim form, so `store_ops::strip_verbatim` normalizes it — otherwise the transcript and the model's working-directory note would both show `\\?\C:\…`. The second bootstrap reuses the project instead of duplicating it.
- **Command table (`src/commands.rs`)**: `SLASH_COMMANDS` is plain data (name, argument hint, summary, and the `Opens` picker a command opens) — the menu is the reference for what the session can do, exactly like pi. `/help` and the keys popup both render from it. Commands: `/help /new /agent /model /provider /mode /project /resume /conversations /rename /delete /agent.new /agent.edit /agent.delete /mcp /settings /login /logout /sync /card /quit`. A `/` on an EMPTY input opens the palette; Tab completes (unique match completes, ambiguous keeps the longest shared prefix via `shared_prefix` and lists the candidates, unknown warns), Enter runs, Esc abandons. Emptying the buffer returns to plain typing.
- **One generic select list**: `Popup::Select` + `SelectPurpose` (`Agent`, `Project`, `Conversation`, `Provider`, `Model`, `McpServer`, `Setting`) backs every picker — `/agent`, `/project`, `/resume`, `/conversations`, `/provider`, `/model`, `/mcp` and `/settings`. In a list, `Enter` performs the row's action (an MCP row toggles on/off, a provider row fetches its models and drills into them, a model row binds `Conversation.override_model_id`, a project row opens a conversation inside it), `e` edits the row in place, `n` creates a new one, `d` confirms a delete. There are no per-row "views" any more.
- **Notes, not modals**: `App::note` appends a `ChatNote` (`Info`/`Warn`/`Error`) rendered inline in the transcript with a `note │` gutter. Slash-command outcomes, tool failures, title results, cloud status and `UiMsg::ToolLog` diagnostics all surface here instead of only in the status line. Notes are session-local and are never persisted as messages.
- **CLI**: `messenger-tui [--store <path>] [--workspace <dir>] [--config <path>] [--import-desktop [<dir>]] [--help]` (hand-rolled parse, no clap). Defaults: store `~/.messenger/tui/store.db`, config `~/.messenger/tui/settings.toml`, desktop-import dir `~/.messenger/files/databases` (matches `DatabaseBuilder.desktop.kt`'s `filesDir/databases/messenger_database.db`). `MESSENGER_TUI_STORE` / `MESSENGER_TUI_WORKSPACE` / `MESSENGER_TUI_CONFIG` override the config file. `--workspace`/`workspace_dir` is only the FALLBACK cwd for a conversation that belongs to no project (MCP tools still work there); a conversation in a project always uses that project's directory.
- **`settings.toml`** (`config.rs`, `#[serde(default)]`, rewritten with defaults on first run): `workspace_dir`, `theme` (`dark`|`light`, selects the syntect theme), `show_think`, `show_tool_details`, `auto_scroll`. `Ctrl+T` / `Ctrl+O` toggle the two display flags and write the file back.
- **Layout**: the chrome is pinned to the BOTTOM of the viewport and the transcript fills everything above it — that split is what makes native scrollback work, because the transcript is append-only so its oldest rows are simply the ones that scroll off the top, whereas a header pinned to row 0 would scroll away on its own. Bottom-up: the `/` palette (only while open), the bordered editor box (grows with the prompt up to `MAX_EDITOR_ROWS`, then scrolls internally), the context line (conversation · project · workspace · Agent · the pinned read-only/writable badge), and the footer (spinner / bound model / last status / token total / cloud account, with right-aligned items dropped whole when they would collide). The transcript has no box of its own — each block is labelled by a `you │ / agent │ / tool │ / note │` gutter, because a terminal has no bubbles and a bordered transcript would cost two rows for nothing. The main loop ticks every 30 ms, which doubles as the Document Engine's streaming batch window — `app.tick()` drains the live `StreamingSession`'s `DiffBatch` and the renderer's source of truth is that session's own `Document`.
- **Keys**: `Ctrl+C` always quits (and always restores the terminal); `Esc` closes one popup layer, else cancels the running turn; `Enter` sends (or submits/picks/confirms inside a popup); `Alt+Enter` or `Ctrl+J` inserts a newline; `Ctrl+S` submits a form from any field; `Tab`/`Shift+Tab` move between fields, complete in the palette, or page in the keys popup; `e`/`n`/`d` edit/new/delete the selected list row. **In the chat every unmodified printable key types into the message**, so only two chords exist (`Ctrl+T` think blocks, `Ctrl+O` tool card bodies) plus `?` for help on an empty input and a leading `/` for the palette.
- **Tool execution is native**: `NativeToolHost` implements `messenger_core::agent::ToolHost` and dispatches `terminal` → `shell.rs` (PowerShell on Windows with `[Console]::OutputEncoding=UTF8`, else `/bin/sh -c`; 60 s timeout; the process is killed on timeout AND on turn cancellation, which also drains and truncates output), the five workspace tools → `workspace.rs` (over the real filesystem, reproducing `WorkspaceTools.desktop.kt`'s bounds and message strings verbatim; `grep` is walkdir+regex in-process because the TUI does not bundle `rg`), and anything else → the connected MCP clients by tool name. Its cwd is the resolved turn's project directory (see "Agentic entry point"), so `terminal` and the workspace tools operate on the tree the session was launched in.
- **Async boundary**: `main` builds the multi-thread runtime and hands its `tokio::runtime::Handle` to `Engine::new`. The UI loop runs on the main thread OUTSIDE any runtime context, so every background action (`/provider fetch`, sign-in/out, sync, card redemption, MCP (re)connect, chat send) must spawn via `engine.spawner()` — a bare `tokio::spawn` from UI code panics with "there is no reactor running". The `streaming_turn_…` and `providers_fetch_models_…` headless tests drive the app from a non-runtime thread precisely to keep this honest.
- **Cloud**: sign-in, sign-out, sync, card preview/redemption, password change, account deletion and the server URL all go through `SyncEngine` from `messenger-sync`. `messenger-sync`'s `login`/`register` now persist the `messenger_session` cookie into `KV_SESSION`/`KV_SESSION_HOST` (`CloudApiClient::login_capturing_cookie` reads `Set-Cookie`), which is what makes cloud auth work for any client without a cookie jar.
- **Legacy import**: `--import-desktop [<dir>]` runs the marker-guarded, idempotent `messenger_store::import_legacy`. That function now accepts BOTH Room file names (`messenger_database` from Android, `messenger_database.db` from the JVM/Desktop `Room.databaseBuilder`), so the Desktop database is importable. The TUI owns its own store; the import is opt-in and one-shot.
- **Deliberate terminal degradations** (documented, not unfinished): math renders as its LaTeX **source** in a bordered box (a terminal cannot stack fractions); models.dev metadata does not exist here, so `context_window` stays 0 (unlimited) unless the provider's `GET /models` reports it — the per-model form exposes an editable Context Window instead.
- **No CI change**: CI runs Gradle builds only (there is no `cargo test` step), so the TUI's verification is the local `cargo test --workspace` in `core/rust`. Do not add a Gradle task for the binary.

## TARGET.md architecture migration (in progress)

The repo is mid-migration to the `TARGET.md` architecture (Rust Agent Core + UniFFI bridge + platform-native renderers) under the approved phased plan: M0 FFI walking skeleton → M1 Rust core (agent runtime, SQLite store + legacy import, cloud sync port) → M2 re-anchor the existing Compose UI onto the Rust core (switch point ①) → M3 incremental Document Engine + Android View chat renderer (switch point ②) → M4 Wear on the Rust core (switch point ③) → M5 Desktop renderer + cleanup. The old stack stays buildable and shippable until each switch point.

Current status — **M0, M1 & M2 complete, M3 in progress, plus the Web platform and the TUI** (206 Rust unit & integration tests green across 12 crates in `core/rust/crates/`, verified end-to-end on Android x86_64 emulator, host, the browser, and an interactive terminal):

- `messenger-llm` — full port of the LLM layer: streaming parser (think wrapping, tool-call accumulation, usage stashing, double-Done), request builder (reasoning three-state, multipart images, tool specs), reqwest+rustls client, `ChatEventStream` with the mandatory has-finished error sentinel (`error_stream_no_data`). (35 tests)
- `messenger-tools` — terminal/workspace declarations and `resolve_request_tools` (per-tool config, read-only/writable mode, and the workspace gate that hides the workspace-bound tools outside a project). Tool arguments are never pre-screened: the sandbox (companion runtime UID / desktop process) confines actual operations, reads are unrestricted, and only the `write_access` tools (edit/create) are declaration-gated by the mode. (16 tests)
- `messenger-mcp` — JSON-RPC 2.0 client with pluggable transports: native desktop child-process stdio and SSE/HTTP remote endpoints. (9 tests)
- `messenger-store` — SQLite schema v2 (Room v20 column names verbatim + the `projects` table and `conversations.projectId`), typed CRUD for all 6 entities + StoreEvent notifications, sync_meta/kv tables, one-shot legacy import (WAL-aware staging copy, idempotent marker). (11 tests)
- **Store-model serde contract**: the Kotlin bridge encodes with `NetworkClient.json` (`encodeDefaults = false`), so any DTO field equal to its Kotlin default (`writable = false`, `role = "chat"`, `contextWindow = 0`, …) is OMITTED from the upsert JSON. serde fills missing `Option<T>` fields with `None` automatically, but every plain scalar column with a Kotlin-side default in `messenger-store`'s `StoredConversation` / `StoredAgent` / `StoredModel` therefore carries `#[serde(default)]` — add the attribute when adding such a column, or the upsert fails with `missing field` (this crashed `createNewConversation`). `upsert_json_with_default_valued_fields_omitted_parses` pins the contract.
- `messenger-core` — parts codec, context math (estimation, 80% summarization, orphan-tool trimming), title helpers, and `run_chat_turn`: the event-driven agent loop with ToolHost callback, cancellation, exact-usage bookkeeping. (22 tests)
- `messenger-sync` — Cloud SaaS synchronization engine port: paged delta pull/push, cursor backwards protection, builtin provider/title-agent guards, avatar caching with ETag sidecars and 304 re-use, agent market APIs, card-key preview and redemption, and builtin cloud model auto-sync. The `projects` collection syncs alongside agents/conversations/providers, applied BEFORE conversations so the `projectId` FK resolves. (21 tests)
- `messenger-document` — Document AST model (`Block`: Paragraph, Heading, CodeBlock, Math, List, Quote, ToolCall, Think, Table, Divider) with stable `BlockId`s and `DocumentDiff` streaming events (`Append`, `Update`, `Finalize`, `Reset`). `List` items are `ListItem { indent, ordered, number, task, inlines }` — per-item nesting level, marker kind, literal ordinal, and a GFM task flag (`Some(checked)` for task items, `None` otherwise, `#[serde(default)]`). Quote blocks carry parsed `inlines` like paragraphs, so nested bold/italic/code/math render inside the accent-bar quote instead of literal markers. (1 test)
- `messenger-markdown` — incremental streaming Markdown parser with prefix-stable inlines, block state-machine scanning, and 20–50 ms token batching windows (`StreamingSession`). Pipe tables parse into `Block::Table` (plain-text cells, inline markup flattened): a `|`-starting row line is HELD one line of lookahead (`MaybeTable` state; `|` lines also never eager-open as paragraphs) and only a GitHub delimiter row (`|---|---|`) promotes it — anything else restores the paused context verbatim, so pipes inside normal prose are untouched. Rows stream in line-by-line via `Update` diffs. Lists parse into `Block::List` the same way: `- `/`* `/`+ ` bullets and `12. `/`3) ` ordinals (nesting = leading spaces / 2), items append one `Update` per line, and partial ordered markers (`- `, `* `, `+ `, `12`/`12.`/`12. text`) never eager-open as paragraphs. GFM task lists (`- [ ] `/`- [x] `/`- [X] `, marker followed by whitespace or end of item) strip the bracket group into `ListItem.task` — renderers draw a checkbox instead of the bullet; `[x]` without a following space stays literal. Blockquotes (`> `) carry parsed inlines in `Block::Quote` the same way paragraphs do. (22 tests)
- `messenger-highlight` — syntect (Sublime Text engine) syntax highlighting over the ~180-language default pack (pure-Rust `default-fancy` build — no oniguruma C dependency, Android-NDK safe): `highlight_code_json(code, language, dark)` returns byte-range + 0xAARRGGBB color spans colored by base16-ocean.dark (dark) / InspiredGitHub (light), unknown languages fall back to plain text, and any internal failure yields `"[]"` so callers degrade to unhighlighted text. Exposed over UniFFI as `highlightCodeJson` and consumed by the native renderer's `CodeBlockView`. (4 tests)
- `messenger-ffi` — complete UniFFI repository and cloud sync facade in `CoreHandle` + `DocumentHandle` (incremental streaming diff generation and full markdown parsing) compiling for host and Android (arm64-v8a, armeabi-v7a, x86_64). (3 tests)
- `messenger-wasm` — the browser boundary: the same `CoreHandle` facade over `wasm-bindgen` instead of UniFFI (which has no wasm generator), gated `#![cfg(target_arch = "wasm32")]` so host builds and `cargo test` stay green. Exports the method-for-method surface `CoreBridge` expects — store CRUD, cloud, market, avatars, `run_turn` with JS tool-host/event callbacks, and the Document engine — every payload a JSON string.
- M2 re-anchor complete: shared repositories (`RustProviderRepository`, `RustModelRepository`, `RustAgentRepository`, `RustConversationRepository`, `RustMessageRepository`, `RustCurrentAgentRepository`) re-anchored on `CoreBridge` + `CoreHandle` SQLite backend; `ChatViewModel` drives agent turns via `coreBridge.runTurn`.
- `messenger-tui` — the native terminal client (Phase 6): streaming chat over the shared agent loop, Document-AST rendering (paragraph/heading/list/quote/table/syntax-highlighted code/math source/think/tool cards), cloud sign-in and sync, a config file with first-run defaults, and the built-in tool set executed natively (shell + the five workspace tools + MCP over stdio/SSE). **One surface + popups (pi-style)**: the frame is always the chat, and every other surface — the `/` command palette, the Agent/project/provider/model/MCP/settings pickers, the editors, the confirmations, the keys reference — is a `Popup` on a stack, driven by the data-only `SLASH_COMMANDS` table. **Agentic entry point**: a session bootstraps the CWD project (creating it on first use) and opens a fresh conversation there with the default Agent. Command outcomes render as inline transcript notes. Own store at `~/.messenger/tui/store.db`, opt-in one-shot import of the legacy Room database. Rendered by hand-rolled crossterm (see the "Terminal client (TUI)" section): own text model (`text.rs`), frame composition (`ui.rs`), per-line differential painting on the main screen (`screen.rs`) — no widget framework, so the headless tests assert on `ui::compose` frames. (106 tests: 85 lib + 2 bin + 19 headless, incl. frame-size/width/budget invariants across every viewport, the popup stack, the CWD-project bootstrap, the `/` palette, and an end-to-end streamed turn with a real tool call)
- `:renderer-android` — Android Native View chat renderer: `DocumentView`, `CodeBlockView`, `ThinkBlockView`, `ToolCallView`, `MathBlockView`, `BulletListView`, `TableView`, `MessageView`, and `ConversationAdapter` for fine-grained invalidation without Compose recomposition overhead. Math renders through `RatexMath`, a bridge over RaTeX-CMP (the same pure-Rust KaTeX-compatible engine the Compose flow uses): `RaTeXEngine.parseBlocking` yields an em-unit display list that replays through RaTeX's own `drawDisplayList` onto an android canvas via `CanvasDrawScope`; glyphs are painted by a CUSTOM painter (the library's internal Android font cache is not populated outside a Composable lifecycle, so the KaTeX TTFs bundled in the RaTeX AAR assets are loaded into a name→Typeface table keyed with a `KaTeX_` prefix fallback — display lists reference bare names like `Main-Regular`), giving true fractions, radicals, stacked big-operator limits AND matrix environments, plus uniform down-scaling when wider than the bubble. INLINE math renders as `MathSpan` (ReplacementSpan) bitmaps from the same path with TRUE baseline alignment and grown font metrics. The built-in `MathEngine` (recursive TeX-subset layout, no matrices) remains as the parse-failure fallback and `MathLayoutCache` still caches its display lists. Code blocks highlight through the Rust `highlightCodeJson` bridge (syntect, see `messenger-highlight`) — spans are parsed in `CodeBlockView` and applied as ForegroundColorSpans; `RendererTheme.isDark` selects the syntect theme, and the former regex `CodeHighlighter` is deleted. The renderer carries NO resources of its own: `RendererTheme` (built in `ChatMessageList.android.kt` from the host `MaterialTheme.colorScheme` + localized strings + decoded avatar bitmaps) injects every color/label, so the View hierarchy follows light/dark and dynamic color exactly like the Compose side.
- Phase 3 complete:
  - `ChatMessageList` expect/actual: Android embeds `:renderer-android`'s `RecyclerView` via `AndroidView`; Desktop preserves Compose `LazyColumn`.
  - `ChatViewModel` streams incremental `DiffBatch` JSON from Rust `DocumentHandle.feed()` via `streamingDiff`, feeding `ConversationAdapter.applyStreamingDiff()` directly for zero-recomposition streaming updates.
  - **Live token streaming contract (three invariants — break any one and text appears only when the turn ends):** (1) the Rust `IncrementalParser` EAGERLY opens a streaming paragraph for a non-blank partial line (`update_active_chunk`'s Idle arm + `can_eager_open_paragraph`, which never eager-opens block-starter prefixes like `# `/` ``` ` so real block handlers see them intact; `eager_line_open` prevents a stray soft-break when the line's newline arrives) — without this, single-line replies emit zero diffs until `finish()`. (2) the Rust turn loop emits `AgentEvent::StreamingStarted { message_id }` right after persisting the placeholder row, and `ChatViewModel` binds `_streamingMessageId` to THAT id — the loop creates the row itself, so caller-side UUIDs never match it. (3) `DocumentView.applyDiffBatch` treats an `update` for a missing block as a create (the first batch can land before the streaming row's DiffUtil rebind registers `streamingViewHolder`, dropping its `append`). (4) diff batches are routed BY MESSAGE ID (`ChatMessageList.android` passes the current `streamingMessageId` to `ConversationAdapter.applyStreamingDiff`): at a tool-round boundary the next round's placeholder row (NEW id) replaces the previous one and DiffUtil dispatches asynchronously to the token stream, so batches arriving before the new row binds are parked per id and replayed in order at bind time — applying them directly would leak live blocks into the previous round's settled bubble AND permanently lose the new round's opening blocks; the parking buffer clears when the turn ends (`clearPendingDiffs`).
  - **Google Messages-style bubbles in the native renderer** (restored parity with the former Compose bubbles): `MessageView` renders the 32dp avatar on the bubble's outer side (vector fallbacks `ic_renderer_bot`/`ic_renderer_person`), 18dp bubbles with the 4dp tail corner on the group's last row (bottom-start for assistant, bottom-end for user), 8/64dp row insets, 11sp timestamp below the last row of a group, pulsing three-dot indicator while streaming with no content, and localized error bubbles (title + error message + retry hint) driven by the item's ERROR status. Assistant bubbles host a `DocumentView`; user bubbles plain text. Body sizes mirror the typography tokens — assistant markdown = bodyLarge 16sp/24sp line height with headingScale (×1.8–0.9), user text = bodyMedium 14sp/20sp — and the FIRST block inside a bubble carries no top margin so text sits vertically centered in the bubble padding. Avatars mirror the Compose `AgentAvatar` exactly: `ChatMessageList.android.kt` loads local file / `content://` sources through Coil (`BitmapImage`) into `RendererTheme` (remote http(s) URLs are skipped — `AgentAvatar` shows the fallback for those too; `MessageView` renders bitmaps FIT_CENTER), and when no avatar is set or a load fails it renders the fallback bitmap by drawing the SAME `Icons.Filled.SmartToy` (assistant) / `Icons.Default.AccountCircle` (user) ImageVectors via `CanvasDrawScope` onto a `primaryContainer` circle with the icon at 60% tinted `onPrimaryContainer` — glyph identical by construction, so bubble avatars match the top bar pixel-for-pixel. Text views carry a `maxWidth` cap (screen − 112dp row insets/avatar/spacer − 28dp bubble padding) so long single-line messages WRAP inside the row instead of pushing the bubble past its far inset, and recycled user TextViews call `forceLayout()` on rebind — a recycled TextView keeps its previous measured width when the new text yields the same line count (`checkForRelayout` skips the requestLayout), which stretched later short bubbles to the earlier long one's width.
  - **Agent-turn tool cards render in the native renderer**: `ChatListItem.ToolGroupItem` maps to `MessageItem.rounds: List<ToolRoundData>` (round text + `ToolCallData(callId, name, arguments, output, isError, running)` from the round's ToolCall parts + the settled TOOL row results). `MessageView.buildStaticBlocks` parses round text into Document blocks and synthesizes `RenderBlock.ToolCall` cards directly (no `<tool_call>` marker round-trip), with static block ids banded per round above `DocumentView.STATIC_ID_BASE` so Rust streaming-session ids never collide. `ToolCallView` mirrors the Compose `ToolCallCard`: collapsible (tap toggles full command + localized result), status badge (Running/✓Done/✕Failed), container color by state (secondaryContainer running / errorContainer failed / surfaceContainerHighest done), terminal cards showing the extracted `command` field instead of raw args JSON. Expand/collapse ANIMATES: `SectionAnimator` (height 0↔measured + fade, the native counterpart of Compose `animateContentSize` + `AnimatedVisibility`) drives both `ToolCallView`'s expanded section and `ThinkBlockView`'s collapsible content; per-token rebinds snap only when no toggle animation is running, so streaming updates never cut a user's expand/collapse short. Orphan TOOL rows render as standalone result cards. Pipe tables (`Block::Table`) render through `TableView`: a rounded bordered card with a bold header row on a subtle fill, hairline row dividers and plain-text cells. Column widths are precomputed from text measurement and the card is built from plain LinearLayout rows — when the natural table fits the bubble every column stretches proportionally to fill the width exactly; only genuinely wider tables sit in the `HorizontalScrollView` and scroll. Lists (`Block::ListBlock`) render through `BulletListView`: one row per item — • or the literal ordinal in a fixed marker column (GFM task items draw a 16dp `TaskCheckboxView` checkbox — primary-filled with an onPrimary check when checked, outlineVariant stroke when not — instead of the marker), body text, nested rows indented per level.
  - **Streaming update discipline**: a row's static section (rounds + finalized text) rebuilds only when its fingerprint (`rounds` + content + theme) changes; while streaming, the live section is fed exclusively by Rust DiffBatch deltas (zero re-parse per token), and the typing indicator hides on the first applied live block.
  - **Auto-scroll**: `LaunchedEffect(streamingMessageId)` snaps the RecyclerView to position 0 (the viewport bottom under `reverseLayout`) when a turn starts or a new round placeholder takes over — per-token recomposition never scrolls, so reading history mid-turn is uninterrupted. (The former `update`-lambda heuristic used `findFirstVisibleItemPosition()`, which measures the TOP of the conversation under `reverseLayout` and never scrolled — new turns appeared only after reopening the chat.)
  - TARGET.md §19 Benchmark test suites implemented and verified: Rust `benchmark_workloads.rs` (streaming throughput 460k+ tokens/s, 10k token docs parsed in < 4ms, 60 tool calls in < 2ms) and Android `RendererBenchmarkTest.kt` (10k char documents in < 50ms, 60 tool calls in < 30ms, diff batch average latency < 0.1ms).
- Phase 4 complete:
  - Phone-side Wear bridge (`MobileWearChatHandler` in `MobileWearSyncManager.kt`) drives chat turns through Rust Agent Core (`coreBridge.runTurn`), streaming `chat_delta`/`chat_done`/`chat_error` WebSocket frames over tether network.
  - Watch-side streaming throttle (`WearChatRepository.kt`): 70ms token batching window minimizes wearable CPU wakeups and UI recomposition, reducing streaming battery drain by > 80% (TARGET.md §11).
  - Compact on-wrist text formatter (`WearTextFormatter.kt`): zero-allocation fast-path for plain text, compact thought indicator (`💭 ...`), tool badges (`🔧 [name]`), concise code block summaries, and formatted math.
  - Phase 4 benchmark suite (`WearTextFormatterTest.kt`): verified live think folding, tool compacting, and sub-0.1ms formatting throughput on wearable simulations.
- Phase 5 complete:
  - Desktop Compose Document View (`DesktopDocumentView` & `DesktopDocumentBubble` in `shared/desktopMain/`): parses structured Document AST blocks (Paragraph, Heading, Code, Math, Think, ToolCall, Quote, Table, ListBlock, Divider) with Compose `key(block.id)` stability so immutable/finalized blocks never recompose during streaming.
  - `ChatMessageList.desktop.kt` upgraded to render assistant messages and tool rounds via `DesktopDocumentBubble` with complete desktop context menu actions (copy, regenerate, delete), retry hooks, and smooth scrolling.
  - Fully retired and removed the `llm-typewriter` library dependency and submodule: Desktop chat rendering completely migrated to `DesktopDocumentBubble` + `DesktopDocumentView` with a built-in pure Kotlin Markdown-to-Blocks AST parser and interactive collapsible tool cards (`buildDesktopToolGroupBlocks`), eliminating the last remaining consumer of the typewriter library.
  - Desktop benchmark test suite (`DesktopDocumentParserTest.kt`): verified block parsing and sub-0.05ms execution latency.
  - All migration milestones M0–M5 / Phases 1–5 defined in TARGET.md are fully delivered, plus the Web platform (Compose Multiplatform on Kotlin/Wasm backed by the Rust core compiled to wasm32 with an OPFS-persisted SQLite store), passing all unit & integration tests with zero compiler warnings across Android, Wear OS, Desktop and Web.
- Phase 6 complete — the native Rust terminal client (`core/rust/crates/messenger-tui`, TARGET.md §16/§22, the last platform listed in §1): `Rust Core → Document Model → Terminal Renderer → ANSI/VT`. It links the core crates directly (no UniFFI, no Kotlin), renders the Document AST onto terminal cells, and ships the whole client surface — conversation list, streaming chat through the Rust agent loop, Document-AST rendering, provider/model/Agent/MCP editors, cloud sign-in and sync, and the built-in tool set executed natively. Its renderer is hand-rolled crossterm with NO widget framework (`ratatui` removed): own `Line`/`Span`/`Style` types (`text.rs`), frame composition (`ui.rs`), and per-line differential painting on the MAIN screen so history lands in the terminal's native scrollback (`screen.rs`).

- `core/rust` is a Cargo workspace; `crates/messenger-ffi` owns the UniFFI boundary (`uniffi::setup_scaffolding!`, proc-macro exports only — no UDL). Generated Kotlin lands in package `cc.ptoe.messenger.core` (set in `uniffi.toml`), one file per namespace. The workspace contains 12 crates covering agent runtime, store, sync, document engine, tools, the browser boundary (`messenger-wasm`), and the native terminal client (`messenger-tui`).
- `:webApp` (directory `webApp`) is a `kotlin.multiplatform` + Compose module with a single `wasmJs` target. Its `buildRustWasm` + `generateWasmBindings` Exec tasks compile `messenger-wasm` for `wasm32-unknown-unknown` and run `wasm-bindgen --target web` into `webApp/build/wasm/`, which is registered as a `wasmJsMain` resource root; the Kotlin bridge imports the glue through the bare specifier `messenger-wasm-bindings`, aliased onto that absolute path by the committed `webApp/webpack.config.d/02-wasm-bindings.js` (a relative specifier would resolve against the compiled Kotlin module's own directory, where the glue does not live); both track the whole Cargo workspace as inputs, for the same reason `:core-bindings` does. Both toolchain prerequisites are non-Gradle (`rustup target add wasm32-unknown-unknown`, `cargo install wasm-bindgen-cli` at the version pinned in `Cargo.lock`). `copyWebAppDistribution` additionally syncs the production bundle into `server/public/app/`.
- Kotlin/JS+Wasm toolchain notes: `gradle.properties` disables the Node.js distribution download (`kotlin.js.nodejs.download=false`, `kotlin.js.yarn=false`) and `settings.gradle.kts` uses `RepositoriesMode.PREFER_SETTINGS`; `settings.gradle.kts` declares the two ivy repositories the toolchain resolves its pinned Node.js and Binaryen (`wasm-opt`) distributions from. Gradle's npm/yarn lockfiles live in `kotlin-js-store/` (git-ignored); `./gradlew kotlinWasmUpgradePackageLock` refreshes them when a dependency changes.
- `:core-bindings` (directory `core/bindings`) is an `com.android.library` module whose `preBuild` runs three `Exec` tasks: `cargoBuildHost` (host release cdylib feeding the bindgen), `generateUniFFIBindings` (`cargo run --bin uniffi-bindgen generate --no-format` into `build/generated/uniffiKotlin`), and `buildRustAndroid` (`cargo ndk --platform 30` for arm64-v8a/armeabi-v7a/x86_64 into `build/rustJniLibs`). All three track the WHOLE workspace as inputs (`core/rust/Cargo.toml` + `Cargo.lock` + `crates/`), not just the ffi crate — sibling-crate edits must invalidate the cdylib, or Gradle silently ships a stale one. Generated code and native libs live under `build/` and are never committed. The bindings load through JNA, so the module depends on `net.java.dev.jna:jna@aar`.
- Rust core integration: the temporary M0 walking-skeleton `RustProbe` was retired; real core wiring is fully anchored on `CoreHandle` and `AndroidCoreBridge`.
- CI: `build-android.yml` installs the Rust stable toolchain with the three Android targets (`dtolnay/rust-toolchain`), cargo-ndk (`taiki-e/install-action`), and `Swatinem/rust-cache` scoped to `core/rust`. `build-wear.yml` gains the same steps when the wear app adopts the core (M4).
- Local dev: `cargo test` inside `core/rust` runs the Rust unit tests; any `:core-bindings`/`androidApp` Gradle build triggers the cargo tasks automatically (incremental). Kotlin-side regeneration is never done by hand.

## Build & Run

### Prerequisites

- JDK 17
- Android Studio (or Android SDK)
- NDK `29.0.14206865` + CMake `3.22.1` for `:runtime` — it builds the vendored PTY JNI (`libtermux.so`) per ABI split; both are pinned in `runtime/build.gradle.kts` (CI installs them via `android-actions/setup-android` packages)
- Gradle wrapper is included

### Local Gradle invocations MUST use `--no-daemon`

Run every local wrapper invocation with `--no-daemon`:

```bash
./gradlew --no-daemon :shared:allTests      # Windows: .\gradlew.bat --no-daemon ...
```

Android Studio keeps its own Gradle daemon alive in the same Gradle user home
(`~/.gradle`). A CLI invocation that attaches to that daemon contends with the
IDE over the build (`Project is locked by another process`, configuration-cache
and file-lock stalls); with the configuration cache also shared between the two,
a run that the IDE has pinned can hang past its own deadline — observed as a
`desktopTest` invocation that produced no output for 900 s. `--no-daemon` starts
a short-lived, single-use JVM for the CLI build and exits, leaving the IDE's
daemon untouched. CI does not need the flag (no IDE daemon exists there).

### Build Commands

```bash
# Build debug APK
./gradlew --no-daemon :androidApp:assembleDebug
./gradlew --no-daemon :wear:assembleDebug
./gradlew --no-daemon :runtime:assembleDebug   # companion runtime app (splits.abi: arm64-v8a/armeabi-v7a/x86_64)

# Build release APK
./gradlew --no-daemon :androidApp:assembleRelease
./gradlew --no-daemon :wear:assembleRelease
./gradlew --no-daemon :runtime:assembleRelease

# Run unit tests
./gradlew --no-daemon :shared:allTests

# Run lint
./gradlew --no-daemon :androidApp:lintDebug

# Run Desktop app
./gradlew --no-daemon :desktopApp:run

# Terminal client (TUI) — see the "Terminal client (TUI)" section
cd core/rust && cargo build -p messenger-tui                 # build the binary
cd core/rust && cargo run -p messenger-tui                    # run it in a terminal
cd core/rust && cargo run -p messenger-tui -- --help          # CLI reference
cd core/rust && cargo test --workspace                         # Rust core + TUI tests

# Build Desktop native distributions
./gradlew --no-daemon :desktopApp:packageReleaseDmg   # macOS
./gradlew --no-daemon :desktopApp:packageReleaseMsi   # Windows
./gradlew --no-daemon :desktopApp:packageReleaseDeb   # Linux

# Web (Compose Multiplatform / Kotlin/Wasm)
./gradlew --no-daemon :webApp:wasmJsBrowserDevelopmentRun   # dev server (COOP/COEP on)
./gradlew --no-daemon :webApp:wasmJsBrowserDistribution     # production bundle under webApp/build/dist
./gradlew --no-daemon :webApp:copyWebAppDistribution        # local: sync it into server/public/app/
                                                            # (deploys instead download the released archive)

# Rust core for the browser (the Gradle tasks above run these automatically)
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.129 --locked   # must match Cargo.lock
cd core/rust && cargo check --target wasm32-unknown-unknown -p messenger-wasm
```

### Android agent runtime (companion `:runtime` app)

Android 10+ SELinux W^X strips `execute`/`execute_no_trans` on `app_data_file` from the `untrusted_app` domain that apps with targetSdk 29+ run in (the main app targets SDK 36), so the main app can never exec binaries inside its data directory — the earlier pinned-Termux-bootstrap-in-app-data design died on this wall. The working design is a **companion app**:

- `:runtime` (`cc.ptoe.messenger.runtime`) runs under its OWN UID with **targetSdk 28** (minSdk 28), which keeps its process in the legacy `untrusted_app_27` SELinux domain that retains `execute`/`execute_no_trans` on `app_data_file` (verified on-device: the main app's domain gets `denied { execute_no_trans }`). It is deliberately NOT `sharedUserId` with the main app — see the SELinux domain rule above (the shared user's seInfo is derived from the highest-targetSdk member at install time, which silently pulled the companion into the modern domain). Because of that deliberate targetSdk, the module disables lint's `ExpiredTargetSdkVersion` fatal check (`runtime/build.gradle.kts`), which would otherwise fail every release build (`lintVital`).
- The companion packages the pinned, SHA-256-verified Termux bootstrap per ABI via `splits.abi` (`arm64-v8a`/`armeabi-v7a`/`x86_64`, downloaded and hash-checked at build time into `jniLibs/<abi>/libbootstrap.zip.so`), extracts it atomically (staging + backup + version/ABI/hash marker, archive-path and symlink-graph validation via `RuntimePathPolicy` — symlink targets may be directories, e.g. `lib/terminfo -> ../share/terminfo`) into its OWN `files/agent-runtime/runtime-<abi>`, and exposes `ShellService` over AIDL (`IShellService`/`IShellCallback`, interface files duplicated in `androidApp`). Access control: the service is protected by the signature-level `cc.ptoe.messenger.runtime.permission.SHELL` (only same-key builds can hold it) and every call re-checks it via `checkCallingPermission`.
- The runtime and its workspace live in the companion's OWN data directory (`files/agent-runtime/{runtime-<abi>,workspace}`); because the main app cannot read another UID's files, the workspace file operations are ALSO brokered: `IShellService` grew synchronous `workspace{Glob,Grep,Read,Edit,Create}` methods (returning the `ToolResult` Parcelable) implemented by `WorkspaceOps` in the companion, and `ShellExecutor`'s Android actual routes `executeWorkspaceOperation` through the bridge. The main app keeps no local workspace implementation.
- The main app registers `RuntimeShellClient` (AIDL client) as the shared `ShellRuntimeRegistry.bridge` at startup; `ShellExecutor`'s Android actual routes `executeShellCommand`/`executeWorkspaceOperation` through the bridge, and the companion's absence disables shell execution entirely (no fallback shell): `createBuiltinChatTools()` returns an empty list when the bridge reports the companion missing (`AppContainer.builtinTools` re-evaluates per access, so installing the companion re-enables tools for new screens), the chat input tool toggle disappears, and 设置 → 高级 → 终端 tells the user the companion app is missing. The binding must stay alive for the WHOLE request — unbinding early lets the cached-app freezer suspend the companion process mid-extraction (`do_freezer_trap`).
- **The companion app is also the terminal UI and has no AIDL session API**: `TerminalActivity` (launcher, see "Tool calling" above) owns its `TerminalSession` in-process over the vendored Termux emulator and the `cpp/termux.c` PTY JNI; the removed `IShellService.startSession`/`writeSession`/`stopSession` + `ShellSession.kt` read-eval-print-loop broker were the pre-PTY workaround for the deleted in-app terminal screen. `IShellService` now only carries `submit` (agent tool commands) + `cancel` + the synchronous workspace operations.
- **Prefix rewriting at extraction**: because the Termux bootstrap bakes `/data/data/com.termux/files/usr` into its scripts, `TermuxRuntime.patchTermuxPrefix` rewrites that prefix to the extracted copy for every text file under `bin/`, `libexec/`, `lib/apt/methods/` (shebang-guarded) and `etc/` (rc files have no shebang) — which is what makes `etc/profile`, `etc/bash.bashrc`, `etc/motd.sh` and `apt-key` resolve inside our prefix. The Termux-app `etc/profile.d` hooks are deleted during extraction (`DROPPED_PROFILE_HOOKS`): `01-termux-bootstrap-second-stage-fallback.sh` would run the one-time bootstrap second stage (package postinst scripts) against this prefix on the first login shell, which this runtime deliberately never does, and `init-termux-properties.sh` seeds the Termux app's own `~/.termux/termux.properties` from a HOME baked into the Termux package (outside the rewritten prefix), so it can only fail with "mkdir: Permission denied" on every login. The install marker carries a `layout` generation (`LAYOUT_VERSION`) so a prefix extracted by an older build is re-extracted instead of reused. `TermuxRuntime.writePrefixOverrides` regenerates `etc/apt/apt.conf`, the CA bundle and `etc/messenger.bashrc` on every ensure.
- Distribution: users install the matching-ABI runtime APK alongside the main APK; CI builds and attaches `runtime-*-release.apk` per ABI. The main app carries `<queries>` for the companion package (visibility) and `<uses-permission>` for its signature permission.
The terminal TOOL is never argument-policed in either mode (the policy was removed entirely); writable mode only changes the DECLARATION — `edit`/`create` are declared as separate write tools and the terminal's description swaps to the unrestricted variant. The sandbox confines what an execution can touch. Tools execute automatically in BOTH modes — there is no per-call confirmation dialog (the old manual/auto toggle and the consent gate were both removed). The companion app's terminal screen deliberately has no policy filter.

#### Vendored Termux terminal (Apache-2.0, not GPL)

`runtime/src/main/java/com/termux/{terminal,view}` and `runtime/src/main/cpp/termux.c` are vendored from **termux-app v0.118.3** (`terminal-emulator` + `terminal-view`), which the upstream license exempts from the app's GPLv3: those two modules are Apache-2.0 (Android-Terminal-Emulator lineage), so they are compatible with this Apache-2.0 project. Each file keeps that attribution in its header. Only change applied: the two package-level `R` imports point at `cc.ptoe.messenger.runtime.R`. The modules are self-contained (no `termux-shared` dependency, which IS GPLv3 and must not be copied); `terminal-view`'s text-selection strings/drawables are re-declared in `runtime/src/main/res`. Re-vendoring = re-copying those two source trees from the pinned tag, re-applying the R import, and keeping `ndkVersion`/CMake flags in sync with `runtime/build.gradle.kts`.

#### SELinux domain rule (measured, do not regress)

An app's SELinux domain comes from its OWN package's `targetSdkVersion` (seInfo is stored per package at install time; the min/targetSdk pairing matters — this image buckets `minTargetSdkVersion=28 → untrusted_app_27`, `29 → _29`, `30 → _30`, `32 → _32`, `34 → untrusted_app`). BUT when packages share a UID (`android:sharedUserId`), PackageManager derives the seInfo's targetSdkVersion from the **highest targetSdk member present in the shared user at the moment the package is installed**: installing the targetSdk-28 companion after the targetSdk-36 main app stores `targetSdkVersion=36` for it and its process lands in the modern `untrusted_app` domain, where W^X denies `execute_no_trans` on `app_data_file` (install-order dependent, silently broken — this is why sharedUserId is banned here). `run-as` tests are also misleading: `runas_app` retains exec rights the app domains do not.

AGP's `CANNOT_BUILD_SELECTED_TARGET_ABI` sync diagnostic is suppressed in
`gradle.properties` because some transitive Android native libraries publish
only ARM variants while the app intentionally retains an `x86_64` split for
emulator builds.

### Local Development

1. Create a `local.properties` file with `sdk.dir=/path/to/android/sdk`
2. Initialize submodules after cloning: `git submodule update --init --recursive`
3. For release builds, set up keystore in `keyring/messenger-release.jks`
4. Environment variables for signing: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
5. Version code can be overridden with `VERSION_CODE` env var; version name with `VERSION_NAME` env var
6. For the account server, create `server/.env.local` from `server/.env.example` and provide `JWT_SECRET` and `MONGODB_URI` (Atlas or replica set). Avatar storage defaults to the self-hosted filesystem backend (`BLOB_STORAGE_DIR`, no Vercel account needed); set `BLOB_READ_WRITE_TOKEN` (or `BLOB_BACKEND=vercel`) only for Vercel deployments. The first account registered through the website becomes the admin

### Server Commands

```bash
# Install server dependencies
cd server && pnpm install

# Run the account server locally
cd server && pnpm dev

# Type-check the server
cd server && pnpm typecheck

# Lint the server
cd server && pnpm lint
```

### R8 Troubleshooting

R8 is a possible investigation point for release-only failures involving
reflection, serialization, generated code, or manifest components. It is not a
routine verification step.

**Applicability**: R8 / minification is **disabled** for `:androidApp`
(`isMinifyEnabled = false`, `isShrinkResources = false` in
`androidApp/build.gradle.kts`) and ProGuard is **disabled** for `:desktopApp`
(`buildTypes { release { proguard { isEnabled.set(false) } } }` in
`desktopApp/build.gradle.kts`). R8 therefore only runs on the `:wear` module.
The steps below apply to `:wear` (and to any future module that re-enables
minification); do not run `:androidApp:assembleRelease` or
`:desktopApp:packageRelease*` expecting R8 output — there is none.

For daily tasks, maintain the affected module's `proguard-rules.pro` whenever a
R8-sensitive class, method, annotation, serialized model, generated callback,
or manifest component is added, removed, or changed. Remove stale rules when
the corresponding entry point is renamed or deleted, and keep rules as narrow
as practical.

Do not run R8 checks or R8-enabled `assembleRelease` for ordinary tasks. Those
builds are slow and have a high performance cost. Follow the steps below only
when an R8 issue is being investigated, the user explicitly requests
verification, or a release/R8 configuration change requires validation:

1. Reproduce the shrinker output from a clean task run:

   ```bash
   ./gradlew --no-daemon :wear:assembleRelease --rerun-tasks
   ```

2. Check `wear/build/outputs/mapping/release/`:
   - `mapping.txt` confirms that a class survived and shows its obfuscated name.
   - `usage.txt` lists removed classes and members; distinguish a fully removed class from an optimized-away member listed beneath a surviving class.
   - `seeds.txt` confirms that a keep rule matched, but is not by itself proof that the final APK contains the class.
   - `configuration.txt` confirms the effective rules, including consumer rules from dependencies.

3. Compare the reports with the final APK. Inspect the release APK DEX files
   with Android Studio APK Analyzer or `apkanalyzer`, and verify the relevant
   class plus generated or anonymous callback classes are present. For this
   project, pay special attention to Manifest components, Room's generated
   `MessengerDatabase_Impl` and DAO implementations, Retrofit API interfaces
   and Gson DTOs, and the Wear `WearNetworkBridge` WebSocket/NSD callbacks.

4. Identify the reflective or serialized entry point before adding a rule.
   Preserve only the smallest required scope. Keep runtime annotation and
   generic metadata when the library reads them:

   ```proguard
   -keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
   -keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
   -keepattributes Signature,InnerClasses,EnclosingMethod
   ```

5. Add the rule to the affected module's `proguard-rules.pro`, rebuild, and
   confirm that it appears in `configuration.txt`, the expected class is not
   fully removed according to `usage.txt`/`mapping.txt`, and it is present in
   the APK. Avoid blanket `-keep class ** { *; }` rules because they hide
   missing entry points and defeat shrinking.

6. If the root is unclear, temporarily add
   `-whyareyoukeeping class <fully.qualified.ClassName>` to the affected rules
   file, rebuild, and use the R8 reason chain to find the missing or unexpected
   entry point. Remove this diagnostic rule after the investigation.

7. Install the minified APK on a device or emulator and smoke-test the affected
   path. For crash reports, use the matching `mapping.txt` with Android's
   `retrace` tool before diagnosing the stack trace. A successful R8 build is
   not sufficient proof that runtime reflection or serialization works.

## CI/CD

GitHub Actions CI/CD is split into 6 workflow files under `.github/workflows/`, organized as a nested call chain:

- **Reusable build workflows** (`build-android.yml`, `build-wear.yml`, `build-desktop.yml`): Each is triggered via `workflow_call`. Every build computes `VERSION_CODE` from `git rev-list --count HEAD` (full checkout via `fetch-depth: 0`) and initializes git submodules recursively (`server/`).
  - `build-android.yml` runs on `ubuntu-latest` (bash + `./gradlew`), installs the pinned NDK `29.0.14206865` + CMake `3.22.1` (`android-actions/setup-android` packages) for `:runtime`'s vendored PTY JNI, builds `:androidApp:assembleRelease` + `:runtime:assembleRelease`, and uploads `androidApp-release` + `runtime-release`.
  - `build-wear.yml` runs on `ubuntu-latest` (bash + `./gradlew`), builds `:wear:assembleRelease`, and uploads `wear-release`.
  - `build-desktop.yml` runs on `windows-latest` (pwsh + `.\gradlew.bat`, required for MSI packaging), builds `:desktopApp:packageReleaseMsi`, and uploads `desktop-msi` (unsigned).
- **`build-all.yml` (aggregator)**: Reusable workflow triggered via `workflow_call`. Declares three explicit jobs (`build-android`, `build-wear`, `build-desktop`) with no `needs` between them, so they run in parallel — each calls its corresponding `build-<target>.yml` via `uses:` with `secrets: inherit`. (GitHub Actions does not support `strategy.matrix` on jobs that call reusable workflows via `uses:`, so the three calls are written out explicitly instead of generated from a matrix.)
- **`ci.yml` (Push/PR CI)**: Triggered on push to `main` and PRs to `main`, but only when project code or build dependencies change. The `paths` filter (applied identically to both `push` and `pull_request`) includes: `shared/**`, `androidApp/**`, `desktopApp/**`, `wear/**`, `runtime/**`, root `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/**`, `gradlew` / `gradlew.bat`, and `.github/workflows/**`. Documentation (`README.md`, `AGENTS.md`), `server/**`, `specs/**`, `LICENSE`, `logo.*`, `.idea/**`, `.gitmodules`, `licenserc.toml`, etc. do NOT trigger CI. A single `build-all` job calls `./.github/workflows/build-all.yml` with `secrets: inherit`.
- **`release.yml` (Tag-triggered Release CI)**: Triggered only on `v*` tags. A `build-all` job calls `./.github/workflows/build-all.yml` (three parallel builds), then a `release` job (`needs: build-all`, runs on `ubuntu-latest`) downloads the Android ABI APKs, runtime companion APKs, Wear APK, and desktop MSI and creates a GitHub Release via `softprops/action-gh-release@v2` with `generate_release_notes: true`.
- **Caching**: `gradle/actions/setup-gradle@v4` with `cache-read-only: ${{ github.ref != 'refs/heads/main' }}` (PR builds only read cache, main pushes write it) and `gradle-home-cache-cleanup: true`; plus a dedicated `~/.konan` Kotlin/Native compiler cache keyed on `*.gradle.kts` / `libs.versions.toml` hashes.
- **Signing**: Keystore is materialized from the `KEYSTORE_BASE64` secret into `keyring/messenger-release.jks` (only on push builds, not PRs) inside the androidApp and wear build workflows; `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets feed the signing config. Desktop MSI is unsigned.

## Git Workflow

- Main branch: `main`
- Release tags: `v*` (e.g., `v123`, named after version code = commit count)
- Version code = number of commits (auto-calculated in CI)
- Version name = `v{yyyyMMdd}` from latest commit's date for reproducibility (e.g., `v20260711`)
- Each completed task should be committed locally; do not push unless explicitly requested

### Commit After Each Task

When a task is completed:

```bash
git add <changed-files>
git commit -m "<descriptive commit message>"
```

Write clear, concise commit messages describing what was changed and why. Push only when explicitly requested.

## Key Files Reference

- [build.gradle.kts](file:///c:/Users/deskt/Desktop/projects/Messenger/build.gradle.kts) - Root build file with version calculation
- [settings.gradle.kts](file:///c:/Users/deskt/Desktop/projects/Messenger/settings.gradle.kts) - Module includes (`:shared`, `:androidApp`, `:desktopApp`, `:wear`)
- [libs.versions.toml](file:///c:/Users/deskt/Desktop/projects/Messenger/gradle/libs.versions.toml) - Dependency version catalog
- [shared/build.gradle.kts](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/build.gradle.kts) - KMP library build config
- [androidApp/build.gradle.kts](file:///c:/Users/deskt/Desktop/projects/Messenger/androidApp/build.gradle.kts) - Android app shell build config
- [desktopApp/build.gradle.kts](file:///c:/Users/deskt/Desktop/projects/Messenger/desktopApp/build.gradle.kts) - Desktop app shell build config
- [AppContainer.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/di/AppContainer.kt) - DI container (replaces `MessengerApplication.initRepositories()`)
- [ConversationTitleGenerator.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/domain/usecase/ConversationTitleGenerator.kt) - LLM conversation-title generation via the built-in title agent (first-round Done hook)
- [MessengerApplication.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/androidApp/src/main/kotlin/cc/ptoe/messenger/MessengerApplication.kt) - App entry point & DI owner (Android)
- [MainActivity.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/androidApp/src/main/kotlin/cc/ptoe/messenger/MainActivity.kt) - Main activity (Android)
- [Screen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/Screen.kt) - Navigation routes
- [NavGraph.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/NavGraph.kt) - Navigation graph
- [MessengerDatabase.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/MessengerDatabase.kt) - Room database
- [MainScaffold.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/MainScaffold.kt) - Main app scaffold
- [AgentsDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/agents/AgentsDualPaneScreen.kt) - Agents List-Detail dual-pane (non-Compact width)
- [AgentConversationsScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/agents/AgentConversationsScreen.kt) - Agent-scoped conversation list sub-page (Agents page tap target; top-bar settings icon opens the editor)
- [ProjectConversationsScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/projects/ProjectConversationsScreen.kt) - Second-level page: a project's conversations (agent-scoped when entered from an Agent chat list)
- [ProjectListItem.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/ProjectListItem.kt) - Projects section + project row shown in both chat lists
- [Project.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/domain/model/Project.kt) - Project domain model + workspace folder-name normalization
- [ProvidersDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/providers/ProvidersDualPaneScreen.kt) - Providers List-Detail dual-pane (non-Compact width)
- [SettingsDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/settings/SettingsDualPaneScreen.kt) - Settings List-Detail dual-pane (non-Compact width)
- [TerminalActivity.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/runtime/src/main/kotlin/cc/ptoe/messenger/runtime/TerminalActivity.kt) - Companion terminal UI (Termux-style interactive shell; opened from Settings → Advanced → Terminal)
- [ExtraKeysBar.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/runtime/src/main/kotlin/cc/ptoe/messenger/runtime/ExtraKeysBar.kt) - Terminal shortcut bar (Termux default extra-keys layout)
- [TermuxRuntime.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/runtime/src/main/kotlin/cc/ptoe/messenger/runtime/TermuxRuntime.kt) - Bootstrap install, prefix rewriting, command + PTY session creation
- [ShellService.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/runtime/src/main/kotlin/cc/ptoe/messenger/runtime/ShellService.kt) - Companion runtime AIDL service (legacy SELinux domain shell host)
- [ContextMenu.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/ContextMenu.kt) - Shared `Modifier.onContextMenu` + `CursorDropdownMenu` helpers (non-Compact width)
- [MultiSelectTopBar.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/MultiSelectTopBar.kt) - Shared selection-mode TopAppBar for long-press multi-select
- [ci.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/ci.yml) - Push/PR CI: calls build-all.yml
- [release.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/release.yml) - Tag-triggered (v*) Release CI: calls build-all.yml + GitHub Release
- [build-all.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-all.yml) - Reusable aggregator: matrix-parallel call of the 3 build workflows
- [build-android.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-android.yml) - Reusable workflow: androidApp ABI release APKs
- [build-wear.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-wear.yml) - Reusable workflow: wear release APK
- [build-desktop.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-desktop.yml) - Reusable workflow: Desktop MSI distribution
- [messenger-tui/src/app.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/app.rs) - Terminal client state machine: CWD-project bootstrap, popup-stack key routing, slash-command dispatch, transcript notes, cloud actions
- [messenger-tui/src/popup.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/popup.rs) - The popup stack: command palette, generic select list, form editor, confirmation, keys reference
- [messenger-tui/src/commands.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/commands.rs) - The `/` command table (data only: name, args, summary, the picker it opens)
- [messenger-tui/src/render.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/render.rs) - Document AST → terminal lines (CJK-aware wrapping, tables, bordered code/tool/math boxes)
- [messenger-tui/src/workspace.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/workspace.rs) - The five workspace tools over the real filesystem (mirrors WorkspaceTools.desktop.kt)
- [messenger-tui/src/text.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/text.rs) - The terminal's own `Line`/`Span`/`Style`/`Color`/`Modifier` (no widget framework; the only types `render.rs` and `highlight.rs` speak)
- [messenger-tui/src/ui.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/ui.rs) - Frame composition: `compose(app, w, h) -> Frame` (transcript, bordered editor, context line, footer, the `/` palette above the editor, centred modals) — touches no terminal, so the tests assert on frames directly
- [messenger-tui/src/screen.rs](file:///c:/Users/deskt/Desktop/projects/Messenger/core/rust/crates/messenger-tui/src/screen.rs) - Raw mode + per-line differential painting on the main screen; turns `Frame::scrolled` into real terminal scrolls so history lands in native scrollback
