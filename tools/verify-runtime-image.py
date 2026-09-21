#!/usr/bin/env python3
"""
Validates a built runtime image without needing a device.

The riskiest part of PiKit is not the Kotlin: it is the shortcut that
represents a Linux filesystem as zip archives plus a `SYMLINKS.txt` side-car.
A mistake there produces an environment that looks fine but cannot exec
anything, and the failure only shows up at runtime on a phone.

This script reconstructs the exact tree the on-device installer
(`BootstrapInstaller.kt`) produces, using the same rules:

  * entries are relative to `$PREFIX`
  * `SYMLINKS.txt` holds `target<U+2190>path` lines, applied after extraction
  * `bin/`, `libexec`, `lib/apt/apt-helper`, `lib/apt/methods` are executable

It then asserts the properties the environment actually depends on.

Usage:
    python tools/verify-runtime-image.py
    python tools/verify-runtime-image.py --abi arm64-v8a
"""

from __future__ import annotations

import argparse
import io
import posixpath
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prefix_patch import (  # noqa: E402
    NEVER_REWRITE,
    decompress_content,
    dex_headers_valid,
    is_dex,
)


def load_image_builder():
    """
    Loads the image builder by path, because its file name is not a module name.

    Only its declarations are read — what a vendored tree drops ([VENDOR_JUNK_DIRS],
    [VENDOR_JUNK_FILES], [VENDOR_JUNK_SUFFIXES]), the per-tree extras ([PI_DROPS],
    [WEB_ACCESS_DROPS]) and the one exception ([WEB_ACCESS_DOCS]) — and the predicate
    they feed, so that the check below states the rule the builder applied instead of a
    second copy of it. A copy is how a check and its subject drift apart while both look
    right, which is the failure this whole file exists to catch.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "pikit_runtime_image", Path(__file__).resolve().parent / "build-runtime-image.py"
    )
    if spec is None or spec.loader is None:  # pragma: no cover - the file is here
        raise SystemExit("cannot load tools/build-runtime-image.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


BUILDER = load_image_builder()

REPO_ROOT = Path(__file__).resolve().parent.parent

#: The application id the image is built for. Set from `--app-id`.
APP_ID = "pi.kit.mob"

#: The prefix the image must be built for, derived from APP_ID.
PREFIX = f"/data/data/{APP_ID}/files/usr"

#: The prefix the bundled Termux packages were compiled against, which the
#: byte-level relocation is supposed to remove entirely.
UPSTREAM_NAME = b"com.termux"

#: Members the build authors itself and which are allowed to name the upstream id
#: in their comments. Taken from `prefix_patch` rather than repeated, so the two
#: cannot drift: that list is what keeps the rewrit*er* from rewriting *itself*,
#: and this one is what keeps the checker from demanding the impossible.
AUTHORED_MEMBERS = NEVER_REWRITE

#: The relocator is required to be present and to pass its own `--selfcheck`.
RELOCATOR = "libexec/pikit/relocate.js"

#: The exclusion list the app reads for its own repair pass; it must be the same
#: set as `AUTHORED_MEMBERS`.
REWRITE_EXCLUSIONS = "share/pikit/never-rewrite.txt"

#: `com/termux` (slash form) appears only inside URLs in comments and docs — `github.com`
#: and the mirrors that host the same repositories — so rewriting one corrupts it, and
#: `UPSTREAM_NAME` cannot catch that: the rewrite replaces a *dotted* id, and `com/termux`
#: is not one. What a bad rewrite of these leaves behind is `pi/kit/mob`, which is this
#: app's id in a form nothing in the image uses: every path in it is
#: `/data/data/pi.kit.mob/...`, dotted. So the check is that string's absence, which needs
#: no re-measuring when a release edits a documented link — a count of the occurrences
#: moved every time pi's documentation did, and had to be bumped by hand.
UPSTREAM_URL_NAME = b"com/termux"
REWRITTEN_URL_NAME = b"pi/kit/mob"

FLAVORS = {"arm64-v8a": "arm64", "x86_64": "x64"}

#: Termux always ships the preload hook under this name.
TERMUX_EXEC = "lib/libtermux-exec-ld-preload.so"

#: Absolute files the app and the agent require.
REQUIRED_FILES = [
    "bin/bash",
    "bin/login",
    "bin/node",
    "bin/rg",     # backs pi's `grep` tool
    "bin/fd",     # backs pi's `find` tool
    "bin/pi",
    "lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js",
    TERMUX_EXEC,
]

#: Absolute files that may legitimately be provided as symlinks.
REQUIRED_ANY = ["bin/sh", "bin/npm", "bin/env"]

#: Documentation the reader has no other copy of, as `(label, root in the image, root in
#: the vendoring cache, what that tree drops, what it keeps)`. The last three are the
#: builder's own declarations, and the expectation below is computed from them plus the
#: cache — so a pi release that adds, renames or retires a chapter moves nothing here and
#: a file the image is missing is named. A list or a count kept in this file would go
#: stale on the next bump and, worse, stay green while it did.
#:
#: The cache is a build host's directory — a machine that only has the assembled assets
#: does not have it — and the check says so there instead of failing.
DOCUMENTED_TREES = (
    (
        "pi",
        "lib/node_modules/@earendil-works/pi-coding-agent",
        ".runtime-build/cache/pi/node_modules/@earendil-works/pi-coding-agent",
        BUILDER.PI_DROPS,
        (),
    ),
    # The extension's cache root is the project directory the image installs as
    # `lib/node_modules/pikit-extensions`, which is why one path is relative to the
    # other: its drops and its kept README are written against that root.
    (
        "pi-web-access",
        "lib/node_modules/pikit-extensions",
        ".runtime-build/cache/web-access",
        BUILDER.WEB_ACCESS_DROPS,
        BUILDER.WEB_ACCESS_DOCS,
    ),
)

#: Documentation the image must carry even where there is no cache to compare against:
#: the structure a reader navigates by, not particular chapters. A chapter *name* is
#: deliberately not asserted — pi may rename, split or retire one, and a check that
#: pinned the names would fail on a pi release for a reason that has nothing to do with
#: the image being wrong. What the names cannot catch is caught by the comparison above,
#: which names the missing file.
ANCHOR_DOCUMENTS = (
    "lib/node_modules/@earendil-works/pi-coding-agent/docs/index.md",
    "lib/node_modules/@earendil-works/pi-coding-agent/docs/docs.json",
    "lib/node_modules/pikit-extensions/node_modules/pi-web-access/README.md",
)

SYMLINK_SEPARATOR = "\u2190"
EXECUTABLE_PREFIXES = ("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")

ELF_MAGIC = b"\x7fELF"
ELF_MACHINE = {0xB7: "aarch64", 0x3E: "x86_64"}


class Image:
    """The tree the installer would produce, held as path -> node."""

    def __init__(self) -> None:
        self.files: dict[str, bytes | None] = {}
        self.dirs: set[str] = set()
        self.symlinks: dict[str, str] = {}
        self.executable: set[str] = set()
        self.errors: list[str] = []
        self._parents_indexed = False

    def _ensure_parents(self) -> None:
        """
        Records directories that exist only implicitly.

        The archives store files, not their parent directories, so
        `lib/icu/78.3/` is present only because `lib/icu/78.3/Makefile.inc` is.
        The installer recreates it with `mkdirs`, and a symlink may legitimately
        point at it, so the verifier has to model it too.
        """
        if self._parents_indexed:
            return
        for path in list(self.files) + list(self.symlinks):
            parts = path.split("/")
            for i in range(1, len(parts)):
                self.dirs.add("/".join(parts[:i]))
        self._parents_indexed = True

    def add_archive(self, archive: zipfile.ZipFile, label: str) -> None:
        symlink_lines: list[str] = []

        for info in archive.infolist():
            name = info.filename

            if name == "SYMLINKS.txt":
                symlink_lines = archive.read(name).decode("utf-8").splitlines()
                continue

            is_dir = name.endswith("/")
            normalised = posixpath.normpath(name).lstrip("/")

            if normalised in (".", ""):
                continue
            if normalised.startswith(".."):
                self.errors.append(f"{label}: entry escapes the prefix: {name}")
                continue

            if is_dir:
                # Directories matter: a symlink may legitimately point at one,
                # and `lib/icu/current` is a symlink used as a path component.
                self.dirs.add(normalised)
                continue

            self.files[normalised] = None
            if normalised.startswith(EXECUTABLE_PREFIXES):
                self.executable.add(normalised)

        for line in symlink_lines:
            line = line.strip()
            if not line:
                continue
            if SYMLINK_SEPARATOR not in line:
                self.errors.append(f"{label}: malformed symlink line: {line!r}")
                continue
            target, path = line.split(SYMLINK_SEPARATOR, 1)
            normalised = posixpath.normpath(path).lstrip("/")
            self.files.pop(normalised, None)
            self.dirs.discard(normalised)
            self.symlinks[normalised] = target

    def exists(self, path: str) -> bool:
        self._ensure_parents()
        return path in self.files or path in self.symlinks or path in self.dirs

    @staticmethod
    def strip_prefix(absolute: str) -> str:
        relative = absolute.lstrip("/")
        prefix = PREFIX.strip("/") + "/"
        return relative[len(prefix):] if relative.startswith(prefix) else relative

    def resolve(self, path: str) -> str | None:
        """
        Follows symlinks, including ones appearing as intermediate path
        components, and returns the final path or None if it is dangling.
        """
        self._ensure_parents()
        parts = [p for p in path.split("/") if p not in ("", ".")]
        resolved: list[str] = []
        steps = 0

        while parts:
            steps += 1
            if steps > 256:
                return None
            part = parts.pop(0)

            if part == "..":
                if resolved:
                    resolved.pop()
                continue

            candidate = "/".join(resolved + [part])
            target = self.symlinks.get(candidate)
            if target is not None:
                if target.startswith("/"):
                    base = self.strip_prefix(target)
                else:
                    # Relative targets resolve against the link's directory.
                    base = "/".join(resolved + [target])
                parts = [p for p in base.split("/") if p not in ("", ".")] + parts
                resolved = []
                continue

            resolved.append(part)

        final = "/".join(resolved)
        return final if (final in self.files or final in self.dirs) else None

    def read(self, archive: zipfile.ZipFile, path: str) -> bytes | None:
        for candidate in self.real_names(archive, path):
            try:
                return archive.read(candidate)
            except KeyError:
                continue
        return None

    def read_head(self, archive: zipfile.ZipFile, path: str, limit: int = 160) -> bytes | None:
        """Reads just the start of an entry; enough to spot a shebang."""
        for candidate in self.real_names(archive, path):
            try:
                with archive.open(candidate) as handle:
                    return handle.read(limit)
            except KeyError:
                continue
        return None

    def read_any_head(self, archives, path: str, limit: int = 160) -> bytes | None:
        for archive in archives:
            head = self.read_head(archive, path, limit)
            if head:
                return head
        return None

    def real_names(self, archive: zipfile.ZipFile, path: str) -> list[str]:
        return [path, f"./{path}"]


def elf_machine(blob: bytes) -> str | None:
    if len(blob) < 20 or blob[:4] != ELF_MAGIC:
        return None
    little = blob[5] == 1
    machine = struct.unpack("<H" if little else ">H", blob[18:20])[0]
    return ELF_MACHINE.get(machine, hex(machine))


def find_runpath(blob: bytes) -> str | None:
    """Reads DT_RUNPATH/DT_RPATH out of a 64-bit ELF's dynamic section."""
    if len(blob) < 64 or blob[:4] != ELF_MAGIC or blob[4] != 2:
        return None
    little = blob[5] == 1
    endian = "<" if little else ">"

    e_phoff = struct.unpack(endian + "Q", blob[32:40])[0]
    e_phentsize = struct.unpack(endian + "H", blob[54:56])[0]
    e_phnum = struct.unpack(endian + "H", blob[56:58])[0]

    dynamic: tuple[int, int] | None = None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + 56 > len(blob):
            break
        p_type = struct.unpack(endian + "I", blob[base:base + 4])[0]
        if p_type == 2:  # PT_DYNAMIC
            p_offset = struct.unpack(endian + "Q", blob[base + 8:base + 16])[0]
            p_filesz = struct.unpack(endian + "Q", blob[base + 32:base + 40])[0]
            dynamic = (p_offset, p_filesz)
            break

    if dynamic is None:
        return None

    offset, size = dynamic
    entries: list[tuple[int, int]] = []
    for at in range(offset, min(offset + size, len(blob) - 16), 16):
        tag, val = struct.unpack(endian + "QQ", blob[at:at + 16])
        if tag == 0:
            break
        entries.append((tag, val))

    strtab = next((val for tag, val in entries if tag == 5), None)
    if strtab is None:
        return None

    for wanted in (29, 15):  # DT_RUNPATH, DT_RPATH
        str_offset = next((val for tag, val in entries if tag == wanted), None)
        if str_offset is None:
            continue
        start = strtab + str_offset
        end = blob.find(b"\0", start)
        if start < len(blob):
            return blob[start:end if end != -1 else len(blob)].decode("utf-8", "replace")
    return None


def check_relocator(overlay: zipfile.ZipFile) -> tuple[bool, str]:
    """
    Runs the packed relocator's `--selfcheck` with `node`.

    The relocator is the one file in the image that has to name the upstream
    application id, so it is exempt from the "nothing still names the old app"
    count above — which means that count says nothing about whether it works, and
    it is the one file whose *function* can be destroyed without any member
    changing. It was: the build's prefix rewrite reached `const OLD_ID =
    'com.termux'`, every archive was then compared against the replacement,
    every comparison found nothing, and every package from the Termux repository
    installed unrelocated while the hook reported success.

    Running the packed file is the only check that fails in that state, and it
    fails at the build rather than at the ninety-sixth package installation.
    """
    try:
        blob = overlay.read(RELOCATOR)
    except KeyError:
        return False, f"{RELOCATOR} is missing from overlay.zip"

    node = shutil.which("node")
    if node is None:
        return False, (
            "node is not on PATH, so the packed relocator cannot be checked; the "
            "build host already needs it to vendor pi"
        )

    with tempfile.TemporaryDirectory(prefix="pikit-relocator-") as scratch:
        script = Path(scratch) / "relocate.js"
        script.write_bytes(blob)
        completed = subprocess.run(
            [node, str(script), "--selfcheck"],
            capture_output=True,
            text=True,
        )
    if completed.returncode != 0:
        return False, "the packed relocator failed its self-check: " + (
            completed.stdout + completed.stderr
        ).strip()
    return True, "the packed relocator is configured (" + completed.stdout.strip() + ")"


def check_rewrite_exclusions(overlay: zipfile.ZipFile) -> tuple[bool, str]:
    """
    The exclusion list the *app* reads must be the one the build applied.

    `PrefixPatcher` rewrites the prefix from inside the app, and it reads this file
    rather than repeating the list — because repeating it is what went wrong: the
    build protected eight authored files and the app did not, so pressing "check
    and repair" rewrote `libexec/pikit/relocate.js`'s `OLD_ID` into `NEW_ID`, after
    which the relocator refused to run and every later `pkg install` went
    unrelocated. A drifted or missing list puts the app back in that state, so it
    is asserted here rather than trusted.
    """
    try:
        text = overlay.read(REWRITE_EXCLUSIONS).decode("utf-8")
    except KeyError:
        return False, f"{REWRITE_EXCLUSIONS} is missing from overlay.zip"

    listed = {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("#")
    }
    expected = set(AUTHORED_MEMBERS)
    if listed != expected:
        return False, (
            "the app's rewrite exclusion list differs from the build's: "
            f"missing {sorted(expected - listed)}, unexpected {sorted(listed - expected)}"
        )
    return True, f"the app's rewrite exclusion list matches the build's ({len(listed)} paths)"


def files_in(root: Path) -> set[str] | None:
    """
    Every file a vendored tree holds, relative to it, or None when it is not there.

    Which of them the image owes is [BUILDER.vendor_junk]'s answer, applied by the caller.

    **A symlink counts, as itself or as the file it points at**, and that is a correction
    rather than a detail. npm creates `node_modules/.bin/*` as symlinks on Linux and as
    shim files on Windows, and the archive writer has no symlink to make for one — the
    extraction produces a regular file — so the two sides of the comparison describe the
    same entry in different forms. Excluding symlinks here made a *cold CI cache* report
    the six `.bin` entries as files the image has and the tree does not
    (`node_modules/.bin/esbuild` and friends, five in pi's tree and one in the
    extension's), while a Windows build host, whose cache has no symlink in that
    directory at all, passed the same check.
    """
    if not root.is_dir():
        return None
    return {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file() or path.is_symlink()
    }


def check_abi(abi: str, flavor: str) -> bool:
    root = REPO_ROOT / "app" / "src" / flavor / "assets" / "runtime" / abi
    print(f"\n=== {abi}  ({root.relative_to(REPO_ROOT)}) ===")

    for name in ("bootstrap.zip", "overlay.zip"):
        if not (root / name).is_file():
            print(f"  FAIL  {name} is missing")
            return False

    image = Image()
    bootstrap = zipfile.ZipFile(root / "bootstrap.zip")
    overlay = zipfile.ZipFile(root / "overlay.zip")
    image.add_archive(bootstrap, "bootstrap.zip")
    image.add_archive(overlay, "overlay.zip")

    ok = True

    def report(passed: bool, message: str) -> None:
        nonlocal ok
        print(("  ok    " if passed else "  FAIL  ") + message)
        if not passed:
            ok = False

    for error in image.errors:
        report(False, error)

    print(f"  -- tree: {len(image.files)} files, {len(image.symlinks)} symlinks")

    for path in REQUIRED_FILES:
        report(image.exists(path), f"present: {path}")

    for path in REQUIRED_ANY:
        report(image.exists(path), f"present (file or symlink): {path}")

    # Every symlink must land on something.
    dangling = [p for p in image.symlinks if image.resolve(p) is None]
    report(not dangling, f"all {len(image.symlinks)} symlinks resolve" +
           ("" if not dangling else f" (dangling: {dangling[:5]})"))

    # Termux scripts hardcode an absolute interpreter path, so a prefix mismatch
    # breaks exec at runtime. Only `bin/` and `libexec/` are asserted: pi's own
    # files are always launched as `node <cli.js>` and never via their shebang,
    # and the published tarball contains bun-specific entries for another
    # runtime entirely.
    bad_shebangs: list[tuple[str, str]] = []
    missing_interpreters: dict[str, int] = {}
    for path in sorted(image.files):
        if not path.startswith(EXECUTABLE_PREFIXES):
            continue
        head = image.read_any_head((bootstrap, overlay), path)
        if not head or not head.startswith(b"#!"):
            continue
        first_line = head.split(b"\n", 1)[0][2:].strip().decode("utf-8", "replace")
        interpreter = first_line.split()[0] if first_line else ""
        if not interpreter.startswith("/"):
            continue
        relative = image.strip_prefix(interpreter)
        if image.resolve(relative) is None:
            missing_interpreters[relative] = missing_interpreters.get(relative, 0) + 1
            # `perl` is a genuine omission, but only dpkg's packaging-developer
            # helpers need it; apt/dpkg/pkg install and remove without it, and
            # the official Termux bootstrap does not ship it either. Report it
            # as a note rather than failing.
            if relative != "bin/perl":
                bad_shebangs.append((path, interpreter))
    report(
        not bad_shebangs,
        "every shebang under bin/ and libexec/ resolves"
        + ("" if not bad_shebangs else f" (broken: {bad_shebangs[:5]})"),
    )
    for interpreter, count in sorted(missing_interpreters.items()):
        print(f"  note  {count} script(s) reference {interpreter}, which is not bundled")

    # Architecture and dynamic linking of the two binaries everything hangs off.
    expected_machine = "aarch64" if abi == "arm64-v8a" else "x86_64"
    for path in ("bin/bash", "bin/node"):
        blob = image.read(bootstrap, path) or image.read(overlay, path)
        if blob is None:
            report(False, f"{path}: cannot read")
            continue
        machine = elf_machine(blob)
        report(machine == expected_machine, f"{path} is a {machine} ELF (expected {expected_machine})")

        runpath = find_runpath(blob)
        report(
            runpath == f"{PREFIX}/lib",
            f"{path} DT_RUNPATH is {runpath!r}",
        )

    # pi must be the real CLI, not an empty stub.
    cli = image.read(overlay, "lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")
    report(cli is not None and len(cli) > 100, "pi CLI entry point is present and non-trivial")

    # What the reader has no other copy of (§3): the structure first, then *everything*
    # the vendored tree holds and the rule keeps — documents, `examples/`, the extension's
    # TypeScript, all of it. Both halves come from the builder, `vendor_junk` being the
    # predicate the trim itself deletes by, so the check cannot disagree with the build
    # about what ships and a release that adds a file expects it of the image without a
    # constant moving. Documents alone were checked here first, and a file dropped from
    # `examples/` passed: the rule is a delete list, so "the rest arrived" is the claim.
    for path in ANCHOR_DOCUMENTS:
        report(image.exists(path), f"present: {path}")
    for label, image_root, cache_root, drop, keep in DOCUMENTED_TREES:
        held = files_in(REPO_ROOT / cache_root)
        if held is None:
            print(
                f"  note  {label}: {cache_root} is not here, so the files the vendored "
                f"tree holds could not be compared"
            )
            continue
        expected = {
            name
            for name in held
            if BUILDER.vendor_kept(name, keep) or not BUILDER.vendor_junk(name, drop)
        }
        prefix = f"{image_root}/"
        # Both forms, because either is a correct way for the archive to carry the entry:
        # a symlinked file as its own bytes (what the writer does today) or as a
        # `SYMLINKS.txt` line. See [files_in] for the cold-CI failure that settled this.
        available = set(image.files) | set(image.symlinks)
        shipped = {path[len(prefix):] for path in available if path.startswith(prefix)}
        missing = sorted(expected - shipped)
        unexpected = sorted(shipped - expected)
        report(
            not missing and not unexpected,
            f"{label}: all {len(expected)} file(s) the tree holds are in the image"
            + (
                ""
                if not missing and not unexpected
                else f" — missing {missing[:5]}, not in the tree {unexpected[:5]}"
            ),
        )

    # The bundled environment must not reference a staging directory.
    stray = [p for p in image.symlinks if "usr-staging" in image.symlinks[p]]
    report(not stray, "no reference to the staging directory survives")

    # --- prefix relocation -------------------------------------------------
    # The whole point of the rewrite: nothing may still name the upstream
    # package, otherwise it would resolve to a directory this app cannot read.
    old_prefix_hits = 0
    url_hits = 0
    rewritten_url_hits = 0
    decompressed_old_hits = 0
    decompressed_members = 0
    bad_dex: list[str] = []
    nested_checked = 0
    authored_seen: set[str] = set()

    for archive, label in ((bootstrap, "bootstrap"), (overlay, "overlay")):
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = posixpath.normpath(info.filename).lstrip("/")
            if name in AUTHORED_MEMBERS:
                # The build's own files, which name the upstream id in their
                # comments on purpose — the relocator has to, it is what it
                # rewrites away from. They are the one exception the count below
                # cannot include, and `check_relocator` is what holds the
                # relocator to account instead.
                authored_seen.add(name)
                continue
            data = archive.read(info.filename)
            old_prefix_hits += data.count(UPSTREAM_NAME)
            url_hits += data.count(UPSTREAM_URL_NAME)
            rewritten_url_hits += data.count(REWRITTEN_URL_NAME)

            # A byte scan of a compressed member proves nothing: the payload is
            # unreadable, so a hit count of zero means "not visible", not
            # "absent". The only meaningful check decompresses first.
            decoded = decompress_content(data)
            if decoded is not None:
                decompressed_members += 1
                plain, _codec = decoded
                decompressed_old_hits += plain.count(UPSTREAM_NAME)

            # Nested archives (libexec/termux-am/am.apk) hide the prefix inside
            # a deflated classes.dex, so they need their own pass.
            if data[:4] in (b"PK\x03\x04",):
                try:
                    import io
                    import zipfile as _zip

                    with _zip.ZipFile(io.BytesIO(data)) as nested:
                        for member in nested.infolist():
                            if member.is_dir():
                                continue
                            nested_checked += 1
                            blob = nested.read(member.filename)
                            rewritten_url_hits += blob.count(REWRITTEN_URL_NAME)
                            if is_dex(blob):
                                if not dex_headers_valid(blob):
                                    bad_dex.append(f"{label}:{info.filename}!{member.filename}")
                                if blob.count(UPSTREAM_NAME):
                                    bad_dex.append(
                                        f"{label}:{info.filename}!{member.filename} still names the old app"
                                    )
                except Exception as error:  # noqa: BLE001
                    report(False, f"{label}:{info.filename} is not a readable archive ({error})")

    report(
        old_prefix_hits == 0 and decompressed_old_hits == 0,
        f"no member still names {UPSTREAM_NAME.decode()} "
        f"(raw hits {old_prefix_hits}, decompressed hits {decompressed_old_hits} "
        f"across {decompressed_members} compressed members)",
    )
    report(
        rewritten_url_hits == 0,
        f"no member rewrote a {UPSTREAM_URL_NAME.decode()} URL into "
        f"{REWRITTEN_URL_NAME.decode()} ({rewritten_url_hits} found)",
    )
    # Reported, not asserted: the number is upstream's — pi's and the bootstrap's
    # documentation and comments decide it — so a release that edits a link must not
    # fail a build over it. What matters is the line above and the docs comparison.
    print(f"  --      {url_hits} {UPSTREAM_URL_NAME.decode()} URL(s) left alone")

    # The members skipped above are only skippable while they are really there:
    # a name in the exclusion list that is absent from the image silently stops
    # covering anything, and a *typo* in it would let a genuinely unrewritten file
    # through the count.
    missing_authored = sorted(AUTHORED_MEMBERS - authored_seen)
    report(
        not missing_authored,
        f"the build's own files are present, unrewritten ({len(authored_seen)} of "
        f"{len(AUTHORED_MEMBERS)})"
        + ("" if not missing_authored else f" — missing: {missing_authored}"),
    )
    passed, message = check_relocator(overlay)
    report(passed, message)
    passed, message = check_rewrite_exclusions(overlay)
    report(passed, message)
    report(
        not bad_dex,
        f"nested archives verified ({nested_checked} members; dex headers and prefixes)"
        + ("" if not bad_dex else f" — problems: {bad_dex[:5]}"),
    )

    return ok


def main() -> int:
    global APP_ID, PREFIX
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=sorted(FLAVORS))
    parser.add_argument(
        "--app-id",
        default="pi.kit.mob",
        help="Application id the image was built for (default: pi.kit.mob)",
    )
    args = parser.parse_args()

    APP_ID = args.app_id
    PREFIX = f"/data/data/{APP_ID}/files/usr"
    print(f"verifying for application id {APP_ID} (prefix {PREFIX})")

    targets = {args.abi: FLAVORS[args.abi]} if args.abi else FLAVORS
    results = {abi: check_abi(abi, flavor) for abi, flavor in targets.items()}

    print()
    for abi, passed in results.items():
        print(f"{abi}: {'PASS' if passed else 'FAIL'}")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
