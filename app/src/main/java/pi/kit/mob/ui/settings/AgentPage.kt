package pi.kit.mob.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pi.kit.mob.data.RefusedWorkspace
import pi.kit.mob.data.WorkspaceRefusal
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.data.WorkspaceStore
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
import pi.kit.mob.ui.kit.PiTone
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import pi.kit.mob.ui.WorkspaceDialog
import pi.kit.mob.ui.rememberWorkspacePaths
import pi.kit.mob.ui.WorkspacePickerSheet
import pi.kit.mob.ui.rememberWorkspaceList
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Agent configuration: the process that carries the conversation, the workspace and
 * instructions work mode reads, and chat mode's preset list.
 *
 * The page is grouped by subject rather than by launch mode. **Run** is the process
 * every conversation has — a chat conversation is a pi process too, which is why the
 * process controls no longer sit under Work mode. **Work mode** is the folder and the
 * instruction documents a work launch reads. **Chat mode** is the presets a chat launch
 * reads. Three cards, one subject each; the page title can then say what the page really
 * configures instead of naming one of its groups.
 *
 * The context row is a way in, not a control: it opens one card of five short rows that
 * answer "what does the model actually receive". It used to open five numbered
 * paragraphs, which is a page of reading before the rows the reader came for.
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
    onBack: () -> Unit,
) {
    // The workspace a new conversation starts in. Collected rather than passed down: the
    // composer's chip writes the same value, and a page that was handed a snapshot would
    // keep showing the row's old answer until the page was rebuilt.
    val saved by session.settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosingWorkspace by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val workspaces = rememberWorkspaceList(
        context = context,
        env = session.env,
        savedDefault = saved.defaultWorkspace,
    )
    val defaultWorkspacePath = saved.defaultWorkspace.ifBlank { session.env.workspacePath }
    val defaultWorkspace = workspaces.firstOrNull { it.path == defaultWorkspacePath }
    val defaultWorkspaceName = defaultWorkspace?.label
        ?: java.io.File(defaultWorkspacePath).name.ifBlank { defaultWorkspacePath }
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
    // Which of the two documents the sheet is editing, or null for none. `null` is also
    // what the sheet keeps drawing with while it animates out — a Miuix window is driven
    // by `show`, and a value that vanished with the flag would return to the other
    // document's text on the way out.
    var editingDocument by remember { mutableStateOf<InstructionsDocument?>(null) }
    var lastDocument by remember { mutableStateOf(InstructionsDocument.Author) }
    if (editingDocument != null) lastDocument = editingDocument!!
    // Read once per visit to the page, for the user row's "still empty" subtitle.
    val userInstructions = remember(session) {
        runCatching { AgentContext.userFile(session.env).readText() }.getOrDefault("")
    }
    val text = strings
    val toasts = LocalPiToast.current
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

    val pageScroll = rememberScrollState()
    PiPage(
        title = text.settings.agentConfig,
        subtitle = agentDescription(agent, text),
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
        // The page's windows, in the slot that keeps them glass. Written after the
        // `PiPage` call they would see no backdrop and fall back to the opaque Miuix
        // window — the contract `PiPage.overlay` states.
        overlay = {
            PiSheet(
                show = showContext,
                title = text.settings.agentContextLeadTitle,
                onDismiss = { showContext = false },
            ) {
                AgentContextSheet(text)
            }

            PiSheet(
                show = editingDocument != null,
                title = if (lastDocument == InstructionsDocument.Author) {
                    text.settings.agentContextAuthorTitle
                } else {
                    text.settings.agentContextUserTitle
                },
                onDismiss = { editingDocument = null },
            ) {
                InstructionsEditor(document = lastDocument, session = session, text = text)
            }

            WorkspaceDialog(
                show = choosingWorkspace,
                text = text,
                workspaces = workspaces,
                home = session.env.homePath,
                defaultPath = defaultWorkspacePath,
                // One window at a time: the picker is opened *after* the question closes, and
                // its answer comes back through the same row when the folder is added.
                onAdd = {
                    choosingWorkspace = false
                    showWorkspacePicker = true
                },
                onRemove = { path -> WorkspaceStore.remove(context, session.env, path) },
                onPick = { path ->
                    choosingWorkspace = false
                    session.settingsStore.update { it.copy(defaultWorkspace = path) }
                },
                onDismiss = { choosingWorkspace = false },
            )

            PiSheet(
                show = showWorkspacePicker,
                title = text.chat.workspacePickerTitle,
                onDismiss = { showWorkspacePicker = false },
            ) {
                WorkspacePickerSheet(
                    text = text,
                    home = session.env.homePath,
                    // The phone's storage: `/sdcard`, when the device has one. A workspace may
                    // live there — it is an ordinary folder once "all files access" is granted,
                    // and the tier's writable root is the workspace, not the page of switches
                    // that used to stand between them (§14).
                    shared = StorageAccess.sharedPath(session.env),
                    existing = rememberWorkspacePaths(context, session.env),
                    granted = StorageAccess.isGranted(),
                    // The intent may not resolve on every device, and a grant that cannot be
                    // asked for is not a crash: the sheet's own sentence already says the
                    // folder is unreadable without it.
                    onGrant = {
                        runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                    },
                    onAdd = { folder ->
                        val added = WorkspaceStore.add(context, session.env, folder)
                        val reason = (added.exceptionOrNull() as? RefusedWorkspace)?.reason
                        val workspace = when {
                            added.getOrNull() != null -> added.getOrNull()
                            // Already in the list: the reader picked the folder they want to be
                            // the default, and it already is a workspace.
                            reason == WorkspaceRefusal.DUPLICATE ->
                                WorkspaceStore.list(context, session.env)
                                    .firstOrNull { it.path == folder }

                            else -> null
                        }
                        if (workspace != null) {
                            showWorkspacePicker = false
                            // A folder added from *this* page is the one the reader wants new
                            // conversations to start in: the row they came from is the default.
                            session.settingsStore.update {
                                it.copy(defaultWorkspace = workspace.path)
                            }
                            null
                        } else {
                            reason?.let { text.chat.workspaceRefusal(it) }
                        }
                    },
                )
            }
        },
    ) { padding ->
        // The page draws through the navigation strip now, so the strip's height is content
        // padding at the end of the scroll: the last row can still be scrolled clear of the
        // gesture handle, which a bottom padding on the viewport would have prevented.
        Column(Modifier.settingsPageScroll(padding, pageScroll)) {
            PiSection(title = text.settings.agentRunSection) {
                AgentStatusRow(
                    agent = agent,
                    restarting = restarting,
                    text = text,
                    onRestart = {
                        restarting = true
                        restartMoved = false
                        session.restartAgent()
                    },
                )
                if (agent is AgentStatus.Failed) {
                    SettingsNote(text = agent.message, danger = true)
                }
                KeepAliveRow(text = text)
            }

            PiSection(title = text.settings.workMode) {
                ArrowPreference(
                    title = text.settings.defaultWorkspace,
                    summary = text.settings.defaultWorkspaceSubtitle(defaultWorkspaceName),
                    onClick = { choosingWorkspace = true },
                )
                // Two rows, because the file pi reads is *composed* from two documents:
                // the app's own prompts — which a newer PiKit replaces — and the reader's,
                // which nothing here ever writes. One row for both would make "may I edit
                // this" and "is my text safe" the same question, and they are opposites.
                ArrowPreference(
                    title = text.settings.agentContextAuthorTitle,
                    summary = text.settings.agentContextAuthorSubtitle,
                    endActions = {
                        SettingsValue(text.settings.agentContextReadOnly)
                    },
                    onClick = { editingDocument = InstructionsDocument.Author },
                )
                ArrowPreference(
                    title = text.settings.agentContextUserTitle,
                    summary = if (userInstructions.isEmpty()) {
                        text.settings.agentContextUserEmpty
                    } else {
                        text.settings.agentContextUserSubtitle
                    },
                    endActions = {
                        SettingsValue(
                            text.settings.agentContextInstructionsEditable,
                            emphasised = true,
                        )
                    },
                    onClick = { editingDocument = InstructionsDocument.User },
                )
                ArrowPreference(
                    title = text.settings.agentContextLeadTitle,
                    summary = text.settings.agentContextSummary,
                    onClick = { showContext = true },
                )
            }

            // The chat half: the reader's personas, their default, and the editor that
            // writes one. A section rather than a page, for the reason the mode split is
            // structural — changing a workspace must not first ask which mode is being
            // edited — and one composable of its own because a preset is a list with an
            // identity, not one row.
            ChatPresetSection(session = session, settings = saved, text = text)
        }
    }
}

/**
 * The status row: one dot for the state, one word for it, and the one control a reader
 * comes here to use.
 *
 * The dot is the only leading mark this page spends. It is not decoration: "running",
 * "starting" and "failed" are three states a word alone takes a moment to read, and the
 * colour answers before the sentence does. The label stays the accessible answer, so the
 * dot is `contentDescription = null`.
 */
@Composable
private fun AgentStatusRow(
    agent: AgentStatus,
    restarting: Boolean,
    text: Strings,
    onRestart: () -> Unit,
) {
    BasicComponent(
        title = text.settings.agentStatus,
        summary = agentDescription(agent, text),
        startAction = { AgentStatusDot(agent) },
        endActions = {
            // One control, and it is the restart: stop-and-start in one tap, which is
            // the only thing a reader comes to this row to do. A stop button beside it
            // was that same act made into a second, destructive-looking one — and a
            // stopped agent is a state nobody asks for.
            if (restarting) {
                SettingsWorking()
            } else {
                SettingsAction(
                    text = text.settings.restartAgent,
                    // `PiAgentSession.restartAgent` and not a coroutine of this page's:
                    // a scope tied to the composition is cancelled when the page leaves,
                    // and cancelled between the stop and the start it leaves a stopped
                    // agent behind. The session's scope outlives the page.
                    onClick = onRestart,
                )
            }
        },
    )
}

@Composable
private fun AgentStatusDot(agent: AgentStatus) {
    val color = when (agent) {
        is AgentStatus.Running -> PiTone.live()
        is AgentStatus.Starting -> MiuixTheme.colorScheme.primary
        is AgentStatus.Stopped -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        is AgentStatus.Failed -> PiTone.error()
    }
    Box(
        Modifier
            .padding(end = 12.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The context sheet: five short rows, one card, in the order the request is built.
 *
 * It used to be five numbered paragraphs of prose, and the numbers made the sheet read
 * as a chapter rather than as a list of the things the model receives. A row's title is
 * the word a reader checks for — "system prompt", "tools", "conversation" — and the body
 * is the one sentence under it. The list is bounded and scrolls: the sheet's own edge is
 * the window's, and the last row has to clear the gesture strip.
 */
@Composable
private fun AgentContextSheet(text: Strings) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = CONTEXT_NOTES_MAX_HEIGHT)
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            .padding(bottom = piSheetBottomInset()),
    ) {
        Text(
            text = text.settings.agentContextLead,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = PiCorner.radius,
        ) {
            ContextRow(text.settings.agentContextSystemTitle, text.settings.agentContextSystemBody)
            ContextRow(
                text.settings.agentContextInstructionsTitle,
                text.settings.agentContextInstructionsBody,
            )
            ContextRow(text.settings.agentContextRuntimeTitle, text.settings.agentContextRuntimeBody)
            ContextRow(text.settings.agentContextToolsTitle, text.settings.agentContextToolsBody)
            ContextRow(text.settings.agentContextHistoryTitle, text.settings.agentContextHistoryBody)
        }
    }
}

/** One part of the request: the heading a reader checks, and the sentence under it. */
@Composable
private fun ContextRow(title: String, body: String) {
    BasicComponent(title = title, summary = body)
}

/** How tall the context sheet's rows may grow before they scroll. */
private val CONTEXT_NOTES_MAX_HEIGHT = 420.dp

/**
 * The battery-optimisation exemption: the one keep-alive lever the OS owns.
 *
 * The state and the ask are [BatteryOptimisation]'s, which the first-launch dialog
 * uses too — this row is the *report* and the way back to it. The exemption is the
 * system's own dialog, which the app cannot draw: the foreground service and the turn
 * wake lock only work if the OS is not allowed to reclaim the process the moment the
 * screen goes off.
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
            // BOTH events, and that is the fix rather than belt and braces. The system
            // dialog is another task's activity: some launchers deliver
            // `STOP` → `START` → `RESUME` on the way back and some deliver
            // `PAUSE` → `RESUME` only, and a grant that landed while the page was
            // stopped is what the row has to re-read. `ON_RESUME` alone was the report
            // "关闭电池优化之后仍然可以用" — the exemption was held and the row kept
            // offering the ask.
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
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
            // Drawn only while the exemption is missing. It used to be drawn always and
            // disabled once held — which is a button that still exists on screen for a
            // decision that has already been made, and the exemption is not something
            // there is any reason to ask for twice.
            if (!exempt) {
                SettingsAction(
                    text = text.settings.keepAliveAsk,
                    onClick = { BatteryOptimisation.request(context) },
                )
            }
        },
    )
}

/**
 * The two halves of the global `AGENTS.md`, over the page.
 *
 * One sheet with two modes. The app's half ([InstructionsDocument.Author]) is a
 * *document to read*: it is PiKit's, rewritten from this build on every launch, and
 * the row that opens it says "read-only". The reader's half is an editor, unchanged in
 * what it promises — nothing in this app ever writes it.
 *
 * ## Its own draft
 *
 * The reader's text is read once when the sheet opens and kept in the composition:
 * the file is theirs and re-reading it under their cursor would delete keystrokes.
 * Nothing else in the app writes it either, so there is no "the file changed
 * underneath" state to handle.
 *
 * ## Why a save restarts the agent
 *
 * pi reads `AGENTS.md` when it starts, so a file written under a running agent is a
 * change the model does not see until the next launch. Saying "restart the agent
 * yourself" would leave the sentence the user just wrote unread, which is worse than
 * a restart they did not ask for: the same trade the storage page already makes.
 * Every live conversation restarts, because the file is global.
 *
 * ## No close button, in the title or in the footer
 *
 * The sheet's title is Miuix's own and has no action slot, and the panel is dismissible
 * three ways that need no control at all: the scrim, a downward drag and back. What is
 * left in the footer is the one button that *changes* the file.
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
/** Which of the two documents that compose `AGENTS.md` a row opens. */
private enum class InstructionsDocument { Author, User }

@Composable
private fun InstructionsEditor(
    document: InstructionsDocument,
    session: PiAgentSession,
    text: Strings,
) {
    val toasts = LocalPiToast.current
    val file = remember(session, document) {
        when (document) {
            InstructionsDocument.Author -> AgentContext.authorFile(session.env)
            InstructionsDocument.User -> AgentContext.userFile(session.env)
        }
    }
    // State-based because the editor is a glass sheet: beta02's `GlassTextField` is
    // the field drawn for that material, and its state overload is the API new fields
    // start from. `saved`/`failed` follow the text through a snapshotFlow rather than
    // an `onValueChange` the state overload does not have.
    val draftState = remember(session, document) {
        TextFieldState(
            runCatching { file.readText() }.getOrElse { AgentContext.default(session.env) },
        )
    }
    val draft = draftState.text.toString()
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(draftState) {
        snapshotFlow { draftState.text }.collect {
            saved = false
            failed = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            // Scrollable, and that is what makes the panel survive a keyboard: a
            // 240-440dp field plus a button against a window that just lost 300dp
            // would be *clipped* — the buttons and the bottom of the document
            // unreachable — so it scrolls instead, and it does not scroll at all while
            // everything fits.
            //
            // This is the instructions sheet's body, so it takes the page's elastic end
            // as well; `piOverScroll` is before the scroll rather than after it for the
            // reason its KDoc gives.
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            .padding(bottom = piSheetBottomInset()),
    ) {
        Text(
            text = file.absolutePath,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )

        if (document == InstructionsDocument.Author) {
            // The app's half is read, never edited: it is PiKit's text and a newer
            // build replaces it. A field here would be a control that silently loses
            // its answer on the next launch.
            SelectionContainer {
                Text(
                    text = draft,
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            GlassTextField(
                state = draftState,
                // Monospace and small, like every document editor in the app and for the
                // same reason: this is a document the model reads, its structure is
                // headings and bullets, and a proportional face hides that.
                textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    // A Markdown document: an autocorrected command in a bullet is a
                    // sentence that tells the agent to run something that does not exist.
                    autoCorrectEnabled = false,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(),
                cornerRadius = PiCorner.radius,
                // No focus ring, and the caret stated instead: see `piFieldColors`.
                colors = GlassTextFieldDefaults.textFieldColors(
                    borderColor = Color.Transparent,
                ),
                cursorBrush = piFieldCursor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT),
            )
        }

        // What the last press did is the app's one transient message rather than a line
        // of prose under the field, which used to stay there until the next keystroke —
        // a sentence about an action that has already finished.
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

        if (document == InstructionsDocument.User) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The button takes the whole row: there is one control here, and a
                    // half-width Save with nothing bidding against it read as a choice.
                    // There is no Cancel either: the scrim, the back gesture and a
                    // downward drag all leave the sheet without saving.
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SettingsAction(
                    text = text.settings.agentContextSave,
                    onClick = {
                        // Written first, reported after: a save that claims to have
                        // happened and did not is the one outcome this button must not
                        // have.
                        val written = runCatching {
                            file.parentFile?.mkdirs()
                            file.writeText(draft)
                            // The composed document is what pi reads, and pi reads it once at
                            // startup: writing a half without recomposing leaves the running
                            // agent — and the next launch — on the previous text.
                            AgentContext.compose(session.env)
                        }.isSuccess
                        failed = !written
                        saved = written
                        // Every live conversation reads AGENTS.md at its own startup, so
                        // every one of them is stale — the host's restart loop is what
                        // reaches them (a turn in flight is waited out per conversation).
                        if (written) session.host.restartAll()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The editor's height bounds.
 *
 * A floor so it reads as a document rather than a field, and a cap so the buttons
 * stay on screen — past the cap the field scrolls itself. The pair was settled by
 * the app's other long-document editor and is kept here so two editors doing the
 * same job do not stand at two heights.
 */
private val EDITOR_MIN_HEIGHT = 240.dp
private val EDITOR_MAX_HEIGHT = 440.dp
