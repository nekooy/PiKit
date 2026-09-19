package pi.kit.mob.pi

import android.content.Context
import android.util.Log
import pi.kit.mob.data.CustomEndpoint
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.ModelSettings
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.SettingsStore
import pi.kit.mob.data.apiKeyEnvironment
import pi.kit.mob.data.catalogueRefreshEnvironment
import pi.kit.mob.env.BootstrapInstaller
import pi.kit.mob.env.BundledExtension
import pi.kit.mob.env.PrefixPatcher
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.pi.NoticeKind
import pi.kit.mob.terminal.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Progress of unpacking the bundled Termux environment. */
sealed interface RuntimeStatus {
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
 * Owns everything that outlives a single screen: the unpacked environment, the
 * agent process, and the conversation state derived from its event stream.
 *
 * A process-wide singleton, because the agent must keep running while the UI is
 * backgrounded and must not be tied to an Activity lifecycle. [PiAgentService]
 * exists only to hold a foreground notification so the OS does not reclaim it
 * mid-turn.
 */
class PiAgentSession private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    val env: TermuxEnv = TermuxEnv.of(appContext)
    val settingsStore: SettingsStore = SettingsStore(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runtime = MutableStateFlow<RuntimeStatus>(RuntimeStatus.NotInstalled)
    val runtime: StateFlow<RuntimeStatus> = _runtime.asStateFlow()

    private val _agent = MutableStateFlow<AgentStatus>(AgentStatus.Stopped)
    val agent: StateFlow<AgentStatus> = _agent.asStateFlow()

    private val _conversation = MutableStateFlow(ConversationState())
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()

    private var client: PiRpcClient? = null
    private var recordJob: Job? = null

    private val environmentLock = Mutex()

    /**
     * Serialises [startAgent], so the "already running" guard cannot be read by two
     * callers before either of them has written `Starting`. See that function.
     */
    private val startLock = Mutex()

    val sessionDir: File get() = File(env.filesDir, "pi-sessions")

    /**
     * PTY sessions shown in the terminal tab.
     *
     * Owned here rather than by the composable: the terminal is one of four tabs
     * and leaves the composition whenever the user looks at another one, which
     * used to kill the shell along with it. Anything long-running started there —
     * `pkg upgrade`, a build — has to outlive the tab.
     */
    val terminalSessions: TerminalSessionManager by lazy {
        TerminalSessionManager(appContext, env) { apiKeyEnvironment(settingsStore.read()) }
    }

    // ----------------------------------------------------------- environment

    /**
     * Unpacks the bundled runtime if needed. Cheap to call repeatedly: the
     * installer no-ops when the installed image already matches.
     */
    suspend fun ensureEnvironment(): Boolean = environmentLock.withLock {
        if (_runtime.value is RuntimeStatus.Ready) return@withLock true

        val installer = BootstrapInstaller(appContext)
        try {
            // The whole body runs off the caller's thread, not just the unpack. The
            // caller here is the composition that draws the first usable frame, and
            // the two steps after the unpack are both filesystem work: the
            // environment probe stats five paths, and `syncStorageLinks` creates a
            // symlink farm and then writes, reads and deletes a probe file inside
            // `/sdcard` — a FUSE round trip per granted folder. None of it needs the
            // main thread; a `StateFlow` is safe to write from any of them.
            withContext(Dispatchers.IO) {
                val plan = installer.ensureInstalled { progress ->
                    when (progress) {
                        is BootstrapInstaller.Progress.Working ->
                            _runtime.value = RuntimeStatus.Installing(progress.fraction, progress.message)
                        is BootstrapInstaller.Progress.Done -> Unit
                    }
                }
                _runtime.value = RuntimeStatus.Ready(plan.revision, checkEnvironment())
                // Runs after the unpack so `$HOME` exists; harmless when the
                // permission has not been granted yet.
                syncStorageLinks()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Installing the bundled runtime failed", t)
            _runtime.value = RuntimeStatus.Failed(t.message ?: t.toString())
            false
        }
    }

    /**
     * Rewrites the shell's welcome banner in the current interface language.
     *
     * Off the main thread and safe to call on every launch and on every language
     * change: the write is skipped unless the text actually differs.
     */
    suspend fun refreshTerminalBanner() = withContext(Dispatchers.IO) {
        runCatching { BootstrapInstaller(appContext).installTerminalBanner(settingsStore.read().language) }
            .onFailure { Log.w(TAG, "Could not write the terminal banner", it) }
        Unit
    }

    /**
     * Reports missing pieces rather than discovering them later as mysterious
     * tool failures. `rg` and `fd` matter most: pi's `grep` and `find` tools
     * shell out to them and pi will not download them on Android.
     */
    private fun checkEnvironment(): List<String> = buildList {
        PiInstallation.requiredTools(env).forEach { (tool, present) ->
            if (!present) add("'$tool' is missing from the bundled environment")
        }
        if (PiInstallation.cliEntry(env) == null) add("the pi CLI was not found in the bundled environment")
    }

    /**
     * Brings `~/storage` in line with the storage policy the user chose.
     *
     * Called after the environment check and whenever the policy changes, so the
     * links appear or disappear without a restart. A failure here is not fatal
     * and is not surfaced as an environment warning: the rest of the runtime
     * works without shared storage, and the default policy grants none of it.
     */
    fun syncStorageLinks(): Boolean {
        val policy = StorageAccess.readPolicy(appContext)
        if (policy.isEmpty) {
            // Revoking has to take effect even when the Android permission is
            // gone, so the farm is removed before the permission is consulted.
            StorageAccess.removeFarm(env.storageDir)
            Log.i(TAG, "shared storage: policy is empty, ~/storage removed")
            return false
        }
        if (!StorageAccess.isGranted()) {
            Log.i(TAG, "shared storage: policy set but the system permission is missing")
            return false
        }
        val links = StorageAccess.applyPolicy(env, policy)
            .onFailure { Log.w(TAG, "Could not create the storage symlinks", it) }
            .isSuccess
        // Logged rather than assumed: the recorded grant and what the process can
        // actually do have been seen to disagree, and that is exactly the "can
        // read but cannot save" report. The reach line is measured from inside
        // this process, because a shell — even one with the same uid — has a
        // different SELinux context and cannot stand in for it.
        Log.i(TAG, "shared storage: symlinks=$links writable=${StorageAccess.canWriteSharedStorage()}")
        Log.i(TAG, StorageAccess.describeReach(env, policy))
        return links
    }

    /** The storage policy in force, for the Settings page. */
    fun storagePolicy(): StorageAccess.Policy = StorageAccess.readPolicy(appContext)

    /**
     * Applies a new storage policy: persists it, rebuilds the symlink farm, and
     * tells the agent about the change.
     *
     * The agent is restarted rather than left running: its system prompt and its
     * `AGENTS.md` describe what is reachable, and the guard extension caches the
     * workspace root at startup, so a running process would keep the old view of
     * the device.
     */
    fun setStoragePolicy(policy: StorageAccess.Policy) {
        StorageAccess.writePolicy(appContext, policy)
        syncStorageLinks()
        if (_agent.value is AgentStatus.Running) scheduleRestart()
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
     * as [environmentLock] and must not be: `ensureEnvironment()` takes that one, and
     * nesting them in the other order somewhere else would be the deadlock.
     */
    suspend fun startAgent(): Boolean = startLock.withLock { startAgentLocked() }

    private suspend fun startAgentLocked(): Boolean {
        if (_agent.value is AgentStatus.Running || _agent.value is AgentStatus.Starting) return true
        if (!ensureEnvironment()) return false

        val cliEntry = PiInstallation.cliEntry(env)
        if (cliEntry == null) {
            _agent.value = AgentStatus.Failed(
                "The pi CLI is not present in the bundled environment at " +
                    PiInstallation.CLI_ENTRY_RELATIVE,
            )
            return false
        }

        _agent.value = AgentStatus.Starting
        // The launch files, off the caller's thread. This path is taken on every
        // launch, on every `scheduleRestart` and on every settings change, and its
        // caller is the service's `lifecycleScope` — `Dispatchers.Main.immediate` —
        // so before this it parsed and rewrote `models.json` and `settings.json` and
        // rendered `web-search.json` on the main thread, between the user's tap and
        // the next frame. Everything below is filesystem work; the `_agent` writes
        // above and below are safe from any thread.
        val (settings, workingDir) = withContext(Dispatchers.IO) {
            val settings = settingsStore.read()
            // Same lifecycle as `models.json`: rewritten on every launch, because
            // these files live in `$HOME` while the profile lives in the app's
            // config, and a user who edits one by hand would otherwise have no way
            // back.
            writeModelsJson(settings)
            writePiDefaults(settings)
            // The bundled extension's own default is `workflow: "summary-review"`,
            // which opens its result curator in a browser; there is no browser here,
            // so PiKit writes the document a fresh install has before pi starts. Done
            // on every launch rather than from the settings page, because the page may
            // never be opened and the default has to hold anyway.
            webSearch.ensureDocument()
            // `$HOME/workspace`, not `$HOME`: see [TermuxEnv.workspace]. A user
            // who set their own working directory keeps it — the guard is told the
            // same path through `PIKIT_WORKSPACE`, so "the workspace" and where pi
            // actually runs can never be two different directories.
            val workingDir = File(settings.workingDir.ifBlank { env.workspacePath })
            workingDir.mkdirs()
            sessionDir.mkdirs()
            settings to workingDir
        }

        return try {
            val process = withContext(Dispatchers.IO) {
                PiProcessLauncher.launch(
                    context = appContext,
                    env = env,
                    options = settings.toLaunchOptions(
                        nodePath = env.node.absolutePath,
                        cliEntry = cliEntry.absolutePath,
                        workingDir = workingDir.absolutePath,
                        sessionDir = sessionDir.absolutePath,
                    ),
                )
            }
            val newClient = PiRpcClient.from(process).also { it.start() }
            client = newClient
            attach(newClient)

            // There is no handshake in this protocol; `get_state` doubles as the
            // readiness probe and as the first load of session metadata.
            //
            // The response is consumed by the request rather than forwarded to
            // the record stream, so it has to be folded into the conversation
            // state by hand — otherwise the UI never learns which model is in
            // use and sits on "Loading model…" forever.
            val sessionState = newClient.handshake()
            logSessionState("handshake", sessionState)
            _conversation.update { ConversationReducer.reduce(it, sessionState) }

            _agent.value = AgentStatus.Running
            // The settings page's own model list is one tap away and the thinking
            // picker needs the level list pi derives from the model, so it is loaded
            // as soon as the process can answer. `get_available_models` used to be
            // asked here as well, on every launch, for the "does pi's catalogue know
            // this id" test that decided between an override and a definition — the
            // whole of that machinery is gone (see [modelsJsonWith]), and with it a
            // round trip whose reply is every model of every configured provider,
            // costs, windows and thinking maps included.
            refreshThinkingLevels()
            // Off the launch path, and nothing waits on it: this is a network round
            // trip and the launch is a tap. See [refreshCatalogueIfStale].
            scope.launch { refreshCatalogueIfStale(workingDir) }
            true
        } catch (e: PiProcessExitedException) {
            // pi writes nothing to stdout before its first response, so a
            // startup failure shows up purely on stderr.
            Log.e(TAG, "pi exited during startup", e)
            _agent.value = AgentStatus.Failed(e.message ?: "pi failed to start")
            detach()
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Starting the agent failed", t)
            _agent.value = AgentStatus.Failed(t.message ?: t.toString())
            detach()
            false
        }
    }

    /**
     * Refreshes pi's own model catalogue when the copy on disk is old enough, and
     * relaunches the agent if it changed.
     *
     * ## What this is for
     *
     * pi resolves a model id its catalogue does not contain from a copy of the
     * provider's **default** model (`buildFallbackModel`), so every model-shaped fact
     * about it — the thinking levels, the context window, the cost, whether it takes
     * images — is the default model's. That is not a display problem: it is what the
     * reader saw as "deepseek 含关闭一共 4 档你却只有 3 档" (the fallback says
     * `deepseek-v4-pro`'s `off, high, max` where pi.dev's catalogue gives
     * `deepseek-flash` `off, low, high, max`) and as "openai 一共 6 你却只有 5"
     * (`gpt-5.6-*` has six levels, `gpt-5.5` — the default — has five). The agent
     * logs the same fact as `Model "…" not found for provider "…". Using custom model
     * id.`
     *
     * The catalogue lives in `$HOME/.pi/agent/models-store.json`, and an agent launched
     * by this app reads whatever is in that file. Nothing pi does on the RPC path
     * *writes* it: `refreshModelCatalogs` is imported by the interactive TUI and the
     * model selector and by nothing else. RPC mode does start a fire-and-forget
     * `modelRuntime.refresh()` at launch (`main()`: `!offlineMode && appMode === "rpc"`),
     * but that call only republishes the overlay **inside the running process** — it goes
     * through `Models.refresh`, not through the CLI's `refreshModelCatalogs`, and the
     * store on disk is not touched. So it can move the model list out from under a
     * question without making the next launch any fresher, which is a second reason this
     * function exists. [PiProcessLauncher.refreshCatalogue] runs pi's own
     * `update --models`, which *does* persist. See that function for the measurement.
     *
     * (An earlier version of this note and of `PiLaunchOptions.offline` read the two calls
     * as one and said "RPC mode refreshes the catalogue", which is true of the process
     * and false of the file. The distinction is what makes the 4-hour window necessary
     * rather than redundant.)
     *
     * ## Where it runs, and what it costs
     *
     * After the handshake, in the background, when the copy on disk is older than
     * [CATALOGUE_REFRESH_WINDOW_MS] — 0.67 s and one small HTTPS request measured on a
     * desktop bundle with one configured provider, and now one request per *built-in*
     * provider rather than per provider the user has a key for
     * (`catalogueRefreshEnvironment`): ~757 KB across thirty-two of them, concurrent, so
     * roughly one round trip. It is deliberately *not* on the launch path: pi is already
     * up and answering by the time this starts, so a phone with no network pays nothing
     * the user can see. A failure (no network, a provider pi.dev does not list) is logged
     * and rescheduled after [CATALOGUE_RETRY_MS] — an offline phone must not pay the
     * timeout at every start.
     *
     * The agent is launched without `--offline`, which is what the report asked for, but
     * that alone does not refresh this store — see above.
     *
     * When it *did* change something, the running agent is reading the old one —
     * pi reads the store at startup — so it is relaunched, which is the same
     * convergence the old `models.json` writer used. Only when the agent is idle:
     * a turn in flight is worth more than a correct level list, and the fresh
     * catalogue is on disk for the next launch either way.
     *
     * ## What "changed" means, and the restart loop it used to be
     *
     * A restart is itself an agent start, and an agent start runs this function, so
     * "did it change" has to be a test that converges — and the obvious test did not.
     * The store's *file* was compared by `(lastModified, length)`, and a refresh
     * rewrites that file on every run even when nothing about the catalogue moved:
     *
     *  - `remote-catalog-provider.js` persists `{ ...stored, checkedAt }` when the
     *    provider answers `304 Not Modified` — the freshness stamp moves and nothing
     *    else — and persists a fresh entry with a new `checkedAt` on a `200` as well.
     *  - `FileModelsStore.write` does that unconditionally: it returns
     *    `JSON.stringify(current, null, 2)` as the next body for the lock to write,
     *    with no comparison against what is already there.
     *
     * So every refresh changed the file's mtime, every mtime change scheduled a
     * restart, every restart ran another refresh (which was always due while
     * [CATALOGUE_REFRESH_WINDOW_MS] was zero), and the agent was torn down
     * and relaunched on a loop — measured from the report as the status line
     * flickering between "starting" and "ready" for as long as the app was open, with
     * one `launching:` line after another in the log. The two flags pi writes that
     * the agent never reads are `checkedAt` (pi's own freshness window) and `etag`
     * (its revalidation validator), so [catalogueRevision] projects them out and
     * compares what is left: each provider's `models` and its `lastModified`, which
     * is everything `remoteModels` consults when it decides what the agent resolves.
     *
     * @param workingDir the directory pi is run from, which `update` inherits.
     */
    private suspend fun refreshCatalogueIfStale(workingDir: File) {
        val now = System.currentTimeMillis()
        val dueAt = cataloguePrefs.getLong(KEY_CATALOGUE_DUE_AT, 0L)
        if (!catalogueRefreshDue(now, dueAt)) return
        if (!catalogueRefreshRunning.compareAndSet(false, true)) return
        try {
            val cliEntry = PiInstallation.cliEntry(env)?.absolutePath ?: return
            val store = File(env.piConfigDir, CATALOGUE_STORE_NAME)
            val before = store.catalogueContent()
            val settings = settingsStore.read()
            val exit = withContext(Dispatchers.IO) {
                runCatching {
                    PiProcessLauncher.refreshCatalogue(
                        context = appContext,
                        env = env,
                        options = settings.toLaunchOptions(
                            nodePath = env.node.absolutePath,
                            cliEntry = cliEntry,
                            workingDir = workingDir.absolutePath,
                            // Not used by `update --models` (the argv it builds has no
                            // `--session-dir`), but the environment is shared with the
                            // agent launch and a `pi` that writes a session somewhere
                            // unexpected is worse than one that writes it here.
                            sessionDir = sessionDir.absolutePath,
                        ),
                        // Every built-in provider, not only the profile's: the point of
                        // this scan is the providers the user is *not* currently on, which
                        // is where the model they cannot select lives. The profile's own
                        // key still wins for the provider it names — see
                        // `catalogueRefreshEnvironment` and [PiLaunchOptions.extraEnv].
                        extraEnv = catalogueRefreshEnvironment(),
                    )
                }.getOrNull()
            }
            // Scheduled from the attempt: a success is trusted for the full window
            // [CATALOGUE_REFRESH_WINDOW_MS] — pi's own freshness window, and the reason a
            // launch does not re-download thirty-two catalogues — while a failure buys
            // half an hour so an offline phone does not wait for the timeout every time
            // the agent starts.
            val succeeded = exit == 0
            cataloguePrefs.edit()
                .putLong(
                    KEY_CATALOGUE_DUE_AT,
                    now + if (succeeded) CATALOGUE_REFRESH_WINDOW_MS else CATALOGUE_RETRY_MS,
                )
                .apply()
            if (!succeeded) return
            // The file is rewritten by every refresh; the catalogue is not. See the
            // note above: comparing the file made this a restart loop.
            if (store.catalogueContent() == before) return
            if (_conversation.value.isStreaming) {
                Log.i(TAG, "model catalogue changed; the agent keeps the old one until it restarts")
                return
            }
            Log.i(TAG, "model catalogue changed; restarting pi to read it")
            scheduleRestart()
        } finally {
            catalogueRefreshRunning.set(false)
        }
    }

    /**
     * The catalogue the agent would resolve from, as a comparable string.
     *
     * An absent or unreadable file both read as [CATALOGUE_UNREADABLE], so the first
     * write to a store that did not exist is a change — which it is: that refresh is
     * the one that fills the overlay in.
     */
    private fun File.catalogueContent(): String =
        if (isFile) runCatching { readText() }.getOrNull().let(::catalogueRevision)
        else CATALOGUE_UNREADABLE

    private fun attach(source: PiRpcClient) {
        recordJob?.cancel()
        recordJob = scope.launch {
            source.records.collect { record ->
                _conversation.update { ConversationReducer.reduce(it, record) }
                // pi changed the model by itself — a `/model` command typed into the
                // composer, a scoped-model cycle, an extension calling
                // `pi.setModel`. Every model-shaped fact the app holds came from a
                // `get_state` reply that nothing else re-asks for, so the context
                // meter kept reporting the previous model's window and the thinking
                // picker kept offering the previous model's levels. `model_select` is
                // emitted by `session.setModel` (`agent-session.js`,
                // `_emitModelSelect`) and streamed like any other event, so one
                // refresh here covers every way the model can move. The app's own
                // switch is the same event plus its own refresh, which is one small
                // round trip on a rare action rather than a correctness question.
                if (record is PiRecord.Event && record.type == "model_select") refreshState()
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
            val code = source.exitCodeOrNull()
            _agent.value = AgentStatus.Failed(
                if (code != null) "pi exited with code $code\n${source.stderrSnapshot().trim()}"
                else "pi stopped unexpectedly",
            )
        }
    }

    private fun detach() {
        recordJob?.cancel()
        recordJob = null
        client = null
    }

    /** Stops the agent gracefully. Closing stdin is the only clean shutdown. */
    suspend fun stopAgent() {
        val current = client ?: return
        detach()
        withContext(Dispatchers.IO) { current.close() }
        _agent.value = AgentStatus.Stopped
    }

    // -------------------------------------------------------------- commands

    fun sendPrompt(text: String, images: List<ImageAttachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        val busy = _conversation.value.isStreaming
        runCommand {
            // pi rejects a plain prompt while streaming and requires an explicit
            // queueing behaviour, so only the idle case may omit it.
            client?.request("prompt", timeout = null) { id ->
                PiCommand.prompt(
                    id = id,
                    message = trimmed,
                    streamingBehaviour = if (busy) "steer" else null,
                    images = images,
                )
            }
        }
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
        runCommand {
            val response = client?.requestOrThrow("new_session") { PiCommand.newSession(it) }
                ?: return@runCommand
            // A `session_before_switch` extension handler can refuse the switch, and
            // pi answers `success: true` with `data.cancelled: true` when it does
            // (`docs/rpc.md`, `new_session`). Clearing the transcript and re-reading
            // state on a session that never changed threw away the conversation the
            // user was looking at — the app's own view of the session is only
            // refreshed when the session actually moved.
            if (response.cancelled()) return@runCommand
            _conversation.value = ConversationState()
            refreshState()
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
    ) {
        val fileName: String get() = File(path).name
    }

    /**
     * Transcripts pi has saved: pinned first, then most recently used.
     *
     * Reads every session file, so it runs on an IO dispatcher — a long history
     * is megabytes of JSONL and this must not block the frame.
     */
    /**
     * What the last parse of each session file found, stamped with the file it was
     * parsed from.
     *
     * The history list re-reads a transcript from its first line to its last to
     * count its messages, and a session is megabytes of JSONL with four regexes run
     * per line — so opening the list re-parsed every session the user has ever had,
     * every time, including the ones that had not been touched since the last open.
     * The key is the path, so a rename or a delete cannot leave a stale entry
     * behind, and the stamp is (mtime, length) — the same pair
     * [SessionSearchIndex] uses, and for the same reason: a file written twice
     * inside the filesystem's timestamp granularity keeps its modification time.
     *
     * Concurrent because the listing is a `withContext(Dispatchers.IO)` body and two
     * opens can overlap.
     */
    private class CachedMeta(val modifiedAt: Long, val length: Long, val meta: SessionMeta)

    private val sessionMetaCache = ConcurrentHashMap<String, CachedMeta>()

    suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val pinned = pinnedSessions.value
        val files = sessionDir.listFiles { file -> file.isFile && file.extension == "jsonl" }
            ?: return@withContext emptyList()

        files.map { file ->
            val modifiedAt = file.lastModified()
            val length = file.length()
            val cached = sessionMetaCache[file.absolutePath]
            val meta = if (cached != null && cached.modifiedAt == modifiedAt && cached.length == length) {
                cached.meta
            } else {
                runCatching {
                    file.bufferedReader().use { reader -> SessionMetadata.read(reader.lineSequence()) }
                }.getOrDefault(SessionMeta()).also {
                    sessionMetaCache[file.absolutePath] = CachedMeta(modifiedAt, length, it)
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
            )
        }.also {
            // A conversation deleted while the app was running would otherwise be
            // cached for the life of the process.
            sessionMetaCache.keys.retainAll(files.mapTo(HashSet()) { file -> file.absolutePath })
        }.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.modifiedAt })
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
     * When the next catalogue refresh is allowed, and whether one is running.
     *
     * `SharedPreferences` rather than a field: the deadline has to survive the
     * process, or a phone that is offline would attempt — and wait for — the refresh
     * on every launch. The pins next door are stored the same way, for the same
     * reason: this is app state that is not a user *setting*, so it is not in
     * [SettingsStore].
     */
    private val cataloguePrefs by lazy {
        appContext.getSharedPreferences("pikit_model_catalogue", Context.MODE_PRIVATE)
    }

    private val catalogueRefreshRunning = java.util.concurrent.atomic.AtomicBoolean(false)

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

        if (target.path == _conversation.value.sessionFile) {
            return runCatching {
                client?.requestOrThrow("set_session_name") { PiCommand.setSessionName(it, name) }
                _conversation.update { it.copy(sessionName = name) }
            }
        }

        return withContext(Dispatchers.IO) {
            runCatching { appendSessionInfo(File(target.path), name) }
        }
    }

    private fun appendSessionInfo(file: File, name: String) {
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
     * Reopens a saved conversation.
     *
     * The transcript is also re-fetched: switching sessions rebinds pi's state,
     * so the messages shown afterwards come from `get_messages` rather than from
     * anything the UI still had.
     *
     * ## The turn in flight
     *
     * `switch_session` ends the running turn — `RuntimeHost.teardownCurrent` starts
     * with `await this.session.abort()` — so a move made while the agent is
     * answering stops that answer, and there is no way to keep it. The refusal this
     * used to be (a silent return, and a notice on the page saying why) was itself
     * the report: the tap did nothing the user could act on. [interrupt] is the
     * caller saying the user was asked and said yes; the page raises the question
     * with `InterruptTurnDialog`, which is where the cost of the tap is spelled out.
     */
    fun switchSession(target: SessionSummary, interrupt: Boolean = false) {
        if (turnInFlight && !interrupt) return
        runCommand {
            val response = client?.requestOrThrow("switch_session") {
                PiCommand.switchSession(it, target.path)
            } ?: return@runCommand
            // Refused by a `session_before_switch` handler: pi still answers
            // `success: true`, so without this the transcript on screen was replaced
            // by the messages of a session pi never opened.
            if (response.cancelled()) return@runCommand
            // pi rebinds to a different session, so stale UI state would be wrong.
            _conversation.value = ConversationState()
            refreshState()
            val messages = client?.request("get_messages") { PiCommand.getMessages(it) }
            _conversation.update {
                ConversationReducer.replaceWithMessages(it, messages?.data)
            }
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
            val response = client?.request("get_state") { PiCommand.getState(it) }
                ?: return@runCommand
            logSessionState("refresh", response)
            _conversation.update { ConversationReducer.reduce(it, response) }
            // The supported levels follow the model, so a state refresh is also the
            // moment they are re-read: switching the model on the composer changes
            // which levels exist without touching anything else.
            refreshThinkingLevels()
        }
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

    /**
     * Switches the answering model without restarting the agent.
     *
     * The model chip above the composer is the reason this exists. The obvious
     * implementation — make the profile active and call [scheduleRestart] — costs
     * a process teardown, a `pi` startup and a fresh handshake (seconds, and the
     * whole transcript is re-read), to change one field that pi accepts over RPC
     * while it is running. The alternative, a restart that is merely *fast*, is
     * still a restart: the running turn, the queue and the terminal's view of the
     * session all go with it.
     *
     * So the switch is applied over RPC, and the restart is only a fallback for
     * when pi refuses. Two details are load-bearing:
     *
     *  - The provider is sent as **pi's own provider id** (`deepseek`,
     *    `pikit-custom`), because `set_model` looks the model up in the snapshot
     *    of models pi already knows and matches `m.provider === command.provider`
     *    — checked against pi 0.85.1's `rpc-mode.js`, which answers
     *    `Model not found: <provider>/<id>` for anything else. The *environment
     *    variable* name is the same string for the built-in providers but not the
     *    same concept, and sending `DEEPSEEK_API_KEY` here is a lookup that always
     *    misses.
     *  - The profile is committed to the store first, so the setting survives a
     *    later restart even if the RPC call below is rejected.
     *
     * [model] is a parameter rather than `profile.modelId` because a profile holds
     * *several* models — the provider is the endpoint and the key, and the model is
     * a choice made against it. The chosen one is written back into the profile
     * before the call, so the store, the picker's tick and the composer's chip
     * agree on the answer whichever way the call goes.
     *
     * A miss is not silent: `set_model` appears in pi's reference client and in
     * its RPC mode, so a rejected call means this pi build does not offer that
     * model — a custom endpoint's `models.json` not being read yet, say. Any
     * failure falls back to a restart, which cannot be wrong.
     */
    fun selectProfile(profile: ModelProfile, model: String) {
        val committed = settingsStore.profiles.upsert(profile.copy(modelId = model))
        settingsStore.profiles.setActive(committed.id)
        val provider = committed.providerEntry
        if (provider == null || model.isBlank()) {
            // Nothing pi could be told: an incomplete profile only makes sense as
            // a launch-time configuration, so relaunch with it.
            scheduleRestart()
            return
        }
        runCommand {
            // No live process to tell. This is the state a launch failure leaves
            // behind — `client` is null and the agent is `Failed` — and it used to
            // return here and do nothing at all, which is the report that picking
            // another provider's model "does not load it" and only a manual
            // restart recovered. The profile is already committed above, so a
            // relaunch is the whole fix: `startAgent` reads the store.
            val live = client
            if (live == null) {
                scheduleRestart()
                return@runCommand
            }
            val applied = runCatching {
                live.requestOrThrow("set_model") {
                    PiCommand.setModel(it, provider.id, model)
                }
            }.isSuccess
            if (applied) refreshState() else scheduleRestart()
        }
    }

    fun compact() {
        runCommand { client?.request("compact", timeout = null) { PiCommand.compact(it) } }
    }

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
            val directory = File(env.home, EXPORT_DIRECTORY).apply { mkdirs() }
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
        runCommand {
            val response = client?.requestOrThrow("clone") { PiCommand.clone(it) } ?: return@runCommand
            val cancelled = response.data?.jsonObject?.get("cancelled")
                ?.jsonPrimitive?.booleanOrNull == true
            if (cancelled) return@runCommand
            _conversation.value = ConversationState()
            refreshState()
            val messages = client?.request("get_messages") { PiCommand.getMessages(it) }
            _conversation.update { ConversationReducer.replaceWithMessages(it, messages?.data) }
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

    fun setSessionName(name: String) {
        runCommand { client?.requestOrThrow("set_session_name") { PiCommand.setSessionName(it, name) } }
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

    private fun runCommand(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: PiProcessExitedException) {
                _conversation.update { it.copy(lastError = e.message) }
                _agent.value = AgentStatus.Failed(e.message ?: "pi exited")
            } catch (t: Throwable) {
                _conversation.update { it.copy(lastError = t.message ?: t.toString()) }
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
        settingsRestartJob = scope.launch {
            delay(SETTINGS_RESTART_DEBOUNCE_MS)
            stopAgent()
            startAgent()
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
            stopAgent()
            startAgent()
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
     * The bundled web-access extension's configuration, and the only writer of
     * pi's own `$HOME/.pi/agent/web-search.json`.
     *
     * Owned by the session rather than by the settings page so that PiKit's one
     * default is written on launch, whether or not that page is ever opened, and
     * so the page and the agent read the same in-memory copy.
     */
    val webSearch: WebSearchStore by lazy {
        // The language is read per write rather than captured: the comments in the
        // rendered document are in the interface's language, and a user who switches
        // it and then taps a control should get the file in the language they are
        // looking at.
        WebSearchStore(env, scope) { settingsStore.read().language }
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

    /**
     * Merges PiKit's own entries into pi's `models.json`.
     *
     * The file is pi's, and pi's documentation invites the user to edit it by hand
     * (`docs/models.md`), so nothing here may replace it or delete it. Three things
     * are written and nothing else is touched:
     *
     *  - the custom endpoint's provider entry, when one is in use — and only that
     *    key is withdrawn when one is not, because `pikit-custom` is PiKit's own
     *    reserved provider id. Its `models` array is built from the profile's per-model
     *    settings, because pi has no catalogue for the provider and the entries are the
     *    only thing that describes its models at all;
     *  - one `models` entry per model id pi's catalogue does **not** contain, for the ids
     *    the user filled settings in for. The entry names the model pi would have
     *    resolved the id to (`buildFallbackModel`'s copy of the provider's default, read
     *    out of `get_state`), so the settings change what the user asked to change and
     *    nothing else;
     *  - the withdrawal of everything earlier builds of this app wrote: the same entries
     *    once they are no longer wanted, and the `modelOverrides.input` declarations the
     *    build before this one wrote for every id the user had declared.
     *
     * Nothing is written for a model pi's own catalogue contains. That is the rule rather
     * than an omission: `models` is the only mechanism that reaches an id pi does not
     * know, and it *replaces* pi's entry for one it does, window and cost included. See
     * [modelsJsonWith].
     *
     * Written only when the encoded document differs, so an untouched file keeps
     * its bytes, its mtime and its comments.
     *
     * Failure is logged and ignored: a missing `models.json` breaks a custom
     * provider, and pi reports that itself with a message naming the provider —
     * better than refusing to start the agent at all.
     *
     * @return true when the file was actually rewritten. Nothing in the app waits on
     *   that any more — the answer used to decide whether a running pi had to be
     *   restarted to read a declaration that had just been discovered — but a caller
     *   that wants to know whether the file moved still can.
     */
    private fun writeModelsJson(settings: PiSettings): Boolean {
        val target = File(env.home, CustomEndpoint.MODELS_FILE_RELATIVE)
        val profile = settingsStore.profiles.activeProfile

        return runCatching {
            val provider = settings.provider
            val customInUse = provider != null && provider in PiProvider.needsBaseUrl
            val definitions = modelDefinitions(provider, profile, catalogueBasis(env, provider))

            // Nothing of PiKit's to say and no file of the user's to say it in: the
            // file is created only when something has to go in it. Without this a
            // built-in provider on a fresh install would leave an empty
            // `{"providers":{}}` behind, which reads as an app-written file rather
            // than as one the user made.
            //
            // "Nothing to say" is about *entries*, not about the map's keys: the active
            // provider is always in it so that an entry turned off — or one a previous
            // build wrote — can be withdrawn, and that is only worth doing to a file that
            // exists.
            if (!target.isFile && !customInUse && definitions.values.all { it.wanted.isEmpty() }) {
                return false
            }

            val existing = if (target.isFile) {
                val read = Json.parseToJsonElement(target.readText()) as? JsonObject
                if (read == null) {
                    // Unreadable to us, and it is the user's: leave it exactly as it
                    // is rather than replacing it with PiKit's idea of the document.
                    Log.w(TAG, "left ${CustomEndpoint.MODELS_FILE_RELATIVE} alone: not a JSON object")
                    return false
                }
                read
            } else {
                JsonObject(emptyMap())
            }

            val custom = if (customInUse) {
                CustomEndpoint.providerObject(
                    baseUrl = settings.baseUrl,
                    // Every model the active profile offers, so pi's `set_model` can
                    // switch between them without a restart; the active one leads
                    // the list, and a profile that somehow has none still registers
                    // the model it would launch with.
                    modelIds = profile?.selectableModels.orEmpty().ifEmpty { listOf(settings.modelId) },
                    settings = profile?.modelSettings.orEmpty(),
                )
            } else {
                null
            }

            val document = modelsJsonWith(
                existing = existing,
                customProviderId = CustomEndpoint.PROVIDER_ID,
                customProvider = custom,
                definitions = definitions,
            )
            val encoded = Json.encodeToString(JsonObject.serializer(), document)
            // `!target.isFile ||` is load-bearing, not a shortcut. Without it the
            // first write to a `models.json` that does not exist yet throws
            // FileNotFoundException out of `readText()`, the enclosing
            // `runCatching` swallows it as "could not merge models.json", and the
            // file is never created. pi is then launched with
            // `--provider pikit-custom`, does not find that provider in any
            // models.json, and `findInitialModel` answers
            // `Unknown provider "pikit-custom"` followed by `process.exit(1)`
            // (pi's `core/model-resolver.js`). Measured as the report "custom
            // endpoint configured, then pi exit with code 1 on the chat page":
            // the provider registration is the one thing a custom endpoint needs,
            // and this is the only path that writes it. `writePiDefaults` below
            // has always had the guard; this writer lost it when it stopped
            // replacing the file wholesale.
            if (!target.isFile || target.readText() != encoded) {
                target.parentFile?.mkdirs()
                target.writeText(encoded)
                Log.i(TAG, "wrote ${CustomEndpoint.MODELS_FILE_RELATIVE}")
                true
            } else {
                false
            }
        }.onFailure { Log.w(TAG, "could not merge ${CustomEndpoint.MODELS_FILE_RELATIVE}", it) }
            .getOrDefault(false)
    }

    /**
     * Merges PiKit's tool list, the active profile's defaults and the bundled
     * web-access package into pi's own settings file.
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
    private fun writePiDefaults(settings: PiSettings) {
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
                bundledExtension = env.webAccessExtension,
            )
            val encoded = Json.encodeToString(JsonObject.serializer(), document)
            // Rewritten only when it differs, so an unchanged file keeps its
            // timestamp and pi's own settings writes are not raced.
            if (!target.isFile || target.readText() != encoded) {
                target.parentFile?.mkdirs()
                target.writeText(encoded)
                Log.i(
                    TAG,
                    "wrote $TOOL_DEFAULTS_FILE_RELATIVE defaultTools=${PiLaunchOptions.DEFAULT_TOOLS} " +
                        "defaultProvider=${settings.provider?.id.orEmpty()}",
                )
            }
        }.onFailure { Log.w(TAG, "could not write $TOOL_DEFAULTS_FILE_RELATIVE", it) }
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

        /** Long enough that typing a model id does not restart per keystroke. */
        private const val SETTINGS_RESTART_DEBOUNCE_MS = 1_500L

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
         * than bypassed, with the maintenance page's button (`PiUpdater`) as the manual
         * override for anyone who wants a model released an hour ago.
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
         * Where `/export` writes its HTML, under `$HOME`.
         *
         * `$HOME/export` rather than a hidden directory, because the Files tab is
         * rooted at `$HOME` and a folder nobody can see is a folder nobody opens.
         * pi is handed an absolute path inside the runtime's own filesystem, which
         * is the same filesystem this process writes the runtime into.
         */
        private const val EXPORT_DIRECTORY = "export"

        /** pi entry ids are eight hex characters. */
        private val ENTRY_ID_ALPHABET = ('0'..'9') + ('a'..'f')

        /** How much of a session file's tail to read when finding its last id. */
        private const val SESSION_TAIL_BYTES = 64 * 1024L

        private val ENVELOPE_ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

        @Volatile
        private var instance: PiAgentSession? = null

        fun of(context: Context): PiAgentSession =
            instance ?: synchronized(this) {
                instance ?: PiAgentSession(context).also { instance = it }
            }
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
 * pi's own settings with PiKit's tool list, the active profile's defaults and the
 * bundled web-access package merged in.
 *
 * `defaultProvider`/`defaultModel` are pi's keys for what a `pi` started without
 * `--provider`/`--model` should answer with (`dist/core/settings-manager.js:461-474`,
 * read by `dist/core/sdk.js:101-103`). PiKit gives the *agent* those two on the
 * command line, so the chat page worked while a `pi` typed in the terminal tab
 * reported no model at all: it had no argv and no defaults to fall back on.
 *
 * `packages` is where pi keeps the package sources it loads extensions from, and a
 * plain absolute path is pi's documented *local path* source. The bundled
 * `pi-web-access` extension is vendored under `$PREFIX` rather than installed, so
 * registering it here is what gives a hand-run `pi` the same extension the app's
 * agent has. Only a missing entry is added: an entry the user added — including one
 * for this very path, at some other spelling — is never removed or rewritten.
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
): JsonObject {
    var merged = settingsWithDefaultTools(existing, tools)

    // A `packages` key that is not an array is not this function's to reinterpret:
    // the write is skipped, the same way an unparseable `models.json` is left alone.
    // A user whose own entry names a *different* source is untouched either way —
    // this only ever appends the bundled directory when it is not already listed.
    val packages = existing["packages"]
    if (bundledExtension != null && (packages == null || packages is JsonArray)) {
        val path = bundledExtension.directory.absolutePath
        val listed = (packages as? JsonArray).orEmpty()
            .any { (it as? JsonPrimitive)?.contentOrNull == path }
        if (!listed) {
            merged = JsonObject(
                merged + ("packages" to JsonArray((packages as? JsonArray).orEmpty() + JsonPrimitive(path))),
            )
        }
    }

    if (!settings.isConfigured) return merged
    val provider = settings.provider ?: return merged
    merged = JsonObject(merged + ("defaultProvider" to JsonPrimitive(provider.id)))
    merged = JsonObject(merged + ("defaultModel" to JsonPrimitive(settings.modelId)))
    if (settings.thinkingLevel.isNotBlank()) {
        merged = JsonObject(merged + ("defaultThinkingLevel" to JsonPrimitive(settings.thinkingLevel)))
    }
    return merged
}

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
    customProviderId: String,
    customProvider: JsonObject?,
    /**
     * Per provider, PiKit's own `models` entries: the ids a catalogue check vouched for,
     * and every id the app has ever written one for. Empty for a provider the app has
     * nothing to say about — a custom endpoint's entries live in [customProvider] itself.
     * See [modelDefinitions].
     */
    definitions: Map<String, ModelDefinitions> = emptyMap(),
): JsonObject {
    val providers = LinkedHashMap<String, JsonElement>()
    (existing["providers"] as? JsonObject)?.forEach { (id, entry) -> providers[id] = entry }

    // Only PiKit's own provider id is ever withdrawn. A user's provider, and any
    // key inside one, is not this app's to remove.
    if (customProvider != null) {
        providers[customProviderId] = customProvider
    } else {
        providers.remove(customProviderId)
    }

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
            val stripped = JsonObject(entry - "input")
            if (stripped.isEmpty()) overrides.remove(id) else overrides[id] = stripped
        }
        for ((id, settings) in definition.overrides) {
            overrides[id] = modelOverrideFor(settings, overrides[id] as? JsonObject)
        }

        val next = LinkedHashMap<String, JsonElement>(current.orEmpty())
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
 */
internal data class ModelDefinitions(
    val wanted: List<ModelEntry> = emptyList(),
    val ours: List<String> = emptyList(),
    val overrides: Map<String, ModelSettings> = emptyMap(),
)

/**
 * PiKit's own entries for one provider — `models` for what pi does not know, `modelOverrides`
 * for what it does.
 *
 * The active provider is always in the answer, even with nothing wanted — that is not a
 * convenience but the withdrawal: an id that has left the profile, or one whose entry a
 * previous build wrote, is only found by visiting the provider in pi's file.
 *
 * ## Why the `models` half is gated on the catalogue basis
 *
 * [ModelProfile.writtenModels] is the record of a catalogue check, and this is where the
 * record is trusted — or not. What may be *written* as a definition is gated on
 * [catalogueBasis], because pi's catalogue gains models without this app doing anything:
 * `pi update --models` refreshes `models-store.json` from pi.dev, and a `pi` update can add
 * models to the built-in half. A definition left in place after pi learned the model would
 * *replace* pi's own entry for it — window, cost and thinking map included — which is
 * exactly the failure this whole path exists to avoid. So a basis that has moved withdraws
 * the definitions and lets the model page's own check re-write them.
 *
 * What may be *withdrawn* is deliberately wider than what may be written: [ours] is
 * reported whatever the basis says, because an entry whose basis has moved is precisely
 * the one that must come out of pi's file.
 *
 * ## Why the `modelOverrides` half is gated on it too, and what that costs
 *
 * An override merges, so a stale one cannot do the damage a stale definition can — but the
 * *set* of ids this function routes to `modelOverrides` is
 * [ModelProfile.knownCatalogueIds], and that set is a claim about the catalogue at the
 * moment of the check. A basis that has moved means the set may be wrong in the one
 * direction that matters: an id it calls unknown may be one pi has since started
 * cataloguing, and a `models` entry for such an id replaces pi's own window, cost and
 * thinking map. Gating both halves on the basis is what makes "the check still describes
 * the world" a single condition rather than one per mechanism.
 *
 * The cost is real and worth naming: pi updating its catalogue quietly returns a
 * catalogued model's declared window to pi's value until the model page is visited again.
 * That is the same trade every other declaration in this app makes — the alternative is a
 * number the user set three pi releases ago silently winning over the catalogue's — and
 * the page says what happened rather than reverting in silence.
 *
 * ## Which ids go where
 *
 * [ModelProfile.knownCatalogueIds] decides, and it is the answer the check recorded: an id
 * in it is an override (pi resolved it, so a definition would replace pi's own entry), and
 * an id outside it is a definition (pi did not, so an override would never be consulted).
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

    val overrides = declared
        .filter { (id, _) -> id in known }
        .toMap()

    val facts = profile.customModelFacts
    // The facts are the model pi resolves an unknown id to, and without them an entry
    // cannot be written faithfully — it would name pi's hard-coded defaults for a model
    // whose real window nobody asked pi about. The model page's controls are in the same
    // state (undrawable) whenever the check did not answer, so this refuses nothing the
    // user was able to ask for.
    //
    // The basis gate covers the overrides too, and for a reason of its own: the *set* it
    // was computed from is [ModelProfile.knownCatalogueIds], which is a claim about the
    // catalogue at that moment. A basis that no longer matches means that set may name an
    // id pi has since started cataloguing, and writing a definition for such an id is the
    // one unrecoverable mistake here.
    val wanted = if (facts == null || profile.catalogueBasis != basis) {
        emptyList()
    } else {
        declared.filterNot { (id, _) -> id in known }.map { (id, settings) ->
            ModelEntry(id, facts, settings)
        }
    }
    return mapOf(
        provider.id to ModelDefinitions(wanted = wanted, ours = ours, overrides = overrides),
    )
}

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
    val content = if (store.isFile) runCatching { store.readText() }.getOrNull() else null
    return "${provider?.id.orEmpty()}|$version|${catalogueRevision(content)}"
}

/**
 * Whether the model catalogue is due to be refreshed at [now].
 *
 * Pure and next to its two windows so the policy can be read and tested without a
 * device: a deadline of zero is "never refreshed", which is due.
 */
internal fun catalogueRefreshDue(now: Long, dueAt: Long): Boolean = now >= dueAt

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
internal fun catalogueRevision(text: String?): String {
    if (text.isNullOrBlank()) return CATALOGUE_UNREADABLE
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return CATALOGUE_UNREADABLE
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

private fun PiSettings.toLaunchOptions(
    nodePath: String,
    cliEntry: String,
    workingDir: String,
    sessionDir: String,
): PiLaunchOptions = PiLaunchOptions(
    nodePath = nodePath,
    cliEntry = cliEntry,
    workingDir = workingDir,
    sessionDir = sessionDir,
    provider = provider?.id,
    modelId = modelId.takeIf { it.isNotBlank() },
    thinkingLevel = thinkingLevel,
    apiKeyEnvVar = provider?.envVar,
    apiKey = apiKey.takeIf { it.isNotBlank() },
)
