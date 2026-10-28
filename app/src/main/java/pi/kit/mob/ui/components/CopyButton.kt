/*
 * The copy control, in one place.
 *
 * Three callers draw it — the meta row under a message, a code block's header and a tool card's
 * output — and they are the same action on the same page: put this text on the clipboard.
 *
 * ## What it is
 *
 * **A glyph, not a button-shaped thing.** The first version of this file wrapped the glyph in a
 * filled tonal circle and offered the reader six containers to choose from; the answer came back as
 * a picture of the *glyph* they wanted — two rounded sheets, the back one peeking at the top right
 * — and no container at all. That is the correction worth keeping: under a message this sits next
 * to a 12sp timestamp and on a code block it sits next to a 12sp language label, and a chip of any
 * shape there is a second bubble, which is what the comment in `MessageMeta` had already argued
 * before the container was added.
 *
 * It is `res/drawable/ic_copy.xml` — the reader's own SVG, pasted into the conversation after four
 * rounds of candidates — drawn through Miuix's [Icon], which tints a drawable with a `SrcIn` filter
 * and therefore keeps the glyph's antialiasing. `ic_check.xml` is its confirmation.
 *
 * The box the glyph sits in is Miuix's [IconButton] at a pill corner radius — the library's own
 * round press outline, the same shape every other action in the app draws — with **no fill**:
 * [Color.Transparent] as the button's background is what keeps nothing painted behind the glyph,
 * which is the point of the four rounds above.
 *
 * ## The confirmation
 *
 * A copy button that does nothing visible leaves the reader wondering whether it worked, so the
 * glyph becomes `ic_check` for [COPIED_MILLIS] — the same weight, the same place, so it reads as
 * one control answering rather than as two icons taking turns. The swap is a [Crossfade] on
 * Miuix's own spring ([GLYPH_SWAP]) rather than a replaced drawable, because the two glyphs are
 * the same control in two states and a cut between them reads as a flicker. Nothing is announced
 * through the semantics tree: the label does not change, and a screen reader that hears "copy
 * message" twice is better than one that hears a state it cannot act on.
 */
package pi.kit.mob.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pi.kit.mob.R
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CopyButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    // The app's secondary ink by default, which is what a code block's header wants: the button
    // there sits among the block's own furniture — the language label, the same grey — and is body
    // content rather than the reader's own.
    //
    // The default used to be `LocalContentColor`, so the two call sites disagreed by accident: the
    // code block inherited whatever the bubble's `Surface` had set while the meta row asked for a
    // variant ink. The default is now stated here — Miuix's summary ink, which is its role for
    // text that is furniture rather than content — and the meta row passes the **accent** on
    // purpose, because that row is a finished turn's furniture: the same accent as the
    // `工作 X 秒 · N 个步骤` line above it and the message's own timestamp beside it. See
    // `MessageMeta`.
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            onClick()
            copied = true
            scope.launch {
                delay(COPIED_MILLIS)
                copied = false
            }
        },
        // 28dp rather than the 40dp Miuix's IconButton defaults to: this sits in a row of 12sp
        // text, under a message or across a code block's header, and a full-size disc would be a
        // second bubble. Note that `Modifier.size` *replaces* IconButton's minimum rather than
        // growing out of it, so the drawn box is also the touch target; the meta row compensates
        // by pulling the row out to the glyph's ink (see `CopyButtonInkInset`).
        modifier = modifier.size(COPY_BUTTON_SIZE),
        // Nothing painted: `Transparent` rather than Miuix's default `Color.Unspecified`, because
        // this button is deliberately shapeless — the radius is here so that the press overlay the
        // library draws is a pill rather than a rectangle, exactly as the old `clip` was.
        backgroundColor = Color.Transparent,
        cornerRadius = COPY_BUTTON_SIZE / 2,
        minWidth = COPY_BUTTON_SIZE,
        minHeight = COPY_BUTTON_SIZE,
    ) {
        Crossfade(
            targetState = copied,
            animationSpec = GLYPH_SWAP,
            label = "copyGlyph",
        ) { shown ->
            Icon(
                painter = painterResource(if (shown) R.drawable.ic_check else R.drawable.ic_copy),
                contentDescription = contentDescription,
                // 18dp: the glyph is the whole control now — Lucide's sheets are drawn with their
                // own stroke, which thins as the icon shrinks, so it needs the size more than a
                // filled glyph would.
                modifier = Modifier.size(COPY_GLYPH_SIZE),
                tint = tint,
            )
        }
    }
}

/** The button's drawn size, and the glyph inside it. */
private val COPY_BUTTON_SIZE = 28.dp
private val COPY_GLYPH_SIZE = 18.dp

/**
 * How far the glyph's ink sits inside the button, per side.
 *
 * The button is [COPY_BUTTON_SIZE] with a [COPY_GLYPH_SIZE] glyph centred in it, so this is the
 * box the *reader* does not see. It is part of the button's geometry rather than a detail of its
 * drawing, because a caller that has to line the button up with something else has to line up the
 * **ink**: `MessageMeta` pulls its row out by this much so the glyph — not the invisible box —
 * lands on the column the message's own text starts at.
 *
 * Declared after the two sizes it is derived from: a top-level property reads the ones above it
 * and this one would otherwise compute from their not-yet-assigned values.
 */
internal val CopyButtonInkInset = (COPY_BUTTON_SIZE - COPY_GLYPH_SIZE) / 2

/** Long enough to be seen, short enough not to become a state the reader has to undo. */
private const val COPIED_MILLIS = 1400L

/**
 * The two glyphs' swap: Miuix's own spring, critically damped, at the response its press
 * feedback uses.
 *
 * Read from the library rather than chosen here, because a swap between two states of one
 * control is exactly what the library's motion is for — and because the app's old motion
 * scale, `PiMotion`, was Material3's `motionScheme` and left with it.
 */
private val GLYPH_SWAP = folmeSpring<Float>(damping = 1f, response = 0.2f)
