package pi.kit.mob.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pi.kit.mob.env.SafetyGuard
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiAppBarScroll
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetRow
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.design.PiNote
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
 * ## The page is the first of three layers, and it says so
 *
 * ARCHITECTURE §5 puts three independent layers between this agent and the user's
 * files, and each one alone has a hole. This page is the first: the scoped grant
 * below, which decides what is offered and what is permitted (see the section under
 * this one). The second is not on this page at all —
 * `env/SafeDelete.kt` is the only place in the app allowed to remove a directory
 * tree, it refuses any path outside `filesDir`, and it deletes a symlink as a
 * link rather than following it, which is what stops a link into shared storage
 * being walked *through*. That layer is why "revoking never deletes anything" is
 * a promise the page can keep, and why nothing here has to warn about a mis-tap.
 * The third is the tool-call guard at the bottom of the page.
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
 *
 * ## How the page is drawn
 *
 * Labelled groups by subject — what the agent may reach, the folders, the folders
 * the user added, taking it all back, and the guard — rather than six cards of one
 * row each. The switch rows put the switch in the row's trailing slot and leave
 * the row itself inert, which is deliberate: each of these switches can raise a
 * confirmation or restart the agent, so the tap has to be aimed at the control
 * rather than at the sentence beside it. `PiRow`'s own `enabled` dims the label and
 * the path of a row that cannot be moved, which is the by-hand 0.38 alpha the
 * folder rows used to apply.
 *
 * The one destructive row — taking every grant back — carries the error role, and
 * so does every question this page asks whose answer cannot be taken back from
 * here. The role lives on the notice around the wording rather than on the button:
 * `PiButtonKind` is Material's five kinds and none of them is the error colour, so
 * the frame is where that meaning has a home.
 */
@Composable
fun StoragePage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val context = LocalContext.current
    val sheets = LocalSheetHost.current
    // Collected rather than read once: the switch is the store's value, so a change
    // made anywhere reaches the row.
    val settings by session.settingsStore.settings.collectAsState()
    // The guard is a file under `$PREFIX`; reinstalling the runtime (or a restore)
    // can put it there while this page is open. Same lifecycle refresh as `granted`
    // below, so the switch is never offered against a stale install answer.
    var guardInstalled by remember { mutableStateOf(SafetyGuard.isInstalled(session.env)) }

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
    // Turning the guard off, held until the question is answered. One dialog for the
    // one control here with no undo on this page.
    var confirmDisableGuard by remember { mutableStateOf(false) }

    // "All files access" has no result callback — the only signal that the user
    // came back from the system page is the lifecycle. Re-reading here also
    // rebuilds the farm, because the links can only be created once the grant
    // exists. The policy and the guard ride along: both can change while the
    // page is up (a restore, a runtime reinstall), and a row that kept the
    // answer it was composed with is the "needs a restart" class of bug.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StorageAccess.isGranted()
                policy = session.storagePolicy()
                guardInstalled = SafetyGuard.isInstalled(session.env)
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
            //
            // `PiSheetRow` is the design system's row for this panel, and its own
            // 56dp floor is what the picker's rows needed: they used to be 44dp, under
            // the 48dp a target wants and 8dp shorter than the two navigation rows
            // beside them, so every plain folder in the tree was the small row in its
            // own list.
            Column {
                PiSheetTitle(text.settings.storageCustomPickTitle)
                PiSheetList {
                    item(key = "use") {
                        PiSheetRow(
                            label = text.settings.storageCustomPickUse,
                            subtitle = StorageAccess.displayPathOf(directory.absolutePath),
                            leading = { Icon(Icons.Filled.Check, contentDescription = null) },
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
                            PiSheetRow(
                                label = text.settings.storageCustomPickUp,
                                subtitle = StorageAccess.displayPathOf(
                                    directory.parentFile?.absolutePath ?: directory.absolutePath,
                                ),
                                leading = {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                },
                                onClick = { pickerDir = directory.parentFile },
                            )
                        }
                    }
                    items(children, key = { it.absolutePath }) { child ->
                        PiSheetRow(
                            label = child.name,
                            leading = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = { pickerDir = child },
                        )
                    }
                }
            }
        })
    }

    PiScaffold(
        title = text.settings.storagePageTitle,
        subtitle = text.settings.storagePageSubtitle,
        onBack = onBack,
        scrollBehavior = PiAppBarScroll.Pinned,
    ) { content ->
        Column(
            content
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding)
                .padding(bottom = 32.dp),
        ) {
            AccessSection(
                text = text,
                policy = policy,
                granted = granted,
                onOpenSettings = { showGrantPrompt = true },
            )

            PiSectionHeader(text.settings.storageFolders)
            PiGroup {
                StorageAccess.Root.entries.forEachIndexed { index, root ->
                    if (index > 0) PiRowDivider()
                    // While the whole tree is on, a sub-folder's switch changes
                    // nothing: it is already reachable through `shared`. Disabled
                    // and shown as included rather than left tappable. The predicate
                    // is `Policy.isSubsumed`, which `StorageAccess.linkPlanFor` also
                    // applies — the two disagreed once, and the farm named
                    // `/sdcard/Download` twice for it.
                    val subsumed = policy.isSubsumed(root)
                    // The subtitle is the *real* directory — `/sdcard/DCIM` — and not the
                    // link's name inside the environment (`~/storage/dcim`). The mismatch
                    // is the point of the row: a user looking for the folder they
                    // recognise in a file manager needs the spelling the file manager
                    // uses, and `StorageAccess.displayTargetOf` is the one place that
                    // decides it. Both names are in the manual — the link is lower case
                    // because `termux-setup-storage` created it that way for years and
                    // scripts type it.
                    //
                    // The label no longer carries the directory name: the Camera row read
                    // "Camera (DCIM)", so the folder name appeared twice, once in the label
                    // and once in the path under it. The path is the honest place for it,
                    // and `storageFolderDcim` is "Camera" now.
                    PiRow(
                        title = folderLabel(text, root),
                        subtitle = StorageAccess.displayTargetOf(root),
                        leading = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        // The switch is the target and the row is inert: every one of
                        // these can raise a question or restart the agent, and a row
                        // that toggles on a tap aimed at its own text is a grant asked
                        // for by accident.
                        trailing = {
                            Switch(
                                checked = subsumed || root in policy.roots,
                                enabled = granted && !subsumed,
                                onCheckedChange = { wanted ->
                                    if (wanted) {
                                        askToGrant(folderLabel(text, root)) {
                                            apply(policy.toggled(root, true))
                                        }
                                    } else {
                                        apply(policy.toggled(root, false))
                                    }
                                },
                            )
                        },
                        enabled = granted && !subsumed,
                    )
                }
            }

            // The page's one alert, and only while the policy is unrestricted — the
            // one root that means *everything on the phone*. Every row used to carry
            // a warning, and a page where every row is an alert is a page where no
            // alert is read; a folder that is switched off cannot lose anything
            // either. A fresh install, which has an empty policy, is correctly
            // warning-free.
            if (policy.isUnrestricted) {
                PiNotice(text = text.settings.storageBroadWarning, tone = PiTone.Danger)
            }

            PiSectionHeader(text.settings.storageCustomSection)
            PiGroup {
                policy.custom.sorted().forEachIndexed { index, path ->
                    if (index > 0) PiRowDivider()
                    PiRow(
                        title = path.substringAfterLast('/').ifEmpty { path },
                        subtitle = StorageAccess.displayPathOf(path),
                        leading = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailing = {
                            IconButton(onClick = { pendingRemove = path }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = text.settings.storageCustomRemove,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (policy.custom.isNotEmpty()) PiRowDivider()
                PiRow(
                    title = text.settings.storageCustomAdd,
                    subtitle = if (policy.isUnrestricted) {
                        text.settings.storageCustomSubsumed
                    } else {
                        text.settings.storageCustomAddBody
                    },
                    leading = {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
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
                PiSectionHeader(text.settings.storageRevokeAll)
                PiGroup {
                    PiRow(
                        title = text.settings.storageRevokeAll,
                        subtitle = text.settings.storageRevokeAllSubtitle,
                        leading = {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        // Revoking every grant drops the symlink farm and every folder
                        // the agent could reach, and the user has to grant them again one
                        // by one. `NONE` is not a smaller version of a grant — it is the
                        // destructive direction of the same switch — so the row carries
                        // the error role the way the other destructive controls here do.
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmRevoke = true },
                    )
                }
            }

            // Last, and in its own group, because it is the one control on this page
            // that is not a folder: the switches above decide what the agent may
            // *reach*, and this one decides whether anything checks what it does with
            // it. `SafetyGuard` has the whole argument — the enforcement of those
            // switches runs inside this extension, which is why turning it off is a
            // question rather than a tap.
            PiSectionHeader(text.settings.storageGuardSection)
            PiGroup {
                PiRow(
                    title = text.settings.storageGuardTitle,
                    subtitle = if (guardInstalled) {
                        text.settings.storageGuardSubtitle
                    } else {
                        text.settings.storageGuardMissing
                    },
                    leading = {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailing = {
                        Switch(
                            checked = guardInstalled && settings.safetyExtension,
                            // A runtime image built before the guard existed has nothing
                            // to switch: a switch that reports a state it cannot change is
                            // worse than one that is visibly inert and says why.
                            enabled = guardInstalled,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    session.setSafetyExtension(true)
                                } else {
                                    confirmDisableGuard = true
                                }
                            },
                        )
                    },
                    enabled = guardInstalled,
                )
            }
            PiNote(text.settings.storageGuardNote)
        }
    }

    val answer = confirmGrant
    if (pendingGrant != null && answer != null) {
        val name = pendingGrant
        ConfirmDialog(
            title = text.settings.storageConfirmGrantTitle,
            body = text.settings.storageConfirmGrantBody(name.orEmpty()),
            confirmLabel = text.common.confirm,
            icon = Icons.Filled.SdCard,
            onConfirm = {
                answer()
                pendingGrant = null
                confirmGrant = null
            },
            onDismissRequest = {
                pendingGrant = null
                confirmGrant = null
            },
        )
    }

    if (confirmRevoke) {
        ConfirmDialog(
            title = text.settings.storageRevokeAllTitle,
            body = text.settings.storageRevokeAllBody,
            confirmLabel = text.common.confirm,
            destructive = true,
            onConfirm = {
                apply(StorageAccess.Policy.NONE)
                confirmRevoke = false
            },
            onDismissRequest = { confirmRevoke = false },
        )
    }

    if (pendingRemove != null) {
        val path = pendingRemove
        ConfirmDialog(
            title = text.settings.storageCustomRemoveTitle,
            body = text.settings.storageCustomRemoveBody(path.orEmpty()),
            confirmLabel = text.settings.storageCustomRemove,
            destructive = true,
            onConfirm = {
                apply(policy.minus(path.orEmpty()))
                pendingRemove = null
            },
            onDismissRequest = { pendingRemove = null },
        )
    }

    if (showGrantPrompt) {
        ConfirmDialog(
            title = text.settings.storageGrantPromptTitle,
            body = text.settings.storageGrantPromptBody,
            confirmLabel = text.settings.storageGrantPromptConfirm,
            icon = Icons.Filled.SdCard,
            onConfirm = {
                showGrantPrompt = false
                runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                    .onFailure { Log.w(TAG, "No all-files-access page on this device", it) }
            },
            onDismissRequest = { showGrantPrompt = false },
        )
    }

    if (confirmDisableGuard) {
        ConfirmDialog(
            title = text.settings.storageGuardOffTitle,
            body = text.settings.storageGuardOffBody,
            confirmLabel = text.settings.storageGuardOffConfirm,
            icon = Icons.Filled.Security,
            destructive = true,
            onConfirm = {
                confirmDisableGuard = false
                session.setSafetyExtension(false)
            },
            onDismissRequest = { confirmDisableGuard = false },
        )
    }
}

/**
 * The one-line summary: none, some folders, or everything, and the way to the
 * Android page when there is no grant yet.
 *
 * Stated rather than implied, because it is the answer to "what can the agent see
 * right now?" and the switch list below is long enough to be misread — and the
 * permission row is in the *same* group, because it is not a second access level:
 * without the grant none of the switches can move, so the two belong on one frame
 * with the action that resolves it.
 *
 * The permission that row leads to is an **app-op**, not the manifest permission:
 * `appops get --uid pi.kit.mob MANAGE_EXTERNAL_STORAGE` reads `allow` on an app that
 * `dumpsys package` reports as `granted=false, flags=[USER_SET]`, and it survives
 * `pm clear` and a reinstall. `StorageAccess.isGranted()` asks the platform for it
 * (`Environment.isExternalStorageManager()`) rather than reading the permission flag,
 * because that is what decides whether the mount is readable — so a "fresh" install
 * can be genuinely already granted and is then not asked, and
 * `appops set --uid … deny` is the only thing that makes a clean first launch
 * measurable.
 */
@Composable
private fun AccessSection(
    text: Strings,
    policy: StorageAccess.Policy,
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
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

    PiSectionHeader(text.settings.storageAccessLevel)
    PiGroup {
        PiRow(
            title = level,
            subtitle = detail,
            leading = {
                Icon(
                    Icons.Filled.SdCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (!granted) {
            PiRowDivider()
            PiRow(
                title = text.settings.storageGrant,
                subtitle = text.settings.storageMissing,
                leading = {
                    Icon(
                        Icons.Filled.SdCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenSettings,
            )
        }
    }
    if (!granted) {
        // Stated under the group rather than only in the row: with the grant missing
        // the row's own line is the *blocker* (`storageMissing`), so what the agent
        // can reach is what is left to say — and the switch list below is long enough
        // to be misread. A fact about what the reader is looking at rather than a
        // failure: the agent is confined to its own home, which is a working state,
        // so the tone is neutral rather than the error role.
        PiNotice(text = text.settings.storageNoAccessBody, tone = PiTone.Neutral)
        PiNote(text.settings.storageNote)
    }
}

/**
 * One question, in the app's one confirmation shape: a title, the sentence that
 * says what the answer does, and the two buttons.
 *
 * [destructive] is the error role, and it is the *frame* rather than the button:
 * `PiButtonKind` is Material's five kinds and the error colour is not one of them,
 * so a confirmation that cannot be taken back from this page is marked by the
 * notice its wording sits in. The wording itself is the caller's, unchanged — the
 * sentence explaining what a restore or a revoke does *not* do is the reason the
 * question is asked at all.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val text = strings
    val mark = icon
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = if (mark != null) {
            { Icon(mark, contentDescription = null) }
        } else {
            null
        },
        title = { Text(title) },
        text = {
            PiNotice(
                text = body,
                tone = if (destructive) PiTone.Danger else PiTone.Neutral,
            )
        },
        confirmButton = {
            PiButton(
                text = confirmLabel,
                onClick = onConfirm,
                kind = PiButtonKind.Filled,
                size = PiButtonSize.Small,
            )
        },
        dismissButton = {
            PiButton(
                text = text.common.cancel,
                onClick = onDismissRequest,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
            )
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

private const val TAG = "PiKit"
