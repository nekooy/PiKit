package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * ## Why a root can be left-aligned, and a pushed page is not
 *
 * The four tab roots carry a title the reader already knows they are looking at,
 * and the bar's right side is where three or four action icons live. The
 * library centres the title between the navigation icon and those actions, and
 * clamps it into the space they leave, so a root with four actions has its title
 * pushed off centre by their width — measured on the chat root, whose title
 * moved left by the width of the third action icon. A pushed page has one arrow
 * and at most two actions, so the clamp does not bite and the centred title is
 * the platform's own idiom there.
 *
 * [alignStart] is therefore a property of a *root*: the title and the subtitle
 * start on the page's own gutter instead of being centred between two sets of
 * controls. It is drawn here rather than by the library because the library's
 * bar has no alignment parameter — it computes one — and the bar it draws is the
 * same one, at the same height and with the same type, so the two variants differ
 * only in where the title begins.
 */
@Composable
fun PiPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    alignStart: Boolean = false,
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
                if (alignStart) {
                    StartAlignedBar(title = title, onBack = onBack, actions = actions)
                } else {
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
                }
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
                        textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
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
        // No wrapper `Box`: the body is the page. The `floating` slot this used to
        // draw into was passed by no caller, and its `Box` sat outside `body`'s
        // padding — a control placed there would have landed under the root
        // navigation bar.
        body(padding)
    }
}

/**
 * The bar a root draws when its title starts at the page's gutter.
 *
 * Every number here is the library's: [TopAppBarDefaults.CollapsedHeight] for the
 * row, `title3` at medium weight for the title — the style
 * `SmallTopAppBarLayout` draws it in — `NavigationIconPadding` and
 * `ActionIconPadding` for the two ends, and the same `systemBars` top inset the
 * library's bar applies. What is *not* the library's is the placement: the title
 * is the first child of a row whose remainder belongs to the actions, so it
 * begins where the page's own content begins and cannot be pushed by them.
 *
 * The title is the same [TITLE_GUTTER] as the subtitle under it, which is what
 * makes the two read as one block rather than as a heading that is indented
 * differently from its own second line.
 */
@Composable
private fun StartAlignedBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .height(TopAppBarDefaults.CollapsedHeight)
            .padding(start = TITLE_GUTTER, end = TopAppBarDefaults.ActionIconPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = LocalStrings.current.common.back,
                )
            }
            Spacer(Modifier.width(TITLE_GUTTER - TopAppBarDefaults.NavigationIconPadding))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.padding(start = ACTION_GAP)) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

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
