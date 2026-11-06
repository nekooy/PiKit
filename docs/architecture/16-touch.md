# Touch: the four moments PiKit buzzes

The interface is Miuix's (§13), and Miuix's components already buzz where the
library decided they should. What this app drew itself — a message card, a
conversation row, a file row, a terminal bar, an on-screen keyboard, twelve menus —
had no rule at all: two long-press buzzes were placed by hand, in two screens, and
everything else was silent. That is the state this chapter replaces. It is not "add
haptics", it is **four moments and a longer list of silences**.

**A buzz is a control's acknowledgement, not the outcome's notification.** The screen
already tells the reader what happened; a vibration that repeats it is noise. What a
buzz adds is the one thing a glass control cannot draw: that the touch was a control
and not a scroll, and that a gesture was recognised rather than ignored.

## What the library does, measured

Read out of `miuix-ui-android` 0.9.4's own bytecode, because the library documents
none of it and a second buzz is the failure this chapter exists to prevent:

| Component | Performs |
| --- | --- |
| `Switch`, `RadioButton`, `Checkbox` | `ToggleOn` / `ToggleOff` on the flip |
| `Slider`, `RangeSlider` | `TextHandleMove` per step |
| `NumberPicker`, `PullToRefresh`, the scroll-end bounce | one at their thresholds |

So a settings page is already tactile without a line of this app's code: its rows are
`SwitchPreference`, `RadioButtonPreference` and `SliderPreference`. **A page that wraps
one of those must not add its own.** The two arrive within a few milliseconds of each
other, and two light ticks together are one heavy buzz — the opposite of the effect.

## The vocabulary

Four moments, four platform types, one file: `ui/kit/PiHaptics.kt`.

| Moment | Call | Type | API < 34 | API < 30 |
| --- | --- | --- | --- | --- |
| a hold became a recognised gesture | `hold()` | `LongPress` | — | — |
| a control's mode flipped and stays flipped | `toggle(on)` | `ToggleOn` / `ToggleOff` | `CONTEXT_CLICK` / `CLOCK_TICK` | — |
| a row that is one of a set became the chosen one | `choose()` | `ToggleOn` | `CONTEXT_CLICK` | — |
| a key on a keyboard this app draws | `key()` | `KeyboardTap` | — | — |

The fallbacks are `HapticFeedbackConstantsCompat`'s, from AndroidX core 1.19.1, which
is what `HapticFeedback` calls through: an effect the device does not have is replaced
by the nearest one it does (`ToggleOn` → `CONTEXT_CLICK` below API 34, `Confirm` →
`VIRTUAL_KEY` below 30, `TextHandleMove` → nothing below 27). A buzz defined on an
Android 16 phone therefore still lands on this app's API 26 floor, one step firmer than
intended, and never becomes a call the framework silently drops.

**`choose()` is `ToggleOn` because a radio list is a set of switches.** Miuix's own
radio row performs exactly that, and the same question — which model, which tier, which
shell — must not feel different depending on whether it was asked in a settings list or
in the composer's menu.

**`key()` is the one place the lightest type is used**, and the reason is physical: a
keyboard drawn on glass has no travel and no click. This app draws two of them — the
terminal's bar and its full PC keyboard (`PcKeyboard`) — and neither is an IME, so the
IME's own keypress setting cannot speak for them. The text field is not this case: its
keys are the platform's, and a second tick from the app would double them and honour
neither the user's setting nor the theme.

**There is no `reject()` and no `confirm()`.** Both were considered and dropped: every
refusal this app can produce (an invalid path, a failed restore, a refused
`pkg install`) already arrives as a sentence in the error role or a toast, and every
completion already shows its result — a message in the transcript, a row that
disappears, a count that moves. A vocabulary with two moments nobody can name a caller
for is a vocabulary that grows a fifth moment at the first ambiguous call site.

### Placement

Haptics are events, so they are called from handlers, never during composition, and
**before** the state change:

```kotlin
onSelectedIndexChange = {
    if (show) {          // the menu's exit gate, as every menu in this app has
        haptics.choose()
        onDismiss()
        session.selectProfile(profile, model)
    }
}
```

A pick that changes nothing — the model already in effect, the row already chosen —
does not buzz: there was no transition, only a tap. The menu's `show` gate comes first
for the same reason the action does: the panel is drawn for the length of its exit, and
a second tap aimed at the page behind a menu that is already leaving must not buzz.

## The silences

Named here, because the restraint is the design and an omission would be
indistinguishable from an oversight:

- **Navigation.** A page push, the drawer, a tab, the back arrow, a sheet or a window
  opening or closing. The transition is the acknowledgement. An app that buzzes on
  every page change reads as nervous rather than solid, and the same motion that tells
  the reader where they are already has their attention.
- **A control whose only answer is the press.** Buttons, icon buttons, plain rows, chips:
  the fill under the finger is drawn at touch-down, which is before any buzz could
  arrive, and it is more precise than a vibration — it says *which* control. A control
  that *changes state* is not this case: a radio row paints a press **and** buzzes,
  because the press says "you touched me" and the buzz says "and I am the answer now".
  The line is what the control does, not whether it has a ripple.
- **Typing into the text field.** The IME owns key feedback, as a user setting.
- **Scrolling, page swipes, the elastic over-scroll.** Motion is the answer. (Miuix adds
  its own soft one at the ends of a bounce; that is the library's.)
- **A result the same frame already shows.** Sending a message — the transcript is the
  answer, and the send disc is the one control where an extra tick would fire dozens of
  times per conversation; copying — the toast; deleting a conversation — the row
  leaving. This is the largest group in the app, and it is why the count of call sites
  is in the tens rather than the hundreds.

## Where the app buzzes for the library

Three places are the app's because the library cannot be the one to buzz:

**A hand-built row that replaces a library row carries the library's behaviour.**
`PiMenuRow` exists because a `BasicComponent` with end actions cannot be measured
inside a `ListPopupColumn` (§13), and the row it replaces — `RadioButtonPreference` —
buzzes. `PiMenuRow` therefore buzzes when it is a *choice* (`selected != null`) and
stays silent when it is an *action* (`onClick` with no `selected`) or a *fact*
(no `onClick`); the row already declares which of the three it is, and the gesture
follows the declaration. Without this the same act ticked in one menu and not in
another, which is the inconsistency this chapter removes.

**The conversation row that flips a checkbox.** In selection mode the trailing Miuix
`Checkbox` buzzes when it is tapped, and the row does not — so the same act had two
feels depending on where the thumb landed. The row buzzes too, and the checkbox keeps
its own; the two hit regions are exclusive, so nothing double-buzzes.

**A dropdown menu's chosen row.** `DropdownImpl` does not tick — measured, above —
although the same question in a settings list is a radio row that does. The app calls
`choose()` at the menu's call site, where the answer is accepted, rather than forking
the library's row.

And one gesture the library has no component for at all: **a hold that raises
something** — a message's menu, a conversation's actions, a file's actions. The
conversation row paints no press state at all (a fill that tracks the finger ends when
the finger does, while the menu it raised stays), and the file card's sink is a *press*
— drawn at touch-down, gone at touch-up — not an acknowledgement that the gesture was
recognised. Without the buzz, the half-second before the menu appears is a hold with no
answer, which reads as a tap that arrived late. That is the oldest rule in this chapter
and the only one the app had before it: both existing call sites were this moment.

## One door

`LocalPiHaptics` is provided by `PiKitMiuixTheme`, not by a page, because a window
dialog is a second composition — a value provided by the page would be missing in
exactly the window whose buttons need it, and a dialog's buttons would be silent while
the page's were not. The default outside the theme is a no-op, so a preview or a
screenshot harness composes a screen without a window and does not crash on a buzz.

`tools/check-haptics.py` fails the build when anything outside the kit calls
`performHapticFeedback` or names a `HapticFeedbackType` — a fifth moment invented at a
call site compiles, reads like any other line, and is exactly the drift this chapter
exists to stop — and when the kit's set of moments is not the four in the table above.
Adding a moment is therefore a change to this file and to the checker's list in the
same commit.

What no checker can see is judgement: whether a particular call site deserves its
buzz. That is this chapter's table and the review's job.
