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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.R
import pi.kit.mob.data.Workspace
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.Strings
import pi.kit.mob.ui.kit.LocalPiBackdrop
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiCustomDialog
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiSheetSection
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import pi.kit.mob.ui.settings.SettingsChoiceRow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val revision by WorkspaceStore.revision.collectAsStateWithLifecycle()
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
    val revision by WorkspaceStore.revision.collectAsStateWithLifecycle()
    return remember(revision, context, env) {
        WorkspaceStore.list(context, env).map { it.path }.toSet()
    }
}

/**
 * The picker's own side gutter.
 *
 * Zero because `PiSheet` already spends the sheet's 16dp gutter: adding another 16 here
 * put the path field and the listing 32dp from the edge, the narrow column the sheet
 * rewrite removed. The constant stays so a future host with no gutter has one place to
 * state it.
 */
private val PICKER_GUTTER = 0.dp

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
     * the app's snackbar lives in the activity's window underneath, so a message raised
     * from here is dimmed by the sheet's scrim or hidden by the panel — a button that
     * appears to do nothing. `null` means the folder was taken, and the caller closes the
     * sheet.
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
    // The path field is state-based because this sheet is a glass surface: beta02's
    // `GlassTextField` is the field drawn for that material, and its state overload is
    // the API the library asks new fields to use.
    val typed = remember { TextFieldState(expand(home)) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val newName = remember { TextFieldState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun go(to: String) {
        refusal = null
        // A tap on a row is a tap *away* from the path field: without this the keyboard
        // stayed up over the list the row had just navigated to, and the field kept a
        // focus ring that no longer matched what the sheet was showing.
        focus.clearFocus()
        path = expand(to)
        typed.setTextAndPlaceCursorAtEnd(path)
    }

    // The store's revision is in the key so the listing is refreshed when a folder is
    // added from this sheet and it stays open (a refusal leaves it open). The walk is
    // on IO and published through `produceState`, like the files page's listing: a
    // `listFiles()` in composition blocks the sheet's enter animation, and on
    // `/sdcard` with thousands of entries it is a visible stall.
    val revision by WorkspaceStore.revision.collectAsStateWithLifecycle()
    val listing by produceState<List<File>?>(initialValue = null, path, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(path).listFiles() }.getOrNull()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                // Ordinary order. The old comparator put every name starting with "a"
                // first, which lifted `android`, `assets` and `app` above the folder
                // the reader had just opened; a path browser has no reason to hide
                // that rule.
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }
    val directories = listing.orEmpty()

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
        GlassTextField(
            state = typed,
            label = text.chat.workspacePickerPath,
            cornerRadius = PiCorner.radius,
            lineLimits = TextFieldLineLimits.SingleLine,
            // The pair every other field in the app passes: without it this one alone
            // draws the library's blue focus ring and a caret the ring's colour.
            colors = GlassTextFieldDefaults.textFieldColors(
                borderColor = Color.Transparent,
            ),
            cursorBrush = piFieldCursor(),
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            onKeyboardAction = {
                keyboard?.hide()
                go(typed.text.toString())
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PICKER_GUTTER, end = PICKER_GUTTER, top = 8.dp),
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
                newName.setTextAndPlaceCursorAtEnd("")
                creating = true
            }
        }
        PiSheetSection {
            if (listing == null) {
                // Unreadable rather than empty: listFiles answers null for a directory this
                // process may not open, which on a phone is almost always the grant missing
                // for something under /sdcard.
                if (!granted) {
                    ArrowPreference(title = text.chat.workspacePickerGrant, onClick = onGrant)
                }
                Text(
                    text = text.chat.workspacePickerUnreadable,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else if (directories.isEmpty()) {
                Text(
                    text = text.chat.workspacePickerEmpty,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            directories.forEach { directory ->
                val added = directory.absolutePath in existing
                ArrowPreference(
                    title = directory.name,
                    // The trailing badge sits beside the library's own arrow, which is what
                    // a folder row is: a way into its children. A row already in the list
                    // still opens (it may have children), and the badge is what stops the
                    // reader adding it twice.
                    endActions = if (added) {
                        {
                            Text(
                                text = text.chat.workspacePickerAdded,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                    } else {
                        {}
                    },
                    onClick = { go(directory.absolutePath) },
                )
            }
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
            // The sheet's one committing action, so it is the primary fill rather than
            // a grey secondary one: "用这个文件夹" is the reason the picker is open, and
            // the grey fill read as a disabled control under a row of blue shortcuts.
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text(text = text.chat.workspacePickerChoose)
        }
    }

    // Making a folder, where the folders are. It creates inside the path the sheet is
    // *showing*, which is the only place it could mean: the reader navigated here.
    // The same `PiCustomDialog` as the workspace list above: the list dialog is glass
    // over the page, and this one is a window only when the sheet itself is a window.
    val newNameText = newName.text.toString()
    PiCustomDialog(
        show = creating,
        title = text.chat.workspacePickerNewFolderTitle,
        onDismissRequest = { creating = false },
    ) {
        GlassTextField(
            state = newName,
            modifier = Modifier.fillMaxWidth(),
            label = text.chat.workspacePickerNewFolderName,
            useLabelAsPlaceholder = true,
            lineLimits = TextFieldLineLimits.SingleLine,
            cornerRadius = PiCorner.radius,
            colors = GlassTextFieldDefaults.textFieldColors(
                borderColor = Color.Transparent,
            ),
            cursorBrush = piFieldCursor(),
        )
        PiDialogAnswers(
            dismiss = text.common.cancel,
            onDismiss = { creating = false },
            confirm = text.chat.workspacePickerCreate,
            onConfirm = {
                val typed = newNameText.trim()
                val target = File(path, typed)
                val made = typed.isNotBlank() &&
                    runCatching { target.mkdirs() || target.isDirectory }.getOrDefault(false)
                creating = false
                if (made) {
                    go(target.absolutePath)
                } else {
                    refusal = text.chat.workspacePickerCreateFailed
                }
            },
            // Nothing to create while the field is empty, or while the name is a path.
            confirmEnabled = newNameText.isNotBlank() && !newNameText.contains('/'),
            modifier = Modifier.padding(top = 12.dp),
            glass = LocalPiBackdrop.current != null,
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
 * The default workspace, as a bottom sheet of choices.
 *
 * A settings question is answered in a sheet now, not in a centred dialog: the list
 * is long enough to scroll, a sheet is what the reference uses for every list of
 * choices, and the page behind it stays visible at the top instead of being hidden
 * behind a card. The row that opens it is the agent page's, and the value it writes
 * is what the *next* conversation starts in — this conversation keeps the workspace
 * it was launched in, which is the composer's chip to change.
 *
 * **One tap is the answer.** The previous dialog moved a radio mark under the finger
 * and then asked for a second tap on 确定, so choosing a folder was three taps and a
 * dismissal. A sheet row that applies immediately is the interaction every settings
 * list in the reference has, and a selection that changes nothing is a read rather
 * than an edit — `SettingsChoiceRow` only buzzes when the answer actually changes.
 *
 * The built-in workspace cannot be removed, and neither can the default: removing it
 * would leave the preference pointing at a folder the list no longer offers. A folder
 * deleted from the file manager needs no bin at all; `WorkspaceStore.list` drops it on
 * sight.
 */
@Composable
internal fun WorkspaceSheet(
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
    fun removable(workspace: Workspace): Boolean =
        !workspace.builtIn && workspace.path != defaultPath

    PiSheet(
        show = show,
        title = text.settings.defaultWorkspace,
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(bottom = piSheetBottomInset()),
        ) {
            PiSheetSection {
                workspaces.forEach { workspace ->
                    SettingsChoiceRow(
                        title = text.chat.workspaceChip(
                            workspace.label,
                            workspace.path == defaultPath,
                        ),
                        summary = workspace.displayPath(home),
                        selected = workspace.path == defaultPath,
                        checkAtStart = true,
                        onClick = { onPick(workspace.path) },
                        endActions = if (removable(workspace)) {
                            {
                                IconButton(
                                    onClick = { onRemove(workspace.path) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    // The bin, at the 16dp a sheet row's trailing mark is
                                    // drawn at: taking a workspace out of the list is the
                                    // same act as taking a model or a saved key out of its
                                    // list, so it wears the same glyph (§13).
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = text.chat.workspaceRemove,
                                        modifier = Modifier.size(16.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            // Adding is a row of its own rather than a third answer beside two buttons:
            // it is the one thing here that leaves the sheet, and it must not read as a
            // way to dismiss the question.
            PiSheetSection {
                ArrowPreference(
                    title = text.chat.addWorkspace,
                    onClick = onAdd,
                )
            }
        }
    }
}
