#!/usr/bin/env python3
"""
Offline consistency check for the ModMorpher Android app.

There is no Android SDK in CI-less environments, and aapt/AGP would otherwise
be the thing that catches these mistakes. This script catches the cheap ones
without a JDK:

  * every XML file is well-formed
  * every @string/@color/@drawable/@layout/@menu/@mipmap/@xml reference exists
  * every R.* reference in Kotlin exists
  * every ViewBinding property (binding.foo) is a real id in that layout
  * every Activity declared in the manifest has a matching Kotlin class
  * the launcher icon / theme referenced by the manifest exist

It does NOT type-check Kotlin. Run:  python3 scripts/check_resources.py
"""
from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
RES = os.path.join(ANDROID, "app", "src", "main", "res")
SRC = os.path.join(ANDROID, "app", "src", "main", "java")
MANIFEST = os.path.join(ANDROID, "app", "src", "main", "AndroidManifest.xml")

KINDS = ("string", "color", "drawable", "mipmap", "layout", "style", "menu", "xml", "id")

# Resources provided by AndroidX/Material, not by this module.
LIBRARY_RESOURCES = {
    ("string", "appbar_scrolling_view_behavior"),
}

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def element_ids(root) -> set[str]:
    """Ids of every element, handling ElementTree's namespaced attributes."""
    found = set()
    for el in root.iter():
        for key, value in el.attrib.items():
            if key == "id" or key == ANDROID_NS + "id":
                found.add(value.split("/")[-1])
    return found


def collect() -> dict[str, set[str]]:
    defined: dict[str, set[str]] = {kind: set() for kind in KINDS}
    for root, _, files in os.walk(RES):
        folder = os.path.basename(root)
        for name in files:
            path = os.path.join(root, name)
            base = name.rsplit(".", 1)[0]
            if folder.startswith("values"):
                for child in ET.parse(path).getroot():
                    if child.tag in defined and "name" in child.attrib:
                        defined[child.tag].add(child.attrib["name"])
            elif folder.startswith(("layout", "menu")):
                kind = "layout" if folder.startswith("layout") else "menu"
                defined[kind].add(base)
                defined["id"] |= element_ids(ET.parse(path).getroot())
            elif folder.startswith("drawable"):
                defined["drawable"].add(base)
            elif folder.startswith("mipmap"):
                defined["mipmap"].add(base)
            elif folder.startswith("xml"):
                defined["xml"].add(base)
    return defined


def xml_files() -> list[str]:
    found = []
    for root, _, files in os.walk(os.path.join(ANDROID, "app", "src", "main")):
        found += [os.path.join(root, f) for f in files if f.endswith(".xml")]
    return sorted(found)


def layout_ids(layout: str) -> set[str]:
    path = os.path.join(RES, "layout", layout + ".xml")
    return element_ids(ET.parse(path).getroot())


def main() -> int:
    errors: list[str] = []
    defined = collect()

    # 1) XML well-formedness
    files = xml_files()
    for path in files:
        try:
            ET.parse(path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"malformed XML: {path}: {exc}")

    # 2) resource references (dot-free names are ours; dotted ones are library
    #    styles like Widget.Material3.*, which we cannot verify offline)
    ref_re = re.compile(r"@(string|color|drawable|mipmap|layout|menu|xml)/([A-Za-z0-9_]+)")
    for path in files:
        text = open(path, encoding="utf-8").read()
        for kind, name in ref_re.findall(text):
            if (kind, name) in LIBRARY_RESOURCES:
                continue
            if name not in defined[kind]:
                errors.append(f"{os.path.relpath(path, ANDROID)}: missing @{kind}/{name}")

    # 3) Kotlin R.* references
    r_re = re.compile(r"(?<!android\.)\bR\.(string|color|drawable|layout|menu|id|mipmap)\.([A-Za-z0-9_]+)")
    for root, _, files_ in os.walk(SRC):
        for name in files_:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            for kind, ident in r_re.findall(open(path, encoding="utf-8").read()):
                if ident not in defined[kind]:
                    errors.append(f"{os.path.relpath(path, ANDROID)}: missing R.{kind}.{ident}")

    # 4) ViewBinding properties, per layout actually used by that file
    bindings = {
        "ActivityMainBinding": "activity_main",
        "ActivitySettingsBinding": "activity_settings",
        "ItemJobBinding": "item_job",
    }
    ids = {layout: layout_ids(layout) for layout in bindings.values()}
    for root, _, files_ in os.walk(SRC):
        for name in files_:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8").read()
            for cls, layout in bindings.items():
                if cls not in text:
                    continue
                for match in re.finditer(r"\bbinding\.([A-Za-z0-9_]+)", text):
                    after = text[match.end():match.end() + 1]
                    if after == "(":
                        continue  # method call, not a generated property
                    prop = match.group(1)
                    if prop not in ids[layout] and prop != "root":
                        errors.append(
                            f"{os.path.relpath(path, ANDROID)}: binding.{prop} "
                            f"is not an id in {layout}.xml"
                        )

    # 5) manifest sanity
    tree = ET.parse(MANIFEST)
    for el in tree.getroot().iter("activity"):
        cls = el.attrib.get("{http://schemas.android.com/apk/res/android}name", "")
        if cls.startswith("."):
            rel = cls[1:].replace(".", "/")
            if not os.path.isfile(os.path.join(SRC, "modmorpher", "app", rel + ".kt")):
                errors.append(f"manifest declares {cls} but no matching Kotlin file")
    for attr, kind in (
        ("{http://schemas.android.com/apk/res/android}icon", "mipmap"),
        ("{http://schemas.android.com/apk/res/android}roundIcon", "mipmap"),
        ("{http://schemas.android.com/apk/res/android}theme", "style"),
    ):
        value = tree.getroot().find("application").attrib.get(attr, "")
        if value.startswith("@"):
            name = value.split("/")[-1]
            if name not in defined[kind]:
                errors.append(f"manifest {attr.split('}')[-1]} -> missing {kind}/{name}")

    print(f"checked {len(files)} XML files and the Kotlin sources")
    if errors:
        print("\nproblems:")
        for error in sorted(set(errors)):
            print("  -", error)
        return 1
    print("OK: all resource, binding and manifest references resolve")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
