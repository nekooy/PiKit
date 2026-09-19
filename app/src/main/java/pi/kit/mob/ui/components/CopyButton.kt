/*
 * The copy control, in one place.
 *
 * Two callers draw it — the meta row under a message and the header of a code block — and they are
 * the same action on the same page: put this text on the clipboard.
 *
 * ## What it is
 *
 * **A glyph, not a button-shaped thing.** The first version of this file wrapped the glyph in a
 * filled tonal circle and offered the reader six containers to choose from; the answer came back as
 * a picture of the *glyph* they wanted — Lucide's `copy`, two rounded sheets — and no container at
 * all. That is the correction worth keeping: under a message this sits next to a 12sp timestamp and
 * on a code block it sits next to a 12sp language label, and a chip of any shape there is a second
 * bubble, which is what the comment in `MessageMeta` had already argued before the container was
 * added.
 *
 * It is [PiIcons.Copy] rather than Material's `ContentCopy` because Material draws two
 * square-cornered sheets as solid shapes, and the rounded outline is what the reader asked for.
 *
 * ## The confirmation
 *
 * A copy button that does nothing visible leaves the reader wondering whether it worked, so the
 * glyph becomes [PiIcons.Check] for [COPIED_MILLIS] — the same set, the same weight, the same
 * place, so it reads as one control answering rather than as two icons taking turns. Nothing is
 * announced through the semantics tree: the label does not change, and a screen reader that hears
 * "copy message" twice is better than one that hears a state it cannot act on.
 */
package pi.kit.mob.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // code block inherited whatever the bubble's `Surface` had set (`onSurface`, near-black in the
    // light theme) while the meta row asked for `onSurfaceVariant` — the report "代码块边上的复制按钮
    // 颜色太深了". The default is now stated here, and the meta row passes the **accent** on
    // purpose, because that row is a finished turn's furniture: the same blue as the
    // `工作 X 秒 · N 个步骤` line above it and the message's own timestamp beside it. See
    // `MessageMeta`.
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        // 28dp rather than Material's 48dp: this sits in a row of 12sp text, under a message or
        // across a code block's header, and a full-size disc would be a second bubble. The touch
        // target is not 28dp — `IconButton` keeps the 48dp minimum around it, which is what the
        // message meta row was already relying on.
        modifier = modifier.size(COPY_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = if (copied) PiIcons.Check else PiIcons.Copy,
            contentDescription = contentDescription,
            // 18dp: the glyph is the whole control now, so it carries the weight the 13dp one
            // could not — and Lucide's sheets are drawn with their own stroke, which thins as the
            // icon shrinks, so it needs the size more than a filled Material glyph would.
            modifier = Modifier.size(COPY_GLYPH_SIZE),
            tint = tint,
        )
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
