@rem
@rem Copyright 2026 originalFactor
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem     https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem Runs the Gradle wrapper with its OWN daemon registry.
@rem
@rem Why: a warm daemon is worth ~4 s per invocation (JVM start + configuration),
@rem but sharing the default registry with Android Studio is what made CLI builds
@rem deadlock against the IDE's busy daemon - the reason AGENTS.md used to require
@rem `--no-daemon` everywhere. A separate registry gives the CLI its own daemon and
@rem leaves the IDE's untouched; the Gradle user home caches stay shared (Gradle
@rem locks them across daemons).
@rem
@rem Usage: gradlew-cli.bat :androidApp:assembleDebug
@rem Override the registry location with GRADLE_CLI_REGISTRY if needed.

@echo off
setlocal EnableExtensions

if "%GRADLE_CLI_REGISTRY%"=="" set "GRADLE_CLI_REGISTRY=%USERPROFILE%\.gradle\cli-daemon"
set "GRADLE_OPTS=-Dorg.gradle.daemon.registry.base=%GRADLE_CLI_REGISTRY% %GRADLE_OPTS%"

call "%~dp0gradlew.bat" --daemon %*
exit /b %ERRORLEVEL%
