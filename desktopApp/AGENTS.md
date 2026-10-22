# desktopApp — Desktop (JVM) application shell

Thin `org.jetbrains.kotlin.jvm` + `compose.multiplatform` + `kotlin.compose` shell over `shared`. `Main.kt` is the entry point (constructs `AppContainer` directly) and `compose.desktop { application { … } }` defines the native distribution formats (`Dmg`/`Msi`/`Deb`).

- Version name comes from the root `VERSION`; the native package version is `rootProject.ext["packageVersion"]` = `MAJOR.MINOR.<commitCount>` (jpackage/MSI requires a numeric, strictly increasing `ProductVersion`).
- ProGuard is ENABLED in release (`proguard { isEnabled = true; obfuscate = false; optimize = true }`), with rules in `desktopApp/proguard-rules.pro` plus JetBrains' `default-compose-desktop-rules.pro`. Obfuscation stays off: Compose Desktop/Skiko/JNA resolve classes by name. Kept surface: the UniFFI/JNA bridge, kotlinx.serialization lookup, Coil/Ktor service-loader targets, OkHttp's optional TLS providers. The `main` entry point is already kept by the plugin's generated root config.
- Platform actuals live in `shared/src/desktopMain` (PowerShell/`/bin/sh` shell executor, FileDialog image picker, database builder) — see `shared/AGENTS.md`.
- **Undecorated window + taskbar**: `Main.kt` creates the window with `undecorated = true`, so AWT has no native frame to fit into the desktop work area and Windows maximizes it over the taskbar. `DesktopWindow.kt` (`WorkAreaMaximizedBounds()`) sets `Frame.maximizedBounds` to the current monitor's bounds minus `Toolkit.getScreenInsets()` and re-applies it when the window changes monitor. `maximizedBounds` is only a hint applied at maximize time — setting it while already maximized does nothing — so the effect must be installed before any maximize (title-bar button, Win+Up, double-click).
- CI builds four artifacts: Windows x86_64 (MSI), Windows ARM64 (zipped app image — jpackage cannot emit an arm64 MSI, openjdk JDK-8361207), Linux x86_64 and Linux ARM64 (`.deb`).
- Desktop CI runs with `ANDROID_HOME` pointed at an EMPTY directory (no Android package is needed to build the JVM app) and therefore passes `-Pmessenger.buildRuntimeNative=false`: `:runtime`'s `externalNativeBuild` block would otherwise make AGP resolve an NDK at configuration time and fail the build before any task runs. Anything that configures `:runtime` without an NDK installed needs that flag.

```bash
./gradlew-cli :desktopApp:run
./gradlew-cli :desktopApp:packageReleaseMsi   # Windows (CI uses windows-latest + .\gradlew.bat)
./gradlew-cli :desktopApp:packageReleaseDmg   # macOS
./gradlew-cli :desktopApp:packageReleaseDeb   # Linux
```
