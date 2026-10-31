package pi.kit.mob.env

import android.content.Context
import android.os.Build
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
     * The directory the agent was spawned in: its working directory, and the writable
     * root the permission extension judges every write against.
     *
     * Published rather than assumed, because the reader picks the workspace from the
     * composer ([pi.kit.mob.data.WorkspaceStore]) and it is not always
     * `$HOME/workspace`. The extension falls back to `$HOME/workspace` when this is
     * absent, so a hand-run `pi` still gets the same rule.
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

        // Shared storage is not published any more. The page of folder grants is gone
        // (§14), and what the agent may write is the workspace it was started in — a
        // folder on the phone becomes writable by *being* that workspace, not by being
        // switched on. `~/storage` still exists as a convenience, created by the
        // installer and before every launch, and nothing has to agree with the
        // extension about it.

        // Which file states the permission tier. Published rather than assumed so the
        // extension has one documented source for it, and so a development build can
        // point the agent at another file; the extension's own fallback is the same
        // path, which is what a hand-run `pi` in the terminal tab relies on.
        put(PermissionPolicy.ENV_VAR, PermissionPolicy.file(env).absolutePath)

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
    ): Map<String, String> = build(
        context = context,
        env = env,
        extra = buildMap {
            // The directory the agent's own transcripts live in, so a `pi` typed here
            // `--continue`s the conversation the app is showing and a conversation
            // started here is in the app's list. The agent is launched with the same
            // value on its argv, so the two cannot drift; a shell that already exists
            // keeps the environment it was born with, which is the honest behaviour for
            // a PTY and the same rule the credential below has.
            put("PI_CODING_AGENT_SESSION_DIR", env.sessionDir.absolutePath)
            putAll(credentials)
        },
    )

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
     *  - `PIKIT_WORKSPACE` — the writable root the permission extension judges every
     *    write against; see [PiProcessLauncher]'s counterpart in
     *    `tools/pikit-permissions.ts` (§14).
     *
     * @param workspace the agent's working directory, and therefore the root a write
     *   may land in — it must be the same value the process is spawned in.
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
