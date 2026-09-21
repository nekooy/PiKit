#!/usr/bin/env python3
"""
Builds the offline runtime image that PiKit unpacks on first launch.

The image is assembled from four sources and shipped inside the APK as two zip
archives per CPU architecture:

  1. The official Termux bootstrap, taken verbatim from termux-packages.
  2. Termux .deb packages for Node.js, npm, ripgrep and fd (plus their
     transitive dependencies), unpacked into the same prefix.
  3. A host-side vendoring of the pi coding agent (`npm ci`), copied under
     `$PREFIX/lib/node_modules`.
  4. A small set of generated wrapper scripts.

Everything is written into `app/src/<flavor>/assets/runtime/<abi>/` as
`bootstrap.zip`, `overlay.zip` and `revision.txt`.

Why the prefix is relocated by rewriting, not by recompiling
------------------------------------------------------------
Termux packages are compiled against a fixed prefix. In the official
aarch64 bootstrap, 615 of 3472 files hardcode `/data/data/com.termux`,
including 338 ELF binaries whose `DT_RUNPATH` points at an absolute
`$PREFIX/lib`, and 114 scripts whose shebang names the absolute interpreter.
The first process the app launches, `$PREFIX/bin/login`, is one of them, and
none of it is editable at runtime. So the tree is relocated here instead:
`prefix_patch` rewrites the package id to `APP_ID`, which is exactly as long
as `com.termux`, so every byte offset stays where it was.

Why ripgrep and fd are mandatory
--------------------------------
pi's `grep` tool shells out to `rg` and its `find` tool to `fd`. pi downloads
those itself on desktop platforms, but explicitly refuses on Android because
upstream Linux builds are glibc-linked and will not run on bionic. If they are
missing, those two tools fail at call time rather than at startup, so they are
baked in here.

Usage:
    python tools/build-runtime-image.py --arch aarch64 --flavor arm64
    python tools/build-runtime-image.py --arch x86_64  --flavor x64
    python tools/build-runtime-image.py --all

Flags:
    --arch ARCH         Termux architecture to build (aarch64, x86_64)
    --flavor FLAVOR     Gradle product flavour that packages it (arm64, x64)
    --all               Build every architecture
    --pi-version VER    Vendor this pi release instead of the pinned PI_VERSION
    --keep-staging      Keep the ~240 MB staging tree each architecture is
                        assembled in. It is deleted after a successful build
                        otherwise; it is only useful for inspecting a build.
"""

from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prefix_patch  # noqa: E402  (sibling module, path set up above)

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILD_ROOT = REPO_ROOT / ".runtime-build"
CACHE_DIR = BUILD_ROOT / "cache"
STAGING_ROOT = BUILD_ROOT / "staging"

#: Bootstrap release pinned by this project. Bump deliberately, then rebuild.
BOOTSTRAP_TAG = "bootstrap-2026.09.20-r1+apt.android-7"

#: Termux apt repository. `Packages.xz` does not exist; `.bz2` is the smallest.
APT_BASE = "https://packages.termux.dev/apt/termux-main"
APT_COMPONENT = "main"
APT_SUITE = "stable"

BOOTSTRAP_URL = (
    "https://github.com/termux/termux-packages/releases/download/"
    + urllib.parse.quote(BOOTSTRAP_TAG, safe="")
    + "/bootstrap-{arch}.zip"
)

#: The application id PiKit ships as, and therefore the prefix the image is
#: built for.
#:
#: It must be exactly 10 characters: Termux packages hardcode
#: `/data/data/com.termux/files/usr`, and the only way to relocate that without
#: recompiling is a byte-for-byte replacement, which requires the new name to be
#: the same length. See tools/prefix_patch.py.
APP_ID = "pi.kit.mob"

#: The application id Termux packages are compiled against, i.e. the name this
#: script rewrites away from.
UPSTREAM_APP_ID = "com.termux"

PREFIX = f"/data/data/{APP_ID}/files/usr"
UPSTREAM_PREFIX = f"/data/data/{UPSTREAM_APP_ID}/files/usr"

#: Debian tarballs store paths relative to the filesystem root, so a Termux
#: package's members look like `./data/data/com.termux/files/usr/bin/node`.
#: Note this is the *upstream* prefix: the packages are unpatched on disk and
#: get rewritten after extraction.
DEB_PATH_PREFIX = UPSTREAM_PREFIX.strip("/") + "/"

#: pi needs Node >= 22.19.0. termux `nodejs` is well ahead of that.
NODE_PACKAGE = "nodejs"

#: Root packages layered on top of the bootstrap.
ROOT_PACKAGES = [
    NODE_PACKAGE,
    "npm",
    "ripgrep",   # backs pi's `grep` tool
    "fd",        # backs pi's `find` tool
]

PI_PACKAGE = "@earendil-works/pi-coding-agent"

#: The pi release the image vendors. Pinned like BOOTSTRAP_TAG and
#: WEB_ACCESS_VERSION, and for the same reason: an image is supposed to be the same
#: image wherever it is built, and "latest" made a rebuild of one commit depend on
#: the day it ran. It matters more here than for the other two — pi's RPC records,
#: tool list and model catalogue are what this app parses, so a silent move changes
#: behaviour with no diff to look at. `--pi-version` overrides it for one build.
#:
#: This is the *only* way pi moves on a user's device. There is deliberately no
#: in-app update any more: it ran pi's own `npm install -g` into the live prefix, and
#: an interrupted run left a tree whose `jiti` was gone — which is what pi 0.86.x
#: loads the bundled TypeScript guard extension through, so every agent start after it
#: failed outright (`Failed to load extension "…/pi-safety-guard.ts": Cannot find
#: module 'jiti'`). A version that ships in the image cannot be half-installed.
#: ARCHITECTURE §2 has the reasoning.
PI_VERSION = "0.86.1"

PI_ENTRY_RELATIVE = f"lib/node_modules/{PI_PACKAGE}/dist/bundle/cli.js"

#: Records which version a vendored pi tree in the cache holds.
#:
#: `vendor_pi` used to reuse `node_modules` whenever it existed, so bumping
#: PI_VERSION changed the revision digest, rebuilt the image — and vendored the
#: *same* tree again, because nothing compared the cache against what was asked for.
#: The fix is one marker file, and it is also what makes `--pi-version` mean anything
#: on a machine whose cache is warm.
VENDORED_MARKER = "pikit-vendored.json"

#: The web-access extension, bundled because the image cannot run
#: `pi install npm:pi-web-access` — see `vendor_web_access`. Pinned deliberately:
#: a new version changes the extension's tools and its config, so it is bumped
#: with the image and by hand, exactly as BOOTSTRAP_TAG is.
WEB_ACCESS_PACKAGE = "pi-web-access"
WEB_ACCESS_VERSION = "0.30.0"

#: Where the bundled extension lives inside the prefix, relative to `$PREFIX`.
#: Its own `node_modules` is a separate tree from pi's so that npm cannot touch
#: the vendored agent, and so that removing this directory removes all of it.
WEB_ACCESS_ROOT = ("lib", "node_modules", "pikit-extensions")
WEB_ACCESS_RELATIVE = "/".join((*WEB_ACCESS_ROOT, "node_modules", WEB_ACCESS_PACKAGE))

#: What the extension ships for its npm page rather than for running, as paths relative
#: to the tree the trim is given (`lib/node_modules/pikit-extensions` in the image, the
#: cache's project directory behind it). The shape is [vendor_junk]'s `drop`.
#:
#: `README.md` is deliberately *not* here: it is the extension's only documentation — 104
#: KB naming every provider, key and option — and it ships through [WEB_ACCESS_DOCS]. The
#: changelog needs no entry either: it is one of [VENDOR_JUNK_FILES] everywhere.
WEB_ACCESS_DROPS = (
    "node_modules/pi-web-access/pi-web-fetch-demo.mp4",
    "node_modules/pi-web-access/banner.png",
    "node_modules/pi-web-access/SECURITY.md",
)

#: Modules the extension loads at runtime that `--omit=optional` was dropping.
#:
#: `defuddle/node` — the entry the extension's `extract.ts` imports for every
#: `fetch_content` call — reaches `defuddle/dist/elements/math.full.js`, whose fourth
#: line is an unconditional `require("mathml-to-latex")`, and `defuddle` declares that
#: package as an *optional* dependency. Vendoring with `--omit=optional` (see
#: `vendor_web_access`) therefore shipped a tree in which **every** fetch failed, with
#: `Cannot find module 'mathml-to-latex'`, whatever the URL — the `require` runs when
#: the module is loaded, not when a formula is met. Reproduced on the vendored tree
#: before this was added, from `defuddle/node` down to `elements/math.full.js`.
#:
#: Exact versions, like every other pinned thing in this image: the point of vendoring
#: is that two installs of one APK run the same code. `mathml-to-latex` brings
#: `@xmldom/xmldom`, which it depends on itself; `temml` is deliberately *not* here,
#: because `math.full.js` reaches it inside a `try`/`catch` and falls back to plain
#: text — it is optional in the sense the flag means.
WEB_ACCESS_RUNTIME_DEPS = {
    "mathml-to-latex": "1.8.0",
}

#: What the app reads to find the extension and to describe it on the settings
#: page. Written as data rather than duplicated in Kotlin so that a runtime image
#: without the extension (an older one, or a build that dropped it) is a missing
#: file the app can report instead of a path it assumes exists.
WEB_ACCESS_METADATA = "share/pikit/web-access.json"

#: The check `verify_web_access` runs, so a tree that is missing something the
#: extension loads fails *this* build rather than every fetch on a phone.
#:
#: It goes through `defuddle/node` exactly as `extract.ts` does, on a page with a
#: formula in it, and prints the text it recovered. A missing optional dependency
#: throws at `import` time, which is the failure that shipped.
WEB_ACCESS_CHECK = """
const html = '<html><head><title>Doc</title></head><body><article><p>Hello '
  + '<math><mi>x</mi><mo>=</mo><mn>1</mn></math> world</p></article></body></html>';
const { Defuddle } = await import('defuddle/node');
const result = await Defuddle(html, 'https://example.com');
const text = result && result.content ? result.content : '';
if (!text.includes('Hello')) {
  console.error('the extraction returned nothing usable');
  process.exit(1);
}
console.log('defuddle/node extraction ok (' + text.length + ' chars)');
"""

#: The prefix-rewrite exclusion list, so the app's own repair pass can apply the
#: same one. See `install_rewrite_exclusions`.
REWRITE_EXCLUSIONS = "share/pikit/never-rewrite.txt"

ARCHES = {
    "aarch64": {"flavor": "arm64", "abi": "arm64-v8a"},
    "x86_64": {"flavor": "x64", "abi": "x86_64"},
}


def log(message: str) -> None:
    print(f"[build-runtime] {message}", flush=True)


def safe_filename(name: str) -> str:
    """
    Makes a string usable as a Windows filename.

    Debian versions routinely contain a colon for the epoch — `ca-certificates`
    is versioned `1:2026.08.13` — which is illegal in a Windows path and makes
    `os.replace` fail with WinError 87 rather than anything self-explanatory.
    """
    return re.sub(r"[^A-Za-z0-9._-]", "_", name)


# --------------------------------------------------------------------------- #
# Downloading
# --------------------------------------------------------------------------- #


def download(url: str, destination: Path, *, expected_sha256: str | None = None) -> Path:
    """Downloads `url` to `destination`, resuming and verifying where possible.

    **`destination` has to name the URL, not just the architecture** — see
    [url_key]. A cache whose name ignores the URL is a cache that answers a bumped
    pin with the previous download, which is the bug the bootstrap archive had: it
    was cached as `bootstrap-{arch}.zip` while its URL carries BOOTSTRAP_TAG, so
    changing the tag rebuilt an image around the *old* bootstrap and stamped the new
    tag's revision on it.
    """
    if destination.is_file():
        if expected_sha256 is None:
            return destination
        digest = sha256_of(destination)
        if digest == expected_sha256:
            return destination
        log(f"checksum mismatch for {destination.name}, re-downloading")
        destination.unlink()

    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    attempt = 0
    while True:
        attempt += 1
        try:
            log(f"downloading {url}")
            request = urllib.request.Request(
                url, headers={"User-Agent": "pikit-build/1.0"}
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                # `Content-Length` is the only thing that can tell a short transfer
                # from a complete one: a server that closes early is not an
                # exception, and `copyfileobj` returns what it got. Measured — a
                # 32.8 MB bootstrap arrived as 14.2 MB, with a valid zip header, and
                # the failure surfaced minutes later as `BadZipFile` while patching
                # the archive.
                announced = response.headers.get("Content-Length")
                with open(partial, "wb") as handle:
                    shutil.copyfileobj(response, handle, length=1 << 20)
            written = partial.stat().st_size
            if announced is not None and written != int(announced):
                raise OSError(f"short download: {written} of {announced} bytes")
            break
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            if attempt >= 5:
                raise
            log(f"  attempt {attempt} failed ({error}); retrying in 3s")
            time.sleep(3)

    if expected_sha256 is not None:
        digest = sha256_of(partial)
        if digest != expected_sha256:
            partial.unlink(missing_ok=True)
            raise SystemExit(
                f"Checksum mismatch for {url}\n  expected {expected_sha256}\n  got      {digest}"
            )

    partial.replace(destination)
    return destination


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def url_key(url: str) -> str:
    """
    A short, stable key for a URL, for naming the file it is downloaded into.

    Twelve hex characters of the URL's SHA-256: the bootstrap archive's URL carries
    BOOTSTRAP_TAG, so a tag bump has to produce a different cache *name*, or the
    download is skipped and the image is built from the previous tag's archive —
    silently, because the revision the app compares is hashed from the tag, not from
    the bytes. A content hash of the download is not available before the download.
    """
    return hashlib.sha256(url.encode()).hexdigest()[:12]


# --------------------------------------------------------------------------- #
# apt index
# --------------------------------------------------------------------------- #


@dataclass
class DebianPackage:
    name: str
    version: str
    architecture: str
    filename: str
    size: int
    depends: list[str] = field(default_factory=list)
    sha256: str | None = None


def parse_packages_index(text: str) -> dict[str, DebianPackage]:
    packages: dict[str, DebianPackage] = {}
    for paragraph in text.split("\n\n"):
        fields: dict[str, str] = {}
        current_key = None
        for line in paragraph.splitlines():
            if line.startswith(" ") and current_key:
                fields[current_key] += " " + line.strip()
                continue
            if ": " in line:
                current_key, value = line.split(": ", 1)
                fields[current_key] = value.strip()
        if "Package" not in fields or "Filename" not in fields:
            continue

        depends: list[str] = []
        for clause in fields.get("Depends", "").split(","):
            clause = clause.strip()
            if not clause:
                continue
            # Alternatives are ordered by preference; take the first.
            alternative = clause.split("|")[0].strip()
            # Strip version constraints: "libicu (>= 78)" -> "libicu"
            name = alternative.split("(")[0].strip()
            if name and not name.startswith("${"):
                depends.append(name)

        package = DebianPackage(
            name=fields["Package"],
            version=fields.get("Version", ""),
            architecture=fields.get("Architecture", ""),
            filename=fields["Filename"],
            size=int(fields.get("Size", "0") or 0),
            depends=depends,
            sha256=fields.get("SHA256") or None,
        )
        packages[package.name] = package
    return packages


def load_apt_index(arch: str) -> dict[str, DebianPackage]:
    """The apt index for `arch`, from the cache when it is there.

    Cached by architecture and *not* by URL, deliberately: this URL is the Termux
    repository's moving `stable` suite — it is not derived from BOOTSTRAP_TAG — so
    its content changes with no way to name the version in a filename. Keeping the
    first one a machine fetched is what makes two builds of one commit agree; the
    cost is that a new *package version* needs the cache file deleted, which
    `--refresh-images` does not do (see [download] for the two caches that do).
    """
    url = f"{APT_BASE}/dists/{APT_SUITE}/{APT_COMPONENT}/binary-{arch}/Packages.bz2"
    cached = CACHE_DIR / f"Packages-{arch}.bz2"
    download(url, cached)
    text = bz2.decompress(cached.read_bytes()).decode("utf-8", errors="replace")
    packages = parse_packages_index(text)
    log(f"apt index for {arch}: {len(packages)} packages")
    return packages


def resolve_closure(
    roots: list[str], index: dict[str, DebianPackage]
) -> list[DebianPackage]:
    """Transitively resolves `roots`, returning packages with dependencies first."""
    ordered: list[DebianPackage] = []
    seen: set[str] = set()
    visiting: set[str] = set()

    def visit(name: str) -> None:
        if name in seen:
            return
        package = index.get(name)
        if package is None:
            log(f"  warning: '{name}' is not in the index; skipping")
            seen.add(name)
            return
        if name in visiting:
            return
        visiting.add(name)
        for dependency in package.depends:
            visit(dependency)
        visiting.discard(name)
        seen.add(name)
        ordered.append(package)

    for root in roots:
        visit(root)
    return ordered


# --------------------------------------------------------------------------- #
# .deb extraction
# --------------------------------------------------------------------------- #


def deb_entry_to_relative(name: str) -> str | None:
    """
    Maps a Debian tarball member path to a path relative to `$PREFIX`.

    Members are rooted at the filesystem, e.g.
    `./data/data/com.termux/files/usr/bin/node`, while our archives are
    relative to the prefix, so the prefix has to come off. Returns `None` for
    members outside the prefix, which cannot be placed in the overlay.
    """
    relative = name[2:] if name.startswith("./") else name
    relative = relative.lstrip("/")
    if relative.startswith(DEB_PATH_PREFIX):
        return relative[len(DEB_PATH_PREFIX):]
    if relative in ("", PREFIX.strip("/")):
        return ""
    return None


def extract_deb(deb_path: Path, destination: Path) -> list[tuple[str, str]]:
    """
    Unpacks a .deb's data member into `destination` (the overlay root, which
    maps onto `$PREFIX`).

    Returns the symlinks found, as (target, path-relative-to-prefix) pairs, which
    the runtime zip cannot represent directly and so must record in
    `SYMLINKS.txt` for the on-device installer to recreate.
    """
    with open(deb_path, "rb") as handle:
        if handle.read(8) != b"!<arch>\n":
            raise SystemExit(f"{deb_path} is not an ar archive")
        member = None
        while True:
            header = handle.read(60)
            if len(header) < 60:
                break
            name = header[0:16].decode("ascii", "replace").strip()
            size = int(header[48:58].decode("ascii", "replace").strip() or 0)
            data = handle.read(size)
            if size % 2:
                handle.read(1)
            if name.startswith("data.tar"):
                member = data
                break

    if member is None:
        raise SystemExit(f"{deb_path} has no data.tar member")

    stream: io.BufferedIOBase = io.BytesIO(member)
    if member[:2] == b"\x1f\x8b":
        import gzip

        stream = gzip.GzipFile(fileobj=io.BytesIO(member))  # type: ignore[assignment]
    elif member[:6] == b"\xfd7zXZ\x00":
        import lzma

        stream = lzma.LZMAFile(io.BytesIO(member))  # type: ignore[assignment]
    elif member[:4] == b"\x28\xb5\x2f\xfd":
        stream = zstd_stream(member)  # type: ignore[assignment]

    symlinks: list[tuple[str, str]] = []
    skipped = 0
    with tarfile.open(fileobj=stream, mode="r|") as tar:
        for entry in tar:
            relative = deb_entry_to_relative(entry.name)
            if relative is None:
                # Every member is preceded by its ancestor directories
                # (`./`, `./data`, `./data/data`, ...). Those are created
                # implicitly by the mkdir below, so only count real strays.
                raw = (entry.name[2:] if entry.name.startswith("./") else entry.name)
                raw = raw.strip("/")
                if raw and not DEB_PATH_PREFIX.startswith(raw + "/"):
                    skipped += 1
                continue
            if not relative:
                continue

            target = destination / relative
            if entry.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)

            if entry.issym() or entry.islnk():
                # Recorded for SYMLINKS.txt; the zip stores no link entries.
                symlinks.append((entry.linkname, relative))
                continue

            if not entry.isfile():
                continue

            source = tar.extractfile(entry)
            if source is None:
                continue
            with open(target, "wb") as out:
                shutil.copyfileobj(source, out)
            os.chmod(target, entry.mode & 0o7777 or 0o644)

    if skipped:
        log(
            f"  note: skipped {skipped} entries of {deb_path.name} that lie "
            "outside $PREFIX"
        )

    return symlinks


def zstd_stream(member: bytes):
    try:
        import zstandard  # type: ignore
    except ImportError as error:  # pragma: no cover - depends on host
        raise SystemExit(
            "A .deb member is zstd-compressed but the 'zstandard' module is "
            "missing. Install it with:\n\n    python -m pip install zstandard\n"
        ) from error
    return zstandard.ZstdDecompressor().stream_reader(io.BytesIO(member))


# --------------------------------------------------------------------------- #
# pi vendoring
# --------------------------------------------------------------------------- #


def vendor_pi(pi_cache: Path, pi_version: str) -> Path:
    """
    Produces a self-contained pi tree with `npm ci`.

    `--omit=optional` is essential rather than cosmetic: pi's shrinkwrap pins 26
    `@esbuild/*` platform packages and a set of clipboard binaries as optional
    dependencies, which together are ~280 MiB and are never loaded at runtime.
    Omitting them takes the tree from ~396 MiB to ~101 MiB. `--ignore-scripts`
    is safe because pi declares no install lifecycle scripts, and the three
    dependencies that do are no-ops on Linux.

    The cache is keyed by the version, through [VENDORED_MARKER]: a warm cache is
    reused only when it holds the version this build asked for, so bumping
    PI_VERSION (or passing `--pi-version`) actually moves the agent instead of
    rebuilding an image around the tree that was vendored first.
    """
    spec = f"{PI_PACKAGE}@{pi_version}"
    marker = pi_cache / VENDORED_MARKER
    node_modules = pi_cache / "node_modules"

    if node_modules.is_dir():
        held = read_vendored_marker(marker).get("spec")
        if held == spec:
            log(f"reusing vendored pi from cache ({spec})")
            return pi_cache
        log(
            f"re-vendoring pi: the cache holds {held or 'an unrecorded version'}, "
            f"this build wants {spec}"
        )
        shutil.rmtree(pi_cache, ignore_errors=True)

    pi_cache.mkdir(parents=True, exist_ok=True)
    log(f"vendoring {spec} with npm ci (this downloads ~100 MiB)")
    run(
        [
            "npm", "install", spec,
            "--omit=dev", "--omit=optional", "--ignore-scripts",
            "--no-audit", "--no-fund", "--loglevel=error",
        ],
        cwd=pi_cache,
    )
    # Written after the install, never before: a marker that outlives a failed
    # install is a cache that claims to hold something it does not.
    marker.write_text(json.dumps({"spec": spec}) + "\n", encoding="utf-8")
    return pi_cache


def read_vendored_marker(marker: Path) -> dict:
    """
    What a vendoring cache records about itself: its `spec` (the package and version it
    holds) and, where the writer sets it, `pristine` — whether the tree is what npm
    installed rather than the image builder's trimmed copy. An unreadable or absent
    marker is an empty dict, which matches neither, so the tree is vendored again.
    """
    try:
        held = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return held if isinstance(held, dict) else {}



def run(command: list[str], cwd: Path) -> None:
    log("  $ " + " ".join(command))
    executable = shutil.which(command[0])
    if executable is None:
        raise SystemExit(f"Required program not found on PATH: {command[0]}")
    completed = subprocess.run(
        [executable, *command[1:]],
        cwd=str(cwd),
        shell=False if os.name != "nt" else True,  # npm is a .cmd shim on Windows
    )
    if completed.returncode != 0:
        raise SystemExit(f"Command failed with exit code {completed.returncode}: {command}")


def vendor_web_access(cache: Path) -> Path:
    """
    Vendors the bundled web-access extension, and returns its project directory.

    The extension's own documentation installs it with

        pi install npm:pi-web-access

    which writes `npm:pi-web-access` into pi's user settings under `$HOME` and
    installs the package there. PiKit does not do that on the device, for three
    reasons: it needs npm and a network at a moment the app may be offline; it
    installs into the user's own directory, which is the one place this project
    treats as the user's (and where a reinstall of the runtime must not reach);
    and the version would then drift with whatever npm resolved that day, so two
    installs of the same APK could run different tools.

    So the package is vendored into the image instead, under
    `$PREFIX/lib/node_modules/pikit-extensions`, and the app registers the
    directory in pi's global settings by absolute path — pi's own *local path*
    package source, which loads it through the package's `pi` manifest and needs
    no npm at runtime.

    `--legacy-peer-deps` is what keeps this affordable: the package declares the
    `@earendil-works/pi-*` packages as peers, and npm would otherwise install a
    second full copy of pi (measured: 193 MB with peers, 30 MB without). pi is
    already at `$PREFIX/lib/node_modules`, which Node finds by walking up from
    this directory at runtime.

    `--omit=dev --omit=optional --ignore-scripts` matches the pi vendoring: no
    test tooling, no platform-specific optional binaries, no install scripts. The
    one optional dependency that is *not* optional in practice is named in
    [WEB_ACCESS_RUNTIME_DEPS] and installed on its own — `--omit=optional` took it
    out and every `fetch_content` call failed without it.

    What it returns is the tree npm installed, untouched: the trim runs on the copy the
    image ships ([install_web_access]), so a change to the rule reaches a warm cache
    instead of being silently absent from the image. That used not to be true — the cache
    itself was trimmed — which is what the marker's `pristine` flag remembers.
    """
    node_modules = cache / "node_modules"
    package_json = cache / "package.json"
    installed = node_modules / WEB_ACCESS_PACKAGE
    marker = cache / VENDORED_MARKER
    spec = f"{WEB_ACCESS_PACKAGE}@{WEB_ACCESS_VERSION}"
    missing = [
        name for name in WEB_ACCESS_RUNTIME_DEPS
        if not (node_modules / name).is_dir()
    ]
    # The documentation is required by the same check and for the same reason: it is
    # what [WEB_ACCESS_DOCS] protects from the trim, so a cache without it is a cache
    # that would ship the extension with no reference for its options. `held == spec`
    # would not notice — the version did not move — which is the failure mode this whole
    # file is written against.
    missing += [name for name in WEB_ACCESS_DOCS if not (cache / name).is_file()]
    # The version is checked, not just the presence of a tree, and that check was
    # missing: `WEB_ACCESS_VERSION` went into the revision digest, so bumping it
    # rebuilt the image — around the extension the cache had vendored first, because
    # nothing here compared the cache against what this build asked for. It is the bug
    # `vendor_pi` already has `VENDORED_MARKER` for, in the same shape and for the same
    # reason. The old tree could not be caught by the version in `build-metadata.json`
    # either: that file is written from `WEB_ACCESS_VERSION`, so it would have named
    # 0.30.0 over a 0.29.0 tree.
    held = read_vendored_marker(marker)
    reusable = held.get("spec") == spec and held.get("pristine") is True
    if installed.is_dir() and not missing and reusable:
        log(f"reusing vendored web-access extension from cache ({spec})")
        # Verified on the reuse path too, and that is the point of it: a cache that
        # was built before this check existed, or emptied of one directory by hand,
        # is exactly the tree that shipped the bug.
        verify_web_access(cache)
        return cache
    if installed.is_dir():
        # Regenerated rather than patched in place: the cache is a build artefact,
        # and a tree that was half-updated is how two builds come to differ.
        if held.get("spec") != spec:
            log(
                f"re-vendoring the web-access extension: the cache holds "
                f"{held.get('spec') or 'an unrecorded version'}, this build wants {spec}"
            )
        elif held.get("pristine") is not True:
            log(
                "re-vendoring the web-access extension: the cache was written by a build "
                "that trimmed it in place, so it is not the tree npm installs"
            )
        else:
            log(f"re-vendoring the web-access extension: {', '.join(missing)} missing from the cache")
        shutil.rmtree(node_modules)
        marker.unlink(missing_ok=True)

    cache.mkdir(parents=True, exist_ok=True)
    package_json.write_text(
        json.dumps(
            {
                "name": "pikit-extensions",
                "version": "0.0.0",
                "private": True,
                "description": "Extensions PiKit bundles for the agent.",
                # Named here rather than installed in a second npm call, so the
                # whole tree is resolved once and the lock file records it.
                "dependencies": dict(WEB_ACCESS_RUNTIME_DEPS),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    log(f"vendoring {WEB_ACCESS_PACKAGE}@{WEB_ACCESS_VERSION} (this downloads ~30 MiB)")
    run(
        [
            "npm", "install", f"{WEB_ACCESS_PACKAGE}@{WEB_ACCESS_VERSION}",
            "--omit=dev", "--omit=optional", "--ignore-scripts", "--legacy-peer-deps",
            "--no-audit", "--no-fund", "--loglevel=error",
        ],
        cwd=cache,
    )
    if not installed.is_dir():
        raise SystemExit(f"{WEB_ACCESS_PACKAGE} was not installed into {cache}")
    for name in WEB_ACCESS_RUNTIME_DEPS:
        if not (node_modules / name).is_dir():
            raise SystemExit(
                f"{name} was not installed into {cache}. It is an *optional* "
                f"dependency of the extension's extractor, so it needs naming in "
                f"the generated package.json; without it every fetch_content call "
                f"fails with `Cannot find module`."
            )
    for name in WEB_ACCESS_DOCS:
        if not (cache / name).is_file():
            raise SystemExit(
                f"{name} is missing from the vendored {WEB_ACCESS_PACKAGE}. It is the "
                f"extension's only documentation and [trim_vendor_tree] is told to keep "
                f"it; a release that stopped shipping it is a version of the extension "
                f"whose options no longer have a reference on the device."
            )

    # After the verification above, never before: a marker that outlives a failed
    # install is a cache that claims to hold something it does not. Same rule as the
    # pi vendoring's own marker.
    #
    # `pristine` records that this cache is what npm installed, untouched. It was not
    # always: the trim used to run on the cache itself, so a build host's copy is a tree
    # with another rule's deletions already applied, and reusing it would ship an image
    # missing whatever the current rule keeps — silently, because the version did not
    # move. A marker without the flag is such a cache, and it is re-vendored once.
    marker.write_text(
        json.dumps({"spec": spec, "pristine": True}) + "\n", encoding="utf-8"
    )
    verify_web_access(cache)
    return cache


def verify_web_access(cache: Path) -> None:
    """
    Loads the extension's extraction path once, so a missing module fails here.

    The check exists because the failure it catches is invisible to everything else
    in this build: the tree was complete as far as npm, the file list and the image
    verifier were concerned, and the first `fetch_content` on a phone — for any URL
    at all — died on `Cannot find module 'mathml-to-latex'`, an optional dependency
    of the extractor that `--omit=optional` had removed.

    It runs the real entry (`defuddle/node`) on a real page, with a formula in it, so
    it also covers the second half of that bug: `mathml-to-latex` is only *loaded*
    when the module is, but it is only *used* when a formula is met.
    """
    node = shutil.which("node")
    if node is None:
        raise SystemExit(
            "node is required to verify the vendored web-access extension, and it is "
            "not on PATH. It is already required to vendor pi, so a build host that "
            "can run this script at all has it."
        )
    # Inside the cache tree, because a bare specifier resolves from the *script's*
    # own directory rather than from the working directory; and removed again,
    # because the whole tree is copied into the image and this is not part of it.
    script = cache / "pikit-web-access-check.mjs"
    script.write_text(WEB_ACCESS_CHECK, encoding="utf-8", newline="\n")
    try:
        completed = subprocess.run(
            [node, str(script)], cwd=str(cache), capture_output=True, text=True
        )
    finally:
        script.unlink(missing_ok=True)
    if completed.returncode != 0:
        raise SystemExit(
            "the vendored web-access extension cannot extract a page, so every "
            "fetch_content call would fail on the device:\n  "
            + (completed.stdout + completed.stderr).strip()
        )
    log("  " + completed.stdout.strip())


#: What a vendored npm tree loses, and — this is the whole rule — what it loses *only*.
#: Everything else ships, including the things nobody thought about when the rule was a
#: keep list: pi's `examples/` (132 files, its documentation links into them 49 times),
#: the dependency trees' own documentation, their `.github` directory, their TypeScript
#: sources. A keep list has to be right about everything; a delete list has to be right
#: about what it names, and what it leaves behind is visible in the size of the archive.
#: Names a vendored tree loses wherever they appear, each with the reason it does — the
#: reason is what a reader of the check's output sees, so it is worth being exact.
#: `.yarn` is the one that is not about testing: it is a package manager's own plugin and
#: release bundles, published inside a dependency, and `@mixmark-io/domino` alone ships
#: 1.07 MB of them.
VENDOR_JUNK_DIRS = {
    "test": "test fixtures",
    "tests": "test fixtures",
    "__tests__": "test fixtures",
    ".yarn": "a package manager's own bundles",
}
VENDOR_JUNK_FILES = ("README.md", "CHANGELOG.md")
VENDOR_JUNK_SUFFIXES = (".map",)

#: The scope in a vendored `node_modules` that the two dependency rules below leave alone.
#:
#: `@earendil-works/pi-*` is pi's own API: an extension author codes against those type
#: declarations and reads those chapters, so they are the one part of a dependency tree
#: that is documentation for *this* app rather than a JavaScript library's npm page. 370
#: `.d.ts` files, 0.75 MB, against the 12.36 MB of type declarations the other 87 packages
#: in pi's tree ship and nothing reads: Node ignores a `types` condition at runtime, and
#: jiti strips types rather than resolving declarations, so no `.d.ts` is ever loaded on a
#: device. They are the largest thing left in the image that cannot run.
PI_API_SCOPE = "@earendil-works"

#: The one thing pi's own tree drops on top of the shared rule: four screenshots, 2.29 MB
#: of the 2.79 MB its `docs/` weighs (1.44 MB of that a sponsor's mascot). The reader is a
#: terminal on a phone and draws none of them, and the two chapters that embed one show a
#: missing image — the cost this design accepts, against 2.3 MB of archive per ABI.
PI_DROPS = ("docs/images",)

#: Documentation the shared rule would otherwise delete, as paths relative to the tree
#: being trimmed. The extension's README is the only one: it is its only documentation —
#: every provider, key and option it accepts — and `README.md` is a name the rule deletes
#: everywhere else.
WEB_ACCESS_DOCS = ("node_modules/pi-web-access/README.md",)


def vendored_package(relative: str) -> str | None:
    """
    Which npm package a path inside a vendored tree belongs to, or None when the path is
    the tree's own — pi's or the extension's — rather than a dependency's.
    """
    directories = relative.split("/")[:-1]
    if "node_modules" not in directories:
        return None
    # The last `node_modules` before the file: a dependency's own nested `node_modules`
    # is what decides there, not the tree's outermost one.
    at = len(directories) - 1 - directories[::-1].index("node_modules")
    following = directories[at + 1:]
    if not following:
        return None
    return "/".join(following[:2]) if following[0].startswith("@") else following[0]


def vendor_junk(relative: str, drop: tuple[str, ...] = ()) -> str | None:
    """
    Why a vendored tree loses this path, or None when it ships.

    One predicate, two callers: [trim_vendor_tree] deletes by it, and
    `tools/verify-runtime-image.py` derives what the image owes by it. Two spellings of
    this rule is how a check and its subject drift apart while both look right.

    `relative` is a posix path relative to the tree being trimmed, and `drop` is the
    per-tree extra (`PI_DROPS`, `WEB_ACCESS_DROPS`).
    """
    parts = relative.split("/")
    for part in parts:
        if part in VENDOR_JUNK_DIRS:
            return VENDOR_JUNK_DIRS[part]
    if parts[-1] in VENDOR_JUNK_FILES:
        return "the package's own npm-page prose"
    if parts[-1].endswith(VENDOR_JUNK_SUFFIXES):
        return "source maps"
    package = vendored_package(relative)
    if package is not None and not package.startswith(PI_API_SCOPE):
        if parts[-1].endswith(".d.ts"):
            return "a dependency's type declarations"
        if "docs" in parts[:-1]:
            return "a dependency's manual"
    if any(relative == entry or relative.startswith(entry + "/") for entry in drop):
        return "the npm page's artwork"
    return None


def vendor_kept(relative: str, keep: tuple[str, ...]) -> bool:
    """Whether `keep` protects this path from [vendor_junk] — the rule's exception."""
    return any(relative == entry or relative.startswith(entry + "/") for entry in keep)


def trim_vendor_tree(
    root: Path, *, drop: tuple[str, ...] = (), keep: tuple[str, ...] = ()
) -> None:
    """
    Removes the parts of a vendored npm tree that [vendor_junk] names.

    Measured on the web-access tree (33.4 MB uncompressed, 7,773 files): the test
    fixtures, the npm page's artwork and prose, the source maps, a package manager's own
    bundles and the dependencies' type declarations and manuals are 17.8 MB of it, leaving
    15.7 MB to ship. `@mixmark-io/domino` alone is 3.4 MB of HTML5-parser conformance data
    inside a runtime dependency and ships 1.07 MB of Yarn plugin bundles, and `LICENSE`
    files always stay: they are the terms the code ships under.

    `drop` is the tree's own extra ([PI_DROPS], [WEB_ACCESS_DROPS]) and `keep` its
    exception ([WEB_ACCESS_DOCS]). Both are paths relative to `root`.
    """
    removed = 0
    # Deepest first, so a directory the rule names is removed once rather than walked
    # twice — its files would otherwise be counted twice as well.
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.is_dir():
            continue
        relative = path.relative_to(root).as_posix()
        if vendor_kept(relative, keep) or not vendor_junk(relative, drop):
            continue
        removed += sum(f.stat().st_size for f in path.rglob("*") if f.is_file())
        shutil.rmtree(path, ignore_errors=True)
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if vendor_kept(relative, keep) or not vendor_junk(relative, drop):
            continue
        removed += path.stat().st_size
        path.unlink()
    log(f"  trimmed {removed / 1e6:.1f} MB of vendored source material")


def install_rewrite_exclusions(overlay_root: Path) -> None:
    """
    Writes the prefix-rewrite exclusion list into the image, for the app to read.

    `prefix_patch.NEVER_REWRITE` protects the eight files the build itself wrote —
    most importantly `libexec/pikit/relocate.js`, which has to name the upstream id
    because that is what it rewrites away from. The app has a second rewriter:
    `PrefixPatcher`, which repairs a tree after `pkg install` and is reached from
    the settings button. It did not know about the list, and it rewrote exactly
    those files: pressing the button turned `OLD_ID` into `NEW_ID`, after which the
    relocator refused to run at all (`exit 4`), the storage self-test's two
    relocator checks failed, and every package installed afterwards went
    unrelocated. A repair that damages the thing that does the repairs.

    The list is written here rather than repeated in Kotlin so the two cannot
    drift; `PrefixPatcher` also carries a fallback for a runtime image built
    before this file existed.
    """
    destination = overlay_root / REWRITE_EXCLUSIONS
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        "# Files the prefix rewrite must not touch, one path per line, relative to\n"
        "# $PREFIX. Generated by tools/build-runtime-image.py from\n"
        "# prefix_patch.NEVER_REWRITE; the app reads it for its own repair pass.\n"
        + "".join(f"{name}\n" for name in sorted(prefix_patch.NEVER_REWRITE)),
        encoding="utf-8",
        newline="\n",
    )
    log(f"recorded {len(prefix_patch.NEVER_REWRITE)} rewrite exclusion(s)")


def install_web_access(overlay_root: Path, vendored: Path) -> None:
    """
    Copies the vendored extension into the overlay, trims the copy, and records how to
    find it.

    The trim is here rather than in [vendor_web_access] so that the cache stays the tree
    npm installed: pi's tree has always been trimmed this way, on the copy, and the
    extension's used to be trimmed in place — which made a rule change invisible on a warm
    cache and cost the marker's `pristine` flag to recover from.
    """
    destination = overlay_root.joinpath(*WEB_ACCESS_ROOT)
    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(vendored, destination, symlinks=True)
    trim_vendor_tree(destination, drop=WEB_ACCESS_DROPS, keep=WEB_ACCESS_DOCS)

    metadata = overlay_root / WEB_ACCESS_METADATA
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(
        json.dumps(
            {
                "name": WEB_ACCESS_PACKAGE,
                "version": WEB_ACCESS_VERSION,
                # Relative to `$PREFIX`, which the app knows: the absolute path
                # contains the application id, and writing it here would make the
                # image un-reusable (and un-comparable) for a different id.
                "path": WEB_ACCESS_RELATIVE,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    log(f"installed the web-access extension at {WEB_ACCESS_RELATIVE}")


# --------------------------------------------------------------------------- #
# Assembly
# --------------------------------------------------------------------------- #


def build_for(arch: str, flavor: str, pi_version: str | None, keep_staging: bool) -> None:
    info = ARCHES[arch]
    abi = info["abi"]
    assets_dir = REPO_ROOT / "app" / f"src/{flavor}" / "assets" / "runtime" / abi
    staging = STAGING_ROOT / arch
    overlay_root = staging / "overlay"

    # Resolved here rather than at each use, so the tree that is vendored, the
    # revision digest and the metadata the app reads cannot disagree about which pi
    # this image carries. `--pi-version` is the override; PI_VERSION is the answer.
    pi_version = pi_version or PI_VERSION

    log(f"=== building {arch} -> {assets_dir.relative_to(REPO_ROOT)} (pi {pi_version}) ===")

    if not keep_staging and staging.exists():
        shutil.rmtree(staging)
    overlay_root.mkdir(parents=True, exist_ok=True)

    # 1. bootstrap, verbatim -------------------------------------------------
    # The cache file names the URL it came from: `bootstrap-{arch}.zip` was reused
    # across a BOOTSTRAP_TAG bump, which built an image from the previous bootstrap
    # and gave it the new tag's revision.
    bootstrap_url = BOOTSTRAP_URL.format(arch=arch)
    bootstrap = CACHE_DIR / f"bootstrap-{arch}-{url_key(bootstrap_url)}.zip"
    download(bootstrap_url, bootstrap)
    # A backstop for the check [download] makes: a truncated archive can also arrive
    # from a machine that had one before that check existed, and the message it
    # produces hours later is a `BadZipFile` traceback from inside the zip module.
    if not zipfile.is_zipfile(bootstrap):
        raise SystemExit(
            f"{bootstrap} is not a zip file, which normally means a truncated download. "
            "Delete it and build again."
        )
    log(f"bootstrap: {bootstrap.stat().st_size / 1e6:.1f} MB")

    # 2. apt packages --------------------------------------------------------
    index = load_apt_index(arch)
    resolved = resolve_closure(ROOT_PACKAGES, index)
    log("layering packages: " + ", ".join(f"{p.name} {p.version}" for p in resolved))

    symlinks: list[tuple[str, str]] = []
    for package in resolved:
        url = f"{APT_BASE}/{package.filename}"
        deb = CACHE_DIR / "debs" / safe_filename(
            f"{package.name}_{package.version}_{package.architecture}.deb"
        )
        download(url, deb, expected_sha256=package.sha256)
        symlinks.extend(extract_deb(deb, overlay_root))

    # 3. pi ------------------------------------------------------------------
    pi_cache = vendor_pi(CACHE_DIR / "pi", pi_version)
    pi_source = pi_cache / "node_modules" / PI_PACKAGE
    if not pi_source.is_dir():
        raise SystemExit(f"pi was not vendored correctly: {pi_source} is missing")
    pi_destination = overlay_root / "lib" / "node_modules" / PI_PACKAGE
    if pi_destination.exists():
        shutil.rmtree(pi_destination)
    pi_destination.parent.mkdir(parents=True, exist_ok=True)
    log("copying pi into the overlay")
    shutil.copytree(pi_source, pi_destination, symlinks=True)

    # The same trim the web-access tree gets, and for the same reason: pi's dependency
    # tree ships source maps, test fixtures, type declarations and the npm page of a
    # hundred packages. Measured over the vendored tree (105.5 MB, 14,007 files) under the
    # rule above, 51.5 MB of it goes and 53.9 MB ships: source maps 33.94 MB, the
    # dependencies' type declarations 12.36 MB, four screenshots 2.29 MB ([PI_DROPS]),
    # `README.md`/`CHANGELOG.md` 2.12 MB, the dependencies' manuals 0.42 MB and test
    # fixtures 0.38 MB.
    #
    # What a keep list cost, for the record: it removed 40.3 MB and deleted pi's
    # `examples/` (0.96 MB, 132 files its own documentation links into 49 times) and the
    # dependency trees' documentation (0.57 MB) without anyone deciding to — which is why
    # the rule is a delete list. Against that rule the archive is 4.7 MB smaller per ABI
    # (arm64 75.08 -> 70.38 MB, x86_64 74.59 -> 69.88 MB) and an APK that stores the
    # archive uncompressed by the same. Introducing the trim at all took `overlay.zip`
    # from 82.63 MB to 72.68 MB and the arm64 release APK from 111.8 MB to 107.2 MB
    # (measured 2026-09-15).
    trim_vendor_tree(pi_destination, drop=PI_DROPS)

    # 3b. the bundled web-access extension ----------------------------------
    # Ahead of the prefix rewrite below, so it is covered by it like everything
    # else in the overlay, and ahead of the zip, so it is what ships.
    install_web_access(overlay_root, vendor_web_access(CACHE_DIR / "web-access"))

    # 4. wrappers ------------------------------------------------------------
    bin_dir = overlay_root / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    write_executable(
        bin_dir / "pi",
        "#!/data/data/{}/files/usr/bin/sh\n"
        "exec \"$PREFIX/bin/node\" \"$PREFIX/{}\" \"$@\"\n".format(APP_ID, PI_ENTRY_RELATIVE),
    )

    install_relocator(overlay_root)
    install_rewrite_exclusions(overlay_root)

    # 4b. the dpkg wrapper ---------------------------------------------------
    # The bootstrap ships `bin/dpkg`, and every package installation goes through
    # it, so a wrapper there cannot be bypassed by a route apt did not mediate —
    # including a `file://` archive, which apt never copies into its cache and
    # which the apt hook therefore cannot see. Verified on a device: the hook ran,
    # found nothing, and dpkg unpacked a package still naming `com.termux`.
    extra_members, replaces_members = bootstrap_dpkg_wrapper()
    log("staged the dpkg wrapper for the bootstrap archive")

    # 5. relocate the prefix ------------------------------------------------
    # The zip cannot carry symlinks, so they are recorded the way Termux does.
    # This has to happen *before* the rewrite so the recorded targets are
    # rewritten as well.
    write_symlinks_txt(overlay_root, symlinks)

    # The packages above are compiled for /data/data/com.termux/files/usr. The
    # whole tree is rewritten to the application id PiKit actually ships
    # as, which is what lets it coexist with a real Termux install.
    log(f"relocating prefix {UPSTREAM_PREFIX} -> {PREFIX}")
    overlay_stats = prefix_patch.patch_tree(overlay_root, APP_ID)
    log(f"  overlay: {overlay_stats.note()}")
    if overlay_stats.errors:
        raise SystemExit(f"overlay patch errors: {overlay_stats.errors[:5]}")
    verify_relocator(overlay_root)

    # The bootstrap ships as an opaque blob, so it is rewritten member-wise,
    # including its SYMLINKS.txt. Doing it here rather than on the device means
    # the installer creates correctly-targeted links from the start, and that
    # the result can be verified offline.
    assets_dir.mkdir(parents=True, exist_ok=True)
    log("relocating bootstrap archive")
    bootstrap_stats = prefix_patch.patch_archive(
        bootstrap,
        assets_dir / "bootstrap.zip",
        APP_ID,
        extra_members=extra_members,
        replaces_members=replaces_members,
    )
    log(f"  bootstrap: {bootstrap_stats.note()}")
    if bootstrap_stats.errors:
        raise SystemExit(f"bootstrap patch errors: {bootstrap_stats.errors[:5]}")

    write_zip(overlay_root, assets_dir / "overlay.zip")

    revision = compute_revision(arch, assets_dir, resolved, pi_version)
    (assets_dir / "revision.txt").write_text(revision + "\n", encoding="utf-8")

    log(f"bootstrap.zip {(assets_dir / 'bootstrap.zip').stat().st_size / 1e6:.1f} MB")
    log(f"overlay.zip   {(assets_dir / 'overlay.zip').stat().st_size / 1e6:.1f} MB")
    log(f"revision      {revision}")

    summary = {
        "arch": arch,
        "abi": abi,
        "flavor": flavor,
        "prefix": PREFIX,
        "bootstrap_tag": BOOTSTRAP_TAG,
        "packages": {p.name: p.version for p in resolved},
        "pi_version": pi_version,
        "web_access": {"name": WEB_ACCESS_PACKAGE, "version": WEB_ACCESS_VERSION},
        "revision": revision,
        "symlinks": len(symlinks),
    }
    (assets_dir / "build-metadata.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )

    # The staging tree is ~240 MB per architecture of extracted package contents,
    # and nothing reads it once the archives are written — `compute_revision` reads
    # only the archives and the hand-written tools. It used to be left behind and
    # deleted at the *start* of the next build, so two architectures meant a
    # permanent ~480 MB under `.runtime-build/staging` plus 22,000 file creations
    # per rebuild. `--keep-staging` is what keeps it now, for inspecting a build.
    #
    # The start-of-run delete stays as well, so a build that fails halfway still
    # leaves at most one run's worth of staging rather than an ever-growing tree.
    if not keep_staging:
        shutil.rmtree(staging, ignore_errors=True)


def write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")
    os.chmod(path, 0o700)


def install_relocator(overlay_root: Path) -> None:
    """
    Installs the package relocator and wires apt into it.

    This is what makes `pkg install` transparent. Termux packages are compiled
    for `/data/data/com.termux/files/usr`; the image is relocated to this app's
    id, but anything installed later is not. Rather than repairing the tree after
    the fact — which leaves a window in which the new binary cannot run, and
    which the user had to trigger by leaving the terminal — apt is told to
    rewrite each archive *before* dpkg unpacks it.

    Two pieces:

      * `libexec/pikit/relocate.js` and the `pikit-relocate` wrapper that runs it
        under `$PREFIX/bin/node`, which is the only interpreter guaranteed to be
        present that can do a byte-level rewrite. The relocator is listed in
        `prefix_patch.NEVER_REWRITE`, because it is the one file in the image
        that has to *name* the upstream id, and rewriting it made it a no-op that
        reported success.
      * `etc/apt/apt.conf.d/99pikit-relocate`, which binds `--apt-list` to
        `DPkg::Pre-Install-Pkgs`. apt runs that hook with the list of archives it
        is about to hand to dpkg on the hook's stdin, which is the same list dpkg
        receives — so it covers a single package and a batch of ninety-six alike,
        including the copies apt stages under `tmp/apt-dpkg-install-*` for a
        batch, which no scan of the archive cache can see.

        It replaced a `DPkg::Pre-Invoke` hook that scanned the cache and got two
        things wrong. The cache is not `$PREFIX/var/cache/apt`: Termux's apt has
        `/data/data/<app-id>/cache/apt` compiled in, so the hook reported
        "nothing to relocate" for every run while the real cache went untouched.
        And rewriting a cached archive changes its size, which is the check apt
        uses to decide the file it has is the one the index describes, so apt
        re-downloaded the original over the rewritten copy on the next install.
    """
    source = REPO_ROOT / "tools" / "pikit-relocate.js"
    if not source.is_file():
        raise SystemExit(f"missing {source}; it is part of the build, not generated")

    libexec = overlay_root / "libexec" / "pikit"
    libexec.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, libexec / "relocate.js")
    log(f"installed the package relocator ({source.name})")

    write_executable(
        overlay_root / "bin" / "pikit-relocate",
        "#!/data/data/{}/files/usr/bin/sh\n"
        "# Runs the package relocator under the bundled Node.\n"
        "#\n"
        "# `--apt-list` rewrites the archives apt is about to hand to dpkg; the\n"
        "# `dpkg` wrapper uses `--debs-here` on the exact archives dpkg is about to\n"
        "# unpack; run by hand, `--prefix` repairs an already-installed tree and\n"
        "# `--debs` scans apt's archive cache.\n"
        "exec \"$PREFIX/bin/node\" \"$PREFIX/libexec/pikit/relocate.js\" \"$@\"\n".format(APP_ID),
    )

    install_agent_guard(overlay_root)

    apt_conf = overlay_root / "etc" / "apt" / "apt.conf.d"
    apt_conf.mkdir(parents=True, exist_ok=True)
    hook = f'"{prefix_patch.prefix_for(APP_ID)}/bin/pikit-relocate --apt-list"'
    # Written as a plain concatenation rather than `str.format`: apt's own syntax
    # is brace-delimited, so a format string would have to escape every one of
    # them and the result would stop looking like an apt config file.
    (apt_conf / "99pikit-relocate").write_text(
        "# Installed by the PiKit build. Do not edit: it is regenerated whenever\n"
        "# the runtime image is rebuilt.\n"
        "#\n"
        "# Termux packages arrive compiled for /data/data/com.termux/files/usr. The\n"
        "# bundled image is already relocated to this app's prefix, but a package\n"
        "# installed later is not, and a binary whose DT_RUNPATH points at a\n"
        "# directory this app cannot read fails at exec time. This hook rewrites the\n"
        "# archives apt is about to hand to dpkg — the list dpkg itself receives on\n"
        "# its command line — so nothing is ever installed in a broken state and\n"
        "# `pkg install` needs no extra step.\n"
        "#\n"
        "# `DPkg::Pre-Install-Pkgs` and not `DPkg::Pre-Invoke`: a Pre-Invoke hook can\n"
        "# only see apt's archive cache, which is not `$PREFIX/var/cache/apt` —\n"
        "# Termux's apt has `/data/data/" + APP_ID + "/cache/apt` compiled in — and\n"
        "# rewriting a cached archive changes its size, which is the check apt uses\n"
        "# to decide whether the file it has is the file the index describes, so it\n"
        "# re-downloaded the original over the rewritten copy.\n"
        "#\n"
        "# The rewrite is not length-preserving: the tar stream is decompressed to be\n"
        "# edited and re-compressed afterwards, so the archive changes size. That is\n"
        "# harmless here, where the file is consumed once by the dpkg run this hook\n"
        "# was called for. The *payload* is length-preserving — every ELF keeps its\n"
        "# byte offsets, which is the whole reason the application id is ten bytes.\n"
        "\n"
        "DPkg::Pre-Install-Pkgs {\n"
        "  " + hook + ";\n"
        "};\n",
        encoding="utf-8",
        newline="\n",
    )
    log("wired the relocator into apt (DPkg::Pre-Install-Pkgs)")


def bootstrap_dpkg_wrapper() -> tuple[dict[str, tuple[bytes, int]], dict[str, str]]:
    """
    The archive edits that put a `dpkg` wrapper into the bootstrap.

    The bootstrap is the one part of the image that is *not* extracted into the
    overlay — it is patched member-wise and shipped as an opaque archive — so the
    wrapper has to be injected here rather than written to a directory. Returns
    the `extra_members` and `replaces_members` maps `patch_archive` takes.

    The real dpkg is renamed to `bin/dpkg.real` and keeps the mode it shipped
    with; the wrapper takes its place and is executable.
    """
    source = REPO_ROOT / "tools" / "pikit-dpkg.sh"
    if not source.is_file():
        raise SystemExit(f"missing {source}; it is part of the build, not generated")

    return (
        {"bin/dpkg": (source.read_bytes(), 0o755)},
        {"bin/dpkg": "bin/dpkg.real"},
    )


def verify_relocator(overlay_root: Path) -> None:
    """
    Runs the packed relocator's own `--selfcheck`, and fails the build if it fails.

    The relocator is the one file in the image that has to name the upstream
    application id, so it is excluded from the prefix rewrite, and that exclusion
    was missing once. What happened then is the reason this function exists: the
    build rewrote `OLD_ID` into this app's id, the relocator compared every
    archive against its own replacement, found zero occurrences, reported success,
    and every package installed from the Termux repository failed to unpack with
    `unable to stat './data/data/com.termux'`. Nothing else in this build could
    see it — the archives were valid, the counts were zero, and the image passed
    `verify-runtime-image.py`, which only asks whether any member still names the
    upstream id. Running the packed file is the check that fails.

    It runs on the *patched* overlay, not on `tools/pikit-relocate.js`, so it
    covers the copy that ships.
    """
    script = overlay_root / "libexec" / "pikit" / "relocate.js"
    if not script.is_file():
        raise SystemExit(f"the relocator is missing from the overlay: {script}")
    node = shutil.which("node")
    if node is None:
        raise SystemExit(
            "node is required to verify the packed relocator, and it is not on PATH.\n"
            "It is already required to vendor pi, so a build host that can run this "
            "script at all has it."
        )
    completed = subprocess.run(
        [node, str(script), "--selfcheck"],
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise SystemExit(
            "the packed relocator failed its self-check, so no package would be "
            "relocated:\n  " + (completed.stdout + completed.stderr).strip()
        )
    log("  relocator self-check: " + completed.stdout.strip())


def install_agent_guard(overlay_root: Path) -> None:
    """
    Installs the extension that stops the agent from deleting the user's files.

    Pi has no permission system by design — "no permission popups", as its own
    documentation puts it — and the app launches it with `--approve`. Combined
    with `MANAGE_EXTERNAL_STORAGE`, that means a `bash` tool call reaches every
    file on the device with nothing in the way.

    This extension subscribes to `tool_call` and refuses the calls that can
    destroy data outside the environment: a recursive delete aimed outside the
    workspace, a filesystem format, or a write to a raw block device. It is
    deliberately conservative — it blocks a pattern, it does not try to
    understand a shell — and it explains itself to the model, so the agent
    reports what happened instead of retrying blindly.

    It is shipped inside `$PREFIX` and copied into `$HOME/.pi/agent/extensions`
    by the on-device installer, which is where pi auto-discovers global
    extensions and which survives a runtime reinstall.
    """
    source = REPO_ROOT / "tools" / "pi-safety-guard.ts"
    if not source.is_file():
        raise SystemExit(f"missing {source}; it is part of the build, not generated")

    # The prefix is rewritten in place further down, so stage the file under a
    # name that needs no relocation: the extension reads $PREFIX from the
    # environment at runtime. `BootstrapInstaller.prepareHome` copies it into
    # `$HOME/.pi/agent/extensions`, which is where pi auto-discovers global
    # extensions and which survives a runtime reinstall.
    destination = overlay_root / "share" / "pikit" / "pi-safety-guard.ts"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    log("staged the agent safety guard (share/pikit)")

    install_storage_self_test(overlay_root)


def install_storage_self_test(overlay_root: Path) -> None:
    """
    Installs the storage self-test, so a user can check this themselves.

    It answers the two questions that cannot be settled by reading a flag: can the
    environment actually read and write the folders that were granted, and does
    the delete guard really leave a symlink's target alone. Both are measured
    rather than asserted, and both have been wrong in this project before — the
    permission flag said "granted" while the mount disagreed, and a `file://`
    install path bypassed the relocation entirely.

    Shipped in `$PREFIX/share/pikit` next to the guard, and also placed as
    `bin/pikit-storage-check` so it can be run from the terminal by name.
    """
    source = REPO_ROOT / "tools" / "storage-self-test.sh"
    if not source.is_file():
        raise SystemExit(f"missing {source}; it is part of the build, not generated")

    staged = overlay_root / "share" / "pikit" / source.name
    staged.parent.mkdir(parents=True, exist_ok=True)
    # Written with Unix line endings. Its shebang already names this app — the
    # script is one of the build's own files, listed in `prefix_patch.NEVER_REWRITE`
    # so the rewrite cannot reach it — and it reads `$PREFIX` from the environment
    # for everything else.
    text = source.read_text(encoding="utf-8").replace("\r\n", "\n")
    staged.write_text(text, encoding="utf-8", newline="\n")

    bin_dir = overlay_root / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    write_executable(
        bin_dir / "pikit-storage-check",
        "#!/data/data/{}/files/usr/bin/sh\n"
        "# Runs the storage self-test. See Settings -> Updates & repair.\n"
        "exec \"$PREFIX/bin/sh\" \"$PREFIX/share/pikit/{}\" \"$@\"\n".format(
            APP_ID, source.name
        ),
    )
    log("installed the storage self-test (bin/pikit-storage-check)")



def write_symlinks_txt(root: Path, symlinks: list[tuple[str, str]]) -> None:
    """
    Writes the `target←path` side-car the on-device installer reads.

    The separator is U+2190 LEFT ARROW. Paths are prefix-relative.
    """
    lines: list[str] = []
    seen: set[str] = set()
    for target, relative in symlinks:
        if relative in seen:
            continue
        seen.add(relative)
        lines.append(f"{target}\u2190./{relative}")
    (root / "SYMLINKS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    log(f"recorded {len(lines)} symlinks")


def write_zip(root: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if relative == "SYMLINKS.txt":
                archive.write(path, relative)
                continue
            if path.is_dir():
                # Record empty directories so `tmp/` survives the round trip.
                if not any(path.iterdir()):
                    archive.writestr(relative + "/", b"")
                continue
            # Store already-compressed payloads rather than recompressing them.
            compression = (
                zipfile.ZIP_STORED
                if path.suffix in {".gz", ".xz", ".bz2", ".zst", ".zip", ".png", ".wasm"}
                else zipfile.ZIP_DEFLATED
            )
            archive.write(path, relative, compress_type=compression)


def compute_revision(
    arch: str,
    assets_dir: Path,
    resolved: list[DebianPackage],
    pi_version: str,
) -> str:
    """
    A stable fingerprint so the app can detect that its image changed.

    Everything that ends up in the image has to be covered, or an installed app
    keeps the old runtime because the revision it reads back still matches. That
    is not hypothetical: the relocator and the agent safety guard are copied into
    the overlay from `tools/`, so a change to either of them would otherwise ship
    invisibly — the APK would carry the new file and the device would never
    unpack it, because `ensureInstalled` compares revisions, not contents.

    The files that *decide* the image's content are covered by content too, and
    that is a separate list from "the files inside it": the rewrite is
    length-preserving by design (the package id and the app id are both ten
    characters), so a change to `prefix_patch.py` can rebuild every archive with
    **identical sizes** — which is all the archive digest below can see. The
    builder's own code is in the same list for the same reason, and it is the
    conservative direction to be wrong in: an edit there means the APK's image is
    not the one a device already unpacked, and one extra unpack is cheaper than a
    runtime that silently stays old.
    """
    digest = hashlib.sha256()
    digest.update(arch.encode())
    digest.update(BOOTSTRAP_TAG.encode())
    # The pinned pi release, not "latest": the pin is what makes two builds of one
    # commit the same image. The digest used to take the literal string "latest" when
    # no version was pinned, which meant bumping pi moved the *archives* and so the
    # digest below — but only because npm had downloaded something new, which is the
    # accident this pin removes.
    digest.update(pi_version.encode())
    # The bundled extension's version, so bumping it reinstalls the runtime: its
    # files are inside the archives the size-and-name digest below already covers,
    # but a *removal* would not change any other input.
    digest.update(WEB_ACCESS_VERSION.encode())
    for package in resolved:
        digest.update(f"{package.name}={package.version};".encode())
    for name in ("bootstrap.zip", "overlay.zip"):
        path = assets_dir / name
        if path.is_file():
            digest.update(name.encode())
            digest.update(str(path.stat().st_size).encode())
    # The hand-written pieces of the image, by content rather than by size: these
    # are small text files and a one-character fix has to move the revision. The
    # last two are not *in* the image — one is the rewriter, the other is this
    # file — and are here because they decide what the rest of it contains.
    for source in (
        REPO_ROOT / "tools" / "pikit-relocate.js",
        REPO_ROOT / "tools" / "pikit-dpkg.sh",
        REPO_ROOT / "tools" / "pi-safety-guard.ts",
        REPO_ROOT / "tools" / "storage-self-test.sh",
        REPO_ROOT / "tools" / "prefix_patch.py",
        REPO_ROOT / "tools" / "build-runtime-image.py",
    ):
        if source.is_file():
            digest.update(source.name.encode())
            digest.update(hashlib.sha256(source.read_bytes()).digest())
    return f"{arch}-{digest.hexdigest()[:16]}"


# --------------------------------------------------------------------------- #
# Entry point
# --------------------------------------------------------------------------- #


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arch", choices=sorted(ARCHES), help="Termux architecture")
    parser.add_argument("--flavor", help="Gradle product flavour (arm64, x64)")
    parser.add_argument(
        "--pi-version",
        default=None,
        help=f"Vendor this pi release instead of the pinned {PI_VERSION}",
    )
    parser.add_argument("--all", action="store_true", help="Build every architecture")
    parser.add_argument("--keep-staging", action="store_true")
    args = parser.parse_args()

    if args.all:
        for arch, info in ARCHES.items():
            build_for(arch, info["flavor"], args.pi_version, args.keep_staging)
        return 0

    if not args.arch:
        parser.error("--arch is required unless --all is given")
    flavor = args.flavor or ARCHES[args.arch]["flavor"]
    build_for(args.arch, flavor, args.pi_version, args.keep_staging)
    return 0


if __name__ == "__main__":
    sys.exit(main())
