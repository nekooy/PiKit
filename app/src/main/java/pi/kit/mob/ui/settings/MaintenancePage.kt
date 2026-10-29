package pi.kit.mob.ui.settings

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.env.PrefixPatcher
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.CatalogueStatus
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.basic.BasicComponent

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
 * ## The three runs are one row each, and none of them speaks in the row
 *
 * The page has three things that run: the catalogue refresh, the relocation walk,
 * and the storage self-test. Each is one Miuix row whose trailing slot holds the
 * run's one control — the button, or the live spinner while it is going — and whose
 * title and summary never move.
 *
 * The run's status text used to fill the row's value slot, and it squeezed the text
 * beside it: measured mid-walk, "正在检测…已扫描 2816 个文件" held 464px of the row
 * while the title was left 182px wide and wrapped across two lines. A run announces
 * itself as toasts instead — that it started, and what came back — so the row keeps
 * its width for the words the row is about. The reader's original request is still the
 * shape of the control: "把那些操作按钮放到列表右边…点击后显示那种转圈的团状ui" — the
 * button, then the spinner, then the result; only the result's *address* moved out of
 * the row.
 *
 * A failure is a dialog, not a banner: the run came back wrong, and in settings that is
 * announced and then dismissed with one OK ([SettingsAlert]) — which is also where the
 * run's own clear call lives now, since no row carries a verdict to dismiss. A success
 * is only the toast.
 *
 * ## Why the verdict and the output are two components
 *
 * A run can come back with the installer's own output — the walk reports one line per
 * file it could not rewrite, and the self-test prints one per failing check — and those
 * lines have no bound. The sentence is the dialog's own summary; the lines are drawn
 * under it in a bounded, scrolling block, because the version that printed them into
 * the page pushed this page's own buttons off the screen and buried the one line the
 * reader came for.
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
    val toasts = LocalPiToast.current

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
            catalogueVerdict(catalogueStatus, text)?.let { toasts?.show(it) }
        }
    }
    var seenRepair by remember { mutableStateOf(repair) }
    LaunchedEffect(repair) {
        val result = repair
        if (result != null && result !== seenRepair) {
            seenRepair = result
            relocateVerdict(result, text)?.let { toasts?.show(it) }
        }
    }
    var seenStorage by remember { mutableStateOf(storageCheck) }
    LaunchedEffect(storageCheck) {
        if (storageCheck !== seenStorage) {
            seenStorage = storageCheck
            storageVerdict(storageCheck, text)?.let { toasts?.show(it) }
        }
    }

    PiPage(
        // The Settings row is "Maintenance & repair" and the manual quotes that
        // same label; the old header said "Updates & repair". Reading the row's own
        // key rather than a second string with the same value is what keeps the
        // two from drifting apart again. The subtitle stays the page's own,
        // longer sentence — `StoragePage` splits row and page the same way
        // (`storageTitle` vs `storagePageTitle`).
        title = text.settings.updateAndRepair,
        subtitle = text.settings.maintenanceSubtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The page's elastic end. See `piOverScroll`.
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.piAgent) {
                BasicComponent(
                    title = text.settings.installedVersion,
                    summary = text.settings.installedVersionSubtitle,
                    endActions = {
                        SettingsValue(version ?: text.settings.unknown, monospace = true)
                    },
                )
                // The run's control is the row's whole furniture. The row never
                // reports the run — its status text squeezed the title to 182px
                // while it held 464px (see the class doc) — so the button comes
                // back when the spinner goes and there is nothing in the row to
                // dismiss.
                BasicComponent(
                    title = text.settings.modelList,
                    summary = text.settings.modelListRefreshSubtitle,
                    endActions = {
                        RunControl(
                            running = catalogueStatus is CatalogueStatus.Running,
                            label = text.settings.modelListRefresh,
                            onAction = {
                                toasts?.show(text.settings.modelListRefreshing)
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
            // page (ARCHITECTURE §13); the dialogs are bound to the state below.

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
            PiSection(title = text.settings.installedPackages) {
                // The ledger's state first, because it is the question the
                // section answers before any button in it does: did my packages
                // survive, and are they current. No control on this row — it is
                // a fact, and the two below are the actions.
                BasicComponent(
                    title = text.settings.packagesRow,
                    summary = text.settings.packagesRowSubtitle,
                    endActions = {
                        val report = packages
                        SettingsValue(
                            text = packagesValue(report, text),
                            emphasised = report?.let { it.upgradable.isNotEmpty() || it.restored.isNotEmpty() } == true,
                            danger = report?.pending?.isNotEmpty() == true,
                        )
                    },
                )
                BasicComponent(
                    title = text.settings.relocate,
                    summary = text.settings.relocateSubtitle,
                    endActions = {
                        RunControl(
                            running = repairRunning,
                            label = text.settings.relocateNow,
                            onAction = {
                                toasts?.show(text.settings.relocateStateScanning)
                                session.repairInstalledPackages()
                            },
                        )
                    },
                )
                // The escape hatch, last and separate: every button above it
                // repairs something inside the environment, and this one
                // replaces the environment. Its subtitle is the cost sentence —
                // what is kept and where it goes afterwards — because the name
                // alone reads like a refresh. Pressing it asks first.
                BasicComponent(
                    title = text.settings.rebuildEnvironment,
                    summary = text.settings.rebuildEnvironmentSubtitle,
                    endActions = {
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
            //
            // No group label: the card holds one row and that row's title is the
            // heading the label would have repeated. An untitled `PiSection` is the
            // library's own answer for a card that is obviously one thing.
            PiSection {
                BasicComponent(
                    title = text.settings.storageCheck,
                    summary = text.settings.storageCheckSubtitle,
                    endActions = {
                        RunControl(
                            running = storageCheck is StorageSelfTest.Status.Running,
                            label = text.settings.storageCheckRun,
                            onAction = {
                                toasts?.show(text.settings.storageCheckRunning)
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
    val catalogueFailure = catalogueStatus as? CatalogueStatus.Failed
    SettingsAlert(
        show = catalogueFailure != null,
        title = text.settings.modelListStateFailed,
        body = catalogueFailure?.message.orEmpty(),
        confirm = text.common.ok,
        onConfirm = catalogue::dismiss,
    )

    val repairFailure = repair?.takeIf { it.relocatorDamaged || it.errors.isNotEmpty() }
    SettingsAlert(
        show = repairFailure != null,
        title = text.settings.relocateStateFailed,
        body = when {
            repairFailure == null -> ""
            repairFailure.relocatorDamaged -> text.settings.relocateBroken
            else -> text.settings.relocateProblems
        },
        confirm = text.common.ok,
        lines = repairFailure?.errors.orEmpty(),
        onConfirm = session::clearRepairResult,
    )

    val storageFailure = (storageCheck as? StorageSelfTest.Status.Finished)
        ?.takeIf { !it.passed }
    SettingsAlert(
        show = storageFailure != null,
        title = text.settings.storageCheckFailed,
        body = storageFailure?.output
            ?.lastOrNull { it.startsWith(STORAGE_CHECK_TALLY) }
            ?: text.settings.storageCheckFailed,
        confirm = text.common.ok,
        lines = storageFailure?.output?.filter { FAILURE_MARKER in it }.orEmpty(),
        onConfirm = selfTest::dismiss,
    )

    // The one press on this page that replaces something rather than repairing
    // it, so it asks first. The dialog says the two things being decided: what
    // stops (a running conversation) and what is kept (the user's packages).
    PiConfirm(
        show = confirmRebuild,
        title = text.settings.rebuildEnvironment,
        text = text.settings.rebuildEnvironmentConfirm,
        confirm = text.settings.rebuildEnvironmentConfirmNow,
        dismiss = text.common.cancel,
        destructive = true,
        onConfirm = {
            confirmRebuild = false
            session.rebuildEnvironment()
        },
        onDismiss = { confirmRebuild = false },
    )
}

/**
 * The one control a run has: its button, or the live spinner while it is going.
 *
 * The pair is exclusive rather than a disabled button beside an indicator, which is
 * what a button-with-a-pill-under-it strip drew: while a run is in flight there is
 * nothing to press, and a disabled button is the same statement twice. The spinner is
 * the whole "it started" signal in the row — the run's words go to a toast — and the
 * button comes back where it was when the run ends, so a finished run is never a dead
 * end.
 */
@Composable
private fun RunControl(
    running: Boolean,
    label: String,
    onAction: () -> Unit,
) {
    if (running) {
        SettingsWorking()
    } else {
        SettingsAction(text = label, onClick = onAction)
    }
}

/**
 * The ledger's state as the row's one phrase: whatever the reader needs first.
 *
 * A restore that left names behind outranks the update hint, which outranks the
 * story of what the last restore did, which outranks a bare count — the same
 * "one fact in a value slot" rule the runs on this page follow. The detail the
 * phrase cannot carry is in [PackagesNotices] under the group.
 */
private fun packagesValue(report: PiAgentSession.PackagesReport?, text: Strings): String =
    when {
        report == null -> ""
        report.pending.isNotEmpty() -> text.settings.packagesPending(report.pending.size)
        report.upgradable.isNotEmpty() -> text.settings.packagesUpgradable(report.upgradable.size)
        report.restored.isNotEmpty() -> text.settings.packagesRestored(report.restored.size)
        else -> text.settings.packagesInstalled(report.installed)
    }

/**
 * What a count cannot say: which names a restore left behind, and which command
 * closes an update gap. One card of notes under the group — the page's "the row
 * states the fact, the note answers" split, with the card untitled because the group
 * directly above it is what the notes are about. Neither is a button: both answers are
 * commands the terminal runs, and a button here would have to download something the
 * note only points at.
 */
@Composable
private fun PackagesNotices(report: PiAgentSession.PackagesReport) {
    if (report.pending.isEmpty() && report.upgradable.isEmpty()) return
    val text = strings
    PiSection {
        if (report.pending.isNotEmpty()) {
            val names = report.pending.joinToString(", ") { it.name }
            SettingsNote(text.settings.packagesPendingNote(names), danger = true)
        }
        if (report.upgradable.isNotEmpty()) {
            SettingsNote(text.settings.packagesUpgradableNote)
        }
    }
}

/**
 * What a successful run announces, as a toast: null while there is nothing to
 * say and when the run failed.
 *
 * One word rather than a sentence — "已更新" is the whole fact, and the toast's
 * context is the button that was just pressed beside it. A failure is not a
 * toast's business: it is a dialog, with the sentence and the lines to read.
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
