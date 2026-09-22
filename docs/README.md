# PiKit's documentation

Every document in this repository, and the rule for adding one.

## The documents

| File | What it is | Open it when |
| --- | --- | --- |
| [../README.md](../README.md) | What PiKit is: the pitch, the features, how to build it | First contact |
| [../README.zh-CN.md](../README.zh-CN.md) | The same README in 简体中文; the two are mirrors | After changing what the project says about itself |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The map of the design decisions and the constraints behind them | Before changing anything whose shape looks arbitrary |
| [architecture/](architecture/) | The chapters themselves, one file per topic | When a comment in the code cites a chapter |
| [BUILDING.md](BUILDING.md) | Prerequisites, the image builder, artifacts, signing, working on a device | Before the first build, on a new machine |
| [RELEASING.md](RELEASING.md) | The version, the tag, the workflows, and the signing that has to happen first | When publishing a build |
| [MAINTAINING.md](MAINTAINING.md) | The recurring chores: upstream pi, packages, dependencies, the device pass | Periodically, and after an upstream release |
| [LICENSING.md](LICENSING.md) | Why GPLv3, what the APK carries, and where the credits are named | Before distributing anything |
| [assets/icon.svg](assets/icon.svg) | The launcher icon, as the README shows it — **generated** | Never by hand |
| [assets/preview-0.2.0.webp](assets/preview-0.2.0.webp) | The four tabs, as the README shows them — a screenshot of the running app, compressed and not generated | When the interface it shows changes |
| [../AGENTS.md](../AGENTS.md) | Commands, hard constraints and commit conventions for a change to this repository | Before writing code |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | How to propose a change, and where the rules that matter live | Before opening a pull request |
| [../SECURITY.md](../SECURITY.md) | How to report a vulnerability, what is in scope, and what goes upstream | Before reporting one |

`AGENTS.md` sits at the repository root rather than here because an agent looks for
it there.

## How the tree is organised

**One topic per file.** A reader who wants to know why the runtime image is rebuilt
should open one file and read it, not search a 2,700-line document for the section
that mentions it. `ARCHITECTURE.md` is a map with a table of chapters for exactly
that reason, and each chapter is one topic: the measurement that settled a design and
the design it rejected live together in the chapter that describes the behaviour.

**A file that outgrows its topic gets split, not trimmed.** Around 25 KB — roughly
400 lines — is the point to look at it again; the fix is a new file per topic with a
row added to this index and to `ARCHITECTURE.md`'s table, not a shorter version of
the same reasoning. Nothing is deleted to make a file smaller: the measurement that
rejected a design is the most valuable part of it.

**The index is updated in the same change as the file.** A document nobody links to
is a document nobody finds; a link to a file that no longer exists is worse, and
both are found by reading, since nothing in the build parses Markdown.

**Generated files say so.** The first comment names the generator and the command
that reproduces it (`docs/assets/icon.svg` and `tools/render-icon.py` are the
pattern), and a checker under `tools/` — listed in `tools/build-apks.py`'s `checks`
— fails the build when the file and its source have drifted apart. Nothing else in
`docs/` is generated, and a hand-written file never claims to be.

**Architecture chapters are addressed by § number, and a part is part of it.** A comment
in the code says "ARCHITECTURE §4", not a file name, because files get split and reordered;
§4 is `04-package-relocation.md` and the table in `ARCHITECTURE.md` is what resolves the
number. A chapter that grew past one topic has numbered parts — `§6.1`–`§6.2`,
`§7.1`–`§7.4`, `§12.1`–`§12.3` — and the part number is in the file name, so **every §
resolves to exactly one file**: a citation without a part is never written.

**A measurement lives with the design it settled, not in a separate record.** Node bounds
from `uiautomator`, a `logcat` line, a frame duration and a byte count are the reasoning:
they say why something is shaped the way it is and what the alternative cost. Each chapter
therefore carries its own evidence, in place and undated, and there is no second tree of
topics to keep in step with it.

**Prose states the problem, not who reported it.** A section is not dated, a bug report
is not quoted as the subject of a sentence, and "the reader reported X" is written as
what was observed: "a two-character prompt drew an 86%-wide bar". A measurement, a device,
a log line and an interface string are evidence; the wording of a report is not — but what
the report *established* is kept with the number that settled it, and that is the same rule
as never deleting a rejected design.

**Prose is English, the interface is not.** Documentation, comments and commit
messages are written in English. Anything the user reads goes through
`app/src/main/java/pi/kit/mob/locales/` instead — see `AGENTS.md`.
