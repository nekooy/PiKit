package pi.kit.mob.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.R
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.NoticeKind
import pi.kit.mob.pi.ToolState
import pi.kit.mob.ui.clipEntryFor
import pi.kit.mob.ui.components.CopyButton
import pi.kit.mob.ui.kit.PiTone
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
     * than a `computedStateOf` on the position: **following stops the moment the
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
 * on every streamed token, and a `computedStateOf` reading the same values would
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
     * the failure is in the transcript, one tap away, with the failed step's red
     * family word on it.
     */
    val failedSteps: Int,
)

/**
 * One tool call: a step of the turn's work, drawn as a line of text rather than a pill.
 *
 * ## The shape, and what it replaced
 *
 * A call used to wear a filled [ChatStatusPill] — the tool's raw name in a capsule whose
 * fill was the state: a live container while it ran, a grey one when it succeeded, a red
 * one when it failed. Six steps in one turn therefore drew six filled capsules of
 * different widths and colours, and the loudest object on each row was the tool's *name*
 * rather than the argument that says what the call is doing. On a page whose transcript
 * is otherwise bare text, those capsules were the only stack of coloured boxes in the
 * conversation.
 *
 * ZCode's agent transcript draws a step as one quiet line, and that is the shape adopted
 * here: the family's own word, the argument, and only the state that is worth saying — a
 * live dot while the call is out, the family word in red when it failed, **nothing** when
 * it succeeded. One text size, one line height and no fill, so a run of six steps reads
 * as six lines of one list rather than six controls. The ink still changes with the state
 * ([ToolFamily.ink]), which keeps the one thing the pill did well — "is this still
 * running?" is answerable without reading — while dropping the container that made it
 * loud.
 *
 * ## The hierarchy
 *
 * The family word (`终端`, `读取`, `编辑`) is the quietest part, in
 * `onSurfaceVariantSummary`; the argument is the line's subject. A file name is drawn in
 * `onSurface` because it is what the reader is looking for, while a shell command keeps
 * the summary grey and monospace — a command is technical detail, not a title. ZCode
 * makes the same distinction between folder/file text and a command summary.
 *
 * The raw tool name is pi's spelling (`bash`, `web_fetch`), not a word the interface
 * should make anyone learn, so [toolFamilyOf] turns it into a category and
 * [toolKindLabel] translates that category. An unknown tool keeps its own name rather
 * than being filed under a guess.
 *
 * ## Where the mark went
 *
 * A turn's steps are drawn inside the answer card's `StepGroup`, whose one-pixel rule
 * frames the rows. A step itself is words only: the rule already says "this is one run
 * of work", and a column of sixteen icons beside them was what the old pills were.
 *
 * The chevron stays at the end of the line, after the argument, and is only present
 * once the call has settled — a running call has nothing to open. It is 16dp and takes
 * its colour from the same summary grey, so the row's one control does not become a
 * second object.
 */
internal enum class ToolFamily { Shell, Read, Edit, Search, Web, Agent, Todo, Other }

/**
 * The family a pi tool belongs to, from its wire name.
 *
 * pi names its tools after what they do (`bash`, `read`, `edit`, `grep`, `web_fetch`),
 * and a provider can add its own spelling. The match is deliberately a substring one:
 * `TodoWrite` must be a todo before `write` makes it an edit, and `web_search` is a web
 * call before `search` claims it. The order of the branches is the rule.
 */
internal fun toolFamilyOf(name: String): ToolFamily {
    val normalized = name.lowercase()
    return when {
        normalized.contains("bash") || normalized.contains("shell") ||
            normalized.contains("exec") || normalized.contains("terminal") -> ToolFamily.Shell

        normalized.contains("todo") -> ToolFamily.Todo
        normalized.contains("fetch") || normalized.contains("web") -> ToolFamily.Web
        normalized.contains("agent") || normalized.contains("subagent") ||
            normalized == "task" || normalized.startsWith("task") -> ToolFamily.Agent

        normalized.contains("grep") || normalized.contains("glob") ||
            normalized.contains("search") -> ToolFamily.Search

        normalized.contains("edit") || normalized.contains("write") ||
            normalized.contains("patch") -> ToolFamily.Edit

        normalized.contains("read") || normalized == "ls" || normalized == "find" ||
            normalized.contains("view") -> ToolFamily.Read

        else -> ToolFamily.Other
    }
}

/** The family's word in the interface's language, or the tool's own name when unknown. */
internal fun toolKindLabel(family: ToolFamily, name: String, text: Strings): String =
    when (family) {
        ToolFamily.Shell -> text.chat.toolShell
        ToolFamily.Read -> text.chat.toolRead
        ToolFamily.Edit -> text.chat.toolEdit
        ToolFamily.Search -> text.chat.toolSearch
        ToolFamily.Web -> text.chat.toolWeb
        ToolFamily.Agent -> text.chat.toolAgent
        ToolFamily.Todo -> text.chat.toolTodo
        ToolFamily.Other -> name.ifBlank { text.chat.toolFallback }
    }

/** The ink a step wears: live while it runs, red when it failed, quiet otherwise. */
@Composable
private fun ToolFamily.ink(state: ToolState): Color = when (state) {
    ToolState.Failed -> PiTone.error()
    ToolState.Pending, ToolState.Running -> PiTone.live()
    ToolState.Succeeded -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
internal fun ToolCallRow(item: ChatItem.Tool, text: Strings) {
    // `rememberSaveable`, not `remember`: a `LazyColumn` disposes the items that
    // scroll out of its viewport, and a plain `remember` goes with them — so a card
    // the reader had opened came back closed as soon as it left the screen and came
    // back. Lazy items are wrapped in a `SaveableStateProvider` keyed by the item's
    // own key, so a saveable value is restored with the item.
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    // `LocalClipboard`, not the deprecated `LocalClipboardManager` — the replacement is
    // suspend, so the copy runs in a scope. See `clipEntryFor`.
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val running = item.state == ToolState.Pending || item.state == ToolState.Running
    val family = remember(item.name) { toolFamilyOf(item.name) }
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
        // Only the header row toggles the card. Making the whole card clickable would
        // be tidier, but a clickable consumes the long-press, which would leave the
        // output impossible to select or copy — the opposite of what a tool result is
        // for. A running call is not a control at all: there is nothing to open yet.
        DisclosureRow(
            enabled = !running,
            // The label is on the control rather than on the arrow inside it: a screen
            // reader reaching this row should hear what the row does.
            onClickLabel = if (expanded) text.chat.collapse else text.chat.expand,
            onClick = { expanded = !expanded },
        ) {
            Text(
                text = toolKindLabel(family, item.name, text),
                style = MiuixTheme.textStyles.footnote1,
                color = family.ink(item.state),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The one-line preview of what the call was made with: the whole of the
            // closed row's content, and the reason it is the argument rather than the
            // output — `ls -la` says what the call is, and the first two lines of a
            // directory listing do not. It starts after the family word, not in a pill.
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = STEP_TEXT_GAP),
                    // `footnote1`, not `footnote2`: the preview is a *command* or a path,
                    // and 11sp monospace on a phone is the size at which a path or a flag
                    // has to be read twice. The same size the expanded panel prints it
                    // at, so opening the card does not resize the text the reader was
                    // already looking at.
                    style = if (family == ToolFamily.Shell) {
                        MiuixTheme.textStyles.footnote1.copy(lineHeight = MONO_LINE_HEIGHT)
                    } else {
                        MiuixTheme.textStyles.footnote1
                    },
                    fontFamily = if (family == ToolFamily.Shell) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    // File names are the one argument that is the line's subject;
                    // a command is technical detail and stays in the summary grey.
                    color = if (family == ToolFamily.Read || family == ToolFamily.Edit) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Running is the only state worth a mark. A success tick on every row
            // would make a finished turn a column of ticks, and a cross beside a
            // failed row repeats what its red family word already says — the ink is
            // the state, and a settled step is the quiet default.
            if (running) {
                Box(
                    Modifier
                        .padding(start = STEP_STATUS_GAP)
                        .size(LIVE_DOT_SIZE)
                        .background(PiTone.live(), CircleShape),
                )
            }
            if (!running) {
                DisclosureChevron(
                    expanded = expanded,
                    modifier = Modifier.padding(start = TOOL_CHEVRON_GAP),
                )
            }
        }

        if (expanded && !running) {
            // A gap between the row and what it opened. The row's own padding put the
            // chevron's underline almost on the panel's first line, so the panel
            // read as part of the header rather than as the thing it revealed.
            Spacer(Modifier.height(TOOL_PANEL_GAP))

            // The panel is the only filled surface a tool call has, so it says "this
            // is the thing you opened" without decorating every closed row in the
            // turn.
            //
            // `PiTone.inset()` rather than the palette's high surface step, which is
            // what this was: in the dark scheme `surfaceContainerHigh` *is*
            // `surfaceContainer` (#242424 both), so the panel had no fill of its own
            // there — the output of a tool call ran straight into the card behind it
            // and the only boundary left was the divider. The wash is a step in both
            // schemes (#E6E6E6 on the white card, #3D3D3D on the dark one) because it
            // is a contrast rather than a colour; see `PiTone.inset`'s KDoc.
            Surface(
                color = PiTone.inset(),
                shape = ROW_SHAPE,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MiuixTheme.textStyles.footnote1.copy(
                                lineHeight = MONO_LINE_HEIGHT,
                            ),
                            fontFamily = FontFamily.Monospace,
                            // Normal ink: this is the command that ran, which is the
                            // one line of a tool call the reader came to read. The
                            // #A2A2A2 the panel used to use put it *below* the 3:1
                            // floor on the panel's own fill.
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    if (item.output.isNotBlank()) {
                        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = text.chat.outputLines(lines),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                            )
                            // The same copy control the meta row and a code block
                            // use: one glyph set, one weight, one confirmation.
                            CopyButton(
                                onClick = { scope.launch { clipboard.setClipEntry(clipEntryFor(item.output)) } },
                                contentDescription = text.chat.copyOutput,
                            )
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
                                text = item.output,
                                style = MiuixTheme.textStyles.footnote1.copy(
                                    lineHeight = MONO_LINE_HEIGHT,
                                ),
                                fontFamily = FontFamily.Monospace,
                                // The tool's output is the answer the call produced,
                                // not a caption on it: normal ink, for the reason the
                                // summary above states.
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A standalone reasoning row's mark, and its gap to the label. */
internal val STEP_MARK_SIZE = 16.dp
internal val STEP_MARK_GAP = 8.dp

/** Between a step's family word and its argument: one word-space at the reading size. */
private val STEP_TEXT_GAP = 8.dp

/** Between an argument and the state mark that ends it. */
private val STEP_STATUS_GAP = 8.dp

/** The status pill's mark cell: 14dp in every state, so a dot does not narrow the pill. */
private val PILL_MARK_SIZE = 14.dp

/** Between the preview and the chevron: the two were reading as one word. */
private val TOOL_CHEVRON_GAP = 4.dp

/**
 * The air a disclosure's press highlight leaves above and below its label.
 *
 * It was 3dp, and it was the row's padding rather than the highlight's: the tint then
 * spanned the whole card's width, and its rounded ends cut into the first glyph of a
 * short label (`2 个步骤`) while the row's top and bottom carried a line of text's own
 * leading. 6dp is what makes the highlight read as a control.
 *
 * It is also why the tool row is not a `PiRow`: that is 14dp of padding and 52dp
 * tall, because a settings row is read and tapped one at a time, while the point
 * of this row is that six of them are one glance.
 */
private val DISCLOSURE_PAD = 6.dp

/**
 * How far a disclosure's highlight is allowed to bleed past its label.
 *
 * The highlight is drawn around the control rather than across the row, so its
 * horizontal padding has to come from somewhere. It comes from the card's own inset,
 * which is 16dp: the row moves its draw left by this much and pads the label by the
 * same, so the label keeps the transcript's margin and the highlight gains 10dp of air
 * on both sides without the card growing.
 */
private val DISCLOSURE_BLEED = 10.dp

/**
 * The line box every monospace block in the app is drawn at.
 *
 * the 13sp footnote style's own leading is tight for monospace, whose glyphs are one width and
 * therefore have no natural word rhythm to read along; 18sp is what makes a
 * directory listing legible. One number for a tool's preview, a tool's output, the
 * reasoning block and a fenced code block in a reply — the three used to hold their
 * own copy of it, and a code block that disagreed with the terminal beside it by
 * 2sp is exactly the kind of drift a reader feels without being able to name.
 */
internal val MONO_LINE_HEIGHT = 18.sp

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
 * One of the page's three disclosures: a row that is the tap target, and a *control*
 * that carries the feedback.
 *
 * ## Why the highlight is not the row
 *
 * A row and a control are not the same size here. The row spans the card so the whole
 * line answers a tap, while the label may be four characters wide — and the theme's
 * indication draws a 10% tint over whichever node was pressed. Painted on the row,
 * that tint was a band across the card with a short label jammed into its rounded
 * leading end and a screenful of empty highlight after the chevron.
 *
 * So the two are separated: the row owns the tap, `interactionSource` is shared, and
 * the tint is bound to the inner node that hugs the label. The *same* indication the
 * rest of the app shows (`LocalIndication`) is used rather than a hand-rolled colour,
 * which is what keeps this row's feedback the same gesture as a settings row's.
 *
 * The inner node is then allowed to bleed [DISCLOSURE_BLEED] past the row's own start,
 * which is what its horizontal padding costs — see that constant for the accounting.
 *
 * A ripple on a row that spans the transcript was the earlier problem, and it is
 * recorded here because its successor looks similar and is not: that one was a bar of
 * colour flashing across the conversation on every tap ("点击反馈的条块"), a block of
 * feedback under the finger rather than a highlight on the control.
 *
 * The label belongs to the *control* on all three disclosures, and one place that says
 * so is what stops a fourth caller from putting it on the arrow.
 */
@Composable
internal fun DisclosureRow(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClickLabel = onClickLabel,
                // The feedback is the inner node's; see the KDoc.
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .offset(x = -DISCLOSURE_BLEED)
                .clip(ROW_SHAPE)
                .indication(interactions, LocalIndication.current)
                .padding(horizontal = DISCLOSURE_BLEED, vertical = DISCLOSURE_PAD),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

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
 * The glyph is Lucide's `chevron-down` (`ic_chevron_down.xml`) rather than the
 * library's `ExpandMore`, and the difference is what the row *looks* like: the
 * library's is a filled caret, which at 16dp beside a 13sp label reads as a heavier
 * object than the label it belongs to, and a filled triangle is the figure a spinner
 * or a play control uses. The stroke is 2 of 24 — the weight of `ic_check.xml`, which
 * this page already draws beside text.
 *
 * The label belongs to the *control*, not to the arrow: a screen reader reaching a
 * tappable row should hear what the row does, and an arrow inside it is decoration.
 * Each caller passes its own through `onClickLabel`.
 *
 * ## Why the turn is a 160 ms tween
 *
 * It was the design language's fast spatial spring, and Miuix publishes no motion
 * tokens to inherit — the chevron is the one thing on this page that moves on a tap,
 * and a rotation this small is over before a spring's settle is readable. A short
 * tween is the whole of the motion, named here rather than at three call sites.
 */
@Composable
internal fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = CHEVRON_TURN_MILLIS),
        label = "disclosure-chevron",
    )
    Icon(
        painter = painterResource(R.drawable.ic_chevron_down),
        contentDescription = null,
        modifier = modifier
            .size(CHEVRON_SIZE)
            .rotate(rotation),
        tint = tint,
    )
}

/** One size for every disclosure arrow on the page. */
private val CHEVRON_SIZE = 16.dp

/** How long the chevron takes to turn over. Short: it is a state, not a journey. */
private const val CHEVRON_TURN_MILLIS = 160

/**
 * The row that opens a message's reasoning.
 *
 * ## What it says
 *
 * One line — the brain, `思考 · 1.2k 字`, a chevron — in the same shape as the tool
 * rows under it and the turn band above them, so the three read as one kind of thing:
 * a row that opens. The first draft of this row said `Show reasoning` with the count
 * pushed to the far right, and both halves of that were wrong for the same reason: the
 * label was a *verb* where the reader wanted a *fact*, and the one number they wanted
 * was a screen's width away from the word it belonged to. The reference
 * implementations put the count in the label (`Thought for 1.2s`); PiKit's own turn
 * band says `工作 47 秒 · 5 个步骤`, so the count is what goes in the label and the
 * verb moves to the control's `onClickLabel`, where a screen reader looks for it.
 *
 * ## Why the mark is muted now
 *
 * The brain used to be the accent's blue, on the argument that "thinking" is a mode
 * the composer's chip also wears in primary. In ZCode's transcript the thought row is
 * the quietest row of the turn, not an accent: the label and the brain sit in the
 * summary grey, and the reader's eye goes to the reasoning text only when they open
 * it. The blue was making the control louder than the answer beside it, which is the
 * same mistake the old tool pills made.
 *
 * A nested row (inside the answer card's `StepGroup`) draws no brain: the group's
 * left rule already marks it as one of the turn's steps, and the label still says
 * `思考`.
 */
@Composable
internal fun ReasoningToggle(
    expanded: Boolean,
    chars: Int,
    text: Strings,
    onToggle: () -> Unit,
    nested: Boolean = false,
) {
    // 180 degrees rather than a second glyph: one icon that turns is a smaller
    // visual change per frame than swapping two vectors, and it reads as the same
    // control changing state. `DisclosureChevron` is where that is decided, for all
    // three of the page's disclosures.
    DisclosureRow(
        onClickLabel = if (expanded) text.chat.hideReasoning else text.chat.showReasoning,
        onClick = onToggle,
    ) {
        if (!nested) {
            Icon(
                painter = painterResource(R.drawable.ic_thinking),
                contentDescription = null,
                modifier = Modifier.size(STEP_MARK_SIZE),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(STEP_MARK_GAP))
        }
        Text(
            text = text.chat.reasoningLabel(chars),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
 *
 * ## Why the fill is the quotation wash
 *
 * The block is drawn inside the answer's card, and its fill is `PiTone.inset()` — the
 * same step the tool output panel and every code block take. It used to be
 * `surfaceContainerHigh`, "one step past the card's `surfaceContainer`", which is a
 * step in the light scheme and *no* step at all in the dark one, where the palette
 * gives both roles #242424; there the reasoning floated with no panel around it.
 * Same shape as the tool output panel beside it, [ROW_SHAPE], because the two are the
 * same thing: an information-dense inset inside a turn.
 */
@Composable
internal fun ReasoningBlock(
    thinking: String,
    /** Whether the block should follow its own newest line while the model is thinking. */
    follow: Boolean = false,
) {
    val scroll = rememberScrollState()
    // A streamed block grows below the viewport. `maxValue` is read in a snapshot
    // flow rather than in composition: it changes after every layout pass, and the
    // only work needed is a scroll. A reader who has taken the scroll with a finger
    // keeps their place — the same division the transcript's own follow rule makes —
    // so a drag in progress is let through and the next completed layout resumes.
    LaunchedEffect(follow, scroll) {
        if (!follow) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.collect { max ->
            if (max > 0 && !scroll.isScrollInProgress) scroll.scrollTo(max)
        }
    }
    Surface(
        color = PiTone.inset(),
        shape = ROW_SHAPE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = thinking,
            modifier = Modifier
                .heightIn(max = REASONING_MAX_HEIGHT)
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MiuixTheme.textStyles.footnote1.copy(
                lineHeight = MONO_LINE_HEIGHT,
            ),
            // The reasoning's own body, in normal ink: it is the model's thinking, which
            // is content, and the panel it sits in is already the quietest surface on the
            // page. The summary grey it used to use made it a caption on itself.
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** How much of the reasoning is on screen before it scrolls on its own. */
private val REASONING_MAX_HEIGHT = 240.dp

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
 * the *process* rail on a turn's steps is drawn by the answer card's `StepGroup`
 * and is a different statement.
 */
@Composable
internal fun NoticeCard(item: ChatItem.Notice) {
    // [ChatTone]'s middle three, and the *ink* rather than a container: a notice is
    // a remark about the conversation rather than a control, so it wears its colour
    // as text and rule with no fill of its own. A warning is the app's own accent and
    // not the live tone — live means "still running", and nothing in a notice is.
    val tone = when (item.kind) {
        NoticeKind.Info -> ChatTone.Neutral
        NoticeKind.Warning -> ChatTone.Accent
        NoticeKind.Error -> ChatTone.Danger
    }
    val color = tone.ink()
    Text(
        text = item.text,
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
        style = MiuixTheme.textStyles.footnote1.copy(lineHeight = MONO_LINE_HEIGHT),
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
 * text, with the time in the reading label size and the count in the same size after
 * a separator, and the report was two-fold: the word "已" in front of the time read as
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
 * [turnSummaryLabel]'s last case: the row is not drawn, rather than printing the
 * words `0 个步骤`.
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
    // A restored turn with no timestamps and nothing hidden has no fact to state,
    // and a blank line is not a row — the "empty is not a zero" rule, made visible.
    if (label.isEmpty()) return

    DisclosureRow(
        enabled = foldable,
        onClickLabel = if (expanded) text.chat.stepsHide else text.chat.stepsShow,
        onClick = onToggle,
    ) {
        // No leading glyph: the duration and the count are a statement about the
        // turn, not another line of its work. The step list keeps the 24dp indent,
        // which is what makes the steps read as the thing this line opens.
        Text(
            text = label,
            // One style for both cases, and that is a correction rather than a
            // simplification. The two differ only in whether there is something
            // behind the row, and the style used to change with it — the reading
            // style + Medium when the row was a control, the smaller one + Normal
            // when it was not — so the same sentence about the same turn (`工作 47 秒`)
            // was drawn at two sizes in one conversation, which is exactly the
            // report: "有步骤时和没步骤时上面的工作X秒字体大小样式不一样". A turn with no
            // hidden rows is not a lesser fact about the turn; it is the same line
            // with nothing behind it, and the chevron below is what says which of
            // the two it is.
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            // The accent, and the chevron beside it is the same colour. Both used to be
            // `onSurfaceVariant`, with the arrow `primary` as "the one coloured mark among the
            // page's three disclosures" — and the reader's report is that this line, a message's
            // timestamp and its copy button are the furniture of a finished turn and should read
            // as one accent: "把工作几秒、几个步骤、消息时间、消息复制按钮等都变成主题蓝色".
            color = ChatTone.Accent.ink(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (foldable) {
            DisclosureChevron(
                expanded = expanded,
                modifier = Modifier.padding(start = TOOL_CHEVRON_GAP),
                tint = ChatTone.Accent.ink(),
            )
        }
    }
}


// ------------------------------------------------------------------ furniture

/**
 * What a piece of the page's furniture means, rather than what colour it is.
 *
 * The four meanings are the transcript's whole vocabulary: a fact, the app's own
 * emphasis, a thing that is running, and a thing that failed. The kit names the two
 * that a Miuix palette has no single role for ([PiTone.live] and [PiTone.error], drawn
 * from the tertiary and error pairs); the other two are the palette's own `primary` and
 * its quotation wash. A call site picks a meaning and the roles follow, which is
 * what stops this page's "warning" from being another page's "information".
 */
internal enum class ChatTone {
    Neutral,
    Accent,
    Live,
    Danger,
    ;

    @Composable
    fun container(): Color = when (this) {
        // The wash, not `surfaceContainerHigh`: a status pill is drawn *on* the answer
        // card, and in the dark scheme the high surface step is the card's own #242424
        // — the pill had no fill of its own there, so its label read as bare text with
        // a dot after it. `inset()` is a step on whatever it is drawn over, which is
        // the property a pill needs.
        Neutral -> PiTone.inset()
        Accent -> MiuixTheme.colorScheme.primaryContainer
        Live -> PiTone.liveContainer()
        Danger -> PiTone.errorContainer()
    }

    @Composable
    fun onContainer(): Color = when (this) {
        // Normal ink. `onSurfaceContainerHigh` is #A2A2A2 in the light scheme — the
        // summary grey, for text that explains something — and a neutral pill carries a
        // *state* ("已退出", "读取中"), which is a thing the reader is meant to read
        // rather than to skip. It was also the pill's own fill's near neighbour: the
        // container is #E8E8E8 and the ink #A2A2A2, a 2.5:1 pair.
        Neutral -> MiuixTheme.colorScheme.onSurface
        Accent -> MiuixTheme.colorScheme.onPrimaryContainer
        // The pair of [container]'s `tertiaryContainer` — `PiTone.live()` is the kit's
        // name for that ink, and the two are one pair: a live pill drawn with the
        // secondary container's ink was two different families on one surface.
        Live -> PiTone.live()
        Danger -> MiuixTheme.colorScheme.onErrorContainer
    }

    @Composable
    fun ink(): Color = when (this) {
        // `onSurface`, for the reason [onContainer] gives: a neutral label is a value
        // rather than an explanation. `onSurfaceVariantSummary` is 60% black, which on
        // the answer card's white is legible and on the reader's own tinted prompt card
        // is not.
        Neutral -> MiuixTheme.colorScheme.onSurface
        Accent -> MiuixTheme.colorScheme.primary
        Live -> PiTone.live()
        Danger -> PiTone.error()
    }
}

/**
 * A small state, on a pill: the two "something is running" rows at the foot of the
 * transcript.
 *
 * The tool rows used to wear this pill too, with the tool's name inside it and the
 * fill as the state. It is kept for the two rows that are a *state and nothing else* —
 * "opening the conversation" and "compacting the context" — because those have no
 * step row to live in. A tool call is a step of the turn and is drawn bare in
 * [ToolCallRow]; a filled capsule was the only decoration in a list of text.
 *
 * A [Surface] rather than a hand-drawn background, so the pill is clipped and filled
 * by the same code every other Miuix surface is. The radius is a full round rather
 * than a button's 16dp because a pill is the app's shape for a *state*, and 28dp is
 * the height the transcript's status rows are measured at.
 *
 * A live pill draws the running dot, which says "this has not come back yet" without
 * a word of its own; that is the same job the old design system's morphing indicator
 * had, done with the one palette role that means it.
 */
@Composable
internal fun ChatStatusPill(
    label: String,
    tone: ChatTone,
    modifier: Modifier = Modifier,
) {
    val ink = tone.ink()
    Surface(
        // A minimum, not a fixed height: the label is one line at 13sp by default, and
        // the app's text-size setting draws it larger — a `height` would clip it.
        modifier = modifier.heightIn(min = PILL_HEIGHT),
        shape = RoundedCornerShape(percent = 50),
        color = tone.container(),
        contentColor = ink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PILL_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tone == ChatTone.Live) {
                Box(
                    modifier = Modifier
                        .padding(end = PILL_MARK_GAP)
                        .size(PILL_MARK_SIZE),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(LIVE_DOT_SIZE)
                            .background(ink, CircleShape),
                    )
                }
            }
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The pill's height, its inset and the air around its mark. */
private val PILL_HEIGHT = 28.dp
private val PILL_INSET = 10.dp
private val PILL_MARK_GAP = 5.dp
private val LIVE_DOT_SIZE = 6.dp

/**
 * The radius of an inset panel inside a card — a tool's output, a message's reasoning.
 *
 * The app's cards are cut at [pi.kit.mob.ui.kit.PiCorner.radius] (20dp), so a panel that
 * sits *inside* one takes a step in: 12dp, which is the shape both of a turn's expanded
 * blocks use.
 */
internal val ROW_SHAPE = RoundedCornerShape(12.dp)

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
