# §15 — Many conversations

The app used to be one conversation. What that cost, why the second one is a second
process rather than a switch, and what stays single on purpose.

## Opening a conversation does not start an agent

A saved conversation is a JSONL file, and every message in it is one record in the same
shape `get_messages` answers with. `SessionTranscript` turns the file into that payload and
`ConversationReducer.replaceWithMessages` folds it, so opening a conversation is a file read
— measured at well under a frame — with **no process at all**. The agent starts when
something is sent (`sendPrompt` starts one when its client is null), and the page's launch
effect now skips a conversation that already has a transcript.

That replaces the old path, which launched pi, waited for its handshake, then asked it for
`switch_session`, `get_state` and `get_messages` — two seconds of Node startup to display
text that was already on disk. When the agent does start for a conversation restored this
way, `switchSessionTo` binds the process to the file but does **not** re-fetch or clear the
transcript: the page would otherwise go blank for a moment at the exact instant the reader
sent something.

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
  thing to keep in step. It lists **one launch mode's** conversations at a time — the
  mode of the conversation on screen — so the two kinds of conversation keep two
  histories rather than one; §17 owns that rule and the switch that moves it.

### The three things that had to become shared

Splitting one object into a pool forces the question of what a conversation *owns*. The
answer is the test for every future change: a fact that two conversations cannot
disagree about is not a conversation's fact.

| Fact | Owner | Why |
| --- | --- | --- |
| The unpacked image, its stamps and `RuntimeStatus` | `PiHost` | One install of ~285 MB. Two cold starts racing would unpack over each other; the lock is the host's. |
| `SettingsStore`, the permission tier, the terminal's PTYs | `PiHost` | A setting is about the app. A shell outlives the transcript that opened it — `pkg upgrade` must not die because the reader switched conversation. |
| Pi's model catalogue and its refresh window | `PiHost` | One published `models-store.json` and one deadline. Refresh runs with an isolated scratch agent dir and publishes atomically without restarting a conversation. |
| `models.json` and Pi's `settings.json` | `PiHost.launchFileLock` through the handshake | Startup files and credential environment come from one snapshot. A successful spawn does not mean the child has finished reading those files. |
| The session directory's listing and its per-file metadata cache | `SessionListingCache` | The files are one directory. A cache owned by a conversation was emptied by every switch, so the drawer re-parsed every session file the reader had — and drew its spinner while it did (below). |
| The child, its RPC client, `ConversationState`, the workspace, the model, the wake lock | `PiAgentSession` | One process, one transcript. |

`PiAgentSession` keeps its public shape and delegates the shared half to the host, which
is why the screens did not have to be rewritten: a screen that says `session.env` still
means the one environment.

**The drawer's listing is the shared fact the first release missed.** The history drawer is
rebuilt when the reader switches conversation — the chat page is keyed on the session — and
the listing it scans was cached in the *session*, so every switch started from an empty
cache and re-read the first line of every JSONL file in the directory. Measured on the
1080 px emulator with twenty saved conversations: the drawer composed with `n=0,
loading=true`, the scan finished 3 ms later, and the rows were on screen **146 ms** after
the composition, with a spinner in between. That is the flash a quick swipe back into the
drawer showed: a blank panel that fills in. The cache is process-wide now (`meta`, keyed by
path with the `(mtime, length)` stamp), and the last complete listing is kept and used as
the first frame a newly composed drawer draws, so a switch costs a refresh rather than a
blank. The drawer's own scroll position is hoisted above the conversation key for the same
reason — it is the list's state, not a conversation's — and `RootContent` holds it.

**The page also has to *read* the conversation it is keyed on, and that is not automatic.**
`ChatTab` hands the page a `ConversationState` collected from `session.conversation`, and
`collectAsState` remembers the `MutableState` it returns **with no key of its own** — the keys
it takes restart the collector and nothing else — so a change of `session` kept the previous
conversation's value until the new flow emitted, one frame later. `key(session)` sits *below*
that read, so that frame was built from the old conversation's state: measured on the 1080 px
emulator, a tap on the header's new-conversation mark composed the new conversation's page
carrying the previous transcript's **28 rows**, for **300 ms** — the frame is long because the
new conversation's `startAgent` runs on the same thread — and the page then replaced them with
the empty state. That is the flash of the conversation, scrolled where it had been left, that
precedes a new conversation on a faster device, where the same frame is one frame long.
`collectAsState`'s `initial` argument cannot fix it, because it is read only on the first
composition; the reads are `key(session)`-ed instead — the transcript and the agent status —
so the first frame of a switch draws the conversation being switched to.

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
the drawer opens it again. The refusal is therefore rare by construction: it needs
five answers in flight *and* nothing idle to give way, or eight children all mid-turn. The
sentence it shows names the answering cap, because that is the limit the reader can see
and wait out.

The eviction rule is the reason `lastUsedAt` is written by `activate` rather than by the
transcript: "recently useful" is what the reader did, not what the fixture wrote.

### Only what is answering keeps a process

`MAX_LIVE` bounds how many children may exist, but nothing *stops* an idle one — and a
conversation that has finished its turn is an idle child holding ~120 MB for a transcript
that is already on disk. `PiHost` sweeps them: any conversation that is neither the one on
screen nor answering has its agent stopped **after a 90-second grace**, watched from three places because any of them
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

## The name: the first prompt, and one generated title

A conversation pi never names is titled by its first user turn, which is what the drawer
has always drawn and what pi's own picker does. With **Generate conversation titles** on,
a fresh conversation also gets a stored name from that opening prompt: `TitleGenerator`
hands the prompt to a one-shot `pi --print` child running the same model, with DSH's
first-prompt title policy — one line in the messages' language, about five words or ten
CJK characters, no Markdown or commentary — and writes the returned line with
`set_session_name`. The line is normalized (first non-empty line, emphasis and quotes
stripped) and clipped to 40 code points and 80 UTF-8 bytes before it becomes a drawer
row. The request runs when the first live user `message_end` arrives
rather than after the first answer: the title describes the question, and waiting for a
turn that may fail or be stopped would waste the one moment the prompt is certain.

The request is one throwaway `pi --print` process. Pi has no title command in its RPC,
but its print mode is exactly the missing piece: the process starts, runs one prompt and
writes the final assistant text to stdout. It reaches every provider pi supports —
built-in or a custom `models.json` endpoint — through the same model layer the
conversation uses, so the app does not carry a second provider table with its own URL,
authentication and response-shape gaps. A hand-written direct request was tried and
removed: it covered four envelopes and returned an empty answer on a reasoning model
whose thinking consumed the output budget. `--thinking off` is what makes a title cost a
few tokens instead of hundreds; `--no-session` keeps the run out of the history list;
`--no-tools`, `--no-extensions`, `--no-skills`, `--no-prompt-templates`,
`--no-context-files`, `--no-mcp` and `--no-approve` close every ambient resource door so
an installed extension cannot turn a title into another agent run. The price is one
Node start per new conversation — measured at 1.87 s on the host — paid once, in the
background, on a feature that is already optional. The prompt is capped at two thousand
characters, which is the opening question rather than the transcript, and a failed run is
logged by exit status only and never retried for that conversation.

Only a conversation this app started itself is title-eligible: a fresh launch with
nothing to restore, `new_session`, or the new child a dead process has to spawn. That
mark is explicit state on the conversation, not a `File.exists()` test at handshake
time. Pi names the session file in its handshake but may create the empty header later;
the old test could therefore decide a brand-new session already existed and skip every
title. The mark is consumed by the first live user `message_end` — Pi persists that
record before handling another RPC — and history, forks and cold-start restores clear it,
so an unnamed existing conversation is never renamed on a follow-up prompt. The live child
persists the generated name with its own `set_session_name`; the path and name are checked
again at the write so a reset, rename or a replaced process cannot receive a stale title.
The header reads that name from the session state, and `PiHost.sessionListingRevision`
refreshes an already cached drawer row. The session file remains the only stored title,
so conversation export and backup do not need another file.

## A new conversation appears in the drawer when its file does

A conversation has no row until pi has written its session file, and that moment is the
first prompt, not the screen. The drawer is composed once and then parked at progress
zero, so its listing — a scan of the session directory — runs when the drawer is first
drawn and on an explicit reload, neither of which happens between a new conversation's
first prompt and the reader pulling the drawer out. The row was therefore missing until
the app was restarted or some other action bumped the reload, which is the report that a
new conversation could not be found in history.

The fix has two triggers, because the file and the opening are independent moments: the
drawer refreshes when the conversation's path appears (a message sent while the drawer is
out), and when the drawer opens with a path the listing does not carry (the file was
written while the drawer was closed, and the scan had run before pi flushed it — the
first attempt used only the first trigger and was measured stale in exactly that order).
The membership test keeps a file the scan already found from starting a second scan on
every recomposition, and a conversation opened from the drawer needs nothing here:
`listSessions()` already ran when the drawer was built.

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
