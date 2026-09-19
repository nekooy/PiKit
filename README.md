<p align="center">
  <img src="docs/assets/icon.svg" width="96" height="96" alt="PiKit">
</p>

<h1 align="center">PiKit</h1>

<p align="center"><strong>Upstream pi and Termux, sealed in one APK — a lean Android AI agent.</strong></p>

<p align="center">English | <a href="README.zh-CN.md">简体中文</a></p>

PiKit runs the [pi](https://pi.dev) coding agent on an Android phone with nothing to
install first: a Termux environment, Node.js, `ripgrep`, `fd` and the `pi` CLI are baked
into the APK and unpacked from it on first launch, so the first prompt works offline.
Kotlin and Jetpack Compose over a vendored Termux terminal, and no WebView anywhere.

## Features

- **A simple idea, an elegant native interface.** Kotlin and Jetpack Compose, four tabs,
  no WebView. English, 简体中文 or 日本語.
- **Chat on pi's RPC protocol.** A finished turn folds to one line
  (`Worked 47s · 5 steps`) and reopens on a tap; thinking level, model, context use and
  cache hits are chips above the input, and both switches apply without restarting the
  agent. The app adds `!command`, `/new`, `/compact` and `/stop`, and nothing else.
- **A terminal and a file browser.** PTY sessions that survive a tab switch, the keys a
  phone keyboard lacks, and a read-only view of `$HOME`.
- **Providers on pi's terms.** The 32 providers pi's own key table knows, plus a custom
  endpoint; a model's context window, output limit, thinking levels and image support
  come from pi's catalogue.
- **Storage is scoped and safe.** The grant starts empty and is opened one folder at a
  time; a delete cannot leave the app's own data; and a **guard extension** in the agent
  refuses a recursive delete outside `$HOME/workspace` and any rewrite of pi's own files.
  No account, no telemetry.

## Install

Download from the [releases page](https://github.com/nekooy/PiKit/releases/latest) — one
APK per CPU architecture, about 107 MB, and no Play Store listing by design.

| Device | File |
| --- | --- |
| phone, tablet | `PiKit-<version>-arm64.apk` |
| `x86_64` emulator | `PiKit-<version>-x64.apk` |

`adb shell getprop ro.product.cpu.abi` says which is yours, and the wrong one is
harmless: the app reports that the build carries no image for the device instead of
starting. Verify with `sha256sum -c SHA256SUMS`, install by tapping the file or with
`adb install PiKit-<version>-arm64.apk`, and give the first launch a minute — it unpacks the
runtime (~110 MB), then asks about notifications and "all files access" (needed only for
**Settings → Shared storage**). **Settings → Model & provider** takes an API key, or
`pi /login` in the Terminal tab for a subscription. Updating is manual too: **About PiKit
→ Check for updates** asks GitHub when you tap it, and a newer APK installs over the old
one with your data intact.

## Build

Android 8.0+ on `arm64-v8a` or `x86_64`. JDK 17–23, the Android SDK with NDK r29,
Python 3.10+ with `zstandard` and Node.js; [docs/BUILDING.md](docs/BUILDING.md) has the
whole story.

```bash
python tools/build-apks.py    # checks, tests, then all four APKs
```

The runtime images are generated rather than committed (~285 MB per ABI), so that
command assembles a missing or stale one on the way.

## Documentation

[docs/README.md](docs/README.md) indexes every document. These are the ones a reader
usually wants:

| Document | What it is |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | The map of the design decisions and the constraints behind them, chapter by chapter |
| [BUILDING.md](docs/BUILDING.md) | Prerequisites, the image builder, artifacts, signing, working on a device |
| [VERIFICATION.md](docs/VERIFICATION.md) | What has actually been exercised, on which emulator and which phone, and what has not |
| [RELEASING.md](docs/RELEASING.md) | The version, the tag, the workflows, and the signing that has to happen first |
| [MAINTAINING.md](docs/MAINTAINING.md) | The recurring chores: upstream pi, packages, dependencies, the device pass |
| [LICENSING.md](docs/LICENSING.md) | Why GPLv3, what the APK carries, and where the credits are named |
| [AGENTS.md](AGENTS.md) | Commands, hard constraints and commit conventions for a change to this repository |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to propose a change, and where the rules that matter live |
| [SECURITY.md](SECURITY.md) | How to report a vulnerability, what is in scope, and what goes upstream instead |

## License

**GNU GPLv3** — [LICENSE](LICENSE), and the reasoning in
[docs/LICENSING.md](docs/LICENSING.md): the APK distributes the Termux environment and
its terminal emulator, both GPLv3, so a distributed build has to make the corresponding
source available, `tools/` included. The pi agent itself is MIT.

## Acknowledgements

PiKit is mostly other people's work. The three it is built on:

| Component | Licence | From |
| --- | --- | --- |
| Termux environment and terminal | GPLv3 | [termux/termux-packages](https://github.com/termux/termux-packages), [termux/termux-app](https://github.com/termux/termux-app) |
| pi, the agent (`@earendil-works/pi-coding-agent`) | MIT | [pi.dev](https://pi.dev) |
| `pi-web-access` by Nico Bailon (search, page reading, PDF extraction) | MIT | [nicobailon/pi-web-access](https://github.com/nicobailon/pi-web-access) |

Everything else — Node.js and the bundled command-line tools, the Android libraries, the
formula renderer, the marks — is listed with its licence in
[docs/LICENSING.md](docs/LICENSING.md) and in the app under **Settings → About PiKit →
Credits**.
