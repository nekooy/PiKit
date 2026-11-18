#!/usr/bin/env python3
"""
Renders the launcher icon into the SVG the README shows, and checks that the app's
two launcher icons are the same mark.

The README's icon is a second copy of something that already exists, and a second
copy is a thing that drifts: the launcher icon is a foreground vector plus a colour
from `values/colors.xml`, and nothing about editing either of those would remind
anyone that the picture at the top of the README is now wrong. So this script
*derives* the SVG from those two files instead of the SVG being hand-drawn, and
`--check` fails when the file on disk no longer matches what they say — which is
what `tools/build-apks.py` runs:

    python tools/render-icon.py           # write docs/assets/icon.svg
    python tools/render-icon.py --check   # fail if it is out of date

The picture is derived from the icon the app **ships with** — the white field and
black mark, `LauncherIcon.DEFAULT = LIGHT` — because the README is a fresh install's
first view of the app.

The *other* copy is `ic_launcher_foreground.xml`, the white-cut mark the black icon
is made of — the second of the two icons the personalization page offers. That one
cannot be derived: an Android vector has no way to take another vector's geometry, so
the path data is written twice and the only defence against the two copies diverging
is to compare them. This does that, in both modes, for the same reason the SVG is
compared at all: a mark that was edited in one file and not the other is two
different icons under one name, and nothing about editing either file would say so.

The picture is the launcher's own viewport, not the layers' full 108 units. An
adaptive icon draws each 108-unit layer with its bounds extended by a quarter on
every side (`AdaptiveIconDrawable.getExtraInsetFraction()`), so what a launcher
shows is the layer's centre 72 units, magnified 1.5x — and a copy drawn as the
whole 108 puts the mark at two thirds the size it has on a device. The crop is
the platform's number, not a redraw: the path data below is still the layer's.

Two things here are the README's own and are deliberately not in the launcher
icon. The corner radius (24 of those 108 units, kept at the same roundness in
the 72-unit frame the picture is drawn in) stands in for the mask an Android
launcher applies — an unmasked square reads as a bug next to other projects'
icons. The hairline border exists because the default icon is white and GitHub
renders README images on a white card in the light theme, where a white square
with no edge is invisible; the line is the icon's own white plus one step, not a
different design.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FOREGROUND = Path("app/src/main/res/drawable/ic_launcher_foreground.xml")
LIGHT_FOREGROUND = Path("app/src/main/res/drawable/ic_launcher_foreground_light.xml")
#: The same mark for the two screens that show the app's logo in-app (§12.3).
WORDMARK = Path("app/src/main/res/drawable/ic_pi_wordmark.xml")
COLORS = Path("app/src/main/res/values/colors.xml")
OUTPUT = Path("docs/assets/icon.svg")

#: The icon the app ships with, and so the one the README shows. The white field is
#: the default (`LauncherIcon.DEFAULT = LIGHT`), so the picture is derived from the
#: light foreground and the light background — deriving it from the black icon would
#: show the README an icon a fresh install does not have.
SHIPPED_FOREGROUND = LIGHT_FOREGROUND
SHIPPED_BACKGROUND_COLOR = "ic_launcher_background_light"

#: The white icon's field, in `colors.xml`. Read rather than assumed, so that "the
#: ink is not the field it is drawn on" compares two files instead of restating one.
LIGHT_BACKGROUND_COLOR = "ic_launcher_background_light"

#: The README's stand-ins for the launcher's mask and for the card it sits on. The
#: radius is 24 of the layer's 108 units, which is 16 of the 72 the picture is drawn
#: in — the roundness of the icon is unchanged, the frame it is drawn in is not. The
#: hairline is the white field plus one step rather than the icon's own ink: the
#: default icon is now white, and a white square on GitHub's white card would have no
#: edge to be seen by.
CORNER_RADIUS = 16
BORDER = "#D5DAE2"
#: The layer is 108 units square; the masked viewport shows its centre 72, because an
#: adaptive icon draws each layer with its bounds extended by a quarter on every side.
LAYER = 108
VIEWPORT = 72
CROP = (LAYER - VIEWPORT) // 2
#: Rendered size. The viewBox is the icon's own units and every coordinate below is the
#: icon's, not a rescaled copy of it.
SIZE = 512

PATH_PATTERN = re.compile(r"<path\b([^>]*?)/>", re.DOTALL)
ATTRIBUTE_PATTERN = re.compile(r'android:(\w+)="([^"]*)"')


def _attributes(block: str) -> dict[str, str]:
    return dict(ATTRIBUTE_PATTERN.findall(block))


def _colour(value: str) -> str:
    """`#FF000000` (Android's aarrggbb) to `#000000`, refusing anything translucent."""
    digits = value.lstrip("#")
    if len(digits) != 8:
        raise SystemExit(f"render-icon: expected #aarrggbb, got {value!r}")
    alpha, rgb = digits[:2], digits[2:]
    if alpha.upper() != "FF":
        raise SystemExit(
            f"render-icon: {value!r} is translucent. The README icon is drawn on "
            "GitHub's own background, so a translucent colour would render as a "
            "guess; make it opaque or teach this script what to composite onto."
        )
    return f"#{rgb.upper()}"


def _glyph(xml: str) -> tuple[dict[str, str], list[dict[str, str]]]:
    """A foreground vector's `<group>` attributes and its `<path>` blocks, in order."""
    group_match = re.search(r"<group\b([^>]*)>", xml)
    return (
        _attributes(group_match.group(1)) if group_match else {},
        [_attributes(block) for block in PATH_PATTERN.findall(xml)],
    )


def _named_colour(colors: str, name: str) -> str | None:
    """One opaque colour out of `colors.xml`, or None when the file does not name it."""
    match = re.search(rf'<color\s+name="{name}"\s*>([^<]+)</color>', colors)
    return _colour(match.group(1).strip()) if match else None


def wordmark_problems() -> list[str]:
    """Every way the in-app wordmark is not the launcher foreground's drawing.

    `ic_pi_wordmark.xml` exists because the two screens without a bar draw the app's
    *logo* rather than an avatar (§12.3), and a logo has to be the icon the reader
    tapped. It is the launcher path moved to its own bounding box by one `<group>`:
    that is the whole design, so the check is that the path data is literally the
    foreground's, and that the translation is the negative of the mark's origin —
    computed here from the path's own coordinates rather than copied, because a
    foreground edit moves the origin and a copied number would then be the drift.

    The viewport and the density-independent size are checked too: they are what
    keeps the drawing's proportions, and a hand-rounded pair is a squashed wordmark.
    """
    foreground = (REPO_ROOT / FOREGROUND).read_text(encoding="utf-8")
    wordmark_path = REPO_ROOT / WORDMARK
    if not wordmark_path.is_file():
        return [f"{WORDMARK} is missing — the two screens without a bar have no logo"]
    wordmark = wordmark_path.read_text(encoding="utf-8")

    _, dark_paths = _glyph(foreground)
    group, paths = _glyph(wordmark)
    problems: list[str] = []
    if len(dark_paths) != 1 or len(paths) != 1:
        return [
            f"{WORDMARK} must be one <path>: the foreground has {len(dark_paths)}, "
            f"the wordmark {len(paths)}"
        ]
    if paths[0].get("pathData") != dark_paths[0].get("pathData"):
        problems.append(
            f"{WORDMARK}'s path data is not {FOREGROUND}'s: the in-app logo and the "
            "launcher icon are now two drawings"
        )

    points = [
        (float(x), float(y))
        for x, y in re.findall(r"[ML]\s*(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)", dark_paths[0]["pathData"])
    ]
    if not points:
        return problems + [f"{FOREGROUND}'s path has no absolute points to derive a box from"]
    origin_x = min(x for x, _ in points)
    origin_y = min(y for _, y in points)
    width = max(x for x, _ in points) - origin_x
    height = max(y for _, y in points) - origin_y
    expected_group = {"translateX": _number(-origin_x), "translateY": _number(-origin_y)}
    actual_group = {
        key: group.get(key, "0")
        for key in ("translateX", "translateY")
        if key in group
    }
    if actual_group != {key: value for key, value in expected_group.items() if value != "0"}:
        problems.append(
            f"{WORDMARK} is drawn at {actual_group or 'no translation'} where the "
            f"foreground's bounding box starts at ({origin_x:g}, {origin_y:g})"
        )
    vector = re.search(r"<vector\b([^>]*)>", wordmark)
    attributes = _attributes(vector.group(1)) if vector else {}
    for attribute, wanted in (
        ("viewportWidth", width),
        ("viewportHeight", height),
        ("width", width),
        ("height", height),
    ):
        value = attributes.get(attribute, "")
        if value.endswith("dp"):
            value = value[: -len("dp")]
        try:
            matches = abs(float(value) - wanted) < 0.0005
        except ValueError:
            matches = False
        if not matches:
            problems.append(
                f"{WORDMARK}'s {attribute} is {attributes.get(attribute)!r} where the "
                f"mark is {_number(wanted)}"
            )
    return problems


def _number(value: float) -> str:
    """A number the way the vector files write one: no trailing zeroes, no `.0`."""
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def light_ink_problems() -> list[str]:
    """Every way the white icon is not the black one's mark, in one list.

    Four ways the two foregrounds can disagree, and each is a different wrong icon
    rather than a different wording of one wrongness:

      - a path edited, added or removed, which is a mark that is no longer the
        shape of the one a user gets by switching back;
      - a `fillType` that differs, which fills the bowl of the P solid — the one
        subpath where that flag is doing work;
      - more than one ink, where the icon is a single flat figure; and
      - an ink that *is* the field it is drawn on, which is a white square with
        nothing to see on it.

    The colour is compared rather than required to be black: "black on white" is a
    claim the three catalogs make, not one this script gets to make for them.
    """
    dark_group, dark_paths = _glyph((REPO_ROOT / FOREGROUND).read_text(encoding="utf-8"))
    light_group, light_paths = _glyph(
        (REPO_ROOT / LIGHT_FOREGROUND).read_text(encoding="utf-8")
    )
    colors = (REPO_ROOT / COLORS).read_text(encoding="utf-8")

    problems: list[str] = []
    if dark_group != light_group:
        problems.append(
            f"{LIGHT_FOREGROUND} is drawn at {light_group or 'no translation'} where "
            f"{FOREGROUND} is drawn at {dark_group or 'no translation'}: the mark sits "
            "somewhere else on one of the two icons"
        )
    if len(dark_paths) != len(light_paths):
        problems.append(
            f"{LIGHT_FOREGROUND} has {len(light_paths)} <path> where {FOREGROUND} has "
            f"{len(dark_paths)}"
        )
    else:
        for number, (black, white) in enumerate(zip(dark_paths, light_paths), start=1):
            if black.get("pathData") != white.get("pathData"):
                problems.append(
                    f"path {number} of {LIGHT_FOREGROUND} is not {FOREGROUND}'s"
                )
            if black.get("fillType") != white.get("fillType"):
                problems.append(
                    f"path {number} of {LIGHT_FOREGROUND} has fillType "
                    f"{white.get('fillType')!r} where {FOREGROUND} has "
                    f"{black.get('fillType')!r}"
                )

    inks = {_colour(block["fillColor"]) for block in light_paths}
    if len(inks) != 1:
        problems.append(
            f"{LIGHT_FOREGROUND} is inked in {len(inks)} colours "
            f"({', '.join(sorted(inks))}); it is one flat figure"
        )
    elif inks == {_named_colour(colors, LIGHT_BACKGROUND_COLOR)}:
        problems.append(
            f"{LIGHT_FOREGROUND} is inked {inks.pop()}, which is the field it is drawn "
            f"on ({LIGHT_BACKGROUND_COLOR}): the white icon would be a blank square"
        )
    return problems


def build_svg() -> str:
    foreground = (REPO_ROOT / SHIPPED_FOREGROUND).read_text(encoding="utf-8")
    colors = (REPO_ROOT / COLORS).read_text(encoding="utf-8")

    background = _named_colour(colors, SHIPPED_BACKGROUND_COLOR)
    if background is None:
        raise SystemExit(
            f"render-icon: no {SHIPPED_BACKGROUND_COLOR} in {COLORS}, which is the "
            "colour the adaptive icon paints behind the mark"
        )

    group, blocks = _glyph(foreground)
    translate_x = group.get("translateX", "0")
    translate_y = group.get("translateY", "0")
    transform = (
        f' transform="translate({translate_x} {translate_y})"'
        if translate_x != "0" or translate_y != "0"
        else ""
    )

    if not blocks:
        raise SystemExit(f"render-icon: no <path> in {SHIPPED_FOREGROUND}")
    fills = {_colour(block["fillColor"]) for block in blocks}
    if len(fills) != 1:
        raise SystemExit(
            f"render-icon: the foreground uses {len(fills)} colours "
            f"({', '.join(sorted(fills))}). This script writes one; a multi-colour "
            "glyph needs it taught about them."
        )
    # One `<g fill=...>` rather than one fill per path: the paths are one glyph,
    # and a colour repeated per path is a colour that can be changed in one place
    # and not the other.
    paths = [
        "        <path{} d=\"{}\" />".format(
            ' fill-rule="evenodd"' if block.get("fillType") == "evenOdd" else "",
            block["pathData"],
        )
        for block in blocks
    ]

    fill = fills.pop()
    return "\n".join(
        [
            '<?xml version="1.0" encoding="UTF-8"?>',
            "<!--",
            "    PiKit's launcher icon as the README shows it. Generated by",
            "    tools/render-icon.py from app/src/main/res/drawable/ic_launcher_foreground_light.xml",
            "    and app/src/main/res/values/colors.xml — edit those, not this, then run",
            "    `python tools/render-icon.py`.",
            "-->",
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}"',
            f'    viewBox="{CROP} {CROP} {VIEWPORT} {VIEWPORT}" role="img" aria-label="PiKit">',
            f'    <rect x="{CROP}" y="{CROP}" width="{VIEWPORT}" height="{VIEWPORT}"',
            f'        rx="{CORNER_RADIUS}" fill="{background}" />',
            f'    <rect x="{CROP + 0.5}" y="{CROP + 0.5}" width="{VIEWPORT - 1}"',
            f'        height="{VIEWPORT - 1}" rx="{CORNER_RADIUS - 0.5}"',
            f'        fill="none" stroke="{BORDER}" stroke-width="1" />',
            f'    <g{transform} fill="{fill}">',
            *paths,
            "    </g>",
            "</svg>",
            "",
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if the SVG on disk is out of date, or if the two "
        "launcher foregrounds are not one mark",
    )
    arguments = parser.parse_args()

    rendered = build_svg()
    output = REPO_ROOT / OUTPUT

    # Both modes, and before anything else is looked at: the two foregrounds are the
    # repository's files rather than this script's output, and a generator that
    # quietly rewrote the README for a repository whose two icons had stopped being
    # the same mark is how that disagreement gets committed. The reminder lands where
    # it is wanted — on whoever has just edited the mark in one of the two files.
    problems = light_ink_problems()
    if problems:
        for problem in problems:
            print(f"FAIL {problem}")
        print(
            "FAIL — the white icon and the black one are not the same mark; neither "
            "the SVG nor anything else is written until they are"
        )
        return 1

    # The third copy of the mark: the two screens that draw the app's logo in-app.
    # Checked in both modes, for the same reason as the two foregrounds — a reader
    # who taps the icon and lands on a different figure has been shown two apps.
    wordmark = wordmark_problems()
    if wordmark:
        for problem in wordmark:
            print(f"FAIL {problem}")
        print("FAIL — the in-app logo is not the launcher icon; nothing is written")
        return 1
    print("ok   the in-app wordmark is the launcher icon's drawing, moved to its box")

    if arguments.check:
        if not output.is_file():
            print(f"FAIL {OUTPUT} is missing — the README shows no icon without it")
            return 1
        # Compared as *bytes*, not as text: `read_text` would fold a CRLF checkout
        # into LF and pass, and the repository stores text with LF
        # (`.gitattributes`), so a line-ending change to this file is drift like
        # any other.
        if output.read_bytes() != rendered.encode("utf-8"):
            print(
                f"FAIL {OUTPUT} does not match {FOREGROUND} and {COLORS}.\n"
                "The README would show an icon the app does not have. Regenerate it:\n"
                "    python tools/render-icon.py"
            )
            return 1
        print("ok   the white icon is the black one's mark, in its own ink")
        print(f"ok   {OUTPUT} matches the launcher icon")
        return 0

    output.parent.mkdir(parents=True, exist_ok=True)
    # `newline="\n"` rather than the platform's: this file is checked in, and a
    # generated file that differs by line ending on Windows is a diff nobody meant
    # to make.
    with open(output, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(rendered)
    print(f"wrote {OUTPUT} ({len(rendered)} bytes) from {SHIPPED_FOREGROUND}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
