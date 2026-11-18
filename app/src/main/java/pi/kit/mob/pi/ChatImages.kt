package pi.kit.mob.pi

import java.security.MessageDigest

/**
 * The name a prompt's picture is known by once it has left the wire.
 *
 * ## Why the transcript keeps a key instead of the picture
 *
 * pi echoes an attached picture back inside the user message it persisted — the whole
 * base64, on `message_start` and in the session file alike — and the transcript cannot
 * hold that: a four-megabyte photo is five and a half megabytes of base64, kept per turn
 * for as long as the conversation is open, and a scroll re-measures every row it passes.
 * It was tried as the *count* first (the cheapest possible answer, and the one that made
 * the reader ask why their own picture was a line of text), so the transcript now keeps
 * the *address* of the picture and the app's own cache keeps the pixels
 * (`ui/chat/ChatPictures.kt` draws them).
 *
 * ## Why a digest of the base64 and not an id of our own
 *
 * Two paths have to arrive at the same name without ever meeting. The send path holds
 * the file the reader picked; the transcript holds what pi echoed back; a conversation
 * opened from disk holds only the session file. A digest is the one answer that needs
 * nothing remembered: a cache that was pruned, a session written by an older build, or
 * a conversation restored on a fresh install all reconcile themselves by re-deriving the
 * name.
 *
 * It is a digest of the **encoded text**, not of the decoded bytes, and that is what
 * makes them reconcile without a decode: the string this app produced is the string pi
 * echoes, character for character, so the send path can store the picture under the name
 * the transcript will ask for and never decode it twice. SHA-1 because this is an
 * identity, not a secret — no threat model wants a stronger digest for a filename — and
 * the cost matters: it runs over megabytes on the record thread, and a 40-character hex
 * name is readable in a log line, a directory listing and a tombstone.
 */
internal object ChatImages {

    /**
     * The cache name of the picture whose base64 is [base64].
     *
     * A record that carries an image block with no `data` — a session pi trimmed, or a
     * shape this app has not met — still gets a name, so the row draws a placeholder
     * rather than dropping a picture that was sent.
     */
    fun keyOf(base64: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(base64.toByteArray(Charsets.UTF_8))
        val name = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            name.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return name.toString()
    }

    private const val HEX = "0123456789abcdef"
}
