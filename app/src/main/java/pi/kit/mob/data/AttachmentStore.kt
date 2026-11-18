package pi.kit.mob.data

import java.io.File

/**
 * Where a `文件` attachment's copy lives: `files/attachments`.
 *
 * ## Why this is an object and where it has to be
 *
 * The copy exists for one reason: pi's wire format carries images as bytes and everything
 * else as *nothing*, so a picked document is sent as the absolute path of a copy this app
 * made, and that same path is drawn again in the transcript for as long as the conversation
 * exists (the extras chip, §7.3). Two consequences follow, and both are why the directory
 * is not private to the chat page:
 *
 *  - it must outlive the platform's own cleanup, so it is under `filesDir` rather than
 *    `cacheDir` (§7.1 has the argument);
 *  - it must travel with the conversations, because a transcript restored without the
 *    files it names is a history of dead paths — so [BackupArchive] walks this directory
 *    as part of its CONVERSATIONS category.
 *
 * It is deliberately *not* the agent's workspace: an attachment is staging for one message,
 * and a workspace that collected every document ever mentioned is a workspace the agent has
 * to wade through.
 */
internal object AttachmentStore {

    /** The directory's name under `filesDir`; also its entry name inside a backup. */
    const val DIRECTORY = "attachments"

    /**
     * The most the directory may hold before its oldest copies go.
     *
     * 256 MB is sixty-four 4 MB documents — the ceiling one prompt's four attachments reach
     * in a single send — which is room for a long working history on the app's private disk
     * without it becoming the largest thing in the install. Past the budget the copies are
     * deleted oldest-first, and a path in an old prompt can then name a file that is gone:
     * the copy was always staging, and the file the reader picked is the record.
     */
    private const val BUDGET_BYTES = 256L * 1024 * 1024

    /** `files/attachments` — where every copy lives, named by the file's own name. */
    fun directory(filesDir: File): File = File(filesDir, DIRECTORY)

    /**
     * Holds [filesDir]'s attachment directory to [BUDGET_BYTES], oldest copies first.
     *
     * Called after a copy is written, which is the only moment the directory grows. A
     * read-only failure — a directory that was never created — is not an error here: the
     * caller has just written the file it cares about.
     */
    fun prune(filesDir: File) {
        val files = directory(filesDir).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= BUDGET_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { oldest ->
            if (total <= BUDGET_BYTES) return
            val length = oldest.length()
            if (oldest.delete()) total -= length
        }
    }
}
