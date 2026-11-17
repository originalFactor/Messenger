#!/usr/bin/env bash
# Inventory the remaining Rust Core dependencies before the one-commit retirement.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo"

patterns=(
  'CoreHandle|HttpServerHandle|startHttpServer'
  'RestCoreBridge|CoreBridgeRegistry'
  'core-bindings|core-bindings-desktop|core/rust|messenger-ffi|messenger-http'
  'cargo-ndk|cargoBuildHost|uniffi|UniFFI|libmessenger_ffi'
)
exclude=(
  --glob '!**/build/**'
  --glob '!**/.gradle/**'
  --glob '!**/.idea/**'
  --glob '!server/**'
  --glob '!rendering/**'
  --glob '!core/rust/**'
  --glob '!HANDOFF_RUST_RETIREMENT.md'
  --glob '!shared/docs/RUST_CORE_DEPENDENCY_AUDIT.md'
)

printf 'Rust Core dependency inventory for %s\n' "$(git rev-parse --short HEAD)"
scan() {
  local label=$1; shift
  printf '\n[%s]\n' "$label"
  rg -n --no-heading "${exclude[@]}" -e "$(IFS='|'; echo "${patterns[*]}")" "$@" || true
}

scan 'application sources' \
  androidApp/src desktopApp/src shared/src wear/src runtime/src
scan 'Gradle and native bindings' \
  settings.gradle.kts build.gradle.kts \
  androidApp/build.gradle.kts desktopApp/build.gradle.kts shared/build.gradle.kts \
  runtime/build.gradle.kts wear/build.gradle.kts \
  core/bindings/build.gradle.kts core/bindings-desktop/build.gradle.kts
scan 'CI and release' \
  .github/workflows .github/scripts release.ps1 gradlew-cli gradlew-cli.bat
scan 'R8 and AIDL' \
  androidApp/proguard-rules.pro desktopApp/proguard-rules.pro runtime/proguard-rules.pro \
  wear/proguard-rules.pro core/bindings/proguard-rules.pro \
  androidApp/src/main/aidl runtime/src/main/aidl
scan 'documentation' \
  AGENTS.md shared/AGENTS.md androidApp/AGENTS.md desktopApp/AGENTS.md \
  wear/AGENTS.md runtime/AGENTS.md core/rust/AGENTS.md shared/docs androidApp/docs

printf '\n[native artifacts]\n'
find androidApp desktopApp core/bindings core/bindings-desktop -path '*/build/*' -type f \
  \( -name 'libmessenger_ffi.so' -o -name 'libsqliteJni.so' -o -name '*uniffi*' \) -print 2>/dev/null || true
