# Building PiKit

Everything needed to build the APKs and get one onto a device. The README keeps
the three commands you need most of the time; this is the whole story. Why the
runtime image has to exist at all is in
[ARCHITECTURE §1](architecture/01-the-prefix.md).

## Prerequisites

- **JDK 17 to 23** (built and tested with JDK 21). **Not JDK 24 or 25:** the
  wrapper is Gradle 8.14.5, and a Gradle release cannot run on a JVM newer than the
  one it knows about — Java 25 fails before the build script is even read, which is
  why the error arrives as an IDE dialog rather than as a task failure. Android
  Studio keeps its own choice per project: set **Settings → Build, Execution,
  Deployment → Build Tools → Gradle → Gradle JDK** to 17 or 21 (`gradleJvm` in
  `.idea/gradle.xml`, with the resolved path in `.gradle/config.properties` — both
  are untracked, because both are per-machine). Nothing in this project asks for a
  JVM newer than 17; `sourceCompatibility`/`targetCompatibility`/`jvmTarget` are
  all 17.
- **Android SDK** with `compileSdk 36` and its build-tools, plus **NDK r29** — the
  vendored terminal emulator builds a small PTY shim through `ndk-build`. The 36 is
  an AAR floor rather than a preference: the formula renderer's own metadata requires
  it, and CI installs exactly `platforms;android-36` + `build-tools;36.0.0`
  (ARCHITECTURE §12.2). `targetSdk` is a separate property and stays 28.
- **Python 3.10+** with the `zstandard` module (`python -m pip install zstandard`).
  Only the runtime image builder needs it.
- **Node.js and npm**, only for vendoring the pi agent into that image.

`build-apks.py` names the ones it can see for itself before it starts: the JDK, the
SDK, the NDK (by the version `terminal-emulator/build.gradle.kts` pins, resolved
through `local.properties` or `ANDROID_HOME`), Node and the Gradle wrapper. `npm` and
`zstandard` are reported by name too, but only when an image actually has to be
built — a machine that already has the images can rebuild the APKs without them.

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
**`arm64`/`x64` × `debug`/`release`**, one Gradle invocation per variant so a failure
names the one it happened to. Every step streams its output, and the summary at the
end is the list of files to install. The last thing it does is read the release APKs
it has just built: `tools/check-release-math.py` fails the build when R8 has removed
the formula renderer's reflective command table — a failure that no JVM test and no
debug device can see (ARCHITECTURE §12.2).

| Flag | For |
| --- | --- |
| `--skip-tests` | build without running any check |
| `--debug-only`, `--release-only` | two APKs instead of four |
| `--refresh-images` | rebuild the runtime image even when it is already there |
| `--clean` | delete the built APKs first |

It also repairs the one failure the packaging guard is known to catch: if a variant
fails with a stale APK (see *The size of an APK is checked*), it deletes that
variant's APK and retries it once with `--no-build-cache`.

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
`bootstrap.zip`, `overlay.zip`, `revision.txt`) against `IMAGE_INPUTS` — the builder,
the prefix rewriter, the relocator, the `dpkg` wrapper, the delete guard and the
storage self-test — and rebuilds a flavour whose image predates one of them, naming
the file that made it stale.

The image carries a
`revision.txt`, and `BootstrapInstaller.ensureInstalled` compares revisions rather
than contents, so an image built from a previous guard both ships the previous guard
*and* leaves a device that already installed the earlier APK running its old
unpacked runtime after the new APK is installed. That is why the revision digest
hashes those files' *content* rather than trusting the archives' sizes — and why the
two files that decide the content rather than ending up in it are hashed as well,
`tools/prefix_patch.py` and the builder itself: the rewrite is length-preserving (both
ids are ten characters), so it can rebuild every archive at exactly the same size.

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
5. Emit two stored (uncompressed) zip archives, `revision.txt` (the id the app compares
   an installed runtime against) and `build-metadata.json` (what the image was built
   from), plus `SYMLINKS.txt`, because the zip format cannot represent symlinks.

## Build output

| Artifact | Size (decimal MB, as a download reports it) | Contains |
| --- | --- | --- |
| `app-arm64-debug.apk` | ~126 MB | arm64 runtime image, `libtermux.so` (arm64) |
| `app-arm64-release.apk` | ~107 MB | as above, R8-shrunk |
| `app-x64-debug.apk` | ~126 MB | x86_64 runtime image, `libtermux.so` (x86_64) |
| `app-x64-release.apk` | ~107 MB | as above, R8-shrunk |

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
