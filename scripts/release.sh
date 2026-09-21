#!/usr/bin/env bash
# Build, verify and install as one fail-fast operation.
#
# The point: nothing reaches the phone unless verification passed, and what
# gets installed is exactly the artifact that was verified. Running build,
# verify and install as three separate shell commands — as the README used to
# suggest — lets an install proceed after a failed verify, because a script's
# exit status cannot stop its caller's next command.
set -euo pipefail

cd "$(cd "$(dirname "$0")/.." && pwd)"

APK="app/build/outputs/apk/release/app-release.apk"
: "${NOTIFICATIONS_SIGNING_PASSWORD:?set NOTIFICATIONS_SIGNING_PASSWORD, e.g. \$(op read \"op://Private/Notifications signing key/password\")}"

# Refuse to ship from a dirty tree: the artifact should correspond to a
# commit, so provenance is checkable after the fact.
if [ -n "$(git status --porcelain)" ]; then
    echo "✗ working tree is dirty — commit or stash first, so the artifact maps to a revision" >&2
    exit 1
fi
rev="$(git rev-parse --short HEAD)"

echo "→ building $rev"
rm -f "$APK"
# --no-daemon is deliberate. A Gradle daemon captures its environment at
# start and reuses it, so a daemon spawned by an earlier build without
# NOTIFICATIONS_SIGNING_PASSWORD keeps reporting "keystore password was incorrect"
# no matter what the current shell exports. Release builds are rare; the
# daemon buys nothing and costs a genuinely confusing failure mode.
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}" \
  ./gradlew :app:assembleRelease --console=plain -q --no-daemon

echo "→ verifying"
./scripts/verify-release.sh "$APK"

echo "→ installing $rev  ($(shasum -a 256 "$APK" | cut -c1-16)…)"
adb install -r "$APK"

echo "✓ installed $rev"
