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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * ## The three runs are one row
 *
 * The page has three things that run and report: the catalogue refresh, the
 * relocation walk, and the storage self-test. Each is one `PiRow` carrying the run's
 * state in its value column *and its tone*, with the run's own control — the button,
 * or the live indicator while it is going — in the row's trailing slot, and then the
 * verdict in a `PiNotice` above the standing explanation. That shape is not a
 * preference; it is what the three were *not*: one showed its state in the row and two
 * only in prose, one drew a progress bar and one did not, and the buttons of one
 * started 4dp left of the note under them.
 *
 * The control is *in* the row rather than in a strip under it because the strip was
 * three controls for one action — the run's button, a dismiss and a pill — and it made
 * each run two objects with a paragraph between them. The reader's report is the short
 * version: "把那些操作按钮放到列表右边…点击后显示那种转圈的团状ui，然后显示已是最新".
 * [RunActions] is still the backup page's shape, where a run's own sheet gives it a
 * place to stand.
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
    val repairScanned by session.repairScanned.collectAsState()
    val catalogueStatus by catalogue.status.collectAsState()
    val version by catalogue.version.collectAsState()
    val storageCheck by selfTest.status.collectAsState()
    val text = strings

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
                // The catalogue run's state, in the value column, in the tone that says
                // what it means, and the button that starts it in the trailing slot
                // while there is nothing to report. A verdict on the row is the row's
                // own dismiss: tapping it clears the result and brings the button back
                // (see [RunControl]). The old strip under the row — a button, a dismiss
                // and a pill, three controls for one action — is what "删除多余控件"
                // asked away; its dismiss is the row now.
                val catalogueRunning = catalogueStatus is CatalogueStatus.Running
                val catalogueSettled = !catalogueRunning && catalogueStatus !is CatalogueStatus.Idle
                PiRow(
                    title = text.settings.modelList,
                    subtitle = text.settings.modelListRefreshSubtitle,
                    value = catalogueValue(catalogueStatus, text),
                    valueColor = catalogueTone(catalogueStatus).ink(MaterialTheme.colorScheme),
                    leading = { RunMark(Icons.Filled.Refresh) },
                    onClick = if (catalogueSettled) catalogue::dismiss else null,
                    trailing = {
                        RunControl(
                            running = catalogueRunning,
                            hasResult = catalogueSettled,
                            label = text.settings.modelListRefresh,
                            onAction = { catalogue.start() },
                        )
                    },
                )
            }

            // `refreshVersion` used to ride on the dismiss button — the moment the page
            // was about to be looked at again. There is no dismiss button any more, so
            // the run's own end is that moment: the row keeps its verdict, and the
            // version beside it is re-read in case a runtime reinstall happened behind
            // the page.
            LaunchedEffect(catalogueStatus) {
                if (catalogueStatus is CatalogueStatus.Done ||
                    catalogueStatus is CatalogueStatus.Failed
                ) {
                    catalogue.refreshVersion()
                }
            }

            // A failure is the one outcome the row cannot state on its own — its
            // reason is a sentence — so it keeps a notice. A *success* does not: the
            // row's value already says "已更新" or "已是最新", and a second banner
            // saying the same thing in a sentence was the "完成的横幅" the page was
            // asked to drop.
            (catalogueStatus as? CatalogueStatus.Failed)?.let { failed ->
                PiNotice(text = failed.message, tone = PiTone.Danger)
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
            PiSectionHeader(text.settings.installedPackages)
            PiGroup {
                val repairSettled = !repairRunning && repair != null
                PiRow(
                    title = text.settings.relocate,
                    subtitle = text.settings.relocateSubtitle,
                    // The walk reads every file under `$PREFIX` — 22 000 on a freshly
                    // installed image — so while it runs the column reports what it has
                    // read rather than a bar that appears frozen, and the row's control
                    // is the live indicator. Same treatment as the catalogue above.
                    value = relocateValue(repair, repairRunning, repairScanned, text),
                    valueColor = relocateTone(repair, repairRunning).ink(MaterialTheme.colorScheme),
                    leading = { RunMark(Icons.Filled.Extension) },
                    onClick = if (repairSettled) session::clearRepairResult else null,
                    trailing = {
                        RunControl(
                            running = repairRunning,
                            hasResult = repairSettled,
                            label = text.settings.relocateNow,
                            onAction = { session.repairInstalledPackages() },
                        )
                    },
                )
            }

            repair?.let { result -> RelocationVerdict(result) }

            // Storage does not belong on this page conceptually — it changes no
            // file under $PREFIX — but the *check* is a maintenance action, and it
            // is the only place a user can get an answer to "can the agent really
            // reach my files, and will it ever delete them" without a terminal.
            PiSectionHeader(text.settings.storageCheck)
            PiGroup {
                val storageRunning = storageCheck is StorageSelfTest.Status.Running
                val storageSettled = !storageRunning && storageCheck is StorageSelfTest.Status.Finished
                PiRow(
                    title = text.settings.storageCheck,
                    subtitle = text.settings.storageCheckSubtitle,
                    value = storageCheckState(storageCheck, text).orEmpty(),
                    valueColor = storageCheckTone(storageCheck).ink(MaterialTheme.colorScheme),
                    leading = { RunMark(Icons.Filled.VerifiedUser) },
                    onClick = if (storageSettled) selfTest::dismiss else null,
                    trailing = {
                        RunControl(
                            running = storageRunning,
                            hasResult = storageSettled,
                            label = text.settings.storageCheckRun,
                            onAction = { selfTest.start() },
                        )
                    },
                )
            }

            StorageCheckVerdict(storageCheck)
        }
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
 * The one control a run has: its button, or the live indicator while it is going.
 *
 * The pair is exclusive rather than a disabled button beside an indicator, which is
 * what the button-with-a-pill-under-it strip drew: while a run is in flight there is
 * nothing to press, and a disabled `Filled` button is the same statement twice. The
 * indicator is `PiLoading` rather than `PiStatePill` because the word that pill
 * carries lives in the row's own value column — "正在扫描 1234 个文件" — where it has the
 * width for a number and does not make the row change height when it arrives.
 *
 * [hasResult] hides the button once the run has a verdict on the row. The value
 * column then *is* the row's report — "已修复", "已是最新" — and a button beside a
 * verdict invites a second run against an answer the reader has not read yet. The
 * way back to the button is the row itself: a tap clears the verdict, and the button
 * returns where it was. That gesture is what keeps hiding it from being a dead end —
 * nothing else on this page dismisses a result.
 */
@Composable
private fun RunControl(
    running: Boolean,
    hasResult: Boolean,
    label: String,
    onAction: () -> Unit,
) {
    when {
        running -> PiLoading(size = 20.dp, color = PiTone.Live.ink(MaterialTheme.colorScheme))
        hasResult -> Unit
        else -> PiButton(
            text = label,
            onClick = onAction,
            kind = PiButtonKind.Filled,
            size = PiButtonSize.ExtraSmall,
        )
    }
}

/**
 * The catalogue run's value: what it is doing, or what it found.
 *
 * The two are one column because they are one question — "where is this run" — and the
 * pill that used to carry the running half is gone with the strip; the words are the
 * same ones it showed.
 */
private fun catalogueValue(status: CatalogueStatus, text: Strings): String =
    if (status is CatalogueStatus.Running) {
        text.settings.modelListRefreshing
    } else {
        catalogueState(status, text).orEmpty()
    }

/** The relocation walk's value, the same split as [catalogueValue]. */
private fun relocateValue(
    result: PrefixPatcher.Result?,
    running: Boolean,
    scanned: Int,
    text: Strings,
): String = if (running) {
    text.settings.relocateScanning(scanned)
} else {
    relocateState(result, running, text).orEmpty()
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
 * What the relocation walk found, when that is something to act on.
 *
 * The two failure shapes keep a notice; the two successes — repaired and nothing to
 * do — do not, because the row's own value already says "已修复" or "无需修复" and a
 * banner repeating it was one of the "完成的横幅" the page was asked to drop. So this
 * only draws when there is a sentence the value cannot carry.
 *
 * Checked in this order because the damaged relocator is the one outcome the walk
 * cannot repair from the inside: the relocator no longer names the upstream id at
 * all, so its own `OLD_ID` was rewritten and it now refuses to run (`exit 4`) —
 * every later `pkg install` goes unrelocated until the runtime is reinstalled, and
 * that is the one sentence here that names an action. The failing files come next,
 * as the heading and then the lines.
 */
@Composable
private fun RelocationVerdict(result: PrefixPatcher.Result) {
    val text = strings
    when {
        result.relocatorDamaged -> PiNotice(
            text = text.settings.relocateBroken,
            tone = PiTone.Danger,
        )

        result.errors.isNotEmpty() -> {
            PiNotice(text = text.settings.relocateProblems, tone = PiTone.Danger)
            ErrorBody(result.errors)
        }

        // Repaired or already clean: the row's value is the whole report.
        else -> Unit
    }
}

/**
 * What the storage self-test found, when it found a failure.
 *
 * A pass is the row's own word ("通过") and nothing more — the success banner was a
 * "完成的横幅" and is gone with the others. A failure keeps its notice and its
 * failing lines: the verdict's numbers are the tally line the script prints, which
 * the row cannot carry, and the lines are what the reader came for.
 *
 * The line list is never the whole transcript. The script prints one line per check
 * — fourteen on a fresh install, and one more per granted folder — and printing all
 * of them pushed this page's own controls off the screen and buried the one line the
 * user came for. The full list is still one command away.
 */
@Composable
private fun StorageCheckVerdict(status: StorageSelfTest.Status) {
    val text = strings
    val finished = status as? StorageSelfTest.Status.Finished ?: return
    if (finished.passed) return

    PiNotice(
        text = finished.output.lastOrNull { it.startsWith(STORAGE_CHECK_TALLY) }
            ?: text.settings.storageCheckFailed,
        tone = PiTone.Danger,
    )
    ErrorBody(finished.output.filter { FAILURE_MARKER in it })
}

/**
 * The catalogue run's state, as the row's value: null while there is nothing to say.
 *
 * A word rather than a sentence, because the value column is a share of the row:
 * "Updated" is the whole fact. The sentence that used to sit under the row for this
 * outcome is gone with the completion banners.
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

/** The self-test's state, as the row's value: the same split as the two above. */
private fun storageCheckState(status: StorageSelfTest.Status, text: Strings): String? =
    when (status) {
        is StorageSelfTest.Status.Idle -> null
        is StorageSelfTest.Status.Running -> text.settings.storageCheckRunning
        is StorageSelfTest.Status.Finished -> if (status.passed) {
            text.settings.storageCheckPassed
        } else {
            text.settings.storageCheckFailed
        }
    }

/**
 * The tone a run's state is reported in, which is the design language's vocabulary
 * rather than a colour: `Live` is the one hue for a thing still going, `Accent` is
 * the app's own "this landed", `Danger` is the one that failed, and `Neutral` is a
 * row with nothing to report.
 */
private fun catalogueTone(status: CatalogueStatus): PiTone = when (status) {
    CatalogueStatus.Idle -> PiTone.Neutral
    CatalogueStatus.Running -> PiTone.Live
    is CatalogueStatus.Done -> PiTone.Accent
    is CatalogueStatus.Failed -> PiTone.Danger
}

private fun relocateTone(result: PrefixPatcher.Result?, running: Boolean): PiTone = when {
    running -> PiTone.Live
    result == null -> PiTone.Neutral
    result.relocatorDamaged || result.errors.isNotEmpty() -> PiTone.Danger
    else -> PiTone.Accent
}

private fun storageCheckTone(status: StorageSelfTest.Status): PiTone = when (status) {
    is StorageSelfTest.Status.Idle -> PiTone.Neutral
    is StorageSelfTest.Status.Running -> PiTone.Live
    is StorageSelfTest.Status.Finished -> if (status.passed) PiTone.Accent else PiTone.Danger
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
