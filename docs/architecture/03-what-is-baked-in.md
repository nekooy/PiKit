# 3. What is baked in, and why `rg` and `fd` are not optional

*[Architecture](../ARCHITECTURE.md) §3.*

Pi's `grep` tool shells out to `rg` and its `find` tool shells out to `fd`. Pi
normally downloads those itself, but it explicitly refuses on Android, on the grounds
that the upstream Linux builds are glibc-linked and will not run on bionic — it tells the
user to `pkg install ripgrep` instead. That refusal happens at *tool call* time, not at
startup, so a missing `rg` presents as a confusing mid-conversation failure. Both are
therefore unpacked into the image, along with `pcre2` (which Termux's `ripgrep` build
requires).

The image is assembled by `tools/build-runtime-image.py` from five sources:

1. the official Termux bootstrap, taken verbatim from termux-packages and then
   relocated to PiKit's package id (see section 1);
2. Termux `.deb` packages for `nodejs`, `npm`, `ripgrep`, `fd` and their
   transitive dependencies, resolved from the apt index and unpacked into the
   same prefix;
3. a host-side `npm ci` of the pi agent;
4. a pinned, vendored copy of the `pi-web-access` extension (below);
5. generated wrappers.

Two details in that script are load-bearing:

- **Java's zip API cannot represent symlinks**, so `.deb` symlinks are recorded in
  a `SYMLINKS.txt` side-car using Termux's own `target←path` convention (U+2190).
  `BootstrapInstaller` parses exactly this and recreates the links.
- **A zip entry carries no Unix mode the JVM will apply either**, so the installer keeps a
  list of the paths that need one. It originally missed
  `lib/node_modules/<package>/bin/`, which made `bin/npm` a symlink to a file that could
  not be executed — invisible until an update to a newer pi existed. Both the installer
  and the on-demand repair cover it now.
- **`npm ci --omit=optional`** takes pi's tree from ~396 MiB to ~101 MiB by
  dropping 26 `@esbuild/*` platform packages and the desktop clipboard binaries,
  none of which are loaded at runtime on Android. `--ignore-scripts` is safe
  because pi declares no install lifecycle scripts.

`tools/verify-runtime-image.py` rebuilds the tree the installer would produce and asserts
against it: all 1291 symlinks on `arm64-v8a` (1293 on `x86_64`) resolve, every shebang
under `bin/` and `libexec/` points at something that exists, `bin/bash` and `bin/node`
are valid ELF for the target ABI with
`DT_RUNPATH=/data/data/pi.kit.mob/files/usr/lib`, and the documentation below is present
by name and by count.

## The bundled extension, and why it is not `pi install`

`pi install npm:pi-web-access` would write into pi's *user* settings under `$HOME` and
install the package there. PiKit vendors it into the image instead, for three reasons that
are one reason seen from different sides: the install needs npm and a network at a moment
the app may be offline; it would land in the user's own directory, the one place this
project treats as the user's; and its version would be whatever npm resolved that day.

The package is installed at build time under `$PREFIX/lib/node_modules/pikit-extensions`
and registered by **absolute path** in pi's global `packages` array — pi's documented
*local path* package source, which loads it through the package's own `pi` manifest
(`"extensions": ["./index.ts"]`) and needs no npm at runtime. The path is composed from
`$PREFIX` in the app, not written into the image: an absolute path in a build artifact
would bake the application id in, and the image is otherwise id-independent.

Four build details carry numbers:

- **`--legacy-peer-deps`**, because the extension declares the `@earendil-works/pi-*`
  packages as peers and npm would otherwise install a second full copy of pi: measured,
  193 MB with peers against 30 MB without. pi is already at `$PREFIX/lib/node_modules`,
  which Node finds by walking up from the extension's own directory.
- **Trimming**, because the package ships its npm page inside itself
  (`pi-web-fetch-demo.mp4`, `banner.png`, the changelog), every dependency's test fixtures,
  its source maps and its type declarations. Measured under the delete list below: 33.4 MB
  of vendored tree becomes 15.7 MB **per ABI**; `@mixmark-io/domino` alone is 3.4 MB of
  HTML5-parser conformance data inside a runtime dependency, plus 1.07 MB of Yarn plugin
  bundles. The trim runs on the copy the image ships, never on the cache — see the last
  part of that section.
- **`--omit=optional` needs one exception, named in `WEB_ACCESS_RUNTIME_DEPS`.** The flag
  exists for platform-specific optional binaries that are never loaded, but `defuddle/node`,
  the entry `extract.ts` imports for every `fetch_content`, unconditionally requires
  `mathml-to-latex` from `defuddle/dist/elements/math.full.js`, and `defuddle` declares that
  package as *optional*. Without it **every fetch failed** with `Cannot find module
  'mathml-to-latex'`. `mathml-to-latex` (with `@xmldom/xmldom`) is therefore a direct
  dependency of the generated `package.json`; `temml`, the other optional dependency on
  that path, is genuinely optional (a `try`/`catch` plain-text fallback) and stays omitted.
  `verify_web_access` runs the real entry on a page with a formula in it at the end of every
  vendoring *and* on every cache reuse, so a tree missing something the extractor loads
  fails the build.
- **A metadata file** at `share/pikit/web-access.json` names the package, its version and
  its path relative to `$PREFIX`. The settings page reads it so that a runtime image without
  the extension is a *missing file* the UI can report rather than a path the app assumes
  exists.

The extension's options are its own file, `$HOME/.pi/agent/web-search.json`, and the app
edits it directly rather than mirroring it into `SharedPreferences`: two copies of one
setting is two answers to "which provider is in use". PiKit sets one default there —
`"workflow": "none"` — because the extension's own default is `summary-review`, which opens
its result curator in a browser a phone does not have. A file that does not parse is
reported rather than overwritten. (Section 9 has the rest.)

The page used to carry two SearXNG fields (`searxngBaseUrl`, `searxngHeaders`); they were
removed with the rest of the per-provider fields because a field PiKit draws is a field
PiKit has to *write* (section 9), and the extension documents and validates them now.

## The extension's licence, and why it is named on the About page

`pi-web-access` is **MIT** (Nico Bailon, `github.com/nicobailon/pi-web-access`,
pinned by `WEB_ACCESS_VERSION = "0.30.0"` in the image builder), and so are the packages
npm resolves under it — `turndown`, `@mozilla/readability`, `defuddle`, `linkedom`,
`unpdf`, `undici`, `p-limit`, `typebox`. MIT is compatible with this app's GPLv3 as long
as the notice travels with the code, and it does: the APK contains each package's own
`LICENSE`, and **Settings → About PiKit → Credits** names the extension, its author and
the notable dependencies beside Termux, Node.js, `pi`, `ripgrep` and `fd`.

The archives are stored uncompressed in the APK (`androidResources.noCompress`)
and streamed straight out of assets, so unpacking never holds the image in
memory. Extraction goes into a staging directory that is renamed into place only
after every archive is applied, so an interrupted install cannot leave a
half-populated `$PREFIX`. `$HOME` lives outside `$PREFIX`, so reinstalling the
runtime never discards the user's Pi sessions.

## The vendored trees are trimmed by a delete list, and the documentation rides on it

Both vendored trees are trimmed of what nothing loads, and the rule that does it is a
**delete list**: `test`/`tests`/`__tests__` directories, a package manager's `.yarn`
bundles, the `.map` files beside them, every `README.md` and `CHANGELOG.md`, a
dependency's type declarations and a dependency's `docs/`, pi's four `docs/images`
screenshots, and the extension's npm-page banner and video. Everything else ships.

The shape of the rule is the part worth recording, because the first two attempts got it
wrong in opposite ways. It began as three directory names, which caught pi's own
documentation and nothing else. It was then broadened to a *keep* list — delete `docs`,
`examples`, `benchmarks`, `.md`, `.map`, and keep back the chapters — which is correct only
about what it names: it deleted pi's `examples/` (132 files, 0.96 MB, which pi's own
documentation links into 49 times), the dependency trees' documentation (0.57 MB, mostly
`undici/docs`), and the extension's only reference for its options, all without anyone
deciding to. A keep list has to be right about everything; a delete list has to be right
about what it names, and what it leaves behind is visible in the size of the archive.

Two of the entries are narrower than a name, and both are about whose documentation it is:

- **a dependency's type declarations, and a dependency's manual** — `.d.ts` and `docs/`
  inside a `node_modules`, which are 12.36 MB and 0.42 MB of pi's tree and 1.04 MB and
  0.42 MB of the extension's. Nothing loads either on a device: Node ignores a `types`
  condition at runtime, and jiti strips types rather than resolving declarations. The
  exception is `@earendil-works/*`, which the rule leaves whole — 370 `.d.ts` files,
  0.75 MB — because that is pi's own API, what an extension author codes against, beside
  pi's chapters and the extension's README;
- **the extension's own page** — its banner (1.27 MB), its demonstration video (5.13 MB)
  and its `SECURITY.md` are `WEB_ACCESS_DROPS`, and its `README.md` is the one file the
  rule's `keep` protects.

What that costs and buys, measured:

- pi's tree is 105.5 MB / 14,007 files, of which 51.5 MB goes — 33.94 MB of source maps,
  12.36 MB of a dependency's type declarations, 2.29 MB of screenshots, 2.12 MB of
  `README.md`/`CHANGELOG.md`, 0.42 MB of a dependency's manuals, 0.38 MB of test fixtures —
  leaving 53.9 MB to ship. The keep list removed 40.3 MB, and the 1.6 MB difference is
  `examples/` and the dependency documentation above;
- the extension's project tree is 33.4 MB / 7,773 files, of which 17.8 MB goes, leaving
  15.7 MB;
- the shipped `overlay.zip` is 4.7 MB smaller per ABI than the delete list's first version
  (arm64 75.08 → 70.38 MB, `x86_64` 74.59 → 69.88 MB) and 12.25 MB smaller than before any
  trim at all (82.63 MB); the release APKs measure 105.5 MB on arm64 and 105.0 MB on
  `x86_64`, and they store the archive uncompressed, so they move with it;
- what arrives is pi's 30 chapters and their `docs.json` index at
  `$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/docs/`, its `examples/`,
  `@earendil-works/pi-*`'s declarations and chapters, and the extension's `README.md` at
  `$PREFIX/lib/node_modules/pikit-extensions/node_modules/pi-web-access/README.md`.

The losses a *reader* would notice are the screenshots, and that cost is visible:
`docs/images` is 2.29 MB of the 2.79 MB pi's `docs/` weighs (1.44 MB of that a sponsor's
mascot), the reader is a terminal on a phone that draws none of them, and the two chapters
that embed one show a missing image. Carrying all four was the rejected alternative, at
2.3 MB of archive per ABI. The rest is nobody's reading material: a dependency's type
declarations and its manual, and pi's own `README.md` with its 569 KB `CHANGELOG.md` among
the npm pages — the first describes installing pi into a Termux that is not this app, and
the second is a release history for a version that cannot move under the user's feet.

One consequence of the rule lives in the cache, not the image. A vendored tree used to be
trimmed **in place** in `.runtime-build` — so a build host that had one held a tree with
another rule's deletions already applied, and a warm cache would have shipped an image
missing exactly what the current rule keeps, silently, because the version had not moved.
The trim now runs on the copy that ships, as pi's tree always did, and the marker's
`pristine` flag is what a cache written by the old code fails, so it re-vendors once.

Three things keep what ships from quietly going missing again, because nothing loads these
files and a device shows no error when they are absent:

- `verify-runtime-image.py` asserts three entry points by path — the manual's
  `docs/index.md` and its `docs.json` index, and the extension's `README.md` — and then
  compares the image against the vendoring cache: **every file the tree holds and the rule
  keeps must be in the image, by name** (7,618 in pi's tree at 0.86.1, 5,449 in the
  extension's). The expectation is computed from the tree and from `vendor_junk`, the same
  predicate the trim deletes by, so a release that adds, renames or retires anything moves
  no constant here and a file the image is missing is named. **A symlink counts on both
  sides, as itself or as the file it points at**: npm makes `node_modules/.bin/*` symlinks
  on Linux and shim files on Windows, and the archive carries either form, so a comparison
  that excluded symlinks reported six `.bin` entries as files the image has and the tree
  does not — on a cold CI cache only, because a Windows build host's cache has no symlink
  in that directory to disagree about. Documents alone were compared
  first, and a file dropped from `examples/` passed it — which is why the claim is about
  every file: the rule is a delete list, so "everything else arrived" is what it promises.
  The count that stood in for this at the start (a floor of 25 chapters, measured against
  30) was the wrong shape twice over — it went stale on any documentation edit and stayed
  green through the ones it should have caught;
- the rewrite check is an invariant rather than a count: no member may contain
  `pi/kit/mob`, the application id in a slash form nothing in the image uses, which is
  exactly what a rewrite that clobbered a `com/termux` URL would leave behind. That
  replaced a pinned count of those URLs (195, two of them in the `docs/termux.md` that now
  ships) which had to be re-measured whenever upstream edited a link — and the occurrence
  is still *reported*, just not asserted;
- `tools/test-vendor-trim.py` pins the rule itself on synthetic trees — what it does *not*
  name is still there (the `examples/` and dependency documentation above), each deletion,
  both per-tree exceptions, and the agreement between what the trim deletes and what the
  image verifier expects — and runs among `build-apks.py`'s checks.

One detail of the relocation pass lands well here: `docs/termux.md` documents
`/data/data/com.termux/files/home`, and the prefix rewrite that relocates every Termux
package also rewrites the chapter, so the paths a reader finds in it name the prefix the
app actually has. The two `github.com/termux/...` links in the same file are left alone.

## Every version in the image is pinned, pi's included

`BOOTSTRAP_TAG`, `PI_VERSION` and `WEB_ACCESS_VERSION` are constants in
`tools/build-runtime-image.py`, and all three are hashed into the image's revision, so two
builds of one commit are the same image — which is the whole point of a revision a device
compares against.

pi was once vendored as `@earendil-works/pi-coding-agent` with no version — npm's
"latest" — and `vendor_pi` reused `.runtime-build/cache/pi` whenever `node_modules` was
there, so "latest" meant the version vendored first. pi's RPC records, its tool list and its
model catalogue are all things this app parses, and all three move between releases.

`PI_VERSION` now pins the release, and a `VENDORED_MARKER` file in the cache records which
version the cached tree was vendored from, so bumping the constant re-vendors instead of
rebuilding an image around the tree that was already there — without the marker the pin
would have been decorative.

The vendored extension's cache needed the same fix. `vendor_web_access` reused
`node_modules` whenever it existed and re-checked only the named runtime dependencies, so
a `WEB_ACCESS_VERSION` bump would have rebuilt the image **around the tree the cache
vendored first** while `build-metadata.json` named the new version; `pikit-vendored.json`
records `@version` for it now, exactly as it does for pi.

`--pi-version` overrides the pin for one build, which is how a pi release candidate gets
tried without editing the tree. [MAINTAINING.md](../MAINTAINING.md) has the routine for
moving pi.

## What the image does not carry

Three `dpkg` packaging-developer scripts — `dpkg-buildapi`, `dpkg-buildtree` and
`dpkg-fsys-usrunmess` — want `perl`, which is not bundled. `apt`, `dpkg` and `pkg` install
and remove packages without it, and the official Termux bootstrap has the same omission,
so this is a limit that is accepted rather than fixed: bundling `perl` to make three
developer scripts run would pay for a language runtime on every device.

---
