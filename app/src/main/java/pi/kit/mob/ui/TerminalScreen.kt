package pi.kit.mob.ui

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
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
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pi.kit.mob.R
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.terminal.TerminalSessionManager
import pi.kit.mob.ui.kit.DarkNavigationBar
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.LocalPiNavigationInset
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
fun TerminalScreen(session: PiAgentSession,
    onBack: () -> Unit,
) {
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
    LaunchedEffect(Unit) {
        manager.begin()
        // The two setup commands the banner lists are the app's job on the first
        // visit: the storage farm follows Android's grant, and the apt mirror is
        // probed and chosen once, silently, so the first `pkg install` is not the
        // first thing that tries the network. See `PiHost.ensureTerminalSetup`.
        session.ensureTerminalSetup()
    }

    // The bar's modes. `ctrlOn`/`altOn` are latches on the old two-row bar; the hardware
    // switch below is the one that survived it.
    // Which input path owns the terminal: the phone's IME, or the app's own keyboard panel
    // above the bar. The bar's middle button switches it, and `holders.imeSuppressed` is
    // what the view client reads before raising the soft keyboard on a tap — a panel and
    // an IME that both react to the same tap is two keyboards fighting over one cursor.
    var keyboardPanel by rememberSaveable { mutableStateOf(false) }
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
    // TerminalView asks the client for the modifier latches on every key event. The bar
    // no longer carries `CTRL`/`ALT`, so both are false — the fields stay because the view
    // client reads them unconditionally.
    val holders = remember { TerminalHolders() }

    /**
     * Raises or lowers the IME through the *terminal view*, which is the object the input
     * connection belongs to.
     *
     * `LocalSoftwareKeyboardController` speaks for a Compose text field and would ask the
     * window for a keyboard nothing is listening to; `InputMethodManager.showSoftInput` on
     * the view that holds the session is what Termux does, and what the bar's keyboard
     * button and the keyboard panel both call.
     */
    val showIme: (Boolean) -> Unit = { show ->
        val view = holders.view
        val manager = view?.context?.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        if (view != null) {
            if (show) {
                view.requestFocus()
                manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            } else {
                manager?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }
    holders.ctrlDown = false
    holders.altDown = false

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

    // Measured here rather than inside the bar: one key size for every cap in it, from
    // a `TextMeasurer` pass before the first layout (see `rememberKeySize`).
    val keys = rememberKeySize(t, LocalDensity.current.fontScale)

    // The strip under the key bar is the terminal's black rather than the app's
    // surface, and the gesture handle on it is light in both schemes: the page is a
    // black rectangle from the header down, and a #F7F7F7 band under it — measured on
    // the 1080 px emulator, 63 px tall — was the one place the app's light chrome met
    // the shell's black along a straight line. The key bar paints the strip itself now
    // (see [ExtraKeysRow]); this effect owns the handle's ink, and it is a composition
    // rather than a page body effect so a theme change while the page is open cannot
    // leave the two disagreeing.
    DarkNavigationBar()

    PiPage(
        // The library's bar, like every other pushed page: the settings pages behind
        // the drawer put their back arrow at the library's inset, and a second arrow
        // position on the one page reached from the chat bar read as a second app.
        onBack = onBack,
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
            // No clear-screen button. It was one of four marks in this row, and the
            // shell has its own two ways to clear (the `clear` command and the one the
            // banner prints in its command table); a button for it spent the row's
            // first slot on the only action here that changes nothing on the phone's
            // display after the reader has scrolled, which made "new terminal" and the
            // roster — the two controls this page is reached for — start further from
            // the thumb. The banner lists `clear` beside the other commands instead.
            IconButton(onClick = { manager.create() }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = t.terminal.newTerminal,
                )
            }
            // There is deliberately **no close button here.**
            //
            // Closing a shell is the one destructive thing this page can do, and as a
            // sibling of "new" and the roster in this action row it sat one thumb-width
            // from both — the three were 40dp apart — so the wrong tap cost a whole
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
        // The app's own keyboard is a panel of keys, so back closes it rather than the
        // page: it is the thing on top of the page, and the same rule every sheet and
        // the drawer follow. Registered here, inside the page's body, so it is composed
        // after `PiPage`'s own handler and wins (the dispatcher runs the most recently
        // added enabled callback first). It also clears `imeSuppressed`, or the
        // terminal would keep refusing to raise the IME after the panel was dismissed
        // by a gesture rather than by its own button.
        if (keyboardPanel) {
            BackHandler {
                keyboardPanel = false
                holders.imeSuppressed = false
            }
        }
        // The scaffold's padding is the bar's height at the top; its bottom is zero, because
        // the key bar below paints the navigation strip as its own bottom padding. The column
        // therefore reaches the window's last pixel without the terminal growing: the strip
        // the body used to stop above is now spent inside the key bar's black.
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
                            // No inset for the bar: it is a sibling in the column now,
                            // and the Box above it is measured in the space that is
                            // left. That is what makes the keyboard panel *push* the
                            // terminal up rather than cover its last lines — the view
                            // is shorter, the renderer has fewer rows, the shell
                            // scrolls.
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

            }

            ExtraKeysRow(
                modifier = Modifier.fillMaxWidth(),
                text = t,
                size = keys,
                autoScroll = autoScroll,
                keyboardPanel = keyboardPanel,
                onToggleAutoScroll = { autoScroll = !autoScroll },
                onToggleKeyboardPanel = { on ->
                    keyboardPanel = on
                    // A panel and an IME that both answer the same tap are two
                    // keyboards fighting over one cursor, so the panel owning the
                    // input means the IME is put away — and the terminal stops
                    // raising it on a tap until the panel is closed.
                    holders.imeSuppressed = on
                    if (on) showIme(false)
                },
                onSend = { sequence -> selected?.write(sequence) },
                // Through the view rather than through Compose: the IME is attached
                // to the terminal's own input connection, so `LocalSoftware
                // KeyboardController` (which speaks for a Compose text field) would
                // ask the window for a keyboard nothing is listening to.
                onToggleKeyboard = { show -> showIme(show) },
            )
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
    // A single collector rather than `collectAsState()` plus a `LaunchedEffect(revision…)`:
    // the revision is bumped once per PTY read — hundreds of times a second for `yes` or an
    // unpacking archive — and the old shape paid, per chunk, a snapshot read, a
    // recomposition of this anchor, and a cancelled-and-relaunched coroutine before the
    // same repaint it makes below. The flow is conflated by `collect`, which is all this
    // needs: one repaint of the latest screen state. `updateSize()` is deliberately not
    // here — the `AndroidView`'s own update block calls `applyViewSize()`, which is where
    // the geometry is applied.
    LaunchedEffect(manager, selectedId) {
        manager.screenRevision.collect {
            holders.view?.onScreenUpdated()
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
            addAll(barLabels(text))
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
 * Every label the bar draws, in the order the rows read it.
 *
 * One list, two consumers: the bar renders these strings and [rememberKeySize] measures
 * them, so a key cannot be drawn at a size the measurement did not consider. The order is
 * the rows' own rule — the line-editing keys first, then the session keys and the symbols
 * — and the arrows are glyphs rather than words, so they are measured from [ARROW_GLYPHS].
 */
private fun barLabels(text: pi.kit.mob.locales.Strings): List<String> = listOf(
    text.terminal.tab,
    text.terminal.escape,
    text.terminal.interrupt,
    text.terminal.eof,
    text.terminal.home,
    text.terminal.end,
    text.terminal.ctrl,
    text.terminal.alt,
    text.terminal.pageUp,
    text.terminal.pageDown,
    text.terminal.scrollShort,
    "/",
    "-",
    "|",
    "~",
    "*",
)

/**
 * The extra-keys bar: one row of keys and three round actions, on the terminal's own dark.
 *
 * ## The row
 *
 * `滚动` first, then the four arrows, then `Ctrl+C`, `Ctrl+V`, `Ctrl+X` and `Esc`. The
 * order is the order of use on a phone: follow-the-output is the one key that is a *state*
 * and belongs at the leading edge where the thumb already is, the arrows are what a soft
 * keyboard does badly, the three Ctrl combinations are what a terminal is for, and `Esc`
 * ends the row because it is the one a shell asks for least.
 *
 * ## Why it is one row again
 *
 * Two rows of ten keys each was the previous shape, and the rows were a reading rule
 * (`the line`, `the session`) rather than a packing one — but the reader who has to *use*
 * the bar is holding a phone in one hand, and a second row puts half the keys past the
 * thumb's reach for a bar that exists to save taps. The keys that went with the second row
 * — `CTRL`, `ALT`, `TAB`, `PGUP`, `PGDN`, `HOME`, `END`, `C-D` and the five symbols — are
 * all reachable another way (`TAB` from the soft keyboard, the rest through the send-text
 * field or a typed command), and the eight that stayed are the ones that were pressed.
 *
 * ## The three round buttons
 *
 * They are not keys: they act on the sheet or on the input path rather than sending a byte.
 * **Send text** opens a field, and what is typed there goes to the terminal as one piece
 * followed by a carriage return — the answer to a `git commit -m` message or a multi-line
 * command. **Hardware keys** switches the input path: on, the soft keyboard is put away and
 * the terminal stops raising it on a tap, so a Bluetooth or USB keyboard owns the input; off,
 * the IME is the input again. It is a *mode* and stays set, which is what separates it from
 * the third button. **Keyboard** raises or lowers the IME on the spot.
 *
 * ## The dress
 *
 * The bar is the terminal's own dark: the page behind it is black, and a light strip along
 * its bottom edge read as a second surface rather than as part of the terminal. The keys are
 * one step lighter on it, the actions are circles, and nothing here takes a colour from the
 * app's theme — the app is light, the terminal is not, and the bar belongs to the terminal.
 *
 * Its background also carries the navigation strip: the black is painted first and the
 * strip's height is padding *inside* it, so the shell reaches the window's last pixel while
 * the keys stay above the gesture handle. A strip painted by the page instead — the app's
 * #F7F7F7 in the light scheme — was the one straight line where the two surfaces met.
 */
@Composable
private fun ExtraKeysRow(
    modifier: Modifier = Modifier,
    text: pi.kit.mob.locales.Strings,
    size: KeySize,
    autoScroll: Boolean,
    keyboardPanel: Boolean,
    onToggleAutoScroll: () -> Unit,
    onSend: (String) -> Unit,
    onToggleKeyboardPanel: (Boolean) -> Unit,
    onToggleKeyboard: (Boolean) -> Unit,
) {
    var sending by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // Read from the window rather than remembered, so it stays right when the keyboard is
    // raised by tapping the terminal instead of by a button.
    val density = LocalDensity.current
    val keyboardUp = WindowInsets.ime.getBottom(density) > 0
    // The strip under the bar is the bar's own: the background goes on first and the inset
    // is padding *inside* it, so the shell's black reaches the window's last pixel and the
    // keys stay above the gesture handle rather than being cut by it.
    val inset = LocalPiNavigationInset.current
    // Every key the bar draws sends a byte to the pty, so each press answers with the
    // key tick. The panel supplies its own tick and keeps `onSend`, or a key there would
    // sound twice; the scroll toggle and the panel action below are not keys.
    val haptics = LocalPiHaptics.current
    val sendKey: (String) -> Unit = { sequence ->
        haptics.key()
        onSend(sequence)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TERMINAL_BACKGROUND)
            .padding(
                start = BAR_SIDE_PADDING,
                top = BAR_PADDING,
                end = BAR_SIDE_PADDING,
                bottom = BAR_PADDING + inset,
            ),
    ) {
        if (keyboardPanel) {
            PcKeyboard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = BAR_GAP),
                onSend = onSend,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BarShape)
                .background(BAR_BACKGROUND)
                // No *leading* padding: the keys have to run to the pill's own edge, so
                // the *pill's* rounded clip is what hides a half-scrolled key there. An
                // inner inset put a straight vertical cut five dp inside the arc, which is
                // what read as a rendering fault. The trailing side is the other way
                // round: the last action is a 34dp circle in a 44dp pill, so a 5dp inset
                // puts it *concentric* with the pill's end cap — the same 5dp margin the
                // keys have above and below — and anything smaller lets the arc cut the
                // circle's right side off, which is what "the keyboard button is flush
                // against the edge" was.
                .padding(
                    top = BAR_INNER_PADDING,
                    end = BAR_INNER_PADDING,
                    bottom = BAR_INNER_PADDING,
                ),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The keys scroll; the three actions do not. Nine equal slots fit a 360dp
            // screen but squeezed every cap down to 25dp, which is a key a thumb misses —
            // and the row is the one place where a key that cannot be hit is worse than a
            // key that is off screen, because the bar exists to save typing. A key is as
            // wide as its label needs and the row scrolls when the labels do not fit.
            val keysScroll = rememberScrollState()
            val fadePx = with(density) { BAR_FADE_WIDTH.toPx() }
            Box(
                Modifier
                    .weight(1f)
                    // The keys are *erased* over the last stretch of the viewport rather
                    // than painted over. Painting the pill's own colour here was the first
                    // version and could not work: that colour is 90% opaque, so a second
                    // coat of it took the strip's last pixels to 99% and left a *lighter
                    // rectangle* whose right, top and bottom edges were straight lines —
                    // measured as a 3-to-4 level step across a flat bar. `DstOut` removes
                    // what is drawn instead of adding colour, so over the bar there is
                    // nothing to see and over a key there is nothing left.
                    //
                    // The shape is the one the implementations that exist converge on — a
                    // mask (not an overlay) over a *short, fixed* length: Android's own
                    // fading edge is exactly this at a fixed `fadingEdgeLength`, and
                    // `GIGAMOLE/ComposeFadingEdges` defaults to a 16dp `FadeClip` whose
                    // stops (0, 0.5, 1 with half alpha at the midpoint) are a linear ramp.
                    // Compose Foundation 1.12.1 has no modifier for it — the recipes are
                    // hand-rolled `drawWithContent` masks — so the mask is written here.
                    //
                    // A ramp as *wide as the distance still to scroll* was the previous
                    // version, and it was wrong in a way the eye catches: at rest it covered
                    // a whole key, and a rounded rectangle at 20% ink still reads as a shape
                    // — a shape at low contrast is the smudge the fade was supposed to
                    // remove. Sixteen dp of ramp over a 38dp key leaves an edge, not a
                    // ghost.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // `this.size` and not `size`: this composable's own `size: KeySize`
                        // parameter shadows the draw scope's, and reading it answers a `Dp`
                        // — the compiler reported "actual type is 'Dp', but 'Float' was
                        // expected" against a line that looked entirely correct.
                        val end = this.size.width
                        // Sixteen dp, except where less than that is left past the edge. The
                        // strip is masked by what can still come through for the same reason
                        // a fade exists at all: on a bar whose six keys fit — measured at
                        // 271dp of viewport against 263dp of keys — a fixed strip would dim
                        // the last key's own edge by a fifth with nothing behind it to hint
                        // at, and that is the artefact this whole exercise started from. The
                        // cap only bites in the last 16dp of travel, where the content that
                        // is cut is a sliver rather than a key.
                        val width = minOf(fadePx, (keysScroll.maxValue - keysScroll.value).toFloat())
                        if (width <= 0f) return@drawWithContent
                        drawRect(
                            brush = barFadeBrush(startX = end - width, endX = end),
                            blendMode = BlendMode.DstOut,
                        )
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // No clip of its own: the leading edge is the pill's arc, and the
                        // trailing one is where the actions begin.
                        .horizontalScroll(keysScroll),
                    horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Spacers inside the scrolling content rather than padding on the
                    // viewport: an inset would scroll the cut with the keys and put a
                    // straight edge back inside the pill. These scroll away with them, so
                    // at rest the first key clears the arc and once the row moves it is
                    // the arc that hides it.
                    Spacer(Modifier.width(BAR_INNER_PADDING))
                    BarKey(
                        label = text.terminal.scrollShort,
                        spoken = text.terminal.scrollToggleHint,
                        // The one key whose *state* is visible: the accent while
                        // following, the error ink while paused, so it reads as a mode
                        // rather than as a button.
                        labelColor = if (autoScroll) TERMINAL_ACCENT else TERMINAL_ERROR,
                        onClick = {
                            // A mode rather than a key: following stays off until it is
                            // turned back on, and `!autoScroll` is the value applied.
                            haptics.toggle(on = !autoScroll)
                            onToggleAutoScroll()
                        },
                    )
                    ArrowKey(ARROW_UP, sendKey)
                    ArrowKey(ARROW_DOWN, sendKey)
                    ArrowKey(ARROW_LEFT, sendKey)
                    ArrowKey(ARROW_RIGHT, sendKey)
                    BarKey(label = text.terminal.escape, onClick = { sendKey("\u001b") })
                    Spacer(Modifier.width(BAR_INNER_PADDING))
                }
            }
            BarAction(
                painter = painterResource(R.drawable.ic_send_text),
                modifier = Modifier.padding(start = BAR_INNER_PADDING),
                description = text.terminal.keysMessage,
            ) {
                // The sheet is the answer: this raises a window rather than sending a
                // byte, so it is navigation and stays silent.
                draft = ""
                sending = true
            }
            BarAction(
                painter = painterResource(R.drawable.ic_laptop),
                // A *mode*, and the sentence says which one a tap gives: the panel stays
                // up until it is tapped again, and while it is up the IME is not used.
                description = if (keyboardPanel) {
                    text.terminal.keysPanelHide
                } else {
                    text.terminal.keysPanelShow
                },
                active = keyboardPanel,
                onClick = {
                    // The mode the comment above describes: the action flips it rather
                    // than pressing a key, and `!keyboardPanel` is the value applied.
                    val next = !keyboardPanel
                    haptics.toggle(on = next)
                    onToggleKeyboardPanel(next)
                },
            )
            BarAction(
                painter = painterResource(R.drawable.ic_keyboard),
                description = if (keyboardUp) {
                    text.terminal.keysKeyboardHide
                } else {
                    text.terminal.keysKeyboardShow
                },
                onClick = {
                    // A key, not a mode: it raises or lowers the phone's IME, whose
                    // state the bar only mirrors.
                    haptics.key()
                    onToggleKeyboard(!keyboardUp)
                },
            )
        }
    }

    // The text that goes in whole. A sheet rather than a dialog: the field is the point,
    // it wants the height, and the keyboard will be over the bottom of the screen anyway.
    PiSheet(
        show = sending,
        title = text.terminal.keysMessage,
        onDismiss = { sending = false },
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            label = text.terminal.keysMessageLabel,
            useLabelAsPlaceholder = true,
            cornerRadius = PiCorner.radius,
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Button(
            cornerRadius = PiCorner.radius,
            onClick = {
                // Exactly what was typed, and nothing else: the sheet is a way to get a
                // long or multi-line string into the terminal's line, not a way to run it.
                // The reader presses Return where they want one — in the text, or on the
                // keyboard — and a `git commit -m` message is the case that made the
                // difference visible: appending a return ran it before it could be
                // reviewed or completed.
                onSend(draft)
                sending = false
                draft = ""
            },
            enabled = draft.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) {
            Text(text = text.terminal.keysMessageSend)
        }
    }
}

/**
 * One key of the bar: the terminal's dark, a lighter rectangle, and the sequence's name.
 *
 * Its width is the widest label's ([KeySize]), not its own: the whole row reads as one
 * instrument that way, and the reason the arrows have always been aligned by construction
 * survives into a row that is not a pad.
 */
@Composable
private fun BarKey(
    label: String,
    spoken: String? = null,
    labelColor: Color = TERMINAL_KEY_INK,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(BAR_KEY_HEIGHT)
            .widthIn(min = BAR_KEY_MIN_WIDTH),
        cornerRadius = BAR_KEY_RADIUS,
        minWidth = 0.dp,
        minHeight = 0.dp,
        colors = ButtonDefaults.buttonColors(
            color = TERMINAL_KEY,
            contentColor = labelColor,
        ),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Monospace,
            color = labelColor,
            maxLines = 1,
            // A label past the key's own width is marked rather than cut mid-glyph.
            overflow = TextOverflow.Ellipsis,
            modifier = if (spoken == null) {
                Modifier
            } else {
                Modifier.clearAndSetSemantics { contentDescription = spoken }
            },
        )
    }
}

/** An arrow key: the glyph is the label and the sequence is its own. */
@Composable
private fun ArrowKey(key: ArrowKey, onSend: (String) -> Unit) {
    BarKey(label = key.glyph, onClick = { onSend(key.sequence) })
}

/**
 * One of the three round actions: a glyph on the terminal's dark, in a circle.
 *
 * [active] is a mode that stays set — the hardware-keyboard switch is the only one today —
 * and it is drawn as the accent ink rather than as a different shape, so the bar does not
 * move when it is tapped.
 */
@Composable
private fun BarAction(
    painter: androidx.compose.ui.graphics.painter.Painter,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(BAR_ACTION_SIZE)
            .clip(CircleShape)
            .background(if (active) TERMINAL_ACCENT else TERMINAL_ACTION)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = if (active) TERMINAL_BACKGROUND else TERMINAL_KEY_INK,
            modifier = Modifier.size(BAR_ACTION_GLYPH),
        )
    }
}

/** The bar's dress: the terminal's own colours rather than the app's theme's. */
private val BAR_BACKGROUND = Color(0xE62C2C2E)
private val TERMINAL_KEY = Color(0xFF48484A)
private val TERMINAL_ACTION = Color(0xFF3A3A3C)
private val TERMINAL_KEY_INK = Color(0xFFF2F2F7)
private val TERMINAL_ACCENT = Color(0xFF8FC1FF)
private val TERMINAL_ERROR = Color(0xFFFF8A80)

private val BarShape = RoundedCornerShape(26.dp)
private val BAR_KEY_HEIGHT = 32.dp
private val BAR_KEY_MIN_WIDTH = 38.dp
private val BAR_KEY_RADIUS = 10.dp
private val BAR_ACTION_SIZE = 34.dp
private val BAR_ACTION_GLYPH = 17.dp
private val BAR_GAP = 4.dp
private val BAR_FADE_WIDTH = 16.dp
private val BAR_PADDING = 6.dp
private val BAR_INNER_PADDING = 5.dp
private val BAR_SIDE_PADDING = 8.dp

/**
 * The erase at the bar's trailing edge: a linear alpha ramp over [BAR_FADE_WIDTH].
 *
 * `DstOut` keeps nothing but the alpha, so the colour is black throughout. Linear, and
 * short, because that is the shape the implementations that exist use: Android's fading
 * edge at a fixed `fadingEdgeLength`, and `GIGAMOLE/ComposeFadingEdges` at 16dp with half
 * alpha at the midpoint — a linear ramp. An S-curve was the previous shape, chosen while
 * the strip was painted and as wide as the remaining scroll; with a mask 16dp wide the
 * curve is below what the eye resolves and the length is what decides whether the strip
 * reads as an edge or as a smudge.
 */
private fun barFadeBrush(startX: Float, endX: Float): Brush = Brush.horizontalGradient(
    0f to Color(0f, 0f, 0f, 0f),
    1f to Color(0f, 0f, 0f, 1f),
    startX = startX,
    endX = endX,
)

/**
 * One run of keys, on its own track.
 *
 * The track is what makes a group legible: the keys of a group sit on one container —
 * Miuix's card, `surfaceContainer`, one step of the library's surface ramp up from the
 * bar, with the keys themselves back on `surface` — so a group reads as one object and a
 * key reads as raised out of it. That is the same relationship a settings group has to its
 * rows, which is why it needs no new vocabulary.
 *
 * It takes a modifier now: the bar's grid is one card that holds two scrolling rows, so it
 * is the *remaining* width rather than its content's, and `weight` has to reach the card
 * for the rows inside to have bounds to scroll within.
 */
@Composable
private fun KeyGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
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
 * A key that carries a glyph rather than a word, for the bar's own two actions.
 *
 * The same [Button] the key chips use, with an icon where the label would be, so the
 * cluster at the right is the same family as the grid beside it rather than a second kind
 * of control dropped into the bar. Its spoken label is the sentence, because a glyph says
 * nothing to a screen reader.
 */
@Composable
private fun GlyphKey(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: KeySize,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(size.width, size.height)
            .clearAndSetSemantics { contentDescription = description },
        cornerRadius = size.height / 2,
        minWidth = 0.dp,
        minHeight = 0.dp,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ),
        insideMargin = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
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

    /**
     * Whether the soft keyboard is put away on purpose, because a physical one owns the
     * input.
     *
     * The bar's middle button sets it. It is on the holders rather than in composition
     * state because the client that has to read it — [onSingleTapUp] — is a Java-ish
     * callback built once per view, not a composable.
     */
    @Volatile
    var imeSuppressed = false

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
            // the keyboard, since there is no hardware one — unless the reader has handed
            // the input to a physical keyboard, in which case a tap must not undo that.
            if (imeSuppressed) return
            val terminal = view ?: return
            terminal.requestFocus()
            val manager = terminal.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
        }

        // **False, unlike Termux's own default.** In Termux the back key is the
        // hardware escape key a phone does not have, so it is offered as one. This
        // app is page-based and has both an ESC key on the bar and a back arrow in
        // the page bar, which leaves back with exactly one meaning — leave the page —
        // and the mapped escape broke it: `TerminalView.onKeyPreIme` consumed the key
        // and wrote `\e` into the shell, so the gesture never reached `PiPage`'s
        // `BackHandler`. Measured on the emulator: two back presses on a fresh shell
        // left the page where it was and produced `Display all 491 possibilities?`
        // after the banner, because readline binds `ESC ESC` to `complete` and the
        // prompt was empty. A gesture that types into the thing it was meant to leave
        // is the bug this line removes; Escape stays available on the bar.
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

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
