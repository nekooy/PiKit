package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
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
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassSurface
import top.yukonga.miuix.kmp.material.GlassShape
import kotlin.math.roundToInt

/**
 * The composer needs an explicit upward endpoint, which GlassTransformPopup cannot accept.
 * Use the same size, center and content springs, leaving the live trigger in place as the
 * panel fades in. Measure the scroll viewport at its final size, not on the animated bounds.
 */
@Composable
internal fun BoxScope.PiUpwardMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchor: PiMenuAnchor,
    backdrop: Backdrop?,
    sizing: GlassPopupSizing,
    content: @Composable ColumnScope.() -> Unit,
) {
    val trigger = anchor.bounds
    val open = show && !trigger.isEmpty
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = open
    val transition = rememberTransition(visibility, label = "composerMenu")
    if (!(open || visibility.currentState || !visibility.isIdle)) return

    val geometry = transition.animateFloat({ GlassMotion.transformBounds(targetState) }, label = "bounds") {
        if (it) 1f else 0f
    }
    val center = transition.animateFloat({ GlassMotion.transformCenter(targetState) }, label = "center") {
        if (it) 1f else 0f
    }
    val surface = transition.animateFloat({ GlassMotion.transformIconMaterial(targetState) }, label = "surface") {
        if (it) 1f else 0f
    }
    val rows = transition.animateFloat({ GlassMotion.transformContentMaterial(targetState) }, label = "rows") {
        if (it) 1f else 0f
    }
    val dismiss by rememberUpdatedState { if (open) onDismissRequest() }
    BackHandler(enabled = open, onBack = dismiss)
    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })

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
    val radius = with(density) {
        lerp(trigger.minDimension / 2, PiCorner.popup.toPx(), geometry.value.coerceIn(0f, 1f)).toDp()
    }
    val shape = remember(radius) { GlassShape(radius) }
    val visuals = GlassPopupDefaults.visuals()
    val material = glassSurface(backdrop, visuals)

    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
        content = {
            Box(material(shape))
            Box(Modifier.pointerInput(open) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!open) event.changes.forEach { it.consume() }
                    }
                }
            })
            Column(
                Modifier.width(IntrinsicSize.Max)
                    .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
                    .verticalScroll(rememberScrollState(), enabled = open)
                    .padding(vertical = GlassPopupDefaults.ContentPaddingVertical),
                content = content,
            )
        },
    ) { children, constraints ->
        val safe = safeWindow.intersect(Rect(origin, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())))
        val margin = sizing.safeMargin.toPx()
        val gap = 8.dp.toPx()
        val maxWidth = minOf(sizing.maxWidth.roundToPx(), (safe.width - margin * 2).toInt())
        val maxHeight = minOf(sizing.maxHeight.roundToPx(), upwardMenuMaxHeight(trigger, safe, margin, gap))
        if (trigger.isEmpty || maxWidth <= 0 || maxHeight <= 0) {
            layout(constraints.maxWidth, constraints.maxHeight) {}
        } else {
            val foreground = children[2].measure(Constraints(
                minWidth = sizing.minWidth.roundToPx().coerceAtMost(maxWidth),
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            ))
            val settled = placeUpwardMenu(trigger, Size(foreground.width.toFloat(), foreground.height.toFloat()), safe, margin, gap, direction)
            val frame = popupMorphBounds(trigger, settled, geometry.value, center.value)
            val frameWidth = frame.width.roundToInt()
            val frameHeight = frame.height.roundToInt()
            val background = children[0].measure(Constraints.fixed(frameWidth, frameHeight))
            val touch = children[1].measure(Constraints.fixed(frameWidth, frameHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val left = (frame.left - origin.x).roundToInt()
                val top = (frame.top - origin.y).roundToInt()
                background.placeWithLayer(left, top) { alpha = surface.value }
                if (open) touch.place(left, top)
                val scale = if (foreground.width > 0 && foreground.height > 0) {
                    minOf(frame.width / foreground.width, frame.height / foreground.height)
                } else {
                    1f
                }
                foreground.placeWithLayer(left, top) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = (frame.width - foreground.width * scale) / 2
                    translationY = (frame.height - foreground.height * scale) / 2
                    val fraction = rows.value.coerceIn(0f, 1f)
                    alpha = fraction
                    val blur = GlassMotion.TRANSFORM_BLUR_PX * (1f - fraction)
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
                    clip = true
                    this.shape = shape
                }
                if (!open) touch.place(left, top, zIndex = 1f)
            }
        }
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
    val minLeft = safe.left + margin
    val left = wantedLeft.coerceIn(minLeft, (safe.right - margin - size.width).coerceAtLeast(minLeft))
    val bottom = minOf(trigger.top - gap, safe.bottom - margin)
    return Rect(left, bottom - size.height, left + size.width, bottom)
}

internal fun popupMorphBounds(trigger: Rect, settled: Rect, sizeProgress: Float, centerProgress: Float): Rect {
    val width = lerp(trigger.width, settled.width, sizeProgress).coerceAtLeast(0f)
    val height = lerp(trigger.height, settled.height, sizeProgress).coerceAtLeast(0f)
    val centerX = lerp(trigger.center.x, settled.center.x, centerProgress)
    val centerY = lerp(trigger.center.y, settled.center.y, centerProgress)
    return Rect(Offset(centerX - width / 2, centerY - height / 2), Size(width, height))
}
