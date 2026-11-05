package pi.kit.mob.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pi.kit.mob.data.Workspace
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.TurnInFlightException
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiGlass
import pi.kit.mob.ui.kit.glassLift
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiTone
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Management for pi's saved conversations: reopen, rename, pin, delete.
 *
 * The drawer's content, drawn over the conversation rather than instead of it: the list
 * is what the leading mark and a rightward drag pull out, and the conversation stays
 * visible at the panel's trailing edge, which is what says the page behind is one drag
 * away. It is not a dialog for the older reason — a dialog leaves no room for a search
 * field, and multi-select delete in a dialog is a worse experience than a real list.
 *
 * Titles come from the session file: pi does not generate names, so an unnamed session
 * is labelled with its first user message, which is exactly what pi's own session picker
 * does. That is also the reason the rows are drawn as questions rather than as records:
 * for most of the list the title *is* the reader's own words.
 *
 * The heading is the app's name at page-title size, with the list's own state on a
 * caption under it — how many are saved, how many are selected, whether a search is
 * still reading. §9.2 has why that caption is one fixed line rather than a heading that
 * grows, collapses or moves as the list does.
 */
@Composable
fun SessionsScreen(
    session: PiAgentSession,
    /**
     * The list's scroll position, owned by the caller: this screen is rebuilt whenever the
     * conversation changes, and a position remembered here was rebuilt with it.
     */
    listState: LazyListState,
    onBack: () -> Unit,
    onOpened: () -> Unit,
    /**
     * Makes [PiAgentSession.SessionSummary]'s conversation the one on screen. The host
     * reads a saved conversation from its file and activates it — no process is started
     * until the first prompt — so this is a move between windows, and the conversation
     * that was answering keeps its own child.
     */
    onOpenConversation: (PiAgentSession.SessionSummary) -> Unit,
    /** Leaves the drawer for the settings page, which is reached from its foot. */
    onOpenSettings: () -> Unit,
    /**
     * The session file the conversation on screen is writing to.
     *
     * The one row in the list that is also a position: it is drawn filled, the way the
     * reference drawing marks the conversation being read, and tapping it is a
     * no-op rather than opening a second process on the file that is already open.
     */
    currentPath: String?,
) {
    val text = strings
    // The first frame comes from the last listing, not from an empty list. The drawer is
    // rebuilt when the reader switches conversation (the chat page is keyed on the
    // session), and a drawer that starts empty draws its spinner and then its rows — on
    // a quick swipe back into it, a blank panel that fills in. The rows are the same
    // directory the scan below is about to read, so they are the right thing to draw
    // while it does; see `PiAgentSession.cachedSessionSummaries`.
    var summaries by remember {
        mutableStateOf<List<PiAgentSession.SessionSummary>>(session.cachedSessionSummaries())
    }
    var loading by remember { mutableStateOf(summaries.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The page needs one string from the live conversation — the file the agent is
    // writing to — and nothing else. Collecting the whole state here would rebuild this
    // page on every streamed token (`ChatScreen` collects it for the transcript, where
    // that is the point); mapping first and dropping repeats means a turn in flight
    // invalidates nothing on this screen. The `initial` reads `.value` once, before the
    // first frame, which is the one place a `.value` read in composition is safe — it is
    // replaced by the collector's own first emission, and it is why the value is no
    // longer assigned to a state variable from the composition body.
    val activeSessionFileFlow = remember(session) {
        session.conversation.map { it.sessionFile }.distinctUntilChanged()
    }
    val activeSessionFile by activeSessionFileFlow.collectAsState(
        initial = remember(session) { session.conversation.value.sessionFile },
    )
    // The three dialogs are kept composed and opened by a flag: a dialog raised by
    // composing it (`target?.let { Dialog(show = true) }`) vanishes the frame its answer
    // clears the target, which skips Miuix's exit animation, and a title recomputed from
    // the live list reads "0 conversations" while the panel leaves. Each keeps its
    // subject in one state and its visibility in another — the shape the Files page's
    // preview sheet uses.
    // Which saved conversations are live *processes* right now. A live row is one tap
    // back to a conversation that is still answering, which is the thing that did not
    // exist while the app had one child.
    // Two states, not one: a row's dot says whether an answer is being written right now
    // (blue) or finished while the reader was elsewhere (green). "Live" — a process that
    // exists — is neither of those, and drawing it was the dot that appeared on every
    // conversation that merely had a child.
    val streamingPaths by session.host.streamingPaths.collectAsState()
    val unreadPaths by session.host.unreadPaths.collectAsState()
    var renameTarget by remember { mutableStateOf<PiAgentSession.SessionSummary?>(null) }
    var renameUp by remember { mutableStateOf(false) }
    var deleteTargets by remember {
        mutableStateOf<List<PiAgentSession.SessionSummary>>(emptyList())
    }
    var deleteUp by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var matches by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var matchedQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current

    LaunchedEffect(reload) {
        // Only a drawer with nothing to show draws the spinner. With a listing already
        // on screen the scan is a refresh, and raising `loading` for it is what put an
        // empty panel over rows the reader had seen a frame earlier.
        if (summaries.isEmpty()) loading = true
        summaries = session.listSessions()
        loading = false
    }

    // The content scan is expensive (a parse per message line, on conversations
    // that can be megabytes), so it waits for a pause in typing: the effect is
    // cancelled and restarted on every keystroke, and the delay before the work is
    // what debounces it.
    //
    // `searching` is raised *before* the delay, not after it: the filter's own title
    // match runs on the keystroke, so for the length of the wait `visible` can already be
    // empty and the page would say "nothing matches" — a claim about a scan that had not
    // started. Raised first, the whole wait is the "still reading" state it is.
    LaunchedEffect(query, summaries) {
        val needle = query.trim()
        if (needle.length < MIN_SEARCH_CHARS || summaries.isEmpty()) {
            matches = emptyMap()
            matchedQuery = ""
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        matches = session.searchSessionContent(summaries, needle)
        matchedQuery = needle
        searching = false
    }

    // A snippet is shown only for the query it answered: while a new scan is
    // running the previous one's snippets would be highlighted against text that
    // does not contain what is in the field.
    val snippets = if (matchedQuery == query.trim()) matches else emptyMap()

    // The workspaces a conversation can have come from, so a group is headed by the name
    // the composer's chip shows rather than by a path segment: the list and the chat page
    // name the same folder the same way, which is what lets a reader recognise the one the
    // conversation came from.
    val context = LocalContext.current
    val settings by session.settingsStore.settings.collectAsState()
    val workspaces = rememberWorkspaceList(
        context = context,
        env = session.env,
        savedDefault = settings.defaultWorkspace,
    )
    val defaultWorkspacePath = settings.defaultWorkspace.ifBlank { session.env.workspacePath }

    val visible = remember(summaries, query, snippets) {
        if (query.isBlank()) {
            summaries
        } else {
            val needle = query.trim()
            summaries.filter {
                it.title.contains(needle, ignoreCase = true) || snippets.containsKey(it.path)
            }
        }
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    fun togglePinned(item: PiAgentSession.SessionSummary) {
        session.setPinned(item, !item.pinned)
        reload++
    }

    // The drawer, in the shape the reference drawing gives it: the app's name at the
    // size of a page title, a search field under it, the conversations, and one control
    // at the foot.
    //
    // Not a `PiPage`. A page's bar is 48dp of chrome with a small title in it, and this
    // is not a page under a bar: it is a surface pulled over the conversation, and its
    // heading is the app's own name at twice a bar title's height. The one thing the bar
    // was still carrying here is `onBack`, which the drawer now owns — the system gesture
    // closes the panel rather than leaving the app.
    BackHandler(onBack = onBack)

    // The drawer is a surface pulled over the conversation, not a page: the window's own
    // insets are its only frame, so the panel reaches the window's last pixel and the list
    // and the settings pill carry the navigation strip as their own bottom padding instead.
    // A band of `surface` under the panel was the opaque strip that cut the last row.
    val navigationInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Top and sides only: the strip is spent by the list's content padding
                // and the pill's own margin, both of which can be scrolled or moved,
                // where a padding here would end the panel above the handle.
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HEADING_GUTTER, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text.root.appName,
                    style = MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (selecting) {
                    // The three answers selection mode has, in the bar's own order:
                    // everything, then what happens to it, then out. The list is the same
                    // list, so the control that starts a selection and the controls that
                    // end one stand in the same place.
                    val visiblePaths = visible.map { it.path }.toSet()
                    val allVisibleSelected =
                        visiblePaths.isNotEmpty() && visiblePaths.all { it in selected }
                    // 36dp, not the library's 48: these three are the *same* control
                    // the single select button was, and a row of three full-size icon
                    // buttons beside a title wider than the title itself was the reason
                    // the heading looked crowded rather than the reason it was useful.
                    IconButton(
                        modifier = Modifier.size(SELECTION_BUTTON),
                        onClick = {
                            selected = if (allVisibleSelected) {
                                selected - visiblePaths
                            } else {
                                selected + visiblePaths
                            }
                        },
                        enabled = visiblePaths.isNotEmpty(),
                    ) {
                        // One glyph for both answers, told apart by the accent: the
                        // library's set has a check-the-list mark and no un-check one, and
                        // the state a reader needs from this button is "what will the tap
                        // do", which the tint says and the spoken description says out
                        // loud.
                        Icon(
                            imageVector = MiuixIcons.SelectAll,
                            contentDescription = if (allVisibleSelected) {
                                text.sessions.deselectAll
                            } else {
                                text.sessions.selectAll
                            },
                            modifier = Modifier.size(SELECTION_GLYPH),
                            tint = if (allVisibleSelected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onBackground
                            },
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(SELECTION_BUTTON),
                        onClick = {
                            deleteTargets = summaries.filter { it.path in selected }
                            deleteUp = true
                        },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(
                            MiuixIcons.Delete,
                            contentDescription = text.sessions.deleteSelected,
                            modifier = Modifier.size(SELECTION_GLYPH),
                        )
                    }
                    IconButton(
                        modifier = Modifier.size(SELECTION_BUTTON),
                        onClick = { exitSelection() },
                    ) {
                        Icon(
                            MiuixIcons.Close,
                            contentDescription = text.sessions.cancelSelection,
                            modifier = Modifier.size(SELECTION_GLYPH),
                        )
                    }
                } else {
                    IconButton(
                        modifier = Modifier.size(SELECTION_BUTTON),
                        onClick = { selecting = true },
                        enabled = summaries.isNotEmpty(),
                    ) {
                        Icon(
                            MiuixIcons.SelectAll,
                            contentDescription = text.sessions.select,
                            modifier = Modifier.size(SELECTION_GLYPH),
                        )
                    }
                }
            }

            // What the list is doing, under the heading and in the search field's own
            // column. The line is always drawn — an empty one holds its height — because a
            // sentence that came and went would move the field and every row under it by a
            // line, which is the jump `PiPage` reserves its subtitle line to avoid.
            Text(
                text = when {
                    selecting -> text.sessions.selected(selected.size)
                    loading -> text.sessions.loading
                    // The scan has no progress to report — it is one file at a time and
                    // the count is not known — so the line says what is happening rather
                    // than how far along it is. Without it a slow scan is
                    // indistinguishable from an empty result.
                    searching -> text.sessions.searching
                    else -> text.sessions.saved(summaries.size)
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HEADING_GUTTER, end = HEADING_GUTTER)
                    .padding(top = 2.dp, bottom = 8.dp),
            )

            // The search field is a permanent row rather than a magnifier that opens one:
            // the reference drawing's first row is a place to type, and a drawer that
            // needs a tap before it can be searched reads as a list with no way into it.
            // The library's `SearchBar` is still not used — its expanded state is a
            // results surface, and this field narrows the list in place.
            TextField(
                cornerRadius = PiCorner.radius,
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = HEADING_GUTTER, end = HEADING_GUTTER, bottom = 8.dp),
                label = text.sessions.searchHint,
                useLabelAsPlaceholder = true,
                singleLine = true,
                // No focus ring: see `piFieldColors`.
                colors = piFieldColors(),
                cursorBrush = piFieldCursor(),
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        ClearFieldButton(
                            onClick = { query = "" },
                            contentDescription = text.common.clear,
                        )
                    }
                },
            )

            // The list passes under the field rather than starting beneath it: a row
            // that has been scrolled to the top was cut by the field's own edge, which
            // is the seam this drawer's reference drawing spends the same 16dp hiding.
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                // A scan that has not answered yet is not an empty result. The list
                // used to say "nothing matches" for as long as the scan ran, which is
                // a claim about a search that was still reading files.
                searching && visible.isEmpty() -> Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    SearchingNote(label = text.sessions.searching)
                }

                visible.isEmpty() -> SessionsNote(
                    title = if (summaries.isEmpty()) {
                        text.sessions.emptyHeading
                    } else {
                        text.sessions.nothingMatches(query)
                    },
                    body = if (summaries.isEmpty()) {
                        text.sessions.empty
                    } else {
                        text.sessions.searchEmptyNote
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> ConversationList(
                    visible = visible,
                    text = text,
                    workspaces = workspaces,
                    defaultPath = defaultWorkspacePath,
                    selecting = selecting,
                    selected = selected,
                    snippets = snippets,
                    currentPath = currentPath,
                    onToggle = { path ->
                        selected = if (path in selected) selected - path else selected + path
                    },
                    // No question and no interruption: a conversation that is live is
                    // switched to and keeps its answer, and one that is not is opened as
                    // a second process in the directory its session file records. The
                    // dialog that used to stand here asked whether to throw an answer
                    // away, which is a cost this design does not charge.
                    streamingPaths = streamingPaths,
                    unreadPaths = unreadPaths,
                    onOpen = { item ->
                        if (item.path != activeSessionFile) onOpenConversation(item)
                        onOpened()
                    },
                    onRename = {
                        renameTarget = it
                        renameUp = true
                    },
                    onTogglePin = ::togglePinned,
                    onDelete = {
                        deleteTargets = listOf(it)
                        deleteUp = true
                    },
                    bottomInset = navigationInset,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    listState = listState,
                )
            }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(LIST_FADE)
                        .background(
                            // Three stops, like the chat page's own fades: a row that
                            // crosses this loses most of its contrast in the first third
                            // instead of appearing to be cut by the field's edge. The
                            // colour is the panel's `background`, not the card's
                            // `surface`: the panel is the step from the chat card behind
                            // it, and a fade in the card's colour erased that step under
                            // the field.
                            Brush.verticalGradient(
                                0f to MiuixTheme.colorScheme.background,
                                0.45f to MiuixTheme.colorScheme.background.copy(alpha = 0.55f),
                                1f to Color.Transparent,
                            ),
                        ),
                )
            }
        }

        // The one control at the drawer's foot, and the only way into the settings now
        // that the bar that carried four destinations is gone. A pill rather than an icon:
        // it floats over a list it does not belong to, and the word is what keeps a
        // floating gear from reading as another row action. Its width follows the word —
        // `設定` and `Settings` are not the same length, and a fixed width would make one
        // language's label touch the edges.
        //
        // **Centred on the panel, not hung at its gutter.** At the leading edge the pill
        // shared a column with the row marks under it, so it read as the last row's
        // action rather than as the drawer's own footer; the foot of a panel is a
        // centred control, and the list's gutter is the rows' rule. The list's own
        // end padding is unchanged, so the centred pill still clears the last row when
        // the list is scrolled to its end.
        SettingsPill(
            label = text.settings.title,
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // The pill floats over the panel's foot, so the strip is its own margin:
                // it keeps the control above the gesture handle now that the panel
                // reaches the window's last pixel.
                .padding(bottom = 40.dp + navigationInset),
        )
    }

    // All three are composed for as long as the page is; `show` is what changes, so the
    // panel animates out with its subject still on screen (see the state block above).
    RenameDialog(
        show = renameUp,
        text = text,
        initial = renameTarget?.let { if (it.hasExplicitName) it.title else "" }.orEmpty(),
        isActive = renameTarget != null && renameTarget?.path == activeSessionFile,
        onDismiss = { renameUp = false },
        onConfirm = { name ->
            val target = renameTarget ?: return@RenameDialog
            renameUp = false
            scope.launch {
                session.renameSession(target, name)
                    .onFailure {
                        toasts?.show(text.sessions.renameFailed(it.message.orEmpty()))
                    }
                reload++
            }
        },
    )

    DeleteConfirm(
        show = deleteUp,
        text = text,
        targets = deleteTargets,
        onDismiss = { deleteUp = false },
        onConfirm = {
            val targets = deleteTargets
            deleteUp = false
            exitSelection()
            scope.launch {
                // A live conversation owns its file while it runs: pi appends to it, so
                // unlinking it would leave a process writing into nothing. The close is
                // asked for first — and a conversation that is *answering* refuses it, in
                // which case the delete is refused with the sentence that says why.
                val live = targets.mapNotNull { session.host.sessionFor(it.path) }
                if (live.any { it.turnInFlight }) {
                    toasts?.show(text.sessions.switchWhileWorking)
                    reload++
                    return@launch
                }
                live.forEach { session.host.closeConversation(it, interrupt = true) }
                session.deleteSessions(targets)
                    .onSuccess { removed ->
                        if (removed < targets.size) {
                            toasts?.show(
                                text.sessions.partiallyDeleted(removed, targets.size),
                            )
                        }
                    }
                    .onFailure { error ->
                        // A refusal because the agent is mid-turn is not a disk
                        // failure, and the sentence for it is in the catalogs;
                        // `deleteFailed` would print a process-language reason
                        // ("the agent is still working on this session") the
                        // reader cannot act on.
                        toasts?.show(
                            if (error is TurnInFlightException) {
                                text.sessions.switchWhileWorking
                            } else {
                                text.sessions.deleteFailed(error.message.orEmpty())
                            },
                        )
                    }
                reload++
            }
        },
    )
}

/**
 * The conversations, grouped, with the pinned group first.
 *
 * Pinned and unpinned are two groups under two small headings rather than one flat list
 * with a glyph stuck on every pinned title: the grouping says it once, at the heading,
 * where a mark on every row fought the title for attention. A pinned row also carries a
 * pin of its own, because the heading scrolls away and the mark on the row does not.
 *
 * A row is a flat run of text on the panel's own surface. The pinned run used to be
 * tinted in the scheme's secondary container to say where it began, and that tint is
 * gone with the outlines: the one fill in the list now belongs to the conversation on
 * screen, and a second meaning for the same colour is a list with two highlights.
 *
 * **A row paints no state while it is held, and that is the same rule read from the
 * other end.** The library's press indication is a light that follows the *finger*, and
 * the finger is not what this list is about: a hold opens a menu that stays after the
 * finger leaves, with a dim over the list, so the light it painted lit nothing and said
 * the wrong thing — it made the row the subject of the press at the moment the press
 * stopped being the subject. Measured on a hold that opened a menu, sampled from a
 * `screencap` at density 2.625: the held row read rgb(22) where its neighbours read
 * rgb(14) under the panel's dim, and fell back to 14 when the long press was accepted,
 * so the panel was lit for part of its own opening and dark for the rest. The hold is
 * acknowledged where the transcript acknowledges one (§7.3): by the platform's
 * long-press haptic, at the moment the menu is decided. A plain tap is answered by the
 * conversation it opens, which is a whole page of feedback rather than a flash.
 *
 * The unpinned heading appears only when something above it is pinned. Without that
 * break a mixed list has no way to say where the pinned group ends, and with it on an
 * all-unpinned list the page would open under a heading that states the obvious.
 */
@Composable
private fun ConversationList(
    visible: List<PiAgentSession.SessionSummary>,
    /** The list's scroll position, owned by the root so a rebuild keeps it. */
    listState: LazyListState,
    text: Strings,
    workspaces: List<Workspace>,
    defaultPath: String,
    selecting: Boolean,
    selected: Set<String>,
    snippets: Map<String, String>,
    /** The saved conversations that are live processes right now. */
    streamingPaths: Set<String>,
    unreadPaths: Set<String>,
    /** The session file on screen: its row is the one drawn filled. */
    currentPath: String?,
    onToggle: (String) -> Unit,
    onOpen: (PiAgentSession.SessionSummary) -> Unit,
    onRename: (PiAgentSession.SessionSummary) -> Unit,
    onTogglePin: (PiAgentSession.SessionSummary) -> Unit,
    onDelete: (PiAgentSession.SessionSummary) -> Unit,
    /** The navigation strip the panel now draws through, as the list's own end padding. */
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val pinnedVisible = visible.filter { it.pinned }
    val otherVisible = visible.filterNot { it.pinned }
    // One group per workspace, the most recent group first, and the conversations whose
    // file recorded no directory last. The pinned shelf stays above all of them: pinning is
    // a decision about a conversation that crosses workspaces, and burying a pinned row
    // inside the group it happens to come from is how it stops being findable.
    val groups = sessionGroups(
        sessions = otherVisible,
        workspaces = workspaces,
        defaultPath = defaultPath,
        unknownLabel = text.sessions.groupUnknown,
        // The folder's own name, without the composer chip's `（默认）` marker: a
        // heading is read once at the top of a run of rows, and the marker is about a
        // choice the reader made elsewhere. Two workspaces with the same name are told
        // apart by their conversations, not by which of them is the default.
        chip = { label, _ -> label },
    )

    LazyColumn(
        state = listState,
        // The drawer's body, so it takes the panel's elastic end: the list is short after
        // the first conversation and long after a month of them, and the first case is the
        // one Compose's own effect skips. See `piOverScroll`.
        modifier = modifier.piOverScroll(),
        // Room at the end for the settings pill, which floats over the last rows: without
        // it the final conversation is the one under the control that leaves the list. The
        // strip is added here rather than around the panel: the list draws through it, and
        // its rows have to be able to scroll clear of the gesture handle.
        contentPadding = PaddingValues(bottom = LIST_BOTTOM + bottomInset),
        // A row is a run of text with its own inset now, not a card with an outline, so the
        // gap between two of them is the room the rows already carry — 2dp keeps two
        // highlighted rows from merging into one when they happen to be neighbours.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (pinnedVisible.isNotEmpty()) {
            item(key = "heading-pinned") {
                GroupHeading(text = text.sessions.pinned)
            }
            items(pinnedVisible, key = { it.path }) { item ->
                ConversationRow(
                    item = item,
                    text = text,
                    selecting = selecting,
                    checked = item.path in selected,
                    snippet = snippets[item.path],
                    streaming = item.path in streamingPaths,
                    unread = item.path in unreadPaths,
                    pinned = true,
                    current = item.path == currentPath,
                    onToggleChecked = { onToggle(item.path) },
                    onOpen = { onOpen(item) },
                    onRename = { onRename(item) },
                    onTogglePin = { onTogglePin(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
        groups.forEach { group ->
            item(key = "workspace-" + group.key) {
                GroupHeading(text = text.sessions.groupTitle(group.label, group.sessions.size))
            }
            items(group.sessions, key = { it.path }) { item ->
                ConversationRow(
                    item = item,
                    text = text,
                    selecting = selecting,
                    checked = item.path in selected,
                    snippet = snippets[item.path],
                    streaming = item.path in streamingPaths,
                    unread = item.path in unreadPaths,
                    pinned = false,
                    current = item.path == currentPath,
                    onToggleChecked = { onToggle(item.path) },
                    onOpen = { onOpen(item) },
                    onRename = { onRename(item) },
                    onTogglePin = { onTogglePin(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

/**
 * A group's heading: a small word at the list's own gutter.
 *
 * The library's `SmallTitle` measures 28dp on each side, which is the settings pages'
 * gutter and not this list's — a heading two steps in from the rows it heads reads as
 * another row. Drawn here so the heading and the row titles below it begin on one column.
 */
@Composable
private fun GroupHeading(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HEADING_GUTTER, end = HEADING_GUTTER)
            .padding(top = 10.dp, bottom = 4.dp),
    )
}


/** One workspace's conversations, as a heading draws them. */
internal data class SessionGroup(
    /** Stable across a recomposition and unique in the list: the workspace's path. */
    val key: String,
    val label: String,
    val sessions: List<PiAgentSession.SessionSummary>,
)

/**
 * The conversations by the workspace they came from, most recently used group first.
 *
 * The grouping key is the `cwd` pi recorded in the session file, which is the only place
 * a saved conversation says where it was started — so this works for a conversation from
 * before the app had a workspace picker too, and for one started by a `pi` typed in the
 * terminal.
 *
 * A directory that is no longer a workspace still gets a group, labelled by its last path
 * segment: a conversation from a folder the reader deleted is one they may still want to
 * read, and hiding it because its workspace left the list would be a history page that
 * loses conversations. A file with no recorded directory goes to the one group with no
 * path at all.
 *
 * Order: the group holding the most recently modified conversation first. Within a group
 * the order is the list's own — pinned removed, then by modification time — because the
 * caller hands them over already sorted.
 */
internal fun sessionGroups(
    sessions: List<PiAgentSession.SessionSummary>,
    workspaces: List<Workspace>,
    defaultPath: String,
    unknownLabel: String,
    chip: (String, Boolean) -> String,
): List<SessionGroup> {
    if (sessions.isEmpty()) return emptyList()
    val byPath = workspaces.associateBy { it.path }
    val groups = LinkedHashMap<String, MutableList<PiAgentSession.SessionSummary>>()
    for (session in sessions) {
        val path = session.workspace.orEmpty()
        groups.getOrPut(path) { mutableListOf() }.add(session)
    }
    return groups.map { (path, items) ->
        val workspace = byPath[path]
        val label = when {
            path.isBlank() -> unknownLabel
            // The name the composer's *menu* gives it, `（默认）` included: the headings
            // are a list, and a list is where the mark belongs. The chip above the composer
            // is the one place that says the folder's own name alone.
            workspace != null -> chip(workspace.label, path == defaultPath)
            else -> java.io.File(path).name.ifBlank { path }
        }
        SessionGroup(
            key = path.ifBlank { "unknown" },
            label = label,
            sessions = items,
        )
    }.sortedWith(
        compareByDescending<SessionGroup> { group ->
            group.sessions.maxOfOrNull { it.modifiedAt } ?: 0L
        },
    ).sortedBy { group ->
        // The group with no recorded directory is last whatever its timestamps: it is the
        // one group a reader cannot name, and sorting it to the top would push the
        // workspaces they recognise below a heading that says "somewhere".
        if (group.key == "unknown") 1 else 0
    }
}

/**
 * One conversation as the question it was.
 *
 * A flat row rather than a card: the list is a column of the reader's own questions,
 * and an outline around each one made a page of framed boxes out of what the reference
 * drawing shows as text on a surface. The only row with a fill is the conversation on
 * screen — that is the one fact the list carries about the page behind it, and colour is
 * how it is said. The row is one line: a title, when it was, and the marks that say
 * "live" and "pinned", so the list is one rhythm whatever the content is.
 *
 * The row is a `combinedClickable` box rather than the library's `Card` because it has to
 * be: `Card` draws the container this design removes, and what the row still needs from a
 * card is exactly its two gestures — a tap to open and a hold for the three per-row
 * actions. Both are on the box, and the hold is labelled with [Strings.Sessions.actions]
 * rather than left an unnamed gesture, which is what a screen reader reads out for it.
 *
 * The box carries **no** press indication: the fill on this row says "this is the
 * conversation behind the drawer", not "this row is under a finger", and a hold that
 * opens a menu leaves the finger behind while the menu stays. [ConversationList]'s KDoc
 * has the measurement; the hold answers with the long-press haptic instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: PiAgentSession.SessionSummary,
    text: Strings,
    selecting: Boolean,
    checked: Boolean,
    snippet: String?,
    /** Whether an answer is being written in this conversation right now. */
    streaming: Boolean,
    /** Whether an answer finished here while the reader was somewhere else. */
    unread: Boolean,
    pinned: Boolean,
    /** Whether this is the conversation the page behind the drawer is showing. */
    current: Boolean,
    onToggleChecked: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    // Locale.getDefault() follows the interface language because the root applies it,
    // so a date under a Chinese interface reads as Chinese.
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // The menu is the row's own state: it is anchored on this row, it dies with the row,
    // and a row that scrolls out of the list with its menu open takes the menu with it.
    var actionsUp by remember(item.path) { mutableStateOf(false) }
    // The hold's own acknowledgement. The row paints no press state (see below), so
    // without this the 500 ms before the menu appears is a hold with no answer at all —
    // the same reason the transcript answers with this haptic (§7.3).
    val haptics = LocalHapticFeedback.current
    val hold = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        actionsUp = true
    }

    Box {
        Box(
            Modifier
                .fillMaxWidth()
                // The margin is outside the fill: a highlighted row has to be inset from
                // the panel's edge to read as a highlight rather than as a band across it.
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(ROW_CORNER))
                .background(
                    if (current) PiTone.inset() else Color.Transparent,
                )
                .semantics {
                    onLongClick(label = text.sessions.actions) {
                        // Nothing to hold for while the list is in selection mode: the
                        // tap is already the checkbox, and a second way to reach the same
                        // three actions there would be a menu over a multi-select.
                        if (!selecting) hold()
                        true
                    }
                }
                // No press indication, on purpose — [ConversationList]'s KDoc has the
                // measurement. A fill here can only mean "this is the conversation
                // behind the drawer"; a light that follows the finger is the one thing
                // the row must not wear when a menu has just opened over it, because it
                // is painted by the touch and ends when the touch does, while the menu
                // is what is on screen. The hold is acknowledged by the haptic.
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (selecting) onToggleChecked() else onOpen() },
                    onLongClick = if (selecting) null else hold,
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { text.sessions.emptyTitle },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        color = MiuixTheme.colorScheme.onSurface,
                        // One line, always: the list is a rhythm of equal rows, and a
                        // title that could take two made some rows two lines and some
                        // one. The ellipsis carries what does not fit.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    snippet?.let { match ->
                        Text(
                            text = match,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            // Two lines, and only while searching: every result carries
                            // a snippet, so the list stays one rhythm in each mode.
                            // Capped so a match deep in a long reply cannot open a tall
                            // row.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .semantics {
                                    contentDescription = text.sessions.contentMatch(match)
                                },
                        )
                    }
                }
                // The two facts a row carries about its own state, in the order they are
                // read: pinned (a decision the reader made) then live (a process that is
                // answering). Both are small and in the trailing lane, which is where the
                // eye goes for meta rather than for the question.
                if (pinned) {
                    Icon(
                        imageVector = MiuixIcons.Pin,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (streaming || unread) {
                    // A dot is a colour and no words, so it carries its meaning as a
                    // description: whether a row is answering right now, or has answered
                    // while the reader was elsewhere, is the one fact here a screen reader
                    // would otherwise never say.
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (streaming) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    PiTone.done()
                                },
                            )
                            .semantics {
                                contentDescription = if (streaming) {
                                    text.sessions.running
                                } else {
                                    text.sessions.unread
                                }
                            },
                    )
                }
                Text(
                    text = formatter.format(Date(item.modifiedAt)),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                // The selection box is the row's *trailing* control, not its leading
                // one: on the leading edge it displaces every title by its own width the
                // moment selection begins, which re-flows the whole list under the
                // reader's finger.
                if (selecting) {
                    Checkbox(
                        state = ToggleableState(checked),
                        onClick = { onToggleChecked() },
                    )
                }
            }
        }

        // The three things a conversation can have done to it, as a floating menu at
        // the row that was held (§7.1): three short actions on one conversation do not
        // need a panel from the bottom edge, and the row is the thing the question is
        // about — the menu lands on it instead of covering the list it came from.
        //
        // The label names what can be done; there is no title inside the menu and no
        // cancel row, because a menu is dismissed by the scrim, the back gesture or a
        // tap outside. The gate inside each item keeps a second tap — aimed at the page
        // behind a menu that is already leaving — from running an action twice.
        OverlayDropdownPopup(
            entry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = text.sessions.rename,
                        icon = { modifier ->
                            Icon(MiuixIcons.Rename, contentDescription = null, modifier = modifier)
                        },
                        onClick = {
                            if (actionsUp) {
                                actionsUp = false
                                onRename()
                            }
                        },
                    ),
                    DropdownItem(
                        text = if (item.pinned) text.sessions.unpin else text.sessions.pin,
                        icon = { modifier ->
                            Icon(
                                imageVector = if (item.pinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                                contentDescription = null,
                                modifier = modifier,
                            )
                        },
                        onClick = {
                            if (actionsUp) {
                                actionsUp = false
                                onTogglePin()
                            }
                        },
                    ),
                    DropdownItem(
                        text = text.sessions.delete,
                        icon = { modifier ->
                            Icon(MiuixIcons.Delete, contentDescription = null, modifier = modifier)
                        },
                        onClick = {
                            if (actionsUp) {
                                actionsUp = false
                                onDelete()
                            }
                        },
                    ),
                ),
            ),
            show = actionsUp,
            onDismiss = { actionsUp = false },
            onDismissFinished = {},
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
        )
    }
}

/**
 * While the content scan is running: the sentence, and a mark that it is still moving.
 *
 * A scan can take a second on a long history, and a still page with no rows is
 * indistinguishable from a search that found nothing — which is the claim the old
 * version made for as long as the scan ran.
 */
@Composable
private fun SearchingNote(label: String) {
    Row(
        modifier = Modifier
            .squircleSurface(
                color = MiuixTheme.colorScheme.secondaryContainer,
                cornerRadius = 50.dp,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The empty state: a heading and the sentence that says what is missing.
 *
 * Centred like the other three states of the list (`loading` and `searching` are centred
 * `Box`es, and the Files page's note is the same shape). It used to be a bare `Column`
 * under a `weight(1f)`, which is a Column that starts at the top — so "no conversations"
 * sat against the search field while "searching" sat in the middle of the page, and the
 * heading jumped between the two as the state changed.
 */
@Composable
private fun SessionsNote(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The name a conversation is filed under.
 *
 * A window dialog of Miuix's own, with the library's text field inside it: the field
 * is a rectangle with the content radius while a search field is a capsule, which is
 * the one distinction that keeps a typed name from reading as a query.
 *
 * The dialog stays composed and [show] opens it, so answering it slides the panel away
 * instead of removing the window mid-animation. The field therefore needs to be reset
 * by the *open* rather than by the composition: [LaunchedEffect] on [show] puts [initial]
 * back when the panel comes up, and leaves the text alone while it slides out.
 */
@Composable
private fun RenameDialog(
    show: Boolean,
    text: Strings,
    initial: String,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    LaunchedEffect(show) {
        if (show) value = initial
    }

    WindowDialog(
        show = show,
        title = text.sessions.renameTitle,
        // The note under the field says what naming the conversation the agent is
        // currently writing to does, which is the one case where the answer is not
        // obvious: the name is written into the file being appended to right now.
        summary = if (isActive) text.sessions.renameActiveNote else text.sessions.renameClosedNote,
        onDismissRequest = onDismiss,
    ) {
        TextField(
            cornerRadius = PiCorner.radius,
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = text.sessions.renameLabel,
            useLabelAsPlaceholder = true,
            singleLine = true,
            // No focus ring, and the caret stated instead: see `piFieldColors`.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
        )
        // The app's one dialog-button row — see `PiDialogAnswers`. This pair was
        // already the right *shape* and was the model for it; it is the shared
        // composable now so the two cannot drift.
        PiDialogAnswers(
            dismiss = text.sessions.cancel,
            onDismiss = onDismiss,
            confirm = text.sessions.rename,
            onConfirm = { onConfirm(value) },
            // Nothing to rename to while the field is empty: the confirm is disabled
            // rather than refusing the tap, which is the one state the library's
            // disabled fill exists for.
            confirmEnabled = value.isNotBlank(),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The delete question, for one conversation or a selection of them.
 *
 * A list of what is about to go, one bullet each, because a bulk delete on this page
 * is the one action that cannot be taken back — the session file is removed outright,
 * with no trash behind it.
 *
 * ## Why this dialog is built here and not from `PiConfirm`
 *
 * `PiConfirm` hands its body to the dialog's *summary*, which is a `Text` with no line
 * limit and no scroll, inside a dialog that applies a height cap only on a large
 * screen (`DialogContentLayout` caps at two-thirds of the window when the window is at
 * least 840×480, and leaves a phone uncapped and bottom-anchored). A bulk delete is one
 * select-all away from a hundred bullets, and a hundred bullets in that slot grow the
 * panel off the top of the window with the first names unreachable — the panel is
 * bottom-aligned, so the buttons survive and the question does not. The body is
 * therefore a bounded, scrolling column here: three hundred and twenty dp is about
 * sixteen conversations before the scroll starts, which is the difference between a
 * long list and a broken dialog.
 *
 * The other asks on this page keep [PiConfirm]: they are one sentence, and a sentence
 * cannot overflow. This one is the only question whose body is a *list*.
 */
@Composable
private fun DeleteConfirm(
    show: Boolean,
    text: Strings,
    targets: List<PiAgentSession.SessionSummary>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val count = targets.size
    val first = targets.firstOrNull()?.title?.ifBlank { text.sessions.emptyTitle }.orEmpty()
    // A dialog window is full-screen (`usePlatformDefaultWidth = false`), so this is
    // the screen's height. The body takes the fixed ceiling on a portrait phone and a
    // share of the window when the window is short — a landscape phone is 360dp tall,
    // where a 320dp body plus a title and two buttons is taller than the window that
    // draws it.
    val bodyHeight = minOf(
        DELETE_BODY_HEIGHT,
        LocalWindowInfo.current.containerDpSize.height * DIALOG_BODY_SHARE,
    )

    WindowDialog(
        // Visibility is a flag of its own, and the target list is *not* cleared on
        // confirm: shown from `count > 0` on the live list, the panel spent its exit
        // animation reading "Delete 0 conversations" — the question changed while it was
        // leaving. The list survives the close, and the next delete overwrites it.
        show = show,
        title = if (count == 1) {
            text.sessions.deleteTitleOne
        } else {
            text.sessions.deleteTitleMany(count)
        },
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bodyHeight)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (count == 1) {
                    text.sessions.deleteBodyOne(first)
                } else {
                    text.sessions.deleteBodyMany
                },
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (count > 1) {
                // The list is the point of the dialog — it is what "these are deleted"
                // means — but each line is a title, and a title is one line where it is
                // read in the list above. Two here is already generous, and it is what
                // keeps one long title from turning the scroll into a page about a
                // single conversation.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    targets.forEach { item ->
                        Text(
                            text = "• ${item.title.ifBlank { text.sessions.emptyTitle }}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // The app's one dialog-button row, with the delete answer in the error colour
        // — the same `destructive` flag `PiConfirm` takes. This dialog is built here
        // rather than from `PiConfirm` because its body is a *list* of what is about to
        // go, which `PiConfirm`'s one sentence cannot hold; the answers are still the
        // shared row, so the two dialogs cannot disagree about what a confirmation looks
        // like.
        PiDialogAnswers(
            dismiss = text.sessions.cancel,
            onDismiss = onDismiss,
            confirm = text.sessions.delete,
            onConfirm = onConfirm,
            destructive = true,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * The question a session move asks while the agent is answering.
 *
 * ## Why it is a dialog and not the notice it replaced
 *
 * pi ends the running turn as the first step of `switch_session` and `new_session`
 * (`RuntimeHost.teardownCurrent` starts with `await this.session.abort()`), so the
 * move the reader tapped cannot be made without stopping the answer. There are exactly
 * two honest answers — "switch anyway" and "not now" — and the page used to pick one
 * of them on the reader's behalf: a notice saying the move would interrupt, and no
 * move. That is a tap that produces a sentence rather than a result, which is the
 * report. The dialog does not invent a third answer; it puts the choice where the
 * reader can make it. The confirm button says what it does rather than `OK`, because
 * "interrupt and switch" is the whole of the warning in two words.
 *
 * One composable rather than one per caller: the two callers ask the same question.
 * The history list's rows are one, and the chat page's own new-session (`/new`, and
 * the header's `+`) is the other — pi implements both with the same abort, so a second
 * dialog written beside the second call site would be the same dialog twice.
 *
 * [show] rather than a dialog raised by composing it: the caller keeps this composed and
 * flips the flag, so the panel animates out instead of the window disappearing when the
 * answer clears the target it was built from.
 */
@Composable
internal fun InterruptTurnDialog(
    show: Boolean,
    text: Strings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PiConfirm(
        show = show,
        title = text.sessions.switchInterruptTitle,
        text = text.sessions.switchInterruptBody,
        confirm = text.sessions.switchInterruptConfirm,
        dismiss = text.common.cancel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        // Switching ends the running answer, which is the irreversible half of the
        // question — the confirm label is the ceremony the old notice lacked.
        destructive = true,
    )
}

/** How long the search field must be quiet before the content scan starts. */
private const val SEARCH_DEBOUNCE_MS = 200L

/** One character matches almost every conversation and is not worth the scan. */
private const val MIN_SEARCH_CHARS = 2

/**
 * How tall the delete dialog's body may grow before it scrolls, and the share of a
 * short window it may take instead.
 *
 * The dialog itself caps nothing on a phone (Miuix caps its content only on a large
 * screen), so this is the whole of the bound. A body line is about 19dp: three hundred
 * and twenty is sixteen conversations at default text size, and it leaves the title and
 * the two buttons inside the window on a portrait phone. A landscape phone is only
 * ~360dp tall, so the body is also held to just under half of it — where the title and
 * the buttons then take the rest.
 */
private val DELETE_BODY_HEIGHT = 320.dp

/** The share of a short window the delete dialog's body may take. */
private const val DIALOG_BODY_SHARE = 0.45f

/**
 * The gap under the last row.
 *
 * The drawer's own furniture: it has to be tall enough for the settings pill, which
 * floats over the end of the list — 16dp put the pill on top of the last conversation
 * and made the final row unreadable, and the pill plus its own margin is about 72dp.
 */
private val LIST_BOTTOM = 112.dp

/** How far the drawer's list fades into the field above it. */
private val LIST_FADE = 30.dp

/** The drawer's gutter: the heading, the field and the group headings share it. */
private val HEADING_GUTTER = 20.dp

/** The selection controls in the drawer's heading, and their glyphs. */
private val SELECTION_BUTTON = 36.dp
private val SELECTION_GLYPH = 20.dp

/** A row's corner, when it is the highlighted one. */
private val ROW_CORNER = 14.dp

/**
 * The one control at the drawer's foot: a pill with the gear and the word.
 *
 * The reader's way into the settings, and the only one now that the four-destination bar
 * is gone. It is a pill rather than a floating icon because it sits over a list of rows
 * that are themselves tappable: a bare disc there is a fourth row action with no label,
 * while a word says "this leaves the list". Its width follows the word, so `設定` and
 * `Settings` both fit without a fixed measure and without the label touching either end.
 *
 * **The same object as the chat page's capsules**, because it is the same kind of thing:
 * a control that floats over a page rather than a row on it. It carries [PiGlass.fill]
 * and [glassLift] — the raised near-black the header's circle and pill wear, and the
 * hand-drawn blurred shadow under them — instead of an opaque container and an elevation
 * shadow. Those two were the pill's own answer to "a fill alone reads as a highlighted
 * row"; with the glass pair it reads as the header's kind of object, which is what a
 * reader who has just left that page expects a floating control to look like.
 */
@Composable
private fun SettingsPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            // After the lift, so the blurred silhouette is drawn *under* the fill and
            // clipped to nothing of its own: the same order the header's capsules use
            // (`glassLift`, `clip`, `background`).
            .glassLift(shape)
            .clip(shape)
            .background(PiGlass.fill())
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Settings,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
