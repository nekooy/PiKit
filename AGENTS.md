# Working on PiKit

Notes for an AI agent changing this repository. [README.md](README.md) says what
the project is; [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says why the design is
shaped the way it is, chapter by chapter, and [docs/README.md](docs/README.md) is
the index of every document. This file is the practical part.

## Build and test

```bash
python tools/build-apks.py                   # tests + all four APKs, one command
./gradlew :app:testX64DebugUnitTest          # 377 tests, fast, no device
./gradlew :app:compileX64DebugKotlin         # quick check for a Kotlin-only change
python tools/build-runtime-image.py --all    # the image alone, without an APK
```

There is no `:app:testDebugUnitTest`: the ABI is a product flavor, so that task's name
carries it. (`:terminal-emulator:testDebugUnitTest` exists — the vendored VT parser is
its own module and has no flavor.) APKs land in `app/build/outputs/apk/<flavor>/debug/`. Prerequisites,
the image builder, signing and the device workflow are in
[docs/BUILDING.md](docs/BUILDING.md). For anything layout-related, measure rather
than look: `uiautomator dump` gives exact node bounds.

**`tools/build-apks.py` enumerates the build by hand, so a change that adds
something it lists must update it in the same change.** It is the one command the
README, `docs/BUILDING.md` and this file all point at, and a list it does not know
about does not fail — it goes missing:

- a new Gradle test task or module → the task list in `run_tests()`;
- a new standalone checker under `tools/` → the `checks` list there, which is also
  what prints its one-line verdict (and which skips itself when what the checker
  needs is absent, as the relocator check does without the `.deb` cache). The one
  exception is a checker that reads an **APK**: `checks` runs before anything is
  packaged, so `check-release-math.py` is called by `main()` after `build_apks()`;
- a new ABI or flavour → `VARIANTS` and `ABI_DIRS`, and `build-runtime-image.py`
  with them, because the assets a flavour packages are that script's output;
- a new file the runtime image is built from → `IMAGE_INPUTS` there **and** the
  digest in `build-runtime-image.py`, which are what make an edit to the image's
  sources rebuild it and make a device unpack the result. The list is checked, not
  trusted: `image_input_manifest_errors()` reads the builder's own source and
  fails the build over a file it copies that the list does not name;
- a new prerequisite — a runtime, an SDK component, a version floor →
  `check_prerequisites()`, so it is reported by name instead of exploding inside
  Gradle (and `image_builder_prerequisites()` for the two only an image build
  needs, so a machine that already has the images is not blocked by them);
- a new flag → the parser in `main()` **and** the usage block in the module
  docstring, which `docs/BUILDING.md` mirrors as its flag table.

Test counts are the exception: the script sums the JUnit XML it just produced, so
adding tests edits nothing here — the number in the command list above is the one
figure maintained by hand.

## Documentation — where a change goes

`docs/README.md` is the index of every document and the place the rules are
written out in full; this is the short version, because a change that adds or moves
a document is exactly the change that forgets them.

- **One topic per file.** `docs/ARCHITECTURE.md` is a map with a table of chapters,
  not a document; the reasoning itself is one file per chapter in
  `docs/architecture/`, and each chapter carries its own measurements — the number
  that settled a design and the design it rejected live together.
- **A file that outgrows its topic gets split, not trimmed.** Look at it again
  around 25 KB (roughly 400 lines); the fix is a new file, a row in `docs/README.md`
  and a row in `ARCHITECTURE.md`'s table — never a shorter version of the same
  reasoning. A chapter that outgrows one file gains numbered parts (`§6.1`, `§7.3`,
  `§12.2`), the part number goes into the file name, and every citation is re-pointed
  to the part it means in the same change — a citation without a part (`§12`) is never
  written.
- **Cite chapters by § number, not by file name**, in code comments and in other
  documents: `ARCHITECTURE §12.2` resolves through the table in `ARCHITECTURE.md`,
  and that is what survives a split or a rename.
- **A generated file says so, and is checked.** The first comment names the
  generator and the command that reproduces it; a checker under `tools/` — listed in
  `build-apks.py`'s `checks` — fails the build when the file and its source have
  drifted. `docs/assets/icon.svg` (from `tools/render-icon.py`, which reads the
  launcher icon) is the only one today.
- **The index is updated in the same change as the file.** Nothing in the build
  parses Markdown, so a link to a file that no longer exists is found by reading,
  not by CI.
- **Prose is English; the interface is not.** Documentation, comments and commit
  messages are English. Anything the user reads goes through `locales/`.

## Version, workflows, and the repository

- **The version lives in `gradle.properties`** — `pikit.versionName` and
  `pikit.versionCode` — and nowhere else. Never write one inline in
  `app/build.gradle.kts`: the value reaches the About page, the shell's
  `TERMUX_VERSION` is *not* this (it is the bundled environment's), the manifest and
  the release workflow's tag all read it, and `docs/RELEASING.md` is the rule for
  bumping it. `versionName` is refused unless it is a bare `major.minor.patch`;
  `versionCode` must go up for every published build.
- **`pikit.repository` is the one place the update check's repository is named**
  (`<owner>/<repo>`, compiled into `BuildConfig.REPOSITORY`). It is asked when the
  user taps the About page's row and never in the background; the row compares
  GitHub's `releases/latest` tag against `pikit.versionName`, so a version that is
  committed but never released is invisible to it.
- **Release APKs are signed with the debug key until four `pikit.keystore.*`
  properties are supplied together.** A *partial* set is a build failure on purpose
  — a release signed with the wrong key can never be upgraded, and the mistake is
  silent until someone tries.
- **The NDK version is pinned in `terminal-emulator/build.gradle.kts` and copied
  into both workflows' `NDK_VERSION`.** Three spellings of one version is a thing
  that rots, so update all three in one change. The build-tools version is a
  workflow-only pair in the same shape — `BUILD_TOOLS_VERSION` in `ci.yml` and
  `release.yml` — because the release one installs them and then runs the
  `apksigner` inside them.
- **Both workflows are dispatch-only; `ci.yml` is the fast half and `release.yml` the
  slow one.** `ci.yml` runs the JVM suites and the checkers that need no runtime image,
  and is dispatched when the answer has to come from a clean machine; an ordinary
  change is verified by `python tools/build-apks.py` instead. `release.yml` assembles
  the images (hundreds of MB of upstream downloads), builds both release APKs and
  publishes them, and refuses a tag that already exists. Dispatching is the only way
  to test a change to a workflow — nothing else verifies one. `release.yml`
  **publishes** the release it creates (no draft) and is serialised against itself
  with `concurrency: release-…`, never cancelled.
- **Nothing here commits, tags or branches on its own.** See *Commits* below.

## Hard constraints — do not "fix" these

**The application id must be exactly 10 characters.** `pi.kit.mob`. The bundled
Termux image is relocated from `com.termux` (also 10) by a length-preserving byte
rewrite, so a different length corrupts the binaries. `app/build.gradle.kts` and
`tools/prefix_patch.py` both enforce it.

**`targetSdk` stays 28.** Android 10 refuses `exec()` of files in an app's own
writable data directory from the `untrusted_app_29` SELinux domain up, and the whole
runtime lives there. AOSP's policy grants `app_data_file:file execute_no_trans` from
`private/untrusted_app_27.te` (`25 < targetSdkVersion <= 28`) and not from
`untrusted_app_29.te`, so 28 is the **highest** value that runs, not merely the safest
(ARCHITECTURE §1). The `ExpiredTargetSdkVersion` lint warning is expected.

**The prefix is an absolute path compiled into every binary.**
`/data/data/pi.kit.mob/files/usr`. Nothing may move it, and
`BootstrapInstaller.verifyPrefixMatchesImage()` compares inode identity (not
strings) because `/data/data/<pkg>` and `/data/user/0/<pkg>` are bind-mounted views
of one directory.

**Every recursive delete goes through `env/SafeDelete.kt`.** It is the only one,
and it refuses any path outside the app's private data directory. Do not add
`File.deleteRecursively` anywhere.

**No vertically scrollable component may be measured with an unbounded height, and
`MarkdownText` is the one that is shared.** It is a plain `Column`, and it has to stay
one: it is drawn inside the transcript's `LazyColumn` *and* inside `SettingsBody`'s
`Column(verticalScroll)`, where a `LazyColumn` throws `IllegalStateException:
Vertically scrollable component was measured with an infinity maximum height
constraints`. The rule is about the *call sites*, not the composable: a shared
composable's constraints are the union of everywhere it is drawn, so making the
container lazy again — tempting for a 300-block reply — is still the crash.

**A formula is typeset by JLaTeXMath, and its line box has to be built around it.** The renderer is
`ru.noties:jlatexmath-android` (`MathCache.kt`'s `typeset`), and the vertical placement is the
caller's job. Compose places an inline placeholder by an *edge*, and of the edges in
`PlaceholderSpan.getSize` only **`TextCenter`** grows both the ascent and the descent — which is what
a formula needs — so `MathView.kt` declares the smallest box centred on the text's centre that
contains the formula's ink and draws the drawable inside it at the offset that puts the formula's
baseline on the line's. The ascent and descent come from a `TextMeasurer` measurement of the same
style, because the sentence's font decides them. ARCHITECTURE §12.2 has the two failed
attempts and the full mechanism.

**A formula the renderer refuses is rewritten before it is given up on.** `LatexCompat.kt` drops
numbering (`\tag`, `\label`, `\nonumber`), unwraps decorations (`\cancel{x}` → `x`), renames
`\ce` to `\text` and `\color` to `\textcolor`, and aliases `color` in JLaTeXMath's own macro table
where it can. Only then does the formula fall back to being drawn as its source, with a `Log.w`
naming the body — a formula that silently shows its source is a bug report waiting to happen.

**Do not go back to a Compose-measured renderer.** `io.github.huarangmeng:latex-renderer` typeset
from KaTeX's metrics and cost **21.7 ms per formula**: 142 formulas took 3078 ms on `MathCache`'s
worker, where JLaTeXMath lays the same formula out in **0.42 ms** on the same emulator. Moving that
work off the UI thread — which `MathCache` does, and should keep doing — is not a substitute for the
renderer being cheap: it was tried first and the reply was still slow. What the swap cost is real
and is recorded in ARCHITECTURE §12.2: the ink is fixed at typeset time (so a theme change re-typesets,
0.35 ms a formula), and the MathSpeak accessibility description is gone, leaving the LaTeX source as
the placeholder's alternative text.

**Keep JLaTeXMath whole in the release build, and check the APK that ships.** R8 runs only in the
release variant, and JLaTeXMath resolves its command table **by name at run time** — `MacroInfo`
and `TeXFormulaParser` use `Class.forName`, `getDeclaredMethod`/`getMethod` and
`getDeclaredField`, so a member nothing appears to call is deleted and the lookup returns null.
Measured on one 42-construct sweep: the debug APK fell back on `\oiint`/`\oiiint` only, the release
APK additionally on `\dfrac`, `\tfrac`, `\left\{…\right.`, `\begin{align}`, `\operatorname` and
`\substack` — a difference no debug build shows, which is how it reached a phone while
every formula test on the emulator was green. `app/proguard-rules.pro` keeps the whole
`org.scilab.forge.jlatexmath` package (330 KB of classes against a 107 MB APK, and **218**
reflective entry points to guess wrong about otherwise), and `tools/check-release-math.py` — called
by `build-apks.py` **after** the APKs are built, not from its `checks` list — fails when a release
APK's dex has lost them. **A formula verified on a debug build is not a formula verified on the
build people install**; ARCHITECTURE §12.2 carries
the numbers.

**Do not hand-draw an icon, and do not adjust one you did not draw.** Six revisions of one 24-unit
glyph were rejected, and every one looked reasonable on the way; ARCHITECTURE §12.3 has that
sequence. **Render candidates at the size the app draws them, and choose from those** — a 64dp
preview says nothing about whether a glyph survives at 21dp. And **a resource that fails to compile
is silently replaced by a cached older one**, which is how a drawable went missing from three APKs:
`unzip -l app-debug.apk | grep ic_whatever` is the check that settles it — **on a debug APK**,
because a release one renames every resource to a two-character path (`res/0K.xml`) and the same
grep returns nothing for a resource that is present and correct.

**A glyph from another set has another weight, so its size is a call-site decision.** ⌘ at the
21dp the Material glyphs beside it use is too heavy: Lucide draws that
figure out to 21 of its 24 units where Material's `Add` keeps its ink inside 14. It is drawn at
**17dp** in `ChatScreen.kt`, and the vector is untouched — scaling a borrowed glyph at the call
site is a layout decision; editing its geometry is the mistake the bullet above is about.

**When the question is how something should look, ask for a picture of the thing wanted — not for a
menu.** The copy button took four rounds — six *containers*, then six glyphs from other icon sets,
then five more sets — before an SVG pasted into the conversation (`ic_copy.xml`) worked;
`ic_check.xml` is Lucide's check at about the weight of its walls, and `CopyButton.kt` is the one
composable both copy buttons use. ARCHITECTURE §12.3 has the four rounds.

**`Modifier.fillMaxWidth()` sets the *minimum* width, not just the maximum.** Inside a
parent that caps the width — `Box(Modifier.widthIn(max = …))` over a `BoxWithConstraints`
— it therefore asks for exactly the cap and gets it, which is how a two-character prompt
drew an 86%-wide bar. Size-from-content means *no* `fillMaxWidth` on the child at all;
the parent's `widthIn(max = …)` already leaves the minimum at zero.
`wrapContentWidth` is not the fix and is a no-op in that position, because the minimum
arriving there is already zero.

**The agent's workspace is `$HOME/workspace`, not `$HOME`.** It is where pi is spawned
(`TermuxEnv.workspace`), what `PIKIT_WORKSPACE` publishes, and the one directory
`tools/pi-safety-guard.ts` allows a recursive delete in. Changing one of those without
the others is a guard that either refuses the agent's real work or allows a delete of
`$HOME/.pi`; `tools/test-safety-guard.mjs` is the command that says which. The guard
also lives inside the runtime image, so an edit to it has to reach the image:
`python tools/build-apks.py` does that on its own (`IMAGE_INPUTS` — an image older than
one of the files it is built from is rebuilt, and the image revision covers the file's
content), and an APK built without it ships the previous guard.

## Commits — ask first

**Do not run `git commit`, `git push`, or anything else that writes to a
repository, unless the user has explicitly asked for it in that turn.** Not as a
final step, not to "save progress", not because the change is finished and
tested. Being asked to *write* code is not being asked to *commit* it, and an
unwanted commit is rewritten history for whoever has to clean it up. The same
applies to `git add`, `git stash`, `git checkout`/`restore`, branch creation, tag
creation, and `git init`. Read-only commands (`status`, `diff`, `log`, `show`,
`blame`) are fine at any time. The repository exists (`nekooy/PiKit`) and its
history starts at one commit, so `git log` and `git blame` are worth reading
before concluding that something is arbitrary.

## Commit message format

When a commit *is* requested, use Conventional Commits. **Only the subject line is
required — the body and the footer are optional**, and a subject-line-only commit
is often the right choice for a small change.

**A message is short: the subject plus at most five bullets of one or two lines each.**
A change that does not fit in that is two commits, or the detail belongs in a document
under `docs/` that the message points at. The body says *why*; the diff already says
*what*, and a message nobody reads to the end is not a record.

- **Subject** — `<type>(<scope>): <subject>`. Imperative mood, no trailing full
  stop, lower case after the colon, at most 72 characters. It says what the commit
  does to the tree — "reject a recursive delete outside $HOME", not "rejected".
- **Type** — `feat` (new user-visible behaviour), `fix`, `refactor`
  (behaviour-preserving), `perf`, `docs` (documentation or comments only), `test`,
  `build` (Gradle, image builder, dependencies, packaging), `ci`, `chore`.
- **Scope** — optional, lower case, one word: `chat`, `settings`, `storage`,
  `env`, `locales`, `pi`, `terminal`, `build`, `tools`, `docs`; omit it when the
  change spans the project.
- **Body** — optional, blank line after the subject, wrapped at 72 columns.
  Explain *why*, not what: the diff already says what. **When it is more than a
  line or two, write it as a bulleted list, one point per bullet**, rather than a
  dense paragraph. Say how a bug manifested and what the evidence was, and name a
  design that was rejected along the way.
- **Footer** — optional, blank line before it: `Refs:`, `Closes:`,
  `Co-authored-by:`, or `BREAKING CHANGE:` followed by what breaks and what to do
  about it.

```
fix(chat): fold a restored conversation's turns

Turns are re-derived from the stored messages with the same rule the live
reducer uses; a turn closes at its own last message rather than at the next
prompt, which billed the idle gap to a turn that had already finished.
```

## Conventions

- **Comments explain *why*, and name the measurement**, and several record a
  design that was tried and failed with the number that settled it ("the labels
  landed at y=1454 with the keyboard up against y=2274 with it down"). Keep the
  reasoning for a rejected design when you remove the behaviour: it moves into the
  chapter under `docs/architecture/` that describes it, and is never deleted.
- **Prose states the problem, not who reported it.** A chapter, a comment or a
  commit message describes what was observed and what it measured — "a
  two-character prompt drew an 86%-wide bar" — and never dates a section, quotes a
  bug report as the subject of a sentence, or writes "the reader reported X".
  Quoting a specification, a log line or an interface string is fine; narrating a
  report is not. What a report *established* stays, because that is the rejected
  design's reasoning.
- **User-visible text goes through the catalogs**, never inline. `locales/Strings.kt`
  declares the interfaces; the three catalogs implement them, and the compiler
  refuses one that is missing a member. Prose is the exception: `ManualText*.kt`
  and `TerminalBanner.kt`, kept concise and parallel across languages.
- **The conversation reducer is pure and Android-free.** `ConversationReducer` takes
  `(ConversationState, PiRecord, now)` and returns state, which is what makes the
  state machine testable on the JVM against captured wire traffic — keep it that
  way, including passing `now` in rather than calling `System.currentTimeMillis`.
- **Tests that pin behaviour beat tests that mirror it.** `ChatFoldTest` and
  `RealCaptureTest` exist because a hand-written fixture once agreed with a bug:
  pi sends no `id` on `toolcall_delta` records, the reducer read one, and the
  fixture matched. Add the test that fails without your fix.
- **Kotlin formatting**: 4-space indent, trailing commas, `internal` for things
  only the tests or the same module need. Tests are plain JUnit, with no
  Robolectric and no instrumentation.

## Things that will waste your time if you do not know them

- **Start the AVD with `-gpu host`, not `-gpu swiftshader_indirect`.** On software
  rasterisation the GPU context times out (`dumpsys gfxinfo`: `GPU Context timeout:
  10`, frames at 4950 ms) and the app's window never finishes its first draw, while
  `dumpsys window` calls it visible and `dumpsys input` calls it `NOT_VISIBLE,
  alpha=0` with a blank `screencap` — and `uiautomator dump` still returns a correct
  tree, so it looks like a broken instrument rather than a broken backend.
- **`pkg install` relocation has two paths** — apt's `DPkg::Pre-Install-Pkgs` hook
  (`pikit-relocate --apt-list`, which reads the archives apt is about to hand to
  dpkg from stdin), plus a `bin/dpkg` wrapper around a renamed `bin/dpkg.real`.
  The wrapper is not redundant: it covers every install apt did not mediate, and
  the hook cannot be replaced by a scan of apt's cache (ARCHITECTURE §4).
- **The relocator is the one file the build's prefix rewrite must not touch.** It
  is listed in `prefix_patch.NEVER_REWRITE`; without that, `OLD_ID` becomes
  `NEW_ID`, every archive is "already relocated", and no package installs. Run
  `node tools/pikit-relocate.js --selfcheck` after touching the build (ARCHITECTURE
  §4).
- **`--api-key` goes on the command line for a custom endpoint**, because pi does
  not expand `$VARIABLE` in `models.json` for a provider it does not know. The key
  is therefore in argv, deliberately; ARCHITECTURE §6.1 has the control test.
- **The emulator's DNS comes and goes.** Measured on the Medium_Phone AVD
  (Play-Store `user` build): `ping api.deepseek.com` and `ping github.com` both
  resolve, and a `pi` run by hand reached the provider and got a real `401` back —
  so a test that needs the network is worth attempting. When it does not resolve,
  nothing local fixes it: `getaddrinfo` goes through `netd`, so neither
  `resolv.conf` nor `/etc/hosts` helps, and `tools/dns-pin.cjs` plus a
  `dns-pins.txt` in the app's `files/` directory is the workaround.
- **`appops set <pkg> MANAGE_EXTERNAL_STORAGE deny` is not enough** to revoke "all
  files access": the uid mode stays `allow`. Use `appops set --uid <pkg> … deny`.
- **The screen is about 48 columns wide** on a 1080px phone — the terminal prints
  into it and the in-app manual is read on it, which is why `TerminalBanner.kt`
  pads its command column and the manual is written in short lines.
- **`--refresh-images` rebuilds the image; pi is pinned, not refreshed.**
  `PI_VERSION` in `tools/build-runtime-image.py` is the release the image vendors, and
  the cache records which version it holds, so bumping the constant re-vendors on the
  next build. `--pi-version <version>` overrides it for one build. A cache written
  before that marker existed re-vendors once, which is not a bug.
- **A stale APK comes from the Gradle build cache, not from packaging.**
  `build-apks.py` recognises the failure (an APK larger than its own entries) and
  retries that variant once with `--no-build-cache`; if it happens twice, delete
  `~/.gradle/caches/build-cache-1`.
- **The history is one commit, and it is the whole application.** There is no
  earlier revision to compare against: the first commit carries every design decision
  at once, so `git log` answers "when did this appear" with 0.1.0 and nothing else.
  What explains why something is the way it is, is still the comments and
  `docs/architecture/` — and `git blame` on a line is only useful from the second
  commit onwards.
