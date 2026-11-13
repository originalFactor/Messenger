#!/usr/bin/env bash
# Reuse jpackage's Deb payload; makepkg supplies canonical pacman metadata.
set -euo pipefail

if [[ $# -ne 1 || ! -f $1 ]]; then
    echo "Usage: $0 path/to/Messenger.deb" >&2
    exit 1
fi

deb=$(realpath "$1")
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
arch_dir=$(mktemp -d)
trap 'rm -rf -- "$arch_dir"' EXIT

# The only enabled Linux target is x86_64. Never mislabel a foreign payload.
[[ $(dpkg-deb --field "$deb" Architecture) == amd64 ]]
package_version=$(dpkg-deb --field "$deb" Version)
dpkg-deb --extract "$deb" "$arch_dir/payload"
cp "$repo_dir/desktopApp/packaging/arch/PKGBUILD" "$arch_dir/PKGBUILD"
# Fast compression: the payload already contains compressed jars. makepkg
# defaults to zstd -19, which costs more time for little additional saving.
cat > "$arch_dir/makepkg.conf" <<'CONFIG'
source /etc/makepkg.conf
COMPRESSZST=(zstd -c -T0 -3 -)
PKGEXT='.pkg.tar.zst'
CONFIG

# Only packaging runs in Arch; no JDK, Rust toolchain or compilation in Docker.
# makepkg must run as an unprivileged user and writes into this temporary mount.
docker run --rm --user "$(id -u):$(id -g)" \
    --env HOME=/tmp --env "MESSENGER_PACKAGE_VERSION=$package_version" \
    --volume "$arch_dir:/build" --workdir /build \
    archlinux:base-devel \
    makepkg --config /build/makepkg.conf --nodeps --nocheck

packages=("$arch_dir"/*.pkg.tar.zst)
[[ ${#packages[@]} -eq 1 && -f ${packages[0]} ]]
cp -- "${packages[0]}" "$(dirname -- "$deb")/"
