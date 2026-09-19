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
shell script beginning `#!/data/data/com.termux/files/usr/bin/sh` that also
exports `LD_PRELOAD=.../libtermux-exec-ld-preload.so`. Three independent
mechanisms are hardcoded, and none is editable at runtime:

1. **Dynamic linking.** Termux links with
   `-Wl,-rpath=$TERMUX__PREFIX__LIB_DIR -Wl,--enable-new-dtags`, so every binary
   carries an absolute `DT_RUNPATH`. This is also why Termux's own environment
   sets `PATH` but deliberately *unsets* `LD_LIBRARY_PATH` — upstream's comment
   in `termux-shared`'s shell environment setup reads: *"Termux binaries on
   Android 7+ rely on DT_RUNPATH, so LD_LIBRARY_PATH should be unset by
   default"*. (The file is not vendored here; the quote is from upstream.)
2. **Shebangs.** `termux-exec` rewrites interpreter paths, but its
   `termuxPrefixPath()` only recognises `/bin`-shaped paths — `strstr(path,
   "/bin/")` must match at offset 0 or 4. An absolute old-prefix shebang matches
   at neither, so it is passed through verbatim and the exec fails with `ENOENT`.
3. **Compile-time constants.** `termux-exec` and `termux-core` define
   `TERMUX__PREFIX` via `-D` in their Makefiles. A `TERMUX__PREFIX` environment
   variable is honoured at runtime, but it only reaches the `/bin` rewriting
   logic above, so it cannot repair shebangs, `DT_RUNPATH`, or the absolute paths
   compiled into binaries.

Termux's own documentation is unambiguous: *"You cannot move `$PREFIX` to another
location because all programs expect that `$PREFIX` will not be changed."* The
maintainers' answer to "can I embed Termux?" is that you must fork and
**recompile the bootstrap** for your package name
([termux-app#3699](https://github.com/termux/termux-app/discussions/3699)); the
follow-up request for a simpler story was
[closed as not planned](https://github.com/termux/termux-app/issues/3956).

**Decision.** Ship the official bootstrap under PiKit's own application id by
rewriting the package name throughout the image, rather than recompiling it or
adopting `com.termux`. `com.termux` and `pi.kit.mob` are both exactly **10
bytes**, so replacing the dot form everywhere is **length-preserving**: every
byte offset, ELF section offset, dynamic-section offset and internal string
length is unchanged. The files change meaning without changing shape. No
recompilation, no `patchelf`, no Docker, and the result runs under an id that can
coexist with a real Termux install. `tools/prefix_patch.py` enforces the
10-character rule and refuses to run otherwise, because a different length would
shift every offset and corrupt the binaries.

The application id is therefore not a free choice, and `gradle.properties` says
so next to the value. `TermuxEnv` additionally compares the prefix the image was
built for (`BuildConfig.TERMUX_PREFIX`) against the live `filesDir` and fails
loudly on a mismatch instead of producing a subtly broken environment.

## What the rewrite has to get right

Every item below was found by measurement on the real bootstrap, not by
reasoning — most of them by `tools/verify-runtime-image.py`, which is why that
tool exists.

| Problem | Why it happens | Handling |
| --- | --- | --- |
| `com/termux` must not be replaced | All 193 occurrences are `github.com/termux/...` URLs in comments and dpkg metadata. Rewriting them corrupts documentation and package descriptions. | Only the dot form is matched. The verifier asserts all 193 survive. |
| The build's own files | `libexec/pikit/relocate.js` has to *name* the upstream id — it is what it rewrites away from — and the apt hook, the `dpkg` wrapper and the relocator's own comments explain that prefix. | Listed in `prefix_patch.NEVER_REWRITE` and skipped. The verifier imports that same set, allows exactly those members to name the old app, asserts they are all present, and runs the relocator's `--selfcheck`. Measured: without the exception the relocator's constant became this app's id, and no package from the Termux repository could be installed at all (§4). |
| Compressed files | Contents are invisible to a byte scan, and editing them destroys the stream. | Detected by magic bytes and skipped. Measured: zero compressed files in the bootstrap contain the prefix, so nothing is missed. |
| Symlink targets | A link target is filesystem metadata, not file content — no byte scan over regular files can reach it. | `readlink`, rewrite, recreate. At build time the same fix is applied to `SYMLINKS.txt` *before* zipping, so the installer creates correct links from the start. |
| `libexec/termux-am/am.apk` | It hides two occurrences inside a **deflated** `classes.dex`, invisible to a flat scan; a byte replace over the container would corrupt the deflate streams. | The nested archive is unpacked, members patched, and the archive rebuilt. Entry set is preserved (11 members). |
| dex checksum and signature | Editing bytes invalidates the dex header's Adler-32 and SHA-1, and ART may refuse to load it. | Both are recomputed. **Order matters**: the signature covers bytes from offset 32, the checksum covers bytes from offset 12 *including the signature*, so the signature is written first and the checksum last. The verifier re-derives both. |
| dpkg file hashes | dpkg records an md5 per installed file, and every patched file invalidates its record. `dpkg --verify` would report mass modification and dpkg would treat patched conffiles as locally edited. | Regenerated from the patched bytes. Measured: 514 records refreshed; all 3286 recorded hashes then verify. Note `.md5sums` paths have **no leading slash** (`data/data/...`), unlike `Conffiles:` entries. |
| `update-alternatives` links | They are created by `postinst`, so they are not in `data.tar`. | **Does not apply.** Measured: zero of the 27 bundled packages use `update-alternatives`; only `nodejs` (`preinst`) and `npm` (`postinst`) have maintainer scripts, and both are pure `echo`. |
| `Conffiles:` hashes in `status` | dsh-mobile-apk hit this and had to wrap `dpkg`. | **Does not apply.** Measured: this bootstrap's `status` contains zero `Conffiles:` blocks. |
| Partial writes | Rewriting in place against a live environment can corrupt a file if interrupted. | Writes go to a temp file and are renamed into place. |
| Idempotency | A re-run must not accumulate damage. | `pi.kit.mob` does not contain `com.termux`, so a second pass is a no-op by construction. |

## Consequences

The rewrite is safer than the alternatives it replaces. Because it can edit the
package id *inside* compiled binaries, the compiled-in paths in `apt`, `dpkg` and
`git` become correct for the new prefix — so PiKit needs **none** of the wrapper
scripts and environment-variable overrides that a length-changing relocation
requires (dsh-mobile-apk, which uses a 24-character id, needs five wrappers plus
`APT_CONFIG` and `GIT_EXEC_PATH`).

## The one file the environment cannot start without

`lib/libtermux-exec-ld-preload.so` is preloaded into every process the environment
runs, and on Android an `LD_PRELOAD` pointing at an *empty* file is fatal rather
than ignored: the linker refuses the program (`file offset for the library … >= file
size: 0 >= 0`), so `sh`, `ls` and `node` all fail at once — the terminal shows that
line and the agent cannot start either. The file is written by `termux-exec`'s own
`postinst` (`cp -a` from `libtermux-exec-direct-ld-preload.so` on API 28+), which
means it is written *during* the bootstrap's second stage: the one step of the
install that thirty maintainer scripts share.

On the Medium_Phone AVD one install that replaced an existing prefix — the
arm64-to-x64 variant switch, the same shape as any runtime update — came out with
that file at 0 bytes while its source was intact, the second stage reported success
and every other file in the prefix was correct. Its mtime was identical to the
nanosecond to the file the `postinst` copies from, so it is that `cp`'s own output
and not a leftover; a fresh install and a repeat of the same reinstall both came out
correct, so it is not deterministic. `BootstrapInstaller.verifyPreloadLibrary()`
therefore verifies the file on every launch — two `stat`s on the usual path — and
re-runs the package's own `termux-exec-ld-preload-lib setup` when it is missing or
empty, with `LD_PRELOAD` removed from that child's environment because everything
exec'd while it points at the broken file dies in the linker. Measured on that
device: `: > lib/libtermux-exec-ld-preload.so` followed by a launch produced
`Repaired the Termux $LD_PRELOAD library; setup exited 0` and a working terminal.

The one thing the rewrite cannot pre-empt is `pkg install`: anything installed
later arrives compiled for the upstream id. [Section 4](04-package-relocation.md)
makes that invisible to the user. The alternative — recompiling the bootstrap
with `TERMUX_APP__PACKAGE_NAME` changed — remains available and is what the
maintainers recommend, but it needs Docker plus the Android SDK, runs to hours
per architecture, and has a known open bug where `pkg install` is itself broken
in renamed builds (termux-generator#12, labelled `wontfix`).

## Consequently, `targetSdk = 28`

Android 10 removed the ability for apps in the `untrusted_app_29` SELinux domain —
`targetSdkVersion = 29` and up — to `exec()` files in their own data directory. An app
that unpacks and runs its own binaries must therefore target **28 or lower**, and 28 is
the highest value that still runs them.

That boundary is not a guess and not a matter of counting precedents: the policy source
says so, in the two domains either side of it.

- [`private/untrusted_app_27.te`](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_27.te)
  covers `25 < targetSdkVersion <= 28` and carries
  `allow untrusted_app_27 app_data_file:file execute_no_trans;` under AOSP's own comment
  **"The ability to call exec() on files in the apps home directories for targetApi 26,
  27, and 28."**
- [`private/untrusted_app_29.te`](https://android.googlesource.com/platform/system/sepolicy/+/main/private/untrusted_app_29.te)
  covers `targetSdkVersion = 29` and **does not have that rule.**

Which permission it is matters, because the answer is not "no `execute` at all":
[`private/untrusted_app_all.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te)
grants every untrusted app `app_data_file:file { r_file_perms execute }` — the permission
to *transition* to another domain — and reserves `execute_no_trans` for
`system_linker_exec` (Chrome's Crashpad loading a native executable out of an APK,
b/112050209). Running a binary that sits in the app's own data directory is an execution
that does *not* change domain, so it is `execute_no_trans` or nothing. The
`untrusted_app_27.te` file says the same thing from the other side, on its `execmod`
line: text relocations are "now disallowed for targetSdkVersion>=Q", which is the domain
whose upper bound is 28.

This note used to say the question "has *not* been able to settle", and listed a project
claiming `34 still allows native exec`. That claim contradicts the current policy — no
domain above 28 is granted `execute_no_trans` on `app_data_file` — and the sentence is
kept here only as the refuted reading. The other precedents are consistent rather than
contradictory, once the mechanism is named: Termux and AlevAP target 28; warp-mobile at
36 moves the binaries into `jniLibs`, which are extracted to an APK-owned path rather
than the data directory; and Google Play's Termux at 37 runs under a `proot` loader,
which never asks the kernel to execute a data-directory file in the first place.

What has still not been measured **on this project's own device** is the consequence of
getting it wrong, which is why 28 is not an invitation to experiment: a wrong value here
does not fail the build, it fails at `exec()` on the first launch, after the ~200 MB
unpack. `ExpiredTargetSdkVersion` therefore fires and stays fired — the build disables
that one check with a comment rather than silencing lint wholesale.

---
