package pi.kit.mob.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val PiTopEdgeClearance = 30.dp

/** A bounded scrolling region below fixed controls. The sampler is a sibling of the recording. */
@Composable
internal fun PiTopEdgeViewport(
    contentScrolled: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    content: @Composable (onOffsetChanged: (Float) -> Unit) -> Unit,
) {
    val backdrop = rememberPiBackdrop(color)
    var overscroll by remember { mutableFloatStateOf(0f) }
    val reportOverscroll: (Float) -> Unit = remember { { overscroll = it } }
    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            content(reportOverscroll)
        }
        if (contentScrolled || overscroll < -1f) {
            PiTopEdgeFade(backdrop, color, PiTopEdgeClearance)
        }
    }
}

/** Progressive texture blur on supported devices, a contrast fade on older Android versions. */
@Composable
internal fun PiTopEdgeFade(
    backdrop: Backdrop?,
    color: Color,
    height: Dp,
    solidInset: Dp = 0.dp,
) {
    if (height <= 0.dp) return
    val inset = (solidInset / height).coerceIn(0f, 0.99f)
    val ramp = 1f - inset
    val effect = if (backdrop != null) {
        val tint = remember(color) { listOf(BlendColorEntry(color.copy(alpha = 0.3f))) }
        Modifier.progressiveTextureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
            gradient = ProgressiveBlur.Top.copy(startFraction = inset, curve = 0.25f),
            colors = BlurDefaults.blurColors(blendColors = tint),
        ).background(
            Brush.verticalGradient(
                0f to color,
                inset to color,
                inset + ramp * 0.18f to Color.Transparent,
            ),
        )
    } else {
        Modifier.background(
            Brush.verticalGradient(
                0f to color,
                inset to color,
                inset + ramp * 0.45f to color.copy(alpha = 0.62f),
                inset + ramp * 0.8f to color.copy(alpha = 0.26f),
                1f to Color.Transparent,
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(height).then(effect))
}
