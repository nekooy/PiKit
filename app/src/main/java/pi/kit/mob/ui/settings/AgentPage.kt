package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.env.AgentContext
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet

/**
 * Working directory, what the model is given, and the process controls.
 *
 * The working directory keeps saving as it is typed — deliberately unlike the
 * model form. It is a path the user is copying from somewhere else, nothing
 * downstream is invalidated by a half-typed value, and there is no provider to
 * bill for a mistake.
 *
 * Thinking level used to live here. It is a property of the model answering —
 * pi combines it with the model's own reasoning capability, and the two are
 * meaningless apart — so it moved to the model page, which is also where the
 * value it applies *to* is chosen.
 *
 * The context description used to be a page of its own. It is a section here
 * now: the answer to "what does the agent know?" sits with the workspace it
 * works in and the process that launches it. The five points stay points — a
 * reader checking whether something is in the context is looking for a heading
 * — and the one part the user owns is the row that follows them.
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

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            // The row this page opens reads "Agent process"
            // (`text.settings.agentProcess`), and so do the manual and this page's
            // own section list; only the header said "Agent". Reading the row's key
            // rather than a second string with the same value is what keeps the two
            // from drifting apart again. The subtitle is the same status line the
            // row shows, from the same function.
            title = text.settings.agentProcess,
            subtitle = agentDescription(agent, text),
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.workspace) {
                SettingsRow(
                    title = text.settings.workingDirectory,
                    // The default is `$HOME/workspace`, not `$HOME`: the agent's
                    // home also holds pi's own configuration, the saved sessions
                    // and the shared-storage links, and the guard refuses a
                    // recursive delete anywhere outside the workspace. See
                    // `TermuxEnv.workspace`.
                    subtitle = text.settings.workingDirSubtitle(session.env.workspacePath),
                    icon = Icons.Filled.Folder,
                )
                OutlinedTextField(
                    value = working,
                    onValueChange = { value ->
                        working = value
                        session.settingsStore.update { it.copy(workingDir = value) }
                    },
                    label = { Text(text.settings.workingDirectory) },
                    placeholder = { Text(session.env.workspacePath) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                SettingsNote(
                    text.settings.workingDirNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            SettingsSection(text.settings.agentContextSection) {
                // The five parts, as numbered points of prose. A numbered title
                // over a body, rather than one paragraph: the second part names
                // *two* files and the third is a list of variables, so a reader
                // who wants to know whether something is in the context at all is
                // looking for a heading, not for a clause in the middle of a
                // sentence.
                Text(
                    text = text.settings.agentContextLead,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                )
                ContextPoint(1, text.settings.agentContextSystemTitle, text.settings.agentContextSystemBody)
                ContextPoint(
                    2,
                    text.settings.agentContextInstructionsTitle,
                    text.settings.agentContextInstructionsBody,
                )
                ContextPoint(3, text.settings.agentContextRuntimeTitle, text.settings.agentContextRuntimeBody)
                ContextPoint(4, text.settings.agentContextToolsTitle, text.settings.agentContextToolsBody)
                ContextPoint(5, text.settings.agentContextHistoryTitle, text.settings.agentContextHistoryBody)
                SettingsDivider()
                // The row part 2 points at — the one thing in this section that is
                // not prose. It is the card's **last** element on purpose: a row
                // with content under it draws a ripple whose bottom corners are
                // rounded inside the card, which reads as a detached row (§9.2).
                //
                // The whole path is the subtitle, monospace and on its own line:
                // `AGENTS.md` alone appears in a project directory too, and this is
                // the *global* one.
                SettingsRow(
                    title = text.settings.agentContextInstructions,
                    subtitle = instructions.absolutePath,
                    monospace = true,
                    value = text.settings.agentContextInstructionsEditable,
                    icon = Icons.Filled.Description,
                    showChevron = true,
                    onClick = {
                        sheets.show(Sheet(key = "agent-context") {
                            InstructionsEditor(
                                session = session,
                                onClose = { sheets.dismiss() },
                            )
                        })
                    },
                )
            }
            SettingsNote(text.settings.agentContextNote)

            SettingsSection(text.settings.process) {
                SettingsRow(
                    title = text.settings.agentProcess,
                    subtitle = agentDescription(agent, text),
                    icon = Icons.Filled.PlayArrow,
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                session.stopAgent()
                                session.startAgent()
                            }
                        },
                    ) { Text(text.settings.restartAgent) }

                    OutlinedButton(
                        onClick = { scope.launch { session.stopAgent() } },
                        enabled = agent != AgentStatus.Stopped,
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text(text.settings.stopAgent, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                if (agent is AgentStatus.Failed) {
                    Text(
                        agent.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            SettingsNote(text.settings.failedStartNote)
        }
    }
}

/**
 * One numbered point of the description: a title and what it holds.
 *
 * Deliberately not a `SettingsRow`: a row is a control — it has a value column, a
 * chevron and a ripple — and four of these five points have nothing to operate. The
 * number is drawn here rather than written into the string, so the three catalogs
 * cannot disagree about how many parts there are.
 */
@Composable
private fun ContextPoint(number: Int, title: String, body: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = "$number. $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
 */
@Composable
private fun InstructionsEditor(
    session: PiAgentSession,
    onClose: () -> Unit,
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = text.settings.agentContextInstructions,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = text.common.cancel)
            }
        }

        HorizontalDivider()

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
        )

        Text(
            text = text.settings.agentContextEditorNote,
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            failed -> Text(
                text = text.settings.agentContextFailed,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            saved -> Text(
                text = text.settings.agentContextSaved,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text(text.common.cancel) }
            TextButton(onClick = { confirmRestore = true }) {
                Text(text.settings.agentContextRestore)
            }
            Box(Modifier.weight(1f))
            Button(
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
            ) {
                Text(text.settings.agentContextSave)
            }
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
 * The same pair the web-search document editor uses, and for the same reason: a floor
 * so it reads as a document rather than a field, and a cap so the buttons stay on
 * screen — past the cap the field scrolls itself. The default text is about seventy
 * lines.
 */
private val EDITOR_MIN_HEIGHT = 260.dp
private val EDITOR_MAX_HEIGHT = 460.dp
