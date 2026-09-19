package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.AgentStatus
import pi.kit.mob.pi.PiAgentSession
import kotlinx.coroutines.launch

/**
 * Working directory and the process controls.
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
