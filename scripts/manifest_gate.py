#!/usr/bin/env python3
"""Structural checks on `aapt2 dump xmltree` output, for the release gate.

The gate used to pattern-match lines: any line mentioning
`android:allowBackup` whose text contained `=false` passed. An application
label, or a child element's attribute, containing that text satisfied it even
with the real attribute missing (security audit 2026-09-27, R-02). Here the
dump is parsed into a tree and only the real attribute is read: a direct
attribute of <application>, matched by exact name, with an exact value.
Anything missing, repeated, or unrecognised fails.

It also checks what backup being off still leaves open. Android 12+ governs
device-to-device transfer separately, through the dataExtractionRules
resource, so that resource must exist and exclude every domain in both of its
sections.

Usage (each prints a message and exits non-zero on failure):
    manifest_gate.py backup    <manifest-xmltree.txt>   → prints the rules resource id
    manifest_gate.py resource  <dump-resources.txt> <id> → prints the file path(s)
    manifest_gate.py rules     <rules-xmltree.txt>
"""
import re
import sys

ANDROID_NS = "http://schemas.android.com/apk/res/android"
# aapt2 prints the namespace URI; older builds printed the `android:` prefix.
PREFIXES = (ANDROID_NS + ":", "android:")
ALLOW_BACKUP_ID = "0x01010280"
RULES_ID = "0x0101063e"
DOMAINS = {"root", "file", "database", "sharedpref", "external"}


class GateError(Exception):
    pass


class Node:
    def __init__(self, name, indent):
        self.name = name
        self.indent = indent
        self.attrs = []  # (name, value) as printed, in order
        self.children = []


LINE = re.compile(r"^( *)([ENA]): (.*)$")
ELEMENT = re.compile(r"^(\S+) \(line=\d+\)$")


def parse(text):
    """Parse xmltree output into Nodes. Namespace (N:) lines are transparent."""
    root = Node("#root", -1)
    stack = [root]
    for raw in text.splitlines():
        if not raw.strip():
            continue
        m = LINE.match(raw)
        if not m:
            raise GateError(f"unrecognised xmltree line: {raw!r}")
        indent, kind, rest = len(m.group(1)), m.group(2), m.group(3)
        while stack[-1].indent >= indent:
            stack.pop()
        parent = stack[-1]
        if kind == "E":
            em = ELEMENT.match(rest)
            if not em:
                raise GateError(f"unrecognised element line: {raw!r}")
            node = Node(em.group(1), indent)
            parent.children.append(node)
            stack.append(node)
        elif kind == "N":
            node = Node("#ns", indent)  # namespace scope: children belong to it
            parent.children.append(node)
            stack.append(node)
        else:
            if "=" not in rest:
                raise GateError(f"attribute without a value: {raw!r}")
            name, value = rest.split("=", 1)
            parent.attrs.append((name, value))
    return root


def elements(node, name):
    """Elements with this name, looking through namespace scopes only."""
    out = []
    for c in node.children:
        if c.name == "#ns":
            out.extend(elements(c, name))
        elif c.name == name:
            out.append(c)
    return out


def the_one(nodes, what):
    if len(nodes) != 1:
        raise GateError(f"expected exactly one {what}, found {len(nodes)}")
    return nodes[0]


def android_attr(node, local, res_id):
    """Values of a direct android: attribute, matched by exact name and id."""
    names = {p + f"{local}({res_id})" for p in PREFIXES}
    return [v for n, v in node.attrs if n in names]


def application(text):
    manifest = the_one(elements(parse(text), "manifest"), "<manifest>")
    return the_one(elements(manifest, "application"), "<application> in <manifest>")


def check_backup(text):
    """Require allowBackup=false on <application>; return the rules resource id."""
    app = application(text)
    values = android_attr(app, "allowBackup", ALLOW_BACKUP_ID)
    if len(values) != 1:
        raise GateError(f"expected one android:allowBackup on <application>, found {len(values)}")
    if values[0] not in ("false", "(type 0x12)0x0"):
        raise GateError(f"android:allowBackup is {values[0]!r}, expected false")

    rules = android_attr(app, "dataExtractionRules", RULES_ID)
    if len(rules) != 1:
        raise GateError(f"expected one android:dataExtractionRules on <application>, found {len(rules)}")
    if not re.fullmatch(r"@0x7f[0-9a-f]{6}", rules[0]):
        raise GateError(f"android:dataExtractionRules is {rules[0]!r}, expected an app resource reference")
    return rules[0][1:]


def resource_files(dump, res_id):
    """File path(s) for an XML resource id in `aapt2 dump resources` output."""
    lines = dump.splitlines()
    for i, line in enumerate(lines):
        if re.match(rf"^\s*resource {re.escape(res_id)} xml/\S+$", line):
            files = []
            for nxt in lines[i + 1:]:
                fm = re.match(r"^\s*\(.*\) \(file\) (\S+) type=XML$", nxt)
                if not fm:
                    break
                files.append(fm.group(1))
            if not files:
                raise GateError(f"resource {res_id} has no XML file")
            return files
    raise GateError(f"resource {res_id} not found as an xml resource")


def check_rules(text):
    """Both sections must exist once, exclude every domain, and include nothing."""
    rules = the_one(elements(parse(text), "data-extraction-rules"), "<data-extraction-rules>")
    for section in ("cloud-backup", "device-transfer"):
        sec = the_one(elements(rules, section), f"<{section}>")
        if elements(sec, "include"):
            raise GateError(f"<{section}> includes data")
        excluded = set()
        for ex in elements(sec, "exclude"):
            for name, value in ex.attrs:
                if name == "domain":
                    m = re.fullmatch(r'"([a-z]+)" \(Raw: "\1"\)', value)
                    if not m:
                        raise GateError(f"unrecognised domain value {value!r} in <{section}>")
                    excluded.add(m.group(1))
        missing = DOMAINS - excluded
        if missing:
            raise GateError(f"<{section}> doesn't exclude {', '.join(sorted(missing))}")


def main(argv):
    try:
        if len(argv) == 3 and argv[1] == "backup":
            print(check_backup(open(argv[2]).read()))
        elif len(argv) == 4 and argv[1] == "resource":
            print("\n".join(resource_files(open(argv[2]).read(), argv[3])))
        elif len(argv) == 3 and argv[1] == "rules":
            check_rules(open(argv[2]).read())
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except GateError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
