package pi.kit.mob.pi

import android.content.Context
import android.util.Log
import pi.kit.mob.env.ShellEnvironment
import pi.kit.mob.env.TermuxEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs the storage self-test and reports what it found.
 *
 * ## Why this is a separate process rather than app code
 *
 * The two questions worth asking about storage cannot be answered from inside the
 * app's own Kotlin with any confidence, because both have already been wrong once
 * in this project:
 *
 *  - **Can the environment reach the granted folders?** `Environment
 *    .isExternalStorageManager()` reports what Android recorded, and it has
 *    disagreed with what the mount actually allows. A probe from the app's
 *    process answers for the app; the agent and the terminal are *child* processes
 *    of it, and they are the ones that actually read the user's files.
 *  - **Does the delete guard hold?** The guard is `SafeDelete` plus the shell
 *    wrapper around the link farm. A test that reimplements either proves nothing
 *    about the code that ships.
 *
 * So the check is a shell script inside the runtime image
 * (`share/pikit/storage-self-test.sh`), run here as a child process with the same
 * environment the terminal and the agent get. It is also placed on `PATH` as
 * `pikit-storage-check`, so a user who prefers the terminal can run the identical
 * thing by name.
 *
 * Nothing here is on the app's critical path: a failure to spawn is reported as a
 * failed check, not thrown.
 */
class StorageSelfTest(
    context: Context,
    private val env: TermuxEnv,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed interface Status {
        data object Idle : Status

        data object Running : Status

        /** [output] is every line the script printed, in order. */
        data class Finished(val passed: Boolean, val output: List<String>) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null

    /** The script inside the runtime image. */
    private val script: File get() = File(env.prefix, SCRIPT_RELATIVE)

    /** Where the image also puts it, so it can be run by name. */
    private val onPath: File get() = File(env.binDir, "pikit-storage-check")

    fun start() {
        if (job?.isActive == true) return
        _status.value = Status.Running
        job = scope.launch { run() }
    }

    fun dismiss() {
        if (job?.isActive == true) return
        _status.value = Status.Idle
    }

    private suspend fun run() {
        val command = when {
            script.isFile -> listOf(env.bash.absolutePath, script.absolutePath)
            onPath.isFile -> listOf(env.bash.absolutePath, onPath.absolutePath)
            else -> {
                _status.value = Status.Finished(
                    passed = false,
                    output = listOf(
                        "The storage self-test is not in this runtime image.",
                        "Expected it at $SCRIPT_RELATIVE.",
                        "Rebuild the runtime with tools/build-runtime-image.py, or run",
                        "`pikit-storage-check` from the terminal if that exists.",
                    ),
                )
                return
            }
        }

        val output = ArrayList<String>(64)
        val exit = try {
            val process = ProcessBuilder(command)
                .directory(env.home.also { it.mkdirs() })
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(ShellEnvironment.build(appContext, env))
                }
                .start()
            // Read to the end before waiting: the script prints far less than a
            // pipe buffer, but waiting first would deadlock the day it does not.
            process.inputStream.bufferedReader().forEachLine { line ->
                if (output.size < MAX_LINES) output += line
            }
            process.waitFor()
        } catch (t: Throwable) {
            Log.e(TAG, "the storage self-test could not be started", t)
            _status.value = Status.Finished(
                passed = false,
                output = listOf("Could not start the self-test: ${t.message ?: t::class.java.simpleName}"),
            )
            return
        }

        if (output.isEmpty()) {
            output += "(the self-test printed nothing; exit code $exit)"
        }
        // The script's exit status and its own tally are made to agree, so either
        // one is a valid verdict. Trusting the exit code alone would hide a script
        // that was killed halfway.
        val tally = output.lastOrNull { it.startsWith("storage self-test:") }
        val passed = exit == 0 && tally?.contains(" 0 failed") == true
        Log.i(TAG, "storage self-test exited $exit: ${tally ?: "no tally"}")
        _status.value = Status.Finished(passed, output)
    }

    private companion object {
        const val TAG = "PiKit"

        /** Also generated into the image by tools/build-runtime-image.py. */
        const val SCRIPT_RELATIVE = "share/pikit/storage-self-test.sh"

        const val MAX_LINES = 400
    }
}
