package pi.kit.mob.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.TurnInFlightException
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.components.SheetHost
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiEmptyState
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSearchField
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetRow
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiTextField
import pi.kit.mob.ui.design.PiStatePill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Management for pi's saved conversations: reopen, rename, pin, delete.
 *
 * Shown in place of the chat rather than in a dialog because the list is the
 * whole point of the screen — a dialog leaves no room for a search field, and
 * multi-select delete in a dialog is a worse experience than a real page.
 *
 * Titles come from the session file: pi does not generate names, so an unnamed
 * session is labelled with its first user message, which is exactly what pi's
 * own session picker does. That is also the reason the rows are drawn as
 * questions rather than as records: for most of the list the title *is* the
 * reader's own words.
 *
 * The title is one line that never changes ("Conversations") and the subtitle is the
 * list's own state — how many are saved, how many are selected, whether a search is
 * still reading. §9.2 has why the header is one fixed strip and not one of Material's
 * app bars.
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
    var renaming by remember { mutableStateOf<PiAgentSession.SessionSummary?>(null) }
    var confirmingDelete by remember { mutableStateOf<List<PiAgentSession.SessionSummary>>(emptyList()) }
    // A conversation the user tapped while the agent was answering.
    var pendingSwitch by remember { mutableStateOf<PiAgentSession.SessionSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var matches by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var matchedQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheets = LocalSheetHost.current

    LaunchedEffect(reload) {
        loading = true
        summaries = session.listSessions()
        loading = false
    }

    // The content scan is expensive (a parse per message line, on conversations
    // that can be megabytes), so it waits for a pause in typing: the effect is
    // cancelled and restarted on every keystroke, and the delay before the work is
    // what debounces it.
    LaunchedEffect(query, summaries) {
        val needle = query.trim()
        if (needle.length < MIN_SEARCH_CHARS || summaries.isEmpty()) {
            matches = emptyMap()
            matchedQuery = ""
            searching = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
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

    PiScaffold(
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
        // platform convention puts it and where `PiScaffold` draws it. It used to
        // be the last item in the action row, i.e. next to Delete, which is both
        // unconventional and a hazard.
        onBack = onBack,
        actions = {
            if (selecting) {
                // Scoped to `visible` rather than every saved conversation:
                // a search that has narrowed the page to three rows is a
                // list of three, and a button that silently selected rows
                // nobody can see would make the next delete a surprise.
                // Tapping again drops exactly those rows, so a selection
                // built up under an earlier filter is not thrown away by
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
                    Icon(
                        if (allVisibleSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                        contentDescription = if (allVisibleSelected) {
                            text.sessions.deselectAll
                        } else {
                            text.sessions.selectAll
                        },
                    )
                }
                IconButton(
                    onClick = {
                        confirmingDelete = summaries.filter { it.path in selected }
                    },
                    enabled = selected.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = text.sessions.deleteSelected)
                }
                IconButton(onClick = { exitSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = text.sessions.cancelSelection)
                }
            } else {
                IconButton(
                    onClick = { selecting = true },
                    enabled = summaries.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Checklist, contentDescription = text.sessions.select)
                }
            }
        },
    ) { body ->
        Column(body) {
            // Directly under the bar and not inside a card: the field filters the
            // list under it, and a frame of its own would put a third border
            // between the bar and the rows it acts on — a card is one subject
            // with its own body, and this field has no body but the list.
            PiSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = text.sessions.searchHint,
                clearContentDescription = text.common.clear,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            )

            message?.let {
                PiNotice(
                    text = it,
                    action = {
                        PiButton(
                            text = text.common.ok,
                            kind = PiButtonKind.Text,
                            size = PiButtonSize.ExtraSmall,
                            onClick = { message = null },
                        )
                    },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PiLoading()
                }

                // A scan that has not answered yet is not an empty result. The
                // list used to say "nothing matches" for as long as the scan ran,
                // which is a claim about a search that was still reading files.
                searching && visible.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PiStatePill(text.sessions.searching)
                }

                visible.isEmpty() -> PiEmptyState(
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
                        // A turn in flight makes this a question rather than a
                        // switch: pi aborts the running answer as the first step of
                        // its own `switch_session` (`RuntimeHost.teardownCurrent`),
                        // so the tap would stop an answer the reader may still want.
                        // See [InterruptTurnDialog].
                        if (session.turnInFlight) {
                            pendingSwitch = item
                        } else {
                            session.switchSession(item)
                            onOpened()
                        }
                    },
                    onActions = { item ->
                        sheets.show(
                            Sheet(key = "session-actions:${item.path}") {
                                SessionActionsSheet(
                                    text = text,
                                    summary = item,
                                    onRename = { renaming = item },
                                    onTogglePin = {
                                        session.setPinned(item, !item.pinned)
                                        reload++
                                    },
                                    onDelete = { confirmingDelete = listOf(item) },
                                )
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            text = text,
            initial = if (target.hasExplicitName) target.title else "",
            isActive = target.path == session.conversation.value.sessionFile,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch {
                    session.renameSession(target, name)
                        .onFailure { message = text.sessions.renameFailed(it.message.orEmpty()) }
                    reload++
                }
            },
        )
    }

    pendingSwitch?.let { target ->
        InterruptTurnDialog(
            text = text,
            onDismiss = { pendingSwitch = null },
            onConfirm = {
                pendingSwitch = null
                session.switchSession(target, interrupt = true)
                onOpened()
            },
        )
    }

    if (confirmingDelete.isNotEmpty()) {
        DeleteDialog(
            text = text,
            targets = confirmingDelete,
            onDismiss = { confirmingDelete = emptyList() },
            onConfirm = {
                val targets = confirmingDelete
                confirmingDelete = emptyList()
                exitSelection()
                scope.launch {
                    session.deleteSessions(targets)
                        .onSuccess { removed ->
                            if (removed < targets.size) {
                                message = text.sessions.partiallyDeleted(removed, targets.size)
                            }
                        }
                        .onFailure { error ->
                            // A refusal because the agent is mid-turn is not a disk
                            // failure, and the sentence for it is in the catalogs;
                            // `deleteFailed` would print a process-language reason
                            // ("the agent is still working on this session") the
                            // reader cannot act on.
                            message = if (error is TurnInFlightException) {
                                text.sessions.switchWhileWorking
                            } else {
                                text.sessions.deleteFailed(error.message.orEmpty())
                            }
                        }
                    reload++
                }
            },
        )
    }
}

/**
 * The conversations, grouped, with the pinned group first.
 *
 * Pinned and unpinned are two labelled groups rather than one flat list with a
 * glyph stuck on every pinned title. The old mark — a filled pushpin at 14dp in
 * `primary`, hard against a bold title — was the whole of the "this is pinned"
 * signal and it fought the title for attention on every row. Grouping does that
 * work once, at the heading, and a pinned row only has to look slightly lifted
 * (`primaryContainer` at half strength) to stay easy to pick out inside its
 * group. The heading itself carries no glyph for the same reason: the group's
 * name already says what it is, and `PiSectionHeader`'s one voice is what the
 * rest of the app's headings are drawn in.
 *
 * The unpinned heading appears only when something above it is pinned. Without
 * that break a mixed list has no way to say where the pinned group ends, and
 * with it on an all-unpinned list the page would open under a heading that
 * states the obvious.
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
    onActions: (PiAgentSession.SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinnedVisible = visible.filter { it.pinned }
    val otherVisible = visible.filterNot { it.pinned }
    val rows = buildList<ListRow> {
        if (pinnedVisible.isNotEmpty()) {
            add(ListRow.Section(text.sessions.pinned))
            pinnedVisible.forEach { add(ListRow.Session(it)) }
            if (otherVisible.isNotEmpty()) {
                add(ListRow.Section(text.sessions.recent))
            }
        }
        otherVisible.forEach { add(ListRow.Session(it)) }
    }

    LazyColumn(
        modifier = modifier,
        // A gap above the navigation bar, so the last conversation is not read
        // against the bar's own edge. One constant for every scrolling page:
        // `PiPageBottom`.
        contentPadding = PaddingValues(bottom = PiPageBottom),
    ) {
        itemsIndexed(rows, key = { index, row ->
            when (row) {
                is ListRow.Section -> "section-$index-${row.title}"
                is ListRow.Session -> row.item.path
            }
        }) { index, row ->
            when (row) {
                is ListRow.Section -> PiSectionHeader(text = row.title)
                is ListRow.Session -> ConversationRow(
                    item = row.item,
                    text = text,
                    selecting = selecting,
                    checked = row.item.path in selected,
                    snippet = snippets[row.item.path],
                    onToggleChecked = { onToggle(row.item.path) },
                    onOpen = { onOpen(row.item) },
                    onActions = { onActions(row.item) },
                )
            }
            // A hairline between two conversation rows only. A section heading is
            // the break — a divider after one would draw a line under the label
            // and another under the same gap.
            if (
                row is ListRow.Session &&
                index < rows.lastIndex &&
                rows[index + 1] is ListRow.Session
            ) {
                // Inset to the text rather than to the page: the rows are inset
                // 12dp and pad themselves another 12dp, so a full-bleed hairline
                // would be the one thing on the page that ignores the columns.
                PiRowDivider(inset = false)
            }
        }
    }
}

/** One line of the list: a group heading, or a conversation. */
private sealed interface ListRow {
    data class Section(val title: String) : ListRow

    data class Session(val item: PiAgentSession.SessionSummary) : ListRow
}

/**
 * One conversation as the question it was.
 *
 * The primary line is the reader's own words — the title pi shows is either the
 * name they gave it or, far more often, their first message — and the second
 * line is when it was and how much followed. A match from the content scan is
 * the third line, and only while searching.
 *
 * Deliberately not a `PiRow`: a settings row names a control, and the design
 * language's history chapter says this row shares nothing with those. What
 * separates the two lines here is weight and colour rather than a box or a
 * glyph, which is what keeps a list of two-line questions one rhythm instead of
 * a table.
 *
 * ## Why the actions are a hold, not a button
 *
 * The row used to carry a trailing `MoreVert`, which was the only thing in it
 * that was not the reader's question, sitting in the 48dp of edge the title
 * wanted. A press-and-hold is the platform's own convention for per-row actions
 * on a list, and it gives the row's right edge back to the text; the same three
 * actions stay reachable, and one of them — delete — is also in the multi-select
 * bar. [combinedClickable] announces the hold through
 * [Strings.Sessions.actions] rather than leaving it an unnamed gesture
 * (`onLongClickLabel`), which is what a screen reader reads out for it.
 */
@Composable
private fun ConversationRow(
    item: PiAgentSession.SessionSummary,
    text: Strings,
    selecting: Boolean,
    checked: Boolean,
    snippet: String?,
    onToggleChecked: () -> Unit,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    // Locale.getDefault() follows the interface language because the root
    // applies it, so a date under a Chinese interface reads as Chinese.
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 12dp of page inset, then the row's own padding: the press outline
            // is a rounded rectangle inside the page rather than a full-bleed
            // band, which is what the flat edge-to-edge list this replaced lacked
            // — "no corners on the tap target".
            .padding(horizontal = 12.dp)
            // Clipped to the row's radius before the ripple, so the press outline
            // is the same shape the divider between two rows is inset to.
            .clip(PiShapes.row)
            // Half-strength `primaryContainer` is the row's only "pinned" mark
            // left after the heading took over the job of saying so. Full
            // strength read as a selected row; this is a lift, not a state.
            .background(
                if (item.pinned) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .combinedClickable(
                onLongClickLabel = text.sessions.actions,
                // Nothing to hold for while the list is in selection mode: the
                // tap is already the checkbox, and a second way to reach the same
                // three actions there would be a menu over a multi-select.
                onLongClick = if (selecting) null else onActions,
                onClick = { if (selecting) onToggleChecked() else onOpen() },
            )
            .padding(
                start = if (selecting) 4.dp else 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { text.sessions.emptyTitle },
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                // Two lines: a session title is the user's own words and a one-line
                // cap cut the middle out of names that matter.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(formatter.format(Date(item.modifiedAt)))
                    append(" · ")
                    append(text.sessions.messageCount(item.messageCount))
                    if (!item.hasExplicitName) append(" · ").append(text.sessions.untitled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            snippet?.let { match ->
                Text(
                    text = match,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    // Two lines, and only while searching: every result carries a
                    // snippet, so the list stays one rhythm in each mode. Capped
                    // so a match deep in a long reply cannot open a tall row.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .semantics { contentDescription = text.sessions.contentMatch(match) },
                )
            }
        }
    }
}

/**
 * The three things a conversation can have done to it, as a sheet body.
 *
 * A sheet and not a `DropdownMenu` anchored to the row: an anchored menu is a
 * focusable second window, and it leaves a zero-size layout node in the row it
 * hangs from, which `Arrangement.spacedBy` then charges a gap for. Every menu in
 * PiKit is a body on the root's one modal layer for the same reason, and this is
 * a body for it — `PiSheetTitle` and `PiSheetList` from the design system rather
 * than a shape of its own, and no dismiss button: the scrim, the back gesture and
 * the downward drag all close a sheet, so a *Cancel* row was a fourth way to do
 * what three already did.
 *
 * The title is the question and the subtitle is the conversation it is about,
 * so the reader does not have to look behind the scrim to remember which row
 * they held down.
 */
@Composable
private fun SessionActionsSheet(
    text: Strings,
    summary: PiAgentSession.SessionSummary,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = LocalSheetHost.current

    Column(Modifier.fillMaxWidth()) {
        PiSheetTitle(
            title = text.sessions.actions,
            subtitle = summary.title.ifBlank { text.sessions.emptyTitle },
        )
        PiSheetList {
            item {
                PiSheetRow(
                    label = text.sessions.rename,
                    leading = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = sheetAction(host, onRename),
                )
            }
            item {
                PiSheetRow(
                    label = if (summary.pinned) text.sessions.unpin else text.sessions.pin,
                    leading = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = sheetAction(host, onTogglePin),
                )
            }
            item {
                PiSheetRow(
                    label = text.sessions.delete,
                    leading = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    danger = true,
                    onClick = sheetAction(host, onDelete),
                )
            }
        }
    }
}

/**
 * Wraps a sheet row's action so that it runs only while the sheet is still the
 * thing on screen, and so that the sheet is dismissed first.
 *
 * The layer keeps drawing the panel for the length of its exit, and the design
 * system's `PiSheetRow` installs its click for as long as the row is composed —
 * so without this gate a second tap, aimed at the page behind a sheet that is
 * already leaving, would land on a row of a sheet that no longer exists.
 * `components/Forms.kt`'s rows carry the same gate for the same reason.
 *
 * [SheetHost.isOpen] is read when the tap happens rather than when the body is
 * built: it is snapshot state, so reading it inside the lambda is the one way to
 * get the answer as of the tap.
 */
private fun sheetAction(host: SheetHost, action: () -> Unit): () -> Unit = {
    if (host.isOpen) {
        host.dismiss()
        action()
    }
}

@Composable
private fun RenameDialog(
    text: Strings,
    initial: String,
    isActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.sessions.renameTitle) },
        text = {
            Column {
                // The app's real text field rather than a second one: the design
                // language names this dialog as one of the three places a typed
                // string is entered, and its corner is the content radius for
                // exactly this reason — a pill reads as a search.
                PiTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = text.sessions.renameLabel,
                )
                Text(
                    if (isActive) text.sessions.renameActiveNote else text.sessions.renameClosedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text(text.sessions.rename) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text.sessions.cancel) } },
    )
}

@Composable
private fun DeleteDialog(
    text: Strings,
    targets: List<PiAgentSession.SessionSummary>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (targets.size == 1) {
                    text.sessions.deleteTitleOne
                } else {
                    text.sessions.deleteTitleMany(targets.size)
                },
            )
        },
        text = {
            Text(
                if (targets.size == 1) {
                    text.sessions.deleteBodyOne(
                        targets.first().title.ifBlank { text.sessions.emptyTitle },
                    )
                } else {
                    text.sessions.deleteBodyMany + "\n\n" +
                        targets.joinToString("\n") {
                            "• ${it.title.ifBlank { text.sessions.emptyTitle }}"
                        }
                },
            )
        },
        confirmButton = {
            // Red, like every other confirmation that cannot be taken back: the session
            // file is deleted outright. See [InterruptTurnDialog] below for the same
            // treatment of a different irreversible move.
            TextButton(onClick = onConfirm) {
                Text(text.sessions.delete, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text.sessions.cancel) } },
    )
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
 */
@Composable
internal fun InterruptTurnDialog(
    text: Strings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.sessions.switchInterruptTitle) },
        text = { Text(text.sessions.switchInterruptBody) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text.sessions.switchInterruptConfirm,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text.common.cancel) } },
    )
}

/** How long the search field must be quiet before the content scan starts. */
private const val SEARCH_DEBOUNCE_MS = 200L

/** One character matches almost every conversation and is not worth the scan. */
private const val MIN_SEARCH_CHARS = 2
