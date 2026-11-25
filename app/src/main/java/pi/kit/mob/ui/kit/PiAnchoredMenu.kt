package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassSurface
import top.yukonga.miuix.kmp.material.GlassShape
import kotlin.math.roundToInt

internal val LocalPiMenuInteractive = staticCompositionLocalOf { true }

/**
 * The kit's own glass menu.
 *
 * Context menus take Miuix's `GlassPopup` edge reveal; composer menus and a glass icon
 * button's menu are the library's transform. A glass icon button's menu is the button
 * becoming the menu: the button is hidden for the whole transform and the panel's first
 * frame *is* that button — a copy of its glyph, reported through
 * [Modifier.piGlassMenuAnchorContent], grows into the panel and fades as the rows arrive,
 * and the panel's surface starts at the button's own surface. That surface is the panel's
 * glass at both ends because the button samples a backdrop of its own — its bar's, or the
 * ground its call site records — so neither end of the transform swaps one surface for
 * another. The button is hidden only because the panel's first frame is it: a second copy
 * left under the glass showed through as a ghost, and a copy left beside the growing frame
 * read as a pill standing next to its own menu.
 */
@Composable
internal fun BoxScope.PiAnchoredMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchor: PiMenuAnchor,
    placement: PiPopupPlacement,
    backdrop: Backdrop?,
    sizing: GlassPopupSizing,
    below: Float = 0f,
    anchorContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val trigger = anchor.bounds
    // `Auto` is the library's own edge reveal; the other two grow out of the control.
    val morph = placement != PiPopupPlacement.Auto
    val open = show && if (morph) !trigger.isEmpty else trigger != Rect.Zero
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = open
    val transition = rememberTransition(visibility, label = "anchoredMenu")
    // Active covers the exit as well: the button stays hidden until its copy has shrunk
    // back onto it, and the copy is what the reader sees hand the control back.
    val active = open || visibility.currentState || !visibility.isIdle
    if (anchorContent != null) {
        SideEffect { anchor.contentHidden = active }
        DisposableEffect(anchor) { onDispose { anchor.contentHidden = false } }
    }
    if (!active) return

    val geometry = transition.animateFloat({
        when {
            morph -> GlassMotion.transformBounds(targetState)
            else -> GlassMotion.popupMorph()
        }
    }, label = "bounds") { if (it) 1f else 0f }
    val center = if (morph) {
        transition.animateFloat({ GlassMotion.transformCenter(targetState) }, label = "center") { if (it) 1f else 0f }
    } else {
        geometry
    }
    val surface = transition.animateFloat({
        if (morph) GlassMotion.transformIconMaterial(targetState) else GlassMotion.popupMorphFade(targetState)
    }, label = "surface") { if (it) 1f else 0f }
    val rows = if (morph) {
        transition.animateFloat({ GlassMotion.transformContentMaterial(targetState) }, label = "rows") { if (it) 1f else 0f }
    } else {
        surface
    }
    // The library's popup blur: 40px at the start of the reveal, sharp by the time the
    // rows are readable. It is what carries an edge reveal that has no glyph to hand over.
    val sharpness = transition.animateFloat({ GlassMotion.popupMorphBlur() }, label = "sharpness") {
        if (it) 1f else 0f
    }
    val dismiss by rememberUpdatedState(onDismissRequest)
    BackHandler(enabled = open) { dismiss() }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout.union(WindowInsets.statusBars)
        .union(WindowInsets.navigationBars).union(WindowInsets.captionBar)
    val window = LocalWindowInfo.current.containerSize
    val safeWindow = Rect(
        left = insets.getLeft(density, direction).toFloat(),
        top = insets.getTop(density).toFloat(),
        right = (window.width - insets.getRight(density, direction)).toFloat(),
        bottom = (window.height - insets.getBottom(density)).toFloat(),
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    // The settled rectangle, written during the panel's measure and read by the glyph
    // copy's layer. Not snapshot state: a write during measure must not invalidate that
    // same pass; the copy's layer re-runs because it also reads the animation states.
    val travel = remember { TransformTravel() }
    val copyRect = anchor.contentBounds.takeUnless { it.isEmpty } ?: trigger
    // One shape for the whole transition: the 24dp corner is clamped to half the smaller
    // side while the frame is still small, so the collapsed endpoint is the trigger's own
    // capsule — and the corner is not read from the animation in composition (docs/UI.md,
    // *State, and what a frame may not cost*).
    val shape = remember { GlassShape(PiCorner.popup) }
    val menuClip = remember { MenuClip() }
    val clipRadius = with(density) { PiCorner.popup.toPx() }
    val material = glassSurface(backdrop, GlassPopupDefaults.visuals())

    Box(Modifier.matchParentSize().zIndex(if (open) 1f else 0f)) {
        if (open) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
        }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() },
            content = {
                // The panel surface, and — for a button that has no backdrop of its own —
                // the button's opaque fill on top of it. The fill is opaque at the first
                // frame and fades as the glass takes over, so the morph starts on the
                // control the reader pressed instead of on a panel-shaped circle.
                Box(
                    Modifier.matchParentSize().then(if (open) Modifier.pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    } else Modifier),
                ) {
                    Box(
                        Modifier.matchParentSize()
                            .then(material(shape))
                            .then(
                                if (anchorContent != null) {
                                    Modifier.graphicsLayer { alpha = surface.value.coerceIn(0f, 1f) }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                val scroll = rememberScrollState()
                CompositionLocalProvider(LocalPiMenuInteractive provides open) {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .then(if (open) Modifier.verticalScroll(scroll) else Modifier.retainedMenuScroll(scroll))
                            .padding(vertical = GlassPopupDefaults.ContentPaddingVertical)
                            // The rows keep their settled size while an edge-revealed
                            // panel unfolds; the panel's frame is what clips them, the
                            // same division `GlassPopup` draws. The clip is computed
                            // from the animation state in the draw pass: a path built
                            // during measure is recorded once into the row layer and
                            // never re-recorded, which left only the first row visible.
                            .then(
                                if (morph) Modifier else Modifier.drawWithContent {
                                    val settled = menuClip.settled
                                    if (settled.width > 0.5f && settled.height > 0.5f) {
                                        val t = geometry.value.coerceAtLeast(0f)
                                        val endRatio = settled.height / settled.width
                                        val ratio = GlassMotion.POPUP_START_RATIO +
                                            (endRatio - GlassMotion.POPUP_START_RATIO) * t
                                        val height = (settled.width * ratio)
                                            .coerceIn(0f, size.height)
                                        menuClip.path.rewind()
                                        menuClip.path.addRoundRect(
                                            RoundRect(
                                                rect = Rect(0f, 0f, size.width, height),
                                                cornerRadius = CornerRadius(
                                                    minOf(clipRadius, minOf(size.width, height) / 2f),
                                                ),
                                            ),
                                        )
                                        clipPath(menuClip.path) { this@drawWithContent.drawContent() }
                                    } else {
                                        drawContent()
                                    }
                                },
                            ),
                        content = content,
                    )
                }
            },
        ) { children, constraints ->
            val safe = safeWindow.intersect(Rect(origin, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())))
            val margin = sizing.safeMargin.toPx()
            val gap = 8.dp.toPx()
            val maxWidth = minOf(sizing.maxWidth.roundToPx(), (safe.width - margin * 2).toInt())
            // `below` is a bar's measured bottom in window coordinates. A bar button's
            // menu hangs from the bar rather than overlapping its subtitle; the morph
            // still starts at the button, only the settled rectangle moves down.
            val settleTrigger = if (placement == PiPopupPlacement.FromAnchor) {
                menuSettleTrigger(trigger, below)
            } else {
                trigger
            }
            val availableHeight = when (placement) {
                PiPopupPlacement.Above -> upwardMenuMaxHeight(trigger, safe, margin, gap)
                PiPopupPlacement.Auto ->
                    (safe.bottom - margin - maxOf(safe.top, minOf(safe.top + margin, trigger.top))).toInt()
                PiPopupPlacement.FromAnchor ->
                    (safe.bottom - margin - maxOf(safe.top, minOf(safe.top + margin, settleTrigger.top))).toInt()
            }
            val maxHeight = minOf(sizing.maxHeight.roundToPx(), availableHeight)
            if (maxWidth <= 0 || maxHeight <= 0) {
                menuClip.settled = Rect.Zero
                return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
            }
            val foreground = children[1].measure(
                Constraints(
                    minWidth = sizing.minWidth.roundToPx().coerceAtMost(maxWidth),
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                ),
            )
            val size = Size(foreground.width.toFloat(), foreground.height.toFloat())
            var contextPlacement: ContextMenuPlacement? = null
            val settled = when (placement) {
                PiPopupPlacement.Above -> placeUpwardMenu(trigger, size, safe, margin, gap, direction)
                PiPopupPlacement.Auto -> placeContextMenuPlacement(settleTrigger, size, safe, margin, direction)
                    .also { contextPlacement = it }
                    .rect

                PiPopupPlacement.FromAnchor -> placeContextMenu(settleTrigger, size, safe, margin, direction)
            }
            menuClip.settled = settled
            travel.centerX = settled.center.x
            travel.centerY = settled.center.y
            travel.width = size.width
            val frame = if (morph) {
                popupMorphBounds(trigger, settled, geometry.value, center.value)
            } else {
                popupEdgeRevealBounds(
                    settled = settled,
                    direction = direction,
                    alignTop = contextPlacement?.alignTop ?: true,
                    progress = geometry.value,
                )
            }
            val background = children[0].measure(Constraints.fixed(frame.width.roundToInt(), frame.height.roundToInt()))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val left = (frame.left - origin.x).roundToInt()
                val top = (frame.top - origin.y).roundToInt()
                background.placeWithLayer(left, top) {
                    // The edge-reveal panel is glass from its first frame, as `GlassPopup`
                    // draws it; a glyph menu's surface is opaque from its first frame too
                    // — the button it replaces — and fades out under the glass. Only the
                    // composer's transform fades a whole panel in.
                    alpha = if (morph && anchorContent == null) surface.value.coerceIn(0f, 1f) else 1f
                }
                val contentScale = if (morph) 1f else popupContentScale(geometry.value)
                val scale = if (morph && size.width > 0 && size.height > 0) {
                    minOf(frame.width / size.width, frame.height / size.height)
                } else {
                    contentScale
                }
                foreground.placeWithLayer(left, top) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    if (morph) {
                        translationX = (frame.width - size.width * scale) / 2
                        translationY = (frame.height - size.height * scale) / 2
                    }
                    val fraction = rows.value.coerceIn(0f, 1f)
                    alpha = fraction
                    val blur = if (morph) {
                        GlassMotion.TRANSFORM_BLUR_PX * (1f - fraction)
                    } else {
                        // The rest of Miuix's edge reveal: the rows are out of focus while
                        // the panel is small and sharpen as it reaches its own size.
                        GlassMotion.POPUP_MORPH_BLUR_PX * (1f - sharpness.value.coerceIn(0f, 1f))
                    }
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                    clip = true
                    this.shape = shape
                }
            }
        }
        // The button's glyph, handed over to the panel: it starts exactly on the button
        // (scale 1, no offset, no blur), grows to the settled panel's width and centre,
        // and fades as the rows arrive. The whole button is hidden for as long as this
        // exists, and the panel's first frame is that button, so the reader sees one
        // object becoming the menu rather than a second icon beside it.
        val glyph = anchorContent
        if (glyph != null) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        Modifier.graphicsLayer {
                            val motion = anchorCopyMotion(
                                trigger = trigger,
                                settledCenter = travel.center,
                                settledWidth = travel.width,
                                sizeProgress = geometry.value,
                                centerProgress = center.value,
                            )
                            translationX = motion.translation.x
                            translationY = motion.translation.y
                            scaleX = motion.scale
                            scaleY = motion.scale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            val fraction = surface.value.coerceIn(0f, 1f)
                            alpha = 1f - fraction
                            val blur = GlassMotion.TRANSFORM_BLUR_PX * fraction
                            renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                        },
                    ) { glyph() }
                },
            ) { children, constraints ->
                val placeable = children[0].measure(
                    Constraints.fixed(
                        width = copyRect.width.roundToInt().coerceAtLeast(0),
                        height = copyRect.height.roundToInt().coerceAtLeast(0),
                    ),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (copyRect.left - origin.x).roundToInt(),
                        (copyRect.top - origin.y).roundToInt(),
                    )
                }
            }
        }
    }
}

/** Geometry written during measurement and read by the glyph copy's layer. */
private class TransformTravel {
    var centerX: Float = 0f
    var centerY: Float = 0f
    var width: Float = 0f

    val center: Offset get() = Offset(centerX, centerY)
}

/**
 * The part of a menu's settled content that its current frame shows, in the content's own
 * coordinates, written while the panel is measured.
 *
 * Not snapshot state: a write during measure must not invalidate that same pass. The
 * draw pass reads it behind the animation state that moved the frame.
 */
private class MenuClip {
    /** The panel's settled rectangle, in window coordinates. Written during measure. */
    var settled: Rect = Rect.Zero
    val path: Path = Path()
}

/** The glyph copy's offset from, and growth over, the button it starts as. */
internal data class AnchorCopyMotion(val translation: Offset, val scale: Float)

/**
 * How far the button's glyph has travelled into the panel, and how much it has grown.
 *
 * The collapsed endpoint is the button and the expanded one is the settled panel, so at
 * progress 0 the copy is the button itself and at 1 it spans the panel. The intermediate
 * frames are what make the menu look like the button unfolding rather than a panel fading
 * in over it.
 */
internal fun anchorCopyMotion(
    trigger: Rect,
    settledCenter: Offset,
    settledWidth: Float,
    sizeProgress: Float,
    centerProgress: Float,
): AnchorCopyMotion {
    val growth = sizeProgress.coerceIn(0f, 1f)
    val travel = centerProgress.coerceIn(0f, 1f)
    val width = trigger.width + (settledWidth - trigger.width) * growth
    val scale = if (trigger.width > 0f) (width / trigger.width).coerceAtLeast(0f) else 1f
    return AnchorCopyMotion(
        translation = Offset(
            (settledCenter.x - trigger.center.x) * travel,
            (settledCenter.y - trigger.center.y) * travel,
        ),
        scale = scale,
    )
}

/** Keep the visible scroll offset on exit without retaining the scroll gesture node. */
private fun Modifier.retainedMenuScroll(scroll: ScrollState): Modifier = clearAndSetSemantics {}.layout { measurable, constraints ->
    val rows = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val height = rows.height.coerceAtMost(constraints.maxHeight)
    layout(rows.width, height) {
        rows.placeRelative(0, -scroll.value.coerceAtMost(rows.height - height))
    }
}

internal fun upwardMenuMaxHeight(trigger: Rect, safe: Rect, margin: Float, gap: Float): Int =
    (minOf(trigger.top - gap, safe.bottom - margin) - safe.top - margin).toInt().coerceAtLeast(0)

internal fun placeUpwardMenu(
    trigger: Rect,
    size: Size,
    safe: Rect,
    margin: Float,
    gap: Float,
    direction: LayoutDirection,
): Rect {
    val wantedLeft = if (direction == LayoutDirection.Ltr) trigger.left else trigger.right - size.width
    val left = clampedMenuLeft(wantedLeft, size, safe, margin)
    val bottom = minOf(trigger.top - gap, safe.bottom - margin)
    return Rect(left, bottom - size.height, left + size.width, bottom)
}

/** A context menu's settled rectangle and which of its horizontal edges is pinned. */
internal data class ContextMenuPlacement(val rect: Rect, val alignTop: Boolean)

internal fun placeContextMenu(trigger: Rect, size: Size, safe: Rect, margin: Float, direction: LayoutDirection): Rect =
    placeContextMenuPlacement(trigger, size, safe, margin, direction).rect

internal fun placeContextMenuPlacement(
    trigger: Rect,
    size: Size,
    safe: Rect,
    margin: Float,
    direction: LayoutDirection,
): ContextMenuPlacement {
    val left = endAlignedMenuLeft(trigger, size, safe, margin, direction)
    val minTop = maxOf(safe.top, minOf(safe.top + margin, trigger.top))
    val below = safe.bottom - margin - trigger.top
    val above = trigger.bottom - minTop
    val alignTop = size.height <= below || below >= above
    val wantedTop = if (alignTop) trigger.top else trigger.bottom - size.height
    val top = wantedTop.coerceIn(minTop, (safe.bottom - margin - size.height).coerceAtLeast(minTop))
    return ContextMenuPlacement(Rect(Offset(left, top), size), alignTop)
}

/**
 * The trigger rectangle a menu's settled placement is computed from.
 *
 * [below] is a bar's measured bottom in window coordinates. A menu opened from a control
 * in the bar settles below the bar rather than over its subtitle; the morph still starts
 * at the real button, which is why this rectangle is only used for the settled endpoint.
 */
internal fun menuSettleTrigger(trigger: Rect, below: Float): Rect =
    if (below > trigger.top) trigger.copy(top = below, bottom = below) else trigger

private fun endAlignedMenuLeft(trigger: Rect, size: Size, safe: Rect, margin: Float, direction: LayoutDirection): Float =
    clampedMenuLeft(if (direction == LayoutDirection.Ltr) trigger.right - size.width else trigger.left, size, safe, margin)

private fun clampedMenuLeft(wanted: Float, size: Size, safe: Rect, margin: Float): Float {
    val minLeft = safe.left + margin
    return wanted.coerceIn(minLeft, (safe.right - margin - size.width).coerceAtLeast(minLeft))
}

/**
 * Miuix's `GlassPopup` edge reveal: a flat capsule on the anchor's logical end grows to the
 * settled panel on two springs — width first, then height.
 *
 * The fixed edges are the ones the reveal happens *from*: the logical end stays put, and so
 * does the horizontal edge the panel was placed against. `progress` is deliberately not
 * clamped above 1: `GlassMotion.popupMorph()` overshoots, and the panel and its rows must
 * rebound together rather than the frame being pinned while the content overshoots.
 */
internal fun popupEdgeRevealBounds(
    settled: Rect,
    direction: LayoutDirection,
    alignTop: Boolean,
    progress: Float,
): Rect {
    val t = progress.coerceAtLeast(0f)
    val width = settled.width * (GlassMotion.POPUP_START_WIDTH + (1f - GlassMotion.POPUP_START_WIDTH) * t)
    val endRatio = if (settled.width > 0f) settled.height / settled.width else 1f
    val ratio = GlassMotion.POPUP_START_RATIO + (endRatio - GlassMotion.POPUP_START_RATIO) * t
    val height = width * ratio
    val left = if (direction == LayoutDirection.Ltr) settled.right - width else settled.left
    val top = if (alignTop) settled.top else settled.bottom - height
    return Rect(Offset(left, top), Size(width, height))
}

/** The fraction of its settled width a `GlassPopup`'s rows start at. */
internal fun popupContentScale(progress: Float): Float =
    GlassMotion.POPUP_START_WIDTH + (1f - GlassMotion.POPUP_START_WIDTH) * progress.coerceAtLeast(0f)

internal fun popupMorphBounds(trigger: Rect, settled: Rect, sizeProgress: Float, centerProgress: Float): Rect {
    val width = lerp(trigger.width, settled.width, sizeProgress).coerceAtLeast(0f)
    val height = lerp(trigger.height, settled.height, sizeProgress).coerceAtLeast(0f)
    val centerX = lerp(trigger.center.x, settled.center.x, centerProgress)
    val centerY = lerp(trigger.center.y, settled.center.y, centerProgress)
    return Rect(Offset(centerX - width / 2, centerY - height / 2), Size(width, height))
}
