package pi.kit.mob.ui

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.terminal.TerminalSessionManager
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageHeader
import pi.kit.mob.ui.components.PickerBody
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.ReadOnlySheetRow
import pi.kit.mob.ui.components.Sheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pinch-to-zoom bounds, in sp. */
private const val MIN_TEXT_SIZE_SP = 8
private const val MAX_TEXT_SIZE_SP = 40

/**
 * A real terminal onto the bundled environment, backed by the vendored Termux
 * PTY stack.
 *
 * This exists so the environment is never a black box: when a package fails to
 * install or a path looks wrong, the user can run `pkg`, `ls` and `env`
 * themselves instead of guessing from agent output.
 *
 * The shells themselves belong to [PiAgentSession.terminalSessions], not to this
 * composable — switching to another tab disposes the whole screen, and a shell
 * that died with it would make a long build or an upgrade impossible.
 */
@Composable
fun TerminalScreen(session: PiAgentSession) {
    // Named `t` rather than `strings`: the session menu below takes a parameter
    // called `sessions`, and a local named `strings` would be read as the
    // catalog inside that call site regardless of what is passed.
    val t = strings
    val manager = session.terminalSessions
    val sessions by manager.sessions.collectAsState()
    val selectedId by manager.selectedId

    // Used to defer a resize until the layout stops changing, so the keyboard
    // animation does not resize the PTY once per frame. Scoped to this composable,
    // so a resize scheduled for a screen that is being disposed is cancelled with
    // it rather than running against a detached view.
    val scope = rememberCoroutineScope()

    // If every session is gone we are one blank page away from being unable to
    // type anything at all, so make sure one exists before the first frame.
    LaunchedEffect(Unit) { manager.begin() }

    DisposableEffect(Unit) {
        onDispose {
            // Deliberately nothing at all. Closing the sessions here is what used
            // to kill the shell on a tab switch; relocating the packages the user
            // had just installed was what this block did next, and that is gone
            // too. Leaving a page is not a moment the user chose and not one they
            // were told about, and it is not where the problem is: a package is
            // rewritten before dpkg unpacks it (ARCHITECTURE §4), so there is
            // nothing to reconcile afterwards. Settings -> Maintenance & repair is
            // the fallback, and the only place it is mentioned.
        }
    }

    val sheets = LocalSheetHost.current

    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    // Whether new output is allowed to pull the view back to the bottom.
    //
    // Held as the *inverse* of the emulator's own flag and mirrored into it on
    // every change, rather than read out of the emulator: the emulator lives in a
    // `View` that Compose recreates on every tab switch, and a piece of UI state
    // that only exists once a view has been attached would come back wrong — or
    // not at all — after a rotation. Toggling applies it to whatever view is live.
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    // Termux lets the on-screen modifier keys combine with the soft keyboard, so
    // TerminalView asks the client for their state on every key event.
    val holders = remember { TerminalHolders() }
    holders.ctrlDown = ctrlOn
    holders.altDown = altOn

    // The session a toggle should apply to, and the view showing it. `holders.view`
    // is null before the first layout pass and after the tab is disposed, so both
    // this effect and the `update` block above read it defensively.
    LaunchedEffect(autoScroll, holders.view) {
        holders.applyAutoScroll(autoScroll)
    }

    val selected = sessions.firstOrNull { it.id == selectedId } ?: sessions.firstOrNull()

    // Counted from the shells that are actually alive rather than from the list:
    // an entry survives its shell until it is closed, so `sessions.size` would
    // report an exited terminal as one of the "n sessions running" the header
    // claims. A single live shell keeps the idle line, which is the hint about
    // what this page is rather than a count.
    val running = sessions.count { it.running }

    Box(Modifier.fillMaxSize().background(TERMINAL_BACKGROUND)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                title = t.header.terminal,
                subtitle = if (running > 1) {
                    t.header.terminalSubtitle(running)
                } else {
                    t.header.terminalSubtitleIdle
                },
                actions = {
                    IconButton(onClick = { selected?.write("clear\n") }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = t.terminal.clear,
                        )
                    }
                    IconButton(onClick = { manager.create() }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = t.terminal.newTerminal,
                        )
                    }
                    IconButton(
                        onClick = { selected?.let { manager.close(it.id) } },
                        enabled = selected != null,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = t.terminal.closeTerminal,
                        )
                    }
                    IconButton(
                        onClick = {
                            // The session list is the same kind of question as the
                            // language row or the model chip — pick one of these —
                            // so it is asked with the same control, in the same
                            // layer. It used to be a `DropdownMenu` anchored to
                            // this button: a 280dp context menu with a tick glyph,
                            // which is the one selection UI in the app that did not
                            // match the others, and the only one that took window
                            // focus away from the terminal's own input.
                            sheets.show(
                                Sheet(key = "terminal-sessions") {
                                    // The selection is derived here rather than taken
                                    // from the `selected` local above: this body was
                                    // built when the sheet opened, and only the reads
                                    // *inside* it follow live state — so a row closed
                                    // here would otherwise leave the tick on the
                                    // session that is already gone.
                                    val current = sessions.firstOrNull { it.id == selectedId }
                                        ?: sessions.firstOrNull()
                                    PickerBody(
                                        title = t.terminal.switchTerminal,
                                        options = sessions.map { entry ->
                                            PickerOption(
                                                id = entry.id,
                                                label = entry.displayTitle,
                                                description = if (entry.running) {
                                                    null
                                                } else {
                                                    t.terminal.exited(entry.exitStatus)
                                                },
                                                // The sheet stays open: closing the
                                                // shells is a thing a user may be doing
                                                // to several of them at once.
                                                trailing = {
                                                    IconButton(
                                                        onClick = { manager.close(entry.id) },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.Close,
                                                            contentDescription = t.terminal
                                                                .closeSession(entry.displayTitle),
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                        selectedId = current?.id,
                                        onPick = { manager.select(it) },
                                        action = {
                                            ReadOnlySheetRow(
                                                label = t.terminal.closeAllTerminals,
                                                value = null,
                                                onClick = { manager.closeAll() },
                                            )
                                        },
                                        footnote = t.terminal.sessionsRunOn,
                                    )
                                },
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = t.terminal.switchTerminal,
                        )
                    }
                },
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TERMINAL_BACKGROUND),
            ) {
                // A session can exit between a recomposition and the view pass.
                if (selected != null) {
                    val entry = selected
                    val terminal = entry.session
                    // One applier per session id, so switching sessions starts from
                    // a clean slate and a pending resize cannot land on the view of
                    // a session it was not measured for.
                    val applySize = remember(entry.id) { ViewSizeApplier(scope) }
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            // Leave room for the extra keys row underneath.
                            .padding(bottom = EXTRA_KEYS_HEIGHT)
                            // Compose's after-layout hook. `updateSize()` is what
                            // creates the emulator the renderer paints from, and
                            // it bails out on a zero-sized view — so it has to run
                            // once real bounds exist, which is here.
                            //
                            // `onGloballyPositioned` reports the *laid-out* size
                            // and fires once per layout pass. Both matter: the
                            // AndroidViewHolder's child is laid out after this
                            // Compose node, so a size read any earlier is still
                            // zero; and during the keyboard animation a layout pass
                            // runs on every frame, so applying the size
                            // unconditionally meant the PTY was resized, the shell
                            // reflowed and the whole screen repainted on each one.
                            .onGloballyPositioned { coordinates: LayoutCoordinates ->
                                val size = coordinates.size
                                val view = holders.view ?: return@onGloballyPositioned
                                if (size.width <= 0 || size.height <= 0) return@onGloballyPositioned
                                // `applyViewSize` normalises the geometry, so the
                                // comparison is against the grid the emulator
                                // really has rather than against pixels the
                                // keyboard animation is still moving.
                                applySize.record(size.width, size.height, view)
                            },
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                // TerminalRenderer only paints a background
                                // rectangle for cells whose background differs
                                // from the default, so untouched areas stay
                                // transparent. Without a background on the view
                                // itself the terminal is see-through: the app's
                                // own surface shows, and the default white
                                // foreground text is white-on-white and therefore
                                // invisible.
                                setBackgroundColor(
                                    TerminalColors.COLOR_SCHEME
                                        .mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
                                )
                                // Mirrors android:focusableInTouchMode="true"
                                // from Termux's own layout. Without it the view
                                // cannot take focus in touch mode, so tapping it
                                // never raises the soft keyboard.
                                isFocusable = true
                                isFocusableInTouchMode = true
                                // Order matters. TerminalRenderer is created by
                                // setTextSize(), and attachSession() calls
                                // updateSize(), which dereferences it — so
                                // without this the first layout pass throws
                                // NullPointerException on mRenderer.mFontWidth.
                                holders.applyTextSize(this)
                                setTerminalViewClient(holders.viewClient)
                                holders.view = this
                            }
                        },
                        update = { view ->
                            holders.applyTextSize(view)
                            // Applied on every update rather than only from the
                            // toggle's effect: this block runs after a
                            // recomposition, and on a tab switch the view is
                            // created after the state was already set — the effect
                            // has nothing to attach to at that point, so without
                            // this the toggle would come back to its default
                            // position every time the tab was reopened.
                            holders.applyAutoScroll(autoScroll)
                            if (view.mTermSession !== terminal) {
                                view.attachSession(terminal)
                                view.requestFocus()
                            }
                            // Repaints unconditionally: a session that was
                            // already running wrote its prompt before this view
                            // existed, and `TerminalSession` only notifies a
                            // client on *new* output, so nothing else would paint
                            // it.
                            view.applyViewSize()
                            manager.attachView(view, entry.id)
                        },
                    )
                }

                ExtraKeysRow(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    text = t,
                    ctrlOn = ctrlOn,
                    altOn = altOn,
                    autoScroll = autoScroll,
                    onToggleCtrl = { ctrlOn = !ctrlOn },
                    onToggleAlt = { altOn = !altOn },
                    onToggleAutoScroll = { autoScroll = !autoScroll },
                    onSend = { sequence -> selected?.write(sequence) },
                )
            }
        }
    }

    // Output that arrived while another tab was showing still has to be painted
    // once this view exists. TerminalSessionClient.onTextChanged covers the live
    // case; this covers the session that was already running.
    ScreenRepaintAnchor(manager, holders, selected?.id)

    // A view is recreated on every tab switch; drop the stale reference or it
    // keeps repainting a View that is no longer in the hierarchy.
    DisposableEffect(holders) {
        onDispose { holders.view?.let(manager::detachView) }
    }
}

/**
 * Repaints the [TerminalView] when the session it shows changed while it was not
 * the one being composed.
 *
 * This is its own composable, and emits nothing, so the revision it observes is
 * read here and nowhere else. Reading it in [TerminalScreen] itself would make
 * every chunk of terminal output recompose the header, the session menu and the
 * whole `AndroidView` block — hundreds of times a second for something like
 * `yes` or an unpacking archive.
 */
@Composable
private fun ScreenRepaintAnchor(
    manager: TerminalSessionManager,
    holders: TerminalHolders,
    selectedId: String?,
) {
    val revision by manager.screenRevision.collectAsState()
    LaunchedEffect(revision, selectedId) {
        holders.view?.let { view ->
            view.updateSize()
            view.onScreenUpdated()
        }
    }
}

/**
 * The extra-keys bar's height.
 *
 * Two chip rows plus the gap between them and the padding around them: 30dp of
 * chip, 6dp of gap, 30dp of chip, and 5dp above and below. The arrows make the bar
 * two deep by themselves — up has to sit above down for the pad to mean anything —
 * and once one key needs a second row the rest may as well use it, which is how the
 * bar went from a horizontally scrolling strip to two rows that fit on screen.
 */
private val EXTRA_KEYS_HEIGHT = 76.dp

/** One chip's height, and the gap between chips in both directions. */
private val KEY_HEIGHT = 30.dp
private val KEY_GAP = 6.dp

/**
 * The corner every key in the bar is drawn with.
 *
 * One value, because two things have to agree: the `Surface`'s shape and the clip
 * that bounds the key's ripple (see [ExtraKeyChip]). Two spellings of "7dp" is how
 * they would come apart.
 */
private val EXTRA_KEY_SHAPE = RoundedCornerShape(7.dp)

/**
 * One arrow: the escape sequence it sends, and the glyph it shows.
 *
 * The two are separate fields and not one value, because the first version of the
 * pad passed the *sequence* as the label and the bar drew `[A`, `[B`, `[C`, `[D` —
 * the escape byte is invisible, so the user saw the tail of the sequence and
 * nothing else. A label is not a thing to derive from a sequence.
 */
private class ArrowKey(val sequence: String, val glyph: String)

private val ARROW_UP = ArrowKey("\u001b[A", "\u2191")
private val ARROW_DOWN = ArrowKey("\u001b[B", "\u2193")
private val ARROW_RIGHT = ArrowKey("\u001b[C", "\u2192")
private val ARROW_LEFT = ArrowKey("\u001b[D", "\u2190")

/**
 * Every key in the bar is this size.
 *
 * ## Why uniform, and why measured rather than a constant
 *
 * Three earlier versions sized the chips to their own labels, and every one of them
 * produced a bar that looked like a mistake: the arrows came out 32dp wide against
 * `PGUP`'s 44, and the padding between them was whatever fell out. Worse, the two
 * rows of the arrow pad could not be made to line up, because the cell above a chip
 * and the chip itself wanted different widths — the last attempt at fixing that
 * squeezed `换行` into `换`.
 *
 * One size for every key removes the whole class of problem: the pad's cells are
 * trivially the same width because everything is, `↑` and `↓` share a column by
 * construction, and no label can be clipped by a neighbour's width.
 *
 * ## How the size is found
 *
 * The **widest label's natural width plus the chip padding**, measured with a
 * [TextMeasurer] before the first layout rather than by measuring laid-out chips.
 * Measuring the chips works, but it costs a frame in which the whole bar is drawn
 * too narrow and then reflows; measuring the text costs nothing and is exact,
 * because a chip contains nothing but its text.
 *
 * Measured on the emulator at font scale 1.0: the widest label is `PGUP`/`PGDN`
 * (four monospace characters, 77px) and the result is 50dp, so every key in the bar
 * is a 151px rectangle — the arrows and `滚动` included.
 *
 * ## The one cost, and its ceiling
 *
 * Eleven keys of the widest label plus the pad and the gaps exceed a 1080px bar, so
 * the row scrolls at font scale 1.0 rather than fitting. The ceiling exists for
 * exactly that reason: past it the bar would scroll *more*, not fit more, so a long
 * translation is better served by a bound than by a wider key.
 */
private data class KeySize(val width: Dp, val height: Dp)

/**
 * Measures the widest label in the bar and returns the size every key should be.
 *
 * `remember`ed on the labels, so it runs once per language and font scale rather
 * than once per composition.
 */
@Composable
private fun rememberKeySize(
    text: pi.kit.mob.locales.Strings,
    fontScale: Float,
): KeySize {
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = remember(text) {
        buildList {
            add(text.terminal.ctrl)
            add(text.terminal.alt)
            add(text.terminal.home)
            add(text.terminal.end)
            add(text.terminal.pageUp)
            add(text.terminal.pageDown)
            add(text.terminal.newline)
            add(text.terminal.scrollShort)
            add(text.terminal.interrupt)
            add(text.terminal.eof)
            add(text.terminal.escape)
            add(text.terminal.tab)
            EXTRA_KEYS_ROW_TWO.forEach { add(it.fixedLabel ?: it.labelOf(text)) }
            ARROW_GLYPHS.forEach(::add)
        }
    }
    val widest = remember(labels, style, fontScale) {
        labels.maxOf { measurer.measure(it, style).size.width }
    }
    val width = with(density) { widest.toDp() } + KEY_TEXT_PADDING
    // The floor keeps a one-glyph label's key from being a sliver, and the ceiling
    // keeps a long translation from making the row scroll further than it has to.
    return KeySize(
        width = width.coerceIn(MIN_KEY_WIDTH, MAX_KEY_WIDTH),
        height = KEY_HEIGHT,
    )
}

/** The four arrow glyphs, named once so the size measurement and the pad agree. */
private val ARROW_GLYPHS = listOf("\u2190", "\u2191", "\u2193", "\u2192")

/** A chip's own horizontal padding, on both sides together. */
private val KEY_TEXT_PADDING = 22.dp

private val MIN_KEY_WIDTH = 34.dp
private val MAX_KEY_WIDTH = 64.dp

/**
 * The arrow pad: up directly above down, left and right flanking them, with the
 * two non-glyph controls in the corners above.
 *
 * Uniform keys do all the work here. Every cell is [KeySize.width] wide, so `↑` is
 * above `↓` because they are both the middle cell of their row — there is no
 * measurement to keep in step and no way for the two to drift apart, which is what
 * four earlier attempts at this each got wrong in a different way.
 */
@Composable
private fun ArrowPad(
    text: pi.kit.mob.locales.Strings,
    size: KeySize,
    following: Boolean,
    onToggleAutoScroll: () -> Unit,
    onSend: (String) -> Unit,
) {
    Column(
        modifier = Modifier.height(size.height * 2 + KEY_GAP),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            ScrollToggle(
                label = text.terminal.scrollShort,
                hint = text.terminal.scrollToggleHint,
                following = following,
                size = size,
                onClick = onToggleAutoScroll,
            )
            ExtraKeyChip(
                label = ARROW_UP.glyph,
                active = false,
                size = size,
            ) { onSend(ARROW_UP.sequence) }
            ExtraKeyChip(
                label = text.terminal.newline,
                active = false,
                size = size,
            ) { onSend(NEWLINE_SEQUENCE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            ExtraKeyChip(label = ARROW_LEFT.glyph, active = false, size = size) {
                onSend(ARROW_LEFT.sequence)
            }
            ExtraKeyChip(label = ARROW_DOWN.glyph, active = false, size = size) {
                onSend(ARROW_DOWN.sequence)
            }
            ExtraKeyChip(label = ARROW_RIGHT.glyph, active = false, size = size) {
                onSend(ARROW_RIGHT.sequence)
            }
        }
    }
}

/**
 * What the line-break key sends: `lnext`, then a newline.
 *
 * A bare `\n` is what this used to send, and it does not insert a line break — it
 * *ends the line*. In a canonical-mode terminal the newline character **is** the
 * line delimiter, so the shell read the line and ran it, which is what the reader
 * meant by "the newline key is a carriage return". The key needed a sequence of
 * its own rather than the obvious one.
 *
 * `0x16` is the terminal's `lnext` — the `^V` of `stty -a` — the line
 * discipline's "quote the next character". The newline that follows it is
 * therefore data in the line being typed rather than the end of it, which is
 * exactly what "insert a line break" means:
 *
 *  - at a shell prompt, readline has the terminal in non-canonical mode and
 *    handles `^V` itself as `quoted-insert`; the newline lands in the editing
 *    buffer and the line is not accepted. This is the mechanism that makes a
 *    pasted multi-line command editable rather than a sequence of commands, and
 *    the same one the reader gets here;
 *  - a program reading whole lines — canonical mode — has the quoting done by the
 *    line discipline instead, and receives a newline *inside* the line.
 *
 * Return still runs the line, from the soft keyboard; this key writes the line.
 * The two are deliberately different keys, and the bar's own label for this one
 * says which is which.
 */
private const val NEWLINE_SEQUENCE = "\u0016\n"

/**
 * The scroll-follow toggle: the word `滚动`, in the pad's top-left cell.
 *
 * ## What it deliberately is not
 *
 * It began as a wide two-line button pinned to the right of the whole bar, showing
 * its state in an icon *and* a word. That was three mistakes in one control: it was
 * the most prominent thing on the bar while being its least-used, its label resized
 * the scrolling area beside it whenever the state changed, and it said the same
 * thing twice.
 *
 * It then became an icon-only square, which was quieter but said nothing at all: a
 * glyph for "jump to the bottom" does not tell a user what tapping it does, and this
 * is a control most people meet for the first time here.
 *
 * A word is the answer to both. It reads as one of the bar's keys because it *is*
 * one — same size, same monospace label, same styling as `滚动`'s neighbours — and
 * the word is short enough to fit one key without shrinking the type, which is what
 * a longer label would have forced on the whole bar.
 *
 * **Nothing about the button changes when it is tapped except its tint**: the theme's
 * primary while following, its error colour while paused. Same size, same word, same
 * shape, so it cannot be misread as a mode it is not and it never moves anything
 * beside it.
 */
@Composable
private fun ScrollToggle(
    label: String,
    hint: String,
    following: Boolean,
    size: KeySize,
    onClick: () -> Unit,
) {
    Surface(
        shape = EXTRA_KEY_SHAPE,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .size(size.width, size.height)
            // The clip goes *before* `clickable`, and that ordering is the fix for
            // the reported ripple that spilled out of the corners. Material3's
            // `Surface` puts its own `clip(shape)` *after* whatever modifier it was
            // handed, and an indication draws where its node sits in the chain — so
            // a `clickable` applied to the bare chain paints a rectangle over the
            // rounded shape underneath it. Same rule, and same wording, as
            // `ToolCard`'s header row in `chat/Transcript.kt`.
            .clip(EXTRA_KEY_SHAPE)
            .clickable(
                // The full phrase, not the two-glyph label: the visible word names
                // the thing, and the spoken one has to say what tapping it does.
                onClickLabel = hint,
                onClick = onClick,
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (following) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * Keys a phone keyboard cannot produce, mirroring Termux's extra keys row.
 *
 * Escape sequences are sent straight to the session rather than synthesised as
 * key events, because the view's key handling depends on focus and on the client
 * reporting modifier state, which is fragile when the soft keyboard owns input.
 *
 * The label is a lambda so it is read inside the composable and follows the
 * interface language; the sequence is the only part that is fixed.
 */
private class ExtraKey(
    val sequence: String,
    val labelOf: (pi.kit.mob.locales.Strings) -> String,
    /** Printable keys and arrows show a glyph, so they pin their own label. */
    val fixedLabel: String? = null,
)

/**
 * The keys that are not arrows, split across the bar's two rows.
 *
 * First row: the keys that go with text entry — escape, tab, interrupt and EOF —
 * beside the arrow pad. Second row: navigation and the symbols a shell prompt
 * wants. Both rows scroll horizontally *together* (they are one `Column` inside one
 * `LazyRow`), so the pad stays aligned with the row beside it no matter where the
 * bar is scrolled to.
 *
 * The printable keys repeat their own character as their label and reuse the ESC
 * lambda; the lambda is only consulted when there is no fixed label, which keeps a
 * translated name like TAB/HOME from being overwritten by the escape byte.
 */
private val EXTRA_KEYS_ROW_ONE: List<ExtraKey> = listOf(
    ExtraKey("\u001b", { it.terminal.escape }),
    ExtraKey("\t", { it.terminal.tab }),
    ExtraKey("\u0003", { it.terminal.interrupt }),
    ExtraKey("\u0004", { it.terminal.eof }),
)

private val EXTRA_KEYS_ROW_TWO: List<ExtraKey> = listOf(
    ExtraKey("\u001b[H", { it.terminal.home }),
    ExtraKey("\u001b[F", { it.terminal.end }),
    ExtraKey("\u001b[5~", { it.terminal.pageUp }),
    ExtraKey("\u001b[6~", { it.terminal.pageDown }),
    ExtraKey("/", { it.terminal.escape }, "/"),
    ExtraKey("-", { it.terminal.escape }, "-"),
    ExtraKey("|", { it.terminal.escape }, "|"),
    ExtraKey("~", { it.terminal.escape }, "~"),
    ExtraKey("*", { it.terminal.escape }, "*"),
)

/**
 * The extra-keys bar: the arrow pad, then two rows of keys beside it.
 *
 * Every key is the same rectangle ([rememberKeySize]), which is what makes the pad
 * line up and keeps the ten keys beside it on an even grid. Everything scrolls
 * sideways together as one `Column` inside one `LazyRow`, so the pad stays aligned
 * with the rows beside it at any scroll offset.
 *
 * The two rows of the right-hand column are **start**-aligned rather than centred.
 * They hold four and nine keys, so centring would inset the shorter row by half a
 * key and break the grid the uniform size was for.
 */
@Composable
private fun ExtraKeysRow(
    modifier: Modifier = Modifier,
    text: pi.kit.mob.locales.Strings,
    ctrlOn: Boolean,
    altOn: Boolean,
    autoScroll: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onSend: (String) -> Unit,
) {
    val size = rememberKeySize(text, LocalDensity.current.fontScale)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(EXTRA_KEYS_HEIGHT),
        // Light, like the header above it and the navigation bar below. The
        // terminal itself stays dark — that is the transcript, not chrome.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                // The pad spans both rows, so it is one item; everything else is a
                // row of chips in the column beside it. The scroll toggle lives
                // inside the pad and scrolls with it: the pad is at the *start* of
                // the row, so the toggle is on screen at scroll offset zero without
                // holding a strip of the bar hostage for a control that is tapped
                // once a session.
                ArrowPad(
                    text = text,
                    size = size,
                    following = autoScroll,
                    onToggleAutoScroll = onToggleAutoScroll,
                    onSend = onSend,
                )
            }
            item {
                Column(
                    modifier = Modifier.height(size.height * 2 + KEY_GAP),
                    verticalArrangement = Arrangement.spacedBy(KEY_GAP),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExtraKeyChip(text.terminal.ctrl, active = ctrlOn, size = size) {
                            onToggleCtrl()
                        }
                        ExtraKeyChip(text.terminal.alt, active = altOn, size = size) {
                            onToggleAlt()
                        }
                        EXTRA_KEYS_ROW_ONE.forEach { key ->
                            ExtraKeyChip(
                                label = key.fixedLabel ?: key.labelOf(text),
                                active = false,
                                size = size,
                            ) { onSend(key.sequence) }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EXTRA_KEYS_ROW_TWO.forEach { key ->
                            ExtraKeyChip(
                                label = key.fixedLabel ?: key.labelOf(text),
                                active = false,
                                size = size,
                            ) { onSend(key.sequence) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of the bar's keys, at the bar's uniform size.
 *
 * The width comes from [size] rather than from the label, which is the point: a
 * key is a key whatever it says, and the single glyph keys and the four-letter ones
 * line up because they are the same rectangle.
 */
@Composable
private fun ExtraKeyChip(
    label: String,
    active: Boolean,
    size: KeySize,
    onClick: () -> Unit,
) {
    Surface(
        shape = EXTRA_KEY_SHAPE,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .size(size.width, size.height)
            // Before `clickable`, not after: the ripple is drawn by the clickable's
            // node, and a clip that comes later in the chain cannot bound it. See
            // `ScrollToggle` for the measured report.
            .clip(EXTRA_KEY_SHAPE)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** Mirrors the terminal's own default background so the screen has no seam. */
private val TERMINAL_BACKGROUND = Color(
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
)

/**
 * Brings the terminal's geometry in line with the view once the layout settles.
 *
 * The keyboard animation runs a layout pass per frame, and each one hands the view
 * a slightly different height — which measures out to a slightly different number
 * of rows. Thirteen distinct grids over a single animation is what was measured on
 * a Pixel-sized emulator. Resizing the PTY for each of them makes the shell reflow,
 * emit output and repaint the screen on every frame, for a row count that ends up
 * where it would have anyway, and the user sees that as stutter while the keyboard
 * slides up.
 *
 * So the size is *debounced* rather than deduplicated: every layout pass records
 * the latest size, and the resize is applied once the size has stopped changing. A
 * genuine resize — rotating the phone, switching sessions, opening the extra-keys
 * row — settles just as quickly and is applied just as reliably; what disappears is
 * the per-frame churn in the middle of an animation.
 *
 * The delay is short enough to be invisible next to the keyboard's own duration,
 * and long enough to absorb a frame or two of jitter, so the terminal cannot be
 * left at a stale size.
 */
private class ViewSizeApplier(private val scope: CoroutineScope) {

    private var pending: Job? = null

    fun record(widthPx: Int, heightPx: Int, view: TerminalView) {
        if (widthPx <= 0 || heightPx <= 0) return

        // Only the last size of a burst survives: each new pass cancels the resize
        // that had not fired yet.
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_DELAY_MS)
            apply(view)
        }
    }

    private fun apply(view: TerminalView) {
        // The view can be detached between the schedule and the run: the user may
        // have switched tabs. Resizing a view that is no longer in the hierarchy
        // would size the PTY to a rectangle nobody can see.
        if (view.parent == null) return
        view.applyViewSize()
    }

    private companion object {
        const val SETTLE_DELAY_MS = 90L
    }
}

/**
 * Brings the emulator's geometry in line with the view's real bounds and paints the
 * current screen.
 *
 * The repaint is not optional: `TerminalSession` tells its client about *new*
 * output, so a session that printed its prompt before this view existed would
 * otherwise show an empty screen until the user typed something.
 */
private fun TerminalView.applyViewSize() {
    updateSize()
    onScreenUpdated()
}


/**
 * State that belongs to one [TerminalView], which Compose recreates whenever the
 * terminal tab re-enters the composition. The sessions it displays do not live
 * here — only the things about the view itself.
 */
private class TerminalHolders {

    @Suppress("unused")
    var view: TerminalView? = null

    /**
     * Font size the user sees, in sp. Converted to pixels for the renderer.
     *
     * 12sp, which is Termux's own default for the same terminal (`Math.round(12 *
     * dipInPixels)` in its preferences) and the size this bar's 48-column banner
     * is written for: at 14 the first launch fitted noticeably fewer columns than
     * the banner's own comment claims, and a phone screen's worth of MOTD scrolled
     * off before the user could read it. Pinch still zooms from here.
     */
    var textSizeSp = 12

    /** Toggled by the on-screen CTRL/ALT keys; read by TerminalView per key. */
    var ctrlDown = false
    var altDown = false

    private var appliedTextSizePx = -1

    /**
     * `TerminalRenderer.setTextSize()` takes pixels, not sp.
     *
     * Termux converts its stored preference the same way — its default is
     * `Math.round(12 * dipInPixels)`. Passing a raw sp value would render text
     * at roughly a third of the intended size on a modern density screen.
     *
     * `TypedValue.applyDimension` rather than reading `scaledDensity` directly:
     * that field is deprecated from API 34 and no longer accounts for the font
     * scale on its own, so the multiplication this replaces would silently stop
     * following the user's text size on a new device.
     */
    fun applyTextSize(terminal: TerminalView) {
        val metrics = terminal.resources.displayMetrics
        val pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), metrics)
            .roundToInt()
            .coerceAtLeast(1)
        if (pixels != appliedTextSizePx) {
            appliedTextSizePx = pixels
            terminal.setTextSize(pixels)
        }
    }

    /**
     * Pushes the bar's scroll-follow choice into the live emulator.
     *
     * `toggleAutoScrollDisabled` is the only mutator the vendored
     * [TerminalEmulator] exposes — there is no setter — so the flag is read back
     * and flipped only when it disagrees. Calling the toggle unconditionally would
     * invert the state on every recomposition.
     *
     * `mEmulator` is null until a session is attached, which is why this returns
     * silently rather than asserting: the `update` block that calls it runs before
     * `attachSession` on a fresh view.
     */
    fun applyAutoScroll(following: Boolean) {
        val emulator = view?.mEmulator ?: return
        if (emulator.isAutoScrollDisabled() == following) emulator.toggleAutoScrollDisabled()
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val next = (textSizeSp * scale)
                .roundToInt()
                .coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
            if (next == textSizeSp) return scale
            textSizeSp = next
            view?.let { applyTextSize(it) }
            // Consume the gesture, otherwise the scale detector keeps applying it.
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent) {
            // Mirrors Termux's client. On a phone this is the only way to raise
            // the keyboard, since there is no hardware one.
            val terminal = view ?: return
            terminal.requestFocus()
            val manager = terminal.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = true

        override fun shouldEnforceCharBasedInput(): Boolean = true

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = ctrlDown

        override fun readAltKey(): Boolean = altDown

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
            false

        override fun onEmulatorSet() {
            // Mirrors Termux's own client: this is where the cursor blinker is
            // started, because it can only run once an emulator exists.
            view?.setTerminalCursorBlinkerState(true, false)
        }

        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
        }

        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) {
            Log.i(tag, message)
        }

        override fun logDebug(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun logVerbose(tag: String, message: String) {
            Log.v(tag, message)
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, "error", e)
        }
    }
}
