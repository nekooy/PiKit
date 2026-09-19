"""
Summarises an Android method trace: which methods the main thread actually spent
its time in.

Used to answer "what does a first tap on the composer cost?" without guessing.
The trace format is undocumented but stable enough:

  * a `*version` line, then key=value metadata, then `*threads`, then one line per
    thread (`id name`), then `*methods`, then one line per method
    (`id class method signature file`), then `*end`, then binary records.

Only the sample records are read here, and only for one thread, so the result is
a ranked list of the methods that were on the stack when a sample was taken.
"""

from __future__ import annotations

import struct
import sys
from collections import Counter
from pathlib import Path


def read_header(blob: bytes) -> tuple[int, dict[int, str]]:
    """Returns `(offset, method_id -> name)` for the text header."""
    at = 0

    def read_line() -> str:
        nonlocal at
        end = blob.index(b"\n", at)
        line = blob[at:end].decode("utf-8", "replace")
        at = end + 1
        return line

    magic = read_line()
    if not magic.startswith("*version"):
        raise SystemExit(f"not an Android trace: first line was {magic!r}")

    # Metadata until *threads.
    while True:
        line = read_line()
        if line.startswith("*threads"):
            break

    threads: list[tuple[int, str]] = []
    while True:
        line = read_line()
        if line.startswith("*methods"):
            break
        # Thread lines are tab-separated: `<id>\t<name>\t<kind>`.
        parts = line.split("\t")
        if len(parts) >= 2:
            try:
                threads.append((int(parts[0]), parts[1]))
            except ValueError:
                continue

    methods: dict[int, str] = {}
    while True:
        line = read_line()
        if line.startswith("*end"):
            break
        # Method lines are `<id>\t<class>\t<name>\t<signature>\t<file>`.
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            method_id = int(parts[0])
        except ValueError:
            continue
        methods[method_id] = f"{parts[1]}.{parts[2]}"

    return at, methods, threads


def main(path: str, want_thread: str | None = None) -> int:
    blob = Path(path).read_bytes()
    at, methods, threads = read_header(blob)

    main_thread_id = None
    for thread_id, name in threads:
        if want_thread is None or want_thread in name:
            main_thread_id = thread_id
            thread_name = name
            if want_thread is not None:
                break

    print(f"threads: {[n for _, n in threads]}")
    if main_thread_id is None:
        raise SystemExit("no matching thread")

    # Binary records: u16 size (bytes, including the header), u8 type, payload.
    #   type 1 = thread:        u16 thread id
    #   type 2 = stack:         u32 depth, then `depth` u32 method ids
    #   type 3 = method action: u32 method id, u8 action
    # A stack record *is* a sample: the thread it belongs to is whichever `thread`
    # record came last, and every method that is not in the previous stack was
    # pushed when this one was taken.
    at, methods, threads = read_header(blob)

    current_thread = None
    previous: list[int] = []
    counts: Counter = Counter()
    samples = 0

    while at + 3 <= len(blob):
        size = struct.unpack_from("<H", blob, at)[0]
        kind = blob[at + 2]
        if size < 3 or at + size > len(blob):
            break
        payload = blob[at + 3 : at + size]
        if kind == 1 and len(payload) >= 2:
            current_thread = struct.unpack_from("<H", payload, 0)[0]
            previous = []
        elif kind == 2 and len(payload) >= 4:
            depth = struct.unpack_from("<I", payload, 0)[0]
            ids = [
                struct.unpack_from("<I", payload, 4 + 4 * i)[0]
                for i in range(min(depth, (len(payload) - 4) // 4))
            ]
            if current_thread == main_thread_id:
                samples += 1
                # Only the methods new since the last sample are on the stack
                # for the first time: a sample weighs every frame above the
                # deepest frame it shares with the previous one.
                shared = 0
                for a, b in zip(previous, ids):
                    if a != b:
                        break
                    shared += 1
                for method_id in ids[shared:]:
                    counts[methods.get(method_id, str(method_id))] += 1
            previous = ids
        at += size

    print(f"samples for {main_thread_id}: {samples}")
    print("top methods (count = samples whose stack reached them first):")
    for name, count in counts.most_common(45):
        print(f"  {count:6}  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
