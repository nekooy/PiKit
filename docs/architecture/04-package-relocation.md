# 4. `pkg install` without the user noticing

*[Architecture](../ARCHITECTURE.md) §4.*

A package installed from the Termux repository is compiled for
`/data/data/com.termux/files/usr`. Its `DT_RUNPATH` points at a library directory
this app cannot read, so it dies at exec time and looks broken. Section 1 fixes
the *bundled* image at build time; this section is the packages that arrive
afterwards. Repairing the tree after the install finishes is wrong in three ways
that only show up on a real device:

1. **There is a window in which the package is broken.** Between `dpkg` unpacking
   a binary and the repair pass reaching it, running that binary fails. On a
   phone the window is however long the user takes to leave the terminal tab —
   which may be never.
2. **It is quadratic.** The repair walks all ~19 000 files of the prefix to fix
   the handful that changed.
3. **It is not where the problem is.** The damage is done by `dpkg` unpacking an
   archive that names the wrong prefix. Repairing afterwards is treating the
   symptom.

## The rewrite happens before `dpkg` runs

Two things do this, and both are needed: each covers a case the other was measured
missing.

**1. apt's `DPkg::Pre-Install-Pkgs` hook.** `etc/apt/apt.conf.d/99pikit-relocate`
binds a Node script to it:

```
DPkg::Pre-Install-Pkgs { ".../bin/pikit-relocate --apt-list"; };
```

apt runs that hook with the list of archives it is about to hand to `dpkg` on the
hook's stdin — the same list `dpkg` receives on its own command line — so
`--apt-list` rewrites exactly the files that are about to be unpacked. It covers a
single package and a batch of ninety-six alike, and for a batch those are the
copies apt stages under `$PREFIX/tmp/apt-dpkg-install-*`, which no scan of an
archive cache can see. The mode is silent on success, since its stdout lands in the
user's `apt-get install` output.

**2. The `dpkg` wrapper.** `bin/dpkg` is a shell script; the real binary was
renamed to `bin/dpkg.real` at image build time. The wrapper relocates the exact
archives in its own arguments, then execs the real dpkg. It is not redundant: it
covers every installation apt did not mediate — `dpkg -i foo.deb`, an archive
fetched by hand, a `dpkg` call from a script — and it cannot be routed around by
anything that goes through `dpkg`. Measured on a device, with the hook installed
and firing:

```
Get:1 file:/…/hookrepo ./ pikit-hooktest 1.0.0 [982 B]
[pikit-relocate] nothing to relocate in /…/usr/var/cache/apt/archives
# then, on disk:  #!/data/data/com.termux/files/usr/bin/sh
```

**apt does not copy an archive fetched over `file://` into its cache.** The hook
looked where apt puts downloads, found nothing, and reported success. With the
wrapper, the same install reports:

```
[pikit-dpkg] argv: --status-fd 14 --no-triggers --unpack --auto-deconfigure /…/pikit-hooktest_1.0.0_x86_64.deb
[pikit-relocate] relocated 1 archive(s), 3 occurrence(s)
# then, on disk:  #!/data/data/pi.kit.mob/files/usr/bin/sh
```

`bin/dpkg.real` keeps the mode the bootstrap gave it, and the wrapper is mode
0755. The renamed binary is rewritten like every other member — it carries the
upstream prefix in seven places, so a rename that skipped the rewrite would ship
a `dpkg` whose `DT_RUNPATH` points at a directory the app cannot read.
`dpkg-deb` is deliberately *not* wrapped: it builds and inspects archives rather
than installing them.

Both layers are idempotent, so an archive one of them already rewrote contains no
occurrence of the old prefix and the other's pass is a no-op.

### The apt hook this replaced, and why it was wrong twice

The first version was `DPkg::Pre-Invoke`, running `pikit-relocate --debs` over apt's
archive cache. It failed twice: the real cache is the `/data/data/<app-id>/cache/apt`
compiled into `lib/libapt-pkg.so` — what `apt-config dump` prints for `Dir::Cache` — so
it scanned an empty directory and exited 0 on every run; and rewriting a cached archive
changes its size, which apt compares against the index, so the next `apt-get install`
fetched the original over the repaired copy. Padding the archive back to its original
length was rejected — the compressed size is not a value the compressor can be asked
for. `aptArchiveDirs()` now asks `apt-config` first and falls back to the compiled-in
path, and `--debs` survives for a manual repair. Acting on the archives `dpkg` is about
to consume removes the problem instead of managing it.

### The one file the rewrite must not touch

`libexec/pikit/relocate.js` is the relocator, and it is the only file in the image
that has to *name* the upstream id. The build's prefix rewrite (§1) therefore skips
it, together with the rest of the files the build writes itself; the list is
`prefix_patch.NEVER_REWRITE`, and `verify-runtime-image.py` imports that same set
so the two cannot drift apart.

The exclusion was missing, and the rewrite reached `const OLD_ID = 'com.termux'` and
turned it into `'pi.kit.mob'`, so `OLD_ID === NEW_ID`: every archive was compared
against its own replacement and the hook reported success on every run. On a device,
**no package from the Termux repository could be installed at all** — 96 of 96 failed
to unpack with

```
dpkg: error processing archive …/00-brotli_1.2.0_aarch64.deb (--unpack):
 unable to stat './data/data/com.termux' (which was about to be installed): Permission denied
```

Three things now stop it recurring:

- `assertConfigured()` refuses to run at all when the two ids are equal or differ in
  length. A silent no-op that reports success is the worst failure mode there is.
- `--selfcheck` exercises `rewrite()` on a synthetic path, and both
  `build-runtime-image.py` (against the patched overlay) and
  `verify-runtime-image.py` (against `overlay.zip`) call it. A build that rewrites
  its own relocator now fails at the build.
- `test-relocate.py` runs the relocator **extracted from `overlay.zip`**, not the
  copy in `tools/`, and relocates a real package with it. Only the packed copy can
  be wrong in this way, so only the packed copy proves anything.

## Why the rewrite happens on the tar stream

`tools/pikit-relocate.js` decompresses every tar member of a `.deb` — `data.tar.*`
*and* `control.tar.*` — rewrites the **uncompressed tar**, then recompresses it.
Rewriting only `data.tar` was measured failing: `control.tar` holds `conffiles` and
the maintainer scripts, which `dpkg` copies into `var/lib/dpkg/info`, and a `postinst`
whose shebang still names the old interpreter cannot be executed — the kernel
resolves the shebang, and that directory is not readable by this app — so the package
unpacks and can never be configured:

```
dpkg (subprocess): unable to execute installed python package post-installation script
  (/data/data/pi.kit.mob/files/usr/var/lib/dpkg/info/python.postinst): Permission denied
```

Working on the stream rather than on an extracted tree is the rest of the design,
and getting it wrong is silent:

`tar -x` recreates directories from the names in the archive, so extracting a package
that names `data/data/com.termux/files/usr` produces a directory *called* `com.termux`,
and `tar -c` writes that name straight back: the bytes inside the files would be patched
and the paths would not, so `dpkg` would record files under a prefix that does not exist.
Working on the stream rewrites both in one pass: every occurrence in a tar is either a
header's name field (NUL-padded, so a plain byte replace is safe) or file content. A tar
header checksums its own bytes, so a rewrite leaves a stale checksum; `fixTarChecksums`
recomputes the 8-byte field for every header it invalidated — which does not violate the
length-preserving rule, because the field is 8 bytes before and after. `xz`, `zstd`, `bzip2`
and `gzip` all ship inside the bundled image (`bin/xz`, `bin/zstd`, …), so the script shells
out to them rather than carrying decompressors written in JavaScript.

## The fallbacks, and why they are not the primary path

- **`pikit-relocate --prefix`** rewrites an installed tree. Used from Settings →
  Maintenance & repair, and it also refreshes `var/lib/dpkg/info/*.md5sums` so
  `dpkg --verify` stays quiet. This is what the Kotlin `PrefixPatcher` does almost
  identically; the two exist because the shell one can run without the app process
  and the Kotlin one can run without the terminal.
- **`PrefixPatcher`** in the app. It is now reached only from that one button. It
  used to run on leaving the terminal tab, ran whether or not anything had been
  installed, and walked ~19 000 files to repair what the hooks above prevent.

  It applies the **same exclusion list as the build**, read from
  `$PREFIX/share/pikit/never-rewrite.txt`, which `build-runtime-image.py` writes from
  `prefix_patch.NEVER_REWRITE` (`verify-runtime-image.py` asserts the two agree). This
  class did not have it: pressing the button rewrote `bin/dpkg`, the apt hook and
  `libexec/pikit/relocate.js`, whose `OLD_ID` became `NEW_ID`, after which the
  relocator refused every run (`relocator misconfigured: OLD_ID === NEW_ID`, exit 4)
  and every package installed afterwards went unrelocated. Measured on the device with
  a before/after sha256 catalogue of all 19 325 files: exactly three files changed, and
  nothing else — and the same press took the storage self-test from `10 passed, 0 failed`
  to `8 passed, 2 failed`, both failures its relocator-boundary checks, which key on the
  refusal message the misconfigured script never reaches. The list is read per pass rather than held from construction, because
  the runtime is re-extracted into the prefix after the session object exists, and it is
  the *union* of the file and a compiled-in fallback, so a stale or partial file cannot
  drop protection.

  The pass also reports progress — it is 22 000 files now that the bundled extension is
  in the image — because a button that only greys out for several seconds is
  indistinguishable from one that did nothing. And it refuses to run at all when the
  relocator in the runtime no longer names the upstream id, because a repair cannot fix
  that damaged state; the page says to reinstall instead.

## What is verified, and how

`tools/test-relocate.py` runs the relocator over real `.deb` files from the build
cache and asserts, with nothing but the Python standard library:

- no `com.termux` survives anywhere in the rewritten container, **including inside
  the `control.tar` member**, which a raw byte scan of a compressed container
  cannot see;
- the archive is still a valid `ar` file whose `data.tar.*` unpacks to the same
  entry set, in the same order, with every path relocated and every entry keeping
  its kind;
- the rewritten `DT_RUNPATH` of each ELF names the new prefix — the property that
  decides whether an installed binary runs at all;
- no entry changed size, which is what keeps dpkg's own `md5sums` records valid
  for the files themselves (the *archive* changes size; see above);
- the relocator extracted from `overlay.zip` passes `--selfcheck` and relocates a
  real package, which is the only check that fails when the build rewrites the
  relocator.

A Windows build host has no `xz`, so the test points the relocator at
`tools/pikit-compress.py` through `PIKIT_RELOCATE_HELPER`; the app never sets that
variable, and on a device the image's own tools are used.

The end-to-end path is verified on a device as well, because the two layers above
are exactly the part a unit test cannot reach.
`tools/build-hooktest-package.py` builds a synthetic package carrying the upstream
prefix in a shebang, an ELF-ish blob and a symlink target — three cases the relocator
has to handle differently. Installing it from a local apt repository exercises `apt` →
hook → `dpkg` wrapper → `dpkg`, and the check afterwards is the installed script's own
first line: an unrelocated shebang cannot exec, so a run that produces output is a run
that worked.

Set `PIKIT_DEBUG=1` in the environment to have the relocator report the sizes and
occurrence counts it sees, and `PIKIT_DPKG_TRACE=1` for the wrapper to print its
argv and the archive paths it was given. Both exist for diagnosis on a device and
are off unless something sets them.

---
