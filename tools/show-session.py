"""Prints what a pi session file recorded, one line per message.

Used to answer "did the turn actually work" from the session JSONL rather than
from a screenshot: the file is what pi wrote, it survives the app being closed,
and it distinguishes a real reply from a retry notice.
"""

import json
import sys
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else ".runtime-build/e2e-session.jsonl")
if not path.is_file():
    raise SystemExit(f"no session file at {path}")

lines = [line for line in path.read_text(encoding="utf-8", errors="replace").split("\n") if line.strip()]
print(f"{len(lines)} record(s) in {path.name}")

for line in lines:
    try:
        record = json.loads(line)
    except json.JSONDecodeError:
        continue

    if record.get("type") != "message":
        continue
    message = record.get("message") or {}
    role = message.get("role")
    blocks = message.get("content") or []
    text = "".join(
        block.get("text", "") for block in blocks if isinstance(block, dict)
    )

    if role == "assistant":
        tools = [
            block.get("name") for block in blocks
            if isinstance(block, dict) and block.get("type") == "toolCall"
        ]
        thinking = "".join(
            block.get("thinking", "") for block in blocks
            if isinstance(block, dict) and block.get("type") == "thinking"
        )
        print(f"  assistant stopReason={message.get('stopReason')!r} tools={tools}")
        if thinking:
            print(f"    thinking: {thinking[:120]!r}")
        if text:
            print(f"    text: {text[:300]!r}")
        usage = message.get("usage") or {}
        if usage:
            print(f"    usage: in={usage.get('input')} out={usage.get('output')} total={usage.get('totalTokens')}")
    elif role == "toolResult":
        print(f"  toolResult: {text[:300]!r}")
    else:
        print(f"  {role}: {text[:160]!r}")
