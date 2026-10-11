/*
 * The stored avatar pictures.
 *
 * A custom avatar is a *file the app owns*, not the URI the picker handed back. A
 * `content://` grant from `GetContent` lasts as long as the process that asked for
 * it — sometimes less, since the provider decides — so a settings field holding one
 * would show the picture until the app was next launched and a blank circle after
 * that. The import here is what makes the choice durable: the picture is decoded,
 * scaled once, and written into `files/avatars/` as a PNG this app can always read
 * back.
 *
 * ## Why the copy is re-encoded rather than byte-copied
 *
 * Two reasons and both are about the *next* launch rather than this one. The picked
 * file can be any format the platform decodes (HEIC, WebP, AVIF) and a picture the
 * system's decoder understands today is not a promise about a restore onto another
 * device; and a 12-megapixel photo copied verbatim is 4 MB of file to draw into a
 * 40dp circle. A PNG at [AVATAR_STORED_PX] is a few tens of kilobytes and is a
 * format every Android build reads.
 *
 * The pictures live under `files/`, which puts them inside the app's private
 * directory — the only place `SafeDelete` will touch, and inside the `SETTINGS`
 * category of a backup, so a restore puts them back where the saved paths expect
 * them.
 */
package pi.kit.mob.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which of the two speakers a stored picture belongs to. */
enum class AvatarSlot(val id: String) {
    Ai("ai"),
    User("user"),
}

object AvatarStore {

    /** The directory every stored picture lives in, under the app's `files/`. */
    fun directory(filesDir: File): File = File(filesDir, DIR)

    /** The file [slot]'s picture is stored as. */
    fun file(filesDir: File, slot: AvatarSlot): File =
        File(directory(filesDir), "${slot.id}.png")

    /**
     * Decodes [uri], scales it and stores it as [slot]'s picture.
     *
     * @return the stored file's absolute path, or null when the pick could not be
     * read as an image at all — a wrong file type, a provider that refused, or no
     * memory for the decode. The caller reports the null rather than leaving the
     * reader with a circle that silently did not change.
     *
     * The decode is bounded twice: `inSampleSize` from the source's own bounds so a
     * large photo is never fully unpacked, and a final scale to [AVATAR_STORED_PX]
     * on the longest side so exactly one size of file is ever written.
     */
    suspend fun store(
        context: Context,
        filesDir: File,
        slot: AvatarSlot,
        uri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= AVATAR_STORED_PX &&
                bounds.outHeight / (sample * 2) >= AVATAR_STORED_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull() ?: return@withContext null

        runCatching {
            val scaled = scaleToFit(bitmap, AVATAR_STORED_PX)
            val target = file(filesDir, slot)
            target.parentFile?.mkdirs()
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // The source and the intermediate both go; the scaled one is only kept
            // when it is a different object from the source.
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
            target.absolutePath
        }.getOrNull()
    }

    /**
     * Removes [slot]'s stored picture.
     *
     * A single file rather than a tree, so it is `File.delete` and not
     * `SafeDelete` — the rule that one exists for is recursive deletes, and the
     * directory itself is left in place with the other slot's picture in it.
     */
    fun clear(filesDir: File, slot: AvatarSlot) {
        runCatching { file(filesDir, slot).delete() }
    }

    /**
     * [source] scaled so its longest side is [max], or [source] itself when it
     * already fits.
     *
     * `Bitmap.createScaledBitmap` keeps the aspect ratio when both sides are given
     * as a proportion of the source, which is what stops the round avatar from
     * cropping a picture that was never square in the first place.
     */
    private fun scaleToFit(source: Bitmap, max: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= max) return source
        val ratio = max.toFloat() / longest
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private const val DIR = "avatars"

    /**
     * The longest side a stored picture is allowed to be.
     *
     * 512px: comfortably more than any avatar is drawn at (the largest is the 88dp
     * preview in the editor, which is 264px at 3x), and small enough that the file
     * is a few tens of kilobytes rather than megabytes.
     */
    private const val AVATAR_STORED_PX = 512
}
