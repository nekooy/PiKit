#!/usr/bin/env python3
"""
Host-side decompressor/compressor for `tools/pikit-relocate.js`.

Why this exists: on a device, `xz`, `zstd`, `gzip` and `bzip2` all ship inside
the bundled Termux image (`bin/xz`, `bin/zstd`, …), so the relocator just calls
them. A Windows build host has none of them, which would make the `.deb` path —
the one that actually matters for `pkg install` — untestable.

`pikit-relocate.js` uses this only when `PIKIT_RELOCATE_HELPER` points at it.
That variable is set by `tools/test-relocate.py` and by nothing else: production
never takes this path, so a missing file here cannot affect the app.

Contract, deliberately file-based rather than stdin/stdout so that a confined
sandbox cannot break it with a pipe restriction:

    pikit-compress.py decompress <in> <out>
    pikit-compress.py compress   <in> <out>
    pikit-compress.py available  <suffix>
"""

from __future__ import annotations

import bz2
import gzip
import lzma
import sys
from pathlib import Path


def decompress(source: Path, target: Path) -> None:
    blob = source.read_bytes()
    if blob[:2] == b"\x1f\x8b":
        data = gzip.decompress(blob)
    elif blob[:6] == b"\xfd7zXZ\x00" or blob[:3] == b"BZh":
        # `lzma` handles both the .xz container and raw LZMA alone.
        try:
            data = lzma.decompress(blob)
        except lzma.LZMAError:
            data = bz2.decompress(blob)
    else:
        data = _zstd().ZstdDecompressor().decompress(blob, max_output_size=1 << 31)
    target.write_bytes(data)


def compress(source: Path, target: Path, suffix: str) -> None:
    blob = source.read_bytes()
    if suffix in (".gz", ""):
        target.write_bytes(gzip.compress(blob, 9))
    elif suffix == ".xz":
        target.write_bytes(lzma.compress(blob, format=lzma.FORMAT_XZ, preset=6))
    elif suffix == ".lzma":
        target.write_bytes(lzma.compress(blob, format=lzma.FORMAT_ALONE, preset=6))
    elif suffix == ".bz2":
        target.write_bytes(bz2.compress(blob, 9))
    else:
        import io

        buffer = io.BytesIO()
        with _zstd().ZstdCompressor(level=19) as compressor:
            with compressor.stream_writer(buffer) as writer:
                writer.write(blob)
        target.write_bytes(buffer.getvalue())


def _zstd():
    import zstandard  # type: ignore

    return zstandard


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2

    command = argv[0]
    if command == "available":
        return 0
    if command == "decompress":
        decompress(Path(argv[1]), Path(argv[2]))
        return 0
    if command == "compress":
        compress(Path(argv[1]), Path(argv[2]), argv[3] if len(argv) > 3 else ".gz")
        return 0

    print(f"unknown command: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
