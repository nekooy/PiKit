package pi.kit.mob.ui.kit

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.AgentMode
import pi.kit.mob.locales.Strings
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * The two launch modes as one sliding segment.
 *
 * The selected half is not a second background per segment: it is one thumb that
 * travels between the two labels, which is what makes the answer read as a position
 * rather than as two independent buttons. A tap animates the thumb to the label
 * that was tapped, and a horizontal drag moves it under the finger and settles on
 * the nearer side — the control is small enough that a thumb expects to push it.
 *
 * **What a change means is the caller's.** Today the drawer's foot uses it to *start* a
 * conversation in the mode tapped and the history row's menu converts a saved one; the
 * control draws the choice and reports it, and the page behind it decides whether that
 * becomes a new process or a relaunch. That is why [onModeChange] is named for the
 * answer rather than for a toggle: the switch has been both, and the shape of the
 * control survived the change.
 *
 * The labels are the library's own pair — [MiuixTheme.textStyles.subtitle] for the chosen
 * half, `body2` for the other — because the glass path is `GlassSegmentedTabRow` and the
 * hand-drawn fallback below API 33 has to be the same control at the same size. The first
 * version used `title3`, which made a binary switch taller and louder than the
 * conversation it sits over; the switch is a control about a message, not a page title.
 */
@Composable
fun PiModeSwitch(
    mode: AgentMode,
    onModeChange: (AgentMode) -> Unit,
    text: Strings,
    modifier: Modifier = Modifier,
    /**
     * The drawer's own layer. When present the switch is Miuix's
     * [GlassSegmentedTabRow] — its track, indicator and drag are the library's — and a
     * missing backdrop (Android below 33) keeps the opaque capsule below as the
     * fallback.
     */
    backdrop: LayerBackdrop? = null,
) {
    val haptics = LocalPiHaptics.current
    // The library's segmented row draws the chosen label in `subtitle` and the other in
    // `body2`; the hand-drawn fallback below API 33 used `paragraph`, so the same control
    // changed type size on a device without the shader. Both paths state the library's
    // pair, and the measurement uses it, so the capsule is the same width either way.
    val style = MiuixTheme.textStyles.subtitle
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Both labels share one width: the thumb is a single rectangle, and two segments
    // of different widths would put its centre off the label it belongs to.
    val labelWidth = maxOf(
        measurer.measure(text = text.settings.modeWork, style = style).size.width,
        measurer.measure(text = text.settings.modeChat, style = style).size.width,
    )
    val segmentWidthPx = labelWidth + with(density) { (MODE_LABEL_PADDING * 2).toPx() }
    val segmentWidth = with(density) { segmentWidthPx.toDp() }
    val maxOffset = segmentWidthPx

    if (backdrop != null) {
        // The library's segmented row uses its own type ladder (`subtitle` for the chosen
        // tab, `body2` for the other) and its own 16dp side padding plus an 8dp gap. The
        // old 13dp/paragraph measurement is a few dp too narrow for that, which is how
        // 工作/聊天 became "..."/"..." on the first build. Measure the library's own
        // styles and paddings here, and the control is exactly as wide as its labels.
        val glassLabelWidth = maxOf(
            measurer.measure(text = text.settings.modeWork, style = MiuixTheme.textStyles.subtitle).size.width,
            measurer.measure(text = text.settings.modeChat, style = MiuixTheme.textStyles.subtitle).size.width,
            measurer.measure(text = text.settings.modeWork, style = MiuixTheme.textStyles.body2).size.width,
            measurer.measure(text = text.settings.modeChat, style = MiuixTheme.textStyles.body2).size.width,
        )
        val glassSegmentWidthPx = glassLabelWidth +
            with(density) {
                (GlassSegmentedTabRowDefaults.TabPaddingHorizontal * 2).toPx() +
                    // 8dp per segment — 4dp beside each label: the library's text is
                    // laid out with its own style, and at the 0.95 baseline scale the
                    // measured advance rounds up on the device — at exactly the
                    // padding + label width the last glyph became an ellipsis.
                    8.dp.toPx()
            }
        val glassWidth = with(density) {
            (glassSegmentWidthPx * 2 + GlassSegmentedTabRowDefaults.TabGap.toPx()).toDp()
        }
        // One Miuix control, not a hand-drawn capsule: the library's segmented row owns
        // the glass track, the sliding indicator and the drag gesture. Both labels take
        // an equal share, which is the rule the old thumb also obeyed.
        GlassSegmentedTabRow(
            tabs = listOf(text.settings.modeWork, text.settings.modeChat),
            selectedIndex = if (mode == AgentMode.WORK) 0 else 1,
            onSelect = { index ->
                val target = if (index == 0) AgentMode.WORK else AgentMode.CHAT
                if (target != mode) {
                    // §16: the drawer foot's mode switch is a floating capsule, the
                    // `tap()` family — it starts a conversation in the chosen mode.
                    haptics.tap()
                    onModeChange(target)
                }
            },
            backdrop = backdrop,
            modifier = modifier.width(glassWidth),
            height = MODE_SWITCH_HEIGHT,
            // The app's mode switch keeps the accented thumb it has always drawn:
            // the library's default segmented style is a neutral grey wash, and the
            // two launch modes are a choice the page says in the accent everywhere
            // else. The glass track around it is the library's.
            indicatorColor = MiuixTheme.colorScheme.primary,
            selectedContentColor = MiuixTheme.colorScheme.onPrimary,
            contentColor = MiuixTheme.colorScheme.onSurface,
        )
        return
    }

    fun targetOf(value: AgentMode): Float =
        if (value == AgentMode.WORK) 0f else maxOffset

    var thumbPx by remember { mutableFloatStateOf(targetOf(mode)) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(mode, maxOffset, dragging) {
        if (dragging) return@LaunchedEffect
        animate(
            initialValue = thumbPx,
            targetValue = targetOf(mode),
            animationSpec = tween(durationMillis = MODE_SLIDE_MILLIS),
        ) { value, _ -> thumbPx = value }
    }

    val dragState = rememberDraggableState { delta ->
        thumbPx = (thumbPx + delta).coerceIn(0f, maxOffset)
    }

    Box(
        modifier = modifier
            .height(MODE_SWITCH_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .background(PiGlass.fill())
            .padding(MODE_SWITCH_INSET)
            .selectableGroup()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    val target = when {
                        velocity > MODE_FLING_VELOCITY -> AgentMode.CHAT
                        velocity < -MODE_FLING_VELOCITY -> AgentMode.WORK
                        thumbPx >= maxOffset / 2f -> AgentMode.CHAT
                        else -> AgentMode.WORK
                    }
                    if (target != mode) {
                        // §16: the drawer foot's mode switch is one of the *floating
                        // capsules*, the `tap()` family — it starts the conversation in
                        // the chosen mode rather than flipping the current one.
                        haptics.tap()
                        onModeChange(target)
                    }
                },
            ),
    ) {
        // The thumb is drawn under the labels and moved with a layer offset, so a
        // drag frame relayouts nothing but this one box.
        Box(
            Modifier
                .offset { IntOffset(thumbPx.roundToInt(), 0) }
                .width(segmentWidth)
                .height(MODE_SEGMENT_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(MiuixTheme.colorScheme.primary),
        )
        Row(Modifier.height(MODE_SEGMENT_HEIGHT)) {
            ModeSegment(
                label = text.settings.modeWork,
                selected = mode == AgentMode.WORK,
                width = segmentWidth,
                style = style,
                onClick = {
                    if (mode != AgentMode.WORK) {
                        haptics.tap()
                        onModeChange(AgentMode.WORK)
                    }
                },
            )
            ModeSegment(
                label = text.settings.modeChat,
                selected = mode == AgentMode.CHAT,
                width = segmentWidth,
                style = style,
                onClick = {
                    if (mode != AgentMode.CHAT) {
                        haptics.tap()
                        onModeChange(AgentMode.CHAT)
                    }
                },
            )
        }
    }
}

/** One half of [PiModeSwitch]; the thumb behind it supplies the chosen state. */
@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    width: Dp,
    style: TextStyle,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(MODE_SEGMENT_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

/** The outer capsule: 36dp of segment plus the pill's own 3dp inset on each side. */
private val MODE_SWITCH_HEIGHT = 42.dp

/** The travelling thumb's height, and one label's touch box. */
private val MODE_SEGMENT_HEIGHT = 36.dp

/** The inset that separates the thumb from the capsule's rounded edge. */
private val MODE_SWITCH_INSET = 3.dp

/** The air between a label and its segment's edge: the library's own 16dp tab padding. */
private val MODE_LABEL_PADDING = 16.dp

/** How long the thumb takes to travel when a tap changes the mode. */
private const val MODE_SLIDE_MILLIS = 180

/** A release faster than this settles on the side it was thrown toward. */
private const val MODE_FLING_VELOCITY = 420f
