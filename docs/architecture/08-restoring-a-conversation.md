# 8. Restoring a conversation restores its layout

*[Architecture](../ARCHITECTURE.md) §8.*

pi persists every session as JSONL and `get_messages` returns the messages, so
switching to an earlier conversation can rebuild the transcript. It cannot
rebuild the **turns** — pi stores the messages but not the `agent_start` /
`agent_settled` events that bracketed them — and the fold depends entirely on the
turn list. The result was that switching sessions appeared to *lose* the folding:
every tool card of every past turn came back on screen at full length. The live
path builds turns in the reducer as the events arrive. History has to re-derive
them, from the same rule, at load time:

- a user message opens a turn;
- the last assistant message that carried **text** is that turn's answer — a
  message that only carried a tool call is not one;
- the next user message closes it.

Two details that only show up in the numbers:

- **Close at the turn's own last message, not at the next prompt.** The next
  prompt can be hours later, and closing there billed the idle gap to the turn
  that had already finished. Measured: a turn whose reply landed at t=5000 and
  whose next prompt came at t=999000 reported 998 s instead of 4 s.
- **A missing timestamp means unknown, not zero.** `TurnSummaryData.elapsedMs` is
  nullable; `formatDuration` coerces to a one-second minimum, so a message list
  without timestamps would have shown a confident `1s` for every turn in the
  conversation. The row now shows the step count alone.

The live and restored paths deliberately differ in one visible way, and it is
worth knowing rather than "fixing": the live path appends an assistant row on
`message_start`, before it knows whether the message will carry text or only a
tool call. A live tool-using turn therefore hides one more row than the same turn
restored from disk. `ChatFoldTest` pins both numbers.

## Searching a conversation's content

The history list filtered on the title alone, and a title is the first user
message — so "which conversation was that about the retry logic" was answerable
only by opening conversations until one looked right. It now searches the prose
of every transcript too.

Three decisions, each because the obvious version is too expensive or too noisy:

- **User and assistant text only.** A tool result is usually a file listing or a
  command's output, so including it would answer "which conversation mentioned
  `package.json`" with every conversation that ever ran `ls`. Only `type ==
  "text"` content blocks are read, which is also what keeps pi's echoed images —
  base64, megabytes per screenshot — out of the index.
- **A transcript is re-read only when its stamp or its length changed**
  (`SessionSearchIndex`). Otherwise the scan costs a JSON parse per message line
  on every keystroke. Both fields are compared because a file rewritten inside
  the filesystem's timestamp granularity keeps its stamp.
- **The scan is debounced by the effect that runs it**: `LaunchedEffect(query,
  summaries)` is cancelled and restarted on each keystroke, so a `delay` before
  the work is a debounce with no state of its own. A snippet is drawn only for
  the query it answered, or the previous scan's matches would be highlighted
  against text that no longer contains what is in the field.

`SessionMetadata.snippet()` bounds the shown window to about 120 characters with
ellipses, which is what stops a match in the middle of a chapter from putting the
chapter in a list row.

---
