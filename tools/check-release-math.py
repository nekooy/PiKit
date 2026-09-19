#!/usr/bin/env python3
"""
Fails when a release APK has been shrunk past the formula renderer.

JLaTeXMath builds its command table **by name at run time**: `MacroInfo` and
`TeXFormulaParser` resolve each macro entry point with `Class.forName`,
`getDeclaredMethod`/`getMethod` and `getDeclaredField`, and those names exist only
as strings — in `TeXFormulaSettings.xml` and in `PredefMacros`. R8 cannot see any
of that, so it deletes the members and the lookup returns null:

    java.lang.NullPointerException: Attempt to invoke virtual method
    'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object,
    java.lang.Object[])' on a null object reference

`MathCache` catches it and draws the formula as its LaTeX source, which is what the
reader reported as "只有第一块渲染正常了" — the first block of a three-block answer had
no `\\dfrac` in it. Measured on a 42-construct sweep, same sources, x64: the debug APK
falls back on `\\oiint`/`\\oiiint` only; the release APK additionally falls back on
`\\dfrac`, `\\tfrac`, `\\left\\{…\\right.`, `\\begin{align}`, `\\operatorname` and
`\\substack`. **The release build rendered six fewer constructs than the debug build
this project verifies with**, and nothing in a JVM test or on a debug device can see it.

What it establishes:

  1. Every `*_macro` name the library's own AAR defines is still findable in every
     release APK's dex. The AAR is the reference rather than the debug APK because
     `release.yml` builds `--release-only`, where there is no debug APK to compare
     with — and because "what the library defines" is the property that matters, not
     "what another build happens to have kept".
  2. That is the whole of the `-keep class org.scilab.forge.jlatexmath.** { *; }` rule
     in `app/proguard-rules.pro`, stated as a property of the artifact: the rule is one
     way to satisfy it, and a build that dropped the rule fails here rather than on a
     reader's phone.

Stdlib only, and it reads zips rather than running `unzip`, so it works on a Windows
build host as well as in CI. Run it after the APKs are built — `tools/build-apks.py`
does that itself, which is why it is not in that script's `checks` list: those run
before anything is packaged.
"""

from __future__ import annotations

import io
import os
import re
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
APK_ROOT = REPO_ROOT / "app" / "build" / "outputs" / "apk"

#: The dependency whose command table is looked up by name, and the coordinate the
#: Gradle cache files it under.
LIBRARY = "jlatexmath-android"
GROUP = "ru.noties"

#: The method names JLaTeXMath resolves reflectively: `<name>_macro(String[])`. A
#: `\\dfrac` in a document reaches one of these too — the formula is defined in
#: `TeXFormulaSettings.xml` as `\\genfrac{…}`, and `\\genfrac` is a `_macro` method, which
#: is why a name-based check covers constructs that are not named `_macro` themselves.
MACRO = re.compile(rb"[A-Za-z][A-Za-z0-9]*_macro")


def library_macro_names(aar: Path) -> set[bytes]:
    """Every `*_macro` name in the AAR's classes, read out of the constant pools."""
    names: set[bytes] = set()
    with zipfile.ZipFile(aar) as archive:
        with archive.open("classes.jar") as jar:
            with zipfile.ZipFile(io.BytesIO(jar.read())) as classes:
                for entry in classes.namelist():
                    if entry.endswith(".class"):
                        names.update(MACRO.findall(classes.read(entry)))
    return names


def dex_bytes(apk: Path) -> bytes:
    """The concatenated `classes*.dex` of an APK."""
    with zipfile.ZipFile(apk) as archive:
        return b"".join(
            archive.read(name)
            for name in archive.namelist()
            if re.fullmatch(r"classes\d*\.dex", name)
        )


def find_aar(cache: Path) -> Path | None:
    """The library's AAR in the Gradle cache, newest first."""
    candidates = sorted(
        cache.glob(f"modules-2/files-2.1/{GROUP}/{LIBRARY}/*/*/*.aar"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    return candidates[0] if candidates else None


def main() -> int:
    cache = Path(
        os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle"
    ) / "caches"
    apks = sorted(APK_ROOT.glob("*/release/*.apk"))

    if not apks:
        print(f"skip no release APK under {APK_ROOT.relative_to(REPO_ROOT)} "
              "(nothing to check — build one first)")
        return 0

    aar = find_aar(cache)
    if aar is None:
        print(f"skip no {LIBRARY} AAR under {cache}/modules-2, which is the reference "
              "this check needs")
        return 0

    names = library_macro_names(aar)
    if not names:
        print(f"FAIL {aar.name} defines no *_macro name, so this check would pass "
              "vacuously")
        return 1

    failures = 0
    for apk in apks:
        dex = dex_bytes(apk)
        missing = sorted(name for name in names if name not in dex)
        where = apk.relative_to(REPO_ROOT)
        if missing:
            failures += 1
            print(f"FAIL {where}: {len(missing)} of the library's {len(names)} macro "
                  "entry points are gone — the release build will draw those formulas "
                  "as their LaTeX source")
            for name in missing[:10]:
                print(f"       missing {name.decode()}")
            if len(missing) > 10:
                print(f"       … and {len(missing) - 10} more")
            print("       app/proguard-rules.pro must keep the library whole; see its "
                  "JLaTeXMath section")
        else:
            print(f"ok   {where}: all {len(names)} macro entry points are in the dex")

    if failures:
        print(f"FAIL {failures} of {len(apks)} release APK(s) cannot typeset "
              "everything the library defines")
        return 1
    # No marker on this last line: `build-apks.py` prefixes it with its own `ok`, and it
    # takes the last non-empty line as the verdict, the way it does for every checker.
    print(f"{len(names)} macro entry points present in {len(apks)} release APK(s)")
    return 0


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    sys.exit(main())
