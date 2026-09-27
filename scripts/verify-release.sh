#!/usr/bin/env bash
# Release gate for Notifications.
#
# Notifications reads every notification on the phone, so an APK signed by
# the wrong key — or a debuggable one — would inherit that access. Five
# checks, all fatal:
#   1. a pin already exists (enrolment is a separate, explicit act)
#   2. the signer matches it
#   3. the build is not debuggable
#   4. backups are off
#   5. device transfer excludes every data domain
#
# Parsing rules, learned from a security review that broke the previous
# version of this script:
#   - never use `cmd | grep -q` for a security decision. grep exits early,
#     the producer takes SIGPIPE, and under `pipefail` the pipeline reports
#     failure — so `a && fail || ok` reports OK for a *failing* check.
#   - never regex across a whole dump. Match the exact attribute, or an
#     unrelated string elsewhere can satisfy the test.
#   - not even line by line: a label can contain the attribute's text.
#     Parse the tree and read the attribute itself (manifest_gate.py).
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

# --- 4. backup off, and device transfer excluded --------------------------
# Parsed structurally by manifest_gate.py: only a direct attribute of
# <application>, by exact name and value. The awk this replaces matched any
# line mentioning allowBackup, so a label or a child element containing the
# text "android:allowBackup=false" passed with the real attribute missing
# (security audit 2026-09-27, R-02). Its own regression tests run first.
GATE="$(cd "$(dirname "$0")" && pwd)/manifest_gate.py"
python3 "$(dirname "$GATE")/test_manifest_gate.py" >"$work/gate-tests.txt" 2>&1 \
    || { cat "$work/gate-tests.txt" >&2; die "manifest gate's own tests failed"; }

"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$work/manifest.txt" \
    || die "aapt2 xmltree failed"
rules_id="$(python3 "$GATE" backup "$work/manifest.txt")" || die "backup check failed"
echo "✓ allowBackup=false"

# allowBackup=false doesn't cover Android 12+ device-to-device transfer; the
# dataExtractionRules resource does. Resolve it (release builds shorten its
# path) and require every domain excluded from both sections.
"$AAPT2" dump resources "$APK" > "$work/resources.txt" || die "aapt2 dump resources failed"
rules_files="$(python3 "$GATE" resource "$work/resources.txt" "$rules_id")" \
    || die "data extraction rules resource not found"
for f in $rules_files; do
    "$AAPT2" dump xmltree --file "$f" "$APK" > "$work/rules.txt" || die "aapt2 xmltree $f failed"
    python3 "$GATE" rules "$work/rules.txt" || die "data extraction rules in $f don't exclude everything"
done
echo "✓ cloud backup and device transfer exclude everything"

echo "$APK looks releasable."
