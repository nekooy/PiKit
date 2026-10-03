package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pi.kit.mob.env.PrefixPatcher
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.CatalogueStatus
import pi.kit.mob.pi.CatalogueUpdater
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.StorageSelfTest

/**
 * Maintenance: the model catalogue and repairing the prefix.
 *
 * Both actions change files under `$PREFIX`, which is why they are together and
 * away from the settings that only change what the agent is launched with.
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
 * ## The three runs are one shape
 *
 * The page has three things that run and report: the catalogue refresh, the
 * relocation walk, and the storage self-test. Each is a `SettingsRow` carrying the
 * run's state in its value column, a [SettingsActionStrip] with the button and the
 * bar, and then the verdict in a [SettingsNote] above the standing explanation. That
 * shape is not a preference — it is what the three were *not*: one showed its state
 * in the row and two only in prose, one drew a progress bar and one did not, and the
 * buttons of one started 4dp left of the note under them. `SettingsActionStrip` is
 * the shared half, so the three cannot drift apart again.
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
    val repairScanned by session.repairScanned.collectAsState()
    val catalogueStatus by catalogue.status.collectAsState()
    val version by catalogue.version.collectAsState()
    val storageCheck by selfTest.status.collectAsState()
    val text = strings

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            // The Settings row is "Maintenance & repair" and the manual quotes that
            // same label; the header said "Updates & repair". Reading the row's own
            // key rather than a second string with the same value is what keeps the
            // two from drifting apart again. The subtitle stays the page's own,
            // longer sentence — `StoragePage` splits row and page the same way
            // (`storageTitle` vs `storagePageTitle`).
            title = text.settings.updateAndRepair,
            subtitle = text.settings.maintenanceSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.piAgent) {
                SettingsRow(
                    title = text.settings.installedVersion,
                    subtitle = text.settings.installedVersionSubtitle,
                    icon = Icons.Filled.Build,
                    value = version ?: text.settings.unknown,
                    monospaceValue = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.modelList,
                    subtitle = text.settings.modelListRefreshSubtitle,
                    icon = Icons.Filled.Refresh,
                    // The run's state, in the value column, which is where the two
                    // blocks below carry theirs. It used to be in the paragraph under
                    // the button and nowhere else, so the page's three runs reported
                    // themselves three different ways.
                    value = catalogueState(catalogueStatus, text),
                )
                SettingsActionStrip(
                    actionLabel = text.settings.modelListRefresh,
                    onAction = { catalogue.start() },
                    dismissLabel = text.settings.dismiss,
                    actionEnabled = catalogueStatus !is CatalogueStatus.Running,
                    onDismiss = if (catalogueStatus is CatalogueStatus.Done ||
                        catalogueStatus is CatalogueStatus.Failed
                    ) {
                        {
                            catalogue.dismiss()
                            catalogue.refreshVersion()
                        }
                    } else {
                        null
                    },
                    progress = if (catalogueStatus is CatalogueStatus.Running) {
                        text.settings.modelListRefreshing
                    } else {
                        null
                    },
                )

                when (val current = catalogueStatus) {
                    // No verdict yet: the value column and the bar say the run is going,
                    // and the note under them says what the button is for. A paragraph
                    // repeating "refreshing" would be the third telling.
                    CatalogueStatus.Idle, CatalogueStatus.Running -> Unit

                    is CatalogueStatus.Done -> SettingsNote(
                        // The changed case also restarted the agent, so the sentence
                        // says both; the unchanged case is a success too — the model the
                        // user came for may simply not exist yet — and saying so is what
                        // keeps "nothing happened" from reading as a failure.
                        text = if (current.changed) {
                            text.settings.modelListChanged
                        } else {
                            text.settings.modelListUnchanged
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is CatalogueStatus.Failed -> SettingsNote(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Last in the block, always, and in the same position in all three:
                // what the button is for. The relocation block below has carried its
                // note this way from the start, and this one drew the same sentence
                // *instead of* the verdict while idle — so the two blocks put a
                // paragraph in two different places.
                SettingsNote(text.settings.modelListNote)
            }

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
            SettingsSection(text.settings.installedPackages) {
                SettingsRow(
                    title = text.settings.relocate,
                    subtitle = text.settings.relocateSubtitle,
                    icon = Icons.Filled.Extension,
                    value = relocateState(repair, repairRunning, text),
                )
                SettingsActionStrip(
                    actionLabel = text.settings.relocateNow,
                    onAction = { session.repairInstalledPackages() },
                    dismissLabel = text.settings.dismiss,
                    // Guarded on the walk, not on the last result being on
                    // screen: the run takes long enough that a button disabled
                    // until Dismiss reads as broken.
                    actionEnabled = !repairRunning,
                    onDismiss = if (repair != null && !repairRunning) {
                        { session.clearRepairResult() }
                    } else {
                        null
                    },
                    // The walk reads every file under `$PREFIX` — 22 000 on a
                    // freshly installed image — so it reports what it has read
                    // rather than a bar that appears frozen. Same treatment as the
                    // catalogue refresh above.
                    progress = if (repairRunning) {
                        text.settings.relocateScanning(repairScanned)
                    } else {
                        null
                    },
                )

                repair?.let { result ->
                    SettingsNote(
                        text = when {
                            // Checked before `errors` because it is the one outcome
                            // the walk cannot repair from the inside: the relocator
                            // no longer names the upstream id at all, so its own
                            // `OLD_ID` was rewritten and it now refuses to run
                            // (`exit 4`) — every later `pkg install` goes
                            // unrelocated until the runtime is reinstalled.
                            result.relocatorDamaged -> text.settings.relocateBroken

                            result.errors.isNotEmpty() ->
                                text.settings.relocateProblems + "\n" +
                                    result.errors.joinToString("\n")

                            result.changed -> text.settings.relocated(
                                occurrences = result.occurrences,
                                files = result.filesRewritten,
                                symlinks = result.symlinksRewritten,
                                modes = result.modesFixed,
                            )

                            else -> text.settings.nothingToRelocate
                        },
                        color = if (result.errors.isNotEmpty() || result.relocatorDamaged) {
                            MaterialTheme.colorScheme.error
                        } else {
                            // Success, same primary as the catalogue and storage
                            // verdicts above: a grey "已修复" read as muted metadata
                            // beside two blue result lines and looked unfinished.
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }

                // Under the button, always, the way the storage check carries its
                // own explanation: a page whose actions are "refresh metadata" and
                // "repair a package" has to say what the second one is for, and the
                // row's caption alone cannot.
                SettingsNote(
                    text.settings.relocateNote
                )
            }

            // Storage does not belong on this page conceptually — it changes no
            // file under $PREFIX — but the *check* is a maintenance action, and it
            // is the only place a user can get an answer to "can the agent really
            // reach my files, and will it ever delete them" without a terminal.
            SettingsSection(text.settings.storageCheck) {
                SettingsRow(
                    title = text.settings.storageCheck,
                    subtitle = text.settings.storageCheckSubtitle,
                    icon = Icons.Filled.VerifiedUser,
                    value = when (val current = storageCheck) {
                        is StorageSelfTest.Status.Idle -> null
                        is StorageSelfTest.Status.Running -> text.settings.storageCheckRunning
                        is StorageSelfTest.Status.Finished ->
                            if (current.passed) {
                                text.settings.storageCheckPassed
                            } else {
                                text.settings.storageCheckFailed
                            }
                    },
                )
                SettingsActionStrip(
                    actionLabel = text.settings.storageCheckRun,
                    onAction = { selfTest.start() },
                    dismissLabel = text.settings.dismiss,
                    actionEnabled = storageCheck !is StorageSelfTest.Status.Running,
                    onDismiss = if (storageCheck is StorageSelfTest.Status.Finished) {
                        { selfTest.dismiss() }
                    } else {
                        null
                    },
                    // The third run gets the same bar the other two draw. It used to
                    // have none — the value column said "checking" and nothing moved —
                    // which is the one place on this page where a run in flight and a
                    // run that never started looked the same.
                    progress = if (storageCheck is StorageSelfTest.Status.Running) {
                        text.settings.storageCheckRunning
                    } else {
                        null
                    },
                )

                val finished = storageCheck as? StorageSelfTest.Status.Finished
                if (finished == null) {
                    SettingsNote(
                        text.settings.storageCheckNote
                    )
                } else {
                    // The verdict and the failures, never the whole transcript. The
                    // script prints one line per check — fourteen of them on a fresh
                    // install, and one more per granted folder — and printing all of
                    // them pushed this page's own buttons off the screen and buried
                    // the one line the user came for. The full list is still one
                    // command away, and the note below says which.
                    Column(Modifier.padding(vertical = 4.dp)) {
                        SettingsNote(
                            text = finished.output
                                .lastOrNull { it.startsWith(STORAGE_CHECK_TALLY) }
                                ?: if (finished.passed) {
                                    text.settings.storageCheckPassed
                                } else {
                                    text.settings.storageCheckFailed
                                },
                            color = if (finished.passed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        finished.output.filter { FAILURE_MARKER in it }.forEach { line ->
                            // Monospace, because the line is the script's own
                            // output; same 16dp inset as the note above it.
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                        SettingsNote(
                            text.settings.storageCheckTerminalHint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The catalogue run's state, as the row's value: null while there is nothing to say.
 *
 * A word rather than the sentence the note carries, because the value column caps its
 * text at 0.35 of the row (see `SettingsRow`): `modelListChanged` is 19 characters and
 * would be an ellipsis there, while "Updated" is the whole fact.
 */
private fun catalogueState(status: CatalogueStatus, text: Strings): String? = when (status) {
    CatalogueStatus.Idle -> null
    CatalogueStatus.Running -> text.settings.modelListStateRefreshing
    is CatalogueStatus.Done -> if (status.changed) {
        text.settings.modelListStateUpdated
    } else {
        text.settings.modelListStateCurrent
    }

    is CatalogueStatus.Failed -> text.settings.modelListStateFailed
}

/**
 * The relocation walk's state, as the row's value.
 *
 * The same three outcomes the note under the button reports, in one word each. The
 * damaged-relocator case is a failure here and not a fourth word: from the row's point
 * of view it is a walk that came back with something the user has to act on, and the
 * note is where the action is named.
 */
private fun relocateState(
    result: PrefixPatcher.Result?,
    running: Boolean,
    text: Strings,
): String? = when {
    running -> text.settings.relocateStateScanning
    result == null -> null
    result.relocatorDamaged || result.errors.isNotEmpty() -> text.settings.relocateStateFailed
    result.changed -> text.settings.relocateStateRepaired
    else -> text.settings.relocateStateClean
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
