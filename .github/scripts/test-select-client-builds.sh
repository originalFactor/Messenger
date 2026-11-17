#!/usr/bin/env bash
# Exercise the real selector against commits, including moves and unsafe bases.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
git -C "$repo" -c init.defaultBranch=main init -q
git -C "$repo" config user.email ci-selector@example.invalid
git -C "$repo" config user.name 'CI selector test'
git -C "$repo" config commit.gpgsign false
git -C "$repo" config core.hooksPath /dev/null
mkdir -p "$repo"/{wear,runtime,desktopApp,androidApp,shared,core,rendering}
for path in wear/source.kt runtime/source.kt desktopApp/source.kt androidApp/source.kt shared/source.kt core/source.rs rendering/source.kt; do
    printf 'initial\n' > "$repo/$path"
done
git -C "$repo" add .
git -C "$repo" commit -qm initial

check() {
    local base=$1 head=$2 event=$3 expected=$4 result
    result=$(cd "$repo" && CI_DIFF_BASE="$base" CI_DIFF_HEAD="$head" CI_DIFF_EVENT="$event" \
        GITHUB_OUTPUT=/dev/stdout bash "$script_dir/select-client-builds.sh")
    if [[ $result != "$expected" ]]; then
        printf 'Unexpected selection for %s: expected <%s>, got <%s>\n' "$event" "$expected" "$result" >&2
        exit 1
    fi
}

all=$'android=true\nwear=true\ndesktop=true'
android=$'android=true\nwear=false\ndesktop=false'
android_wear=$'android=true\nwear=true\ndesktop=false'
desktop=$'android=false\nwear=false\ndesktop=true'
android_desktop=$'android=true\nwear=false\ndesktop=true'
initial=$(git -C "$repo" rev-parse HEAD)
previous=$initial

change() {
    local file=$1 expected=$2 event=${3:-pull_request} next
    printf 'changed\n' >> "$repo/$file"
    git -C "$repo" add "$file"
    git -C "$repo" commit -qm "change $file"
    next=$(git -C "$repo" rev-parse HEAD)
    check "$previous" "$next" "$event" "$expected"
    check "$previous" "$next" push "$all"
    previous=$next
}

change wear/source.kt "$android_wear"
change runtime/source.kt "$android"
check "$initial" "$previous" pull_request "$android_wear"
change androidApp/source.kt "$android" pull_request
change desktopApp/source.kt "$desktop"
check "$initial" "$previous" pull_request "$all"
change shared/source.kt "$all"
change core/source.rs "$all"
change rendering/source.kt "$all"

git -C "$repo" mv runtime/source.kt desktopApp/moved.kt
git -C "$repo" commit -qm 'move across clients'
next=$(git -C "$repo" rev-parse HEAD)
check "$previous" "$next" pull_request "$android_desktop"
previous=$next

git -C "$repo" rm -q wear/source.kt
git -C "$repo" commit -qm 'delete wear source'
next=$(git -C "$repo" rev-parse HEAD)
check "$previous" "$next" pull_request "$android_wear"
check "$next" "$next" pull_request "$all"
check 0000000000000000000000000000000000000000 "$next" pull_request "$all"
check 1111111111111111111111111111111111111111 "$next" pull_request "$all"
check "$next" "$initial" pull_request "$all"
check "$previous" "$next" push "$all"
check "$initial" "$next" unknown "$all"
previous=$next

printf 'root document\n' > "$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -qm 'unclassified path'
next=$(git -C "$repo" rev-parse HEAD)
check "$previous" "$next" pull_request "$all"

# A PR's tested HEAD is commonly a synthetic merge with the base as a parent.
git -C "$repo" switch -qc base "$initial"
printf 'base change\n' >> "$repo/desktopApp/source.kt"
git -C "$repo" commit -qam 'base desktop edit'
pr_base=$(git -C "$repo" rev-parse HEAD)
git -C "$repo" switch -qc feature "$initial"
printf 'feature change\n' >> "$repo/runtime/source.kt"
git -C "$repo" commit -qam 'feature runtime edit'
git -C "$repo" merge -q --no-ff "$pr_base" -m 'test merge'
merge=$(git -C "$repo" rev-parse HEAD)
check "$pr_base" "$merge" pull_request "$android"

printf 'CI client selection scenarios passed\n'
