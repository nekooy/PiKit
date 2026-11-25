package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
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
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShape
import kotlin.math.roundToInt

internal val LocalPiMenuInteractive = staticCompositionLocalOf { true }

/**
 * The kit's own glass menu.
 *
 * Context menus pop out of their row's end edge in Miuix's `GlassPopup` shape;
 * composer menus and glass icon buttons morph from the control. A glass icon button's menu is the button
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

    val opacity = transition.animateFloat({
        if (morph) GlassMotion.transformIconMaterial(targetState) else GlassMotion.popupMorphFade(targetState)
    }, label = "opacity") { if (it) 1f else 0f }
    val geometry = if (morph) {
        transition.animateFloat({ GlassMotion.transformBounds(targetState) }, label = "bounds") { if (it) 1f else 0f }
    } else null
    val center = if (morph) {
        transition.animateFloat({ GlassMotion.transformCenter(targetState) }, label = "center") { if (it) 1f else 0f }
    } else null
    // A context menu's geometry follows the library's `GlassPopup`: a small, flat
    // capsule at the anchor edge grows into the settled panel on `popupMorph`, while
    // its contents arrive on the same fade and lose a 40px blur. This is the
    // difference between a menu that pops out of its row and one that fades in.
    val pop = if (morph) {
        null
    } else {
        // The open uses the library's popup spring; the close uses its faster exit
        // spring, so the silhouette is gone before the 150 ms row fade has finished.
        // Pairing the opening spring with the exit was what left an empty shrinking
        // shell in the earlier edge reveal.
        transition.animateFloat({
            popupProgressSpec(entering = targetState)
        }, label = "pop") { if (it) 1f else 0f }
    }
    val sharpness = if (morph) {
        null
    } else {
        transition.animateFloat({ GlassMotion.popupMorphBlur() }, label = "sharpness") { if (it) 1f else 0f }
    }
    val rows = if (morph) {
        transition.animateFloat({ GlassMotion.transformContentMaterial(targetState) }, label = "rows") { if (it) 1f else 0f }
    } else opacity
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
    val visuals = GlassPopupDefaults.visuals()
    val material = glassSurface(backdrop, visuals)
    val shadowOutsets = remember(visuals.shadow) {
        val radius = visuals.shadow?.let { it.radius + maxOf(kotlin.math.abs(it.offsetX), kotlin.math.abs(it.offsetY)) } ?: 0f
        LayerOutsets((radius / GlassDefaults.SourceDensity).dp)
    }

    Box(Modifier.matchParentSize().zIndex(if (open) 1f else 0f)) {
        if (open) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
        }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() },
            content = {
                val scroll = rememberScrollState()
                val menuRows: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalPiMenuInteractive provides open) {
                        Column(
                            Modifier
                                .width(IntrinsicSize.Max)
                                .then(if (open) Modifier.verticalScroll(scroll) else Modifier.retainedMenuScroll(scroll))
                                .padding(vertical = GlassPopupDefaults.ContentPaddingVertical),
                            content = content,
                        )
                    }
                }
                val input = if (open) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                } else Modifier
                // Two children in both modes: the glass surface and the rows. The
                // layout below measures each against the animated frame rather than
                // letting a full-window host carry a whole-panel alpha.
                Box(input.then(material(shape)))
                menuRows()
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
            // A context menu's settled rectangle also carries which horizontal edge
            // stays put while the panel unfolds: the end edge for an LTR menu, and
            // `alignTop` for the vertical one.
            val contextPlacement = if (placement == PiPopupPlacement.Auto) {
                contextMenuPlacement(settleTrigger, size, safe, margin, direction)
            } else {
                null
            }
            val settled = contextPlacement?.bounds ?: when (placement) {
                PiPopupPlacement.Above -> placeUpwardMenu(trigger, size, safe, margin, gap, direction)
                PiPopupPlacement.Auto, PiPopupPlacement.FromAnchor ->
                    placeContextMenu(settleTrigger, size, safe, margin, direction)
            }
            if (geometry == null || center == null) {
                // The context menu's opening, ported from Miuix's `GlassPopup`: a small
                // flat capsule at the anchor edge grows into the settled panel on the
                // popup spring, its rows arrive on the separate fade, and the blur the
                // library uses is the last thing to clear. The panel is not a static
                // rectangle with an opacity ramp any more.
                val reveal = pop?.value?.coerceIn(0f, 1f) ?: 1f
                val frame = contextMenuRevealFrame(
                    settled = settled,
                    size = size,
                    progress = reveal,
                    direction = direction,
                    alignTop = contextPlacement?.alignTop ?: true,
                )
                val background = children[0].measure(
                    Constraints.fixed(
                        frame.width.roundToInt().coerceAtLeast(0),
                        frame.height.roundToInt().coerceAtLeast(0),
                    ),
                )
                return@Layout layout(constraints.maxWidth, constraints.maxHeight) {
                    val left = (frame.left - origin.x).roundToInt()
                    val top = (frame.top - origin.y).roundToInt()
                    background.placeWithLayer(left, top) {
                        alpha = opacity.value.coerceIn(0f, 1f)
                        outsets = shadowOutsets
                    }
                    val scale = if (size.width > 0 && size.height > 0) {
                        minOf(frame.width / size.width, frame.height / size.height)
                    } else {
                        1f
                    }
                    foreground.placeWithLayer(left, top) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = (frame.width - size.width * scale) / 2
                        translationY = (frame.height - size.height * scale) / 2
                        alpha = opacity.value.coerceIn(0f, 1f)
                        // Blur belongs to the opening only: on exit it smears the
                        // disappearing rows into a ghost over the page.
                        val blur = if (open) {
                            (1f - (sharpness?.value ?: 1f)) * GlassMotion.POPUP_MORPH_BLUR_PX
                        } else {
                            0f
                        }
                        renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                        clip = true
                        this.shape = shape
                    }
                }
            }
            travel.centerX = settled.center.x
            travel.centerY = settled.center.y
            travel.width = size.width
            val frame = popupMorphBounds(trigger, settled, geometry.value, center.value)
            val background = children[0].measure(Constraints.fixed(frame.width.roundToInt(), frame.height.roundToInt()))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val left = (frame.left - origin.x).roundToInt()
                val top = (frame.top - origin.y).roundToInt()
                background.placeWithLayer(left, top) {
                    alpha = if (anchorContent != null) 1f else opacity.value.coerceIn(0f, 1f)
                    outsets = shadowOutsets
                }
                val scale = if (size.width > 0 && size.height > 0) {
                    minOf(frame.width / size.width, frame.height / size.height)
                } else 1f
                foreground.placeWithLayer(left, top) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = (frame.width - size.width * scale) / 2
                    translationY = (frame.height - size.height * scale) / 2
                    val fraction = rows.value.coerceIn(0f, 1f)
                    alpha = fraction
                    val blur = if (anchorContent != null) GlassMotion.TRANSFORM_BLUR_PX * (1f - fraction) else 0f
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
        if (glyph != null && geometry != null && center != null) {
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
                            val fraction = opacity.value.coerceIn(0f, 1f)
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

/**
 * The popup progress spring with a sub-pixel finish.
 *
 * `SpringSpec`'s default Float threshold is 1% of the range; on a 480px menu that is
 * 4.8px, which the animation would otherwise snap in one frame at the end of the pop.
 * 0.001 of the range is below half a pixel for any menu this app draws.
 */
private fun popupProgressSpec(entering: Boolean): SpringSpec<Float> {
    val base = if (entering) GlassMotion.popupMorph() else GlassMotion.popupExit()
    return SpringSpec(
        dampingRatio = base.dampingRatio,
        stiffness = base.stiffness,
        visibilityThreshold = 0.001f,
    )
}

/** Geometry written during measurement and read by the glyph copy's layer. */
private class TransformTravel {
    var centerX: Float = 0f
    var centerY: Float = 0f
    var width: Float = 0f

    val center: Offset get() = Offset(centerX, centerY)
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

/** A context menu's settled rectangle, and which corner it unfolds from. */
internal data class ContextMenuPlacement(val bounds: Rect, val alignTop: Boolean)

internal fun contextMenuPlacement(
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

internal fun placeContextMenu(
    trigger: Rect,
    size: Size,
    safe: Rect,
    margin: Float,
    direction: LayoutDirection,
): Rect = contextMenuPlacement(trigger, size, safe, margin, direction).bounds

/**
 * The frame a context menu occupies at [progress], ported from Miuix's `GlassPopup`.
 *
 * The settled panel's end edge and its top (or bottom) edge stay put; the opposite
 * edges unfold from a small, flat capsule at the anchor. Width, height and aspect
 * ratio are interpolated separately on the same spring, which is what makes the
 * opening read as a panel pulled out of the row rather than a rectangle scaling up.
 *
 * Progress is deliberately allowed above 1: the library's 0.82-damped spring
 * overshoots a little, and the frame has to carry that rebound with the rows.
 */
internal fun contextMenuRevealFrame(
    settled: Rect,
    size: Size,
    progress: Float,
    direction: LayoutDirection,
    alignTop: Boolean,
): Rect {
    val t = progress.coerceAtLeast(0f)
    val width = size.width * (GlassMotion.POPUP_START_WIDTH + (1f - GlassMotion.POPUP_START_WIDTH) * t)
    val endRatio = if (size.width > 0f) size.height / size.width else 1f
    val ratio = GlassMotion.POPUP_START_RATIO + (endRatio - GlassMotion.POPUP_START_RATIO) * t
    val height = width * ratio
    val left = if (direction == LayoutDirection.Ltr) settled.right - width else settled.left
    val top = if (alignTop) settled.top else settled.bottom - height
    return Rect(left, top, left + width, top + height)
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

internal fun popupMorphBounds(trigger: Rect, settled: Rect, sizeProgress: Float, centerProgress: Float): Rect {
    val width = lerp(trigger.width, settled.width, sizeProgress).coerceAtLeast(0f)
    val height = lerp(trigger.height, settled.height, sizeProgress).coerceAtLeast(0f)
    val centerX = lerp(trigger.center.x, settled.center.x, centerProgress)
    val centerY = lerp(trigger.center.y, settled.center.y, centerProgress)
    return Rect(Offset(centerX - width / 2, centerY - height / 2), Size(width, height))
}
