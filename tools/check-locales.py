#!/usr/bin/env python3
"""
Checks the two hand-written prose surfaces the string catalogs do not cover.

`TerminalBanner.kt` claims in its own header that every line is at most 48 display
columns, and `ManualText*.kt` claims to be free of the artefacts a wrap pass leaves
in Chinese and Japanese text. Both are claims about text that is written by hand
and read on a 48-column phone screen, and both have been wrong before — the first
version of the banner wrapped into a two-line mess, and a second run of
`tools/reflow-manual.py` used to insert a space inside every CJK sentence it
re-joined.

Run from the repository root:

    python tools/check-locales.py
"""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LOCALES = REPO_ROOT / "app/src/main/java/pi/kit/mob/locales"

#: The terminal, and the manual read on it, are about this wide.
WIDTH = 48

#: A CJK glyph, a space, another CJK glyph. Those scripts do not use word spaces,
#: so this is always a wrap artefact rather than anything deliberate. A space
#: between a CJK glyph and a Latin word is real and is left alone.
CJK_SPACE = re.compile(
    r"[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]"
    r" [\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]"
)

BANNERS = re.compile(r'private val (\w+_BANNER) = """\n(.*?)"""\.trimIndent\(\)', re.S)


def display_width(text: str) -> int:
    """Columns [text] occupies, counting East Asian wide glyphs as two."""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def check_banners() -> int:
    source = (LOCALES / "TerminalBanner.kt").read_text(encoding="utf-8")
    matches = list(BANNERS.finditer(source))
    if not matches:
        print(f"{LOCALES.name}/TerminalBanner.kt: no banner bodies found")
        return 1

    failures = 0
    for match in matches:
        name, body = match.group(1), match.group(2)
        lines = body.split("\n")
        widest = max(lines, key=display_width)
        print(f"  {name}: widest {display_width(widest)} columns")
        for number, line in enumerate(lines, start=1):
            columns = display_width(line)
            if columns > WIDTH:
                failures += 1
                print(f"  FAIL  {name} line {number} is {columns} columns: {line!r}")
    return failures


def check_cjk_spaces() -> int:
    failures = 0
    for name in ("ManualText.kt", "ManualTextTranslated.kt"):
        text = (LOCALES / name).read_text(encoding="utf-8")
        for number, line in enumerate(text.split("\n"), start=1):
            if CJK_SPACE.search(line):
                failures += 1
                print(f"  FAIL  {name}:{number} has a space inside a CJK run: {line.strip()!r}")
    if failures == 0:
        print("  no CJK word was split by a wrap pass")
    return failures


def main() -> int:
    print("terminal banners")
    failures = check_banners()
    print("manual bodies")
    failures += check_cjk_spaces()
    print("PASS" if failures == 0 else f"FAIL — {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
