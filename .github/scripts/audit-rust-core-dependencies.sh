#!/usr/bin/env bash
# Inventory the remaining Rust Core dependencies before the one-commit retirement.
# --strict fails while production/build blockers remain; the default prints a report.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo"

strict=false
summary_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --strict) strict=true; shift ;;
    --summary) summary_file=${2:-}; [[ -n "$summary_file" ]] || { echo "usage: $0 [--strict] [--summary FILE]" >&2; exit 2; }; shift 2 ;;
    *) echo "usage: $0 [--strict] [--summary FILE]" >&2; exit 2 ;;
  esac
done

patterns=(
  'CoreHandle|HttpServerHandle|startHttpServer'
  'RestCoreBridge|CoreBridgeRegistry'
  'core-bindings|core-bindings-desktop|core/rust|messenger-ffi|messenger-http'
  'cargo-ndk|cargoBuildHost|uniffi|UniFFI|libmessenger_ffi'
)
exclude=(
  --exclude-dir=build
  --exclude-dir=.gradle
  --exclude-dir=.idea
  --exclude-dir=server
  --exclude-dir=rendering
  --exclude-dir=rust
  --exclude=HANDOFF_RUST_RETIREMENT.md
  --exclude=RUST_CORE_DEPENDENCY_AUDIT.md
)

commit=$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')
printf 'Rust Core dependency inventory for %s\n' "$commit"
scan() {
  local label=$1; shift
  printf '\n[%s]\n' "$label"
  local existing=()
  for path in "$@"; do
    if [[ -e "$path" ]]; then existing+=("$path"); fi
  done
  if [[ ${#existing[@]} -eq 0 ]]; then return 0; fi
  grep -rEn "${exclude[@]}" -e "$(IFS='|'; echo "${patterns[*]}")" "${existing[@]}" || true
}

blockers() {
  local paths=()
  for path in androidApp/src/main desktopApp/src/main settings.gradle.kts build.gradle.kts \
    androidApp/build.gradle.kts desktopApp/build.gradle.kts shared/build.gradle.kts \
    core/bindings/build.gradle.kts core/bindings-desktop/build.gradle.kts .github/workflows; do
    if [[ -e "$path" ]]; then paths+=("$path"); fi
  done
  if [[ ${#paths[@]} -eq 0 ]]; then return 0; fi
  grep -rEn "${exclude[@]}" \
    -e 'CoreHandle\.open|startHttpServer\(|RestCoreBridge\(|implementation\(project\(":core-bindings' \
    -e 'include\(":core-bindings|Setup Rust toolchain|Install cargo-ndk|cargoBuildHost|libmessenger_ffi\.so' \
    "${paths[@]}" || true
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
artifact_paths=()
for path in androidApp desktopApp core/bindings core/bindings-desktop; do
  if [[ -e "$path" ]]; then artifact_paths+=("$path"); fi
done
if [[ ${#artifact_paths[@]} -gt 0 ]]; then
  find "${artifact_paths[@]}" -path '*/build/*' -type f \
    \( -name 'libmessenger_ffi.so' -o -name 'libsqliteJni.so' -o -name '*uniffi*' \) -print 2>/dev/null || true
fi

blocking_count=$(blockers | grep -c . || true)
blocking_count=${blocking_count:-0}
printf '\n[summary]\n'
printf 'blocking_references=%s\n' "$blocking_count"
if [[ -f core/rust/Cargo.toml ]]; then printf 'rust_workspace=present\n'; else printf 'rust_workspace=absent\n'; fi
if [[ -d core/bindings || -d core/bindings-desktop ]]; then printf 'native_bindings=present\n'; else printf 'native_bindings=absent\n'; fi
if [[ -n "$summary_file" ]]; then printf '%s\n' "$blocking_count" > "$summary_file"; fi
if $strict && [[ "$blocking_count" != 0 ]]; then
  printf 'Rust Core retirement is blocked by %s production/build references.\n' "$blocking_count" >&2
  exit 1
fi
