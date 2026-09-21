#!/usr/bin/env python3
"""
Pins the rule that decides what a vendored tree keeps — and what it loses.

`trim_vendor_tree` deletes by [vendor_junk], and the shape of that rule is the point: it
is a *delete* list, so everything it does not name ships. The rule it replaced was a keep
list, and it deleted pi's `examples/` — 132 files, which pi's own documentation links into
49 times — along with the dependency trees' documentation, because nobody had thought to
name them. A keep list has to be right about everything; a delete list has to be right
about what it names.

So the cases below assert both directions, and the one that matters most is the first:
what the rule does not name is still there. The rest pin each deletion, the per-tree drops
([PI_DROPS], [WEB_ACCESS_DROPS]) and the one exception ([WEB_ACCESS_DOCS], the
extension's README, which the rule deletes by name everywhere else).

The trees are synthetic and stdlib-only, so this runs on any build host and needs neither
the network nor the vendoring cache.

Usage:
    python tools/test-vendor-trim.py
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
from pathlib import Path
from types import ModuleType

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILDER = REPO_ROOT / "tools" / "build-runtime-image.py"


def load_builder() -> ModuleType:
    """
    Imports the builder by path, because its file name is not a module name.

    It has a `__main__` guard and imports nothing at module scope that a check host
    lacks, so loading it runs no work — which is what makes `trim_vendor_tree`, the
    predicate it deletes by and the constants the callers pass testable at all.
    """
    spec = importlib.util.spec_from_file_location("pikit_runtime_image", BUILDER)
    if spec is None or spec.loader is None:  # pragma: no cover - a missing file
        raise SystemExit(f"cannot load {BUILDER}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


#: A tree shaped like pi's package: the source material the rule deletes, beside the
#: things it must not — `examples/`, pi's own documentation and type declarations, the
#: dependencies that are pi's own API, the TypeScript the agent loads through jiti.
PI_TREE = {
    "docs/index.md": "# Index\n",
    "docs/rpc.md": "# RPC\n",
    "docs/docs.json": "{}\n",
    "docs/images/interactive-mode.png": "not really a PNG",
    "docs/images/exy.png": "not really a PNG",
    "README.md": "# pi\n",
    "CHANGELOG.md": "# 0.86.1\n",
    "LICENSE": "MIT\n",
    "LICENSE.md": "MIT\n",
    "dist/index.d.ts": "export type X = 1\n",
    "dist/bundle/cli.js": "console.log(1)\n",
    "dist/bundle/cli.js.map": "{}\n",
    "dist/bundle/cli.ts": "export {}\n",
    "examples/extensions/plan-mode/index.ts": "export {}\n",
    "examples/extensions/README.md": "# examples\n",
    "examples/sdk/05-tools.ts": "export {}\n",
    "tests/fixture.js": "test()\n",
    "node_modules/@earendil-works/pi-ai/dist/index.d.ts": "export type T = 1\n",
    "node_modules/@earendil-works/pi-ai/docs/models.md": "# models\n",
    "node_modules/openai/index.d.ts": "export type U = 1\n",
    "node_modules/openai/index.js": "module.exports = {}\n",
    "node_modules/undici/index.js": "module.exports = {}\n",
    "node_modules/undici/docs/docs/api/Fetch.md": "# Fetch\n",
    "node_modules/undici/README.md": "# undici\n",
    "node_modules/undici/tests/runner.js": "fixture()\n",
    "node_modules/typebox/index.d.ts": "export type T = 1\n",
    "node_modules/domino/.yarn/plugins/plugin-version.cjs": "plugin()\n",
    "node_modules/mathml-to-latex/vendor.js.map": "{}\n",
}

#: The same shape with the extension's layout, which is the one a single *file* of `keep`
#: protects and a per-tree `drop` prunes.
WEB_ACCESS_TREE = {
    "node_modules/pi-web-access/README.md": "# pi-web-access\n",
    "node_modules/pi-web-access/CHANGELOG.md": "# 0.30.0\n",
    "node_modules/pi-web-access/SECURITY.md": "report it\n",
    "node_modules/pi-web-access/banner.png": "not really a PNG",
    "node_modules/pi-web-access/pi-web-fetch-demo.mp4": "not really a video",
    "node_modules/pi-web-access/index.ts": "export {}\n",
    "node_modules/pi-web-access/dist/index.js": "export {}\n",
    "node_modules/pi-web-access/tests/extract.test.ts": "test()\n",
    "node_modules/mathml-to-latex/index.js": "module.exports = {}\n",
    "node_modules/mathml-to-latex/index.js.map": "{}\n",
    "node_modules/unpdf/dist/index.d.ts": "export type P = 1\n",
    "node_modules/unpdf/dist/pdfjs.mjs": "export {}\n",
}

#: `(case name, tree, drop, keep, paths that must survive, paths that must be gone)`.
CASES = (
    (
        "pi keeps what the rule does not name",
        PI_TREE,
        ("docs/images",),
        (),
        [
            # The regression this rule exists for: a keep list deleted these.
            "examples/extensions/plan-mode/index.ts",
            "examples/sdk/05-tools.ts",
            # The documentation pi's own comments cite, and its index.
            "docs/index.md",
            "docs/rpc.md",
            "docs/docs.json",
            # pi's own type declarations, and the dependencies that are pi's own API —
            # whose declarations and chapters are what an extension author reads.
            "dist/index.d.ts",
            "node_modules/@earendil-works/pi-ai/dist/index.d.ts",
            "node_modules/@earendil-works/pi-ai/docs/models.md",
            # The TypeScript the agent loads, and the licences it ships under.
            "dist/bundle/cli.ts",
            "LICENSE",
            "LICENSE.md",
            "dist/bundle/cli.js",
            "node_modules/undici/index.js",
            "node_modules/openai/index.js",
        ],
        [
            # The npm page, everywhere it appears.
            "README.md",
            "CHANGELOG.md",
            "node_modules/undici/README.md",
            "examples/extensions/README.md",
            # Test fixtures, the source maps beside them, a package manager's bundles.
            "tests",
            "node_modules/undici/tests",
            "node_modules/domino/.yarn",
            "dist/bundle/cli.js.map",
            "node_modules/mathml-to-latex/vendor.js.map",
            # A dependency's type declarations and a dependency's manual: nothing loads
            # either on a device.
            "node_modules/openai/index.d.ts",
            "node_modules/typebox/index.d.ts",
            "node_modules/undici/docs",
            # The one thing pi's own tree drops on top of the rule.
            "docs/images",
        ],
    ),
    (
        "the extension keeps its README and loses its npm page",
        WEB_ACCESS_TREE,
        (
            "node_modules/pi-web-access/pi-web-fetch-demo.mp4",
            "node_modules/pi-web-access/banner.png",
            "node_modules/pi-web-access/SECURITY.md",
        ),
        ("node_modules/pi-web-access/README.md",),
        [
            "node_modules/pi-web-access/README.md",
            "node_modules/pi-web-access/index.ts",
            "node_modules/pi-web-access/dist/index.js",
            "node_modules/mathml-to-latex/index.js",
            "node_modules/unpdf/dist/pdfjs.mjs",
        ],
        [
            "node_modules/pi-web-access/CHANGELOG.md",
            "node_modules/pi-web-access/SECURITY.md",
            "node_modules/pi-web-access/banner.png",
            "node_modules/pi-web-access/pi-web-fetch-demo.mp4",
            "node_modules/pi-web-access/tests",
            "node_modules/mathml-to-latex/index.js.map",
            "node_modules/unpdf/dist/index.d.ts",
        ],
    ),
    (
        "with neither drop nor keep, the rule alone decides",
        PI_TREE,
        (),
        (),
        # A screenshot is not the rule's business: pi's `docs/images` goes only because
        # `PI_DROPS` names it, which is why this case keeps it and the first one does not.
        ["docs/index.md", "docs/images/exy.png", "examples/sdk/05-tools.ts", "LICENSE.md"],
        ["README.md", "CHANGELOG.md", "tests"],
    ),
)


def build_tree(root: Path, files: dict[str, str]) -> None:
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")


def run_case(builder: ModuleType, name: str, tree: dict[str, str], drop: tuple[str, ...],
             keep: tuple[str, ...], alive: list[str], gone: list[str],
             failures: list[str]) -> None:
    with tempfile.TemporaryDirectory(prefix="pikit-vendor-trim-") as temporary:
        root = Path(temporary)
        build_tree(root, tree)
        builder.trim_vendor_tree(root, drop=drop, keep=keep)

        for relative in alive:
            if not (root / relative).exists():
                failures.append(f"{name}: {relative} was removed and has to ship")
                continue
            # The verifier derives what the image owes from these two functions, so a
            # path the trim keeps has to be one `vendor_junk` does not name, and the
            # other way round — the two answers cannot be allowed to disagree.
            if builder.vendor_junk(relative, drop) and not builder.vendor_kept(relative, keep):
                failures.append(
                    f"{name}: {relative} ships but vendor_junk() calls it junk, so the "
                    f"image verifier would expect it to be gone"
                )
        for relative in gone:
            target = root / relative
            if target.is_dir():
                # A dropped directory that is only emptied is gone for the image's
                # purposes — the rule deletes files, and `undici/docs` keeps its
                # directory entry while losing everything in it.
                survivors = [p for p in target.rglob("*") if p.is_file()]
                if survivors:
                    failures.append(
                        f"{name}: {relative} still holds {len(survivors)} file(s)"
                    )
                elif not all(
                    builder.vendor_junk(member, drop)
                    for member in tree
                    if member.startswith(relative + "/")
                ):
                    failures.append(
                        f"{name}: {relative} is emptied but vendor_junk() would keep part of it"
                    )
                continue
            if target.exists():
                failures.append(f"{name}: {relative} survived and has to go")
                continue
            if not builder.vendor_junk(relative, drop) and not builder.vendor_kept(relative, keep):
                failures.append(
                    f"{name}: {relative} is deleted but vendor_junk() would expect it"
                )

    print(f"  ok    {name}")


def main() -> int:
    builder = load_builder()
    failures: list[str] = []

    # The wiring, not the rule's numbers: a renamed constant would leave a call site
    # passing nothing, and only a device would show it.
    if not builder.PI_DROPS or not all(d.startswith("docs/") for d in builder.PI_DROPS):
        failures.append(f"PI_DROPS is {builder.PI_DROPS!r}, not pi's screenshots")
    if not builder.WEB_ACCESS_DOCS or not all(
        name.endswith("README.md") for name in builder.WEB_ACCESS_DOCS
    ):
        failures.append(f"WEB_ACCESS_DOCS is {builder.WEB_ACCESS_DOCS!r}, not the extension's README")
    # The README is deleted by name everywhere else, so an exception that stopped being
    # one — or a rule that stopped deleting it — is the pair this asserts together.
    if "README.md" not in builder.VENDOR_JUNK_FILES:
        failures.append("the rule no longer deletes README.md, so the keep above is untested")
    if not builder.vendor_junk("package/README.md") or not builder.vendor_junk("package/x.map"):
        failures.append("the rule no longer deletes the npm page or source maps")
    if builder.vendor_junk("package/docs/index.md") or builder.vendor_junk("package/examples/a.ts"):
        failures.append("the rule deletes documentation, which is what it must not do")
    # The dependency rules, and the one scope they do not apply to.
    for name, why in (
        ("package/node_modules/openai/index.d.ts", "a dependency's type declarations"),
        ("package/node_modules/undici/docs/api/x.md", "a dependency's manual"),
        ("package/node_modules/domino/.yarn/plugins/p.cjs", "a package manager's own bundles"),
    ):
        if builder.vendor_junk(name) != why:
            failures.append(f"{name} is not deleted as {why!r}")
    for name in ("package/node_modules/@earendil-works/pi-ai/index.d.ts",
                 "package/node_modules/@earendil-works/pi-ai/docs/models.md",
                 "package/dist/index.d.ts"):
        if builder.vendor_junk(name):
            failures.append(f"{name} is pi's own API or pi's own tree and must ship")

    for name, tree, drop, keep, alive, gone in CASES:
        run_case(builder, name, tree, drop, keep, alive, gone, failures)

    if failures:
        for failure in failures:
            print(f"  FAIL  {failure}")
        print(f"vendor trim: {len(failures)} expectation(s) not met")
        return 1

    print(f"vendor trim: the rule deletes, and only what it names ({len(CASES)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
