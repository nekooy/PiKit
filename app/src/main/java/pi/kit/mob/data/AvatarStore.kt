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
     * The picked picture, decoded and bounded, for the crop step.
     *
     * Separate from [store] because it happens *before* the reader has chosen what part
     * of the picture to keep: this one is only ever a preview, so it is bounded to a
     * little more than the square it is drawn in rather than to the stored size.
     */
    suspend fun decodeForCrop(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= CROP_SOURCE_PX &&
                bounds.outHeight / (sample * 2) >= CROP_SOURCE_PX
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    /**
     * Writes the square the reader cropped, as the slot's picture.
     *
     * The crop is already square, so the only work is the size: one file, one format and
     * one dimension, exactly as [store] does it, because everything downstream — the
     * circle, the backup, the decode on the next launch — expects that.
     */
    fun storeSquare(filesDir: File, slot: AvatarSlot, square: Bitmap): String? = runCatching {
        val scaled = scaleToFit(square, AVATAR_STORED_PX)
        val target = file(filesDir, slot)
        target.parentFile?.mkdirs()
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== square) square.recycle()
        scaled.recycle()
        target.absolutePath
    }.getOrNull()

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

    /**
     * The longest side the *crop preview* is decoded at.
     *
     * Twice the stored size, because the reader zooms in before choosing: at the preview
     * size a zoomed crop would come from interpolated pixels rather than from the source.
     */
    private const val CROP_SOURCE_PX = 1024
}
