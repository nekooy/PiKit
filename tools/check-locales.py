#!/usr/bin/env python3
"""
Checks the hand-written prose surface the string catalogs do not cover.

`TerminalBanner.kt` claims in its own header that every line is at most 48 display
columns. That is a claim about text written by hand and read on a 48-column phone
screen, and it has been wrong before: the first version wrapped into a two-line mess.

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

#: The terminal is about this wide.
WIDTH = 48

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


def main() -> int:
    print("terminal banners")
    failures = check_banners()
    print("PASS" if failures == 0 else f"FAIL — {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
