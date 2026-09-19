package pi.kit.mob.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.TurnInFlightException
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageHeader
import pi.kit.mob.ui.components.ReadOnlyBody
import pi.kit.mob.ui.components.ReadOnlySheetRow
import pi.kit.mob.ui.components.Sheet
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
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )

        message?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { message = null }
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(visible, key = { it.path }) { summary ->
                SessionRow(
                    item = summary,
                    text = text,
                    selecting = selecting,
                    checked = summary.path in selected,
                    snippet = snippets[summary.path],
                    onToggleChecked = {
                        selected = if (summary.path in selected) {
                            selected - summary.path
                        } else {
                            selected + summary.path
                        }
                    },
                    onOpen = {
                        // A turn in flight makes this a question rather than a
                        // switch: pi aborts the running answer as the first step of
                        // its own `switch_session` (`RuntimeHost.teardownCurrent`),
                        // so the tap would stop an answer the reader may still want.
                        // It used to be a flat refusal with a notice explaining why,
                        // and the notice was the report — a tap that did nothing the
                        // reader could act on. See [InterruptTurnDialog].
                        if (session.turnInFlight) {
                            pendingSwitch = summary
                        } else {
                            session.switchSession(summary)
                            onOpened()
                        }
                    },
                    // The row's actions are a menu, and every menu in PiKit is a
                    // sheet body on the root's modal layer rather than a
                    // `DropdownMenu` anchored to the button. An anchored menu is not
                    // a view in the row: `Popup` is a focusable second window and it
                    // leaves a zero-size layout node in this Row, which
                    // `Arrangement.spacedBy` then charges a gap for — so tapping the
                    // button moved it 4dp and the menu opened at the row's left edge
                    // instead of under the button.
                    onActions = {
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

@Composable
private fun SessionRow(
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
            .clickable { if (selecting) onToggleChecked() else onOpen() }
            .padding(start = if (selecting) 4.dp else 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = text.sessions.pinned,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    item.title.ifBlank { text.sessions.emptyTitle },
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                // One line is right for a date and a count, but the default
                // overflow is `Clip`, which cuts a glyph in half rather than
                // saying it did. A translated date and a long "untitled" suffix
                // are the two ways this line can outgrow its row.
                overflow = TextOverflow.Ellipsis,
            )
            snippet?.let { match ->
                Text(
                    text = match,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
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
