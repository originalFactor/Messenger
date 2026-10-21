# androidApp — Android application shell

Thin `com.android.application` shell over `shared` (AGP 9 built-in Kotlin + `kotlin.compose` + `kotlin.serialization` — do NOT apply `kotlin-android`). Holds `applicationId`, `versionCode`/`versionName` (from the root `VERSION` + commit count), `signingConfigs` (keystore `keyring/messenger-release.jks`, env `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`), `buildTypes`, `buildFeatures { compose = true }`, and application-level `res/`.

- `MainActivity.kt` — the Compose entry point.
- `MessengerApplication.kt` — constructs and owns the `shared` `AppContainer`.
- `RuntimeShellClient.kt` — AIDL client registered as `ShellRuntimeRegistry.bridge` at startup; routes shell/workspace calls to the `:runtime` companion (absent companion ⇒ tools disabled, see `runtime/AGENTS.md`).
- `src/main/AndroidManifest.xml` — the FULL application manifest (the `shared` androidMain one is a minimal root): activities/services/providers, `enableOnBackInvokedCallback` (predictive back), `windowSoftInputMode="adjustResize"` on the chat activity, and the FQN service declaration `cc.ptoe.messenger.data.wear.MobileHttpServer` (class lives in `shared`). `aidl/` carries copies of `IShellService`/`IShellCallback` (same FQN as `:runtime`) plus `<queries>`/`<uses-permission>` for the companion.
- R8 + resource shrinking are ENABLED for release (`isMinifyEnabled = true`, `isShrinkResources = true`) with rules in `androidApp/proguard-rules.pro`. The kept surface is: manifest components, the FQN-declared `MobileHttpServer`/`MobileWearChatHandler` from `shared`, ucrop's activity, kotlinx.serialization's serializer lookup, Coil/Ktor service-loader targets, OkHttp's optional TLS providers and reflective ViewModel constructors. `:core-bindings` and `:renderer-android` contribute the UniFFI/JNA and RaTeX keeps as consumer rules.
- ABIs come from the root build script and are applied as per-build-type `ndk.abiFilters` (NOT `splits.abi`, which is global and would force arm64-only debug builds): release `ext["releaseAbis"]` = `arm64-v8a`; debug `ext["debugAbis"]` = `arm64-v8a` + `x86_64` so Android Studio Run installs on an AVD. Override with `-Pmessenger.abis` / `ANDROID_ABIS` (release) and `-Pmessenger.debugAbis` / `ANDROID_DEBUG_ABIS` (debug); the lists also drive the cargo-ndk targets and the `:runtime` bootstrap archives.

```bash
./gradlew --no-daemon :androidApp:assembleDebug
./gradlew --no-daemon :androidApp:assembleRelease   # R8 on; one APK per ABI
./gradlew --no-daemon :androidApp:lintDebug
```
