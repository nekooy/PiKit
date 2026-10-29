package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pi.kit.mob.env.AgentContext
import pi.kit.mob.env.BatteryOptimisation
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Working directory, what the model is given, and the process controls.
 *
 * The working directory keeps saving as it is typed — deliberately unlike the
 * model form. It is a path the user is copying from somewhere else, nothing
 * downstream is invalidated by a half-typed value, and there is no provider to
 * bill for a mistake.
 *
 * Thinking level used to live here, then on the model page. It is a property of
 * the model answering — pi combines it with the model's own reasoning
 * capability, and the two are meaningless apart — and the conversation changes
 * it mid-turn, so the control is the composer's chip.
 *
 * The context description used to be a page of its own. It is a section here
 * now: the answer to "what does the agent know?" sits with the workspace it
 * works in and the process that launches it. The five points open in the same
 * bottom sheet the AGENTS.md row uses — a window over the page, not a wall of
 * prose above the one control the section is for.
 *
 * The three sections are three Miuix cards under one pinned bar: this page's body
 * is the page, so the bar keeps its two lines and gives the groups the height (§13).
 *
 * ## The two sheets are windows, not views
 *
 * Miuix's `WindowBottomSheet` is its own window, which changes one thing about this
 * page: the context notes and the `AGENTS.md` editor are remembered as two booleans and
 * drawn with `PiSheet`, where the old design system's modal layer lived inside the page
 * and a sheet was an entry in a queue. The scrim, the downward drag and the back gesture
 * dismiss either one, and the editor's document keeps its draft because the editor body
 * is what holds it — the sheet is only the frame.
 */
@Composable
internal fun AgentPage(
    session: PiAgentSession,
    agent: AgentStatus,
    workingDir: String,
    onBack: () -> Unit,
) {
    var working by remember(workingDir) { mutableStateOf(workingDir) }
    // The restart is two suspending calls — stop, then start — and pi takes about two
    // seconds to handshake. Without a state of its own the button was inert for that
    // whole window: the row's subtitle said "starting" either way, so a tap that
    // landed and a tap that missed looked identical. The flag drives the row's own
    // indicator and takes the second tap that would otherwise queue a second restart.
    var restarting by remember { mutableStateOf(false) }
    // Whether the process has actually been seen to leave `Running` since the press.
    // Without it the effect below would fire on the frame of the press, when the
    // status is still `Running` from the agent that is about to be stopped, and
    // announce a restart that had not started.
    var restartMoved by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    val text = strings
    val toasts = LocalPiToast.current
    val instructions = remember(session) { AgentContext.file(session.env) }
    LaunchedEffect(agent) {
        if (!restarting) return@LaunchedEffect
        if (agent is AgentStatus.Starting || agent is AgentStatus.Stopped) restartMoved = true
        if (!restartMoved) return@LaunchedEffect
        when (agent) {
            is AgentStatus.Running -> {
                restarting = false
                restartMoved = false
                // The toast reports the *outcome*, and only a start that succeeded has
                // one: a failed launch writes `AgentStatus.Failed`, which the note under
                // this row is already showing with pi's own stderr in it.
                toasts?.show(text.settings.restartedAgent)
            }

            is AgentStatus.Failed -> {
                restarting = false
                restartMoved = false
            }

            else -> Unit
        }
    }

    PiPage(
        // The row this page opens reads "Agent process" (`text.settings.agentProcess`),
        // and so do the manual and this page's own section list; only the old header
        // said "Agent". Reading the row's key rather than a second string with the same
        // value is what keeps the two from drifting apart again. The subtitle is the
        // same status line the row shows, from the same function.
        title = text.settings.agentProcess,
        subtitle = agentDescription(agent, text),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The page's elastic end, from the kit: three sections fit a phone and
                // five do not, and both sizes have to answer a drag. See `piOverScroll`.
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.workspace) {
                BasicComponent(
                    title = text.settings.workingDirectory,
                    // The default is `$HOME/workspace`, not `$HOME`: the agent's home
                    // also holds pi's own configuration, the saved sessions and the
                    // shared-storage links, and the guard refuses a recursive delete
                    // anywhere outside the workspace. See `TermuxEnv.workspace`.
                    summary = text.settings.workingDirSubtitle(session.env.workspacePath),
                )
                SettingsTextField(
                    value = working,
                    onValueChange = { value ->
                        working = value
                        session.settingsStore.update { it.copy(workingDir = value) }
                        // The directory is read when the process is spawned — it is the
                        // shell's own `cwd` and `PIKIT_WORKSPACE` — so a new one does not
                        // reach the running agent at all. It used to be a sentence telling
                        // the reader to restart, and then no sentence at all, which is the
                        // worse of the two: the field saved and nothing else happened. The
                        // restart is debounced by the session (1.5 s, and it waits for a
                        // turn to settle), so a typed path restarts the agent once, after
                        // the typing stops.
                        session.scheduleRestart()
                    },
                    label = text.settings.workingDirectory,
                    // The workspace path is the hint rather than a placeholder in the
                    // sense of a greyed value: Miuix's field floats its label, so the
                    // example belongs on the supporting line where it is read as an
                    // example rather than as content.
                    placeholder = session.env.workspacePath,
                )
            }

            PiSection(title = text.settings.agentContextSection) {
                // The five parts live in a sheet, the same panel shape as the AGENTS.md
                // editor under them. They used to sit open as numbered points of prose,
                // which is a page of reading before the rows this section is actually
                // for; the row's title is the question the sheet answers.
                ArrowPreference(
                    title = text.settings.agentContextLeadTitle,
                    onClick = { showContext = true },
                )
                // The row part 2 points at — the one thing in this section that is not
                // prose. The whole path is the subtitle, on its own line: `AGENTS.md`
                // alone appears in a project directory too, and this is the *global*
                // one. A path is a machine string, so it is the row's full-width second
                // line rather than its trailing value, which a third of a row cannot
                // show.
                ArrowPreference(
                    title = text.settings.agentContextInstructions,
                    summary = instructions.absolutePath,
                    // "Editable" in the row's own value slot, with the arrow beside it —
                    // the shape the language row uses. It is a fact about the file, not
                    // an action of its own, which is why it is a word rather than a
                    // second control.
                    endActions = {
                        SettingsValue(
                            text.settings.agentContextInstructionsEditable,
                            emphasised = true,
                        )
                    },
                    onClick = { showInstructions = true },
                )
            }

            PiSection(title = text.settings.process) {
                BasicComponent(
                    title = text.settings.agentProcess,
                    summary = agentDescription(agent, text),
                    endActions = {
                        // One control, and it is the restart: stop-and-start in one tap,
                        // which is the only thing a reader comes to this row to do. A
                        // stop button beside it was that same act made into a second,
                        // destructive-looking one — and a stopped agent is a state
                        // nobody asks for.
                        if (restarting) {
                            SettingsWorking()
                        } else {
                            SettingsAction(
                                text = text.settings.restartAgent,
                                // `PiAgentSession.restartAgent` and not a coroutine of
                                // this page's: a scope tied to the composition is
                                // cancelled when the page leaves, and cancelled between
                                // the stop and the start it leaves a stopped agent
                                // behind. The session's scope outlives the page, and
                                // `restarting` is only this row's spinner — cleared by
                                // the effect above when the status settles.
                                onClick = {
                                    restarting = true
                                    restartMoved = false
                                    session.restartAgent()
                                },
                            )
                        }
                    },
                )

                if (agent is AgentStatus.Failed) {
                    SettingsNote(text = agent.message, danger = true)
                }

                KeepAliveRow(text = text)
            }
        }
    }

    PiSheet(
        show = showContext,
        title = text.settings.agentContextSection,
        onDismiss = { showContext = false },
    ) {
        // The lead sentence is the row's own subtitle, which the sheet's title has no
        // slot for; the five points are what the row promised.
        Text(
            text = text.settings.agentContextLead,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Bounded and scrolled: five prose blocks are taller than a sheet may
                // grow, and the sheet's own ceiling is the status bar. A plain Column
                // rather than a lazy list, because these are paragraphs rather than
                // rows and a lazy list here would be measured against a bounded height
                // for no benefit.
                //
                // The sheet body is where the page would be, so it takes the page's
                // elastic end — on the four-point phone these notes still fit.
                .heightIn(max = CONTEXT_NOTES_MAX_HEIGHT)
                .piOverScroll()
                .verticalScroll(rememberScrollState()),
        ) {
            ContextPoint(1, text.settings.agentContextSystemTitle, text.settings.agentContextSystemBody)
            ContextPoint(
                2,
                text.settings.agentContextInstructionsTitle,
                text.settings.agentContextInstructionsBody,
            )
            ContextPoint(3, text.settings.agentContextRuntimeTitle, text.settings.agentContextRuntimeBody)
            ContextPoint(4, text.settings.agentContextToolsTitle, text.settings.agentContextToolsBody)
            ContextPoint(5, text.settings.agentContextHistoryTitle, text.settings.agentContextHistoryBody)
        }
    }

    PiSheet(
        show = showInstructions,
        title = text.settings.agentContextInstructions,
        onDismiss = { showInstructions = false },
    ) {
        if (showInstructions) {
            InstructionsEditor(session = session, text = text)
        }
    }
}

/**
 * One numbered point of the description: a title and what it holds.
 *
 * Deliberately not a row: a row is a control — it has a value slot, an arrow and a press
 * feedback — and four of these five points have nothing to operate. The number is drawn
 * here rather than written into the string, so the three catalogs cannot disagree about
 * how many parts there are.
 */
@Composable
private fun ContextPoint(number: Int, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "$number. $title",
            style = MiuixTheme.textStyles.body1,
        )
        Text(
            text = body,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** How tall the context sheet's prose may grow before it scrolls itself. */
private val CONTEXT_NOTES_MAX_HEIGHT = 440.dp

/**
 * The battery-optimisation exemption: the one keep-alive lever the OS owns.
 *
 * The state and the ask are [BatteryOptimisation]'s, which the first-launch dialog
 * uses too — this row is the *report* and the way back to it. The exemption is the
 * system's own dialog, which the app cannot draw: the foreground service and the turn
 * wake lock only work if the OS is not allowed to reclaim the process the moment the
 * screen goes off, which is the report "息屏后会直接 terminated", and this row reports
 * whether the exemption is already held and offers the ask.
 *
 * The state is the row's subtitle, because it is a sentence rather than a value: the
 * English one is thirty-eight characters, and as a trailing value it left the title 79px
 * — one glyph per line — where the same text as a subtitle has the row's full width. A
 * value is a short fact about a row; a sentence about its state is a subtitle (§13).
 */
@Composable
private fun KeepAliveRow(text: Strings) {
    val context = LocalContext.current
    // Re-read when the user comes back from the system's battery-optimisation
    // dialog or settings page. There is no result callback for either, so the
    // lifecycle is what tells us to look again — the same treatment StoragePage
    // gives "all files access". A `remember` here cached the answer for the life
    // of the page: granting the exemption and returning left the row saying
    // "not exempt" with the button still enabled until the page was reopened.
    var exempt by remember { mutableStateOf(BatteryOptimisation.isExempt(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = BatteryOptimisation.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BasicComponent(
        title = text.settings.keepAliveTitle,
        summary = if (exempt) text.settings.keepAliveGranted else text.settings.keepAliveDenied,
        endActions = {
            // Always drawn, disabled once held: a button that appeared when the exemption
            // was missing made the section jump a button taller and then shorter again as
            // the answer changed.
            SettingsAction(
                text = text.settings.keepAliveAsk,
                onClick = { BatteryOptimisation.request(context) },
                enabled = !exempt,
            )
        },
    )
}

/**
 * The editor for the global `AGENTS.md`, over the page.
 *
 * ## Its own draft
 *
 * The text is read once when the sheet opens and kept in the composition: the file is
 * the user's and re-reading it under their cursor would delete keystrokes. Nothing
 * else in the app writes it either — [AgentContext] writes it only when it is missing
 * — so there is no "the file changed underneath" state to handle.
 *
 * ## Why a save restarts the agent
 *
 * pi reads `AGENTS.md` when it starts, so a file written under a running agent is a
 * change the model does not see until the next launch. Saying "restart the agent
 * yourself" would leave the sentence the user just wrote unread, which is worse than
 * a restart they did not ask for: the same trade the storage page already makes.
 *
 * ## No close button, in the title or in the footer
 *
 * The sheet's title is Miuix's own and has no action slot, and the panel is dismissible
 * three ways that need no control at all: the scrim, a downward drag and back. What is
 * left in the footer is the two buttons that *change* the file.
 *
 * ## Why the field is Miuix's field with its own style
 *
 * A document editor needs three things a plain settings field does not: a monospace
 * face (the document's structure is headings and bullets, and a proportional face hides
 * that), `autoCorrectEnabled = false` (an autocorrected command in a bullet tells the
 * agent to run something that does not exist) and its own height bounds, which is the
 * scroll the brief keeps. Miuix's `TextField` takes all three, so the field is its own
 * call rather than a [SettingsTextField]: that wrapper exists for one-line settings
 * values and should not grow a variant for the one document in the app.
 */
@Composable
private fun InstructionsEditor(session: PiAgentSession, text: Strings) {
    val toasts = LocalPiToast.current
    val file = remember(session) { AgentContext.file(session.env) }
    var draft by remember {
        mutableStateOf(
            runCatching { file.readText() }.getOrElse { AgentContext.default(session.env) },
        )
    }
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            // Scrollable, and that is what makes the panel survive a keyboard: a
            // 240-440dp field plus two buttons against a window that just lost 300dp
            // would be *clipped* — the buttons and the bottom of the document
            // unreachable — so it scrolls instead, and it does not scroll at all while
            // everything fits.
            //
            // This is the instructions sheet's body, so it takes the page's elastic end
            // as well; `piOverScroll` is before the scroll rather than after it for the
            // reason its KDoc gives.
            .piOverScroll()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = file.absolutePath,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )

        TextField(
            cornerRadius = PiCorner.radius,
            value = draft,
            onValueChange = {
                draft = it
                saved = false
                failed = false
            },
            // Monospace and small, like the web-search document editor and for the same
            // reason: this is a document the model reads, its structure is headings and
            // bullets, and a proportional face hides that.
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                // A Markdown document: an autocorrected command in a bullet is a
                // sentence that tells the agent to run something that does not exist.
                autoCorrectEnabled = false,
            ),
            maxLines = Int.MAX_VALUE,
            // No focus ring, and the caret stated instead: see `piFieldColors`.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
        )

        // What the last press did is the app's one transient message rather than a line
        // of prose under the field, which used to stay there until the next keystroke —
        // a sentence about an action that has already finished. The note that used to
        // sit above the buttons — "saving restarts the agent" — is gone with it: the
        // save *does* restart the agent (`scheduleRestart` below), so telling the reader
        // to expect it is telling them about something they do not have to act on.
        LaunchedEffect(failed, saved) {
            if (failed || saved) {
                toasts?.show(
                    if (failed) {
                        text.settings.agentContextFailed
                    } else {
                        text.settings.agentContextSaved
                    },
                )
                failed = false
                saved = false
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The pair splits the row — Restore, then Save — with room under them so
                // the sheet's last control is not against the panel edge. It used to be
                // sized to its words on the trailing edge; a sheet's footer is the same
                // shape as a form's, so the two buttons are halves here as they are on
                // every page that ends in a pair. There is no Cancel: the scrim, the back
                // gesture and a downward drag all leave the sheet without saving.
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SettingsAction(
                text = text.settings.agentContextRestore,
                onClick = { confirmRestore = true },
                modifier = Modifier.weight(1f),
            )
            SettingsAction(
                text = text.settings.agentContextSave,
                onClick = {
                    // Written first, reported after: a save that claims to have
                    // happened and did not is the one outcome this button must not
                    // have.
                    val written = runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(draft)
                    }.isSuccess
                    failed = !written
                    saved = written
                    if (written) session.scheduleRestart()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    PiConfirm(
        show = confirmRestore,
        title = text.settings.agentContextRestoreTitle,
        text = text.settings.agentContextRestoreBody,
        confirm = text.settings.agentContextRestoreConfirm,
        dismiss = text.common.cancel,
        onConfirm = {
            // The file itself, not a draft of it: this button says "restore the
            // default", and a version that only refilled the editor would leave the file
            // as it was until a second press of Save — which is the button beside it,
            // not this one. The question above is what makes writing here safe.
            //
            // `AgentContext.default` is also what the installer writes, so this is the
            // *initial* text rather than a second copy of it kept for this button.
            val restored = AgentContext.default(session.env)
            val written = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(restored)
            }.isSuccess
            // The editor shows what is in the file, whether or not the write worked:
            // leaving an unsaved draft of the default on screen after a failed write
            // would invite a Save that appears to do nothing.
            draft = if (written) {
                restored
            } else {
                runCatching { file.readText() }.getOrDefault(restored)
            }
            confirmRestore = false
            failed = !written
            saved = written
            if (written) session.scheduleRestart()
        },
        onDismiss = { confirmRestore = false },
    )
}

/**
 * The editor's height bounds.
 *
 * The same pair the web-search document editor uses (240/440), and for the same
 * reason: a floor so it reads as a document rather than a field, and a cap so the
 * buttons stay on screen — past the cap the field scrolls itself. The numbers used
 * to be 260/460 here against 240/440 there, which is the report that two editors
 * doing the same job stood at two heights.
 */
private val EDITOR_MIN_HEIGHT = 240.dp
private val EDITOR_MAX_HEIGHT = 440.dp
