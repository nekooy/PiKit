/*
 * The surfaces every page is built from: the page shell, the card, the group, the row.
 *
 * ## The page shell
 *
 * A page is a large flexible app bar over a scrolling body. That is Material's
 * expressive app-bar variant — the guidance says the medium and large *non*-flexible
 * bars are no longer recommended and that the flexible pair should replace them —
 * and it is the right shape for this app because every page's title is a moving
 * fact: the chat page's is the first line of the conversation, the files page's
 * carries a path, the history page's carries a count. A flexible bar takes a
 * subtitle, so that second fact has a home rather than being crammed beside the
 * title, and it collapses on scroll so the reading area grows as the reader
 * scrolls into it.
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
 * [PiRow] is the dense voice from `PiShapes` — 14dp of vertical padding, a 12dp
 * corner for its press outline, and a trailing slot that is either a value or a
 * chevron but never both. It is deliberately *not* a `ListItem`: `ListItem`'s
 * expressive heights are tuned for media-rich browsing lists, and three of this
 * app's lists (settings, files, model profiles) are closer to a table.
 */
package pi.kit.mob.ui.design

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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.ui.components.PageBackHandler

/**
 * A page: a flexible app bar, a body, and room for one floating control.
 *
 * The body is *given* the scroll connection rather than applying it itself, so a
 * page's list can scroll the bar away while the page keeps its own layout. That is
 * what makes the collapsed state work at all: the nested-scroll link has to be on
 * the scrolling container, and the only thing that knows which container that is
 * is the page.
 *
 * ## The status bar is the frame's business, not the page's
 *
 * [WindowInsets.statusBars] is consumed here and the app bar's own `windowInsets`
 * is zero, so a page cannot get this wrong. The alternative — leaving the inset to
 * the caller — is how a page ends up with its title under the clock, and it is the
 * same failure the theme's own status-bar handling exists to prevent: one screen
 * that forgot is one screen whose clock is invisible, and it is found by looking at
 * that screen rather than by any check. The root pads the *bottom* and the
 * horizontal edges for the same reason, one level up.
 *
 * [onBack] positions a back arrow at the *start* of the bar, which is where the
 * platform convention puts it — three of this app's pages have one, and the Files
 * page's second level is the only place a gesture alone used to be the way back.
 * The back handler yields to any open sheet, which is what stops a swipe while a
 * picker is up from closing the page underneath it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollBehavior: PiAppBarScroll = PiAppBarScroll.Collapsing,
    actions: @Composable RowScope.() -> Unit = {},
    floating: (@Composable () -> Unit)? = null,
    body: @Composable (Modifier) -> Unit,
) {
    if (onBack != null) {
        PageBackHandler(enabled = true) { onBack() }
    }

    val barState = rememberTopAppBarState()
    val barScroll = when (scrollBehavior) {
        PiAppBarScroll.Collapsing -> TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
        PiAppBarScroll.Pinned -> TopAppBarDefaults.pinnedScrollBehavior(barState)
        PiAppBarScroll.None -> null
    }
    val nested = if (barScroll != null) Modifier.nestedScroll(barScroll.nestedScrollConnection) else Modifier

    // The bar paints nothing of its own until the body scrolls under it, and then
    // a fill separates the two. That is the M3 rule — a colour fill rather than a
    // shadow — and it is why the container colour is transparent here: at rest the
    // title sits on the page's own surface, which is what makes the body read as
    // one continuous sheet rather than as a band above a panel.
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val navigation: @Composable () -> Unit = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = LocalStrings.current.common.back,
                    )
                }
            }
        }
        val subtitleBlock: @Composable () -> Unit = {
            if (subtitle != null) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (scrollBehavior) {
            // The large bar is for a page whose title is the point — the chat
            // page, whose title is the conversation, and the history page, whose
            // title is what the list is.
            PiAppBarScroll.Collapsing -> LargeFlexibleTopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = subtitleBlock,
                navigationIcon = navigation,
                actions = actions,
                colors = colors,
                scrollBehavior = barScroll,
                windowInsets = WindowInsets(0),
            )

            // A page whose body is the point gets the medium bar: it gives the
            // list three more lines of height on a phone.
            PiAppBarScroll.Pinned -> MediumFlexibleTopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = subtitleBlock,
                navigationIcon = navigation,
                actions = actions,
                colors = colors,
                scrollBehavior = barScroll,
                windowInsets = WindowInsets(0),
            )

            PiAppBarScroll.None -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

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

/** How a page's app bar behaves as its body scrolls. */
enum class PiAppBarScroll {
    /** Shrinks to a single line as the reader scrolls, giving the body the height. */
    Collapsing,

    /** Keeps a medium two-line bar and stays put. */
    Pinned,

    /** No bar at all; the page draws its own heading. */
    None,
}

/**
 * A card: one subject, on its own surface, with the app's content radius.
 *
 * [onClick] turns the whole card into one target. When it is null the card is a
 * container and its own children carry their taps — which is the difference
 * between "this card is the action" and "this card holds actions", and mixing the
 * two is how a tap on a button inside a card ends up firing the card's handler.
 */
@Composable
fun PiCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: androidx.compose.ui.graphics.Shape = PiShapes.card,
    border: BorderStroke? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/**
 * The heading above a group.
 *
 * Small, emphasized, letterspaced, and in the *secondary* role rather than the
 * primary one. The primary colour is reserved for selection and for actions; a
 * heading that borrowed it made every label look tappable, which is the reason
 * this is a distinct component rather than a styled `Text`.
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
            .padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.secondary,
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
 */
@Composable
fun PiRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
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
            .padding(horizontal = 12.dp, vertical = 14.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
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
            )
        }
    }
}

/**
 * A paragraph of explanation, in the page rather than in a container.
 *
 * The one prose voice in the app, and it is deliberately not [PiNotice]: a notice is
 * a *container* answering a state ("the save failed"), and using it for an
 * explanation puts a filled box around a sentence that is not about anything that
 * happened. Its leading inset matches [PiSectionHeader]'s, so a note under a heading
 * lines up with it instead of drifting left — the drift the settings pages used to
 * have, at three different left edges on one screen.
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
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
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
