package pi.kit.mob.env

import java.io.File

/**
 * `AGENTS.md` — what pi reads before the model sees a prompt.
 *
 * ## Why this is not just an installer detail
 *
 * pi loads `$HOME/.pi/agent/AGENTS.md` automatically at startup, and PiKit writes it on
 * install: it describes the environment the agent is running in (the prefix, `$HOME`,
 * the workspace), the one directory a recursive delete may target, what the safety
 * extension refuses, which shared-storage folders the user granted, and how to leave
 * the phone tidy. It is part of the *context* rather than of the machinery — the
 * safety rules that must hold are enforced by the extension, and this file is what
 * tells the model about them.
 *
 * ## Once it exists, it is the user's
 *
 * The file is editable from **Settings → Agent context**, and that is why [install]
 * writes it only when it is missing. An update that silently replaced the file would
 * throw away the sentences a user added about their own project — and the file's own
 * rules are advice to the model, so a stale default costs a less well-informed agent
 * rather than an unguarded one. The settings page offers **Restore the default** for
 * a user who wants PiKit's text back.
 *
 * This is deliberately the opposite of `models.json` and `settings.json`, which the
 * launcher rewrites on every start: those are projections of what the app knows (the
 * profile, the provider), and a hand edit to one of them is a change the app cannot
 * see and would otherwise have no way back from. This one is a document.
 */
object AgentContext {

    /** The name pi looks for, in [TermuxEnv.piConfigDir]. */
    const val FILE_NAME = "AGENTS.md"

    /** The file itself — the same path [install] writes and the settings page edits. */
    fun file(env: TermuxEnv): File = File(env.piConfigDir, FILE_NAME)

    /**
     * Writes PiKit's own instructions if nothing is there yet, and leaves an existing
     * file exactly as it is. See this object's header for why.
     */
    fun install(env: TermuxEnv) {
        val file = file(env)
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(default(env))
    }

    /**
     * What a fresh install is told, as text.
     *
     * The paths are interpolated from [env] rather than written down, so a build for a
     * different application id — which moves the prefix — describes the prefix it
     * actually has.
     */
    fun default(env: TermuxEnv): String = """
        # Agent environment: PiKit on Android

        You are running inside a Termux-based Linux environment bundled in the
        PiKit Android app. There is no separate Termux installation.

        - Prefix: `${env.prefixPath}` (also `${'$'}PREFIX`)
        - Home: `${env.homePath}` (also `${'$'}HOME`)
        - Workspace: `${env.workspacePath}` — **work here**
        - Package manager: `pkg` / `apt` (requires network). Packages are relocated
          to this prefix automatically; install them normally.

        Already installed: Node.js, npm, the pi CLI, ripgrep (`rg`) and `fd`.
        Do not install those again.

        ## The workspace

        Your working directory is `${env.workspacePath}`. Every project you
        create, clone or build goes here. Do not work in `${'$'}HOME` itself: it
        holds pi's own configuration (`${'$'}HOME/.pi`), its saved sessions, the
        `storage` links and your workspace, and a path mistake there damages the
        agent rather than a project.

        ## Deleting things

        A `pikit-safety-guard` extension refuses destructive `bash` calls. It works
        on the command's text, so it is a rule to follow, not a fence to test.

        - **A recursive delete is allowed only inside `${env.workspacePath}`.**
          `rm -rf build`, `rm -rf ~/workspace/old`, `find ~/workspace -name '*.tmp'
          -delete` are fine. `rm -rf`, `rm -r`, `find -delete` and `find -exec rm`
          anywhere else — `${'$'}HOME` itself, `${'$'}HOME/tmp`, `~/storage/*`,
          `/sdcard`, `/data` — are refused, and the refusal comes back to you as a
          tool error.
        - **Outside the workspace, delete one file at a time.** `rm -f
          ~/storage/shared/Download/old.txt` and `rm -f `${'$'}HOME/notes.md` are
          allowed: a single named file is recoverable in a way a tree is not, and
          an agent that cannot remove a stale file is not usable. What is refused
          is removing a *directory* or a glob of them.
        - **Never delete or rewrite pi's own files**: anything under
          `${'$'}HOME/.pi` (the guard, `auth.json`, `settings.json`,
          `models.json`, `web-search.json`, `AGENTS.md`, saved sessions) and the
          app's `pikit-config.json` next to `${'$'}HOME`. They hold credentials and
          history, and overwriting one breaks the agent that is talking to you.
        - `mkfs`, `fdisk`, `wipefs`, `shred`, `dd of=/dev/block/…` and a redirect
          onto a raw device are refused outright. Nothing you are asked to do needs
          them.

        ## Shared storage — the user's own files

        `~/storage/*` points at the user's real phone storage: photos, documents,
        downloads, backups. Only the folders the user switched on exist there, and
        the guard refuses a path inside shared storage that they did not grant. It
        is not scratch space and it is not a project directory.

        - Reading is fine. Writing is fine when that is what the user asked for.
          Removing or overwriting is not, unless they asked for exactly that path.
        - If a task seems to need a recursive delete outside the workspace, say
          what you would delete and ask the user to confirm. Do not improvise, and
          do not try to work around the guard.

        ## Housekeeping — leave nothing behind

        Be tidy, every time. A phone's storage is small, and your leftovers are
        indistinguishable from the user's files to whoever has to clean up.

        - **After a task, clean up what you made to get there**: extracted
          archives, cloned and no-longer-needed repositories, downloaded
          installers, build logs, scratch scripts, `node_modules` of a throwaway
          test, temporary output. If you created it and it is not the deliverable,
          remove it before you report done.
        - Keep scratch files inside `${env.workspacePath}`, in a subdirectory you
          can delete in one call — `~/workspace/tmp/` is the natural place — rather
          than scattering them across `${'$'}HOME` or `${'$'}PREFIX/tmp`.
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
