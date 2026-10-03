package pi.kit.mob.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.TurnInFlightException
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.components.ErrorBanner
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageHeader
import pi.kit.mob.ui.components.ReadOnlyBody
import pi.kit.mob.ui.components.ReadOnlySheetRow
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.settings.SettingsDivider
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
 * own session picker does.
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

    Column(Modifier.fillMaxSize()) {
        PageHeader(
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
            // The back arrow belongs at the start of the header. It used to be
            // the last item in the action row, i.e. next to Delete, which is
            // both unconventional and a hazard.
            onBack = onBack,
            backContentDescription = text.sessions.back,
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
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(text.sessions.searchHint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )

        message?.let {
            ErrorBanner(message = it, onDismiss = { message = null })
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            return@Column
        }

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (summaries.isEmpty()) {
                        text.sessions.empty
                    } else {
                        text.sessions.nothingMatches(query)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }

        // One card around the list, the same shape every settings section uses:
        // rounded outer corners, rows clipped to that radius on press, and
        // `SettingsDivider` between them. The flat edge-to-edge list was the
        // report — no corners on the tap target, and a full-bleed hairline that
        // did not match the settings pages the rest of the app reads as one with.
        //
        // Pinned and unpinned are two labelled groups rather than one flat list
        // with a glyph stuck on every pinned title. The old mark — a filled
        // pushpin at 14dp in `primary`, hard against a bold title — was the
        // whole of the "this is pinned" signal and it fought the title for
        // attention on every row. Grouping does that work once, at the heading,
        // and a pinned row only has to look slightly lifted (`primaryContainer`
        // at half strength) to stay easy to pick out inside its group.
        val pinnedVisible = visible.filter { it.pinned }
        val otherVisible = visible.filter { !it.pinned }
        val rows = buildList<ListRow> {
            if (pinnedVisible.isNotEmpty()) {
                add(ListRow.Section(text.sessions.pinned, showPin = true))
                pinnedVisible.forEach { add(ListRow.Session(it, elevated = true)) }
                if (otherVisible.isNotEmpty()) {
                    add(ListRow.Section(text.sessions.recent, showPin = false))
                }
            }
            otherVisible.forEach { add(ListRow.Session(it, elevated = false)) }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                itemsIndexed(rows, key = { index, row ->
                    when (row) {
                        is ListRow.Section -> "section-$index-${row.title}"
                        is ListRow.Session -> row.item.path
                    }
                }) { index, row ->
                    when (row) {
                        is ListRow.Section -> SessionsSectionLabel(
                            text = row.title,
                            showPin = row.showPin,
                            extraTop = row.title == text.sessions.recent,
                        )
                        is ListRow.Session -> SessionRow(
                            item = row.item,
                            text = text,
                            selecting = selecting,
                            checked = row.item.path in selected,
                            snippet = snippets[row.item.path],
                            elevated = row.elevated,
                            onToggleChecked = {
                                selected = if (row.item.path in selected) {
                                    selected - row.item.path
                                } else {
                                    selected + row.item.path
                                }
                            },
                            onOpen = {
                                // A turn in flight makes this a question rather than a
                                // switch: pi aborts the running answer as the first step of
                                // its own `switch_session` (`RuntimeHost.teardownCurrent`),
                                // so the tap would stop an answer the reader may still want.
                                // See [InterruptTurnDialog].
                                if (session.turnInFlight) {
                                    pendingSwitch = row.item
                                } else {
                                    session.switchSession(row.item)
                                    onOpened()
                                }
                            },
                            // The row's actions are a menu, and every menu in PiKit is a
                            // sheet body on the root's modal layer rather than a
                            // `DropdownMenu` anchored to the button. An anchored menu is not
                            // a view in the row: `Popup` is a focusable second window and it
                            // leaves a zero-size layout node in this Row, which
                            // `Arrangement.spacedBy` then charges a gap for.
                            onActions = {
                                val summary = row.item
                                sheets.show(
                                    Sheet(key = "session-actions:${summary.path}") {
                                        ReadOnlyBody(title = summary.title.ifBlank { text.sessions.emptyTitle }) {
                                            item {
                                                ReadOnlySheetRow(
                                                    label = text.sessions.rename,
                                                    value = null,
                                                    onClick = { renaming = summary },
                                                )
                                            }
                                            item {
                                                ReadOnlySheetRow(
                                                    label = if (summary.pinned) {
                                                        text.sessions.unpin
                                                    } else {
                                                        text.sessions.pin
                                                    },
                                                    value = null,
                                                    onClick = {
                                                        session.setPinned(summary, !summary.pinned)
                                                        reload++
                                                    },
                                                )
                                            }
                                            item {
                                                ReadOnlySheetRow(
                                                    label = text.sessions.delete,
                                                    value = null,
                                                    onClick = { confirmingDelete = listOf(summary) },
                                                )
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                    // A hairline between two conversation rows only. A section
                    // heading is the break — a divider after one would draw a
                    // line under the label and another under the same gap.
                    if (
                        row is ListRow.Session &&
                        index < rows.lastIndex &&
                        rows[index + 1] is ListRow.Session
                    ) {
                        SettingsDivider()
                    }
                }
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

/** One line of the list card: a group heading, or a conversation row. */
private sealed interface ListRow {
    data class Section(val title: String, val showPin: Boolean) : ListRow

    data class Session(
        val item: PiAgentSession.SessionSummary,
        val elevated: Boolean,
    ) : ListRow
}

/**
 * A group heading inside the list card.
 *
 * Same voice as `SettingsSection`'s label — `titleSmall`, SemiBold, `primary`,
 * letterspaced uppercase — because the break between "已置顶" and "最近" is the
 * same kind of break as the one between two settings cards. The pin glyph rides
 * with the *heading* rather than with every row: one mark at the group is the
 * whole signal, and the row is left to carry its title.
 */
@Composable
private fun SessionsSectionLabel(
    text: String,
    showPin: Boolean,
    extraTop: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (extraTop) 18.dp else 12.dp,
                bottom = 6.dp,
            ),
    ) {
        if (showPin) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun SessionRow(
    item: PiAgentSession.SessionSummary,
    text: Strings,
    selecting: Boolean,
    checked: Boolean,
    snippet: String?,
    elevated: Boolean,
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
            // Clipped to the card's radius before the ripple, so first and last
            // rows keep the rounded press outline the way settings rows do.
            .clip(MaterialTheme.shapes.medium)
            // Half-strength `primaryContainer` is the row's only "pinned" mark
            // left after the heading took over the job of saying so. Full
            // strength read as a selected row; this is a lift, not a state.
            .background(
                if (elevated) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .clickable { if (selecting) onToggleChecked() else onOpen() }
            // Same floor as `SettingsRow`, and the same 12dp vertical padding: a
            // session row and a settings row are the same kind of page-list line.
            .padding(start = if (selecting) 4.dp else 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { text.sessions.emptyTitle },
                fontWeight = FontWeight.Medium,
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
        if (!selecting) {
            IconButton(onClick = onActions) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = text.sessions.actions,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(text.sessions.renameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
