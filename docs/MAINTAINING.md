# Maintaining PiKit

What to do repeatedly, and what to check when it breaks. Cutting a release is a
separate, shorter list in [RELEASING.md](RELEASING.md) — but its first item is the
*Before a release: the sweep* below, because upgrading what has a newer upstream is a
maintenance routine that a release merely gives a deadline to.

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

## Before a release: the sweep

**Every release starts by walking the *Periodically* table below and upgrading whatever
has a newer upstream, in the release's own change — before the version bump, not after
the dispatch.** The table is the list; this section is the order and the two rules that
keep the sweep from becoming a rebuild.

1. **The build's own dependencies first** — Gradle, AGP, Kotlin and its Compose compiler
   plugin, the AndroidX artifacts and the BOM. A compile-level break is cheapest to
   unwind before anything else has moved, and the AAR floors in its row decide whether a
   `compileSdk` bump is part of the same change.
2. **Then the three image inputs** — `pi`, the web-access extension, and the Termux
   bootstrap with its package set. These are the slower ones: each downloads hundreds of
   megabytes, and the bootstrap has the cache trap and the device pass in its own row.
3. **Then the runtime's advisories** (`npm audit --omit=dev` in both vendored trees), and
   the checkers the sweep touched: `python tools/build-apks.py` end to end.

The product pins are **`tools/image-versions.json`** — `pi` and `web_access` — and not
constants in `tools/build-runtime-image.py`, because the builder's bytes are hashed into
the seed stamp and a pi bump must move the content layer alone (ARCHITECTURE §3). A
release that bumps `pi` and finds the image rebuilding its seed has edited the wrong
file.

Two things are deliberately not swept blind. **Minors and patches go in one batch; a
major goes alone, against a full APK build** — Kotlin, its Compose compiler plugin,
AndroidX and the BOM move together, and two majors in one change is a failure with two
candidates. **A bootstrap bump is a device pass, not a version bump**: it changes the
userland under everything, and its row's index comparison is what stops a newer
bootstrap from being silently downgraded by the layered `.deb`s.

A release whose sweep found nothing newer still runs `python tools/build-apks.py`; the
sweep is a check, and a check that skipped its own command is not evidence.

**The device half is the app's own page.** `Settings → Maintenance & repair` reports the
same state from inside the installed build, and walking it before a release is what
catches the two things the repository cannot: the packages row's `N 个有可用更新` (the
device's dpkg status against the image's shipped set — `pkg upgrade` in the Terminal tab
is the fix, and the count coming back to zero is the pass), and the model list, whose
`Refresh now` is the only way to see a just-released model before the release ships. The
relocation row and the storage check are the two runs whose failure is a device fact
rather than a build one; *The device pass* below has the rest. The page deliberately has
no control that updates pi: pi moves only with a PiKit release (ARCHITECTURE §2, §9.3),
which is why the sweep's second step is where a new pi enters.

## Periodically

| What | How | Why it is on this list |
| --- | --- | --- |
| Dependency versions, by hand | Check `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` and the `uses:` lines in both workflows; bump minors and patches freely, and majors in one coordinated change against a **full APK build**. The authoritative sources, so nobody has to hunt for them again: `https://services.gradle.org/versions/current` for Gradle, `https://dl.google.com/dl/android/maven2/<group-as-path>/<artifact>/maven-metadata.xml` for AGP and every AndroidX artifact (**not** Maven Central, whose `com.android.tools.build:gradle` stops at 2.x), `https://search.maven.org/solrsearch/select?q=g:%22<group>%22+AND+a:%22<artifact>%22&core=gav&rows=8&wt=json` for Kotlin, coroutines and serialization (its GAV index lags releases — it listed Kotlin 2.2.0 as current while 2.4.21 was already out; when it disagrees with a pin, `https://maven-central.storage-download.googleapis.com/maven2/<group-as-path>/<artifact>/maven-metadata.xml`, Google's mirror of Central, settles it), `https://registry.npmjs.org/<package>/latest` for pi and the extension, and `https://github.com/<owner>/<repo>/releases/tag/v<N>.0.0` for a workflow action — a `404` there is what proves the major in the YAML is the newest one | Dependabot's version updates were switched off: eleven pull requests in the first week, four of them red, and the ones that matter are *majors* — Kotlin, its Compose compiler plugin, AndroidX and the BOM move together with the build files, so a pull request per library is four checks nobody can merge one at a time. **Security alerts stay on**: they are a separate feed that only speaks when an advisory affects something pinned here |
| The runtime's own dependencies | `npm audit --omit=dev` inside `.runtime-build/cache/pi` (and `.../web-access`) after a bump, and the Termux packages' advisories upstream. When an advisory names a transitive package before upstream has caught up, raise it through `PACKAGE_OVERRIDES` in `tools/build-runtime-image.py` — a floor there is what both caches resolve, and it is a content-layer change like any vendored byte | Nothing scans the image: pi, Node.js, `rg`, `fd` and the Termux packages are inputs pinned by `tools/build-runtime-image.py`, not dependencies of a build that an advisory feed reads. A clean alerts page says nothing about what the APK ships. Last run: 0 vulnerabilities in both trees, against pi 1.4.0 and web-access 0.44.0, with `PACKAGE_OVERRIDES` flooring undici at 8.12.3 (pi's own pin was 8.10.2), body-parser at 2.4.0 and fast-uri at 3.1.9 (the extension's express/ajv had 2.3.0/3.1.8) |
| `pi` itself | `npm view @earendil-works/pi-coding-agent version`, then bump `"pi"` in `tools/image-versions.json` and rebuild | The agent is the point of the app; its RPC records, tool list and catalogue are what the app parses. The pin is in that file rather than in the builder because the builder's bytes are hashed into the seed stamp: a pi bump is a *content*-layer change and must not move the seed. The cache records which version it holds, so the bump re-vendors on the next build. **This is the only way pi moves**: the app has no update button for it any more (ARCHITECTURE §2) |
| Where the app looks for releases | `pikit.repository` in `gradle.properties`, if the repository moves | It is compiled into the update check's URL and shown on the About page. A published release is what the check sees, so a version that is committed but not released looks up to date |
| The Termux bootstrap and package set | Check for a newer `BOOTSTRAP_TAG` on every pass and bump it with the package list in `tools/build-runtime-image.py` — releases land weekly (Sundays), so a week or two behind is fine, but this is a checked item now rather than a deliberate one. Compare the archive against the apt index before rebuilding, and run the device pass before the bump ships | A bootstrap bump changes the userland under everything; it is a device pass, not a version bump. The cached download is named after the URL it came from, so a tag bump fetches the new archive instead of rebuilding the image around the old one. **Check it against the apt index before rebuilding**: the image layers `openssl`, `libffi`, `zlib`, `ca-certificates`, `pcre2`, `libc++` and `resolv-conf` from `Packages-*.bz2` *over* the bootstrap, so a bootstrap that ships a newer build of one of those is silently downgraded by that extraction — compare the new archive's `var/lib/dpkg/status` against the resolved versions, and delete `Packages-*.bz2` to re-resolve if any of them moved. On the 2026.09.20 bump the two agreed on every shared package; on the 2026.10.11 one the *cached* index was four packages behind the archive (openssl 3.6.3 against 3.6.5, ca-certificates 2026.08.13 against 2026.09.25, pcre2 10.47 against 10.49, libc++ 29 against 30) and the live index had caught up to all seven — the stale cache is the trap, and the delete is its remedy. On the 2026.10.18 bump the cached and live indexes agreed with the archive on all seven, so nothing was re-resolved; the archive itself moved five packages (coreutils 9.11-1 → 9.12, curl/libcurl 8.22.0 → 8.23.0, libmpfr 4.2.1-1 → 4.2.2, command-not-found 3.5.0-14 → 3.5.0-15) with no package added or removed |
| The web-access extension | `npm view pi-web-access version`, then bump `"web_access"` in `tools/image-versions.json`; the builder re-vendors and re-verifies when the version moves | Its page-extraction path is checked by a real fetch, and a broken one fails silently at runtime otherwise. The pin is in the same file as pi's, and for the same reason |
| Gradle, AGP, the NDK | The wrapper is Gradle's, in `gradle/wrapper/gradle-wrapper.properties`; AGP is in `gradle/libs.versions.toml`; the NDK is pinned in `terminal-emulator/build.gradle.kts` **and copied into both workflows' `NDK_VERSION`** | Three spellings of one version is exactly the kind of thing that rots. They are currently Gradle 9.8.1, AGP 9.4.1 and compileSdk 37. Two orderings matter and are not obvious: **AGP 9.4.1 refuses to apply on anything below Gradle 9.6.0**, and it turns on built-in Kotlin, the new DSL and defaultTargetSdk-to-compileSdk — all three opted out in `gradle.properties`; and **a newer AndroidX needs a newer `compileSdk`** — each AAR's `minCompileSdk` is enforced, so a `compose-bom` bump drags `compileSdk` up with it. Measured ceiling under AGP 8.13 and `compileSdk` 36: core-ktx 1.18.0, lifecycle 2.10.0, activity-compose 1.13.0, compose-bom 2026.06.01 (Compose 1.11.4). core-ktx 1.19.0+, Compose 1.12.0+ (compose-bom 2026.08.00+) and lifecycle 2.11.0 each demand AGP 9.1 and compileSdk 37 — 22 AAR-metadata failures at a time, and what this build's pins now sit above. Kotlin 2.4.21 is the release this build pins. The constraint that once held the pin at 2.3.21 is now measured, not assumed: AGP 8.13's R8 filled the minify log with An error occurred when parsing kotlin metadata on 2.4.20 while parsing 2.3.21 clean, and a silent R8 miss is one thing this project cannot afford (ARCHITECTURE §12.2). Re-measured on AGP 9.4.1's R8 when the pin moved to 2.4.21: both release variants minified with no such line in the log, and `check-release-math.py` found all 218 reflective macro entry points in the release APKs. The warning belonged to AGP 8.13's R8. android.builtInKotlin, android.newDsl and android.sdk.defaultTargetSdkToCompileSdkIfUnset are all on by default in AGP 9 and all three are pinned off in gradle.properties: the last would set targetSdk from compileSdk, which is the one default that must never reach this project's pikit.targetSdk=28 |
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

## The README preview strip

Four phone screenshots — Chat, Terminal, Files, Settings — butted together with no frames and
no captions, saved as `docs/assets/preview-<version>.webp`. The strip it replaces keeps its own
version rather than being overwritten (`preview-0.2.0.webp`), and nothing generates the file, so
this is the whole recipe:

1. **Capture four full-bleed screenshots** at 1080x2400, one per tab, on the interface language
   the strip is written for (简体中文 for the one the README ships). Tap by the bounds
   `adb shell uiautomator dump` reports rather than by eye off a screenshot: the PNG reaches a
   reader at whatever width the window has, while the tree is in device pixels.
2. **Give the chat panel a transcript.** The emulator has no provider, so a real turn cannot be
   produced and the empty state is what the tab opens on. Seed a conversation into
   `files/pi-sessions/` with `run-as`: a `{"type":"session",…}` line, then one record per
   message — `{"type":"message",…,"message":{"role":"user"…}}`, assistant messages whose
   `content` carries `toolCall` blocks, a `toolResult` record per call, and a last assistant
   message carrying `usage`. Two traps, both hit while shooting 0.5.0: the records must form a
   **linear `parentId` chain**, because pi stores messages as a tree and `get_messages` returns
   only the branch that reaches the last record — five tool calls hanging off one prompt
   collapse to one step — and the file only reaches the screen if the app *restores* it, which
   needs `open_new_on_launch=false` (or a tap on its row in History). `adb push` to
   `/data/local/tmp` needs `MSYS_NO_PATHCONV=1` in Git Bash, or the path arrives as `D:/…`.
3. **Make the chips read something.** With no profile configured, `get_state` reports the model
   as `unknown`, so the composer's chips say `unknown` and `0%`. A `files/pikit-config.json`
   naming a provider and a model fixes the first (a dummy key is fine — nothing is sent), and a
   `usage` block on the last message fixes the other two.
4. **Show the fold, opened.** The chat panel is the one panel with a claim to make, and it is the
   feature the page exists for: an **opened** turn — the fold band with its step count, then a
   `✓` row per tool with its name and argument — above a Markdown answer with a heading and a
   table. The collapsed band is the same control in its other state and hides the point; a turn
   with no tool calls at all draws no count, which is how the first attempt shipped an answer
   that said nothing about tools.
5. **Compose**: each screenshot scaled to 480px wide (Pillow, `Image.LANCZOS`) and pasted left
   to right with no gap, saved as WebP at quality 88 — 1920 wide for four panels, which is what
   the previous strips are.
6. **Reference it**: both READMEs (they are mirrors), the asset row in
   [README.md](README.md), and the caption under the image, which names the version the strip
   shows.

## Traps that cost an afternoon

Three that are specific to maintaining this project. The rest — a stale APK from the build
cache, the emulator's DNS, `appops set --uid` — are in AGENTS.md's *Things that will waste
your time*, which is the list a change is written against.

- **A stale runtime image.** Editing the image's sources without rebuilding ships the
  previous guard *and* leaves a device running its old unpacked runtime. `build-apks.py`
  catches it by timestamp against `IMAGE_INPUTS` and by content digest;
  `--refresh-images` overrules it.
- **Two checks in `verify-runtime-image.py` are deliberately not counts, and the trim rule
  is a delete list.** Nothing in the image may contain `pi/kit/mob` — the app id in a slash
  form nothing uses, which is what a rewrite that clobbered a `com/termux` URL would leave —
  and every file a vendored tree holds that the rule keeps is expected in the image by
  name, computed from the cache through `vendor_junk`, the same predicate the trim deletes
  by. So a pi release that edits a link, adds a file or retires one needs no constant
  moved, and a missing one is reported by name (ARCHITECTURE §3). A count of either goes
  stale on the next upstream edit and stays green while it does. Adding a name to the
  delete list is the cheap change; adding one to a keep list silently deletes everything
  the list forgot.
- **`adb shell pm clear pi.kit.mob`** deletes the unpacked runtime, which costs a
  fresh ~285 MB unpack on the next launch. `am force-stop` is the cheap restart.

## The repository's own surface: the description and the topics

Neither is in the tree, so neither is in a commit — and both are what a reader meets
before the README. They are edited with `gh`, and they have one rule each.

**The tagline is one sentence, and it exists in three places that have to agree.** The
current English is `A clean Android AI agent built on pi and Termux.`; the current Chinese
is `一个基于 pi 和 Termux 的简洁安卓 AI Agent。`. The three places are each README's
`<strong>` line and the repository description, which is the two joined by a single
space, English first:

```bash
gh api repos/nekooy/PiKit --jq .description      # read it BEFORE rewriting a tagline
gh api -X PATCH repos/nekooy/PiKit --input - <<'EOF'
{"description": "A clean Android AI agent built on pi and Termux. 一个基于 pi 和 Termux 的简洁安卓 AI Agent。"}
EOF
```

The description is often already the sentence the READMEs are missing, so read it first
and bring the two READMEs *to* it rather than inventing a third wording. When the English
drops a clause, the Chinese needs its own re-cut sentence rather than the dropped phrase —
a mirror is per sentence, not per phrase.

**Topics are discovery metadata and nothing more** — not in the code, the README or the
build, changeable at will. The rules are GitHub's (lower case, hyphen-separated, ≤50
characters, ≤20 per repository) and there is no controlled vocabulary and no synonym
merging, which is why a topic is chosen by *audience* rather than by size:

```bash
gh api -X GET search/repositories -f q="topic:$t" -f per_page=1 --jq '.total_count'
gh api -X GET search/repositories -f q="topic:$t language:kotlin" -f per_page=1 --jq '.total_count'
```

The narrowed count is the one that matters. `material-design` carries ten times
`material3`'s repositories and is mostly web and Flutter UI kits — its Kotlin slice is
~19% — while `material3` is ~82% Kotlin, so the smaller number buys the larger audience.
`material-3-expressive` (101) is the spelling the community uses; `md3e`, `m3-expressive`
and `material-expressive` are dead ends. **`PUT /repos/<owner>/<repo>/topics` replaces
the whole set** — omitting a name deletes it — so always send the full list, and `sort`
the read back because the API returns them in its own order:

```bash
gh api -X PUT repos/nekooy/PiKit/topics --input - <<'EOF'
{"names":["ai-agent","android","android-app","coding-agent","gplv3","jetpack-compose","llm",
"material-3-expressive","material3","pi","pikit","termux"]}
EOF
gh api repos/nekooy/PiKit/topics --jq '.names | sort | join(", ")'
```

## Housekeeping

- **Test counts are summed by `tools/build-apks.py` and written down by hand** in
  `AGENTS.md`; a change that adds tests updates it.
  `CONTRIBUTING.md` points at AGENTS.md rather than keeping a copy of its own — there were
  three once and they drifted apart (375, 353 and 362 for one suite; 377 in 31 suites
  after a re-measurement).
- **The README icon is generated** (`tools/render-icon.py`), and the build fails when
  it and the launcher icon have drifted apart. Edit the vector, regenerate, commit
  both. The same script holds the app's *two* launcher icons to being one mark: the
  white one is the black one's path data in the opposite ink, and it fails on a
  difference in the geometry, in a `fillType`, or in an ink that is the field it is
  drawn on — in both of the script's modes, because that is a property of two files
  rather than of the SVG.
- **The README preview strip is hand-made** — nothing generates it, and it is the one
  asset a change to the interface can leave stale. *The README preview strip* above is
  the recipe; the filenames are version-stamped and the previous strip is kept.
- **Rejected designs are not deleted.** When a behaviour is removed, its reasoning
  moves into the chapter that describes it, with the number that settled it.
- **Git is written deliberately, by the agent or by a person, and only after the gate.**
  Finished, verified work is committed in the turn that finished it; a push is always
  the user's call; and the commands that rewrite history — `stash`, `reset`, `rebase`,
  branch and tag creation — are asked about first. AGENTS.md's *Commits* is the rule.
