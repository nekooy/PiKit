"""
Audits a captured pi RPC stream against what PiKit's client assumes.

The client's rules were written from observation and are covered by unit tests —
but those fixtures were hand-written by the same person who wrote the parser, so
they encode the same assumption twice and cannot catch a mismatch with the real
protocol. This reads a capture produced by `tools/capture-pi-rpc.mjs` and reports,
field by field, whether each assumption holds.

Usage: python tools/audit-capture.py <capture.jsonl>
"""

from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


def load(path: Path) -> list[dict]:
    records = []
    for number, line in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError as error:
            print(f"  line {number}: NOT VALID JSON ({error})")
    return records


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".runtime-build/pi-rpc-capture.jsonl")
    if not path.is_file():
        raise SystemExit(f"no capture at {path}; run tools/capture-pi-rpc.mjs first")

    records = load(path)
    print(f"=== {len(records)} record(s) from {path}")

    kinds = Counter(r.get("type", "(no type)") for r in records)
    print("\n=== record types")
    for kind, count in kinds.most_common():
        print(f"  {count:4}  {kind}")

    # --- the command envelope ------------------------------------------------
    print("\n=== envelope: does it match `{ id, type, ...payload }`?")
    responses = [r for r in records if r.get("type") == "response"]
    print(f"  {len(responses)} response(s)")
    for r in responses[:4]:
        print(f"    id={r.get('id')!r} command={r.get('command')!r} success={r.get('success')!r}")

    # --- tool call lifecycle -------------------------------------------------
    print("\n=== tool call lifecycle fields (what the reducer reads)")
    for r in records:
        if r.get("type") != "message_update":
            continue
        event = r.get("assistantMessageEvent") or {}
        kind = event.get("type")
        if kind == "toolcall_start":
            print(f"  toolcall_start  contentIndex={event.get('contentIndex')!r} "
                  f"id={event.get('id')!r} toolName={event.get('toolName')!r}")
        elif kind == "toolcall_end":
            call = event.get("toolCall") or {}
            print(f"  toolcall_end    contentIndex={event.get('contentIndex')!r} "
                  f"toolCall.id={call.get('id')!r} toolCall.name={call.get('name')!r} "
                  f"toolCall.type={call.get('type')!r}")
        elif kind == "toolcall_delta":
            # The reducer looks for `id` here. Report whether it is present.
            pass

    deltas_with_id = sum(
        1
        for r in records
        if r.get("type") == "message_update"
        and (r.get("assistantMessageEvent") or {}).get("type") == "toolcall_delta"
        and "id" in (r.get("assistantMessageEvent") or {})
    )
    deltas_total = sum(
        1
        for r in records
        if r.get("type") == "message_update"
        and (r.get("assistantMessageEvent") or {}).get("type") == "toolcall_delta"
    )
    print(f"\n  toolcall_delta records: {deltas_total}, of which carry an `id`: {deltas_with_id}")
    if deltas_total and deltas_with_id == 0:
        print("    -> the reducer's `updateToolById` reads `delta.id`, which is never present,")
        print("       so streamed tool arguments are dropped until tool_execution_start.")

    print("\n=== tool execution fields")
    for r in records:
        if r.get("type") in ("tool_execution_start", "tool_execution_end"):
            print(f"  {r['type']:22} toolCallId={r.get('toolCallId')!r} "
                  f"toolName={r.get('toolName')!r} hasArgs={'args' in r} "
                  f"hasResult={'result' in r} isError={r.get('isError')!r}")

    # --- message shapes ------------------------------------------------------
    print("\n=== message_start / message_end roles and content blocks")
    for r in records:
        if r.get("type") not in ("message_start", "message_end"):
            continue
        message = r.get("message") or {}
        blocks = message.get("content")
        types = [b.get("type") for b in blocks] if isinstance(blocks, list) else blocks
        print(f"  {r['type']:14} role={message.get('role')!r} stopReason={message.get('stopReason')!r} "
              f"blocks={types}")

    # --- thinking blocks -----------------------------------------------------
    print("\n=== thinking block shape (the reducer reads `thinking`)")
    for r in records:
        if r.get("type") != "message_end":
            continue
        for block in (r.get("message") or {}).get("content") or []:
            if block.get("type") == "thinking":
                print(f"  keys={sorted(block)} thinking_prefix={(block.get('thinking') or '')[:48]!r}")

    # --- usage ---------------------------------------------------------------
    print("\n=== usage fields")
    seen = set()
    for r in records:
        usage = r.get("usage") or (r.get("message") or {}).get("usage")
        if isinstance(usage, dict):
            seen.add(tuple(sorted(usage)))
    for keys in seen:
        print(f"  {keys}")

    print("\n=== the settle signals present")
    for kind in ("agent_start", "agent_end", "agent_settled", "turn_start", "turn_end"):
        count = sum(1 for r in records if r.get("type") == kind)
        print(f"  {kind:16} {count}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
