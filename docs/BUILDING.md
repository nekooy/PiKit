# Building PiKit

Everything needed to build the APKs and get one onto a device. The README keeps
the three commands you need most of the time; this is the whole story. Why the
runtime image has to exist at all is in
[ARCHITECTURE §1](architecture/01-the-prefix.md).

## Prerequisites

- **JDK 17 to 26** (built and tested with JDK 21). The wrapper is Gradle 9.8.1, and a
  Gradle release cannot run on a JVM newer than the one it knows about — a too-new
  Java fails before the build script is even read, which is why the error arrives as
  an IDE dialog rather than as a task failure. Android Studio keeps its own choice
  per project: set **Settings → Build, Execution, Deployment → Build Tools → Gradle →
  Gradle JDK** to 17 or 21 (`gradleJvm` in `.idea/gradle.xml`, with the resolved path
  in `.gradle/config.properties` — both are untracked, because both are per-machine).
  Nothing in this project asks for a JVM newer than 17;
  `sourceCompatibility`/`targetCompatibility`/`jvmTarget` are all 17.
- **Android SDK** with `compileSdk 37` and its build-tools, plus **NDK r29** — the
  vendored terminal emulator builds a small PTY shim through `ndk-build`. The 37 is
  an AAR floor rather than a preference: every AndroidX artifact this build resolves
  (Compose 1.12.1, material3 1.5.0-beta01) declares `minCompileSdk=37` in its own
  metadata, the same mechanism that made 36 the floor before it. CI installs exactly
  `platforms;android-37.0` + `build-tools;37.0.0`. `targetSdk` is
  a separate property and stays 28 — AGP 9 otherwise defaults a module's `targetSdk`
  to its `compileSdk`, which is why `gradle.properties` pins that default off and why
  every variant's merged manifest is worth re-checking after a build-system change.
- **Python 3.10+** with the `zstandard` module (`python -m pip install zstandard`).
  Only the runtime image builder needs it.
- **Node.js and npm**, only for vendoring the pi agent into that image.

`build-apks.py` names the ones it can see for itself before it starts: the JDK, the
SDK, the NDK (by the version `terminal-emulator/build.gradle.kts` pins, resolved
through `local.properties` or `ANDROID_HOME`), Node and the Gradle wrapper. `npm` and
`zstandard` are reported by name too, but only when an image actually has to be
built — a machine that already has the images can rebuild the APKs without them.

**Editing the in-app manual is a build step, not a text edit.** One checker holds
`locales/ManualText*.kt`'s raw-string bodies to being a fixed point of
`tools/reflow-manual.py`, so any edit there has to be followed by that script or
`build-apks.py` fails on "the manual's reflow". Write the manual *unwrapped* and let the
tool wrap it: a hand-wrapped line longer than the phone re-wraps into a two-line mess,
which is the defect the pass exists for. Only CJK paragraphs reflow cleanly — an
English paragraph containing an em dash (`—`) is **not** idempotent and alternates
between two wrappings on every run — so write manual English without em dashes.
`python tools/reflow-manual.py --check` reports the offending lines without rewriting
anything.

## One command, or the three

```bash
python tools/build-apks.py
```

That is the whole build. It checks the prerequisites first (and names what is
missing rather than failing inside Gradle), assembles the runtime images whose APK
assets do not have them *or whose sources have changed since they were assembled*
(below), runs the unit suites and the tools that check what Gradle cannot see — the
images, the relocator against real `.deb` files, the manual's reflow, the terminal
banners, the agent's delete guard, the README icon — and then builds
**`arm64`/`x64` × `debug`/`release`** in **one Gradle invocation**, so the four
variant pipelines share one configuration phase and run in parallel. A failure
still names the variant it happened to (`verifyApkPackaging<Flavour><BuildType>`).
The unit suites and the checker tools run alongside each other rather than one
after another. Every step streams its output, and the summary at the end is the
list of files to install. The last thing it does is read the release APKs
it has just built: `tools/check-release-math.py` fails the build when R8 has removed
the formula renderer's reflective command table — a failure that no JVM test and no
debug device can see (ARCHITECTURE §12.2).

| Flag | For |
| --- | --- |
| `--skip-tests` | build without running any check |
| `--debug-only`, `--release-only` | two APKs instead of four |
| `--refresh-images` | rebuild the runtime image even when it is already there |
| `--clean` | delete the built APKs first |

`gradle.properties` turns the **configuration cache** on: the second and later
builds of the same task set skip Gradle's configuration phase. A build script edit
(or a changed input the script reads) invalidates it on purpose; force a clean
recalculation with `--no-configuration-cache` when a task graph looks stale. The
custom verification tasks (`verifyApkPackaging*`, `verifyRuntimeImage*`,
`verifyInjectedAbi*`) capture only plain values and directories in their actions
for the same reason — closing over `Project` or a variant object is what the cache
refuses to serialize.

It also repairs the one failure the packaging guard is known to catch: if a
variant fails with a stale APK (see *The size of an APK is checked*), it deletes
that variant's APK and retries it once with `--no-build-cache` — only the variants
the guard named, so a four-variant build does not redo the three that were fine.

The same steps by hand, when one of them needs its own flags or its own output:

```bash
# 1. Assemble the offline runtime images (~105 MB of assets per ABI). This
#    downloads the Termux bootstrap, its .deb dependencies and the npm packages
#    once, then relocates the package id throughout.
python tools/build-runtime-image.py --all

# 2. Check the images are self-consistent before spending time on Gradle.
python tools/verify-runtime-image.py

# 3. Build the APKs. One per ABI, so each stays as small as possible.
./gradlew :app:assembleArm64Debug :app:assembleX64Debug
```

Install the one matching your device's CPU:

```bash
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

## One ABI per APK: pick the variant that matches the device

Each APK carries exactly one ABI's runtime image, and the app resolves the ABI from
`Build.SUPPORTED_ABIS` — so the variant and the device have to agree. Android
Studio's default variant is `arm64Debug` (arm64 is declared first), and its *Run*
also passes `android.injected.build.abi`, the ABI of the device it is deploying to,
which overrides every module's `abiFilters`. Selecting the wrong variant therefore
produces a genuine mixture — the `arm64` flavor's `assets/runtime/arm64-v8a/` with
the emulator's `lib/x86_64/libtermux.so` — that installs, starts, and then reports
that its runtime image is missing.

Set it once per device, in **Build → Select Build Variant**:

| Device | Variant |
| --- | --- |
| x86_64 emulator (the usual one) | `x64Debug` |
| arm64 phone | `arm64Debug` |

`app/build.gradle.kts` refuses the mismatch now: assembling a variant whose ABI is
not in the injected list fails with the variant to pick instead, and a plain
`./gradlew :app:assembleX64Debug` is unaffected.

**An x86_64 emulator cannot run the arm64 image even though it lists
`arm64-v8a`.** The Play-Store x86_64 images translate ARM through
`libndk_translation.so`, and Google's translation layer cannot preload the arm64
`libtermux-exec-ld-preload.so`: measured on the Medium_Phone AVD, every exec in
the runtime dies with `CANNOT LINK EXECUTABLE … is for EM_AARCH64 (183) instead of
EM_X86_64 (62)`. The app does not silently fall back to the bundled ABI for that
reason — a build/device mismatch is reported, not papered over.

## Why the images are a separate step

They are not committed. Each is around 285 MB of binaries assembled from upstream
sources — measured: a 90 MB bootstrap plus the 196 MB of overlay files that are not
already in it — and they are reproducible from `tools/build-runtime-image.py` plus a
network connection, so committing them would only ever add a merge conflict. The
app cannot be built without them: `BootstrapInstaller.plan()` fails with an
explicit message if `assets/runtime/<abi>/` is empty.

### An image is rebuilt when a file it is built from changes

Nothing else in the build knows that relationship: Gradle sees the archives as assets,
so it repackages when they change and cannot tell that they are out of date, and
`verify-runtime-image.py` only checks that an image is *self-consistent*, which a stale
one is. `build-apks.py` therefore compares each image's timestamp (the oldest of
`bootstrap.zip`, `overlay.zip`, `content.zip`, `revision.txt`, `content-revision.txt`)
against `IMAGE_INPUTS` — the builder,
the prefix rewriter, the pinned product versions, the relocator, the `dpkg` wrapper,
the delete guard and the storage self-test — and rebuilds a flavour whose image
predates one of them, naming the file that made it stale.

The image carries one revision stamp per layer — `revision.txt` for the seed
(`bootstrap.zip` + `overlay.zip`) and `content-revision.txt` for the content layer
(`content.zip`) — and `BootstrapInstaller.ensureInstalled` compares them rather than
contents, so an image built from a previous guard both ships the previous guard *and*
leaves a device that already installed the earlier APK running its old unpacked
runtime after the new APK is installed. Which stamp moves decides what a device does:
the content one point-replaces pi, the extension and the guard in place; the seed's
re-extracts the environment, and it is kept rare on purpose. It covers the
environment's *shape* — bootstrap tag, package names, the sources that assemble the
seed — and not the packages' versions, because a cold build resolves the current apt
index and version drift is what `pkg upgrade` is for. The content stamp hashes the
archive's bytes (that layer carries no apt packages, so the bytes are stable), and the
archives are written reproducibly — sorted entries, fixed member dates — so a rebuild
that changed nothing moves no stamp (ARCHITECTURE §3).

The list is checked rather than trusted — `image_input_manifest_errors()` reads the
builder's own source and fails the build over a repository file it copies that the
list does not name — and `--refresh-images` rebuilds the images regardless.

**A cache file has to name what it cached.** `.runtime-build/cache` holds downloads so
a rebuild does not re-fetch 200 MB, and each one is named after its URL rather than its
architecture: `bootstrap-<arch>-<url hash>.zip`, `pikit-vendored.json` for the pi tree,
the version in each `.deb`'s name. An architecture-named cache can build an image around the
previous `BOOTSTRAP_TAG`'s archive and stamp the new tag's revision on it, silently, because
the revision is hashed from the tag rather than the bytes. The apt
*index* is the one deliberate exception: its URL is the repository's moving `stable`
suite, it has no version to name it by, and keeping the first one fetched is what
makes two builds of one commit agree — deleting `Packages-<arch>.bz2` is how a new
package version is picked up.

The builder's work, in order:

1. Fetch the Termux bootstrap for the ABI and unpack it into a staging prefix.
2. Fetch and install the `.deb` dependencies Node needs, and the npm packages for
   the pi CLI, into the same prefix.
3. Run `tools/prefix_patch.py` over the result, rewriting
   `/data/data/com.termux/files/usr` to the app's own prefix in every file that
   names it.
4. Fix up the things the rewrite cannot reach — the `bin/dpkg` wrapper, the apt
   hooks, the executable-bit list, and `tools/pi-safety-guard.ts` staged where the
   installer can find it.
5. Split the content layer out of the overlay and emit three zip archives —
   `bootstrap.zip` and `overlay.zip` (the seed: the dpkg-managed environment) and
   `content.zip` (pi, the extension, the guard, the relocator) — with one revision
   stamp per layer (`revision.txt`, `content-revision.txt`) and `build-metadata.json`
   (what the image was built from). Each archive carries its own `SYMLINKS.txt`,
   because the zip format cannot represent symlinks, and `content.zip` carries
   `CONTENT-PATHS.txt`, the roots the installer replaces in place.

## Build output

| Artifact | Size (decimal MB, as a download reports it) | Contains |
| --- | --- | --- |
| `app-arm64-debug.apk` | ~130 MB | arm64 runtime image, `libtermux.so` (arm64) |
| `app-arm64-release.apk` | ~110 MB | as above, R8-shrunk |
| `app-x64-debug.apk` | ~130 MB | x86_64 runtime image, `libtermux.so` (x86_64) |
| `app-x64-release.apk` | ~110 MB | as above, R8-shrunk |

Each flavour carries only its own architecture's image and native library, so neither
APK is a fat binary. Inside each APK the runtime
archives are stored uncompressed, which is what lets the app stream them out of
assets during first-run unpacking without buffering ~110 MB in memory.

### The size of an APK is checked, not just its contents

`assemble<Flavour><BuildType>` also runs `verifyApkPackaging<Flavour><BuildType>`,
which compares each APK's length with what its own entries account for. It exists
because one build produced an `app-arm64-debug.apk` of 202.8 MB whose entries came
to 126.3 MB: the extra 76.5 MB hid the central directory, so Java could not open the
file as a zip at all and neither would the installer. Packaging was not the culprit —
appending 50 MB and re-running `packageArm64Debug` restores the real size — the stale
artifact came from the **build cache**, which that build reported reusing.

If it fires, rebuild that variant without the cache:

```bash
./gradlew :app:assembleArm64Debug --no-build-cache
```

Deleting `~/.gradle/caches/build-cache-1` removes the offending entry for good, at
the cost of one slow build. The check is deliberately loose — it tolerates a
stored entry's 4 KB page alignment, local headers and a megabyte of slack — because
the failure it looks for is measured in megabytes.

## Signing

Release builds fall back to the **debug** key unless the four `pikit.keystore.*`
properties are given, so such a build is installable but can never be upgraded by a
properly signed one. The keystore this project uses lives in `.release/`, ignored and
never committed, and published releases carry its certificate.

A real keystore is picked up from four Gradle properties, so nothing secret is ever
committed and a local build and CI use the same path. `docs/RELEASING.md` has the
`keytool` command that makes a keystore and the four GitHub secrets that carry it:

```bash
./gradlew :app:assembleArm64Release \
    -Ppikit.keystore.file=/absolute/path/release.jks \
    -Ppikit.keystore.alias=pikit \
    -Ppikit.keystore.storePassword=… \
    -Ppikit.keystore.keyPassword=…
```

All four have to be present — a half-configured keystore is a build failure rather
than a silent fall back to the debug key. Their absence is not an error: that is the
debug-key case above. They may be given as `-P` arguments or as
`ORG_GRADLE_PROJECT_*` environment variables; `tools/build-apks.py` forwards the
latter as the former, so a build it drives cannot come out debug-signed because an
environment variable did not reach the process that resolves it.

## Working on a device

```bash
adb install -r -d app/build/outputs/apk/x64/debug/app-x64-debug.apk
adb shell am start -n pi.kit.mob/.ui.MainActivity
adb exec-out screencap -p > shot.png
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

`uiautomator dump` gives exact node bounds: a screenshot tells you something is
wrong, bounds tell you by how much. `tools/android-ui-dump.py` prints an app's
clickable nodes with their tap centres.

Two emulator facts worth knowing. A Play-Store system image's DNS comes and goes —
`ping api.deepseek.com` and `github.com` both resolve, and a later request fails — and
it has no `adb root`, which is why `tools/dns-pin.cjs` exists for testing. And
`adb shell pm clear pi.kit.mob` costs a fresh ~285 MB unpack on the next launch, where
`adb shell am force-stop pi.kit.mob` restarts the app without that cost.

### Driving a device from Git Bash

Four traps, each of which produces a plausible-looking wrong answer rather than an
error:

- **`MSYS_NO_PATHCONV=1` on every `adb` path argument.** Git Bash rewrites a leading
  `/` into a drive path, so `adb push x.jsonl /data/local/tmp/x.jsonl` transfers to
  `D:/APP/Git/data/local/tmp/…` and fails, and `adb shell sh -c 'cat /data/…'` reads a
  file that does not exist. `adb pull` needs it **and** a Windows-style destination:
  `MSYS_NO_PATHCONV=1 adb pull /sdcard/ui.xml D:/tmp/ui.xml`.
- **Write output to a real Windows path, never `/tmp`.** The `python` in this shell is
  a Windows python, so a script that opens `/d/tmp/…` fails and a file written to
  `/tmp` inside `adb shell` is on the device, not on the host — which is also why a
  screenshot is cheapest with no remote file at all:
  `adb exec-out screencap -p > D:/tmp/shot.png`.
- **`screencap` returns a stale cached frame when the display pipeline has frozen.**
  Two captures byte-identical while the app animates, and a status-bar clock behind
  `adb shell date`, mean the capture is the lie — `uiautomator dump` and `logcat` stay
  live throughout, so the app is fine and the screenshot is not evidence.
  `KEYCODE_SLEEP` → `KEYCODE_WAKEUP` → a swipe recovers it; a stuck AVD cold-boots.
- **A tap coordinate read off a screenshot is not a device coordinate.** `screencap`
  returns 1080x2400 while the image is shown ~900px wide, so a coordinate measured in
  the picture scales by **1.2**. `uiautomator dump` bounds are already device pixels and
  are the source to use; match a node by its `text`/`content-desc` and read its
  `bounds` instead of estimating.

Starting the AVD is worth one more line: `emulator -avd <name> -gpu host
-no-snapshot-save -no-boot-anim`, backgrounded. A **snapshot load can hang** — the AVD
sits `offline` and then vanishes from `adb devices` — and `-no-snapshot` cold-boots in
about 30 s, which is faster than waiting for a load that never finishes. Poll
`adb shell getprop sys.boot_completed` for `1` rather than the device list.

**A Compose `Switch`'s `checked` in the uiautomator tree can lag the stored value**, so
a switch that reads `checked="false"` after three dumps may still have flipped. The two
honest checks are the stored preference (`adb shell run-as pi.kit.mob cat
shared_prefs/pikit_settings.xml`) and the pixel for its *effect* — an on-track is
`primary`. Re-dump after every swipe: row bounds move, and a tap computed against the
previous dump lands on the row that took its place.

### Seeing a populated transcript without a provider

An emulator has no API key, so a prompt produces no answer and the chat tab opens on its
empty state — which is no use for anything the transcript draws. The way in is a seeded
session file:

```bash
MSYS_NO_PATHCONV=1 adb push D:/tmp/seed.jsonl /data/local/tmp/seed.jsonl
MSYS_NO_PATHCONV=1 adb shell \
  "run-as pi.kit.mob sh -c 'cp /data/local/tmp/seed.jsonl files/pi-sessions/seed.jsonl'"
```

It is picked up by the history list, which the chat header's history icon opens. The
records and the two traps — a **linear `parentId` chain**, because pi stores messages as a
tree and `get_messages` returns only the branch reaching the last record, and the profile
file that makes the composer's chips read a real model instead of `unknown` — are written
out in [MAINTAINING.md](MAINTAINING.md)'s *The README preview strip*, steps 2 and 3,
which is the recipe for exactly this. Delete the seed file afterwards: it lands in the
app's real session directory.
