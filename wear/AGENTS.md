# wear — Wear OS companion (chat only)

Phone-backed companion with no settings UI: a two-screen chat flow — chat list (mobile conversations with agent avatars + last-message previews) and chat screen (messages + input). Navigation is state-based (`WearScreen.ChatList`/`Chat`), no Navigation Compose. Agents are synced from the phone; provider/model/settings management stays on the phone. The module is Android-only (`com.android.application` + `kotlin-compose`, AGP 9 built-in Kotlin) and is NOT a KMP module. Wear chat requests are forwarded to the phone — the watch never calls providers directly, and the pipeline bypasses ChatViewModel (no tool execution).

## Transport: WebSocket over the tether network

- The phone runs a foreground service `MobileHttpServer` (Java-WebSocket, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`) listening on TCP `18765`, registered as an NSD mDNS service (`_messenger._tcp.`); the watch discovers it via `WearNetworkBridge` (OkHttp WebSocket + `NsdManager`) and `WearBridgeClient` polls every 3 s. Wear watches tether their network to the phone via Bluetooth PAN, so both devices are always on the same L2 network — no pairing, no GMS, no runtime permissions (INTERNET + NSD normal permissions only). The phone holds a `WifiManager.MulticastLock` so mDNS survives doze.
- **The port is authenticated** (`WearBridgeToken`): the server binds all interfaces (the PAN is not loopback), so every request needs the shared secret — a 32-byte random token persisted in the phone's SharedPreferences, published only in the mDNS TXT record (`token`), and required in the `X-Messenger-Bridge` handshake header (query `?token=` also accepted). `onOpen` closes with code 1008 on mismatch, before any handler runs. Never relax this: the `chat`/`sync`/`get_avatar` handlers read and write the whole chat database.
- `chat` emits exactly ONE terminal frame per request: `Finished` **and** `Cancelled` both send `chat_done` (with the partial text), `Error` sends only `chat_error`. The watch stops at the first terminal frame, so a second one is dropped and an error delivered after a `chat_done` would be silently lost.
- The phone handler does NOT insert the assistant placeholder: the Rust loop creates and fills that row and reports its id via `StreamingStarted`, which is the `assistantMessageId` echoed in `chat_done`.
- DataLayer/WearableListenerService is NOT usable (Samsung China-region Galaxy Watches lack GMS for Wear OS) and Bluetooth RFCOMM was abandoned (Samsung pairing quirks + fragile `BLUETOOTH_CONNECT` flow) — do not reintroduce either.
- Line-delimited JSON protocol over WebSocket text frames: `sync` / `chat` / `new_conversation`, all with `requestId` correlation. Business logic lives in `MobileWearChatHandler` (phone side, `MobileWearSyncManager.kt`) and drives chat turns through Rust Agent Core (`coreBridge.runTurn`), streaming `chat_delta`/`chat_done`/`chat_error` frames back.
- The phone-side bridge classes live in `shared/src/androidMain/kotlin/cc/ptoe/messenger/data/wear/` but are declared in `androidApp`'s manifest by fully-qualified name (`cc.ptoe.messenger.data.wear.MobileHttpServer`) — the class is in `shared`, not `androidApp`.
- Wear caches the latest synced snapshot in DataStore for fast resume. Chat actions (`chat`/`new_conversation`) go over the same WebSocket with inline replies.

## On-wrist behavior

- **Streaming throttle** (`WearChatRepository.kt`): 70 ms token batching window minimizes CPU wakeups/recomposition (~80% less streaming battery drain).
- **`WearTextFormatter.kt`**: zero-allocation fast path for plain text; compact thought indicator (`💭 ...`), tool badges (`🔧 [name]`), concise code-block summaries, formatted math. Covered by `WearTextFormatterTest.kt`.
- The phone side forwards sync requests with `content = "[图片]"` placeholders for image-only messages (no empty rows on the watch).

## Build

```bash
./gradlew-cli :wear:assembleDebug
./gradlew-cli :wear:assembleRelease   # R8 on; armeabi-v7a only
```

ABIs are per-build-type `ndk.abiFilters` (not `splits.abi`, which is global): release filters on `ext["wearReleaseAbis"]` = `armeabi-v7a` — Wear OS devices run a 64-bit kernel with a 32-bit userspace, so that is the only ABI CI builds and publishes — while debug filters on `ext["wearDebugAbis"]` = `x86_64`, so `:wear:assembleDebug` installs on a Wear OS AVD with no extra flags. Overrides: `-Pmessenger.wearAbis` / `WEAR_ABIS` (release) and `-Pmessenger.wearDebugAbis` / `WEAR_DEBUG_ABIS` (debug). The watch never links the Rust core, so its x86_64 build costs no extra Rust target. R8 + resource shrinking are on for release, same as `:androidApp` and `:runtime`.

## R8 troubleshooting (R8 runs on every Android release build)

R8 is an investigation point for release-only failures (reflection, serialization, generated code, manifest components) — not a routine check. Do NOT run release builds for ordinary tasks (slow). Keep the module's `proguard-rules.pro` (`:wear`, `:androidApp`, `:runtime`) updated whenever an R8-sensitive class/method/annotation/serialized model/generated callback/manifest component changes; remove stale rules and keep rules narrow. The procedure below applies to any of the three Android modules — substitute the module name in the paths.

When investigating:

1. Reproduce from a clean run: `./gradlew-cli :wear:assembleRelease --rerun-tasks`.
2. Inspect `wear/build/outputs/mapping/release/`: `mapping.txt` (survivors + obfuscated names), `usage.txt` (removed classes/members — a member under a surviving class is optimized, not removed), `seeds.txt` (keep-rule matches, not proof of APK presence), `configuration.txt` (effective rules incl. consumer rules).
3. Compare with the final APK (APK Analyzer / `apkanalyzer`). Watch Manifest components, the UniFFI-generated `cc.ptoe.messenger.core` classes + JNA, kotlinx.serialization DTOs/serializers, ucrop's activity (`:androidApp`), the vendored Termux classes and AIDL stubs (`:runtime`), and `WearNetworkBridge` WebSocket/NSD callbacks (`:wear`).
4. Identify the reflective/serialized entry point before adding a rule. Preserve only the smallest scope; keep annotations/signature metadata when a library reads them (`RuntimeVisibleAnnotations`, `Signature`, …).
5. Rebuild and confirm the rule in `configuration.txt`, the class in `usage.txt`/`mapping.txt`, and its presence in the APK. Never `-keep class ** { *; }`.
6. If unclear, temporarily add `-whyareyoukeeping class <FQN>`, read the reason chain, remove the diagnostic rule afterwards.
7. Smoke-test the minified APK; use `retrace` with the matching `mapping.txt` for crash stacks. A green R8 build does not prove runtime reflection works.
