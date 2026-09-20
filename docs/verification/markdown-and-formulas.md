# Markdown and formulas

*[Verification](../VERIFICATION.md): what the parser and the renderer draw, the delimiter
rule, and the constructs that are rewritten or given up on. The renderer's own cost is
[the formula renderer](formula-renderer.md).*

## The instrument

A synthetic session file in pi's own format — a `{"type":"session","version":3,…}` header, then
`message` entries linked by `id`/`parentId` — eight turns of five formula paragraphs, five display
formulas, five three-column tables with formulas in the cells and a fenced code block each, opened
from the history list. It is not committed, because a fixture pi can open is a fixture that gets
stale.

## What renders

From the accessibility tree: **a formula inside a table cell renders** — the node
`fraction: n + 1 over 2` appears where the source (`$\frac{n+1}{2}$`) no longer does — and inline
and display blocks render too, the display one a `Text` node of its own. The code block is monospace
and uncoloured, one text node. A message carries a copy button (`复制消息`) and a date-and-time
stamp.

Measured the same way on the emulator: `</>` draws its two chevrons and slash; 使用手册 renders end
to end (twelve full-page swipes, no `FATAL`) and so does a saved conversation; `$\frac{a}{b}$` and
`$x^{2}+y^{2}=z^{2}$` typeset inside table cells; all fifteen display formulas of a reply render,
the off-screen ones measured only as they arrive.

## A regression only a screenshot caught

The first version of the formula-as-text change drew every display formula as its literal LaTeX
source, because `MdBlock.Formula` holds the body with its delimiters stripped and the inline path
decides what is mathematics *by looking for a delimiter*. The delimiters are put back now. It would
not have failed the JVM suite; the glyph regression found in the same sweep is in
[Marks and colours](marks-and-colours.md).

## The delimiter rule: a formula padded with spaces

A formula written as
`$P(A_i\mid B)=\dfrac{P(A_i)P(B\mid A_i)}{\sum_j P(A_j)P(B\mid A_j)} $` was drawn as its own source
and wrapped like prose. Six variants of it separated the cause:

| variant | before the fix |
| --- | --- |
| `$P(A_i\mid B)=1$` | formula |
| `$ P(A_i\mid B)=1 $` | **source** |
| `$P(A_i\mid B)=1 $` | **source** |
| `$ P(A_i\mid B)=1$` | **source** |
| a newline inside the delimiters | formula (the parser had already joined the lines) |
| `it costs $5 and $10 today` | prose, correctly |

The rule that keeps a price a price — no space just inside either `$` — was rejecting every formula
a model pads, which is how TeX is written. It now rejects a padded body only when the body does
*not* look like mathematics: a `\command`, a script, a group or a relation. All four padded variants
render afterwards, and `it costs $ 5 and $ 10` is still a sentence. `MarkdownMathTest`'s "a formula
padded with spaces is mathematics when it looks like mathematics" is the case that fails without it,
and the case beside it is the price that must not become one.

## `\[…\]` inside a sentence

`\[E = mc^{2}\]` inside a sentence was drawn with its brackets, and then as its source: the
tokeniser read `\[…\]` only line-leading, and `unescapeMath` — which stripped `\(…\)`, `$$` and `$`
but not `\[…\]` — left the brackets for the renderer, which refused them. Both halves are fixed; the
first alone looked like it had not worked. (`\(…\)` was already handled, so the first attempt at the
tokeniser half was dead code and was reverted.)

## A formula wider than its box

A display formula wider than the screen scrolls and gets a gradient at whichever edge has more to
show plus a pill along the bottom reporting how much of it is on screen and how far it has been
scrolled. An *inline* formula wider than its line cannot scroll, so it is scaled down uniformly to fit
with a floor of half size; a 1204 px formula in a 1080 px line was measured drawn at `scale=0.897`
and complete.

## What the renderer refuses, and what is rewritten instead

Eight constructs out of forty fall back to their source — `\ce`, `\color`, `\tag`, `\label`,
`\cancel`, `\hcancel`, `\oiint`, `\oiiint` — with the body of every failure logged.
`LatexCompat.kt` rewrites the first six: it drops numbering, unwraps decorations, renames `\ce` to
`\text` and `\color` to `\textcolor`, and aliases `color` in JLaTeXMath's own (public)
`MacroInfo.Commands` table. Only the two glyphs the fonts do not have still fall back to their
source.

The instrument is the 42-construct sweep `node tools/formula-sweep.mjs`, whose constructs are listed
in the file and whose `logcat` lines read `formula not typeset`;
[The release build's dex](release-build.md) has the same sweep's result for each APK.

## What is not covered here

Text *selection* across a display formula — the point of making a display formula a `Text` — is
exercised by no instrument on the emulator: `adb shell input swipe` does not raise Compose's
selection toolbar. The accessibility tree shows a display formula publishing its own semantics
(`content-desc="fraction: 1 over 2"`), which is consistent with the formula being inside the text
rather than beside it, and is not the same as a selection working. It is listed among the
[known gaps](known-gaps.md), with the other checks that need a person.
