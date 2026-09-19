# The 2026-09-19 round: the release APK's dex

*Part of [VERIFICATION.md](../VERIFICATION.md). What R8 removed from the release build that the debug one kept, and the checker that now fails the build over it.*

## The release APK typeset six constructs less than the debug one (2026-09-19, after the swap)

**The report was three formula blocks and a phone screenshot**: "我发现这三块公式只有第一块渲染正常了" —
`全概率`, `贝叶斯` and `合成一行`, of which the first drew and the other two were shown as their LaTeX
source. Reproducing it in the app with a stored session on the emulator rendered **the same three
bodies**; the difference was not the text but the APK — the emulator had the **debug** build, and the
reader installs the **release** one, the only build R8 runs on. The failure is logged, and the log
names the cause rather than the symptom:

```
W PiKit: formula not typeset, drawn as its source: \dfrac{1}{2}
W PiKit: java.lang.NullPointerException: Attempt to invoke virtual method
         'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object,
         java.lang.Object[])' on a null object reference
```

JLaTeXMath resolves each macro entry point by name at run time (`Class.forName`, `getDeclaredMethod`,
`getDeclaredField` in `MacroInfo`/`TeXFormulaParser`), so R8 cannot see the use, deletes the members,
and the reflective lookup returns null. The same 42-construct sweep — reproducible with
`node tools/formula-sweep.mjs`, its constructs listed in the file — gave, from `logcat`'s
`formula not typeset` lines:

| APK | constructs that fell back to their source |
| --- | --- |
| `app-x64-debug.apk` | `\oiint`, `\oiiint` (both absent from the library's fonts — the documented limit) |
| `app-x64-release.apk`, before the fix | `\dfrac`, `\tfrac`, `\left\{\begin{matrix}…\right.`, `\begin{align}`, `\operatorname`, `\substack`, `\oiint`, `\oiiint` |
| `app-x64-release.apk`, after the fix | `\oiint`, `\oiiint` — identical to debug |

The fix is `-keep class org.scilab.forge.jlatexmath.** { *; }` in `app/proguard-rules.pro`: the library
is 330 KB of classes against a 107 MB APK, it reaches **218** `*_macro` entry points this way, and a
narrower rule would be a guess about which of its reflective paths matter. The release APK grew by
**32 KB** (107,231,914 → 107,264,682 bytes) for it.

**The checker was tested against the failure it is for, not only against success.**
`tools/check-release-math.py` compares every release APK's dex against the `*_macro` names the
library's own AAR defines (218 of them, read from the AAR's constant pools — the AAR rather than a
debug APK, because `release.yml` builds `--release-only`, where there is no debug APK). Run while the
arm64 release APK on disk was still the pre-fix build:

```
FAIL app\build\outputs\apk\arm64\release\app-arm64-release.apk: 218 of the library's 218 macro
     entry points are gone — the release build will draw those formulas as their LaTeX source
ok   app\build\outputs\apk\x64\release\app-x64-release.apk: all 218 macro entry points are in the dex
FAIL 1 of 2 release APK(s) cannot typeset everything the library defines
```

`tools/build-apks.py` runs it as its last step, after the APKs are built — the one checker that cannot
be in its `checks` list, since those run before anything is packaged.

**The copy button under an agent message was 5dp in from the message's own edge**: "对话页ai消息回复
气泡下面复制按钮最左侧没对齐气泡". It is not the row that was wrong but the *ink*: the button is 28dp
with an 18dp glyph centred in it, so the 5dp the reader cannot see sits between the box the row aligns
and the mark they see. Measured with `uiautomator dump` at density 2.625, before and after pulling the
row out by `(28 − 18) / 2` dp:

| | message text | copy glyph | timestamp |
| --- | --- | --- | --- |
| before | x=32 | x=46 | x=111 |
| after | x=32 | x=33 | x=98 |

The glyph now lands on the message's own column (33 against 32, the remaining pixel being rounding),
and the timestamp kept its 18 px distance from the glyph. The inset is `CopyButtonInkInset`, derived
from the button's own two sizes rather than written down twice, and the row is pulled on whichever side
actually ends it: left for a left-aligned row, and right only when there is no timestamp to end it
instead.
