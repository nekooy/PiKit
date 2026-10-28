package pi.kit.mob.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.TurnInFlightException
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Management for pi's saved conversations: reopen, rename, pin, delete.
 *
 * Shown in place of the chat rather than in a dialog because the list is the whole
 * point of the screen — a dialog leaves no room for a search field, and multi-select
 * delete in a dialog is a worse experience than a real page.
 *
 * Titles come from the session file: pi does not generate names, so an unnamed
 * session is labelled with its first user message, which is exactly what pi's own
 * session picker does. That is also the reason the rows are drawn as questions rather
 * than as records: for most of the list the title *is* the reader's own words.
 *
 * The title is one line that never changes ("Conversations") and the subtitle is the
 * list's own state — how many are saved, how many are selected, whether a search is
 * still reading. §9.2 has why the header is one fixed strip rather than a bar that
 * grows, collapses or moves as the list does; it is Miuix's small bar now, and the
 * shape that chapter describes is the same one.
 */
@Composable
fun SessionsScreen(
    session: PiAgentSession,
    onBack: () -> Unit,
    onOpened: () -> Unit,
) {
    val text = strings
    var summaries by remember { mutableStateOf<List<PiAgentSession.SessionSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
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
    var renameTarget by remember { mutableStateOf<PiAgentSession.SessionSummary?>(null) }
    var renameUp by remember { mutableStateOf(false) }
    // A conversation the user tapped while the agent was answering.
    var switchTarget by remember { mutableStateOf<PiAgentSession.SessionSummary?>(null) }
    var switchUp by remember { mutableStateOf(false) }
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
        loading = true
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

    PiPage(
        title = text.sessions.title,
        subtitle = when {
            selecting -> text.sessions.selected(selected.size)
            loading -> text.sessions.loading
            // The scan has no progress to report — it is one file at a time and
            // the count is not known — so the subtitle says what is happening
            // rather than how far along it is. Without it a slow scan is
            // indistinguishable from an empty result.
            searching -> text.sessions.searching
            else -> text.sessions.saved(summaries.size)
        },
        // The back arrow belongs at the start of the bar, which is where the
        // platform convention puts it and where `PiPage` draws it. It used to be the
        // last item in the action row, i.e. next to Delete, which is both
        // unconventional and a hazard.
        onBack = onBack,
        actions = {
            if (selecting) {
                // Scoped to `visible` rather than every saved conversation: a search
                // that has narrowed the page to three rows is a list of three, and a
                // button that silently selected rows nobody can see would make the
                // next delete a surprise. Tapping again drops exactly those rows, so a
                // selection built up under an earlier filter is not thrown away by
                // clearing one list's worth of it.
                val visiblePaths = visible.map { it.path }.toSet()
                val allVisibleSelected =
                    visiblePaths.isNotEmpty() && visiblePaths.all { it in selected }
                IconButton(
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
                    // do", which the tint says and the spoken description below says
                    // out loud.
                    Icon(
                        imageVector = MiuixIcons.SelectAll,
                        contentDescription = if (allVisibleSelected) {
                            text.sessions.deselectAll
                        } else {
                            text.sessions.selectAll
                        },
                        tint = if (allVisibleSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onBackground
                        },
                    )
                }
                IconButton(
                    onClick = {
                        deleteTargets = summaries.filter { it.path in selected }
                        deleteUp = true
                    },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(MiuixIcons.Delete, contentDescription = text.sessions.deleteSelected)
                }
                IconButton(onClick = { exitSelection() }) {
                    Icon(MiuixIcons.Close, contentDescription = text.sessions.cancelSelection)
                }
            } else {
                IconButton(
                    onClick = { selecting = true },
                    enabled = summaries.isNotEmpty(),
                ) {
                    Icon(MiuixIcons.Tasks, contentDescription = text.sessions.select)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Directly under the bar and not inside a card: the field filters the list
            // under it, and a frame of its own would put a third border between the
            // bar and the rows it acts on. The library's `SearchBar` is not used here
            // for the same reason it is not used on the Files page: its expanded state
            // is a results surface, and this field narrows the list in place.
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                label = text.sessions.searchHint,
                useLabelAsPlaceholder = true,
                singleLine = true,
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

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                // A scan that has not answered yet is not an empty result. The list
                // used to say "nothing matches" for as long as the scan ran, which is
                // a claim about a search that was still reading files.
                searching && visible.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
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
                    modifier = Modifier.weight(1f),
                )

                else -> ConversationList(
                    visible = visible,
                    text = text,
                    selecting = selecting,
                    selected = selected,
                    snippets = snippets,
                    onToggle = { path ->
                        selected = if (path in selected) selected - path else selected + path
                    },
                    onOpen = { item ->
                        // A turn in flight makes this a question rather than a switch:
                        // pi aborts the running answer as the first step of its own
                        // `switch_session` (`RuntimeHost.teardownCurrent`), so the tap
                        // would stop an answer the reader may still want. See
                        // [InterruptTurnDialog].
                        if (session.turnInFlight) {
                            switchTarget = item
                            switchUp = true
                        } else {
                            session.switchSession(item)
                            onOpened()
                        }
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
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

    InterruptTurnDialog(
        show = switchUp,
        text = text,
        onDismiss = { switchUp = false },
        onConfirm = {
            val target = switchTarget ?: return@InterruptTurnDialog
            switchUp = false
            session.switchSession(target, interrupt = true)
            onOpened()
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
 * Pinned and unpinned are two groups under two small titles rather than one flat list
 * with a glyph stuck on every pinned title: the grouping says it once, at the heading,
 * where a mark on every row fought the title for attention.
 *
 * The pinned rows are drawn in the scheme's secondary container — one run of tinted
 * rows under [Strings.Sessions.pinned] rather than one lifted panel. A panel is what
 * the first answer was (a half-strength container behind the whole group), and it
 * cannot survive this rewrite: a row is a `Card`, which is the one container in the
 * library that carries both a click and a hold, and a card inside a card is a frame
 * inside a frame. The tint says the same thing at the row's own edge, and the heading
 * above the run says where the group begins.
 *
 * The unpinned heading appears only when something above it is pinned. Without that
 * break a mixed list has no way to say where the pinned group ends, and with it on an
 * all-unpinned list the page would open under a heading that states the obvious.
 */
@Composable
private fun ConversationList(
    visible: List<PiAgentSession.SessionSummary>,
    text: Strings,
    selecting: Boolean,
    selected: Set<String>,
    snippets: Map<String, String>,
    onToggle: (String) -> Unit,
    onOpen: (PiAgentSession.SessionSummary) -> Unit,
    onRename: (PiAgentSession.SessionSummary) -> Unit,
    onTogglePin: (PiAgentSession.SessionSummary) -> Unit,
    onDelete: (PiAgentSession.SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinnedVisible = visible.filter { it.pinned }
    val otherVisible = visible.filterNot { it.pinned }

    LazyColumn(
        modifier = modifier,
        // A gap above the navigation bar, so the last conversation is not read
        // against the bar's own edge.
        contentPadding = PaddingValues(bottom = LIST_BOTTOM),
        // The seam between two row cards, the same 2dp the Files listing uses: a row
        // is one object with one outline.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (pinnedVisible.isNotEmpty()) {
            item(key = "heading-pinned") {
                SmallTitle(text = text.sessions.pinned)
            }
            items(pinnedVisible, key = { it.path }) { item ->
                ConversationRow(
                    item = item,
                    text = text,
                    selecting = selecting,
                    checked = item.path in selected,
                    snippet = snippets[item.path],
                    pinned = true,
                    onToggleChecked = { onToggle(item.path) },
                    onOpen = { onOpen(item) },
                    onRename = { onRename(item) },
                    onTogglePin = { onTogglePin(item) },
                    onDelete = { onDelete(item) },
                )
            }
            if (otherVisible.isNotEmpty()) {
                item(key = "heading-recent") {
                    SmallTitle(text = text.sessions.recent)
                }
            }
        }
        items(otherVisible, key = { it.path }) { item ->
            ConversationRow(
                item = item,
                text = text,
                selecting = selecting,
                checked = item.path in selected,
                snippet = snippets[item.path],
                pinned = false,
                onToggleChecked = { onToggle(item.path) },
                onOpen = { onOpen(item) },
                onRename = { onRename(item) },
                onTogglePin = { onTogglePin(item) },
                onDelete = { onDelete(item) },
            )
        }
    }
}

/**
 * One conversation as the question it was.
 *
 * The primary line is the reader's own words — the title pi shows is either the name
 * they gave it or, far more often, their first message — and the second line is when
 * it was and how much followed. A match from the content scan is the third line, and
 * only while searching. The title is one line and ellipsises: a title that could take
 * two made the list a mix of one- and two-line rows, and every row is the same height
 * now.
 *
 * The row is a `Card` and not a `BasicComponent` for one reason: the hold. The
 * library's settings row carries a single click, and this list's per-row actions —
 * rename, pin, delete — are reached by pressing and holding, which `Card` is the
 * container that supports. What separates the two lines here is still weight and
 * colour rather than a box or a glyph, which is what keeps a list of questions one
 * rhythm instead of a table.
 *
 * ## Why the actions are a hold, not a button
 *
 * The row used to carry a trailing `MoreVert`, which was the only thing in it that was
 * not the reader's question, sitting in the 48dp of edge the title wanted. A
 * press-and-hold is the platform's own convention for per-row actions on a list, and
 * it gives the row's right edge back to the text; the same three actions stay
 * reachable, and one of them — delete — is also in the multi-select bar. The long
 * click is labelled with [Strings.Sessions.actions] rather than left an unnamed
 * gesture, which is what a screen reader reads out for it.
 */
@Composable
private fun ConversationRow(
    item: PiAgentSession.SessionSummary,
    text: Strings,
    selecting: Boolean,
    checked: Boolean,
    snippet: String?,
    pinned: Boolean,
    onToggleChecked: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    // Locale.getDefault() follows the interface language because the root applies it,
    // so a date under a Chinese interface reads as Chinese.
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    // The menu is the row's own state: it is anchored on this card, it dies with the
    // row, and a row that scrolls out of the list with its menu open takes the menu
    // with it.
    var actionsUp by remember(item.path) { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .semantics {
                    onLongClick(label = text.sessions.actions) {
                        // Nothing to hold for while the list is in selection mode: the
                        // tap is already the checkbox, and a second way to reach the
                        // same three actions there would be a menu over a multi-select.
                        if (!selecting) actionsUp = true
                        true
                    }
                },
            // The pinned group's tint, painted at the row's own edge — see
            // [ConversationList] for why it is not one panel any more.
            colors = if (pinned) {
                CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.secondaryContainer,
                    contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                CardDefaults.defaultColors()
            },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = { if (selecting) onToggleChecked() else onOpen() },
            onLongPress = if (selecting) null else { { actionsUp = true } },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selecting) {
                    Checkbox(
                        state = ToggleableState(checked),
                        onClick = { onToggleChecked() },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { text.sessions.emptyTitle },
                        style = MiuixTheme.textStyles.headline1,
                        color = MiuixTheme.colorScheme.onSurface,
                        // One line, always: the list is a rhythm of equal rows, and a
                        // title that could take two made some rows two lines and some
                        // one. The ellipsis carries what does not fit.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(formatter.format(Date(item.modifiedAt)))
                            append(" · ")
                            append(text.sessions.messageCount(item.messageCount))
                            if (!item.hasExplicitName) append(" · ").append(text.sessions.untitled)
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    snippet?.let { match ->
                        Text(
                            text = match,
                            style = MiuixTheme.textStyles.body2,
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
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = text.sessions.renameLabel,
            useLabelAsPlaceholder = true,
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = text.sessions.cancel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = text.sessions.rename,
                onClick = { onConfirm(value) },
                modifier = Modifier.weight(1f),
                enabled = value.isNotBlank(),
            )
        }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = text.sessions.cancel,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = text.sessions.delete,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                // Red, like every other confirmation that cannot be taken back: the
                // session file is deleted outright. This is the colour `PiConfirm`'s
                // `destructive` draws; this dialog builds its own button, so it asks
                // for it directly. See [InterruptTurnDialog] below for the same
                // treatment of a different irreversible move.
                colors = ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                ),
            )
        }
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
 * The page's own furniture, not the bar's height: the bar is the root scaffold's, and
 * this is the space that keeps a row from being read against its edge.
 */
private val LIST_BOTTOM = 16.dp
