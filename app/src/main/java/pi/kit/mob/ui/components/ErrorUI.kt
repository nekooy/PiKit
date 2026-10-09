/*
 * The app's one error vocabulary.
 *
 * Failures used to be drawn seven different ways — a `Surface(errorContainer)`
 * with a Retry button in the chat, the same surface without one in the session
 * list, bare red `Text` under a settings field, a `StatusLine` with its own tone
 * enum on the backup page, a red row value, a per-message line in the
 * transcript, and a full-screen `MessageScreen`. The shapes were close enough
 * to read as one family and different enough that a reader could not tell a
 * dismissible warning from a blocking failure by looking at it.
 *
 * This file is the family. Three parts, chosen by what the failure *is* rather
 * than by which page drew it:
 *
 *  - [ErrorBanner] — something went wrong and there is an action (Retry) or a
 *    way to put it away. Sits in the flow, above the control it is about.
 *  - [InlineError] — a field or a save refused. A caption under the control,
 *    in the error colour, no container: the control is still the subject.
 *  - [StatusNotice] — not an error at all: "the image will not be sent",
 *    "compaction will retry". A quieter strip, tappable to dismiss, because a
 *    warning that has been read is clutter.
 *
 * The colour is [PiTone]'s rather than a role picked here, so "failed" is one
 * decision in one place; [InlineError] and [StatusNotice] are the design system's
 * `PiNotice` outright.
 *
 * [ErrorBanner] is the one that is not, and for a measured reason: `PiNotice` has
 * no cap on its body, while this banner's body is often the agent's own stderr —
 * measured at well over a screen — and has to stay a strip at [maxLines]. So the
 * banner keeps its own two-slot row and takes its fill, its ink, its corner and
 * its action from the same vocabulary: `PiTone.Danger`'s container pair,
 * `PiShapes.row`, and a `PiButton`.
 *
 * The in-transcript errors (a message's `error` line, a failed tool card) stay
 * where they are: they are *content* the conversation produced, not chrome the
 * app added, and a banner around them would make one turn look like the app
 * itself had failed.
 *
 * Everything user-visible is passed in by the caller — the app's text lives in
 * the three catalogs under `locales/`, and nothing here may invent a string.
 */
package pi.kit.mob.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTone

/**
 * A failure banner with an optional action and an optional way to put it away.
 *
 * `onAction` draws [actionLabel] as a trailing button — the chat's Retry.
 * `onDismiss` makes the whole banner tappable — the session list's "tap to
 * clear". Both may be present (the chat's `lastError` is dismissible; its
 * `AgentStatus.Failed` is not, because the failure is still true), and neither
 * is required when the banner is purely informational.
 *
 * [maxLines] is the *body's* budget. The agent-failure banner is a stderr dump
 * and was measured at well over a screen; three lines with an ellipsis keeps
 * the banner one strip rather than the page. It is the reason this is not
 * `PiNotice`: that component's body has no cap.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    maxLines: Int = ERROR_BANNER_LINES,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Clipped before the tap is attached: a `clickable`'s indication is a
            // rectangle, and on a rounded banner it would paint square corners
            // outside the fill.
            .clip(PiShapes.row)
            .background(PiTone.Danger.container(MaterialTheme.colorScheme))
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(
                start = ERROR_BANNER_PAD,
                end = if (onAction != null) 4.dp else ERROR_BANNER_PAD,
                top = 2.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            color = PiTone.Danger.onContainer(MaterialTheme.colorScheme),
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (onAction != null && actionLabel != null) {
            PiButton(
                text = actionLabel,
                onClick = onAction,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
            )
        }
    }
}

/**
 * A warning that is not a failure: the reader is being told what will happen,
 * and tapping the strip puts the sentence away.
 *
 * The accent tone rather than the danger one on purpose — a notice that looks
 * like a failure trains the eye to ignore both. It used to be
 * `secondaryContainer`, which `PiTone` has no role for: the four tones are
 * neutral, accent, live and danger, and a remark the app is making about the
 * page it is showing is the *accent* one. The composer's image warnings and the
 * like live here; anything with a Retry is an [ErrorBanner].
 */
@Composable
fun StatusNotice(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    PiNotice(
        text = message,
        modifier = modifier
            .clip(PiShapes.row)
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
        tone = PiTone.Accent,
    )
}

/** Three lines: enough for the reason, short enough to stay a strip. */
private const val ERROR_BANNER_LINES = 3

private val ERROR_BANNER_PAD = 12.dp
