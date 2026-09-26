package pi.kit.mob.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.BackupArchive
import pi.kit.mob.data.BackupCategory
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.BackupStatus
import pi.kit.mob.pi.PiAgentSession

/**
 * Backup & restore: the archive's contents, and the two buttons that move it.
 *
 * ## Why this page is not the maintenance page
 *
 * It is the same kind of action — a long filesystem walk with a result to report —
 * so it used to belong there. What separates it is the direction of the risk:
 * everything on the maintenance page repairs a runtime that is rebuilt from the
 * APK on the next launch, and every mistake it can make costs a reinstall. This
 * page moves the only copy of the user's own data — the profiles with their keys,
 * every conversation, the workspace — and a mistake it makes costs the data. Two
 * different questions, two pages, and the row between them is where the reader
 * changes their mind about which one they are asking.
 *
 * ## What the page shows, and in which order
 *
 * The two directions are two cards, and each draws the state that belongs to it:
 * an export's "packing…" line appears under the export button and an import's
 * under the import button, because one shared status line under both reads as
 * having done the wrong thing. The selection is the same eight switches both
 * times, which is the point of the design: what was ticked on the way out is what
 * is offered on the way back in, and an archive that came from someone else shows
 * exactly what it carries rather than what the reader picked last time.
 *
 * ## The picker
 *
 * `CreateDocument`/`OpenDocument` rather than a path: the archive has to be able
 * to leave the app — to Downloads, to a cloud provider's client, to a laptop over
 * a cable — and the app has no business choosing where. It is also what keeps the
 * feature working without "all files access", which is the permission a user is
 * least likely to have granted.
 */
@Composable
internal fun BackupPage(session: PiAgentSession, onBack: () -> Unit) {
    val manager = session.backup
    val status by manager.status.collectAsState()
    val review by manager.review.collectAsState()
    val text = strings

    var selection by rememberSaveable(stateSaver = CategoriesSaver) {
        mutableStateOf(BackupCategory.DEFAULT)
    }
    var includeKeys by rememberSaveable { mutableStateOf(true) }

    // The name the archive was offered under. Held here because the system hands the
    // saved file back as a URI whose last segment is a *database key* for the
    // Downloads provider (`msf:8`), so this is the only name there is when the
    // provider will not answer `DISPLAY_NAME` — and a report that names the wrong
    // file is worse than one that names none.
    var suggested by remember { mutableStateOf("") }

    // The launcher callbacks read the newest selection: the lambdas below are
    // re-created on every recomposition, and the result arrives after the user has
    // been to another app and back.
    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ARCHIVE_MIME),
    ) { target ->
        if (target != null) manager.export(target, suggested, selection, includeKeys)
    }
    val pickArchive = rememberLauncherForActivityResult(
        // No MIME filter worth having: a `.zip` arrives as
        // `application/octet-stream` from one provider and `application/zip` from
        // the next, and a filter that hides the user's own backup is worse than a
        // picker that lists every file.
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) manager.inspect(source)
    }

    val busy = status is BackupStatus.Running

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.backupTitle,
            subtitle = text.settings.backupSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.backupExportSection) {
                Categories(
                    offered = BackupCategory.entries.toSet(),
                    selected = selection,
                    enabled = !busy,
                    onToggle = { category ->
                        selection = selection.toggle(category)
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = text.settings.backupApiKeys,
                    subtitle = text.settings.backupApiKeysSubtitle,
                    checked = includeKeys,
                    enabled = !busy,
                    onChange = { includeKeys = it },
                )

                ActionRow(
                    label = text.settings.backupExport,
                    enabled = !busy && selection.isNotEmpty(),
                    onClick = {
                        suggested = BackupArchive.suggestedName()
                        createArchive.launch(suggested)
                    },
                    onDismiss = if (status.isExportOutcome()) manager::dismiss else null,
                    dismissLabel = text.settings.dismiss,
                )
                StatusLine(status = status, kind = BackupStatus.Kind.EXPORT)
                if (selection.isEmpty()) {
                    SettingsNote(
                        text.settings.backupNothingSelected,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            SettingsSection(text.settings.backupImportSection) {
                val pending = review
                if (pending == null) {
                    ActionRow(
                        label = text.settings.backupImport,
                        enabled = !busy,
                        onClick = { pickArchive.launch(arrayOf("*/*")) },
                        onDismiss = if (status.isImportOutcome()) manager::dismiss else null,
                        dismissLabel = text.settings.dismiss,
                    )
                    StatusLine(status = status, kind = BackupStatus.Kind.IMPORT)
                } else {
                    val available = pending.manifest.included
                    // Re-seeded whenever a different archive is read, so the ticks
                    // are the archive's contents rather than the last import's
                    // choices.
                    var chosen by remember(pending) { mutableStateOf(available) }

                    SettingsRow(
                        title = text.settings.backupReviewTitle,
                        subtitle = text.settings.backupReviewFrom(
                            version = pending.manifest.app,
                            // The day only: the manifest's stamp is UTC and a time
                            // of day would be right in one time zone and wrong in
                            // the rest.
                            date = pending.manifest.createdAt.take(ISO_DATE_LENGTH),
                        ),
                    )
                    if (available.isEmpty()) {
                        SettingsDivider()
                        SettingsNote(
                            text.settings.backupReviewEmpty,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } else {
                        SettingsDivider()
                        Categories(
                            offered = available,
                            selected = chosen,
                            enabled = !busy,
                            onToggle = { category -> chosen = chosen.toggle(category) },
                        )
                        SettingsDivider()
                        // What the archive carries, said plainly: whether a key is
                        // in it is not something the reader can see from the file
                        // name, and it is the one fact that decides whether this
                        // archive may be passed on.
                        SettingsRow(
                            title = if (pending.manifest.apiKeys) {
                                text.settings.backupReviewKeys
                            } else {
                                text.settings.backupReviewNoKeys
                            },
                        )
                    }

                    ActionRow(
                        label = text.settings.backupImportConfirm,
                        enabled = !busy && chosen.isNotEmpty(),
                        onClick = { manager.restore(chosen) },
                        onDismiss = if (busy) null else manager::cancelReview,
                        dismissLabel = text.common.cancel,
                    )
                    StatusLine(status = status, kind = BackupStatus.Kind.IMPORT)
                    SettingsNote(
                        text.settings.backupImportNote,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            SettingsNote(text.settings.backupNote)
        }
    }
}

/**
 * The eight switches, drawn from the enum rather than written out.
 *
 * Both cards draw the same list and the difference is only [offered], so a ninth
 * category is one entry in [BackupCategory], one pair of strings and no change
 * here. The alternative — two hand-written runs of eight switches — is two lists
 * to keep in step with the enum, which is how a category comes to be exportable
 * and not restorable.
 */
@Composable
private fun Categories(
    offered: Set<BackupCategory>,
    selected: Set<BackupCategory>,
    enabled: Boolean,
    onToggle: (BackupCategory) -> Unit,
) {
    val text = strings
    val shown = BackupCategory.entries.filter { it in offered }
    shown.forEachIndexed { index, category ->
        if (index > 0) SettingsDivider()
        val (title, subtitle) = category.label(text)
        SettingsSwitchRow(
            title = title,
            subtitle = subtitle,
            checked = category in selected,
            enabled = enabled,
            onChange = { onToggle(category) },
        )
    }
}

/**
 * A button and, beside it, the action that clears the outcome it produced.
 *
 * The pair is the maintenance page's shape — a `Button` to run, a `TextButton` to
 * dismiss — because the two pages ask the reader to press the same kind of thing.
 */
@Composable
private fun ActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)?,
    dismissLabel: String,
) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    }
}

/**
 * The line under a card's buttons, for the half of the status that card owns.
 *
 * Split by direction rather than shown under both: an export's progress under the
 * import button reads as an import that is somehow packing a file, and the one
 * status value both halves read from is exactly why this takes a [kind].
 */
@Composable
private fun StatusLine(status: BackupStatus, kind: BackupStatus.Kind) {
    val text = strings
    val tone: Tone
    val message: String

    when (status) {
        BackupStatus.Idle -> return
        BackupStatus.NotAnArchive -> {
            message = text.settings.backupNotAnArchive
            tone = Tone.ERROR
        }

        is BackupStatus.Failed -> {
            if (status.kind != kind) return
            message = text.settings.backupFailed(status.message)
            tone = Tone.ERROR
        }

        is BackupStatus.Running -> {
            if (status.kind != kind) return
            message = when (kind) {
                BackupStatus.Kind.EXPORT -> text.settings.backupExportRunning(status.entries)
                BackupStatus.Kind.IMPORT -> text.settings.backupImportRunning(status.entries)
            }
            tone = Tone.PROGRESS
        }

        is BackupStatus.Exported -> {
            if (kind != BackupStatus.Kind.EXPORT) return
            message = text.settings.backupExportDone(status.name, status.entries)
            tone = Tone.OK
        }

        is BackupStatus.Imported -> {
            if (kind != BackupStatus.Kind.IMPORT) return
            message = text.settings.backupImportDone(status.entries)
            tone = Tone.OK
        }
    }

    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = when (tone) {
            Tone.OK -> MaterialTheme.colorScheme.primary
            Tone.ERROR -> MaterialTheme.colorScheme.error
            Tone.PROGRESS -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private enum class Tone { PROGRESS, OK, ERROR }

/** One category's row: what it is, and what is inside it. */
private fun BackupCategory.label(text: Strings): Pair<String, String> = when (this) {
    BackupCategory.SETTINGS -> text.settings.backupCategorySettings to text.settings.backupCategorySettingsSubtitle
    BackupCategory.MODELS -> text.settings.backupCategoryModels to text.settings.backupCategoryModelsSubtitle
    BackupCategory.CONVERSATIONS ->
        text.settings.backupCategoryConversations to text.settings.backupCategoryConversationsSubtitle

    BackupCategory.AGENT_PROMPT ->
        text.settings.backupCategoryAgentPrompt to text.settings.backupCategoryAgentPromptSubtitle

    BackupCategory.WORKSPACE ->
        text.settings.backupCategoryWorkspace to text.settings.backupCategoryWorkspaceSubtitle

    BackupCategory.HTML_EXPORTS ->
        text.settings.backupCategoryExports to text.settings.backupCategoryExportsSubtitle
}

private fun Set<BackupCategory>.toggle(category: BackupCategory): Set<BackupCategory> =
    if (category in this) this - category else this + category

private fun BackupStatus.isExportOutcome(): Boolean = when (this) {
    is BackupStatus.Exported -> true
    is BackupStatus.Running -> kind == BackupStatus.Kind.EXPORT
    is BackupStatus.Failed -> kind == BackupStatus.Kind.EXPORT
    else -> false
}

private fun BackupStatus.isImportOutcome(): Boolean = when (this) {
    is BackupStatus.Running -> kind == BackupStatus.Kind.IMPORT
    is BackupStatus.Failed -> kind == BackupStatus.Kind.IMPORT
    BackupStatus.NotAnArchive, is BackupStatus.Imported -> true
    else -> false
}

/**
 * The selection as a list of ids.
 *
 * A `Set<BackupCategory>` is not something the save boundary knows how to write,
 * and the ids are the archive's own spelling of the categories rather than a second
 * one: what survives a rotation is the same set that would go into a manifest.
 */
private val CategoriesSaver = listSaver<Set<BackupCategory>, String>(
    save = { categories -> categories.map { it.id } },
    restore = { ids -> ids.mapNotNull(BackupCategory::fromId).toSet() },
)

/** A `.zip`, which is what the file picker is told it is creating. */
private const val ARCHIVE_MIME = "application/zip"

/** `yyyy-MM-dd` out of an ISO stamp; see the review row for why only the day. */
private const val ISO_DATE_LENGTH = 10
