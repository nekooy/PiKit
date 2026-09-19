"""Reflow the Kotlin raw-string manual bodies to a phone-width column.

The manual is rendered on a phone, so a hand-wrapped line longer than the screen
wraps again — into a two-line mess. This script re-wraps every paragraph, bullet
and numbered item in the three manual bodies to a display width, counting a CJK
glyph as two columns, and leaves headings, tables, code fences, list markers and
blank lines alone.

It expects paragraphs on one line — write the manual unwrapped and let this do
the wrapping. Running it over an already-wrapped body is then a no-op: it
re-joins each paragraph first (`rejoin`), and `join_wrapped` puts the break back
without inventing a space inside a run of CJK glyphs, which is what a second run
used to leave behind. Hand-edited line breaks inside a paragraph are still not
preserved; `--check` reports a body that is not a fixed point of this pass, so an
edit that was never reflowed is caught rather than silently left too wide.

Run from the repository root:

    python tools/reflow-manual.py             # rewrite the two files
    python tools/reflow-manual.py --check     # report over-long lines only
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FILES = (
    REPO_ROOT / "app/src/main/java/pi/kit/mob/locales/ManualText.kt",
    REPO_ROOT / "app/src/main/java/pi/kit/mob/locales/ManualTextTranslated.kt",
)
#: The terminal and the manual share a screen about this wide.
WIDTH = 48

#: Kinsoku: punctuation that may not begin a line, and punctuation that may not
#: end one. Without this a CJK paragraph breaks before a full stop or after an
#: opening bracket, which reads as a typo rather than as wrapping.
NO_LINE_START = set("、。，．：；！？）」』】〕》〉｝”’…—・")
NO_LINE_END = set("（「『【〔《〈｛“‘")


def display_width(text: str) -> int:
    """Columns [text] occupies, counting East Asian wide glyphs as two."""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def is_cjk(ch: str) -> bool:
    return unicodedata.east_asian_width(ch) in "WF"


#: Punctuation that is neither a CJK glyph nor a word character, so a space
#: beside it is deliberate (a dash, a slash, a bracket).
def is_punctuation(ch: str) -> bool:
    return unicodedata.category(ch).startswith("P") and not is_cjk(ch)


#: A space that got left inside a CJK word by an earlier wrap: between two CJK
#: glyphs. It is never deliberate — those scripts do not use word spaces — and
#: it shows up as a visible gap in the middle of a sentence. A space between a
#: CJK glyph and a Latin word is left alone: it is a real boundary, and removing
#: it would run "用这个 Key" together.
INTRUSIVE_SPACE = re.compile(
    r"(?<=[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef])\s+(?=[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef])"
)


def tighten(text: str) -> str:
    """Remove spaces that a previous wrap left inside a run of CJK glyphs."""
    return INTRUSIVE_SPACE.sub("", text)


def join_wrapped(current: str, piece: str) -> str:
    """
    Rejoins a line the wrap had broken, putting back only the space it removed.

    A space between two CJK glyphs is never deliberate — those scripts do not use
    word spaces, and one there reads as a typo. Inserting it unconditionally is
    what made a *second* run over an already-wrapped body different from the
    first: the Chinese and Japanese bodies gained a visible gap at every line
    break the pass undid ("已 安装的软件包", "し て並びます"), and since the tool
    re-joins before it re-wraps, running it twice was not the same as running it
    once. Everywhere else the space is the word boundary the wrap removed, and it
    goes back.

    A markdown emphasis delimiter beside a CJK glyph is the same case one step
    further out: `设置` + `**。` is one word broken at the delimiter, and a space
    there put a visible gap between the bold run and the full stop it belongs to
    (`**设置 **。`), which this app's own manual had in all three bodies.

    CJK punctuation takes no space either, whatever is beside it: the gap a break
    after `、` or `——` produced showed up inside an enumeration
    (`` `ESC`、`TAB`、 `C-C` ``) and before a clause.
    """
    current = current.rstrip()
    if not current:
        return piece
    if is_cjk_punct(current[-1]) or is_cjk_punct(piece[0]):
        return current + piece
    if is_cjk(current[-1]) and is_cjk(piece[0]):
        return current + piece
    if "*" in (current[-1], piece[0]) and (is_cjk(current[-1]) or is_cjk(piece[0])):
        return current + piece
    return current + " " + piece


#: CJK punctuation, by the ranges that are marks rather than letters or digits.
#: The fullwidth block's letters (`Ａ`) and digits (`１`) are words, and the
#: renderer's own rule (`needsSpaceBetween` in `Markdown.kt`) lists exactly these.
CJK_PUNCTUATION = (
    (0x3000, 0x3002),
    (0x3008, 0x3011),
    (0x3014, 0x301F),
    (0x2014, 0x2014),
    (0x2026, 0x2026),
    (0xFF01, 0xFF0F),
    (0xFF1A, 0xFF20),
    (0xFF3B, 0xFF40),
    (0xFF5B, 0xFF65),
)


def is_cjk_punct(ch: str) -> bool:
    return any(low <= ord(ch) <= high for low, high in CJK_PUNCTUATION)


def tokenize(text: str) -> list[str]:
    """Split into wrappable tokens: CJK glyphs break anywhere, Latin words do not.

    A run of `*` is glued to whichever neighbour it touches, so a break never
    lands between an emphasis delimiter and the word it opens or closes. Without
    that, the Chinese body came out as `**设置` / `**。`, which the renderer drew
    as bold text with a stray space inside it.
    """
    tokens: list[str] = []
    current = ""
    for ch in text:
        if ch == " " or is_cjk(ch):
            if current:
                tokens.append(current)
                current = ""
            tokens.append(ch)
        else:
            current += ch
    if current:
        tokens.append(current)
    return glue_delimiters(tokens)


def glue_delimiters(tokens: list[str]) -> list[str]:
    """
    Attaches every all-`*` token to a neighbour, so emphasis never splits.

    A run is attached to the *following* word when there is one, and to the
    preceding token only when what follows cannot carry it — a space, or a CJK
    closing mark that may not start a line. So `**拒绝也没关系**，` comes out as
    `**拒` + the glyphs + `系**` + `，`: an opening delimiter can never be orphaned
    at the end of a line and a closing one can never be orphaned at the start.
    """
    out: list[str] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token and set(token) == {"*"}:
            following = tokens[index + 1] if index + 1 < len(tokens) else None
            if following and following != " " and following[0] not in NO_LINE_START:
                out.append(token + following)
                index += 2
                continue
            if out and out[-1] != " ":
                out[-1] += token
                index += 1
                continue
        out.append(token)
        index += 1
    return out


def wrap_line(line: str, width: int, lookahead: bool = True) -> list[str]:
    """Greedy wrap of one line, which may start with a list marker.

    With `lookahead`, a word that would overflow is checked against the width
    left on the *current* line: if the next word is narrower, the line breaks one
    word early rather than leaving an orphan on a line of its own. Without it the
    prose develops stray single words ("Its", "not", "yet"), which read as
    mistakes on a phone where the whole paragraph is visible at once.

    A single token wider than the line — a URL or a long path — is emitted on its
    own line rather than split; that is the one case that may exceed the column.
    """
    tokens = tokenize(line)
    out: list[str] = []
    current = ""
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token == " ":
            # Never at the start of a line: a break that put us here removed the
            # space, and re-emitting it gave the wrapped body a leading blank on
            # whichever line the lookahead below broke early on — which is also
            # what stopped an already-wrapped body from being its own fixed point.
            if current and index + 1 < len(tokens):
                current += " "
            index += 1
            continue
        if not current and display_width(token) > width:
            out.append(token)
            index += 1
            continue
        candidate = current + token
        if display_width(candidate) <= width or not current:
            current = candidate
            index += 1
            continue
        # The mark hangs past the column, which is what a CJK typesetter does.
        if token in NO_LINE_START or (current and current[-1] in NO_LINE_END):
            out.append(candidate)
            current = ""
            index += 1
            continue
        following = tokens[index + 1] if index + 1 < len(tokens) else None
        if (
            lookahead
            and following not in (None, " ")
            and display_width(current + following) <= width
        ):
            out.append(current.rstrip())
            current = ""
            continue
        out.append(current.rstrip())
        current = token
        index += 1
    if current.strip():
        out.append(current.rstrip())
    return out or [""]


def marker_of(stripped: str) -> str:
    """The list marker at the start of [stripped], or the empty string."""
    for prefix in ("- ", "* "):
        if stripped.startswith(prefix):
            return prefix
    digits = ""
    for ch in stripped:
        if ch.isdigit() or ch == ".":
            digits += ch
        else:
            break
    if digits.endswith(". ") or digits.endswith(") "):
        return digits
    return ""


def is_verbatim(line: str) -> bool:
    """Lines whose exact shape has to survive: headings, tables, code, quotes."""
    stripped = line.strip()
    return (
        stripped.startswith("#")
        or stripped.startswith("```")
        or stripped.count("|") >= 2
        or stripped.startswith(">")
    )


#: A list marker that appears *inside* a paragraph line, which happens when a
#: numbered list is unwrapped: `... a label. 2. Provider ...`. Without splitting
#: there, the rejoin runs the whole list together into one paragraph, and the
#: manual loses its numbering. The lookbehind covers both a Latin full stop and
#: a CJK one, because the latter is not followed by a space.
EMBEDDED_MARKER = re.compile(r"(?<=[.。!！?？:：;；])\s*((?:\d{1,2}[.)]|[-*])\s)")


def split_embedded_markers(text: str) -> list[str]:
    """Break a line at any list marker that ended up inside it."""
    parts: list[str] = []
    rest = text
    while True:
        match = EMBEDDED_MARKER.search(rest)
        if match is None:
            break
        parts.append(rest[: match.start()].strip())
        rest = rest[match.start():].strip()
    parts.append(rest.strip())
    return [part for part in parts if part]


def rejoin(lines: list[str], width: int = WIDTH) -> list[str]:
    """Join hard-wrapped paragraph and bullet lines, keeping list items apart.

    A continuation is any line that does not start with a list marker; a line
    that does starts a new item. A marker that has ended up *inside* a run — as
    it does when an unwrapped numbered list is reflowed — is split back out, but
    only when the run is long enough that it cannot be one item's own text: a
    sentence like "step 2. Run it" must not become a list item.
    """
    out: list[str] = []
    current = ""
    for line in lines:
        if is_verbatim(line):
            if current:
                out.append(current)
                current = ""
            out.append(line)
            continue
        stripped = line.strip()
        if not stripped:
            if current:
                out.append(current)
                current = ""
            out.append("")
            continue
        pieces = [tighten(stripped)]
        if display_width(stripped) > width:
            pieces = [tighten(piece) for piece in split_embedded_markers(stripped)]
        for piece in pieces:
            if marker_of(piece):
                if current:
                    out.append(current)
                current = piece
            elif current:
                current = join_wrapped(current, piece)
            else:
                current = piece
    if current:
        out.append(current)
    return out


def reflow_lines(lines: list[str], width: int) -> list[str]:
    out: list[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            out.append("")
            continue
        if is_verbatim(line):
            out.append(line.rstrip())
            continue
        # An unwrapped numbered list arrives as one line ("... a label. 2. Pick
        # from the list. 3. Paste the key."). Split it before wrapping, or the
        # manual loses its numbering.
        for piece in split_embedded_markers(stripped):
            marker = marker_of(piece)
            body = piece[len(marker):]
            # The first line carries the marker within the column; the
            # continuation is indented to the marker's own width, so it wraps
            # that much earlier.
            cont_width = display_width(marker)
            parts = wrap_line(marker + body, width - cont_width)
            cont = " " * cont_width if marker else ""
            for position, part in enumerate(parts):
                out.append((cont if position else "") + part)
    return out


def reflow_body(body: str, width: int = WIDTH) -> str:
    return "\n".join(reflow_lines(rejoin(body.split("\n")), width))


def rewrite(text: str, width: int = WIDTH) -> str:
    """Apply [reflow_body] to every `internal val ... = \"\"\"...\"\"\".trimIndent()` body."""
    marker = '= """'
    result = text
    start = 0
    while True:
        begin = result.find(marker, start)
        if begin < 0:
            return result
        body_start = begin + len(marker) + 1
        end = result.find('""".trimIndent()', body_start)
        if end < 0:
            return result
        body = result[body_start:end]
        wrapped = reflow_body(body, width)
        result = result[:body_start] + wrapped + result[end:]
        start = body_start + len(wrapped)


def over_long(path: Path, width: int = WIDTH) -> list[tuple[int, int, str]]:
    """Lines that would wrap on the device, ignoring the two excusable cases.

    A token wider than the column — a URL, a path in inline code — cannot be
    broken and is emitted on a line of its own. And a CJK closing mark is never
    allowed to start a line, so it hangs a column or two past the edge; that is
    what a Chinese or Japanese typesetter does as well.
    """
    bad: list[tuple[int, int, str]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
        stripped = line.strip()
        if (
            not stripped
            or is_verbatim(line)
            or stripped.startswith(("/**", "/*", "*", "//"))
            or stripped.startswith(("package", "import", '"""', ")", "internal", "fun "))
        ):
            continue
        if any(display_width(token) > width for token in tokenize(stripped)):
            continue
        if stripped[-1] in NO_LINE_START:
            continue
        width_here = display_width(line.rstrip())
        if width_here > width:
            bad.append((number, width_here, line.rstrip()))
    return bad


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report over-long lines, change nothing")
    parser.add_argument("--width", type=int, default=WIDTH)
    args = parser.parse_args()

    if args.check:
        failed = False
        for path in FILES:
            for number, width, line in over_long(path, args.width):
                failed = True
                print(f"{path.name}:{number}: {width} columns: {line}")
            # A body that is not a fixed point of the wrapper has been edited and
            # not reflowed. Reported here rather than left to be noticed on a
            # phone: the edit usually introduces a line that wraps twice.
            original = path.read_text(encoding="utf-8")
            if rewrite(original, args.width) != original:
                failed = True
                print(f"{path.name}: not reflowed — run `python tools/reflow-manual.py`")
        return 1 if failed else 0

    for path in FILES:
        original = path.read_text(encoding="utf-8")
        updated = rewrite(original, args.width)
        if updated != original:
            # `newline="\n"` rather than the platform default: the manual bodies are
            # Kotlin string constants under the repository's `* text=auto eol=lf`
            # rule, and a text-mode write on Windows turned both files CRLF — a
            # whole-file diff, in a change that only moved a line break.
            with path.open("w", encoding="utf-8", newline="\n") as handle:
                handle.write(updated)
            print(f"reflowed {path.relative_to(REPO_ROOT)}")
        else:
            print(f"unchanged {path.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
