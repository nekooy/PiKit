package pi.kit.mob.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings

/**
 * The header every page uses: a title on the left, that page's actions on the
 * right, on a band that also paints behind the status bar.
 *
 * The band is a single `Surface`, so its colour runs edge to edge with no seam
 * where the status bar ends.
 *
 * [onBack] puts the back arrow at the *start* of the row, which is where the
 * platform convention puts it. It used to be the last item in [actions], i.e. on
 * the far right next to the destructive controls, which is both unconventional
 * and easy to hit by accident.
 *
 * [subtitleColor] exists because a page sometimes has something to say in the
 * subtitle that is not neutral — the chat header reports a failed agent there —
 * and tinting it through [contentColor] would tint the title and the action icons
 * along with it.
 *
 * [subtitleContent] replaces [subtitle] for a page whose second line is not one
 * string: the chat header splits it into the agent's state, the model, and the
 * session's numbers, each on its own line and each with its own emphasis.
 *
 * ## The band is one height, and that height does not depend on its contents
 *
 * Every page's header is exactly [headerBandHeight]: one title line, one subtitle
 * line and [HEADER_VERTICAL_PADDING] above and below. A page with nothing to put
 * on the second line draws an empty one rather than a shorter band, and the chat
 * page — whose title is the first user message and whose subtitle is the agent's
 * state — therefore does not move the transcript down the screen when either
 * changes. The height is derived from the two styles' own line heights rather
 * than written as a dp constant, so it stays a *proportion* of the user's font
 * scale instead of a box a large font is cut off by.
 *
 * Measured on the emulator before this: the chat page's band was 236 px — 63 px of
 * status bar and 173 px of content — with the title at `titleLarge`, and 82 px of
 * that title was one Han glyph box against 74 px for a Latin one, so a header's
 * height depended on the script its title happened to start with. `新对话` and its
 * status line were replaced by a heading and a title of user data several times a
 * session, and each change moved everything below it.
 *
 * The title used to be `titleLarge` over up to **two** lines, which was itself a
 * fix: at font scale 1.8 a 71-character title was laid out in the 649 px the chat
 * page's three action buttons leave and drew as `run ls /nonexist…` on one line.
 * Two lines fitted more of it and could not fit all of it either — the ceiling was
 * 12 characters per line at 1.8 — and a third line was 100 px of band at that
 * scale, a quarter of the screen. The title is `titleMedium` over one line now:
 * a long title ends in an ellipsis and the whole of it is one tap away in 历史对话,
 * whose row gives it two lines, and in the transcript below the header.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleContent: (@Composable ColumnScope.() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = LocalStrings.current.common.back,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val titleStyle = MaterialTheme.typography.titleMedium
    val subtitleStyle = MaterialTheme.typography.bodySmall

    Surface(color = containerColor, contentColor = contentColor, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerBandHeight(titleStyle, subtitleStyle))
                    // Trailing padding is small so a row of icon buttons keeps
                    // its own 48dp touch targets against the edge. The same is
                    // not done on the leading side: the back arrow's own touch
                    // target should sit at the very start. No vertical padding:
                    // the row's height is the band, and the two line boxes sit
                    // centred inside it.
                    .padding(start = if (onBack != null || navigationIcon != null) 0.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    navigationIcon != null -> navigationIcon()

                    onBack != null -> IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backContentDescription,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = titleStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitleContent != null) {
                        subtitleContent()
                    } else {
                        // Drawn even when there is no subtitle, as an empty
                        // string, because the *line* is part of the band: a page
                        // that skipped it would be 16sp shorter than its
                        // neighbours, and the transcript below the chat header
                        // would have moved every time the agent's state went from
                        // ready to starting and back.
                        //
                        // One line with an ellipsis, where the settings rows'
                        // subtitles wrap freely: this one is inside a *band*, so a
                        // second line is a taller band and the movement the band
                        // exists to prevent. A page whose subtitle is a sentence
                        // longer than the screen therefore ends it in `…` rather
                        // than wrapping — the files page's path, and the chat
                        // header's first line of pi's stderr, which is the line
                        // that names the cause.
                        Text(
                            text = subtitle.orEmpty(),
                            style = subtitleStyle,
                            color = subtitleColor ?: contentColor.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
        }
    }
}

/**
 * The band's height: both line boxes plus [HEADER_VERTICAL_PADDING] on each side.
 *
 * Read from the styles rather than fixed in dp so that the band follows the user's
 * font scale — a header that kept a pixel height while the text grew would cut the
 * line it is supposed to hold. `lineHeight` is the number to use because it is what
 * the line box is laid out at; a style that carries none (an `Em` height, or none at
 * all) falls back to [TextUnit.Unspecified]'s own line, which is what drawing the
 * text would do.
 */
@Composable
private fun headerBandHeight(titleStyle: TextStyle, subtitleStyle: TextStyle): Dp {
    val density = LocalDensity.current
    fun TextStyle.line(): Dp = with(density) {
        when {
            lineHeight.isSp -> lineHeight.toDp()
            fontSize.isSp -> fontSize.toDp()
            else -> FALLBACK_LINE_HEIGHT
        }
    }
    return titleStyle.line() + subtitleStyle.line() + HEADER_VERTICAL_PADDING * 2
}

/**
 * 8dp above and below the two lines, which puts the band at 56dp at font scale 1.0
 * with `titleMedium` over `bodySmall`.
 *
 * It is also what makes the row a home for a 48dp `IconButton`: the buttons are
 * centred in the band, so four of the eight dp are theirs on each side.
 */
private val HEADER_VERTICAL_PADDING = 8.dp

/** The last resort for a `TextStyle` that names neither a line height nor a size. */
private val FALLBACK_LINE_HEIGHT = 16.dp
