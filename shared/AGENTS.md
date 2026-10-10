# shared — KMP library

All shared Android/Desktop/Web logic. Clean Architecture: `domain/` (pure Kotlin models + repository interfaces), `data/` (Room, network, repository impls), `presentation/` (Compose UI + ViewModels), `di/` (manual DI).

## Source sets

- `commonMain` — target-agnostic. MUST NOT reference Room or `java.*` (the Room artifact does not exist for wasmJs; one such reference breaks the whole web target).
- `jvmSharedMain` — Android + Desktop only: Room, `OkioStorage`, `okio.FileSystem.SYSTEM`, Room-backed repositories, `CloudSyncRepository`. Tests for it live in `jvmSharedTest` (uses `org.junit`/`runBlocking`, not available on wasm).
- `androidMain` / `desktopMain` / `wasmJsMain` — platform `actual` implementations.
- The hierarchy is manual (`dependsOn` edges `commonMain` ← `jvmSharedMain` ← `androidMain`/`desktopMain`, `commonTest` ← `jvmSharedTest` ← `desktopTest`): the default Kotlin hierarchy template cannot express the Android+Desktop-but-not-wasm grouping, so root `gradle.properties` sets `kotlin.mpp.applyDefaultHierarchyTemplate=false`. Keep both the `dependsOn` calls and that property.
- Room compiler is registered per-target via KSP: `add("kspAndroid", …)` + `add("kspDesktop", …)`.
- The `compose.resources` block stays in `shared` (strings under `composeResources/values[-zh-rCN]/`).

## DI

Manual DI via `di/AppContainer.kt` (constructs all repositories/data sources). Android: `MessengerApplication` owns it (`MessengerApplication.instance`); Desktop: `Main.kt` constructs it; `wear` mirrors the pattern. Never reintroduce `MessengerApplication.initRepositories()`.

## Database (Room)

`MessengerDatabase` (in `jvmSharedMain`) with 6 entities: `ProviderEntity`, `ModelEntity`, `AgentEntity`, `ProjectEntity`, `ConversationEntity`, `MessageEntity`. Messages are embedded in conversations (`partsJson` column holds multimodal payloads); models are embedded in providers. Current version 21 with `fallbackToDestructiveMigration` — schema changes bump the version and add a migration (e.g. `MIGRATION_20_21` created the `projects` table + `conversations.projectId` FK `ON DELETE SET NULL`).

Key columns to know: `ModelEntity.contextWindow/inputRate/outputRate/inputModalities/outputModalities/supports*`; `AgentEntity.role/toolsEnabled/toolsFollowDefault/toolsConfig/description`; `ConversationEntity.contextSummary*/contextTokens*/overrideToolsEnabled/overrideToolsConfig/writable/projectId`.

## Navigation & adaptive UI

- `Screen.kt` sealed class routes (`createRoute()`), `NavGraph.kt` destinations, `BottomLevelRoutes.kt` for bottom-nav routes. Nav arguments read via `backStackEntry.arguments?.read { getStringOrNull("key") }` (CMP SavedState API).
- **Page transitions**: `NavGraph` wires NavHost transitions from `presentation/navigation/PageTransitions.kt`; each `Screen` has an order in `RouteOrders` (grouped by bottom-nav branch), later = slide in from right/bottom, earlier = reversed; pops add fade and follow the predictive-back gesture on Android.
- **In-screen sub-pages** (e.g. `CloudServerScreen` inside `CloudSettingsScreen`, tool-config overlays) are state-hosted, not routes, and register `presentation/platform/BackHandler` (expect/actual → `PredictiveBackHandler` on Android, requires manifest `enableOnBackInvokedCallback`; no-op on Desktop).
- **Adaptive layout**: `MainScaffold` uses `BoxWithConstraints` + `windowSizeClassFor()` — Compact (< 600 dp) = bottom `NavigationBar`, Medium/Expanded = `NavigationRailBar`. The floating bottom nav exists only in Compact; its 92 dp clearance is published as `LocalBottomNavClearance` (default 0) and consumed by top-level screens' FAB/list padding.
- **Dual-pane (non-Compact)**: `NavGraph` renders `*DualPaneScreen` (list left / detail right) for Conversations, Agents, Providers, Settings instead of pushing detail routes. Selection state is `rememberSaveable`.
- Non-Compact extras: right-click context menus (`components/ContextMenu.kt`), hover background, `TooltipBox` on rail/app-bar icons. Long-press multi-select uses `components/MultiSelectTopBar.kt` (Conversations, Agents).
- `ProviderEdit`/`AgentEdit` use optional-parameter routes; `AgentMarket*` routes require a signed-in Cloud user.

## Network / API

- OpenAI-compatible API via Retrofit + OkHttp + SSE; Gson converter. Auth-header handling lives in `PlatformHttpClient.kt` (platform actuals); `OpenAiClient.kt` is the API interface; DTOs in `data/remote/dto/`; SSE parsers in `data/remote/sse/`.
- **Reasoning formats**: `ChatStreamParser` wraps both `delta.reasoning_content` (full CoT, format `"reasoning_content"`) and `delta.reasoning` (reasoning summary, format `"reasoning_summary"`) into `<think>` blocks. The first detected format is persisted to `Conversation.reasoningFormat` and mirrored via cloud sync; `buildRequestMessages` replays history accordingly (`reasoning_content` restores think tags into the field, `think_tag` keeps tags as-is, `reasoning_summary` strips reasoning entirely — never send reasoning fields to those gateways).
- **Multimodal**: `ChatMessageDto.content` is a `JsonElement` (legacy string or OpenAI `[{type,text|image_url}]` array); `ApiRepositoryImpl` picks the shape from `Message.hasImages`. Images are downscaled to 1568 px, EXIF-rotated, cached as PNG under `filesDir/chat_images/`, reaped on delete.
- **Image cloud sync**: each image part in `partsJson` is `{type:"image", dataUri, localPath}` with the full base64 `dataUri`. The server stores `partsJson` verbatim (byte-identical round-trip — never strip/re-encode/reorder server-side); on pull, `CloudSyncRepository.rehydrateChatImages` recreates missing local files from `dataUri` under a deterministic `sync_{sha256(...)}` name (idempotent).
- Wear chat is forwarded to the phone over WebSocket (see `wear/AGENTS.md`); the watch never calls providers directly.

## Cloud client (`data/cloud/`)

- `CloudSyncRepository` (session cookie + per-account DataStore cursor) pulls `GET /api/sync?since=N`, applies tombstones transactionally, flattens provider models / conversation messages into Room, and pushes snapshots to the `PUT` endpoints. Local mutations are debounced; deletes become pending-delete markers until the server tombstone write succeeds. `CloudUser` carries optional SaaS fields (`role`, `aiApiKey`, `quotaBalance`, `quotaExpiresAt`).
- **Builtin cloud provider**: on login/`/me` refresh the client persists `aiApiKey` and idempotently seeds a local Provider with reserved ID `BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"` pointing at `{serverUrl}/v1`. It is excluded from cloud sync both ways, ignored by `hasLocalData()`, removed on logout/deletion/server-URL change, and hidden from edit/delete in `ProvidersScreen`. Its model list auto-syncs from `GET {server}/v1/models` (idempotent: preserves Room ids and `isEnabled`, deterministic ids for new models, forced on reseed/empty table, otherwise hourly — failures never block).
- **Built-in title agent** `Agent.BUILTIN_TITLE_AGENT_ID = "builtin-title-agent"` ("标题生成" literal) is seeded only when no title-role holder exists (`ensureBuiltinTitleAgent()`, called at startup / after full sync / `clearAllDataAndReinit`); excluded from sync like the builtin provider.
- Avatars: multipart upload to the avatar endpoints; GETs are authenticated proxies, downloaded during sync into `filesDir/cloud_avatars` so `AgentAvatar` is purely file-based.
- Cloud settings UI (`CloudSettingsScreen`): account card, plan card (aggregate balance + per-entitlement rows), card-key redemption (preview → confirm → redeem), sync/security sections; the server URL lives in the `CloudServerScreen` sub-page.

## Chat features

- **Context auto-summarization (80%)**: when the model declares `contextWindow > 0`, a projected context at 80% folds all sent messages older than the last 10 into a summary (non-stream call) stored on the conversation (`contextSummary` + `contextSummaryUntil`), injected at request time as a leading SYSTEM message — the UI history is never modified. Per-round usage is recorded after `Done` (exact for the builtin provider, estimates otherwise). Failures show a snackbar but never block the send. Fallback without a context window: `takeLast(20)`.
- **Agent roles**: `Agent.role` is `chat` (default) / `title`, plus the `isDefault` boolean. Regular/Default/Title Generator pickers in `AgentEditScreen`; Default and Title are single-holder roles — claiming one demotes the previous holder (`AgentEditViewModel.save()`). `AgentRepositoryImpl.delete` blocks the built-in, default, and title-holder; `clone` resets role. `Agent.description` is display-only and never sent to the model. All "select agent" pickers filter out title-role agents.
- **Title generation**: on the first non-blank stream `Done`, `ConversationTitleGenerator.launchGenerateIfNeeded` (Mutex-serialized, fire-and-forget) titles "untitled" conversations using the title holder's prompt/model; strips think blocks/quotes/newlines, caps length; re-reads before writing (never clobbers a manual rename or summary state). Failure surfaces a localized error and falls back to truncating the first user message.
- **Projects (a project IS a workspace)**: only project conversations may call workspace-bound tools (terminal + glob/grep/read/edit/create); a plain conversation declares none. `createBuiltinChatTools(workspaceRoot)` registers NOTHING for `null` (no platform fallback). `ChatViewModel.resolveWorkspaceFor(conv)` reads a FRESH DB row (never the `WhileSubscribed` project StateFlow). The model is told the cwd via `TurnRequest.workspace_note`. Deleting a project keeps its conversations (they become plain; `ON DELETE SET NULL`). Chat lists render a Projects section + Recent (`RECENT_CONVERSATION_LIMIT = 10`); `ProjectConversationsScreen` is the second-level page. Projects sync as their own collection and are applied BEFORE conversations on pull. Android workspace calls are brokered per-call over AIDL with an explicit `root` (see `runtime/AGENTS.md`).
- **Tool calling**: built-in `terminal` + `WorkspaceTool` (glob/grep/read/edit/create) via expect/actual `executeShellCommand` / `executeWorkspaceOperation`. Grep delegates to bundled ripgrep 14.1.1 (output contract `path:line:text` with NUL-terminated paths; `--hidden --no-ignore -g '!.git'`). Tool arguments are NEVER pre-screened — enforcement is sandbox-based (Android: companion UID; desktop: user process); reads are unrestricted, writes are declaration-gated by mode. `ChatTool.writeAccess` classifies write tools. Desktop shell = PowerShell (UTF-8) or `/bin/sh`, cwd = workspace; 60 s timeout kills the process. Android routes through the `:runtime` companion — NO fallback shell (absent companion ⇒ empty tool list).
- **Agent mode (read-only/writable)**: toggled in the `+` bottom panel (`ChatInputBar`), stored PER-CONVERSATION (`Conversation.writable`). Mode only changes the DECLARED tools (write tools + unrestricted terminal description in writable mode); tools execute automatically in both modes (no confirmation dialogs). Turn-time decisions read the DB row, never the `agentWritable` StateFlow. MCP tools are mode-agnostic.
- **Agent tool config**: per-Agent master + per-tool switches (`toolsEnabled` / `toolsConfig`, absent key = enabled) with the swipe takeover gesture for `toolsFollowDefault`; conversations can override via `overrideToolsEnabled` / `overrideToolsConfig`. A request carries `tools` when the effective master switch is on AND the platform registry is non-empty — `supportsToolCalling` metadata is deliberately NOT a gate.
- **Agent loop** (`ChatViewModel.launchChatTurn`): a `Done` with tool calls persists an assistant row, executes each call (persisting `role=TOOL` rows, `NonCancellable` interrupted marker on cancel), rebuilds context, and continues until final text / API error / cancel — unbounded rounds. Tool turns round-trip through `partsJson` (`ContentPart.ToolCall/ToolResult`, codec in `data/util/ContentPartCodec.kt`); `buildRequestMessages` maps them to OpenAI `tool_calls` / `role:"tool"` messages and drops leading orphan TOOL rows.
- **MCP**: `ToolsSettingsScreen` manages MCP servers (DataStore `mcp_servers_json`, `McpManager`); transports are stdio (Desktop `ProcessBuilder`, Android via `:runtime` AIDL `startMcpProcess`/`sendMcpInput`/`stopMcpProcess`) and SSE/HTTP (Ktor). `AppContainer.availableTools` aggregates built-in + MCP tools.

## Chat rendering

- Android: `:renderer-android` native Views (`RecyclerView` + `MessageView` + `DocumentView`) consuming Rust `DiffBatch` streams; Desktop: `DesktopDocumentBubble` / `DesktopDocumentView` Compose blocks keyed by `block.id`. LaTeX via the RaTeX engine (no italic; serif); code highlighting via Rust syntect.
- `ChatViewModel` exposes `streamingContent` / `streamingMessageId` StateFlows (SSE `Content` appends; reset on completion).
- An agent turn collapses into one `ChatListItem.ToolGroupItem` (tool rounds → results → final text, placeholder-row-first included); ERROR placeholders stay standalone; orphan TOOL rows render as standalone cards. Wear bypasses ChatViewModel and never runs tools.

## Design language (Material 3 Expressive)

- M3 Expressive (reference: InstallerX-Revived). Theming in `presentation/theme/Theme.kt`: static light/dark + Material You dynamic color, `ExpressiveShapes` (10–32 dp), `ExpressiveTypography`, `MaterialExpressiveTheme` + `MotionScheme.expressive()` (`@OptIn(ExperimentalMaterial3ExpressiveApi::class)` — NOT `ExperimentalMaterial3Api`).
- UI rules: consume theme tokens (`shapes`/`colorScheme`/`typography`) — no ad-hoc `RoundedCornerShape` for shapes a token covers (one-offs like chat bubble tails excepted); prefer spring motion over linear tweens for spatial transitions; prefer expressive component variants (contained loading indicator, `MaterialShapes`).
- **Settings entries**: every `ListItem` carries a `title` (feature name only) and a `subtitle` (purpose-only description — no implementation/style detail).

## Editing guides

- **New screen**: add route to `Screen.kt` → destination in `NavGraph.kt` → composable under `presentation/ui/<feature>/` → ViewModel in `presentation/viewmodel/` → (bottom-level routes) `BottomLevelRoutes.kt`.
- **New database entity**: entity (`data/local/entity/`) → DAO (`data/local/dao/`) → register in `MessengerDatabase` → domain model → repository interface → impl (`data/repository/`) → wire into `AppContainer`.
- **New API endpoint**: DTOs (`data/remote/dto/`) → `OpenAiClient` method → repository interface → `ApiRepositoryImpl` → handle `HttpException` via `extractHttpErrorMessage()`.
