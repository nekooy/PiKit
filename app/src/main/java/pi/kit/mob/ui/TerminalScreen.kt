package pi.kit.mob.ui

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.PiPage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * that died with it would make a long build or an upgrade impossible. Nothing is
 * reconciled on the way out either: a package is rewritten before dpkg unpacks it
 * (ARCHITECTURE §4), so a page that is left is not a state to repair, and
 * Settings -> Maintenance & repair is the fallback — the only place it is mentioned.
 *
 * ## What is chrome here, and what is not
 *
 * The rectangle in the middle is the vendored `TerminalView`, painted from the
 * terminal's own sixteen colours; nothing on this page restyles it, and nothing
 * may — a terminal that borrowed the app's palette stops being the thing whose
 * output the reader is trusting. So the page is the *chrome*: a Miuix page bar
 * that says which shell this is, a card that reports a shell that has ended, and
 * the key bar. All of it is Miuix — a [PiPage] with Miuix's own small top bar,
 * Miuix buttons for the keys, Miuix's window list popup for the roster — and it
 * invents no colour, radius or control of its own (ARCHITECTURE §13).
 *
 * The welcome banner is the shell's too, not the page's:
 * [PiAgentSession.refreshTerminalBanner] writes it in the interface language and a
 * shell reads it at start-up, so a language change reaches the *next* shell — a
 * running shell's scrollback is the shell's own, and repainting it would be editing
 * output the reader may already be reading.
 */
@Composable
fun TerminalScreen(session: PiAgentSession) {
    // `t`, not `strings`: that is the imported accessor read on the next line, and
    // `text` is the name the composables below take for the same catalog.
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
    // The roster popup's own open state. It lives here rather than in the popup
    // because the popup is drawn by the window while the button that raises it is
    // in the page's bar — and a page that is disposed while the popup closes must
    // not leave a window behind.
    var sessionsMenuOpen by remember { mutableStateOf(false) }
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

    // Measured here rather than inside the bar, because two things have to agree on
    // it: the bar's own height, and the room the terminal leaves under its last line.
    val keys = rememberKeySize(t, LocalDensity.current.fontScale)
    val keysHeight = barHeight(keys)

    PiPage(
        // The four tab roots left-align their titles and subtitles. The bar's right
        // side is where three or four action icons live, and the library centres a
        // title between the navigation icon and those actions — so a root with four
        // actions has its title pushed off centre by their width. See `PiPage`'s
        // *Why a root can be left-aligned*.
        alignStart = true,
        // Which shell this is, and what the page is. The subtitle *is* the roster
        // count once there is more than one shell: the line is there either way, so
        // the count costs no height, where a pill reporting it above the terminal
        // grew and shrank the terminal's own rectangle every time a shell was
        // created or closed. One shell keeps the idle line, which is the hint about
        // what this page is rather than a count.
        //
        // A shell's *failure* is still not here: a header's subtitle is a plain
        // string, so a shell that ended could neither carry the error tone nor
        // offer the one thing that fixes it — [ShellStateRow] over the terminal does
        // both.
        title = selected?.displayTitle ?: t.header.terminal,
        subtitle = if (running > 1) {
            t.header.terminalSubtitle(running)
        } else {
            t.header.terminalSubtitleIdle
        },
        // The header's height is the terminal's: the PTY is sized from the rectangle
        // under it, so anything that grew the header while the reader watched would
        // resize the shell under them. The bar is Miuix's small bar at its fixed
        // height — pinned, `CollapsedHeight` (52dp), and scroll cannot move any of it
        // — and the subtitle is not the library's: [PiPage] draws it beneath the bar
        // itself, one line with an ellipsis. That is what keeps the header constant
        // in every language: the library's own subtitle has no line limit, so this
        // page's English idle sentence wrapped onto a second line where the Chinese
        // one did not, and the bar — and therefore the PTY's rectangle — moved by a
        // line when the language changed. The line's text then changes between the
        // idle sentence and the roster count without moving anything, because it is
        // always one line. §9.2 has the measurement.
        actions = {
            IconButton(
                onClick = { selected?.write("clear\n") },
                // Nothing to clear when the page has no shell to clear: the close
                // button beside it is gated on the same condition, and a button that
                // takes a tap and does nothing is worse than one that refuses it.
                enabled = selected != null,
            ) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = t.terminal.clear,
                    // `IconButton` refuses the tap without drawing the refusal —
                    // Miuix's `IconButton` has no disabled appearance at all — so a
                    // gated button looked live. The disabled ink is the scheme's own
                    // role for exactly this, applied at the glyph.
                    tint = if (selected != null) {
                        LocalContentColor.current
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
            IconButton(onClick = { manager.create() }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = t.terminal.newTerminal,
                )
            }
            // There is deliberately **no close button here.**
            //
            // Closing a shell is the one destructive thing this page can do, and as the
            // third icon in this action row it sat one thumb-width from "clear" and
            // "new" — the three were 40dp apart — so the wrong tap cost a whole
            // session's scrollback. The close control lives in the roster instead
            // ([TerminalSessionMenu]'s trailing delete), where every row already names
            // the shell it is about and the action cannot be ambiguous; the roster's
            // "close all" row is there for the same reason.
            //
            // The roster's switch. Miuix's icon set has no terminal glyph, so the
            // button shows the thing it opens — a stack of sessions — and the
            // content description says what tapping it does. It is an `IconButton`
            // rather than Miuix's own `WindowIconDropdownMenu` because that
            // component's items are one line of text and no trailing action, and a
            // shell has to be closable from the list without being switched to
            // first (see [TerminalSessionMenu]).
            IconButton(
                onClick = { sessionsMenuOpen = true },
                // Held down while its popup is open, which is the state Miuix's own
                // popup buttons show: the bar says which button the panel belongs to
                // even while the panel dims it.
                holdDownState = sessionsMenuOpen,
            ) {
                Icon(
                    imageVector = MiuixIcons.Layers,
                    contentDescription = t.terminal.switchTerminal,
                )
            }
            // The popup is anchored to the parent layout of this call — the app
            // bar's action row — and aligned to its end, so the menu hangs under
            // the action group with its right edge at the screen's margin. It is the
            // overlay popup rather than the window one for the reason §7.1 gives: a
            // window is a second window, so it can outlive the page that raised it and
            // it takes the focus with it — which on this page would close the keyboard
            // the moment the roster is opened to switch shells.
            OverlayListPopup(
                show = sessionsMenuOpen,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { sessionsMenuOpen = false },
            ) {
                ListPopupColumn {
                    TerminalSessionMenu(
                        text = t,
                        manager = manager,
                        onPicked = { sessionsMenuOpen = false },
                    )
                }
            }
        },
    ) { padding ->
        // The scaffold's padding is the bar's height and the insets the page is
        // actually under: the root already took the navigation bar away, so the
        // column ends where the page does and the terminal is measured from there.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ShellStateRow(
                text = t,
                selected = selected,
                onNewTerminal = { manager.create() },
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
                            // Leave room for the extra keys row underneath — the same
                            // derived height the bar itself is drawn at, so the last
                            // line of the terminal can never sit under a key.
                            .padding(bottom = keysHeight)
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
                    size = keys,
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
 * The session picker: one row per shell with the live one ticked and its title in the
 * accent, a delete that closes a shell without leaving the menu, and the roster's one
 * page-level action.
 *
 * A Miuix window list popup raised from the bar's stack button — the library's own
 * anchored floating menu, and the shape the previous menu already had. It was a
 * `DropdownMenu` and then a bottom sheet, and the sheet was wrong for this one: the
 * question is "which terminal", asked from a toolbar button over the terminal
 * itself, and a panel from the bottom edge covered the thing being switched to
 * (§7.1). The popup is anchored by its call site's parent, which is why the call
 * sits in the action row rather than inside the button.
 *
 * The rows are the kit's [PiMenuRow], not Miuix's `RadioButtonPreference`: a
 * `BasicComponent` with end actions cannot be measured inside a `ListPopupColumn` in
 * Miuix 0.9.4 (`PiMenuRow`'s KDoc has the arithmetic, and this roster is where the
 * crash was found), and one menu row for every popup in the app is what keeps this
 * list and the chat's model list from drifting apart. The tick the preference would
 * have drawn is passed as the row's start slot, and the title beside it is drawn in
 * the same accent, because the two are one statement: a single-choice row that ticks
 * itself in one ink and names itself in another says "chosen" and "ordinary" at once.
 * `PiMenuRow` colours a `selected` title `primary` for exactly this, which is what
 * keeps this list and the composer's model menu reading as one control.
 *
 * The roster's trailing action ends a shell, and it is drawn as one: `Delete` at 18dp
 * in the summary ink, inside the same 28dp `IconButton`, rather than the ✕ the row
 * carried at the library's own size and ink. A shell closed from here is gone from
 * the roster rather than hidden, and the mark is the one the app already puts on that
 * (`SessionsScreen`'s selection delete, the profile rows in `ModelPages`); a fine,
 * muted glyph also leaves the tick and the accent title to say which shell is live
 * instead of competing with them for the row's one emphasis.
 *
 * The selection is derived here rather than taken from the `selected` local in
 * [TerminalScreen]: this body was built when the popup opened, and only the reads
 * *inside* it follow live state — so a row closed here would otherwise leave the
 * tick on the shell that is already gone.
 */
@Composable
private fun TerminalSessionMenu(
    text: pi.kit.mob.locales.Strings,
    manager: TerminalSessionManager,
    onPicked: () -> Unit,
) {
    val sessions by manager.sessions.collectAsState()
    val selectedId by manager.selectedId
    val current = sessions.firstOrNull { it.id == selectedId } ?: sessions.firstOrNull()

    // No "is the menu still open" gate: the popup draws a full-window layer that
    // absorbs a tap while it animates out, so a tap aimed at the page underneath a
    // leaving menu cannot be eaten by one of its rows.
    sessions.forEach { entry ->
        PiMenuRow(
            title = entry.displayTitle,
            summary = if (entry.running) {
                null
            } else {
                text.terminal.exited(entry.exitStatus)
            },
            // The shell on screen is one of a set, so the row is selectable rather
            // than merely clickable: the radio mark beside it is then decoration for
            // the state a screen reader already reports. `selected` is also what
            // colours the title, so the tick, the title beside it and the "selected" a
            // screen reader says are one answer rather than three.
            selected = entry.id == current?.id,
            startAction = {
                RadioButton(selected = entry.id == current?.id, onClick = null)
            },
            trailing = {
                // The menu stays open: closing the shells is a thing a user may
                // be doing to several of them at once.
                //
                // Tapping this does not also switch to the row it is in,
                // because the button's own pointer input consumes the down
                // first — which is what keeps a close from selecting the shell
                // it has just closed.
                //
                // The mark is `Delete` at 18dp in the summary ink: the action ends a
                // shell for good, so it wears the glyph the app already puts on that,
                // and a fine muted mark lets the tick and the accent title carry the
                // row's state instead of competing with them. The tap box stays
                // 28dp — the mark shrank, the target did not.
                IconButton(
                    onClick = { manager.close(entry.id) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = text.terminal
                            .closeSession(entry.displayTitle),
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            },
            onClick = {
                onPicked()
                manager.select(entry.id)
            },
        )
    }

    // The roster's one page-level action, as a row of its own — a menu has no
    // action strip below it, and the one thing that acts on every shell reads as
    // the last entry of the list it empties. It is the same row as the shells above
    // it with the error role for a title, which is the one cue that says this entry
    // is not a choice.
    PiMenuRow(
        title = text.terminal.closeAllTerminals,
        titleColor = MiuixTheme.colorScheme.error,
        onClick = {
            onPicked()
            manager.closeAll()
        },
    )
}

/**
 * A shell that has ended, over the terminal — and nothing at all while the shells
 * are fine.
 *
 * A shell that exited is a Miuix card on the error container, in the app's own
 * words, with the one remedy that applies to it, because the terminal itself cannot
 * say it and the picker is behind a tap. It is the only thing this row draws: the
 * *count* of running shells is the header's subtitle now (`TerminalScreen`), where
 * the line it replaced already existed — a pill reporting the count grew the strip
 * above the terminal by its own height and took it away again on the next close,
 * and the PTY is sized from the rectangle under that strip. The count cost a `TERM`
 * resize every time a shell was created or closed; it costs nothing as a subtitle.
 *
 * Miuix's icon set has no warning triangle, so the mark is its `Info` glyph in the
 * container's own foreground: the sentence beside it is what says a shell ended and
 * what its code was, and a second alarm glyph would be saying it twice.
 *
 * Nothing is drawn while the page's one shell is alive, and that is the point: this
 * is chrome around a rectangle the reader came to read. The terminal still changes
 * height when a shell *ends* or is closed, and that is the trade the notice makes:
 * its content is worth a line, and a strip of fixed height would hold that room
 * open for a report that is almost never there and charge the terminal for it in
 * lines of output at all times.
 */
@Composable
private fun ShellStateRow(
    text: pi.kit.mob.locales.Strings,
    selected: TerminalSessionManager.Entry?,
    onNewTerminal: () -> Unit,
) {
    val ended = selected?.takeIf { !it.running } ?: return

    Card(
        cornerRadius = PiCorner.radius,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KEY_BAR_SIDE_PADDING, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
        ),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = text.terminal.exited(ended.exitStatus),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            // A compact text button, not the bar's full 40dp one: this row is a
            // line of chrome above a terminal, and every dp it spends is a line of
            // output it costs while it is on screen.
            TextButton(
                cornerRadius = PiCorner.radius,
                text = text.terminal.newTerminal,
                onClick = onNewTerminal,
                minWidth = 0.dp,
                minHeight = 30.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                textStyle = MiuixTheme.textStyles.footnote1,
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.onErrorContainer,
                ),
            )
        }
    }
}

/**
 * The bar's height, derived from the keys in it.
 *
 * The arrow cluster is two keys deep — up has to sit above down for the pad to mean
 * anything — and it is the tallest thing in the bar, so the bar is that cluster plus
 * the padding of the group it sits in and the bar's own padding. The groups beside
 * it are one key deep and are centred on that.
 *
 * It is a function of [KeySize] rather than a constant, and that is a fix rather than
 * tidiness. It used to be a flat 76dp derived from 30dp chips, and the chips grew without
 * it: the bar was shorter than its own contents, so the second row sat on the bar's
 * bottom edge and at font scale 1.8 the whole bar overflowed — measured on the emulator,
 * the labels of the row underneath were drawn over by the tab strip. Nothing about the
 * bar is a fixed size: the keys scale with the interface, so the space holding two of
 * them has to.
 */
private fun barHeight(size: KeySize): Dp =
    size.height * 2 + KEY_GAP + GROUP_PADDING * 2 + KEY_BAR_PADDING * 2

/** One chip's height floor, and the gap between chips in both directions. */
// 24dp with the label style. The bar's keys sat at the composer's 34dp so the two
// tappable pill families matched, and at that size they read as oversized against
// the terminal's own text; a second pass took them further down — the type as well
// as the padding — because the label's line box alone kept the chips near 28dp
// however tight the padding got. A floor rather than the height: the type is still
// scaled by the system font size, so the chip is the larger of this and its own
// label's line box.
private val KEY_HEIGHT = 24.dp
private val KEY_GAP = 5.dp

/**
 * The padding inside the bar's one group's track.
 *
 * A group is what makes the bar readable at a glance — every key in the strip sits on
 * one raised track — and the track is Miuix's own card: `surfaceContainer`, the one
 * step of the library's surface ramp that a group of controls sits on, with the keys
 * themselves back on `surface` so a key reads as raised out of its group.
 *
 * There used to be a second constant beside this one — the gap *between* two groups,
 * more than three times the gap between two keys — and both went with the second
 * frame (see [ExtraKeysRow] for what the reader saw of it).
 */
private val GROUP_PADDING = 4.dp

/** The bar's padding around its contents, on each side of each axis. */
private val KEY_BAR_PADDING = 5.dp
private val KEY_BAR_SIDE_PADDING = 6.dp

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
 * because a chip contains nothing but its text. The padding is no longer one
 * constant on both axes: a round key's cap curves into the label's box by an amount
 * that grows with the key, so the horizontal inset is solved against the height
 * rather than written down (see [rememberKeySize]).
 *
 * The measurement is taken in the same type the keys are drawn in — Miuix's
 * `footnote2` in the monospace face — so the number is the chips' real width rather
 * than a proportional face's estimate of it. The figures this bar was first tuned
 * against (the widest label `PGUP`/`PGDN` at 77px, every key about 44dp wide) were
 * taken from a proportional label style, and the shape they settled on is the one
 * that survived: a uniform key, smaller than the 50dp/34dp figures an earlier
 * revision drew, which read as oversized for the terminal's chrome.
 *
 * ## The one cost, and its ceiling
 *
 * Eleven keys of the widest label plus the pad and the gaps exceed a 1080px bar, so
 * the row scrolls at font scale 1.0 rather than fitting. The ceiling exists for
 * exactly that reason: past it the bar would scroll *more*, not fit more, so a long
 * translation is better served by a bound than by a wider key.
 *
 * The bound is where the longest shipped label ends, not a round number below it.
 * `Scroll` is 6 characters and `滚动` two, but `スクロール` is five full-width glyphs —
 * measured at 184px, 70dp, against an earlier 64dp ceiling — so the Japanese bar drew
 * `スク` and cut the rest of the word off mid-glyph. At [MAX_KEY_WIDTH] the bar takes
 * that label in full and the ones it shipped with are unaffected, because the width
 * every key is given is still the *widest* label's: a language whose labels are short
 * pays nothing for the ceiling being high.
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
    // The keys' own type, family included: a measurement in a different face is a
    // measurement of a different bar (see [KeySize]). Built from the style set the
    // theme handed down rather than on every recomposition, so the `remember`s
    // below are keyed on something that only changes with the theme.
    val typography = MiuixTheme.textStyles
    val style = remember(typography) {
        typography.footnote2.copy(fontFamily = FontFamily.Monospace)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = remember(text) {
        buildList {
            add(text.terminal.ctrl)
            add(text.terminal.alt)
            add(text.terminal.newline)
            add(text.terminal.scrollShort)
            EXTRA_KEYS.forEach { add(it.fixedLabel ?: it.labelOf(text)) }
            ARROW_GLYPHS.forEach(::add)
        }
    }
    val widest = remember(labels, style, fontScale) {
        labels.maxOf { measurer.measure(it, style).size.width }
    }
    val tallest = remember(labels, style, fontScale) {
        labels.maxOf { measurer.measure(it, style).size.height }
    }
    // The height follows the label for the same reason the width does, and it is the
    // half that was missed: the type's line box is scaled by the system font size
    // while the chip was once a flat 34dp, so at 1.8 every key in the bar clipped
    // its own label top and bottom. Measured on the emulator in Japanese at 1.8:
    // the labels were cut to the height of the chip, `スクロール` to `スク`, and the
    // second row of the bar ended up under the tab strip.
    //
    // The floor keeps a one-glyph label's key from being a sliver, so it is applied
    // here: the inset below is a function of the height, and the height it has to be
    // solved against is this one.
    val height = (with(density) { tallest.toDp() } + KEY_VERTICAL_PADDING)
        .coerceAtLeast(KEY_HEIGHT)

    // A round key's own cap eats into the label's box, and how much it eats grows
    // with the key. The label sits [KEY_VERTICAL_PADDING]/2 from the top and the
    // bottom of the key, so its top-left corner is inside the pill only while the
    // horizontal inset clears `r - sqrt(2rv - v²)` — with `r` the key's half-height
    // and `v` the vertical inset. Worked out at both ends: 3.06dp at the 24dp key of
    // font scale 1.0, which the 6dp the label already had covers, and 9.35dp at a
    // 44dp one, which it does not. So the inset is solved rather than fixed, and it
    // is the same shape of fix as the height above: the number that was a constant
    // is a constant only where the key is short.
    val radius = height.value / 2f
    val halfVerticalInset = KEY_VERTICAL_PADDING.value / 2f
    val capInset = radius -
        sqrt((2f * radius * halfVerticalInset - halfVerticalInset * halfVerticalInset).coerceAtLeast(0f))
    val sidePadding = maxOf(KEY_TEXT_PADDING / 2, capInset.dp)
    // The ceiling keeps a long translation from making the row scroll further than
    // it has to.
    return KeySize(
        width = (with(density) { widest.toDp() } + sidePadding * 2)
            .coerceIn(MIN_KEY_WIDTH, MAX_KEY_WIDTH),
        height = height,
    )
}

/** The four arrow glyphs, named once so the size measurement and the pad agree. */
private val ARROW_GLYPHS = listOf("\u2190", "\u2191", "\u2193", "\u2192")

/** A chip's own padding, on both axes together: 6dp beside the label, 4dp above it. */
private val KEY_TEXT_PADDING = 12.dp
private val KEY_VERTICAL_PADDING = 8.dp

private val MIN_KEY_WIDTH = 24.dp

/**
 * The widest a key may be drawn, whatever its label measures.
 *
 * 72dp is `スクロール` — the longest label the bar ships, five full-width glyphs —
 * plus the chip's own padding, rounded up. See [KeySize] for why the bound is
 * worth having and why this is where it sits.
 */
private val MAX_KEY_WIDTH = 72.dp

/**
 * The arrow pad: up directly above down, left and right flanking them, with the
 * two non-glyph controls in the corners above.
 *
 * Uniform keys do all the work here. Every cell is [KeySize.width] wide, so `↑` is
 * above `↓` because they are both the middle cell of their row — there is no
 * measurement to keep in step and no way for the two to drift apart, which is what
 * four earlier attempts at this each got wrong in a different way.
 *
 * It is the contents of one [KeyGroup] and the only two-deep item in the bar:
 * [barHeight] is the cluster's own height plus the padding around it.
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
 * one — same size, same monospace label, same Miuix button as its neighbours — and
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
    KeyButton(
        label = label,
        size = size,
        // The key's tint is the whole state: the fill, the word and the size are the
        // same in both positions, so nothing about the bar moves when it is tapped.
        labelColor = if (following) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.error
        },
        onClick = onClick,
        // The full phrase, not the two-glyph label: the visible word names the
        // thing, and the spoken one has to say what tapping it does.
        spokenLabel = hint,
    )
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
 * The keys that are neither arrows nor modifiers, as one run.
 *
 * Text entry first — escape, tab, interrupt and EOF — then the navigation keys, then
 * the symbols a shell prompt wants, because that is the order they are reached for
 * in. They are one group and not two rows: everything in the bar scrolls sideways
 * *together*, so a run and the cluster beside it stay on the same axis wherever the
 * strip is scrolled to, and a second row's only job would be to halve how far the
 * strip has to travel.
 *
 * The printable keys repeat their own character as their label and reuse the ESC
 * lambda; the lambda is only consulted when there is no fixed label, which keeps a
 * translated name like TAB/HOME from being overwritten by the escape byte.
 */
private val EXTRA_KEYS: List<ExtraKey> = listOf(
    ExtraKey("\u001b", { it.terminal.escape }),
    ExtraKey("\t", { it.terminal.tab }),
    ExtraKey("\u0003", { it.terminal.interrupt }),
    ExtraKey("\u0004", { it.terminal.eof }),
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
 * One run of keys, on its own track.
 *
 * The track is what makes a group legible: the keys of a group sit on one container
 * — Miuix's card, `surfaceContainer`, one step of the library's surface ramp up from
 * the bar, with the keys themselves back on `surface` — so a group reads as one
 * object and a key reads as raised out of it. That is the same relationship a
 * settings group has to its rows, which is why it needs no new vocabulary.
 *
 * It wraps its contents, which is what lets the arrow cluster's track be two keys
 * tall while the runs beside it are one, all centred on the bar's axis.
 */
@Composable
private fun KeyGroup(content: @Composable () -> Unit) {
    Card(
        cornerRadius = PiCorner.radius,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        insideMargin = PaddingValues(GROUP_PADDING),
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * The extra-keys bar: the arrow cluster and the keys beside it, in one group.
 *
 * Every key is the same size ([rememberKeySize]) and every key is fully round — the
 * Miuix button's corner radius is the key's own half-height, so "fully round" is half
 * of the shorter side rather than a number written here. The uniform size is what
 * makes the pad line up and keeps a label from being clipped by a neighbour's width,
 * and it is the one property of this bar that four earlier revisions each got wrong
 * in a different way (ARCHITECTURE §7.4).
 *
 * ## Why the arrows are still a cluster and not four more keys in the row
 *
 * A flat `← ↑ ↓ →` is not a d-pad: the eye reads it left to right and has to stop and
 * count to find "down". The four arrows are therefore a two-row cross — `↑` above
 * `↓`, `←` and `→` flanking them — and because every key is the same rectangle the
 * alignment is by construction rather than by two widths agreeing. Flattening it into
 * the row is the design §7.4 measured and rejected, and it is the one thing the
 * grouping here may not do.
 *
 * ## One group, not two
 *
 * The bar used to be two tracks — the arrow cluster in one frame, the modifiers and
 * every other key in a second, 16dp apart — and the second frame is gone. Both
 * frames were drawn on the same container colour, so what the reader saw was one
 * strip with a seam down it: the same three surfaces at two different insets, which
 * reads as a rendering mistake rather than as two sets of keys. There is exactly one
 * thing this bar is, and it is one pad of keys; the frames that said otherwise were
 * the only part of the bar that was not the keys themselves.
 *
 * The strip is still one `LazyRow` with a single item, so the group is measured
 * unbounded along the axis and scrolls sideways when it is wider than the screen —
 * which, at eleven keys and the widest label, it is at every font scale.
 */
@Composable
private fun ExtraKeysRow(
    modifier: Modifier = Modifier,
    text: pi.kit.mob.locales.Strings,
    size: KeySize,
    ctrlOn: Boolean,
    altOn: Boolean,
    autoScroll: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onSend: (String) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight(size))
            // The page's own surface — the colour Miuix's navigation bar and the
            // page behind it are painted in — and this strip is the page's bottom
            // edge, so anything else drew a band of its own between the terminal and
            // the tab strip. It was a container colour a step *paler* than that in
            // the light scheme, which is a seam at the exact place the page is meant
            // to end. The keys stay raised out of it: [KeyGroup] puts each group on
            // `surfaceContainer`.
            .background(MiuixTheme.colorScheme.surface),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = KEY_BAR_SIDE_PADDING, vertical = KEY_BAR_PADDING),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                KeyGroup {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArrowPad(
                            text = text,
                            size = size,
                            following = autoScroll,
                            onToggleAutoScroll = onToggleAutoScroll,
                            onSend = onSend,
                        )
                        // Two rows, and the modifiers at the head of the first one: the
                        // bar is two keys tall for the arrow pad's sake, so a single row
                        // of keys in it left half the height empty wherever it did not
                        // reach — and the row reached past the right edge rather than
                        // using the space below it. The keys that used to sit to the
                        // right of CTRL and ALT now wrap under them, which is the report
                        // "把后面一条按键放在 ctrl 和 alt 下面以充分利用空间".
                        //
                        // The pair still reads as a pair: they are first, they latch, and
                        // their active fill is the one that stays pressed.
                        Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                                ExtraKeyChip(text.terminal.ctrl, active = ctrlOn, size = size) {
                                    onToggleCtrl()
                                }
                                ExtraKeyChip(text.terminal.alt, active = altOn, size = size) {
                                    onToggleAlt()
                                }
                                EXTRA_KEYS.take(EXTRA_KEYS_PER_ROW).forEach { key ->
                                    ExtraKeyChip(
                                        label = key.fixedLabel ?: key.labelOf(text),
                                        active = false,
                                        size = size,
                                    ) { onSend(key.sequence) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                                EXTRA_KEYS.drop(EXTRA_KEYS_PER_ROW).forEach { key ->
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
    }
}

/**
 * How many of [EXTRA_KEYS] share the modifiers' row, so the rest fall under them.
 *
 * Six: with `CTRL` and `ALT` at the head of the row that is eight keys, against seven
 * on the row below — the two rows are within one key of each other in every shipped
 * language, which is what "the space is used" means here. The symbols are the seven
 * that end up on the second row, and they are the ones a reader reaches for least.
 */
private const val EXTRA_KEYS_PER_ROW = 6

/**
 * One of the bar's keys, at the bar's uniform size and fully round.
 *
 * The width comes from [size] rather than from the label, which is the point: a
 * key is a key whatever it says, and the single glyph keys and the four-letter ones
 * line up because they are the same rectangle.
 *
 * A Miuix [Button] rather than a bare clickable box, because the library's button is
 * what carries the tap: the press indication, the button role for a screen reader,
 * and the squircle corner (whose radius is the key's half-height, so the two things
 * that have to agree on the shape — the fill and the touch area — are one value).
 * The active key is the theme's primary, which is the only thing that changes when a
 * modifier latches.
 */
@Composable
private fun ExtraKeyChip(
    label: String,
    active: Boolean,
    size: KeySize,
    onClick: () -> Unit,
) {
    KeyButton(
        label = label,
        size = size,
        color = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.surface
        },
        labelColor = if (active) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        onClick = onClick,
    )
}

/**
 * The bar's key: one shape, one size, one label style.
 *
 * Written once because the scroll toggle and the lettered keys have to be
 * indistinguishable but for their word and tint — that is the whole argument in
 * [ScrollToggle] — and two composables that draw the same key would drift apart.
 *
 * [color] is the fill, and it is passed rather than derived: the toggle changes only
 * its tint, and the latched modifiers keep a fill of their own, so the one thing that
 * differs between them is a parameter.
 *
 * [spokenLabel] replaces the visible word for a screen reader, which is what a short
 * label on a stateful control needs: "滚动" names the thing, and the spoken sentence
 * has to say what tapping it does. It is null for the lettered keys, whose label is
 * already the whole truth.
 */
@Composable
private fun KeyButton(
    label: String,
    size: KeySize,
    color: Color = MiuixTheme.colorScheme.surface,
    labelColor: Color,
    onClick: () -> Unit,
    spokenLabel: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(size.width, size.height),
        // Half of the key's own height, which is what makes a wide key a pill and a
        // short one a circle; a fixed dp would be one shape at font scale 1.0 and
        // another at 1.8.
        cornerRadius = size.height / 2,
        // The key's size is its modifier, not a floor: Miuix's button minimums are
        // for a word on a page, and a 58dp key would not fit eleven to a bar.
        minWidth = 0.dp,
        minHeight = 0.dp,
        colors = ButtonDefaults.buttonColors(color = color, contentColor = labelColor),
        insideMargin = PaddingValues(0.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            // The bar is a terminal's chrome and its labels are sequences, so the
            // face is the terminal's own; the measurement in [rememberKeySize] uses
            // the same style so the two agree on how wide a key has to be.
            fontFamily = FontFamily.Monospace,
            color = labelColor,
            maxLines = 1,
            // A label past the ceiling is marked as truncated rather than cut
            // mid-glyph: `TextOverflow.Clip` is the default, and it is what drew
            // `スクロール` as `スク` with nothing to say the word was longer.
            overflow = TextOverflow.Ellipsis,
            modifier = if (spokenLabel == null) {
                Modifier
            } else {
                // Replaces the visible word rather than adding to it, so the control
                // is read as the sentence a reader needs once, not as a word followed
                // by a sentence about it.
                Modifier.clearAndSetSemantics { contentDescription = spokenLabel }
            },
        )
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

        // `SHOW_IMPLICIT` is deprecated as of API 30, and the call stays: this app's
        // `targetSdk` is 28 (§1), so the flags argument is the API this build is
        // written against on every device it runs on, and its replacement
        // (`WindowInsetsController`) is an API-30 API that would need a second path
        // for the API 26–29 devices this app also supports. Suppressing the note is
        // the honest reading: nothing here is going to be migrated, and the flag is
        // ignored by the framework on the versions that deprecated it.
        @Suppress("DEPRECATION")
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
