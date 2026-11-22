package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One page: a Miuix scaffold, a glass top bar, and back.
 *
 * Every destination and every settings page is the same shape, so the shape is
 * written once. The page supplies its title, its top-bar actions and its body;
 * the scaffold supplies the surface colour, the status-bar inset, the popup host
 * that Miuix's own dialogs draw into, the glass layer the bar samples, and the
 * corner clip a pushed page slides under.
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
 * [onBack] is null on the app's root, which is also what turns the system back
 * gesture off: a handler that does nothing is a page that swallows a gesture meant for
 * the activity.
 *
 * ## The subtitle is drawn here, not passed to the bar
 *
 * Miuix's top bars — `SmallTopAppBar` and `GlassTopAppBar` alike — render their
 * `subtitle` with no line limit, so a sentence
 * that is wider than the space beside the action icons **wraps**, and the bar —
 * and therefore every page's content rectangle — grows by a line. The terminal
 * page measured it: its English idle sentence takes a second line on a phone
 * where the Chinese one does not, which moved the PTY's rectangle when the
 * language changed. It is one line here instead: the bar keeps its own height,
 * the subtitle sits in its measured bottom slot with an ellipsis, and the
 * page's content top does not change with the length of the subtitle.
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
    /**
     * Whether the scroll region has content above its viewport — Miuix's
     * `listState.canScrollBackward`.
     *
     * This drives the background band and nothing else. The bar's controls are glass
     * from the first frame (see the `isContentScrolled` argument below), so a page at
     * rest and a page scrolled under its bar show the same back arrow and the same
     * action pills; what this flag decides is whether the progressive band behind them
     * is drawn — and on a page that passes [contentScrollsUnderBar] `false` it decides
     * nothing, because that band is off whatever this says. Whole-page scrollers can
     * also report their actual elastic translation via [LocalPiPageOverscroll], so an
     * upward pull that passes under the bar is covered during the spring back. `false`
     * is the default for a fixed viewport such as the terminal, which keeps the band off
     * entirely.
     */
    contentScrolled: Boolean = false,
    contentScrollsUnderBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * Windows that belong to this page: its sheets, questions and custom dialogs.
     *
     * They are composed here rather than after the `PiPage` call because this is the
     * only place that owns both halves of the glass contract: [LocalPiBackdrop] is
     * provided, and the layer that value records is the page body rather than the
     * controls that sample it. A `PiSheet` or `PiConfirm` written after `PiPage`
     * returns sees no page backdrop and silently falls back to the opaque Miuix
     * window; one written here keeps the OS4 material and the page's own
     * `GlassDialog`/`GlassBottomSheet` exit animation.
     */
    overlay: @Composable () -> Unit = {},
    body: @Composable (PaddingValues) -> Unit,
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    // The bar and the body must share one backdrop. The bar is composed in the
    // Scaffold's `topBar` slot, which is outside the content lambda, so a backdrop
    // created inside the body would never reach it; the value is therefore created
    // here and passed to both halves.
    val pageBackdrop = rememberPiBackdrop()

    var barBottom by remember { mutableFloatStateOf(0f) }
    var bodyOverscroll by remember { mutableFloatStateOf(0f) }
    val reportOverscroll: (Float) -> Unit = remember { { bodyOverscroll = it } }

    // The band's own progress. The bar itself is reported as scrolled always, so that
    // the buttons' material never falls back to a bare glyph (see `isContentScrolled`
    // below); what is left to drive is the background band, and this is the value that
    // does it. It keeps the library's spec — 100ms, linear — so the band fades in and
    // out exactly as it used to.
    //
    // A fitting page can pass behind the bar during an upward spring without changing
    // ScrollState. The reported translation resets with the spring; unlike accumulated
    // deltas it cannot leave the bar raised. A page whose body never travels behind the
    // bar (`contentScrollsUnderBar = false`) keeps the band off whatever the scroll
    // position says.
    val barOverlapped = contentScrolled || (contentScrollsUnderBar && bodyOverscroll < -1f)
    val bandAlpha by animateFloatAsState(
        targetValue = if (contentScrollsUnderBar && barOverlapped) 1f else 0f,
        animationSpec = tween(durationMillis = BAND_FADE_MILLIS, easing = LinearEasing),
        label = "piPageBarBand",
    )

    CompositionLocalProvider(LocalPiBarBottom provides barBottom) {
    PiPopupHostProvider {
    Scaffold(
        modifier = modifier,
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            GlassTopAppBar(
                title = title,
                modifier = Modifier.onGloballyPositioned { barBottom = it.boundsInWindow().bottom },
                // Always scrolled, so the bar's controls hold the material from the
                // first frame: `GlassTopAppBar` turns this one flag into
                // `materialProgress` — the pill's alpha, its edge stroke and its shadow —
                // and into `maskProgress`, the band's 100ms fade. Miuix 0.9.5-beta03 has
                // no way to raise the first without the second at the call site:
                // `GlassIconButton` takes no progress parameter, `inheritTopBarMaterial =
                // false` only opts out of the bar's backdrop and material (the shared
                // progress is still passed), and the context that carries it
                // (`LocalGlassTopAppBarContext`) is internal. `backgroundAlpha` is the
                // public multiplier the library applies to the band after `maskProgress`,
                // so the band is handed to `bandAlpha` below and every control keeps its
                // material at rest.
                //
                // At rest this leaves `keepMaterial` true, which is what keeps the pill,
                // its stroke and its shadow drawn; the band is off because `bandAlpha` is
                // 0, so the library's `bandActive` is false and its progressive blur skips
                // the pass. The bar still records the layer its buttons sample — that is
                // the cost of a glass control over live content, and with no
                // `GlassIconButton` in the slot the library attaches no such layer at all.
                isContentScrolled = true,
                expandable = false,
                // OS4's material: the bar draws a progressive blur over the page's
                // backdrop and its buttons sample the same layer. `progressiveBlur`
                // needs a non-null backdrop, which this page creates on devices that
                // can run the shader; elsewhere the bar keeps its gradient band.
                progressiveBlur = contentScrollsUnderBar,
                // The band's visibility, and now its only driver: the buttons no longer
                // depend on it.
                backgroundAlpha = { bandAlpha },
                backdrop = pageBackdrop,
                material = PiGlass.material(),
                defaultWindowInsetsPadding = true,
                navigationIcon = {
                    if (onBack != null) {
                        GlassIconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Glass.ChevronBackward,
                                // Named, because a back arrow is the one control
                                // on the bar with no text beside it: a screen
                                // reader that reads nothing here leaves a page
                                // that cannot be left. The label is the
                                // catalog's, not a literal.
                                contentDescription = LocalStrings.current.common.back,
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                },
                // The bar's own actions row carries no gap: `GlassTopAppBar` wraps this
                // slot in a `Row(horizontalArrangement = Arrangement.End)` and nothing
                // else, so two `GlassIconButton`s at its 44dp size are laid edge to edge.
                // Measured on the files page at 420dpi, the two glyph centres were 116px
                // apart against a 115.5px button — a zero gap, which reads as one two-icon
                // control rather than as two buttons, and puts "go up" a thumb-width from
                // "go home" with no gutter between the targets.
                //
                // The gap is stated here, in the one place every page's bar is composed,
                // rather than at each call site: `PiPage` owns the bar, and a spacing that
                // lives in the callers is a spacing the next page forgets.
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
                        // The library's own alignment, so a short control in this row still
                        // sits on the bar's centre line.
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                },
                bottomContent = {
                    if (subtitle != null) {
                        Text(
                            text = subtitle.ifEmpty { "\u200B" },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = TITLE_GUTTER, end = TITLE_GUTTER)
                                .padding(bottom = SUBTITLE_BOTTOM),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // The body is the page, and the page reaches the window's last pixel: only the
        // bar's height and the horizontal system insets are its, while the strip is handed
        // down as a value a scrolling body spends as content padding. Handing the strip in
        // as a bottom *padding* here was the opaque band under every page — the scaffold's
        // surface showed through it and the viewport ended above the handle.
        //
        // The body is also the page's glass layer: the bar above it samples this subtree,
        // so cards and rows scrolling under the bar are what the material blurs. The layer
        // stops at the bar because a bar inside it would sample its own output.
        CompositionLocalProvider(
            LocalPiBackdrop provides pageBackdrop,
            LocalPiNavigationInset provides padding.calculateBottomPadding(),
            LocalPiPageOverscroll provides reportOverscroll,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
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
            // Outside the recorded body on purpose: a sheet or dialog that sampled a
            // layer containing itself is the RenderThread stack overflow in docs/UI.md.
            overlay()
        }
    }
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

/** The reported pixel translation of the one scroller that can pass behind the page bar. */
internal val LocalPiPageOverscroll = compositionLocalOf<((Float) -> Unit)?> { null }

/**
 * The page bar's bottom in window pixels, including the subtitle and top inset.
 */
val LocalPiBarBottom: ProvidableCompositionLocal<Float> = compositionLocalOf { 0f }


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

/**
 * The gap between two controls in a page's bar.
 *
 * The bar's `actions` slot arrives as a bare `Row` at `Arrangement.End`, so this is the only
 * space two `GlassIconButton`s have. 4dp is the figure the chat header already puts between
 * its marks, and it is enough that the two 44dp buttons read as two: at 420dpi it is 10.5px
 * of gutter against a 115.5px button, where the files page measured none at all.
 *
 * Small on purpose. A bar is a row of controls that belong together, and the gesture handle's
 * own inset is 12dp — a gap that wide would read as two groups and push the trailing button
 * further from the edge than `ActionIconPadding` puts it.
 */
private val ACTION_GAP = 4.dp

private val SUBTITLE_BOTTOM = 6.dp

/**
 * How long the bar's background band takes to appear and to leave.
 *
 * The library's own band fades through `maskProgress` with `tween(100, LinearEasing)`,
 * and the band is driven from this file now (the bar is reported as scrolled so its
 * buttons keep their material), so the same 100ms linear ramp is repeated here rather
 * than a curve of the kit's own: the band should look exactly as it did.
 */
private const val BAND_FADE_MILLIS = 100
