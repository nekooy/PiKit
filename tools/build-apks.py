#!/usr/bin/env python3
"""
Builds every APK in one command, after the checks that can stop it.

The README lists the commands a person needs most of the time; this is the one to
run when the answer wanted is "all four APKs, and do not hand me a broken one".
It does, in order:

  1. **Prerequisites.** Java, the Android SDK and its NDK, Node and the Gradle
     wrapper, each named with what is actually missing rather than failing later
     inside Gradle.
  2. **The runtime images**, which the app cannot be built without. They are not
     committed (`docs/BUILDING.md` says why), so a missing one is built here
     (~200 MB of downloads the first time) instead of being an error the user has
     to translate into a second command. One whose repository inputs have changed
     since it was assembled is rebuilt as well, because nothing else in the build
     knows those two things are related — see [IMAGE_INPUTS]. `--refresh-images`
     rebuilds them either way.
  3. **The tests that can fail here.** The app's unit suite, the vendored VT
     parser's suite, and the tools that check what Gradle cannot see: the images
     (including that the packed relocator still passes its own `--selfcheck`), the
     relocator against real `.deb` files, the manual's reflow and the terminal
     banners, and the agent's delete guard. `--skip-tests` bypasses them, which is
     the only reason that flag exists.
  4. **The four APKs** — `arm64`/`x64` × `debug`/`release` — one Gradle invocation
     each, so a failure names the variant it happened to, and so the packaging
     guard's known failure mode (a stale APK left by the build cache) can be
     repaired for that variant alone and retried. That failure is written up in
     `docs/BUILDING.md`; this script applies its remedy automatically, once.
  5. **The APKs that were just built**, once. `check-release-math.py` reads a
     release APK's dex and fails when R8 has removed the formula renderer's
     reflective command table — a failure that is invisible in every test and on
     every debug device, and that only the *release* build has. It cannot run
     among the checks above, because a check runs before anything is packaged.

Every step streams its output, so a long build shows progress rather than a blank
terminal. The summary at the end is the list of files to install.

    python tools/build-apks.py                  # tests, then all four APKs
    python tools/build-apks.py --skip-tests     # just build
    python tools/build-apks.py --debug-only     # the two debug APKs
    python tools/build-apks.py --release-only   # the two release APKs
    python tools/build-apks.py --refresh-images # rebuild the runtime image first
    python tools/build-apks.py --clean          # drop APK outputs first
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
APP = REPO_ROOT / "app"
APK_ROOT = APP / "build" / "outputs" / "apk"

#: flavour -> build type, in the order they are built. The flavour names are
#: Gradle's (`arm64`, `x64`) and also the output directory names; the APK inside is
#: `app-<flavour>-<type>.apk`.
VARIANTS = (("arm64", "debug"), ("arm64", "release"), ("x64", "debug"), ("x64", "release"))

#: Flavour -> the ABI directory its runtime image lives in. The two names differ,
#: which is exactly the sort of thing a build script should get right once.
ABI_DIRS = {"arm64": "arm64-v8a", "x64": "x86_64"}

#: Flavour -> the architecture `build-runtime-image.py` names it by. The third
#: spelling of the same axis, for the same reason as [ABI_DIRS]: the image builder
#: takes Termux's `aarch64`/`x86_64` and Gradle takes `arm64`/`x64`.
ARCH_NAMES = {"arm64": "aarch64", "x64": "x86_64"}

#: What the packaging guard says when it has caught an APK with trailing bytes.
#: Matched loosely on purpose: the point is to recognise the documented failure and
#: apply the documented remedy, not to parse Gradle's prose.
STALE_APK_MARKERS = (
    "verifyApkPackaging",
    "stale artifact",
    "cannot be read as a zip at all",
)

#: The three files a complete runtime image directory holds.
#:
#: `revision.txt` is part of the set rather than a nicety: it is what the app compares an
#: installed runtime against (`BootstrapInstaller.ensureInstalled`), and an image without it
#: falls back to a revision derived from the archives' *names and sizes* — which cannot see
#: an edit that did not change a length. An image that lacks it is therefore out of date by
#: definition, and is rebuilt.
IMAGE_FILES = ("bootstrap.zip", "overlay.zip", "revision.txt")

#: Every repository file that decides what a runtime image contains.
#:
#: This exists because *nothing else in the build knows the relationship*: Gradle sees the
#: archives as assets, so it repackages when they change but cannot tell that they are out
#: of date, and `verify-runtime-image.py` checks that an image is self-consistent, not that
#: it is current. Left alone, an edit to the agent's delete guard, the relocator or the
#: `dpkg` wrapper ships the *previous* one — and the failure is worse than a stale file:
#: the image's `revision.txt` does not move either, so a device that already installed the
#: previous APK keeps its old unpacked runtime *after* installing the new APK.
#:
#: Kept by hand, like [VARIANTS] and [ABI_DIRS], and it is checked rather than trusted:
#: [image_input_manifest_errors] reads the builder's own source and reports any repository
#: file it pulls in that this list does not name.
IMAGE_INPUTS = (
    # The recipe itself: the bootstrap tag and package set, the pi and web-access versions,
    # and the rewrite pass.
    "tools/build-runtime-image.py",
    "tools/prefix_patch.py",
    # Copied into the overlay, by content, from this repository.
    "tools/pikit-relocate.js",
    "tools/pikit-dpkg.sh",
    "tools/pi-safety-guard.ts",
    "tools/storage-self-test.sh",
)

#: The module whose `ndkVersion` the PTY shim is built with, and so the NDK to look for.
NDK_BUILD_FILE = Path("terminal-emulator") / "build.gradle.kts"


def log(message: str) -> None:
    print(f"[build-apks] {message}", flush=True)


def banner(title: str) -> None:
    print(f"\n=== {title} ===", flush=True)


def run(command: list[str], *, capture: bool = False) -> tuple[int, str]:
    """
    Runs a command from the repository root.

    Streams by default and *still returns its output*, because the two things this
    script has to do with a build's output are opposite: show it (a five-minute
    APK build with a blank terminal looks hung) and search it (the packaging
    guard's message decides whether a retry is warranted). `capture` is for the
    short checks, whose output is only interesting when they fail.
    """
    log("$ " + " ".join(redact(str(part)) for part in command))
    if capture:
        completed = subprocess.run(
            command, cwd=str(REPO_ROOT), capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
        return completed.returncode, (completed.stdout or "") + (completed.stderr or "")

    process = subprocess.Popen(
        command,
        cwd=str(REPO_ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    lines: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="", flush=True)
        lines.append(line)
    return process.wait(), "".join(lines)


def gradle(tasks: list[str], *, extra: list[str] | None = None) -> tuple[int, str]:
    """
    Runs the Gradle wrapper, which is a `.bat` on Windows and a shell script here.

    `cmd /c` rather than `shell=True`: a list plus a shell joins its arguments and
    re-parses the quoting, which breaks the moment a path has a space in it.
    """
    arguments = [*tasks, *(extra or []), *keystore_arguments()]
    if os.name == "nt":
        return run(["cmd", "/c", str(REPO_ROOT / "gradlew.bat"), *arguments])
    return run([str(REPO_ROOT / "gradlew"), *arguments])


#: The four properties a release build is signed with, and the one caller that has to
#: get them right: `.github/workflows/release.yml`.
KEYSTORE_PROPERTIES = (
    "pikit.keystore.file",
    "pikit.keystore.alias",
    "pikit.keystore.storePassword",
    "pikit.keystore.keyPassword",
)


def keystore_arguments() -> list[str]:
    """
    The keystore properties as `-P` arguments, for whichever are in this process's
    environment as `ORG_GRADLE_PROJECT_*`.

    **Passed as arguments even though Gradle reads the environment form itself**, and
    the reason is a failed release: the job exported all four variables, the build
    step's own environment listed them, and the APK still came out signed with a key
    Gradle generated on the spot — a build that succeeds while producing something
    unsigned by the key it was given. As arguments the properties travel in the build
    request, so the signature cannot depend on the environment reaching the process
    that resolves it. The values are redacted from this script's own output by
    [redact].
    """
    arguments: list[str] = []
    for name in KEYSTORE_PROPERTIES:
        value = os.environ.get("ORG_GRADLE_PROJECT_" + name)
        if value:
            arguments += ["-P", f"{name}={value}"]
    return arguments


def redact(text: str) -> str:
    """Replaces a keystore value with `***` — for the command line this script prints."""
    for name in KEYSTORE_PROPERTIES:
        value = os.environ.get("ORG_GRADLE_PROJECT_" + name)
        if value and name.endswith(("Password",)):
            text = text.replace(value, "***")
    return text


def python_tool(script: str, *args: str, capture: bool = True) -> tuple[int, str]:
    return run([sys.executable, str(REPO_ROOT / "tools" / script), *args], capture=capture)


def node_tool(script: str, *args: str) -> tuple[int, str]:
    node = shutil.which("node")
    if node is None:
        return 127, "node is not on PATH"
    return run([node, str(REPO_ROOT / "tools" / script), *args], capture=True)


# --------------------------------------------------------------------------- #
# Prerequisites
# --------------------------------------------------------------------------- #


def read_local_property(name: str) -> str | None:
    """
    One `local.properties` value, unescaped.

    The file is Java properties, so a Windows SDK path arrives as
    `sdk.dir=D\\:\\\\APP\\\\Android\\\\android-sdk`: the colon and the separators are
    escaped. Gradle unescapes them; this has to as well, or the SDK directory is looked
    for under a name no filesystem has.
    """
    file = REPO_ROOT / "local.properties"
    if not file.is_file():
        return None
    for line in file.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or "=" not in stripped:
            continue
        key, _, value = stripped.partition("=")
        if key.strip() != name:
            continue
        return value.strip().replace("\\:", ":").replace("\\\\", "\\")
    return None


def android_sdk_dir() -> Path | None:
    """The SDK Gradle would use: `local.properties` first, then the environment."""
    declared = read_local_property("sdk.dir") or os.environ.get("ANDROID_HOME") or os.environ.get(
        "ANDROID_SDK_ROOT"
    )
    if not declared:
        return None
    path = Path(declared)
    return path if path.is_dir() else None


def expected_ndk_version() -> str | None:
    """The `ndkVersion` the terminal emulator's PTY shim is built with, if it names one."""
    file = REPO_ROOT / NDK_BUILD_FILE
    if not file.is_file():
        return None
    match = re.search(r'ndkVersion\s*=\s*"([^"]+)"', file.read_text(encoding="utf-8", errors="replace"))
    return match.group(1) if match else None


def check_prerequisites() -> list[str]:
    """Returns the list of things that are missing, empty when nothing is."""
    missing: list[str] = []

    if shutil.which("java") is None:
        # Gradle needs a JDK (17-23, per docs/BUILDING.md). `JAVA_HOME` alone is not
        # enough to prove it: Gradle reads `org.gradle.java.home` too, so a missing
        # `java` is a hint rather than a verdict.
        missing.append("java (a JDK 17-23 on PATH, or org.gradle.java.home set)")

    if shutil.which("node") is None:
        missing.append("node (the runtime image builder and the guard tests need it)")

    if sys.version_info < (3, 10):
        missing.append(f"python 3.10+ (this is {sys.version.split()[0]})")

    wrapper = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        missing.append(f"the Gradle wrapper ({wrapper.name})")

    # The SDK: `local.properties` is what Gradle reads, the environment variables
    # are what a CI machine sets. Either is enough.
    sdk = android_sdk_dir()
    if sdk is None:
        missing.append("the Android SDK (sdk.dir in local.properties, or ANDROID_HOME)")

    # The NDK, which the vendored terminal emulator's PTY shim is compiled with. Gradle
    # reports a missing one itself, but only after it has configured every module and
    # started the task graph, and its message names a directory rather than the component
    # a person has to install — which is what this list is for (`docs/BUILDING.md`).
    if sdk is not None:
        version = expected_ndk_version()
        has_ndk_env = bool(os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT"))
        has_ndk = bool(version) and (sdk / "ndk" / str(version)).is_dir()
        if not has_ndk and not has_ndk_env:
            wanted = f"r{version.split('.')[0]} ({version})" if version else "the pinned one"
            missing.append(f"the Android NDK {wanted} under {sdk / 'ndk'} (or ANDROID_NDK_HOME)")

    return missing


def image_builder_prerequisites() -> list[str]:
    """
    What the *image builder* needs, checked when it is about to run.

    Deliberately not part of [check_prerequisites]: `npm` and the `zstandard` module are
    needed to assemble an image and for nothing else, and this script is also run on
    machines that already have the images and only rebuild the APKs. A prerequisite that
    blocks a build which does not need it is a prerequisite that gets worked around.

    Both are named here rather than left to the builder, whose own failure arrives after
    the Termux bootstrap has been downloaded and unpacked.
    """
    missing: list[str] = []

    if shutil.which("npm") is None:
        missing.append(
            "npm (the image builder vendors pi and the bundled web-access extension with it)"
        )

    try:
        has_zstandard = importlib.util.find_spec("zstandard") is not None
    except (ImportError, ValueError):
        has_zstandard = False
    if not has_zstandard:
        missing.append(
            "the 'zstandard' python module (`python -m pip install zstandard`), which the "
            "image builder decompresses .deb members with"
        )

    return missing


def image_dirs() -> dict[str, Path]:
    return {
        flavor: APP / "src" / flavor / "assets" / "runtime" / abi
        for flavor, abi in ABI_DIRS.items()
    }


def missing_flavors() -> list[str]:
    """The flavours whose runtime image is incomplete, in ABI order."""
    return [
        flavor
        for flavor, directory in image_dirs().items()
        if not all((directory / name).is_file() for name in IMAGE_FILES)
    ]


def image_build_time(flavor: str) -> float | None:
    """
    When [flavor]'s image was assembled, or None when there is not a complete one.

    The *oldest* of [IMAGE_FILES]: the archives are written before `revision.txt`, so the
    earliest of the three is the moment the image's content was decided. Comparing against
    that closes the window between the two writes rather than opening it.
    """
    directory = image_dirs()[flavor]
    stamps = [
        (directory / name).stat().st_mtime
        for name in IMAGE_FILES
        if (directory / name).is_file()
    ]
    if len(stamps) != len(IMAGE_FILES):
        return None
    return min(stamps)


def newest_image_input(after: float) -> tuple[str, float] | None:
    """The most recently modified [IMAGE_INPUTS] file newer than [after], if any."""
    newest: tuple[str, float] | None = None
    for name in IMAGE_INPUTS:
        path = REPO_ROOT / name
        if not path.is_file():
            continue
        stamp = path.stat().st_mtime
        if stamp > after and (newest is None or stamp > newest[1]):
            newest = (name, stamp)
    return newest


def stale_image_flavors() -> list[str]:
    """
    The flavours whose image is older than a file it is built from, in ABI order.

    A flavour with no complete image is not reported here — [missing_flavors] owns that —
    so the two reasons a build has to run are never confused in the log.
    """
    stale: list[str] = []
    for flavor in image_dirs():
        built = image_build_time(flavor)
        if built is None:
            continue
        if newest_image_input(built) is not None:
            stale.append(flavor)
    return stale


def image_input_manifest_errors() -> list[str]:
    """
    Checks [IMAGE_INPUTS] against the repository files the image builder reads.

    The list is hand-kept, and a file the builder starts copying without being added to it
    would be a stale image nothing notices — the failure the list exists to prevent. So the
    builder's own source is read for the two ways it takes a repository file in: a
    `REPO_ROOT / "tools" / "..."` path, and a sibling module import that resolves to a file
    under `tools/` (stdlib imports resolve to nothing there, which is what keeps this from
    demanding an entry for `argparse`).
    """
    builder = REPO_ROOT / "tools" / "build-runtime-image.py"
    try:
        source = builder.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        return [f"could not read {builder.name} to check the image inputs list: {error}"]

    referenced = set(re.findall(r'REPO_ROOT\s*/\s*"tools"\s*/\s*"([^"]+)"', source))
    for name in re.findall(r"^\s*import\s+([A-Za-z_][A-Za-z0-9_]*)", source, re.M):
        if (REPO_ROOT / "tools" / f"{name}.py").is_file():
            referenced.add(f"{name}.py")

    unnamed = sorted(name for name in referenced if f"tools/{name}" not in IMAGE_INPUTS)
    absent = sorted(name for name in IMAGE_INPUTS if not (REPO_ROOT / name).is_file())
    errors: list[str] = []
    if unnamed:
        errors.append(
            "build-runtime-image.py reads these, and IMAGE_INPUTS does not name them: "
            + ", ".join(unnamed)
            + " — add them there (and to the builder's own revision digest), or an edit to "
            "one of them ships an image built from the previous version"
        )
    if absent:
        errors.append("IMAGE_INPUTS names files that do not exist: " + ", ".join(absent))
    return errors


# --------------------------------------------------------------------------- #
# Steps
# --------------------------------------------------------------------------- #


def run_image_builder(arguments: list[str]) -> bool:
    """Runs the image builder with [arguments], streaming its output."""
    code, _ = python_tool("build-runtime-image.py", *arguments, capture=False)
    if code != 0:
        log("FAILED: the runtime image builder failed")
        return False
    return True


def ensure_images(refresh: bool, rebuilt: list[str]) -> bool:
    """
    Builds the runtime images when they are missing or out of date, or when asked to.

    Out of date is part of "cannot be built without them" rather than a convenience: the
    archives are what Gradle packages, nothing in the build relates them to the files in
    `tools/` they are made of, and an image built from a previous version of the agent's
    delete guard is not merely old — its revision does not move either, so a device that
    already has the previous APK never unpacks the new one. See [IMAGE_INPUTS].

    [rebuilt] collects the flavours this actually built, which is what lets the caller say
    that `--skip-tests` left them unverified — a fact about what happened rather than about
    the flags.
    """
    banner("runtime images")

    manifest_errors = image_input_manifest_errors()
    if manifest_errors:
        for error in manifest_errors:
            log(f"FAILED: {error}")
        return False

    missing = missing_flavors()
    stale = [flavor for flavor in stale_image_flavors() if flavor not in missing]

    if not refresh and not missing and not stale:
        log("both runtime images are present and newer than everything they are built from")
        return True

    if refresh:
        log("rebuilding the runtime images (--refresh-images)")
        if not run_image_builder(["--all"]):
            return False
        rebuilt.extend(sorted(image_dirs()))
        return True

    prerequisites = image_builder_prerequisites()
    if prerequisites:
        log("FAILED: the runtime images have to be built, and these are missing:")
        for item in prerequisites:
            log(f"  - {item}")
        return False

    # Only the flavours that are actually missing. This used to build both whenever
    # either was incomplete, which is an entire extra architecture's worth of work —
    # re-extracting ~240 MB of package contents, re-vendoring npm and rewriting every
    # string in a tree of 22,000 files — for an image that was already on disk.
    if missing:
        log("building the missing runtime image(s): " + ", ".join(missing))
        for flavor in missing:
            if not run_image_builder(
                ["--arch", ARCH_NAMES[flavor], "--flavor", flavor]
            ):
                return False
            rebuilt.append(flavor)

    if stale:
        log("rebuilding the out-of-date runtime image(s): " + ", ".join(stale))
        for flavor in stale:
            built = image_build_time(flavor)
            driver = newest_image_input(built) if built is not None else None
            if driver is not None:
                log(f"  {flavor}: {driver[0]} is newer than the image it is built into")
            if not run_image_builder(
                ["--arch", ARCH_NAMES[flavor], "--flavor", flavor]
            ):
                return False
            rebuilt.append(flavor)

    return True


def run_tests() -> bool:
    banner("tests")
    failures: list[str] = []

    # The app's unit tests are ABI-independent — one flavour's task runs all of them
    # — and the vendored VT parser is a separate module with its own suite.
    code, _ = gradle([":app:testX64DebugUnitTest", ":terminal-emulator:testDebugUnitTest"])
    if code != 0:
        log("FAIL the JVM test suites (see the output above)")
        failures.append("the JVM test suites")
    else:
        results = APP / "build" / "test-results" / "testX64DebugUnitTest"
        total = sum(
            int(part)
            for file in results.glob("*.xml")
            for part in [_attribute(file, "tests")]
            if part
        )
        log(f"ok   the JVM test suites ({total} tests in {len(list(results.glob('*.xml')))} suites)")

    checks = [
        ("the runtime images", lambda: python_tool("verify-runtime-image.py")),
        # The rule that decides whether the agent's manual — and the extension's only
        # documentation — reaches the phone at all. It is a `keep` argument and one
        # `unlink` away from being silently wrong, and a device shows nothing when it
        # is: the chapters are simply absent, which is how they went missing.
        ("the vendored documentation", lambda: python_tool("test-vendor-trim.py")),
        ("the relocator", lambda: python_tool("test-relocate.py")),
        ("the manual's reflow", lambda: python_tool("reflow-manual.py", "--check")),
        ("the terminal banners", lambda: python_tool("check-locales.py")),
        ("the agent guard", lambda: node_tool("test-safety-guard.mjs")),
        # The README's icon is a second copy of the launcher icon, which is a thing
        # that drifts: nothing about editing the vector would remind anyone that the
        # picture at the top of the README is now wrong. Cheap (it reads two files)
        # and it exists for the same reason as the checks above it.
        ("the README icon", lambda: python_tool("render-icon.py", "--check")),
    ]
    for name, check in checks:
        if name == "the relocator" and not (REPO_ROOT / ".runtime-build" / "cache" / "debs").is_dir():
            log(f"skip {name}: no .deb cache under .runtime-build/cache/debs, which the image "
                "builder fills")
            continue
        code, output = check()
        if code == 0:
            # Each tool prints its own verdict line last; that one line is enough
            # here, and the whole output follows when it is not.
            verdict = next(
                (line.strip() for line in reversed(output.splitlines()) if line.strip()),
                "ok",
            )
            log(f"ok   {name}: {verdict}")
        else:
            print(output)
            log(f"FAIL {name}")
            failures.append(name)

    if failures:
        log("FAILED: " + ", ".join(failures))
        return False
    log("all tests passed")
    return True


def _attribute(xml: Path, name: str) -> str | None:
    """Reads one attribute of a JUnit XML report's root element, without a parser."""
    import re

    try:
        head = xml.read_text(encoding="utf-8", errors="replace")[:2000]
    except OSError:
        return None
    match = re.search(rf'{name}="(\d+)"', head)
    return match.group(1) if match else None


def clear_apk_outputs(variants: tuple[tuple[str, str], ...] = VARIANTS) -> int:
    """
    Removes built APKs, for `--clean` and before a stale-artifact retry.

    Only the variants named: the packaging guard's failure mode is an APK with
    bytes appended by the build cache, and deleting *every* variant's APK would
    undo the ones already built and reported. Deleting the intermediate package
    directory as well was tried and is not needed — Gradle sees the missing output
    and re-runs `package<Flavour><Type>` on its own.
    """
    removed = 0
    for flavor, build_type in variants:
        for apk in (APK_ROOT / flavor / build_type).glob("*.apk"):
            apk.unlink()
            removed += 1
    return removed


def build_variant(flavor: str, build_type: str) -> bool:
    """Builds one variant, retrying once when the packaging guard flags a stale APK."""
    # `capitalize()` on both, so the task is Gradle's `assembleArm64Debug` rather
    # than the `assemblearm64Debug` that the flavour name alone would spell. Gradle
    # matches task names case-insensitively, so both work — and a log line that
    # looks like a typo is not worth relying on that.
    task = f":app:assemble{flavor.capitalize()}{build_type.capitalize()}"

    code, output = gradle([task])
    if code == 0:
        return True

    if any(marker in output for marker in STALE_APK_MARKERS):
        log(f"{task}: the packaging guard caught a stale APK; deleting it and retrying "
            "without the build cache — the remedy in docs/BUILDING.md")
        removed = clear_apk_outputs(((flavor, build_type),))
        log(f"removed {removed} APK(s) for this variant")
        code, _ = gradle([task], extra=["--no-build-cache"])
        if code == 0:
            return True

    log(f"FAILED: {task}")
    return False


def build_apks(variants: list[tuple[str, str]]) -> tuple[bool, list[Path]]:
    built: list[Path] = []
    for flavor, build_type in variants:
        banner(f"APK: {flavor} {build_type}")
        started = time.monotonic()
        if not build_variant(flavor, build_type):
            return False, built
        apks = sorted((APK_ROOT / flavor / build_type).glob("*.apk"))
        if not apks:
            log(f"FAILED: {APK_ROOT / flavor / build_type} has no APK after a successful build")
            return False, built
        for apk in apks:
            log(f"built {apk.name} — {apk.stat().st_size / 1e6:.1f} MB "
                f"in {time.monotonic() - started:.0f}s")
            built.append(apk)
    return True, built


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build every PiKit APK, after the checks that can stop it.",
    )
    parser.add_argument("--skip-tests", action="store_true", help="do not run any check")
    parser.add_argument("--debug-only", action="store_true", help="build only the debug APKs")
    parser.add_argument("--release-only", action="store_true", help="build only the release APKs")
    parser.add_argument(
        "--refresh-images",
        action="store_true",
        help="rebuild the runtime images even when they are already present",
    )
    parser.add_argument("--clean", action="store_true", help="delete the built APKs first")
    args = parser.parse_args()

    if args.debug_only and args.release_only:
        parser.error("--debug-only and --release-only are mutually exclusive")

    started = time.monotonic()
    log(f"repository {REPO_ROOT}")

    missing = check_prerequisites()
    if missing:
        log("FAILED: these are missing:")
        for item in missing:
            log(f"  - {item}")
        log("docs/BUILDING.md lists the versions this project expects")
        return 1

    if args.clean:
        banner("clean")
        log(f"removed {clear_apk_outputs()} existing APK(s)")

    # Always ensured: the app cannot be built without them, with or without tests.
    rebuilt: list[str] = []
    if not ensure_images(args.refresh_images, rebuilt):
        return 1

    if args.skip_tests:
        # Said out loud because the checks are what verify a fresh image, and which images
        # are fresh is not a property of the flags: a stale one is rebuilt without being
        # asked for. See `ensure_images`.
        if rebuilt:
            log("--skip-tests: the rebuilt runtime image(s) ("
                + ", ".join(rebuilt) + ") will not be verified")
    elif not run_tests():
        return 1

    variants = [
        (flavor, build_type)
        for flavor, build_type in VARIANTS
        if not (args.debug_only and build_type != "debug")
        and not (args.release_only and build_type != "release")
    ]

    ok, built = build_apks(variants)
    if not ok:
        return 1

    # After the builds rather than among the checks above: what this one reads is an
    # APK, and the question it answers — did R8 leave the formula renderer alone in the
    # *release* build? — is about an artifact that only now exists. The debug build
    # typesetting a formula says nothing about the release one, which is how a reader
    # came to report "只有第一块渲染正常了" about an APK that had been built and shipped.
    banner("verify what was built")
    code, output = python_tool("check-release-math.py")
    verdict = next(
        (line.strip() for line in reversed(output.splitlines()) if line.strip()),
        "ok",
    )
    if code == 0:
        log(f"ok   {verdict}")
    else:
        print(output)
        log("FAILED: a release APK cannot typeset what the formula library defines")
        return 1

    banner("done")
    for apk in built:
        log(f"{apk.relative_to(REPO_ROOT)}  {apk.stat().st_size / 1e6:.1f} MB")
    log(f"total {time.monotonic() - started:.0f}s")
    log("install the one matching the device, e.g.:")
    log("  adb install -r app/build/outputs/apk/x64/debug/app-x64-debug.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
