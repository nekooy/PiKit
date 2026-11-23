package pi.kit.mob.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import pi.kit.mob.BuildConfig
import pi.kit.mob.data.UpdateCheck
import pi.kit.mob.env.BundledImage
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiInstallation
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.rememberLastPresent
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * What PiKit is, what it ships, and where those pieces live.
 *
 * ## Why the environment's page is here
 *
 * The runtime facts used to be a page of their own behind a "运行环境" row on the tab
 * root, and it was reported as a page with nothing on it to *do* — every row is a
 * read-only fact — that repeated the application facts this page already carried:
 * *inside the runtime* was listed twice (as 内置 pi here and 已安装镜像 there), the tool
 * list was drawn twice, and the agent's process state was a third row about something
 * the "Agent 配置" row on the tab root and the Agent page both already state. So the
 * two pages are one, and the duplicate rows are gone rather than merged:
 *
 *  * **Kept, from this page:** the app's version and package id, pi's version and its
 *    entry point inside `$PREFIX`, and the tool list — one row, with the missing ones
 *    marked, rather than one row per tool.
 *  * **Kept, from the environment page:** the bootstrap tag and version the environment
 *    was unpacked from, and the three paths (`$PREFIX`, `$HOME`, the app's own files
 *    directory) with the note that a package installed there is relocated to that
 *    prefix. These are the facts a bug report is read against that had no other home.
 *    The image's own revision digest was a fifth, and it is gone: it moves only when
 *    the bootstrap beside it does, so it was a second row for one fact.
 *  * **Dropped:** the runtime's *live* state and the agent's process state. Both are
 *    already on screen where they matter — the root gates the whole interface on the
 *    runtime being ready, and the Agent page is where the process is started, stopped
 *    and restarted — so a copy here was a third place to read them that could disagree.
 *  * **Kept as a group:** the licence and the credits are the page's third group — two
 *    rows that open the full text on a page of its own. They are no longer paragraphs
 *    under two `SmallTitle`s: nine credit lines below a run of facts read as a
 *    footnote to the page, and a row keeps the group vocabulary the two cards above
 *    already use while the prose keeps a page's own measure.
 *
 * The two fact cards are split by what a row belongs *to*, not by which page it came from:
 * **应用** is the app and its own facts, **运行环境** is the runtime the app carries. pi's
 * version and the tool list therefore sit in the second, because both live inside
 * `$PREFIX` and arrive with the unpacked image. Under the app's own version row they
 * read as three versions of one thing released together — which is the confusion §3
 * exists to prevent, and the reason the image's revision is the row they now sit under.
 *
 * ## The machine strings, and where they are now
 *
 * §9.2's rule about a machine string is about *where it belongs*, and both halves still
 * hold: a path is handed in as the row's *summary*, which has the row's full width and
 * wraps, and a version sits in the trailing value slot beside the fact it belongs to.
 * A version is drawn in the monospace face there, because a revision "reads back wrong
 * in proportional type" and Miuix's text takes a family per call site.
 */
@Composable
internal fun AboutPage(
    session: PiAgentSession,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    val text = strings
    val env = session.env
    // Remembered rather than called from the composition body: `requiredTools` stats four
    // paths and `installedVersion` reads and parses pi's `package.json`, and the page
    // recomposes on every settings emission and every update-row change. Keyed on the
    // environment, which is the only thing that can make the answer different — a
    // reinstall behind the page is picked up on the next visit, as it was before.
    val tools = remember(env) { PiInstallation.requiredTools(env) }
    val piVersion = remember(env) { PiInstallation.installedVersion(env) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The check's answer lives in the composition and nowhere else: it is a fact
    // about the network at the moment it was asked, and remembering it across a
    // launch would show a release that may have been superseded. Reopening the page
    // resets the row to its idle state, which is one tap from an answer again.
    var updateState by remember { mutableStateOf<UpdateRow>(UpdateRow.Idle) }
    // The reason a failed check reported, held until the reader dismisses it. Null is
    // "no dialog"; the row keeps its own one-word status either way.
    var updateError by remember { mutableStateOf<String?>(null) }

    val pageScroll = rememberScrollState()
    PiPage(
        title = text.settings.aboutTitle,
        subtitle = "PiKit ${BuildConfig.VERSION_NAME}",
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
        overlay = {
            // The failure is an event, so it is a dialog; composed in the overlay it
            // samples the page's backdrop instead of being a window above it.
            val shownUpdateError = rememberLastPresent(updateError)
            SettingsAlert(
                show = updateError != null,
                title = text.settings.checkForUpdates,
                body = text.settings.failedWith(shownUpdateError.orEmpty()),
                confirm = text.common.ok,
                onConfirm = { updateError = null },
            )
        },
    ) { padding ->
        // The page draws through the navigation strip now, so the strip's height is content
        // padding at the end of the scroll: the last row can still be scrolled clear of the
        // gesture handle, which a bottom padding on the viewport would have prevented.
        Column(Modifier.settingsPageScroll(padding, pageScroll)) {
            PiSection(title = text.settings.application) {
                BasicComponent(
                    title = "PiKit",
                    summary = text.settings.appSubtitle,
                    endActions = { SettingsValue(BuildConfig.VERSION_NAME, monospace = true) },
                )
                BasicComponent(
                    title = text.settings.packageName,
                    // The application id is a machine string the licence and every bug
                    // report are read against, so it is the row's summary and wraps
                    // rather than being cut at one line.
                    summary = env.packageId,
                )
                // The last row of this section rather than a section of its own: it is a
                // fact about the application, and the row above it already says which
                // version this build is. See the note below the section for what tapping
                // it does — the one thing on this page that reaches the network.
                ArrowPreference(
                    title = text.settings.checkForUpdates,
                    // One short status line, and the idle text is kept short enough to
                    // stay on it: a failure's reason used to sit here and wrapped to two
                    // or three lines, so tapping the row grew it — the height of the row
                    // depended on what the network said. The reason is a dialog now, and
                    // the idle summary is worded to the same one-line budget so the row
                    // never resizes.
                    summary = when (val state = updateState) {
                        UpdateRow.Idle -> text.settings.checkForUpdatesSubtitle
                        UpdateRow.Checking -> text.settings.updateChecking
                        UpdateRow.NoReleases -> text.settings.updateNoReleases
                        is UpdateRow.UpToDate -> text.settings.updateUpToDate
                        is UpdateRow.Available -> text.settings.updateAvailable
                        is UpdateRow.Failed -> text.settings.updateFailedShort
                    },
                    // Where the check goes while there is no answer to show, and the
                    // version once there is one: a value slot that changes subject is
                    // better than two rows saying one thing each. One line either way, so
                    // the row does not resize when the answer lands — and the spinner is
                    // the row saying it is busy, which is why no second tap can queue a
                    // second request.
                    endActions = {
                        if (updateState == UpdateRow.Checking) {
                            SettingsWorking()
                        } else {
                            SettingsValue(
                                text = when (val state = updateState) {
                                    is UpdateRow.UpToDate -> state.version
                                    is UpdateRow.Available -> state.version
                                    else -> BuildConfig.REPOSITORY
                                },
                                monospace = true,
                            )
                        }
                    },
                    enabled = updateState != UpdateRow.Checking,
                    onClick = {
                        val answered = updateState
                        if (answered is UpdateRow.Available) {
                            openReleasePage(context, answered.pageUrl)
                        } else {
                            updateState = UpdateRow.Checking
                            scope.launch {
                                val outcome = checkForUpdates()
                                updateState = outcome
                                // A failed check raises the app's dialog with the reason:
                                // the row's own line is a one-word status, and "Update
                                // failed" with no reason is a report the reader cannot act
                                // on. The dialog is the settings pages' default for an
                                // operation that errors, and it is dismissed.
                                if (outcome is UpdateRow.Failed) updateError = outcome.reason
                            }
                        }
                    },
                )
            }

            PiSection(title = text.settings.environment) {
                BasicComponent(
                    title = text.settings.termuxEnvironment,
                    // The tag the version below was taken from, so the two can be read
                    // against each other and against the image builder's output.
                    summary = BundledImage.metadata(context)?.bootstrapTag ?: text.settings.unknown,
                    endActions = {
                        SettingsValue(BundledImage.termuxVersion(context), monospace = true)
                    },
                )
                // The rows about what the image *carries*, directly under the environment
                // that names it. They were in the application section, and that was the
                // wrong drawer: pi and `rg`/`fd` are not parts of this app — they live
                // inside `$PREFIX`, they arrive with the unpacked image, and the row above
                // says which environment that is. Read together they answer one question
                // ("what runtime is this and what is in it").
                //
                // The image's own revision row stood here and is gone: it was a digest of
                // the image builder's inputs (`x86_64-974c4c37820b7790`), which changes
                // exactly when the bootstrap above it does, and the bootstrap's tag and
                // version are what a reader can act on. §3 is the chapter it was read
                // against, and that reading is the builder's job, not a phone's.
                BasicComponent(
                    title = text.settings.bundledPi,
                    // 74 characters of path: as a summary it gets the row's full width and
                    // wraps, which is what keeps it readable when the user's font is
                    // larger.
                    summary = PiInstallation.CLI_ENTRY_RELATIVE,
                    endActions = {
                        SettingsValue(
                            piVersion ?: text.settings.unknown,
                            monospace = true,
                        )
                    },
                )
                BasicComponent(
                    title = text.settings.bundledTools,
                    summary = tools.entries.joinToString(", ") { (tool, present) ->
                        if (present) tool else "$tool (${text.settings.bundledToolsMissing})"
                    },
                )
                BasicComponent(
                    title = text.settings.prefix,
                    summary = env.prefixPath,
                )
                BasicComponent(
                    title = text.settings.home,
                    summary = env.homePath,
                )
                BasicComponent(
                    title = text.settings.appFiles,
                    summary = env.filesDir.absolutePath,
                )
            }

            // A group of rows, not two paragraphs at the foot of the page: the two
            // longest texts in the app belong behind rows like everything else on
            // About PiKit, and each row names the note it opens.
            PiSection(title = text.settings.legalSection) {
                ArrowPreference(
                    title = text.notes.licenceTitle,
                    summary = text.settings.licenceSubtitle,
                    onClick = { onOpen(SettingsPage.Licence) },
                )
                ArrowPreference(
                    title = text.notes.creditsTitle,
                    summary = text.settings.creditsSubtitle,
                    onClick = { onOpen(SettingsPage.Credits) },
                )
            }
        }
    }
}

/**
 * One of the About page's long-form notes, on a page of its own.
 *
 * The licence and the credits are prose rather than facts, so they cannot be a row's
 * summary: the licence is two paragraphs and the credits are one line per component.
 * A page gives them the app's reading measure and a place to scroll, while the row
 * that opened it stays a normal `ArrowPreference` in the legal group. The text is
 * drawn by [SettingsParagraph] on the column the group labels use, with no card: the
 * card that once held each block was removed because a box whose only content is a
 * paragraph has nothing to frame (§13).
 */
@Composable
internal fun NotePage(
    title: String,
    body: String,
    onBack: () -> Unit,
) {
    val pageScroll = rememberScrollState()
    PiPage(
        title = title,
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
    ) { padding ->
        Column(Modifier.settingsPageScroll(padding, pageScroll)) {
            SettingsParagraph(text = body)
        }
    }
}

/**
 * What the update row is showing.
 *
 * [Idle] is not "no update" — it is "nobody has asked yet", which is why it is a
 * state of its own rather than an empty one: the app does not check by itself (see
 * `data/UpdateCheck.kt`), so a row that opened as "up to date" would be a claim made
 * without asking.
 */
private sealed interface UpdateRow {
    data object Idle : UpdateRow
    data object Checking : UpdateRow
    data object NoReleases : UpdateRow
    data class UpToDate(val version: String) : UpdateRow
    data class Available(val version: String, val pageUrl: String) : UpdateRow
    data class Failed(val reason: String) : UpdateRow
}

/**
 * Asks GitHub, and turns the answer into the row's next state.
 *
 * `VERSION_NAME` is what the tag is compared against, and the user agent names the app
 * and its version because GitHub asks that a client identify itself — it is also what
 * makes this traffic legible in GitHub's own logs. See `UpdateCheck` for why the request
 * goes to the release page rather than to `api.github.com`.
 */
private suspend fun checkForUpdates(): UpdateRow =
    when (val outcome = UpdateCheck.latest(BuildConfig.REPOSITORY, "PiKit/${BuildConfig.VERSION_NAME}")) {
        is UpdateCheck.Outcome.Found -> {
            val release = outcome.release
            if (UpdateCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                UpdateRow.Available(release.version, release.pageUrl)
            } else {
                UpdateRow.UpToDate(BuildConfig.VERSION_NAME)
            }
        }

        UpdateCheck.Outcome.NoReleases -> UpdateRow.NoReleases
        is UpdateCheck.Outcome.Failed -> UpdateRow.Failed(outcome.reason)
    }

/**
 * Opens a release page in whatever handles `https`.
 *
 * The failure is logged rather than shown: a device with no browser is one where the
 * user already knows it, and a row that says "no application can open this" under a
 * version they can also see on GitHub themselves is noise on the one page that is
 * nothing but facts.
 */
private fun openReleasePage(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { Log.w(TAG, "no activity for $url", it) }
}

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
