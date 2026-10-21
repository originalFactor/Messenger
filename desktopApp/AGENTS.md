# desktopApp — Desktop (JVM) application shell

Thin `org.jetbrains.kotlin.jvm` + `compose.multiplatform` + `kotlin.compose` shell over `shared`. `Main.kt` is the entry point (constructs `AppContainer` directly) and `compose.desktop { application { … } }` defines the native distribution formats (`Dmg`/`Msi`/`Deb`).

- Version name comes from the root `VERSION`; the native package version is `rootProject.ext["packageVersion"]` = `MAJOR.MINOR.<commitCount>` (jpackage/MSI requires a numeric, strictly increasing `ProductVersion`).
- ProGuard is ENABLED in release (`proguard { isEnabled = true; obfuscate = false; optimize = true }`), with rules in `desktopApp/proguard-rules.pro` plus JetBrains' `default-compose-desktop-rules.pro`. Obfuscation stays off: Compose Desktop/Skiko/JNA resolve classes by name. Kept surface: the UniFFI/JNA bridge, kotlinx.serialization lookup, Coil/Ktor service-loader targets, OkHttp's optional TLS providers. The `main` entry point is already kept by the plugin's generated root config.
- Platform actuals live in `shared/src/desktopMain` (PowerShell/`/bin/sh` shell executor, FileDialog image picker, database builder) — see `shared/AGENTS.md`.
- CI builds four artifacts: Windows x86_64 (MSI), Windows ARM64 (zipped app image — jpackage cannot emit an arm64 MSI, openjdk JDK-8361207), Linux x86_64 and Linux ARM64 (`.deb`).

```bash
./gradlew --no-daemon :desktopApp:run
./gradlew --no-daemon :desktopApp:packageReleaseMsi   # Windows (CI uses windows-latest + .\gradlew.bat)
./gradlew --no-daemon :desktopApp:packageReleaseDmg   # macOS
./gradlew --no-daemon :desktopApp:packageReleaseDeb   # Linux
```
