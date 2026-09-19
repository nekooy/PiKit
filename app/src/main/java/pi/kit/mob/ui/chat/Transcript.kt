package pi.kit.mob.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.NoticeKind
import pi.kit.mob.pi.ToolState
import pi.kit.mob.ui.clipEntryFor
import pi.kit.mob.ui.components.PiIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One reading of the list's scroll state.
 *
 * [atBottom] is `!canScrollForward` rather than "the last index is visible": a
 * LazyColumn reports the last item's *index*, and the row an answer is streaming into
 * changes height on every token — so an index test says "at the bottom" while a third
 * of the reply is still below the fold. The offset test is the honest one.
 */
internal data class FollowScrollEvent(
    val scrolling: Boolean,
    /** `!LazyListState.canScrollForward`, i.e. the viewport shows the end. */
    val atBottom: Boolean,
    /**
     * True for the frames this app's own `scrollToItem` owns. A programmatic
     * scroll is indistinguishable from a drag by its position — both move it —
     * so the only way to tell them apart is for the code that starts one to say
     * so.
     */
    val programmatic: Boolean,
)

/** Whether the transcript should keep chasing the tail of a running answer. */
internal data class FollowTailState(
    val follows: Boolean = true,
    /**
     * Whether a gesture has been seen through to its end yet.
     *
     * Not part of the decision the caller makes; it is what separates "the reader
     * is at the bottom and idle" from "the reader is passing through the bottom
     * on the way somewhere else".
     */
    private val dragging: Boolean = false,
) {
    /**
     * Folds one reading of the scroll state.
     *
     * The rule the user asked for, and the reason this is a state machine rather
     * than a `derivedStateOf` on the position: **following stops the moment the
     * reader leaves the bottom**, and starts again the moment they come back to
     * it.
     *
     * Both this and the reader write to the same scroll position, and a streamed
     * answer scrolls on every token, so an unconditional `scrollToItem` is a tug
     * of war the reader always loses: they scroll up to read a command's output
     * and the next token yanks them back to the bottom, every few milliseconds.
     * That is the "not smooth" this fixes, and it cannot be seen from the position
     * alone — the position also moves when this scrolls it.
     *
     * Three decisions, each of which was wrong in an earlier draft:
     *
     *  - **The position decides, not the start of the gesture.** Stopping on the
     *    first frame of a drag that *began* at the bottom looks right — "the
     *    reader grabbed the tail" — but a phone scroll is a small drag repeated,
     *    and a reader who nudges up and returns to the bottom has left and come
     *    back. Reading the position means the answer is "stop" exactly while they
     *    are away and "go" the moment they are back, with no state to un-stick.
     *  - **A gesture's judgement waits for its end.** Mid-fling the offset passes
     *    through the bottom on its way further up; resuming on that frame is the
     *    same tug of war, one fling long. So the answer is re-read on the frame
     *    the gesture ends, which is where a fling has actually landed. That frame
     *    is also the only one allowed to write the answer, because a streamed
     *    token changes the list's content and therefore `atBottom` too — reading
     *    that as "the reader is at the bottom" would resume the follow without
     *    anybody touching the screen.
     *  - **Our own scroll is not a gesture.** `scrollToItem` sets
     *    `isScrollInProgress` exactly as a drag does, so without
     *    [FollowScrollEvent.programmatic] every follow would turn itself off on
     *    its own first frame.
     */
    fun onScroll(event: FollowScrollEvent): FollowTailState = when {
        event.programmatic -> this

        event.scrolling ->
            copy(follows = event.atBottom, dragging = !event.atBottom)

        // Not scrolling, and a gesture was in progress: this is the frame it
        // ended on, and where it landed is the answer. `dragging` clears either
        // way — a gesture does not survive its own end.
        dragging -> FollowTailState(follows = event.atBottom, dragging = false)

        else -> this
    }
}

/**
 * The live answer to "is the transcript following its own tail", and the one thing the
 * chat screen is still allowed to do about it.
 *
 * A class rather than a bare `State<FollowTailState>` because the reader's own actions
 * have to be able to move it: tapping a message does not move the scroll position, so
 * nothing about the scroll state would say they want the tail again. [enable] is the
 * only write the page makes; the rule itself stays in [FollowTailState], which is
 * immutable and tested on its own.
 *
 * There used to be a `stop` as well, for the turn rail's jump — a jump to an earlier
 * turn is the reader saying "not the tail", and a programmatic scroll is deliberately
 * invisible to the rule, so nothing else could carry that intent. The rail was removed
 * (see ARCHITECTURE.md, "The turn rail was removed") and `stop` went with it: the
 * reader's own scrolling is the only thing that stops the follow now.
 */
internal class FollowTailController(initial: FollowTailState) {
    private val state = mutableStateOf(initial)

    val follows: Boolean get() = state.value.follows

    /** The state as a composable [State], so a change recomposes the reader. */
    val asState: State<FollowTailState> get() = state

    /** Turns following back on. The position decides from here. */
    fun enable() {
        if (!state.value.follows) state.value = FollowTailState()
    }

    internal fun apply(next: FollowTailState) {
        if (next != state.value) state.value = next
    }
}

/**
 * Folds the list's scroll state into a [FollowTailController], once per change.
 *
 * `snapshotFlow` rather than a state read in composition, so the decision costs
 * no recomposition of the transcript: this runs on every frame of a scroll and
 * on every streamed token, and a `derivedStateOf` reading the same values would
 * dirty the whole list each time.
 *
 * [programmatic] is the app's own flag, and it has to come from the caller: the
 * one scroll this file's rule must not read as the reader leaving is a scroll
 * the *caller* started, and a local copy here would never be set by anyone. It
 * was a local copy until this signature changed, which is why a follow that
 * landed anywhere but the very bottom — which is what `scrollToItem(last)` did
 * for an answer taller than the screen — turned the following off with its own
 * first frame and left the transcript with no way back to the tail.
 */
@Composable
internal fun rememberFollowTail(
    listState: LazyListState,
    programmatic: State<Boolean>,
): FollowTailController {
    val controller = remember { FollowTailController(FollowTailState()) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.canScrollForward, programmatic.value)
        }
            .distinctUntilChanged()
            .collect { (scrolling, canScrollForward, ours) ->
                controller.apply(
                    controller.asState.value.onScroll(
                        FollowScrollEvent(
                            scrolling = scrolling,
                            atBottom = !canScrollForward,
                            programmatic = ours,
                        ),
                    ),
                )
            }
    }
    return controller
}

/**
 * Puts the end of the transcript on screen.
 *
 * `scrollToItem(last)` is the obvious call and it is *not* this one: it brings
 * the last item to the **top** of the viewport, so an answer taller than the
 * screen — which is every answer to a real question — was left with its first
 * line at the top and the rest of it below the fold. That is the reader's report
 * that the jump-to-latest button "does not go to the latest".
 *
 * ## One movement, and only ever downwards
 *
 * The animated case used to be `animateScrollToItem(last)` followed by an
 * `animateScrollBy` for the leftover, and that is *two* movements in opposite
 * directions whenever the last item is taller than the viewport: the first brings
 * the item's top into view, which from just above the tail is **upwards**, and the
 * second slides back **down**. The reader's report was exactly that — "it goes up,
 * then down, and does not reach the bottom, and the button stays on screen" — and
 * the button stayed because the second distance had been measured before the first
 * animation's layout had settled.
 *
 * So the distance is measured *once*, from the current position, and the whole
 * move is one `animateScrollBy` over it. The only snap left is the one that brings
 * the end into the viewport at all when it is far off screen: that is a single
 * jump in the direction the reader asked for, and it is what `animateScrollToItem`
 * itself does internally before it animates the rest.
 *
 * [itemCount] is the caller's own row count, needed because `layoutInfo` is empty
 * until the first layout pass — a conversation restored from a session file is
 * scrolled to its end before it has ever been measured. The two are combined rather
 * than one being trusted: the count the caller knows reaches a list that has not
 * been laid out, and `layoutInfo` is the truth once it has.
 */
internal suspend fun LazyListState.scrollToEnd(itemCount: Int, animate: Boolean) {
    val count = maxOf(itemCount, layoutInfo.totalItemsCount)
    if (count <= 0) return

    // Start from the last item, which is all a list that has never been measured
    // can act on: `scrollBy` can only move a list that knows its own bounds, and a
    // conversation restored from a session file is scrolled to its end before it has
    // ever been laid out.
    scrollToItem(count - 1)

    // What "the end" *is*, asked of the list rather than computed from it.
    //
    // This is the bug behind "I tap the button and the button is still there". The
    // distance used to be arithmetic — `last.offset + last.size + afterContentPadding
    // - viewportSize.height` — and it disagreed with the list's own answer by exactly
    // two content paddings. Measured while an answer streamed on the Medium_Phone
    // AVD: `visible=1@-2962+3667 viewport=810 afterPad=105 canFwd=true`, where that
    // formula gives 0 and the list still said it could scroll forward. `scrollBy`
    // returns the delta it actually consumed and clamps at the list's own bounds, so
    // it cannot disagree with `canScrollForward`: one measure pass produces both.
    val remaining = scrollBy(END_PROBE)

    if (animate && remaining > 0f) {
        // Back to where the reader was, then travel the whole distance with one
        // animation. The two calls are in the same frame with no suspension point
        // between them, so nothing is drawn in between — the same reason the snap
        // below can be two calls and still be one visible move. Animating to the last
        // item's *top* instead is what earlier produced an up-then-down motion.
        scrollBy(-remaining)
        animateScrollBy(remaining)
    }

    settle()
}

/**
 * Finishes the move once the content has had a frame to lay itself out.
 *
 * A streamed token changes the text before it changes the layout, so a jump that
 * arrived together with one ends short by that token's height — and once the answer
 * stops there is no next token to correct it. Waiting a frame and re-asking is the
 * whole fix; bounded because a transcript that keeps growing is the follow rule's
 * job, and that runs again on the next token anyway.
 */
private suspend fun LazyListState.settle() {
    var frames = 0
    while (frames < SETTLE_FRAMES) {
        withFrameNanos { }
        if (scrollBy(END_PROBE) <= 0f) return
        frames++
    }
}

/**
 * A scroll large enough to reach the end of any transcript, whatever it holds.
 *
 * `scrollBy` clamps at the list's own bounds, so this doubles as the measurement:
 * what it returns *is* the distance that was left.
 */
private const val END_PROBE = 1_000_000f

/** How many frames [settle] may spend catching up with content that grew under it. */
private const val SETTLE_FRAMES = 3

/** What the fold chip shows about the turn it hides. */
internal data class TurnSummaryData(
    val stepCount: Int,
    /**
     * Null when the turn's duration is not knowable — a conversation restored
     * from disk whose messages carry no timestamps. Null means the row shows the
     * step count alone; showing `1s` there would be an invention.
     */
    val elapsedMs: Long?,
    /**
     * How many of the hidden steps failed outright.
     *
     * A *count*, not a flag. It is still computed — the fold rule is where the
     * question belongs, and it is pinned by `ChatFoldTest` — but nothing draws it
     * any more: a red warning and the words "2 failed" after the step count made
     * a row whose whole job is "there is more behind this" report a second,
     * unrelated fact, and the reader's report was that the fold button should say
     * what it folds and nothing else. A failed step is not hidden by it either:
     * the failure is in the transcript, one tap away, with the tool card's own
     * cross on it.
     */
    val failedSteps: Int,
)

/**
 * One tool call: a single line until it is tapped.
 *
 * ## What this replaced, and why
 *
 * The card used to be a filled `surfaceContainer` panel with a header row *and*
 * two lines of the tool's output always visible under it. A turn that used six
 * tools was therefore six panels of three or four lines each, and the answer —
 * the thing the reader asked for — was pushed a screen and a half down by
 * scaffolding. "Too big and too ugly" is the report that came back, and both
 * halves of it are the same cause: the card was designed as something to read
 * rather than as something to notice.
 *
 * So the closed state is one line and nothing else — the state mark, the tool's
 * name, and as much of its argument as fits before the chevron — with no
 * background of its own.
 *
 * ## The line, in the reference's shape
 *
 * `✓ read · /sdcard/notes.md ⌄` — mark, name, a middot, the argument, chevron —
 * which is the same shape as the reasoning row above it and the turn band above
 * that. Three devices were removed to get here, each of them reported:
 *
 *  - **The spinner.** A running call drew a `CircularProgressIndicator` after its
 *    name, which is a second moving thing on a row whose mark already says what
 *    state it is in, and it made the name's own width depend on the state. The
 *    mark carries it: a filled dot while the call runs, a tick or a cross when it
 *    is done.
 *  - **The step rail.** Consecutive calls used to be inset 12dp and joined by a
 *    2dp bar down their left edge, so that a run read as one turn's steps. The
 *    rail cost the reader 12dp of width on every row of a turn and — because the
 *    inset existed on *all* tool rows, rail or not — moved every tool's mark
 *    12dp right of every other row's text. The rows now start at the transcript's
 *    own margin, like the thinking and the answer they belong to, and the rhythm
 *    between them (`STEP_GAP` in `ChatScreen.kt`) is what groups them.
 *  - **The ripple.** See [disclosureClickable].
 *
 * The state mark is the app's own tick and cross — `Icons.Filled.Check` and
 * `Icons.Filled.Close`, the glyphs the model list marks an active profile and its
 * remove button with — rather than the `✓`/`✕` characters this used to draw. At
 * `labelSmall` a text check is a thin diagonal that reads as a stray punctuation
 * mark beside the bold icons on every other row in the app; the character was the
 * one part of the card that had never matched anything.
 *
 * The collapsed line is exactly one text line tall and the summary is ellipsised
 * rather than wrapped, so a card cannot change height as an argument streams in
 * one character at a time.
 */
@Composable
internal fun ToolCard(item: ChatItem.Tool, text: Strings) {
    // `rememberSaveable`, not `remember`: a `LazyColumn` disposes the items that
    // scroll out of its viewport, and a plain `remember` goes with them — so a card
    // the reader had opened came back closed as soon as it left the screen and came
    // back. Lazy items are wrapped in a `SaveableStateProvider` keyed by the item's
    // own key, so a saveable value is restored with the item. The reasoning toggle
    // in `ChatScreen` had the same bug for the same reason.
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    // `LocalClipboard`, not the deprecated `LocalClipboardManager` — the replacement is
    // suspend, so the copy runs in a scope. See `clipEntryFor`.
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val accent = when (item.state) {
        ToolState.Failed -> MaterialTheme.colorScheme.error
        ToolState.Succeeded -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    val shape = RoundedCornerShape(7.dp)
    val summary = remember(item.argumentsJson) { toolSummary(item) }
    // Counted once per output rather than once per recomposition: a tool that dumps
    // thousands of lines streams `output` a chunk at a time, so an unremembered
    // `count()` scanned the whole result again on every one of those updates — and
    // the scan is the cheap half of the cost this row pays for a `cat` of a large
    // file. The other half is the text layout below, which cannot be avoided while
    // the panel is open and shows the whole output, so the count is not made worse
    // by being exact.
    val lines = remember(item.output) { item.output.lineSequence().count() }

    Column(Modifier.fillMaxWidth()) {
        // Only the header row toggles the card. Making the whole card clickable
        // would be tidier, but a clickable consumes the long-press, which would
        // leave the output impossible to select or copy — the opposite of what a
        // tool result is for.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                // The label is on the control rather than on the arrow inside it: a
                // screen reader reaching this row should hear what the row does.
                .disclosureClickable(
                    onClickLabel = if (expanded) text.chat.collapse else text.chat.expand,
                    onClick = { expanded = !expanded },
                )
                .padding(vertical = ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The mark, then the name, then as much of the argument as fits.
            //
            // The three *states* are drawn inside one fixed square, because the tick
            // and the cross are 14dp icons and a `labelSmall` dot's line box is 16dp
            // tall — without it a running row was a shade taller and a shade wider
            // than a finished one and a turn's rows did not stack. There is no
            // per-tool glyph (a magnifier for a read, a terminal for a shell) and
            // that is the point of the reference this was rebuilt against: the mark
            // answers "did it work", which is the question a reader has about a step
            // they did not open, and the name beside it already says which tool ran.
            Box(
                modifier = Modifier.size(TOOL_MARK_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                when (item.state) {
                    ToolState.Succeeded -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(TOOL_MARK_SIZE),
                    )

                    ToolState.Failed -> Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(TOOL_MARK_SIZE),
                    )

                    ToolState.Pending, ToolState.Running -> Text(
                        "\u25cf",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                item.name.ifBlank { text.chat.toolFallback },
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The one-line preview of what the call was made with: the whole of
            // the closed card's content, and the reason it is the argument rather
            // than the output — `ls -la` says what the call is, and the first two
            // lines of a directory listing do not.
            if (summary.isNotBlank()) {
                // A middot of its own rather than a `·` glued to the front of the
                // summary: the separator is the row's, not the argument's, and the
                // preview is monospace — a middot in a monospace cell sits off the
                // optical centre of the name beside it.
                Text(
                    text = "·",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // `weight(1f, fill = false)` — not `fill = true`: the line takes only
                // what it needs, so the chevron sits at the end of the text the way it
                // does on the reasoning row, and `fill = false` still lets a long path
                // use the whole remainder before it ellipsises. Weighted children are
                // measured last, so the chevron is never pushed off the row.
                Text(
                    summary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 6.dp),
                    // `bodySmall`, not `labelSmall`: the preview is a *command*, and
                    // 11sp monospace on a phone is the size at which a path or a flag
                    // has to be read twice. The same size the expanded panel prints
                    // it at, so opening the card does not resize the text the reader
                    // was already looking at.
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = TOOL_LINE_HEIGHT,
                    ),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            DisclosureChevron(
                expanded = expanded,
                modifier = Modifier.padding(start = TOOL_CHEVRON_GAP),
            )
        }

        if (expanded) {
            // A gap between the row and what it opened. The row's own padding put the
            // chevron's underline almost on the panel's first line, so the panel
            // read as part of the header rather than as the thing it revealed.
            Spacer(Modifier.height(TOOL_PANEL_GAP))

            // The panel is the only filled surface a tool call has, so it says "this
            // is the thing you opened" without decorating every closed row in the
            // turn. It starts where the row's own text starts — there is no inset any
            // more, because no row has one.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)) {
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (item.output.isNotBlank()) {
                        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text.chat.outputLines(lines),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { scope.launch { clipboard.setClipEntry(clipEntryFor(item.output)) } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = text.chat.copyOutput,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        // Capped and scrollable: a tool that returns thousands
                        // of lines must not push the rest of the conversation
                        // off the screen. 240dp is about a third of the viewport —
                        // the same reasoning as the reasoning panel's cap — and it
                        // is the panel's *own* scroll, so the transcript underneath
                        // keeps the gesture once this one is at its end.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = TOOL_OUTPUT_MAX_HEIGHT)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                item.output,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = TOOL_LINE_HEIGHT,
                                ),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The status mark: a step up from the 11sp text glyph it replaced. */
private val TOOL_MARK_SIZE = 14.dp

/** Between the preview and the chevron: the two were reading as one word. */
private val TOOL_CHEVRON_GAP = 4.dp

/**
 * The vertical padding every one-line row on the transcript carries.
 *
 * 3dp top and bottom around a `labelMedium` line box of about 16dp, which puts a
 * row at 22dp: enough that two rows in a row do not read as one wrapped paragraph,
 * and short enough that a turn of six tool calls is still a glance rather than a
 * screen. It is *inside* the tap target, so the whole of that 22dp responds to a
 * press rather than only the glyphs.
 */
internal val ROW_PADDING = 3.dp

/**
 * A tool card's line height, for its preview and for its output.
 *
 * `bodySmall`'s own 16sp is tight for monospace, whose glyphs are all one width and
 * therefore have no natural word rhythm to read along; 18sp is the same figure the
 * reasoning block uses, and it is what makes a directory listing legible.
 */
private val TOOL_LINE_HEIGHT = 18.sp

/** How much of a tool's output is on screen before the panel scrolls itself. */
private val TOOL_OUTPUT_MAX_HEIGHT = 240.dp

/**
 * Between a tool card's header row and the panel it opens.
 *
 * The header carries its own padding, and that left the chevron's underline
 * touching the panel's first line — so the panel read as the header's last line
 * rather than as what the tap had revealed.
 */
private val TOOL_PANEL_GAP = 4.dp

/**
 * A disclosure row's tap target, without Material's ripple.
 *
 * The ripple is the one piece of Material feedback that does not survive on this
 * page. `clickable`'s default indication is a *bounded* ripple, so on a row that
 * spans the transcript it fills the whole width — a bar of colour flashing across
 * the conversation on every tap, which is what the reader reported as "点击反馈的条
 * 块", a block of feedback under the finger rather than a highlight on the control.
 * None of the reference implementations this page was rebuilt against has it: what
 * a disclosure row shows when it is tapped is the thing it opened.
 *
 * The interaction source is remembered rather than defaulted, because `clickable`
 * without one creates a source per recomposition — harmless for a ripple that is
 * never drawn, but it is also what `indication = null` needs to be honest about:
 * this control reports no interaction, so nothing can be listening.
 */
@Composable
internal fun Modifier.disclosureClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    onClickLabel = onClickLabel,
    onClick = onClick,
)

/**
 * The one chevron this page's three disclosures use.
 *
 * One glyph that turns, rather than two glyphs that swap, and one place that says
 * how big it is. There are three disclosure controls on the transcript — the turn's
 * fold band, a tool card and a message's reasoning — and they used to differ in
 * every way that is invisible until they are on screen together: 15dp against 16dp,
 * a swap against a rotate, and a `contentDescription` on the glyph in one place and
 * on the control in another. A reader does not need to be told these are the same
 * gesture; they should not have to work it out either.
 *
 * The label belongs to the *control*, not to the arrow: a screen reader reaching a
 * tappable row should hear what the row does, and an arrow inside it is decoration.
 * Each caller passes its own through `onClickLabel`.
 */
@Composable
internal fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = CHEVRON_TURN_MS),
        label = "disclosure-chevron",
    )
    Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = null,
        modifier = modifier
            .size(CHEVRON_SIZE)
            .rotate(rotation),
        tint = tint,
    )
}

/** One size for every disclosure arrow on the page. */
private val CHEVRON_SIZE = 16.dp

/** Long enough to read as a turn rather than a flicker. */
private const val CHEVRON_TURN_MS = 160

/**
 * The row that opens a message's reasoning.
 *
 * ## What it says
 *
 * One line — the brain, `Thinking · 1.2k chars`, a chevron — in the same shape as
 * the tool rows under it and the turn band above them, so the three read as one
 * kind of thing: a row that opens. The first draft of this row said
 * `Show reasoning` with the count pushed to the far right, and both halves of that
 * were wrong for the same reason: the label was a *verb* where the reader wanted a
 * *fact*, and the one number they wanted was a screen's width away from the word it
 * belonged to. The reference implementations put the count in the label
 * (`Thought for 1.2s`); PiKit's own turn band says `Worked 47s · 5 steps`, so the
 * count is what goes after the middot and the verb moves to the control's
 * `onClickLabel`, where a screen reader looks for it.
 *
 * The count updates while the reasoning streams. It was hidden during streaming
 * before, on the theory that a number changing on every token is noise — it is not:
 * it is the only thing on the row that moves, and it is what tells the reader the
 * block they are waiting for is filling up.
 *
 * ## Why it is still distinct from the turn band
 *
 * The two disclosures mean different things and sit a few lines apart, so they are
 * separated by position (the band sits at the turn's boundary, this sits inside a
 * message), by the mark in front of the label (the band has none, this has the
 * brain) and by the chevron's tint (the band's is `primary`). They are the *same*
 * shape otherwise, deliberately: the reader should not have to work out that two
 * rows that open are the same gesture.
 */
@Composable
internal fun ReasoningToggle(
    expanded: Boolean,
    chars: Int,
    text: Strings,
    onToggle: () -> Unit,
) {
    // 180 degrees rather than a second glyph: one icon that turns is a smaller
    // visual change per frame than swapping two vectors, and it reads as the same
    // control changing state. `DisclosureChevron` is where that is decided, for all
    // three of the page's disclosures.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .disclosureClickable(
                onClickLabel = if (expanded) text.chat.hideReasoning else text.chat.showReasoning,
                onClick = onToggle,
            )
            .padding(vertical = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PiIcons.Thinking,
            contentDescription = null,
            modifier = Modifier.size(TOOL_MARK_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text.chat.reasoningLabel(chars),
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Immediately after the label, not at the far edge of the row: the reference
        // this page was rebuilt against puts the arrow where the words end, and a
        // chevron a screen's width from the text it belongs to reads as a second,
        // unrelated control. It is why the weights here are `fill = false` — nothing
        // is pushed to the right any more.
        DisclosureChevron(
            expanded = expanded,
            modifier = Modifier.padding(start = TOOL_CHEVRON_GAP),
        )
    }
}

/**
 * The reasoning itself.
 *
 * ## A quiet panel, not a card with a coloured border
 *
 * Every comparable transcript distinguishes reasoning by *muted colour*, and the
 * projects that draw a container draw a filled one in the surface's own second tone,
 * at the same 12dp radius as their code blocks. The left accent rule this used to
 * have was doing the job twice: it said "secondary" in a colour and "quoted" in a
 * shape, and it cost an `IntrinsicSize.Min` row — an extra intrinsic measure of a
 * block of text that grows on every streamed token, on every frame.
 *
 * The other half of that convention — italic type, Anthropic's own "gray italic
 * text" — was removed outright. It is not a taste call: the reasoning is mostly Han
 * in this app's own languages, and a CJK face has no italic, so the platform
 * synthesises one by slanting the glyphs, which is exactly what makes a 14sp Han
 * line harder to read rather than easier. Latin monospace and paths inside the
 * reasoning were slanted the same way. Muted colour on its own is the whole signal,
 * and the panel's fill already separates the block from the answer under it.
 *
 * ## Why it is capped
 *
 * Reasoning arrives unbounded, several thousand characters of it, and an uncapped
 * block pushed the answer — the thing that was asked for — off the bottom of the
 * screen with no way to know it was there. 240dp is about a third of the transcript
 * viewport: enough to read the shape of the thinking, short enough that the answer is
 * always on screen under it, and scrollable in place when it is not.
 */
@Composable
internal fun ReasoningBlock(thinking: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = thinking,
            modifier = Modifier
                .heightIn(max = REASONING_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                lineHeight = REASONING_LINE_HEIGHT,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How much of the reasoning is on screen before it scrolls on its own. */
private val REASONING_MAX_HEIGHT = 240.dp

/** Slightly looser than `bodySmall`'s 16sp: a block of dense reasoning needs the air. */
private val REASONING_LINE_HEIGHT = 18.sp

/**
 * The fold row's label, as a pure function of what the turn knows about itself.
 *
 * Three cases, and each is a fact the transcript either has or does not:
 *
 *  - a duration and something hidden — `工作 47 秒 · 5 个步骤`;
 *  - a duration and nothing hidden — `工作 47 秒`;
 *  - no duration (a session restored from messages that carry no timestamps) but
 *    something hidden — `5 个步骤`;
 *  - neither — nothing. `0 个步骤` is what the first version printed, and a turn that
 *    is silent about both should be silent rather than report a zero nobody asked
 *    about.
 */
internal fun turnSummaryLabel(summary: TurnSummaryData, text: Strings): String {
    val duration = summary.elapsedMs?.let { text.chat.duration(durationSeconds(it)) }
    val hidden = summary.stepCount.takeIf { it > 0 }
    return when {
        duration != null -> text.chat.turnWorked(duration, hidden)
        hidden != null -> text.chat.stepsSummary(hidden)
        else -> ""
    }
}

/**
 * One notice from an out-of-band action, e.g. a `!command` or a retry.
 *
 * A left rule, like the terminal it came from, and drawn with `drawBehind` rather
 * than as a sibling `Box` in an `IntrinsicSize.Min` row: the rule spans the whole
 * block, and asking a height-constrained row for its intrinsic height to get that is
 * a measurement the text has already told us the answer to. This is the one element
 * on the page whose rail means "this is program output", so it stays a rail —
 * the *process* rail on a turn's steps is drawn by the row wrapper and is a
 * different statement.
 */
@Composable
internal fun NoticeCard(item: ChatItem.Notice) {
    val color = when (item.kind) {
        NoticeKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeKind.Warning -> MaterialTheme.colorScheme.primary
        NoticeKind.Error -> MaterialTheme.colorScheme.error
    }
    Text(
        item.text,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    color = color.copy(alpha = 0.45f),
                    topLeft = Offset.Zero,
                    size = Size(RAIL_WIDTH.toPx(), size.height),
                )
            }
            .padding(start = NOTICE_TEXT_INSET),
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = TOOL_LINE_HEIGHT),
        fontFamily = FontFamily.Monospace,
    )
}

/** How far the rail is inset before the notice's own text starts. */
private val NOTICE_TEXT_INSET = 12.dp

/** The width of the notice's own rail: the one mark on the page that means "program output". */
internal val RAIL_WIDTH = 2.dp

/**
 * A turn's duration in whole seconds, never below one.
 *
 * Never milliseconds: a turn is a model round trip, so anything below a second is
 * noise and showing `0.4s` invites reading meaning into it. Rounding up to one is
 * the honest reading of the same fact — the turn *did* take a moment — and it keeps
 * `工作 0 秒` off the screen.
 *
 * The units are the interface's ([Strings.Chat.duration]); this is only the
 * arithmetic, so it stays here where it can be tested without a language.
 */
internal fun durationSeconds(millis: Long): Long = (millis / 1000).coerceAtLeast(1)

/**
 * The fold row: how long a turn ran and how many steps it hides.
 *
 * ## What it says
 *
 * `工作 47 秒 · 5 个步骤` — the duration and the count, in one bare line, with a
 * chevron at the end of the words. It used to be a filled chip as wide as its own
 * text, with the time in `labelMedium` and the count in the same size after a
 * separator, and the report was two-fold: the word "已" in front of the time read as
 * a second statement about a turn the reader had just watched finish, and the count
 * was drawn only when there was something hidden — which is every finished turn, so
 * the row that was supposed to *be* the count did not have one on it.
 *
 * The chip is gone with them. Three rows on this page open something — this, a
 * message's reasoning, a tool call — and they are now one shape at one margin; a
 * filled band across a transcript of bare rows was the only thing making the turn
 * boundary look like a different *kind* of row rather than the first row of a turn.
 * Position and the accent the label and its chevron share are what separate it from
 * the reasoning row directly beneath it.
 *
 * ## When it is not a control
 *
 * A turn that hides nothing — one that ended without a reply — keeps its time and is
 * not tappable, rather than offering a control that opens nothing. A turn restored
 * from a session file whose messages carry no timestamps has no duration either and
 * shows the step count alone: `0 秒` there would be an invention. A turn that has
 * neither — restored *and* hiding nothing — says nothing at all, which is
 * [turnSummaryLabel]'s last case: an empty label is an empty 6dp line, not the words
 * `0 个步骤`.
 */
@Composable
internal fun TurnSummaryRow(
    summary: TurnSummaryData,
    expanded: Boolean,
    text: Strings,
    onToggle: () -> Unit,
) {
    val foldable = summary.stepCount > 0
    val label = turnSummaryLabel(summary, text)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .disclosureClickable(
                enabled = foldable,
                onClickLabel = if (expanded) text.chat.stepsHide else text.chat.stepsShow,
                onClick = onToggle,
            )
            .padding(vertical = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            // One style for both cases, and that is a correction rather than a
            // simplification. The two differ only in whether there is something
            // behind the row, and the style used to change with it — `labelMedium`
            // + Medium when the row was a control, `labelSmall` + Normal when it
            // was not — so the same sentence about the same turn (`工作 47 秒`) was
            // drawn at two sizes in one conversation, which is exactly the report:
            // "有步骤时和没步骤时上面的工作X秒字体大小样式不一样". A turn with no
            // hidden rows is not a lesser fact about the turn; it is the same line
            // with nothing behind it, and the chevron below is what says which of
            // the two it is.
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            // The accent, and the chevron beside it is the same colour. Both used to be
            // `onSurfaceVariant`, with the arrow `primary` as "the one coloured mark among the
            // page's three disclosures" — and the reader's report is that this line, a message's
            // timestamp and its copy button are the furniture of a finished turn and should read
            // as one accent: "把工作几秒、几个步骤、消息时间、消息复制按钮等都变成主题蓝色".
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (foldable) {
            DisclosureChevron(
                expanded = expanded,
                modifier = Modifier.padding(start = TOOL_CHEVRON_GAP),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}


// ------------------------------------------------------------------ arguments

/**
 * The interesting argument of a tool call, as a single line.
 *
 * The arguments arrive a delta at a time and are therefore often truncated
 * mid-token, so this reads the raw text instead of insisting on valid JSON: a
 * half-received `{"command":"ls` still shows `ls`. Unknown tools fall back to
 * the compacted JSON rather than showing nothing.
 */
internal fun toolSummary(item: ChatItem.Tool): String {
    val raw = item.argumentsJson.trim()
    if (raw.isEmpty()) return ""

    TOOL_ARG_REGEXES.forEach { regex ->
        val match = regex.find(raw) ?: return@forEach
        val value = unescapeJson(match.groupValues[1]).replace('\n', ' ').trim()
        if (value.isNotEmpty()) return value
    }
    // Still streaming, so the string literal has no closing quote yet.
    TOOL_ARG_PREFIX_REGEXES.forEach { regex ->
        val match = regex.find(raw) ?: return@forEach
        val value = unescapeJson(match.groupValues[1]).replace('\n', ' ').trim()
        if (value.isNotEmpty()) return value
    }
    return raw.removeSurrounding("{", "}").replace('\n', ' ').trim()
}

/** Argument keys in the order they are worth showing. */
private val TOOL_ARG_KEYS = listOf(
    "command", "cmd", "filePath", "file_path", "path", "pattern", "query",
    "url", "prompt", "message", "oldText",
)

private val TOOL_ARG_REGEXES = TOOL_ARG_KEYS.map { key ->
    Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
}

private val TOOL_ARG_PREFIX_REGEXES = TOOL_ARG_KEYS.map { key ->
    Regex("\"$key\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)$")
}

private fun unescapeJson(value: String): String {
    if (!value.contains('\\')) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index + 1 >= value.length) {
            out.append(char)
            index++
            continue
        }
        when (val escape = value[index + 1]) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> out.append('\r')
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            else -> out.append(escape)
        }
        index += 2
    }
    return out.toString()
}
