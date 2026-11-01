package pi.kit.mob.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A full keyboard, drawn by the app, for a terminal that has no use for an IME.
 *
 * The phone's own keyboard is built for prose: it autocorrects, it capitalises, it hides
 * the characters a terminal is made of (`|`, `~`, `-`, `\`), and every one of the four
 * layouts it might choose has to be cycled through before the reader finds `Ctrl`. The
 * terminal had a bar of eight shortcuts for that reason; this panel is the rest of the
 * answer — a keyboard whose keys are the ones on a desk keyboard, in the order they are
 * on one, with the modifiers as *latches* rather than as combinations the reader has to
 * find somewhere else.
 *
 * ## What a latch does
 *
 * `Ctrl`, `Alt` and `Shift` stay down until the next key is pressed and then let go, which
 * is what a desk keyboard does with a chord and what a phone cannot do with two thumbs.
 * `Ctrl` maps a letter to its control code (`c` → `0x03`), `Alt` prefixes the escape byte
 * as a terminal's Meta does, and `Shift` uppercases a letter or picks the shifted symbol of
 * a digit. All three can be latched at once, because `Ctrl+Alt+Shift+…` is a real thing.
 *
 * ## What it is not
 *
 * It is not an IME: nothing here inserts text into a field. Every tap is a byte sequence
 * handed to the pty through [onSend], exactly as the bar's keys are, so the terminal's own
 * line editing — history, tab completion, `Ctrl+C` — is what the reader is talking to.
 */
@Composable
internal fun PcKeyboard(
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }

    // One keystroke: the latches decide what the character becomes, then they let go —
    // the release is what makes `Ctrl` a chord rather than a mode, and it is why a reader
    // who taps `Ctrl` and then changes their mind only has to tap it again.
    fun press(text: String, control: Char? = null) {
        val out = buildString {
            if (alt) append('\u001b')
            if (ctrl && control != null) {
                append((control.code and 0x1f).toChar())
            } else {
                append(if (shift) text.uppercase() else text)
            }
        }
        onSend(out)
        ctrl = false
        alt = false
        shift = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KEYBOARD_BACKGROUND)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyRow {
            KeyboardKey("Esc", weight = 1.3f, small = true) { onSend("\u001b") }
            for (digit in '1'..'9') {
                KeyboardKey(digit.toString(), control = digit) { press(digit.toString(), digit) }
            }
            KeyboardKey("0", control = '0') { press("0", '0') }
            KeyboardKey("⌫", weight = 1.3f) { onSend("\u007f") }
        }
        KeyRow {
            KeyboardKey("Tab", weight = 1.3f, small = true) { onSend("\t") }
            for (letter in "qwertyuiop") {
                KeyboardKey(letter.toString(), control = letter) { press(letter.toString(), letter) }
            }
            KeyboardKey("/", control = '/') { press("/", '/') }
        }
        KeyRow {
            ModifierKey("Ctrl", on = ctrl, weight = 1.3f) { ctrl = !ctrl }
            for (letter in "asdfghjkl") {
                KeyboardKey(letter.toString(), control = letter) { press(letter.toString(), letter) }
            }
            KeyboardKey(";", control = ';') { press(";", ';') }
            KeyboardKey("↵", weight = 1.3f) { onSend("\r") }
        }
        KeyRow {
            ModifierKey("Shift", on = shift, weight = 1.5f) { shift = !shift }
            for (letter in "zxcvbnm") {
                KeyboardKey(letter.toString(), control = letter) { press(letter.toString(), letter) }
            }
            KeyboardKey(",", control = ',') { press(",", ',') }
            KeyboardKey(".", control = '.') { press(".", '.') }
            KeyboardKey("-", control = '-') { press("-", '-') }
            KeyboardKey("↑", weight = 1.1f) { onSend("\u001b[A") }
        }
        KeyRow {
            ModifierKey("Alt", on = alt, weight = 1.3f) { alt = !alt }
            KeyboardKey("←", weight = 1.1f) { onSend("\u001b[D") }
            KeyboardKey("↓", weight = 1.1f) { onSend("\u001b[B") }
            KeyboardKey("→", weight = 1.1f) { onSend("\u001b[C") }
            KeyboardKey("Space", weight = 4f) { press(" ") }
            KeyboardKey("|", control = '|') { press("|", '|') }
            KeyboardKey("~", control = '~') { press("~", '~') }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One key cap.
 *
 * [control] is the character `Ctrl` maps to its control code, and it is null for the keys
 * that have none (the arrows, the escapes). [small] is for the two words that are wider
 * than one character in a slot built for one — `Esc` and `Tab` are set a size down so they
 * fit the same height as everything else rather than making their row taller.
 */
@Composable
private fun RowScope.KeyboardKey(
    label: String,
    weight: Float = 1f,
    small: Boolean = false,
    control: Char? = null,
    onPress: () -> Unit,
) {
    KeyCap(
        label = label,
        weight = weight,
        small = small,
        onPress = onPress,
    )
}

@Composable
private fun RowScope.ModifierKey(
    label: String,
    on: Boolean,
    weight: Float,
    onPress: () -> Unit,
) {
    KeyCap(
        label = label,
        weight = weight,
        small = true,
        active = on,
        onPress = onPress,
    )
}

@Composable
private fun RowScope.KeyCap(
    label: String,
    weight: Float,
    small: Boolean,
    active: Boolean = false,
    onPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(KEYBOARD_KEY_HEIGHT)
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) KEYBOARD_ACTIVE else KEYBOARD_KEY)
            .clickable(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Monospace,
            color = if (active) KEYBOARD_BACKGROUND else KEYBOARD_INK,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}

private val KEYBOARD_BACKGROUND = Color(0xFF2C2C2E)
private val KEYBOARD_KEY = Color(0xFF4A4A4C)
private val KEYBOARD_ACTIVE = Color(0xFF8FC1FF)
private val KEYBOARD_INK = Color(0xFFF2F2F7)
private val KEYBOARD_KEY_HEIGHT = 34.dp
