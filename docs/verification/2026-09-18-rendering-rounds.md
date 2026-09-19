# The 2026-09-18 rounds: Markdown, formulas and crashes

*Part of [VERIFICATION.md](../VERIFICATION.md). Five rounds from one day, kept whole because the numbers in them are the evidence a later claim rests on.*

## The Markdown and formula round (2026-09-18)

The instrument was a synthetic session file in pi's own format (a
`{"type":"session","version":3,…}` header, then `message` entries linked by `id`/`parentId`), eight
turns of five formula paragraphs, five display formulas, five three-column tables with formulas in the
cells and a fenced code block each, opened from the history list; it is not committed, because a
fixture pi can open is a fixture that gets stale.

It settled, from the accessibility tree: **a formula inside a table cell renders** — the node
`fraction: n + 1 over 2` appears where the source (`$\frac{n+1}{2}$`) no longer does, which is
"表格等内容里的公式为什么不会渲染" answered — and inline and display blocks render too, the display one a
`Text` node of its own. The code block is monospace and uncoloured, one text node. A message carries a
copy button (`复制消息`) and a date-and-time stamp. The command sheet's seven built-ins plus `!` work:
`uname` from the `!` row appended a real
`{"role":"bashExecution","command":"uname","output":"Linux\n","exitCode":0}` record. The composer's row
is three 40dp buttons with `常用指令` immediately left of the `+`.

It did **not** settle four things:

- **The performance claim had no end-to-end number**; the next round measured `layout=305.9 ms` — a
  Compose problem and not a `swiftshader` one.
- **Selection across a display formula was verified by reading, not dragging**, a gesture over a
  `SelectionContainer` being what `uiautomator` cannot make.
- **The camera entry has not been exercised.** `Take a photo` is a `PickerOption` whose tap launches
  `ActivityResultContracts.TakePicture` against a `FileProvider` URI in the app's cache; the round trip
  into the composer and the permission refusal path are unverified.
- **The `!` prefix route was not exercised, a limit of the instrument rather than a finding**:
  `adb shell input text` will not deliver an exclamation mark to a Compose text field here. Both routes
  call the same function — the sheet `PiAgentSession.runBash(line.trim())` and `ChatScreen`'s `onSend`
  `runBash(prompt.removePrefix("!").trim())` — so a device pass would settle it.

## The session that opened slowly (2026-09-18, later still)

A temporary frame-metrics probe (`TOTAL_DURATION`, `LAYOUT_MEASURE_DURATION`, `DRAW_DURATION`,
`GPU_DURATION`, every frame over 32 ms) ran against the same session, grown to twelve turns:

```
total=835.4  layout=305.9  draw=25.6  gpu=67.3     the frame that opened the conversation
total=923.3  layout=218.4  draw=14.9  gpu=59.5     the same frame after MarkdownText went lazy
```

**305.9 ms in the measure/layout pass, for one conversation** — "打开含有许多公式的历史对话会非常卡" —
and a Compose problem rather than a renderer one: the GPU took 67 ms of the same frame, which on
`swiftshader` is not the bottleneck. The cause was structural: a whole reply is **one item** of the
transcript's `LazyColumn`, so the list had to measure *all* of the item's blocks — a plain `Column` —
to learn its height, including those six screens down. `MarkdownText` was made a `LazyColumn` of its
own, one item per block, and **`layout` fell from 305.9 ms to 218.4 ms, a 29% cut**. **That
`LazyColumn` has since been reverted, because it was a crash**: `MarkdownText` is also drawn inside
`ManualPage`'s `SettingsBody`, a `Column(verticalScroll)`, and inside the file preview, where a
scrollable inside a scrollable gets infinite height on the main axis (ARCHITECTURE §12). The numbers
are the size of the problem, not a description of the current code. What the round does **not** claim
is the steady-state scroll: no scroll-phase frame reached the probe's threshold, and `dumpsys gfxinfo`
reports a 34 ms median that describes `swiftshader` rather than the change.

One fact from that round corrects what this document claimed for one revision: **the prompt bubble's
bug was its width, not its alignment.** The `0.86f` cap travelled through Material3's `Surface` — which
puts its content in a `Box(propagateMinConstraints = true)` — into the bubble's `Column`, so every
prompt drew an 86%-wide bar: "气泡是固定宽度" and "用户消息靠左了" were the same bar. `Alignment.End` was
never wrong; it is `BiasAlignment.Horizontal(1f)`, i.e. `space - size`, and `Alignment.CenterEnd` does
not compile as `wrapContentWidth`'s argument at all (ARCHITECTURE §12, `AGENTS.md`).

## The crashes, the dead taps and the blank glyph (2026-09-18, last round of the day)

**The emulator was running on `swiftshader`, and its GPU context was timing out.** The window read
`inputConfig=NOT_VISIBLE, alpha=0` in `dumpsys input` while `dumpsys window` called it visible and
fullscreen, `uiautomator dump` returned a correct tree, and every `input tap` was rejected with
`InputDispatcher: No new touched window`. `dumpsys gfxinfo` named the cause: `GPU Context timeout: 10`
and two frames in the GPU histogram at **4950 ms** — software rasterisation had stopped completing
frames, so the window never finished its first draw. `-gpu host` fixed it outright, same AVD, same
APK. **Eleven rounds of "unverified because touch injection is broken" were the graphics backend, not
the app and not the instrument.**

With a working screen (Medium_Phone AVD, Android 36.1, `x86_64`), the round's reports verified: the
composer focuses; `</>` draws its two chevrons and slash; 使用手册 renders end to end (twelve full-page
swipes, no `FATAL`) and so does a saved conversation; `$\frac{a}{b}$` and `$x^{2}+y^{2}=z^{2}$`
typeset inside table cells; all fifteen display formulas of a reply render, the off-screen ones
measured only as they arrive; the command sheet shows exactly eight rows — `!`, `/new`, `/compact`,
`/stop`, `/clone`, `/export`, `/model`, `/clear`, with **no `/websearch`, `/curator`, `/google-account`,
`/search` or `/llama`**. The bubble was still wrong: `ok` measured 842px wide for two characters —
exactly the cap — because a `Modifier.fillMaxWidth()` *on the `Surface`* sets the **minimum** width to
the incoming maximum, and removing it is the fix.

**The performance reading, on the working backend.** Opening the formula-heavy session (26 messages,
fifteen display formulas in one reply) and scrolling it up and down sixteen times:

```
open:      Total frames 119  Janky 15 (12.6%)  50th 23ms  90th 34ms   99th 150ms
scrolling: Total frames 201  Janky 55 (27.4%)  50th 28ms  90th 46ms   99th 450ms
```

The 99th percentile is the one to read — 150 ms is the frame that opens the conversation and 450 ms a
formula arriving into view — and **this is a `-gpu host` emulator on a laptop, so the absolute frames
are not a phone's**; the numbers support only that opening a formula-heavy conversation is not a
multi-second stall and scrolling one does not stutter repeatedly.

**Still not verified, and not by this instrument: text *selection* across a display formula**, the
whole point of the change that made a display formula a `Text`. `adb shell input swipe` does not raise
Compose's selection toolbar here; the accessibility tree shows a display formula publishing its own
semantics (`content-desc="fraction: 1 over 2"`), consistent with it being inside the text rather than
beside it, which is not the same as a selection working. A manual check on a device settles it.

## The second round of the same three reports (2026-09-18, after the GPU fix)

The numbers are in ARCHITECTURE §12. `</>` 中间斜杠和两边括号连着了: the first two revisions overlapped
a bracket by 2.2 and 0.3 units at 100 px/unit, and the shipped one clears by 1.36 left and 1.16 right,
rendering at 256, 96, 48 and 28 px. 行内代码背景无圆角: the span draws with a rounded fill — `SpanStyle`
has no radius and ui-text 1.10 has no `BackgroundStyle`, so it is measured content now. 单独成段的公式
仍无法选中复制: both display formulas render, centred, each a `Text` node whose semantics carry the
formula (see the selection gap above). markdown 和 latex 还是特别卡: compose+measure **807 ms** with one
measurer per block against **741 ms** with one per reply — **not fixed, improved**. 思考等级 chip 每次
打开都从 medium 跳到 high: `thinking_levels_for=deepseek-flash` and `thinking_levels=off,high,max`
*were* persisted in `shared_prefs/pikit_settings.xml`, and the lookup by `state.model?.id` was the null
key, so the fallback to `saved.modelId` is the fix.

**Two regressions this round produced and caught by looking at a screenshot rather than by a test.**
The first version of the formula-as-text change drew every display formula as its literal LaTeX
source, because `MdBlock.Formula` holds the body with its delimiters stripped and the inline path
decides what is mathematics *by looking for a delimiter*; the delimiters are put back now. And a
self-intersecting bracket path filled as a solid wedge, a 3.0-unit overlap the icon script's row-by-row
mask comparison reported. Neither would have failed the JVM suite.

## The third round: the icon, and the scroll that is still slow (2026-09-18, latest)

**The icon: this round's verdict was right, its conclusion was overtaken.** The rejection of the four
hand-drawn replacements — "字体都变了，一点都不标准，保持原字体才行" — is the correct verdict on all of
them, and the collision three of them were fighting (the slash crossing the chevron) is something the
real glyph deliberately does. The diagnosis that started it — that Material3's `Icon` zeroes a stroked
vector's `strokeLineWidth` — was false (ARCHITECTURE §12), and what ships is **not** `Icons.Filled.Code`
either: the button is Lucide's `command` (⌘) in `ic_commands.xml`.

**The scroll: one real improvement, measured, and one approach ruled out by a crash.**

| | Janky frames | 50th | 90th | 99th |
| --- | --- | --- | --- | --- |
| 300-block reply, before this round | 33 (11.5%) | 24 ms | 34 ms | 600 ms |
| …with off-screen formulas deferred behind a one-line `Spacer` | **37 (8.6%)** | 26 ms | 32 ms | 850 ms |

The deferral drew a `Spacer` until a formula was inside the window, `onGloballyPositioned` deciding and
`remember` keeping a measured formula measured; the jank percentage fell and the 99th percentile did
not, and the design was removed again (ARCHITECTURE §12). **The honest reading stands: the frame that
opens a 300-block reply is still around 800 ms.** **Making `MarkdownText` a lazy list was tried again
and threw again** — `IllegalStateException: Vertically scrollable component was measured with an
infinity maximum height constraints` — because the transcript does **not** give an answer item a
bounded height: the item is wrapped in a `SelectionContainer` and the bubble, so the previous round's
premise ("the outer list gives this inner one a bounded height") is wrong for *this* call site too
(`AGENTS.md`).

That leaves the remaining work on scroll named rather than done: a reply is one item of the transcript's
list and the list must know that item's height, so the reply's blocks cannot be measured lazily from
inside. **The fix that would work is to make the reply several items of the transcript's list** —
chunking it where the list is built — a change to `ChatScreen.kt`'s transcript rather than to
`MarkdownText`, and it is not made here.
