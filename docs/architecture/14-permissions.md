# 14. Permissions: three tiers, checked on every tool call

*[Architecture](../ARCHITECTURE.md) §14.*

This chapter replaced a single on/off switch. §5 tells the story of the storage grant and
the delete boundary; this is the policy that stands on top of both, and it is the one the
reader meets in the composer.

## The problem with one switch

§5's third layer was PiKit's own extension, `tools/pi-safety-guard.ts`: 1005 lines of rules
about recursive deletes, raw block devices, pi's credential files and the shared-storage
grants, with one switch on the storage page that took the whole thing out of force
(`PIKIT_SAFETY_GUARD=off`). It was written against the failure that had actually happened
— a recursive delete reaching the user's files — and it accumulated a rule for every
version of that failure. Two things were wrong with the result, and neither was a bug in a
rule:

- **It answered a three-answer question with two.** "Let me look at this project" is not
  "let me build it", and neither is "do whatever you want". The middle answer — the one a
  coding agent exists for — was the app's only mode, and a reader who wanted to *review*
  code could not say so, so the guard refused things a reviewer never asked for, and a
  reader who wanted a package installed had to turn the whole policy off.
- **Its rules were PiKit's invention.** The model has no idea what `pikit-safety-guard`
  means, so every refusal was a surprise, and `AGENTS.md` had to explain the rules in
  prose beside the code — two copies of one policy, which drift (§5 records the same
  argument for the storage page).

The replacement is three tiers taken from the DeepSeek Harness, the coding agent this app
is measured against. The names are that harness's own, because the model is trained around
them: an agent told `read-only` reports what it would have changed instead of trying, and
an agent told `full-access` stops asking permission for `npm install`.

| Tier | Reads | Writes | What the composer says |
| --- | --- | --- | --- |
| `read-only` | anywhere | nothing | 仅可查看 · `只读：能查看文件，不能修改，也不执行会改动文件的命令` |
| `workspace-write` (default) | anywhere | inside the workspace, and inside the shared-storage folders the user granted | 工作区内读写 · `只能修改工作区和你授权的共享存储文件夹` |
| `full-access` | anywhere | anywhere | 完全访问 · `不做任何拦截，Agent 可以改动它有权改动的任何文件` |

## Where it is enforced, and why there

**Not in this app.** A tool call is the only place the agent's actions pass through, and
the app is not in that path: pi runs as a child process, and the app sees its records after
the fact. **Not in pi either** — pi has no permission system by design and its own
documentation says so (*"No permission popups. Run in a container, or build your own
confirmation flow with extensions."*). On a phone there is no container. So the policy is
an extension, registered on pi's `tool_call` hook, which fires before a tool runs and whose
return value blocks the call:

- `tools/pikit-permissions.ts` — one file, staged by the image builder at
  `share/pikit/pikit-permissions.ts` and copied by `BootstrapInstaller` into
  `$HOME/.pi/agent/extensions`, where pi discovers it.
- A refusal is a paragraph the model can act on: the tier, the path or command that was
  refused, and what would allow it. The model reports the refusal instead of retrying,
  which is what makes the policy usable rather than mysterious.

**The tier is a file, not an environment variable.** `$HOME/.pikit/permission.json`,
written by `env/PermissionPolicy` and read by the extension **on every tool call**. The
reason is the reader's: a mode that takes effect on the next tool call can be changed in
the middle of a conversation, and that is exactly what the composer's control is for. An
environment variable is read once, when pi loads an extension — the storage policy still
works that way, and it costs an agent restart every time it moves (§5). The cost of the
file is a `readFileSync` of a few hundred bytes per tool call, which is the trade this
design made deliberately.

Three properties follow from the file's location, and each one is a decision:

- **`$HOME/.pikit` is outside the workspace**, so under `workspace-write` the agent cannot
  rewrite the file that judges it. `full-access` can — that is what the tier means.
- **`$HOME` is outside the workspace too**, which is what keeps pi's credentials,
  `settings.json`, `models.json` and every saved conversation out of reach of a middle-tier
  write. The old guard needed a hand-written list of protected paths for this; the path
  rule produces it for free.
- **`$PREFIX` is outside the workspace**, so `pkg install` is refused under
  `workspace-write`. That is deliberate: installing a package is a change to the user's
  runtime rather than to their project, and the honest answer is to ask for `full-access`.
  `AGENTS.md` says so to the model (§ below), and the alternative — naming `$PREFIX` as a
  writable root — was rejected because it would also allow an agent to rewrite the
  runtime's own files from the middle tier.

## What each tier actually refuses

`workspace-write` and `read-only` share one classifier and differ in where the line is:

- **Tool calls that carry a path** (`write`, `edit`): refused when the resolved path is
  outside the writable roots (`workspace-write`), and always refused in `read-only`.
- **`bash`**: the command line is split at the separators that are not inside quotes, so
  `git commit -m 'fix; rm -rf /sdcard case'` is one command and not two; `sh -c '…'`,
  `eval '…'` and a heredoc fed to a shell are inspected as the command they are; `~`,
  `$HOME`, `${HOME}` and `$PREFIX` are expanded except inside single quotes. A segment that
  mutates is refused when a path it names is outside the writable roots. The `~/storage`
  link farm is translated back to the `/sdcard` path it points at (`PIKIT_STORAGE_LINKS`),
  because that is the spelling PiKit itself hands the agent.
- **`read-only` is a whitelist, not a blacklist.** A blacklist of "commands that write" is
  a losing game — `awk` writes, `sed`'s `w` command writes, `jq` can write — so the tier
  allows a curated list of inspection commands (`cat`, `ls`, `rg`, `git log`, `git diff`,
  `sed` without `-i`, `find` without `-delete`/`-exec`, …) and refuses anything it does not
  recognise, redirects to anything but `/dev/null` included. A reviewer's commands are in
  that list; a builder's are not.

What it deliberately does **not** do is read the filesystem: like the guard it replaced, it
matches paths in a command's *text*, so a path built at runtime or hidden in base64 is not
caught. It is a rule the agent is held to, not a sandbox — the same bar §5 states for the
storage switches, and the reason the interface says "checks every tool call" rather than
"prevents".

## What this cost, stated plainly

**Reads are free in every tier, so the storage page's switches no longer limit what the
agent may look at.** The old guard refused a command naming a folder the user had not
granted; that rule is gone. What the switches still decide is what exists under `~/storage`
and what is *writable* — `workspace-write` may write in a folder that is on, refuses one
that is off, and `full-access` ignores the list for writes. The page's sentences were
rewritten with the change (they promise writes, not access), because a page that described
the old policy would be the same lie in the other direction. The trade is the one the tier
model makes: a reader who wants a fence around their photos wants `read-only` or a
`full-access` install they trust, not a per-folder read rule.

**The old extension's file list is gone with it.** Pi's own files are protected by being
outside the workspace rather than by a list of spellings, which is fewer rules and fewer
places to forget one.

## One control, between the two that add and the two that decide

The tier is chosen in the composer, under the input box, between the command list and the
`+` — one 40dp button whose mark *is* the tier: an eye (`Show`) for `read-only`, a lock for
`workspace-write`, an open lock for `full-access`, and only the last of the three changes
colour (the accent), because it is the one with nothing behind it. Tapping it opens a menu
of the three, each with the sentence the table above quotes. The thinking-level and model
menus are the same shape, so the three read as the composer's controls.

Why there, and not as a fourth chip in the row above or a row on the settings page:

- **Not a chip.** That row is the settings the next *message* goes with — the level, the
  model, the cache report — and a pill carrying `工作区内读写` would be a sentence in a row
  that only fits three short values (§7.1 measured that row against a 1080px screen).
- **Not the settings page.** A reader changes the tier while reading a reply that is about
  to do something, which is where the composer is; a page three taps away is a page
  nobody finds at the moment it matters.

The workspace chip beside the model chip is §7.1's, and the two are different questions: the
workspace is *where* the agent works, the tier is *how far* it may go.

## Migration: two policies must never judge one call

An install that upgraded carries `pi-safety-guard.ts` in `$HOME/.pi/agent/extensions`, and
pi loads every module it finds there. If it stayed, every call would be judged twice — by
the old guard's rules and by the tier — and the stricter answer would win with no switch
anywhere that turns it off. So `BootstrapInstaller.installPermissionExtension` **deletes**
the retired file before it writes the new one, and that deletion is not tidiness.

The default is `workspace-write`, and a missing or unreadable permission file means that
tier — never "off". The rule the old switch carried is kept: a missing variable must never
be what takes a policy out of force.

`AGENTS.md` is the document half (`env/AgentContext`). It describes the three tiers, the
workspace, and the fact that an install needs `full-access`; it also carries a generation
marker, because the previous default described the guard's rules in prose (it told the model
a single-file delete outside the workspace was allowed, which `workspace-write` now
refuses), and a stale default teaches the wrong rules.

## The rules are tested, not trusted

`tools/test-permissions.mjs` runs the same `checkToolCall()` the extension runs, over a
table of commands and tool calls from both sides of every line the tiers draw: ordinary work
inside the workspace, a write outside it, the same call under `full-access`, `read-only`
refusing `write`/`edit` and mutating `bash` while allowing inspection commands, a missing
file meaning `workspace-write`, and a path under `~/storage/downloads` judged as the
`/sdcard/Download` path it is. **120 cases**, and it is the `checks` entry `build-apks.py`
runs before anything is packaged.

The number that matters is not the case count but the shape of the table: every case that is
*allowed* is a command a coding agent genuinely needs on a phone. A policy that blocks
legitimate work gets switched to `full-access` and never switched back, and a tier nobody
uses protects nothing.

## What was measured on the emulator

Four facts, each one a thing that would have failed silently:

- **The migration ran.** After installing a build over one that carried the guard,
  `$HOME/.pi/agent/extensions` holds `pikit-permissions.ts` and **not**
  `pi-safety-guard.ts` — `run-as pi.kit.mob ls files/home/.pi/agent/extensions` returns one
  name.
- **The control writes the file the extension reads.** Tapping 完全访问 in the composer's
  menu changed `$HOME/.pikit/permission.json` from `{"mode":"workspace-write"}` to
  `{"mode":"full-access"}` with the agent still running, and the control's mark changed from
  a lock to an open lock in the accent — no restart, which is the whole point of the file.
- **A refusal is visible where it was asked for.** The workspace picker is a window of its
  own, and the first version answered a refused folder with the app's snackbar: the sheet
  stayed open, the host is in the activity's window *underneath*, and the tap looked like it
  had done nothing at all. The refusal is a line in the sheet's own body now —
  `不能把主目录本身当作工作区` under the path it refused, and the folder it *did* accept closes the
  sheet.
- **The tier survives a relaunch**, because it is a preference and the file is rewritten from
  it before every spawn: `workspace-write` was still in force after the app was killed and
  started again.
