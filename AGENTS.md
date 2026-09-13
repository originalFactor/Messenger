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
- **Modules**: `shared` (KMP library: shared Android/Desktop logic), `androidApp` (Android application shell), `desktopApp` (Desktop application shell), `wear` (Wear OS), `server` (Next.js SaaS platform — Vercel or self-hosted: official website, web console, cloud sync, card-key billing, AI API relay)

## Project Structure

```
Messenger/
├── .github/workflows/          # GitHub Actions CI/CD (split into 6 files)
│   ├── build-android.yml       # Reusable workflow: androidApp release APK
│   ├── build-wear.yml          # Reusable workflow: wear release APK
│   ├── build-desktop.yml       # Reusable workflow: Desktop MSI distribution
│   ├── build-all.yml           # Reusable aggregator: 3 explicit parallel jobs calling build-*.yml
│   ├── ci.yml                  # Push/PR CI: calls build-all.yml
│   └── release.yml             # Tag-triggered (v*): calls build-all.yml + GitHub Release
├── gradle/
│   ├── libs.versions.toml      # Version catalog for dependencies
│   └── wrapper/                # Gradle wrapper
├── keyring/                    # Signing keystore
├── llm-typewriter/             # Git submodule: source build of the llm-typewriter library (KMP)
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
│       │       │   │   ├── ChatRepositoryImpl.kt
│       │       │   │   ├── ConversationRepositoryImpl.kt
│       │       │   │   ├── CurrentAgentRepositoryImpl.kt
│       │       │   │   ├── MessageRepositoryImpl.kt
│       │       │   │   ├── ModelRepositoryImpl.kt
│       │       │   │   └── ProviderRepositoryImpl.kt
│       │       │   └── util/
│       │       │       ├── FileKit.kt
│       │       │       ├── Logger.kt
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
│       │       │   │   └── Provider.kt
│       │       │   └── repository/     # Repository interfaces
│       │       │       ├── AgentRepository.kt
│       │       │       ├── ApiRepository.kt
│       │       │       ├── ChatRepository.kt
│       │       │       ├── ConversationRepository.kt
│       │       │       ├── CurrentAgentRepository.kt
│       │       │       ├── MessageRepository.kt
│       │       │       ├── ModelRepository.kt
│       │       │       └── ProviderRepository.kt
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
│       │           │   │   ├── AgentEditScreen.kt
│       │           │   │   ├── AgentMarketScreen.kt
│       │           │   │   └── AgentsScreen.kt
│       │           │   ├── chat/
│       │           │   │   ├── AiMessageBubble.kt
│       │           │   │   ├── ChatInputBar.kt
│       │           │   │   ├── ChatScreen.kt
│       │           │   │   ├── MessageActionMenu.kt
│       │           │   │   └── UserMessageBubble.kt
│       │           │   ├── components/
│       │           │   │   ├── AgentAvatar.kt
│       │           │   │   ├── BottomNavBar.kt
│       │           │   │   ├── ConfirmationDialog.kt
│       │           │   │   ├── EmptyState.kt
│       │           │   │   ├── InputDialog.kt
│       │           │   │   ├── ListItem.kt
│       │           │   │   ├── LoadingIndicator.kt
│       │           │   │   ├── MainScaffold.kt
│       │           │   │   ├── SectionHeader.kt
│       │           │   │   └── SingleChoiceDialog.kt
│       │           │   ├── conversations/
│       │           │   │   ├── ConversationSettingsScreen.kt
│       │           │   │   ├── ConversationsDualPaneScreen.kt
│       │           │   │   └── ConversationsScreen.kt
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
│       │               ├── AgentEditViewModel.kt
│       │               ├── AgentMarketViewModel.kt
│       │               ├── AgentsViewModel.kt
│       │               ├── ChatViewModel.kt
│       │               ├── ConversationSettingsViewModel.kt
│       │               ├── ConversationsViewModel.kt
│       │               ├── ProviderDetailViewModel.kt
│       │               ├── ProviderEditViewModel.kt
│       │               ├── ProvidersViewModel.kt
│       │               └── SettingsViewModel.kt
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
│       │       └── presentation/
│       │           ├── platform/
│       │           │   ├── ImagePicker.desktop.kt        # FileDialog-based
│       │           │   └── PlatformServices.desktop.kt
│       │           └── theme/
│       │               └── Theme.desktop.kt
│       └── commonTest/
│           └── kotlin/cc/ptoe/messenger/
│               ├── ExampleUnitTest.kt
│               └── presentation/utils/StripThinkBlockTest.kt
├── androidApp/                 # Android application shell (com.android.application)
│   ├── build.gradle.kts        # AGP 9 built-in Kotlin + kotlin.compose + kotlin.serialization
│   ├── proguard-rules.pro      # R8/ProGuard rules for androidApp
│   └── src/main/
│       ├── AndroidManifest.xml # Full application manifest (<application>/<activity>/<service>/<provider>)
│       ├── kotlin/cc/ptoe/messenger/
│       │   ├── MainActivity.kt
│       │   └── MessengerApplication.kt
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

- `shared` is a KMP library using `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library` plugins. Contains all shared Android/Desktop code in `commonMain`, platform-specific `actual` implementations in `androidMain` and `desktopMain`. Room KSP registration is per-target: `add("kspAndroid", libs.androidx.room.compiler)` + `add("kspDesktop", libs.androidx.room.compiler)`. The `compose.resources` block stays in `shared`.
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

- **Room Database**: `MessengerDatabase` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/MessengerDatabase.kt`) with 5 entities
  - `ProviderEntity` - API providers
  - `ModelEntity` - Available models per provider
  - `AgentEntity` - AI agent configurations
  - `ConversationEntity` - Chat conversations
  - `MessageEntity` - Chat messages (`partsJson` column stores multimodal `ContentPart` payloads)
- Database version: 12 (with `fallbackToDestructiveMigration`). v12 added `ModelEntity.contextWindow` and the conversation auto-summary columns (`ConversationEntity.contextSummary` / `contextSummaryUntil` / `contextTokens` / `contextTokensAt`)
- Room compiler is registered per-target via KSP in `shared/build.gradle.kts`: `add("kspAndroid", libs.androidx.room.compiler)` + `add("kspDesktop", libs.androidx.room.compiler)`

### Navigation

- Uses Navigation Compose
- Routes defined in `Screen.kt` sealed class (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/Screen.kt`)
- NavGraph defined in `NavGraph.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/NavGraph.kt`); uses `backStackEntry.arguments?.read { getStringOrNull("key") }` for Compose Multiplatform SavedState API compatibility
- Bottom navigation routes defined in `BottomLevelRoutes.kt` (in `shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/BottomLevelRoutes.kt`)
- **Adaptive layout (M3 window size classes)**: `MainScaffold.kt` uses `BoxWithConstraints` + `windowSizeClassFor(maxWidth)` (from `presentation/utils/WindowSizeClass.kt`) to switch between a bottom `NavigationBar` (Compact width < 600 dp, phones) and a left `NavigationRailBar` (Medium/Expanded width ≥ 840 dp, tablet landscape / desktop). The `NavigationRailBar` composable lives next to `BottomNavBar` in `BottomNavBar.kt`.
- **List-Detail dual-pane (non-Compact width)**: when `WindowSizeClass != Compact` (Medium/Expanded), `NavGraph.kt` renders a `*DualPaneScreen` instead of pushing the detail route — left pane is the list, right pane is the detail surface. This applies to `Conversations` (`ConversationsDualPaneScreen` — left pane is the 360 dp conversation list, right pane is `ChatScreen` with `showBackButton = false`), `Agents` (`AgentsDualPaneScreen`), `Providers` (`ProvidersDualPaneScreen`), and `Settings` (`SettingsDualPaneScreen`). Selection state is `rememberSaveable`. On Compact width the existing single-pane + push flow is preserved. The dual-pane trigger threshold was unified to `!= Compact` (previously `Expanded` for Conversations only).
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
- **AI API (OpenAI-compatible proxy)**: `/v1/models` and `/v1/chat/completions` authenticate with a per-user API key (`Authorization: Bearer sk-…`, regenerable in the console). Available models = the union of models served by enabled `upstreams` (no global catalog); `/v1/models` enriches each entry with a non-standard `context_window` field (tokens, 0 = unlimited) resolved with the exact `resolveModelMeta` semantics via `getModelPlaza()` (override ON → custom value with models.dev fallback, OFF → models.dev, multiple upstreams → max). Requests relay to the highest-priority enabled upstream (failover on connect error/5xx before first byte); streaming responses inject `stream_options.include_usage` and pass upstream SSE bytes through verbatim while sniffing usage; after completion the server deducts `ceil(promptTokens × inputRate + completionTokens × outputRate)` from the user's unexpired quota entitlements (first-expiring-first) and writes a `usage_logs` document. Per-model metadata (context window + rates) resolves per model — `modelMeta[modelId].override` ON uses that entry's custom values, OFF uses live models.dev metadata (rates normalized against deepseek-v4.1-flash); zero rates = the call is free, zero context = unlimited. 402 (quota exhausted/expired), 401, 404, and 502 all use OpenAI `{"error":{...}}` bodies so the app's `extractHttpErrorMessage` displays them
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
- **Cloud settings UI (client)**: `CloudSettingsScreen` shows the account card, a 套餐 (plan) card (aggregate balance + latest expiry from `CloudUser.quotaBalance/quotaExpiresAt`, plus per-entitlement rows — plan name, or localized source label for admin grants / card redemptions / migrated quotas — from the `quotaEntitlements` list `/api/auth/me` now returns), and the sync/security sections. The cloud instance URL is no longer edited inline: the Server card is a read-only entry row that opens the `CloudServerScreen` secondary page (URL field + use-default + save via `SettingsViewModel.setCloudServerUrl`), which both layouts share via in-screen state (no nav route). The sub-page registers `presentation/platform/BackHandler` (expect/actual: `androidx.activity.compose.BackHandler` on Android, no-op on Desktop) so the system back gesture returns to the Cloud settings page instead of popping the route
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

### Chat bubble rendering (mobile)

- AI message bubbles use [`llm-typewriter`](https://github.com/ECSDevs/llm-typewriter) for both the streaming reveal and the static rendering of completed messages.
- LaTeX 渲染由 `llm-typewriter` 委托给 [RaTeX-CMP](https://github.com/darriousliu/RaTeX-CMP)（纯 Rust [RaTeX](https://github.com/erweixin/RaTeX) KaTeX 兼容引擎），Android 与 JVM Desktop 共用同一 Rust 核心 + KaTeX 字体；不再依赖 AndroidMath / KCEF / MathJax / Jogamp。
- Messenger builds `llm-typewriter` directly from the checked-out `llm-typewriter/` git submodule via `includeBuild("llm-typewriter")` in `settings.gradle.kts`, so Gradle substitutes the source build instead of downloading `cc.ptoe:llm-typewriter` from Maven Central.
- The library ships progressive Markdown (bold, code fences with syntax highlighting, links) and inline / display LaTeX math, so the AI bubble is rendered via `StreamingTypewriter` + `rememberMarkdownTypewriterRenderer`.
- `ChatViewModel` owns a single `StreamingTypewriterState` and a `streamingMessageId: StateFlow<String?>`. Each SSE `Content` event calls `typewriterState.appendToken(...)`; on `Done` the view model calls `completeSource()`; on `Error` / stop it calls `stop()` (and `skipToEnd()` on stop so the partial content stays visible). The `streamingMessageId` is cleared at the same point so the bubble switches to the static (`baseDelayMs = 0` + `skipToEnd()`) path.
- JitPack 仓库 (`https://jitpack.io`) 现在仅为 `ucrop` (`com.github.Yalantis:ucrop`) 保留，不再为 AndroidMath（RaTeX 迁移后已移除，详见「Dependency management」段）。`settings.gradle.kts` 仍声明该仓库。

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

6. **Dependency management**: Use version catalog (`gradle/libs.versions.toml`) for all dependency versions. `shared/build.gradle.kts`, `androidApp/build.gradle.kts`, and `desktopApp/build.gradle.kts` all consume the version catalog. Root `build.gradle.kts` applies a global `allprojects { configurations.all { exclude(group = "com.google.guava", module = "listenablefuture") } }` to resolve `checkDebugDuplicateClasses` caused by the standalone `listenablefuture:1.0` stub (pulled by `androidx.concurrent:concurrent-futures` via `androidx.core`) colliding with the full Guava that KSP/Room compiler pulls in at build-time for `com.google.common.collect.ImmutableList`. (Historically this was also needed for guava-18.0 dragged in by AndroidMath via llm-typewriter; that path is gone after the RaTeX migration.) JitPack 仓库现在仅为 `ucrop` (`com.github.Yalantis:ucrop`) 保留，不再为 AndroidMath。

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

## Build & Run

### Prerequisites

- JDK 17
- Android Studio (or Android SDK)
- Gradle wrapper is included

### Build Commands

```bash
# Build debug APK
./gradlew :androidApp:assembleDebug
./gradlew :wear:assembleDebug

# Build release APK
./gradlew :androidApp:assembleRelease
./gradlew :wear:assembleRelease

# Run unit tests
./gradlew :shared:allTests

# Run lint
./gradlew :androidApp:lintDebug

# Run Desktop app
./gradlew :desktopApp:run

# Build Desktop native distributions
./gradlew :desktopApp:packageReleaseDmg   # macOS
./gradlew :desktopApp:packageReleaseMsi   # Windows
./gradlew :desktopApp:packageReleaseDeb   # Linux
```

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
   ./gradlew :wear:assembleRelease --rerun-tasks
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

- **Reusable build workflows** (`build-android.yml`, `build-wear.yml`, `build-desktop.yml`): Each is triggered via `workflow_call`. Every build computes `VERSION_CODE` from `git rev-list --count HEAD` (full checkout via `fetch-depth: 0`) and initializes git submodules recursively so the `llm-typewriter/` source build is present on CI.
  - `build-android.yml` runs on `ubuntu-latest` (bash + `./gradlew`), builds `:androidApp:assembleRelease`, and uploads `androidApp-release`.
  - `build-wear.yml` runs on `ubuntu-latest` (bash + `./gradlew`), builds `:wear:assembleRelease`, and uploads `wear-release`.
  - `build-desktop.yml` runs on `windows-latest` (pwsh + `.\gradlew.bat`, required for MSI packaging), builds `:desktopApp:packageReleaseMsi`, and uploads `desktop-msi` (unsigned).
- **`build-all.yml` (aggregator)**: Reusable workflow triggered via `workflow_call`. Declares three explicit jobs (`build-android`, `build-wear`, `build-desktop`) with no `needs` between them, so they run in parallel — each calls its corresponding `build-<target>.yml` via `uses:` with `secrets: inherit`. (GitHub Actions does not support `strategy.matrix` on jobs that call reusable workflows via `uses:`, so the three calls are written out explicitly instead of generated from a matrix.)
- **`ci.yml` (Push/PR CI)**: Triggered on push to `main` and PRs to `main`, but only when project code or build dependencies change. The `paths` filter (applied identically to both `push` and `pull_request`) includes: `shared/**`, `androidApp/**`, `desktopApp/**`, `wear/**`, `llm-typewriter/**`, root `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/**`, `gradlew` / `gradlew.bat`, and `.github/workflows/**`. Documentation (`README.md`, `AGENTS.md`), `server/**`, `specs/**`, `LICENSE`, `logo.*`, `.idea/**`, `.gitmodules`, `licenserc.toml`, etc. do NOT trigger CI. A single `build-all` job calls `./.github/workflows/build-all.yml` with `secrets: inherit`.
- **`release.yml` (Tag-triggered Release CI)**: Triggered only on `v*` tags. A `build-all` job calls `./.github/workflows/build-all.yml` (three parallel builds), then a `release` job (`needs: build-all`, runs on `ubuntu-latest`) downloads all three artifacts (androidApp APK, wear APK, desktop MSI) and creates a GitHub Release via `softprops/action-gh-release@v2` with `generate_release_notes: true`, attaching all three binaries.
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
- [MessengerApplication.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/androidApp/src/main/kotlin/cc/ptoe/messenger/MessengerApplication.kt) - App entry point & DI owner (Android)
- [MainActivity.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/androidApp/src/main/kotlin/cc/ptoe/messenger/MainActivity.kt) - Main activity (Android)
- [Screen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/Screen.kt) - Navigation routes
- [NavGraph.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/navigation/NavGraph.kt) - Navigation graph
- [MessengerDatabase.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/data/local/MessengerDatabase.kt) - Room database
- [MainScaffold.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/MainScaffold.kt) - Main app scaffold
- [AgentsDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/agents/AgentsDualPaneScreen.kt) - Agents List-Detail dual-pane (non-Compact width)
- [ProvidersDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/providers/ProvidersDualPaneScreen.kt) - Providers List-Detail dual-pane (non-Compact width)
- [SettingsDualPaneScreen.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/settings/SettingsDualPaneScreen.kt) - Settings List-Detail dual-pane (non-Compact width)
- [ContextMenu.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/ContextMenu.kt) - Shared `Modifier.onContextMenu` + `CursorDropdownMenu` helpers (non-Compact width)
- [MultiSelectTopBar.kt](file:///c:/Users/deskt/Desktop/projects/Messenger/shared/src/commonMain/kotlin/cc/ptoe/messenger/presentation/ui/components/MultiSelectTopBar.kt) - Shared selection-mode TopAppBar for long-press multi-select
- [ci.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/ci.yml) - Push/PR CI: calls build-all.yml
- [release.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/release.yml) - Tag-triggered (v*) Release CI: calls build-all.yml + GitHub Release
- [build-all.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-all.yml) - Reusable aggregator: matrix-parallel call of the 3 build workflows
- [build-android.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-android.yml) - Reusable workflow: androidApp release APK
- [build-wear.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-wear.yml) - Reusable workflow: wear release APK
- [build-desktop.yml](file:///c:/Users/deskt/Desktop/projects/Messenger/.github/workflows/build-desktop.yml) - Reusable workflow: Desktop MSI distribution
