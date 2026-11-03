# §15 — Many conversations

The app used to be one conversation. What that cost, why the second one is a second
process rather than a switch, and what stays single on purpose.

## What "one conversation" meant

One `pi --mode rpc` child, one `ConversationState`, one workspace, one transcript on
screen. Moving to another conversation was `switch_session` on that child, and pi
implements it by tearing the current runtime down first — `RuntimeHost.teardownCurrent`
begins with `await this.session.abort()`, verified against the bundled bundle rather than
inferred. So a tap on another conversation while an answer was arriving stopped that
answer. The app grew a dialog asking whether to throw the answer away, and it was hated
for the right reason: the question was the design's fault.

The other half of the cost was smaller and only visible in use: every move between
conversations was a rebind, so the model, the thinking level and the workspace had to be
re-stated on the fresh session, and a conversation one step away could not be watched.

## Why a conversation is a process

pi's runtime is per process: a session file, a model, a working directory, a tool
registry, an extension host. There is no command that says "run this turn in *that*
conversation while I look at this one". So the app gives each open conversation its own
child, and a move between conversations becomes a move between windows rather than a
rebind:

- **Nothing is interrupted.** A turn in flight keeps its process, its stdout stream and
  its wake lock. The dialog that asked whether to abandon an answer is gone, and its
  removal is the test of the feature.
- **Opening a saved conversation is a second process**, launched in the directory the
  session file records (`{"type":"session",…,"cwd":…}`), then bound to that file with
  `switch_session` once — the same launch-then-rebind that restores a conversation after
  a restart (§8), because pi has no resume flag.
- **The conversation drawer is the switcher.** A row whose conversation is a live
  process carries a dot; tapping it switches to that window, and the row of the
  conversation on screen is the one drawn filled. There is no second place to find a
  conversation, because the list already existed and a second place would be a second
  thing to keep in step.

### The three things that had to become shared

Splitting one object into a pool forces the question of what a conversation *owns*. The
answer is the test for every future change: a fact that two conversations cannot
disagree about is not a conversation's fact.

| Fact | Owner | Why |
| --- | --- | --- |
| The unpacked image, its stamps and `RuntimeStatus` | `PiHost` | One install of ~285 MB. Two cold starts racing would unpack over each other; the lock is the host's. |
| `SettingsStore`, the permission tier, the terminal's PTYs | `PiHost` | A setting is about the app. A shell outlives the transcript that opened it — `pkg upgrade` must not die because the reader switched conversation. |
| pi's model catalogue and its refresh window | `PiHost` | One `models-store.json`. Two refreshes would be two `pi update --models` processes writing one file; the deadline is persisted once. |
| `models.json` and pi's `settings.json` | `PiHost.launchFileLock` around the write **and** the spawn | The files are one set for the whole app, and pi reads a torn document as `Unknown provider` and exits. The lock is held across the spawn so a second launch cannot observe a document being replaced. |
| The child, its RPC client, `ConversationState`, the workspace, the model, the wake lock | `PiAgentSession` | One process, one transcript. |

`PiAgentSession` keeps its public shape and delegates the shared half to the host, which
is why the screens did not have to be rewritten: a screen that says `session.env` still
means the one environment.

### The pool is bounded, and the bound is memory

A live conversation is a Node.js process: ~120 MB resident before it has read anything,
so there is a ceiling, and the ceiling has two numbers rather than one.

- **`PiHost.MAX_ANSWERING` is 5**, and it is the number the interface names: five
  conversations *producing output at once* is what the pool is sized for. It is not a
  limit on how many may be open, which is how it was first written and what it felt like:
  a sixth conversation was refused with four of the five sitting idle.
- **`PiHost.MAX_LIVE` is 8**, and it is the memory backstop behind that: eight children is
  roughly a gigabyte of resident processes, which is as far as a phone's memory goes
  before the pool starts being a page file.

Opening a conversation when either bound is reached evicts the **least recently used
idle** conversation — never the active one, never one with a turn in flight — because a
conversation's transcript is pi's and already on disk: closing is "stop the child", and
the history list opens it again. The refusal is therefore rare by construction: it needs
five answers in flight *and* nothing idle to give way, or eight children all mid-turn. The
sentence it shows names the answering cap, because that is the limit the reader can see
and wait out.

The eviction rule is the reason `lastUsedAt` is written by `activate` rather than by the
transcript: "recently useful" is what the reader did, not what the fixture wrote.

### Only what is answering keeps a process

`MAX_LIVE` bounds how many children may exist, but nothing *stops* an idle one — and a
conversation that has finished its turn is an idle child holding ~120 MB for a transcript
that is already on disk. `PiHost` sweeps them: any conversation that is neither the one on
screen nor answering has its agent stopped, watched from three places because any of them
can be the last thing that happens (the reader switching away, a background turn settling,
a conversation leaving the pool). Switching back starts it again — `RootContent`'s launch
effect sees `Stopped` — which costs about two seconds and buys the memory back.

The cost is visible and deliberate: a conversation that was open a moment ago shows its
titlebar again for a beat before it can be typed into. What it buys is that "five at once"
is a limit on *work* rather than on windows.

### The notification followed the same split

The foreground service used to keep one shade entry for as long as an agent process was
alive, which is noise: between turns there is nothing to report. It now draws the entry
**only while at least one conversation is streaming** (and the UI is not in front), and
its sentence names how many are. The claim and the entry are different things — a
foreground service exists *because* of its notification — so the entries the service
drops when the last answer settles, and the process it keeps alive while a turn runs, are
decided separately: `PiHost.streamingConversations` is the count, `onUiVisible` is the
window.

The wake lock is split the same way as before: a turn lock per conversation
(`holdCpuDuringTurn`, acquired on `agent_start`, released on `agent_settled`) and one
"the reader left while something was being written" lock in the service.

## What a cold start does now

One conversation is restored, not the whole pool: the session path remembered by the
*active* conversation (written by `activate`, and by a conversation only when it is the
active one — a background conversation finishing a turn must not decide what the next
launch opens). Restoring three processes on a cold start would multiply the unpack's
cost by three for conversations the reader may not want back.

## What this rejected

- **One child, many transcripts, `switch_session` per move.** What the app had. It
  aborts the running turn by construction, and no ordering of the RPC calls changes that.
- **A process per saved conversation.** A month of conversations is hundreds of Node.js
  processes. The pool is bounded for the same reason the attachment cap is (§7.1's
  `MAX_ATTACHMENTS`): the phone's heap is the constraint, and a refusal is cheaper than
  the failure the constraint would otherwise produce.
- **Keeping the app's half of `AGENTS.md` editable.** It names the prefix, the three
  permission tiers and the workspace rules; a stale or hand-edited copy tells the model
  the wrong tier, which is a bug report about the app rather than a preference. It is
  written from `AgentContext.default(env)` on every launch now, and the reader's own
  rules live in the second half, which nothing in the app ever writes (§14).
