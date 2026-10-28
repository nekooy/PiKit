package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One page: a Miuix scaffold, a small top bar, and back.
 *
 * Every destination and every settings page is the same shape, so the shape is
 * written once. The page supplies its title, its top-bar actions and its body;
 * the scaffold supplies the surface colour, the status-bar inset, the popup host
 * that Miuix's own dialogs draw into, and the corner clip a pushed page slides
 * under.
 *
 * [body] receives the scaffold's [PaddingValues] rather than the raw window: with
 * the bar in place those are the insets the content is actually under — the bar's
 * height at the top, the navigation strip at the bottom — and applying them is the
 * body's whole contract. A list scrolled inside the page therefore ends at the bar
 * rather than under it, and a composer typed into ends above the keyboard without
 * the page having to know the keyboard exists.
 *
 * [onBack] is null on a tab root, which is also what turns the system back gesture
 * off: a handler that does nothing is a page that swallows a gesture meant for the
 * activity.
 *
 * ## The subtitle is drawn here, not passed to the bar
 *
 * Miuix's `SmallTopAppBar` renders a `subtitle` with no line limit, so a sentence
 * that is wider than the space beside the action icons **wraps**, and the bar —
 * and therefore every page's content rectangle — grows by a line. The terminal
 * page measured it: its English idle sentence takes a second line on a phone
 * where the Chinese one does not, which moved the PTY's rectangle when the
 * language changed. It is one line here instead: the bar keeps its own height,
 * the subtitle sits beneath it with an ellipsis, and the page's content top is the
 * same on every page in every language.
 *
 * **Null and blank are two different answers.** `null` is a page that has no
 * subtitle at all and gets no line; `""` reserves the line while the value it
 * will hold is still being read. The distinction is what keeps the files page's
 * content rectangle still: its count is null while a folder is being listed, and
 * a line that appeared and disappeared once per folder moved the whole page by
 * its height twice. A page whose subtitle arrives later passes `orEmpty()`.
 */
@Composable
fun PiPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    body: @Composable (PaddingValues) -> Unit,
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    // Named, because a back arrow is the one control
                                    // on the bar with no text beside it: a screen
                                    // reader that reads nothing here leaves a page
                                    // that cannot be left. The label is the
                                    // catalog's, not a literal.
                                    contentDescription = LocalStrings.current.common.back,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
                if (subtitle != null) {
                    Text(
                        // A zero-width space keeps a blank line's height without
                        // drawing a glyph. `Text("")` still lays out its line box, so
                        // this is belt and braces rather than a requirement — but it
                        // also keeps the layout from collapsing if the library ever
                        // skips an empty string.
                        text = subtitle.ifEmpty { "\u200B" },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surface)
                            .padding(start = 24.dp, end = 24.dp)
                            .padding(bottom = 6.dp),
                    )
                }
            }
        },
    ) { padding ->
        // No wrapper `Box`: the body is the page. The `floating` slot this used to
        // draw into was passed by no caller, and its `Box` sat outside `body`'s
        // padding — a control placed there would have landed under the root
        // navigation bar.
        body(padding)
    }
}
