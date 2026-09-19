# Licensing

PiKit — a standalone Android app that bundles the Termux environment and the pi
coding agent.
Copyright (C) 2026 PiKit contributors.

PiKit is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License, version 3 or later**, as published by the Free
Software Foundation. It is distributed in the hope that it will be useful, but
**without any warranty**; without even the implied warranty of merchantability or
fitness for a particular purpose. The full text is in [LICENSE](../LICENSE), and
<https://www.gnu.org/licenses/gpl-3.0.txt> is the canonical copy of it.

## Why GPLv3

Because of what the APK carries, not because of a preference. PiKit distributes the
following in the same work, and they are GPLv3:

- **The Termux Linux environment** and its bootstrap packages
  ([termux/termux-packages](https://github.com/termux/termux-packages)) — GPLv3.
- **The Termux terminal emulator**, vendored under `terminal-emulator/` and
  `terminal-view/` ([termux/termux-app](https://github.com/termux/termux-app)) —
  GPLv3. [ARCHITECTURE §11](architecture/11-vendored-code.md) says why exactly those
  two modules and no others.
- **The runtime image's userland** — Node.js, npm, ripgrep, fd and their
  dependencies, each under its own licence; several of them (notably the GNU
  userland and bash) are GPLv3.

GPLv3 is not the only licence in the APK. What is MIT, and what each component is,
is listed below and written into the app itself under **Settings → About PiKit →
Credits**, next to the build step that vendors it ([ARCHITECTURE
§3](architecture/03-what-is-baked-in.md)).

## What a distributed build must do

1. **Ship the licence.** The full GPLv3 text travels with the build; the About page
   names the components, and this file is the record of why.
2. **Make the corresponding source available.** That is *this repository*, including
   the scripts under `tools/` that produce the bundled runtime image
   (`build-runtime-image.py`, `prefix_patch.py`, `pikit-relocate.js`,
   `pikit-dpkg.sh`, `pi-safety-guard.ts`, `storage-self-test.sh`) — and, because the
   image is built from upstream sources rather than committed, the versions the
   image was built from, which `tools/build-runtime-image.py` pins (`BOOTSTRAP_TAG`,
   the `pi` version, the web-access version) and the image's `build-metadata.json`
   records.
3. **Say what changed.** Nothing in the Termux packages or the vendored terminal
   modules is modified beyond the prefix rewrite that relocates them to
   `/data/data/pi.kit.mob/files/usr`; that rewrite and every other edit the builder
   makes are in the scripts above, not applied silently to a tarball.

## Components, and their licences

| Component | Licence | Where it comes from |
| --- | --- | --- |
| Termux environment, bootstrap and packages | GPLv3 | [termux/termux-packages](https://github.com/termux/termux-packages) |
| `terminal-emulator`, `terminal-view` (vendored) | GPLv3 | [termux/termux-app](https://github.com/termux/termux-app) |
| `@earendil-works/pi-coding-agent` (the agent) | MIT | [earendil-works/pi](https://github.com/earendil-works/pi) |
| `pi-web-access` | MIT | [nicobailon/pi-web-access](https://github.com/nicobailon/pi-web-access) |
| Node.js | MIT | [nodejs.org](https://nodejs.org) |
| ripgrep | MIT / Unlicense | [BurntSushi/ripgrep](https://github.com/BurntSushi/ripgrep) |
| fd | MIT / Apache-2.0 | [sharkdp/fd](https://github.com/sharkdp/fd) |
| AndroidX, Jetpack Compose, Material 3, Kotlin, kotlinx.serialization, Okio | Apache-2.0 | [developer.android.com/jetpack](https://developer.android.com/jetpack) |
| `ru.noties:jlatexmath-android` (formula typesetting) | GPL-2.0, over upstream JLaTeXMath's GPL-2.0 **with a linking exception** | [noties/jlatexmath-android](https://github.com/noties/jlatexmath-android), [opencollab/jlatexmath](https://github.com/opencollab/jlatexmath) |
| Lucide (the model, thinking, command and check marks) | ISC | [lucide.dev](https://lucide.dev) |

The web-access extension resolves a further set of MIT/Apache-licensed packages
(`turndown`, `@mozilla/readability`, `defuddle`, `linkedom`, `unpdf`, `undici`,
`p-limit`, `typebox`); they are named in the app's credits for the same reason as
the rest, because the list is what the licence requires.
