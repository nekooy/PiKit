package pi.kit.mob.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * hold: a path is handed in as the row's *summary*, which has the row's full width and
 * wraps, and a version sits in the trailing value slot beside the fact it belongs to.
 * A version is drawn in the monospace face there, because a revision "reads back wrong
 * in proportional type" and Miuix's text takes a family per call site.
 */
@Composable
internal fun AboutPage(
    session: PiAgentSession,
    onBack: () -> Unit,
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

    PiPage(
        title = text.settings.aboutTitle,
        subtitle = "PiKit ${BuildConfig.VERSION_NAME}",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
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

            // The failure, in the app's dialog shape rather than a note under the
            // section: a check that failed is an *event* — the reader asked for it and it
            // came back wrong — so it is announced and dismissed, not left as a
            // paragraph that has to be found again by scrolling.
            SettingsAlert(
                show = updateError != null,
                title = text.settings.checkForUpdates,
                body = text.settings.failedWith(updateError.orEmpty()),
                confirm = text.common.ok,
                onConfirm = { updateError = null },
            )

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

            // Prose, and *not* in a container: a card around a paragraph of licence text
            // drew a box whose only content was the sentence, which is the one kind of
            // surface §13 reserves for a control or a subject with a body. The reader's
            // report — "许可证和致谢两项的内容文字都去掉背景色，更简洁" — is that the
            // background was doing nothing the heading above it does not.
            SmallTitle(text = text.notes.licenceTitle)
            SettingsParagraph(text = text.notes.licence)

            SmallTitle(text = text.notes.creditsTitle)
            SettingsParagraph(text = text.notes.credits)
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

/**
 * The user manual, rendered from Markdown.
 *
 * ## What this page owns, and what it does not
 *
 * The manual is translated prose — `locales/ManualText*.kt` — and the renderer is
 * `MarkdownText`, the same one the transcript draws. Its block styles are the
 * renderer's: a paragraph is Miuix's own running-prose style, `paragraph` — 17sp — and
 * its line box is raised to 26sp, where `paragraph`'s own 1.2em would be 20.4sp. The
 * extra 5.6sp is what keeps an inline code span's fill — the font's box plus a point
 * of padding — from touching the line above; a formula grows its own line through its
 * placeholder instead (see `Markdown.kt`, which carries the measurement, and
 * ARCHITECTURE §12.1 for the box it protects). A code span is drawn on its own fill
 * against the page, and headings are sized by level with a wider gap above a level-1
 * or level-2 one. What is left for the page is the *measure*, and that is what it
 * does here.
 *
 * ## The measure
 *
 * [MANUAL_MEASURE] is 560dp of block and 520dp of text after the 20dp gutters — about
 * 62 characters at 17sp, which is inside the 45–75 a line of prose is comfortable
 * at. The cap only binds on a tablet or a landscape phone: on the phone this is
 * written for, the screen is narrower than the cap and the block is the width of the
 * page. It is centred rather than left-aligned so that a wide window puts the extra on
 * both sides instead of leaving one long gap.
 *
 * ## The contents bar, and why it is pinned
 *
 * The manual is ten chapters and about seven screens of prose, and the only way to reach
 * a chapter used to be to scroll until it arrived. The bar above the document is a
 * horizontal run of chips — one per `##` heading, read from the prose itself so all
 * three languages get their own without a second list to keep in step — and a tap
 * scrolls the document to that heading.
 *
 * Pinned rather than the first thing in the scroll: a table of contents that scrolls
 * away is only useful once, which is the state the page was in before it had one. It
 * costs about 60dp of the page forever, and the manual is read in short sittings —
 * "what was the settings row called" — which is exactly the reading a chapter index is
 * for.
 *
 * The scroll *target* is measured rather than estimated: each chapter's own
 * `MarkdownText` reports its position inside the scrolling column through
 * `onGloballyPositioned`, so a heading's offset is the layout's own answer and survives
 * a font-size change or a window resize.
 *
 * ## It stays a plain scrolling `Column`
 *
 * `MarkdownText` renders a plain `Column` and must keep doing so — a `LazyColumn`
 * inside a vertically scrollable parent is measured against an infinite height and
 * throws `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`, which is a crash this page has already been
 * the source of twice. So the scrolling is the page's, and it is a `Column`; the
 * document is split into one `MarkdownText` per chapter, which is still a `Column` each
 * and still no lazy list.
 */
@Composable
internal fun ManualPage(onBack: () -> Unit) {
    val text = strings
    // The manual is prose, so it is picked by language directly rather than
    // through the label catalog. Read outside the `remember` because a
    // composition local cannot be read inside its calculation.
    val language = LocalLanguage.current
    val body = remember(language) { manualFor(language) }
    // Split once per language: the chapters are the prose's own `##` headings.
    val document = remember(body) { parseManual(body) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Where each chapter starts inside the scrolling column, in pixels. Recorded by the
    // layout rather than computed from the text, so a font-size change or a different
    // window width moves the targets with the prose.
    val starts = remember(document) { mutableStateMapOf<Int, Int>() }

    PiPage(
        // A document rather than a list of rows: the subtitle is the manual's own, and
        // the body below it is the only thing on the page.
        title = text.manual.title,
        subtitle = text.manual.subtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ManualContents(
                label = text.manual.contents,
                titles = document.chapters.map { it.title },
                onJump = { index ->
                    val y = starts[index] ?: return@ManualContents
                    scope.launch { scroll.animateScrollTo(y.coerceIn(0, scroll.maxValue)) }
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                if (document.intro.isNotBlank()) {
                    ManualProse(document.intro)
                }
                document.chapters.forEachIndexed { index, chapter ->
                    MarkdownText(
                        text = chapter.body,
                        modifier = Modifier
                            // Before the measure and the padding: the recorded position
                            // is then the top of the chapter *including* its own spacing,
                            // which is where a scroll should put it.
                            .onGloballyPositioned { coordinates ->
                                starts[index] = coordinates.positionInParent().y.roundToInt()
                            }
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = MANUAL_MEASURE)
                            .padding(start = 20.dp, end = 20.dp),
                    )
                }
                // The bottom inset is the page's own gap above the navigation bar: a
                // manual's last line set against the bar reads as a page that was cut
                // rather than as one that ended. It is a spacer rather than the last
                // chapter's padding because the last chapter ends the prose.
                Spacer(Modifier.height(MANUAL_FOOT_SPACE))
            }
        }
    }
}

/** The measure and the page's own bottom margin. */
@Composable
private fun ManualProse(text: String) {
    MarkdownText(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .widthIn(max = MANUAL_MEASURE),
    )
}

/**
 * The chapter index: a label and a run of chips, one per `##` heading, pinned above the
 * document.
 *
 * The row scrolls horizontally rather than wrapping: ten chapter titles would be three
 * lines of chips, and a pinned block three lines tall is a frame around the document
 * rather than a way into it. Each chip is the heading's own text, so the index and the
 * document cannot disagree about what a chapter is called.
 */
@Composable
private fun ManualContents(
    label: String,
    titles: List<String>,
    onJump: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // The page's own surface, so the document passes *under* the bar rather
            // than showing through it.
            .background(MiuixTheme.colorScheme.surface)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(titles) { index, title ->
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClickLabel = title) { onJump(index) },
                    shape = RoundedCornerShape(percent = 50),
                    // The composer's chip fill, for the same reason it is there: the bar is
                    // the page's `surface`, and this is the palette's step above it in both
                    // schemes (#E8E8E8 on #F7F7F7, #242424 on black). A wash would have
                    // been the other candidate and is too faint on the dark band.
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MiuixTheme.textStyles.footnote2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One `##` chapter: its heading, and the prose under it. */
private class ManualChapter(val title: String, val body: String)

/** The manual as its intro and its numbered chapters. */
private class ManualDocument(val intro: String, val chapters: List<ManualChapter>)

/**
 * Splits the manual's Markdown at its `##` headings.
 *
 * The headings are the document's own chapter markers — every language's manual is
 * written with ten of them, `## 1. …` through `## 10. …` — so this reads the prose
 * rather than a parallel list of chapters: a heading added to one catalog appears in
 * that language's index, and a language's index can never name a chapter its prose does
 * not have. The level-one `# PiKit` title and the paragraphs under it are the intro.
 */
private fun parseManual(body: String): ManualDocument {
    val intro = StringBuilder()
    val chapters = mutableListOf<ManualChapter>()
    var title: String? = null
    var current = StringBuilder()

    fun flush() {
        val heading = title ?: return
        chapters += ManualChapter(heading, current.toString())
        current = StringBuilder()
    }

    body.lineSequence().forEach { line ->
        if (line.startsWith("## ")) {
            flush()
            title = line.removePrefix("## ").trim()
            current.append(line).append('\n')
        } else {
            (if (title == null) intro else current).append(line).append('\n')
        }
    }
    flush()
    return ManualDocument(intro.toString(), chapters)
}

/**
 * The width the manual's prose is set to on a window wide enough to use it.
 *
 * A cap rather than a fixed width: on the ~411dp phone this app is written for, the
 * page's own width is the measure and nothing here changes anything.
 */
private val MANUAL_MEASURE = 560.dp

/** The air under the manual's last line, above the page's own bottom inset. */
private val MANUAL_FOOT_SPACE = 24.dp
