package pi.kit.mob.ui.settings

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.ReadOnlyBody
import pi.kit.mob.ui.components.Sheet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which of the user's folders the agent may reach.
 *
 * ## Why this page exists
 *
 * The previous design had one switch: "All files access". Granting it linked the
 * whole of `/sdcard` into the environment as `~/storage`, and an autonomous
 * agent with recursive-delete capability had the user's photos, documents and
 * backups in reach as ordinary directories. That is the design that could — and
 * did — remove a phone's files.
 *
 * This page replaces it with an explicit, per-folder decision:
 *
 *  * The **default is nothing**. A fresh install cannot see shared storage at
 *    all, and the agent works inside `$HOME`.
 *  * Each folder is a separate switch, and **every one of them asks for
 *    confirmation** before it is switched on. Only the roots this page once called
 *    "broad" (the whole tree, Documents, Pictures, DCIM) used to warn, on the
 *    theory that a photo album costs more than a download — but a user who expects
 *    a prompt for one switch and gets none for the next stops reading them, and the
 *    warning is about the agent's *reach*, not about which folder it is.
 *  * **The whole of shared storage subsumes the rest.** While it is on, the rows
 *    below it are disabled and shown as included: they are already reachable, and a
 *    switch that changes nothing is a lie about what it does.
 *  * **Any folder can be added**, not only the seven named ones
 *    ([StorageAccess.Policy.custom]): the named list is a shortcut, and a user who
 *    keeps notes in `/sdcard/Notes` should not have to grant all of `/sdcard`.
 *  * Revoking never deletes anything: it removes the links the environment uses, not
 *    the files. A folder the user added is taken back only after a confirmation
 *    ([storageCustomRemoveTitle]) because its ✕ is the one control here with no undo;
 *    switching a named folder off is still one tap, because the switch itself is the
 *    undo.
 *  * The Android permission is still required — there is no way around it for a
 *    terminal app — but it is framed for what it is: a prerequisite, not the
 *    decision. What the agent can reach is decided here.
 *
 * ## What the switches do and do not enforce
 *
 * They decide what PiKit *offers* the agent (`~/storage/<name>`, created only for the
 * folders that are on) and what it *permits*: the `tool_call` guard refuses a
 * command that names shared storage outside this list. That guard is the
 * enforcement, because it is the only place the agent's actions pass through —
 * Android grants "all files access" to the process and the agent runs as this app,
 * so no filesystem-level switch could be honest about it. `pi-safety-guard.ts`
 * carries the details, and the page's copy says "PiKit refuses", not "Android
 * hides", for the same reason.
 */
@Composable
fun StoragePage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val context = LocalContext.current
    val sheets = LocalSheetHost.current

    var policy by remember { mutableStateOf(session.storagePolicy()) }
    var granted by remember { mutableStateOf(StorageAccess.isGranted()) }
    // A folder waiting for the "let the agent reach it?" answer. One dialog for
    // every way in — a switch, or a folder chosen in the picker.
    var pendingGrant by remember { mutableStateOf<String?>(null) }
    var confirmRevoke by remember { mutableStateOf(false) }
    // A folder the user's ✕ asked to take back, held until the question is answered.
    // The ✕ used to remove it on the tap, which is the report this answers: the one
    // control in this section that a mis-tap cannot be undone from this page.
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var showGrantPrompt by remember { mutableStateOf(false) }
    // Where the folder picker is looking; null when it is closed.
    var pickerDir by remember { mutableStateOf<File?>(null) }

    // "All files access" has no result callback — the only signal that the user
    // came back from the system page is the lifecycle. Re-reading here also
    // rebuilds the farm, because the links can only be created once the grant
    // exists.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StorageAccess.isGranted()
                if (granted) session.syncStorageLinks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun apply(next: StorageAccess.Policy) {
        policy = next
        session.setStoragePolicy(next)
    }

    // The answer to "let the agent reach it?" — held as a callback so that every
    // way in (a switch, or a folder chosen in the picker) raises the same dialog.
    var confirmGrant: (() -> Unit)? by remember { mutableStateOf(null) }

    fun askToGrant(name: String, onConfirm: () -> Unit) {
        if (!granted) {
            showGrantPrompt = true
            return
        }
        pendingGrant = name
        confirmGrant = onConfirm
    }

    /**
     * Opens the folder picker as a sheet, from an event and never from composition.
     *
     * The body reads `pickerDir` itself, so stepping into a folder recomposes the
     * list in place: the sheet is one modal panel whose *contents* move, not a
     * sheet that is dismissed and re-shown per tap (which would animate the panel
     * up and down the screen on every step).
     */
    fun openPicker(start: File) {
        pickerDir = start
        sheets.show(Sheet(key = "storage-picker") {
            val directory = pickerDir ?: return@Sheet
            val root = StorageAccess.sharedRoot()
            // Listed off the main thread, and above the body because a lazy list's
            // content lambda is not composable. This used to run inside that lambda,
            // so every step into a folder did one `stat` per entry — on `/sdcard`,
            // which is a FUSE mount, hundreds of syscalls on the frame thread each
            // time the sheet recomposed. The picker waits one frame for the list
            // instead, and the two navigation rows are on screen while it does.
            val children by produceState(initialValue = emptyList<File>(), directory) {
                value = withContext(Dispatchers.IO) {
                    directory.listFiles()
                        ?.filter { it.isDirectory && it.canRead() }
                        ?.sortedBy { it.name.lowercase() }
                        .orEmpty()
                }
            }
            // A body of our own rather than `PickerBody`: every row of that one
            // dismisses the sheet on tap (it is a menu), and stepping through a
            // directory tree is not a menu — the panel would animate away and back
            // on every step. These rows navigate, and only "use this folder" closes.
            ReadOnlyBody(title = text.settings.storageCustomPickTitle) {
                item(key = "use") {
                    PickerRow(
                        label = text.settings.storageCustomPickUse,
                        description = StorageAccess.displayPathOf(directory.absolutePath),
                        leading = Icons.Filled.Check,
                        onClick = {
                            val chosen = directory.absolutePath
                            pickerDir = null
                            sheets.dismiss()
                            askToGrant(StorageAccess.displayPathOf(chosen)) {
                                apply(policy.plus(chosen))
                            }
                        },
                    )
                }
                if (root != null && directory.absolutePath != root.absolutePath) {
                    item(key = "up") {
                        PickerRow(
                            label = text.settings.storageCustomPickUp,
                            description = StorageAccess.displayPathOf(
                                directory.parentFile?.absolutePath ?: directory.absolutePath,
                            ),
                            leading = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = { pickerDir = directory.parentFile },
                        )
                    }
                }
                items(children, key = { it.absolutePath }) { child ->
                    PickerRow(
                        label = child.name,
                        description = null,
                        leading = Icons.Filled.Folder,
                        onClick = { pickerDir = child },
                    )
                }
            }
        })
    }

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.storagePageTitle,
            subtitle = text.settings.storagePageSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            AccessLevelCard(text, policy, granted)

            if (!granted) {
                GrantCard(
                    text = text,
                    onOpenSettings = { showGrantPrompt = true },
                )
            }

            SettingsSection(text.settings.storageFolders) {
                StorageAccess.Root.entries.forEachIndexed { index, root ->
                    if (index > 0) SettingsDivider()
                    // While the whole tree is on, a sub-folder's switch changes
                    // nothing: it is already reachable through `shared`. Disabled
                    // and shown as included rather than left tappable. The predicate
                    // is `Policy.isSubsumed`, which `StorageAccess.linkPlanFor` also
                    // applies — the two disagreed once, and the farm named
                    // `/sdcard/Download` twice for it.
                    val subsumed = policy.isSubsumed(root)
                    FolderRow(
                        label = folderLabel(text, root),
                        path = StorageAccess.displayTargetOf(root),
                        checked = subsumed || root in policy.roots,
                        enabled = granted && !subsumed,
                        onToggle = { wanted ->
                            if (wanted) {
                                askToGrant(folderLabel(text, root)) {
                                    apply(policy.toggled(root, true))
                                }
                            } else {
                                apply(policy.toggled(root, false))
                            }
                        },
                    )
                }
            }

            if (policy.isUnrestricted) {
                SettingsNote(text.settings.storageBroadWarning)
            }

            SettingsSection(text.settings.storageCustomSection) {
                policy.custom.sorted().forEachIndexed { index, path ->
                    if (index > 0) SettingsDivider()
                    CustomFolderRow(
                        path = path,
                        removeLabel = text.settings.storageCustomRemove,
                        onRemove = { pendingRemove = path },
                    )
                }
                if (policy.custom.isNotEmpty()) SettingsDivider()
                SettingsRow(
                    title = text.settings.storageCustomAdd,
                    subtitle = if (policy.isUnrestricted) {
                        text.settings.storageCustomSubsumed
                    } else {
                        text.settings.storageCustomAddBody
                    },
                    icon = Icons.Filled.CreateNewFolder,
                    enabled = granted && !policy.isUnrestricted,
                    onClick = {
                        if (!granted) {
                            showGrantPrompt = true
                        } else {
                            StorageAccess.sharedRoot()?.let { openPicker(it) }
                        }
                    },
                )
            }

            if (!policy.isEmpty) {
                SettingsSection(text.settings.storageRevokeAll) {
                    SettingsRow(
                        title = text.settings.storageRevokeAll,
                        subtitle = text.settings.storageRevokeAllBody,
                        icon = Icons.Filled.DeleteForever,
                        danger = true,
                        onClick = { confirmRevoke = true },
                    )
                }
            }
        }
    }

    val confirm = confirmGrant
    if (pendingGrant != null && confirm != null) {
        val name = pendingGrant
        AlertDialog(
            onDismissRequest = {
                pendingGrant = null
                confirmGrant = null
            },
            icon = { Icon(Icons.Filled.SdCard, contentDescription = null) },
            title = { Text(text.settings.storageConfirmGrantTitle) },
            text = { Text(text.settings.storageConfirmGrantBody(name.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm()
                        pendingGrant = null
                        confirmGrant = null
                    },
                ) { Text(text.common.confirm) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingGrant = null
                        confirmGrant = null
                    },
                ) { Text(text.common.cancel) }
            },
        )
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(text.settings.storageRevokeAllTitle) },
            text = { Text(text.settings.storageRevokeAllBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        apply(StorageAccess.Policy.NONE)
                        confirmRevoke = false
                    },
                ) {
                    // Red: revoking every grant drops the symlink farm and every folder
                    // the agent could reach, and the user has to grant them again one by
                    // one. `NONE` is not a smaller version of a grant — it is the
                    // destructive direction of the same switch.
                    Text(text.common.confirm, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text(text.common.cancel) }
            },
        )
    }

    if (pendingRemove != null) {
        val path = pendingRemove
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(text.settings.storageCustomRemoveTitle) },
            text = { Text(text.settings.storageCustomRemoveBody(path.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        apply(policy.minus(path.orEmpty()))
                        pendingRemove = null
                    },
                ) {
                    Text(
                        text.settings.storageCustomRemove,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(text.common.cancel) }
            },
        )
    }

    if (showGrantPrompt) {
        AlertDialog(
            onDismissRequest = { showGrantPrompt = false },
            title = { Text(text.settings.storageGrantPromptTitle) },
            text = { Text(text.settings.storageGrantPromptBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGrantPrompt = false
                        runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                            .onFailure { Log.w(TAG, "No all-files-access page on this device", it) }
                    },
                ) { Text(text.settings.storageGrantPromptConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { showGrantPrompt = false }) { Text(text.common.cancel) }
            },
        )
    }
}

/**
 * The one-line summary: none, some folders, or everything.
 *
 * Stated rather than implied, because it is the answer to "what can the agent
 * see right now?" and the switch list below is long enough to be misread.
 */
@Composable
private fun AccessLevelCard(text: Strings, policy: StorageAccess.Policy, granted: Boolean) {
    val level = when {
        !granted || policy.isEmpty -> text.settings.storageLevelNone
        policy.isUnrestricted -> text.settings.storageLevelAll
        else -> text.settings.storageLevelSelected
    }
    val grantedPaths = policy.roots.sortedBy { it.ordinal }.map { folderLabel(text, it) } +
        policy.custom.sorted().map { StorageAccess.displayPathOf(it) }
    val detail = when {
        !granted -> text.settings.storageMissing
        policy.isEmpty -> text.settings.storageNoAccessBody
        else -> grantedPaths.joinToString(", ")
    }

    SettingsSection(text.settings.storageAccessLevel) {
        SettingsRow(
            title = level,
            subtitle = detail,
            icon = Icons.Filled.SdCard,
            monospace = false,
        )
    }
    if (policy.isEmpty || !granted) {
        SettingsNote(text.settings.storageNoAccessBody)
    }
}

@Composable
private fun GrantCard(text: Strings, onOpenSettings: () -> Unit) {
    SettingsSection(text.settings.storageGrantPromptTitle) {
        SettingsRow(
            title = text.settings.storageGrant,
            subtitle = text.settings.storageMissing,
            icon = Icons.Filled.SdCard,
            showChevron = true,
            onClick = onOpenSettings,
        )
    }
    SettingsNote(text.settings.storageNote)
}

/**
 * One folder, and whether the agent may reach it.
 *
 * ## Why the row shows the real directory and not the link
 *
 * The subtitle used to be `~/storage/dcim`, which is the name of the *symlink*
 * inside the environment. The real directory on the device is `/sdcard/DCIM`, and
 * the mismatch is the point of the row: a user looking for the folder they
 * recognise in a file manager needs to see the spelling the file manager uses.
 * Both names are shown in the manual — the link is lower case because
 * `termux-setup-storage` created it that way for years and scripts type it.
 *
 * ## Why the label no longer carries the directory name
 *
 * The Camera row read "Camera (DCIM)" so that the folder name appeared twice, once
 * in the label and once in the path under it. The path is the honest place for it
 * and the parenthetical was noise; `storageFolderDcim` is "Camera" now.
 */
@Composable
private fun FolderRow(
    label: String,
    path: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                },
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else DISABLED_ALPHA,
                ),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onToggle,
        )
    }
}

/**
 * One row of the folder picker.
 *
 * Not [ReadOnlySheetRow]: that one dismisses the sheet as part of the tap, which
 * is right for a menu and wrong for a tree — picking a subdirectory has to keep the
 * panel open and move its contents. The leading glyph is what tells the three kinds
 * apart: a tick for "use this folder", a back arrow for going up, a folder for
 * going in.
 */
@Composable
private fun PickerRow(
    label: String,
    description: String?,
    leading: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One folder the user added, with the one action it has: take it back. */
@Composable
private fun CustomFolderRow(
    path: String,
    removeLabel: String,
    onRemove: () -> Unit,
) {
    SettingsRow(
        title = path.substringAfterLast('/').ifEmpty { path },
        subtitle = StorageAccess.displayPathOf(path),
        icon = Icons.Filled.Folder,
        trailing = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = removeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun folderLabel(text: Strings, root: StorageAccess.Root): String = when (root) {
    StorageAccess.Root.Shared -> text.settings.storageFolderShared
    StorageAccess.Root.Downloads -> text.settings.storageFolderDownloads
    StorageAccess.Root.Documents -> text.settings.storageFolderDocuments
    StorageAccess.Root.Pictures -> text.settings.storageFolderPictures
    StorageAccess.Root.Dcim -> text.settings.storageFolderDcim
    StorageAccess.Root.Music -> text.settings.storageFolderMusic
    StorageAccess.Root.Movies -> text.settings.storageFolderMovies
}

/** Material3's disabled content alpha, for a row whose switch does nothing. */
private const val DISABLED_ALPHA = 0.38f

private const val TAG = "PiKit"
