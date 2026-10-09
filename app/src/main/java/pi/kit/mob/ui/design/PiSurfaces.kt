/*
 * The surfaces every page is built from: the page shell, the card, the group, the row.
 *
 * ## The page shell
 *
 * A page is one fixed header over a scrolling body. The header carries a title and a
 * subtitle, which is what this app needs because every page's title is a moving fact:
 * the chat page's is the first line of the conversation, the files page's carries a
 * path, the history page's carries a count — and the second fact needs a home rather
 * than being crammed beside the first.
 *
 * It is drawn here rather than taken from Material's app bars because those could not
 * hold one height: the flexible pair's expanded height depends on whether a subtitle
 * is present, and the large one is taller again, so four pages wore three heights and
 * two of them moved as the reader scrolled. [PiScaffold]'s own KDoc has the tokens and
 * the measurement.
 *
 * ## The two containers
 *
 * [PiCard] and [PiGroup] are the same rounded rectangle used for two different
 * jobs, and keeping them apart is what keeps a page readable. A **card** is one
 * subject with its own body — a reply, a file preview, a summary. A **group** is a
 * set of peer rows that share a frame, and its rows carry the dividers between
 * themselves. The distinction matters because a group's rows must *not* each be a
 * card: eight cards stacked is eight visual objects where the reader needs one
 * list, and this is the mistake the old settings pages made.
 *
 * ## Row density
 *
 * [PiRow] is the dense voice from `PiShapes` — 10dp of vertical padding, a 12dp
 * corner for its press outline, and a trailing slot that is either a value or a
 * chevron but never both. It is deliberately *not* a `ListItem`: `ListItem`'s
 * expressive heights are tuned for media-rich browsing lists, and three of this
 * app's lists (settings, files, model profiles) are closer to a table.
 */
package pi.kit.mob.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.ui.components.PageBackHandler

/**
 * A page: one fixed header, a body, and room for one floating control.
 *
 * The body is *given* the scroll connection rather than applying it itself, so the
 * header can show that content has scrolled under it while the page keeps its own
 * layout. The nested-scroll link has to be on the scrolling container, and the only
 * thing that knows which container that is is the page.
 *
 * ## One header, and one height, on every page
 *
 * The header is drawn here rather than taken from Material's app bars, and the
 * reason is a measurement of theirs rather than a preference: the flexible bars'
 * expanded height is **two different tokens depending on whether a subtitle is
 * present** (`MediumFlexibleAppBarWithoutSubtitleExpandedHeight` against
 * `…WithSubtitleExpandedHeight`, and the same pair for the large bar). So a page
 * that passed a subtitle and one that did not were never the same height, and a
 * page that chose the large bar was taller again — four pages, three heights. A
 * single `heightIn` with the same two lines everywhere is the smallest thing that
 * cannot drift: the *pages* differ in their title and their actions, not in how
 * tall the strip above the body is.
 *
 * 56dp is a one-line bar's own height with room for the subtitle line inside it
 * (a `titleLarge` line box and a `bodySmall` one come to about 44dp), and
 * `heightIn` rather than `height` so a large font scale grows the strip instead of
 * clipping it.
 *
 * ## The status bar is the header's own surface
 *
 * [WindowInsets.statusBars] is consumed *inside* the header's `Surface`, so the
 * strip behind the clock is painted by the same container as the title — it changes
 * with it when content scrolls under, and a page cannot get it wrong. The
 * alternative is the failure this replaced: the inset consumed *above* the bar left
 * the status strip on the page's surface while the bar filled with
 * `surfaceContainer` as soon as the body scrolled, so one band of chrome was drawn
 * in two colours. The root pads the *bottom* and the horizontal edges for the same
 * reason, one level up.
 *
 * [onBack] positions a back arrow at the *start* of the bar, which is where the
 * platform convention puts it — three of this app's pages have one, and the Files
 * page's second level is the only place a gesture alone used to be the way back.
 * The back handler yields to any open sheet, which is what stops a swipe while a
 * picker is up from closing the page underneath it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floating: (@Composable () -> Unit)? = null,
    /**
     * The title's own style, for the one page whose title can be a whole sentence.
     *
     * Every page but the chat page is named by a word or two, and the shell's
     * `titleLarge` is sized for that. A conversation's title is the reader's own
     * first line, which the header ellipsises at one line — so the one page whose
     * title is prose asks for a smaller style and fits more of it. The default is
     * the shell's, so a page that says nothing gets the shared look.
     */
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    body: @Composable (Modifier) -> Unit,
) {
    if (onBack != null) {
        PageBackHandler(enabled = true) { onBack() }
    }

    val barState = rememberTopAppBarState()
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior(barState)
    val nested = Modifier.nestedScroll(barScroll.nestedScrollConnection)

    Column(modifier.fillMaxSize()) {
        PiPageHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            barState = barState,
            titleStyle = titleStyle,
        )

        Box(Modifier.fillMaxSize()) {
            body(nested.fillMaxSize())
            if (floating != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                ) {
                    floating()
                }
            }
        }
    }
}

/**
 * The strip above every page's body: a title, an optional second fact, and the
 * page's actions — at one height, whatever the page.
 *
 * A `Surface` so the status-bar inset it consumes is painted in the same colour as
 * the rest of the strip, and `overlappedFraction` rather than a `contentOffset`
 * comparison for the fill: it is the library's own answer to "how much of the bar
 * has content behind it", it is a fraction so the threshold is stated in the units
 * it means, and it reads the state inside a `derivedStateOf` so a scroll recomposes
 * this only at the moment the answer flips.
 *
 * `heightOffsetLimit` is what makes that fraction mean anything — it is computed
 * from the limit, and the pinned behaviour never sets it (it only moves
 * `contentOffset`) — so it is set here from the strip's own measured height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PiPageHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    barState: TopAppBarState,
    titleStyle: TextStyle,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val heightPx = with(density) { PiHeaderHeight.toPx() }
    SideEffect {
        if (barState.heightOffsetLimit != -heightPx) barState.heightOffsetLimit = -heightPx
    }
    val overlapped by remember(barState) {
        derivedStateOf { barState.overlappedFraction > 0.01f }
    }
    val fill by animateColorAsState(
        targetValue = if (overlapped) scheme.surfaceContainer else scheme.surface,
        animationSpec = PiMotion.defaultEffects(),
        label = "headerFill",
    )

    Surface(
        color = fill,
        contentColor = scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Inside the Surface, so the strip behind the clock is this
                // container and moves with it.
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = PiHeaderHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = LocalStrings.current.common.back,
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (onBack != null) 4.dp else 16.dp,
                        end = 8.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/**
 * The header's own height, for every page in the app.
 *
 * One constant rather than a per-page number, because a header that differs between
 * pages is exactly what this header exists to prevent. The composer's floating
 * height and the transcript's tail are separate numbers (`ChatScreen`) and are not
 * this one.
 */
val PiHeaderHeight = 56.dp

/**
 * A card: one subject, on its own surface, with the app's content radius.
 *
 * [onClick] turns the whole card into one target. When it is null the card is a
 * container and its own children carry their taps — which is the difference
 * between "this card is the action" and "this card holds actions", and mixing the
 * two is how a tap on a button inside a card ends up firing the card's handler.
 *
 * [contentColor] defaults to `onSurface`, which is what every card that sits on a
 * neutral container wants. It is a parameter because a card on `primaryContainer` —
 * the reader's own prompt — takes `onPrimaryContainer`, and a card whose container
 * and content colour came from two different roles was the reason that prompt's text
 * had to name a colour at each call site instead.
 */
@Composable
fun PiCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: androidx.compose.ui.graphics.Shape = PiShapes.card,
    border: BorderStroke? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = container,
        contentColor = contentColor,
        shape = shape,
        border = border,
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * A labelled group of peer rows, as one surface.
 *
 * Rows inside are expected to be [PiRow]s and to put [PiRowDivider] between
 * themselves; the group's own corners are what the first and last row's press
 * outline is clipped to, which is why each row clips to [PiShapes.row] and not to
 * the group's radius — the two differ by the group's horizontal padding, which is
 * the optical-roundness rule applied to a press state.
 */
@Composable
fun PiGroup(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PiShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

/**
 * The heading above a group.
 *
 * Small, emphasized, letterspaced, and in the *primary* role.
 *
 * It used to be `secondary`, on the theory that the primary colour belongs to
 * selection and actions and a heading in it would look tappable. Measured against
 * the light scheme's actual values that theory does not hold: `secondary` is
 * `#5C5D72`, a desaturated slate that on `surface` is a *grey* label — the page's
 * section names read as one more shade of body text and a reader scanning for
 * "where does the API key live" had nothing to scan for. The tappability worry is
 * answered by form rather than by colour: a heading is upper-cased, letterspaced,
 * `labelLargeEmphasized` and inset 24dp, and no control in this app looks like
 * that. One hue for "this is a name for the things below it" is worth more than
 * the residual risk of confusion, and every settings page states its structure
 * with it.
 */
@Composable
fun PiSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * One row of a group.
 *
 * [leading] is an icon or a mark, [value] is the fact the row is *about* and
 * [trailing] is a control. The three are separate rather than one slot because a
 * settings row routinely carries both a fact and a way in — the storage row says
 * which level is granted *and* opens the page — and a single slot makes that a
 * decision at every call site. The chevron is drawn whenever [onClick] is set and
 * no [trailing] was supplied, because a row that does something has to say so, and
 * the alternative is a list where three rows are tappable and look identical to the
 * four that are not.
 *
 * A row that carries a *control* must not also carry [onClick]: the two would both
 * fire for one tap, which is the bug `SettingsSwitchRow` was split out to fix. That
 * is why `trailing` suppresses the chevron instead of sitting beside it.
 *
 * [monospace] is for a row whose subtitle or value is a path, a version or an
 * identifier: proportional digits make a path noticeably harder to read back, and
 * it is applied to both so a row cannot be half-machine.
 *
 * [valueColor] is the value's ink, and it is a parameter because a row that reports
 * the outcome of something the reader just started carries that outcome in a tone
 * (`PiTone`) rather than in the neutral the rest of the list speaks in — the
 * maintenance runs and the battery-exemption row are the callers. The default is
 * the neutral so a row that has no outcome to report looks like every other row.
 */
@Composable
fun PiRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    monospace: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.38f
    val machine = if (monospace) FontFamily.Monospace else FontFamily.Default
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(PiShapes.row)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = machine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = machine,
                color = valueColor.copy(alpha = alpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                // 20dp, the same box the settings rows have always drawn their
                // chevron in. Left at `Icon`'s own 24dp it was 19x31 px of ink where
                // the settings tree's rows measured 16x27 on the same 420dpi screen,
                // so two lists one tap apart drew two different chevrons — the
                // report "设置首页的右箭头粗细不一致" — and the smaller one is the
                // right answer: this is chrome beside body text, not a 24dp tap
                // target (the row itself is the target).
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A paragraph of explanation, in the page rather than in a container.
 *
 * The app's *object line* voice: a sentence with no container, for the few remarks
 * that are about an outcome but are not failures — "saved, the agent is restarting",
 * "the file could not be read". Its leading inset matches [PiSectionHeader]'s, so a
 * line under a heading lines up with it instead of drifting left.
 *
 * ## What it is not for any more
 *
 * It used to be the voice of the app's *explanation* — a paragraph on every settings
 * page saying what the page was for, when a change would take effect, why the default
 * was what it was — and those paragraphs are gone. The app now carries two kinds of
 * prose and writes a third kind nowhere:
 *
 *  - about **one control** — the consequence of pressing a button, what a field's
 *    value is for — it goes *inside that control*, as a row's `subtitle` or a field's
 *    `supportingText`. (A `PiButton`'s `caption`, a second line inside its own fill,
 *    was a third home for one of these and is gone — see `PiActions`.)
 *  - about a **state** — a save that failed, a shell that exited — it is [PiNotice],
 *    in its container, next to the thing in that state.
 *  - about the **page** — nothing. A paragraph under the controls it describes is met
 *    after the decision rather than before it, and the manual is the document that can
 *    say a thing once instead of at every place it is true. §13's "where a sentence
 *    goes" has the three reasons and what moved.
 */
@Composable
fun PiNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: PiTone = PiTone.Neutral,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tone.ink(MaterialTheme.colorScheme),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * The hairline between two rows of a group.
 *
 * Inset from the start by the width of a leading mark plus the row's padding, so
 * the line begins under the text rather than under the icon — the platform's own
 * list convention, and the reason it is a component: the inset has to agree with
 * [PiRow]'s padding, and a literal at each call site does not stay in agreement.
 * A row with no leading mark passes [inset] as false.
 */
@Composable
fun PiRowDivider(inset: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 64.dp else 24.dp, end = 24.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/**
 * A page that has nothing to show yet: a medallion, a sentence, and one action.
 *
 * The medallion is cut from the app's one abstract silhouette, and the pair is the
 * *only* place that silhouette appears: the agent's own mark, and this. Scaled to
 * 96dp it is the only thing on the page, which is what the empty state is for — and
 * it is deliberately not an illustration, because a drawn picture of a folder tells
 * the reader less than the sentence under it does.
 *
 * A conversation with nothing in it is not a blank area either: the chat page draws
 * this where the transcript would be, which is the first thing the app ever shows.
 */
@Composable
fun PiEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(PiShapes.medallion)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(PiShapes.agent)
                    // At full strength, and at half the medallion's own size. The
                    // first version drew this at 55% of `onSecondaryContainer` and
                    // 40dp, and two nested scalloped figures at similar weights read
                    // as one smudge rather than as a mark inside a container: the
                    // inner shape has to be the darker of the two for the pair to
                    // read as a lockup at all.
                    .background(MaterialTheme.colorScheme.onSecondaryContainer),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** The group's own divider, for a page that wants the hairline without the inset
 * arithmetic: a group whose rows have no leading marks. */
@Composable
fun PiFullDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/** An inset that keeps a page's own content clear of the rounded screen edge. */
val PiPagePadding = PaddingValues(horizontal = 12.dp)

/**
 * How far above the bottom of a page its content stops.
 *
 * A page hands the bottom of the window to the navigation bar, and content that runs
 * flush against that bar reads as cut off rather than as finished — the visible case
 * was a file listing drawn as a card whose lower rounded corners sat on the bar's own
 * top edge. Every scrolling body therefore ends with this much blank space, so the
 * last row, card or paragraph has the bar under a gap rather than under itself.
 *
 * One constant rather than a per-page number, for the same reason the header has
 * one: pages used to carry 0, 12 and 32dp of it, which is a difference nobody chose.
 */
val PiPageBottom = 16.dp
