# Verification status

What has actually been exercised, and how. This is the map: the evidence itself lives
under [`verification/`](verification/), one file per topic, and the reasoning behind
every design is in [ARCHITECTURE.md](ARCHITECTURE.md).

Every figure here is a measurement — node bounds from `uiautomator`, frame durations,
byte counts — rather than an impression, and each file names the build and the device
that produced it. A run on the emulator and a run on a phone are not the same claim,
and both are written down as what they are.

**These files are cited by name, not by number.** The design record is addressed as
`ARCHITECTURE §12.2` because a code comment cannot hold a working link; the evidence
record is read and cited from prose, so a topic file's own name is its address, and a
file that gains a section never renumbers its neighbours.

## The files

| Chapter | What it settles |
| --- | --- |
| [On a build machine](verification/build-machine.md) | The unit suites and every checker that needs no device: the runtime images, package relocation, the agent guard, signing, both workflows and the published release |
| [On the emulator](verification/emulator.md) | The graphics backend the emulator needs, first launch and permissions, storage, `pkg install`, and the interface measured with `uiautomator` |
| [On a physical device](verification/device.md) | The `arm64-v8a` phone pass: the terminal, the runtime, and coexistence with the official Termux |
| [Markdown and formulas](verification/markdown-and-formulas.md) | What the parser and the renderer draw, the delimiter rule, and the constructs that are rewritten or given up on |
| [The formula renderer](verification/formula-renderer.md) | What the renderer cost, which one replaced it, and the line box the swap required |
| [The transcript's frame cost](verification/transcript-performance.md) | Why opening a long reply is slow, every attempt to fix it, and the number or the crash that settled each |
| [Marks and colours](verification/marks-and-colours.md) | The borrowed glyphs, the copy mark, the colour the rows converged on, and an icon cache that is not a build |
| [The release build's dex](verification/release-build.md) | What R8 removes from the release APK that the debug one keeps, and the checker that now fails the build over it |
| [Known gaps](verification/known-gaps.md) | What has not been verified, what needs a person rather than an instrument, and what is accepted rather than fixed |

## How to read it

**A claim's status is the file that covers its topic, not this page.** The files are one
topic each, the way [`docs/architecture/`](architecture/) is organised, and a file
accumulates its topic's evidence: a design that was tried and failed is kept beside the
one that replaced it, with the number that settled the choice, and is never deleted to
make the file shorter.

**Where a claim was measured is part of the claim.** Node bounds, a `logcat` line and a
frame duration can be re-measured; "it looks right" cannot. A feature verified only on
the emulator is written down as emulator-only, and one that needs the phone's network,
its shared storage or its `arm64-v8a` translation is in
[On a physical device](verification/device.md).

**What is not verified is written down too.** [Known gaps](verification/known-gaps.md)
is the list of gaps, of checks that need a person rather than an instrument, and of the
few limitations that are accepted rather than fixed.
