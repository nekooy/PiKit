package pi.kit.mob.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Backup & restore: the archive's contents, and the two directions that move it.
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
 * The two directions are two labelled groups, and each draws the state that belongs
 * to it: an export's "packing…" line appears under the export button and an import's
 * under the import button, because one shared status line under both reads as
 * having done the wrong thing. The selection is the same six switches both
 * times, which is the point of the design: what was ticked on the way out is what
 * is offered on the way back in, and an archive that came from someone else shows
 * exactly what it carries rather than what the reader picked last time.
 *
 * The switches are `SwitchPreference`, whose row toggles with the switch: picking what
 * an archive holds is immediate and undoable, so a tap anywhere on the row is the
 * right target. The storage page deliberately does the opposite with its folder
 * rows, because each of those can raise a question before it moves.
 *
 * A restore is the one destructive thing here — it replaces the files of the names
 * the archive carries, and what it replaced is not recoverable — so it is the one
 * action whose wording sits in a note in the error role, above the button that acts on
 * it. The sentence itself is unchanged, because "it deletes nothing" is exactly what a
 * reader needs before pressing it.
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
    val toasts = LocalPiToast.current

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
    val running = status as? BackupStatus.Running
    // The line's sentence, split by direction for the same reason the verdict is: a
    // count of entries packed under the import button reads as an import that is
    // somehow writing a file.
    val exportWorking = running
        ?.takeIf { it.kind == BackupStatus.Kind.EXPORT }
        ?.let { text.settings.backupExportRunning(it.entries) }
    val importWorking = running
        ?.takeIf { it.kind == BackupStatus.Kind.IMPORT }
        ?.let { text.settings.backupImportRunning(it.entries) }

    // A run that finished is a moment rather than a condition, so its outcome is the
    // app's one transient message: "4 entries restored" used to be a strip under the
    // button that stayed there — with a Dismiss button beside it — until the next run
    // replaced it. The status is cleared with it, because the toast *is* the report.
    LaunchedEffect(status) {
        val message = when (val settled = status) {
            BackupStatus.NotAnArchive -> text.settings.backupNotAnArchive
            is BackupStatus.Failed -> text.settings.backupFailed(settled.message)
            is BackupStatus.Exported -> text.settings.backupExportDone(settled.name, settled.entries)
            is BackupStatus.Imported -> text.settings.backupImportDone(settled.entries)
            else -> null
        }
        if (message != null) {
            toasts?.show(message)
            manager.dismiss()
        }
    }

    PiPage(
        title = text.settings.backupTitle,
        subtitle = text.settings.backupSubtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.backupExportSection) {
                Categories(
                    offered = BackupCategory.entries.toSet(),
                    selected = selection,
                    enabled = !busy,
                    onToggle = { category ->
                        selection = selection.toggle(category)
                    },
                )
                SwitchPreference(
                    title = text.settings.backupApiKeys,
                    summary = text.settings.backupApiKeysSubtitle,
                    checked = includeKeys,
                    enabled = !busy,
                    onCheckedChange = { includeKeys = it },
                )
            }
            RunActions(
                label = text.settings.backupExport,
                onAction = {
                    suggested = BackupArchive.suggestedName()
                    createArchive.launch(suggested)
                },
                dismissLabel = text.settings.dismiss,
                enabled = !busy && selection.isNotEmpty(),
                working = exportWorking,
            )
            if (selection.isEmpty()) {
                PiSection {
                    SettingsNote(text.settings.backupNothingSelected)
                }
            }

            // The import is two states, and only one of them has a group: with nothing
            // read yet there is nothing to put in a card, so the section is its label
            // and its button; once an archive has been read the group is the archive's
            // own contents and the button beside it is the restore.
            val pending = review
            if (pending == null) {
                SmallTitle(text = text.settings.backupImportSection)
                RunActions(
                    label = text.settings.backupImport,
                    onAction = { pickArchive.launch(arrayOf("*/*")) },
                    dismissLabel = text.settings.dismiss,
                    enabled = !busy,
                    working = importWorking,
                )
            } else {
                // Re-seeded whenever a different archive is read, so the ticks are the
                // archive's contents rather than the last import's choices.
                var chosen by remember(pending) { mutableStateOf(pending.manifest.included) }
                val available = pending.manifest.included

                PiSection(title = text.settings.backupImportSection) {
                    BasicComponent(
                        title = text.settings.backupReviewTitle,
                        summary = text.settings.backupReviewFrom(
                            version = pending.manifest.app,
                            // The day only: the manifest's stamp is UTC and a time of
                            // day would be right in one time zone and wrong in the rest.
                            date = pending.manifest.createdAt.take(ISO_DATE_LENGTH),
                        ),
                    )
                    // Nothing tickable when the archive carries nothing: the note below
                    // says why, and the title row stays so the reader still sees which
                    // archive was read.
                    if (available.isNotEmpty()) {
                        Categories(
                            offered = available,
                            selected = chosen,
                            enabled = !busy,
                            onToggle = { category -> chosen = chosen.toggle(category) },
                        )
                        // What the archive carries, said plainly: whether a key is in it
                        // is not something the reader can see from the file name, and it
                        // is the one fact that decides whether this archive may be passed
                        // on.
                        BasicComponent(
                            title = if (pending.manifest.apiKeys) {
                                text.settings.backupReviewKeys
                            } else {
                                text.settings.backupReviewNoKeys
                            },
                        )
                    }
                }
                // The destructive half, in the error role: the wording says what a
                // restore does *and* what it does not, and the button that acts on it is
                // the next thing below the card.
                PiSection {
                    SettingsNote(text.settings.backupImportNote, danger = true)
                }
                RunActions(
                    label = text.settings.backupImportConfirm,
                    onAction = { manager.restore(chosen) },
                    dismissLabel = text.common.cancel,
                    enabled = !busy && chosen.isNotEmpty(),
                    onDismiss = if (busy) null else manager::cancelReview,
                    working = importWorking,
                )
            }
        }
    }
}

/**
 * The six switches, drawn from the enum rather than written out.
 *
 * Both directions draw the same list and the difference is only [offered], so a
 * seventh category is one entry in [BackupCategory], one pair of strings and no
 * change here. The alternative — two hand-written runs of six switches — is two
 * lists to keep in step with the enum, which is how a category comes to be
 * exportable and not restorable.
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
    shown.forEach { category ->
        val (title, subtitle) = category.label(text)
        SwitchPreference(
            title = title,
            summary = subtitle,
            checked = category in selected,
            enabled = enabled,
            onCheckedChange = { onToggle(category) },
        )
    }
}

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
