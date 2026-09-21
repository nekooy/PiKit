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
under `bin/` and `libexec/` points at something that exists, and `bin/bash` and `bin/node`
are valid ELF for the target ABI with
`DT_RUNPATH=/data/data/pi.kit.mob/files/usr/lib`.

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
  (`pi-web-fetch-demo.mp4`, `banner.png`, the markdown) plus every dependency's test
  fixtures, `.yarn` plugin bundles and source maps. Measured: 24.9 MB of vendored tree
  becomes 14.6 MB, and the shipped archive 8.6 MB becomes 5.5 MB **per ABI**;
  `@mixmark-io/domino` alone is 3.4 MB of HTML5-parser conformance data inside a runtime
  dependency.
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
