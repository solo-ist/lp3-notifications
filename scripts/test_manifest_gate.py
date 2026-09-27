#!/usr/bin/env python3
"""Regression tests for the release gate's manifest checks.

The two impersonation cases are from the 2026-09-27 security audit (R-02):
the old line-matching gate accepted both. The rest pin down that anything
missing, repeated, or unrecognised is rejected, not guessed at.

Run: python3 scripts/test_manifest_gate.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from manifest_gate import GateError, check_backup, check_rules, resource_files  # noqa: E402

NS = "http://schemas.android.com/apk/res/android"


def manifest(app_attrs, children=""):
    """xmltree output shaped like real aapt2 output for a release APK."""
    return (
        f"N: android={NS} (line=2)\n"
        "  E: manifest (line=2)\n"
        f"    A: {NS}:versionCode(0x0101021b)=7\n"
        '    A: package="ist.solo.routine" (Raw: "ist.solo.routine")\n'
        "      E: application (line=31)\n"
        + "".join(f"        A: {a}\n" for a in app_attrs)
        + children
    )


GOOD = [
    f"{NS}:label(0x01010001)=@0x7f040000",
    f"{NS}:allowBackup(0x01010280)=false",
    f"{NS}:dataExtractionRules(0x0101063e)=@0x7f060000",
]
LABEL = f"{NS}:label(0x01010001)=@0x7f040000"
RULES = f"{NS}:dataExtractionRules(0x0101063e)=@0x7f060000"


def rules(cloud=("root", "file", "database", "sharedpref", "external"),
          transfer=("root", "file", "database", "sharedpref", "external"),
          extra=""):
    def section(name, domains):
        out = f"    E: {name} (line=8)\n"
        for d in domains:
            out += f'        E: exclude (line=9)\n          A: domain="{d}" (Raw: "{d}")\n'
        return out
    out = "E: data-extraction-rules (line=7)\n"
    if cloud is not None:
        out += section("cloud-backup", cloud)
    if transfer is not None:
        out += section("device-transfer", transfer)
    return out + extra


class Backup(unittest.TestCase):
    def rejects(self, text):
        with self.assertRaises(GateError):
            check_backup(text)

    def test_explicit_false_passes_and_returns_the_rules_id(self):
        self.assertEqual(check_backup(manifest(GOOD)), "0x7f060000")

    def test_legacy_encoding_of_false_passes(self):
        attrs = [LABEL, "android:allowBackup(0x01010280)=(type 0x12)0x0", RULES]
        self.assertEqual(check_backup(manifest(attrs)), "0x7f060000")

    def test_explicit_true_fails(self):
        self.rejects(manifest([LABEL, f"{NS}:allowBackup(0x01010280)=true", RULES]))

    def test_missing_attribute_fails(self):
        self.rejects(manifest([LABEL, RULES]))

    def test_audit_label_impersonating_the_attribute_fails(self):
        spoof = f'{NS}:label(0x01010001)="android:allowBackup=false" (Raw: "android:allowBackup=false")'
        self.rejects(manifest([spoof, RULES]))

    def test_audit_child_metadata_impersonating_the_attribute_fails(self):
        child = (
            "          E: meta-data (line=40)\n"
            f'            A: {NS}:name(0x01010003)="android:allowBackup=false" '
            '(Raw: "android:allowBackup=false")\n'
        )
        self.rejects(manifest([LABEL, RULES], child))

    def test_attribute_only_on_a_child_element_fails(self):
        child = "          E: activity (line=40)\n" f"            A: {NS}:allowBackup(0x01010280)=false\n"
        self.rejects(manifest([LABEL, RULES], child))

    def test_right_name_wrong_resource_id_fails(self):
        self.rejects(manifest([LABEL, f"{NS}:allowBackup(0x01019999)=false", RULES]))

    def test_repeated_attribute_fails(self):
        self.rejects(manifest(GOOD + [f"{NS}:allowBackup(0x01010280)=true"]))

    def test_unrecognised_value_fails(self):
        self.rejects(manifest([LABEL, f"{NS}:allowBackup(0x01010280)=@0x7f010000", RULES]))

    def test_missing_extraction_rules_fails(self):
        self.rejects(manifest([LABEL, f"{NS}:allowBackup(0x01010280)=false"]))

    def test_two_application_elements_fail(self):
        text = manifest(GOOD) + "      E: application (line=90)\n"
        self.rejects(text)

    def test_unparseable_dump_fails(self):
        self.rejects(manifest(GOOD) + "garbage that is not xmltree\n")


class Resource(unittest.TestCase):
    DUMP = (
        "  type xml id=06 entryCount=1\n"
        "    resource 0x7f060000 xml/data_extraction_rules\n"
        "      () (file) res/4j.xml type=XML\n"
        "  type string id=04 entryCount=1\n"
    )

    def test_resolves_the_shortened_path(self):
        self.assertEqual(resource_files(self.DUMP, "0x7f060000"), ["res/4j.xml"])

    def test_unknown_id_fails(self):
        with self.assertRaises(GateError):
            resource_files(self.DUMP, "0x7f060001")


class Rules(unittest.TestCase):
    def rejects(self, text):
        with self.assertRaises(GateError):
            check_rules(text)

    def test_everything_excluded_passes(self):
        check_rules(rules())

    def test_missing_device_transfer_fails(self):
        self.rejects(rules(transfer=None))

    def test_missing_domain_fails(self):
        self.rejects(rules(transfer=("root", "file", "database", "sharedpref")))

    def test_an_include_fails(self):
        extra = '    E: cloud-backup (line=30)\n        E: include (line=31)\n          A: domain="file" (Raw: "file")\n'
        self.rejects(rules(extra=extra))  # also a second cloud-backup: rejected either way

    def test_include_inside_a_section_fails(self):
        text = rules().replace(
            "    E: device-transfer (line=8)\n",
            '    E: device-transfer (line=8)\n        E: include (line=9)\n          A: domain="file" (Raw: "file")\n',
        )
        self.rejects(text)


if __name__ == "__main__":
    unittest.main()
