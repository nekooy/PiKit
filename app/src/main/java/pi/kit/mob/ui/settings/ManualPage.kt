package pi.kit.mob.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.BuildConfig
import pi.kit.mob.data.UpdateCheck
import pi.kit.mob.env.BundledImage
import pi.kit.mob.locales.LocalLanguage
import pi.kit.mob.locales.manualFor
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.PiInstallation
import pi.kit.mob.ui.MarkdownText

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
 * the "Agent 进程" row on the tab root and the Agent page both already state. So the
 * two pages are one, and the duplicate rows are gone rather than merged:
 *
 *  * **Kept, from this page:** the app's version and package id, pi's version and its
 *    entry point inside `$PREFIX`, and the tool list — one row, with the missing ones
 *    marked, rather than one row per tool.
 *  * **Kept, from the environment page:** the revision stamped into the unpacked image,
 *    and the three paths (`$PREFIX`, `$HOME`, the app's own files directory) with the
 *    note that a package installed there is relocated to that prefix. These are the
 *    facts a bug report is read against that had no other home.
 *  * **Dropped:** the runtime's *live* state and the agent's process state. Both are
 *    already on screen where they matter — the root gates the whole interface on the
 *    runtime being ready, and the Agent page is where the process is started, stopped
 *    and restarted — so a copy here was a third place to read them that could disagree.
 *  * **Not repeated:** the licence and the credits are still sections of this page.
 *
 * The two cards are split by what a row belongs *to*, not by which page it came from:
 * **应用** is the app and its own facts, **运行环境** is the runtime the app carries. pi's
 * version and the tool list therefore sit in the second, because both live inside
 * `$PREFIX` and arrive with the unpacked image. Under the app's own version row they
 * read as three versions of one thing released together — which is the confusion §3
 * exists to prevent, and the reason the image's revision is the row they now sit under.
 */
@Composable
internal fun AboutPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val env = session.env
    val tools = PiInstallation.requiredTools(env)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The check's answer lives in the composition and nowhere else: it is a fact
    // about the network at the moment it was asked, and remembering it across a
    // launch would show a release that may have been superseded. Reopening the page
    // resets the row to its idle state, which is one tap from an answer again.
    var updateState by remember { mutableStateOf<UpdateRow>(UpdateRow.Idle) }

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.aboutTitle,
            subtitle = "PiKit ${BuildConfig.VERSION_NAME}",
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.application) {
                SettingsRow(
                    title = "PiKit",
                    subtitle = text.settings.appSubtitle,
                    icon = Icons.Filled.Info,
                    value = BuildConfig.VERSION_NAME,
                    monospaceValue = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.packageName,
                    subtitle = env.packageId,
                    icon = Icons.Filled.Info,
                    // The application id is a machine string the licence and
                    // every bug report are read against; it is monospace and
                    // wraps rather than being cut at one line.
                    monospace = true,
                )
                SettingsDivider()
                // The last row of this section rather than a section of its own: it
                // is a fact about the application, and the row above it already says
                // which version this build is. See the note below the section for
                // what tapping it does — the one thing on this page that reaches the
                // network.
                SettingsRow(
                    title = text.settings.checkForUpdates,
                    subtitle = when (val state = updateState) {
                        UpdateRow.Idle -> text.settings.checkForUpdatesSubtitle
                        UpdateRow.Checking -> text.settings.updateChecking
                        UpdateRow.NoReleases -> text.settings.updateNoReleases
                        is UpdateRow.UpToDate -> text.settings.updateUpToDate
                        is UpdateRow.Available -> text.settings.updateAvailable
                        is UpdateRow.Failed -> text.settings.failedWith(state.reason)
                    },
                    icon = Icons.Filled.Refresh,
                    // Where the check goes while there is no answer to show, and the
                    // version once there is one: a value column that changes subject
                    // is better than two rows saying one thing each.
                    value = when (val state = updateState) {
                        is UpdateRow.UpToDate -> state.version
                        is UpdateRow.Available -> state.version
                        else -> BuildConfig.REPOSITORY
                    },
                    monospaceValue = true,
                    trailing = if (updateState == UpdateRow.Checking) {
                        {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        null
                    },
                    // No chevron while the check runs: a second tap would queue a
                    // second request behind the first, and the spinner is already the
                    // row saying it is busy.
                    showChevron = updateState != UpdateRow.Checking,
                    onClick = if (updateState == UpdateRow.Checking) {
                        null
                    } else {
                        {
                            val answered = updateState
                            if (answered is UpdateRow.Available) {
                                openReleasePage(context, answered.pageUrl)
                            } else {
                                updateState = UpdateRow.Checking
                                scope.launch { updateState = checkForUpdates() }
                            }
                        }
                    },
                )
            }
            SettingsNote(text.settings.updateNote)

            SettingsSection(text.settings.environment) {
                SettingsRow(
                    title = text.settings.termuxEnvironment,
                    // The tag the version below was taken from, so the two can be read
                    // against each other and against the image builder's output.
                    subtitle = BundledImage.metadata(context)?.bootstrapTag ?: text.settings.unknown,
                    icon = Icons.Filled.Memory,
                    value = BundledImage.termuxVersion(context),
                    monospaceValue = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.installedImage,
                    subtitle = text.settings.installedImageSubtitle,
                    icon = Icons.Filled.Memory,
                    value = env.installedRevision ?: text.settings.notInstalled,
                    // A revision is 23 characters of hex and digits: it reads back
                    // wrong in proportional type, and one line of it is wider than
                    // the value column is allowed to be.
                    monospaceValue = true,
                )
                SettingsDivider()
                // The two rows about what the image *carries*, directly under the
                // revision that names it. They were in the application section, and
                // that was the wrong drawer: pi and `rg`/`fd` are not parts of this
                // app — they live inside `$PREFIX`, they arrive with the unpacked
                // image, and the row above says which image that is. Read together
                // the three answer one question ("what runtime is this and what is in
                // it"); split across two cards they answered two half-questions, and
                // pi's version sat under "PiKit 0.2.1" as though the two came from the
                // same place.
                SettingsRow(
                    title = text.settings.bundledPi,
                    subtitle = PiInstallation.CLI_ENTRY_RELATIVE,
                    icon = Icons.Filled.Build,
                    value = PiInstallation.installedVersion(env) ?: text.settings.unknown,
                    // 74 characters of path. It measured 699 px on one line and
                    // fits at font scale 1.0 only; monospace and three lines is
                    // what keeps it readable when the user's font is larger.
                    monospace = true,
                    monospaceValue = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.bundledTools,
                    subtitle = tools.entries.joinToString(", ") { (tool, present) ->
                        if (present) tool else "$tool (${text.settings.bundledToolsMissing})"
                    },
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.prefix,
                    subtitle = env.prefixPath,
                    icon = Icons.Filled.Storage,
                    monospace = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.home,
                    subtitle = env.homePath,
                    icon = Icons.Filled.Home,
                    monospace = true,
                )
                SettingsDivider()
                SettingsRow(
                    title = text.settings.appFiles,
                    subtitle = env.filesDir.absolutePath,
                    icon = Icons.Filled.Folder,
                    monospace = true,
                )
            }
            SettingsNote(text.settings.runtimePrefixNote)

            SettingsSection(text.notes.licenceTitle) {
                Text(
                    text.notes.licence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            SettingsSection(text.notes.creditsTitle) {
                Text(
                    text.notes.credits,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
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

/** The user manual, rendered from Markdown. */
@Composable
internal fun ManualPage(onBack: () -> Unit) {
    val text = strings
    // The manual is prose, so it is picked by language directly rather than
    // through the label catalog. Read outside the `remember` because a
    // composition local cannot be read inside its calculation.
    val language = LocalLanguage.current
    val body = remember(language) { manualFor(language) }

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.manual.title,
            subtitle = text.manual.subtitle,
            onBack = onBack,
        )

        SettingsBody {
            // The manual routinely exceeds one screen, and MarkdownText renders a
            // plain column, so the scrolling has to come from the page body.
            MarkdownText(body, modifier = Modifier.fillMaxWidth())
        }
    }
}


