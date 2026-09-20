# Marks and colours

*[Verification](../VERIFICATION.md): the borrowed glyphs, the copy mark's attempts, the
colour the rows converged on, and an icon cache that is not a build.*

## The commands button

- **`</>`.** The first two revisions overlapped a bracket by 2.2 and 0.3 units at 100 px/unit, and
  the shipped one clears by 1.36 left and 1.16 right, rendering at 256, 96, 48 and 28 px. A later
  revision's self-intersecting bracket path filled as a solid wedge — a 3.0-unit overlap the icon
  script's row-by-row mask comparison reported — and neither defect would have failed the JVM suite.
- **Four hand-drawn replacements were rejected**: on every one of them the weight was wrong, and the
  collision three of them were fighting, the slash crossing the chevron, is something the real glyph
  deliberately does. The diagnosis that started that work — that Material3's `Icon` zeroes a stroked
  vector's `strokeLineWidth` — was false (ARCHITECTURE §12.3).
- **What ships is Lucide's `command` (⌘)** in `ic_commands.xml`, not `Icons.Filled.Code`. At the
  21 dp the Material glyphs beside it use it is a visibly heavier mark, because the figure reaches
  21 of its 24 units where Material's `Add` stays inside 14. It is drawn at **17 dp** at the call
  site, with the imported vector untouched.

## The copy mark: the question was the glyph, not the button

Six *containers* were rendered at 1× and 2.5× in both places the control appears — under a message,
on a code block's header — and offered; what settled it was a picture of a glyph, two rounded
sheets, with no container behind it. The button is now Lucide's `copy` at 18 dp with nothing behind
it, and Lucide's `check` for a moment after a tap (ARCHITECTURE §12.3 has the two remarks on the way,
because the wrong question was "what shape should the button be").

## The copy glyph's path

`ic_copy.xml` is one SVG path, and the first version split it into two elements — turning the third
subpath's **relative** move into an absolute one by hand: `m170.666666 -256` is relative to where
the *second* subpath ended (618.666667, 362.666667), not to where the first began, so the back sheet
was written at y=21.333 instead of y=106.667 — up by **85.333 units** — and the two sheets sat 256
apart instead of 170.667, which is the visible defect. The fix copies the path **verbatim**,
relative moves and all, in one `<path>`, changing only the fill colour and `fillType`; a
character-for-character comparison of the two path strings reports `identical: True` (771
characters each) — a path is data, and a conversion that cannot be avoided is arithmetic, not
judgement.

## The copy button under a message was 5 dp in from the message's own edge

It is not the row that was wrong but the *ink*: the button is 28 dp with an 18 dp glyph centred in
it, so the 5 dp that cannot be seen sits between the box the row aligns and the mark itself.
Measured with `uiautomator dump` at density 2.625, before and after pulling the row out by
`(28 − 18) / 2` dp:

| | message text | copy glyph | timestamp |
| --- | --- | --- | --- |
| before | x=32 | x=46 | x=111 |
| after | x=32 | x=33 | x=98 |

The glyph now lands on the message's own column (33 against 32, the remaining pixel being rounding),
and the timestamp kept its 18 px distance from the glyph. The inset is `CopyButtonInkInset`, derived
from the button's own two sizes rather than written down twice, and the row is pulled on whichever
side actually ends it: left for a left-aligned row, and right only when there is no timestamp to end
it instead.

## The colour the rows converged on

**The two copy buttons were not the same colour, and one call site was to blame.** Under a message
the meta row asked for `onSurfaceVariant` explicitly; the code block's header took `CopyButton`'s
default, `LocalContentColor.current` — whatever the bubble's `Surface` set, `onSurface` — near-black
in the light theme against the grey of the other. The default is now
`MaterialTheme.colorScheme.onSurfaceVariant`, named in `CopyButton` rather than inherited, and the
message call site passes no colour at all: one action, one colour.

**The turn's furniture is the accent colour now, and the code block's copy button is not:**

| where | was | now |
| --- | --- | --- |
| `工作 X 秒 · N 个步骤` (`TurnSummaryRow`) | `onSurfaceVariant`, with a `primary` chevron | `primary`, chevron unchanged |
| a message's timestamp (`MessageMeta`) | `onSurfaceVariant` | `primary` |
| the message's copy button | `onSurfaceVariant` | `primary` |
| the code block's copy button | `LocalContentColor` → `onSurface`, then `onSurfaceVariant` | `onSurfaceVariant` (unchanged in effect) |

`MessageMeta`'s `color` parameter is gone — both call sites passed the same value, so the row reads
the accent itself — and the note that the chevron was the one coloured mark among the page's three
disclosures is corrected, since its label is the same colour now.

## A stale icon on the device is not a stale icon in the build

An old amber/gold icon was seen twice — in the launcher and then in the agent notification — after
every drawable had been changed to black and white. Both were the device's own icon cache, which
survives an APK update. Telling the two apart takes a minute and no device: sweep the built APK for
the colour's bytes (`6bc4ffff`, and both endiannesses of it, across `res/`, `resources.arsc` and the
compiled XML) — the current build reports `none`. A launcher or shade icon that is still wrong after
an install is cleared by a reboot or a reinstall, not by a rebuild.
