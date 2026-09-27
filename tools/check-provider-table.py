#!/usr/bin/env python3
"""
Fails when PiKit's provider table has drifted from the bundled pi's.

`PiProviderTest` pins the enum against a hand-copied fixture of `getApiKeyEnvVars`,
and that is exactly the failure mode the copy cannot see: a pi release that adds a
provider does not change the fixture, so the test stays green and the picker is
simply missing a row. That is how `meta` sat missing for a release cycle while the
bundled `chunk-*.js` already listed `meta: META_API_KEY` and shipped five Muse
models under it.

This checker reads the table out of the bundled pi itself and compares it with the
fixture in `PiProviderTest.kt`, so a drift is a failing build rather than a provider
nobody can select. It skips itself when the pi cache is absent, the same way
`test-relocate.py` skips without a `.deb` cache: the input is what the image builder
fills, and reporting a failure for a missing input is noise.

Run from the repository root:

    python tools/check-provider-table.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

PI_PACKAGE = (
    REPO_ROOT
    / ".runtime-build/cache/pi/node_modules/@earendil-works/pi-coding-agent"
)

PROVIDER_TEST = REPO_ROOT / "app/src/test/java/pi/kit/mob/data/PiProviderTest.kt"
SETTINGS_STORE = REPO_ROOT / "app/src/main/java/pi/kit/mob/data/SettingsStore.kt"

#: `getApiKeyEnvVars` builds one object literal of id -> env var. The ids and the
#: values are both string literals; bare identifiers (`openai: "OPENAI_API_KEY"`)
#: appear too, and are folded in by the same pattern with the quotes optional.
ENV_PAIR = re.compile(r'(?:"([^"]+)"|([A-Za-z0-9_-]+))\s*:\s*"([A-Z][A-Z0-9_]*)"')

#: The fixture is a Kotlin map of `"id" to "ENV_VAR"` entries.
FIXTURE_PAIR = re.compile(r'"([^"]+)"\s+to\s+"([A-Z][A-Z0-9_]*)"')

#: The enum is `ID("pi-id", "ENV_VAR", "Label")`, on one line or wrapped.
ENUM_ENTRY = re.compile(
    r'^\s{4}[A-Z][A-Z0-9_]*\(\s*"([^"]+)",\s*"([A-Z][A-Z0-9_]*)"',
    re.M,
)

#: The fixture's `deliberatelyAbsent` list — providers pi has and the picker does
#: not, each with a reason in the comment above it. The checker reads the ids and
#: expects exactly those to be missing from the fixture.
ABSENT_ID = re.compile(r'^\s{8}"([^"]+)",\s*$', re.M)

#: Providers whose key resolution lives outside the `envVar` object literal:
#: `anthropic` answers with a list of three variables and `github-copilot` with a
#: name check. Neither is a single-key provider this app offers in the same shape,
#: so the fixture's single entry for `anthropic` is this app's simplification and
#: is not compared against the literal.
SPECIAL_CASED = {"anthropic", "github-copilot", "openai-codex"}


def find_provider_chunk() -> Path | None:
    """The chunk that defines `getApiKeyEnvVars`, wherever the bundler put it."""
    chunks = PI_PACKAGE / "dist/bundle/chunks"
    if not chunks.is_dir():
        return None
    for chunk in sorted(chunks.glob("chunk-*.js")):
        try:
            if "getApiKeyEnvVars" in chunk.read_text(encoding="utf-8", errors="replace"):
                return chunk
        except OSError:
            continue
    return None


def bundled_table(chunk: Path) -> dict[str, str]:
    """
    The single-key half of pi's `getApiKeyEnvVars` table.

    Two providers are answered outside the object literal (`github-copilot` by a
    name check, `anthropic` by a list of three variables) and neither is a
    single-key provider this app offers, so they are not looked for here.
    """
    text = chunk.read_text(encoding="utf-8", errors="replace")
    start = text.find("let envVar={")
    if start < 0:
        return {}
    end = text.find("}[provider]", start)
    if end < 0:
        return {}
    body = text[start:end]
    table = dict[str, str]()
    for match in ENV_PAIR.finditer(body):
        key = match.group(1) or match.group(2)
        table[key] = match.group(3)
    return table


def fixture_table() -> dict[str, str]:
    text = PROVIDER_TEST.read_text(encoding="utf-8")
    start = text.find("piSingleKeyProviders = mapOf(")
    if start < 0:
        return {}
    end = text.find("\n    )", start)
    body = text[start : end if end > 0 else len(text)]
    return dict(FIXTURE_PAIR.findall(body))


def deliberately_absent() -> set[str]:
    text = PROVIDER_TEST.read_text(encoding="utf-8")
    start = text.find("deliberatelyAbsent = listOf(")
    if start < 0:
        return set()
    end = text.find("\n    )", start)
    body = text[start : end if end > 0 else len(text)]
    return set(ABSENT_ID.findall(body))


def enum_table() -> dict[str, str]:
    text = SETTINGS_STORE.read_text(encoding="utf-8")
    return dict(ENUM_ENTRY.findall(text))


def main() -> int:
    chunk = find_provider_chunk()
    if chunk is None:
        print(
            f"skip the provider table: no pi bundle under {PI_PACKAGE}, "
            "which the image builder fills"
        )
        return 0

    bundled = bundled_table(chunk)
    if not bundled:
        print(f"FAIL could not read getApiKeyEnvVars out of {chunk.name}")
        return 1

    fixture = fixture_table()
    enum = enum_table()
    absent = deliberately_absent()
    problems = []

    for provider_id, env_var in sorted(bundled.items()):
        if provider_id in SPECIAL_CASED:
            continue
        if provider_id in absent:
            # A deliberate omission is a decision the fixture records, not drift —
            # but only while it *is* recorded. A provider that vanishes from the
            # absent list and stays out of the fixture is drift again.
            if provider_id in fixture:
                problems.append(
                    f"{provider_id!r} is in deliberatelyAbsent and in the fixture; "
                    f"it cannot be both"
                )
            continue
        if provider_id not in fixture:
            problems.append(
                f"pi has {provider_id!r} -> {env_var}, and PiProviderTest's fixture "
                f"does not (the picker cannot offer it)"
            )
        elif fixture[provider_id] != env_var:
            problems.append(
                f"pi reads {env_var} for {provider_id!r}, the fixture says "
                f"{fixture[provider_id]}"
            )

    for provider_id, env_var in sorted(fixture.items()):
        if provider_id in SPECIAL_CASED:
            continue
        if provider_id not in bundled:
            problems.append(
                f"the fixture has {provider_id!r} -> {env_var}, and pi's "
                f"getApiKeyEnvVars does not (a provider that no longer exists, or "
                f"a typo)"
            )

    for provider_id in sorted(absent):
        if provider_id in fixture:
            problems.append(
                f"{provider_id!r} is in deliberatelyAbsent and in the fixture; "
                f"it cannot be both"
            )

    # The enum is the picker. It is compared against the fixture rather than the
    # bundle so that a deliberate omission (OAuth-only, two-input providers) is
    # still a decision the fixture records — `deliberatelyAbsent` is its list.
    for provider_id, env_var in sorted(fixture.items()):
        if provider_id not in enum:
            problems.append(
                f"the fixture has {provider_id!r} -> {env_var}, and PiProvider "
                f"does not (a provider the test vouches for is missing from the "
                f"picker)"
            )
        elif enum[provider_id] != env_var:
            problems.append(
                f"PiProvider maps {provider_id!r} to {enum[provider_id]}, the "
                f"fixture says {env_var}"
            )

    if problems:
        print(f"FAIL the provider table has drifted from {chunk.name}:")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\nPiProviderTest's fixture is the deliberate filter over pi's table; "
            "update it (and PiProvider) together, and record any provider that "
            "stays out in deliberatelyAbsent."
        )
        return 1

    print(
        f"ok the provider table matches pi's ({len(fixture)} single-key providers "
        f"checked against {chunk.name})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
