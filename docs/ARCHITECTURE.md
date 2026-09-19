# Architecture, and the constraints that shaped it

This is the map. The reasoning itself lives under [`architecture/`](architecture/),
one file per topic — a chapter that outgrew a single topic is several files sharing
its § number — because most of the shape of this project is forced by two external
systems that do not bend, the Termux package format and Pi's RPC protocol, and a
single 2,700-line document is one nobody reads before changing the thing it
describes.

Chapters 1–6 are those constraints. 7–9 are the interface, where several designs
were tried and rejected and the number that settled each one is recorded. 10–12 are
ownership, vendored code and rendering. [AGENTS.md](../AGENTS.md) has the commands
and the hard rules this document explains; [docs/README.md](README.md) is the index
of everything under `docs/`.

## The chapters

| § | Chapter | What it settles |
| --- | --- | --- |
| 1 | [The prefix cannot move](architecture/01-the-prefix.md) | Why `/data/data/pi.kit.mob/files/usr` is rewritten into every binary in place, and why `targetSdk` stays 28 |
| 2 | [Talking to Pi](architecture/02-talking-to-pi.md) | `--mode rpc` instead of driving the TUI through a PTY, and the exact command line |
| 3 | [What is baked in](architecture/03-what-is-baked-in.md) | What the runtime image carries, why `rg` and `fd` are not optional, and why every version in it — pi's included — is pinned |
| 4 | [`pkg install` without the user noticing](architecture/04-package-relocation.md) | Relocating a package on the tar stream, the two install paths, and the one file the rewrite must not touch |
| 5 | [Storage, and why the first design could delete a phone](architecture/05-storage-and-safety.md) | The three layers — a scoped grant, a refusing delete, a guard inside the agent — and the self-test |
| 6 | [Custom endpoints](architecture/06-custom-endpoints.md) | `models.json` merged and never replaced, the provider list, why the key is in `argv`, and the end-to-end run |
| 6 | [The model page's facts](architecture/06-models-and-catalogue.md) | How a model's settings pick between a merging override and a replacing definition, why the catalogue check is a read, and what a custom id's entry holds |
| 7 | [The composer](architecture/07-composer.md) | The bottom of the screen: the tab strip, the IME insets, and the controls above the input box |
| 7 | [The model and thinking-level switches](architecture/07-models-and-thinking.md) | One provider with many models, where the levels come from, and what is saved |
| 7 | [The transcript](architecture/07-transcript.md) | Following, folding, tool rows, the removed cursor and turn rail, and the page's rhythm |
| 7 | [The terminal's key bar](architecture/07-terminal-keys.md) | Uniform keys, an arrow pad, and the word that toggles |
| 8 | [Restoring a conversation](architecture/08-restoring-a-conversation.md) | How a reopened conversation gets its turns, durations and folding back |
| 9 | [Pages, and which moves are one](architecture/09-pages-and-navigation.md) | Back behaviour, the flattened settings root, sheets, dividers, and forms that ask before leaving |
| 10 | [Process and state ownership](architecture/10-process-and-state.md) | Who owns the agent process, the session and the saved state |
| 11 | [Vendored code](architecture/11-vendored-code.md) | The two Termux modules, why only those two, and their licence |
| 12 | [What the model writes](architecture/12-markdown-and-math.md) | Markdown, mathematics and tables, and the test that could not catch a bug |

## How to read it

**Read the chapter, not the code first.** Every file in `app/src/main/java` that
depends on a non-obvious decision names the chapter in a comment, and several name
the design that was rejected with the measurement that rejected it. Numbers here are
measurements — node bounds from `uiautomator`, window frames, byte counts — rather
than estimates, and they are the reason a rejected design stays written down
instead of being deleted with the code.

**When a design is removed, its reasoning moves here.** It does not get deleted with
the code: the next person to try it should find out why it failed without repeating
the afternoon. That rule, and the rest of the conventions, is in
[AGENTS.md](../AGENTS.md).

**A chapter that outgrows its file gets split, not shortened.** The index above is
the list of chapters; `docs/README.md` explains how the files are organised and
where a new one goes.
