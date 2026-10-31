# 5. Storage, and why the first design could delete a phone

*[Architecture](../ARCHITECTURE.md) §5.*

The first version of this app had two storage states: "none" and "all files
access". The second linked the whole of `/sdcard` into the environment as
`~/storage/*` the moment the user granted Android's permission, and the agent ran
with `--approve` and no permission popups of any kind. An autonomous process with
recursive-delete capability therefore had every photo, document and backup on the
device in reach as an ordinary directory inside its home folder. That is a design
that can remove a phone's files, and it did.

Two independent guards answer it now, and a third one — the permission tiers — decides what
a tool call may write. They are independent on purpose: each alone has a hole, and the holes
are not the same.

- **The app cannot recursively delete outside its own data directory.** `env/SafeDelete.kt`,
  below.
- **The agent is judged on every tool call.** `tools/pikit-permissions.ts` and its three
  tiers; §14 is that chapter.
- **Nothing is *granted* any more.** The page of folder switches — the middle answer between
  "none" and "all files access" — was removed once the tiers made it redundant. That is the
  first section below, because its reasoning is the reason the other two are shaped as they
  are.

## The grant page, and why it is gone

For a while the app carried a Settings page — *Shared storage* — with one switch per folder
(`shared`, `downloads`, `documents`, `pictures`, `dcim`, `music`, `movies`, plus any folder
the user added), and the set was enforced in two places: the link farm the environment was
given, and the list of writable roots the tool-call guard was handed. It was a good page for
the model the app had then: the guard drew its line at *reads* as well as writes, so a folder
that was off was a folder the agent could not even look at, and the switches were the only way
to open one.

The tiers changed the line, and with it the answer:

- **Reads are free in every tier** (§14). A grant that decides what may be *read* has nothing
  left to decide; only writes are judged.
- **A write is judged against the workspace the conversation runs in.** That is a directory the
  reader already chose, on purpose, moments ago, with a chip in the composer. A permission to
  write to `/sdcard/Dev/app` is therefore expressed by *starting a conversation there* — which
  is the same gesture, on the same screen, and it needs no second list to be kept in step.
- So the page's switches decided a set that nothing read, and every folder a reader wanted was
  a folder they could already reach by making it a workspace. The page was three quarters of
  the surface of a feature whose remaining quarter is the Android grant itself.

What replaced it, precisely:

- **The workspace picker reaches the phone.** Any directory may be a workspace — `/sdcard`,
  the app's own tree, `$HOME`, `$HOME/.pi` — so `/sdcard/Dev/app` is an ordinary project. The
  picker offers `/sdcard` as a shortcut and asks for Android's "all files access" when a
  folder there cannot be listed. Nothing is refused: the only answers left in
  `WorkspaceStore.refusal` are "that is not a directory". A workspace of `$HOME` therefore
  makes pi's own state writable in the middle tier, which is the tier's promise applied to the
  folder the reader chose (`$HOME/.pikit/permission.json` still decides the tier itself, so a
  refusal cannot be edited away from inside the workspace *it* protects — the file is read by
  the extension in the pi process, and the process may rewrite it).
- **`~/storage` is a convenience, not a permission.** While the grant holds, one link —
  `~/storage/shared` — points at the whole of the phone's storage, under the name
  `termux-setup-storage` uses for the root. It used to be the seven names upstream creates,
  and six of them are folders *inside* the seventh: `~/storage/downloads` and
  `~/storage/shared/Download` were two names for one directory, which is the duplication the
  self-test calls "one folder, one name". A write through the link is judged by the tier and
  the workspace like any other path.
- **Nothing publishes a grant to the agent any more.** `PIKIT_STORAGE_ALLOWED` and
  `PIKIT_STORAGE_LINKS` are gone from `ShellEnvironment.build`; the only environment variables
  the tiers read are `PIKIT_WORKSPACE` and `PIKIT_PERMISSION_FILE` (§14).

Two facts about the removed design are worth keeping, because they are measurements rather
than preferences. **The whole tree subsumed the rest:** with `shared` on, the switches below it
were disabled and drawn as included — a switch that changes nothing is a lie about what it
does — and the farm held one link (`shared`) rather than one per granted folder, because
`/sdcard/Download` was reachable as `~/storage/downloads` under its own grant and as
`~/storage/shared/Download` through the broad one, and creating both is a duplicated Downloads
folder. And **a page where every row is an alert is a page where no alert is read**: the
folder rows once carried a warning each, then only the unrestricted one did, then none, and a
folder that is off cannot lose anything — which is also why the page raised no standing note
above its list.

## Layer — the app cannot recursively delete outside its own data directory

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

The recursive call sites are the staging tree, the runtime prefix and the
model-discovery scratch agent directory. (The `~/storage` farm is not one of
them: a link has to be removed *as a link*, so `StorageAccess.syncFarm` deletes them
one at a time.) `PrefixPatcher` — which walks a tree and *writes* to every file it
finds — carries the same boundary check.

## Layer — the agent is refused the calls that destroy data

Pi has no permission system by design, and its documentation says so: *"No
permission popups. Run in a container, or build your own confirmation flow with
extensions."* On a phone there is no container, so this app installs one: a
`tool_call` extension, staged into the runtime image and copied into
`$HOME/.pi/agent/extensions` at first run and on every runtime update.

That extension used to be a hand-written list of destructive shapes — recursive deletes
outside the workspace, `mkfs` and friends, writes to a raw block device, pi's own
credential files, commands naming shared storage the user had not granted — with one
on/off switch. **It is now three permission tiers** (`tools/pikit-permissions.ts`,
read-only / workspace-write / full-access), chosen in the composer and checked on every
tool call. §14 is that chapter: the tiers, what each refuses, why the mode is a file
rather than an environment variable, and what the change cost.

What this chapter keeps is the part of the old design that is still true:

- The tier is the **only** place the agent's actions pass through, so it is where a
  workspace becomes a limit rather than a convenience. Reads are free in every tier, which
  is the one thing the old guard did that the tiers deliberately do not (§14's *What this
  cost*).
- It is a rule, not a sandbox. It matches paths in a command's text, so a path built at
  runtime is not caught; nothing unprivileged can turn that into a wall on Android,
  because "all files access" belongs to the process and the agent runs as this app.
- `$HOME/.pi/agent/AGENTS.md` states the same rules in prose, so the model has them before
  it writes a command at all. The two are the same rules written twice on purpose, and
  neither is allowed to be the only copy.

## The document beside the machine

`$HOME/.pi/agent/AGENTS.md` is the other half of the agent's context — pi reads it
automatically at startup — and it is **composed from two documents**, because it is the only
name pi looks for and two files cannot be two answers to one question:

- `AGENTS.author.md` is PiKit's own text: what the environment is, where the workspace is,
  which tier is enforced, what the phone's storage looks like. It is editable — a reader may
  disagree about a sentence — and it carries a **generation marker** on its first line. A build
  whose `AgentContext.GENERATION` is higher replaces it; an equal generation leaves it exactly
  as the reader left it. That is the whole rule, and it is what makes "edit it if you like, but
  I will overwrite it when I change the default" a sentence the code can keep.
- `AGENTS.user.md` is the reader's, created **empty** and never written by the app after that.
  It is where the sentences about one's own projects belong, and the promise is that no
  upgrade touches it.

`AGENTS.md` itself is written by `AgentContext.compose` — the author's half, then the reader's
under a comment that names it — and it is rewritten on every launch, after either half is
edited, and on install. Composing at launch rather than only at install is a migration
requirement as much as a convenience: the installer does not run at all when the runtime is
already there, so an app-only upgrade that carried a new default would otherwise never reach
the file.

The two-halves split is a change of shape, so the migration has three cases and all three were
exercised on the device: no `AGENTS.md` at all (both halves written fresh), an `AGENTS.md` that
is a *previous default* (the marker says so, the author's half is replaced, the reader's half
starts empty), and an `AGENTS.md` the reader wrote before the split (it becomes the reader's
half verbatim, and the author's half is written beside it).

Settings → Agent process has one row per half. The author's row offers the default back; the
reader's does not, because "restore the default" for a document whose promise is that the app
never rewrites it could only mean "throw my text away".

## What still decides what the agent may write

`ShellEnvironment.build` publishes the workspace (`PIKIT_WORKSPACE`), the directory the
conversation was started in — see §10 for the list of workspaces and how a conversation
picks one — and the tier file (`PIKIT_PERMISSION_FILE`). Nothing else about storage reaches
the agent process. The bar is stated in the UI: the tier chip says what each level allows,
and a refused write comes back to the agent as a tool error naming the tier.

The old extension's own failure record is worth keeping, because it is what the tiers were
designed against: measured on the Medium Phone AVD with nothing granted, the agent asked to
run `cat /sdcard/Download/notes.txt` and came back with *"the pikit-safety-guard intercepted
the command"*, listing the storage page as the fix. Under the tiers the same call is a
read and therefore allowed — the policy now draws its line at writes.

## What cannot be settled from outside, and the self-test that settles it

Two facts about storage are genuinely hard to establish from outside the app:

> **`/storage/emulated` is a FUSE mount.** Access is decided by the media
> provider against the app's uid, not by Unix group membership: this app's process
> is in neither `sdcard_rw` nor `media_rw`.

> **A `run-as` shell is not the app**: same uid, a `runas_app` SELinux context and a
> different supplementary-group set, so `echo >> /sdcard/Download/x` is refused with
> `Permission denied` there while the app performs the identical write successfully.

So the check runs inside the environment, as a child process with the same
environment the terminal and the agent get: `tools/storage-self-test.sh`, shipped
in the image and reachable two ways — Settings → Maintenance & repair → **Check
storage**, and `pikit-storage-check` on `PATH`. It reports:

- the running uid and supplementary groups;
- whether the runtime is intact;
- the link farm, with a real read **and a write probe** per link, so a read-only mount is
  reported as read-only;
- the delete guard, by building the shape that used to destroy data — a symlink to
  a directory holding a sentinel — removing the links the way the app does, and
  checking the sentinel is still there;
- the relocator's boundary, which must refuse `/sdcard` and `$HOME`.

A **destructive control** establishes that the experiment measures the hazard
rather than passing by construction: a naive traversal that follows the link (as
`File.deleteRecursively` does) empties the target — 1 file to 0 — while the app's
by-path removal leaves it intact; without it, `rm -rf` on the farm proves nothing,
because `rm -rf` does not follow symlinks.

**Verified on the Medium Phone emulator**, with `MANAGE_EXTERNAL_STORAGE` granted and the
farm in place:

```
storage: permission=granted root=/storage/emulated/0
reach=[documents:link=true,read=ok(1),write=ok  downloads:link=true,read=ok(1),write=ok]
```

and after revoking the grant: every link gone, `~/storage` removed, and all four
seeded files — a document, a download, a downloaded file the agent had written,
and a camera photo — still present and unchanged. With the grant withheld the shipped
`pikit-storage-check` reports `9 passed, 0 failed`, which is the state a fresh install is
in.

## A canonical path is not the path the app holds

Handing a file to another app is the one place where a path the app has and a path the
*caller* will see part company: `FileProvider.getUriForFile` resolves a file's
**canonical** path, so a file the app reaches as
`…/files/home/storage/shared/Download/a.txt` arrives at the provider as
`/storage/emulated/0/Download/a.txt` — the spelling the link farm is a convenience for —
and matched none of the configured roots, which is why "open with" on a file under
`~/storage` did nothing. `res/xml/file_paths.xml` carries an `external-path` root for it
now. The lesson is the same one the link farm teaches: the same file has several names,
and the one that decides is the one at the boundary being crossed.

## The first launch asks three things, in order

Three prompts are owed on a fresh install — notifications, the storage
explanation, and the battery-optimisation exemption — and they cannot be raised by
three independent one-shot effects. The first version was exactly that, and the
notification prompt was **lost**: it was
requested from `MainActivity.onCreate`'s first composition, ~0.6 s after `onResume`,
while the splash was still exiting and the foreground service was starting. Measured
from the platform's own event log, with nothing else touching the device:

```
wm_create_activity … GrantPermissionsActivity REQUEST_PERMISSIONS   .714
wm_on_resume_called                                                 .863
Displayed …                                                         .941
individual_permissions_requested … POST_NOTIFICATIONS                .953
wm_finish_activity … GrantPermissionsActivity, app-request           .965
```

251 ms from creation to cancellation, with no user input and no permission state
change. A `LaunchedEffect(Unit)` never runs again, so the request was gone for the
rest of the launch — which is why the prompt appeared on the *second* start. A
control run with storage pre-granted (so that no storage dialog exists) reproduced
the cancellation in 235 ms, so the two prompts are not competing for one window.

What replaced it, in `PiKitRoot`:

- **One owner, ordered, above the language key.** A `PromptStep` state — `None`,
  `Notification`, `Storage`, `KeepAlive` — raised behind the same runtime gate the
  storage prompt
  already sat behind, so the first prompt appears when the app is *usable* rather
  than over the unpacking screen. It sits *above* `key(language)` because that key
  rebuilds its subtree when the language arrives, and a rebuild disposes the
  launcher registration while the request is in flight — which is the second request
  that produced `Can request only one set of permissions at a time` 66 ms after the
  first.
- **Answer-driven.** The sequence advances from the notification launcher's result
  callback and from the storage dialog's own buttons, never from an effect that fires
  once.
- **Bounded retry.** This emulator's platform closes the dialog on its own frame:
  measured across every app state tried — from the first composition, from the first
  *usable* frame, and from a fully idle app twenty seconds later — the dialog lived
  60-360 ms and then closed, with no user input and no permission state change. So the
  app asks again at most twice when the callback comes back in under a second, since an
  answer that fast cannot be a decision; on this emulator the sequence reads as three
  `Displayed … GrantPermissionsActivity` lines ~1.7 s apart and then the storage dialog.
  Not asking at all was tried and is worse: with the request removed, Android 16 did **not**
  raise the prompt for this legacy-target app when it created its notification channel, so
  the user was never asked.
- **The storage and keep-alive steps are one-shot by design**: every button of either
  records the answer. The storage step is the one that changed when the page went away:
  it used to close by pointing at **Settings → Shared storage**, and now the place a user
  changes their mind is the workspace picker — adding a folder on the phone is what raises
  the system page again. The keep-alive step's later home is still **Settings → Agent
  process**. Both are recorded on the *answer* rather than on the showing, which is why the
  keep-alive dialog appears once per install — it is in front of the platform's own
  `RequestIgnoreBatteryOptimizations` box, and there is no result callback for that box (the
  user may answer it, ignore it, or come back through the recents list), so a state that
  waited for one would ask again on every launch.

The keep-alive step is the third one because it is the only one of the three that is
not about *reaching* something: a missing notification or storage grant fails in front
of the user within the first minute, while a process reclaimed with the screen off
looks like the answer simply stopping. It was reachable only from the agent page
before, three taps in, which is the state that made it worth a prompt. `BatteryOptimisation`
holds the state, the ask and the record of having asked, because the agent page's
keep-alive row reports the same fact for ever after.

`MANAGE_EXTERNAL_STORAGE` is an **app-op**: `appops get --uid pi.kit.mob
MANAGE_EXTERNAL_STORAGE` reads `allow` on an app that `dumpsys package` reports as
`granted=false, flags=[USER_SET]`. It survives `pm clear` and a reinstall, and
`StorageAccess.isGranted()` asks the platform for it on purpose
(`Environment.isExternalStorageManager()`, not the permission flag — it is what decides
whether the mount is readable), so a "fresh" install can be genuinely already granted and
is then not asked — which is why `appops set --uid … deny` is required before measuring a
clean first launch.

---

## What the farm costs, now that it is only a convenience

Nothing in the write path. The farm is created while the grant holds and removed when it
does not (`StorageAccess.syncFarm`, called at install, before every launch and from
`termux-setup-storage`'s broadcast), and its one measurement survives: the links must not be
duplicated, because `/sdcard/Download` reachable as both `~/storage/downloads` and
`~/storage/shared/Download` is a duplicate Downloads folder rather than two conveniences.
`StorageFarmTest` pins the one name and the property; the self-test probes it for real.
