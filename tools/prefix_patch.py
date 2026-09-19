#!/usr/bin/env python3
"""
Equal-length prefix rewriting for the bundled Termux environment.

Termux packages are compiled against the absolute prefix
`/data/data/com.termux/files/usr`, and 708 files in the official bootstrap
contain that string — 362 of them ELF binaries whose `DT_RUNPATH` points at
`$PREFIX/lib`, plus 149 scripts whose shebang names the absolute interpreter.

Because `com.termux` and `pi.kit.mob` are both exactly 10 bytes, replacing the
dot form throughout a file is length-preserving: every byte offset, ELF section
offset and internal string length is unchanged, so no recompilation and no
`patchelf` pass is needed. That is the whole trick, and it is why the
replacement target must stay 10 characters.

Design notes, each of which corresponds to a bug found by measurement:

* **`com/termux` must never be replaced.** All 193 occurrences of the slash
  form are `github.com/termux/...` URLs inside comments and dpkg metadata;
  rewriting them corrupts documentation and package descriptions.
* **Compressed files are skipped.** Their contents are not visible to a byte
  scan, and editing the bytes would destroy the stream. Measured: zero
  compressed files in the official bootstrap contain the prefix.
* **Symlinks are metadata, not file contents.** An absolute link target is not
  reachable by any byte-level file edit, so links are retargeted explicitly.
* **`libexec/termux-am/am.apk` hides two occurrences inside a deflated
  `classes.dex`.** Those are patched by unpacking the nested archive, and the
  dex header's Adler-32 checksum and SHA-1 signature are recomputed because
  changing bytes invalidates both.
* **dpkg records hashes of the files it installed.** Every patched file
  invalidates its entry in `var/lib/dpkg/info/*.md5sums` and the conffile
  hashes in `var/lib/dpkg/status`. Both are fixed-width hex, so regenerating
  them is also length-preserving.

Used both as a library by `build-runtime-image.py` and as a CLI:

    python tools/prefix_patch.py <tree> --app-id pi.kit.mob [--dry-run]
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import struct
import sys
import zipfile
import zlib
from dataclasses import dataclass, field
from pathlib import Path

#: The string baked into Termux packages. Never change this without also
#: changing the replacement to the same length.
OLD_NAME = b"com.termux"

#: A replacement must be exactly as long as OLD_NAME. Asserted at runtime.
REQUIRED_NAME_LENGTH = len(OLD_NAME)

#: Compression containers whose bytes must not be edited in place.
COMPRESSED_MAGIC: tuple[tuple[bytes, str], ...] = (
    (b"\x1f\x8b", "gzip"),
    (b"\xfd7zXZ\x00", "xz"),
    (b"\x28\xb5\x2f\xfd", "zstd"),
    (b"BZh", "bzip2"),
    (b"\x04\x22\x4d\x18", "lz4"),
    (b"\x5d\x00\x00", "lzma"),
)

#: Zip containers are not skipped: they are opened and patched member-wise.
ZIP_MAGIC = (b"PK\x03\x04", b"PK\x05\x06")

#: Executable bit is applied to these prefixes by the on-device installer.
EXECUTABLE_PREFIXES = ("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")

#: Files the build writes itself, which must never go through the prefix rewrite.
#:
#: Every one of them is *authored* naming this app's id — their shebangs, their
#: `$PREFIX` uses and their apt hook all use it already — so the rewrite has
#: nothing to do except in the comments, where the upstream id has to appear to
#: explain what is being relocated at all.
#:
#: Rewriting those comments is not cosmetic. It turned `relocate.js`'s
#: `const OLD_ID = 'com.termux'` into `const OLD_ID = 'pi.kit.mob'`, so the
#: relocator compared the archive against its own replacement, found zero
#: occurrences in every package, reported success, and every package installed
#: from the Termux repository failed to unpack with `unable to stat
#: './data/data/com.termux'`. It also produced comments that contradicted
#: themselves — "works because `pi.kit.mob` and `pi.kit.mob` are both exactly 10
#: bytes" — which is how the report found it.
#:
#: The bootstrap's `bin/dpkg` is here for the same reason: it is injected from
#: `tools/pikit-dpkg.sh`, its shebang already names this app, and its comments
#: name the upstream prefix. Its renamed original, `bin/dpkg.real`, is *not*
#: here, and is rewritten like any other compiled file.
#:
#: Kept in step with `verify-runtime-image.py`, which imports this set and allows
#: exactly these members to name the old application while asserting that nothing
#: else does.
NEVER_REWRITE: frozenset[str] = frozenset(
    {
        "bin/dpkg",
        "bin/pi",
        "bin/pikit-relocate",
        "bin/pikit-storage-check",
        "etc/apt/apt.conf.d/99pikit-relocate",
        "libexec/pikit/relocate.js",
        "share/pikit/pi-safety-guard.ts",
        "share/pikit/storage-self-test.sh",
    }
)


def is_authored(relative: str) -> bool:
    """True for a path the build wrote itself, in either `x/y` or `./x/y` form."""
    return (relative[2:] if relative.startswith("./") else relative.lstrip("/")) in NEVER_REWRITE

#: Where dpkg keeps per-package file lists and hashes.
DPKG_INFO_DIR = "var/lib/dpkg/info"
DPKG_STATUS = "var/lib/dpkg/status"

DEX_MAGICS = (b"dex\n035\x00", b"dex\n036\x00", b"dex\n037\x00", b"dex\n038\x00", b"dex\n039\x00", b"dex\n040\x00")


def prefix_for(app_id: str) -> str:
    """The absolute prefix a runtime image for `app_id` is built for."""
    return f"/data/data/{app_id}/files/usr"


def old_prefix() -> str:
    return prefix_for("com.termux")


def replacement_for(app_id: str) -> bytes:
    if len(app_id.encode()) != REQUIRED_NAME_LENGTH:
        raise ValueError(
            f"application id {app_id!r} is {len(app_id)} characters, but the "
            f"equal-length rewrite requires exactly {REQUIRED_NAME_LENGTH}.\n"
            "A different length shifts every ELF section offset and corrupts "
            "the binary; pick a 10-character id (for example 'pi.kit.mob')."
        )
    return app_id.encode()


# --------------------------------------------------------------------------- #
# Classification
# --------------------------------------------------------------------------- #


def is_elf(blob: bytes) -> bool:
    return blob[:4] == b"\x7fELF"


def is_dex(blob: bytes) -> bool:
    return blob[:8] in DEX_MAGICS


def is_zip(blob: bytes) -> bool:
    return blob[:4] in ZIP_MAGIC


def compression_of(blob: bytes) -> str | None:
    """Names the compression container, or None for an uncompressed blob."""
    for magic, name in COMPRESSED_MAGIC:
        if blob.startswith(magic):
            return name
    return None


def decompress_content(blob: bytes) -> tuple[bytes, str] | None:
    """
    Returns `(plaintext, codec)` for a supported compressed container.

    A byte scan over a compressed member proves nothing: the payload is
    unreadable, so searching the *compressed* bytes for the prefix always comes
    back empty whether or not it is in there. The only meaningful check is to
    decompress first.
    """
    import bz2
    import gzip
    import io
    import lzma

    try:
        if blob[:2] == b"\x1f\x8b":
            return gzip.decompress(blob), "gzip"
        if blob[:6] == b"\xfd7zXZ\x00":
            return lzma.decompress(blob, format=lzma.FORMAT_XZ), "xz"
        if blob[:3] == b"BZh":
            return bz2.decompress(blob), "bzip2"
        if blob[:4] == b"\x28\xb5\x2f\xfd":
            import zstandard

            reader = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(blob))
            return reader.read(), "zstd"
    except Exception:  # noqa: BLE001 - unreadable containers are left alone
        return None
    return None


def compress_content(plain: bytes, codec: str) -> bytes:
    """Re-encodes `plain` with the codec it arrived in."""
    import bz2
    import gzip
    import io
    import lzma

    if codec == "gzip":
        buffer = io.BytesIO()
        # mtime=0 keeps the output byte-stable across builds.
        with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as handle:
            handle.write(plain)
        return buffer.getvalue()
    if codec == "xz":
        return lzma.compress(plain, format=lzma.FORMAT_XZ)
    if codec == "bzip2":
        return bz2.compress(plain)
    if codec == "zstd":
        import zstandard

        return zstandard.ZstdCompressor().compress(plain)
    raise ValueError(f"unknown codec {codec!r}")


def patch_container(data: bytes, replacement: bytes, stats: Stats) -> bytes:
    """
    Rewrites the prefix inside a compressed member.

    Decompressing and recompressing is safe here because the length-preserving
    guarantee applies to the *payload*: an ELF inside a `.gz` keeps every offset
    it had. The compressed length does change, which is why dpkg's recorded
    hashes are regenerated afterwards.

    Members with nothing to change are returned untouched, so only the handful
    that actually name the prefix are re-encoded.
    """
    decoded = decompress_content(data)
    if decoded is None:
        # Undecodable container: leave it exactly as it is rather than risk
        # corrupting it, and surface the fact in the stats.
        stats.skipped_compressed += 1
        return data
    plain, codec = decoded
    patched, count = rewrite(plain, replacement)
    if count == 0:
        return data
    stats.compressed_patched += 1
    stats.occurrences += count
    return compress_content(patched, codec)


def executable_bit_required(relative: str) -> bool:
    return relative.startswith(EXECUTABLE_PREFIXES)


# --------------------------------------------------------------------------- #
# Core rewrite
# --------------------------------------------------------------------------- #


def rewrite(blob: bytes, replacement: bytes) -> tuple[bytes, int]:
    """
    Replaces the dot form, asserting the length is preserved.

    Returns the new blob and the number of replacements. Raises if the length
    would change, which can only happen if OLD_NAME or `replacement` is wrong.
    """
    if len(replacement) != REQUIRED_NAME_LENGTH:
        raise ValueError("replacement must be the same length as the original")
    count = blob.count(OLD_NAME)
    if count == 0:
        return blob, 0
    patched = blob.replace(OLD_NAME, replacement)
    if len(patched) != len(blob):
        raise AssertionError(
            "rewrite changed the file length; this would corrupt ELF offsets"
        )
    return patched, count


def recompute_dex_headers(blob: bytes) -> bytes:
    """
    Rebuilds a dex file's Adler-32 checksum and SHA-1 signature.

    Any byte edit invalidates both, and ART may refuse to load a dex whose
    headers disagree with its contents. Both fields are fixed width, so the
    file length is unchanged.

    Order matters: the signature covers everything from offset 32, and the
    checksum covers everything from offset 12 — *including the signature*. So
    the signature must be written first, and the checksum computed last.
    """
    if not is_dex(blob) or len(blob) < 32:
        return blob
    buffer = bytearray(blob)
    buffer[12:32] = hashlib.sha1(bytes(buffer[32:])).digest()
    struct.pack_into("<I", buffer, 8, zlib.adler32(bytes(buffer[12:])) & 0xFFFFFFFF)
    return bytes(buffer)


def dex_headers_valid(blob: bytes) -> bool:
    """True when a dex file's checksum and signature match its contents."""
    if not is_dex(blob) or len(blob) < 32:
        return False
    expected_checksum = struct.unpack_from("<I", blob, 8)[0]
    if expected_checksum != (zlib.adler32(blob[12:]) & 0xFFFFFFFF):
        return False
    return blob[12:32] == hashlib.sha1(blob[32:]).digest()


@dataclass
class Stats:
    files_scanned: int = 0
    files_patched: int = 0
    occurrences: int = 0
    symlinks_patched: int = 0
    archives_unpacked: int = 0
    dex_files_fixed: int = 0
    skipped_compressed: int = 0
    compressed_patched: int = 0
    dpkg_records_fixed: int = 0
    authored_preserved: int = 0
    errors: list[str] = field(default_factory=list)

    def note(self) -> str:
        return (
            f"scanned={self.files_scanned} patched={self.files_patched} "
            f"occurrences={self.occurrences} symlinks={self.symlinks_patched} "
            f"nested_archives={self.archives_unpacked} dex={self.dex_files_fixed} "
            f"compressed={self.compressed_patched} unreadable={self.skipped_compressed} "
            f"dpkg_records={self.dpkg_records_fixed} authored={self.authored_preserved}"
        )


def patch_nested_archive(blob: bytes, replacement: bytes, stats: Stats) -> bytes:
    """
    Rewrites the prefix inside a zip/apk container member-wise.

    `libexec/termux-am/am.apk` carries `com.termux` inside a deflated
    `classes.dex`, which a flat byte scan cannot see. A brute-force byte
    replace over the whole container would corrupt the deflate streams, so the
    archive is unpacked, each member patched, and the archive rebuilt.
    """
    import io

    source = io.BytesIO(blob)
    if not zipfile.is_zipfile(source):
        return blob
    source.seek(0)

    changed = False
    buffer = io.BytesIO()
    with zipfile.ZipFile(source, "r") as archive, \
            zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as rebuilt:
        for info in archive.infolist():
            data = archive.read(info.filename)
            patched, count = rewrite(data, replacement)
            if count:
                changed = True
                stats.occurrences += count
                if is_dex(patched):
                    patched = recompute_dex_headers(patched)
                    # Only count a freshly broken header as a repair.
                    stats.dex_files_fixed += 1
            # Preserve the original storage method where it matters.
            compression = (
                zipfile.ZIP_STORED
                if info.compress_type == zipfile.ZIP_STORED
                else zipfile.ZIP_DEFLATED
            )
            rebuilt.writestr(info.filename, patched, compress_type=compression)

    if not changed:
        return blob
    stats.archives_unpacked += 1
    return buffer.getvalue()


def patch_file_on_disk(
    path: Path,
    relative: str,
    replacement: bytes,
    stats: Stats,
    *,
    dry_run: bool = False,
) -> None:
    """Patches one regular file, writing atomically via a temp file + rename."""
    try:
        blob = path.read_bytes()
    except OSError as error:
        stats.errors.append(f"{relative}: {error}")
        return

    stats.files_scanned += 1

    if compression_of(blob) is not None:
        patched = patch_container(blob, replacement, stats)
        if patched is not blob:
            stats.files_patched += 1
            if not dry_run:
                write_atomic(path, patched)
        return

    if is_zip(blob):
        patched = patch_nested_archive(blob, replacement, stats)
        if patched is not blob:
            stats.files_patched += 1
            if not dry_run:
                write_atomic(path, patched)
        return

    patched, count = rewrite(blob, replacement)
    if count == 0:
        return

    if is_dex(patched):
        patched = recompute_dex_headers(patched)
        stats.dex_files_fixed += 1

    stats.files_patched += 1
    stats.occurrences += count
    if not dry_run:
        write_atomic(path, patched)


def write_atomic(path: Path, blob: bytes) -> None:
    temp = path.with_name(path.name + ".patch-tmp")
    temp.write_bytes(blob)
    os.chmod(temp, path.stat().st_mode & 0o7777)
    os.replace(temp, path)


# --------------------------------------------------------------------------- #
# Symlinks
# --------------------------------------------------------------------------- #


def patch_symlink(
    path: Path,
    replacement: bytes,
    stats: Stats,
    *,
    dry_run: bool = False,
) -> None:
    """
    Retargets a symlink whose target names the old prefix.

    A link target is filesystem metadata, not file content, so no amount of
    byte scanning over regular files will find it. Absolute targets are
    rewritten; relative targets are left alone because they resolve inside the
    tree and are already correct.
    """
    try:
        target = os.readlink(path)
    except OSError as error:
        stats.errors.append(f"{path}: {error}")
        return

    if not target.startswith("/") or OLD_NAME not in target.encode():
        return

    patched_target = target.encode().replace(OLD_NAME, replacement).decode()
    stats.symlinks_patched += 1
    if dry_run:
        return
    os.unlink(path)
    os.symlink(patched_target, path)


def patch_symlinks_in_text(
    text: str,
    replacement: bytes,
    stats: Stats,
) -> str:
    """
    Rewrites `SYMLINKS.txt`, the side-car that lists links the zip format
    cannot carry. Patching this at build time is strictly better than fixing
    links after the fact, because the installer then creates them correctly.
    """
    out_lines: list[str] = []
    for line in text.splitlines():
        if "\u2190" in line:
            target, path = line.split("\u2190", 1)
            if OLD_NAME in target.encode():
                target = target.encode().replace(OLD_NAME, replacement).decode()
                stats.symlinks_patched += 1
            line = f"{target}\u2190{path}"
        out_lines.append(line)
    return "\n".join(out_lines) + ("\n" if text.endswith("\n") else "")


# --------------------------------------------------------------------------- #
# dpkg bookkeeping
# --------------------------------------------------------------------------- #


#: Matches any Termux-style prefix, with or without a leading slash.
#:
#: dpkg's `.md5sums` files record paths *without* a leading slash
#: (`data/data/com.termux/files/usr/bin/bash`), while `Conffiles:` entries are
#: absolute. Both forms have to be reducible to a prefix-relative path.
ANY_PREFIX_RE = re.compile(r"^/?data/data/[^/]+/files/usr/")


def _relative_to_prefix(path: str) -> str:
    return ANY_PREFIX_RE.sub("", path)


def _fix_md5sums_text(text: str, read_file, stats: Stats) -> str:
    """Rewrites the recorded md5 of every file whose bytes we changed."""
    lines: list[str] = []
    for line in text.splitlines():
        match = re.match(r"^([0-9a-f]{32})  (.+)$", line)
        if not match:
            lines.append(line)
            continue
        recorded, recorded_path = match.group(1), match.group(2)
        data = read_file(_relative_to_prefix(recorded_path))
        if data is None:
            lines.append(line)
            continue
        actual = hashlib.md5(data).hexdigest()
        if actual != recorded:
            stats.dpkg_records_fixed += 1
        lines.append(f"{actual}  {recorded_path}")
    return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def _fix_status_text(text: str, read_file, stats: Stats) -> str:
    """
    Rewrites the conffile md5s dpkg records in its status database.

    Without this, dpkg considers the patched conffiles under `etc/` to be
    locally edited and prompts keep-or-replace on the next `pkg upgrade`.
    """

    def fix(match: re.Match[str]) -> str:
        path_line, hash_line = match.group(1), match.group(2)
        absolute = path_line.strip().split(" ", 1)[0]
        data = read_file(_relative_to_prefix(absolute))
        if data is None:
            return match.group(0)
        actual = hashlib.md5(data).hexdigest()
        if actual != hash_line.strip():
            stats.dpkg_records_fixed += 1
        return f"{path_line}\n {actual}\n"

    return re.sub(r"( /[^\n]*)\n ([0-9a-f]{32})\n", fix, text)


def refresh_dpkg_hashes(root: Path, stats: Stats, *, dry_run: bool = False) -> None:
    """
    Regenerates dpkg's recorded hashes for a tree that was patched on disk.

    An md5 is 32 hex characters whatever its value, so rewriting these records
    stays length-preserving as well.
    """

    def read_file(relative: str) -> bytes | None:
        candidate = root / relative
        try:
            return candidate.read_bytes() if candidate.is_file() else None
        except OSError:
            return None

    info_dir = root / DPKG_INFO_DIR
    if info_dir.is_dir():
        for md5sums in sorted(info_dir.glob("*.md5sums")):
            try:
                original = md5sums.read_text(encoding="utf-8", errors="replace")
            except OSError as error:
                stats.errors.append(f"{md5sums}: {error}")
                continue
            patched = _fix_md5sums_text(original, read_file, stats)
            if patched != original and not dry_run:
                write_atomic(md5sums, patched.encode())

    status = root / DPKG_STATUS
    if status.is_file():
        try:
            original = status.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            stats.errors.append(f"{status}: {error}")
            return
        patched = _fix_status_text(original, read_file, stats)
        if patched != original and not dry_run:
            write_atomic(status, patched.encode())


# --------------------------------------------------------------------------- #
# Tree walk
# --------------------------------------------------------------------------- #


def patch_tree(
    root: Path,
    app_id: str,
    *,
    dry_run: bool = False,
    skip_dpkg: bool = False,
) -> Stats:
    """Rewrites the prefix throughout an extracted runtime tree."""
    replacement = replacement_for(app_id)
    stats = Stats()

    for current_dir, dir_names, file_names in os.walk(root):
        directory = Path(current_dir)
        dir_names.sort()
        file_names.sort()

        for name in file_names:
            absolute = directory / name
            relative = absolute.relative_to(root).as_posix()

            # Files the build authored are left exactly as written: they already
            # name this app's id, and the upstream id in their comments is the
            # documentation for what the rewrite is for. See NEVER_REWRITE.
            if is_authored(relative):
                stats.authored_preserved += 1
                continue

            if absolute.is_symlink():
                patch_symlink(absolute, replacement, stats, dry_run=dry_run)
                continue

            if name == "SYMLINKS.txt":
                text = absolute.read_text(encoding="utf-8", errors="replace")
                patched = patch_symlinks_in_text(text, replacement, stats)
                if patched != text and not dry_run:
                    write_atomic(absolute, patched.encode())
                continue

            patch_file_on_disk(absolute, relative, replacement, stats, dry_run=dry_run)

    if not skip_dpkg:
        refresh_dpkg_hashes(root, stats, dry_run=dry_run)

    return stats


def patch_archive(
    source: Path,
    destination: Path,
    app_id: str,
    *,
    symlinks_member: str = "SYMLINKS.txt",
    extra_members: dict[str, tuple[bytes, int]] | None = None,
    replaces_members: dict[str, str] | None = None,
) -> Stats:
    """
    Rewrites every member of a zip archive, including the symlink side-car.

    Used for the bootstrap, which ships as an opaque blob: patching it at build
    time means the on-device installer creates correctly-targeted symlinks from
    the start, instead of creating broken ones and repairing them afterwards.

    `extra_members` adds files that are not in the source at all, mapping a member
    name to `(content, unix mode)`. `replaces_members` renames an existing member,
    mapping old name to new. Both exist so the bootstrap can carry a wrapper
    around a binary it ships -- `bin/dpkg` -> `bin/dpkg.real` plus a new `bin/dpkg`
    -- without the archive extraction the overlay path uses, which the bootstrap
    deliberately does not go through.
    """
    replacement = replacement_for(app_id)
    stats = Stats()
    renames = replaces_members or {}

    with zipfile.ZipFile(source, "r") as archive:
        infos = archive.infolist()
        contents: list[tuple[zipfile.ZipInfo, bytes]] = []
        for info in infos:
            data = archive.read(info.filename)
            # The *destination* name decides, not the source: `bin/dpkg` is renamed
            # to `bin/dpkg.real`, and it is the real dpkg binary that has to be
            # rewritten while the wrapper injected under the original name is not.
            if is_authored(renames.get(info.filename, info.filename)):
                stats.authored_preserved += 1
                contents.append((info, data))
                continue
            if info.filename in renames:
                # Copy the entry under its new name, and rewrite it like any other
                # member. The rewrite is the *point*: `bin/dpkg` carries the
                # upstream prefix in seven places, so a rename that skipped it
                # would ship a binary whose `DT_RUNPATH` points at a directory this
                # app cannot read. The mode and the compression come along, so the
                # renamed file is indistinguishable from the original as far as the
                # installer is concerned.
                data, count = rewrite(data, replacement)
                if count:
                    stats.files_patched += 1
                    stats.occurrences += count
                renamed = zipfile.ZipInfo(renames[info.filename], date_time=info.date_time)
                renamed.external_attr = info.external_attr
                renamed.compress_type = info.compress_type
                contents.append((renamed, data))
                continue
            if info.filename == symlinks_member:
                text = data.decode("utf-8", errors="replace")
                data = patch_symlinks_in_text(text, replacement, stats).encode()
            elif not info.is_dir():
                stats.files_scanned += 1
                if compression_of(data) is not None:
                    patched = patch_container(data, replacement, stats)
                    if patched is not data:
                        stats.files_patched += 1
                    data = patched
                elif is_zip(data):
                    data = patch_nested_archive(data, replacement, stats)
                else:
                    data, count = rewrite(data, replacement)
                    if count:
                        stats.files_patched += 1
                        stats.occurrences += count
                        if is_dex(data):
                            data = recompute_dex_headers(data)
                            stats.dex_files_fixed += 1
            contents.append((info, data))

    # Injected members are added *before* the dpkg bookkeeping pass, and they go
    # through the same rewrite as everything else: a wrapper is a file in the
    # image like any other, and one that mentions the upstream prefix in a comment
    # is a file the verifier would rightly reject. The order also matters for
    # `_fix_md5sums_text`, which reads members by name.
    for name, (content, mode) in (extra_members or {}).items():
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        # `external_attr` carries the Unix mode in its top 16 bits, which is how
        # the installer decides whether an entry is executable.
        info.external_attr = (mode & 0xFFFF) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        if is_authored(name):
            # Authored, so it names the upstream id only in its comments; see
            # NEVER_REWRITE. Rewriting it here is what broke the relocator.
            stats.authored_preserved += 1
            data = content
            count = 0
        else:
            data, count = rewrite(content, replacement)
        if count:
            stats.files_patched += 1
            stats.occurrences += count
        contents = [(i, d) for i, d in contents if i.filename != name]
        contents.append((info, data))

    # dpkg records md5s of the files it installed, and that database lives in
    # this same archive, so it has to be regenerated from the patched bytes.
    # Without it `dpkg --verify` reports mass modification, and dpkg treats the
    # nine patched conffiles under `etc/` as locally edited.
    by_name = {info.filename: data for info, data in contents}

    def read_member(relative: str) -> bytes | None:
        return by_name.get(relative)

    for index, (info, data) in enumerate(contents):
        name = info.filename
        if name.startswith(DPKG_INFO_DIR + "/") and name.endswith(".md5sums"):
            text = data.decode("utf-8", errors="replace")
            contents[index] = (info, _fix_md5sums_text(text, read_member, stats).encode())
        elif name == DPKG_STATUS:
            text = data.decode("utf-8", errors="replace")
            contents[index] = (info, _fix_status_text(text, read_member, stats).encode())

    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as rebuilt:
        for info, data in contents:
            if info.is_dir():
                rebuilt.writestr(info.filename, b"")
                continue
            compression = (
                zipfile.ZIP_STORED
                if info.compress_type == zipfile.ZIP_STORED
                else zipfile.ZIP_DEFLATED
            )
            rebuilt.writestr(info, data, compress_type=compression)

    return stats


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tree", type=Path, help="Extracted runtime tree to rewrite")
    parser.add_argument("--app-id", default="pi.kit.mob")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-dpkg", action="store_true")
    args = parser.parse_args()

    if not args.tree.is_dir():
        print(f"not a directory: {args.tree}", file=sys.stderr)
        return 2

    stats = patch_tree(
        args.tree, args.app_id, dry_run=args.dry_run, skip_dpkg=args.skip_dpkg
    )
    print(("would patch" if args.dry_run else "patched") + ": " + stats.note())
    for error in stats.errors[:20]:
        print(f"  error: {error}", file=sys.stderr)
    return 1 if stats.errors else 0


if __name__ == "__main__":
    sys.exit(main())
