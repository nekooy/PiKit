# The 2026-09-19 rounds: formulas and the copy mark

*Part of [VERIFICATION.md](../VERIFICATION.md). The padded formula, the over-wide formula, and the copy mark's four rounds.*

## The padded formula, the wide formula, and the copy mark (2026-09-19, last round of the day)

**"这种换行的仍然没渲染" was a delimiter rule, not a wrapping bug.** The formula in the screenshot —
`$P(A_i\mid B)=\dfrac{P(A_i)P(B\mid A_i)}{\sum_j P(A_j)P(B\mid A_j)} $` — rendered as its own source
and wrapped like prose. Six variants of it on the emulator separated the cause:

| variant | before |
| --- | --- |
| `$P(A_i\mid B)=1$` | formula |
| `$ P(A_i\mid B)=1 $` | **source** |
| `$P(A_i\mid B)=1 $` | **source** |
| `$ P(A_i\mid B)=1$` | **source** |
| a newline inside the delimiters | formula (the parser had already joined the lines) |
| `it costs $5 and $10 today` | prose, correctly |

The rule that keeps a price a price — no space just inside either `$` — was rejecting every formula a
model pads, which is how TeX is written. It now rejects a padded body only when the body does *not*
look like mathematics: a `\command`, a script, a group or a relation. All four padded variants render
afterwards, and "it costs $ 5 and $ 10" is still a sentence. `MarkdownMathTest`'s "a formula padded
with spaces is mathematics when it looks like mathematics" is the case that fails without it, and the
case beside it is the price that must not become one.

**`\[…\]` inside a sentence** ("由 \[E = mc^{2}\] 可知") was drawn with its brackets, because
`unescapeMath` did not strip `\[`/`\]` even once the tokeniser produced the token. Both halves fixed,
which is why the first alone looked like it had not worked.

**"无明显暗示能够滑动查看完整内容" is now drawn.** A display formula wider than the screen scrolls and gets
a gradient at whichever edge has more to show plus a pill along the bottom reporting how much of it is
on screen and where the reader is in it. An *inline* formula wider than its line cannot scroll, so it is
scaled down uniformly to fit with a floor of half size; a 1204px formula in a 1080px line was measured
drawn at `scale=0.897` and complete.

**The copy mark: the question was the glyph, not the button.** Six *containers* were rendered at 1× and
2.5× in both places the control appears (under a message, on a code block's header) and offered; the
answer was a picture of a glyph — two rounded sheets — with no container. The button is now Lucide's
`copy` at 18dp with nothing behind it, and Lucide's `check` for a moment after a tap (ARCHITECTURE §12
has the two remarks on the way, because the wrong question was "what shape should the button be").

**And 拍照 moved above 图片 in the attach sheet** — a one-line reorder, because the row the sheet exists
for should not be the third one.

## The copy mark's path, and the release's asset names (2026-09-19, last of the day)

**The icon was wrong, and the reader found it by opening the source.** `ic_copy.xml` was supposed to be
an SVG they supplied, and the first version split its single path into two elements — turning the third
subpath's **relative** move into an absolute one by hand: `m170.666666 -256` is relative to where the
*second* subpath ended (618.666667, 362.666667), not to where the first began, so the back sheet was
written at y=21.333 instead of y=106.667 — up by **85.333 units** — and the two sheets sat 256 apart
instead of 170.667, which is the "瑕疵" the reader saw. The fix copies the path **verbatim**, relative
moves and all, in one `<path>`, changing only the fill colour and `fillType`;
`.tooling/copy-svg-check.py` compares the two strings and reports `identical: True` (771 characters
each) — a path is data, and a conversion that cannot be avoided is arithmetic, not judgement.

**The published APKs were named after Gradle's output.** `gh release create` attached
`app-arm64-release.apk` and `app-x64-release.apk` — names that say neither the application nor the
version. The build job now renames them in place, after the signing check and before the checksums and
the artifact, so the release carries **`PiKit-<version>-arm64.apk`** and
**`PiKit-<version>-x64.apk`**, and `SHA256SUMS` lines up with them. `docs/RELEASING.md` records the
rule and the post-release check.

**The two copy buttons were not the same colour, and one call site was to blame.** Under a message the
meta row asked for `onSurfaceVariant` explicitly; the code block's header took `CopyButton`'s default,
`LocalContentColor.current` — whatever the bubble's `Surface` set, `onSurface` — near-black in the light
theme against the grey the reader had just seen ("代码块边上的复制按钮颜色太深了"). The default is now
`MaterialTheme.colorScheme.onSurfaceVariant`, named in `CopyButton` rather than inherited, and the
message call site passes no colour at all: one action, one colour.

**The turn's furniture is the accent colour now, and the code block's copy button is not.**
"把工作几秒、几个步骤、消息时间、消息复制按钮等都变成主题蓝色，代码块复制按钮不变":

| where | was | now |
| --- | --- | --- |
| `工作 X 秒 · N 个步骤` (`TurnSummaryRow`) | `onSurfaceVariant`, with a `primary` chevron | `primary`, chevron unchanged |
| a message's timestamp (`MessageMeta`) | `onSurfaceVariant` | `primary` |
| the message's copy button | `onSurfaceVariant` | `primary` |
| the code block's copy button | `LocalContentColor` → `onSurface`, then `onSurfaceVariant` | `onSurfaceVariant` (unchanged in effect) |

`MessageMeta`'s `color` parameter is gone — both call sites passed the same value, so the row reads the
accent itself — and the note that the chevron was "the one coloured mark among the page's three
disclosures" is corrected, since its label is the same colour now.
