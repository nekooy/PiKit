# The release build's dex

*[Verification](../VERIFICATION.md): what R8 removes from the release APK that the debug one
keeps, and the checker that now fails the build over it.*

## The failure

Three formula blocks of a stored session drew as their LaTeX source in the installed app — `全概率`,
`贝叶斯` and `合成一行` — where the first rendered. Reproducing it in the app with the same session on
the emulator rendered **the same three bodies**, so the difference was not the text but the APK: the
emulator was running the **debug** build, and R8 runs only in the **release** build, which is the one
people install. The failure is logged, and the log names the cause rather than the symptom:

```
W PiKit: formula not typeset, drawn as its source: \dfrac{1}{2}
W PiKit: java.lang.NullPointerException: Attempt to invoke virtual method
         'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object,
         java.lang.Object[])' on a null object reference
```

JLaTeXMath resolves each macro entry point by name at run time (`Class.forName`, `getDeclaredMethod`,
`getDeclaredField` in `MacroInfo`/`TeXFormulaParser`), so R8 cannot see the use, deletes the members,
and the reflective lookup returns null.

## The sweep

The same 42-construct sweep — reproducible with `node tools/formula-sweep.mjs`, its constructs listed
in the file — gave, from `logcat`'s `formula not typeset` lines:

| APK | constructs that fell back to their source |
| --- | --- |
| `app-x64-debug.apk` | `\oiint`, `\oiiint` (both absent from the library's fonts — the documented limit) |
| `app-x64-release.apk`, before the fix | `\dfrac`, `\tfrac`, `\left\{\begin{matrix}…\right.`, `\begin{align}`, `\operatorname`, `\substack`, `\oiint`, `\oiiint` |
| `app-x64-release.apk`, after the fix | `\oiint`, `\oiiint` — identical to debug |

## The rule and its cost

`-keep class org.scilab.forge.jlatexmath.** { *; }` in `app/proguard-rules.pro`: the library is
330 KB of classes against a 107 MB APK, it reaches **218** `*_macro` entry points this way, and a
narrower rule would be a guess about which of its reflective paths matter. The release APK grew by
**32 KB** (107,231,914 → 107,264,682 bytes) for it.

## The checker, tested against the failure it is for

`tools/check-release-math.py` compares every release APK's dex against the `*_macro` names the
library's own AAR defines (218 of them, read from the AAR's constant pools — the AAR rather than a
debug APK, because `release.yml` builds `--release-only`, where there is no debug APK). Run while
the arm64 release APK on disk was still the pre-fix build:

```
FAIL app\build\outputs\apk\arm64\release\app-arm64-release.apk: 218 of the library's 218 macro
     entry points are gone — the release build will draw those formulas as their LaTeX source
ok   app\build\outputs\apk\x64\release\app-x64-release.apk: all 218 macro entry points are in the dex
FAIL 1 of 2 release APK(s) cannot typeset everything the library defines
```

`tools/build-apks.py` runs it as its last step, after the APKs are built — the one checker that
cannot be in its `checks` list, since those run before anything is packaged.
