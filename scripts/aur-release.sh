#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Updates the AUR package (backupthing-bin) for a published GitHub release and commits it to the local AUR clone.
# packaging/aur/ in this repo is the source of truth; the AUR clone only receives copies of its files.
# Usage: scripts/aur-release.sh <version> [pkgrel]   e.g. scripts/aur-release.sh 0.3.0
#        AUR_DIR overrides the clone location (default ~/backupthing-bin).
# Run it in a terminal: the push at the end needs the AUR key's passphrase.
set -euo pipefail
version="${1:?usage: scripts/aur-release.sh <version> [pkgrel]}"
pkgrel="${2:-1}"
[[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ && "$pkgrel" =~ ^[1-9][0-9]*$ ]] || { echo "bad version or pkgrel" >&2; exit 2; }
repo="$(cd "$(dirname "$0")/.." && pwd)"
src="$repo/packaging/aur"
aur="${AUR_DIR:-$HOME/backupthing-bin}"

if [[ ! -d "$aur/.git" ]]; then
  git -c init.defaultBranch=master clone ssh://aur@aur.archlinux.org/backupthing-bin.git "$aur"
fi

# Bump, checksum, build and lint in a throwaway directory so no tarballs or packages land in either repo, and the
# PKGBUILD here only changes once the release tarball has downloaded and built.
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
cp "$src/PKGBUILD" "$build/"
sed -i -e "s/^pkgver=.*/pkgver=$version/" -e "s/^pkgrel=.*/pkgrel=$pkgrel/" "$build/PKGBUILD"
(
  cd "$build"
  updpkgsums
  makepkg -f --nodeps
  namcap PKGBUILD ./*.pkg.tar.zst || true
)
cp "$build/PKGBUILD" "$src/PKGBUILD"
(cd "$src" && makepkg --printsrcinfo > .SRCINFO)

cp "$src/PKGBUILD" "$src/.SRCINFO" "$src/LICENSE" "$aur/"
cd "$aur"
git add -f PKGBUILD .SRCINFO LICENSE
if git diff --cached --quiet; then
  echo "AUR clone already up to date."
else
  git commit -q -m "Update to $version-$pkgrel"
fi
git log --oneline -3

if git rev-parse -q --verify origin/master > /dev/null && [[ -z "$(git rev-list origin/master..HEAD)" ]]; then
  echo "Nothing to push."
  exit 0
fi
git --no-pager show --stat HEAD
read -rp "Push to the AUR now? [y/N] " answer
if [[ "$answer" == [yY] ]]; then
  git push -u origin master
else
  echo "Not pushed. Later: git -C $aur push -u origin master"
fi
