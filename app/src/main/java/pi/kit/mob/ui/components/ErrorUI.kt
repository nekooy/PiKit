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
 *    "compaction will retry". A quieter strip on `secondaryContainer`, tappable
 *    to dismiss, because a warning that has been read is clutter.
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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A failure banner with an optional action and an optional way to put it away.
 *
 * `onAction` draws [actionLabel] as a trailing `TextButton` — the chat's Retry.
 * `onDismiss` makes the whole banner tappable — the session list's "tap to
 * clear". Both may be present (the chat's `lastError` is dismissible; its
 * `AgentStatus.Failed` is not, because the failure is still true), and neither
 * is required when the banner is purely informational.
 *
 * [maxLines] is the *body's* budget. The agent-failure banner is a stderr dump
 * and was measured at well over a screen; three lines with an ellipsis keeps
 * the banner one strip rather than the page.
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(
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
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (onAction != null && actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/**
 * A refusal under a control: the field is still the subject, this is what it
 * said when it would not take the value.
 *
 * No container and no icon. A red box under every field that has ever failed
 * turns a settings card into a Christmas tree; the colour and the position are
 * enough, and the row above already names the thing that refused.
 */
@Composable
fun InlineError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A warning that is not a failure: the reader is being told what will happen,
 * and tapping the strip puts the sentence away.
 *
 * `secondaryContainer` rather than the error colours on purpose — a notice that
 * looks like a failure trains the eye to ignore both. The composer's image
 * warnings and the like live here; anything with a Retry is an [ErrorBanner].
 */
@Composable
fun StatusNotice(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * A small stacked block of one heading and its explanation, for a page that has
 * to say "here is what went wrong and here is what to do" without a banner.
 *
 * The runtime-install failure is this: it owns the screen, there is nothing
 * under it to return to, and a dismissible strip would be a lie. The title is
 * SemiBold on its own line and the body is muted under it — the same
 * relationship `ContextPoint` draws on the Agent page, so the two prose blocks
 * in the app read as the same species.
 */
@Composable
fun ErrorBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (onAction != null && actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** Three lines: enough for the reason, short enough to stay a strip. */
private const val ERROR_BANNER_LINES = 3

private val ERROR_BANNER_PAD = 12.dp
