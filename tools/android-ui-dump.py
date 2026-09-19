"""Prints the clickable nodes of an Android uiautomator dump, with their bounds.

Used to drive the app's UI from a script without guessing coordinates: a tap that
lands four pixels off a row looks exactly like a broken handler.
"""

import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else ".runtime-build/ui.xml"
xml = open(path, encoding="utf-8").read()

for tag in re.findall(r"<node[^>]*>", xml):
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    bounds = re.search(r'bounds="([^"]*)"', tag)
    clickable = 'clickable="true"' in tag
    checked = re.search(r'checked="([^"]*)"', tag)
    cls = re.search(r'class="([^"]*)"', tag)

    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if not label and not clickable:
        continue

    box = bounds.group(1) if bounds else "?"
    # A tap target's centre, which is what a script needs.
    centre = ""
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", box)
    if match:
        x1, y1, x2, y2 = (int(v) for v in match.groups())
        centre = f"centre=({(x1 + x2) // 2},{(y1 + y2) // 2})"

    kind = (cls.group(1) if cls else "").rsplit(".", 1)[-1]
    state = f" checked={checked.group(1)}" if checked else ""
    print(f"  {box:26} {centre:20} clickable={str(clickable):5}{state}  {kind:16} {label}")
