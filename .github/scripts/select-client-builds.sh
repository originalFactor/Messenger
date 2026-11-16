#!/usr/bin/env bash
# Select the conservative CI fan-out from the complete PR tree comparison.
set -euo pipefail

android=false
wear=false
desktop=false
all_clients() { android=true; wear=true; desktop=true; }
emit() {
    local output=${GITHUB_OUTPUT:-/dev/stdout}
    printf 'android=%s\nwear=%s\ndesktop=%s\n' "$android" "$wear" "$desktop" >> "$output"
}

event=${CI_DIFF_EVENT:-}
base=${CI_DIFF_BASE:-}
head=${CI_DIFF_HEAD:-}

# Main-branch runs cancel their predecessors. A push cannot know whether the
# preceding run finished, so it must cover every client from cancelled runs.
if [[ $event == push ]]; then
    all_clients
    emit
    exit 0
fi

# Missing, untrusted or non-ancestral bases (including force pushes and branch
# creation) cannot prove which clients are unaffected. Build everything.
if [[ $event != pull_request ]] ||
   [[ ! $base =~ ^[0-9a-f]{40}$ || ! $head =~ ^[0-9a-f]{40}$ ]] ||
   ! git cat-file -e "${base}^{commit}" 2>/dev/null ||
   ! git cat-file -e "${head}^{commit}" 2>/dev/null ||
   ! git merge-base --is-ancestor "$base" "$head"; then
    all_clients
    emit
    exit 0
fi

paths=$(mktemp)
trap 'rm -f "$paths"' EXIT
# Turning off rename detection reports both the deleted and added path, so a
# cross-module move selects both clients. NUL separators preserve all filenames.
if ! git diff --no-renames --name-only -z "$base" "$head" -- > "$paths"; then
    all_clients
    emit
    exit 0
fi

seen=false
while IFS= read -r -d '' path; do
    seen=true
    case "$path" in
        androidApp/*|runtime/*) android=true ;;
        # The phone owns the Wear bridge, so also compile it for protocol edits.
        wear/*) android=true; wear=true ;;
        desktopApp/*) desktop=true ;;
        # Shared, renderer, Core and build/workflow inputs can cross clients.
        # Unknown paths also fail safe rather than shrinking coverage silently.
        *) all_clients ;;
    esac
done < "$paths"

if [[ $seen == false ]]; then all_clients; fi
emit
