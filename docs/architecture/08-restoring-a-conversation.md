# 8. Restoring a conversation restores its layout

*[Architecture](../ARCHITECTURE.md) §8.*

## The file is read on the tap, and that is a cost with a number

`PiHost.openConversation` rebuilds the transcript synchronously, on the thread that handled
the tap: `SessionTranscript.payload` parses every message line, and
`ConversationReducer.replaceWithMessages` folds them — including one SHA-1 per picture, over
the base64 pi echoed back (§7.1). Measured on the desktop JVM on a synthetic session of 200
messages:

| picture payload | file | `payload` | fold (pictures hashed) |
| --- | --- | --- | --- |
| none | 170 KB | 3.8 ms | 1.4 ms |
| 2 × 1.2 MB | 3.4 MB | 11.9 ms | 4.2 ms |
| 6 × 1.2 MB | 9.8 MB | 24.1 ms | 10.8 ms |

A phone is several times slower per core and its storage slower again, so a picture-heavy
conversation costs a few hundred milliseconds of frozen frame on the tap that opens it — and
`SessionTranscript`'s own ceiling is 32 MB, which is roughly a second of it. `forkAt` has the
same shape twice: it reads the whole session into memory to copy its records, and then the
conversation it opens reads the copy back.

This is recorded rather than fixed, and the reason is not the size of the work: it is where
the *state* would have to go. `ConversationState.loadingSession` exists for exactly this
(§10.1's handshake), and its contract is that `switchSessionTo` is its only writer — a
drawer open that came back a moment later would have to make the page draw "opening the
conversation" for a conversation the reader has already been told is instant, and the
drawer's own current-row mark keys on `sessionFile`, which the parse is what produces. A
change that moves the read off the tap is therefore a change to this section's promise, not
a `withContext(IO)` around one call.

pi persists every session as JSONL, and a conversation opened from the drawer is rebuilt
from that file (`SessionTranscript.payload`) without a process: the records are in the same
shape `get_messages` answers with. What the file cannot rebuild is the **turns** — pi stores
the messages but not the `agent_start` / `agent_settled` events that bracketed them — and
the fold depends entirely on the turn list, so switching sessions appeared to *lose* the
folding: every tool card of every past turn came back on screen at full length. The live path
builds turns in the reducer as the events arrive; history re-derives them, from the same rule,
at load time:

- a user message opens a turn;
- the last assistant message that carried **text** is that turn's answer — a message that
  only carried a tool call is not one;
- the next user message closes it.

Two details that only show up in the numbers:

- **Close at the turn's own last message, not at the next prompt.** The next prompt can be
  hours later, and closing there billed the idle gap to the turn that had already finished.
  Measured: a turn whose reply landed at t=5000 and whose next prompt came at t=999000
  reported 998 s instead of 4 s.
- **A missing timestamp means unknown, not zero.** `TurnSummaryData.elapsedMs` is nullable;
  `formatDuration` coerces to a one-second minimum, so a message list without timestamps
  would have shown a confident `1s` for every turn in the conversation. The row now shows
  the step count alone.

The live and restored paths deliberately differ in one visible way: the live path appends an
assistant row on `message_start`, before it knows whether the message will carry text or only
a tool call, so a live tool-using turn hides one more row than the same turn restored from
disk. `ChatFoldTest` pins both numbers.

## What a conversation is called

A row's label is the first user turn, and "the first user turn" used to mean "the first line
of text in it". Two kinds of conversation therefore had no true name: a prompt that was
only a picture drew `空对话` — the empty label, over a conversation that had plainly just
started — and one whose picture pi had rescaled was titled with pi's own
`[Image: original 1200x2670, displayed at 899x2000 …]` note, an English sentence in the
middle of the interface.

The turn is now read in three parts. `SessionMetadata.read` strips the machinery with the
same parser the transcript's chip uses (`parsePromptExtras`, which lives in `pi` because the
shapes it parses are pi's), counts the `image` blocks the turn carried, and counts the paths
its address block named. `SessionSummary` carries both counts, and the row's label falls
back in that order: the words, then `N 张图片`, then `N 个文件`, then the empty label. The
first turn names the conversation even when it has no words — the scan used to keep looking
for text, so a picture-first conversation was titled with the *second* turn's sentence,
something the reader said afterwards.

The counts come from the content blocks, not from the notes: a picture inside pi's limit
gets no note, and that is now the common case.

## Searching a conversation's content

The drawer's list filtered on the title alone, and a title is the first user message, so
"which conversation was that about the retry logic" was answerable only by opening
conversations until one looked right. It now searches the prose of every transcript too,
under three decisions:

- **User and assistant text only.** A tool result is usually a file listing or a command's
  output, so including it would answer "which conversation mentioned `package.json`" with
  every conversation that ever ran `ls`. Only `type == "text"` content blocks are read,
  which is also what keeps pi's echoed images — base64, megabytes per screenshot — out of
  the index.
- **A transcript is re-read only when its stamp or its length changed**
  (`SessionSearchIndex`); otherwise the scan costs a JSON parse per message line on every
  keystroke. Both fields are compared because a file rewritten inside the filesystem's
  timestamp granularity keeps its stamp.
- **The scan is debounced by the effect that runs it**: `LaunchedEffect(query, summaries)`
  is cancelled and restarted on each keystroke, so a `delay` before the work is a debounce
  with no state of its own. A snippet is drawn only for the query it answered, or the
  previous scan's matches would be highlighted against text that no longer contains what is
  in the field.

`SessionMetadata.snippet()` bounds the shown window to about 120 characters with ellipses,
which is what stops a match in the middle of a chapter from putting the chapter in a list row.

## Coming back to the session after the process dies

pi has no resume flag: every `pi --mode rpc` launch opens a **new** session file under
`--session-dir`. The handshake only folds `get_state` into the conversation — including that
new `sessionFile` — while `items` and `turns` from the previous process stay on screen. The
reader therefore saw their conversation and typed into a different one: after 切后台/锁屏 and
a few failed retries, the next message started a new conversation in the same UI.

The fix is to remember the session path in force and, after every launch, `switch_session`
back to it (and re-read `get_messages`) when the handshake opened a different file. The rule
is pure (`sessionRestoreTarget`) and pinned by `SessionRestoreTest`: a remembered path wins
only when the file still exists and is not already the one pi opened. A deleted file is
cleared rather than chased.

**The file the state names is the one on screen, even while the switch is in flight.** The
handshake folds `get_state` — including the fresh `sessionFile` — a moment before the
`switch_session` above lands, and for that moment the conversation's state named a file no
drawer row has. The drawer marks its current row by that path, so a reader who opened a
conversation and swiped back into the list before pi finished starting lost the row's fill
and got it back: the "灰色背景消失一下然后出现" report, one frame of it caught on a 175 ms
screencap cadence. `sessionFileAfterHandshake` is the rule that fixes it — while a restore
is pending the file the page is already showing wins, and the switch's own `get_state`
writes the name once it has landed. `SessionRestoreTest` pins the three cases: a pending
restore keeps the shown file, a launch with nothing to restore follows pi's report, and a
restore that was not needed (pi opened the very file that was wanted) follows it too.

Two neighbours of that rule matter for the same report:

- **A partial wake lock for the length of one turn** (`agent_start` → `agent_settled`).
  Without it the CPU sleeps when the screen goes off, the child's stdout is no longer
  drained, and the turn looks interrupted when the user returns.
- **One automatic restart after an unexpected exit**, not a loop, and never after a
  deliberate `stopAgent`. Combined with the restore above, a background kill comes back to
  the same conversation instead of a blank new one.

---
