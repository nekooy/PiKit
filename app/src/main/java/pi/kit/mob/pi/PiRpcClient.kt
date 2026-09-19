package pi.kit.mob.pi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Raised for protocol-level failures: timeouts, rejected commands, transport loss. */
class PiRpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The agent process ended. [stderr] holds its diagnostics: pi writes nothing to
 * stdout before its first response, so "exited without ever producing a record"
 * is how a startup failure (bad config, no model, extension load error) shows up.
 */
class PiProcessExitedException(
    val exitCode: Int,
    val stderr: String,
) : Exception(
    buildString {
        append("pi exited with code ").append(exitCode)
        if (stderr.isNotBlank()) append(":\n").append(stderr.trim())
    },
)

/**
 * Client for one `pi --mode rpc` process.
 *
 * Designed around the protocol's sharper edges, each of which was confirmed
 * against a live pi 0.85.1 process:
 *
 *  - stdout carries protocol only; all diagnostics go to stderr, which is
 *    drained on its own thread into a bounded buffer.
 *  - Responses are correlated by `id`, never by order: pi answers `prompt`
 *    immediately and keeps processing, so replies can interleave with events
 *    and with each other.
 *  - A response whose `id` is not pending is surfaced as an ordinary record
 *    rather than dropped — that is how pi reports parse errors, which carry no
 *    `id` at all.
 *  - There is no shutdown command: closing stdin is the graceful path and makes
 *    pi flush stdout and exit 0. `SIGTERM` skips the flush, so it is only a
 *    fallback.
 */
// `CompletableDeferred.getCompleted()` is still marked experimental; it is
// exactly the "read only if already completed" accessor needed here to avoid
// blocking a reader thread on the process exit code.
@OptIn(ExperimentalCoroutinesApi::class)
class PiRpcClient internal constructor(
    private val stdout: InputStream,
    private val stdin: OutputStream,
    private val stderr: InputStream,
    private val requestClose: () -> Unit,
    private val awaitExit: () -> Int,
    private val forceKill: () -> Unit,
) {

    private val channel = Channel<PiRecord>(Channel.UNLIMITED)

    /** Every record that is not a correlated response, in arrival order. */
    val records: Flow<PiRecord> = channel.receiveAsFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<PiRecord.Response>>()
    private val idCounter = AtomicLong()
    private val stdoutSeen = AtomicBoolean(false)
    private val writeLock = Any()

    private val stderrLock = Any()
    private val stderrBuffer = StringBuilder()

    private val exited = CompletableDeferred<Int>()

    private var started = false

    /** Starts the reader threads. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread(::pumpStdout, "pikit-pi-stdout").apply { isDaemon = true }.start()
        Thread(::pumpStderr, "pikit-pi-stderr").apply { isDaemon = true }.start()
        Thread(::awaitProcessExit, "pikit-pi-wait").apply { isDaemon = true }.start()
    }

    // -------------------------------------------------------------- requests

    /**
     * Sends one command and waits for its response.
     *
     * @param timeout how long to wait. Pass `null` for commands that
     *   legitimately block: `abort` only answers once the agent is idle, and
     *   `compact` / `bash` / `prompt` can run for a long time.
     */
    suspend fun request(
        type: String,
        timeout: Duration? = DEFAULT_TIMEOUT,
        build: (id: String) -> String,
    ): PiRecord.Response {
        val id = "r${idCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<PiRecord.Response>()
        pending[id] = deferred
        try {
            send(build(id))
            val response = if (timeout == null) {
                deferred.await()
            } else {
                withTimeoutOrNull(timeout) { deferred.await() }
                    ?: throw PiRpcException("Timed out after $timeout waiting for '$type'")
            }
            return response
        } finally {
            pending.remove(id)
        }
    }

    /** As [request], but throws when pi reports `success: false`. */
    suspend fun requestOrThrow(
        type: String,
        timeout: Duration? = DEFAULT_TIMEOUT,
        build: (id: String) -> String,
    ): PiRecord.Response {
        val response = request(type, timeout, build)
        if (!response.success) {
            throw PiRpcException(response.error ?: "pi rejected '$type'")
        }
        return response
    }

    /**
     * Writes a raw record. The payload must be a JSON object: pi dereferences
     * `command.id` without a guard, so a bare `null` line crashes it.
     */
    fun send(raw: String) {
        require(raw.startsWith("{")) { "RPC records must be JSON objects, got: ${raw.take(64)}" }
        synchronized(writeLock) {
            try {
                stdin.write(raw.toByteArray(Charsets.UTF_8))
                stdin.write('\n'.code)
                stdin.flush()
            } catch (e: IOException) {
                throw PiRpcException("Lost the connection to pi", e)
            }
        }
    }

    /**
     * Waits for stdout EOF and the process exit.
     *
     * pi has no handshake, so `get_state` is the readiness probe. On a startup
     * failure the process exits without ever writing to stdout, and the reason
     * is on stderr.
     */
    suspend fun handshake(timeout: Duration = DEFAULT_TIMEOUT): PiRecord.Response {
        val response = try {
            request("get_state", timeout) { PiCommand.getState(it) }
        } catch (e: PiRpcException) {
            val exit = exited.takeIf { it.isCompleted }?.getCompleted()
            if (exit != null && !stdoutSeen.get()) throw PiProcessExitedException(exit, stderrSnapshot())
            throw e
        }
        return response
    }

    // ---------------------------------------------------------------- state

    fun stderrSnapshot(): String = synchronized(stderrLock) { stderrBuffer.toString() }

    fun exitCodeOrNull(): Int? = exited.takeIf { it.isCompleted }?.getCompleted()

    // -------------------------------------------------------------- shutdown

    /**
     * Closes stdin so pi shuts down gracefully, then escalates if it does not.
     * Returns the exit code, or null if the process had to be force-killed.
     */
    suspend fun close(
        gracePeriod: Duration = 3.seconds,
        killTimeout: Duration = 1.seconds,
    ): Int? {
        requestClose()
        val graceful = withTimeoutOrNull(gracePeriod) { exited.await() }
        if (graceful != null) return graceful
        forceKill()
        return withTimeoutOrNull(killTimeout) { exited.await() }
    }

    // ------------------------------------------------------------- internals

    private fun pumpStdout() {
        val framer = JsonlFramer()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            stdout.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    framer.feed(buffer, 0, read) { line -> dispatch(PiRecordParser.parse(line)) }
                }
            }
            framer.end { line -> dispatch(PiRecordParser.parse(line)) }
        } catch (_: IOException) {
            // Process died mid-read; the waiter thread reports the exit code.
        } catch (t: Throwable) {
            channel.trySend(PiRecord.Malformed("<reader failed: ${t.message}>"))
        } finally {
            // No more records can arrive; unblock anyone awaiting a response.
            synchronizePendingOnExit()
            channel.close()
        }
    }

    private fun pumpStderr() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            stderr.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    appendStderr(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        } catch (_: IOException) {
            // Nothing useful to add.
        }
    }

    private fun awaitProcessExit() {
        val code = runCatching { awaitExit() }.getOrDefault(-1)
        exited.complete(code)
        synchronizePendingOnExit()
    }

    /**
     * A response is a reply only when it carries an `id` we are waiting on.
     * Everything else — including id-less parse errors — is an event.
     */
    private fun dispatch(record: PiRecord) {
        stdoutSeen.set(true)
        if (record is PiRecord.Response && record.id != null) {
            val waiter = pending.remove(record.id)
            if (waiter != null) {
                waiter.complete(record)
                return
            }
        }
        channel.trySend(record)
    }

    /**
     * Complies with pi's reference client, which resolves pending requests when
     * the stream ends. Callers get a clear failure instead of hanging.
     */
    private fun synchronizePendingOnExit() {
        val exit = exited.takeIf { it.isCompleted }?.getCompleted()
        val snapshot = stderrSnapshot()
        for ((id, deferred) in pending) {
            pending.remove(id)
            val failure = if (exit != null) {
                PiProcessExitedException(exit, snapshot)
            } else {
                PiRpcException("pi closed its output stream")
            }
            deferred.completeExceptionally(failure)
        }
    }

    private fun appendStderr(text: String) {
        synchronized(stderrLock) {
            stderrBuffer.append(text)
            // Crash dumps can be tens of kilobytes; keep the tail.
            if (stderrBuffer.length > MAX_STDERR_CHARS) {
                stderrBuffer.delete(0, stderrBuffer.length - MAX_STDERR_CHARS)
            }
        }
    }

    companion object {
        /**
         * pi's own reference client uses 30s. Commands that legitimately block
         * for longer must be issued with an explicit `null` timeout.
         */
        val DEFAULT_TIMEOUT: Duration = 30.seconds

        /** Adapts a spawned `pi` process to the transport this client expects. */
        fun from(process: Process): PiRpcClient = PiRpcClient(
            stdout = process.inputStream,
            stdin = process.outputStream,
            stderr = process.errorStream,
            requestClose = { runCatching { process.outputStream.close() } },
            awaitExit = { process.waitFor() },
            forceKill = { process.destroyForcibly() },
        )

        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val MAX_STDERR_CHARS = 64 * 1024
    }
}
