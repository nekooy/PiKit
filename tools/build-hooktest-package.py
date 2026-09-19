"""
Builds a synthetic `.deb` that deliberately carries the upstream prefix.

Used to test the on-device relocation hook where nothing else can: every package
in the Termux repository is either already in the bootstrap or unreachable from a
network-less emulator, so the only way to watch the hook do its job is to hand it
a package that obviously needs the job done.

The payload embeds `/data/data/com.termux/files/usr` in three places that the
relocator has to handle differently:

  * a **shebang**, which is file content and must be rewritten;
  * a **symlink target**, which is filesystem metadata and cannot be reached by a
    byte scan at all;
  * an **ELF-ish blob**, standing in for a binary whose `DT_RUNPATH` is compiled
    in.

After `apt install`, all three must name `pi.kit.mob`, and the package must be
absent from the repository cache. A hook that ran *after* dpkg would leave the
installed files naming `com.termux`.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import sys
import tarfile
import time
from pathlib import Path

OLD = "/data/data/com.termux/files/usr"
NEW = "/data/data/pi.kit.mob/files/usr"
NEW_ID = "pi.kit.mob"
PACKAGE = "pikit-hooktest"
VERSION = "1.0.0"
ARCH = "x86_64"

SHEBANG_SCRIPT = f"""#!{OLD}/bin/sh
# Installed by the relocation-hook test. The interpreter path above is the one
# Termux packages are compiled with, and it does not exist on this device.
echo "hooktest ran"
"""

# Stands in for an ELF: the relocator does not need valid machine code to rewrite
# a path, and a real binary would only make the test harder to read.
BINARY_BLOB = (
    b"\x7fELF\x02\x01\x01\x00"
    + b"\x00" * 24
    + f"{OLD}/lib".encode()
    + b"\x00" * 8
    + b"pikit hooktest payload"
    + b"\x00" * 8
)

CONTROL = f"""Package: {PACKAGE}
Version: {VERSION}
Architecture: {ARCH}
Maintainer: PiKit test <nobody@example.com>
Section: utils
Priority: optional
Description: Synthetic package that carries the upstream Termux prefix
 Used to prove that the apt relocation hook rewrites a downloaded .deb before
 dpkg unpacks it.
"""


def write_ar(members: list[tuple[str, bytes]]) -> bytes:
    out = [b"!<arch>\n"]
    for name, data in members:
        # A 60-byte ar header: name, mtime, uid, gid, mode, size, magic.
        header = (
            f"{name + '/':<16}"
            f"{int(time.time()):<12}"
            f"{'0':<6}"
            f"{'0':<6}"
            f"{'100644':<8}"
            f"{len(data):<10}"
            "`\n"
        ).encode("ascii")
        assert len(header) == 60, len(header)
        out.append(header)
        out.append(data)
        if len(data) % 2:
            out.append(b"\n")
    return b"".join(out)


def tar_gz(entries: list[tuple[tarfile.TarInfo, bytes]]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for info, data in entries:
            archive.addfile(info, io.BytesIO(data) if data else None)
    return gzip.compress(buffer.getvalue(), 9)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out_dir = Path(sys.argv[1])
    repo = out_dir / ARCH / "repo"
    repo.mkdir(parents=True, exist_ok=True)

    # --- control ---------------------------------------------------------
    control_info = tarfile.TarInfo("./control")
    control_blob = CONTROL.encode()
    control_info.size = len(control_blob)
    control_info.mode = 0o644
    control_tar = tar_gz([(control_info, control_blob)])

    # --- data ------------------------------------------------------------
    # A Debian data member's names are paths relative to the filesystem root, and
    # dpkg unpacks them there. So these already name *this* app's prefix: the
    # rewriting the package needs is of the paths *inside* its files, which is the
    # part no unpacker could do.
    entry_root = f"./data/data/{NEW_ID}/files/usr"
    entries: list[tuple[tarfile.TarInfo, bytes]] = []
    seen_dirs: set[str] = set()

    def add_directories(name: str) -> None:
        """
        Records every parent directory as a tar directory entry.

        Not optional: a `.deb` that lists `share/doc/pkg/README` without listing
        `share/`, `share/doc/` and `share/doc/pkg/` makes dpkg fail with
        `unable to create '.../README.dpkg-new': No such file or directory`,
        because dpkg's extraction assumes the archive carried its directories.
        Real packages always do; a hand-built one has to be told to.
        """
        parts = name.split("/")[:-1]
        for depth in range(1, len(parts) + 1):
            relative = "/".join(parts[:depth])
            if relative in seen_dirs:
                continue
            seen_dirs.add(relative)
            info = tarfile.TarInfo(f"{entry_root}/{relative}")
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            info.size = 0
            entries.append((info, b""))

    def add(name: str, blob: bytes, mode: int = 0o644) -> None:
        add_directories(name)
        info = tarfile.TarInfo(f"{entry_root}/{name}")
        info.size = len(blob)
        info.mode = mode
        entries.append((info, blob))

    add("bin/pikit-hooktest", SHEBANG_SCRIPT.encode(), 0o755)
    add("lib/pikit-hooktest-payload", BINARY_BLOB)
    add("share/doc/pikit-hooktest/README", b"see the control file\n")

    add_directories("lib/pikit-hooktest-link")
    link_info = tarfile.TarInfo(f"{entry_root}/lib/pikit-hooktest-link")
    link_info.type = tarfile.SYMTYPE
    link_info.linkname = f"{OLD}/lib/pikit-hooktest-payload"
    link_info.mode = 0o777
    entries.append((link_info, b""))

    data_tar = tar_gz(entries)

    deb = write_ar(
        [
            ("debian-binary", b"2.0\n"),
            ("control.tar.gz", control_tar),
            ("data.tar.gz", data_tar),
        ]
    )
    deb_path = repo / f"{PACKAGE}_{VERSION}_{ARCH}.deb"
    deb_path.write_bytes(deb)

    # --- index -----------------------------------------------------------
    stanzas = [
        f"Package: {PACKAGE}",
        f"Version: {VERSION}",
        f"Architecture: {ARCH}",
        "Maintainer: PiKit test <nobody@example.com>",
        "Section: utils",
        "Priority: optional",
        f"Filename: {deb_path.name}",
        f"Size: {len(deb)}",
        f"SHA256: {hashlib.sha256(deb).hexdigest()}",
        "Description: Synthetic package that carries the upstream Termux prefix",
        "",
    ]
    (repo / "Packages").write_text("\n".join(stanzas), encoding="utf-8", newline="\n")

    print(f"built {deb_path} ({len(deb)} bytes)")
    print(f"payload names {OLD} in a shebang, an ELF blob and a symlink target")
    return 0


if __name__ == "__main__":
    sys.exit(main())
