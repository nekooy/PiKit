package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import pi.kit.mob.env.AgentContext
import pi.kit.mob.env.BatteryOptimisation
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTextField
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.design.PiNote

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
 * bottom sheet the AGENTS.md row uses — a panel over the page, not a wall of
 * prose above the one control the section is for.
 *
 * The three sections are three `PiGroup`s under one pinned bar: this page's body
 * is the page, so the bar keeps its two lines and gives the groups the height
 * (§13).
 */
@Composable
internal fun AgentPage(
    session: PiAgentSession,
    agent: AgentStatus,
    workingDir: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember(workingDir) { mutableStateOf(workingDir) }
    val text = strings
    val sheets = LocalSheetHost.current
    val instructions = remember(session) { AgentContext.file(session.env) }

    PiScaffold(
        // The row this page opens reads "Agent process"
        // (`text.settings.agentProcess`), and so do the manual and this page's
        // own section list; only the header said "Agent". Reading the row's key
        // rather than a second string with the same value is what keeps the two
        // from drifting apart again. The subtitle is the same status line the
        // row shows, from the same function.
        title = text.settings.agentProcess,
        subtitle = agentDescription(agent, text),
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding)
                .padding(top = 8.dp, bottom = PiPageBottom),
        ) {
            PiSectionHeader(text.settings.workspace)
            PiGroup {
                PiRow(
                    title = text.settings.workingDirectory,
                    // The default is `$HOME/workspace`, not `$HOME`: the agent's
                    // home also holds pi's own configuration, the saved sessions
                    // and the shared-storage links, and the guard refuses a
                    // recursive delete anywhere outside the workspace. See
                    // `TermuxEnv.workspace`.
                    subtitle = text.settings.workingDirSubtitle(session.env.workspacePath),
                    leading = { RowMark(Icons.Filled.Folder) },
                )
                PiTextField(
                    value = working,
                    onValueChange = { value ->
                        working = value
                        session.settingsStore.update { it.copy(workingDir = value) }
                    },
                    label = text.settings.workingDirectory,
                    placeholder = session.env.workspacePath,
                    // 12/4, the field's own inset inside the group's frame: a field
                    // brings its own outline, so it is inset to sit inside the group
                    // rather than drawn edge to edge (§9.2).
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            PiSectionHeader(text.settings.agentContextSection)
            PiGroup {
                // The five parts live in a sheet, the same panel shape as the
                // AGENTS.md editor under them. They used to sit open as numbered
                // points of prose, which is a page of reading before the rows this
                // section is actually for; the row's title is the question the
                // sheet answers.
                PiRow(
                    title = text.settings.agentContextLeadTitle,
                    leading = { RowMark(Icons.Filled.Info) },
                    onClick = {
                        sheets.show(Sheet(key = "agent-context-notes") {
                            ContextNotesSheet()
                        })
                    },
                )
                PiRowDivider()
                // The row part 2 points at — the one thing in this section that is
                // not prose. The whole path is the subtitle, on its own line:
                // `AGENTS.md` alone appears in a project directory too, and this is
                // the *global* one. The path is a machine string, which §9.2 keeps
                // in the subtitle — the row's full width, three lines — because a
                // third of a row cannot show a path.
                PiRow(
                    title = text.settings.agentContextInstructions,
                    subtitle = instructions.absolutePath,
                    leading = { RowMark(Icons.Filled.Description) },
                    // "Editable" in the row's own value slot, with `PiRow`'s chevron
                    // beside it — the shape the language row uses. As a `PiBadge` it
                    // was a filled pill, which is this app's *action* voice: a row
                    // whose end is a badge reads as a control inside a control, and
                    // the one thing that is true here is a fact about the file.
                    value = text.settings.agentContextInstructionsEditable,
                    onClick = {
                        sheets.show(Sheet(key = "agent-context") {
                            InstructionsEditor(session = session)
                        })
                    },
                )
            }

            PiSectionHeader(text.settings.process)
            PiGroup {
                PiRow(
                    title = text.settings.agentProcess,
                    subtitle = agentDescription(agent, text),
                    leading = { RowMark(Icons.Filled.PlayArrow) },
                    // One control, and it is the restart: stop-and-start in one tap,
                    // which is the only thing a reader comes to this row to do. The
                    // stop button beside it was the second half of that same act made
                    // into a separate, destructive-looking one — and a stopped agent
                    // is a state nobody asks for.
                    trailing = {
                        PiButton(
                            text = text.settings.restartAgent,
                            onClick = {
                                scope.launch {
                                    session.stopAgent()
                                    session.startAgent()
                                }
                            },
                            kind = PiButtonKind.Filled,
                            size = PiButtonSize.Small,
                        )
                    },
                )

                if (agent is AgentStatus.Failed) {
                    PiNotice(
                        text = agent.message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        tone = PiTone.Danger,
                    )
                }

                PiRowDivider()
                KeepAliveRow(text = text)
            }
        }
    }
}

/**
 * A leading mark, in the muted role a list row draws one in.
 *
 * The design package's rows leave the tint to `LocalContentColor`, which a group
 * sets to `onSurface` — the title's own colour, at which a leading glyph competes
 * with the words beside it. Every list in this app draws its leading mark in
 * `onSurfaceVariant`.
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
 * One numbered point of the description: a title and what it holds.
 *
 * Deliberately not a row: a row is a control — it has a value column, a chevron
 * and a ripple — and four of these five points have nothing to operate. The
 * number is drawn here rather than written into the string, so the three catalogs
 * cannot disagree about how many parts there are.
 */
@Composable
private fun ContextPoint(number: Int, title: String, body: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(
            text = "$number. $title",
            style = MaterialTheme.typography.bodyMediumEmphasized,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * What the model is given, as a sheet over the page.
 *
 * Same panel shape as [InstructionsEditor], and no close button of its own: it is
 * read-only, and the scrim, the downward drag and back already dismiss it. The body is
 * the five numbered points, which used to sit open on the page: as a sheet they are the
 * answer to the row's question rather than a wall of prose the reader scrolls past to
 * reach the one control the section owns.
 *
 * The 12dp that used to clear the title's hairline — the first point sat against the
 * rule — is the sheet title's own bottom padding now: the design system's title
 * separates itself by space rather than by a rule (§13).
 *
 * The body stays a plain scrolling `Column` rather than a `PiSheetList`: the
 * points are prose blocks, not rows, and the ceiling below is what keeps the
 * panel a panel.
 */
@Composable
private fun ContextNotesSheet() {
    val text = strings
    Column(Modifier.fillMaxWidth()) {
        PiSheetTitle(
            title = text.settings.agentContextSection,
            subtitle = text.settings.agentContextLead,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = CONTEXT_NOTES_MAX_HEIGHT)
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
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** How tall the context sheet's list may grow before it scrolls itself. */
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
 * The state is the row's subtitle, because it is a sentence rather than a value (§9.2
 * held a value to a share of the row, and this one is thirty-eight characters in
 * English). The ask is the row's trailing control, on the same line, which is the
 * shape the process pair above has — and the pairing is what the report
 * "操作按钮放同一行" asked for.
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

    PiRow(
        title = text.settings.keepAliveTitle,
        // The state is the *subtitle* rather than the value column, and that is a
        // measurement rather than a preference: the sentence is long in every catalog
        // ("Battery optimisation is off for this app") and the ask beside it is seven
        // characters, so as a value it left the title 79px — one glyph per line,
        // "后/台…" — where the same text as a subtitle has the row's full width. A
        // value is a short fact about a row; a sentence about its state is a subtitle
        // (§13).
        subtitle = if (exempt) text.settings.keepAliveGranted else text.settings.keepAliveDenied,
        leading = { RowMark(Icons.Filled.Settings) },
        // The ask is the row's trailing control, on the same line as the state it
        // changes — the same shape the process pair above now has.
        trailing = {
            // Always drawn, disabled once held: a button that appeared when the
            // exemption was missing made the section jump a button taller and then
            // shorter again as the answer changed.
            PiButton(
                text = text.settings.keepAliveAsk,
                onClick = { BatteryOptimisation.request(context) },
                kind = PiButtonKind.Outlined,
                size = PiButtonSize.Small,
                enabled = !exempt,
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
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
 * The title is [PiSheetTitle], which has no action slot, and the panel is
 * dismissible three ways that need no control at all: the scrim, a downward drag and
 * back. The ✕ that used to sit on this title's line went first, and the footer's
 * *Cancel* after it — the same reasoning applied to the last line instead of the
 * first. What is left in the footer is the two buttons that *change* the file.
 *
 * ## Why the field is Material's own and not [PiTextField]
 *
 * The design package's field cannot express what a document editor needs, and every
 * one of the three is a behaviour this file already had: a monospace `textStyle` (the
 * document's structure is headings and bullets, and a proportional face hides that),
 * `autoCorrectEnabled = false` (an autocorrected command in a bullet tells the agent
 * to run something that does not exist) and its own height bounds, which is the scroll
 * the brief keeps. So the field is `OutlinedTextField` drawn with the design system's
 * own shape and colours, and the gap is reported rather than papered over.
 */
@Composable
private fun InstructionsEditor(session: PiAgentSession) {
    val text = strings
    val file = remember(session) { AgentContext.file(session.env) }
    var draft by remember {
        mutableStateOf(
            runCatching { file.readText() }.getOrElse { AgentContext.default(session.env) },
        )
    }
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        PiSheetTitle(
            title = text.settings.agentContextInstructions,
            subtitle = file.absolutePath,
        )

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                saved = false
                failed = false
            },
            // Monospace and small, like the web-search document editor and for the
            // same reason: this is a document the model reads, its structure is
            // headings and bullets, and a proportional face hides that.
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                // A Markdown document: an autocorrected command in a bullet is a
                // sentence that tells the agent to run something that does not exist.
                autoCorrectEnabled = false,
            ),
            shape = PiShapes.card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                // 12h/12t/8b: more air above the box than the 12/4 every other
                // field uses — the editor sat against the sheet's title — and room
                // for the outcome line that follows to read as its own part.
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 8.dp)
                .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
        )

        // The note that used to sit here — "saving restarts the agent" — was the Save
        // button's own caption, drawn inside its fill on a second line. It is gone:
        // the caption made the Save button a two-line control standing beside a
        // one-line Restore, which read as two different sizes for two peer actions.
        // The consequence of the save is still stated where it always was for the
        // section rather than for the button — `agentContextSaved` reports it after
        // the fact — and the sheet's own title names the file.
        when {
            failed -> PiNote(
                text = text.settings.agentContextFailed,
                tone = PiTone.Danger,
            )

            saved -> PiNote(
                text = text.settings.agentContextSaved,
                tone = PiTone.Accent,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Both sit together on the right — Restore, Save — rather than split
                // across the row, and with room under them so the sheet's last
                // control is not against the panel edge. There is no Cancel: the
                // scrim, the back gesture and a downward drag all leave the sheet
                // without saving, and a button for that was the third of three.
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            PiButton(
                text = text.settings.agentContextRestore,
                onClick = { confirmRestore = true },
                kind = PiButtonKind.Outlined,
                size = PiButtonSize.Small,
            )
            PiButton(
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
                kind = PiButtonKind.Filled,
                size = PiButtonSize.Small,
            )
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(text.settings.agentContextRestoreTitle) },
            text = { Text(text.settings.agentContextRestoreBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The file itself, not a draft of it: this button says
                        // "restore the default", and a version that only refilled
                        // the editor would leave the file as it was until a second
                        // press of Save — which is the button beside it, not this
                        // one. The question above is what makes writing here safe.
                        //
                        // `AgentContext.default` is also what the installer writes,
                        // so this is the *initial* text rather than a second copy of
                        // it kept for this button.
                        val restored = AgentContext.default(session.env)
                        val written = runCatching {
                            file.parentFile?.mkdirs()
                            file.writeText(restored)
                        }.isSuccess
                        // The editor shows what is in the file, whether or not the
                        // write worked: leaving an unsaved draft of the default on
                        // screen after a failed write would invite a Save that
                        // appears to do nothing.
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
                ) { Text(text.settings.agentContextRestoreConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(text.common.cancel) }
            },
        )
    }
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
