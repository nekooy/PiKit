package pi.kit.mob.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    Surface(color = containerColor, contentColor = contentColor, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Trailing padding is small so a row of icon buttons keeps
                    // its own 48dp touch targets against the edge. The same is
                    // not done on the leading side: the back arrow's own touch
                    // target should sit at the very start.
                    .padding(start = if (onBack != null || navigationIcon != null) 0.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        // Two lines, not one, for the two headers whose title is
                        // user data rather than a page name: the chat page titles
                        // a conversation with its first user message, and the
                        // sessions list is where that title comes from. Measured
                        // on the emulator at font scale 1.8,
                        // `run ls /nonexistent-dir-xyz in the bash tool then tell
                        // me it failed` was laid out in the 649 px the chat's
                        // three action buttons leave — node bounds [42,84][691,191],
                        // the width of the column exactly, so the column and not
                        // the string was the limit — and drew as `run ls
                        // /nonexist…`. With the second line the same node is
                        // [42,84][691,298] and draws `run ls /` over
                        // `nonexistent-dir…`.
                        //
                        // Two is the ceiling, so this is an improvement and not a
                        // guarantee: titleLarge fits roughly 12 characters per
                        // 649 px line at font scale 1.8 and 28 at 1.0, so a
                        // 71-character title still ellipsises (it needs three
                        // lines at 1.0). A third line of titleLarge is 100 px of
                        // band at 1.8 — the header would be a quarter of the
                        // screen — and the whole title is one tap away in 历史对话,
                        // whose row gives it 890 px and two lines, and is written
                        // out in full in the transcript below it.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleMinHeight(),
                    )
                    if (subtitleContent != null) {
                        subtitleContent()
                    } else if (!subtitle.isNullOrBlank()) {
                        // No `maxLines`, for the same reason the settings rows'
                        // subtitles have none: a page's subtitle is prose, it is the
                        // only thing on its line, and ellipsising it is a sentence
                        // that stops where it was explaining something. It was two
                        // lines, which fits every subtitle in the catalogs today and
                        // would have cut the first translated one that does not.
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = subtitleColor ?: contentColor.copy(alpha = 0.72f),
                        )
                    }
                }
                actions()
            }
        }
    }
}

/**
 * A floor under the title's height, so the band is the same height whatever the
 * title is written in.
 *
 * A line box honours `lineHeight` as a *minimum*, and a Han glyph needs more of
 * it than a Latin one at the same style: measured on the emulator, `新对话` came
 * out 82 px tall where `run ls /nonexistent-dir-…` came out 74 px. That 8 px moved
 * every row below it, so a header's height depended on the script its title
 * happened to start with — and on the chat page the title is the first user
 * message, so it changed once per conversation.
 *
 * The floor is 15% above the style's own line height, which is past the Han box on
 * every device measured and leaves a Latin title with 4dp of slack instead of a
 * clipped descender. Derived from the sp value, so it follows the user's font
 * scale rather than pinning a pixel count.
 */
@Composable
private fun titleMinHeight(): Modifier {
    val lineHeight = MaterialTheme.typography.titleLarge.lineHeight
    if (!lineHeight.isSp) return Modifier
    val min = with(LocalDensity.current) { (lineHeight * 1.15f).toDp() }
    return Modifier.heightIn(min = min)
}
