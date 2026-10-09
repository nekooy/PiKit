package pi.kit.mob.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import pi.kit.mob.ui.design.PiAppBarScroll
import pi.kit.mob.ui.design.PiCard
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiValueRow
import pi.kit.mob.ui.design.PiNote

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
 *
 * ## The machine strings, and where they are now
 *
 * §9.2's rule about a machine string is about *where it belongs*, and both halves still
 * hold: a path is handed in as the row's subtitle, which has the row's full width and
 * three lines, and a version or a revision sits in the value column beside the fact it
 * belongs to. Two things that chapter's own row did are out of reach here, and both are
 * recorded rather than worked around with a row of this file's own:
 *
 *  - **The monospace face** it asks for, because a revision "reads back wrong in
 *    proportional type". The design package's rows carry no font-family slot.
 *  - **The cap on the value column.** §9.2 held the value to 0.35 of the row — 351 px of
 *    the 1016 px a row has, which kept the label the other 349 — so that a 23-character
 *    revision could not squeeze the title beside it. `PiValueRow` gives the value its
 *    natural width and caps it at two lines instead, so the title keeps whatever is
 *    left of the row.
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

    PiScaffold(
        title = text.settings.aboutTitle,
        subtitle = "PiKit ${BuildConfig.VERSION_NAME}",
        onBack = onBack,
        scrollBehavior = PiAppBarScroll.Pinned,
    ) { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding),
        ) {
            PiSectionHeader(text.settings.application)
            PiGroup {
                PiValueRow(
                    title = "PiKit",
                    subtitle = text.settings.appSubtitle,
                    leading = { RowMark(Icons.Filled.Info) },
                    value = BuildConfig.VERSION_NAME,
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.packageName,
                    // The application id is a machine string the licence and
                    // every bug report are read against, so it is the row's
                    // subtitle and wraps rather than being cut at one line.
                    subtitle = env.packageId,
                    leading = { RowMark(Icons.Filled.Info) },
                )
                PiRowDivider()
                // The last row of this section rather than a section of its own: it
                // is a fact about the application, and the row above it already says
                // which version this build is. See the note below the section for
                // what tapping it does — the one thing on this page that reaches the
                // network.
                PiRow(
                    title = text.settings.checkForUpdates,
                    // One short status line, and the idle text is kept short enough
                    // to stay on it: a failure's reason used to sit here and wrapped
                    // to two or three lines, so tapping the row grew it — the height
                    // of the row depended on what the network said. The reason is a
                    // note under the section now, and the idle subtitle is worded to
                    // the same one-line budget so the row never resizes.
                    subtitle = when (val state = updateState) {
                        UpdateRow.Idle -> text.settings.checkForUpdatesSubtitle
                        UpdateRow.Checking -> text.settings.updateChecking
                        UpdateRow.NoReleases -> text.settings.updateNoReleases
                        is UpdateRow.UpToDate -> text.settings.updateUpToDate
                        is UpdateRow.Available -> text.settings.updateAvailable
                        is UpdateRow.Failed -> text.settings.updateFailedShort
                    },
                    leading = { RowMark(Icons.Filled.Refresh) },
                    // Where the check goes while there is no answer to show, and the
                    // version once there is one: a value column that changes subject
                    // is better than two rows saying one thing each. One line either
                    // way, so the row does not resize when the answer lands.
                    trailing = if (updateState == UpdateRow.Checking) {
                        {
                            // 20dp, the chevron's own box, so the row does not change
                            // size when the mark swaps — and the design system's
                            // loading indicator rather than a spinner (§13).
                            PiLoading(size = 20.dp)
                        }
                    } else {
                        {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = when (val state = updateState) {
                                        is UpdateRow.UpToDate -> state.version
                                        is UpdateRow.Available -> state.version
                                        else -> BuildConfig.REPOSITORY
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // The chevron is drawn here because `PiRow` draws its
                                // own only when the trailing slot is empty, and this
                                // row reports a value *and* acts.
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    // No chevron while the check runs: a second tap would queue a
                    // second request behind the first, and the indicator is already
                    // the row saying it is busy.
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
            // The failure's reason, in full, outside the row: a note can wrap
            // without changing the height of anything above or below it. The
            // only note under this section — the standing paragraph that used
            // to sit here explained the check in three sentences and was the
            // page's second thing to read after the row it described.
            (updateState as? UpdateRow.Failed)?.let { failed ->
                PiNote(text.settings.failedWith(failed.reason))
            }

            PiSectionHeader(text.settings.environment)
            PiGroup {
                PiValueRow(
                    title = text.settings.termuxEnvironment,
                    // The tag the version below was taken from, so the two can be read
                    // against each other and against the image builder's output.
                    subtitle = BundledImage.metadata(context)?.bootstrapTag ?: text.settings.unknown,
                    leading = { RowMark(Icons.Filled.Memory) },
                    value = BundledImage.termuxVersion(context),
                )
                PiRowDivider()
                PiValueRow(
                    title = text.settings.installedImage,
                    subtitle = text.settings.installedImageSubtitle,
                    leading = { RowMark(Icons.Filled.Memory) },
                    // A revision is 23 characters of hex and digits: it reads back
                    // wrong in proportional type, and it is the string §9.2 capped the
                    // value column for. Both are why this row wants a monospace face
                    // and two lines — see this page's note on the machine strings.
                    value = env.installedRevision ?: text.settings.notInstalled,
                )
                PiRowDivider()
                // The two rows about what the image *carries*, directly under the
                // revision that names it. They were in the application section, and
                // that was the wrong drawer: pi and `rg`/`fd` are not parts of this
                // app — they live inside `$PREFIX`, they arrive with the unpacked
                // image, and the row above says which image that is. Read together
                // the three answer one question ("what runtime is this and what is in
                // it"); split across two cards they answered two half-questions, and
                // pi's version sat under "PiKit 0.2.1" as though the two came from the
                // same place.
                PiValueRow(
                    title = text.settings.bundledPi,
                    // 74 characters of path. It measured 699 px on one line and
                    // fits at font scale 1.0 only; as a subtitle it gets the row's
                    // full width and three lines, which is what keeps it readable
                    // when the user's font is larger.
                    subtitle = PiInstallation.CLI_ENTRY_RELATIVE,
                    leading = { RowMark(Icons.Filled.Build) },
                    value = PiInstallation.installedVersion(env) ?: text.settings.unknown,
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.bundledTools,
                    subtitle = tools.entries.joinToString(", ") { (tool, present) ->
                        if (present) tool else "$tool (${text.settings.bundledToolsMissing})"
                    },
                    leading = { RowMark(Icons.AutoMirrored.Filled.MenuBook) },
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.prefix,
                    subtitle = env.prefixPath,
                    leading = { RowMark(Icons.Filled.Storage) },
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.home,
                    subtitle = env.homePath,
                    leading = { RowMark(Icons.Filled.Home) },
                )
                PiRowDivider()
                PiRow(
                    title = text.settings.appFiles,
                    subtitle = env.filesDir.absolutePath,
                    leading = { RowMark(Icons.Filled.Folder) },
                )
            }
            PiNote(text.settings.runtimePrefixNote)

            // Prose blocks, each in a `PiCard` rather than a group: this is one
            // subject with a body of its own, which is what a card is for (§13), and
            // the two are read rather than acted on, so nothing in them is a row.
            PiSectionHeader(text.notes.licenceTitle)
            PiCard {
                Text(
                    text = text.notes.licence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PiSectionHeader(text.notes.creditsTitle)
            PiCard {
                Text(
                    text = text.notes.credits,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A leading mark, in the muted role a list row draws one in.
 *
 * The design package's rows leave the tint to `LocalContentColor`, which a group sets
 * to `onSurface` — the title's own colour, at which a leading glyph competes with the
 * words beside it. Every list in this app draws its leading mark in `onSurfaceVariant`.
 */
@Composable
private fun RowMark(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * The user manual, rendered from Markdown.
 *
 * ## What this page owns, and what it does not
 *
 * The manual is translated prose — `locales/ManualText*.kt` — and the renderer is
 * `MarkdownText`, the same one the transcript draws. Its block styles are the
 * renderer's: headings are sized by level with a wider gap above a level-1 or level-2
 * one, a code span is drawn on its own fill against the page, and a paragraph is set
 * in a 22sp line box where Material's own default for 14sp text is 20sp (see that
 * file). What is left for the page is the *measure*, and that is what it does here.
 *
 * ## The measure
 *
 * [MANUAL_MEASURE] is 560dp of block and 520dp of text after the 20dp gutters — about
 * 74 characters at `bodyMedium`, which is the top of the 45–75 a line of prose is
 * comfortable at. The cap only binds on a tablet or a landscape phone: on the phone
 * this is written for, the screen is narrower than the cap and the block is the width
 * of the page. It is centred rather than left-aligned so that a wide window puts the
 * extra on both sides instead of leaving one long gap.
 *
 * ## It stays a plain scrolling `Column`
 *
 * `MarkdownText` renders a plain `Column` and must keep doing so — a `LazyColumn`
 * inside a vertically scrollable parent is measured against an infinite height and
 * throws `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`, which is a crash this page has already been
 * the source of twice. So the scrolling is the page's, and it is a `Column`.
 */
@Composable
internal fun ManualPage(onBack: () -> Unit) {
    val text = strings
    // The manual is prose, so it is picked by language directly rather than
    // through the label catalog. Read outside the `remember` because a
    // composition local cannot be read inside its calculation.
    val language = LocalLanguage.current
    val body = remember(language) { manualFor(language) }

    PiScaffold(
        // A document rather than a list of rows: the title keeps its two lines while
        // the reader is at the top of the manual and gives the page the height back as
        // they read into it (§13).
        title = text.manual.title,
        subtitle = text.manual.subtitle,
        onBack = onBack,
        scrollBehavior = PiAppBarScroll.Collapsing,
    ) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            MarkdownText(
                text = body,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = MANUAL_MEASURE)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * The width the manual's prose is set to on a window wide enough to use it.
 *
 * A cap rather than a fixed width: on the ~411dp phone this app is written for, the
 * page's own width is the measure and nothing here changes anything.
 */
private val MANUAL_MEASURE = 560.dp
