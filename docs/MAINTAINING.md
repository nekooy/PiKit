# Maintaining PiKit

What to do repeatedly, and what to check when it breaks. Cutting a release is a
separate, shorter list in [RELEASING.md](RELEASING.md).

## Every change

```bash
python tools/build-apks.py     # tests + checkers + all four APKs
```

That is the whole loop, and it is also the list of things that must stay true. Two
smaller commands when the full one is not warranted: `./gradlew
:app:testX64DebugUnitTest` for a Kotlin-only change, `python
tools/verify-runtime-image.py` after touching anything that ends up in the image.
[AGENTS.md](../AGENTS.md) has the rules a change has to satisfy — the enums in
`tools/build-apks.py` (variants, image inputs, checkers) are the ones people forget.

## Periodically

| What | How | Why it is on this list |
| --- | --- | --- |
| Dependency versions, by hand | Check `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` and the `uses:` lines in both workflows; bump minors and patches freely, and majors in one coordinated change against a **full APK build**. The authoritative sources, so nobody has to hunt for them again: `https://services.gradle.org/versions/current` for Gradle, `https://dl.google.com/dl/android/maven2/<group-as-path>/<artifact>/maven-metadata.xml` for AGP and every AndroidX artifact (**not** Maven Central, whose `com.android.tools.build:gradle` stops at 2.x), `https://search.maven.org/solrsearch/select?q=g:%22<group>%22+AND+a:%22<artifact>%22&core=gav&rows=8&wt=json` for Kotlin, coroutines and serialization, `https://registry.npmjs.org/<package>/latest` for pi and the extension, and `https://github.com/<owner>/<repo>/releases/tag/v<N>.0.0` for a workflow action — a `404` there is what proves the major in the YAML is the newest one | Dependabot's version updates were switched off: eleven pull requests in the first week, four of them red, and the ones that matter are *majors* — Kotlin, its Compose compiler plugin, AndroidX and the BOM move together with the build files, so a pull request per library is four checks nobody can merge one at a time. **Security alerts stay on**: they are a separate feed that only speaks when an advisory affects something pinned here |
| The runtime's own dependencies | `npm audit --omit=dev` inside `.runtime-build/cache/pi` (and `.../web-access`) after a bump, and the Termux packages' advisories upstream | Nothing scans the image: pi, Node.js, `rg`, `fd` and the Termux packages are inputs pinned by `tools/build-runtime-image.py`, not dependencies of a build that an advisory feed reads. A clean alerts page says nothing about what the APK ships. Last run: 0 vulnerabilities in both trees, against pi 0.86.1 and web-access 0.30.0 |
| `pi` itself | `npm view @earendil-works/pi-coding-agent version`, then bump `PI_VERSION` in `tools/build-runtime-image.py` and rebuild | The agent is the point of the app; its RPC records, tool list and catalogue are what the app parses. The cache records which version it holds, so the bump re-vendors on the next build. **This is the only way pi moves**: the app has no update button for it any more (ARCHITECTURE §2) |
| Where the app looks for releases | `pikit.repository` in `gradle.properties`, if the repository moves | It is compiled into the update check's URL and shown on the About page. A published release is what the check sees, so a version that is committed but not released looks up to date |
| The Termux bootstrap and package set | Bump `BOOTSTRAP_TAG` and the package list in `tools/build-runtime-image.py`, knowingly — not because a newer tag exists. Releases land weekly (Sundays), so being a week or two behind is the normal state | A bootstrap bump changes the userland under everything; it is a device pass, not a version bump. The cached download is named after the URL it came from, so a tag bump fetches the new archive instead of rebuilding the image around the old one. **Check it against the apt index before rebuilding**: the image layers `openssl`, `libffi`, `zlib`, `ca-certificates`, `pcre2`, `libc++` and `resolv-conf` from `Packages-*.bz2` *over* the bootstrap, so a bootstrap that ships a newer build of one of those is silently downgraded by that extraction — compare the new archive's `var/lib/dpkg/status` against the resolved versions, and delete `Packages-*.bz2` to re-resolve if any of them moved. On the 2026.09.20 bump the two agreed on every shared package |
| The web-access extension | Bump `WEB_ACCESS_VERSION`; `vendor_web_access` re-vendors and re-verifies when the version moves | Its page-extraction path is checked by a real fetch, and a broken one fails silently at runtime otherwise |
| Gradle, AGP, the NDK | The wrapper is Gradle's, in `gradle/wrapper/gradle-wrapper.properties`; AGP is in `gradle/libs.versions.toml`; the NDK is pinned in `terminal-emulator/build.gradle.kts` **and copied into both workflows' `NDK_VERSION`** | Three spellings of one version is exactly the kind of thing that rots. Two orderings matter and are not obvious: **AGP 9 needs Gradle ≥ 9.1** and turns on built-in Kotlin, so `org.jetbrains.kotlin.android` stops being applied the old way; and **a newer AndroidX needs a newer `compileSdk`** — AGP 8.x enforces each AAR's `minCompileSdk`, so a `compose-bom` bump drags `compileSdk` up with it. `android.sdk.defaultTargetSdkToCompileSdkIfUnset` (on by default in AGP 9) would set `targetSdk` from `compileSdk`, which is the one default that must not be allowed to reach this project's `pikit.targetSdk=28` |
| Both workflows | Dispatch `ci.yml` by hand after touching it (`workflow_dispatch`); nothing runs on a push or a pull request | A workflow is only verified when it runs; a typo in an `if:` never runs and never fails, and with no automatic trigger `python tools/build-apks.py` is the only check an ordinary change gets |
| The doc indexes | `docs/README.md` and the table in `docs/ARCHITECTURE.md` after any doc is added, split or renamed | Nothing parses Markdown in this build, so a dead link is found by reading, not by CI |

## When upstream pi changes

1. **Read its changelog for records the app parses** — `PiRecord` in
   `pi/PiRpcClient`'s model, the tool-call stream, `get_messages`, the model
   catalogue, the thinking levels. A field that moved is a silent behaviour change
   in the transcript.
2. **Re-capture wire traffic if the shape looks wrong.** `tools/capture-pi-rpc.mjs`
   records a real session; `app/src/test/.../RealCaptureTest` and `ChatFoldTest`
   assert against captures rather than hand-written fixtures, because a fixture
   once agreed with a bug.
3. **Check the provider table.** `PiProviderTest` pins the app's enum against pi's
   own `getApiKeyEnvVars` fixture and lists the providers deliberately absent;
   a provider pi adds should appear there or in that list, with a reason.
4. **Re-run the device pass below** if the change touches the transcript, the
   composer or the catalogue — those are the three places a release has broken
   before.

## The device pass

An emulator is enough for layout and the terminal; a phone is needed for shared
storage, real network and the ARM translation trap. Both are described in
[BUILDING.md](BUILDING.md).

- Install over the previous build (`adb install -r`) and launch cold.
- **Measure, do not look**: `adb shell uiautomator dump` plus
  `tools/android-ui-dump.py` gives node bounds. Every layout claim in
  `docs/architecture/` came from a number, and a new one needs a number too.
- Run a real turn against a real provider, with a tool call and Markdown in the
  answer.
- Tap **Settings → About PiKit → Check for updates** once. It is the app's only
  request of its own, so it is also the one that no JVM test can prove end to end:
  the suite pins the parsing and the version comparison, and the request itself needs
  a device with a network and a published release to see.
- Switch tabs mid-turn, background the app mid-turn, and confirm the turn survives.
- Write through shared storage, and run **Settings → Maintenance & repair → Check
  storage**.
- `pkg install` one small package and confirm it lands in the app's prefix.

## Traps that cost an afternoon

Three that are specific to maintaining this project. The rest — a stale APK from the build
cache, the emulator's DNS, `appops set --uid` — are in AGENTS.md's *Things that will waste
your time*, which is the list a change is written against.

- **A stale runtime image.** Editing the image's sources without rebuilding ships the
  previous guard *and* leaves a device running its old unpacked runtime. `build-apks.py`
  catches it by timestamp against `IMAGE_INPUTS` and by content digest;
  `--refresh-images` overrules it.
- **`EXPECTED_URL_OCCURRENCES` in `verify-runtime-image.py` is a tripwire, not a
  bug.** When a bump legitimately changes the number of relocated URL occurrences,
  update the constant in the same commit — that is the check telling you it noticed.
- **`adb shell pm clear pi.kit.mob`** deletes the unpacked runtime, which costs a
  fresh ~285 MB unpack on the next launch. `am force-stop` is the cheap restart.

## Housekeeping

- **Test counts are summed by `tools/build-apks.py` and written down by hand** in
  `AGENTS.md`; a change that adds tests updates it.
  `CONTRIBUTING.md` points at AGENTS.md rather than keeping a copy of its own — there were
  three once and they drifted apart (375, 353 and 362 for one suite; 377 in 31 suites
  after a re-measurement).
- **The README icon is generated** (`tools/render-icon.py`), and the build fails when
  it and the launcher icon have drifted apart. Edit the vector, regenerate, commit
  both.
- **Rejected designs are not deleted.** When a behaviour is removed, its reasoning
  moves into the chapter that describes it, with the number that settled it.
- **Nothing in this repository writes to git on its own.** Commits, tags and
  branches are made by a person, deliberately — see AGENTS.md.
