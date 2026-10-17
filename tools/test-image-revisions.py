#!/usr/bin/env python3
"""
Pins what the two revision stamps cover, because the failure mode is invisible.

`tools/build-runtime-image.py` writes one revision stamp per image layer, and the
device decides from them whether to re-extract the environment (`revision.txt`,
the seed) or point-replace pi and the guard (`content-revision.txt`). Getting a
digest's coverage wrong costs nothing at the build and a lot on a phone:

- the seed stamp used to cover the packages' *versions* and the archives' bytes.
  A cold build resolves the current apt index, so every release looked like a
  different seed and re-extracted a few hundred megabytes of environment — to
  hand over versions `pkg upgrade` fetches anyway. The seed stamp must move when
  the environment's *shape* moves (a package added or dropped, the bootstrap tag,
  the code that assembles the seed) and not when a version drifts.
- the content stamp must move when the product pins move: pi's version lives in
  `tools/image-versions.json` rather than in the builder's source exactly so that
  it does *not* move the seed stamp, and the price of that split is that nothing
  else would move the content stamp if the pin stopped being read.

Both are pure functions of their inputs, so both are pinned here rather than
discovered on a device.
"""

import importlib.util
import inspect
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILDER_PATH = REPO_ROOT / "tools" / "build-runtime-image.py"


def load_builder():
    spec = importlib.util.spec_from_file_location("pikit_image_builder", BUILDER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["pikit_image_builder"] = module
    spec.loader.exec_module(module)
    return module


class Package:
    """Just enough of the builder's `DebianPackage` for a revision input."""

    def __init__(self, name: str, version: str) -> None:
        self.name = name
        self.version = version


def main() -> int:
    builder = load_builder()
    failures = 0

    def check(condition: bool, description: str) -> None:
        nonlocal failures
        print(("  ok    " if condition else "  FAIL  ") + description)
        if not condition:
            failures += 1

    base = [Package("nodejs", "26.4.0-1"), Package("zlib", "1.3.1"), Package("npm", "11.21.1")]
    drifted = [Package("nodejs", "26.5.0-1"), Package("zlib", "1.3.2"), Package("npm", "11.22.0")]
    grown = base + [Package("newdep", "1.0")]

    seed = builder.compute_seed_revision("aarch64", base)
    check(
        builder.compute_seed_revision("aarch64", drifted) == seed,
        "the seed stamp ignores package versions (a drifted index must not "
        "re-extract a device's environment)",
    )
    check(
        builder.compute_seed_revision("aarch64", grown) != seed,
        "the seed stamp moves when the package *set* changes",
    )
    check(
        builder.compute_seed_revision("x86_64", base) != seed,
        "the seed stamp names the architecture",
    )
    check(
        builder.compute_seed_revision("aarch64", list(reversed(base))) == seed,
        "the seed stamp is order-independent (the closure's order is the "
        "resolver's business)",
    )

    # The content stamp has to move with the pins: `tools/image-versions.json`
    # exists so a pi bump does not move the seed stamp, and these two functions
    # are then the only thing that moves this one.
    check(
        builder.compute_content_revision("aarch64", Path("/nonexistent"), "9.9.9")
        != builder.compute_content_revision("aarch64", Path("/nonexistent"), builder.PI_VERSION),
        "the content stamp moves with the pi pin",
    )
    check(
        builder.WEB_ACCESS_VERSION == builder.IMAGE_VERSIONS["web_access"],
        "the builder reads the web-access pin from tools/image-versions.json",
    )
    check(
        builder.PI_VERSION == builder.IMAGE_VERSIONS["pi"],
        "the builder reads the pi pin from tools/image-versions.json",
    )

    # The seed stamp's source list is what makes an edit to the seed's assembly
    # reach a device at all; a name removed from it is an edit that ships
    # invisibly. Only the membership is checked — the digests above already
    # cover what hashing them does.
    source = inspect.getsource(builder.compute_seed_revision)
    for name in ("pikit-dpkg.sh", "prefix_patch.py", "build-runtime-image.py"):
        check(name in source, f"the seed stamp covers {name}")

    print(f"\n{'PASS' if failures == 0 else 'FAIL'} — revision stamps cover what they must")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
