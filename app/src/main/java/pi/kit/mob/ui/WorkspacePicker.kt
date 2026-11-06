package pi.kit.mob.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import pi.kit.mob.R
import pi.kit.mob.data.Workspace
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.Strings
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiMenuRow
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Home
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

/**
 * The absolute paths that are already workspaces.
 *
 * The picker's rows carry an `已添加` badge from this, and it is read here rather than
 * threaded down because the two places that open the picker are different pages and the
 * store is the one owner of the set.
 */
@Composable
internal fun rememberWorkspacePaths(context: Context, env: TermuxEnv): Set<String> {
    val revision by WorkspaceStore.revision.collectAsState()
    return remember(revision, context, env) {
        WorkspaceStore.list(context, env).map { it.path }.toSet()
    }
}

/** A sheet's own side gutter, matching the composer's other sheets. */
private val PICKER_GUTTER = 16.dp

/**
 * The folder picker: anywhere the app can read, reached by tapping or by typing.
 *
 * ## Why it is not rooted at `$HOME` any more
 *
 * It was, on the argument that a project directory lives in the agent's home and a picker
 * that could walk to `/sdcard` would offer paths the permission tiers exist to keep the
 * agent out of. The tiers removed the second half of that argument — what the agent may
 * *write* is the workspace it was started in (§14), so a workspace on `/sdcard/Dev` is
 * simply a project on `/sdcard/Dev`, and the folder switches on the storage page that used
 * to be the only way to reach one are gone with the page. What is left is "any folder this
 * process can read", and the picker says so: up from `$HOME` reaches `/`, and the two
 * shortcuts go to the two roots a reader actually starts from.
 *
 * ## Why it is easier to use
 *
 * Three things were wrong with a list you could only descend:
 *
 *  - **there was no way in except tapping**, so a path copied from a chat message or a
 *    terminal had to be walked to by hand — the path is a text field now, and `Go` jumps;
 *  - **`$HOME` was the ceiling**, so a sibling of the workspace, or anything on the phone,
 *    was unreachable — the parent row goes all the way to `/`;
 *  - **a folder that was already a workspace looked like any other**, so the honest tap
 *    produced a refusal — those rows say `已添加` and tapping one selects it.
 *
 * The sheet has one action and it acts on the folder *shown* rather than on a selection:
 * the reader navigated to get here, and the path above the button is what will be added.
 *
 * `home` is passed rather than read from the environment so the caller names the root: the
 * composer already holds the session's `TermuxEnv`, and a picker that resolved its own
 * would be a second answer to "where does the agent live".
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WorkspacePickerSheet(
    text: Strings,
    home: String,
    /** `/sdcard`, or null on a device that does not have one. */
    shared: String?,
    /** Absolute paths that are already workspaces, so the list can say so. */
    existing: Set<String>,
    /** Android's "all files access": without it, `/sdcard` cannot be listed. */
    granted: Boolean,
    onGrant: () -> Unit,
    /**
     * Adds [folder], and answers with the sentence to show when it cannot be added.
     *
     * A *returned* refusal rather than a toast, because this body is a window of its own:
     * a toast is drawn by the system, but it is anchored to the app's own window, so a
     * message raised from here lands behind the sheet's scrim or under the panel — a
     * button that appears to do nothing. `null` means the folder was taken, and the
     * caller closes the sheet.
     */
    onAdd: (String) -> String?,
) {
    fun expand(raw: String): String {
        val value = raw.trim()
        val base = home.trimEnd('/')
        return when {
            value == "~" -> base
            value.startsWith("~/") -> base + value.removePrefix("~")
            value == "\$HOME" -> base
            value.startsWith("\$HOME/") -> base + value.removePrefix("\$HOME")
            else -> value
        }.let { if (it.length > 1) it.trimEnd('/') else it }
    }

    var path by remember { mutableStateOf(expand(home)) }
    var typed by remember { mutableStateOf(path) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun go(to: String) {
        refusal = null
        // A tap on a row is a tap *away* from the path field: without this the keyboard
        // stayed up over the list the row had just navigated to, and the field kept a
        // focus ring that no longer matched what the sheet was showing.
        focus.clearFocus()
        path = expand(to)
        typed = path
    }

    // The store's revision is in the key so the listing is refreshed when a folder is
    // added from this sheet and it stays open (a refusal leaves it open).
    val revision by WorkspaceStore.revision.collectAsState()
    val listing = remember(path, revision) {
        runCatching { File(path).listFiles() }.getOrNull()
    }
    val directories = remember(listing) {
        listing
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedWith(compareBy({ !it.name.lowercase().startsWith("a") }, { it.name.lowercase() }))
            .orEmpty()
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
        // The path is editable: a path copied from a message or the terminal is the
        // fastest way to a folder, and it was the one thing a browser could not do.
        TextField(
            value = typed,
            onValueChange = { typed = it },
            label = text.chat.workspacePickerPath,
            cornerRadius = PiCorner.radius,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    keyboard?.hide()
                    go(typed)
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PICKER_GUTTER, end = PICKER_GUTTER, top = 8.dp),
        )
        // The refusal, where the button that was refused is rather than in a toast the
        // sheet covers. Cleared by the next attempt.
        refusal?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 4.dp),
            )
        }
        // The shortcuts are *not* folders: they go somewhere, or make something, rather
        // than being somewhere. One row of small chips rather than one row each — four
        // rows of them took half of a sheet whose point is the listing under them, and
        // the words are kept because a glyph alone did not say which root it meant.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PICKER_GUTTER, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path != home) {
                NavChip(text.chat.workspacePickerHome, rememberVectorPainter(MiuixIcons.Home)) { go(home) }
            }
            if (shared != null && path != shared) {
                NavChip(text.chat.workspacePickerShared, painterResource(R.drawable.ic_smartphone)) {
                    if (granted) {
                        go(shared)
                    } else {
                        // Without the grant the folder cannot be listed, and a row that
                        // silently does nothing is what this sheet had before.
                        onGrant()
                    }
                }
            }
            if (path != "/") {
                NavChip(text.chat.workspacePickerParent, rememberVectorPainter(MiuixIcons.Back)) {
                    go(File(path).parent ?: "/")
                }
            }
            NavChip(text.chat.workspacePickerNewFolder, rememberVectorPainter(MiuixIcons.Add)) {
                newName = ""
                creating = true
            }
        }
        if (listing == null) {
            // Unreadable rather than empty: listFiles answers null for a directory this
            // process may not open, which on a phone is almost always the grant missing
            // for something under /sdcard.
            if (!granted) {
                PiMenuRow(title = text.chat.workspacePickerGrant, onClick = onGrant)
            }
            Text(
                text = text.chat.workspacePickerUnreadable,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 12.dp),
            )
        } else if (directories.isEmpty()) {
            Text(
                text = text.chat.workspacePickerEmpty,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PICKER_GUTTER, vertical = 12.dp),
            )
        }
        directories.forEach { directory ->
            val added = directory.absolutePath in existing
            PiMenuRow(
                title = directory.name,
                // The trailing slot is the badge rather than an arrow: a row that is
                // already a workspace is still worth opening (it may have children), and
                // the badge is what stops the reader adding it twice.
                trailing = if (added) {
                    { Text(text = text.chat.workspacePickerAdded, style = MiuixTheme.textStyles.footnote1) }
                } else {
                    null
                },
                onClick = { go(directory.absolutePath) },
            )
        }
        Button(
            cornerRadius = PiCorner.radius,
            onClick = {
                focus.clearFocus()
                refusal = onAdd(path)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PICKER_GUTTER, end = PICKER_GUTTER, top = 12.dp),
        ) {
            Text(text = text.chat.workspacePickerChoose)
        }
    }

    // Making a folder, where the folders are. It creates inside the path the sheet is
    // *showing*, which is the only place it could mean: the reader navigated here.
    WindowDialog(
        show = creating,
        title = text.chat.workspacePickerNewFolderTitle,
        onDismissRequest = { creating = false },
    ) {
        TextField(
            cornerRadius = PiCorner.radius,
            value = newName,
            onValueChange = { newName = it },
            modifier = Modifier.fillMaxWidth(),
            label = text.chat.workspacePickerNewFolderName,
            useLabelAsPlaceholder = true,
            singleLine = true,
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
        )
        PiDialogAnswers(
            dismiss = text.common.cancel,
            onDismiss = { creating = false },
            confirm = text.chat.workspacePickerCreate,
            onConfirm = {
                val target = File(path, newName.trim())
                val made = newName.isNotBlank() &&
                    runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
                creating = false
                if (made) {
                    go(target.absolutePath)
                } else {
                    refusal = text.chat.workspacePickerCreateFailed
                }
            },
            // Nothing to create while the field is empty, or while the name is a path.
            confirmEnabled = newName.isNotBlank() && !newName.contains('/'),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * A shortcut, as a small pill beside the path rather than a list row of its own.
 *
 * Four rows of navigation above a listing is a sheet that is half chrome; a row of chips
 * is one line, and the words stay because a glyph alone did not say which root it meant
 * (a house is "home", a stack of layers is not obviously "the phone's storage"). It is the
 * same accent ink the rows used, so the two families stay distinguishable from the folder
 * listing under them.
 */
@Composable
private fun NavChip(
    label: String,
    glyph: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        cornerRadius = 14.dp,
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        // The *primary* pair, which is the blue every filled action in the app wears — the
        // settings pages' actions, the sheet's own confirm. Plain `buttonColors()` is the
        // secondary pair (a grey on the surface step), which on this sheet read as four
        // disabled buttons above a list.
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = glyph,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
            Text(text = label, style = MiuixTheme.textStyles.footnote2, maxLines = 1)
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
