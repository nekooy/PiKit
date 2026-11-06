package pi.kit.mob.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The app's haptics: four moments, one vocabulary, one file.
 *
 * ## Why a kit and not a call at the control
 *
 * `LocalHapticFeedback` is two lines away from every `onClick` in the tree, and that is
 * exactly the problem: four screens that each decide for themselves when a buzz is
 * warranted will disagree, and the disagreement is invisible in code review — a
 * `performHapticFeedback(HapticFeedbackType.ToggleOn)` here and a `LongPress` there
 * look like two choices rather than one missing standard. Every buzz in this app goes
 * through [PiHaptics], `tools/check-haptics.py` fails the build when one does not, and
 * the two comments that matter — which moments buzz and which deliberately do not —
 * are written once instead of at forty call sites.
 *
 * ## The four moments
 *
 * The vocabulary is Miuix's own, extended by the two gestures the library has no
 * component for. That is the whole rule: **PiKit buzzes where the library buzzes, plus
 * a hold, plus a key**.
 *
 * | Moment | Call | Platform type | On API < 34 | On API < 30 |
 * | --- | --- | --- | --- | --- |
 * | a hold became a recognised gesture | [hold] | `LongPress` | `LONG_PRESS` | |
 * | a control's mode flipped and stays flipped | [toggle] | `ToggleOn` / `ToggleOff` | `CONTEXT_CLICK` / `CLOCK_TICK` | |
 * | a row that is one of a set became the chosen one | [choose] | `ToggleOn` | `CONTEXT_CLICK` | |
 * | a key on a keyboard PiKit draws was pressed | [key] | `KeyboardTap` | | |
 *
 * The fallbacks are not guesses: they are `HapticFeedbackConstantsCompat`'s answer in
 * AndroidX core 1.19.1, which maps a constant the device does not have onto the nearest
 * one it does (`ToggleOn` → `CONTEXT_CLICK` below API 34, `Confirm` → `VIRTUAL_KEY`
 * below 30, `TextHandleMove` → nothing below 27). A buzz written against the newest
 * platform therefore still lands on an Android 9 phone, one step firmer than intended,
 * and never turns into a call the framework ignores.
 *
 * ## Why [choose] is `ToggleOn`
 *
 * Because a radio list *is* a set of switches: Miuix's `RadioButton`,
 * `RadioButtonPreference` and `Switch` all perform `ToggleOn`/`ToggleOff` themselves,
 * and a choice that buzzes in a radio list and is silent in a menu would be two
 * answers to one question. The library's `DropdownImpl` — the row the composer's
 * model, thinking and permission menus are built from — does not tick, and that is a
 * gap in one component rather than a rule: the same "which of these is in effect"
 * question is a radio row on the settings pages and a dropdown in the composer. The
 * drop-down's call sites therefore buzz themselves, at the moment they accept the
 * answer.
 *
 * ## What never buzzes
 *
 * Named here because the restraint is the design, not an omission:
 *
 * - **Navigation.** A page push, the drawer, a tab, the back arrow, a sheet or a
 *   window opening or closing. The transition is the acknowledgement, and a page
 *   change that also buzzes makes the app feel nervous rather than solid.
 * - **A control whose only answer is the press.** Buttons, icon buttons, plain rows,
 *   chips: the fill under the finger is the answer, and it is drawn at touch-down
 *   before a buzz could arrive. A control that *changes state* is not this case — a
 *   radio row paints a press and buzzes, because the press says "you touched me" and
 *   the buzz says "and I am the answer now".
 * - **Typing into a text field.** The soft keyboard's own keypress feedback is a user
 *   setting in the IME, and the composer's field is the IME's client — a second buzz
 *   per character from the app would double it and honour neither setting.
 * - **Scrolling, page swipes, the elastic over-scroll.** Motion is the answer. (Miuix's
 *   own list adds a soft one at the ends of a bounce; that is the library's, not ours.)
 * - **A result the same frame already shows.** Sending, copying, deleting: the
 *   transcript, the toast and the disappearing row are louder than a buzz, and a
 *   vibration on top of them is noise.
 *
 * ## Never twice
 *
 * Miuix already performs haptics inside `Switch`, `Checkbox`, `RadioButton`,
 * `SwitchPreference`, `RadioButtonPreference`, `Slider`, `RangeSlider`,
 * `NumberPicker`, `PullToRefresh` and its scroll-end bounce. A screen that wraps one of
 * those must not add its own — the two arrive within a few milliseconds of each other
 * and read as one heavy buzz, which is the opposite of the light tick intended.
 *
 * Call `haptics.*` from the *handler* (`onClick` before the state change, or the
 * long-press lambda before the surface is raised). Reading it during composition is
 * meaningless: a haptic is an event.
 */
@Stable
class PiHaptics internal constructor(
    private val feedback: HapticFeedback?,
) {
    /**
     * A hold became a recognised gesture — the message menu, a conversation's actions,
     * the file list's actions.
     *
     * The hold paints no state of its own. A card that lit while the finger rested on it
     * was tried and removed, because a fill that tracks the touch ends when the touch
     * does while the menu it raised stays — and the one row that does sink under the
     * finger (the file list's card) is drawing its *press*, not the answer to the hold.
     * This is what says the five hundred milliseconds were a gesture rather than a tap
     * that arrived late.
     */
    fun hold() {
        feedback?.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * A control's mode flipped: a keyboard latch (`Ctrl`, `Alt`, `Shift`), the
     * terminal's follow-the-output switch, the bar's hardware-keyboard mode.
     *
     * [on] is the mode *after* the tap, which is the direction the platform's two
     * constants distinguish. A control that is a value rather than a mode — a slider,
     * a text size — is not this: `Slider` buzzes per step itself.
     */
    fun toggle(on: Boolean) {
        feedback?.performHapticFeedback(
            if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )
    }

    /**
     * One row of a set became the chosen one: the model, the thinking level, the
     * permission tier, a shell in the roster, a provider in a picker.
     *
     * Only the *choice*: a menu row that runs a command (copy, close all shells, attach
     * a file) is a button in a list and does not buzz — its effect is the answer.
     */
    fun choose() {
        feedback?.performHapticFeedback(HapticFeedbackType.ToggleOn)
    }

    /**
     * A key on a keyboard this app draws: the terminal's bar and its on-screen PC
     * keyboard.
     *
     * The lightest type the platform has, and the one place it is used: a glass key has
     * no travel and no click, so it is the one control that cannot acknowledge the
     * press any other way. The text field is not this — the IME owns its own feedback.
     */
    fun key() {
        feedback?.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    }
}

/**
 * The haptics, provided once at the top of the composition.
 *
 * Outside [PiHapticHost] this is a no-op: a preview, a screenshot harness or a unit
 * test composes a screen without a window and must not crash on a buzz.
 */
val LocalPiHaptics = staticCompositionLocalOf { PiHaptics(null) }

/**
 * Provides [PiHaptics] to everything below, with the platform's own feedback.
 *
 * The theme calls this, so every page, dialog and sheet inherits it — a window opened
 * from a page composes a *second* composition, and a value provided by the page rather
 * than by the theme would be absent in exactly that window. A dialog's buttons would
 * be silent while the page's were not, which is the sort of asymmetry nobody can name
 * and everybody feels.
 */
@Composable
fun PiHapticHost(content: @Composable () -> Unit) {
    val feedback = LocalHapticFeedback.current
    val haptics = remember(feedback) { PiHaptics(feedback) }
    CompositionLocalProvider(LocalPiHaptics provides haptics, content = content)
}
