# desktopApp — Desktop (JVM) application shell

Thin `org.jetbrains.kotlin.jvm` + `compose.multiplatform` + `kotlin.compose` shell over `shared`. `Main.kt` is the entry point (constructs `AppContainer` directly) and `compose.desktop { application { … } }` defines the native distribution formats (`Dmg`/`Msi`/`Deb`).

- Version name comes from the root `VERSION`; the native package version is `rootProject.ext["packageVersion"]` = `MAJOR.MINOR.<commitCount>` (jpackage/MSI requires a numeric, strictly increasing `ProductVersion`).
- ProGuard is DISABLED in release (`proguard { isEnabled.set(false) }`).
- Desktop platform actuals live in `shared/src/desktopMain` (PowerShell/`/bin/sh` shell executor, FileDialog image picker, database builder) — see `shared/AGENTS.md`.

```bash
./gradlew --no-daemon :desktopApp:run
./gradlew --no-daemon :desktopApp:packageReleaseMsi   # Windows (CI uses windows-latest + .\gradlew.bat)
./gradlew --no-daemon :desktopApp:packageReleaseDmg   # macOS
./gradlew --no-daemon :desktopApp:packageReleaseDeb   # Linux
```
