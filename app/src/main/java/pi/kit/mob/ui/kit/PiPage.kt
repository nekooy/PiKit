package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
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
 * [body] receives the scaffold's [PaddingValues] with start, top and end intact and
 * bottom **zero**. The page's own surface runs to the window's last pixel, so the body
 * draws through the navigation strip rather than the scaffold painting an opaque band
 * under it — the band that used to cut the last card of a page in two. The strip has not
 * gone away, though: its height is provided as [LocalPiNavigationInset], and a scrolling
 * page adds it to the end of its own content, where it keeps the last row clear of the
 * gesture handle and moves with the content instead of shrinking the viewport. A composer
 * typed into still ends above the keyboard without the page having to know the keyboard
 * exists.
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
 *
 * ## One bar, and the arrow at the library's inset
 *
 * The app used to draw a second, left-aligned bar for its roots: a title on the page's
 * own gutter, with the back arrow inside a button that started at 26dp. That put the one
 * arrow the pushed pages share at 38dp from the edge while the settings pages behind the
 * drawer drew theirs at 16dp — two pushes, two arrow positions, and a page that moved its
 * own back control depending on where you came from. The chat page no longer has a bar at
 * all (§13), so the left-aligned variant had no caller left; the shared bar is the only
 * one, and every page's arrow starts where the library puts it.
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
                            .padding(start = TITLE_GUTTER, end = TITLE_GUTTER)
                            .padding(bottom = 6.dp),
                    )
                }
            }
        },
    ) { padding ->
        // The body is the page, and the page reaches the window's last pixel: only the
        // bar's height and the horizontal system insets are its, while the strip is handed
        // down as a value a scrolling body spends as content padding. Handing the strip in
        // as a bottom *padding* here was the opaque band under every page — the scaffold's
        // surface showed through it and the viewport ended above the handle.
        CompositionLocalProvider(
            LocalPiNavigationInset provides padding.calculateBottomPadding(),
        ) {
            body(
                PaddingValues(
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = 0.dp,
                ),
            )
        }
    }
}

/**
 * The height of the navigation strip a page body now draws through.
 *
 * `Scaffold` used to hand its content a bottom padding equal to the strip, so every page's
 * viewport ended above it and the scaffold's own `surface` filled it — in dark mode a band
 * of black across the bottom of a page whose last card it cut. The body reaches the window's
 * last pixel now, which leaves a list's last row under the gesture handle unless something
 * pushes it up: a scrolling page reads this value and adds it to the padding at the end of
 * its content, where the row can still be scrolled above the handle and the extra space
 * belongs to the content rather than to the viewport.
 *
 * Outside [PiPage] the value is `0.dp`, which is the safe answer: a composable that is not
 * inside a page has no strip of its own to keep clear of.
 */
val LocalPiNavigationInset: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }


/**
 * The page's own left margin for a title.
 *
 * 26dp is `TopAppBarDefaults.TitlePadding`, which is the gutter a centred title's
 * *box* begins at — and, because the subtitle is drawn with 24dp in the centred
 * variant, the two numbers are already within a rounding of each other. Stating
 * the larger of the two here is what puts a root's title and its subtitle on one
 * column.
 */
private val TITLE_GUTTER = TopAppBarDefaults.TitlePadding

/** The air between a left-aligned title and the first action icon. */
private val ACTION_GAP = 8.dp
