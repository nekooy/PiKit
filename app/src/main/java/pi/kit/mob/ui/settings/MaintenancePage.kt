package pi.kit.mob.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pi.kit.mob.env.PrefixPatcher
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.CatalogueStatus
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.ui.components.LocalToastHost
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.design.PiValueRow
import pi.kit.mob.ui.design.PiStatePill

/**
 * Maintenance: the model catalogue, repairing the prefix, and the storage check.
 *
 * All three change files under `$PREFIX` or establish a fact about it, which is why
 * they are together and away from the settings that only change what the agent is
 * launched with. The runtime image those files come from is unpacked from the APK
 * (its revision is on the About page, with pi's own version); the reinstall path is
 * here, because it is what the page says when a repair cannot help.
 *
 * There is deliberately nothing here that updates pi itself. The bundled agent is an
 * input of the runtime image `tools/build-runtime-image.py` produces, so a new pi
 * arrives with a new PiKit; replacing it on the device was removed after a
 * half-completed `npm install -g` left a tree the agent could not start from (the
 * `jiti` the 0.86 TypeScript guard extension is loaded through was gone). See
 * [CatalogueUpdater] and ARCHITECTURE §2. What the button below does instead is
 * metadata: the providers' model catalogues, which pi's launch path refreshes on its
 * own four-hour window and which the button forces when a just-released model is
 * wanted now.
 *
 * ## The three runs are one row, and none of them speaks in the row
 *
 * The page has three things that run: the catalogue refresh, the relocation walk,
 * and the storage self-test. Each is one `PiRow` whose trailing slot holds the
 * run's one control — the button, or the live spinner while it is going — and
 * whose title and subtitle never move.
 *
 * The run's status text used to fill the row's value column, and it squeezed the
 * text beside it: measured mid-walk, "正在检测…已扫描 2816 个文件" held 464px of
 * the row while the title was left 182px wide and wrapped across two lines. A run
 * announces itself as toasts instead — that it started, and what came back — so
 * the row keeps its width for the words the row is about. The reader's original
 * request is still the shape of the control: "把那些操作按钮放到列表右边…点击后
 * 显示那种转圈的团状ui" — the button, then the spinner, then the result; only the
 * result's *address* moved out of the row.
 *
 * A failure is a dialog, not a banner: the run came back wrong, and in settings
 * that is announced and then dismissed with one OK ([RunFailureDialog]) — which
 * is also where the run's own clear call lives now, since no row carries a
 * verdict to dismiss. A success is only the toast.
 *
 * ## Why the verdict and the output are two components
 *
 * A run can come back with the installer's own output — the walk reports one line
 * per file it could not rewrite, and the self-test prints one per failing check — and
 * those lines have no bound. The sentence is a `PiNotice`; the lines are [ErrorBody],
 * which is bounded and scrolls, because the version that printed them into the page
 * pushed this page's own buttons off the screen and buried the one line the reader
 * came for.
 */
@Composable
internal fun MaintenancePage(
    session: PiAgentSession,
    catalogue: CatalogueUpdater,
    selfTest: StorageSelfTest,
    onBack: () -> Unit,
) {
    val repair by session.repair.collectAsState()
    val repairRunning by session.repairRunning.collectAsState()
    val catalogueStatus by catalogue.status.collectAsState()
    val version by catalogue.version.collectAsState()
    val storageCheck by selfTest.status.collectAsState()
    val packages by session.packages.collectAsState()
    val rebuildRunning by session.rebuildRunning.collectAsState()
    var confirmRebuild by remember { mutableStateOf(false) }
    val text = strings
    val toasts = LocalToastHost.current

    // The ledger is read when the page is opened rather than kept live: nothing
    // changes it while the user is looking at this page except a run they start
    // from it, and those refresh it themselves.
    LaunchedEffect(Unit) { session.refreshPackages() }

    // A run's verdict is announced once, as a toast, and never in the row. Each
    // `seen` starts as whatever was already settled when the page opened, so
    // coming back to a page whose run finished long ago does not re-announce it.
    var seenCatalogue by remember { mutableStateOf(catalogueStatus) }
    LaunchedEffect(catalogueStatus) {
        if (catalogueStatus !== seenCatalogue) {
            seenCatalogue = catalogueStatus
            catalogueVerdict(catalogueStatus, text)?.let { toasts.show(it) }
        }
    }
    var seenRepair by remember { mutableStateOf(repair) }
    LaunchedEffect(repair) {
        val result = repair
        if (result != null && result !== seenRepair) {
            seenRepair = result
            toasts.show(relocateVerdict(result, text))
        }
    }
    var seenStorage by remember { mutableStateOf(storageCheck) }
    LaunchedEffect(storageCheck) {
        if (storageCheck !== seenStorage) {
            seenStorage = storageCheck
            storageVerdict(storageCheck, text)?.let { toasts.show(it) }
        }
    }

    // The bar has no inset of its own: `PiScaffold` zeroes it, because where a
    // page's top edge is is the page's business. Nothing above a settings page
    // applies the status bar inset (the root pads the horizontal safe-drawing and
    // the bottom only), so it is applied here.
    PiScaffold(
        // The Settings row is "Maintenance & repair" and the manual quotes that
        // same label; the header said "Updates & repair". Reading the row's own
        // key rather than a second string with the same value is what keeps the
        // two from drifting apart again. The subtitle stays the page's own,
        // longer sentence — `StoragePage` splits row and page the same way
        // (`storageTitle` vs `storagePageTitle`).
        title = text.settings.updateAndRepair,
        subtitle = text.settings.maintenanceSubtitle,
        onBack = onBack,
            ) { content ->
        Column(
            content
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding)
                .padding(top = 8.dp, bottom = PiPageBottom),
        ) {
            PiSectionHeader(text.settings.piAgent)
            PiGroup {
                PiValueRow(
                    title = text.settings.installedVersion,
                    subtitle = text.settings.installedVersionSubtitle,
                    value = version ?: text.settings.unknown,
                    leading = {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                PiRowDivider()
                // The run's control is the row's whole furniture. The row never
                // reports the run — its status text squeezed the title to 182px
                // while it held 464px (see the class doc) — so the button comes
                // back when the spinner goes and there is nothing in the row to
                // dismiss.
                PiRow(
                    title = text.settings.modelList,
                    subtitle = text.settings.modelListRefreshSubtitle,
                    leading = { RunMark(Icons.Filled.Refresh) },
                    trailing = {
                        RunControl(
                            running = catalogueStatus is CatalogueStatus.Running,
                            label = text.settings.modelListRefresh,
                            onAction = {
                                toasts.show(text.settings.modelListRefreshing)
                                catalogue.start()
                            },
                        )
                    },
                )
            }

            // The run's own end is also the moment the version beside it is
            // re-read, in case a runtime reinstall happened behind the page.
            LaunchedEffect(catalogueStatus) {
                if (catalogueStatus is CatalogueStatus.Done ||
                    catalogueStatus is CatalogueStatus.Failed
                ) {
                    catalogue.refreshVersion()
                }
            }

            // A failure is not announced by a banner here. The three runs' errors
            // are *operations that came back wrong*, and in settings that is a
            // dialog — announced then dismissed — not a strip that stays on the
            // page (ARCHITECTURE §13); the dialog is bound to the state below.

            // Relocation is not something the user should have to think about: a
            // package is rewritten before dpkg ever unpacks it (apt's
            // `DPkg::Pre-Install-Pkgs` hook, plus the `dpkg` wrapper for anything
            // apt did not mediate), so this is the fallback for a tree that was
            // installed before those hooks existed or by something that bypassed
            // both. The reasoning that used to be on this page — package ids,
            // `DT_RUNPATH`, shebangs — is in docs/ARCHITECTURE.md, where it is
            // useful and invisible. `Strings.Settings.relocateNote` is the short
            // version, and it leads with "only if something already errors" rather
            // than with what the button does: the row states the fact, the note
            // answers "do I need this", and the honest answer is almost always no.
            PiSectionHeader(text.settings.installedPackages)
            PiGroup {
                // The ledger's state first, because it is the question the
                // section answers before any button in it does: did my packages
                // survive, and are they current. No control on this row — it is
                // a fact, and the two below are the actions.
                PiRow(
                    title = text.settings.packagesRow,
                    subtitle = text.settings.packagesRowSubtitle,
                    value = packagesValue(packages, text),
                    valueColor = packagesTone(packages).ink(MaterialTheme.colorScheme),
                    leading = { RunMark(Icons.Filled.Inventory2) },
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.relocate,
                    subtitle = text.settings.relocateSubtitle,
                    leading = { RunMark(Icons.Filled.Extension) },
                    trailing = {
                        RunControl(
                            running = repairRunning,
                            label = text.settings.relocateNow,
                            onAction = {
                                toasts.show(text.settings.relocateStateScanning)
                                session.repairInstalledPackages()
                            },
                        )
                    },
                )
                PiRowDivider()
                // The escape hatch, last and separate: every button above it
                // repairs something inside the environment, and this one
                // replaces the environment. Its subtitle is the cost sentence —
                // what is kept and where it goes afterwards — because the name
                // alone reads like a refresh. Pressing it asks first.
                PiRow(
                    title = text.settings.rebuildEnvironment,
                    subtitle = text.settings.rebuildEnvironmentSubtitle,
                    leading = { RunMark(Icons.Filled.Restore) },
                    trailing = {
                        RunControl(
                            running = rebuildRunning,
                            label = text.settings.rebuildEnvironmentNow,
                            onAction = { confirmRebuild = true },
                        )
                    },
                )
            }

            packages?.let { report -> PackagesNotices(report) }

            // Storage does not belong on this page conceptually — it changes no
            // file under $PREFIX — but the *check* is a maintenance action, and it
            // is the only place a user can get an answer to "can the agent really
            // reach my files, and will it ever delete them" without a terminal.
            PiSectionHeader(text.settings.storageCheck)
            PiGroup {
                PiRow(
                    title = text.settings.storageCheck,
                    subtitle = text.settings.storageCheckSubtitle,
                    leading = { RunMark(Icons.Filled.VerifiedUser) },
                    trailing = {
                        RunControl(
                            running = storageCheck is StorageSelfTest.Status.Running,
                            label = text.settings.storageCheckRun,
                            onAction = {
                                toasts.show(text.settings.storageCheckRunning)
                                selfTest.start()
                            },
                        )
                    },
                )
            }
        }
    }

    // The three runs' failures are operations that came back wrong, and in
    // settings that is a dialog — announced then dismissed — not a strip that
    // stays on the page (ARCHITECTURE §13). Each is bound to the run's own
    // state, so its OK button *is* the run's clear call: pressing it reads the
    // failure and dismisses it in one.
    (catalogueStatus as? CatalogueStatus.Failed)?.let { failed ->
        RunFailureDialog(
            title = text.settings.modelListStateFailed,
            message = failed.message,
            onDismiss = catalogue::dismiss,
        )
    }
    repair?.takeIf { it.relocatorDamaged || it.errors.isNotEmpty() }?.let { result ->
        RunFailureDialog(
            title = text.settings.relocateStateFailed,
            message = if (result.relocatorDamaged) {
                text.settings.relocateBroken
            } else {
                text.settings.relocateProblems
            },
            lines = result.errors,
            onDismiss = session::clearRepairResult,
        )
    }
    (storageCheck as? StorageSelfTest.Status.Finished)
        ?.takeIf { !it.passed }
        ?.let { finished ->
            RunFailureDialog(
                title = text.settings.storageCheckFailed,
                message = finished.output.lastOrNull { it.startsWith(STORAGE_CHECK_TALLY) }
                    ?: text.settings.storageCheckFailed,
                lines = finished.output.filter { FAILURE_MARKER in it },
                onDismiss = selfTest::dismiss,
            )
        }

    // The one press on this page that replaces something rather than repairing
    // it, so it asks first. The dialog says the two things being decided: what
    // stops (a running conversation) and what is kept (the user's packages).
    // Its controls are the design system's: two bare text buttons, the
    // destructive one in the error role, and the destructive meaning itself in
    // the body's error-tone frame — never a fill or a border (ARCHITECTURE §13).
    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(text.settings.rebuildEnvironment) },
            text = {
                PiNotice(text = text.settings.rebuildEnvironmentConfirm, tone = PiTone.Danger)
            },
            confirmButton = {
                PiButton(
                    text = text.settings.rebuildEnvironmentConfirmNow,
                    onClick = {
                        confirmRebuild = false
                        session.rebuildEnvironment()
                    },
                    kind = PiButtonKind.Text,
                    size = PiButtonSize.Small,
                    tone = PiTone.Danger,
                )
            },
            dismissButton = {
                PiButton(
                    text = text.common.cancel,
                    onClick = { confirmRebuild = false },
                    kind = PiButtonKind.Text,
                    size = PiButtonSize.Small,
                )
            },
        )
    }
}

/**
 * A leading mark for one of the page's rows, in the muted role every list uses.
 *
 * The design package's rows leave the tint to `LocalContentColor`, which a group sets
 * to `onSurface` — the title's own colour, at which a leading glyph competes with the
 * words beside it. Same figure as `AboutPage`'s `RowMark`, and duplicated rather than
 * shared because it is three lines of `Icon` and a shared one would have to live in
 * the design package to be reachable from both.
 */
@Composable
private fun RunMark(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The one control a run has: its button, or the live spinner while it is going.
 *
 * The pair is exclusive rather than a disabled button beside an indicator, which is
 * what the button-with-a-pill-under-it strip drew: while a run is in flight there is
 * nothing to press, and a disabled `Filled` button is the same statement twice. The
 * spinner is the whole "it started" signal in the row — the run's words go to a
 * toast — and the button comes back where it was when the run ends, so a finished
 * run is never a dead end.
 */
@Composable
private fun RunControl(
    running: Boolean,
    label: String,
    onAction: () -> Unit,
) {
    if (running) {
        PiLoading(size = 20.dp, color = PiTone.Live.ink(MaterialTheme.colorScheme))
    } else {
        PiButton(
            text = label,
            onClick = onAction,
            kind = PiButtonKind.Filled,
            size = PiButtonSize.ExtraSmall,
        )
    }
}

/**
 * The ledger's state as the row's one phrase: whatever the reader needs first.
 *
 * A restore that left names behind outranks the update hint, which outranks the
 * story of what the last restore did, which outranks a bare count — the same
 * "one fact in the value column" rule the runs on this page follow. The detail
 * the phrase cannot carry is in [PackagesNotices] under the group.
 */
private fun packagesValue(report: PiAgentSession.PackagesReport?, text: Strings): String =
    when {
        report == null -> ""
        report.pending.isNotEmpty() -> text.settings.packagesPending(report.pending.size)
        report.upgradable.isNotEmpty() -> text.settings.packagesUpgradable(report.upgradable.size)
        report.restored.isNotEmpty() -> text.settings.packagesRestored(report.restored.size)
        else -> text.settings.packagesInstalled(report.installed)
    }

/** What [packagesValue] says, in the tone that says it. */
private fun packagesTone(report: PiAgentSession.PackagesReport?): PiTone =
    when {
        report == null -> PiTone.Neutral
        report.pending.isNotEmpty() -> PiTone.Danger
        report.upgradable.isNotEmpty() -> PiTone.Live
        report.restored.isNotEmpty() -> PiTone.Accent
        else -> PiTone.Neutral
    }

/**
 * What a count cannot say: which names a restore left behind, and which command
 * closes an update gap. One notice each, under the group — the page's "the row
 * states the fact, the note answers" split. Neither is a button: both answers
 * are commands the terminal runs, and a button here would have to download
 * something the note only points at.
 */
@Composable
private fun PackagesNotices(report: PiAgentSession.PackagesReport) {
    if (report.pending.isNotEmpty()) {
        val names = report.pending.joinToString(", ") { it.name }
        PiNotice(text = strings.settings.packagesPendingNote(names))
    }
    if (report.upgradable.isNotEmpty()) {
        PiNotice(text = strings.settings.packagesUpgradableNote, tone = PiTone.Live)
    }
}

/**
 * The controls of one run: the button that starts it, the one that clears what it
 * left behind, and the pill while it is going.
 *
 * ## Why this is one component
 *
 * §9.3 records what the three runs looked like when each was written out at its
 * call site, and the fix was a shared strip; this is that strip on the design
 * system. The button, the optional dismiss and the indicator are one shape, the
 * caller passes the wording, and the backup page presses the same two buttons in
 * the same order for the same reason — an export and a repair are both "start a
 * long walk, then read what it says".
 *
 * ## The details, each of which was a drift
 *
 *  - **[ACTION_INSET], the same inset `PageNote` and a row's own text use.** The
 *    buttons used to start 4dp left of the note under them, which reads as a
 *    mistake rather than as a decision: the measured pair was a label at x=127
 *    inside a pill whose left edge was x=64 against its own note's text at x=74.
 *    A button has no box of its own, so it lines up with the content above it.
 *  - **They start at the leading edge, not the trailing one.** The row was
 *    `Spacer(weight)` then the buttons, which pushed them to the right; the pages'
 *    other standalone actions — the model page's "new configuration", the export
 *    and import pair here — read as a column that starts where the page's content
 *    starts, and one page's action floating at the opposite edge from the next
 *    page's was the inconsistency this fixes.
 *  - **`working` is `PiStatePill`, and that is the whole indicator.** The
 *    indeterminate bar it replaces was a second way to say the same thing, and the
 *    pill says it in the app's one role for a thing that is *running* (`PiTone.Live`).
 *  - **The dismiss is optional and its label is not.** All three runs can dismiss
 *    their result, but only while there is one, so the button is present exactly
 *    when [onDismiss] is not null.
 *  - **It is [PiButtonSize.Small].** A strip under a row is not the page's hero
 *    action, and Material's own default height is what these three drew before.
 *    The model page's own action is the same size for the same reason: a page's
 *    standalone actions are one component at one weight, not one per page.
 */
@Composable
internal fun RunActions(
    label: String,
    onAction: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    working: String? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ACTION_INSET, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiButton(
                text = label,
                onClick = onAction,
                kind = PiButtonKind.Filled,
                size = PiButtonSize.Small,
                enabled = enabled,
            )
            if (onDismiss != null) {
                PiButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    kind = PiButtonKind.Outlined,
                    size = PiButtonSize.Small,
                )
            }
        }
        if (working != null) {
            PiStatePill(label = working, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/**
 * The gutter a run's button starts at: the same 24dp a group's rows and the note
 * under them use.
 *
 * Not 12dp, which is the group's own frame inset, and not 16dp, which is where it
 * used to be next to a note at 16dp: `PiRow` puts its text 24dp in from the group's
 * edge, so that is the column everything on the page lines up on.
 */
private val ACTION_INSET = 24.dp

/**
 * The installer's own output, in a box that cannot grow the page.
 *
 * The relocation walk reports one line per file it could not rewrite and the
 * self-test one per failing check, and neither count has a bound. Printing them into
 * the page is what pushed this page's buttons off the screen and buried the one line
 * the reader came for, so the block is capped at [ERROR_BODY_MAX] and scrolls inside
 * that; the verdict itself is the notice above it.
 *
 * Monospace, and in the error role rather than the page's own ink: every one of
 * these lines is a path or a script's message, which is read back against a terminal
 * and wants the face the terminal uses.
 */
@Composable
private fun ErrorBody(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = ERROR_BODY_MAX)
            .clip(PiShapes.row)
            .background(PiTone.Danger.container(MaterialTheme.colorScheme))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = PiTone.Danger.onContainer(MaterialTheme.colorScheme),
                )
            }
        }
    }
}

/**
 * A run that came back wrong: a title, the failure's own sentence, and one OK.
 *
 * The settings pages' shape for an operation that failed — "announced then
 * dismissed", because the reader asked for the run and it came back wrong —
 * rather than a strip that stays on the page until something else replaces it.
 * [onDismiss] is the run's own clear call, so the OK button reads the failure
 * and dismisses it in one press; there is no second control for that.
 *
 * [lines] is the installer's own output — the files the walk could not rewrite,
 * the checks the self-test failed — and it is bounded and scrolls inside the
 * dialog, because the version that printed such lines into the page pushed this
 * page's own controls off the screen and buried the one line the reader came
 * for. The sentence is the `PiNotice` frame; the lines are [ErrorBody].
 */
@Composable
private fun RunFailureDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    lines: List<String> = emptyList(),
) {
    val text = strings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PiNotice(text = message, tone = PiTone.Danger)
                if (lines.isNotEmpty()) {
                    ErrorBody(lines)
                }
            }
        },
        confirmButton = {
            PiButton(
                text = text.common.ok,
                onClick = onDismiss,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
            )
        },
    )
}

/**
 * What a successful run announces, as a toast: null while there is nothing to
 * say and when the run failed.
 *
 * One word rather than a sentence — "已更新" is the whole fact, and the toast's
 * context is the button that was just pressed beside it. A failure is not a
 * toast's business: it is a dialog, with the sentence and the lines to read
 * ([RunFailureDialog]).
 */
private fun catalogueVerdict(status: CatalogueStatus, text: Strings): String? = when (status) {
    is CatalogueStatus.Done -> if (status.changed) {
        text.settings.modelListStateUpdated
    } else {
        text.settings.modelListStateCurrent
    }

    else -> null
}

/**
 * What a successful relocation walk announces: repaired, or nothing to do. Null
 * for the two failures — the damaged relocator and the files the walk could not
 * rewrite both name actions and lines, which is the dialog's shape. The
 * damaged-relocator case is not a third success word for the same reason: from
 * the run's point of view it is a walk that came back with something the user
 * has to act on.
 */
private fun relocateVerdict(result: PrefixPatcher.Result, text: Strings): String? = when {
    result.relocatorDamaged || result.errors.isNotEmpty() -> null
    result.changed -> text.settings.relocateStateRepaired
    else -> text.settings.relocateStateClean
}

/** What a passing self-test announces: the failures are the dialog's. */
private fun storageVerdict(status: StorageSelfTest.Status, text: Strings): String? =
    when (status) {
        is StorageSelfTest.Status.Finished -> if (status.passed) {
            text.settings.storageCheckPassed
        } else {
            null
        }

        else -> null
    }

/**
 * The last line of `tools/storage-self-test.sh`, which carries the verdict's numbers.
 *
 * Compared by prefix rather than by a copy of the sentence: the tally is the
 * script's, and `StorageSelfTest` already reads it back the same way to decide
 * whether the run passed.
 */
private const val STORAGE_CHECK_TALLY = "storage self-test:"

/**
 * The marker whose lines are the only ones worth printing after a run.
 *
 * The script's own word (`bad()` in `tools/storage-self-test.sh`), and the same one
 * `StorageSelfTest` looks for when it decides the exit status and the text agree.
 */
private const val FAILURE_MARKER = "FAIL"

/**
 * How tall the installer's own output may get before it scrolls.
 *
 * 240dp is 630px on the measured 420dpi device, which is fifteen lines of the mono
 * face at `bodySmall`'s own 16sp line height (42px): enough that a run with a
 * handful of failures is read in place, and bounded so that a run with forty of them
 * leaves the page's own controls where they were.
 */
private val ERROR_BODY_MAX = 240.dp
