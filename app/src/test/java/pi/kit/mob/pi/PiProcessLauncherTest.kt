package pi.kit.mob.pi

import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.catalogueRefreshEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent's command line.
 *
 * Small surface, high cost when wrong: `--api-key` was described in a comment but
 * never actually passed for the whole life of the project, and nothing noticed
 * because every built-in provider reads its own environment variable. It only
 * surfaced when a provider registered through `models.json` was used — that path
 * gets a `401` with the environment variable alone and succeeds with the flag.
 */
class PiProcessLauncherTest {

    private fun options(
        provider: String? = "deepseek",
        modelId: String? = "deepseek-chat",
        apiKey: String? = "sk-test",
        apiKeyEnvVar: String? = "DEEPSEEK_API_KEY",
        thinkingLevel: String? = "medium",
        offline: Boolean = false,
        noSession: Boolean = false,
    ) = PiLaunchOptions(
        nodePath = "/prefix/bin/node",
        cliEntry = "/prefix/cli.js",
        workingDir = "/home",
        sessionDir = "/home/pi-sessions",
        provider = provider,
        modelId = modelId,
        thinkingLevel = thinkingLevel,
        apiKeyEnvVar = apiKeyEnvVar,
        apiKey = apiKey,
        offline = offline,
        noSession = noSession,
    )

    private fun List<String>.valueOf(flag: String): String? {
        val at = indexOf(flag)
        return if (at >= 0 && at + 1 < size) get(at + 1) else null
    }

    @Test
    fun `passes the api key on the command line`() {
        val command = PiProcessLauncher.buildCommand(options(apiKey = "sk-abcdef123456"))

        assertEquals(
            "the key must be passed, not only exported, because a models.json " +
                "provider does not resolve its apiKey reference",
            "sk-abcdef123456",
            command.valueOf("--api-key"),
        )
    }

    @Test
    fun `omits the flag entirely when there is no key`() {
        for (blank in listOf(null, "", "   ")) {
            val command = PiProcessLauncher.buildCommand(options(apiKey = blank))
            assertTrue(
                "a blank key must not produce a bare --api-key (pi reads the next " +
                    "argument, so it would swallow the following flag)",
                command.none { it == "--api-key" },
            )
        }
    }

    @Test
    fun `refuses an environment reference instead of a key`() {
        // `$PIKIT_API_KEY` is what models.json holds; passing that string as the
        // key produces a 401 that names nothing.
        val failure = runCatching {
            PiProcessLauncher.buildCommand(options(apiKey = "\$DEEPSEEK_API_KEY"))
        }.exceptionOrNull()
        assertTrue("an unexpanded reference must be rejected, got $failure", failure is IllegalStateException)
    }

    @Test
    fun `carries the provider, model and thinking level`() {
        val command = PiProcessLauncher.buildCommand(
            options(provider = "pikit-custom", modelId = "mimo-v2.5", thinkingLevel = "high"),
        )
        assertEquals("pikit-custom", command.valueOf("--provider"))
        assertEquals("mimo-v2.5", command.valueOf("--model"))
        assertEquals("high", command.valueOf("--thinking"))
        assertEquals("rpc", command.valueOf("--mode"))
    }

    @Test
    fun `drops a thinking level pi would reject`() {
        // pi silently clamps an unknown level, so an invalid value looks like it
        // worked and the header disagrees with the process.
        val command = PiProcessLauncher.buildCommand(options(thinkingLevel = "turbo"))
        assertTrue("an unknown level must not be passed", command.none { it == "--thinking" })
    }

    @Test
    fun `never passes --tools`() {
        // `--tools` is not a list of tools to add: pi applies it as a strict
        // allowlist over built-in, extension and SDK tools alike, so passing the
        // seven built-ins here hid every tool an installed extension registered —
        // the extension loaded and the model never saw its tool. The built-ins are
        // written to `$HOME/.pi/agent/settings.json` instead.
        val command = PiProcessLauncher.buildCommand(options())
        assertTrue("--tools would filter out extension tools", command.none { it == "--tools" })
        assertTrue("and no bare list either", command.none { it.contains("read,bash,edit") })
    }

    @Test
    fun `the command starts with node and the cli entry`() {
        val command = PiProcessLauncher.buildCommand(options())
        assertEquals("/prefix/bin/node", command[0])
        assertEquals("/prefix/cli.js", command[1])
        // `--offline` is deliberately absent. It sets `PI_OFFLINE=1` for the whole
        // process, which pi reads as both "skip the update checks" **and** "the
        // model catalogue has no network" (`modelNetworkEnabled` is literally
        // `process.env.PI_OFFLINE === undefined`) — and the second half is why a
        // just-released model could not be selected. What replaced it is narrower:
        // `PI_TELEMETRY=0` keeps the no-telemetry promise, and the catalogue is
        // refreshed by the app when the store is older than pi's own four-hour window;
        // see the tests below.
        assertFalse(
            "--offline would turn off pi's model network as well as its update checks",
            command.contains("--offline"),
        )
        assertFalse(
            "--offline also exports PI_OFFLINE, so nothing may set it either",
            PiProcessLauncher.buildCatalogueCommand(options()).contains("--offline"),
        )
    }

    @Test
    fun `a read-only launch is offline and leaves no session`() {
        // The two throwaway processes that only read pi's catalogue: they pass both, and
        // the agent passes neither. `--offline` is what keeps a read from having the side
        // effect of updating what it is reading — pi's RPC mode otherwise starts a
        // fire-and-forget catalogue refresh that can land in the middle of the answer — and
        // `--no-session` is what keeps a check from leaving an empty conversation in the
        // History page.
        val readOnly = PiProcessLauncher.buildCommand(options(offline = true, noSession = true))

        assertTrue("offline", readOnly.contains("--offline"))
        assertTrue("no session", readOnly.contains("--no-session"))
        assertTrue("and still rpc mode", readOnly.valueOf("--mode") == "rpc")
        assertEquals("one flag each", 1, readOnly.count { it == "--offline" })
    }

    @Test
    fun `the catalogue refresh is pi's own update command, and carries no key`() {
        // pi's catalogue is what says a model's thinking levels, context window and
        // cost; without it pi resolves the model from a copy of the provider's
        // *default* one. `pi update --models` is pi's own way to refresh it
        // (`package-manager-cli.js`).
        val command = PiProcessLauncher.buildCatalogueCommand(options(apiKey = "sk-abcdef123456"))

        assertEquals(
            listOf("/prefix/bin/node", "/prefix/cli.js", "update", "--models"),
            command,
        )
        // Not the agent's flags: this is not RPC mode, and `--offline` would defeat
        // the entire point of running it.
        assertTrue("no --mode", command.none { it == "--mode" })
        assertTrue("no --offline", command.none { it == "--offline" })
        // The key goes through the environment here, and the reason is not only
        // `/proc`: `update` is parsed by the *package* CLI, which rejects an option
        // it does not know, so `--api-key` would make the refresh fail outright.
        assertTrue("no --api-key", command.none { it.startsWith("--") && it != "--models" })
        assertTrue("and no key either", command.none { it.startsWith("sk-") })
    }

    @Test
    fun `the catalogue refresh is due when it has never run, and not again inside the window`() {
        // The policy is pi's own four-hour freshness window, honoured by the app rather
        // than bypassed: `pi update --models` runs `refresh` with `force: true`, which
        // skips pi's own test, so without this the app would re-download all the built-in
        // providers' catalogues on every launch. The numbers are what keep that from
        // costing a phone with no network a timeout each time: a success buys four hours,
        // a failure buys half an hour.
        assertTrue("never refreshed", catalogueRefreshDue(now = 1_000, dueAt = 0))
        assertTrue("the deadline has passed", catalogueRefreshDue(now = 5_000, dueAt = 5_000))
        assertEquals(
            "a success is trusted for pi's own four-hour window",
            4 * 60 * 60 * 1000L,
            PiAgentSession.CATALOGUE_REFRESH_WINDOW_MS,
        )
        assertTrue(
            "a failure is retried sooner than a success is repeated",
            PiAgentSession.CATALOGUE_RETRY_MS in 1..PiAgentSession.CATALOGUE_REFRESH_WINDOW_MS,
        )
        // `catalogueRefreshDue` is the whole rule: due at or past the deadline.
        assertFalse("still inside the window", catalogueRefreshDue(now = 4_999, dueAt = 5_000))
    }

    @Test
    fun `the refresh process is given a credential for every built-in provider`() {
        // Without this `pi update --models` skips every provider pi does not consider
        // configured — `Models.refresh` bails out when `resolveRefreshCredential` answers
        // nothing — so the scan refreshed the profile's provider and left the rest of the
        // catalogue stale, which is the report this exists to answer.
        val env = catalogueRefreshEnvironment()
        // One entry per *variable*, not per provider: several provider ids share one —
        // `moonshotai`/`moonshotai-cn`, the three Qwen plans, `opencode`/`opencode-go` —
        // which is exactly why the environment cannot express "this provider only".
        assertEquals(
            "one entry per distinct credential variable",
            PiProvider.entries
                .filterNot { it in PiProvider.needsBaseUrl }
                .map { it.envVar }
                .toSet()
                .size,
            env.size,
        )
        PiProvider.entries.filterNot { it in PiProvider.needsBaseUrl }.forEach { provider ->
            assertTrue("${provider.id} has no credential", provider.envVar in env)
        }
        // A provider whose id is PiKit's own is not a provider pi can download a catalogue
        // for; a placeholder there would make an unconfigured relay look configured.
        PiProvider.needsBaseUrl.forEach { provider ->
            assertTrue("${provider.id} must not be given a placeholder", provider.envVar !in env)
        }
        // The value is never sent anywhere — pi.dev's per-provider catalogue is public and
        // the credential only decides *whether* pi asks — so it must not look like a
        // secret to anything reading a log.
        assertTrue(
            "the placeholder must not be key-shaped",
            env.values.all { !it.startsWith("sk-") },
        )
    }

    @Test
    fun `the placeholder keys are per launch and never part of the agent's environment`() {
        // The agent must not carry them: pi's "is this provider configured" test is what
        // the model list, the model picker and `get_available_models` are built from, so an
        // agent launched with thirty-two placeholders would report every provider and
        // ~1100 models as available and offer accounts the user does not have.
        val agent = options()
        assertTrue("the agent passes no extra environment", agent.extraEnv.isEmpty())
        // And the one launch that does pass them still lets the user's real key win: the
        // command-line key is written after `extraEnv`, which is what keeps a provider
        // authenticated with the key the user pasted.
        val refresh = options().copy(extraEnv = catalogueRefreshEnvironment())
        assertEquals(
            "a refresh does carry them",
            catalogueRefreshEnvironment().size,
            refresh.extraEnv.size,
        )
    }
}
