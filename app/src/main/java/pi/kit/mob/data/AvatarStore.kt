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
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
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

    /**
     * The files [slot] owns: `<slot>-<stamp>.png`, and the legacy `<slot>.png`.
     *
     * A picture is stored under a name of its own rather than at one fixed path, because
     * the setting holds the *path*. Replacing a picture used to rewrite `ai.png`, so the
     * field did not change — and everything that decodes a picture is keyed on that path
     * (`PiAvatar`'s `produceState`, and any cache above it), so the reader kept seeing the
     * previous picture until the page was re-entered and the decode started over. A name
     * that changes with the content is what makes a replacement visible.
     *
     * The legacy fixed name is matched too: a picture stored by an older build is
     * replaced by the next one instead of sitting in the directory for ever.
     */
    private fun files(filesDir: File, slot: AvatarSlot): List<File> =
        directory(filesDir).listFiles().orEmpty()
            .filter { it.name.startsWith("${slot.id}-") || it.name == "${slot.id}.png" }
            .sortedBy { it.name }

    /**
     * The picked picture, decoded and bounded, for the crop step.
     *
     * Separate from [storeSquare] because it happens *before* the reader has chosen what
     * part of the picture to keep: this one is only ever a preview, so it is bounded to a
     * little more than the square it is drawn in rather than to the stored size.
     *
     * ## The orientation is the platform's job
     *
     * A photo the camera stored upright carries its rotation in an EXIF tag and its
     * pixels are on their side, and `BitmapFactory` reads the pixels and nothing else — so
     * the crop window used to open on a portrait lying down, which reads as a broken
     * picture rather than as a tag the decoder ignored. `ImageDecoder` applies the tag as
     * it decodes; it is API 28, and [decodeSampled] is what 26 and 27 get.
     */
    suspend fun decodeForCrop(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeOriented(context, uri)
            } else {
                decodeSampled(context, uri)
            }
        }.getOrNull()
    }

    /** [decodeForCrop] on API 28 and up: the same sampling, with the EXIF tag applied. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeOriented(context: Context, uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software, because the crop step takes a sub-rectangle with
            // `Bitmap.createBitmap`, which refuses a hardware bitmap. The sample is
            // solved from the size the decoder reports, which is the size *after* the
            // orientation, so a rotated photo is bounded by its upright dimensions.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(
                sampleFor(minOf(info.size.width, info.size.height)),
            )
        }
    }

    /** [decodeForCrop] below API 28, where the EXIF tag is not available this cheaply. */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(minOf(bounds.outWidth, bounds.outHeight))
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * The power of two that keeps the *shorter* side of a [shortest]-side picture at or
     * above [CROP_SOURCE_PX].
     *
     * The shorter side, because the crop is square: the frame can never show more than
     * the shorter side, so that is the dimension the zoom spends.
     */
    private fun sampleFor(shortest: Int): Int {
        var sample = 1
        while (shortest / (sample * 2) >= CROP_SOURCE_PX) sample *= 2
        return sample
    }

    /**
     * Writes the square the reader cropped, as the slot's picture.
     *
     * The crop is already square, so the only work is the size: one file, one format and
     * one dimension, exactly as the whole picture did it, because everything downstream —
     * the circle, the backup, the decode on the next launch — expects that.
     *
     * The caller keeps ownership of [square]: the crop window is still drawing it while
     * this runs (it animates out through `show`), and `scaleToFit` hands its argument back
     * untouched when it already fits, so the only bitmap recycled here is one this
     * function made.
     */
    fun storeSquare(filesDir: File, slot: AvatarSlot, square: Bitmap): String? = runCatching {
        val scaled = scaleToFit(square, AVATAR_STORED_PX)
        val directory = directory(filesDir)
        directory.mkdirs()
        val target = File(directory, "${slot.id}-${System.currentTimeMillis()}.png")
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== square) scaled.recycle()
        // One picture per speaker: the one being replaced goes as soon as the new one is
        // on disk, or the directory grows a file per crop and the backup carries them all.
        files(filesDir, slot).forEach { if (it != target) it.delete() }
        target.absolutePath
    }.getOrNull()

    /**
     * Removes every stored picture of [slot].
     *
     * A handful of files rather than a tree, so it is `File.delete` and not `SafeDelete` —
     * the rule that one exists for is recursive deletes, and the directory itself is left
     * in place with the other slot's picture in it.
     */
    fun clear(filesDir: File, slot: AvatarSlot) {
        files(filesDir, slot).forEach { runCatching { it.delete() } }
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
