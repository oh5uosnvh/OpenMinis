#!/usr/bin/env python3
"""Validate generated VectorDrawables against what AAPT will accept.

AAPT rejects the ENTIRE resource link on one bad attribute value, and the error
surfaces eight minutes into a Gradle run rather than at generation time. This
checks the handful of constraints that actually bit:

  * `fillType` must be `evenOdd` / `nonZero` — SVG's lowercase `evenodd` is a
    hard error ("'evenodd' is incompatible with attribute fillType").
  * colours must be #RGB / #ARGB / #RRGGBB / #AARRGGBB.
  * every `<path>` needs non-empty `pathData`, and every attribute must live in
    the android namespace.
  * `strokeLineCap` / `strokeLineJoin` / `fillType` values are enums; anything
    else is rejected.

Exit 1 on the first file with problems, listing them all.
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

ENUMS = {
    "fillType": {"evenOdd", "nonZero"},
    "strokeLineCap": {"butt", "round", "square"},
    "strokeLineJoin": {"miter", "round", "bevel"},
}

COLOR_RE = re.compile(r'^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')
COLOR_ATTRS = {"fillColor", "strokeColor", "tint"}


def check(path):
    problems = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return [f"XML parse error: {e}"]

    if not root.tag.endswith("vector"):
        problems.append(f"root element is <{root.tag}>, expected <vector>")

    for attr in ("width", "height", "viewportWidth", "viewportHeight"):
        if root.get(ANDROID + attr) is None:
            problems.append(f"<vector> missing android:{attr}")

    nodes = [n for n in root.iter() if n.tag.endswith("path")]
    if not nodes:
        problems.append("no <path> elements")

    for i, node in enumerate(nodes):
        where = f"path[{i}]"
        for key, value in node.attrib.items():
            if not key.startswith(ANDROID):
                problems.append(f"{where}: attribute '{key}' is not in the android namespace")
                continue
            name = key[len(ANDROID):]
            if name in ENUMS and value not in ENUMS[name]:
                problems.append(
                    f"{where}: {name}='{value}' invalid; AAPT accepts {sorted(ENUMS[name])}"
                )
            if name in COLOR_ATTRS and not COLOR_RE.match(value):
                problems.append(f"{where}: {name}='{value}' is not a valid colour literal")
        data = node.get(ANDROID + "pathData")
        if not data or not data.strip():
            problems.append(f"{where}: empty pathData")
        elif '"' in data:
            problems.append(f"{where}: pathData contains a double quote")
    return problems


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "."
    files = sorted(glob.glob(os.path.join(target, "fork_brand_*.xml")))
    if not files:
        print(f"validate-vectors: no fork_brand_*.xml under {target}", file=sys.stderr)
        return 1
    bad = 0
    for f in files:
        problems = check(f)
        if problems:
            bad += 1
            print(f"FAIL {os.path.basename(f)}", file=sys.stderr)
            for p in problems:
                print(f"     {p}", file=sys.stderr)
    print(f"validate-vectors: {len(files)} file(s), {bad} with problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
