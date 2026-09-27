#!/usr/bin/env python3
"""Check gradle/verification-metadata.xml against fresh, independent downloads.

`--write-verification-metadata` records whatever the first resolution
happened to fetch, so on its own it only proves the build is consistent with
itself. This script downloads every listed artifact again, directly from the
one repository settings.gradle.kts allows for its group, over HTTPS and
bypassing Gradle's cache, and compares SHA-256.

It cannot detect an artifact that was already malicious at its canonical
source. It does detect a poisoned local cache, a fallback to the wrong
repository, and tampering in transit on either path.

Run after regenerating the metadata:
    python3 scripts/crosscheck-verification.py
Exit 0 means every artifact matched.
"""
import hashlib
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"

# Must match the googleOnly list in settings.gradle.kts.
GOOGLE_ONLY = [
    r"com\.android(\..*)?",
    r"androidx(\..*)?",
    r"com\.google\.testing\.platform(\..*)?",
]

NS = {"v": "https://schema.gradle.org/dependency-verification"}


def repo_for(group):
    return GOOGLE if any(re.fullmatch(p, group) for p in GOOGLE_ONLY) else CENTRAL


def fetch_sha256(url):
    req = urllib.request.Request(url, headers={"User-Agent": "lp3-routine-crosscheck"})
    with urllib.request.urlopen(req, timeout=60) as r:
        h = hashlib.sha256()
        for chunk in iter(lambda: r.read(1 << 16), b""):
            h.update(chunk)
        return h.hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    tree = ET.parse(root / "gradle/verification-metadata.xml")
    jobs = []
    for comp in tree.getroot().iterfind("v:components/v:component", NS):
        group, name, version = comp.get("group"), comp.get("name"), comp.get("version")
        base = f"{repo_for(group)}/{group.replace('.', '/')}/{name}/{version}"
        for art in comp.iterfind("v:artifact", NS):
            expected = {e.get("value") for e in art.iterfind("v:sha256", NS)}
            expected |= {e.get("value") for e in art.iterfind("v:also-trust", NS)}
            jobs.append((f"{group}:{name}:{version}", f"{base}/{art.get('name')}", expected))

    def check(job):
        label, url, expected = job
        try:
            actual = fetch_sha256(url)
        except urllib.error.HTTPError as e:
            return ("MISSING", label, url, f"HTTP {e.code}")
        except Exception as e:  # network trouble: report, don't pass
            return ("ERROR", label, url, str(e))
        return ("OK" if actual in expected else "MISMATCH", label, url, actual)

    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(check, jobs))

    bad = [r for r in results if r[0] != "OK"]
    for status, label, url, detail in bad:
        print(f"{status:8} {label}\n         {url}\n         {detail}")
    print(f"{len(results) - len(bad)}/{len(results)} artifacts match an independent download")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
