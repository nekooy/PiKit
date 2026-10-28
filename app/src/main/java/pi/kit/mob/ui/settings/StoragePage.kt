package pi.kit.mob.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.env.SafetyGuard
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

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
 * the user added, taking it all back, and the guard. Every switch on this page sits in
 * its row's trailing slot with the row itself **inert**, which is deliberate: each of
 * these can raise a confirmation, so the tap has to be aimed at the control rather
 * than at the sentence beside it. `SwitchPreference` — the row that toggles when the
 * row is tapped — is the right component for a switch that is only a preference, and
 * this page's are questions.
 *
 * The one destructive row — taking every grant back — carries the error role on its
 * title, and so does every question this page asks whose answer cannot be taken back
 * from here: the role is the colour of the sentence and of the confirming word, never
 * a fill (ARCHITECTURE §13).
 *
 * ## The folder picker is a window
 *
 * Adding a folder the user chose steps through the tree in a Miuix bottom sheet, which
 * is its own window: the list is a `Card` of `ArrowPreference` rows inside it, and the
 * two navigation rows are the same shape as the folders they move between. A body of
 * the page's own rather than a generic picker menu, because every row of a menu closes
 * the panel on tap and stepping through a directory tree is not a menu — the panel would
 * animate away and back on every step.
 */
@Composable
fun StoragePage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val context = LocalContext.current
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

    PiPage(
        title = text.settings.storagePageTitle,
        subtitle = text.settings.storagePageSubtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            AccessSection(
                text = text,
                policy = policy,
                granted = granted,
                onOpenSettings = { showGrantPrompt = true },
            )

            PiSection(title = text.settings.storageFolders) {
                StorageAccess.Root.entries.forEach { root ->
                    // While the whole tree is on, a sub-folder's switch changes
                    // nothing: it is already reachable through `shared`. Disabled
                    // and shown as included rather than left tappable. The predicate
                    // is `Policy.isSubsumed`, which `StorageAccess.linkPlanFor` also
                    // applies — the two disagreed once, and the farm named
                    // `/sdcard/Download` twice for it.
                    val subsumed = policy.isSubsumed(root)
                    // The subtitle is the *real* directory — `/sdcard/DCIM` — and not
                    // the link's name inside the environment (`~/storage/dcim`). The
                    // mismatch is the point of the row: a user looking for the folder
                    // they recognise in a file manager needs the spelling the file
                    // manager uses, and `StorageAccess.displayTargetOf` is the one place
                    // that decides it. Both names are in the manual — the link is lower
                    // case because `termux-setup-storage` created it that way for years
                    // and scripts type it.
                    // The switch is the target and the row is inert, which is a decision
                    // rather than an oversight: every one of these switches can raise the
                    // grant question below, and a tap aimed at the sentence beside it is a
                    // question asked by accident. `BasicComponent` with a `Switch` in its
                    // trailing slot draws the same row as `SwitchPreference` and leaves the
                    // row itself unclickable, so the finger has to reach the control.
                    BasicComponent(
                        title = folderLabel(text, root),
                        summary = StorageAccess.displayTargetOf(root),
                        enabled = granted && !subsumed,
                        endActions = {
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
                    )
                }
            }

            // No alert above the list. One stood here while the policy was
            // unrestricted — "these files usually cannot be recovered" — and it is
            // gone: it repeated what the /sdcard row's own subtitle says about what
            // granting it means, and it fired the moment the root was switched on,
            // which is the state the reader had just chosen and did not need telling
            // about a second time.
            PiSection(title = text.settings.storageCustomSection) {
                policy.custom.sorted().forEach { path ->
                    BasicComponent(
                        title = path.substringAfterLast('/').ifEmpty { path },
                        summary = StorageAccess.displayPathOf(path),
                        // The ✕ is the row's only control and it is destructive, so it
                        // is its own button rather than the row's tap: a row that removed
                        // a grant when tapped would remove it by accident.
                        endActions = {
                            IconButton(onClick = { pendingRemove = path }) {
                                Icon(
                                    // `Close`, the one mark the settings pages use for "take
                                    // this entry out of the list" (the search page's key rows,
                                    // the model form's model rows).
                                    imageVector = MiuixIcons.Close,
                                    contentDescription = text.settings.storageCustomRemove,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        },
                    )
                }
                ArrowPreference(
                    title = text.settings.storageCustomAdd,
                    summary = if (policy.isUnrestricted) {
                        text.settings.storageCustomSubsumed
                    } else {
                        text.settings.storageCustomAddBody
                    },
                    enabled = granted && !policy.isUnrestricted,
                    onClick = {
                        if (!granted) {
                            showGrantPrompt = true
                        } else {
                            StorageAccess.sharedRoot()?.let { pickerDir = it }
                        }
                    },
                )
            }

            if (!policy.isEmpty) {
                // Untitled: the card holds this one row, and a label reading the same
                // sentence as the row under it is the heading drawn twice.
                PiSection {
                    BasicComponent(
                        title = text.settings.storageRevokeAll,
                        summary = text.settings.storageRevokeAllSubtitle,
                        titleColor = BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        // Revoking every grant drops the symlink farm and every folder
                        // the agent could reach, and the user has to grant them again one
                        // by one. `NONE` is not a smaller version of a grant — it is the
                        // destructive direction of the same switch — so the row carries
                        // the error role the way the other destructive controls here do.
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
            PiSection(title = text.settings.storageGuardSection) {
                BasicComponent(
                    title = text.settings.storageGuardTitle,
                    summary = if (guardInstalled) {
                        text.settings.storageGuardSubtitle
                    } else {
                        text.settings.storageGuardMissing
                    },
                    // Same inert row as the folder switches, for the same reason: turning it
                    // off is a question, and the finger has to reach the control that asks it.
                    enabled = guardInstalled,
                    endActions = {
                        Switch(
                            checked = guardInstalled && settings.safetyExtension,
                            // A runtime image built before the guard existed has nothing to
                            // switch: a switch that reports a state it cannot change is worse
                            // than one that is visibly inert and says why.
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
                )
            }
        }
    }

    // The picker, as Miuix's own sheet. Its body reads `pickerDir`, so stepping into a
    // folder recomposes the list in place: the sheet is one window whose *contents*
    // move, not a window that is dismissed and re-shown per tap (which would animate the
    // panel up and down the screen on every step).
    val directory = pickerDir
    PiSheet(
        show = directory != null,
        title = text.settings.storageCustomPickTitle,
        onDismiss = { pickerDir = null },
    ) {
        if (directory != null) {
            FolderPicker(
                directory = directory,
                text = text,
                onUse = {
                    val chosen = directory.absolutePath
                    pickerDir = null
                    askToGrant(StorageAccess.displayPathOf(chosen)) {
                        apply(policy.plus(chosen))
                    }
                },
                onUp = { pickerDir = directory.parentFile },
                onOpen = { pickerDir = it },
            )
        }
    }

    val answer = confirmGrant
    PiConfirm(
        show = pendingGrant != null && answer != null,
        title = text.settings.storageConfirmGrantTitle,
        text = text.settings.storageConfirmGrantBody(pendingGrant.orEmpty()),
        confirm = text.common.confirm,
        dismiss = text.common.cancel,
        onConfirm = {
            answer?.invoke()
            pendingGrant = null
            confirmGrant = null
        },
        onDismiss = {
            pendingGrant = null
            confirmGrant = null
        },
    )

    PiConfirm(
        show = confirmRevoke,
        title = text.settings.storageRevokeAllTitle,
        text = text.settings.storageRevokeAllBody,
        confirm = text.common.confirm,
        dismiss = text.common.cancel,
        destructive = true,
        onConfirm = {
            apply(StorageAccess.Policy.NONE)
            confirmRevoke = false
        },
        onDismiss = { confirmRevoke = false },
    )

    val removing = pendingRemove
    PiConfirm(
        show = removing != null,
        title = text.settings.storageCustomRemoveTitle,
        text = text.settings.storageCustomRemoveBody(removing.orEmpty()),
        confirm = text.settings.storageCustomRemove,
        dismiss = text.common.cancel,
        destructive = true,
        onConfirm = {
            apply(policy.minus(removing.orEmpty()))
            pendingRemove = null
        },
        onDismiss = { pendingRemove = null },
    )

    PiConfirm(
        show = showGrantPrompt,
        title = text.settings.storageGrantPromptTitle,
        text = text.settings.storageGrantPromptBody,
        confirm = text.settings.storageGrantPromptConfirm,
        dismiss = text.common.cancel,
        onConfirm = {
            showGrantPrompt = false
            runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                .onFailure { Log.w(TAG, "No all-files-access page on this device", it) }
        },
        onDismiss = { showGrantPrompt = false },
    )

    PiConfirm(
        show = confirmDisableGuard,
        title = text.settings.storageGuardOffTitle,
        text = text.settings.storageGuardOffBody,
        confirm = text.settings.storageGuardOffConfirm,
        dismiss = text.common.cancel,
        destructive = true,
        onConfirm = {
            confirmDisableGuard = false
            session.setSafetyExtension(false)
        },
        onDismiss = { confirmDisableGuard = false },
    )
}

/**
 * The folder tree, as a list that moves rather than a list that closes.
 *
 * The rows are `ArrowPreference`s — the same row every other way into a folder uses —
 * and only "use this folder" ends the sheet. The children are listed off the main
 * thread: this used to run inside a lazy list's item lambda, so every step did one
 * `stat` per entry on `/sdcard`, which is a FUSE mount, and the panel took hundreds of
 * syscalls on the frame thread each time it recomposed. Now the picker waits one frame
 * for the list, and the two navigation rows are on screen while it does.
 *
 * "Up" is hidden at the root of shared storage, which is the one directory the picker
 * may not leave: the grant is about what is *inside* shared storage, and a picker that
 * could walk to `/` would offer to add the whole filesystem to the environment.
 */
@Composable
private fun FolderPicker(
    directory: File,
    text: Strings,
    onUse: () -> Unit,
    onUp: () -> Unit,
    onOpen: (File) -> Unit,
) {
    val root = StorageAccess.sharedRoot()
    val children by produceState(initialValue = emptyList<File>(), directory) {
        value = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.filter { it.isDirectory && it.canRead() }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        }
    }

    Card {
        ArrowPreference(
            title = text.settings.storageCustomPickUse,
            summary = StorageAccess.displayPathOf(directory.absolutePath),
            onClick = onUse,
        )
        if (root != null && directory.absolutePath != root.absolutePath) {
            ArrowPreference(
                title = text.settings.storageCustomPickUp,
                summary = StorageAccess.displayPathOf(
                    directory.parentFile?.absolutePath ?: directory.absolutePath,
                ),
                onClick = onUp,
            )
        }
        // The tree scrolls *inside* the card, and the two navigation rows above it do not.
        // Miuix's sheet caps its content at `windowHeight - topInset` and clips the rest
        // rather than scrolling it (`BottomSheetContentLayout`), so a directory with more
        // entries than fit — `/sdcard` on any phone with a few apps installed — used to
        // end at the panel's edge with the remaining folders unreachable and no gesture
        // that reached them. Bounded, then scrolled, is the shape `SettingsPickerSheet`
        // gives its own list for the same reason.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = PICKER_LIST_MAX)
                .verticalScroll(rememberScrollState()),
        ) {
            children.forEach { child ->
                ArrowPreference(
                    title = child.name,
                    onClick = { onOpen(child) },
                )
            }
        }
    }
}

/** How tall a picker's list may get before it scrolls: about eight rows. */
private val PICKER_LIST_MAX = 420.dp

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

    PiSection(title = text.settings.storageAccessLevel) {
        BasicComponent(
            title = level,
            summary = detail,
        )
        if (!granted) {
            ArrowPreference(
                title = text.settings.storageGrant,
                summary = text.settings.storageMissing,
                onClick = onOpenSettings,
            )
        }
    }
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
