# androidApp — Android application shell

Thin `com.android.application` shell over `shared` (AGP 9 built-in Kotlin + `kotlin.compose` + `kotlin.serialization` — do NOT apply `kotlin-android`). Holds `applicationId`, `versionCode`/`versionName` (from the root `VERSION` + commit count), `signingConfigs` (keystore `keyring/messenger-release.jks`, env `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`), `buildTypes`, `buildFeatures { compose = true }`, and application-level `res/`.

- `MainActivity.kt` — the Compose entry point.
- `MessengerApplication.kt` — constructs and owns the `shared` `AppContainer`.
- `RuntimeShellClient.kt` — AIDL client registered as `ShellRuntimeRegistry.bridge` at startup; routes shell/workspace calls to the `:runtime` companion (absent companion ⇒ tools disabled, see `runtime/AGENTS.md`).
- `src/main/AndroidManifest.xml` — the FULL application manifest (the `shared` androidMain one is a minimal root): activities/services/providers, `enableOnBackInvokedCallback` (predictive back), `windowSoftInputMode="adjustResize"` on the chat activity, and the FQN service declaration `cc.ptoe.messenger.data.wear.MobileHttpServer` (class lives in `shared`). `aidl/` carries copies of `IShellService`/`IShellCallback` (same FQN as `:runtime`) plus `<queries>`/`<uses-permission>` for the companion.
- R8 / resource shrinking are DISABLED here (`isMinifyEnabled = false`) — R8 runs only on `:wear`.

```bash
./gradlew --no-daemon :androidApp:assembleDebug
./gradlew --no-daemon :androidApp:assembleRelease
./gradlew --no-daemon :androidApp:lintDebug
```
