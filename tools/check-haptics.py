#!/usr/bin/env python3
"""
Checks that every buzz in the app comes through one door.

`ui/kit/PiHaptics.kt` is the app's whole haptic vocabulary: four moments — a hold, a
mode that stays flipped, a row that becomes the chosen one, a key on a keyboard this
app draws — each mapped onto one platform constant, with the moments that must stay
silent written down beside them. That standard survives exactly as long as nothing
else in the tree calls `performHapticFeedback` directly: a fifth type invented at a
call site is a fifth moment nobody documented, and it is invisible in review because
the line looks like any other.

The checker enforces the two halves that a machine can see:

  1. the platform's feedback API appears in `PiHaptics.kt` and nowhere else;
  2. the kit declares exactly the four documented moments, so a fifth one has to be
     argued for in `docs/architecture/16-touch.md` (and in this file) rather than
     added to the kit.

What it cannot check is judgement — whether a particular call site deserves its buzz.
That is the chapter's table and the code review's job.

Run from the repository root:

    python tools/check-haptics.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCES = REPO_ROOT / "app/src/main/java"
KIT = SOURCES / "pi/kit/mob/ui/kit/PiHaptics.kt"

#: The four moments, in the order the chapter table lists them. A function added to
#: the kit without a row here is a change to the standard, and this file is where
#: that has to be said out loud.
MOMENTS = ["hold", "toggle", "choose", "key"]

#: The API a second door would use. `LocalHapticFeedback` alone is harmless — reading
#: the local is how the kit is built — so the patterns name the performing half.
DOORS = re.compile(r"performHapticFeedback|HapticFeedbackType\.")

#: A call site, for the count printed at the end: the kit's own name in the tree.
CALL_SITE = re.compile(r"\bhaptics\.(hold|toggle|choose|key)\(")


def kotlin_sources() -> list[Path]:
    return sorted(
        path
        for path in SOURCES.rglob("*.kt")
        # Comments are prose: a KDoc that quotes `performHapticFeedback` is explaining
        # the rule, not breaking it. Stripping them here keeps the check honest.
        if path.is_file()
    )


def without_comments(text: str) -> str:
    """
    [text] with its comments removed, one output line per input line.

    Line *numbers* have to survive the strip: this file reports them, and a reader
    chasing a failure at a line the checker renumbered is chasing a line that moved.
    A `//` inside a string literal is treated as a comment, which is the one thing the
    heuristic cannot see and does not need to — nothing it searches for can appear
    after a URL on the same line.
    """
    out: list[str] = []
    in_block = False
    for line in text.split("\n"):
        kept: list[str] = []
        index = 0
        while index < len(line):
            if in_block:
                end = line.find("*/", index)
                if end == -1:
                    index = len(line)
                else:
                    in_block = False
                    index = end + 2
                continue
            block = line.find("/*", index)
            line_comment = line.find("//", index)
            if line_comment != -1 and (block == -1 or line_comment < block):
                index = len(line)
            elif block != -1:
                kept.append(line[index:block])
                in_block = True
                index = block + 2
            else:
                kept.append(line[index:])
                index = len(line)
        out.append("".join(kept))
    return "\n".join(out)


def check_single_door() -> int:
    failures = 0
    for path in kotlin_sources():
        if path == KIT:
            continue
        body = without_comments(path.read_text(encoding="utf-8"))
        for number, line in enumerate(body.split("\n"), start=1):
            if DOORS.search(line):
                failures += 1
                print(
                    f"  FAIL  {path.relative_to(REPO_ROOT)}:{number} calls the platform "
                    f"directly: {line.strip()!r}"
                )
    if failures == 0:
        print(f"  every buzz goes through {KIT.relative_to(REPO_ROOT).as_posix()}")
    return failures


def check_vocabulary() -> int:
    body = without_comments(KIT.read_text(encoding="utf-8"))
    declared = re.findall(r"^\s{4}fun (\w+)\(", body, flags=re.M)
    print(f"  the kit declares: {', '.join(declared) if declared else 'nothing'}")
    if declared == MOMENTS:
        return 0
    print(
        "  FAIL  the kit's moments are "
        f"[{', '.join(declared)}] but the standard is [{', '.join(MOMENTS)}].\n"
        "        A new moment is a change to docs/architecture/16-touch.md and to this\n"
        "        file's MOMENTS list, in the same change — not a function in the kit."
    )
    return 1


def report_call_sites() -> None:
    per_file: dict[str, int] = {}
    for path in kotlin_sources():
        count = len(CALL_SITE.findall(path.read_text(encoding="utf-8")))
        if count:
            per_file[path.relative_to(REPO_ROOT).as_posix()] = count
    total = sum(per_file.values())
    print(f"  {total} call sites in {len(per_file)} files")
    for name, count in sorted(per_file.items(), key=lambda item: (-item[1], item[0])):
        print(f"    {count:3d}  {name}")


def main() -> int:
    if not KIT.is_file():
        print(f"{KIT.relative_to(REPO_ROOT).as_posix()} is missing")
        return 1
    print("haptics")
    failures = check_vocabulary() + check_single_door()
    report_call_sites()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
