package pi.kit.mob.pi

import android.content.Context
import pi.kit.mob.env.ShellEnvironment
import pi.kit.mob.env.TermuxEnv
import java.io.File

/**
 * Everything needed to start one `pi --mode rpc` process.
 *
 * @param nodePath absolute path to the bundled Node binary. This is *not*
 *   resolved through `PATH`: `execvp` consults the caller's environment, not
 *   the environment we hand the child, so a bare `node` would not be found.
 * @param cliEntry absolute path to pi's `dist/bundle/cli.js`. Pi is always
 *   launched as `node <cli.js>`, never through the `pi` shim or a shell: the
 *   shim is a shell script whose shebang would depend on `termux-exec`
 *   rewriting, and `sh -c` would break signal delivery and argument quoting.
 * @param workingDir the agent's sandbox root — `$HOME/workspace` by default. Also
 *   what the safety guard treats as its workspace, because it is what the child
 *   process is spawned in.
 */
data class PiLaunchOptions(
    val nodePath: String,
    val cliEntry: String,
    val workingDir: String,
    val sessionDir: String?,
    val provider: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
    val apiKeyEnvVar: String? = null,
    val apiKey: String? = null,
    val systemPromptAppend: String? = null,
    /**
     * The directory pi should treat as its agent directory, when a launch must not see
     * PiKit's own files in the real one. Null — every launch but one — uses pi's default,
     * `$HOME/.pi/agent`, which is where PiKit writes pi's settings, extensions and
     * `models.json`.
     *
     * The exception is the catalogue probe in `ModelDiscoveryClient`: an id's presence in
     * pi's catalogue cannot be asked about while PiKit's own `models.json` defines it,
     * because a `models` entry *is* what pi resolves the catalogue from. See
     * `ModelDiscoveryClient.catalogueProbe`.
     */
    val agentDir: String? = null,
    /**
     * Pass `--offline`, which stops every network operation pi would otherwise start by
     * itself. False for the agent, where the flag was removed on purpose: it also turns off
     * pi's update and catalogue checks, and the report that asked for them back was about a
     * `pi` the user had installed. True for the two throwaway processes that only *read*
     * pi's catalogue (`ModelDiscoveryClient`), where it is not a preference but a
     * correctness condition and a cost:
     *
     *  * pi's RPC mode starts a fire-and-forget `modelRuntime.refresh()` at launch
     *    (`main()`: `!offlineMode && appMode==="rpc"`), which fetches pi.dev's per-provider
     *    catalogue and publishes it into the running process. That is a refresh the app's
     *    own launch path has already done, it is a network round trip per provider on a
     *    phone, and — because it is published while the process is alive — it can move the
     *    answer out from under the question. With `--offline` the answer is a function of
     *    the catalogue state the caller recorded, which is what makes the recorded basis
     *    mean anything.
     *  * it also sets `PI_SKIP_VERSION_CHECK=1`, and pi's version check is a network call.
     *
     * A read-only question must not have the side effect of updating what it is reading.
     */
    val offline: Boolean = false,
    /**
     * Pass `--no-session`, so pi writes no session file.
     *
     * True for the throwaway processes: they are launched where a real session would be
     * written (pi's default session directory, which PiKit's own session list reads), and a
     * catalogue check that leaves an empty conversation behind would put a row in the
     * History page that nobody started. The agent never passes it — its session *is* the
     * point.
     */
    val noSession: Boolean = false,
    /**
     * Extra environment for *this* launch, written last among the credential layers.
     *
     * It exists for one caller: the catalogue refresh, which has to make pi consider
     * every built-in provider configured or `pi update --models` silently skips all but
     * the one the user has a key for (`catalogueRefreshEnvironment`). It is a per-launch
     * field rather than something `ShellEnvironment` does for everybody because the
     * agent process must *not* see it: pi's "configured" test is what the model list is
     * built from, so an agent launched with placeholder credentials would claim every
     * provider and every model is available.
     *
     * Ordering, which is the whole of the correctness here: [apiKeyEnvVar]/[apiKey] are
     * written after this, so the user's real key always wins over a placeholder for the
     * same variable (`PiProvider.MOONSHOTAI` and `MOONSHOTAI_CN` share one, as do the
     * three Qwen and two OpenCode ids). Values here also override the shell environment
     * for the same name, deliberately: a caller that names a variable means it.
     */
    val extraEnv: Map<String, String> = emptyMap(),
) {
    companion object {
        /**
         * The built-in tools PiKit turns on. pi enables only `read, bash, edit,
         * write` by default; we bake `rg` and `fd` into the image, so `grep` and
         * `find` are worth enabling too, and `ls` is pure JS and always works.
         *
         * These reach pi as `defaultTools` in `$HOME/.pi/agent/settings.json` and
         * **not** as `--tools`: `--tools` is a strict allowlist over built-in,
         * extension and SDK tools, so passing it hid every tool an installed
         * extension registered.
         */
        val DEFAULT_TOOLS = listOf("read", "bash", "edit", "write", "grep", "find", "ls")

        val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    }
}

/**
 * What pi would make of [level] for a model that supports [available].
 *
 * A copy of pi's own rule (`clampThinkingLevel` in `pi-ai/dist/models.js`): a level
 * the model supports is kept, and one it does not is walked **forward** to the next
 * level it does — falling back to lower ones only when there is nothing above.
 * That is why asking a DeepSeek model for "medium" leaves pi on "high": the
 * catalog's map has no `medium`, and `high` is the first supported level after it.
 *
 * The app needs this only to *display* the level before pi has answered — the
 * picker offers [available] itself, so a tap always lands on a supported level. It
 * is pure and lives here rather than in the UI so it is pinned by a test against
 * the same ordering pi uses; a second, differently-ordered list is exactly how the
 * two would drift.
 */
fun clampThinkingLevel(level: String, available: List<String>): String {
    if (available.isEmpty()) return level
    if (level in available) return level
    val from = PiLaunchOptions.THINKING_LEVELS.indexOf(level)
    if (from < 0) return available.first()
    for (index in from until PiLaunchOptions.THINKING_LEVELS.size) {
        val candidate = PiLaunchOptions.THINKING_LEVELS[index]
        if (candidate in available) return candidate
    }
    for (index in from - 1 downTo 0) {
        val candidate = PiLaunchOptions.THINKING_LEVELS[index]
        if (candidate in available) return candidate
    }
    return available.first()
}

/**
 * The levels to offer and to clamp against: pi's answer for the model that is
 * running, or the last answer it gave **for that same model**.
 *
 * The memory is a cache of pi's answer, and it is per model: the levels are a
 * property of the model's own `thinkingLevelMap`, so `deepseek-v4-pro`'s three and
 * `deepseek-flash`'s four are not interchangeable. Which is why the caller must pass
 * a list that has already been checked against the model in use
 * ([pi.kit.mob.data.PiSettings.rememberedThinkingLevels]) rather than the raw stored
 * one — remembering levels without remembering the model they were about is how a
 * picker comes to show the previous model's menu for the second between a switch and
 * pi's answer.
 *
 * What the memory buys is that the chip does not jump — the saved preference is
 * `medium`, the model offers `high` and `max`, and without a remembered list the chip
 * reads `medium` until `get_state` lands and then moves. It is *not* there to change
 * what is sent: the preference reaches `--thinking` untouched and pi clamps it, which
 * is the whole reason [clampThinkingLevel] exists as a display rule rather than a
 * rewrite of the setting.
 */
fun thinkingLevelsFor(live: List<String>, remembered: List<String>): List<String> =
    live.filter { it.isNotBlank() }.ifEmpty { remembered.filter { it.isNotBlank() } }

/**
 * Spawns the agent process inside the bundled Termux runtime.
 *
 * One deliberate choice, forced by how pi behaves:
 *  - The prompt is never passed on the command line. RPC mode ignores
 *    positional prompt arguments and treats `@file` arguments as fatal, so all
 *    input goes through the `prompt` command.
 *
 * ## Why the agent is *not* launched `--offline` any more
 *
 * It used to be, for the radio time: `--offline` sets `PI_OFFLINE=1` and
 * `PI_SKIP_VERSION_CHECK=1`, which suppresses pi's update check, its
 * package-update check, install telemetry and — the part that mattered —
 * `ModelRuntime`'s network flag, which is literally
 * `process.env.PI_OFFLINE === undefined`. On a phone that reads as "the model
 * list never moves", and it is what the report named: a model that had just been
 * released could not be selected, and neither pi nor its extensions were ever
 * offered an update.
 *
 * What replaced it is narrower and honest:
 *  - `PI_TELEMETRY=0` in the agent's environment keeps the no-telemetry promise
 *    the licence text makes, without disabling anything else (see
 *    `ShellEnvironment.forPiAgent`).
 *  - The model catalogue is the app's own two-speed job: it is refreshed on the launch
 *    path when the copy on disk is older than pi's own four-hour window, and on demand
 *    from the maintenance page's button — see [PiAgentSession.requestCatalogueRefresh]
 *    for why the app has to do it at all: pi's RPC mode does start a refresh of its own,
 *    but that one republishes the overlay inside the running process and never writes
 *    `models-store.json`, so dropping the flag would not have fixed the model list by
 *    itself.
 *  - pi *itself* is never updated on the device: it is an input of the runtime image the
 *    APK carries, so a new pi arrives with a new PiKit. The button that ran pi's own
 *    `npm install -g` here was removed after an interrupted run left a tree no agent
 *    start could read (see [CatalogueUpdater]).
 */
object PiProcessLauncher {

    fun launch(context: Context, env: TermuxEnv, options: PiLaunchOptions): Process =
        spawn(context, env, buildCommand(options), options, mergeStreams = false)

    /**
     * Runs pi's own model-catalogue refresh — `pi update --models` — and waits for it.
     *
     * ## Why the app has to do this at all
     *
     * pi resolves a model id its catalogue does not contain from a **copy of the
     * provider's default model** (`buildFallbackModel` in `core/model-resolver.js`),
     * and that copy keeps the *default* model's thinking-level map, context window,
     * cost and `input`. Measured against the bundled runtime, `--provider deepseek
     * --model deepseek-flash` with no catalogue answers
     * `get_available_thinking_levels` with **three** levels (`off, high, max` — the
     * map of `deepseek-v4-pro`) where pi.dev's catalogue gives the model four
     * (`off, low, high, max`), and the same mechanism turns an OpenAI `gpt-5.6-*`
     * model's six levels into `gpt-5.5`'s five. The warning pi prints on stderr —
     * `Model "…" not found for provider "…". Using custom model id.` — is that
     * path.
     *
     * The catalogue pi consults is `$HOME/.pi/agent/models-store.json`, and **only
     * pi's interactive TUI ever refreshes it**: `refreshModelCatalogs` is imported
     * by `interactive-mode.js` and the model selector, and by nothing on the RPC
     * path. That is why this function exists and why dropping `--offline` from the
     * agent's own command line was not enough on its own: an RPC-mode agent reads
     * whatever is in that store and never asks for a newer one, however online it
     * is.
     *
     * `pi update --models` is pi's own supported way to do it
     * (`package-manager-cli.js`, `refreshModelCatalogs`: `ModelRuntime.refresh` with
     * `allowNetwork: true, force: true`). Measured on the desktop bundle with one
     * configured provider: **0.67 s**, exit 0, `Model catalogs refreshed`, and one
     * `models-store.json` entry — `deepseek` with both of pi.dev's models at
     * pi.dev's own `lastModified`. It visits *configured* providers only (with no
     * credentials it finishes in 0.25 s having written nothing), so the cost is one
     * small HTTPS request per provider the user has a key for.
     *
     * The provider's key goes through the **environment**, never argv: `update` is
     * parsed by the package CLI, which rejects an option it does not know, and a
     * key in argv is visible to anything that can read `/proc`. A key is not needed
     * to *download* the catalogue, but it is what makes pi consider the provider
     * configured, so without it the refresh is a no-op.
     *
     * Failure is not fatal and is not reported to the user: the catalogue the app
     * already has is the one pi would have used anyway.
     *
     * @param extraEnv additional environment for the refresh process, written before the
     *   options' own credential. There is one caller and one reason: the app passes
     *   `catalogueRefreshEnvironment()` so the refresh covers every built-in provider
     *   rather than only the profile's. See [PiLaunchOptions.extraEnv].
     * @return the exit code, or null when the process had to be killed for running
     *   past [timeoutMs] — a phone with no network can lose the whole attempt, and
     *   nothing waits on it.
     */
    fun refreshCatalogue(
        context: Context,
        env: TermuxEnv,
        options: PiLaunchOptions,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = CATALOGUE_TIMEOUT_MS,
    ): Int? {
        val process = spawn(
            context = context,
            env = env,
            command = buildCatalogueCommand(options),
            options = options.copy(extraEnv = extraEnv),
            mergeStreams = true,
        )

        // Drained on its own thread rather than read after the wait: pi prints a line
        // per provider and an error line per failure, and a full pipe buffer would
        // block the child with the parent waiting on the child.
        val output = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }.apply { isDaemon = true }
        drain.start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            android.util.Log.w(TAG, "pi update --models ran past ${timeoutMs}ms and was killed")
            return null
        }
        drain.join(DRAIN_JOIN_MS)
        val code = process.exitValue()
        android.util.Log.i(TAG, "pi update --models: exit=$code ${output.toString().trim()}")
        return code
    }

    /** Shared by both launches: the exact argv is built by the caller. */
    private fun spawn(
        context: Context,
        env: TermuxEnv,
        command: List<String>,
        options: PiLaunchOptions,
        mergeStreams: Boolean,
    ): Process {
        // Logged with the key masked, because the arguments are otherwise exactly
        // what settles a disagreement between the header and Settings.
        android.util.Log.i(TAG, "launching: " + command.joinToString(" ") { maskKey(it) })
        val builder = ProcessBuilder(command)

        builder.directory(File(options.workingDir))
        builder.redirectErrorStream(mergeStreams)

        // Android's ProcessImpl passes this map as the child's complete
        // environment, so it must be fully populated rather than patched.
        val environment = builder.environment()
        environment.clear()
        environment.putAll(
            ShellEnvironment.forPiAgent(
                context = context,
                env = env,
                sessionDir = options.sessionDir?.let { File(it) },
                // The guard is told the same directory the process is spawned in,
                // so "inside the workspace" cannot mean two different things.
                workspace = File(options.workingDir),
            ),
        )
        // The placeholders go in *before* the real credential below, so that a provider
        // the user has a key for is authenticated with that key and not with the
        // placeholder. See [PiLaunchOptions.extraEnv].
        if (options.extraEnv.isNotEmpty()) {
            environment.putAll(options.extraEnv)
        }
        if (options.apiKeyEnvVar != null && !options.apiKey.isNullOrEmpty()) {
            environment[options.apiKeyEnvVar] = options.apiKey
        }
        // pi reads this in `getAgentDir()`; PiKit never sets it for the agent itself
        // (`$HOME/.pi/agent` is where this app writes pi's own configuration), and the
        // one launch that does is the catalogue probe. See [PiLaunchOptions.agentDir].
        options.agentDir?.takeIf { it.isNotBlank() }?.let {
            environment[AGENT_DIR_VAR] = it
        }
        // The key's *length* and the variable it went into, never the key. An
        // authentication failure that the app causes rather than the provider is
        // otherwise indistinguishable from a bad key, and this is the one fact
        // that separates them. Measured: a custom provider registered in
        // `models.json` rejects the environment variable and needs `--api-key`, and
        // this line is what made that visible.
        android.util.Log.i(
            TAG,
            "credentials: env=${options.apiKeyEnvVar ?: "none"} " +
                "envLen=${options.apiKeyEnvVar?.let { environment[it]?.length } ?: 0} " +
                "argvLen=${options.apiKey?.length ?: 0} " +
                "flagPassed=${command.contains("--api-key")}",
        )

        return builder.start()
    }

    /**
     * `pi update --models` — the catalogue refresh, and nothing else.
     *
     * Not `--mode rpc`, not `--offline`, and deliberately **no `--api-key`**: the key
     * goes through the environment (see [refreshCatalogue]), and the package CLI
     * rejects an option it does not know.
     */
    fun buildCatalogueCommand(options: PiLaunchOptions): List<String> = listOf(
        options.nodePath,
        options.cliEntry,
        "update",
        "--models",
    )

    /** Exposed separately so the exact argv can be asserted in tests/docs. */
    fun buildCommand(options: PiLaunchOptions): List<String> = buildList {        add(options.nodePath)
        add(options.cliEntry)
        add("--mode")
        add("rpc")
        // No `--offline` for the agent, deliberately: it set `PI_OFFLINE=1` for the whole
        // process, which turned off pi's model *network* flag as well. See the
        // note on [PiLaunchOptions.offline] — and on the read-only launches, which do pass
        // it, because for them the flag is what makes the answer a function of the
        // catalogue state the caller recorded.
        if (options.offline) add("--offline")
        // The throwaway launches never send a prompt, so there is nothing to save and a
        // session file in pi's default directory would show up as an empty conversation.
        if (options.noSession) add("--no-session")
        // Trust project-local resources once. RPC mode cannot prompt for trust
        // (it has no UI), so leaving this to the default silently ignores the
        // user's project settings.
        add("--approve")
        // No `--tools`, deliberately: it is not a list of tools to *add*. pi applies
        // it as a strict allowlist to built-in, extension and SDK tools alike, so it
        // removed every tool an installed extension registered — the extension
        // loaded and the model never saw its tool. The built-ins this app wants on
        // are written to `$HOME/.pi/agent/settings.json`'s `defaultTools` instead,
        // which selects built-ins only and leaves extension tools enabled (see
        // `PiAgentSession.writePiDefaults`).

        options.sessionDir?.let {
            add("--session-dir")
            add(it)
        }
        options.provider?.takeIf { it.isNotBlank() }?.let {
            add("--provider")
            add(it)
        }
        options.modelId?.takeIf { it.isNotBlank() }?.let {
            add("--model")
            add(it)
        }
        // The key goes on the command line as well as into the environment, and
        // for a custom provider it is the *only* thing that works.
        //
        // Measured, and it cost a long hunt: pi does **not** resolve the `$VAR`
        // reference in a `models.json` provider's `apiKey`. A provider registered
        // that way and given only its environment variable gets `401 无效的令牌`,
        // while the identical request with `--api-key` succeeds. The built-in
        // providers do read their environment variable, which is why this was
        // invisible until a relay was configured — the flag was described in the
        // comment above, and never actually passed.
        //
        // A real cost, accepted deliberately: this does put the key in argv, which
        // is readable through `/proc` by anything that can see this app's
        // processes. Without it a custom endpoint cannot authenticate at all, and
        // the alternative — asking the user to run `pi /login` — is not something
        // a phone UI can offer.
        options.apiKeyEnvVar?.let { variable ->
            options.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                // Refuse a reference rather than a secret: a value that is exactly
                // `$NAME` is a configuration mistake, and sending it produces a 401
                // that names nothing.
                check(!key.startsWith("$") || key.contains(" ")) {
                    "the API key for $variable looks like an environment reference " +
                        "($key); pass the key itself"
                }
                add("--api-key")
                add(key)
            }
        }
        options.thinkingLevel
            ?.takeIf { it in PiLaunchOptions.THINKING_LEVELS }
            ?.let {
                add("--thinking")
                add(it)
            }
        options.systemPromptAppend?.takeIf { it.isNotBlank() }?.let {
            add("--append-system-prompt")
            add(it)
        }
    }

    /**
     * Replaces the value after `--api-key` with its shape.
     *
     * The key is on the command line because a custom provider cannot
     * authenticate without it (see [buildCommand]), so it reaches logcat unless
     * something stops it. A length and two leading characters are enough to tell a
     * full key from a blank one, which is the only question this log is asked.
     */
    private fun maskKey(argument: String): String {
        if (argument.length < 8 || !argument.startsWith("sk-")) return argument
        return "${argument.take(5)}…(${argument.length} chars)"
    }

    private const val TAG = "PiKit"

    /**
     * The variable pi builds its agent directory from.
     *
     * Measured against the bundled bundle, not guessed: `getAgentDir()` is
     * `process.env[ENV_AGENT_DIR]` and `ENV_AGENT_DIR` is
     * `` `${APP_NAME.toUpperCase()}_CODING_AGENT_DIR` `` with `APP_NAME` = pi
     * (`dist/bundle/chunk-*.js`), so `PI_CODING_AGENT_DIR` and nothing else. PiKit names
     * it here rather than exporting it, so a launch that means to use the real agent
     * directory cannot acquire a second spelling of the same variable.
     */
    private const val AGENT_DIR_VAR = "PI_CODING_AGENT_DIR"

    /**
     * How long the catalogue refresh may take before it is killed.
     *
     * pi bounds *itself*: its own `refreshModelCatalogs` aborts the fetch after
     * `fetchWithRetry`'s per-attempt timeout of 4 s, and the whole operation is run
     * with a 15 s `AbortController`. This is the outer bound for the *process*, so a
     * pi that hangs on DNS cannot hold a thread.
     *
     * 60 s rather than the old 20 s, because the refresh now runs with a credential for
     * every built-in provider (`catalogueRefreshEnvironment`): pi issues one request per
     * provider, and while those are concurrent on a desktop bundle, a phone on a slow
     * radio with thirty-two of them cannot be held to pi's single-provider budget. The
     * cost of a longer bound is a thread asleep on a device with no network; the cost of
     * a short one is the refresh being killed mid-flight and every provider but the
     * profile's staying stale, which is the bug this exists to fix. Nothing waits on it.
     */
    private const val CATALOGUE_TIMEOUT_MS = 60_000L

    /** How long the output drain may take to finish after the process has exited. */
    private const val DRAIN_JOIN_MS = 500L
}
