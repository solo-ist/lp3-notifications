#!/usr/bin/env bash
# Release gate for Menu.
#
# Menu is the only route to every app hidden from the LightOS toolbox, so an
# APK signed by the wrong key — or a debuggable one — would quietly take the
# whole shelf with it. Four checks, all fatal:
#   1. a pin already exists (enrolment is a separate, explicit act)
#   2. the signer matches it
#   3. the build is not debuggable
#   4. backups are off
#
# Parsing rules, learned from a security review that broke the previous
# version of this script:
#   - never use `cmd | grep -q` for a security decision. grep exits early,
#     the producer takes SIGPIPE, and under `pipefail` the pipeline reports
#     failure — so `a && fail || ok` reports OK for a *failing* check.
#   - never regex across a whole dump. Match the exact attribute, or an
#     unrelated string elsewhere can satisfy the test.
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
PIN_FILE="$(cd "$(dirname "$0")" && pwd)/release-cert-sha256.txt"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -1 "$SDK/build-tools" | sort -V | tail -1)"
APKSIGNER="$SDK/build-tools/$BT/apksigner"
AAPT2="$SDK/build-tools/$BT/aapt2"

die() { echo "✗ $*" >&2; exit 1; }

[ -f "$APK" ]       || die "no APK at $APK"
[ -x "$APKSIGNER" ] || die "apksigner not found at $APKSIGNER"
[ -x "$AAPT2" ]     || die "aapt2 not found at $AAPT2"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# --- 1 & 2. signer must match an already-enrolled pin ---------------------
[ -s "$PIN_FILE" ] || die "no certificate pin at $PIN_FILE.
    Enrolment is deliberately separate: record the expected fingerprint
    yourself before this gate will pass. A verifier that trusts whatever
    it is handed verifies nothing."

expected="$(tr -d '[:space:]' < "$PIN_FILE")"
[ ${#expected} -eq 64 ] || die "pin is not a 64-char sha-256: $PIN_FILE"

"$APKSIGNER" verify --print-certs "$APK" > "$work/certs.txt" \
    || die "apksigner could not verify $APK"

# exactly one signer, matching the pin.
# No mapfile/readarray here: macOS ships bash 3.2 and lacks both.
sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' "$work/certs.txt" > "$work/digests.txt"
count="$(grep -c . "$work/digests.txt" || true)"
[ "$count" -eq 1 ] || die "expected exactly 1 signer, found $count"
actual="$(head -n1 "$work/digests.txt" | tr -d '[:space:]')"
[ "$actual" = "$expected" ] || die "signer mismatch
    expected $expected
    got      $actual"
echo "✓ signer matches pin"

# --- 3. not debuggable ----------------------------------------------------
"$AAPT2" dump badging "$APK" > "$work/badging.txt" || die "aapt2 badging failed"
if grep -q '^application-debuggable' "$work/badging.txt"; then
    die "APK is debuggable"
fi
echo "✓ not debuggable"

# --- 4. allowBackup explicitly false on <application> ---------------------
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$work/manifest.txt" \
    || die "aapt2 xmltree failed"

# Take the attribute line inside the application element, not any string
# anywhere in the dump that happens to contain "allowBackup".
backup="$(awk '
    /^ *E: application/ { inapp=1; next }
    inapp && /^ *E: / && !/^ *E: (activity|receiver|service|provider|meta-data)/ { inapp=0 }
    inapp && /android:allowBackup/ {
        if (match($0, /=(true|false)/)) { print substr($0, RSTART+1, RLENGTH-1); exit }
        if (match($0, /\(type 0x12\)0x0/))  { print "false"; exit }
        if (match($0, /\(type 0x12\)0x[fF]/)) { print "true";  exit }
    }
' "$work/manifest.txt")"

[ -n "$backup" ]      || die "could not determine allowBackup from the manifest"
[ "$backup" = "false" ] || die "allowBackup is $backup, expected false"
echo "✓ allowBackup=false"

echo "$APK looks releasable."
