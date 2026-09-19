# PiKit's documentation

Every document in this repository, and the rule for adding one.

## The documents

| File | What it is | Open it when |
| --- | --- | --- |
| [../README.md](../README.md) | What PiKit is: the pitch, the features, how to build it | First contact |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The map of the design decisions and the constraints behind them | Before changing anything whose shape looks arbitrary |
| [architecture/](architecture/) | The chapters themselves, one file per topic | When a comment in the code cites a chapter |
| [BUILDING.md](BUILDING.md) | Prerequisites, the image builder, artifacts, signing, working on a device | Before the first build, on a new machine |
| [VERIFICATION.md](VERIFICATION.md) | What has actually been exercised, on which emulator and which phone, and what has not | Before claiming something works |
| [RELEASING.md](RELEASING.md) | The version, the tag, the workflows, and the signing that has to happen first | When publishing a build |
| [MAINTAINING.md](MAINTAINING.md) | The recurring chores: upstream pi, packages, dependencies, the device pass | Periodically, and after an upstream release |
| [LICENSING.md](LICENSING.md) | Why GPLv3, what the APK carries, and where the credits are named | Before distributing anything |
| [assets/icon.svg](assets/icon.svg) | The launcher icon, as the README shows it — **generated** | Never by hand |
| [../AGENTS.md](../AGENTS.md) | Commands, hard constraints and commit conventions for a change to this repository | Before writing code |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | How to propose a change, and where the rules that matter live | Before opening a pull request |
| [../SECURITY.md](../SECURITY.md) | How to report a vulnerability, what is in scope, and what goes upstream | Before reporting one |

`AGENTS.md` sits at the repository root rather than here because an agent looks for
it there.

## How the tree is organised

**One topic per file.** A reader who wants to know why the runtime image is rebuilt
should open one file and read it, not search a 2,700-line document for the section
that mentions it. `ARCHITECTURE.md` is a map with a table of chapters for exactly
that reason: it is the only architecture file whose job is to be short.

**A file that outgrows its topic gets split, not trimmed.** Around 25 KB — roughly
400 lines — is the point to look at it again; the fix is a new file per topic with a
row added to this index and to `ARCHITECTURE.md`'s table, not a shorter version of
the same reasoning. Nothing is deleted to make a file smaller: the measurement that
rejected a design is the most valuable part of it.

**The index is updated in the same change as the file.** A document nobody links to
is a document nobody finds; a link to a file that no longer exists is worse, and
both are checked by reading, since nothing in the build parses Markdown.

**Generated files say so.** The first comment names the generator and the command
that reproduces it (`docs/assets/icon.svg` and `tools/render-icon.py` are the
pattern), and a checker under `tools/` — listed in `tools/build-apks.py`'s `checks`
— fails the build when the file and its source have drifted apart. Nothing else in
`docs/` is generated, and a hand-written file never claims to be.

**Architecture chapters keep their § number.** A comment in the code says
"ARCHITECTURE §4", not a file name, because chapters get split and reordered; §4 is
`04-package-relocation.md` and the table in `ARCHITECTURE.md` is what resolves the
number. §6 is two files and §7 is four for the same reason — a chapter that grew
past one topic keeps its number and gains a part.

**Prose is English, the interface is not.** Documentation, comments and commit
messages are written in English. Anything the user reads goes through
`app/src/main/java/pi/kit/mob/locales/` instead — see `AGENTS.md`.
