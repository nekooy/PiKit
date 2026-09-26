package pi.kit.mob.pi

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.data.BackupArchive
import pi.kit.mob.data.BackupCategory
import pi.kit.mob.data.BackupManifest
import pi.kit.mob.env.SafeDelete
import pi.kit.mob.env.TermuxEnv

/**
 * What the backup page is showing under its buttons.
 *
 * Every case is a state and none of them is a sentence: the words come from the
 * catalogs, the way [CatalogueStatus] and [StorageSelfTest.Status] work. The
 * exception is [Failed], which carries whatever the platform said — a full disk,
 * a revoked URI — and is the same shape as `CatalogueStatus.Failed`, because a
 * message invented here would be a worse description of it.
 */
sealed interface BackupStatus {
    data object Idle : BackupStatus

    /** [entries] is how many files have been written, or read, so far. */
    data class Running(val kind: Kind, val entries: Int) : BackupStatus

    /** [name] is the file the archive was written to, as the picker named it. */
    data class Exported(val name: String, val entries: Int) : BackupStatus

    /** [entries] counts archive entries applied — preference documents and files alike. */
    data class Imported(val entries: Int) : BackupStatus

    /** The picked file is not a PiKit archive, or one from a newer format. */
    data object NotAnArchive : BackupStatus

    /**
     * [kind] is carried because the failure has to be reported under the button
     * that caused it: a message about a destination that could not be opened is
     * nonsense under the import button, and one status value serves both cards.
     */
    data class Failed(val kind: Kind, val message: String) : BackupStatus

    enum class Kind { EXPORT, IMPORT }
}

/** An archive the user picked: copied in, read, and waiting to be applied. */
data class BackupReview(
    val file: File,
    val manifest: BackupManifest,
)

/**
 * Exporting the app's own data to a file the user chooses, and putting it back.
 *
 * ## Why the run lives here and not on the page
 *
 * A workspace backup is thousands of files and a restore is a stop, an unpack and
 * a restart; the page it was started from is one tab that leaves the composition
 * whenever the user looks at another one. `CatalogueUpdater` and `StorageSelfTest`
 * are held by the composition because their runs are seconds long and their result
 * is a line of text — this one is minutes long, its result is the state of the
 * app, and losing it halfway is not a lost message but a half-restored device. So
 * it is owned by the session, beside `repairInstalledPackages`, which walks
 * `$PREFIX` for the same reason.
 *
 * ## The two directions are not symmetric
 *
 * An export is read-only and may run under a live agent: the session files it
 * copies are being appended to, which costs at most a truncated last line in one
 * conversation, and asking the user to stop the agent first would be a step they
 * cannot take on the chat tab.
 *
 * A restore **stops the agent** and starts it again. pi holds the conversation it
 * has open and rewrites `models.json` and `settings.json` at the start of every
 * launch, so a restore made under a running agent is undone by the next thing the
 * agent does — or, worse, lands in the middle of a turn it is still writing.
 *
 * ## Nothing is deleted
 *
 * An entry of the archive replaces the file of that name and nothing else. A
 * preference document is applied key by key through `SharedPreferences`, so a key
 * the archive does not carry keeps the value it has. A restore is therefore a
 * merge, and the page says so — the one thing it can destroy is the *current*
 * contents of a file the archive also has.
 */
class BackupManager(
    private val context: Context,
    private val env: TermuxEnv,
    private val scope: CoroutineScope,
    private val session: PiAgentSession,
) {

    private val archive = BackupArchive.of(context, env)

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _review = MutableStateFlow<BackupReview?>(null)
    val review: StateFlow<BackupReview?> = _review.asStateFlow()

    /**
     * One run at a time, across both directions.
     *
     * Compare-and-set rather than a check followed by an assignment: the writes
     * below are long, and two taps in one frame would otherwise both pass a guard
     * read from the status flow.
     */
    private val running = AtomicBoolean(false)

    /**
     * Where a picked archive is copied to before it is read.
     *
     * The picker's grant is good for as long as the Activity that asked for it, and
     * the review sheet is a state the user can sit in — or leave the app and come
     * back to. Copying once, immediately, is what makes the archive a file this app
     * owns for the rest of the flow.
     */
    private val incoming: File get() = File(env.filesDir, INCOMING_NAME)

    // ---------------------------------------------------------------- export

    /**
     * Writes the selected categories to [target].
     *
     * [target] comes from the system's "create a document" picker, so the file may
     * live anywhere the user has — a Downloads directory, a cloud provider's
     * client. The stream is written to directly rather than staged and copied: a
     * workspace backup is large enough that a second copy of it is a real cost, and
     * a failed export leaves a file the user can see and delete.
     *
     * [suggested] is the name the picker was opened with, and it is the fallback for
     * the one the report shows — see [displayName].
     */
    fun export(
        target: Uri,
        suggested: String,
        categories: Set<BackupCategory>,
        includeApiKeys: Boolean,
    ) {
        if (categories.isEmpty()) return
        if (!running.compareAndSet(false, true)) return
        _status.value = BackupStatus.Running(BackupStatus.Kind.EXPORT, 0)

        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(target)
                        ?: error("the destination could not be opened")
                    out.use { stream ->
                        archive.write(stream, categories, includeApiKeys) { entries ->
                            _status.value = BackupStatus.Running(BackupStatus.Kind.EXPORT, entries)
                        }
                    }
                }
                _status.value = BackupStatus.Exported(
                    name = withContext(Dispatchers.IO) { displayName(target) } ?: suggested,
                    entries = written,
                )
            } catch (t: Throwable) {
                _status.value = BackupStatus.Failed(BackupStatus.Kind.EXPORT, t.message ?: t.toString())
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * The name the picker gave the file.
     *
     * Asked of the provider rather than read off the URI, and that is not pedantry:
     * for the Downloads provider there is no name in the URI at all — the document
     * id is a database key, and reading `target.lastPathSegment` on a real export
     * reported the file the user had just saved as **"8"** (`msf:8`, tail taken).
     * `OpenableColumns.DISPLAY_NAME` is the documented way to ask, and every provider
     * answers it.
     */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // -------------------------------------------------- review, then restore

    /**
     * Copies a picked archive in and reads its manifest.
     *
     * Nothing is applied here: the archive's own table of contents is a list the
     * user has to see before they press the button, because which categories it
     * holds is not something they can tell from a file name — and an archive handed
     * to them by someone else is exactly the case this page exists for.
     */
    fun inspect(source: Uri) {
        if (!running.compareAndSet(false, true)) return
        _status.value = BackupStatus.Running(BackupStatus.Kind.IMPORT, 0)

        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    _review.value?.let { discard() }
                    val input = context.contentResolver.openInputStream(source)
                        ?: error("the picked file could not be read")
                    input.use { stream ->
                        incoming.outputStream().use { copy -> stream.copyTo(copy) }
                    }
                    archive.manifest(incoming)
                }
                if (manifest == null) {
                    withContext(Dispatchers.IO) { incoming.delete() }
                    _status.value = BackupStatus.NotAnArchive
                } else {
                    _review.value = BackupReview(incoming, manifest)
                    _status.value = BackupStatus.Idle
                }
            } catch (t: Throwable) {
                _status.value = BackupStatus.Failed(BackupStatus.Kind.IMPORT, t.message ?: t.toString())
            } finally {
                running.set(false)
            }
        }
    }

    /** Abandons a reviewed archive without applying any of it. */
    fun cancelReview() {
        if (running.get()) return
        discard()
        _status.value = BackupStatus.Idle
    }

    /**
     * Unpacks the reviewed archive and moves the selected categories into place.
     *
     * The order is the whole of the correctness here: the agent is stopped, the
     * archive is unpacked **in full** into a staging directory, and only then is
     * any of it applied — so an archive that is truncated, or one whose zip the
     * platform refuses halfway through, fails before it has changed anything. The
     * stores are reloaded after the files are in place and before the agent is
     * started again, because the agent's own launch rewrites `models.json` and
     * `settings.json` *from* the restored profile.
     */
    fun restore(categories: Set<BackupCategory>) {
        val pending = _review.value ?: return
        if (categories.isEmpty()) return
        if (!running.compareAndSet(false, true)) return
        _status.value = BackupStatus.Running(BackupStatus.Kind.IMPORT, 0)

        scope.launch {
            try {
                val entries = apply(pending.file, categories)
                // The copy taken from the picker is this app's own file now, and a
                // restore that succeeded has no further use for it: it holds the
                // user's whole workspace and, with the keys kept, their profiles —
                // and it would otherwise sit in `filesDir` for the life of the
                // install. A *failed* restore keeps it, so the button can be pressed
                // again without a second trip through the file picker.
                withContext(Dispatchers.IO) { discard() }
                _status.value = BackupStatus.Imported(entries = entries)
            } catch (t: Throwable) {
                _status.value = BackupStatus.Failed(BackupStatus.Kind.IMPORT, t.message ?: t.toString())
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * The restore itself: stop, unpack, move, reload, and start the agent again.
     *
     * ## Why this is one flat `try` and not two nested ones
     *
     * The nested version — the unpack's own `try/finally` inside the agent's — was
     * rejected by **D8**, and only by D8: `./gradlew :app:compileX64DebugKotlin` was
     * green and the failure appeared one task later, as
     * `Invalid stack map table at instruction index 384: aload 6, error: Expected
     * object at local index 6, but was top` followed by an NPE inside `D8.run`. Two
     * exception handlers around a `return` in a suspend function, with a `withContext`
     * in each handler, is a shape the Kotlin compiler emits bad frames for; hoisting
     * the staging directory out of the `try`, dropping the inner handler and
     * **returning after it** rather than from inside it is what dexes. The staged
     * tree is still removed on every path, which is the only reason the `finally` is
     * there at all.
     */
    private suspend fun apply(archiveFile: File, categories: Set<BackupCategory>): Int {
        val wasRunning = session.agent.value is AgentStatus.Running
        session.stopAgent()
        val staging = withContext(Dispatchers.IO) { archive.freshStaging() }
        var moved = 0
        try {
            moved = withContext(Dispatchers.IO) {
                val extraction = archive.extract(archiveFile, categories, staging)
                _status.value = BackupStatus.Running(BackupStatus.Kind.IMPORT, 0)
                // One per archive entry: a preference document is one, the same way
                // the file it was exported from was one. See `applyPreferences`.
                var count = 0
                for ((name, document) in extraction.documents) {
                    archive.applyPreferences(name, document)
                    count++
                    _status.value = BackupStatus.Running(BackupStatus.Kind.IMPORT, count)
                }
                count + archive.applyAll(staging, categories)
            }
            // The files are in place; everything the app holds in memory is still the
            // old value, and this is what makes the restore visible without a relaunch.
            session.reloadAfterRestore()
        } finally {
            // NonCancellable, and in `finally`, so an interrupted restore does not
            // leave a copy of the user's workspace — and, when the keys were kept, of
            // their profiles — sitting in `filesDir` for the life of the install.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { SafeDelete.recursively(staging, env.filesDir) }
            }
            // And the agent is started again even when the restore failed, because the
            // one outcome worse than a restore that did not happen is an app whose
            // agent has silently stopped.
            if (wasRunning) session.startAgent()
        }
        return moved
    }

    /** Clears a finished outcome, and the copied archive the review held. */
    fun dismiss() {
        if (running.get()) return
        if (_review.value != null) {
            cancelReview()
            return
        }
        _status.value = BackupStatus.Idle
    }

    private fun discard() {
        _review.value = null
        incoming.delete()
    }

    private companion object {
        /** Where a picked archive is copied to; under `filesDir`, so it is ours. */
        const val INCOMING_NAME = "backup-incoming.zip"
    }
}
