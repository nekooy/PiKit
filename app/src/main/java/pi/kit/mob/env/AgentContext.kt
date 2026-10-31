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
 * folder it may write in, and how to leave the phone tidy. It is part of the *context*
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
     * The two documents the file pi reads is composed from.
     *
     * `AGENTS.md` is the only name pi looks for, so a second document cannot be a second
     * file it reads — it has to be *merged*. These are the two halves: the app's own
     * prompts, which PiKit maintains, and the reader's, which PiKit never touches.
     */
    const val AUTHOR_FILE_NAME = "AGENTS.author.md"
    const val USER_FILE_NAME = "AGENTS.user.md"

    /**
     * The generation of [default], in the first line of the file.
     *
     * Bumped whenever the default text changes in a way a stale copy would misreport.
     * 1 is the text that named `pikit-safety-guard` and had no marker at all.
     */
    const val GENERATION = 3

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

    /** The composed document — the one pi reads. Written by [compose], never edited. */
    fun file(env: TermuxEnv): File = File(env.piConfigDir, FILE_NAME)

    /** The app's own prompts: edited on the settings page, refreshed by generation. */
    fun authorFile(env: TermuxEnv): File = File(env.piConfigDir, AUTHOR_FILE_NAME)

    /** The reader's own instructions: created once, empty, and never written again. */
    fun userFile(env: TermuxEnv): File = File(env.piConfigDir, USER_FILE_NAME)

    /**
     * Writes the two halves if they are missing, refreshes the app's half when this build
     * carries a newer generation, and composes the document pi reads.
     *
     * The reader's half is created empty and left alone for ever: it is theirs. The app's
     * half is replaced when its marker is behind (see [isStaleDefault]) and kept
     * otherwise — which is what "我改了默认提示词，你没改我的 text 就别动它" means in one
     * rule: the generation says whether the *app's* text moved, not whether the reader
     * edited it.
     */
    fun install(env: TermuxEnv) {
        val author = authorFile(env)
        author.parentFile?.mkdirs()
        val current = if (author.isFile) author.readText() else null
        // A file from before the split has no author half: the old `AGENTS.md` is the
        // reader's now, and is kept as the user half rather than thrown away.
        val legacy = file(env)
        if (current == null && legacy.isFile) {
            val old = legacy.readText()
            if (isStaleDefault(old)) {
                // PiKit's own earlier text: replaced, and the reader's half starts empty
                // rather than inheriting a default they did not write.
                author.writeText(default(env))
            } else {
                // Someone's own document, written when there was one file. It becomes
                // their half; the app's half is written fresh.
                val user = userFile(env)
                if (!user.isFile) user.writeText(old)
                author.writeText(default(env))
            }
        } else if (current == null || isStaleDefault(current)) {
            author.writeText(default(env))
        }
        val user = userFile(env)
        if (!user.isFile) user.writeText("")
        compose(env)
    }

    /**
     * Rewrites the document pi reads from the two halves.
     *
     * Called after every install and after either half is edited, because pi reads the
     * file once at startup: a document composed on the next launch would leave the
     * running agent — and the one a reader just edited for — reading yesterday's rules.
     */
    fun compose(env: TermuxEnv) {
        val author = runCatching { authorFile(env).readText() }.getOrDefault(default(env))
        val user = runCatching { userFile(env).readText() }.getOrDefault("")
        val body = buildString {
            append(author.trimEnd())
            append('\n')
            if (user.isNotBlank()) {
                append('\n')
                append("<!-- The reader's own instructions, kept here by PiKit. -->")
                append('\n')
                append('\n')
                append(user.trimEnd())
                append('\n')
            }
        }
        file(env).writeText(body)
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

        Any directory can be a workspace — the app's own tree, `${'$'}HOME`, even the
        phone's storage under `/sdcard` — so `pwd` is the only thing that says where
        "inside" is, and a path outside it is a write this tier may refuse even when it
        looks like part of the same job. `${'$'}HOME` itself is a legal workspace and a
        poor one: it holds pi's configuration (`${'$'}HOME/.pi`), its saved sessions and
        the `storage` link, so a `workspace-write` agent started there may write the
        agent's own state by accident. Say so if you find yourself in it.

        ## Permissions — three tiers, enforced on every tool call

        PiKit judges every tool call against a tier the reader chooses in the
        composer, beside the input box:

        - **read-only** — inspection only. `write` and `edit` are refused, and so is
          any `bash` command that is not a read-only one (`cat`, `ls`, `rg`, `git
          log`, …). Use it to read and explain, and say what you would change.
        - **workspace-write** (the usual setting) — read anywhere, write only inside
          your workspace. A write, delete, move or redirect aimed anywhere else is
          refused.
        - **full-access** — no refusals at all.

        A refusal comes back to you as a tool error naming the tier and the path. Do
        not retry it and do not work around it: say what you were trying to change,
        and let the reader switch the tier if they meant to allow it.

        ## Deleting things

        Inside the workspace a recursive delete is fine at any depth: `rm -rf build`,
        `rm -rf ./old`, `find . -name '*.tmp' -delete`. Outside it — a sibling of the
        workspace, `${'$'}HOME/tmp`, `/sdcard`, `/data` — it is a write outside the
        workspace, and `workspace-write` refuses it. Ask the reader instead of
        improvising; starting a conversation *in* the folder is what makes it writable.

        ## Shared storage — the user's own files

        `~/storage/shared` is the whole of the user's phone storage: photos, documents,
        downloads, backups, and the folders inside it (`Download`, `DCIM`, `Documents`,
        …). It exists only while the app holds Android's file-access permission, and it
        is a convenience rather than a second permission: a write inside it is judged by
        the tier and by `pwd` like any other path, so reading is free and writing needs a
        workspace that contains the target. Treat the contents as the user's data rather
        than as scratch space, and never delete or overwrite something there unless that
        is exactly what you were asked to do.

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
