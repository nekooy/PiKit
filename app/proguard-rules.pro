# ---------------------------------------------------------------------------
# JNI (vendored Termux PTY shim)
# ---------------------------------------------------------------------------
# libtermux.so exports symbols derived from the Java class and method names,
# e.g. Java_com_termux_terminal_JNI_createSubprocess. R8 would happily rename or
# remove this class -- it is package-private and only called from
# TerminalSession -- which changes the symbol names and makes System.loadLibrary
# fail with UnsatisfiedLinkError at runtime. That failure only appears on a
# device, so the names are pinned here.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class com.termux.terminal.JNI { *; }

# The terminal View is constructed programmatically and driven through the two
# client interfaces; keep their members so callbacks are not optimised away.
-keep interface com.termux.view.TerminalViewClient { *; }
-keep interface com.termux.terminal.TerminalSessionClient { *; }

# ---------------------------------------------------------------------------
# JLaTeXMath (formula typesetting)
# ---------------------------------------------------------------------------
# JLaTeXMath builds its command table **by name at runtime**, so R8 cannot see
# that any of its members are used: `MacroInfo` and `TeXFormulaParser` resolve the
# macro entry points with `Class.forName`, `getDeclaredMethod`/`getMethod` and
# `getDeclaredField`, and the names exist only as strings (in
# `TeXFormulaSettings.xml` and in `PredefMacros`). In a shrinking build the lookup
# therefore returns null and typesetting fails with
#
#   java.lang.NullPointerException: Attempt to invoke virtual method
#   'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object,
#   java.lang.Object[])' on a null object reference
#
# which `MathCache` catches and draws as the formula's LaTeX source. Measured on
# the same 42-construct sweep, same APK sources, x64:
#
#   debug   — `\oiint`, `\oiiint` fall back (both genuinely absent from the fonts)
#   release — `\dfrac`, `\tfrac`, `\left\{…\right.`, `\begin{align}`,
#             `\operatorname`, `\substack`, `\oiint`, `\oiiint` fall back
#
# i.e. **the release APK typeset six fewer constructs than the debug build this
# project verifies with**, and the reader's report "只有第一块渲染正常了" is
# `\dfrac` — the one thing a model writes that the release build could not draw.
# The library is ~330 KB of classes against a 107 MB APK, so it is kept whole
# rather than guessing which of its reflective paths matter; the cost is measured
# in `docs/verification/2026-09-19-release-dex.md` and `tools/check-release-math.py` fails
# the build if a release APK loses these entry points again.
-keep class org.scilab.forge.jlatexmath.** { *; }

# ---------------------------------------------------------------------------
# General
# ---------------------------------------------------------------------------
# Keep annotations that libraries read reflectively.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Coroutines and Compose ship their own consumer rules; nothing further needed.
