#!/usr/bin/env bash
# Verify the Rust Core retirement audit distinguishes an unswitched tree from a clean one.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$script_dir/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

output=$(cd "$repo" && bash "$script_dir/audit-rust-core-dependencies.sh")
printf '%s\n' "$output" | rg -q '^\[application sources\]$'
printf '%s\n' "$output" | rg -q '^\[summary\]$'
printf '%s\n' "$output" | rg -q '^rust_workspace=present$'

if (cd "$repo" && bash "$script_dir/audit-rust-core-dependencies.sh" --strict) >"$tmp/strict.log" 2>&1; then
  printf 'Expected --strict to fail while production startup still uses Rust Core.\n' >&2
  exit 1
fi
rg -q 'retirement is blocked' "$tmp/strict.log"

clean=$tmp/clean
mkdir -p "$clean"/{.github/scripts,.github/workflows,core/rust/crates/messenger-http/docs,androidApp/src/main,androidApp/src/main/aidl,androidApp/docs,desktopApp/src/main,shared/src,shared/docs,wear/src,runtime/src,runtime/src/main/aidl,core/bindings,core/bindings-desktop}
printf '' > "$clean/VERSION"
printf 'clean\n' > "$clean/settings.gradle.kts"
printf 'clean\n' > "$clean/build.gradle.kts"
for path in androidApp/build.gradle.kts desktopApp/build.gradle.kts shared/build.gradle.kts \
  runtime/build.gradle.kts wear/build.gradle.kts core/bindings/build.gradle.kts core/bindings-desktop/build.gradle.kts \
  release.ps1 gradlew-cli gradlew-cli.bat AGENTS.md shared/AGENTS.md androidApp/AGENTS.md desktopApp/AGENTS.md \
  wear/AGENTS.md runtime/AGENTS.md core/rust/AGENTS.md androidApp/proguard-rules.pro desktopApp/proguard-rules.pro \
  runtime/proguard-rules.pro wear/proguard-rules.pro core/bindings/proguard-rules.pro; do
  mkdir -p "$clean/$(dirname "$path")"; printf 'clean\n' > "$clean/$path"
done
printf 'clean\n' > "$clean/.github/workflows/ci.yml"
for path in androidApp/src/main/Main.kt desktopApp/src/main/Main.kt shared/src/Placeholder.kt \
  wear/src/Placeholder.kt runtime/src/Placeholder.kt androidApp/src/main/aidl/Placeholder.aidl \
  runtime/src/main/aidl/Placeholder.aidl shared/docs/placeholder.md androidApp/docs/placeholder.md; do
  printf 'clean\n' > "$clean/$path"
done
cp "$script_dir/audit-rust-core-dependencies.sh" "$clean/.github/scripts/"
git -C "$clean" -c init.defaultBranch=main init -q
git -C "$clean" config user.email audit@example.invalid
git -C "$clean" config user.name 'Audit Fixture'
git -C "$clean" add . >/dev/null
git -C "$clean" commit -qm fixture
summary=$tmp/summary
rm -rf "$clean/core" "$clean/core/rust" "$clean/core/bindings" "$clean/core/bindings-desktop"
(cd "$clean" && bash .github/scripts/audit-rust-core-dependencies.sh --strict --summary "$summary") > "$tmp/clean.log"
test "$(cat "$summary")" = 0
printf '%s\n' "$(cat "$tmp/clean.log")" | rg -q '^rust_workspace=absent$'

echo 'Rust Core dependency audit checks passed'
