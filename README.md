<p align="center">
  <img src="docs/assets/icon.svg" width="96" height="96" alt="PiKit">
</p>

<h1 align="center">PiKit</h1>

<p align="center"><strong>A clean, out-of-the-box Android AI agent built on pi and Termux.</strong></p>

<p align="center">English | <a href="README.zh-CN.md">简体中文</a></p>

PiKit runs the [pi](https://pi.dev) coding agent on an Android phone. The Termux
environment, Node.js and the `pi` CLI are baked into the APK and unpacked on first
launch — no repository to reach, no package to fetch. The only connection needed is one
that can reach the model provider.

<p align="center">
  <img src="docs/assets/preview-0.2.0.webp" width="90%" alt="PiKit 0.2.0 — the Chat, Terminal, Files and Settings tabs">
</p>

<p align="center"><sub>PiKit 0.2.0 interface preview</sub></p>

## Features

- **A simple idea, an elegant native interface.** Kotlin and Jetpack Compose, four tabs,
  no WebView. English, 简体中文 or 日本語.
- **Chat on pi's RPC protocol.** A finished turn folds to one line
  (`Worked 47s · 5 steps`) and reopens on a tap; thinking level, model, context use and
  cache hits are chips above the input, and both switches apply without restarting the
  agent. Beyond that it adds `!command` and seven slash commands — `/new`, `/compact`,
  `/stop`, `/clone`, `/export`, `/model`, `/clear` — and nothing else.
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

<p align="center">Click the icon below to download</p>

<p align="center">
  <a href="https://github.com/nekooy/PiKit/releases/latest/download/app-arm64-release.apk">
    <img src="https://img.shields.io/badge/Download%20APK-arm64-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
  </a>
</p>

<p align="center"><sub>Phones and tablets · about 107 MB</sub></p>

First launch unpacks the runtime (a few seconds). Give it the API key under
**Settings → Model & provider**, or `pi /login` in the Terminal tab for a subscription.
Updates install over the old APK with your data intact — **About PiKit → Check for
updates** when you want one. Other builds and checksums live on the
[releases page](https://github.com/nekooy/PiKit/releases/latest); `adb shell getprop
ro.product.cpu.abi` tells you which file is yours.

## Build

Android 8.0+ on `arm64-v8a` or `x86_64`. JDK 17–23, the Android SDK with NDK r29,
Python 3.10+ with `zstandard` and Node.js; [docs/BUILDING.md](docs/BUILDING.md) has the
whole story.

```bash
python tools/build-apks.py    # checks, tests, then all four APKs
```

The runtime images are generated rather than committed (~100 MB of archives per ABI,
~285 MB unpacked), so that command assembles a missing or stale one on the way.

## Documentation

[docs/README.md](docs/README.md) indexes every document. These are the ones usually wanted
first:

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — the design decisions and the constraints behind them.
- [BUILDING.md](docs/BUILDING.md) — prerequisites, the image builder, artifacts, signing, the device workflow.
- [RELEASING.md](docs/RELEASING.md) — the version, the tag, and the signing that comes first.
- [MAINTAINING.md](docs/MAINTAINING.md) — the recurring chores: upstream pi, packages, dependencies.
- [LICENSING.md](docs/LICENSING.md) — why GPLv3, what the APK carries, where the credits are.
- [AGENTS.md](AGENTS.md) — commands, hard constraints and commit conventions.
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to propose a change, and where the rules live.
- [SECURITY.md](SECURITY.md) — how to report a vulnerability, and what is in scope.

## License

**GNU GPLv3** — [LICENSE](LICENSE), and the reasoning in
[docs/LICENSING.md](docs/LICENSING.md): the APK distributes the Termux environment and
its terminal emulator, both GPLv3, so a distributed build has to make the corresponding
source available, `tools/` included. The pi agent itself is MIT.

## Acknowledgements

PiKit is mostly other people's work. The four it leans on:

- **Termux environment and terminal** — GPLv3, from
  [termux/termux-packages](https://github.com/termux/termux-packages) and
  [termux/termux-app](https://github.com/termux/termux-app).
- **pi, the agent** (`@earendil-works/pi-coding-agent`) — MIT, [pi.dev](https://pi.dev).
- **`pi-web-access`** by Nico Bailon (search, page reading, PDF extraction) — MIT,
  [nicobailon/pi-web-access](https://github.com/nicobailon/pi-web-access).
- **Everything else** — Node.js and the bundled command-line tools, the Android libraries,
  the formula renderer, the marks — is listed with its licence in
  [docs/LICENSING.md](docs/LICENSING.md) and in the app under **Settings → About PiKit →
  Credits**.