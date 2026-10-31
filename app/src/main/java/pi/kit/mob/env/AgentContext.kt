package pi.kit.mob.env

import java.io.File

/**
 * `AGENTS.md` — what pi reads before the model sees a prompt.
 *
 * ## Why this is not just an installer detail
 *
 * pi loads `$HOME/.pi/agent/AGENTS.md` automatically at startup, and PiKit writes it on
 * install: it describes the environment the agent is running in (the prefix, `$HOME`,
 * the workspace), the three permission tiers it may be judged by, which shared-storage
 * folders the user granted, and how to leave the phone tidy. It is part of the *context*
 * rather than of the machinery — the rules that must hold are enforced on every tool call
 * by `tools/pikit-permissions.ts`, and this file is what tells the model about them before
 * it writes a command at all.
 *
 * ## Once it exists, it is the user's
 *
 * The file is editable from **Settings → Agent process**, and that is why [install]
 * writes it only when it is missing. An update that silently replaced the file would
 * throw away the sentences a user added about their own project — and the file's own
 * rules are advice to the model, so a stale default costs a less well-informed agent
 * rather than an unguarded one. The settings page offers **Restore the default** for a
 * user who wants PiKit's text back.
 *
 * There is one exception, and it is a migration rather than a policy: a file that is
 * still the *previous* default — the one whose text described the single `pikit-safety-guard`
 * extension that the three tiers replaced — is rewritten once. That text states rules
 * this app no longer has (it tells the model a single-file delete outside the workspace
 * is allowed, which `workspace-write` now refuses), and a model acting on it would spend
 * a refusal learning the truth. The marker at the top of [default] is how a future change
 * tells the same story without guessing at the prose: an unedited default carries a
 * generation number, and a generation behind is rewritten, while anything else is left
 * alone.
 *
 * This is deliberately the opposite of `models.json` and `settings.json`, which the
 * launcher rewrites on every start: those are projections of what the app knows (the
 * profile, the provider), and a hand edit to one of them is a change the app cannot
 * see and would otherwise have no way back from. This one is a document.
 */
object AgentContext {

    /** The name pi looks for, in [TermuxEnv.piConfigDir]. */
    const val FILE_NAME = "AGENTS.md"

    /**
     * The generation of [default], in the first line of the file.
     *
     * Bumped whenever the default text changes in a way a stale copy would misreport.
     * 1 is the text that named `pikit-safety-guard` and had no marker at all.
     */
    const val GENERATION = 2

    /** The marker's prefix, so a reader can tell our own defaults from the user's words. */
    private const val MARKER = "<!-- pikit-default:"

    /**
     * The sentence only the retired default carries.
     *
     * A generation-0 file is one of those: written by a build before the three tiers,
     * with no marker to compare. This is the string that names the extension those
     * builds installed, and it is specific enough that a user's own prose will not
     * contain it by accident.
     */
    private const val RETIRED_MARK = "pikit-safety-guard"

    /** The file itself — the same path [install] writes and the settings page edits. */
    fun file(env: TermuxEnv): File = File(env.piConfigDir, FILE_NAME)

    /**
     * Writes PiKit's own instructions if nothing is there yet, and leaves an existing
     * file exactly as it is — unless it is an unedited default from an older generation.
     * See this object's header for why.
     */
    fun install(env: TermuxEnv) {
        val file = file(env)
        if (file.isFile && !isStaleDefault(file.readText())) return
        file.parentFile?.mkdirs()
        file.writeText(default(env))
    }

    /**
     * Whether [body] is one of PiKit's own earlier defaults rather than the user's file.
     *
     * A marker whose generation is behind, or no marker at all plus the retired guard's
     * name — the second test is what lets an install that predates the marker migrate.
     */
    internal fun isStaleDefault(body: String): Boolean {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (firstLine.startsWith(MARKER)) {
            val generation = firstLine
                .removePrefix(MARKER)
                .removeSuffix("-->")
                .trim()
                .toIntOrNull()
            return generation != null && generation < GENERATION
        }
        return RETIRED_MARK in body
    }

    /**
     * What a fresh install is told, as text.
     *
     * The paths are interpolated from [env] rather than written down, so a build for a
     * different application id — which moves the prefix — describes the prefix it
     * actually has. The workspace is the *built-in* one: this file is written once, and
     * a conversation may be started in another workspace, which the model reads from its
     * own working directory (`pwd`) rather than from here.
     */
    fun default(env: TermuxEnv): String = """
        $MARKER $GENERATION -->

        # Agent environment: PiKit on Android

        You are running inside a Termux-based Linux environment bundled in the
        PiKit Android app. There is no separate Termux installation.

        - Prefix: `${env.prefixPath}` (also `${'$'}PREFIX`)
        - Home: `${env.homePath}` (also `${'$'}HOME`)
        - The app's built-in workspace: `${env.workspacePath}`
        - Package manager: `pkg` / `apt` (requires network). Packages are relocated
          to this prefix automatically. Installing one writes `${'$'}PREFIX`, which is
          outside the workspace, so `workspace-write` refuses it — ask the reader to
          switch to `full-access` when a package is genuinely needed. A *project*
          install (`npm install`, no `-g`) writes inside the workspace and is fine.

        Already installed: Node.js, npm, the pi CLI, ripgrep (`rg`) and `fd`.
        Do not install those again.

        ## The workspace

        Your working directory is the *workspace of this conversation*, and `pwd`
        is the authoritative answer: the app's built-in one is
        `${env.workspacePath}`, and a reader who works on several projects puts each
        in its own workspace and picks it in the composer. Every project you create,
        clone or build belongs in the workspace you were started in.

        Do not work in `${'$'}HOME` itself: it holds pi's own configuration
        (`${'$'}HOME/.pi`), its saved sessions, the `storage` links and the
        workspaces, and a path mistake there damages the agent rather than a project.

        ## Permissions — three tiers, enforced on every tool call

        PiKit judges every tool call against a tier the reader chooses in the
        composer, beside the input box:

        - **read-only** — inspection only. `write` and `edit` are refused, and so is
          any `bash` command that is not a read-only one (`cat`, `ls`, `rg`, `git
          log`, …). Use it to read and explain, and say what you would change.
        - **workspace-write** (the usual setting) — read anywhere, write only inside
          your workspace and inside the shared-storage folders the reader granted.
          A write, delete, move or redirect aimed anywhere else is refused.
        - **full-access** — no refusals at all.

        A refusal comes back to you as a tool error naming the tier and the path. Do
        not retry it and do not work around it: say what you were trying to change,
        and let the reader switch the tier if they meant to allow it.

        ## Deleting things

        Inside the workspace a recursive delete is fine at any depth: `rm -rf build`,
        `rm -rf ./old`, `find . -name '*.tmp' -delete`. Outside it — `${'$'}HOME`,
        `${'$'}HOME/tmp`, `~/storage/*`, `/sdcard`, `/data` — it is a write outside
        the workspace, and `workspace-write` refuses it. Ask the reader instead of
        improvising.

        ## Shared storage — the user's own files

        `~/storage/*` points at the user's real phone storage: photos, documents,
        downloads, backups. Only the folders the reader switched on exist there, and
        only those are writable. Reading is free; treat the contents as the user's
        data rather than as scratch space, and never delete or overwrite something
        there unless that is exactly what you were asked to do.

        ## Housekeeping — leave nothing behind

        Be tidy, every time. A phone's storage is small, and your leftovers are
        indistinguishable from the user's files to whoever has to clean up.

        - **After a task, clean up what you made to get there**: extracted
          archives, cloned and no-longer-needed repositories, downloaded
          installers, build logs, scratch scripts, `node_modules` of a throwaway
          test, temporary output. If you created it and it is not the deliverable,
          remove it before you report done.
        - Keep scratch files inside the workspace, in a subdirectory you can delete
          in one call — `tmp/` is the natural place — rather than scattering them
          across `${'$'}HOME` or `${'$'}PREFIX/tmp`.
        - Do not delete anything you did not create, and do not "tidy" a project
          the user is working on. Cleaning up means your own by-products, not other
          people's files.
        - `${'$'}PREFIX/tmp` is cleared when the environment is replaced; a
          package you installed with `pkg install` is a change to the user's
          system, so announce it rather than silently keeping or removing it.
        - Say in your final message what you cleaned up, if anything.

        ## Device notes

        - You are on a phone. Prefer fast, bounded commands; the user waits for
          each tool call to return, so avoid unbounded or interactive commands.
        - `grep` and `find` are backed by `rg` and `fd`.
        - There is no `sudo`, no root, and no `perl`.

    """.trimIndent() + "\n"
}
