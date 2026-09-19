# 3. What is baked in, and why `rg` and `fd` are not optional

*[Architecture](../ARCHITECTURE.md) §3.*

Pi's `grep` tool shells out to `rg` and its `find` tool shells out to `fd`. Pi
will normally download those itself, but it explicitly refuses on Android, on the
grounds that the upstream Linux builds are glibc-linked and will not run on
bionic — it tells the user to `pkg install ripgrep` instead. That refusal happens
at *tool call* time, not at startup, so a missing `rg` presents as a confusing
mid-conversation failure. Both are therefore unpacked into the image, along with
`pcre2` (which Termux's `ripgrep` build requires).

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
- **`npm ci --omit=optional`** takes pi's tree from ~396 MiB to ~101 MiB by
  dropping 26 `@esbuild/*` platform packages and the desktop clipboard binaries,
  none of which are loaded at runtime on Android. `--ignore-scripts` is safe
  because pi declares no install lifecycle scripts.

## The bundled extension, and why it is not `pi install`

The extension's own documentation installs it with `pi install npm:pi-web-access`,
which writes `npm:pi-web-access` into pi's *user* settings under `$HOME` and
installs the package there. PiKit vendors it into the image instead, for three
reasons that are all the same reason from different sides: the install needs npm
and a network at a moment the app may be offline; it lands in the user's own
directory, which is the one place this project treats as the user's; and the
version would then be whatever npm resolved that day, so two installs of one APK
could run different tools.

So the package is installed at build time under
`$PREFIX/lib/node_modules/pikit-extensions` and registered by **absolute path**
in pi's global `packages` array — pi's documented *local path* package source,
which loads it through the package's own `pi` manifest
(`"extensions": ["./index.ts"]`) and needs no npm at runtime. The path pi is
given is composed from `$PREFIX` in the app, not written into the image: an
absolute path in a build artifact would bake the application id in, and the image
is otherwise id-independent.

Four build details carry numbers:

- **`--legacy-peer-deps`**, because the extension declares the
  `@earendil-works/pi-*` packages as peers and npm would otherwise install a
  second full copy of pi: measured, 193 MB with peers against 30 MB without. pi is
  already at `$PREFIX/lib/node_modules`, which Node finds by walking up from the
  extension's own directory.
- **Trimming**, because the package ships its npm page inside itself:
  `pi-web-fetch-demo.mp4`, `banner.png` and the markdown, plus every dependency's
  test fixtures, `.yarn` plugin bundles and source maps. Measured: 24.9 MB of
  vendored tree becomes 14.6 MB, and the shipped archive 8.6 MB becomes 5.5 MB
  **per ABI**. `@mixmark-io/domino` alone is 3.4 MB of HTML5-parser conformance
  data inside a runtime dependency.
- **`--omit=optional` needs one exception, and it is named in
  `WEB_ACCESS_RUNTIME_DEPS`.** The flag is there for the same reason it is in the pi
  vendoring — platform-specific optional binaries that are never loaded — but
  `defuddle/node`, the entry the extension's `extract.ts` imports for every
  `fetch_content`, reaches `defuddle/dist/elements/math.full.js`, whose fourth line
  is an unconditional `require("mathml-to-latex")`, and `defuddle` declares that
  package as *optional*. So the vendored tree had none of it and **every fetch
  failed** with `Cannot find module 'mathml-to-latex'`, whatever the URL: the require
  runs when the module is loaded, not when a formula is met. `mathml-to-latex` (which
  brings `@xmldom/xmldom`) is therefore a direct dependency of the generated
  `package.json`, pinned exactly like everything else here. `temml`, the other
  optional dependency on that path, is genuinely optional — `math.full.js` reaches it
  inside a `try`/`catch` and falls back to plain text — and stays omitted.
  `verify_web_access` runs the real entry on a page with a formula in it at the end
  of every vendoring *and* on every cache reuse, so a tree that is missing something
  the extractor loads fails the build rather than every fetch on a phone.
- **A metadata file** at `share/pikit/web-access.json` names the package, its
  version and its path relative to `$PREFIX`. The settings page reads it to find
  the extension and to describe it, so a runtime image without the extension is a
  *missing file* the UI can report rather than a path the app assumes exists.

The extension's options are its own file, `$HOME/.pi/agent/web-search.json`, and
the app edits it directly rather than mirroring it into `SharedPreferences`: two
copies of one setting is two answers to "which provider is in use", and the extension
reads only its own file. PiKit sets one default there — `"workflow": "none"` — because
the extension's own default is `summary-review`, which opens its result curator in
a browser, and a phone has no browser to open it in. Everything else is left as
the extension's documentation describes it, and a file that does not parse is
reported rather than overwritten. (Section 9 has the rest of that story, including the
one thing that has to be *repaired* rather than preserved.)

There used to be two SearXNG fields on the page — the instance address and its extra
headers — with the extension's own validation repeated on this side, because a value
the far end silently drops is indistinguishable from a search that finds nothing. They
were removed with the rest of the per-provider fields for the reason section 9 gives:
a field PiKit draws is a field PiKit has to *write*, and writing a value it invented
would overwrite what the file already said. `searxngBaseUrl` and `searxngHeaders` are
documented in the file itself now, and the extension validates them — the app no longer
has an opinion, which is the honest state of affairs for a key it does not own.

## The extension's licence, and why it is named on the About page

`pi-web-access` is **MIT** (Nico Bailon, `github.com/nicobailon/pi-web-access`,
pinned by `^0.29.0` in the image builder), and so are the packages npm resolves
under it — `turndown`, `@mozilla/readability`, `defuddle`, `linkedom`, `unpdf`,
`undici`, `p-limit`, `typebox`. MIT is compatible with this app's GPLv3 as long as
the notice travels with the code, and it does: the APK contains each package's own
`LICENSE`, and **Settings → About PiKit → Credits** names the extension, its author
and the notable dependencies beside Termux, Node.js, `pi`, `ripgrep` and `fd`. That
is the one place a user can see what is inside the build without unpacking it, so a
dependency added to the image belongs in that list as much as it belongs in
`tools/build-runtime-image.py`.

The archives are stored uncompressed in the APK (`androidResources.noCompress`)
and streamed straight out of assets, so unpacking never holds the image in
memory. Extraction goes into a staging directory that is renamed into place only
after every archive is applied, so an interrupted install cannot leave a
half-populated `$PREFIX`. `$HOME` lives outside `$PREFIX`, so reinstalling the
runtime never discards the user's Pi sessions.

## Every version in the image is pinned, pi's included

`BOOTSTRAP_TAG`, `PI_VERSION` and `WEB_ACCESS_VERSION` are constants in
`tools/build-runtime-image.py`, and all three are hashed into the image's revision.
Two builds of one commit are therefore the same image, which is the whole point of a
revision a device compares against.

**pi used to be the exception, and it was the one that mattered most.** It was
vendored as `@earendil-works/pi-coding-agent` with no version — npm's "latest" — and
the cache made that worse rather than better: `vendor_pi` reused
`.runtime-build/cache/pi` whenever `node_modules` was there, so "latest" meant the
version that happened to be vendored first, on that machine, and a rebuild on another
machine could vendor a different one. Nothing in the diff would say so: pi's RPC
records, its tool list and its model catalogue are what this app parses, and all
three move between releases.

The fix is two things that only work together. `PI_VERSION` pins the release, and a
`VENDORED_MARKER` file in the cache records which version the cached tree was
vendored from, so bumping the constant re-vendors instead of rebuilding an image
around the tree that was already there. Without the marker the pin would have been
decorative: the revision would move, the image would be rebuilt, and the same pi
would go back into it.

`--pi-version` overrides the pin for one build, which is how a pi release candidate
gets tried without editing the tree. [MAINTAINING.md](../MAINTAINING.md) has the
routine for moving pi.

---
