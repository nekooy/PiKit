/*
 * Everything that reports state rather than doing something: the loading
 * indicator, the agent's own mark, badges, notices and progress.
 *
 * ## The loading indicator replaces every spinner
 *
 * Material's expressive loading indicator is the recommended replacement for the
 * indeterminate circular progress indicator, and this app had eleven of those —
 * one per page that loads something — plus the setup screen's determinate bar. The
 * difference is not decoration: a spinner tells the reader that *something* is
 * happening, while a shape that morphs through a set of silhouettes tells them that
 * *this* thing is happening, because the shapes are the app's own. Every indicator
 * here morphs through the same three polygons, which is what makes one at the top
 * of a list and one inside a pill recognisably the same component.
 *
 * ## Coral means live
 *
 * `tertiary` is the app's one reserved hue, and it means *running*: an agent
 * mid-turn, a process that is still going. Everything else that reports state uses
 * a neutral or the primary role. A single-hue vocabulary for one condition is what
 * makes the colour informative instead of merely accentual, and it is the reason
 * [PiTone.Live] exists as a distinct tone rather than being an `accent` flag.
 */
package pi.kit.mob.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three silhouettes every indicator in the app morphs between.
 *
 * Seven-sided, four-sided and a soft burst, in that order: the set starts and ends
 * on a figure that reads as *this app* and passes through one that reads as *a
 * shape*, so the loop has a recognisable frame rather than being an abstract
 * oscillation. Held in a `remember` because a new list every frame would restart
 * the indicator's own animation.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun indicatorShapes() = remember {
    listOf(
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.SoftBurst,
    )
}

/** A standalone working indicator, for a page that is loading something. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiLoading(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(
        modifier = modifier.size(size),
        color = color,
        polygons = indicatorShapes(),
    )
}

/**
 * A small working indicator and a word, as one pill.
 *
 * The label is not optional. A bare indicator inside a transcript says "wait" and
 * leaves the reader to guess what for; the three places this is used all have a
 * different answer — the agent is thinking, a tool is running, a session is
 * loading — and the pill is the smallest thing that can carry it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiWorkingPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: PiTone = PiTone.Live,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fill = tone.container(MaterialTheme.colorScheme)
    val ink = tone.onContainer(MaterialTheme.colorScheme)

    Row(
        modifier = modifier
            .clip(PiShapes.pill)
            .background(fill)
            .height(28.dp)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LoadingIndicator(
            modifier = Modifier.size(18.dp),
            color = ink,
            polygons = indicatorShapes(),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/**
 * The agent's mark: the app's one abstract silhouette, in a container.
 *
 * [working] swaps the filled silhouette for a morphing indicator *in the same
 * footprint*, which is the whole reason the mark is a shape from the library rather
 * than a drawable: the transition from "idle" to "thinking" is a morph between two
 * figures of the same family rather than one image replacing another.
 */
@Composable
fun PiAgentMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    working: Boolean = false,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    ink: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val fill by animateColorAsState(
        targetValue = if (working) MaterialTheme.colorScheme.tertiaryContainer else container,
        animationSpec = PiMotion.defaultEffects(),
        label = "agentFill",
    )
    val content by animateColorAsState(
        targetValue = if (working) MaterialTheme.colorScheme.onTertiaryContainer else ink,
        animationSpec = PiMotion.defaultEffects(),
        label = "agentInk",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(PiShapes.agent)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            PiLoading(size = size * 0.6f, color = content)
        } else {
            Box(
                Modifier
                    .size(size * 0.42f)
                    .clip(PiShapes.agent)
                    .background(content),
            )
        }
    }
}

/**
 * What a piece of state means, rather than what colour it should be.
 *
 * Four tones is the whole vocabulary: neutral for a fact, accent for the app's own
 * emphasis, live for a thing that is running, danger for a thing that failed. A
 * caller picks a meaning and the roles follow, which is what stops one page's
 * "warning" from being another page's "information".
 */
enum class PiTone {
    Neutral,
    Accent,
    Live,
    Danger,
    ;

    @Composable
    fun container(scheme: androidx.compose.material3.ColorScheme): Color = when (this) {
        Neutral -> scheme.surfaceContainerHigh
        Accent -> scheme.primaryContainer
        Live -> scheme.tertiaryContainer
        Danger -> scheme.errorContainer
    }

    @Composable
    fun onContainer(scheme: androidx.compose.material3.ColorScheme): Color = when (this) {
        Neutral -> scheme.onSurfaceVariant
        Accent -> scheme.onPrimaryContainer
        Live -> scheme.onTertiaryContainer
        Danger -> scheme.onErrorContainer
    }

    @Composable
    fun ink(scheme: androidx.compose.material3.ColorScheme): Color = when (this) {
        Neutral -> scheme.onSurfaceVariant
        Accent -> scheme.primary
        Live -> scheme.tertiary
        Danger -> scheme.error
    }
}

/** A small count or state, on a pill. */
@Composable
fun PiBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: PiTone = PiTone.Neutral,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = tone.onContainer(MaterialTheme.colorScheme),
        maxLines = 1,
        modifier = modifier
            .clip(PiShapes.pill)
            .background(tone.container(MaterialTheme.colorScheme))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * A sentence about the page, in the page.
 *
 * An in-page notice rather than a snackbar: every message this app produces is about
 * something the reader is looking at — a rename that failed, a session that could
 * not be opened — and a bar at the bottom of the screen that disappears after four
 * seconds is the wrong place for a sentence whose subject is still on screen. It
 * uses [PiTone.ink] for its text rather than a container, so it reads as a remark
 * rather than as a control.
 */
@Composable
fun PiNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: PiTone = PiTone.Danger,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PiShapes.row)
            .background(tone.container(MaterialTheme.colorScheme))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = tone.onContainer(MaterialTheme.colorScheme),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone.onContainer(MaterialTheme.colorScheme),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Spacer(Modifier.width(4.dp))
            action()
        }
    }
}

/**
 * Definite progress, as a bar.
 *
 * The app's one determinate indicator, used by the setup screen — where the
 * fraction is real and the reader is waiting on a measured 110 MB — and by nothing
 * else. A determinate bar for a step count that is not a fraction of the work is a
 * lie told in a progress bar, which is why every other wait in the app is
 * [PiLoading] or [PiWorkingPill].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(PiShapes.pill),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        // The library's own rounded caps and the gap between the bar and its stop
        // indicator are sized for a 4dp track; on 8dp the gap is most of the bar.
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/** A round swatch, for a colour choice or a status dot. */
@Composable
fun PiDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
            .then(modifier),
    )
}
