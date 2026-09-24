# Releasing PiKit

Publishing a build is: bump one version in one file, commit it, push, then run one
workflow. Everything else is a check that the workflow does for you.

**The push itself starts nothing.** Both workflows are dispatch-only (AGENTS.md): what
they cannot check for themselves is the hand-run checklist under *Before you dispatch*,
and the release job runs every test and checker `tools/build-apks.py` runs before it
packages anything.

## The version lives in one place

`gradle.properties`:

```properties
pikit.versionName=0.1.0
pikit.versionCode=1
```

- **`versionName`** is what the user sees — **Settings → About PiKit**, the in-app
  manual's subtitle, and the release tag. (It is *not* what a shell sees:
  `TERMUX_VERSION` reports the bundled environment's version — the image's own
  metadata, not this file, and `BundledImageTest` pins the tag parse behind it.)
  It must be a bare
  `major.minor.patch`; `app/build.gradle.kts` refuses anything else, because
  `0.1.0-x64` is a build note rather than a version and the ABI is already reported
  by the runtime revision on the same page.
- **`versionCode`** is the integer Android compares. It must go **up by one for
  every published build**: Android refuses to install a lower code over a higher
  one, so a reused code is a build that can never supersede the one already out
  there. This is the number to bump when re-releasing the same `versionName` after
  a bad package.

Which number to raise follows the usual rule: `patch` for a fix, `minor` for a
feature, `major` when the storage format or the application id changes (the latter
is a new app, since the Termux prefix is compiled into every binary).

For a one-off local build, both can be overridden without editing the file:

```bash
./gradlew :app:assembleArm64Release -Ppikit.versionName=0.2.0 -Ppikit.versionCode=99
```

The shape rule still applies to an override — a prerelease suffix is refused even
here, because the value reaches the About page — so an override is for building a
version that is already decided, not for labelling a build. Anything published
comes from the file: `tools/build-apks.py` passes no override, and the release
workflow refuses a version whose tag exists.

## The keystore, once

Release builds fall back to the **debug** key while no keystore is configured —
installable, but impossible to upgrade. This repository therefore keeps one in
`.release/`, ignored by `.gitignore`, holding five files:

| File | What it is |
| --- | --- |
| `release.jks` | The keystore itself: PKCS12, RSA 4096, alias `pikit`, valid 10000 days |
| `release.jks.base64` | The same file, base64-encoded — the form GitHub needs |
| `password.txt` | The store password on its own line, for a script: PKCS12 uses one for the store and the key |
| `github-secrets.md` | The four `gh secret set` commands, **with the password in them** |
| `fingerprint.txt` | The certificate's SHA-256, for checking a published APK against it |

`password.txt` sits next to the keystore it opens: a key and its password in one directory
are, together, the secret. The file is there so a build script does not have to parse prose,
and it is ignored by `.gitignore` like the rest. **Back up the directory, not the file.**

The key this repository generated on 2026-09-16 has the certificate SHA-256
`9B:A9:BF:38:1B:3C:72:2A:A9:1C:2B:79:53:3C:BC:3A:E7:5F:24:D3:90:06:C4:D1:AC:43:44:B5:33:3D:00:E4`,
which is what `apksigner verify --print-certs` should print for a release APK it
signed. A release APK built here before that key existed is signed with the Android
debug certificate instead, and **cannot** be upgraded by one signed with this key —
it has to be uninstalled first, which loses the app's data.

**Back it up somewhere you will still have in five years, and never commit it.** A
committed keystore is anyone's; a lost one means no future build can upgrade anyone who
installed your release.

To make a new one — only if you are replacing the key, which orphans every installed
copy, and never as an ordinary step:

```bash
mkdir -p .release
keytool -genkeypair -v -keystore .release/release.jks -storetype PKCS12 \
    -alias pikit -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=PiKit, O=PiKit, C=CN"
base64 -w0 .release/release.jks > .release/release.jks.base64
```

For a local build with that key:

```bash
./gradlew :app:assembleArm64Release \
    -Ppikit.keystore.file="$PWD/.release/release.jks" \
    -Ppikit.keystore.alias=pikit \
    -Ppikit.keystore.storePassword="$(cat .release/password.txt)" \
    -Ppikit.keystore.keyPassword="$(cat .release/password.txt)"
```

Then give GitHub the four values — **Settings → Secrets and variables → Actions →
New repository secret**, or the CLI, which is what the generated file lists:

```bash
gh secret set KEYSTORE_BASE64 < .release/release.jks.base64
gh secret set KEYSTORE_ALIAS --body "pikit"
gh secret set KEYSTORE_STORE_PASSWORD --body '<the store password>'
gh secret set KEYSTORE_KEY_PASSWORD --body '<the key password>'
```

In PowerShell the first line is `Get-Content .release/release.jks.base64 -Raw | gh secret set KEYSTORE_BASE64`.

PKCS12 uses one password for both, so the last two are the same value. `app/build.gradle.kts`
refuses a *half*-configured keystore rather than silently signing with the debug key,
so a typo in one secret name is a failed build rather than a wrong APK.

`tools/build-apks.py` forwards whichever of the four is in its environment to Gradle as
`-P` arguments, redacting the two passwords from its own output. Gradle reads the
`ORG_GRADLE_PROJECT_*` form by itself, but that is deliberately not relied on: the release
workflow checks the resulting APK against the keystore's own fingerprint, because a build
that succeeds is not evidence that it was signed with the key it was given.

## Publishing the repository, the first time

`nekooy/PiKit` is already through this; it is kept for a fork, a mirror or a
re-creation.

1. **Create the repository and keep it public.** `git add --chmod=+x gradlew` first (a
   Windows checkout has no executable bit — see CONTRIBUTING.md), then
   `gh repo create nekooy/PiKit --public --source . --remote origin --push`. GPLv3 §6
   accepts distributing to your own users, but a public tree is the simplest way to meet
   the corresponding-source obligation; a private repository also makes **Check for
   updates** useless (below).

2. **Let the release job write.** *Settings → Actions → General → Workflow
   permissions* → **Read and write permissions**, because `release.yml` creates a
   release and a tag. It is also the first thing to check if publishing fails with a
   403 that names the token rather than the setting.

3. **Add the four secrets** above, and **fill in the blurb** — description, topics, and
   a social preview exported from `docs/assets/icon.svg` (GitHub wants a PNG there,
   1280×640).

Nothing else is needed: neither workflow runs on its own — each is dispatched by hand
from the Actions tab — and each carries its own toolchain setup.

## Run the release workflow

**Actions → Release → Run workflow.** It refuses a dispatch from any branch but
`main`, reads the version from `gradle.properties`, refuses to continue if
`v<versionName>` is already tagged **or already has a release**, then
assembles the runtime images (~200 MB of
upstream downloads on a cold cache), runs every test and checker
`tools/build-apks.py` runs, builds both release APKs, **checks they carry the release
certificate rather than the debug one**, writes `SHA256SUMS`, and uploads them as an
artifact. A second job, **publish**, downloads that artifact and creates the GitHub
Release, **published rather than drafted**. Budget about an hour cold, several minutes
warm. The run is serialised against itself (`concurrency: release-…`, never cancelled):
two dispatches racing to create the same tag is the failure that guard exists for.

**Publishing is its own job on purpose.** `gh release create` was refused
(`HTTP 403: Resource not accessible by integration`) inside the twenty-step build job, and works
from a job of its own, which needs only `contents: write` and can be re-run alone ("Re-run failed
jobs") without rebuilding anything.

**The assets are named `PiKit-<version>-<abi>.apk`**, renamed in place after the signing check —
one directory, one candidate for "the APK", which is what the checksum file, the artifact and the
publishing job all pick up. The step refuses to write a
`SHA256SUMS` with no checksum line in it, and writes the checksums to the run's step summary as
well.

**The signing check is not a formality**: a release with none of the four
`pikit.keystore.*` properties is signed with the debug key, which is why the workflow
refuses to publish unless **allow debug signing** is set; a *partial* set is a build
failure rather than an APK signed with the wrong key. It compares against the keystore's
own fingerprint (`apksigner verify --print-certs` against `keytool -list -v`), so a
debug-signed APK cannot be published by accident. The
`apksigner` it runs is the one the workflow installed, named once in
`BUILD_TOOLS_VERSION`.

**The release publishes itself, and the notes are edited afterwards.** No draft: a draft
is not a `releases/latest` the update check can see, so a release waiting for a person to
write its notes is a release nothing can see. `--generate-notes` still runs and still
produces the single `**Full Changelog**: …compare/v0.1.0...v0.2.0` line, because it groups
by merged pull request and every commit here went straight to `main`; that line is a true
sentence and a better starting point than an empty body. Edit it on the release page when
you have something to add: `gh release edit v0.2.0 --notes-file …` does the same from a
terminal.

The tag and the release are created together by that one command, so there is no window
in which a version exists and is invisible. `gh release delete v0.2.0 --cleanup-tag
--yes` removes both if a version must be withdrawn.

The release it creates is also what **Settings → About PiKit → Check for updates**
reads: the app asks for `github.com/<owner>/<repo>/releases/latest` and reads the tag out
of the URL GitHub redirects it to (`…/releases/tag/v0.2.0`), comparing that against its own
`versionName`. It is the release *page* and not `api.github.com` on purpose: the API's
unauthenticated budget is 60 requests per hour **per address**, which a VPN exit shares and
spends (ARCHITECTURE §9.2). A version
that is committed but never released is invisible to it, and a release created by hand needs
the same `v<versionName>` tag to be seen. `pikit.repository` is where the app is told which
repository to ask.

**A private repository makes that check useless, and silently so**: an unauthenticated
request for one answers `404`, which the row reports as "no release has been published
yet". The app sends no token and never will — a token in an APK is a token anyone can
read — so the update check works exactly when the repository is public.

The one input is **allow debug signing**, and it exists so that a debug-signed
release is a deliberate choice rather than a surprise: without a keystore the
workflow stops and tells you why. Use it for a preview build that is not for real
users, never for a version people will install and keep.

## Before you dispatch

- [ ] `versionName`/`versionCode` bumped in `gradle.properties`, in the commit
      being released — and `git status` clean, because the workflow releases the
      commit it checks out.
- [ ] `python tools/build-apks.py` passes locally — nothing else runs before the
      release job's own tests, unless you dispatch `ci.yml` on this commit.
- [ ] Every behaviour this release changes has its reasoning and its measurement in
      the chapter that describes it — a feature exercised only on the emulator is
      written down as emulator-only, never as verified.
- [ ] Anything with a layout change has been through a `uiautomator` pass on a
      device, which is this project's bar for a layout claim.
- [ ] The keystore secrets are set — or you have decided, on purpose, to publish a
      debug-signed build.
- [ ] The tag this will create is the tag the app's update check compares against:
      `v<versionName>` and nothing else. The workflow does that for you, and the
      release it creates is public when the run ends; a release created by hand
      needs the same tag, or the About page's row keeps saying the old version is
      newest.
- [ ] `LICENSE` is the full GPLv3 text and `docs/LICENSING.md` names every
      component the APK carries. See [LICENSING.md](LICENSING.md).
- [ ] No secrets in the diff: `local.properties`, `*.jks`, `*.keystore` and the
      runtime images are all in `.gitignore`, and a stray key in a commit cannot be
      taken back.

## After it publishes

1. **Write the notes.** *Releases → the release → Edit release*. The tag and both
   APKs are already there; the body starts as the generated `Full Changelog` line.
   What belongs there is what changed for someone holding the previous APK — not the
   commit list, which the compare link already has. Nothing has to be pressed for the
   release to be visible: the app's update check has been reading it since the run
   ended, and an edit does not change that. The shape below is the one `v0.2.0`
   established; `gh release edit v0.2.0 --notes-file <file>` is how it was applied.
2. **Install the release APK over the previous release**, not next to it:
   `adb install -r app/build/outputs/apk/arm64/release/app-arm64-release.apk`. This
   is the upgrade path a real user takes, and it is the one thing the build itself
   cannot check — a change to the application id, to the keystore or to the runtime
   revision's comparison shows up here and nowhere else.
3. **Check the assets**: `PiKit-<version>-arm64.apk`, `PiKit-<version>-x64.apk` and
   `SHA256SUMS`, and that the sizes on the release page match the local ones rather than a
   truncated upload.
4. **Confirm the signing**, if it matters: `apksigner verify --print-certs` on the
   published APK.
5. **Update the supported-version notes** if this release changed what the previous
   one could do: `SECURITY.md` and any chapter under `docs/architecture/` that
   describes behaviour this build changed.

### The notes: Chinese, four sections, and the same parts around them every time

[`v0.2.0`](https://github.com/nekooy/PiKit/releases/tag/v0.2.0) is the worked example: the block
below is that body's shape with the version-specific parts blanked out, and the shape is what to
copy rather than the notes themselves — the four headings' contents change every release, and a
sentence about one release is wrong in the next.

```markdown
### 新增功能

- <something a user can do now that the previous APK could not>

### 体验优化

- <a change to something that already existed: how it looks, how long it takes, how big it is>

### 问题修复

- 修复…<what was wrong, and what it does now>

### 其他变更

- <the manual, the build, the runtime image — anything the user does not operate>

### 选哪个文件：

| 设备 | 文件 |
| --- | --- |
| 手机、平板 | `PiKit-<version>-arm64.apk` |
| `x86_64` 模拟器 | `PiKit-<version>-x64.apk` |

此版本和上版本使用同一把签名密钥，且无破坏性变更，可以直接覆盖安装升级。

**Full Changelog**: https://github.com/nekooy/PiKit/compare/v<previous>...v<version>
```

- **An empty section is left out, not written as "无".** A heading with nothing under it
  is a promise the release did not keep, and the four names are fixed so a reader knows
  which one to look in: what is new, what got better, what was broken, and what else
  moved.
- **A bullet is one line**, and it says what changed rather than how. The measurement
  that settled a design belongs in the chapter that describes it, and the commit list is
  behind the compare link.
- **The sentence between the table and the changelog link is the standard upgrade
  claim**, kept as-is unless this release *is* a special case (a new keystore, a
  breaking storage or id change) — then rewrite it to say what the reader has to
  do instead. The claim is about the keystore secrets and about `versionCode`,
  and it has to be true of the two artefacts either side of the link.

