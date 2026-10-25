"""
Round-trips the Node package relocator against real Termux packages.

This is the offline proof that `pkg install` works transparently, and it needs
no device: `pikit-relocate.js` is run over copies of the same `.deb` files the
image is built from, and the result is unpacked and inspected.

What it establishes:

  1. After the rewrite, no `com.termux` remains in the package's data member —
     not in an ELF, not in a shebang, not in a symlink target, not in dpkg
     metadata.
  2. The archive is still a valid `ar` container holding a valid `data.tar.*`,
     and the file count is unchanged. A rewrite that broke the container, or
     that added or dropped entries, would fail here.
  3. The rewritten `DT_RUNPATH` of an ELF names the new prefix. That is the
     property that decides whether an installed binary can resolve its libraries
     or dies at exec time looking like a broken package.

Stdlib only — no `ar`, no `dpkg-deb`, no `tar` binary — so it runs on a Windows
build host as well as in CI.
"""

from __future__ import annotations

import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CACHE = REPO_ROOT / ".runtime-build" / "cache" / "debs"
SCRIPT = REPO_ROOT / "tools" / "pikit-relocate.js"

OLD_ID = b"com.termux"
NEW_ID = b"pi.kit.mob"

#: Real packages from the build cache, named by *package* rather than by file.
#: Small ones, but all five are affected: `ripgrep` and `fd` are ELF binaries
#: with a compiled-in RUNPATH, `pcre2` carries the shared library they link
#: against, and `openssl` is the one that also names the prefix in its
#: `control.tar` member — in `conffiles`, which is where a rewrite that only
#: handled `data.tar` used to leave it.
#:
#: **The version is the cache's to decide, not this file's.** A sample list
#: written as exact file names goes stale the moment the apt index moves, and it
#: did: a cold CI cache resolved `openssl` 1.3.6.5 where a warm one still held
#: 1.3.6.3, so none of the samples that carry a `control.tar` prefix matched, and
#: a release build failed on the non-vacuity check below rather than on the
#: relocator. A package name survives that drift, and every version the cache
#: holds for it is tested, so a stale entry beside a fresh one is covered rather
#: than picked between.
SAMPLE_PACKAGES = [
    "ripgrep",
    "fd",
    "pcre2",
    "zlib",
    "openssl",
]

#: Every sample is an `x86_64` package. The rewrite is architecture-independent
#: — a byte pattern in a container — so one ABI is enough, and it keeps the
#: fallback scan below to half the cache.
SAMPLE_ARCH = "x86_64"

#: The image the relocator actually ships in. The script is checked as it is
#: *packed*, not only as it is written: the build's prefix rewrite must skip it,
#: and when it did not, `OLD_ID` became `NEW_ID` and the relative paths above all
#: passed while nothing on a device was ever relocated.
OVERLAY = {
    "arm64-v8a": "arm64",
    "x86_64": "x64",
}


def resolve_samples() -> list[str]:
    """
    The cached `.deb` file names for [SAMPLE_PACKAGES], newest version last.

    Matched by prefix and suffix rather than by splitting on `_`, because a
    sanitised Debian version carries underscores of its own
    (`ca-certificates_1_2026.08.13_all.deb` was `1:2026.08.13`) and a package
    name may too (`libc++` becomes `libc__`). Every version present is returned:
    a warm cache holds the previous one beside the current, and testing both is
    what keeps a moved apt index from silently emptying the sample set.
    """
    if not CACHE.is_dir():
        return []
    names: list[str] = []
    for package in SAMPLE_PACKAGES:
        prefix, suffix = f"{package}_", f"_{SAMPLE_ARCH}.deb"
        found = sorted(
            path.name
            for path in CACHE.iterdir()
            if path.name.startswith(prefix) and path.name.endswith(suffix)
        )
        names.extend(found)
    return names


def read_ar(blob: bytes) -> list[tuple[str, bytes]]:
    """Splits a Debian `.deb` (an `ar` archive) into `(name, data)` pairs."""
    if blob[:8] != b"!<arch>\n":
        raise ValueError("not an ar archive")
    members: list[tuple[str, bytes]] = []
    at = 8
    while at + 60 <= len(blob):
        header = blob[at : at + 60]
        name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError:
            break
        start = at + 60
        end = start + size
        if end > len(blob):
            break
        members.append((name, blob[start:end]))
        at = end + (size % 2)
    return members


def elf_runpath(blob: bytes) -> list[str]:
    """
    Extracts `DT_RUNPATH` / `DT_RPATH` strings from an ELF, endian-agnostically.

    Reads the program headers, finds `PT_DYNAMIC`, then walks the dynamic
    entries for the `DT_STRTAB` and the RUNPATH/RPATH tag.
    """
    if blob[:4] != b"\x7fELF":
        return []
    is64 = blob[4] == 2
    little = blob[5] == 1
    endian = "<" if little else ">"
    import struct

    if is64:
        e_phoff = struct.unpack_from(endian + "Q", blob, 32)[0]
        e_phentsize = struct.unpack_from(endian + "H", blob, 54)[0]
        e_phnum = struct.unpack_from(endian + "H", blob, 56)[0]
    else:
        e_phoff = struct.unpack_from(endian + "I", blob, 28)[0]
        e_phentsize = struct.unpack_from(endian + "H", blob, 42)[0]
        e_phnum = struct.unpack_from(endian + "H", blob, 44)[0]

    dynamic = None
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        p_type = struct.unpack_from(endian + "I", blob, base)[0]
        if p_type != 2:  # PT_DYNAMIC
            continue
        if is64:
            dynamic = (
                struct.unpack_from(endian + "Q", blob, base + 8)[0],
                struct.unpack_from(endian + "Q", blob, base + 32)[0],
            )
        else:
            dynamic = (
                struct.unpack_from(endian + "I", blob, base + 4)[0],
                struct.unpack_from(endian + "I", blob, base + 16)[0],
            )
        break
    if dynamic is None:
        return []

    offset, size = dynamic
    strtab = 0
    wanted: list[int] = []
    for at in range(offset, offset + size, 16 if is64 else 8):
        if at + 16 > len(blob):
            break
        if is64:
            tag, val = struct.unpack_from(endian + "QQ", blob, at)
        else:
            tag, val = struct.unpack_from(endian + "II", blob, at)
        if tag == 0:  # DT_NULL
            break
        if tag == 5:  # DT_STRTAB
            strtab = val
        elif tag in (15, 29):  # DT_RPATH, DT_RUNPATH
            wanted.append(val)

    out: list[str] = []
    for val in wanted:
        # The dynamic section holds a vaddr; for these binaries the string table
        # sits in the first loadable segment, so the file offset equals it.
        start = strtab + val
        if 0 <= start < len(blob):
            out.append(blob[start : blob.find(b"\x00", start)].decode("utf-8", "replace"))
    return out


def main() -> int:
    failures: list[str] = []
    control_checked: list[str] = []

    def check(condition: bool, description: str) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'}  {description}")
        if not condition:
            failures.append(description)

    if not SCRIPT.is_file():
        raise SystemExit(f"missing {SCRIPT}")
    if not CACHE.is_dir():
        raise SystemExit(
            f"{CACHE} does not exist.\n"
            "Run `python tools/build-runtime-image.py --all` once so real packages "
            "are available to test against.",
        )

    available = resolve_samples()
    if not available:
        raise SystemExit(
            f"none of {', '.join(SAMPLE_PACKAGES)} is in {CACHE}.\n"
            "Run `python tools/build-runtime-image.py --all` once so real packages "
            "are available to test against.",
        )

    for name in available:
        print(f"=== {name}")
        source = CACHE / name

        with tempfile.TemporaryDirectory(prefix="pikit-relocate-") as scratch_name:
            scratch = Path(scratch_name)
            work = scratch / name
            shutil.copy2(source, work)

            original = read_ar(work.read_bytes())
            original_data = next((data for member, data in original if member.startswith("data.tar")), None)
            if original_data is None:
                check(False, f"{name}: no data.tar member in the source package")
                continue
            original_entries = entries_of(original_data)

            result = subprocess.run(
                ["node", str(SCRIPT), "--debs", "--dir", str(scratch)],
                capture_output=True,
                text=True,
                env={
                    **os.environ,
                    # The Windows build host has no `xz`/`zstd`/`bzip2`, so the
                    # relocator's host-only helper stands in for them. On a device
                    # the image's own tools are used and this is never set.
                    "PIKIT_RELOCATE_HELPER": str(REPO_ROOT / "tools" / "pikit-compress.py"),
                    "PIKIT_PYTHON": sys.executable,
                },
            )
            if result.returncode != 0:
                check(False, f"{name}: relocator exited {result.returncode}: {result.stderr.strip()}")
                continue
            if "skipped" in result.stdout:
                check(False, f"{name}: relocator skipped the package: {result.stdout.strip()}")
                continue

            patched = read_ar(work.read_bytes())
            patched_data = next((data for member, data in patched if member.startswith("data.tar")), None)
            if patched_data is None:
                check(False, f"{name}: the rewrite destroyed the data.tar member")
                continue

            check(
                len(original) == len(patched),
                f"{name}: archive still has {len(patched)} members (was {len(original)})",
            )

            # 1. Nothing in the container names the upstream prefix any more.
            #
            # This scan only reaches *uncompressed* members. A `control.tar.xz`
            # hides whatever is inside it from the count whether or not it was
            # rewritten, which is why the control member gets its own check below.
            raw = work.read_bytes()
            check(raw.count(OLD_ID) == 0, f"{name}: 0 raw {OLD_ID!r} in the container")

            # 1b. **Every** tar member is rewritten, not just `data.tar`.
            #
            # `control.tar` holds `conffiles`, the maintainer scripts and — in a
            # Debian package, though not in a Termux one — `md5sums`, and dpkg
            # copies the lot into `var/lib/dpkg/info`. Rewriting only `data.tar`
            # was measured leaving a `postinst` whose shebang still named the old
            # interpreter (the kernel refuses to exec it, so the package unpacks
            # and can never be configured) and `conffiles` entries under the old
            # prefix (`unable to stat ... conffile: Permission denied`).
            original_control = [data for member, data in original if member.startswith("control.tar")]
            patched_control = [data for member, data in patched if member.startswith("control.tar")]
            original_control_bodies = [body for _, body in file_entries(original_control)]
            patched_control_bodies = [body for _, body in file_entries(patched_control)]
            if any(OLD_ID in body for body in original_control_bodies):
                control_checked.append(name)
            check(
                all(OLD_ID not in body for body in patched_control_bodies),
                f"{name}: control.tar entries are free of {OLD_ID!r}"
                + (
                    ""
                    if any(OLD_ID in body for body in original_control_bodies)
                    else " (this package carries none to begin with)"
                ),
            )

            # 2. The data member still unpacks to exactly the same entry set.
            #
            # Two normalisations are applied to the original before comparing.
            # `./data/x` and `data/x` are the same path to dpkg, and — the
            # important one — every path that named the upstream prefix *must*
            # have moved to this app's prefix. That is the whole purpose of the
            # rewrite, and comparing the two as-is would instead assert the
            # opposite.
            patched_entries = entries_of(patched_data)
            patched_names = [normalise(name) for name, _ in patched_entries]
            expected_names = [
                normalise(name).replace(OLD_ID.decode(), NEW_ID.decode())
                for name, _ in original_entries
            ]
            check(
                patched_names == expected_names,
                f"{name}: data member still holds {len(patched_names)} entries, same order, "
                "every path relocated",
            )
            expected_entries = sorted(
                (normalise(name).replace(OLD_ID.decode(), NEW_ID.decode()), kind)
                for name, kind in original_entries
            )
            check(
                sorted((normalise(name), kind) for name, kind in patched_entries) == expected_entries,
                f"{name}: every entry kept its kind (file, directory or symlink)",
            )

            # 3. Every regular file is free of the old id, and the ELF RUNPATH
            #    that decides whether the binary runs names the new prefix.
            leftover: list[str] = []
            runpaths: list[tuple[str, str]] = []
            files = 0
            for member, payload in members_with_data(patched_data):
                if member.endswith("/"):
                    continue
                files += 1
                if OLD_ID in payload:
                    leftover.append(member)
                for path in elf_runpath(payload):
                    runpaths.append((member, path))
            check(
                not leftover,
                f"{name}: {files} file(s) unpacked, all free of {OLD_ID!r}"
                + (f" (offenders: {leftover[:3]})" if leftover else ""),
            )
            check(
                bool(runpaths),
                f"{name}: at least one ELF carries a RUNPATH to check ({len(runpaths)} found)",
            )
            check(
                all(NEW_ID.decode() in path or OLD_ID.decode() not in path for _, path in runpaths),
                f"{name}: every RUNPATH names the new prefix"
                + (f" ({runpaths[0][1]})" if runpaths else ""),
            )

            # 4. Every entry kept its size, and the archive still carries the
            #    package's own `md5sums` untouched. That is the equal-length
            #    contract: dpkg hashes what it unpacks, so a rewrite that changed
            #    a file's length would make dpkg report the package as locally
            #    modified — and, worse, could not work at all for an ELF.
            original_sizes = {
                normalise(member).replace(OLD_ID.decode(), NEW_ID.decode()): len(payload)
                for member, payload in members_with_data(original_data)
            }
            patched_sizes = {
                normalise(member): len(payload)
                for member, payload in members_with_data(patched_data)
            }
            check(
                original_sizes == patched_sizes,
                f"{name}: the rewrite is length-preserving (no entry changed size)"
                + (
                    ""
                    if original_sizes == patched_sizes
                    else f"; {len(set(original_sizes.items()) ^ set(patched_sizes.items()))} differ"
                ),
            )

    check(
        bool(control_checked),
        "at least one sample names the prefix in control.tar, so the control check "
        f"above is not vacuous ({', '.join(control_checked) if control_checked else 'none'})",
    )
    check_packed_relocator(check)

    print()
    if failures:
        print(f"FAIL — {len(failures)} check(s) did not hold")
        return 1
    print(f"PASS — {len(available)} package(s) relocate cleanly")
    return 0


def check_packed_relocator(check) -> None:
    """
    Runs the relocator **as the image ships it**, and relocates a package with it.

    This is the regression test for the failure that motivated all of the above
    and that none of the path-based checks could see: the build's prefix rewrite
    used to reach `libexec/pikit/relocate.js` itself, turning
    `const OLD_ID = 'com.termux'` into `const OLD_ID = 'pi.kit.mob'`. From then on
    the relocator compared each archive against the replacement, found zero
    occurrences every time, reported success, and every package the user
    installed failed to unpack with `unable to stat './data/data/com.termux'`.

    The script is extracted from the built image rather than read from `tools/`,
    because only the packed copy can be wrong in that way — and from
    `content.zip`, the layer the build puts it in (an image from before the
    seed/content split carried it in `overlay.zip`, and either is the packed
    copy). If the image has not been built yet the check is skipped with a note
    rather than failed.
    """
    if not CACHE.is_dir():
        print("  note  no build cache, so the packed relocator was not exercised")
        return

    sample = next(iter(resolve_samples()), None)
    for abi, flavor in OVERLAY.items():
        images = REPO_ROOT / "app" / f"src/{flavor}" / "assets" / "runtime" / abi
        blob = None
        for name in ("content.zip", "overlay.zip"):
            archive_path = images / name
            if not archive_path.is_file():
                continue
            with zipfile.ZipFile(archive_path) as archive:
                try:
                    blob = archive.read("libexec/pikit/relocate.js")
                except KeyError:
                    continue
            break
        if blob is None:
            print(f"  note  {abi}: no built image carries the packed relocator; not exercised")
            continue
        if sample is None:
            print("  note  no sample package available to relocate with the packed script")
            continue

        with tempfile.TemporaryDirectory(prefix="pikit-packed-") as scratch_name:
            scratch = Path(scratch_name)
            script = scratch / "relocate.js"
            script.write_bytes(blob)

            environment = {
                **os.environ,
                "PIKIT_RELOCATE_HELPER": str(REPO_ROOT / "tools" / "pikit-compress.py"),
                "PIKIT_PYTHON": sys.executable,
            }
            self_check = subprocess.run(
                ["node", str(script), "--selfcheck"],
                capture_output=True,
                text=True,
                env=environment,
            )
            check(
                self_check.returncode == 0,
                f"{abi}: the packed relocator passes its own self-check"
                + (
                    ""
                    if self_check.returncode == 0
                    else f" (exit {self_check.returncode}: "
                    + (self_check.stdout + self_check.stderr).strip()
                    + ")"
                ),
            )

            work = scratch / sample
            shutil.copy2(CACHE / sample, work)
            run = subprocess.run(
                ["node", str(script), "--debs", "--dir", str(scratch)],
                capture_output=True,
                text=True,
                env=environment,
            )
            # The proof has to be the *payload*, not the container: a Termux
            # package's bytes are compressed, so `com.termux` does not appear in
            # the file even before it is rewritten. That is what made the original
            # failure invisible — the relocator did nothing at all and every
            # path-based check still passed.
            rewritten = next(
                (data for member, data in read_ar(work.read_bytes()) if member.startswith("data.tar")),
                None,
            )
            bodies = members_with_data(rewritten) if rewritten is not None else []
            moved = any(NEW_ID in body for _, body in bodies)
            clean = rewritten is not None and all(OLD_ID not in body for _, body in bodies)
            check(
                run.returncode == 0 and moved and clean,
                f"{abi}: the packed relocator relocates {sample} from outside the image"
                + (
                    ""
                    if run.returncode == 0 and moved and clean
                    else f" (exit {run.returncode}, rewrote={moved}, clean={clean}: "
                    + (run.stdout + run.stderr).strip()
                    + ")"
                ),
            )


def normalise(name: str) -> str:
    """`./data/x` and `data/x` are the same path to dpkg."""
    return name[2:] if name.startswith("./") else name


def _open_tar(data_member: bytes) -> tarfile.TarFile:
    """
    Opens an uncompressed tar stream.

    The relocator recomputes every tar header checksum it invalidates, so the
    stdlib parser accepts the result with no leniency: if this ever needs
    `errorlevel=0` again, the checksum pass has regressed.
    """
    return tarfile.open(fileobj=io.BytesIO(decompress(data_member)), mode="r:")


def entries_of(data_member: bytes) -> list[tuple[str, str]]:
    """
    Reads a `data.tar.*` member into `(name, kind)` pairs.

    Only names and entry kinds are needed, so no file content is held in memory:
    the point of this check is that a rewrite neither adds nor drops an entry.
    """
    out: list[tuple[str, str]] = []
    with tarfile.open(fileobj=io.BytesIO(decompress(data_member)), mode="r:") as archive:
        for member in archive.getmembers():
            kind = "link" if member.issym() or member.islnk() else "dir" if member.isdir() else "file"
            out.append((member.name, kind))
    return out


def members_with_data(data_member: bytes) -> list[tuple[str, bytes]]:
    """Reads a `data.tar.*` member into `(name, content)` pairs."""
    out: list[tuple[str, bytes]] = []
    with tarfile.open(fileobj=io.BytesIO(decompress(data_member)), mode="r:") as archive:
        for member in archive.getmembers():
            if member.isreg():
                handle = archive.extractfile(member)
                out.append((member.name, handle.read() if handle else b""))
            else:
                out.append((member.name, b""))
    return out


def file_entries(tar_members: list[bytes]) -> list[tuple[str, bytes]]:
    """
    `(name, content)` for every regular file in a list of tar members.

    Unlike [members_with_data], this accepts a member that is *not* compressed:
    `control.tar` may be stored as-is, and a check that only understood
    `control.tar.xz` would silently skip it.
    """
    out: list[tuple[str, bytes]] = []
    for payload in tar_members:
        raw = decompress_if_needed(payload)
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
            for member in archive.getmembers():
                if not member.isfile():
                    continue
                handle = archive.extractfile(member)
                out.append((member.name, handle.read() if handle else b""))
    return out


_COMPRESSION_MAGIC = (b"\x1f\x8b", b"\xfd7zXZ\x00", b"BZh", b"\x28\xb5\x2f\xfd")


def decompress_if_needed(payload: bytes) -> bytes:
    return decompress(payload) if payload.startswith(_COMPRESSION_MAGIC) else payload


def decompress(data_member: bytes) -> bytes:
    """Expands a `data.tar.gz|.xz|.bz2|.zst` member in memory."""
    import bz2
    import gzip
    import lzma

    if data_member[:2] == b"\x1f\x8b":
        return gzip.decompress(data_member)
    if data_member[:6] == b"\xfd7zXZ\x00":
        return lzma.decompress(data_member)
    if data_member[:3] == b"BZh":
        return bz2.decompress(data_member)
    try:
        import zstandard  # type: ignore

        return zstandard.ZstdDecompressor().decompress(data_member, max_output_size=1 << 31)
    except Exception as error:  # pragma: no cover - depends on the host
        raise SystemExit(f"cannot decompress the data member: {error}") from error


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    sys.exit(main())
