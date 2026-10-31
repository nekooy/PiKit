package pi.kit.mob.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.Workspace
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.Strings
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The workspace list a chip, a menu or a dialog draws, rebuilt when the set changes.
 *
 * Read through [WorkspaceStore] rather than threaded down from a page: the composer and
 * the history page are two places that draw the same list, and a list that had to be
 * passed through every layer between them is a list the next surface would forget.
 * `WorkspaceStore.revision` is the store's own notification, so a folder added in the
 * picker appears in the menu behind it without a restart; `savedDefault` is in the key as
 * well because it decides the `（默认）` suffix, and the settings page writes it rather than
 * the store.
 */
@Composable
internal fun rememberWorkspaceList(
    context: Context,
    env: TermuxEnv,
    savedDefault: String,
): List<Workspace> {
    val revision by WorkspaceStore.revision.collectAsState()
    return remember(revision, context, env, savedDefault) {
        WorkspaceStore.list(context, env)
    }
}

/** A sheet's own side gutter, matching the composer's other sheets. */
private val PICKER_GUTTER = 16.dp

/**
 * The folder picker: descend from `$HOME`, then add the folder it is showing.
 *
 * A browser rather than a text field, and rooted at `$HOME` rather than at `/`: every
 * workspace is a project directory, project directories live in the agent's home, and a
 * picker that could walk to `/sdcard` or `/data` would offer paths the permission tiers
 * exist to keep the agent out of. `storage` and dot-directories are hidden for the same
 * reason — the first is the user's phone (granted on the storage page, and refused as a
 * workspace by [WorkspaceStore]), and the second holds pi's own configuration.
 *
 * The sheet has one action, and it acts on the folder *shown* rather than on a selection:
 * the reader descended to get here, and the path above the button is what will be added.
 * It is shared by the two places a workspace can be added from — the composer's workspace
 * menu and the agent page's default-workspace dialog — because a second picker is a second
 * set of rules about what may be picked.
 *
 * `home` is passed rather than read from the environment so the caller names the root: the
 * composer already holds the session's `TermuxEnv`, and a picker that resolved its own
 * would be a second answer to "where does the agent live".
 */
@Composable
internal fun WorkspacePickerSheet(
    text: Strings,
    home: String,
    /**
     * Adds [folder], and answers with the sentence to show when it cannot be added.
     *
     * A *returned* refusal rather than a toast, because this body is a window of its own:
     * the app's snackbar lives in the activity's window underneath, so a message raised
     * from here is dimmed by the sheet's scrim or hidden by the panel — a button that
     * appears to do nothing. `null` means the folder was taken, and the caller closes the
     * sheet.
     */
    onAdd: (String) -> String?,
) {
    var path by remember { mutableStateOf(home) }
    var refusal by remember { mutableStateOf<String?>(null) }
    // The store's revision is in the key so the listing is refreshed when a folder is
    // added from this sheet and it stays open (a refusal leaves it open).
    val revision by WorkspaceStore.revision.collectAsState()
    val directories = remember(path, revision) {
        runCatching {
            java.io.File(path).listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "storage" }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            .padding(bottom = piSheetBottomInset()),
    ) {
        Text(
            text = text.chat.workspacePickerBody,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 4.dp),
        )
        // The folder this sheet is showing. Monospace and one line: it is a path, and a
        // path's two ends are what identify it.
        Text(
            text = path,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 6.dp),
        )
        // The refusal, where the button that was refused is rather than in a snackbar the
        // sheet covers. Cleared by the next attempt.
        refusal?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 4.dp),
            )
        }
        if (path != home) {
            PiMenuRow(
                title = text.chat.workspacePickerParent,
                onClick = {
                    refusal = null
                    path = java.io.File(path).parent ?: home
                },
            )
        }
        if (directories.isEmpty()) {
            Text(
                text = text.chat.workspacePickerEmpty,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 12.dp),
            )
        }
        directories.forEach { directory ->
            PiMenuRow(
                title = directory.name,
                onClick = {
                    refusal = null
                    path = directory.absolutePath
                },
            )
        }
        Button(
            cornerRadius = PiCorner.radius,
            onClick = { refusal = onAdd(path) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PICKER_GUTTER, end = PICKER_GUTTER, top = 12.dp),
        ) {
            Text(text = text.chat.workspacePickerChoose)
        }
    }
}

/**
 * The default workspace, as the app's dialog: the workspaces with one mark under them,
 * an "add" row, and the two answers that close it.
 *
 * A dialog rather than a menu, because it is a *settings* question: a dropdown anchored
 * to a settings row borrows the shape of a toolbar control's menu, and this app's
 * settings pages answer their choices in a dialog with the answers under the question
 * (the language picker is the same shape, §13). The row that opens it is the agent page's,
 * and the value it writes is what the *next* conversation starts in — this conversation
 * keeps the workspace it was launched in, which is the composer's chip to change.
 *
 * The mark moves under the finger and nothing is written until the confirm is tapped, and
 * it is keyed on `show` so a cancelled question does not carry its answer into the next
 * opening — the language dialog's two rules.
 */
@Composable
internal fun WorkspaceDialog(
    show: Boolean,
    text: Strings,
    workspaces: List<Workspace>,
    home: String,
    defaultPath: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(show, defaultPath) { mutableStateOf(defaultPath) }

    // The built-in workspace cannot be removed — it is the one every install starts in —
    // and the default cannot be either: removing it would leave the preference pointing
    // at a folder the list no longer offers. A folder that is deleted from the file
    // manager needs no ✕ at all; `WorkspaceStore.list` drops it on sight.
    fun removable(workspace: Workspace): Boolean =
        !workspace.builtIn && workspace.path != defaultPath

    WindowDialog(
        show = show,
        title = text.settings.defaultWorkspace,
        // No horizontal inset for the content, so the rows span the dialog: the title and
        // the answers are centred and have their own margins, and a row's press
        // indication is clipped to the bounds it is given.
        insideMargin = DpSize(0.dp, DialogDefaults.insideMargin.height),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            workspaces.forEach { workspace ->
                RadioButtonPreference(
                    title = text.chat.workspaceChip(workspace.label, workspace.path == defaultPath),
                    summary = workspace.displayPath(home),
                    selected = workspace.path == chosen,
                    onClick = { chosen = workspace.path },
                    endActions = {
                        if (removable(workspace)) {
                            IconButton(
                                onClick = { onRemove(workspace.path) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = text.chat.workspaceRemove,
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    },
                )
            }
            // Adding is a row of its own rather than a third answer beside the two
            // buttons: it is the one thing here that leaves the dialog, and it must not
            // read as a way to dismiss the question.
            PiMenuRow(title = text.chat.addWorkspace, onClick = onAdd)
            PiDialogAnswers(
                dismiss = text.common.cancel,
                onDismiss = onDismiss,
                confirm = text.common.confirm,
                onConfirm = { onPick(chosen) },
                // The answers keep the dialog's own gutter even though the list above them
                // does not: a button's fill has to stop short of the panel's edge, where a
                // row's highlight has to reach it.
                modifier = Modifier.padding(
                    horizontal = DialogDefaults.insideMargin.width,
                    vertical = 12.dp,
                ),
            )
        }
    }
}
