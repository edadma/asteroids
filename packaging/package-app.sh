#!/usr/bin/env bash
# Wrap the linked native binary in a macOS .app bundle and zip it for release.
# Run `sbt nativeLink` first to produce the optimized binary this bundles.
#
#   packaging/package-app.sh [version]
#
# Produces dist/Asteroids.app and dist/Asteroids-<version>-arm64.zip, and prints the zip's
# sha256 (for the Homebrew cask).
set -euo pipefail

version="${1:-0.0.2}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bin="$root/target/scala-3.8.4/asteroids"
app="$root/dist/Asteroids.app"
zip="$root/dist/Asteroids-$version-arm64.zip"

if [[ ! -x "$bin" ]]; then
  echo "binary not found: $bin (run 'sbt nativeLink' first)" >&2
  exit 1
fi

rm -rf "$root/dist"
mkdir -p "$app/Contents/MacOS"
cp "$bin" "$app/Contents/MacOS/asteroids"
sed "s/__VERSION__/$version/g" "$root/packaging/Info.plist.in" > "$app/Contents/Info.plist"

# ditto preserves the bundle structure and resource forks correctly (better than zip).
/usr/bin/ditto -c -k --keepParent "$app" "$zip"
shasum -a 256 "$zip"
