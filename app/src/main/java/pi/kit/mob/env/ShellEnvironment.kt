package pi.kit.mob.env

import android.content.Context
import android.os.Build
import pi.kit.mob.data.SettingsStore
import java.io.File

/**
 * Builds the environment block handed to every process we spawn inside the
 * bundled Termux runtime: the interactive shell shown in the terminal tab and
 * the `pi --mode rpc` agent process.
 *
 * This mirrors Termux's own `TermuxShellEnvironment`. Two details are load
 * bearing and easy to get wrong:
 *
 *  - Termux binaries on Android 7+ resolve their shared libraries through an
 *    absolute `DT_RUNPATH` compiled into each ELF, so `LD_LIBRARY_PATH` must
 *    NOT be set. Setting it can make the linker pick up incompatible libraries.
 *  - `libtermux-exec.so` must be preloaded so that the interpreter path in
 *    script shebangs is rewritten to the live prefix.
 */
object ShellEnvironment {

    /**
     * `host=address[,host=address]`, read from this process's environment.
     *
     * Only a development hook; see the note where it is used. Spelled with a
     * `PIKIT_` prefix so it cannot collide with a Termux or pi variable.
     */
    const val DNS_PINS_VAR = "PIKIT_DNS_PINS"

    /** The file alternative, in the app's private directory. See the note at use. */
    const val DNS_PINS_FILE = "dns-pins.txt"

    /**
     * Shared storage, to the agent's guard: which trees exist and which are granted.
     *
     * The guard in `tools/pi-safety-guard.ts` refuses a `bash` call that names a
     * path inside the first list but outside the second. This is the only place
     * that policy *can* be enforced: Android grants "all files access" to the
     * process, the agent runs as this app, and the storage page's switches would
     * otherwise only decide which `~/storage` links exist — which is a convenience
     * for the agent, not a limit on it.
     *
     * Both are colon-separated in every spelling (see `StorageAccess.spellingsOf`),
     * and the agent is restarted whenever the policy changes, so a cached copy
     * cannot go stale.
     */
    const val STORAGE_ROOTS_VAR = "PIKIT_STORAGE_ROOTS"

    const val STORAGE_ALLOWED_VAR = "PIKIT_STORAGE_ALLOWED"

    /**
     * The `~/storage` link farm, to the agent's guard: link name to target.
     *
     * Newline-separated `name=target` pairs, because a target is an absolute path
     * under `/sdcard` and a *custom* folder's name can legally contain a colon.
     * The guard has to be able to map the path a command actually names —
     * `~/storage/shared/Download/a.txt` — onto the shared-storage path the policy
     * is written in, or the folder switches are defeated by the one spelling PiKit
     * itself offers the agent (measured: `cat ~/storage/shared/Download/a.txt` was
     * allowed with no folder granted, while `cat /sdcard/Download/a.txt` was
     * refused).
     *
     * Only the links the farm will actually hold are published
     * ([StorageAccess.linkPlanFor]), so a revoked folder has no name to translate.
     */
    const val STORAGE_LINKS_VAR = "PIKIT_STORAGE_LINKS"

    /**
     * The agent's workspace, to the agent's guard: the one directory under
     * `$HOME` a recursive delete may target.
     *
     * Published rather than assumed, because the user can point the working
     * directory anywhere ([pi.kit.mob.data.PiSettings.workingDir]). The guard falls
     * back to `$HOME/workspace` when this is absent, so a hand-run `pi` still gets
     * the same rule.
     */
    const val WORKSPACE_VAR = "PIKIT_WORKSPACE"

    fun build(
        context: Context,
        env: TermuxEnv,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        // Values Android binaries expect to find.
        put("ANDROID_ROOT", System.getenv("ANDROID_ROOT") ?: "/system")
        put("ANDROID_DATA", System.getenv("ANDROID_DATA") ?: "/data")
        put("EXTERNAL_STORAGE", System.getenv("EXTERNAL_STORAGE") ?: "/sdcard")

        // The Termux contract.
        put("HOME", env.homePath)
        put("PREFIX", env.prefixPath)
        put("TMPDIR", env.tmpDir.absolutePath)
        put("PATH", env.binDir.absolutePath)
        put("SHELL", env.bash.absolutePath)

        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "en_US.UTF-8")

        // App identity, used by termux-tools scripts and by anything that wants
        // to know it is running inside a Termux-like environment.
        //
        // The version is the bundled *environment's*, not this app's: every consumer
        // of this variable is asking which Termux is hosting the shell, and pi only
        // asks whether it is set at all. See `BundledImage.termuxVersion`.
        put("TERMUX_VERSION", BundledImage.termuxVersion(context))
        put("TERMUX_MAIN_PACKAGE_FORMAT", "debian")
        put("TERMUX_APP__PACKAGE_NAME", context.packageName)
        put("TERMUX_APP__FILES_DIR", env.filesDir.absolutePath)
        put("TERMUX_APP__TARGET_SDK", context.applicationInfo.targetSdkVersion.toString())
        put("TERMUX_APP__PID", android.os.Process.myPid().toString())
        put("TERMUX_APP__UID", android.os.Process.myUid().toString())
        put("TERMUX_APP__PACKAGE_MANAGER", "apt")
        put("TERMUX_APP__PACKAGE_VARIANT", "apt-android-7")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("ANDROID_ART_ROOT", System.getenv("ANDROID_ART_ROOT") ?: "/apex/com.android.art")
        }

        if (env.termuxExecLib.isFile) {
            put("LD_PRELOAD", env.termuxExecLib.absolutePath)
        }

        // Which of the user's folders the agent may reach. The policy is only
        // meaningful once Android has granted access at all: without that, the app
        // cannot read the tree either, and telling the guard that folders are
        // allowed would only produce a refusal from the kernel instead of from us.
        //
        // Read here rather than passed in because this function is the one place
        // every spawned process's environment is built, and a policy that reached
        // the agent process but not the interactive shell — where a user can run
        // `pi` by hand — would be two answers to one question.
        val granted = if (StorageAccess.isGranted()) {
            StorageAccess.readPolicy(context)
        } else {
            StorageAccess.Policy.NONE
        }
        put(STORAGE_ROOTS_VAR, StorageAccess.rootSpellings().joinToString(":"))
        put(STORAGE_ALLOWED_VAR, StorageAccess.allowedSpellings(granted).joinToString(":"))
        // The farm the environment offers, by name: blank when nothing is granted,
        // which is also when `~/storage` does not exist at all.
        put(
            STORAGE_LINKS_VAR,
            StorageAccess.storageLinksValue(
                StorageAccess.linkPlanFor(
                    if (granted.isEmpty) StorageAccess.Policy.NONE else granted,
                ),
            ),
        )

        // Whether the tool-call guard is in force, for the same reason and with the
        // same reach: the extension reads it once when pi loads, and a `pi` typed in
        // the terminal has to be guarded — or unguarded — exactly like the agent.
        // `on` is written rather than omitted so that the switch's state is visible
        // in `/proc/<pid>/environ` instead of being an absence.
        put(
            SafetyGuard.ENV_VAR,
            if (SettingsStore.safetyExtension(context)) SafetyGuard.ENABLED else SafetyGuard.DISABLED,
        )

        // Development hook: pin a hostname to an address inside Node.
        //
        // An Android emulator cannot resolve names — its `getaddrinfo` goes through
        // `netd`, so `resolv.conf` is ignored, and the Play-Store system image is a
        // `user` build, so `/etc/hosts` is not writable. Measured on the guest: TLS
        // to the relay's address with the right SNI succeeds and `/v1/models`
        // answers 200, while the same request by name fails with `ENOTFOUND`. The
        // network is fine; only the lookup is missing, and without a way to supply
        // it the app's whole provider path can never be exercised on an emulator.
        //
        // Two sources, because neither works alone: an environment variable is what
        // a launch inherits, but nothing on release Android lets a test set one for
        // an app, and a file in the app's private directory is writable by `adb
        // shell run-as` but is not something a user would ever create. Together they
        // mean the hook is available to a test and inert on a device.
        //
        // `tools/dns-pin.cjs` does the patching and does nothing unless the pin is
        // present, so a leaked module cannot affect a real install.
        val pins = (System.getenv(DNS_PINS_VAR)?.takeIf { it.isNotBlank() }
            ?: File(env.filesDir, DNS_PINS_FILE).takeIf { it.isFile }?.readText()?.trim())
            ?.takeIf { it.isNotBlank() }
        val pinModule = File(env.filesDir, "dns-pin.cjs")
        if (pins != null && pinModule.isFile) {
            put(DNS_PINS_VAR, pins)
            val existing = System.getenv("NODE_OPTIONS").orEmpty()
            put("NODE_OPTIONS", "$existing --require ${pinModule.absolutePath}".trim())
        }

        putAll(extra)
    }

    /**
     * Environment for the interactive login shell shown in the terminal tab.
     *
     * [credentials] is the active profile's key, under the same variable name the
     * agent is launched with. Without it a `pi` typed in the terminal has no auth
     * for any provider, so `findInitialModel` finds no *available* model and prints
     * "No model selected." — which is what the report described while the chat page
     * worked, because the agent gets the model on its command line.
     */
    fun forLoginShell(
        context: Context,
        env: TermuxEnv,
        credentials: Map<String, String> = emptyMap(),
    ): Map<String, String> = build(context, env, credentials)

    /**
     * Environment for the agent process. Pi is long-lived and non-interactive,
     * so it is told not to emit terminal control sequences it cannot use and is
     * pointed at the bundled Node runtime explicitly.
     *
     * Two pi variables are set deliberately:
     *
     *  - `PI_TELEMETRY=0` — pi posts one request to `pi.dev/api/report-install`
     *    after a version change unless it is told not to (`reportInstallTelemetry`
     *    in its interactive mode; the flag is documented as `PI_TELEMETRY`). The
     *    agent is no longer launched `--offline` — that flag also disabled the
     *    update check and the model catalogue, which the user asked to have back —
     *    so the promise the licence text makes ("no telemetry") is kept by this
     *    variable instead of by the blanket offline flag.
     *  - `PIKIT_WORKSPACE` — the directory a recursive delete may target; see
     *    [PiProcessLauncher]'s counterpart in `tools/pi-safety-guard.ts`.
     *
     * @param workspace the agent's working directory. Also what the guard treats as
     *   the workspace, so it must be the same value the process is spawned in.
     */
    fun forPiAgent(
        context: Context,
        env: TermuxEnv,
        sessionDir: File?,
        workspace: File = env.workspace,
    ): Map<String, String> = build(
        context = context,
        env = env,
        extra = buildMap {
            put("NO_COLOR", "1")
            put("PI_CODING_AGENT", "true")
            put("AI_AGENT", "pi")
            put("PI_TELEMETRY", "0")
            put(WORKSPACE_VAR, workspace.absolutePath)
            if (sessionDir != null) put("PI_CODING_AGENT_SESSION_DIR", sessionDir.absolutePath)
        },
    )
}
