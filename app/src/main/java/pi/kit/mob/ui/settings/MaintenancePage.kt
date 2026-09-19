package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiUpdater
import pi.kit.mob.pi.StorageSelfTest
import pi.kit.mob.pi.UpdateStatus

/**
 * Maintenance: updating pi and repairing the prefix.
 *
 * Both actions change files under `$PREFIX`, which is why they are together and
 * away from the settings that only change what the agent is launched with.
 */@Composable
internal fun MaintenancePage(
    session: PiAgentSession,
    updater: PiUpdater,
    selfTest: StorageSelfTest,
    onBack: () -> Unit,
) {
    val repair by session.repair.collectAsState()
    val repairRunning by session.repairRunning.collectAsState()
    val repairScanned by session.repairScanned.collectAsState()
    val status by updater.status.collectAsState()
    val output by updater.output.collectAsState()
    val version by updater.version.collectAsState()
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
                    title = text.settings.updatePi,
                    subtitle = text.settings.updatePiSubtitle,
                    icon = Icons.Filled.Refresh,
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { updater.start() },
                        enabled = status !is UpdateStatus.Checking && status !is UpdateStatus.Running,
                    ) { Text(text.settings.checkAndUpdate) }

                    if (status !is UpdateStatus.Idle && status !is UpdateStatus.Checking &&
                        status !is UpdateStatus.Running
                    ) {
                        TextButton(onClick = { updater.dismiss(); updater.refreshVersion() }) {
                            Text(text.settings.dismiss)
                        }
                    }
                }

                when (val current = status) {
                    UpdateStatus.Idle -> SettingsNote(
                        text.settings.updateIdleNote,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )

                    UpdateStatus.Checking, UpdateStatus.Running -> Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            text.settings.installing,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    is UpdateStatus.Succeeded -> Text(
                        text.settings.updatedTo(
                            current.version ?: text.settings.unknownVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    is UpdateStatus.Failed -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text.settings.updateFailedNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (output.isNotEmpty()) {
                    OutputLog(output)
                }
            }

            // Relocation is not something the user should have to think about: a
            // package is rewritten before dpkg ever unpacks it (apt's
            // `DPkg::Pre-Install-Pkgs` hook, plus the `dpkg` wrapper for anything
            // apt did not mediate), so this is the fallback for a tree that was
            // installed before those hooks existed or by something that bypassed
            // both. The reasoning that used to be on this page — package ids,
            // `DT_RUNPATH`, shebangs — is in docs/ARCHITECTURE.md, where it is
            // useful and invisible. [Strings.Settings.relocateNote] is the short
            // version, and it is deliberately under the button rather than beside
            // the title: the row states the fact, the note answers "do I need this".
            SettingsSection(text.settings.installedPackages) {
                SettingsRow(
                    title = text.settings.relocate,
                    subtitle = text.settings.relocateSubtitle,
                    icon = Icons.Filled.Extension,
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { session.repairInstalledPackages() },
                        // Guarded on the walk, not on the last result being on
                        // screen: the run takes long enough that a button disabled
                        // until Dismiss reads as broken.
                        enabled = !repairRunning,
                    ) { Text(text.settings.relocateNow) }

                    if (repair != null && !repairRunning) {
                        TextButton(onClick = { session.clearRepairResult() }) {
                            Text(text.settings.dismiss)
                        }
                    }
                }

                if (repairRunning) {
                    // The walk reads every file under `$PREFIX` — 22 000 on a
                    // freshly installed image — so it gets the same progress
                    // treatment the update section uses rather than a dead button.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            text.settings.relocateScanning(repairScanned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                repair?.let { result ->
                    Text(
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
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.errors.isNotEmpty() || result.relocatorDamaged) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Under the button, always, the way the storage check carries its
                // own explanation: a page whose two actions are "upgrade" and
                // "repair a package" has to say what the second one is for, and the
                // row's caption alone cannot.
                SettingsNote(
                    text.settings.relocateNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { selfTest.start() },
                        enabled = storageCheck !is StorageSelfTest.Status.Running,
                    ) { Text(text.settings.storageCheckRun) }

                    if (storageCheck is StorageSelfTest.Status.Finished) {
                        TextButton(onClick = { selfTest.dismiss() }) {
                            Text(text.settings.dismiss)
                        }
                    }
                }

                val finished = storageCheck as? StorageSelfTest.Status.Finished
                if (finished == null) {
                    SettingsNote(
                        text.settings.storageCheckNote,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    // The verdict and the failures, never the whole transcript. The
                    // script prints one line per check — fourteen of them on a fresh
                    // install, and one more per granted folder — and printing all of
                    // them pushed this page's own buttons off the screen and buried
                    // the one line the user came for. The full list is still one
                    // command away, and the note below says which.
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = finished.output
                                .lastOrNull { it.startsWith(STORAGE_CHECK_TALLY) }
                                ?: if (finished.passed) {
                                    text.settings.storageCheckPassed
                                } else {
                                    text.settings.storageCheckFailed
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (finished.passed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        finished.output.filter { FAILURE_MARKER in it }.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        SettingsNote(
                            text.settings.storageCheckTerminalHint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The updater's output, newest last.
 *
 * Scrollable and capped rather than truncated to the last line: npm's output is
 * only useful in context, and a failure that fits on one screen should be
 * readable without copying it anywhere.
 */
@Composable
private fun OutputLog(lines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
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
