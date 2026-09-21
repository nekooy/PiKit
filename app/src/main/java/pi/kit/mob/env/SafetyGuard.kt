package pi.kit.mob.env

import java.io.File

/**
 * The tool-call guard that PiKit bundles with the runtime, and whether it is in force.
 *
 * ## What it is
 *
 * `tools/pi-safety-guard.ts` is a pi extension: it subscribes to `tool_call`, which
 * pi fires *before* a tool runs, and its return value can refuse the call outright.
 * It is what refuses a recursive delete outside the workspace, a filesystem command,
 * a write to a raw block device, a delete or rewrite of pi's own credentials and
 * settings, and — through the policy PiKit publishes in the environment — a command
 * that names a folder of shared storage the user did not grant. `BootstrapInstaller`
 * copies it out of the image into pi's extension directory, and pi loads it there.
 *
 * ## Why there is a switch at all, and what it does not do
 *
 * The guard is a rule the agent is held to, not a sandbox: it matches paths in a
 * command's text, so a command that builds a path at runtime is not caught. A user
 * whose own work trips it — a script that really does need to clear a directory
 * outside the workspace — otherwise has one way out, which is deleting the extension
 * by hand in the terminal, and an extension deleted by hand is one that never comes
 * back with an update. So the switch is an *effectiveness* switch rather than a
 * removal: the file stays installed and loaded, and it does nothing while the switch
 * is off. [ENV_VAR] is how the app tells it so — the same mechanism the storage
 * policy uses, read once when the extension loads, which is why the agent is
 * restarted when the switch moves.
 *
 * Absent from the environment (a `pi` started outside PiKit) the guard runs: the
 * default of a missing variable must never be "off".
 */
object SafetyGuard {

    /**
     * `on` or `off`, in every process PiKit spawns.
     *
     * Published by [ShellEnvironment] rather than passed to the agent process alone,
     * so a `pi` the user types in the terminal tab is guarded exactly like the agent.
     */
    const val ENV_VAR = "PIKIT_SAFETY_GUARD"

    const val ENABLED = "on"

    const val DISABLED = "off"

    /** The module pi loads it from, inside its own `extensions` directory. */
    const val FILE_NAME = "pi-safety-guard.ts"

    /** Where [BootstrapInstaller] installs it and pi discovers it. */
    fun file(env: TermuxEnv): File = File(env.piConfigDir, "extensions/$FILE_NAME")

    /**
     * True when this runtime image actually carries the guard.
     *
     * A runtime built before the guard existed has no file here, and the switch must
     * not claim to turn something off that was never on: the settings page reads this
     * to grey the row out and says why, the way it reports a missing web-access
     * extension.
     */
    fun isInstalled(env: TermuxEnv): Boolean = file(env).isFile
}
