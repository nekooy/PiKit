"""
Turns a captured pi RPC stream into a test fixture.

## Why a fixture at all

The reducer's unit tests were written from the same understanding as the reducer,
by the same person. A fixture that encodes that understanding twice cannot catch a
mismatch with the real protocol — and one such mismatch survived a full test
suite: pi sends no `id` on `toolcall_delta` records, the reducer read `delta.id`
to find the card, and every streamed tool argument was dropped without an error.

So the fixture is generated from a **real capture** instead
(`tools/capture-pi-rpc.mjs`), and `RealCaptureTest` replays it.

## What it strips, and why

- `timestamp` fields, because the test asserts exact elapsed times against an
  injected clock and a captured wall clock would make that meaningless.
- `responseId`, which is generated per request and identifies a real API call.
- The bash tool's output, which on the capture host is a Windows error message.
  It is replaced with a stable placeholder that keeps the same shape and length
  class, so the record sizes stay realistic without shipping a machine's error
  text. Everything else — every field name, every nesting level, every event
  order — is left exactly as pi sent it, because that is the point.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = REPO_ROOT / ".runtime-build" / "pi-rpc-capture.jsonl"
DEFAULT_TARGET = REPO_ROOT / "app/src/test/resources/pi-rpc/real-tool-turn.jsonl"

#: Fields whose value is different on every run.
VOLATILE = ("timestamp", "responseId")

#: A stable stand-in for the capture host's shell error text.
TOOL_OUTPUT_PLACEHOLDER = (
    "pikit-fixture: the bash tool's output was replaced.\n"
    "The capture host had no usable /bin/sh, so pi recorded its error text here; "
    "the record's shape is unchanged."
)


def scrub(value):
    """Recursively drops volatile fields and replaces tool output."""
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if key in VOLATILE:
                continue
            if key == "text" and isinstance(item, str) and len(item) > 200:
                # Long text under `content` is tool output, not model prose: the
                # model's own replies in the capture are short.
                out[key] = TOOL_OUTPUT_PLACEHOLDER
                continue
            out[key] = scrub(item)
        return out
    if isinstance(value, list):
        return [scrub(item) for item in value]
    return value


def main() -> int:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SOURCE
    target = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TARGET

    if not source.is_file():
        raise SystemExit(f"no capture at {source}; run tools/capture-pi-rpc.mjs first")

    kept = []
    for line in source.read_text(encoding="utf-8").split("\n"):
        line = line.strip()
        if not line:
            continue
        record = scrub(json.loads(line))
        kept.append(json.dumps(record, ensure_ascii=False, separators=(",", ":")))

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(kept) + "\n", encoding="utf-8", newline="\n")

    types: dict[str, int] = {}
    for line in kept:
        kind = json.loads(line).get("type", "?")
        types[kind] = types.get(kind, 0) + 1

    print(f"wrote {len(kept)} record(s) to {target.relative_to(REPO_ROOT)}")
    for kind, count in sorted(types.items(), key=lambda kv: -kv[1]):
        print(f"  {count:4}  {kind}")

    # A leaked secret in a fixture is a leaked secret in the repository.
    blob = target.read_text(encoding="utf-8")
    for pattern in (r"sk-[A-Za-z0-9]{16,}", r"Bearer\s+[A-Za-z0-9._-]{16,}"):
        if re.search(pattern, blob):
            target.unlink()
            raise SystemExit(f"the fixture contains what looks like a credential ({pattern}); not written")
    print("no credential-shaped strings in the fixture")
    return 0


if __name__ == "__main__":
    sys.exit(main())
