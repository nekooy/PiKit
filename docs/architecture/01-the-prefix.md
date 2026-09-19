# 1. The prefix cannot move — so it is rewritten in place

*[Architecture](../ARCHITECTURE.md) §1.*

The obvious design for "an app with a built-in Termux environment" — ship the
official Termux bootstrap inside the APK and unpack it into the app's own data
directory — does not work. Termux packages are compiled against a fixed prefix,
`/data/data/com.termux/files/usr`. In the official `bootstrap-aarch64.zip`:

| What | Count |
| --- | --- |
| Files containing `/data/data/com.termux` | **615** of 3472 |
| ELF binaries with `DT_RUNPATH=/data/data/com.termux/files/usr/lib` | **338** |
| Scripts whose line-1 shebang names the absolute old interpreter | **114** |
| Other data files (dpkg `.list`, configs) | 163 |

The first process the app launches, `$PREFIX/bin/login`, is one of the 114 — a
shell script with an old-prefix shebang that also exports
`LD_PRELOAD=.../libtermux-exec-ld-preload.so`. Three independent mechanisms are
hardcoded, and none is editable at runtime:

1. **Dynamic linking.** Termux links with
   `-Wl,-rpath=$TERMUX__PREFIX__LIB_DIR -Wl,--enable-new-dtags`, so every binary
   carries an absolute `DT_RUNPATH`; Termux's own environment therefore sets `PATH`
   but deliberately *unsets* `LD_LIBRARY_PATH`, because on Android 7+ its binaries
   rely on `DT_RUNPATH`.
2. **Shebangs.** `termux-exec`'s `termuxPrefixPath()` only recognises `/bin`-shaped
   paths (`strstr(path, "/bin/")` must match at offset 0 or 4), so an absolute
   old-prefix shebang is passed through verbatim and the exec fails with `ENOENT`.
3. **Compile-time constants.** `TERMUX__PREFIX` is a `-D` in `termux-exec`'s and
   `termux-core`'s Makefiles. The environment variable is honoured at runtime, but
   it only reaches the `/bin` rewriting above, so it cannot repair shebangs,
   `DT_RUNPATH`, or the absolute paths compiled into binaries.

Termux's own documentation is unambiguous: *"You cannot move `$PREFIX` to another
location because all programs expect that `$PREFIX` will not be changed."*

**Decision.** Ship the official bootstrap under PiKit's own application id by
rewriting the package name throughout the image, rather than recompiling it or
adopting `com.termux`. `com.termux` and `pi.kit.mob` are both exactly **10
bytes**, so replacing the dot form everywhere is **length-preserving**: every
byte offset, ELF section offset, dynamic-section offset and internal string
length is unchanged. The files change meaning without changing shape. No
recompilation, no `patchelf`, no Docker, and it coexists with a real Termux
install. `tools/prefix_patch.py` enforces the
10-character rule and refuses to run otherwise, because a different length would
shift every offset and corrupt the binaries.

The application id is therefore not a free choice, and `gradle.properties` says
so next to the value. `TermuxEnv` additionally compares the prefix the image was
built for (`BuildConfig.TERMUX_PREFIX`) against the live `filesDir` and fails
loudly on a mismatch instead of producing a subtly broken environment.

## What the rewrite has to get right

| Problem | Why it happens | Handling |
| --- | --- | --- |
| `com/termux` must not be replaced | All 193 occurrences are `github.com/termux/...` URLs in comments and dpkg metadata. | Only the dot form is matched. The verifier asserts all 193 survive. |
| The build's own files | The relocator has to *name* the upstream id — it is what it rewrites away from — and the apt hook and `dpkg` wrapper explain that prefix. | Listed in `prefix_patch.NEVER_REWRITE` and skipped; the verifier imports that same set and runs the relocator's `--selfcheck`. Measured: without the exception the relocator's constant became this app's id, and no package from the Termux repository could be installed at all (§4). |
| Compressed files | Invisible to a byte scan; editing one destroys the stream. | Detected by magic bytes and skipped. Measured: zero compressed files in the bootstrap contain the prefix, so nothing is missed. |
| Symlink targets | A link target is metadata, not file content; no byte scan reaches it. | `readlink`, rewrite, recreate. At build time the same fix is applied to `SYMLINKS.txt` *before* zipping, so the installer creates correct links from the start. |
| `libexec/termux-am/am.apk` | Two occurrences hide inside a **deflated** `classes.dex`, invisible to a flat scan; a byte replace over the container corrupts the streams. | The nested archive is unpacked, members patched, and the archive rebuilt. Entry set is preserved (11 members). |
| dex checksum and signature | Editing bytes invalidates the header's Adler-32 and SHA-1, and ART may refuse to load it. | Both are recomputed. **Order matters**: the signature covers bytes from offset 32, the checksum covers bytes from offset 12 *including the signature*, so the signature is written first and the checksum last. The verifier re-derives both. |
| dpkg file hashes | dpkg records an md5 per installed file, so every patched file invalidates its record: `dpkg --verify` reports mass modification and patched conffiles look locally edited. | Regenerated from the patched bytes. Measured: 514 records refreshed; all 3286 recorded hashes then verify. Note `.md5sums` paths have **no leading slash** (`data/data/...`), unlike `Conffiles:` entries. |
| `update-alternatives` links | They are created by `postinst`, so they are not in `data.tar`. | **Does not apply.** Measured: zero of the 27 bundled packages use `update-alternatives`; only `nodejs` (`preinst`) and `npm` (`postinst`) have maintainer scripts, and both are pure `echo`. |
| `Conffiles:` hashes in `status` | dsh-mobile-apk hit this and had to wrap `dpkg`. | **Does not apply.** Measured: this bootstrap's `status` contains zero `Conffiles:` blocks. |
| Partial writes | Rewriting in place against a live environment can corrupt a file if interrupted. | Writes go to a temp file and are renamed into place. |
| Idempotency | A re-run must not accumulate damage. | `pi.kit.mob` does not contain `com.termux`, so a second pass is a no-op by construction. |

## Consequences

The rewrite is safer than the alternatives it replaces: because it edits the package
id *inside* compiled binaries, the compiled-in paths in `apt`, `dpkg` and `git` are
correct for the new prefix, so PiKit needs **none** of the wrapper scripts and
environment-variable overrides a length-changing relocation requires (dsh-mobile-apk,
at a 24-character id, needs five wrappers plus `APT_CONFIG` and `GIT_EXEC_PATH`).

## The one file the environment cannot start without

`lib/libtermux-exec-ld-preload.so` is preloaded into every process the environment
runs, and on Android an `LD_PRELOAD` pointing at an *empty* file is fatal rather
than ignored: the linker refuses the program (`file offset for the library … >= file
size: 0 >= 0`), so `sh`, `ls` and `node` all fail at once and the agent cannot start.
The file is written by `termux-exec`'s own `postinst` (`cp -a` from
`libtermux-exec-direct-ld-preload.so` on API 28+), so it arrives during the install's
second stage. Measured on the Medium_Phone AVD: one reinstall that replaced an
existing prefix came out with that file at 0 bytes while its source was intact, and no
repeat reproduced it. `BootstrapInstaller.verifyPreloadLibrary()` therefore verifies
it on every launch — two `stat`s — and re-runs the package's own
`termux-exec-ld-preload-lib setup` when it is missing or empty, with `LD_PRELOAD`
removed from that child because everything exec'd while it points at the broken file
dies in the linker. Measured: `: > lib/libtermux-exec-ld-preload.so` followed by a
launch produced `Repaired the Termux $LD_PRELOAD library; setup exited 0` and a
working terminal.

The one thing the rewrite cannot pre-empt is `pkg install`: anything installed
later arrives compiled for the upstream id. [Section 4](04-package-relocation.md)
makes that invisible to the user. The alternative the maintainers recommend —
recompiling the bootstrap with `TERMUX_APP__PACKAGE_NAME` changed — remains
available ([termux-app#3699](https://github.com/termux/termux-app/discussions/3699);
a follow-up request for a simpler story was
[closed as not planned](https://github.com/termux/termux-app/issues/3956)), but it
needs Docker plus the Android SDK, runs to hours per architecture, and has a known
open bug where `pkg install` is itself broken in renamed builds
(termux-generator#12, labelled `wontfix`).

## Consequently, `targetSdk = 28`

Android 10 removed the ability for apps in the `untrusted_app_29` SELinux domain —
`targetSdkVersion = 29` and up — to `exec()` files in their own data directory, so an
app that unpacks and runs its own binaries must target **28 or lower**: 28 is the
highest value that still runs them.

- [`private/untrusted_app_27.te`](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_27.te)
  covers `25 < targetSdkVersion <= 28` and carries
  `allow untrusted_app_27 app_data_file:file execute_no_trans;` under AOSP's own comment
  **"The ability to call exec() on files in the apps home directories for targetApi 26,
  27, and 28."**
- [`private/untrusted_app_29.te`](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_29.te)
  covers `targetSdkVersion = 29` and **does not have that rule.**

Which permission it is matters, because the answer is not "no `execute` at all":
[`private/untrusted_app_all.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te)
grants every untrusted app `app_data_file:file { r_file_perms execute }` — the right to
*transition* to another domain — and reserves `execute_no_trans` for `system_linker_exec`
(Chrome's Crashpad, b/112050209). A binary in the app's own data directory does not
change domain, so it needs `execute_no_trans` or nothing.

Other precedents are consistent once the mechanism is named: Termux and AlevAP target
28; warp-mobile at 36 moves the binaries into `jniLibs`, extracted to an APK-owned path
rather than the data directory; Google Play's Termux at 37 runs under a `proot` loader,
which never asks the kernel to execute a data-directory file.

Getting 28 wrong does not fail the build: it fails at `exec()` on the first launch,
after the ~200 MB unpack, so 28 is not an invitation to experiment.
`ExpiredTargetSdkVersion` therefore fires and stays fired — the build disables that
one check with a comment rather than silencing lint wholesale.

---
