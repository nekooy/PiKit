package pi.kit.mob.pi

import android.content.Context
import android.os.PowerManager
import android.util.Log
import pi.kit.mob.data.AgentMode
import pi.kit.mob.data.ChatPreset
import pi.kit.mob.data.CustomApi
import pi.kit.mob.data.CustomEndpoint
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.ModelSettings
import pi.kit.mob.data.PermissionMode
import pi.kit.mob.data.PiConfigSnapshot
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.ProfileAddressing
import pi.kit.mob.data.SettingsStore
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.data.apiName
import pi.kit.mob.data.baseUrlName
import pi.kit.mob.data.normalizeApiBaseUrl
import pi.kit.mob.data.renderChatSystemPrompt
import pi.kit.mob.data.replaceConfigFile
import pi.kit.mob.env.BootstrapInstaller
import pi.kit.mob.env.BundledExtension
import pi.kit.mob.env.BundledImage
import pi.kit.mob.env.PackageManifest
import pi.kit.mob.env.PermissionPolicy
import pi.kit.mob.env.PrefixPatcher
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.locales.DiscoveryProblem
import pi.kit.mob.pi.NoticeKind
import pi.kit.mob.terminal.TerminalSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Progress of unpacking the bundled Termux environment. */
sealed interface RuntimeStatus {
    /**
     * A runtime is present and is being revalidated.
     *
     * The pages can be drawn while this runs: only an unpack that actually has work to
     * do holds the first frame behind [Installing]. Before this state existed every
     * launch began at [NotInstalled] and drew the setup screen until the five-path
     * probe and the storage farm had finished, even when the image was already current.
     */
    data object Checking : RuntimeStatus
    data object NotInstalled : RuntimeStatus
    data class Installing(val fraction: Float, val message: String) : RuntimeStatus
    data class Ready(val revision: String, val warnings: List<String> = emptyList()) : RuntimeStatus
    data class Failed(val message: String) : RuntimeStatus
}

/** Lifecycle of the `pi --mode rpc` child process. */
sealed interface AgentStatus {
    data object Stopped : AgentStatus
    data object Starting : AgentStatus
    data object Running : AgentStatus
    data class Failed(val message: String) : AgentStatus
}

/**
 * The failure a launch's throwable describes, or null when it is not a failure at all.
 *
 * A `CancellationException` is the coroutine machinery telling a launch that its caller
 * has gone away — most often Compose's `ForgottenCoroutineScopeException`
 * ("rememberCoroutineScope left the composition") after the page that asked was swiped
 * out while the environment was still being prepared. Turning that into
 * `AgentStatus.Failed` put the sentence in the composer's notice as though pi had
 * refused to start. The launch was abandoned, not refused; [PiAgentSession.startAgent]
 * releases what it spawned and leaves the conversation stopped so the next visit can
 * try again.
 */
internal fun agentStartFailure(t: Throwable): AgentStatus.Failed? =
    if (t is CancellationException) null else AgentStatus.Failed(t.message ?: t.toString())

/**
 * A session command was refused because the agent is mid-turn.
 *
 * ## Why this exists
 *
 * pi's `switch_session` and `new_session` both go through
 * `RuntimeHost.teardownCurrent`, whose first statement is `await
 * this.session.abort()` — verified against the bundled bundle, not inferred. There
 * is no way to move to another session and leave the running turn alone.
 *
 * ## What the app does about it
 *
 * It used to send the command anyway, and that is the first report: "switching
 * sessions while the agent is answering stops the answer". Then it refused and said
 * so — and that is the second: the tap did nothing the user could act on, and the
 * sentence explaining why was a notice rather than a question.
 *
 * So a *move the user asked for* now asks first, with `InterruptTurnDialog`, and goes
 * through when they confirm ([PiAgentSession.switchSession] / [newSession] with
 * `interrupt = true`). This exception is what is left: the cases where there is no
 * question to ask because there is no way to proceed — deleting the session pi is
 * writing to, which has to start a new one and therefore cannot be half-done. That
 * one is still a refusal, and the page still says so.
 */
class TurnInFlightException :
    IllegalStateException("the agent is still working on this session")

/**
 * What the last parse of each session file found, shared by every conversation.
 *
 * The conversation drawer re-reads a transcript from its first line to its last to count its
 * messages, and a session is megabytes of JSONL with four regexes run per line. It used
 * to be a field of [PiAgentSession] — one cache per conversation — which was invisible
 * while the app had a single one and became a cost the moment a tap could open another:
 * every switch built a session with an empty cache, so the drawer re-parsed every session
 * file the user had ever had, and drew its spinner while it did. The directory is the
 * environment's and is the same for all of them, so the cache is too.
 *
 * The key is the path, so a rename or a delete cannot leave a stale entry behind, and the
 * stamp is (mtime, length) — the same pair [SessionSearchIndex] uses, and for the same
 * reason: a file written twice inside the filesystem's timestamp granularity keeps its
 * modification time.
 *
 * [last] is the most recent listing rather than a cache of it: it is what lets a drawer
 * that is composed before the next scan has answered draw the rows it had a moment ago
 * instead of an empty panel with a spinner. It is replaced by every completed scan.
 */
internal object SessionListingCache {
    internal class CachedMeta(val modifiedAt: Long, val length: Long, val meta: SessionMeta)

    /**
     * Concurrent because the listing runs on the IO dispatcher and two conversations can
     * scan at once — one opened from the drawer while the last one is still loading its
     * transcript.
     */
    val meta = ConcurrentHashMap<String, CachedMeta>()

    /** The most recent complete listing, in display order. */
    @Volatile
    var last: List<PiAgentSession.SessionSummary> = emptyList()

    /** A restore rewrites mtimes, so neither half of the cache survives it. */
    fun clear() {
        meta.clear()
        last = emptyList()
    }
}

/**
 * One conversation: the agent process that answers it, and the state its transcript is
 * drawn from.
 *
 * ## Why there can be more than one
 *
 * The app used to own a single `pi --mode rpc` child, and a move to another conversation
 * was a `switch_session` on that child — which pi implements by aborting the running
 * turn first (`RuntimeHost.teardownCurrent`), so a tap on another conversation stopped
 * the answer. A conversation is therefore a *process* now, and this class is what one of
 * them is: its child, its transcript, its workspace, its model. [PiHost] owns what all
 * of them share — the unpacked environment, the settings, the terminal, the pool — and
 * decides which one the screen is on.
 *
 * What is deliberately still single: the environment. Two conversations do not unpack
 * the image twice, write `models.json` twice at once, or run two catalogue refreshes;
 * [PiHost] holds the lock for each of those, and the parts of this class that need them
 * read them through [host].
 */
class PiAgentSession internal constructor(
    internal val host: PiHost,
    /**
     * The saved conversation this one was opened for, when it was opened from the
     * drawer; null for a new one. The launch rebinds the fresh child to that file
     * after the handshake, because pi has no resume flag — every launch opens a new
     * session file.
     */
    private val boundSession: String? = null,
) {

    internal val appContext: Context get() = host.appContext
    val env: TermuxEnv get() = host.env
    val settingsStore: SettingsStore get() = host.settingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The environment's install state. The image is unpacked once, for every conversation. */
    val runtime: StateFlow<RuntimeStatus> get() = host.runtime

    private val _agent = MutableStateFlow<AgentStatus>(AgentStatus.Stopped)
    val agent: StateFlow<AgentStatus> = _agent.asStateFlow()

    /**
     * This conversation's launch mode.
     *
     * A conversation opened from history takes the mode recorded for its session file;
     * a conversation with no file yet takes the app's default, which is the mode the
     * last switch selected. It is a state of the conversation rather than of the app
     * because two conversations with different histories can be open at once: opening a
     * work conversation while chat mode was the last choice must answer work.
     */
    private val _mode = MutableStateFlow(
        boundSession?.let { host.sessionModes.modeFor(it) } ?: settingsStore.read().agentMode,
    )
    val mode: StateFlow<AgentMode> = _mode.asStateFlow()

    /**
     * Which chat-mode preset this conversation speaks with.
     *
     * A conversation opened from history takes the preset recorded for its session file;
     * one with no file yet takes the app's default preset. Chat mode only — work mode
     * reads no prompt, so the state is inert there and the composer does not draw the
     * chip. Changing it is a relaunch, because pi reads the prompt once at process start.
     */
    private val _chatPresetId = MutableStateFlow(
        boundSession?.let { host.chatPresets.selectedId(it) } ?: host.chatPresets.defaultId,
    )
    val chatPresetId: StateFlow<String> = _chatPresetId.asStateFlow()

    /** Every preset, for the composer's chip and the settings page. */
    val chatPresets: StateFlow<List<ChatPreset>> get() = host.chatPresets.presets

    /**
     * Whether this chat conversation may use the bundled extension's network tools.
     *
     * A conversation opened from history takes its own record; one with no file yet
     * takes the app's [ChatWebAccessDefault], which is Off unless the reader chose
     * otherwise. It is a launch flag rather than a runtime switch: the tools are
     * registered when pi starts, so flipping it restarts this conversation the way a
     * preset change does. `SessionModeStore` records it beside the mode.
     */
    private val _chatWebAccess = MutableStateFlow(
        boundSession?.let { host.sessionModes.webAccessFor(it) }
            ?: settingsStore.read().let { settings ->
                settings.chatWebAccessDefault.initialValue(settings.lastChatWebAccess)
            },
    )
    val chatWebAccess: StateFlow<Boolean> = _chatWebAccess.asStateFlow()

    /** The preset in force, resolved: a deleted id falls back to the default. */
    fun chatPreset(): ChatPreset = host.chatPresets.preset(_chatPresetId.value)

    private val _conversation = MutableStateFlow(ConversationState())
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()

    /**
     * The thinking levels each catalogued model declares, keyed by model id.
     *
     * The file it is read from is pi's store, which every conversation reads at its own
     * launch and one catalogue refresh writes, so the map is the [host]'s: two
     * conversations cannot disagree about what a model offers. See
     * [PiHost.catalogueThinkingLevels] for what it is used for.
     */
    val catalogueThinkingLevels: StateFlow<Map<String, List<String>>>
        get() = host.catalogueThinkingLevels

    /**
     * The live client and the job draining it.
     *
     * `@Volatile` because they are written and read from different threads and neither side
     * is a suspension point: `startAgentLocked` publishes a new client from whichever thread
     * called it, `stopAgent`/`detach` publish the null, and the `runCommand` coroutines and
     * [attach]'s collector read the field from the session's own scope. Without the fence a
     * reader can see the previous client — a `sendPrompt` writing into a stream that was
     * just closed, which surfaces as "pi exited" and an automatic restart rather than as the
     * lost write it is.
     */
    @Volatile
    private var client: PiRpcClient? = null

    @Volatile
    private var recordJob: Job? = null

    @Volatile
    private var launchedRoutes: ProfileRoutes? = null

    @Volatile
    private var confirmedRoute: ProfileRoute? = null

    @Volatile
    private var conversationProfile: Pair<String, String>? = null

    /**
     * True between a deliberate [stopAgent] and the next [startAgent].
     *
     * An unexpected exit schedules one automatic restart; a stop the user asked
     * for must not be undone by it. Cleared at the top of every start.
     */
    @Volatile
    private var stopRequested = false

    private var autoRestartJob: Job? = null

    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var turnWakeLock: PowerManager.WakeLock? = null

    /**
     * Whether the next restore is this conversation's first launch.
     *
     * Only the host's *first* conversation may reopen the conversation remembered from
     * the last run, and only on its first launch: "cold start" is the launch of the app,
     * and a later `restartAgent` — a crash, a settings change — is the same conversation
     * continuing under a new pi process. [PiHost] sets this on the conversation it
     * creates at startup and on no other.
     */
    internal var restoresRememberedSession = false

    /**
     * Set once this conversation has been launched at least once, so a restart rebinds
     * to the session file this process opened rather than to a remembered one.
     */
    private var coldStartRestore = java.util.concurrent.atomic.AtomicBoolean(true)

    /**
     * The session file this conversation is bound to, when it was opened from the
     * drawer. Cleared once the child has been rebound, because from then on the
     * transcript's own `sessionFile` is the better answer.
     */
    @Volatile
    private var restoreTarget: String? = boundSession

    /**
     * The fresh session file whose opening prompt may still name it, or null.
     *
     * Set only for a session this conversation started itself — a fresh launch, a
     * `new_session`, or the new child `ensureModelForPrompt` has to spawn because the
     * old one is gone. [openFromDisk] and [switchSessionTo] clear it: an existing
     * conversation is never renamed by the title rule, even when it has no name.
     *
     * The path is kept separately from [autoTitleCandidate] because pi names the file
     * in its handshake before the file exists, and the first user message is what
     * creates it. Waiting for `File.exists()` at handshake time is therefore a race:
     * on a device where pi has already flushed an empty header the file exists, and
     * the title was never attempted. The flag is what makes the decision, not the
     * filesystem.
     */
    private val autoTitlePath = AtomicReference<String?>(null)
    @Volatile
    private var autoTitleCandidate = false

    /** When the screen was last on this conversation, for [PiHost.makeRoom]'s eviction. */
    @Volatile
    internal var lastUsedAt: Long = System.currentTimeMillis()

    /**
     * Serialises [startAgent], so the "already running" guard cannot be read by two
     * callers before either of them has written `Starting`. See that function.
     */
    private val modelCommandLock = Mutex()

    /** Where pi writes a conversation; the path lives in [TermuxEnv] with the rest. */
    val sessionDir: File get() = env.sessionDir

    /** The terminal's PTYs. Shared: a shell is not a conversation, it is the terminal's. */
    val terminalSessions: TerminalSessionManager get() = host.terminalSessions

    /**
     * The conversation this process is in, as pi names it: the session file it writes.
     *
     * Null until the handshake has answered — a conversation nothing has been sent to
     * has no file yet — which is also why the drawer cannot offer a row for one.
     */
    val sessionPath: String?
        get() = _conversation.value.sessionFile ?: restoreTarget

    /**
     * The session file this conversation's transcript was read from, when it was read from
     * disk rather than from a live agent's `get_messages`.
     */
    private var restoredFrom: String? = null

    /**
     * Fills the transcript from a saved conversation on disk, without starting anything.
     *
     * The message list is the same envelope `get_messages` answers with, so the fold is
     * [ConversationReducer.replaceWithMessages]'s and the page cannot tell the two apart
     * except that this one is instant.
     *
     * @return true when the file was read and had at least one message.
     */
    fun openFromDisk(path: String): Boolean {
        val file = File(path)
        val payload = SessionTranscript.payload(file) ?: return false
        autoTitlePath.set(null)
        autoTitleCandidate = false
        // Use the stored name in both the drawer and the header, without naming history.
        val base = ConversationState(
            sessionFile = path,
            sessionName = payload.name,
        )
        _conversation.value = ConversationReducer
            .replaceWithMessages(base, payload.messages)
            .copy(loadingSession = false)
        restoreTarget = path
        restoredFrom = path
        // A row's mode is its own: this is the file's record even when the app's
        // default has moved on since the conversation was last opened. The chat preset
        // and the network-tools switch are the same kind of record, read from the same
        // place so a restored chat conversation keeps its voice and its tools.
        _mode.value = host.sessionModes.modeFor(path)
        _chatPresetId.value = host.chatPresets.selectedId(path)
        _chatWebAccess.value = host.sessionModes.webAccessFor(path)
        return true
    }

    /** Whether [path] is the session file this conversation is (or is being) bound to. */
    internal fun matchesPath(path: String): Boolean =
        path == _conversation.value.sessionFile || path == restoreTarget

    // ----------------------------------------------------------- environment

    /**
     * Unpacks the bundled environment if needed, once for the whole app.
     *
     * The unpack is the host's: two conversations starting together on a cold launch
     * would otherwise both see `NotInstalled` and unpack the same ~285 MB over the top
     * of each other. See [PiHost.ensureEnvironment].
     */
    suspend fun ensureEnvironment(): Boolean = host.ensureEnvironment()

    /** The shell's welcome banner follows the interface language; one file, one writer. */
    suspend fun refreshTerminalBanner() = host.refreshTerminalBanner()

    /**
     * Brings `~/storage` in line with the Android grant.
     *
     * Called from `termux-setup-storage`'s broadcast; the farm itself is one directory
     * in `$HOME`, so the work is the host's.
     */
    fun syncStorageLinks(): Boolean = host.syncStorageLinks()

    /**
     * The setup a fresh shell does by hand, run the first time the terminal page opens:
     * the storage farm re-reconciled, and one silent package-mirror check. See
     * [PiHost.ensureTerminalSetup].
     */
    fun ensureTerminalSetup() = host.ensureTerminalSetup()

    // ------------------------------------------------------------- workspace

    /**
     * The directory the agent works in — the workspace of the conversation on screen.
     *
     * Empty until the first launch resolves it, which is also when the app's default
     * workspace is read: this is one process with one working directory, so a
     * conversation that lived somewhere else is reopened by relaunching the agent there
     * rather than by switching a pi that is already standing in the old tree. See
     * [selectWorkspace].
     */
    private val _workspace = MutableStateFlow("")

    /** The workspace in force, as an absolute path. Read by the composer's chip. */
    val workspace: StateFlow<String> = _workspace.asStateFlow()

    /**
     * The workspace the next launch uses, resolving the saved default the first time.
     *
     * A remembered path that has since been deleted resolves to the built-in workspace
     * through [WorkspaceStore.default] rather than launching pi in a directory that is
     * not there — pi would exit with a code the banner cannot explain.
     */
    private fun launchWorkspace(): String {
        val current = _workspace.value
        if (current.isNotBlank() && File(current).isDirectory) return current
        val resolved = restoredWorkspace() ?: WorkspaceStore.default(appContext, env).path
        _workspace.value = resolved
        return resolved
    }

    /**
     * The workspace the conversation being restored belongs to, or null.
     *
     * Only when the app is set to come back to the last conversation: a launch that opens a
     * *new* one starts in the default workspace, which is what that setting means. When it
     * does restore, the directory is the one the session file records — the same rule
     * [PiHost.openConversation] applies to a row the reader taps, so a cold start and a reopen cannot
     * disagree about where a conversation lives.
     */
    private fun restoredWorkspace(): String? {
        if (settingsStore.read().openNewOnLaunch) return null
        val path = restoreTarget ?: host.lastRememberedSession() ?: return null
        val workspace = SessionMetadata.workspaceOf(File(path)) ?: return null
        return workspace.takeIf { File(it).isDirectory }
    }

    /**
     * Opens this conversation in [path] before it has been launched.
     *
     * The host calls this when it opens a saved conversation from the drawer: the
     * session file records the directory pi ran in, and a child launched anywhere else
     * would show that transcript while every tool call acted in another tree. Distinct
     * from [selectWorkspace], which is the reader's own move and relaunches a running
     * process.
     */
    internal fun prepareWorkspace(path: String) {
        _workspace.value = path
        restoreTarget = restoreTarget ?: boundSession
    }

    /**
     * Records that the screen is on this conversation, for a cold start to reopen.
     *
     * Only the active conversation writes it: a background conversation finishing a turn
     * must not decide what the next launch restores.
     */
    internal fun rememberAsLastConversation() {
        lastUsedAt = System.currentTimeMillis()
        host.rememberLastSession(sessionPath)
    }

    /**
     * Switches to [path] for a *new* conversation: the agent is relaunched there.
     *
     * A relaunch, not a `cd`: the workspace is the child process's working directory and
     * the base every relative path in a tool call resolves against, and pi reads it once
     * at startup. The remembered session is cleared before the restart so the new process
     * does not reopen the conversation that belongs to the workspace being left — see
     * [restoreAfterLaunch], which would otherwise do exactly that.
     *
     * @param interrupt false refuses the switch while an answer is streaming, which is
     *   the same rule [newSession] follows and the same dialog asks about it.
     */
    fun selectWorkspace(path: String, interrupt: Boolean = false) {
        val wanted = path.trim()
        if (wanted.isBlank() || wanted == _workspace.value) return
        if (turnInFlight && !interrupt) return
        _workspace.value = wanted
        // This conversation is leaving the one it was in: the fresh child has to open an
        // empty session there rather than rebind to the file of the workspace it left.
        restoreTarget = null
        if (host.active.value === this) host.rememberLastSession(null)
        // The page must stop claiming the old conversation while the process is being
        // replaced: the relaunch's handshake opens a fresh session, and the old turns
        // were only ever the old process's.
        _conversation.value = ConversationState()
        if (_agent.value is AgentStatus.Running) {
            restartAgent()
        } else {
            scope.launch { startAgent() }
        }
    }

    /**
     * Switches this conversation between the work and chat launch modes.
     *
     * The mode belongs to the conversation, not to the app, so this edits *this*
     * transcript's status rather than opening a second conversation beside it. The
     * process reads its command line once at startup, so a live child is restarted in
     * place; [restoreTarget] still names this conversation's session file, and
     * [restoreAfterLaunch] rebinds the fresh child to it, which is what keeps the
     * transcript and the model's history unchanged across the switch. A conversation
     * opened from disk but not yet started only records the mode: its first prompt
     * launches with it.
     *
     * @param interrupt false refuses the switch while an answer is streaming, which is
     *   the same rule [selectWorkspace] and [newSession] follow; the caller asks first.
     */
    fun switchMode(mode: AgentMode, interrupt: Boolean = false) {
        if (mode == _mode.value) return
        if (turnInFlight && !interrupt) return
        _mode.value = mode
        // The path can be known before the process is: a history row, or a conversation
        // whose first launch is still starting. Record both spellings so the mapping is
        // there whether the file came from the drawer or from the handshake.
        sessionPath?.let { host.sessionModes.setMode(it, mode) }
        restoreTarget?.let { host.sessionModes.setMode(it, mode) }
        // The next *new* conversation starts in the mode just chosen. This is only a
        // default; the conversation on screen is already answered by [_mode].
        settingsStore.update { it.copy(agentMode = mode) }
        if (mode == AgentMode.CHAT) rememberChatWebAccess()
        when (_agent.value) {
            is AgentStatus.Stopped -> Unit
            is AgentStatus.Starting, is AgentStatus.Running, is AgentStatus.Failed ->
                restartAgent()
        }
    }

    /**
     * Changes the permission tier.
     *
     * No restart, and that is the point of the tier being a file rather than an
     * environment variable: `tools/pikit-permissions.ts` reads it on every `tool_call`,
     * so the next thing the agent does is judged by the tier the reader just picked —
     * mid-conversation, with the turn in flight if there is one. See [PermissionMode].
     */
    fun setPermissionMode(mode: PermissionMode) {
        settingsStore.update { it.copy(permissionMode = mode) }
        PermissionPolicy.write(env, mode)
    }

    /**
     * Chooses the chat preset this conversation speaks with.
     *
     * Recorded against the session file and applied by a relaunch, for the same reason
     * [switchMode] is: the prompt is read once, at process start. A conversation that has
     * not started only records it — its first prompt launches with it — and one that is
     * answering is *not* interrupted here: [scheduleRestart] waits for the turn to settle,
     * which is the behaviour the prompt editor already had.
     */
    fun selectChatPreset(id: String) {
        if (id == _chatPresetId.value) return
        _chatPresetId.value = id
        sessionPath?.let { host.chatPresets.setSelection(it, id) }
        restoreTarget?.let { host.chatPresets.setSelection(it, id) }
        if (_mode.value == AgentMode.CHAT) {
            when (_agent.value) {
                is AgentStatus.Stopped -> Unit
                is AgentStatus.Starting, is AgentStatus.Running, is AgentStatus.Failed ->
                    scheduleRestart()
            }
        }
    }

    /**
     * Turns this chat conversation's network tools on or off.
     *
     * A launch flag, so the answer takes effect on the next process; [scheduleRestart]
     * defers it rather than cutting an answer. The value is recorded per session exactly
     * as the mode is, and is inert in work mode, where the tools come with the agent.
     */
    fun setChatWebAccess(enabled: Boolean) {
        if (enabled == _chatWebAccess.value) return
        _chatWebAccess.value = enabled
        sessionPath?.let { host.sessionModes.setWebAccess(it, enabled) }
        restoreTarget?.let { host.sessionModes.setWebAccess(it, enabled) }
        if (_mode.value == AgentMode.CHAT) {
            rememberChatWebAccess()
            when (_agent.value) {
                is AgentStatus.Stopped -> Unit
                is AgentStatus.Starting, is AgentStatus.Running, is AgentStatus.Failed ->
                    scheduleRestart()
            }
        }
    }

    /**
     * Records this chat conversation's switch as the app's last one.
     *
     * [ChatWebAccessDefault.LAST] reads this memory when a conversation with no file
     * starts. It is written when a chat conversation is activated and whenever its
     * switch moves, so "the last setting" means the state the reader last saw — not
     * the last flip alone. Work conversations never write it: their tools come with
     * the agent and the switch is not drawn.
     */
    internal fun rememberChatWebAccess() {
        val value = _chatWebAccess.value
        if (settingsStore.read().lastChatWebAccess != value) {
            settingsStore.update { it.copy(lastChatWebAccess = value) }
        }
    }

    // ---------------------------------------------------------------- agent

    /**
     * Starts the agent if it is not already running.
     *
     * ## Why this is a lock around the whole start
     *
     * "Not already running" used to be a single read of [agent] at the top of the
     * method, and the first thing after it is `ensureEnvironment()` — which suspends.
     * Everything that starts the agent runs on the main thread (the root's
     * `LaunchedEffect`, and `PiAgentService.onStartCommand`'s `lifecycleScope`), and
     * two of them arrive together on every launch: `MainActivity` starts the service
     * when there is a frame to start it from, and `RootContent` starts the agent from
     * its own first composition. On a cold start the environment is still unpacking,
     * so the second caller passes a guard the first has not yet had a chance to
     * change, waits on the same environment lock, and then launches a **second** pi
     * process over the top of the first: `client` is replaced, the first record job is
     * cancelled, and the first process is never closed. From the reader's side that is
     * the status line going "starting → ready → starting → ready" a few seconds after
     * the app is opened.
     *
     * The mutex is what makes the guard mean what it says: the second caller waits
     * here instead of at the environment lock, re-reads the status inside the lock, and
     * returns the running agent rather than starting another. It is not the same lock
     * as the host's environment lock and must not be: `ensureEnvironment()` takes that one, and
     * nesting them in the other order somewhere else would be the deadlock.
     */
    suspend fun startAgent(): Boolean = modelCommandLock.withLock { startAgentSerialized() }

    private suspend fun startAgentSerialized(): Boolean {
        // Whether *this* call got past the guard and owns the `Starting` it may have
        // written. A caller that is cancelled while waiting for the mutex must not
        // clear the state of the start it was waiting behind.
        var claimed = false
        return try {
            claimed = _agent.value !is AgentStatus.Running &&
                _agent.value !is AgentStatus.Starting
            startAgentLocked()
        } catch (c: CancellationException) {
            // The caller is often a page's own coroutine, and a swipe disposes that
            // page while the launch is still unpacking the environment or waiting for
            // the handshake. The inner catch has already released anything it spawned;
            // what is left here is the state — `Starting` would otherwise outlive the
            // abandoned attempt and keep every later start from running.
            if (claimed && _agent.value is AgentStatus.Starting) {
                _agent.value = AgentStatus.Stopped
            }
            throw c
        }
    }

    private suspend fun startAgentLocked(): Boolean {
        if (_agent.value is AgentStatus.Running || _agent.value is AgentStatus.Starting) return true
        stopRequested = false
        if (!ensureEnvironment()) return false

        // The catalogue's thinking levels, before the process that would answer them:
        // the composer's picker reads this while pi is still starting, and a model
        // switch must not wait for a handshake to know what the new model can do.
        host.scope.launch { host.reloadCatalogueThinkingLevels() }

        val cliEntry = PiInstallation.cliEntry(env)
        if (cliEntry == null) {
            _agent.value = AgentStatus.Failed(
                "The pi CLI is not present in the bundled environment at " +
                    PiInstallation.CLI_ENTRY_RELATIVE,
            )
            return false
        }

        _agent.value = AgentStatus.Starting
        val workingDir = File(launchWorkspace())
        val snapshot = settingsStore.profiles.snapshots.value
        var launched: Process? = null
        return try {
            val active = selectedProfile(snapshot)
            val settings = settingsStore.read().withProfile(active)
            val (newClient, sessionState) = host.launchFileLock.withLock {
                withContext(Dispatchers.IO) {
                    val routes = profileRoutes(snapshot)
                    val wanted = routes.route(active)
                    if (active?.modelId?.isNotBlank() == true && wanted == null) {
                        host.scope.launch { host.refreshCatalogueIfStale() }
                        throw modelRouteError()
                    }
                    writeModelsJson(snapshot, routes.entries)
                    writePiDefaults(settingsStore.read().withProfile(snapshot.activeProfile), routes.route(snapshot.activeProfile)?.provider)
                    webSearch.ensureDocument()
                    workingDir.mkdirs()
                    PermissionPolicy.write(env, settings.permissionMode)
                    sessionDir.mkdirs()
                    launchedRoutes = routes
                    // Written under the launch lock immediately before the child that
                    // reads it, so another launch cannot replace the file between the
                    // write and the spawn. Work mode leaves it null.
                    val chatPromptFile = if (_mode.value == AgentMode.CHAT) {
                        writeChatPrompt().absolutePath
                    } else {
                        null
                    }
                    val chatPromptExtension = if (_mode.value == AgentMode.CHAT) {
                        File(env.prefix, CHAT_PROMPT_EXTENSION_SOURCE)
                            .takeIf { it.isFile }
                            ?.absolutePath
                            .also { found ->
                                if (found == null) {
                                    Log.w(
                                        TAG,
                                        "chat prompt extension is missing from the runtime; " +
                                            "chat mode's prompt will carry pi's cwd block",
                                    )
                                }
                            }
                    } else {
                        null
                    }
                    // The network tools a chat conversation may use, when its own switch
                    // is on. Loaded by an explicit `--extension` beside `--no-extensions`
                    // and narrowed to two tool names by `--tools` — see
                    // `PiLaunchOptions.chatWebExtension` for what the two flags do together.
                    // A runtime image without the extension is logged and dropped rather
                    // than refused: the conversation still starts, with no tools, which is
                    // the switch's own "off" state.
                    val chatWebExtension = if (_mode.value == AgentMode.CHAT && _chatWebAccess.value) {
                        env.webExtension?.entryPoint
                            ?.takeIf { it.isFile }
                            ?.absolutePath
                            .also { found ->
                                if (found == null) {
                                    Log.w(
                                        TAG,
                                        "chat mode's web access is on but the bundled " +
                                            "web extension is missing from the runtime",
                                    )
                                }
                            }
                    } else {
                        null
                    }
                    val process = PiProcessLauncher.launch(
                        context = appContext,
                        env = env,
                        options = settings.toLaunchOptions(
                            nodePath = env.node.absolutePath,
                            cliEntry = cliEntry.absolutePath,
                            workingDir = workingDir.absolutePath,
                            sessionDir = sessionDir.absolutePath,
                            providerId = wanted?.provider,
                            credentials = ProfileAddressing.providerEnvironment(snapshot) +
                                ("PIKIT_WEB_CONFIG" to File(env.home, WebSearchSettings.FILE_NAME).absolutePath),
                            chatPromptFile = chatPromptFile,
                            chatPromptExtension = chatPromptExtension,
                            chatWebExtension = chatWebExtension,
                            mode = _mode.value,
                        ),
                    ).also { launched = it }
                    val live = PiRpcClient.from(process).also { it.start() }
                    client = live
                    attach(live)
                    val state = live.handshake()
                    if (!state.success || (wanted != null && !wanted.matches(state.reportedModel()))) throw modelRouteError()
                    confirmedRoute = wanted
                    live to state
                }
            }
            logSessionState("handshake", sessionState)
            // Which session the launch binds to is decided *before* the handshake is
            // folded in, because the answer changes what the fold may write. Every
            // `pi --mode rpc` launch opens a new session file, so the handshake names a
            // file the reader has never seen even when this process was started for a
            // saved conversation; [restoreAfterLaunch] switches pi back to that file a
            // moment later. Folding the fresh name in the meantime made the drawer lose
            // the row it marks as current for the length of the switch — the
            // "选中的灰色背景消失一下然后出现" report, measured on the emulator as one
            // frame in six at 175 ms a frame on a quick swipe back into the list.
            val opened = (sessionState.data as? JsonObject)?.str("sessionFile")
            val restoreTargetPath = restoreTargetFor(opened)
            val shown = _conversation.value.sessionFile
            _conversation.update { state ->
                val reduced = ConversationReducer.reduce(state, sessionState)
                reduced.copy(
                    sessionFile = sessionFileAfterHandshake(
                        shown = shown,
                        reported = reduced.sessionFile,
                        restoring = restoreTargetPath != null,
                    ),
                )
            }
            // Every `pi --mode rpc` launch opens a *new* session file. Without
            // this the UI kept the previous transcript on screen while every
            // prompt after a restart landed in that fresh file — the report of
            // "同一界面，但是实际上已经新开了对话". See [restoreAfterLaunch].
            restoreAfterLaunch(opened, restoreTargetPath)
            if (client !== newClient) throw modelRouteError()
            if (restoreTargetPath != null && active != null && active.modelId.isNotBlank()) {
                confirmModel(newClient, active)
            }
            if (active != null && active.modelId.isNotBlank()) conversationProfile = active.id to active.modelId
            _agent.value = AgentStatus.Running
            // The foreground service is what keeps this process alive while the
            // UI is backgrounded. `MainActivity` starts one on every launch, but
            // that is not the only way a start happens: the Agent page's
            // Restart, `scheduleRestart` after a settings edit, the one
            // automatic recovery after a crash, and a prompt into a dead
            // connection all land here, and the service is gone after a
            // deliberate stop or after a first start that failed and tore it
            // down. Without this the turn that most needed keep-alive was the
            // one running with none. Idempotent: a service already supervising
            // just re-syncs its notification.
            //
            // `runCatching` because Android 8+ refuses `startService` from the
            // background and 12+ restricts `startForegroundService` there: a
            // start that happens to land after the user has left the app must
            // not fail the agent launch over a keep-alive nicety. The child is
            // already up at this point; the service is only the shade entry and
            // the reclaim protection.
            runCatching { PiAgentService.start(appContext) }
                .onFailure { Log.w(TAG, "Could not bring the agent service up", it) }
            // The settings page's own model list is one tap away and the thinking
            // picker needs the level list pi derives from the model, so it is loaded
            // as soon as the process can answer. `get_available_models` used to be
            // asked here as well, on every launch, for the "does pi's catalogue know
            // this id" test that decided between an override and a definition — the
            // whole of that machinery is gone (see [modelsJsonWith]), and with it a
            // round trip whose reply is every model of every configured provider,
            // costs, windows and thinking maps included.
            refreshThinkingLevels()
            // The same per-model memory is consulted when pi resolves the *launch*
            // model, and it outranks `--thinking` there, so a relaunch would otherwise
            // come back at a level recorded for this model earlier. State the reader's
            // choice once, now that the process can hear it.
            _conversation.value.model?.id?.let { model ->
                stateThinkingLevel(newClient, model, _conversation.value.thinkingLevel)
            }
            // Off the launch path, and nothing waits on it: this is a network round
            // trip and the launch is a tap. See [PiHost.refreshCatalogueIfStale].
            host.scope.launch { host.refreshCatalogueIfStale() }
            true
        } catch (e: PiProcessExitedException) {
            // pi writes nothing to stdout before its first response, so a
            // startup failure shows up purely on stderr.
            Log.e(TAG, "pi exited during startup", e)
            _agent.value = AgentStatus.Failed(e.message ?: "pi failed to start")
            detach()
            false
        } catch (t: Throwable) {
            val failure = agentStartFailure(t)
            if (failure == null) {
                // Cancelled, not refused. Release what this attempt had already
                // spawned: `detach` only forgets the client, and a process that had
                // answered the spawn but not the handshake would otherwise outlive the
                // launch with no reader. `NonCancellable` because the caller's job is
                // already cancelled — a suspending close would be cancelled at its
                // first suspension point.
                val pending = client
                val orphan = launched
                detach()
                withContext(NonCancellable) {
                    if (pending != null) {
                        runCatching { pending.close() }
                    } else if (orphan != null) {
                        runCatching { orphan.destroyForcibly() }
                    }
                }
                throw t
            }
            Log.e(TAG, "Starting the agent failed", t)
            _agent.value = failure
            val failed = client
            detach()
            withContext(NonCancellable + Dispatchers.IO) {
                if (failed != null) runCatching { failed.close() }
                else launched?.destroyForcibly()
            }
            false
        }
    }

    private fun attach(source: PiRpcClient) {
        recordJob?.cancel()
        recordJob = scope.launch {
            source.records.collect { record ->
                if (client !== source) return@collect
                val presented = if (record is PiRecord.Event) presentProviderFailure(record) else record
                _conversation.update { ConversationReducer.reduce(it, presented) }
                // The CPU has to stay awake for the length of a turn: without a
                // partial wake lock the device sleeps when the screen goes off,
                // the child's stdout stops being drained, and the answer looks
                // dead when the user comes back.
                if (record is PiRecord.Event) {
                    when (record.type) {
                        "agent_start" -> holdCpuDuringTurn()
                        "agent_settled" -> releaseCpuDuringTurn()
                        "message_end" -> if (record.raw.obj("message")?.str("role") == "user") {
                            nameFirstPrompt(source)
                        }
                        "model_select" -> {
                            confirmedRoute = null
                            refreshState()
                        }
                    }
                }
            }
            // Reached when the flow completes, which happens on stdout EOF —
            // i.e. the agent process is gone.
            //
            // `client === source` is what tells an exit this app caused from one
            // that happened to it. `stopAgent` cancels this job *before* closing
            // the process, but cancellation is only observed at a suspension
            // point: a channel that had already closed when the cancel landed
            // leaves `collect` returning normally, and the lines below would then
            // run for a process that was deliberately stopped — reporting the old
            // process's exit over the top of the fresh one that is starting, and
            // leaving a working agent that the UI refuses to send to
            // (`enabled = agent is Running`). Guarding on the client identity is
            // the honest test: after a restart this source is not the attached one
            // any more.
            if (client !== source) return@launch
            releaseCpuDuringTurn()
            val code = source.exitCodeOrNull()
            // The turn's state has to go with the process: a turn killed
            // mid-flight never emits `agent_settled`, and leaving `isStreaming`
            // up locked the composer into "steer" mode for the next send. The
            // same event closes the tool cards the turn left running — no
            // `tool_execution_end` is coming from a process that is gone — so
            // they stop wearing the live indicator for the rest of the
            // conversation.
            _conversation.update {
                ConversationReducer.finalizeInterruptedTurn(it, System.currentTimeMillis())
            }
            _agent.value = AgentStatus.Failed(
                if (code != null) "pi exited with code $code\n${source.stderrSnapshot().trim()}"
                else "pi stopped unexpectedly",
            )
            scheduleAutoRestart()
        }
    }

    private fun presentProviderFailure(event: PiRecord.Event): PiRecord.Event {
        if (event.type != "message_end") return event
        val message = event.raw.obj("message") ?: return event
        if (message.string("role") != "assistant") return event
        val error = message.string("errorMessage") ?: return event
        val route = confirmedRoute ?: return event
        val host = endpointHost(route.baseUrl) ?: return event
        val network = providerNetworkFailure(error)
        val status = providerAuthStatus(error)
        val problem = when {
            network != null -> DiscoveryProblem.Unreachable(host, network)
            status != null -> DiscoveryProblem.HttpRejected(host, status, viaOverride = false)
            else -> return event
        }
        val presented = stringsFor(settingsStore.read().language).settings.discoveryProblem(problem)
        return event.copy(raw = JsonObject(event.raw + ("message" to JsonObject(
            message + ("errorMessage" to JsonPrimitive(presented)),
        ))))
    }

    private fun detach() {
        recordJob?.cancel()
        recordJob = null
        client = null
        confirmedRoute = null
        launchedRoutes = null
    }

    /** Stops the agent gracefully. Closing stdin is the only clean shutdown. */
    suspend fun stopAgent() = modelCommandLock.withLock { stopAgentSerialized() }

    private suspend fun stopAgentSerialized() {
        stopRequested = true
        autoRestartJob?.cancel()
        autoRestartJob = null
        releaseCpuDuringTurn()
        val current = client ?: return
        detach()
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                current.close()
            } finally {
                _agent.value = AgentStatus.Stopped
            }
        }
    }

    // -------------------------------------------------------------- commands

    fun sendPrompt(text: String, images: List<ImageAttachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        runModelCommand {
            val wanted = selectedProfile() ?: throw modelRouteError()
            val live = ensureModelForPrompt(wanted) ?: return@runModelCommand
            val state = live.requestOrThrow("get_state") { PiCommand.getState(it) }
            val route = launchedRoutes?.route(wanted) ?: throw modelRouteError()
            val reported = state.data as? JsonObject
            if (selectedProfile() != wanted || client !== live || !route.matches(state.reportedModel()) ||
                reported.string("sessionFile") != _conversation.value.sessionFile
            ) throw modelRouteError()
            val busy = turnInFlight || reported?.get("isStreaming") == JsonPrimitive(true)
            live.requestOrThrow("prompt", timeout = null) { id ->
                PiCommand.prompt(id, trimmed, if (busy) "steer" else null, images)
            }
        }
    }

    private suspend fun ensureModelForPrompt(profile: ModelProfile): PiRpcClient? {
        val loaded = launchedRoutes
        val live = client
        if (_agent.value is AgentStatus.Running && live != null && loaded?.canUse(profile) == true) {
            if (confirmedRoute == loaded.route(profile)) return live
            if (turnInFlight) throw modelRouteError()
            try {
                confirmModel(live, profile)
                return live
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PiRpcException) {
                // No prompt has been submitted. Rebuilding the child is safe here.
            }
        }
        if (turnInFlight) throw modelRouteError()
        stopAgentSerialized()
        if (!startAgentSerialized()) return null
        val current = client ?: throw modelRouteError()
        val routes = launchedRoutes ?: throw modelRouteError()
        if (!routes.canUse(profile) || confirmedRoute != routes.route(profile)) throw modelRouteError()
        return current
    }

    private fun selectedProfile(snapshot: PiConfigSnapshot = settingsStore.profiles.snapshots.value): ModelProfile? {
        val (id, model) = conversationProfile ?: return snapshot.activeProfile
        val profile = snapshot.profiles.firstOrNull { it.id == id } ?: throw modelRouteError()
        if (model !in profile.selectableModels) throw modelRouteError()
        return profile.copy(modelId = model)
    }

    private fun modelRouteError(): PiRpcException = PiRpcException(
        stringsFor(settingsStore.read().language).settings.modelRouteUnavailable,
    )

    private fun PiRecord.Response.reportedModel(): JsonObject? =
        (data as? JsonObject)?.get("model") as? JsonObject

    private suspend fun confirmModel(live: PiRpcClient, profile: ModelProfile) {
        val routes = launchedRoutes ?: throw modelRouteError()
        if (!routes.canUse(profile)) throw modelRouteError()
        val wanted = routes.route(profile) ?: throw modelRouteError()
        confirmedRoute = null
        val state = live.requestOrThrow("get_state") { PiCommand.getState(it) }
        if (!wanted.matches(state.reportedModel())) {
            val set = live.requestOrThrow("set_model") { PiCommand.setModel(it, wanted.provider, wanted.model) }
            if (!wanted.matches(set.data as? JsonObject)) throw modelRouteError()
            stateThinkingLevel(live, wanted.model, piLevel = null)
            val confirmed = live.requestOrThrow("get_state") { PiCommand.getState(it) }
            if (!wanted.matches(confirmed.reportedModel())) throw modelRouteError()
            applyState(live, confirmed)
        } else {
            applyState(live, state)
        }
        if (client !== live) throw modelRouteError()
        confirmedRoute = wanted
    }

    /**
     * Stops what is running — and the queue behind it.
     *
     * ## Why `clear_queue` goes first
     *
     * The protocol is explicit that an abort is not a stop for *queued* work:
     * "`abort` continues queued messages when they remain in the session" and the
     * reference client's Esc is `clear_queue` followed by `abort`
     * (`docs/rpc.md`, `clear_queue`). PiKit sent only the `abort`, so a prompt typed
     * while the agent was streaming — which `sendPrompt` queues as `steer` — was
     * delivered the moment the run it interrupted went idle, and a new answer
     * appeared. From the reader's side the Stop button did not stop.
     *
     * The interrupted run's own retry, compaction and bash are covered by `abort`
     * itself: pi's `session.abort()` is `abortRetry()`, `abortCompaction()`,
     * `abortBranchSummary()`, `agent.abort()` and a wait for idle (measured against
     * the bundled `agent-session.js`), so there is nothing to send
     * `abort_retry`/`abort_bash` for.
     *
     * [onCleared] receives the text of the messages that were dropped, joined by
     * newlines, when there were any. The caller decides what to do with it — the
     * composer puts it back in the field — because text the user typed and never saw
     * answered must not disappear silently.
     */
    fun abort(onCleared: (String) -> Unit = {}) {
        runCommand {
            val live = client ?: return@runCommand
            val cleared = runCatching {
                live.request("clear_queue") { PiCommand.clearQueue(it) }
            }.getOrNull()
            // Answers only once the agent is idle, so no client-side timeout.
            live.request("abort", timeout = null) { PiCommand.abort(it) }
            clearedQueueText(cleared)?.let(onCleared)
        }
    }

    /**
     * Starts a fresh conversation.
     *
     * [interrupt] is the caller saying the user has already been told what this costs
     * and chose it: with a turn in flight, pi's `new_session` aborts that turn as its
     * first step (see [TurnInFlightException]), so the running answer stops. Without
     * it — the accidental path — the call is refused and the caller asks first.
     */
    fun newSession(interrupt: Boolean = false) {
        if (turnInFlight && !interrupt) return
        runModelCommand {
            val live = client ?: return@runModelCommand
            if (turnInFlight && !interrupt) return@runModelCommand
            confirmedRoute = null
            val response = live.requestOrThrow("new_session") { PiCommand.newSession(it) }
            if (!response.cancelled()) {
                _conversation.value = ConversationState()
                autoTitlePath.set(null)
                autoTitleCandidate = true
            }
            applyState(live)
            val profile = selectedProfile()
            if (profile != null && profile.modelId.isNotBlank()) ensureModelForPrompt(profile)
        }
    }

    /**
     * A saved conversation on disk.
     *
     * pi has no notion of an automatically generated title — a session is
     * unnamed until someone names it, and pi's own session list falls back to
     * showing the first user message. [title] reproduces that, so the list here
     * reads the same as pi's own.
     */
    data class SessionSummary(
        val path: String,
        val title: String,
        val hasExplicitName: Boolean,
        val messageCount: Int,
        val modifiedAt: Long,
        val pinned: Boolean,
        /**
         * The directory pi recorded in the session file, or null for one written before
         * pi recorded a `cwd`. It is what the drawer groups by, and what
         * [PiHost.openConversation] relaunches the agent into.
         */
        val workspace: String? = null,
        /**
         * What the first turn carried, for the case where it carried no words: the list
         * names such a conversation after its pictures or files rather than `emptyTitle`.
         */
        val imageCount: Int = 0,
        val fileCount: Int = 0,
    ) {
        val fileName: String get() = File(path).name
    }

    /**
     * Transcripts pi has saved: pinned first, then most recently used.
     *
     * Reads every session file, so it runs on an IO dispatcher — a long history
     * is megabytes of JSONL and this must not block the frame.
     */
    suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val pinned = pinnedSessions.value
        val files = sessionDir.listFiles { file -> file.isFile && file.extension == "jsonl" }
            ?: return@withContext emptyList()

        files.map { file ->
            val modifiedAt = file.lastModified()
            val length = file.length()
            val cached = SessionListingCache.meta[file.absolutePath]
            val meta = if (cached != null && cached.modifiedAt == modifiedAt && cached.length == length) {
                cached.meta
            } else {
                runCatching {
                    file.bufferedReader().use { reader -> SessionMetadata.read(reader.lineSequence()) }
                }.getOrDefault(SessionMeta()).also {
                    SessionListingCache.meta[file.absolutePath] = SessionListingCache.CachedMeta(modifiedAt, length, it)
                }
            }
            SessionSummary(
                path = file.absolutePath,
                title = meta.name
                    ?: meta.firstMessage?.let(SessionMetadata::asTitle)
                    // Blank, not a sentence: the label for a conversation with
                    // nothing in it is user-visible text, and it belongs in the
                    // catalogs with every other one. The list substitutes
                    // `sessions.emptyTitle`; a literal here would have shown
                    // English under a Chinese interface.
                    ?: "",
                hasExplicitName = meta.name != null,
                messageCount = meta.messageCount,
                modifiedAt = modifiedAt,
                pinned = file.name in pinned,
                workspace = meta.workspace,
                imageCount = meta.imageCount,
                fileCount = meta.fileCount,
            )
        }.also { summaries ->
            // A conversation deleted while the app was running would otherwise be
            // cached for the life of the process.
            SessionListingCache.meta.keys.retainAll(files.mapTo(HashSet()) { file -> file.absolutePath })
            adoptWorkspaces(summaries)
        }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.modifiedAt })
            .also { SessionListingCache.last = it }
    }

    /**
     * The listing the last scan produced, for a page that is composed before this
     * conversation has ever scanned the directory.
     *
     * The drawer is rebuilt when the reader switches conversation — the chat page
     * is keyed on the session — and a drawer composed from nothing draws its spinner
     * first and its rows a frame or two later. On a quick swipe back into the drawer
     * that is a blank panel that fills in, which reads as the page flashing; the last
     * listing is the same directory the new conversation is about to scan, so it is the
     * right first frame. See [SessionListingCache].
     */
    fun cachedSessionSummaries(): List<SessionSummary> = SessionListingCache.last

    /**
     * Workspaces that arrived from somewhere other than this app's picker.
     *
     * A reader who runs `pi` by hand in the terminal — or any pi session that starts in a
     * directory of its own — writes a session file whose header records that directory, so
     * the drawer groups it under a workspace the composer's menu had never heard of:
     * the group was there and the chip list was not, which is the same conversation in two
     * places disagreeing about where it lives.
     *
     * So a scan adopts them. Every distinct `cwd` that still exists, is not already in the
     * list, and is not the one the app is already showing becomes an ordinary workspace —
     * added exactly the way the picker adds one, so the order, the dedupe and the fallback
     * for a deleted default all behave the same. A directory that has since been deleted is
     * skipped: it cannot be launched in, and adding it would put a row in the menu that
     * does nothing.
     */
    private fun adoptWorkspaces(summaries: List<SessionSummary>) {
        val existing = WorkspaceStore.list(appContext, env).mapTo(HashSet()) { it.path }
        summaries.asSequence()
            .map { it.workspace.orEmpty().trim() }
            .filter { it.startsWith("/") && it !in existing }
            .distinct()
            .filter { java.io.File(it).isDirectory }
            .forEach { path -> WorkspaceStore.add(appContext, env, path) }
    }

    /**
     * The cached searchable prose of each transcript, so typing does not re-read
     * them.
     *
     * Owned by the session rather than by the screen: it survives leaving the
     * page, and the next search reuses what the last one parsed.
     */
    private val searchIndex = SessionSearchIndex()

    // ------------------------------------------------------- model catalogue

    /**
     * One snippet per conversation whose *content* — not just its title — contains
     * [needle], or an empty map for a blank query.
     *
     * The list is filtered on titles synchronously, so this is the expensive half
     * and runs on IO: a conversation is megabytes of JSONL, and a parse per
     * message line per keystroke is not something the frame can pay for. The
     * caller debounces; this cancels cleanly between conversations when it does.
     */
    suspend fun searchSessionContent(
        summaries: List<SessionSummary>,
        needle: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val query = needle.trim()
        if (query.isEmpty()) return@withContext emptyMap()

        val found = LinkedHashMap<String, String>()
        for (summary in summaries) {
            ensureActive()
            val text = searchIndex.text(File(summary.path))
            if (text.isEmpty()) continue
            SessionMetadata.snippet(text, query)?.let { found[summary.path] = it }
        }
        // A conversation deleted while the app was running would otherwise be
        // cached for the life of the process.
        searchIndex.retain(summaries.mapTo(HashSet()) { it.path })
        found
    }

    // ------------------------------------------------------------------ pins

    private val pinnedPrefs by lazy {
        appContext.getSharedPreferences("pikit_session_pins", Context.MODE_PRIVATE)
    }

    private val _pinnedSessions = MutableStateFlow(
        pinnedPrefs.getStringSet(KEY_PINNED, emptySet()).orEmpty().toSet(),
    )

    /** File names of pinned sessions. A session's file name is its identity. */
    val pinnedSessions: StateFlow<Set<String>> = _pinnedSessions.asStateFlow()

    fun setPinned(summary: SessionSummary, pinned: Boolean) {
        val next = _pinnedSessions.value.toMutableSet().apply {
            if (pinned) add(summary.fileName) else remove(summary.fileName)
        }
        _pinnedSessions.value = next
        pinnedPrefs.edit().putStringSet(KEY_PINNED, next).apply()
    }

    // -------------------------------------------------------------- mutation

    /**
     * Renames a saved conversation.
     *
     * The live session is renamed over RPC because pi owns the file while it is
     * open. For any other session the same `session_info` record pi itself
     * writes is appended to the file: pi reads the name by scanning the entries
     * backwards, so a line at the end wins, and the id/parentId keep the entry
     * chain (and therefore pi's session tree) intact.
     */
    suspend fun renameSession(target: SessionSummary, newName: String): Result<Unit> {
        val name = newName.replace(Regex("[\\r\\n]+"), " ").trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Name cannot be empty"))

        return withContext(Dispatchers.IO) {
            try {
                val owner = host.sessionFor(target.path)
                if (owner == null) {
                    appendSessionInfo(File(target.path), name)
                    host.notifySessionListingChanged()
                } else {
                    owner.renameOwnedSession(target.path, name)
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private suspend fun renameOwnedSession(path: String, name: String) = modelCommandLock.withLock {
        check(matchesPath(path)) { "Pi's current conversation changed" }
        val live = client
        if (live == null) {
            appendSessionInfo(File(path), name)
        } else {
            check(nameState(live).str("sessionFile") == path) { "Pi's current conversation changed" }
            live.requestOrThrow("set_session_name", 2.seconds) { PiCommand.setSessionName(it, name) }
        }
        publishSessionName(path, name)
    }

    private fun appendSessionInfo(file: File, name: String) {
        if (!file.isFile) throw java.io.FileNotFoundException(file.name)
        val entry = buildJsonObject {
            put("type", "session_info")
            put("id", randomEntryId())
            // pi indexes entries in file order and takes the last one as the
            // leaf, so the new record has to hang off the current last entry or
            // the session tree gains a second root.
            lastEntryId(file)?.let { put("parentId", it) }
            put("timestamp", isoTimestamp())
            put("name", name)
        }
        file.appendText(entry.toString() + "\n")
    }

    /**
     * The id of the last record in a session file.
     *
     * Read from the tail rather than by parsing the file: sessions grow to
     * megabytes and this only needs the final entry.
     */
    private fun lastEntryId(file: File): String? = runCatching {
        val length = file.length()
        val tailSize = minOf(length, SESSION_TAIL_BYTES).toInt()
        if (tailSize <= 0) return@runCatching null
        val bytes = java.io.RandomAccessFile(file, "r").use { handle ->
            handle.seek(length - tailSize)
            ByteArray(tailSize).also { handle.readFully(it) }
        }
        // The first "id" on a line is the envelope's; anything later belongs to
        // the payload (tool call ids, for instance).
        String(bytes, Charsets.UTF_8).lineSequence()
            .mapNotNull { ENVELOPE_ID.find(it)?.groupValues?.get(1) }
            .lastOrNull()
    }.getOrNull()

    /**
     * Deletes saved conversations.
     *
     * pi has no delete command, so this is the file removal its own session
     * picker falls back to. Deleting the session that is currently open would
     * leave pi appending to a file that no longer exists, so a fresh one is
     * started instead.
     */
    suspend fun deleteSessions(targets: List<SessionSummary>): Result<Int> {
        if (targets.isEmpty()) return Result.success(0)
        val deletedActive = targets.any { it.path == _conversation.value.sessionFile }
        // Deleting the session the agent is writing to is the one case that needs a
        // new session to be started immediately, and that is exactly what pi will
        // not do mid-turn (see [TurnInFlightException]). Unlinking the file the
        // agent still has open would leave it appending into nothing, so the delete
        // is refused rather than half-done.
        if (deletedActive && turnInFlight) return Result.failure(TurnInFlightException())

        val removed = withContext(Dispatchers.IO) {
            targets.count { target -> runCatching { File(target.path).delete() }.getOrDefault(false) }
        }
        for (target in targets) {
            if (target.pinned) setPinned(target, false)
            if (!File(target.path).exists()) {
                host.sessionModes.forget(target.path)
                host.chatPresets.forget(target.path)
            }
        }
        if (deletedActive) newSession()
        return Result.success(removed)
    }

    private fun randomEntryId(): String =
        (1..8).map { ENTRY_ID_ALPHABET.random() }.joinToString("")

    private fun isoTimestamp(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }


    /**
     * Rebinds the live process to [path] and re-reads its transcript.
     *
     * Used by [restoreAfterLaunch] and by nothing else now: a move between
     * conversations is a move between *processes* ([PiHost.openConversation]), so the
     * only reason to rebind this one is that pi just opened a fresh file and the
     * conversation it belongs to is the one this process was launched for.
     *
     * The transcript is re-fetched because switching rebinds pi's state — messages
     * shown afterwards must come from `get_messages`, not from what the UI still held.
     */
    private suspend fun switchSessionTo(path: String) {
        val live = client ?: throw modelRouteError()
        // A conversation opened from the drawer or restored after a restart owns its
        // name already. Clearing the candidate here is what keeps "never title an
        // existing conversation" true even when the file has no name.
        autoTitlePath.set(null)
        autoTitleCandidate = false
        confirmedRoute = null
        val response = live.requestOrThrow("switch_session") {
            PiCommand.switchSession(it, path)
        }
        // Refused by a `session_before_switch` handler: pi still answers
        // `success: true`, so without this the transcript on screen was replaced
        // by the messages of a session pi never opened.
        if (response.cancelled()) throw modelRouteError()
        // A transcript already belonging to **this** file is the answer: clearing it and
        // asking the process for the same messages again is what made a conversation
        // flash empty when its agent finally started. `sessionFile` covers a live
        // transcript being rebound by a mode switch; `restoredFrom` covers one opened
        // from disk. Both are the same fact — the screen is already showing this file.
        val alreadyLoaded = (_conversation.value.sessionFile == path || restoredFrom == path) &&
            _conversation.value.items.isNotEmpty()
        if (!alreadyLoaded) _conversation.value = ConversationState(loadingSession = true)
        try {
            val state = live.requestOrThrow("get_state") { PiCommand.getState(it) }
            if ((state.data as? JsonObject).string("sessionFile") != path) throw modelRouteError()
            _conversation.update { ConversationReducer.reduce(it, state) }
            refreshThinkingLevels()
            if (!alreadyLoaded) loadCurrentMessages(live)
        } finally {
            // In a `finally` because the flag has to come down on the failure paths
            // too: a switch that threw would otherwise leave the page saying
            // "opening the conversation" for ever, with no control that clears it.
            _conversation.update { it.copy(loadingSession = false) }
        }
    }

    /** Folds `get_messages` into the transcript and records the session path. */
    private suspend fun loadCurrentMessages(live: PiRpcClient) {
        val messages = runCatching {
            live.request("get_messages") { PiCommand.getMessages(it) }
        }.getOrNull()
        _conversation.update { ConversationReducer.replaceWithMessages(it, messages?.data) }
        adoptSessionPath(_conversation.value.sessionFile)
    }

    /**
     * Notes the session file this process is in, so a relaunch can rebind to it.
     *
     * The path is also what a cold start remembers, and only the active conversation
     * writes that: a background conversation finishing a turn must not decide what the
     * next launch restores.
     */
    private fun adoptSessionPath(path: String?) {
        if (path.isNullOrBlank()) return
        if (autoTitleCandidate && autoTitlePath.get() == null) autoTitlePath.set(path)
        restoreTarget = path
        // The file is this conversation's identity from here on, so the mode record
        // belongs to it: a chat conversation that just wrote its first file would
        // otherwise reopen as work after the app restarts.
        host.sessionModes.setMode(path, _mode.value)
        // The other two per-conversation records land in the same moment. A chat
        // conversation's first file is also the first moment its preset and its
        // network-tools answer can be keyed to anything.
        host.chatPresets.setSelection(path, _chatPresetId.value)
        host.sessionModes.setWebAccess(path, _chatWebAccess.value)
        if (host.active.value === this) host.rememberLastSession(path)
    }

    /**
     * The saved conversation this launch has to rebind to, or null to keep the session
     * pi opened for itself.
     *
     * This is [restoreAfterLaunch]'s first half, split out because its answer is needed
     * *before* the handshake is folded into the conversation: while a switch is pending
     * the state's session file has to stay the one on screen — see
     * [sessionFileAfterHandshake].
     *
     * Three answers, in order: the file this conversation is already bound to (a
     * restart, or the history row it was opened from), then — only for the first
     * conversation of a cold start — the file the last run was in, and otherwise the
     * fresh session pi just opened. [sessionRestoreTarget] is the pure rule.
     */
    private fun restoreTargetFor(opened: String?): String? {
        val coldStart = coldStartRestore.getAndSet(false)
        val wanted = restoreTarget ?: if (coldStart && restoresRememberedSession) {
            if (shouldRestoreRememberedSession(
                    openNewOnColdStart = settingsStore.read().openNewOnLaunch,
                    coldStart = true,
                )
            ) {
                host.lastRememberedSession()
            } else {
                null
            }
        } else {
            null
        }
        val target = sessionRestoreTarget(wanted, opened) { File(it).isFile }
        // A remembered path whose file is gone is a stale key: clear it so the next
        // launch does not chase a deleted conversation.
        if (wanted != null && !File(wanted).isFile) restoreTarget = null
        // The conversation being restored may not be in the mode the app's default now
        // says: the last visible conversation decides the switch, not the last switch
        // that happened in another conversation.
        if (target != null) {
            _mode.value = host.sessionModes.modeFor(target)
            _chatPresetId.value = host.chatPresets.selectedId(target)
            _chatWebAccess.value = host.sessionModes.webAccessFor(target)
            if (_mode.value == AgentMode.CHAT) rememberChatWebAccess()
            // The restored conversation is now the one on screen, and "last used mode" is
            // that fact — the same invariant `PiHost.activate` keeps for every other
            // change of conversation. Without it a cold start that came back to a chat
            // conversation left the record saying work, and the next conversation created
            // by closing the last one opened on the wrong side of the history list.
            settingsStore.update { it.copy(agentMode = _mode.value) }
        }
        return target
    }

    /**
     * Rebinds a freshly launched agent to the conversation it belongs to.
     *
     * ## Why this exists
     *
     * pi has no resume flag: every `pi --mode rpc` launch opens a **new** session
     * file, and the handshake folds `get_state` into the conversation — including that
     * new `sessionFile` — while `items` and `turns` from the previous process stay on
     * screen. The reader therefore saw their conversation and typed into a different
     * one. Measured as the report: after 切后台/锁屏 and a few failed retries, the next
     * message "在同一界面" started a new conversation.
     *
     * [opened] and [target] are [restoreTargetFor]'s answer, passed in rather than
     * derived here so the fold that happens between the two calls knows whether a
     * switch is coming.
     */
    private suspend fun restoreAfterLaunch(opened: String?, target: String?) {
        val live = client ?: return
        if (target == null) {
            // No saved conversation is being rebound, so this child is a fresh
            // conversation: its first prompt may name it. The candidate is set before
            // the path is adopted because the handshake has already named the file even
            // though pi has not written it yet.
            autoTitleCandidate = true
            adoptSessionPath(opened)
            if (_conversation.value.items.isEmpty() && opened != null) {
                loadCurrentMessages(live)
            }
            return
        }
        Log.i(TAG, "restoring session $target (pi opened $opened)")
        switchSessionTo(target)
    }

    /**
     * Holds a partial wake lock for the length of one turn.
     *
     * Without it the CPU sleeps when the screen goes off, the child's stdout is
     * no longer drained, and the turn looks interrupted when the user returns —
     * the "切后台或者锁屏有时会导致中断对话" report. Released on `agent_settled`,
     * on process exit and on [stopAgent]; the timed acquire is a safety net so a
     * missing settle cannot hold the lock forever.
     */
    private fun holdCpuDuringTurn() {
        val lock = turnWakeLock ?: powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pikit:agent-turn")
            .also {
                it.setReferenceCounted(false)
                turnWakeLock = it
            }
        if (!lock.isHeld) lock.acquire(TURN_WAKE_LOCK_MAX_MS)
    }

    private fun releaseCpuDuringTurn() {
        turnWakeLock?.takeIf { it.isHeld }?.release()
    }

    /**
     * One automatic recovery after an unexpected process death.
     *
     * Backgrounding and locking the screen can kill the child even behind the
     * foreground service. Leaving the agent on `Failed` until the user found
     * Retry meant the next send either did nothing or, after a bare relaunch,
     * went into a new session. One attempt, not a loop: a pi that dies on
     * startup is a configuration failure and a retry storm only hides it.
     * [stopRequested] keeps a deliberate stop from coming back.
     */
    private fun scheduleAutoRestart() {
        if (stopRequested) return
        autoRestartJob?.cancel()
        autoRestartJob = scope.launch {
            delay(AUTO_RESTART_DELAY_MS)
            if (stopRequested) return@launch
            if (_agent.value !is AgentStatus.Failed) return@launch
            Log.i(TAG, "restarting the agent after an unexpected exit")
            startAgent()
        }
    }

    /**
     * Whether the agent is answering this session right now.
     *
     * Both halves matter. `isStreaming` is pi's own `agent_start`/`agent_settled`
     * pair, so it is the honest answer to "is a turn running"; the `Running` check
     * is what keeps a process that died mid-turn — leaving `isStreaming` stuck at
     * true with no `agent_settled` ever coming — from locking the user out of their
     * own session list forever.
     */
    val turnInFlight: Boolean
        get() = _conversation.value.isStreaming && _agent.value is AgentStatus.Running

    /**
     * [turnInFlight] as a flow, for the one observer that is not a screen.
     *
     * The foreground service draws its shade entry from this: an entry that says "an
     * agent is running" for as long as a process is alive is the noise the reader
     * reported, and what they actually want to know from the shade is whether an answer
     * is still being written.
     */
    val streaming: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_conversation, _agent) { conversation, status ->
            conversation.isStreaming && status is AgentStatus.Running
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    init {
        scope.launch {
            var was = false
            streaming.collect { now ->
                if (was && !now) {
                    // `active` is the host's answer to "which conversation is on screen".
                    // The mark is only news when it is not the one being read.
                    _finishedUnseen.value = host.active.value !== this@PiAgentSession
                    host.onTurnFinished(this@PiAgentSession)
                }
                was = now
            }
        }
    }


    /**
     * An answer that finished while the reader was somewhere else.
     *
     * The drawer's dot is a *state*, and the two states it has are not the same fact:
     * a conversation that is writing right now is live and cannot be read yet, and one
     * that finished is finished and has something new. The first is [streaming]; this is
     * the second, set when a turn ends while another conversation is the one on screen
     * and cleared when this one becomes it ([markSeen]).
     *
     * A turn that ends on the conversation the reader is looking at marks nothing: they
     * watched it arrive, and a dot that appears under their eyes is a notification about
     * the thing they are already reading.
     */
    private val _finishedUnseen = MutableStateFlow(false)
    val finishedUnseen: StateFlow<Boolean> = _finishedUnseen.asStateFlow()

    /** The reader is looking at this conversation: whatever it said has been seen. */
    fun markSeen() {
        _finishedUnseen.value = false
    }

    /**
     * Writes this conversation up to its [messages]th message record, and opens nothing.
     *
     * pi's sessions are append-only JSONL, so "fork here" is exactly "copy the records up
     * to here into a new file": the branch is a conversation with the same history and a
     * different future. The header keeps its directory and gets a new id, which is what
     * makes the copy a session of its own rather than a second name for this one.
     *
     * @return the new file's path, or null when this conversation has no file yet, the
     *   count is past the end of it, or the write fails.
     */
    fun forkAt(messages: Int): String? {
        val path = sessionPath ?: return null
        val file = File(path)
        if (messages < 0 || !file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val kept = forkRecords(lines, messages) ?: return null
        kept[0] = kept[0].replaceFirst(Regex("\"id\":\"[^\"]*\""), "\"id\":\"${UUID.randomUUID()}\"")
        return runCatching {
            val target = File(
                file.parentFile,
                "${System.currentTimeMillis()}-fork-${UUID.randomUUID().toString().take(8)}.jsonl",
            )
            target.writeText(kept.joinToString("\n", postfix = "\n"))
            // A fork is a conversation of the same kind as its source: its file is new,
            // so without this record it would reopen as work even when the branch came
            // from a chat conversation.
            host.sessionModes.setMode(target.absolutePath, _mode.value)
            host.chatPresets.setSelection(target.absolutePath, _chatPresetId.value)
            host.sessionModes.setWebAccess(target.absolutePath, _chatWebAccess.value)
            target.absolutePath
        }.getOrNull()
    }

    /**
     * Stops this conversation for good and releases its scope.
     *
     * [PiHost.closeConversation]'s half of a close: the transcript is pi's and already
     * on disk, so nothing is saved here — the child is closed gracefully (stdin closed,
     * which is pi's only clean shutdown) and the coroutines that were draining it are
     * cancelled afterwards. Called on a conversation that is already out of the pool, so
     * nothing observes the `Stopped` it writes.
     */
    internal fun shutdown() {
        stopRequested = true
        autoRestartJob?.cancel()
        autoRestartJob = null
        settingsRestartJob?.cancel()
        releaseCpuDuringTurn()
        val current = client
        detach()
        _agent.value = AgentStatus.Stopped
        scope.launch {
            withContext(Dispatchers.IO) { current?.close() }
            scope.cancel()
        }
    }

    /**
     * Re-reads the session state.
     *
     * The response has to be folded in by hand: a correlated response is consumed
     * by the pending-request map and never reaches the record stream, so without
     * this the UI would keep showing whatever the launch handshake happened to
     * return — which is how the header ended up displaying a provider with an
     * empty model id.
     */
    fun refreshState() {
        runCommand {
            client?.let { applyState(it) }
        }
    }

    /**
     * `get_state`, folded into the conversation and awaited by the caller.
     *
     * The suspend half of [refreshState], for the one caller that must see the answer
     * before deciding anything: [confirmModel] cannot tell which model a replaced
     * session opened on without it, and a fire-and-forget refresh let it decide against
     * an empty state and state the model on every new conversation.
     *
     * A failure is the process's, not this function's: an exception propagates to the
     * caller's `runCommand`, which is what marks a dead pi `Failed` and schedules the one
     * automatic recovery.
     */
    private suspend fun applyState(live: PiRpcClient, state: PiRecord.Response? = null) {
        val response = state ?: live.requestOrThrow("get_state") { PiCommand.getState(it) }
        if (client !== live) return
        logSessionState("refresh", response)
        _conversation.update { ConversationReducer.reduce(it, response) }
        // The session in force is whatever this reply names: a `new_session`
        // or a clone lands here, and the next relaunch has to come back to it.
        adoptSessionPath(_conversation.value.sessionFile)
        // The supported levels follow the model, so a state refresh is also the
        // moment they are re-read: switching the model on the composer changes
        // which levels exist without touching anything else.
        refreshThinkingLevels()
    }

    /**
     * Reads the thinking levels the current model supports.
     *
     * pi derives this list from the model's own `thinkingLevelMap`
     * (`getSupportedThinkingLevels` in `pi-ai/dist/models.js`): a reasoning model
     * that maps only some levels — DeepSeek's `{high, max}`, for instance — reports
     * fewer than the seven pi knows, `xhigh`/`max` appear only when the map names
     * them, and a model with no reasoning support reports exactly `["off"]`.
     * Offering the seven regardless is what made tapping `medium` leave the chip
     * reading `high`.
     */
    private suspend fun refreshThinkingLevels() {
        val current = client ?: return
        val response = runCatching {
            current.request("get_available_thinking_levels") {
                PiCommand.getAvailableThinkingLevels(it)
            }
        }.getOrNull() ?: return
        _conversation.update { ConversationReducer.reduce(it, response) }
        rememberThinkingLevels()
    }

    /**
     * Keeps pi's answer for the model that is running, so the chip can pre-clamp
     * against it before the next answer arrives.
     *
     * Only the *list* is written back — never the level. Persisting pi's clamped
     * level is what this replaces, and the bug is worth spelling out: pi answers
     * `["off"]` for a model that does not reason at all, so using one such model
     * once wrote `off` into the saved preference, and every launch afterwards passed
     * `--thinking off` — for reasoning models too. The level is the user's choice
     * across models; the list is a fact about one of them.
     *
     * Recorded together with the model it is about (`availableThinkingLevelsFor`):
     * the levels are a property of the model's `thinkingLevelMap`, so a list with no
     * key would be a picker showing the previous model's menu.
     */
    private fun rememberThinkingLevels() {
        val state = _conversation.value
        val levels = state.availableThinkingLevels
        if (levels.isEmpty()) return
        val modelId = state.model?.id.orEmpty()
        val saved = settingsStore.read()
        if (saved.availableThinkingLevels == levels && saved.availableThinkingLevelsFor == modelId) return
        settingsStore.update {
            it.copy(availableThinkingLevels = levels, availableThinkingLevelsFor = modelId)
        }
    }

    /**
     * Records the model from a `get_state` reply.
     *
     * A correlated response never reaches the record stream, so this is the only
     * place the raw payload can be observed. One line per agent start, and the
     * quickest way to tell a misconfigured model id from a display problem.
     */
    private fun logSessionState(label: String, response: PiRecord.Response) {
        if (!Log.isLoggable(TAG, Log.INFO)) return
        val model = runCatching { response.data?.jsonObject?.get("model") }.getOrNull()
        Log.i(
            TAG,
            "$label get_state: success=${response.success} error=${response.error} model=$model",
        )
    }

    fun setThinkingLevel(level: String) {
        runCommand { client?.requestOrThrow("set_thinking_level") { PiCommand.setThinkingLevel(it, level) } }
    }

    fun selectProfile(profile: ModelProfile, model: String) {
        runModelCommand {
            val store = settingsStore.profiles
            if (!store.selectModel(profile, model)) {
                val strings = stringsFor(settingsStore.read().language).settings
                throw PiRpcException(if (store.lastWriteFailed) strings.saveFailed else strings.modelRouteUnavailable)
            }
            conversationProfile = profile.id to model
            _conversation.update { it.copy(requestedModel = model, lastError = null) }
            if (turnInFlight) {
                scheduleRestart()
                return@runModelCommand
            }
            val selected = selectedProfile() ?: throw modelRouteError()
            ensureModelForPrompt(selected)
        }
    }

    /**
     * Tells a running pi which level the model it is on should be using.
     *
     * Called at the two moments pi answers a level of its own accord — after `set_model`
     * and after the launch handshake — because pi's per-model memory (`modelThinkingLevels`
     * in its `settings.json`) is consulted on both paths and outranks `--thinking` when it
     * resolves a model. Stating the reader's choice is therefore the only way the two can
     * agree, and [thinkingLevelToState] is the decision of whether anything needs saying.
     *
     * [piLevel] is what pi reported for [modelId]; null means "just switched, so ask it
     * to take this level" rather than "compare first". A failed request is logged and
     * ignored: the transcript's own chip already shows the level the reader chose, and a
     * relaunch is not worth it for a level.
     */
    private suspend fun stateThinkingLevel(live: PiRpcClient, modelId: String, piLevel: String?) {
        val levels = levelsKnownFor(modelId)
        val level = thinkingLevelToState(
            saved = settingsStore.read().thinkingLevel,
            available = levels,
            piLevel = piLevel,
        ) ?: return
        runCatching {
            live.requestOrThrow("set_thinking_level") { PiCommand.setThinkingLevel(it, level) }
        }.onFailure { Log.w(TAG, "could not state the thinking level $level for $modelId", it) }
    }

    /**
     * What [modelId] can do, from the two sources that answer for a model pi may not be
     * on: this process's last answer, when it was an answer for that model, and pi's
     * catalogue on disk (which covers a model the switch has *just* moved to, before pi
     * has answered for it). Empty means nothing knows, and pi's own clamp then stands.
     */
    private fun levelsKnownFor(modelId: String): List<String> {
        val state = _conversation.value
        val live = state.availableThinkingLevels
            .takeIf { state.availableThinkingLevelsFor == modelId }
            .orEmpty()
        return live.ifEmpty { catalogueThinkingLevels.value[modelId].orEmpty() }
    }

    fun compact() {
        runCommand { client?.request("compact", timeout = null) { PiCommand.compact(it) } }
    }

    /**
     * The session's own counters, for the composer's `/stats` row.
     *
     * `get_session_stats` has been buildable since the RPC layer was written and was
     * never called by anything: the numbers it answers — turns, tokens in and out, the
     * cached share, what the conversation has cost — are the ones the context report
     * shows for *this* request, and this is the running total. It is read on demand
     * rather than polled, because pi computes it from the session file and nothing on
     * screen needs it per frame.
     *
     * [onResult] runs on the session's scope, so the caller is handed the numbers
     * rather than a return value: `request` is suspend and this method returns before
     * pi has answered.
     */
    fun sessionStats(onResult: (Result<StatsReport>) -> Unit) {
        runCommand {
            val current = client ?: return@runCommand
            val response = current.request("get_session_stats") { PiCommand.getSessionStats(it) }
            val data = response.data?.jsonObject
            if (!response.success || data == null) {
                onResult(Result.failure(IllegalStateException(response.error ?: "pi did not answer")))
                return@runCommand
            }
            fun number(key: String, from: JsonObject? = data): Long =
                from?.get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val tokens = data["tokens"]?.let { runCatching { it.jsonObject }.getOrNull() }
            onResult(
                Result.success(
                    StatsReport(
                        turns = number("assistantMessages"),
                        messages = number("totalMessages"),
                        toolCalls = number("toolCalls"),
                        inputTokens = number("input", tokens),
                        outputTokens = number("output", tokens),
                        cacheReadTokens = number("cacheRead", tokens),
                        cost = data["cost"]?.jsonPrimitive?.contentOrNull ?: "0",
                    ),
                ),
            )
        }
    }

    /**
     * Puts the last answer on the clipboard.
     *
     * The copy button under an answer is a whole message; this is the same text for the
     * reader who is typing in the terminal and wants the paragraph without scrolling
     * back to it. `data.text` is absent when there is no answer yet — an ordinary state
     * rather than a failure, which is why the callback gets null instead of an error.
     */
    fun copyLastAnswer(onResult: (String?) -> Unit) {
        runCommand {
            val current = client ?: return@runCommand
            val response = current.request("get_last_assistant_text") {
                PiCommand.getLastAssistantText(it)
            }
            val text = response.data?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            onResult(text)
        }
    }

    /** What `/stats` reports: the running totals, not this request's. */
    data class StatsReport(
        val turns: Long,
        val messages: Long,
        val toolCalls: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val cost: String,
    )

    /**
     * Writes the conversation to an HTML file and says where it went.
     *
     * The path is built from the session's own file rather than from a fixed name:
     * a user who exports two conversations should get two files, and the session
     * file's name is already unique per conversation. `export/` sits beside the
     * session files inside the runtime's `$HOME`, which is the directory the Files
     * tab's browser is rooted at — so the path in the notice is one the user can
     * go and open.
     *
     * The notice is written here rather than returned to the caller, and that is
     * what the shape of this says: `client?.request` is suspend and this class's
     * commands all run on the session's own scope, so a function that *returned* the
     * path would return before the write happened. Every outcome is a row in the
     * transcript, which is the same channel the `!` command's output uses.
     */
    fun exportHtml() {
        runCommand {
            val current = client ?: return@runCommand
            val directory = env.exportDir.apply { mkdirs() }
            val stem = conversation.value.sessionFile?.let { File(it).nameWithoutExtension }
                ?.takeIf { it.isNotBlank() }
                ?: "session"
            val path = File(directory, "$stem.html").absolutePath
            val response = current.request("export_html") { PiCommand.exportHtml(it, path) }
            val written = if (response.success) {
                response.data?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull ?: path
            } else {
                null
            }
            _conversation.update { state ->
                if (written != null) {
                    ConversationReducer.notice(state, "Saved to $written")
                } else {
                    ConversationReducer.notice(
                        state,
                        response.error ?: "pi could not export this session",
                        NoticeKind.Error,
                    )
                }
            }
        }
    }

    /**
     * Duplicates the conversation into a session of its own.
     *
     * pi's own `clone`, so the copy keeps the whole branch — including the turns a
     * compaction has folded away — and the original stays open behind it. The
     * transcript is re-read afterwards for the same reason a session switch re-reads
     * it: pi has rebound its state, and what is on screen has to come from pi rather
     * than from what this app happened to be holding.
     *
     * A `cancelled` answer is an extension refusing the fork. pi reports it as
     * `success: true`, so reading `success` alone would tell the user their
     * conversation had been duplicated when it had not.
     */
    fun cloneSession() {
        runModelCommand {
            val live = client ?: return@runModelCommand
            if (turnInFlight) return@runModelCommand
            confirmedRoute = null
            val response = live.requestOrThrow("clone") { PiCommand.clone(it) }
            if (!response.cancelled()) {
                _conversation.value = ConversationState()
                applyState(live)
                loadCurrentMessages(live)
            }
            val profile = selectedProfile()
            if (profile != null && profile.modelId.isNotBlank()) ensureModelForPrompt(profile)
        }
    }

    /**
     * Runs a shell command directly, without involving the model.
     *
     * The output is a row in the transcript, and a command that printed nothing is
     * still a row: it used to add a notice only when the output was non-empty, so
     * `!cd /tmp` — or any command whose result is its exit code — ran, changed
     * something, and left the reader looking at a page that had not moved. A reader
     * cannot tell "it worked and said nothing" from "the tap did not land", and the
     * second is what they will assume. The exit code is the answer, so the exit code
     * is what the row carries when there is nothing else to carry.
     */
    fun runBash(command: String) {
        runCommand {
            val response = client?.request("bash", timeout = null) { PiCommand.bash(it, command) }
                ?: return@runCommand
            val output = response.data?.jsonObject?.get("output")
                ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val exit = response.data?.jsonObject?.get("exitCode")?.jsonPrimitive?.intOrNull
            val text = buildString {
                append(output)
                response.error?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append('\n')
                    append(it)
                }
                if (isEmpty() && exit != null) append("exit code $exit")
            }.trim()
            if (text.isNotEmpty()) {
                _conversation.update { ConversationReducer.notice(it, "$ $command\n$text") }
            }
        }
    }

    private fun nameFirstPrompt(source: PiRpcClient) {
        if (client !== source) return
        val snapshot = _conversation.value
        val candidate = autoTitleCandidate
        var path = autoTitlePath.get()
        if (path == null && candidate) {
            // A handshake that did not name a file leaves the candidate armed; the
            // first user message is the point where the reducer has one. Taking it
            // here rather than waiting for a later `get_state` is what makes the rule
            // cover both pi's "file named at launch" and "file named at first write"
            // behaviours.
            path = snapshot.sessionFile ?: return
            if (!autoTitlePath.compareAndSet(null, path)) path = autoTitlePath.get() ?: return
        }
        if (path == null) return
        if (snapshot.sessionFile != path || !autoTitlePath.compareAndSet(path, null)) return
        if (candidate) autoTitleCandidate = false
        val prompt = firstPromptText(snapshot, path, settingsStore.read().chatTitleEnabled) ?: return
        // The title is a small request to the model this conversation answers from.
        // Reading the profile and its route here, before the coroutine, keeps the
        // request's target the one the launch actually confirmed; a profile change
        // while the request is in flight does not make the title answer a model the
        // conversation was never talking to.
        val profile = runCatching { selectedProfile() }.getOrNull() ?: return
        val route = launchedRoutes?.route(profile) ?: return
        val cliEntry = PiInstallation.cliEntry(env) ?: return
        // `pi --print` is a second short-lived child. It is given the same provider
        // entry, credentials and workspace as this conversation, but no session, no
        // tools and no extensions; see [PiProcessLauncher.generateTitle]. Reading
        // these before the coroutine keeps the request on the route the launch
        // confirmed, rather than on whatever profile is active when it finishes.
        val titleOptions = PiLaunchOptions(
            nodePath = env.node.absolutePath,
            cliEntry = cliEntry.absolutePath,
            workingDir = launchWorkspace(),
            sessionDir = null,
            provider = route.provider,
            modelId = route.model,
            thinkingLevel = "off",
            extraEnv = ProfileAddressing.providerEnvironment(settingsStore.profiles.snapshots.value),
            offline = true,
            noSession = true,
        )
        scope.launch {
            val raw = runCatching {
                PiProcessLauncher.generateTitle(
                    context = appContext,
                    env = env,
                    options = titleOptions,
                    prompt = prompt,
                    systemPrompt = titleSystemPrompt(),
                )
            }.getOrNull() ?: return@launch
            val name = normalizeTitle(raw).takeIf { it.isNotBlank() } ?: return@launch
            try {
                modelCommandLock.withLock {
                    if (client !== source || sessionPath != path || !settingsStore.read().chatTitleEnabled ||
                        !_conversation.value.sessionName.isNullOrBlank()
                    ) return@withLock
                    val liveState = nameState(source)
                    if (liveState.str("sessionFile") != path || !liveState.str("sessionName").isNullOrBlank()) {
                        return@withLock
                    }
                    source.requestOrThrow("set_session_name", 2.seconds) { PiCommand.setSessionName(it, name) }
                    publishSessionName(path, name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not store the new conversation title", error)
            }
        }
    }

    private suspend fun nameState(source: PiRpcClient): JsonObject =
        source.requestOrThrow("get_state", 2.seconds) { PiCommand.getState(it) }.data as? JsonObject
            ?: throw PiRpcException("Pi did not provide session state")

    private fun publishSessionName(path: String, name: String) {
        _conversation.update { state ->
            if (state.sessionFile == path) state.copy(sessionName = name) else state
        }
        host.notifySessionListingChanged()
    }

    // ------------------------------------------------------------ dialogs

    fun answerDialog(value: String) {
        val requestId = _conversation.value.pendingDialog?.id ?: return
        sendRaw(PiCommand.extensionUiValue(requestId, value))
    }

    fun answerDialogConfirmed(confirmed: Boolean) {
        val requestId = _conversation.value.pendingDialog?.id ?: return
        sendRaw(PiCommand.extensionUiConfirmed(requestId, confirmed))
    }

    fun cancelDialog() {
        val requestId = _conversation.value.pendingDialog?.id ?: return
        sendRaw(PiCommand.extensionUiCancelled(requestId))
    }

    private fun sendRaw(record: String) {
        runCommand { client?.send(record) }
        _conversation.update { it.copy(pendingDialog = null) }
    }

    private fun runModelCommand(block: suspend () -> Unit) {
        runCommand(ordered = true) {
            modelCommandLock.withLock { withContext(Dispatchers.IO) { block() } }
        }
    }

    private fun runCommand(ordered: Boolean = false, block: suspend () -> Unit) {
        scope.launch(start = if (ordered) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: PiProcessExitedException) {
                releaseCpuDuringTurn()
                _conversation.update {
                    it.copy(lastError = e.message, isStreaming = false, statusMessage = null)
                }
                _agent.value = AgentStatus.Failed(e.message ?: "pi exited")
                scheduleAutoRestart()
            } catch (t: Throwable) {
                _conversation.update { it.copy(lastError = t.message ?: t.toString()) }
                // A failed stdin write is a dead process even if the exit has not
                // been reaped yet. Treat it like one so Retry and the auto-restart
                // both see `Failed` instead of a `Running` agent that cannot talk.
                if (t is PiRpcException && t.cause is java.io.IOException) {
                    releaseCpuDuringTurn()
                    _conversation.update { it.copy(isStreaming = false, statusMessage = null) }
                    _agent.value = AgentStatus.Failed(t.message ?: "pi exited")
                    scheduleAutoRestart()
                }
            }
        }
    }

    fun clearError() {
        _conversation.update { it.copy(lastError = null) }
    }

    private var settingsRestartJob: Job? = null

    /**
     * Restarts the agent shortly after a setting changes.
     *
     * Provider, model and API key are only read when the process is spawned
     * (`--provider`, `--model`, and the key through the environment), so editing
     * them while the agent runs otherwise has no effect and the UI keeps
     * reporting the model the process was started with. Requiring the user to
     * remember a Restart button is the bug; this debounces instead, so typing a
     * model id does not restart the agent on every keystroke.
     */
    fun scheduleRestart() {
        settingsRestartJob?.cancel()
        // The switch is shown as made now, not when the restart finishes: the store
        // above is already committed, and the process it needs — 1.5 s of debounce,
        // then spawn and handshake — is several seconds during which the conversation
        // state survives with the previous model in it. Same shape as `selectProfile`'s
        // own write (see it): the reducer drops this when the state names another model,
        // and a restart over a setting that is not a model rewrites the same id that is
        // already running.
        val requested = runCatching { selectedProfile()?.modelId }.getOrNull()?.takeIf { it.isNotBlank() }
        _conversation.update { it.copy(requestedModel = requested) }
        settingsRestartJob = scope.launch {
            delay(SETTINGS_RESTART_DEBOUNCE_MS)
            // A settings save is not a reason to kill the turn that is streaming.
            // `stopAgent` tears the process down whatever it is doing, and a save
            // from the model page while an answer is coming used to look exactly
            // like a disconnect: the text stopped mid-sentence and the banner
            // said the agent was gone. Wait for the turn to settle instead — the
            // debounce already coalesces a burst of edits, and this coalesces
            // "edit while answering" into "restart when the answer is done".
            while (true) {
                val restarted = modelCommandLock.withLock {
                    if (turnInFlight) false else {
                        stopAgentSerialized()
                        startAgentSerialized()
                        true
                    }
                }
                if (restarted) break
                delay(SETTINGS_RESTART_POLL_MS)
            }
        }
    }

    /**
     * Restarts the agent now, with no debounce.
     *
     * The user's own explicit "try again": [scheduleRestart] waits 1.5 s because a
     * *setting* may change several times in a row, and that delay in front of a
     * button that was just pressed reads as a dropped tap. There is nothing to
     * coalesce here.
     */
    fun restartAgent() {
        settingsRestartJob?.cancel()
        settingsRestartJob = scope.launch {
            modelCommandLock.withLock {
                stopAgentSerialized()
                startAgentSerialized()
            }
        }
    }

    // -------------------------------------------------------- prefix repair

    private val _repair = MutableStateFlow<PrefixPatcher.Result?>(null)

    /** Outcome of the most recent [repairInstalledPackages] run. */
    val repair: StateFlow<PrefixPatcher.Result?> = _repair.asStateFlow()

    /** True while [repairInstalledPackages] is walking the prefix. */
    private val _repairRunning = MutableStateFlow(false)
    val repairRunning: StateFlow<Boolean> = _repairRunning.asStateFlow()

    /** Files the running walk has read so far; zero when nothing is running. */
    private val _repairScanned = MutableStateFlow(0)
    val repairScanned: StateFlow<Int> = _repairScanned.asStateFlow()

    /**
     * The bundled web extension's configuration, and the only writer of
     * `$HOME/.pikit/web.json`.
     *
     * Owned by the session rather than by the settings page so the default file is
     * written on launch whether or not that page is ever opened, and so the page and
     * the agent read the same in-memory copy.
     */
    val webSearch: WebSearchStore by lazy {
        WebSearchStore(env, scope)
    }

    /**
     * Re-applies the prefix relocation to anything installed since the image
     * was built.
     *
     * `pkg install` downloads artefacts compiled for the upstream package id, so
     * their shebangs and `DT_RUNPATH` point at a directory this app cannot read;
     * a binary in that state fails at exec time and looks like a broken package.
     * The bundled tools are unaffected, so this is a no-op unless the user has
     * actually installed something.
     */
    fun repairInstalledPackages() {
        // Guarded on *running*, not on a previous result being on screen: the old
        // `if (_repair.value != null) return` left the button dead until the user
        // pressed Dismiss, which reads as a broken button rather than as a result
        // waiting to be acknowledged.
        //
        // Compare-and-set rather than `if (value) return` followed by an assignment:
        // that is a check-then-act on two threads, and two taps in one frame would
        // both pass it and walk the prefix twice.
        if (!_repairRunning.compareAndSet(expect = false, update = true)) return
        _repairScanned.value = 0
        // The previous outcome is cleared or the page shows it *under* the spinner
        // for a run that has not produced anything yet.
        _repair.value = null
        scope.launch {
            try {
                val result = runCatching {
                    // The walk is over ~22 000 files and takes long enough that a
                    // silent button is indistinguishable from a dead one.
                    PrefixPatcher(env).repair(onProgress = { scanned -> _repairScanned.value = scanned })
                }.getOrElse { PrefixPatcher.Result(errors = listOf(it.message ?: it.toString())) }
                _repair.value = result
            } finally {
                // In `finally` because an `Error` (OOM over 22 000 files) or a
                // cancellation escaping `repair()` would otherwise leave the flag
                // true for the life of the process: button disabled, spinner up,
                // dismiss hidden, no result — the original report in a new shape.
                _repairRunning.value = false
            }
        }
    }

    fun clearRepairResult() {
        _repair.value = null
    }

    // ------------------------------------------------------ installed packages

    /**
     * What the package ledger says: the last restore's outcome, what is still
     * waiting for one, and what the image ships ahead of the device.
     *
     * Owned by the session for the same reason the repair walk is: these are
     * facts about the environment this session owns, and the maintenance page
     * is one tab that leaves the composition the moment the user looks at
     * another one. Refreshed whenever an install or a restore has just run, and
     * on demand when the page opens.
     */
    internal data class PackagesReport(
        val installed: Int,
        val restored: List<PackageManifest.Entry>,
        val pending: List<PackageManifest.Entry>,
        val upgradable: List<PackageManifest.Entry>,
    )

    private val _packages = MutableStateFlow<PackagesReport?>(null)
    internal val packages: StateFlow<PackagesReport?> = _packages.asStateFlow()

    /** Re-reads dpkg's status, the restore manifest, and the image's shipped set. */
    internal fun refreshPackages() {
        scope.launch { _packages.value = readPackagesReport() }
    }

    /**
     * The three sources the report is made of, each read best-effort: a missing
     * file is an empty answer rather than a failure — a first install has no
     * restore report yet, and an image built before the ledger existed has no
     * packages registered to compare.
     */
    private fun readPackagesReport(): PackagesReport {
        val installed = runCatching {
            val status = File(env.prefix, "var/lib/dpkg/status")
            if (status.isFile) PackageManifest.parseStatus(status.readText()) else emptyList()
        }.getOrDefault(emptyList())
        val report = runCatching {
            if (env.pkgRestoreReport.isFile) {
                PackageManifest.parseReport(env.pkgRestoreReport.readText())
            } else {
                PackageManifest.Report(emptyList(), emptyList())
            }
        }.getOrDefault(PackageManifest.Report(emptyList(), emptyList()))
        val pending = runCatching {
            if (env.pkgRestoreManifest.isFile) {
                PackageManifest.parseList(env.pkgRestoreManifest.readText())
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        val shipped = BundledImage.metadata(appContext)?.packages.orEmpty()
        return PackagesReport(
            installed = installed.size,
            restored = report.ready,
            // The manifest is the live "still missing" list — trimmed after
            // every attempt — and outranks the report's view of the last one.
            pending = pending,
            upgradable = PackageManifest.upgradable(installed, shipped),
        )
    }

    // ------------------------------------------------------- package runs

    /**
     * The result of the most recent package run: which row started it, whether
     * it came back clean, the sentence the page shows and the detail it shows
     * under it.
     *
     * The message is resolved here rather than in the page because the run ends
     * while the page may not be composed at all — the same reason
     * [AgentStatus.Failed] carries its sentence. It is read in the interface
     * language in effect when the run finishes, which is the language the toast
     * and the dialog are about to be read in.
     */
    internal data class PackageRun(
        val kind: Kind,
        val succeeded: Boolean,
        val message: String,
        val lines: List<String>,
    ) {
        /** Which of the page's two rows produced this run. */
        internal enum class Kind { Upgrade, Restore }
    }

    /** True while `pkg upgrade -y` is running in the environment. */
    private val _upgradeRunning = MutableStateFlow(false)
    val upgradeRunning: StateFlow<Boolean> = _upgradeRunning.asStateFlow()

    /** True while the captured packages are being put back. */
    private val _restoreRunning = MutableStateFlow(false)
    val restoreRunning: StateFlow<Boolean> = _restoreRunning.asStateFlow()

    /**
     * The most recent package run, until the page's dialog dismisses it.
     *
     * One slot for both runs because only one of them can be pressed at a time
     * and the dialog's OK is the clear call for whichever it was: two slots
     * would need two clear calls for one acknowledgement.
     */
    private val _packageRun = MutableStateFlow<PackageRun?>(null)
    internal val packageRun: StateFlow<PackageRun?> = _packageRun.asStateFlow()

    /**
     * Updates the environment's packages — the command the ledger's update
     * count used to point at in a terminal.
     *
     * Guarded on *running*, compare-and-set for the reason
     * [repairInstalledPackages] is: two taps in one frame would otherwise start
     * two apt runs over one database, and the second would sit waiting on the
     * lock the first holds with no way to tell it apart from a hang.
     */
    fun upgradePackages() {
        if (!_upgradeRunning.compareAndSet(expect = false, update = true)) return
        // The previous run's result is cleared or the page shows the old
        // failure under a spinner for a run that has not produced anything yet.
        _packageRun.value = null
        scope.launch {
            try {
                val outcome = runCatching { BootstrapInstaller(appContext).upgradePackages() }
                _packageRun.value = upgradeRun(outcome)
            } finally {
                // In `finally` for the reason the walk's flag is: an `Error` or a
                // cancellation escaping the run would otherwise leave the row
                // spinning for the life of the process, with no result to dismiss.
                _upgradeRunning.value = false
                // The ledger is what the page reports, so it is re-read whatever
                // the exit status was: a run that updated most of the list still
                // changed it.
                refreshPackages()
            }
        }
    }

    /**
     * Puts back the packages a runtime update displaced — the command the
     * ledger's pending count used to point at in a terminal.
     *
     * [BootstrapInstaller.restorePendingPackages] is idempotent and trims its
     * manifest as it goes, so a second press after a partial restore continues
     * where the first stopped rather than starting over.
     */
    fun restorePendingPackages() {
        if (!_restoreRunning.compareAndSet(expect = false, update = true)) return
        _packageRun.value = null
        scope.launch {
            try {
                val result = runCatching { BootstrapInstaller(appContext).restorePendingPackages() }
                _packageRun.value = restoreRun(result)
            } finally {
                _restoreRunning.value = false
                refreshPackages()
            }
        }
    }

    /** Dismisses the last package run's result; the page's dialog OK is the only caller. */
    fun clearPackageRun() {
        _packageRun.value = null
    }

    /**
     * Turns one upgrade outcome into the page's one sentence plus detail.
     *
     * "Still behind" and "did not finish" are separate sentences because they
     * ask for different things: the first is a count the reader can decide to
     * ignore, the second is a run to look at the log for. A thrown run has no
     * exit status at all, so it takes the same sentence as a command that
     * failed without moving the ledger — whatever went wrong, the log's end is
     * where the answer is.
     */
    private fun upgradeRun(
        result: Result<BootstrapInstaller.UpgradeOutcome>,
    ): PackageRun {
        val text = stringsFor(settingsStore.read().language).settings
        val outcome = result.getOrNull()
        if (outcome == null) {
            return PackageRun(
                kind = PackageRun.Kind.Upgrade,
                succeeded = false,
                message = text.packagesRunIncomplete,
                lines = result.exceptionOrNull()?.let { listOf(it.message ?: it.toString()) }
                    .orEmpty(),
            )
        }
        if (outcome.succeeded) {
            return PackageRun(
                kind = PackageRun.Kind.Upgrade,
                succeeded = true,
                message = text.packagesUpgradeDone,
                lines = emptyList(),
            )
        }
        return PackageRun(
            kind = PackageRun.Kind.Upgrade,
            succeeded = false,
            message = if (outcome.upgradable.isNotEmpty()) {
                text.packagesUpgradeLeft(outcome.upgradable.size)
            } else {
                text.packagesRunIncomplete
            },
            lines = outcome.logLines,
        )
    }

    /**
     * Turns one restore report into the page's sentence plus detail.
     *
     * The report names what is still missing and nothing else — the restore's
     * `pkg install` pass may have failed for a network reason, but the names it
     * could not bring back are the part a reader can act on.
     */
    private fun restoreRun(result: Result<PackageManifest.Report>): PackageRun {
        val text = stringsFor(settingsStore.read().language).settings
        val report = result.getOrNull()
        if (report == null) {
            return PackageRun(
                kind = PackageRun.Kind.Restore,
                succeeded = false,
                message = text.packagesRunIncomplete,
                lines = result.exceptionOrNull()?.let { listOf(it.message ?: it.toString()) }
                    .orEmpty(),
            )
        }
        return if (report.pending.isEmpty()) {
            PackageRun(
                kind = PackageRun.Kind.Restore,
                succeeded = true,
                message = text.packagesRestoreDone,
                lines = emptyList(),
            )
        } else {
            PackageRun(
                kind = PackageRun.Kind.Restore,
                succeeded = false,
                message = text.packagesRestoreLeft(report.pending.size),
                lines = report.pending.map { "${it.name} ${it.version}".trim() },
            )
        }
    }

    // -------------------------------------------------------------- rebuild

    /**
     * Re-extracts the whole runtime from the APK — the escape hatch the
     * seed/content split made rarely necessary.
     *
     * The operation is the host's because the environment is: it stops *every* live
     * conversation, not just this one, and the page's row is the same row whichever
     * conversation opened it. See [PiHost.rebuildEnvironment].
     */
    fun rebuildEnvironment() = host.rebuildEnvironment()

    /** True while [PiHost.rebuildEnvironment] is unpacking. */
    val rebuildRunning: StateFlow<Boolean> get() = host.rebuildRunning

    // ----------------------------------------------------------------- backup

    /**
     * Exporting the app's own data to a file, and putting it back.
     *
     * Owned by the session rather than by the settings page that draws it, for the
     * same reason [repairInstalledPackages] is: a run is long — a workspace backup
     * is a whole project tree — and the page is one tab that leaves the composition
     * the moment the user looks at another one. See [BackupManager].
     */
    val backup: BackupManager by lazy { BackupManager(appContext, env, scope, this) }

    /**
     * Re-reads everything a restore may have changed underneath this process, and
     * re-derives pi's launch files from what it found.
     *
     * Every store in this app read its file once, when the process started, and
     * every one of them then holds a *copy*: `SharedPreferences` hands out a single
     * cached instance to whoever asks, [SettingsStore] publishes a `StateFlow` built
     * from an `AtomicReference`, and the conversation listing caches what it parsed.
     * Writing the files is only half a restore; this is the other half, and without
     * it a restored theme, language, model or conversation looks exactly like a
     * restore that did nothing at all.
     *
     * Called once the files are in place and **before** the agent is started again:
     * the launch path rewrites `models.json` and `settings.json` from the restored
     * profile, so the profile has to be in memory by the time it runs.
     *
     * Those two files are rewritten *here* as well, and that is not redundant with
     * the launch. A restore that finds the agent already stopped never reaches
     * [startAgent], so the backup's own `settings.json` would be left as the only
     * thing a hand-run `pi` can read — and a backup taken before the defaults were
     * first written, or one whose profile and `settings.json` had drifted apart,
     * leaves the terminal tab typing `pi` into a process with no model at all.
     * Re-deriving them from the restored profile is the same statement the launch
     * makes, made whether or not a launch follows.
     *
     * The restore is the only caller. Every other write in the app goes through the
     * store that owns the file, and that is what keeps each of them its one writer.
     */
    suspend fun reloadAfterRestore() = modelCommandLock.withLock {
        conversationProfile = null
        settingsStore.reload()
        // The presets are a document of their own, and one restore replaces it wholesale:
        // the store read its list at startup, so without this the chat half would show the
        // personas the backup replaced until the app was relaunched.
        host.chatPresets.reload()
        // The web config travels with MODELS, and the store holds the
        // document it read at startup — the search page would otherwise keep
        // showing the old options while the file on disk was the restored one.
        webSearch.reload()
        // Entries are keyed by (path, modified, length), which a restored file
        // usually misses on; cleared because an extracted file's mtime is the
        // moment it was extracted, and two restores in the same second would
        // otherwise be served the first one's metadata.
        SessionListingCache.clear()
        reloadPinnedSessions()
        // The policy decides which of the phone's own folders are linked into
        // `$HOME`, so a restored one changes the tree the agent sees, not just the
        // list on the storage page.
        syncStorageLinks()
        // The welcome banner is written in the interface language: a restored
        // language has to reach the shell as well as the screen.
        refreshTerminalBanner()
        // Last, and on the IO dispatcher for the same reason the launch path is:
        // two JSON documents under `$HOME`, parsed and rewritten. A failure here is
        // logged by the writers and does not fail the restore — the archive's own
        // copies are already in place, and a `pi` typed before the next launch
        // reads those.
        host.launchFileLock.withLock {
            withContext(Dispatchers.IO) {
                val restored = settingsStore.profiles.snapshots.value
                val routes = profileRoutes(restored)
                runCatching {
                    writeModelsJson(restored, routes.entries)
                    writePiDefaults(settingsStore.read().withProfile(restored.activeProfile), routes.route(restored.activeProfile)?.provider)
                }.onFailure { Log.w(TAG, "Could not project the restored profiles", it) }
            }
        }
        Unit
    }

    /**
     * Re-reads the pin set.
     *
     * The preferences instance is shared with the write path, so its values are
     * already the restored ones; the flow beside it is a copy taken at
     * construction, and the drawer reads *that*.
     */
    private fun reloadPinnedSessions() {
        _pinnedSessions.value = pinnedPrefs.getStringSet(KEY_PINNED, emptySet()).orEmpty().toSet()
    }

    private fun profileRoutes(snapshot: PiConfigSnapshot): ProfileRoutes = prepareProfileRoutes(
        env.prefix,
        File(env.piConfigDir, CATALOGUE_STORE_NAME),
        snapshot,
    )

    private fun writeModelsJson(snapshot: PiConfigSnapshot, entries: Map<String, JsonObject>) {
        val file = File(env.home, CustomEndpoint.MODELS_FILE_RELATIVE)
        try {
            val existing = if (file.isFile) Json.parseToJsonElement(file.readText()) as JsonObject
                else JsonObject(emptyMap())
            val cleaned = withdrawNativeProjection(existing, snapshot)
            val projected = modelsJsonWith(cleaned, entries)
            if (projected != existing) replaceConfigFile(file, projected.toString())
        } catch (error: Exception) {
            throw PiRpcException(stringsFor(settingsStore.read().language).settings.modelsJsonWriteFailed, error)
        }
    }

    /**
     * Merges PiKit's tool list, the active profile's defaults and the bundled
     * web extension into pi's own settings file.
     *
     * `--tools` cannot do the first: pi applies it as a strict allowlist to built-in,
     * extension and SDK tools alike, so every tool an installed extension registered
     * was dropped before the model could call it. `defaultTools` selects the
     * *built-in* tools enabled at startup and leaves extension and SDK tools enabled
     * — which is what the terminal tab has always had, because running `pi` by hand
     * passes no `--tools` at all.
     *
     * Merged rather than replaced: this file is pi's own and already holds whatever
     * else the user has set. Only the keys [settingsWithPiDefaults] names are touched.
     *
     * Failure is logged and ignored, like the `models.json` writer: without this
     * file the agent still starts, minus `grep`, `find` and `ls`.
     */
    private fun writePiDefaults(settings: PiSettings, defaultProvider: String?) {
        val target = File(env.home, TOOL_DEFAULTS_FILE_RELATIVE)
        runCatching {
            val existing = if (target.isFile) {
                Json.parseToJsonElement(target.readText()) as? JsonObject ?: JsonObject(emptyMap())
            } else {
                JsonObject(emptyMap())
            }
            val document = settingsWithPiDefaults(
                existing = existing,
                tools = PiLaunchOptions.DEFAULT_TOOLS,
                settings = settings,
                bundledExtension = env.webExtension,
                retiredExtensionPath = File(env.prefix, "lib/node_modules/pikit-extensions").absolutePath,
                defaultProvider = defaultProvider,
            )
            val encoded = Json.encodeToString(JsonObject.serializer(), document)
            // Rewritten only when it differs, so an unchanged file keeps its
            // timestamp and pi's own settings writes are not raced.
            if (!target.isFile || target.readText() != encoded) {
                replaceConfigFile(target, encoded)
                Log.i(
                    TAG,
                    "wrote $TOOL_DEFAULTS_FILE_RELATIVE defaultTools=${PiLaunchOptions.DEFAULT_TOOLS} " +
                        "defaultProvider=${defaultProvider.orEmpty()}",
                )
            }
        }.onFailure { Log.w(TAG, "could not write $TOOL_DEFAULTS_FILE_RELATIVE", it) }
    }

    /**
     * Writes the rendered chat prompt for the child that is about to read it.
     *
     * The stored value is the template (`{{time}}` and all); rendering here is what
     * makes the placeholder's position meaningful and keeps the editor from having to
     * show a timestamp that would be stale by the next launch. A failure is not
     * swallowed: without the file `--system-prompt` would name nothing, so the launch
     * must fail rather than quietly fall back to pi's coding-assistant prompt.
     */
    private fun writeChatPrompt(): File {
        val target = File(env.home, CHAT_PROMPT_FILE_RELATIVE)
        target.parentFile?.mkdirs()
        // The conversation's own preset, not the settings page's one document: chat
        // mode's prompt is a persona the reader chose for *this* conversation, and the
        // launch is where that choice becomes the leading context.
        val preset = chatPreset()
        val rendered = renderChatSystemPrompt(
            template = preset.prompt,
            now = Instant.now(),
            zone = ZoneId.systemDefault(),
        )
        // pi reads an empty custom prompt as "none" and falls back to its coding
        // preamble. The extension is what turns an empty document into "no system
        // prompt"; this one space is the fallback for a launch whose extension could
        // not be loaded, so the failure lands on a blank prompt plus pi's cwd block
        // rather than on the coding prompt this mode exists to remove.
        target.writeText(rendered.ifBlank { " " })
        Log.i(TAG, "wrote $CHAT_PROMPT_FILE_RELATIVE for chat mode (preset ${preset.id})")
        return target
    }

    companion object {
        private const val TAG = "PiKit"

        /**
         * Where pi reads its own settings, relative to `$HOME`.
         *
         * pi uses `PI_CODING_AGENT_DIR` when it is set, and PiKit does not set it,
         * so this is `$HOME/.pi/agent/settings.json`.
         */
        private const val TOOL_DEFAULTS_FILE_RELATIVE = ".pi/agent/settings.json"

        /**
         * The chat prompt's file, beside pi's permission file in `.pikit`.
         *
         * Outside `$HOME/.pi/agent` because it is PiKit's document rather than pi's
         * settings: a backup or a restore of pi's own directory must not lose it, and
         * the path is passed to the child only for a chat launch.
         */
        private const val CHAT_PROMPT_FILE_RELATIVE = ".pikit/chat-system-prompt.txt"

        /**
         * The extension staged by the runtime image that makes the chat prompt exact.
         *
         * `share/pikit` is the image's app-owned directory; the file is loaded by an
         * explicit `--extension`, not discovery. See [PiLaunchOptions.chatPromptExtension].
         */
        internal const val CHAT_PROMPT_EXTENSION_SOURCE = "share/pikit/pikit-chat-prompt.ts"

        /** Long enough that typing a model id does not restart per keystroke. */
        private const val SETTINGS_RESTART_DEBOUNCE_MS = 1_500L

        /**
         * How often a deferred restart checks whether the running turn has settled.
         *
         * Short enough that a restart lands the moment the answer stops, long
         * enough that a turn of several minutes does not spin the coroutine.
         */
        private const val SETTINGS_RESTART_POLL_MS = 500L

        /** Preferred SharedPreferences file for the pin set. */
        private const val KEY_PINNED = "pinned"

        /** The catalogue refresh's own preference file, key and window. */
        private const val KEY_CATALOGUE_DUE_AT = "due_at"

        /**
         * How long a successful catalogue refresh is trusted.
         *
         * Four hours, and the number is pi's own: `REMOTE_CATALOG_REFRESH_INTERVAL_MS` in
         * `remote-catalog-provider.js`, the window inside which pi will not even
         * revalidate a stored catalogue. This app overrides that window when it asks —
         * `pi update --models` runs `refresh` with `force: true`, which skips the test —
         * so the app has to keep the window itself or every launch re-downloads all
         * thirty-two providers' catalogues.
         *
         * The history is worth keeping, because both ends of it were reports:
         *
         *  * Six hours, chosen when the agent itself was launched `--offline` and the
         *    only question was how much radio time a phone should spend. The report that
         *    came back was the other side of it — "some new models cannot be used, and pi
         *    and its extensions are never updated in time" — and a model released this
         *    morning is exactly the case a window defeats.
         *  * Zero, which fixed that by refreshing on every launch. It works, and it is
         *    what shipped: `pi update --models` was a no-op for every provider but the
         *    profile's, so "every launch" cost one small HTTPS request. With the refresh
         *    covering all thirty-two providers (`catalogueRefreshEnvironment`) the same
         *    policy is ~757 KB per launch, which is not a launch-path cost on a phone.
         *
         * Four hours is the reconciliation: pi's own freshness window, honoured rather
         * than bypassed, with the maintenance page's own button ([requestCatalogueRefresh])
         * as the manual override for anyone who wants a model released an hour ago.
         *
         * There is deliberately no button and no code path that updates *pi itself* on
         * the device. The runtime's packages — pi included — are inputs of the image the
         * APK carries (`tools/build-runtime-image.py`), and rewriting pi inside a live
         * install produced the failure this policy replaced: a half-completed
         * `npm install -g`, thrown away by the user when it looked stuck, left a tree
         * whose `jiti` was missing, and since 0.86.x the bundled TypeScript guard
         * extension is loaded *through* `jiti`, the whole agent then refused to start
         * (`pi exited with code 1`, `Cannot find module 'jiti'`). A version that ships in
         * the image cannot be half-installed.
         *
         * A *failure* backs off further, to [CATALOGUE_RETRY_MS]: that is what keeps a
         * phone with no network from paying the attempt at every launch.
         *
         * A window is only safe because "changed" is decided by [catalogueRevision] rather
         * than by the store file, which every refresh rewrites: a due refresh that
         * restarts the agent is a loop, and the loop is what the reader saw as the status
         * line flickering between starting and ready. See
         * [PiAgentSession.refreshCatalogueIfStale].
         */
        internal const val CATALOGUE_REFRESH_WINDOW_MS = 4 * 60 * 60 * 1000L

        /**
         * How long after a *failed* refresh the next attempt waits.
         *
         * A phone with no network must not pay a 20 s attempt on every start, and
         * the catalogue the app already has is the one pi would have used anyway —
         * so a failure buys half an hour of quiet, while a success does not delay
         * the next launch at all.
         */
        internal const val CATALOGUE_RETRY_MS = 30 * 60 * 1000L

        /**
         * pi's catalogue cache, next to the `models.json` both writers use.
         *
         * `internal` because the catalogue probe (`ModelDiscoveryClient.catalogueModelIds`)
         * has to copy it into the scratch agent directory it gives pi: it is the
         * network-updated half of the catalogue, and a probe without it would answer "pi
         * has never heard of it" for every model pi.dev has added since this bundle was
         * built.
         */
        internal const val CATALOGUE_STORE_NAME = "models-store.json"

        /**
         * pi entry ids are eight hex characters.
         */
        private val ENTRY_ID_ALPHABET = ('0'..'9') + ('a'..'f')

        /** How much of a session file's tail to read when finding its last id. */
        private const val SESSION_TAIL_BYTES = 64 * 1024L

        /** Where the session the user is in is remembered across process death. */
        private const val KEY_LAST_SESSION = "last_session"

        /**
         * How long after an unexpected exit the agent comes back on its own.
         *
         * Long enough that a crash loop cannot spin, short enough that a
         * background kill while the user is typing does not leave them staring
         * at a dead composer until they notice Retry.
         */
        private const val AUTO_RESTART_DELAY_MS = 1_500L

        /**
         * Safety net on the turn's wake lock, so a missing `agent_settled` cannot
         * hold the CPU awake forever. Six hours: longer than any real answer, and
         * the lock is re-acquired on each `agent_start`.
         */
        private const val TURN_WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        private val ENVELOPE_ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
    }
}

/**
 * The user's own pi settings with PiKit's built-in tool list unioned in.
 *
 * Top-level and free of Android types on purpose, the way `ConversationReducer`
 * is: this is the rule that decides which tools the model can see, so it is
 * checked on the JVM rather than through a device.
 */
internal fun settingsWithDefaultTools(existing: JsonObject, tools: List<String>): JsonObject {
    val wanted = (existing["defaultTools"] as? JsonArray).orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .plus(tools)
        .distinct()
    return JsonObject(existing + ("defaultTools" to JsonArray(wanted.map { JsonPrimitive(it) })))
}

/**
 * The `retry` object written into pi's own settings — see [settingsWithPiDefaults]
 * for the arithmetic that picked the numbers.
 *
 * A `JsonObject` rather than five named constants: the merge writes the whole
 * object under one key, and a reader wanting one field back has to parse it
 * anyway. `retry.provider.*` is deliberately absent — pi's own advice for those
 * keys is to leave them, and a provider-level retry would only delay the
 * agent-level one this object exists to tune.
 */
internal val RETRY_PATIENCE = JsonObject(
    mapOf(
        "enabled" to JsonPrimitive(true),
        "maxRetries" to JsonPrimitive(8),
        "baseDelayMs" to JsonPrimitive(3_000),
    ),
)

/**
 * pi's own settings with PiKit's tool list, the active profile's defaults and the
 * bundled web extension merged in.
 *
 * `defaultProvider`/`defaultModel` are pi's keys for what a `pi` started without
 * `--provider`/`--model` should answer with (`dist/core/settings-manager.js:461-474`,
 * read by `dist/core/sdk.js:101-103`). PiKit gives the *agent* those two on the
 * command line, so the chat page worked while a `pi` typed in the terminal tab
 * reported no model at all: it had no argv and no defaults to fall back on.
 *
 * `packages` is where pi keeps the package sources it loads extensions from, and a
 * plain absolute path is pi's documented *local path* source. The bundled
 * `pikit-web` extension lives under `$PREFIX` rather than being installed, so
 * registering it here is what gives a hand-run `pi` the same extension the app's
 * agent has. Only a missing entry is added: an entry the user added — including one
 * for this very path, at some other spelling — is never removed or rewritten.
 *
 * `retiredExtensionPath` is the one exception, and it is a migration rather than a
 * user setting: releases before this one registered the vendored `pi-web-access`
 * package at `$PREFIX/lib/node_modules/pikit-extensions`, and that package no
 * longer exists. Leaving the dead path in `packages` would make every launch warn
 * about a package that cannot load, so exactly that one string is dropped.
 *
 * `retry` makes the agent-level retry outlast a network change. pi retries
 * transient provider failures (its own pattern list: overload, rate limit, 5xx,
 * and the connection family — `fetch failed`, `getaddrinfo`, `ECONNRESET`), but
 * the defaults are three attempts on a 2 s base delay, so the patience runs out
 * in about fourteen seconds. Switching Wi-Fi to mobile takes longer than that:
 * the streaming answer died mid-turn, the run landed on `Failed`, and the reader
 * had to re-send by hand. Eight attempts on a 3 s base cover roughly four and a
 * half minutes (3 s, 6 s, 12 s, 24 s, 48 s, then the 60 s ceiling for the rest),
 * and pi announces every attempt as `auto_retry_start`, which the reducer shows
 * as a notice — the wait is visible rather than a dead composer.
 *
 * Merged, never replaced: this file is pi's and already holds whatever else the
 * user set. The two model defaults are written only when [PiSettings.isConfigured],
 * because a half-filled profile would make a hand-run pi fall back to an unrelated
 * provider instead of saying that nothing is configured.
 */
internal fun settingsWithPiDefaults(
    existing: JsonObject,
    tools: List<String>,
    settings: PiSettings,
    bundledExtension: BundledExtension? = null,
    retiredExtensionPath: String? = null,
    /**
     * The provider id the **launch** would use ([ProfileAddressing.runtimeProviderId]),
     * which is not the provider's own id for a relay or for a second profile of one
     * provider. A `pi` typed in the terminal reads this key and resolves it against
     * `models.json`, where the id that exists is this one.
     */
    defaultProvider: String? = settings.provider?.id,
): JsonObject {
    var merged = settingsWithDefaultTools(existing, tools)

    if ("retry" !in merged) merged = JsonObject(merged + ("retry" to RETRY_PATIENCE))

    // A `packages` key that is not an array is not this function's to reinterpret:
    // the write is skipped, the same way an unparseable `models.json` is left alone.
    // A user whose own entry names a *different* source is untouched either way —
    // this only ever removes the retired path and appends the bundled directory.
    val packages = existing["packages"]
    // The guard narrows `packages` to "null, or an array", so the two reads below need
    // no cast of their own — and a cast there is a second place the rule is written.
    if (packages == null || packages is JsonArray) {
        val current = packages.orEmpty()
        val kept = if (retiredExtensionPath.isNullOrBlank()) {
            current
        } else {
            current.filterNot { (it as? JsonPrimitive)?.contentOrNull == retiredExtensionPath }
        }
        val wanted = if (bundledExtension != null) {
            val path = bundledExtension.directory.absolutePath
            if (kept.any { (it as? JsonPrimitive)?.contentOrNull == path }) kept else kept + JsonPrimitive(path)
        } else {
            kept
        }
        if (wanted != current) {
            merged = JsonObject(merged + ("packages" to JsonArray(wanted)))
        }
    }

    if (!settings.isConfigured) return merged
    val provider = defaultProvider?.takeIf { it.isNotBlank() } ?: return merged
    merged = JsonObject(merged + ("defaultProvider" to JsonPrimitive(provider)))
    merged = JsonObject(merged + ("defaultModel" to JsonPrimitive(settings.modelId)))
    if (settings.thinkingLevel.isNotBlank()) {
        merged = JsonObject(merged + ("defaultThinkingLevel" to JsonPrimitive(settings.thinkingLevel)))
    }
    return merged
}

/**
 * Whether [definitions] carry anything PiKit would *write* into a `models.json`
 * that does not exist yet.
 *
 * Withdrawals are deliberately absent: an id in [ModelDefinitions.ours], or a
 * [ModelDefinitions.writtenBaseUrls] with a blank [ModelDefinitions.baseUrl], are
 * statements about a file that is already there, and conjuring a file to take
 * something back out of it is nonsense. What *is* present is the three things
 * the writer puts in: `models` entries, `modelOverrides` entries, and the
 * provider-level `baseUrl` that points a built-in provider at a relay.
 *
 * Checking only `wanted` used to be enough while a custom endpoint was the only
 * reason to create the file — `customInUse` guards that separately. Once a
 * built-in provider gained an endpoint field, the same check started dropping
 * that override on a fresh install: no `models.json`, no `baseUrl`, and pi
 * requested the provider's official address and answered `401` for a key that
 * belonged to the relay.
 */
internal fun hasModelsJsonEntries(definitions: Map<String, ModelDefinitions>): Boolean =
    definitions.values.any { it.wanted.isNotEmpty() || it.overrides.isNotEmpty() || it.baseUrl.isNotEmpty() }

/**
 * `models.json` with PiKit's own entries merged in.
 *
 * Pure, and next to [settingsWithDefaultTools] for the same reason: this is another
 * project's config format, it holds the user's own entries, and getting it wrong
 * loses something the app cannot see.
 *
 * ## One mechanism, not two
 *
 * pi accepts a statement about a model in two ways, and only one of them is any use to
 * this app:
 *
 *  * a `modelOverrides` entry **merges** into the model pi already resolved, field by
 *    field (`applyModelOverride` in `provider-composer.js`), so everything the entry
 *    does not name — the context window, the cost, `thinkingLevelMap`, the reasoning
 *    flag — is left exactly as pi catalogued it. It is read only for an id pi resolved
 *    from its *own* catalogue: `composeModelProvider`'s model list is what
 *    `applyModelOverride` maps over, so an id pi does not know never sees one.
 *  * a `models` entry **replaces** pi's own entry for that id outright
 *    (`modelFromJson`), and anything the entry does not name falls back to pi's
 *    hard-coded defaults. Writing one for a model pi knows therefore downgrades it —
 *    reported from a device as "context 128K, max-out 16.4K" for a `deepseek-flash`
 *    that pi's own catalogue gives 1M and 384K — and writing one for a model pi does
 *    not know is the *only* mechanism that reaches it at all, because
 *    `buildFallbackModel` otherwise copies the provider's default model and no
 *    override is consulted for that copy.
 *
 * PiKit therefore writes `models` entries and nothing else, and only for the ids
 * [modelDefinitions] vouches for — the ones a catalogue check found **absent** from pi's
 * own catalogue. A model pi's catalogue contains is left entirely to pi: its window, its
 * max-out, its cost and its `input` are pi's own, which is what "the catalogue is the
 * authority" has to mean if an entry may replace it. The check is asked of a process that
 * cannot see this file (`ModelDiscoveryClient.catalogueProbe`), because an entry written
 * here is itself what pi would answer with.
 *
 * ## The withdrawal, which is a migration and a policy
 *
 * Every entry either build wrote is withdrawn as soon as it should not be there: for an
 * id the profile no longer has settings for, for an id the profile no longer offers, and
 * for an id whose catalogue check no longer describes the world
 * ([ModelDefinitions]). Two ids are recognised as the app's own — the accumulated record
 * in the profile ([ModelProfile.writtenModels]), and, for what older builds left behind,
 * the exact factless shape ([isPikitModelDefinition]).
 *
 * The `modelOverrides` half of that is now a pure migration: this app writes no override
 * at all, so the only ones it ever wrote are the `input: ["text", "image"]` declarations
 * of a previous build, and each is taken back out here — the key with it when it was the
 * entry's only field. Keys PiKit never wrote are left alone.
 */
internal fun modelsJsonWith(
    existing: JsonObject,
    /**
     * Every provider entry this app owns, by the id the profile is launched as — see
     * [ProfileAddressing.runtimeProviderId]. A `pikit-` id that is not in here is
     * withdrawn.
     *
     * One entry per **saved** profile, not one per active profile, and that is the whole
     * point of the parameter: pi reads this file once, at startup, and a profile that is
     * not in it is a model `set_model` answers `Model not found` for.
     */
    entries: Map<String, JsonObject> = emptyMap(),
    /**
     * Per provider, PiKit's own `models` entries: the ids a catalogue check vouched for,
     * and every id the app has ever written one for. Empty for a provider the app has
     * nothing to say about — a profile registered whole lives in [entries] instead.
     * See [modelDefinitions].
     */
    definitions: Map<String, ModelDefinitions> = emptyMap(),
): JsonObject {
    val providers = LinkedHashMap<String, JsonElement>()
    (existing["providers"] as? JsonObject)?.forEach { (id, entry) -> providers[id] = entry }

    // Only PiKit's own ids are ever withdrawn, and a `pikit-` id that is not wanted any
    // more is exactly the case this is for: the single `pikit-custom` endpoint an older
    // build wrote, and every profile the user has since deleted. A user's provider, and
    // any key inside one, is not this app's to remove.
    for (id in providers.keys.toList()) {
        if (id !in entries && isAppProvider(id, providers[id] as? JsonObject)) providers.remove(id)
    }
    entries.forEach { (id, entry) -> providers[id] = entry }

    for ((providerId, definition) in definitions) {
        val current = providers[providerId] as? JsonObject
        val wanted = definition.wanted
        val wantedIds = wanted.map { it.id }
        val ours = definition.ours

        // A `models` or `modelOverrides` key that is not of the type pi's schema wants
        // is not this function's to reinterpret: either it is left exactly as the user
        // wrote it, the same way an unparseable `models.json` is left alone entirely.
        val rawModels = current?.get("models")
        val rawOverrides = current?.get("modelOverrides")

        // pi re-reads the whole array, so every entry that is not one of PiKit's
        // own is carried over element-wise, order included — including an element
        // that is not an object at all. An entry is PiKit's when its id is one the
        // profile records having written ([ModelDefinitions.ours]) or when it has the
        // fixed shape [isPikitModelDefinition] recognises — the latter being the only
        // way to find what a build before that record existed left behind. Ours are
        // carried over only while they are still wanted, which is what makes an entry
        // withdraw itself when the model's settings go away, when the row leaves the
        // model list, or when the catalogue the check was made against has moved.
        val keep = (rawModels as? JsonArray).orEmpty().filterNot { element ->
            if (element !is JsonObject) return@filterNot false
            val id = element["id"]?.jsonPrimitive?.contentOrNull
            id != null && id !in wantedIds && (id in ours || isPikitModelDefinition(element))
        }
        // The entry each wanted id needs, added to whatever the user's own array already
        // holds. An id that is wanted but has no entry — the first save after the id was
        // added — is the one this adds.
        val present = keep.mapNotNull { element ->
            (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }.toSet()
        val models = keep + wanted.filterNot { it.id in present }.map(::pikitModelDefinition)

        val overrides = LinkedHashMap<String, JsonElement>()
        (rawOverrides as? JsonObject)?.forEach { (id, entry) -> overrides[id] = entry }
        // Two things happen here, in this order: the previous build's `input` declaration
        // is taken back out (this app wrote overrides once, for images only, and the
        // comment above says what that was), and this app's own override for an id is put
        // in — merged field by field over whatever the user wrote by hand for the same id,
        // so a key of theirs is never dropped.
        for (id in overrides.keys.toList()) {
            val entry = overrides[id] as? JsonObject ?: continue
            // An override the user is declaring right now is not the old build's, whatever
            // its `input` says. Everything else that carries exactly `["text", "image"]` is
            // the shape that build produced.
            if (id in definition.overrides) continue
            val input = entry["input"] as? JsonArray ?: continue
            val isPikits = input.mapNotNull { it.jsonPrimitive.contentOrNull } ==
                listOf("text", "image")
            if (!isPikits) continue
            val stripped = LinkedHashMap<String, JsonElement>(entry - "input")
            val remaining = stripPikitImageLimits(stripped["inputLimits"])
            if (remaining == null) stripped.remove("inputLimits") else stripped["inputLimits"] = remaining
            if (stripped.isEmpty()) overrides.remove(id) else overrides[id] = JsonObject(stripped)
        }
        for ((id, settings) in definition.overrides) {
            overrides[id] = modelOverrideFor(settings, overrides[id] as? JsonObject)
        }

        val next = LinkedHashMap<String, JsonElement>(current.orEmpty())
        // The endpoint override is PiKit's statement about where this provider lives.
        // A non-empty field rewrites every model's `baseUrl`. An empty one withdraws a
        // previous override *of this app's* — matched against `writtenBaseUrls`, so a
        // `baseUrl` the user wrote into `models.json` by hand survives a profile that
        // has never named an endpoint. Leaving a stale override in place after the
        // field was cleared would keep routing the provider through a proxy the user
        // had turned off.
        val storedBaseUrl = (next["baseUrl"] as? JsonPrimitive)?.contentOrNull
        when {
            definition.baseUrl.isNotEmpty() ->
                next["baseUrl"] = JsonPrimitive(definition.baseUrl)

            definition.writtenBaseUrls.isNotEmpty() &&
                storedBaseUrl != null &&
                storedBaseUrl in definition.writtenBaseUrls ->
                next.remove("baseUrl")
        }
        // A `models` value that is neither absent nor an array is left exactly as the
        // user wrote it, definitions included: pi rejects the document on its schema
        // check either way, and replacing what the app cannot read is not its job.
        if (rawModels == null || rawModels is JsonArray) {
            if (models.isEmpty()) next.remove("models") else next["models"] = JsonArray(models)
        }
        if (rawOverrides == null || rawOverrides is JsonObject) {
            if (overrides.isEmpty()) next.remove("modelOverrides") else {
                next["modelOverrides"] = JsonObject(overrides)
            }
        }
        if (next.isEmpty()) providers.remove(providerId) else providers[providerId] = JsonObject(next)
    }

    return JsonObject(existing + ("providers" to JsonObject(providers)))
}

/** `["text", "image"]`, spelled once so the writer and the recogniser agree. */
private fun imageInputArray(): JsonArray =
    JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("image")))

/**
 * The `inputLimits` object every entry PiKit writes carries.
 *
 * pi merges this field over whatever the model already had (`mergeInputLimits`), so a
 * resize block naming only the two edges keeps a provider's own `maxBytes` and
 * `jpegQuality`: this app has no opinion about either. The resize is the point — without
 * it pi rescales above its own 2000, and a picture that reached the model at 2000 is not
 * the 3000 one the transcript draws.
 */
internal fun imageLimitObject(): JsonObject = buildJsonObject {
    put(
        "images",
        buildJsonObject {
            put(
                "resize",
                buildJsonObject {
                    put("maxWidth", PIKIT_IMAGE_MAX_EDGE)
                    put("maxHeight", PIKIT_IMAGE_MAX_EDGE)
                },
            )
        },
    )
}

/** [imageLimitObject] merged over the `inputLimits` the user may have written by hand. */
private fun withImageLimit(userWritten: JsonObject?): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(userWritten.orEmpty())
    val images = LinkedHashMap<String, JsonElement>(
        (userWritten?.get("images") as? JsonObject).orEmpty(),
    )
    val resize = LinkedHashMap<String, JsonElement>(
        (images["resize"] as? JsonObject).orEmpty(),
    )
    resize["maxWidth"] = JsonPrimitive(PIKIT_IMAGE_MAX_EDGE)
    resize["maxHeight"] = JsonPrimitive(PIKIT_IMAGE_MAX_EDGE)
    images["resize"] = JsonObject(resize)
    merged["images"] = JsonObject(images)
    return JsonObject(merged)
}

/**
 * [limits] with this app's two edges taken out, or null when nothing else was in it.
 *
 * The withdrawal path has to tell an override this app wrote from one the user wrote, now
 * that every entry carries `inputLimits`. An entry PiKit wrote is exactly the image ceiling
 * plus the `input` it always wrote, so removing both is what makes it empty and removable —
 * and a limit the user added (`maxBytes`, a per-message count) keeps the entry, because
 * that entry is still theirs. A *hand-written* `maxWidth` of 3000 is indistinguishable
 * from this app's, which is the one cost of not marking our own numbers.
 */
private fun stripPikitImageLimits(limits: JsonElement?): JsonObject? {
    val root = limits as? JsonObject ?: return null
    val rest = LinkedHashMap<String, JsonElement>(root)
    val images = LinkedHashMap<String, JsonElement>((root["images"] as? JsonObject).orEmpty())
    val resize = LinkedHashMap<String, JsonElement>((images["resize"] as? JsonObject).orEmpty())
    if ((resize["maxWidth"] as? JsonPrimitive)?.longOrNull == PIKIT_IMAGE_MAX_EDGE.toLong()) {
        resize.remove("maxWidth")
    }
    if ((resize["maxHeight"] as? JsonPrimitive)?.longOrNull == PIKIT_IMAGE_MAX_EDGE.toLong()) {
        resize.remove("maxHeight")
    }
    if (resize.isEmpty()) images.remove("resize") else images["resize"] = JsonObject(resize)
    if (images.isEmpty()) rest.remove("images") else rest["images"] = JsonObject(images)
    return if (rest.isEmpty()) null else JsonObject(rest)
}

/** `["text"]`: what pi's schema defaults to, written out so an entry reads on its own. */
private fun textInputArray(): JsonArray = JsonArray(listOf(JsonPrimitive("text")))

/**
 * One model id PiKit has to write a `models` entry for, and what that entry says.
 *
 * @param facts the fields of the model pi resolves an unknown id to
 *   ([modelDefinitionFacts]) — the values the entry has to name so that declaring
 *   something about the model does not silently change everything else about it.
 * @param settings this app's own three statements, on top of [facts]: see [ModelSettings].
 *   A null number means the entry does not name that key at all, which is not the same as
 *   naming pi's default.
 */
internal data class ModelEntry(
    val id: String,
    val facts: JsonObject = JsonObject(emptyMap()),
    val settings: ModelSettings = ModelSettings(),
)

/**
 * PiKit's own `models` entries for one provider.
 *
 * @param wanted the entries that have to be in pi's file, each with the facts it names.
 *   Only for ids pi's own catalogue does *not* contain — see [overrides] for the other
 *   half.
 * @param overrides statements about ids pi's catalogue **does** contain, by id. These are
 *   `modelOverrides` entries, which pi *merges* into the model it resolved, so naming only
 *   the three things the user changed leaves the window, the cost, `thinkingLevelMap` and
 *   the reasoning flag exactly as pi catalogued them. The alternative — a `models` entry
 *   for an id pi knows — replaces all of that with pi's hard-coded defaults.
 * @param ours every id PiKit may have written a `models` entry for, whether or not it is
 *   still wanted. This is what makes an entry withdrawable without recognising it by shape:
 *   an entry that names the fallback's own facts has no fixed shape left to match, so the
 *   app's record of the ids it wrote ([ModelProfile.writtenModels]) is the marker.
 *   Overrides need no equivalent, because one is recognised by the `input` value the build
 *   that wrote it always produced (see [modelsJsonWith]).
 * @param baseUrl the profile's endpoint override, already normalised. Non-empty rewrites
 *   every model of this provider to that URL (`applyModelsJson`:
 *   `config.baseUrl ?? model.baseUrl`) — the supported way to put a proxy in front of a
 *   built-in provider.
 * @param writtenBaseUrls every spelling of the override PiKit may have written, which is
 *   what an empty [baseUrl] withdraws. See [ModelProfile.writtenBaseUrl] for why
 *   withdrawal is keyed on the written value rather than on the blank field. It is a list
 *   rather than one string because the value in pi's file was produced by the spelling
 *   rule of the build that wrote it: an `anthropic-messages` endpoint used to be completed
 *   with `/v1`, and the file a user upgraded over still holds that shape. Each candidate
 *   is compared exactly, so a `baseUrl` the user wrote by hand survives.
 *   A custom endpoint never comes through here: its whole provider object is rebuilt by
 *   [CustomEndpoint.providerObject].
 */
internal data class ModelDefinitions(
    val wanted: List<ModelEntry> = emptyList(),
    val ours: List<String> = emptyList(),
    val overrides: Map<String, ModelSettings> = emptyMap(),
    val baseUrl: String = "",
    val writtenBaseUrls: List<String> = emptyList(),
)

/**
 * PiKit's own entries for one provider — `models` for what pi does not know, `modelOverrides`
 * for what it does.
 *
 * The active provider is always in the answer, even with nothing wanted — that is not a
 * convenience but the withdrawal: an id that has left the profile, or one whose entry a
 * previous build wrote, is only found by visiting the provider in pi's file.
 *
 * ## How an id is routed, and what a moved catalogue costs
 *
 * [ModelProfile.knownCatalogueIds] is the recorded check's answer: an id in it is an
 * override (pi resolved it, so a definition would replace pi's own entry), and an id
 * outside it is a definition (pi did not, so an override would never be consulted).
 * While the check's [catalogueBasis] still describes the world, that is the whole rule.
 *
 * Once the basis has moved, the record may be wrong in the one direction that matters —
 * an id it calls unknown may be one pi has since started cataloguing — so "known" is
 * widened with [currentCatalogueIds], the store on disk the refresh just rewrote. Those
 * ids are routed to `modelOverrides`, which merges. Ids the store does not hold keep the
 * record's verdict and stay definitions.
 *
 * The previous rule emptied every definition until the model page was visited again. It
 * prevented the unrecoverable mistake (a `models` entry over a model pi now knows,
 * window, cost and thinking map included) by making a catalogue refresh silently return
 * every declared number to pi's default while the form still showed the user's values.
 * Widening the routing keeps the protection and keeps the declarations.
 *
 * What may be *withdrawn* is deliberately wider than what may be written: [ours] is
 * reported whatever the basis says, because an entry whose routing has just changed is
 * precisely one whose old form must come out of pi's file.
 *
 * An id that is no longer in [ModelProfile.modelSettings] — the row was removed, or the
 * user put everything back to what pi would have used anyway — is not written either way,
 * so the two lists cannot drift into a statement about a model the profile says nothing
 * about.
 *
 * A custom endpoint is not here at all: its whole provider object, `models` array
 * included, is rebuilt by [CustomEndpoint.providerObject] on every launch, so there is
 * nothing to merge and nothing to withdraw.
 */
internal fun modelDefinitions(
    provider: PiProvider?,
    profile: ModelProfile?,
    basis: String,
    /**
     * The ids pi's store holds *right now* — the freshest "pi knows this id"
     * answer available without a process. Used to widen the recorded check's
     * routing when the two disagree; see the body.
     */
    currentCatalogueIds: Set<String> = emptySet(),
    /**
     * The wire protocol this built-in provider's models speak, when the catalogue on
     * disk says so unambiguously — see `cataloguedApi`.
     *
     * It is needed for exactly one decision, and it is the one that broke: the shape
     * of the endpoint override. An `anthropic-messages` provider must not have `/v1`
     * appended (or kept) in its `baseUrl`, because its client appends `/v1/messages`
     * itself — the reported `404 … Invalid URL (POST /v1/v1/messages)`. Blank means
     * "not known", and the endpoint is then completed for the OpenAI shapes, which is
     * what every release before this one did for every provider.
     */
    api: String = "",
): Map<String, ModelDefinitions> {
    if (provider == null || profile == null) return emptyMap()
    if (provider in PiProvider.needsBaseUrl) return emptyMap()
    val ours = profile.writtenModels
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val declared = profile.selectableModels
        .mapNotNull { id -> profile.modelSettings[id]?.let { id to it } }
    val known = profile.knownCatalogueIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val facts = profile.customModelFacts
    // The facts are the model pi resolves an unknown id to, and without them an entry
    // cannot be named faithfully in every field — an unnamed window falls back to
    // pi's hard-coded 128k. They are therefore preferred, and when they are absent
    // the entry still carries whatever the user typed: a declaration that names the
    // user's own numbers is better than no declaration at all, and the model page
    // withholds the controls whenever the check did not answer, so this refuses
    // nothing the user was able to ask for.
    //
    // Which *mechanism* each id gets is the recorded check's answer while its basis
    // still matches. Once the catalogue has moved, the record may be wrong in the one
    // direction that matters — an id it calls unknown may be one pi has since started
    // cataloguing, and a `models` entry for such an id replaces pi's own window, cost
    // and thinking map. So a moved basis widens "known" to include what the store on
    // disk holds *now* (the refresh that moved the basis rewrote it), and routes those
    // ids to `modelOverrides`, which merges. The alternative — emptying [wanted] until
    // the model page is visited again — is what made a catalogue refresh silently
    // return every declared number to pi's default while the form still showed the
    // user's values.
    val knownNow = if (profile.catalogueBasis == basis) {
        known
    } else {
        known + currentCatalogueIds
    }
    val wanted = declared.filterNot { (id, _) -> id in knownNow }.map { (id, settings) ->
        ModelEntry(id, facts ?: JsonObject(emptyMap()), settings)
    }
    // Every model the profile offers, not only the ones the user changed: the image
    // ceiling is this app's statement about all of them, and an override written only for
    // a changed model would leave pi's own 2000 in place for the rest.
    val overrides = profile.selectableModels
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filter { it in knownNow }
        .associateWith { profile.modelSettings[it] ?: ModelSettings() }
    return mapOf(
        provider.id to ModelDefinitions(
            wanted = wanted,
            ours = ours,
            overrides = overrides,
            // A custom endpoint is filtered out above; this is the built-in case, where a
            // non-empty field is a proxy override and an empty one means "pi's own URL".
            // Normalised here so the file and the model-list probe agree on the path, for
            // the shape *this provider's* client posts to — see [api].
            baseUrl = normalizeApiBaseUrl(profile.baseUrl, api),
            // Two spellings, because the file may hold either. A build before the
            // protocol decided this shape completed an endpoint with `/v1` for every
            // api, so an `anthropic-messages` override already in `models.json` carries
            // one and a withdrawal has to recognise it as this app's. Both are compared
            // exactly: a value the user wrote by hand is not this app's to remove, and
            // matching loosely (ignoring a `/v1`) would take one that differs from ours
            // only by that segment.
            writtenBaseUrls = listOf(api, CustomApi.OPENAI_COMPLETIONS.id)
                .map { normalizeApiBaseUrl(profile.writtenBaseUrl, it) }
                .filter { it.isNotEmpty() }
                .distinct(),
        ),
    )
}

/**
 * A whole provider entry for a built-in provider that a **second** profile names.
 *
 * The first profile of a provider keeps pi's own id, which is what gives it pi's
 * catalogue, its `compat` table and its default endpoint for free. A second profile
 * cannot share that id — one credential and one `baseUrl` per id — so it is registered
 * as a provider of this app's own, and the facts have to be written out:
 *
 *  * `baseUrl` and `api` are provider-level, and the profile's own endpoint wins when it
 *    names one (the proxy case);
 *  * each model's entry is the catalogue's own object for that id, minus the three fields
 *    that describe the *host* rather than the model (`provider`, `api`, `baseUrl`), with
 *    the profile's three settings laid over it — the same rule [pikitModelDefinition]
 *    applies to a custom endpoint, and for the same reason: an entry that names nothing
 *    falls back to pi's hard-coded 128k/16k rather than to the model it shadows.
 *
 * A store that has never been refreshed for this provider leaves nothing to copy, and a
 * profile with no endpoint of its own then has no host to name: null, rather than a
 * provider invented here. See [PiProcessLauncher]'s vocabulary of failures — a wrong
 * entry is a `401` that reads as a bad key, which is worse than a relaunch.
 */
internal fun builtInProviderObject(
    profile: ModelProfile,
    modelFacts: Map<String, JsonObject>,
): JsonObject? {
    val models = profile.selectableModels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (models.isEmpty()) return null
    val known = models.firstNotNullOfOrNull { modelFacts[it] }
    // The protocol first: it decides what shape the endpoint has to be. An
    // `anthropic-messages` entry must not carry a `/v1` — its client appends
    // `/v1/messages` itself, and a completed one is the reported
    // `404 … POST /v1/v1/messages`. See `normalizeApiBaseUrl`.
    val api = known?.apiName().orEmpty()
    val baseUrl = normalizeApiBaseUrl(profile.baseUrl, api).ifBlank { known?.baseUrlName().orEmpty() }
    if (baseUrl.isEmpty() || api.isEmpty()) return null
    return buildJsonObject {
        put("name", profile.displayName)
        put("baseUrl", baseUrl)
        put("api", api)
        put("apiKey", ProfileAddressing.apiKeyReference(profile))
        put(
            "models",
            buildJsonArray {
                for (id in models) {
                    val facts = modelFacts[id]?.let(::withoutHostFields) ?: JsonObject(emptyMap())
                    add(pikitModelDefinition(ModelEntry(id, facts, profile.settingsFor(id))))
                }
            },
        )
    }
}

/** A catalogue model object without the fields that describe its host. */
private fun withoutHostFields(facts: JsonObject): JsonObject =
    JsonObject(facts.filterKeys { it != "provider" && it != "api" && it != "baseUrl" })

/**
 * PiKit's own `models` entry for one model id pi's catalogue does not contain.
 *
 * ## Why a definition, when an override is the cheaper mechanism
 *
 * A `modelOverrides` entry is applied by pi to the models a provider already has
 * (`composeModelProvider`'s `getModels` maps the catalogue through `applyModelOverride`),
 * so an id pi did not resolve from its catalogue never sees one: `buildFallbackModel`
 * copies the provider's default model for an unknown id and the override is not consulted
 * for that copy. A `models` entry is the only thing that puts the id into the catalogue pi
 * builds, and therefore the only mechanism that can say anything at all about such a model.
 *
 * ## The facts come from pi, not from PiKit
 *
 * A declaration must not change anything about the model except what the user asked to
 * change, and `modelFromJson` gives every field the entry does not name a fixed value —
 * `reasoning: false`, `contextWindow: 128000`, `maxTokens: 16384`, a zero cost and no
 * `thinkingLevelMap`. Those are *not* what the model would otherwise have had: without an
 * entry, pi resolves the id through `buildFallbackModel`, which copies the provider's
 * **default** model, and that copy is what the user was already getting. So [ModelEntry.facts]
 * is that model's own fields ([modelDefinitionFacts], read out of pi's `get_state` reply by
 * `ModelDiscoveryClient.catalogueProbe`), and naming them is what keeps the window, the
 * cost, the reasoning flag and the thinking levels exactly as pi resolved them.
 *
 * The three settings are then written *over* those facts, which is why the order matters: a
 * key put twice replaces the first, so pi's account of the model goes in first and this
 * app's statement is what the document ends up saying. A null number means the key is not
 * named at all — pi's fallback value stays — which is a different document from naming
 * pi's default, and the difference is the whole reason both are nullable.
 *
 * `input` is the one field whose *default* here is pi's own text-only value rather than a
 * placeholder of this app's: an entry with no facts comes from a custom endpoint, where
 * there is no fallback and `modelFromJson` would have said `["text"]` anyway.
 */
internal fun pikitModelDefinition(entry: ModelEntry): JsonObject = buildJsonObject {
    val facts = entry.facts
    val settings = entry.settings
    put("id", entry.id)
    put("name", entry.id)
    // pi's defaults for a definition that names nothing, so that the document is complete
    // and readable on its own, then pi's own account of the model over them.
    put("reasoning", true)
    put("input", textInputArray())
    put("contextWindow", PIKIT_MODEL_CONTEXT_WINDOW)
    put("maxTokens", PIKIT_MODEL_MAX_TOKENS)
    facts.forEach { (key, value) -> put(key, value) }
    // The user's three statements, last.
    put(
        "input",
        when (settings.images) {
            true -> imageInputArray()
            false -> textInputArray()
            // Nothing said: the fallback's own capability list, which is what the model
            // would have had without an entry at all.
            null -> facts.inputArray() ?: textInputArray()
        },
    )
    put(
        "contextWindow",
        settings.contextWindow ?: facts.long("contextWindow") ?: PIKIT_MODEL_CONTEXT_WINDOW,
    )
    put("maxTokens", settings.maxTokens ?: facts.long("maxTokens") ?: PIKIT_MODEL_MAX_TOKENS)
    // The image ceiling, last: it is this app's statement about every model it writes an
    // entry for, and without it pi's own resize would still hold pictures above 2000.
    put("inputLimits", imageLimitObject())
}

/** The `input` list [facts] carry, when pi reported one. */
private fun JsonObject.inputArray(): JsonArray? = this["input"] as? JsonArray

/**
 * PiKit's own `modelOverrides` entry for one id pi's catalogue **does** contain.
 *
 * ## Why this is a different mechanism from [pikitModelDefinition]
 *
 * `applyModelOverride` (`provider-composer.js`) spreads the override over the model pi
 * resolved — `contextWindow: override.contextWindow ?? model.contextWindow` and the same
 * for `input`, `reasoning`, `cost`, `thinkingLevelMap` and `maxTokens` — so an entry that
 * names one field changes one field. That is the opposite of `modelFromJson`, which gives
 * every field the entry does not name a fixed value of its own. A `models` entry for a
 * model pi knows therefore *lowers* it: measured from a device as a `deepseek-flash` with a
 * 1M window and a 384K max-out reading back as pi's hard-coded 128K/16.4K. So an id the
 * catalogue contains is declared with this and never with a definition — which is the
 * whole reason `ModelDefinitions` carries two lists.
 *
 * ## What it names
 *
 * Only the three things the page lets the user change, and only the ones the user actually
 * said something about. That is not politeness: naming a field is *authoring* it, so
 * writing `contextWindow` from the catalogue's own value would pin the model to a window
 * that a later pi release could no longer move. An untouched field is left unnamed, which
 * is what makes the entry mean "this, and nothing else".
 *
 * `api` and `baseUrl` are deliberately absent, and their absence is the point of the
 * mechanism: `applyModelOverride` does not read either, so a model declared here keeps the
 * endpoint pi already resolved for the provider. `modelDefinitionFacts` — the other
 * direction of the same copy — *does* carry both, and that is correct there and wrong
 * here: a definition has to stand alone, an override sits on top of something that already
 * says where to send the request.
 *
 * [userWritten] is the override the user may have put in `models.json` by hand for this
 * same id. pi's documentation invites exactly that, and this app's entries are a merge, so
 * a key of theirs that this app does not write is carried over rather than replaced. Their
 * `input`, if they wrote one, *is* replaced — the page's switch is the app's statement about
 * the same field, and two answers to one question is not a merge.
 */
internal fun modelOverrideFor(settings: ModelSettings, userWritten: JsonObject? = null): JsonObject {
    val merged = LinkedHashMap<String, JsonElement>(userWritten.orEmpty())
    when (settings.images) {
        true -> merged["input"] = imageInputArray()
        false -> merged["input"] = textInputArray()
        // Nothing said. An `input` this app could have written earlier is taken back out,
        // so the switch returning to "untouched" really is untouched — and only a value
        // this app produces (`["text","image"]` was the old build's declaration,
        // `["text"]` is the off switch) is eligible, so an `input` the user wrote by hand
        // for this id survives. A statement of *ours* written before the switch went back
        // to the catalogue's value is the one thing that must not be left behind, because
        // it would keep overriding the catalogue in the direction the user just undid.
        null -> {
            val existing = merged["input"] as? JsonArray
            val ours = existing?.mapNotNull { it.jsonPrimitive.contentOrNull } ==
                listOf("text", "image")
            if (ours) merged.remove("input")
        }
    }
    settings.contextWindow?.let { merged["contextWindow"] = JsonPrimitive(it) }
    settings.maxTokens?.let { merged["maxTokens"] = JsonPrimitive(it) }
    // Not conditional on the user having said anything: every model this profile offers
    // gets the same image ceiling, or pi's own 2000 would stand for each model the page
    // was never opened on. `mergeInputLimits` keeps any other limit the user wrote.
    merged["inputLimits"] = withImageLimit(merged["inputLimits"] as? JsonObject)
    return JsonObject(merged)
}

/** One of [facts] as a number, when pi reported it as one. */
private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

/**
 * The fields of a resolved model pi's `models.json` accepts, ready to be named in a
 * definition.
 *
 * `get_state`'s `model` is a `Model`, and `ModelDefinitionSchema` is a narrower object:
 * it takes a subset of the same field names with the same types. Copying the subset is
 * what makes the entry a faithful copy of the fallback rather than a statement about it,
 * and the whitelist is what keeps the two apart — a `provider` or an `id` carried into a
 * definition is a field pi's schema does not know, and a document it rejects takes every
 * provider in the file down with it, the user's own custom endpoint included.
 *
 * Two fields are dropped on purpose rather than by omission:
 *
 *  * `name`, because the entry is named after the id it declares, not after the model the
 *    facts were read from.
 *  * `cost.tiers`, because a tier is a structure whose members pi validates and whose
 *    absence is the state `modelFromJson` produces anyway (a flat per-token cost). Flat
 *    numbers are what the user sees; a tier list copied through the app is a chance to
 *    fail that validation for a figure that a phone screen never shows.
 *
 * `input` is kept: it is the fallback's own capability list, and it is what an entry whose
 * image switch is *off* has to say. Without it the entry would deny image input that the
 * fallback would have granted, which is the one way an "off" switch could make a model
 * worse than saying nothing at all.
 *
 * A null is dropped too: `Optional` in pi's schema means "absent", not "null".
 */
internal fun modelDefinitionFacts(model: JsonObject): JsonObject {
    val facts = LinkedHashMap<String, JsonElement>()
    for (key in DEFINITION_FACT_KEYS) {
        val value = model[key] ?: continue
        if (value is JsonNull) continue
        if (key == "cost") {
            val cost = value as? JsonObject ?: continue
            val flat = cost.filterKeys { it in COST_KEYS }.filterValues { it !is JsonNull }
            if (flat.isNotEmpty()) facts[key] = JsonObject(flat)
            continue
        }
        if (key == "api" || key == "baseUrl") {
            // `String({ minLength: 1 })`: a blank one is a rejected document.
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank()) continue
        }
        if (key == "input") {
            // `Array(Union([Literal("text"), Literal("image")]))`: anything else is a
            // rejected document, and the only source of this value is pi's own model, so
            // a list pi could not read back is not worth carrying.
            val list = value as? JsonArray ?: continue
            val readable = list.all {
                (it as? JsonPrimitive)?.contentOrNull in setOf("text", "image")
            }
            if (!readable) continue
        }
        facts[key] = value
    }
    return JsonObject(facts)
}

/** The `ModelDefinitionSchema` fields [modelDefinitionFacts] will copy, and no others. */
private val DEFINITION_FACT_KEYS = listOf(
    "api",
    "baseUrl",
    "reasoning",
    "thinkingLevelMap",
    "input",
    "cost",
    "contextWindow",
    "maxTokens",
    "samplingParams",
)

/** The per-token numbers of pi's `cost`; `tiers` is dropped, see [modelDefinitionFacts]. */
private val COST_KEYS = listOf("input", "output", "cacheRead", "cacheWrite")

/**
 * Whether [entry] is one of the model registrations PiKit wrote with no facts to name.
 *
 * The shape is exact and distinctive — pi's defaults for a model definition that names
 * neither window, plus the one statement the old image declaration made — so an entry that
 * matches it is, field for field, that statement, and treating it as PiKit's costs nothing:
 * withdrawing it returns the model to what pi resolved for itself rather than removing
 * anything the user said.
 *
 * It is only half of how an entry is recognised as PiKit's, and now the minority half.
 * Since [pikitModelDefinition] names the fallback's own facts and the user's own numbers, an
 * entry it writes has no fixed shape left to match, so the writer also treats an entry as
 * ours when its id is in the profile's own record of the ids it wrote
 * ([ModelProfile.writtenModels]). This function is what still finds the entries older builds
 * left behind, whose ids that record has never heard of.
 */
internal fun isPikitModelDefinition(entry: JsonObject): Boolean {
    val input = entry["input"] as? JsonArray ?: return false
    return (entry["reasoning"] as? JsonPrimitive)?.booleanOrNull == true &&
        input.mapNotNull { it.jsonPrimitive.contentOrNull } == listOf("text", "image") &&
        (entry["contextWindow"] as? JsonPrimitive)?.longOrNull == PIKIT_MODEL_CONTEXT_WINDOW &&
        (entry["maxTokens"] as? JsonPrimitive)?.longOrNull == PIKIT_MODEL_MAX_TOKENS
}

/**
 * The two numbers [pikitModelDefinition] names when neither pi nor the user supplied one.
 *
 * pi's own `modelFromJson` defaults, named here rather than left out so that the written
 * document says what it means: an entry with a blank window field is a 128k model as far as
 * pi is concerned, and a reader of the file should not have to know that. `internal` because
 * the model page shows the same two numbers as the starting point of a custom endpoint's
 * boxes — the form and the document have to agree about what an untouched field means.
 */
internal const val PIKIT_MODEL_CONTEXT_WINDOW = 128_000L
internal const val PIKIT_MODEL_MAX_TOKENS = 16_384L

/**
 * The longest side of a picture this app sends, declared to pi as well as obeyed here.
 *
 * `SendImage.kt` fits every attachment to this edge before the prompt is built, and the
 * same number is written into each model entry's `inputLimits.images.resize`, so pi's own
 * rescale lands on the pass-through branch and the model receives exactly the bytes the
 * app encoded — which is what makes the transcript's viewer and the model's picture one
 * picture. The two halves are pinned equal by a test.
 */
internal const val PIKIT_IMAGE_MAX_EDGE = 3000


/**
 * What a catalogue check was made against, as one comparable string.
 *
 * Three things can move the answer, and all three are read from disk or from the profile
 * rather than remembered: the provider the question was about, pi's own version — its
 * built-in catalogue is compiled into the bundle, so a `pi update` can add models — and the
 * revision of `models-store.json`, which the launch path refreshes from pi.dev
 * ([PiAgentSession.refreshCatalogueIfStale]). A missing store contributes
 * [CATALOGUE_UNREADABLE], which is a value no readable file produces, so a store that
 * appears or disappears is a change.
 *
 * The provider is in here because the answer is per provider, and so are the facts recorded
 * beside it: they are the fields of the model *that* provider's unknown ids resolve to. A
 * profile whose provider was changed would otherwise carry one provider's fallback into an
 * entry written for another, which is a whole model's worth of wrong numbers rather than a
 * missing one. With the provider named, a switch moves the basis, so the entries are
 * withdrawn until the model page checks the new provider.
 *
 * Pure apart from the two file reads, and it takes [TermuxEnv] rather than the session
 * because the model page has to compute the same string when it records a check.
 */
internal fun catalogueBasis(env: TermuxEnv, provider: PiProvider?): String {
    val version = PiInstallation.installedVersion(env).orEmpty()
    val store = File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME)
    // The document, not its bytes: this runs on the launch path and on the model page, and
    // the same store is read by four other questions on the same launch. See
    // [CatalogueStore] for the one-parse-per-revision rule and who invalidates it.
    return "${provider?.id.orEmpty()}|$version|${catalogueRevisionOf(CatalogueStore.root(store))}"
}

/**
 * Whether the model catalogue is due to be refreshed at [now].
 *
 * Pure and next to its two windows so the policy can be read and tested without a
 * device: a deadline of zero is "never refreshed", which is due.
 */
internal fun catalogueRefreshDue(now: Long, dueAt: Long): Boolean = now >= dueAt

/**
 * Whether a saved profile needs catalogue facts that the store on disk does not have.
 *
 * The store is what pi resolves a model's thinking levels, window and cost from, and the
 * launch path's refresh is the only thing that fills it. Two things go wrong while it is
 * empty, and both are a *saved profile* that the store cannot describe:
 *
 *  * a **second profile of a built-in provider** is registered under an id of this app's
 *    own, and the entry under it is built from the store ([builtInProviderObject]). No
 *    facts, no entry, and that profile has to be launched through pi's own id — so every
 *    switch to it costs a relaunch instead of a `set_model`;
 *  * a **first profile** of a provider whose catalogue is not inside pi's package needs
 *    the store for the model's *levels*: `mimo-v2.6-pro` under `xiaomi` is not in pi's
 *    bundle, so with an empty store pi resolves it as a fallback model and answers
 *    `["off"]` — the picker offers one level, and a reader who set `max` on another model
 *    sees `off` come back. pi.dev's catalogue gives that id `[off, high]` (measured), and
 *    the store is the only path from there to here.
 *
 * The refresh's own deadline ([catalogueRefreshDue]) can postpone exactly the run that
 * would fix this — a failure buys half an hour of quiet, and the failures that leave an
 * empty store are precisely the ones that happened — so the launch path asks this and runs
 * the refresh regardless of the clock.
 *
 * [facts] must read the **store**, not this app's own cache of what pi resolved: a cache
 * filled from a running pi would answer for the fallback model too, which is the wrong
 * answer this exists to replace.
 *
 * A relay is never in this position: its entry is built from the profile itself.
 */
internal fun needsCatalogueFacts(
    profiles: List<ModelProfile>,
    /** The store's facts for a provider, by model id — [storeModelFacts] on disk. */
    facts: (String) -> Map<String, kotlinx.serialization.json.JsonObject>,
): Boolean = profiles.any { profile ->
    val provider = profile.providerEntry ?: return@any false
    if (provider in PiProvider.needsBaseUrl) return@any false
    val models = profile.selectableModels.map { it.trim() }.filter { it.isNotEmpty() }
    models.isNotEmpty() && models.none { it in facts(provider.id) }
}

/**
 * The catalogue an agent start would resolve from, as a string that can be compared.
 *
 * [PiAgentSession.refreshCatalogueIfStale] restarts the agent when the catalogue
 * changed, and a restart is another agent start that refreshes again — so the
 * comparison it uses has to converge. Comparing the *file* does not: `pi update
 * --models` rewrites `models-store.json` on every run, including a `304 Not Modified`
 * (`persist: { ...stored, checkedAt }`), and `FileModelsStore.write` writes whatever it
 * is given with no comparison. The file therefore moves on every refresh and the
 * catalogue usually does not.
 *
 * Two stored fields are projected out, and both are pi's own bookkeeping rather than
 * anything the agent reads:
 *
 *  - `checkedAt` — the freshness stamp of pi's four-hour revalidation window.
 *  - `etag` — the HTTP validator for the next conditional request.
 *
 * What is left is `models` and `lastModified` per provider, which is exactly what
 * `remoteModels` consults (`remote-catalog-provider.js`) when it decides whether a
 * stored overlay beats the built-in catalogue. A provider whose entry is gone, whose
 * model list changed or whose `lastModified` moved is a change; a provider that was
 * merely revalidated is not.
 *
 * Anything that is not a JSON object yields [CATALOGUE_UNREADABLE], which is a value
 * no readable document can produce: a file that appears or is replaced by something
 * this app cannot parse is a change, and the worst that can follow is one extra
 * restart. Pure, so the rule is pinned by a test rather than by a device.
 */
internal fun catalogueRevisionOf(root: JsonObject?): String {
    if (root == null) return CATALOGUE_UNREADABLE
    return buildString {
        root.forEach { (provider, entry) ->
            val fields = entry as? JsonObject ?: return@forEach
            append(provider).append('=')
            append(fields["lastModified"]?.toString().orEmpty())
            append(':')
            append(fields["models"]?.toString().orEmpty())
            append('\n')
        }
    }
}

/**
 * [catalogueRevisionOf] a store's **text**, for a caller that has bytes rather than the
 * parsed document.
 *
 * The document form is what the app uses: `CatalogueStore` has already parsed the file, and
 * parsing it a second time here just to project two fields out of it would put the launch
 * path's most expensive read back. This one stays for the tests and for a caller holding a
 * fixture.
 */
internal fun catalogueRevision(text: String?): String {
    if (text.isNullOrBlank()) return CATALOGUE_UNREADABLE
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    return catalogueRevisionOf(root)
}

/** The revision of a store that is absent, unreadable or not a JSON object. */
internal const val CATALOGUE_UNREADABLE = "\u0000unreadable"

/**
 * Whether pi refused a session switch.
 *
 * `new_session`, `switch_session`, `fork` and `clone` all answer `success: true` with
 * `data.cancelled: true` when an extension's `session_before_*` handler says no
 * (`docs/rpc.md`), so a missing field is the ordinary "it went through" answer.
 */
internal fun PiRecord.Response.cancelled(): Boolean =
    (data as? JsonObject)?.get("cancelled")?.jsonPrimitive?.booleanOrNull == true

/**
 * Whether a launch should rebind to the remembered session.
 *
 * A cold start with "open a new conversation" leaves pi's freshly opened session
 * alone. Every later start of the agent — a restart after a crash, a settings
 * change — still restores, because abandoning a conversation already on screen is
 * the bug this preference is not meant to reintroduce. Pure so the three cases
 * are pinned without a process.
 */
internal fun shouldRestoreRememberedSession(
    openNewOnColdStart: Boolean,
    coldStart: Boolean,
): Boolean = !(openNewOnColdStart && coldStart)

/**
 * The session file a conversation is in once a launch handshake has been folded in.
 *
 * A launch handshake answers with the file **pi opened for itself**, which is not the
 * conversation on screen when the process was started for a saved one: the restore
 * switches pi back to that file a moment later. Writing the fresh name into
 * [ConversationState.sessionFile] in the meantime makes the file — and therefore the
 * row a drawer marks as current — disagree with the transcript the page is showing, so
 * the row the reader just opened loses its fill for the length of the switch and gets
 * it back: the "选中的灰色背景消失一下然后出现" report. While a restore is pending the
 * file already on screen is the answer; the switch's own `get_state` writes the name
 * once it has landed.
 *
 * Pure so the rule is pinned by a test: it is the difference between a blink and no
 * blink, and it cannot be measured from a screenshot without the drawer open at the
 * moment a pi process finishes starting.
 */
internal fun sessionFileAfterHandshake(
    shown: String?,
    reported: String?,
    restoring: Boolean,
): String? = if (restoring) shown else reported

/**
 * The session an agent launch should rebind to, or null to keep the one pi opened.
 *
 * Pure so the rule is pinned by a test: a remembered path wins only when the file
 * is still there and is not already the session the handshake reported. A path
 * whose file is gone returns null *and* is worth clearing in the caller — chasing
 * a deleted conversation is how a restore becomes a hang.
 */
internal fun sessionRestoreTarget(
    remembered: String?,
    opened: String?,
    exists: (String) -> Boolean,
): String? {
    if (remembered.isNullOrBlank()) return null
    if (!exists(remembered)) return null
    if (remembered == opened) return null
    return remembered
}

/**
 * The model a **session replacement** has to be told, or null when pi is already on it.
 *
 * pi rebuilds its whole runtime on `new_session` — services, `models.json`, catalogue —
 * and builds the session's model from the command line it was launched with (`--provider`
 * and `--model`), not from the model the running session was switched to. So a
 * conversation that was switched to another profile by `set_model` comes back on the
 * **launch-time** profile the moment the reader starts a new conversation, silently: the
 * chip names a model id, and two profiles of one relay commonly serve the same id, so
 * nothing on screen says the provider moved.
 *
 * That is not a cosmetic difference. The launch-time profile's key may have been rotated
 * away since, and the first message of the new conversation then answers
 * `401 … 无效的令牌` from the relay — a failure that reads as a bad key and that the reader
 * can only clear by tapping the model again, which is a `set_model` back onto the profile
 * they thought they were on. Measured on the emulator: picked profile B at 17:24:58
 * (`get_state` → `pikit-custom-relayb`), started a new conversation, and at 17:25:08 the
 * reply was `pikit-custom-relaya` again; the next prompt carried A's stale key.
 *
 * Pure so the rule is pinned without a process: [wantedProvider]/[wantedModel] are the
 * pair the app shows, [runningProvider]/[runningModel] what pi answered after the
 * replacement, and a null answer means the two already agree and nothing is sent.
 */
internal fun modelToState(
    runningProvider: String?,
    runningModel: String?,
    wantedProvider: String?,
    wantedModel: String?,
): Pair<String, String>? {
    if (wantedProvider.isNullOrBlank() || wantedModel.isNullOrBlank()) return null
    if (runningProvider == wantedProvider && runningModel == wantedModel) return null
    return wantedProvider to wantedModel
}

/**
 * The text of the messages `clear_queue` dropped, or null when there were none.
 *
 * pi answers `{"steering": [...], "followUp": [...]}` (the same shape it reports
 * through `queue_update`). Steering leads: it is the message that was going to be
 * delivered first, so it is the one the user expects to see first if this is put back
 * in front of them.
 */
internal fun clearedQueueText(response: PiRecord.Response?): String? {
    val data = response?.takeIf { it.success }?.data as? JsonObject ?: return null
    val messages = listOf("steering", "followUp").flatMap { key ->
        (data[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
    }.filter { it.isNotBlank() }
    return messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun PiSettings.toLaunchOptions(
    nodePath: String,
    cliEntry: String,
    workingDir: String,
    sessionDir: String,
    /**
     * The provider id pi knows this profile by ([ProfileAddressing.runtimeProviderId]),
     * not the provider's own id: a second profile of one provider is registered under an
     * id of this app's own, and `--provider` has to name the id that exists.
     */
    providerId: String?,
    /** Every saved profile's credential, by variable — see [ProfileAddressing]. */
    credentials: Map<String, String>,
    /** The rendered chat prompt, or null for a work launch. */
    chatPromptFile: String? = null,
    /** The extension that makes [chatPromptFile] exact, or null when the image lacks it. */
    chatPromptExtension: String? = null,
    /** The bundled web extension, when this chat conversation's switch is on. */
    chatWebExtension: String? = null,
    /** This launch's mode; [PiSettings.agentMode] is only a new conversation's default. */
    mode: AgentMode = agentMode,
): PiLaunchOptions = PiLaunchOptions(
    nodePath = nodePath,
    cliEntry = cliEntry,
    workingDir = workingDir,
    sessionDir = sessionDir,
    provider = providerId,
    modelId = modelId.takeIf { it.isNotBlank() },
    thinkingLevel = thinkingLevel,
    extraEnv = credentials,
    offline = true,
    mode = mode,
    systemPromptFile = chatPromptFile,
    chatPromptExtension = chatPromptExtension,
    chatWebExtension = chatWebExtension,
)

/**
 * The records a branch up to [messages] message records keeps.
 *
 * Extracted from [PiAgentSession.forkAt] so the count is testable without a
 * process: `0` is a real branch point (regenerating the first turn), and the
 * message *after* the requested count is where the copy stops. One line of a pi
 * session is one record; only records whose `type` is `message` are counted, so
 * a session-info record that follows the last kept message is not a reason to
 * take one more turn.
 */
internal fun forkRecords(lines: List<String>, messages: Int): MutableList<String>? {
    if (messages < 0 || lines.size < 2) return null
    val kept = ArrayList<String>(lines.size)
    kept += lines.first()
    var seen = 0
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        if (line.contains("\"type\":\"message\"")) {
            if (seen >= messages) break
            seen++
        }
        kept += line
    }
    return if (seen < messages) null else kept
}
