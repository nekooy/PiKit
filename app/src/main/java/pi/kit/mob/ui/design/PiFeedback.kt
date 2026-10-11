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
 * ## One role means live
 *
 * `tertiary` is the app's one role with a single job, and the job is *running*: an
 * agent mid-turn, a process that is still going. Everything else that reports state
 * uses a neutral or the primary role. One role for one condition is what makes the
 * colour informative instead of merely accentual, and it is the reason
 * [PiTone.Live] exists as a distinct tone rather than being an `accent` flag.
 *
 * It used to be a reserved coral, and that is the part that did not survive: an
 * orange patch in a blue interface is a colour the user did not choose and cannot
 * change. The *hue* now comes from the accent — a neighbour of it, at a lower
 * saturation (see `PiColor.accentScheme`) — so the role is still tellable from
 * [PiTone.Accent] without being a colour from outside the scheme.
 */
package pi.kit.mob.ui.design

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.ui.components.PiMark

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
 * A small pill that reports the state of one piece of work: a word, and either the
 * live indicator while it runs or a mark the caller supplies once it has settled.
 *
 * The label is not optional. A bare indicator inside a transcript says "wait" and
 * leaves the reader to guess what for; the three places this is used all have a
 * different answer — the agent is thinking, a tool is running, a session is
 * loading — and the pill is the smallest thing that can carry it.
 *
 * [mark] is what makes the pill able to hold a *settled* state as well as a running
 * one: `null` draws the morphing indicator (the default, and the whole of what a
 * running thing shows), and a caller that passes something draws it in the
 * indicator's own footprint instead. A tool call wears the same pill in every state
 * for exactly this reason — one row, one shape — and only the mark inside it
 * changes: the indicator while it runs, the tick or the cross when it is done. The
 * mark is drawn in `LocalContentColor`, which the pill sets to its own ink.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiStatePill(
    label: String,
    modifier: Modifier = Modifier,
    tone: PiTone = PiTone.Live,
    mark: (@Composable () -> Unit)? = null,
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
        if (mark == null) {
            LoadingIndicator(
                modifier = Modifier.size(18.dp),
                color = ink,
                polygons = indicatorShapes(),
            )
        } else {
            CompositionLocalProvider(LocalContentColor provides ink) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { mark() }
            }
        }
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
 * A speaker's avatar: a fully round badge carrying a picture, a short label or a mark.
 *
 * Round rather than one of `PiShapes`' radii, and that is the one shape decision
 * here: an avatar is the same mark in every app the reader uses, and a squircle in a
 * row of circles reads as a button. [color] is the whole fill — the reader picks it —
 * and the ink is chosen from the colour's luminance rather than pinned to white,
 * because one of the eight seeds is light enough that white on it is a squint.
 *
 * Four things can be in the circle and the order between them is the whole of the
 * contract:
 *
 *  1. **[imagePath]** — a picture the reader chose. It fills the circle (cropped, not
 *     letterboxed) and its own colours are what is seen, so nothing here tints it.
 *  2. **[label]** — the reader's own word.
 *  3. **[icon]** — a framework glyph (`Person`, for the reader's own side) or, for the
 *     agent, [mark].
 *  4. **[mark]** — the app's Pi logo. It is last because it is *the product's* mark
 *     rather than a speaker's: the agent's circle falls back to it, and the reader's
 *     never does — the two speakers must not fall back to the same figure, which is
 *     why the agent gets the logo and the reader the silhouette.
 */
@Composable
fun PiAvatar(
    color: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    imagePath: String? = null,
    mark: Boolean = false,
    size: Dp = 28.dp,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val ink = if (color.luminance() > 0.5f) Color.Black else Color.White
    val picture = rememberAvatarBitmap(imagePath)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> Image(
                bitmap = picture,
                contentDescription = null,
                // Crop rather than fit: a portrait photo in a circle should fill it.
                // `Alignment.Center` is the crop's own anchor, so the middle of the
                // picture is what survives.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            label != null -> Text(
                text = label,
                style = labelStyle,
                color = ink,
                maxLines = 1,
            )

            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_GLYPH_SHARE),
            )

            mark -> PiMark(
                size = size * AVATAR_MARK_SHARE,
                tint = ink,
                modifier = Modifier,
            )
        }
    }
}

/**
 * How much of the circle the fallback marks take, and why the two numbers differ.
 *
 * They were one number (0.62) until the Pi mark was looked at beside the silhouette it
 * shares a slot with: a framework icon's box is not its ink. `Icons.Filled.Person` draws
 * between 4 and 20 of its 24 units, so at 62% of the circle its *ink* is 0.62 × 16/24 =
 * **41%** of the diameter; `ic_pi_mark.xml`'s viewport **is** the glyph's bounding box —
 * the path runs 29.9→67.5 in x and 40→77.6 in y of a 37.6-unit viewport — so the same
 * 62% drew an ink twice the silhouette's and the agent's avatar read as a logo with a
 * ring of colour around it rather than as a mark in a circle.
 *
 * [AVATAR_MARK_SHARE] is therefore set to the silhouette's *ink* (0.46 × 34dp = about
 * 16dp of a 34dp circle) rather than to its box. It is still the heavier of the two at
 * equal ink, because the mark is a solid blocky figure where the silhouette is an
 * outline of one, which is what keeps the two speakers tellable apart at a glance.
 */
private const val AVATAR_GLYPH_SHARE = 0.62f
private const val AVATAR_MARK_SHARE = 0.46f

/**
 * The custom avatar picture at [path], decoded once and downscaled to avatar size.
 *
 * ## Why not an image loader
 *
 * PiKit has no image-loading dependency, and this is the one picture in the app: a
 * `BitmapFactory` decode off the main thread in a `produceState` is smaller than the
 * dependency would be, and it is the same file the reader picked rather than a cache
 * with its own eviction to reason about.
 *
 * The decode is **downscaled** on the way in. The file is whatever the user picked —
 * a 12-megapixel photo is 48 MB as an `ARGB_8888` bitmap, drawn into a 40dp circle —
 * so `inSampleSize` is solved from the file's own bounds until the longest side is
 * near [AVATAR_DECODE_PX]. The bounds pass reads the header only, which is why it is
 * two `decodeFile` calls rather than one.
 *
 * A path that no longer resolves (the file was deleted, the export was restored
 * without it) decodes to null, and the avatar falls back to the label or the mark
 * exactly as if no picture had been chosen — a broken reference costs the picture and
 * nothing else.
 */
@Composable
private fun rememberAvatarBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (
                    bounds.outWidth / (sample * 2) >= AVATAR_DECODE_PX &&
                    bounds.outHeight / (sample * 2) >= AVATAR_DECODE_PX
                ) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
}

/** The longest side a decoded avatar is allowed to be, in pixels. 40dp at 3x. */
private const val AVATAR_DECODE_PX = 160

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
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
 * [PiLoading] or [PiStatePill].
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
