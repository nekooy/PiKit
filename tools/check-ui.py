#!/usr/bin/env python3
"""
Checks the UI rules a machine can see, so the prose in `docs/UI.md` is not the only
thing holding them.

The design language (ARCHITECTURE §13) is mostly judgement — whether a surface is a
card or a floating control, whether a moment is a toast or a condition. Some of its
rules are not judgement: they are a closed set of files, and a regex can hold the
door. Each is a drift that compiles, looks like every other line, and is invisible in
review until a screen is a second material or a fourth palette:

  1. **The colour families stay closed.** A `Color(0x…)` literal or a named chromatic
     hue (`Color.Red` and friends) is legal only in the four families that own one:
     the theme (`ui/kit/PiKitTheme.kt`), the terminal (`TerminalScreen.kt`,
     `TerminalKeyboard.kt`), the setup screen (`PiKitRoot.kt`) and the avatar's
     content colours (`data/AvatarColor.kt` — a disc is content, red in both schemes
     the way a photograph is, §13). Everywhere else the colour is
     `MiuixTheme.colorScheme.*` or a `PiTone` role. `Color.White`, `Color.Black` and
     `Color.Transparent` are structural, not hues, and are allowed anywhere.
  2. **The bar is composed in one place.** `GlassTopAppBar`/`SmallTopAppBar` appears
     in `ui/kit/PiPage.kt` and nowhere else, because the kit draws the page's one-line
     subtitle itself; a screen that composes its own bar passes the library a
     `subtitle` that wraps and grows the bar (the terminal measured it).
  3. **The glass overlay pair and the panel belong to the kit.** `GlassDialog` /
     `GlassBottomSheet` are composed in `ui/kit/PiOverlays.kt` (which makes the
     glass-or-window split on `LocalPiBackdrop`) and `glassPanel` in
     `ui/kit/PiKitTheme.kt` (which supplies the shared material and the opaque
     fallback). A screen reaches them through `PiConfirm`/`PiSheet`/`PiGlass`, not
     directly — a direct call is a control that skips the fallback gate or the
     material every other floating control shares.
  4. **Every popup menu is the kit's glass one.** `GlassPopup`,
     `GlassIconDropdownMenu` and `GlassDropdownPopup` are composed in
     `ui/kit/PiPopupMenu.kt`; `OverlayListPopup`, `OverlayDropdownPopup`,
     `WindowListPopup` and the `*Cascading*` variants nowhere at all. A menu
     drawn from Miuix's basic popup panel is the pre-OS4 opaque rectangle beside a
     page whose bar, capsules and dialogs are glass (§13), and one composed at its
     trigger is clipped by that trigger's node instead of measured against the
     window. A screen reaches a menu through `PiPopupMenu`; a *custom* panel that
     is genuinely not a menu of rows is judgement, and `docs/UI.md`'s popup section
     is where that is written.

  5. **A placeholder field is floored at the label's line box.** Miuix draws
     `label` as a 17sp `Text` with platform font padding (23dp), while the typed
     line is 19dp; a field that passes `useLabelAsPlaceholder = true` without
     `Modifier.piFieldHeightForPlaceholder()` shrinks by 4dp when the first
     character arrives (measured 182px → 171px on the composer and 110px → 102px
     on the drawer search, §13). The same call must carry the modifier.

A custom-content dialog may still compose `WindowDialog`/`WindowBottomSheet` itself
(the kit's confirm shape does not fit a field or a crop frame); that is judgement,
and `docs/UI.md`'s dialog section is where the glass-or-window rule for it is written.

Run from the repository root:

    python tools/check-ui.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCES = REPO_ROOT / "app/src/main/java"
UI = SOURCES / "pi/kit/mob/ui"

#: The files that own a colour family. A literal hue anywhere else is a fifth
#: palette, which is the drift the Miuix rewrite removed (§13). The avatar's disc is
#: content rather than a scheme role, so its eight hand-picked colours are a family
#: of their own and do not follow the light/dark switch.
COLOR_OWNERS = {
    UI / "kit/PiKitTheme.kt",
    UI / "TerminalScreen.kt",
    UI / "TerminalKeyboard.kt",
    UI / "PiKitRoot.kt",
    SOURCES / "pi/kit/mob/data/AvatarColor.kt",
}

#: An invented colour: a hex literal, or a named chromatic hue. White/Black/
#: Transparent are structural (a scrim, a knockout) and are not what this names.
INVENTED_COLOR = re.compile(
    r"Color\s*\(\s*0x|Color\.(?:Red|Green|Blue|Yellow|Cyan|Magenta|Gray|LightGray|DarkGray)\b"
)

#: The bar, composed only by the page frame.
BAR = re.compile(r"\b(?:GlassTopAppBar|SmallTopAppBar)\s*\(")
BAR_OWNER = UI / "kit/PiPage.kt"

#: The glass overlay pair, composed only by the kit's dialog/sheet split.
GLASS_OVERLAY = re.compile(r"\b(?:GlassDialog|GlassBottomSheet)\s*\(")
GLASS_OVERLAY_OWNER = UI / "kit/PiOverlays.kt"

#: The panel modifier, called only by `PiGlass.panel`.
GLASS_PANEL = re.compile(r"\bglassPanel\s*\(")
GLASS_PANEL_OWNER = UI / "kit/PiKitTheme.kt"

#: The OS4 popup components, composed only by the kit's menu surface.
GLASS_POPUP = re.compile(
    r"\b(?:GlassPopup|GlassTransformPopup|GlassIconDropdownMenu|GlassDropdownPopup)\s*\("
)
GLASS_POPUP_OWNER = UI / "kit/PiPopupMenu.kt"

#: The pre-OS4 popup panels and the preference rows that draw them, which no screen may
#: compose any more. This one is not a "composed only here" rule: nothing in the app draws
#: these, so the owner set is empty and every occurrence is a failure.
OLD_POPUP = re.compile(
    r"\b(?:OverlayListPopup|OverlayDropdownPopup|WindowListPopup|WindowDropdownPopup"
    r"|OverlayCascadingListPopup|WindowCascadingListPopup"
    r"|OverlayDropdownPreference|WindowDropdownPreference"
    r"|OverlaySpinnerPreference|WindowSpinnerPreference)\s*\("
)

#: A Miuix field that draws its label as the placeholder. The label takes part in the
#: decoration's measurement only while the field is empty, so the field must pass the
#: kit's height floor or it shrinks when the first character replaces the label.
PLACEHOLDER_LABEL = re.compile(r"useLabelAsPlaceholder\s*=\s*true")
PLACEHOLDER_GUARD = "piFieldHeightForPlaceholder"


def kotlin_sources() -> list[Path]:
    return sorted(path for path in SOURCES.rglob("*.kt") if path.is_file())


def without_comments(text: str) -> str:
    """
    [text] with its comments removed, one output line per input line.

    Line numbers survive the strip: a reader chasing a failure at a line the checker
    renumbered is chasing a line that moved. A `//` inside a string literal is treated
    as a comment, which the heuristic cannot see and does not need to — none of the
    patterns above appears after a URL on the same line. (Same helper as
    `check-haptics.py`; the two checkers read the same tree the same way.)
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


def scan(rule: str, pattern: re.Pattern[str], owner: Path | set[Path]) -> int:
    owners = owner if isinstance(owner, set) else {owner}
    failures = 0
    for path in kotlin_sources():
        if path in owners:
            continue
        body = without_comments(path.read_text(encoding="utf-8"))
        for number, line in enumerate(body.split("\n"), start=1):
            if pattern.search(line):
                failures += 1
                rel = path.relative_to(REPO_ROOT).as_posix()
                print(f"  FAIL  {rel}:{number} {rule}: {line.strip()!r}")
    return failures


def check_placeholder_height() -> int:
    """Every `useLabelAsPlaceholder = true` call carries the height floor."""
    failures = 0
    for path in kotlin_sources():
        body = without_comments(path.read_text(encoding="utf-8"))
        for match in PLACEHOLDER_LABEL.finditer(body):
            call_start = max(
                body.rfind("TextField(", 0, match.start()),
                body.rfind("GlassTextField(", 0, match.start()),
            )
            if call_start == -1:
                continue
            depth = 0
            call_end = -1
            for index in range(call_start, len(body)):
                char = body[index]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                    if depth == 0:
                        call_end = index
                        break
            if call_end == -1 or PLACEHOLDER_GUARD not in body[call_start:call_end]:
                failures += 1
                line = body.count("\n", 0, match.start()) + 1
                rel = path.relative_to(REPO_ROOT).as_posix()
                print(
                    f"  FAIL  {rel}:{line} a placeholder field without "
                    f"{PLACEHOLDER_GUARD}(): 'useLabelAsPlaceholder = true'",
                )
    return failures


def main() -> int:
    if not UI.is_dir():
        print(f"{UI.relative_to(REPO_ROOT).as_posix()} is missing")
        return 1
    print("ui")
    failures = 0
    failures += scan("an invented colour outside the four families", INVENTED_COLOR, COLOR_OWNERS)
    failures += scan("a bar composed outside PiPage", BAR, BAR_OWNER)
    failures += scan("a glass overlay composed outside PiOverlays", GLASS_OVERLAY, GLASS_OVERLAY_OWNER)
    failures += scan("glassPanel called outside PiGlass", GLASS_PANEL, GLASS_PANEL_OWNER)
    failures += scan(
        "an OS4 popup composed outside PiPopupMenu",
        GLASS_POPUP,
        GLASS_POPUP_OWNER,
    )
    # No owner: the pre-OS4 panels are not drawn anywhere any more.
    failures += scan("a pre-OS4 popup panel composed", OLD_POPUP, set())
    failures += check_placeholder_height()
    if failures == 0:
        print(
            "  the colour families are closed, the bar is PiPage's, the glass pair is the "
            "kit's, every menu is the kit's glass popup, every placeholder field is floored",
        )
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
