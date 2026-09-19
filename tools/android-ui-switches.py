"""Prints the toggle nodes of a uiautomator dump with their centres.

The Settings pages draw their switches as `View` nodes whose `class` is
`android.view.View` rather than `Switch`, so the only way to find one reliably is
to look for the clickable nodes that sit beside a label. Guessing a coordinate
and missing by twenty pixels is indistinguishable from a handler that does
nothing, which is exactly the confusion this avoids.
"""

import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else ".runtime-build/ui.xml"
xml = open(path, encoding="utf-8").read()

nodes = []
for tag in re.findall(r"<node[^>]*>", xml):
    def attr(name, default=""):
        match = re.search(rf'{name}="([^"]*)"', tag)
        return match.group(1) if match else default

    box = attr("bounds")
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", box)
    if not match:
        continue
    x1, y1, x2, y2 = (int(v) for v in match.groups())
    nodes.append(
        {
            "label": attr("text") or attr("content-desc"),
            "clickable": attr("clickable") == "true",
            "checked": attr("checked"),
            "class": attr("class").rsplit(".", 1)[-1],
            "x1": x1,
            "y1": y1,
            "x2": x2,
            "y2": y2,
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
        }
    )

# A switch is a clickable, checkable node in the right-hand column.
for node in nodes:
    if not node["clickable"] or node["checked"] == "":
        continue
    # Find the label in the same row, to its left.
    label = ""
    best = None
    for other in nodes:
        if other["label"] == "" or other["clickable"]:
            continue
        if other["x1"] >= node["x1"]:
            continue
        if not (node["y1"] - 40 <= other["cy"] <= node["y2"] + 40):
            continue
        if best is None or other["y1"] < best["y1"]:
            best = other
    if best:
        label = best["label"].split("\n")[0]

    state = "ON " if node["checked"] == "true" else "off"
    print(f"  {state}  centre=({node['cx']},{node['cy']})  {label}")
