# 5. Storage, and why the first design could delete a phone

*[Architecture](../ARCHITECTURE.md) §5.*

The first version of this app had two storage states: "none" and "all files
access". The second linked the whole of `/sdcard` into the environment as
`~/storage/*` the moment the user granted Android's permission, and the agent ran
with `--approve` and no permission popups of any kind. An autonomous process with
recursive-delete capability therefore had every photo, document and backup on the
device in reach as an ordinary directory inside its home folder. That is a design
that can remove a phone's files, and it did. Three independent layers replace it.
They are independent on purpose: each one alone has a hole, and the failure modes
are not the same.

## Layer 1 — the grant is scoped, not binary

`StorageAccess.Policy` is a set of `Root`s (`shared`, `downloads`, `documents`,
`pictures`, `dcim`, `music`, `movies`). The default, including for an install that
upgrades from a build which had no policy at all, is **empty**: `~/storage` is not
created and the environment cannot see shared storage. The user opts into
individual trees from Settings → Shared storage, and the broad ones ask for
confirmation first. An empty policy *removes* the link farm rather than merely
declining to create it, so revoking takes effect immediately; revoking never
deletes anything: a link is removed by path and the directory it pointed at is
untouched.

The link names are the lower-case ones `termux-setup-storage` creates
(`~/storage/dcim`, `~/storage/downloads`), because scripts type them and matching
upstream matters more than matching the file manager. The *settings row* therefore
shows the real directory instead — `/sdcard/DCIM`, `/sdcard/Download` — since a
page that showed only `~/storage/dcim` would never contain the spelling the user
recognises. `StorageAccess.displayTargetOf` is the one place that decides this,
and it falls back to the link path when the external directory cannot be read.

## Layer 2 — the app cannot recursively delete outside its own data directory

`env/SafeDelete.kt` is the only place in the app allowed to remove a directory
tree, and it refuses unless all of the following hold:

- the tree is inside `filesDir` — so `/sdcard`, `/storage`, `/mnt` and `/` are
  refused whatever path is passed;
- the tree is not `filesDir` itself, nor one of Android's own subdirectories
  (`cache`, `shared_prefs`, `databases`, …);
- **symlinks are removed as links, never followed.** This is the one that matters
  for `~/storage`: `File.deleteRecursively()` decides what a child is from
  `isDirectory()`, which follows the link, so a link into shared storage would be
  walked *through* and the target's contents deleted.

The three call sites are the staging tree, the prefix, and the link farm; all
three used to call `deleteRecursively()` directly. `PrefixPatcher` — which walks a
tree and *writes* to every file it finds — carries the same boundary check.

## Layer 3 — the agent is refused the calls that destroy data

Pi has no permission system by design, and its documentation says so: *"No
permission popups. Run in a container, or build your own confirmation flow with
extensions."* On a phone there is no container, so this app installs one:
`tools/pi-safety-guard.ts`, copied into `$HOME/.pi/agent/extensions` at first run
and on every runtime update. It subscribes to `tool_call`, which pi fires before
a tool runs and whose return value blocks the call, and refuses:

- a recursive delete (`rm -rf`, `rm -r`, `find -delete`, `find -exec rm`) aimed
  anywhere outside the agent's workspace `$HOME/workspace` — including `$HOME`
  itself, `$HOME/tmp`, the `~/storage` links and every storage root;
- `mkfs`, `fdisk`, `wipefs`, `parted` and friends;
- `dd of=/dev/block/…` and a redirect onto a raw device;
- a recursive `chmod`/`chown` on a storage root;
- `shred` and `wipe`;
- **a command that names shared storage outside the folders the user switched on**,
  in the `/sdcard` spellings *and* through the `~/storage` links (§ the folder
  switches, below);
- **anything that deletes or rewrites pi's own files** — the guard itself,
  `auth.json`, `settings.json`, `models.json`, `web-search.json`, `AGENTS.md`, the
  saved sessions, and the app's `pikit-config.json` — by any of `rm`, `mv`, `>`,
  `truncate`, `sed -i` or `chmod`, or by a `write`/`edit` tool call naming one (those
  carry a path and no command text, so the shell rules never see them). A *single-file*
  `rm` elsewhere is allowed on purpose, so the path is the only thing that tells
  housekeeping from an agent deleting its own credentials.

The workspace is the line, and it is a line the app drew deliberately: it is
`$HOME/workspace`, not `$HOME`, so the agent's own configuration, its sessions and
the shared-storage links are not inside the directory the agent may clear. Inside
it a recursive delete is allowed **at any depth** — the first version allowed only
the first level under `$HOME`, which refused `rm -rf ~/myapp/node_modules` (the
inside of a project, the thing a build cleanup actually is) while allowing
`rm -rf ~/node_modules`.

Two properties of the matching are worth stating because they are what the report
was about. It resolves `$HOME` as shell spellings — `$HOME`, `${HOME}`, `"$HOME"` —
*except* inside single quotes, where the shell does not expand either; the guard
used to expand only `~`, so `rm -rf ~/tmp/x` was refused and `rm -rf "$HOME/tmp/x"`
was allowed, and expanding `'$HOME/workspace/x'` would turn a refusal into an allow.
And a command line is split at the separators that are **not inside quotes**, which
is what stopped `git commit -m 'fix; rm -rf /sdcard case'`, `grep -rn '&& rm -rf'`
and a heredoc *writing* a script from being read as destructive; a quoted string
handed to a shell (`sh -c '…'`, `eval '…'`, a heredoc fed to `bash`) is then
inspected as the command it is, and a newline is a separator, so a three-line script
is looked at as three commands rather than not at all.

It is deliberately conservative — it matches a small set of patterns and allows
what it does not recognise, because a guard that blocks legitimate work gets
removed by the user, and a removed guard protects nothing. Where it does block, it
explains why, so the model reports the refusal and asks instead of retrying
blindly. `$HOME/.pi/agent/AGENTS.md` states the same rules in prose, and is
rewritten on every launch so a stale copy cannot contradict the guard.

## The folder switches are a rule, not a wall

The storage page's switches were reported as a dead letter: grant "all files
access" once and the agent can read `/sdcard` whatever the switches say. That is
exactly what the platform allows — the grant belongs to the *process*, the agent
runs as this app, and there is no unprivileged way to run it as anyone else (a
chroot or a mount namespace needs root; SAF hands out content URIs, not paths, and
a shell cannot use them). A switch that stopped at the filesystem could not be
honest, so it does not claim to be: what it decides is what PiKit *offers*
(`~/storage/*`, created only for the folders that are on) and what PiKit
*permits* — and the permitting is done by the guard above, because a tool call is
the only place the agent's actions pass through the app.

`ShellEnvironment.build` publishes all three lists to every process it spawns, in every
spelling of each path (`StorageAccess.spellingsOf` — `/sdcard/DCIM`,
`/storage/emulated/0/DCIM` and `/storage/self/primary/DCIM` are one directory and a
command may name any of them), plus the link farm itself
(`PIKIT_STORAGE_LINKS`, `name=target` per line, from `StorageAccess.linkPlanFor`), and
`PiAgentSession.setStoragePolicy` restarts the agent when a switch changes, so a
cached copy cannot go stale. The guard reads them once at startup; with no variables
at all it polices nothing, which is what keeps it inert when someone runs `pi` by hand
in the terminal.

The link farm is published because it is the one spelling the app *itself* hands the
agent, and it names no shared-storage path at all: `~/storage/shared/Download/a.txt`
is `/sdcard/Download/a.txt`, and a rule that only matched the roots let the whole
policy be stepped around by the app's own convenience link — measured, before the
translation, as `cat ~/storage/shared/Download/a.txt` being allowed with nothing
granted while `cat /sdcard/Download/a.txt` was refused.

`ShellEnvironment.build` also publishes the workspace (`PIKIT_WORKSPACE`), which is
the directory a recursive delete may target *and* the directory a relative target is
resolved against — because it is where PiKit spawns the agent. A `pi` run by hand in
the Terminal tab has no such variable and starts in `$HOME`, so the guard resolves
relative targets against `$HOME` there. That distinction is not cosmetic: applying
the workspace's rule to a hand-run agent sitting in `$HOME` would allow a delete of
`$HOME/node_modules` by a rule written for the workspace.

The bar is stated in the UI now rather than implied away: the page says the agent
may reach the folders that are on and that PiKit refuses the rest. It is a policy,
not a sandbox — the guard matches paths in a command's text, so a path built at
runtime or hidden in base64 is not caught — and `tools/test-safety-guard.mjs` holds
180 cases: the four axes of the report (a recursive delete outside the workspace, an
ordinary command outside it, ordinary work inside it, and pi's own files) plus every
command that was allowed before this rule existed.

Measured on the Medium_Phone AVD with nothing granted: the agent asked to run
`cat /sdcard/Download/notes.txt` came back with *"the pikit-safety-guard
intercepted the command"* and listed the storage page as the fix. With Download
switched on, the same call returned the file's contents.

Three details of the page itself came out of using it:

- **Every folder asks before it is switched on.** Only the roots the page called
  "broad" (the whole tree, Documents, Pictures, DCIM) used to warn, on the theory
  that a photo album costs more than a download — but the user who reported it
  pointed out the obvious asymmetry: they were prompted for Pictures and not for
  Music, which teaches that the prompts are noise. The question is about the
  agent's reach in every case, so it is asked in every case and `Root` no longer
  carries an `isBroad` flag at all.
- **The whole tree subsumes the rest.** While `shared` is on, the switches below it
  are disabled and drawn as included: a switch that changes nothing is a lie about
  what it does. Adding a folder is disabled for the same reason. `Policy.isSubsumed`
  is that condition, and `StorageAccess.linkPlanFor` applies it as well — so the farm
  holds one link (`shared`) in this state rather than one per granted folder.
  `/sdcard/Download` is reachable as `~/storage/downloads` under its own grant and as
  `~/storage/shared/Download` through the broad one, and creating both is what a user
  reported as a duplicated Download folder. The rule has to live in the plan and not
  in `applyPolicy`'s creation loop, because the plan is also what prunes: without it
  the stale `downloads` link survived every later apply.
- **Any folder can be added** (`Policy.custom`, persisted beside the root names).
  The seven named roots are a shortcut, and "my notes are in `/sdcard/Notes`" was
  otherwise answerable only by granting all of shared storage. A custom folder goes
  through the same three paths as a named one — the link farm, the guard's allowed
  list, and the page's summary — via `linkPlanFor` and `allowedSpellings`, which is
  why the naming rule has its own test (`StoragePolicyTest`): a link name has to be
  shell-safe (`My Stuff` → `my-stuff`, runs of punctuation collapsed) and two
  folders with the same basename both have to get one (`photos`, `photos-2`) rather
  than one of them being silently dropped. The picker is a sheet of our own rows
  rather than `PickerBody`, because every row of that component dismisses the sheet
  it is in — right for a menu, wrong for stepping through a tree.

## What cannot be settled from outside, and the self-test that settles it

Two facts about storage are genuinely hard to establish, and both were wrong at
least once in this project's history:

- **Whether the permission that is recorded matches what the mount allows.**
  `Environment.isExternalStorageManager()` reports Android's bookkeeping. It has
  said "granted" while writes failed.
- **Whether the delete guard holds.** It is `SafeDelete` plus the link-farm
  handling; a reimplementation of either proves nothing about the code that ships.

Two measurements make this concrete, and both shaped the design:

> **`/storage/emulated` is a FUSE mount.** Access is decided by the media
> provider against the app's uid and its `MANAGE_EXTERNAL_STORAGE` app-op — *not*
> by Unix group membership. This app's process is in neither `sdcard_rw` nor
> `media_rw`, which reads like a fault and is not one.

> **A `run-as` shell is not the app.** It has the same uid but a `runas_app`
> SELinux context and a different supplementary-group set. Measured: `echo >>
> /sdcard/Download/x` is refused with `Permission denied` from such a shell, while
> the app performs the identical write successfully. Any storage check run from
> `run-as` therefore reports the shell's limits and calls them the app's.

So the check runs inside the environment, as a child process with the same
environment the terminal and the agent get: `tools/storage-self-test.sh`, shipped
in the image and reachable two ways — Settings → Upgrade Pi & repair → **Check
storage**, and `pikit-storage-check` on `PATH`. It reports:

- the running uid and supplementary groups, so the above is visible rather than
  folklore;
- whether the runtime is intact;
- the link farm, with a real read **and a write probe** per granted folder, so a
  read-only grant is reported as read-only;
- the delete guard, by building the shape that used to destroy data — a symlink to
  a directory holding a sentinel — removing the links the way the app does, and
  checking the sentinel is still there;
- the relocator's boundary, which must refuse `/sdcard` and `$HOME`.

A **destructive control** establishes that the experiment measures the hazard
rather than passing by construction: a naive traversal that follows the link (as
`File.deleteRecursively` does, because `isDirectory()` follows it) empties the
target — 1 file to 0 — while the app's by-path removal leaves it intact. Without
that control, `rm -rf` on the farm proves nothing, because `rm -rf` does not
follow symlinks in the first place.

**Verified on the Medium Phone emulator**, with `MANAGE_EXTERNAL_STORAGE` granted
from the Settings row and two folders switched on:

```
storage: permission=granted root=/storage/emulated/0
reach=[documents:link=true,read=ok(1),write=ok  downloads:link=true,read=ok(1),write=ok]
```

and after **Remove all access**: both links gone, `~/storage` empty, and all four
seeded files — a document, a download, a downloaded file the agent had written,
and a camera photo — still present and unchanged.

## The first launch asks for both permissions, in order

Two prompts are owed on a fresh install — notifications, and the storage
explanation — and they cannot be raised by two independent one-shot effects. The
first version was exactly that, and the notification prompt was **lost**: it was
requested from `MainActivity.onCreate`'s first composition, ~0.6 s after
`onResume`, while the splash was still exiting, the activity's window was being
replaced and the foreground service was starting. Measured from the platform's own
event log, with nothing else touching the device:

```
wm_create_activity … GrantPermissionsActivity REQUEST_PERMISSIONS   .714
wm_on_resume_called                                                 .863
Displayed …                                                         .941
individual_permissions_requested … POST_NOTIFICATIONS                .953
wm_finish_activity … GrantPermissionsActivity, app-request           .965
```

251 ms from creation to cancellation, with no user input and no permission state
change: the dialog reached RESUMED, was displayed, took input focus, and was then
finished by the system on that frame. A `LaunchedEffect(Unit)` never runs again, so
the request was gone for the rest of the launch — which is why the prompt appeared
on the *second* start, and why one user saw only the storage prompt and another
only the notification one. A control run with storage pre-granted (so that no
storage dialog exists) reproduced the cancellation in 235 ms, so the two prompts are
not competing for one window.

What replaced it, in `PiKitRoot`:

- **One owner, ordered, above the language key.** A `PromptStep` state — `None`,
  `Notification`, `Storage` — raised behind the same runtime gate the storage prompt
  already sat behind, so the first prompt appears when the app is *usable* rather
  than over the unpacking screen. It sits *above* `key(language)` because that key
  rebuilds its subtree when the language arrives, and a rebuild disposes the
  launcher registration while the request is in flight — which is the second
  request that produced `Can request only one set of permissions at a time` 66 ms
  after the first.
- **Answer-driven.** The sequence advances from the notification launcher's result
  callback, never from an effect that fires once. The storage step then runs in the
  same composition the answer arrives in, which is what "依次" means here.
- **Bounded retry.** This emulator's platform closes the dialog on its own frame
  every time, and cannot be talked out of it: measured across every app state tried
  — requested from the first composition, from the first *usable* frame, and from a
  fully idle app twenty seconds later — the dialog lived 60-360 ms and then closed,
  with no user input and no permission state change. So the app asks again at most
  twice when the callback comes back in under a second, since an answer that fast
  cannot be a decision. The sequence on this emulator reads as three `Displayed …
  GrantPermissionsActivity` lines ~1.7 s apart and then the storage dialog; on a
  device where the dialog survives, the first attempt is the one the user answers.
  Not asking at all was tried and is worse: with the request removed, Android 16 did
  **not** raise the prompt for this legacy-target app when it created its
  notification channel, so the user was never asked.
- **The storage step is still one-shot by design**: either button marks it prompted,
  and `Settings → Shared storage` is where a user changes their mind later.

One measurement worth keeping, because it looks like a bug and is not:
`MANAGE_EXTERNAL_STORAGE` is an **app-op**, and `appops get --uid pi.kit.mob
MANAGE_EXTERNAL_STORAGE` reads `allow` on an app that `dumpsys package` reports as
`granted=false, flags=[USER_SET]`. The app-op survives `pm clear` and a reinstall,
and `StorageAccess.isGranted()` asks the app-op on purpose (it is what decides
whether the mount is readable), so a "fresh" install can be genuinely already
granted and is then not asked. That is correct behaviour, not a missing prompt —
and it is why `appops set --uid … deny` is required before measuring a clean first
launch.

---

## Why the shared-storage page shows no warning by default

`FolderRow` raises its red "these files are usually irreplaceable" line only for the
one root that means *everything on the phone* (`shared`), and only while that root's
switch is **on**. Every row used to carry it, including Downloads and Pictures, which
is not true of them — both can be lost and re-downloaded — and a page where every row
is a red alert is a page where no alert is read. A folder that is switched off cannot
lose anything either, so its row says where it points instead. On a fresh install,
which is an empty policy, that means the page is correctly red-free.

---
