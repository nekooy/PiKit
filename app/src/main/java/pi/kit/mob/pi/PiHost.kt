package pi.kit.mob.pi

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pi.kit.mob.data.ProfileAddressing
import pi.kit.mob.data.SettingsStore
import pi.kit.mob.data.WorkspaceStore
import pi.kit.mob.data.catalogueRefreshEnvironment
import pi.kit.mob.env.BootstrapInstaller
import pi.kit.mob.env.StorageAccess
import pi.kit.mob.env.TermuxEnv
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.terminal.TerminalSessionManager

/**
 * The app-wide half of the agent: the environment, the settings, and the conversations.
 *
 * ## Why this exists
 *
 * It used to be one object. `PiAgentSession` owned the unpacked runtime, the settings
 * store, the terminal's PTYs *and* the one `pi --mode rpc` child, which was right while
 * the app could only be in one conversation at a time: a move between conversations was
 * a `switch_session` on that one child, and pi's `teardownCurrent` aborts the running
 * turn as its first step, so a tap on another conversation stopped the answer.
 *
 * Two conversations at once cannot be one child, and two children cannot share one
 * environment object that each of them thinks it owns: the image is unpacked once, the
 * terminal's shells outlive the conversation that opened them, and pi's `models.json`
 * is one file. So the app-wide half moved here — exactly the parts whose answer is the
 * same for every conversation — and what is left in [PiAgentSession] is one process and
 * one transcript.
 *
 * ## What "the same for every conversation" means
 *
 *  * the unpacked image and its [RuntimeStatus] (one install, one set of stamps);
 *  * [SettingsStore] — a setting is a fact about the app, not about a transcript;
 *  * the terminal's sessions, which belong to the Terminal tab and not to a conversation;
 *  * pi's model catalogue (`models-store.json`) and the refresh window that guards it;
 *  * the preference that remembers which conversation a cold start should reopen;
 *  * the pool itself: which conversations are live, and which one the screen is on.
 *
 * Everything else — the child, its RPC client, the [ConversationState] the transcript is
 * drawn from, the workspace that child was launched in, the model it answers from, the
 * wake lock its turn holds — is per conversation, in [PiAgentSession].
 *
 * ## The pool is bounded
 *
 * A live conversation is a Node.js process of its own: ~120 MB resident before it has
 * read anything, so the pool is a memory budget rather than a taste — [MAX_LIVE] is the
 * point where a phone still has room for the transcript, the image's own tools and the
 * app itself. One more conversation is refused rather than allowed to end in an
 * allocation failure the reader cannot connect to anything. The ceiling is five because
 * a reader with several projects open asked for five; it is not a number to raise again
 * without a measurement on the smallest phone the app supports.
 *
 * A conversation that is closed is not lost: pi writes its transcript to its session
 * file as it goes, so closing is "stop the child", and reopening it from the history
 * list is the same as it ever was.
 */
class PiHost private constructor(context: Context) {

    val appContext: Context = context.applicationContext

    val env: TermuxEnv = TermuxEnv.of(appContext)

    val settingsStore: SettingsStore = SettingsStore(appContext)

    /**
     * The host's own scope, for work that must outlive a conversation.
     *
     * A conversation has a scope of its own; this one is for the environment (the
     * unpack, the catalogue refresh) and for anything that must keep running after the
     * conversation that asked for it was closed.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ----------------------------------------------------------- environment

    private val _runtime = MutableStateFlow<RuntimeStatus>(RuntimeStatus.NotInstalled)
    val runtime: StateFlow<RuntimeStatus> = _runtime.asStateFlow()

    private val environmentLock = Mutex()

    /**
     * Unpacks the bundled environment if needed. Cheap to call repeatedly: the
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
            //
            // The lock is the host's, not a conversation's: two conversations starting
            // together on a cold launch would otherwise both see `NotInstalled` and
            // unpack the same ~285 MB over the top of each other.
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
                // The packages a reinstall displaced are put back in the
                // background rather than awaited here: the pass can need a
                // network (a cached `.deb` covers most names offline, but not a
                // name whose archive apt has already dropped) and this lock is
                // what `startAgent` waits on. `restorePendingPackages` is
                // idempotent and trims its manifest as it goes, so a launch
                // killed mid-restore finishes the job on the next one.
                scope.launch {
                    runCatching { installer.restorePendingPackages() }
                        .onFailure { Log.w(TAG, "Restoring the user's packages failed", it) }
                    // Whatever happened, the ledger and the restore manifest are
                    // what the maintenance page reports; re-read them once the
                    // attempt has settled. Every live conversation draws that page, so
                    // every live conversation is asked.
                    sessions.value.forEach { it.refreshPackages() }
                }
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
     * Brings `~/storage` in line with the Android grant.
     *
     * There is no switch any more (§14): the farm is a convenience that exists while
     * "all files access" does and is taken away when it is not, which is what a revoked
     * grant has to mean — a directory of dead paths reads as a broken environment.
     *
     * Called before every launch and from `termux-setup-storage`'s broadcast, so a
     * failure is logged rather than surfaced: the rest of the runtime works without
     * shared storage, and a workspace on `/sdcard` is what actually needs the grant.
     */
    fun syncStorageLinks(): Boolean {
        val granted = StorageAccess.isGranted()
        val synced = runCatching { StorageAccess.syncFarm(env) }
            .onFailure { Log.w(TAG, "Could not reconcile the storage symlinks", it) }
            .getOrDefault(false)
        Log.i(TAG, "shared storage: granted=$granted farm=$synced")
        return synced
    }

    /**
     * PTY sessions shown in the terminal tab.
     *
     * Owned here rather than by a conversation: the terminal is one of four tabs and
     * leaves the composition whenever the user looks at another one, which used to
     * kill the shell along with it. A shell is not a conversation either — a
     * `pkg upgrade` started from the Terminal tab must survive a switch to another
     * conversation, and it does because nothing about this moved with the transcript.
     */
    val terminalSessions: TerminalSessionManager by lazy {
        TerminalSessionManager(appContext, env) {
            // Every saved profile's credential, not only the active one. A shell is the
            // other way into the same pi: the file it reads is the one this app writes,
            // and the variables its entries name are the ones here.
            ProfileAddressing.providerEnvironment(settingsStore.profiles.snapshots.value)
        }
    }

    // ------------------------------------------------------- model catalogue

    private val _catalogueThinkingLevels = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /**
     * The thinking levels each catalogued model declares, keyed by model id.
     *
     * pi's live answer (`get_available_thinking_levels`) is the authority for the model
     * that is running, and it stays first in `thinkingLevelsFor`. This is the *file*
     * the same facts are in, read ahead of the process: pi's store on disk carries every
     * built-in provider's catalogue (§7.2), so the composer can offer a model's own
     * levels on the frame the reader switches to it instead of two round trips later.
     *
     * Empty until [reloadCatalogueThinkingLevels] has read the store — on the launch
     * path and after a refresh — which reads as "nothing is known about this model" and
     * falls back to pi's seven, exactly as the app did before it had this.
     */
    val catalogueThinkingLevels: StateFlow<Map<String, List<String>>> =
        _catalogueThinkingLevels.asStateFlow()

    /**
     * When the next catalogue refresh is allowed, and whether one is running.
     *
     * `SharedPreferences` rather than a field: the deadline has to survive the
     * process, or a phone that is offline would attempt — and wait for — the refresh
     * on every launch.
     *
     * The refresh itself is the host's because the file it writes is the host's: one
     * `pi update --models` at a time, one deadline, one answer to "did the catalogue
     * move" — however many conversations are live. A per-conversation copy of that
     * flag would let two launches run two refreshes over one file.
     */
    private val cataloguePrefs by lazy {
        appContext.getSharedPreferences("pikit_model_catalogue", Context.MODE_PRIVATE)
    }

    private val catalogueRefreshRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Re-reads [catalogueThinkingLevels] out of pi's store on disk.
     *
     * Called on the launch path and after every catalogue refresh, which are the only
     * two moments the file can move: the store is written by `pi update --models` and by
     * nothing else. A read that finds an empty store — a fresh install before the first
     * refresh — leaves the published map alone rather than clearing what a previous read
     * found, because "the file is empty now" is not a fact about any model.
     */
    internal suspend fun reloadCatalogueThinkingLevels() {
        val levels = withContext(Dispatchers.IO) {
            readCatalogueThinkingLevels(File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME))
        }
        if (levels.isNotEmpty()) _catalogueThinkingLevels.value = levels
    }

    /**
     * Refreshes pi's own model catalogue when the copy on disk is old enough.
     *
     * Runs off the launch path, and nothing waits on it: this is a network round trip
     * and the launch is a tap. See [refreshCatalogueNow] for what it costs and why the
     * window exists.
     *
     * @param workingDir the directory pi is run from, which `update` inherits. The
     *   *active* conversation's, because a refresh started from a conversation keeps
     *   that conversation's environment; the answer is the same for all of them.
     */
    internal suspend fun refreshCatalogueIfStale(workingDir: File) {
        val due = catalogueRefreshDue(System.currentTimeMillis())
        // A profile whose `models.json` entry cannot be built without catalogue facts is a
        // reason to refresh *now*, even inside the retry window: the refresh is what makes
        // that entry possible, and the failures that empty a store are the ones that wrote
        // the window. See [needsCatalogueFacts].
        if (!due && !savedProfilesNeedCatalogueFacts()) return
        refreshCatalogueNow(workingDir)
    }

    /** Whether the launch path would refresh the catalogue at [now]. */
    private fun catalogueRefreshDue(now: Long): Boolean =
        catalogueRefreshDue(now, cataloguePrefs.getLong(KEY_CATALOGUE_DUE_AT, 0L))

    /** [needsCatalogueFacts] against the store on disk. */
    private fun savedProfilesNeedCatalogueFacts(): Boolean {
        val profiles = settingsStore.profiles.profiles
        if (profiles.isEmpty()) return false
        val store = File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME)
        val cache = HashMap<String, Map<String, JsonObject>>()
        return needsCatalogueFacts(profiles) { providerId ->
            cache.getOrPut(providerId) { storeModelFacts(store, providerId) }
        }
    }

    /**
     * Runs the catalogue refresh, whatever the deadline says, and reports what it found.
     *
     * The one entry point for both callers: the launch path
     * ([refreshCatalogueIfStale], which checks the window first) and the maintenance
     * page's own button ([requestCatalogueRefresh]). They have to behave identically —
     * one refresh in flight at a time, the same deadline written back, the same
     * "did anything change" test — or the button and the window would disagree about
     * what the store on disk means.
     *
     * @return true when the refresh succeeded *and* the catalogue it wrote differs from
     *   the one the agent would otherwise have resolved from.
     */
    private suspend fun refreshCatalogueNow(workingDir: File): Boolean {
        if (!catalogueRefreshRunning.compareAndSet(false, true)) return false
        val now = System.currentTimeMillis()
        try {
            val cliEntry = PiInstallation.cliEntry(env)?.absolutePath ?: return false
            val store = File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME)
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
                            sessionDir = env.sessionDir.absolutePath,
                            // `update --models` reads neither: its argv is built by
                            // `buildCatalogueCommand`, and the credential it needs comes
                            // from `extraEnv` below.
                            providerId = null,
                            credentials = emptyMap(),
                        ),
                        // Every built-in provider, not only the profile's: the point of
                        // this scan is the providers the user is *not* currently on, which
                        // is where the model they cannot select lives. The profile's own
                        // key still wins for the provider it names — see
                        // `catalogueRefreshEnvironment` and [PiLaunchOptions.extraEnv].
                        //
                        // The saved profiles' variables go on top, and they are not
                        // optional: `models.json` now names `$PIKIT_KEY_…` for every
                        // profile it registers, and pi resolves a provider's credential
                        // while refreshing — an unresolvable one aborts the whole refresh
                        // (`Could not refresh model catalogs: deepseek: Failed to resolve
                        // API key … PIKIT_KEY_…`), which is how a launch path that could
                        // not read the catalogue started building entries with no facts.
                        extraEnv = catalogueRefreshEnvironment() +
                            ProfileAddressing.providerEnvironment(
                                settingsStore.profiles.snapshots.value,
                            ),
                    )
                }.getOrNull()
            }
            // Scheduled from the attempt: a success is trusted for the full window
            // [PiAgentSession.CATALOGUE_REFRESH_WINDOW_MS] — pi's own freshness window,
            // and the reason a launch does not re-download thirty-two catalogues — while
            // a failure buys half an hour so an offline phone does not wait for the
            // timeout every time the agent starts.
            val succeeded = exit == 0
            cataloguePrefs.edit()
                .putLong(
                    KEY_CATALOGUE_DUE_AT,
                    now + if (succeeded) {
                        PiAgentSession.CATALOGUE_REFRESH_WINDOW_MS
                    } else {
                        PiAgentSession.CATALOGUE_RETRY_MS
                    },
                )
                .apply()
            if (!succeeded) return false
            // What the composer reads ahead of pi is this file, so it is re-read on
            // every successful refresh — including the one whose revision did not move,
            // which on a first run is the read that fills it.
            reloadCatalogueThinkingLevels()
            // The file is rewritten by every refresh; the catalogue is not. See the
            // note in `PiAgentSession`: comparing the file made this a restart loop.
            if (store.catalogueContent() == before) return false
            // Every live conversation reads the store at *its* startup, so every one of
            // them is stale — but only the idle ones are restarted: a turn in flight is
            // worth more than a correct level list, and the fresh catalogue is on disk
            // for the next launch either way.
            var restarted = false
            for (session in sessions.value) {
                if (session.turnInFlight) {
                    Log.i(TAG, "model catalogue changed; a live conversation keeps the old one until it restarts")
                    continue
                }
                session.scheduleRestart()
                restarted = true
            }
            if (restarted) Log.i(TAG, "model catalogue changed; restarting the idle conversations to read it")
            return restarted
        } finally {
            catalogueRefreshRunning.set(false)
        }
    }

    /**
     * The maintenance page's manual catalogue refresh, run to completion.
     *
     * The button is the *only* thing this adds over the launch path: pi's own
     * freshness window is four hours ([PiAgentSession.CATALOGUE_REFRESH_WINDOW_MS]), so
     * a model published an hour ago is not due for another three by the clock. Pressing
     * the button skips that test — nothing else about the refresh is different, which is
     * why this delegates to the same function the launch path uses.
     *
     * Only [refreshCatalogueNow] running is a failure here: the launch path was
     * already refreshing, so the answer the button was about to buy is on its way.
     * A refresh that succeeded without changing anything is a success — the model the
     * user was looking for may simply not exist yet.
     *
     * @return true when the catalogue actually moved, which is also the case where the
     *   agent is restarted to read it.
     */
    internal suspend fun requestCatalogueRefresh(): Boolean {
        val workspace = active.value.workspace.value
        val workingDir = File(workspace.ifBlank { WorkspaceStore.default(appContext, env).path })
        Log.i(TAG, "manual model catalogue refresh requested")
        return refreshCatalogueNow(workingDir)
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

    // ------------------------------------------------------------------ rebuild

    /** True while [rebuildEnvironment] is unpacking. */
    private val _rebuildRunning = MutableStateFlow(false)
    val rebuildRunning: StateFlow<Boolean> = _rebuildRunning.asStateFlow()

    /**
     * Re-extracts the whole runtime from the APK — the escape hatch the
     * seed/content split made rarely necessary.
     *
     * Deleting the stamps *is* the mechanism: [BootstrapInstaller.ensureInstalled]
     * compares them, and a missing stamp is a mismatch, so the next pass takes
     * the rebuild path — capture the user's packages, unpack the seed and the
     * content layer, replay what was captured. Nothing else is touched here;
     * the installer owns the rest. Every live agent is stopped first, because the
     * environment under all of them is about to be replaced.
     */
    fun rebuildEnvironment() {
        if (!_rebuildRunning.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                stopAllAgents()
                runCatching { env.stampFile.delete() }
                runCatching { env.contentStampFile.delete() }
                _runtime.value = RuntimeStatus.NotInstalled
                ensureEnvironment()
            } finally {
                _rebuildRunning.value = false
                sessions.value.forEach { it.refreshPackages() }
            }
        }
    }

    // ---------------------------------------------------------------- pool

    /**
     * Every live conversation, in the order they were opened.
     *
     * The screens draw one of them ([active]); this is what the conversation switcher
     * lists and what [PiAgentService] supervises.
     */
    private val _sessions = MutableStateFlow<List<PiAgentSession>>(emptyList())
    val sessions: StateFlow<List<PiAgentSession>> = _sessions.asStateFlow()

    /**
     * How many live conversations are answering right now.
     *
     * The service's one question. Zero is "nothing is being written", which is the
     * moment its shade entry goes away; the count is what its sentence names, so a
     * reader with two answers arriving sees that rather than a repeated "one".
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val streamingConversations: StateFlow<Int> = _sessions
        .flatMapLatest { live ->
            if (live.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(0)
            } else {
                kotlinx.coroutines.flow.combine(live.map { it.streaming }) { flags ->
                    flags.count { it }
                }
            }
        }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0)

    /**
     * The conversation the screen is on.
     *
     * A screen that is *about* a conversation (the chat, its history list acting on the
     * live transcript) reads this; a screen that is about one of them (the agent page's
     * process controls) takes it as a parameter, so a change of conversation is a
     * recomposition there rather than a silent retarget.
     */
    private val _active = MutableStateFlow(createConversation(bound = null))
    val active: StateFlow<PiAgentSession> = _active.asStateFlow()

    init {
        // The first conversation is the one the app restores into: it is the only one
        // that may bind to the conversation remembered from the last run, and it does so
        // on its first launch (see `PiAgentSession.restoreAfterLaunch`).
        _sessions.value = listOf(_active.value)
        _active.value.restoresRememberedSession = true
    }

    /**
     * Serialises the launch files, for the reason they are files.
     *
     * `models.json` and pi's `settings.json` are rewritten from the settings on every
     * launch, and two conversations starting together would otherwise write one file
     * twice at once — a torn document pi reads as an `Unknown provider` exit or as a
     * half-written model list. The lock is held across the write *and* the process
     * launch that reads it, so a second start cannot observe a document the first is
     * still replacing.
     */
    internal val launchFileLock = Mutex()

    private fun createConversation(bound: String?): PiAgentSession =
        PiAgentSession(this, bound)

    /**
     * Opens a new, empty conversation and makes it the active one.
     *
     * @return null when [MAX_LIVE] conversations are already open and none of them is
     *   idle; the caller says so rather than silently evicting a conversation whose
     *   answer is still arriving.
     */
    fun newConversation(): PiAgentSession? {
        val opened = makeRoom() ?: return null
        val session = opened
        _sessions.value = _sessions.value + session
        activate(session)
        return session
    }

    /**
     * Makes the conversation saved at [path] the active one.
     *
     * A conversation that is already live is *activated*, not reopened: that is the
     * whole point — a tap on the history list of a conversation that is answering
     * switches to it and leaves the answer running. Otherwise a conversation is opened
     * for it, bound to that file, and launched in the directory the session records.
     *
     * @return null when the pool is full of working conversations.
     */
    fun openConversation(path: String, workspace: String?): PiAgentSession? {
        val existing = sessionFor(path)
        if (existing != null) {
            activate(existing)
            return existing
        }
        val session = makeRoom(bound = path) ?: return null
        _sessions.value = _sessions.value + session
        // The workspace is fixed when the child starts, and a saved conversation records
        // the directory it ran in — opening it elsewhere would show one transcript while
        // every tool call acted in another tree.
        if (!workspace.isNullOrBlank() && File(workspace).isDirectory) {
            session.prepareWorkspace(workspace)
        }
        activate(session)
        return session
    }

    /**
     * The live conversation bound to [path], if there is one.
     *
     * A conversation is identified by the session file pi writes it to, which is also
     * what the history list keys on. A conversation that has not asked anything yet has
     * no file of its own; it is matched by nothing, which is correct — nobody can tap a
     * row for it.
     */
    fun sessionFor(path: String): PiAgentSession? =
        _sessions.value.firstOrNull { it.matchesPath(path) }

    /**
     * Makes [session] the one the screen is on, and remembers it for the next launch.
     *
     * Not a switch of any process: the conversation that was active keeps its child and
     * its turn, which is what having more than one conversation means.
     */
    fun activate(session: PiAgentSession) {
        if (session !in _sessions.value) {
            _sessions.value = _sessions.value + session
        }
        _active.value = session
        session.rememberAsLastConversation()
    }

    /**
     * Stops a conversation and takes it out of the pool.
     *
     * Its transcript is pi's, on disk, so nothing is lost: closing is "stop the child",
     * and the history list opens it again. The active conversation can be closed too —
     * the screen then moves to another live one, or to a fresh empty conversation when
     * that was the last.
     *
     * @return false when [session] is mid-turn and [interrupt] was not given, which is
     *   the caller's cue to ask first.
     */
    fun closeConversation(session: PiAgentSession, interrupt: Boolean = false): Boolean {
        if (session.turnInFlight && !interrupt) return false
        val remaining = _sessions.value - session
        _sessions.value = remaining
        session.shutdown()
        if (_active.value === session) {
            val next = remaining.lastOrNull() ?: createConversation(bound = null).also { fresh ->
                _sessions.value = listOf(fresh)
                fresh
            }
            _active.value = next
            next.rememberAsLastConversation()
        }
        return true
    }

    /** A conversation slot to open into, evicting the least recently used idle one. */
    private fun makeRoom(bound: String? = null): PiAgentSession? {
        if (_sessions.value.size < MAX_LIVE) return createConversation(bound)
        // Full: an idle conversation can be closed to make room, because its transcript
        // is on disk and reopening it is one tap. The active one and any conversation
        // with a turn in flight are not candidates — closing the one the reader is
        // looking at is not "making room", it is a surprise.
        val idle = _sessions.value
            .filter { it !== _active.value && !it.turnInFlight }
            .minByOrNull { it.lastUsedAt }
            ?: return null
        _sessions.value = _sessions.value - idle
        idle.shutdown()
        Log.i(TAG, "closed an idle conversation to open another one")
        return createConversation(bound)
    }

    /** Starts the active conversation's agent, for the launch path. */
    suspend fun startActive(): Boolean = _active.value.startAgent()

    /**
     * Schedules a restart of every live conversation.
     *
     * For a change to a file every child reads at *its* own startup — pi's `settings.json`
     * and the composed `AGENTS.md`. Each conversation waits out its own turn
     * (`scheduleRestart` polls `turnInFlight`), so a reader editing a setting while two
     * answers are arriving does not lose either.
     */
    fun restartAll() {
        _sessions.value.forEach { it.scheduleRestart() }
    }

    /** Stops every child, for the maintenance paths that replace the environment. */
    suspend fun stopAllAgents() {
        _sessions.value.forEach { it.stopAgent() }
    }

    // ------------------------------------------------------ remembered session

    /** Where the session the reader is in is remembered across process death. */
    private val sessionPrefs by lazy {
        appContext.getSharedPreferences("pikit_agent_session", Context.MODE_PRIVATE)
    }

    /**
     * Remembers the conversation a cold start should reopen.
     *
     * Written by whichever conversation is active, so a restart lands on what the
     * reader was looking at rather than on whichever child happened to write last.
     */
    internal fun rememberLastSession(path: String?) {
        sessionPrefs.edit().apply {
            if (path.isNullOrBlank()) remove(KEY_LAST_SESSION) else putString(KEY_LAST_SESSION, path)
            apply()
        }
    }

    internal fun lastRememberedSession(): String? =
        sessionPrefs.getString(KEY_LAST_SESSION, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "PiKit"

        /**
         * How many conversations may be live at once.
         *
         * Five, and the number is a memory budget rather than a taste: each live
         * conversation is one Node.js child (~120 MB resident before it reads a
         * transcript), so five is roughly 600 MB of resident processes on a phone whose
         * per-app heap is smaller than that — the rest lives in the children's own
         * address spaces, and the eviction rule ([makeRoom]) is what keeps the pool from
         * becoming a page file. A sixth is refused with a sentence rather than allowed
         * to end in an allocation failure nothing in the interface explains.
         */
        const val MAX_LIVE = 5

        private const val KEY_CATALOGUE_DUE_AT = "due_at"

        private const val KEY_LAST_SESSION = "last_session"

        @Volatile
        private var instance: PiHost? = null

        /** The one host of this process. */
        fun of(context: Context): PiHost =
            instance ?: synchronized(this) {
                instance ?: PiHost(context).also { instance = it }
            }
    }
}
