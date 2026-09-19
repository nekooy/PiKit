package pi.kit.mob.pi

import android.content.Context
import android.util.Log
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.apiKeyEnvironment
import pi.kit.mob.data.catalogueRefreshEnvironment
import pi.kit.mob.env.ShellEnvironment
import pi.kit.mob.env.TermuxEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Progress of a `pi update` run. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object Running : UpdateStatus
    data class Succeeded(val version: String?) : UpdateStatus

    /** [output] is the tail of what the process printed, for the user to read. */
    data class Failed(val message: String, val output: String) : UpdateStatus
}

/**
 * Runs pi's own `update` command inside the bundled runtime.
 *
 * Implemented as `node <cli.js> update` — the CLI's supported path — rather than
 * by calling npm with the equivalent arguments here. pi resolves the install
 * method and the `--prefix` from where its own files live, which is exactly the
 * knowledge that would have to be duplicated (and kept in step with upstream)
 * to drive npm directly.
 *
 * ## Two runs, because one flag cannot do it
 *
 * The button used to run a bare `pi update`, which pi documents as *pi only* —
 * it prints `Extensions are skipped. Run pi update --extensions to update
 * extensions.` to a log nobody reads — so an installed extension never moved, and
 * the model catalogue was refreshed only by the launch path. The report named
 * exactly that ("pi and its extensions are never updated in time").
 *
 * `--all` and `--models` are mutually exclusive in pi's own parser (`--models
 * cannot be combined with --self, --extensions, --all`), so this runs two
 * commands in order:
 *
 *  1. `update --all` — pi itself and every installed package (extension).
 *  2. `update --models` — the model catalogue, which `--all` does not cover.
 *
 * The order matters: the first can replace the bundle on disk, and the second then
 * runs the *new* CLI. Both stream into the same visible log. The second is also the
 * app's **manual override** of the launch path's catalogue window
 * (`CATALOGUE_REFRESH_WINDOW_MS`): a model pi.dev published an hour ago is not due for
 * another three by the clock, and this button is how the user stops waiting for it.
 *
 * Two things about the environment matter:
 *
 *  - `PI_OFFLINE` must not be set. pi gates the version lookup on it, and an
 *    update that cannot ask what the latest version is fails at the first step.
 *    The agent itself is no longer launched `--offline` either, but the
 *    environment is rebuilt here rather than inherited, so this is stated for the
 *    next reader.
 *  - `PI_SKIP_VERSION_CHECK` is set so the CLI does not perform its own startup
 *    version check on top of the one the update command is about to do.
 *
 * stdout and stderr are read on their own threads and exposed line by line.
 * `npm install` is slow and silent between steps, and a screen that shows
 * nothing for a minute is indistinguishable from one that hung.
 */
class PiUpdater(
    private val context: Context,
    private val env: TermuxEnv,
    /**
     * The live settings, read when a phase is spawned — never captured at construction.
     * The updater is remembered for the life of the Settings screen and the user may
     * change the provider or its key while it sits there, so a value read once would
     * refresh the catalogue with a credential that is no longer the profile's.
     */
    private val settings: () -> PiSettings,
    private val onUpdated: () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _output = MutableStateFlow<List<String>>(emptyList())

    /** Everything the process printed, oldest first, capped at [MAX_OUTPUT_LINES]. */
    val output: StateFlow<List<String>> = _output.asStateFlow()

    /** Version currently on disk, re-read after an update. */
    private val _version = MutableStateFlow(PiInstallation.installedVersion(env))
    val version: StateFlow<String?> = _version.asStateFlow()

    private var job: Job? = null

    fun refreshVersion() {
        _version.value = PiInstallation.installedVersion(env)
    }

    /**
     * Starts an update. A second call while one is running is ignored rather
     * than queued: two concurrent `npm install -g` runs into the same prefix
     * corrupt each other.
     */
    fun start(force: Boolean = false) {
        if (job?.isActive == true) return

        val cliEntry = PiInstallation.cliEntry(env)
        if (cliEntry == null) {
            _status.value = UpdateStatus.Failed(
                "pi is not installed in the bundled runtime, so there is nothing to update.",
                "",
            )
            return
        }

        _output.value = emptyList()
        _status.value = UpdateStatus.Checking
        job = scope.launch { run(cliEntry, force) }
    }

    fun dismiss() {
        if (job?.isActive == true) return
        _status.value = UpdateStatus.Idle
        _output.value = emptyList()
    }

    private suspend fun run(cliEntry: File, force: Boolean) {
        _status.value = UpdateStatus.Running

        val phases = piUpdatePhases(force)

        var failure: String? = null
        for (phase in phases) {
            val command = listOf(env.node.absolutePath, cliEntry.absolutePath) + phase
            emit("$ ${command.joinToString(" ")}")

            val exit = try {
                val process = ProcessBuilder(command)
                    .directory(env.workspace.also { it.mkdirs() })
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(updateEnvironment())
                    }
                    .start()
                drain(process.inputStream)
                process.waitFor()
            } catch (t: Throwable) {
                Log.e(TAG, "pi update could not be started", t)
                _status.value = UpdateStatus.Failed(
                    "Could not start the updater: ${t.message ?: t::class.java.simpleName}",
                    tail(),
                )
                return
            }

            if (exit != 0 && failure == null) {
                failure = "pi ${phase.joinToString(" ")} exited with code $exit."
            }
        }

        refreshVersion()
        if (failure == null) {
            _status.value = UpdateStatus.Succeeded(_version.value)
            // The replacement is on disk but the running agent still holds the
            // old code, so it has to be respawned before the new version is what
            // the user is actually talking to.
            onUpdated()
        } else {
            _status.value = UpdateStatus.Failed(failure, tail())
        }
    }

    /**
     * The runtime environment, minus the offline flags the agent is launched with.
     * `PI_SKIP_VERSION_CHECK` is added because the command does its own version check and
     * a second one only adds latency.
     *
     * The two credential layers are here because the second phase *is* the catalogue
     * refresh, and a refresh visits only the providers pi considers configured. This
     * environment used to carry the app's own variables and nothing else — no provider key
     * at all — so `update --models` finished in a quarter of a second having written
     * nothing: the maintenance page's "check and update" button was the app's one manual
     * way to refresh a catalogue, and it did not do it. Measured before the fix: exit 0,
     * `Model catalogs refreshed`, `models-store.json` untouched.
     *
     * So: [catalogueRefreshEnvironment] for every built-in provider, then the active
     * profile's own key over it — the same order, for the same reason, as
     * [PiLaunchOptions.extraEnv]. The first phase (`update --all`) ignores both.
     */
    private fun updateEnvironment(): Map<String, String> =
        ShellEnvironment.build(context, env).toMutableMap().apply {
            // Belt and braces: `build` does not set it, but a `PI_OFFLINE` in the
            // app's own environment would be inherited by nothing (the map is
            // rebuilt) and must not be added here either — pi gates the version
            // lookup on it, which is the whole point of the updater.
            remove(OFFLINE_VAR)
            put(SKIP_VERSION_CHECK_VAR, "1")
            // No terminal behind this process; colour codes in the log would be
            // literal escape sequences on screen.
            put("NO_COLOR", "1")
            put("TERM", "dumb")
            putAll(catalogueRefreshEnvironment())
            putAll(apiKeyEnvironment(settings()))
        }

    /** Reads merged stdout/stderr into the visible log, line by line. */
    private fun drain(stream: java.io.InputStream) {
        val reader = stream.bufferedReader()
        try {
            reader.forEachLine(::emit)
        } catch (_: java.io.IOException) {
            // The process died mid-read; the exit code is the real signal.
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun emit(line: String) {
        _output.update { previous ->
            // npm prints progress with carriage returns, which arrive as a
            // trickle of near-identical lines; the cap keeps the flow bounded.
            (previous + line).takeLast(MAX_OUTPUT_LINES)
        }
    }

    private fun tail(): String = _output.value.takeLast(FAILURE_TAIL_LINES).joinToString("\n")

    private companion object {
        const val TAG = "PiKit"
        const val OFFLINE_VAR = "PI_OFFLINE"
        const val SKIP_VERSION_CHECK_VAR = "PI_SKIP_VERSION_CHECK"
        const val MAX_OUTPUT_LINES = 400
        const val FAILURE_TAIL_LINES = 40
    }
}

/**
 * The commands an update runs, in order, as the arguments after the CLI entry.
 *
 * Two, because pi's own parser rejects the combination a user would expect to work:
 * `--all` and `--models` are mutually exclusive (`--models cannot be combined with
 * --self, --extensions, --all, or --extension`). A bare `pi update` — which is what
 * this app used to run — updates **pi only** and prints
 * `Extensions are skipped. Run pi update --extensions to update extensions.` into a
 * log nobody reads, which is how an installed extension stayed at the version it was
 * installed at, and how the catalogue was refreshed only by the launch path.
 *
 * `--all` comes first because it can replace the bundle on disk, so the second run
 * uses the new CLI. Pure and top-level so the order and the flags are pinned by a
 * test: an update that silently stops updating the extensions is invisible until a
 * user notices an extension is old.
 */
internal fun piUpdatePhases(force: Boolean): List<List<String>> = listOf(
    buildList {
        add("update")
        add("--all")
        if (force) add("--force")
    },
    listOf("update", "--models"),
)
