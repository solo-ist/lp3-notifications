#!/usr/bin/env bash
# Build, verify and install as one fail-fast operation.
#
# The point: nothing reaches the phone unless verification passed, and what
# gets installed is exactly the artifact that was verified. Running build,
# verify and install as three separate shell commands — as the README used to
# suggest — lets an install proceed after a failed verify, because a script's
# exit status cannot stop its caller's next command.
#
# "Exactly" is enforced, not assumed (security audit 2026-09-27):
#   - the build runs on a `git archive` export of HEAD in a private temp
#     directory, so edits made to the working tree mid-build can't reach it
#   - the APK is copied read-only into that directory before verification,
#     and the verified copy is the one installed — not a path in the build
#     tree that something could replace in between
set -euo pipefail

cd "$(cd "$(dirname "$0")/.." && pwd)"

: "${NOTIFICATIONS_SIGNING_PASSWORD:?set NOTIFICATIONS_SIGNING_PASSWORD, e.g. \$(op read \"op://Private/Notifications signing key/password\")}"

# Refuse to ship from a dirty tree: the artifact should correspond to a
# commit, so provenance is checkable after the fact.
if [ -n "$(git status --porcelain)" ]; then
    echo "✗ working tree is dirty — commit or stash first, so the artifact maps to a revision" >&2
    exit 1
fi
rev="$(git rev-parse --short HEAD)"

work="$(mktemp -d)"   # mode 0700: nobody else can write into it
trap 'rm -rf "$work"' EXIT

echo "→ exporting $rev"
mkdir "$work/src"
git archive --format=tar HEAD | tar -x -C "$work/src"
# The SDK location is machine-specific and never committed.
[ -f local.properties ] && cp local.properties "$work/src/"

echo "→ building $rev"
# --no-daemon is deliberate. A Gradle daemon captures its environment at
# start and reuses it, so a daemon spawned by an earlier build without
# NOTIFICATIONS_SIGNING_PASSWORD keeps reporting "keystore password was incorrect"
# no matter what the current shell exports. Release builds are rare; the
# daemon buys nothing and costs a genuinely confusing failure mode.
( cd "$work/src" && JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}" \
    ./gradlew :app:assembleRelease --console=plain -q --no-daemon )

apk="$work/notifications-$rev.apk"
install -m 0400 "$work/src/app/build/outputs/apk/release/app-release.apk" "$apk"

echo "→ verifying"
# The gate from the same exported revision, not the working tree's.
"$work/src/scripts/verify-release.sh" "$apk"

sha="$(shasum -a 256 "$apk" | cut -d' ' -f1)"
echo "→ installing $rev  (sha256 $sha)"
adb install -r "$apk"

# Keep the exact installed artifact for later inspection (build/ is ignored).
mkdir -p build/release
cp "$apk" "build/release/"
echo "✓ installed $rev — copy at build/release/notifications-$rev.apk"
