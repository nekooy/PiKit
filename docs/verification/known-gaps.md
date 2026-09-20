# Known gaps

*[Verification](../VERIFICATION.md): what has not been verified, what needs a person rather
than an instrument, and what is accepted rather than fixed.*

## Needs a person, not an instrument

- **Text selection across a display formula.** `adb shell input swipe` does not raise Compose's
  selection toolbar on this emulator — a stationary three-second press and a 2.5-second drag both
  left the screen unchanged — so this is unverified, and it is the headline promise of the
  formula-as-text change. The accessibility tree does show a display formula publishing semantics
  (`content-desc="fraction: 1 over 2"`), which is consistent with it being inside the text and is not
  the same thing ([Markdown and formulas](markdown-and-formulas.md)).
- **The camera entry.** `拍照` (`Take a photo`) is a `PickerOption` whose tap launches
  `ActivityResultContracts.TakePicture` against a `FileProvider` URI in the app's cache; the round
  trip into the composer and the permission refusal path are unverified.
- **The `!` prefix route.** `adb shell input text` will not deliver an exclamation mark to a Compose
  text field here, so the `!`-prefixed path is unexercised — a limit of the instrument rather than a
  finding. Both routes call the same function (the sheet's `PiAgentSession.runBash(line.trim())` and
  `ChatScreen`'s `onSend` `runBash(prompt.removePrefix("!").trim())`), so a device pass would settle
  it; the sheet's own `!` row is verified ([On the emulator](emulator.md)).
- **The command button's mark**, Lucide's `command` (⌘) at 17 dp rather than the 21 dp the Material
  glyphs beside it use. Verified in a screenshot on the emulator, so it is a matter of taste rather
  than of evidence: one borrowed glyph at a call-site size (`AGENTS.md`), not a redrawn vector.
- **A reply's chunking**, the fix that would bound the frame opening a long reply, is not made —
  [The transcript's frame cost](transcript-performance.md) has what it is and why it is the
  remaining one.

## The model page's custom-parameters section

Rewritten and not measured. It is now one group of rows per model — a heading row naming the model,
the image switch, and the two numbers as rows edited in a sheet — instead of a switch row plus two
outlined boxes. What a `uiautomator` pass has to settle: the 38dp indent that aligns the number rows'
labels under the switch row's title (it is arithmetic on another file's layout, so it is exactly the
kind of thing that is off by a few pixels), whether a heading row, three controls and a divider still
read as one group at font scale 1.8, and whether the sheet's field keeps focus with the keyboard up —
it is a sheet rather than a dialog for that reason.

## The first-launch screen's colour

The screen is white on black now, and that is a colour claim: the mark and the progress bar were
amber, and both are the launcher icon's white (`ic_pi_mark.xml`, `SETUP_FOREGROUND`). No test can see
a colour, so this one is verified by reading the two drawables and the palette — the icon checker
covers the launcher icon only.

## Two rows with no `uiautomator` pass

The search page's "add an option" flow (an ~80-row picker with a filter field, and a value sheet
holding a text field) and the workspace default on the agent page. Their behaviour below the UI is
covered — `WebSearchStoreTest` for the store round trip, `tools/test-safety-guard.mjs` for the rules
— but this project's bar for a *layout* claim is node bounds, and these two have none: the picker's
list height against the keyboard, and that the value sheet's field keeps focus (it is a sheet rather
than a dialog for exactly that reason).

## "Open with" on a file under `~/storage`

One device check is outstanding. The cause is established and fixed — `FileProvider.getUriForFile`
resolves a file's *canonical* path, so a path that reads as
`…/files/home/storage/shared/Download/a.txt` arrives as `/storage/emulated/0/Download/a.txt` and
matched none of the configured roots, which is why the tap did nothing — and `res/xml/file_paths.xml`
now carries an `external-path` root. What is not yet demonstrated on a device is the round trip into
a receiving app, and the chooser's behaviour for a MIME type nothing claims.

## Accepted, rather than fixed

**Three `dpkg` packaging-developer scripts** (`dpkg-buildapi`, `dpkg-buildtree`,
`dpkg-fsys-usrunmess`) want `perl`, which is not bundled. `apt`, `dpkg` and `pkg` install and remove
packages without it, and the official bootstrap has the same omission.
