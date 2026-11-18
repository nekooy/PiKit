/*
 * What a picture is by the time pi forwards it.
 *
 * pi rescales every attachment that is larger than its model entry's declared limit —
 * 2000 px on a side unless something says otherwise — and re-encodes it as JPEG at 80.
 * That made two pictures out of one: the app kept a 1024 px copy for the screen and the
 * model received a different 2000 px one, so a reply that pointed at something in the
 * image pointed at a picture the reader had never seen.
 *
 * This file removes the second picture. A picture is fitted to [SEND_IMAGE_MAX_EDGE] and
 * encoded here, below the byte ceiling pi checks before it decides to rescale at all, so
 * pi's own pass is the pass-through branch (`wasResized: false`) and the bytes the model
 * receives are the bytes this app encoded. `models.json` declares the same 3000 in
 * `inputLimits`, so the two halves agree; see `PIKIT_IMAGE_MAX_EDGE`.
 *
 * The rule for when nothing is touched is the same three facts pi tests: inside the edge,
 * inside the byte ceiling, and decodable. An untouched picture is sent byte-for-byte,
 * which is what keeps a screenshot's text crisp and a photo's metadata intact.
 */
package pi.kit.mob.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * The longest side of a picture this app sends.
 *
 * 3000 is a deliberate step above pi's 2000 rather than a round number: it is the size a
 * long screenshot needs to keep its text legible to the model (`§7.3` measures the trade),
 * and it stays under the single-side ceiling providers apply to multi-image requests (4096)
 * with room for the normalisation each one does on arrival.
 */
internal const val SEND_IMAGE_MAX_EDGE = 3000

/**
 * The largest encoded picture this app sends, in source bytes.
 *
 * pi's test is a base64 length below 4.5 MiB, and base64 is four thirds of the bytes it
 * carries; 3.5 MB leaves headroom under the 4,718,592-character threshold so the rounding
 * of the encoding can never push a picture back into pi's rescale branch.
 */
internal const val SEND_IMAGE_MAX_BYTES = 3_500_000

/** Quality ladder for a picture that has to be re-encoded, best first. */
private val SEND_IMAGE_QUALITIES = intArrayOf(90, 80, 70, 60)

/** A picture ready for the wire: exactly the bytes the model will receive. */
internal data class PreparedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
) {
    // A data class over a ByteArray compares the array by identity, which is never what a
    // caller wants; the fields a test asserts on are the facts, not the buffer.
    override fun equals(other: Any?): Boolean =
        other is PreparedImage &&
            mimeType == other.mimeType &&
            width == other.width &&
            height == other.height &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        ((mimeType.hashCode() * 31 + width) * 31 + height) * 31 + bytes.contentHashCode()
}

/**
 * Whether a decoded picture can be sent untouched.
 *
 * The three facts are pi's own: the long side, the encoded byte ceiling, and a decodable
 * raster (checked by the caller, which is the only place with the bytes decoded).
 */
internal fun fitsWithin(width: Int, height: Int, bytes: Int): Boolean =
    width > 0 && height > 0 &&
        maxOf(width, height) <= SEND_IMAGE_MAX_EDGE &&
        bytes <= SEND_IMAGE_MAX_BYTES

/**
 * The size a picture takes after being fitted to [edge] on its long side.
 *
 * Aspect ratio is preserved and no side falls below one pixel: a 12000 x 3 banner is a
 * degenerate input, and rounding it to zero would make the encoder throw rather than send
 * a one-pixel-tall image.
 */
internal fun fitSize(width: Int, height: Int, edge: Int = SEND_IMAGE_MAX_EDGE): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= edge || longest <= 0) return width to height
    val share = edge.toFloat() / longest
    return (width * share).toInt().coerceAtLeast(1) to
        (height * share).toInt().coerceAtLeast(1)
}

/**
 * The picture as the model will receive it, or null when the bytes are not an image.
 *
 * A picture already inside both limits is returned as the same array, untouched. Anything
 * else is decoded once at a sample that still covers the target, scaled exactly to it, and
 * encoded down the ladder until it fits — the smallest attempt is kept when none does,
 * because sending an oversized picture is worse than sending a slightly soft one.
 */
internal fun prepareImageForSend(bytes: ByteArray, mimeType: String): PreparedImage? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return@runCatching null
    if (fitsWithin(width, height, bytes.size)) {
        return@runCatching PreparedImage(bytes, mimeType, width, height)
    }

    val (targetWidth, targetHeight) = fitSize(width, height)
    val sampled = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleFor(maxOf(width, height)) },
    ) ?: return@runCatching null
    val scaled = if (sampled.width == targetWidth && sampled.height == targetHeight) {
        sampled
    } else {
        Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true).also {
            if (it !== sampled) sampled.recycle()
        }
    }
    val alpha = scaled.hasAlpha()
    val encoded = encodeWithinLimits(scaled, alpha)
    scaled.recycle()
    encoded?.let {
        PreparedImage(
            bytes = it,
            mimeType = if (alpha) "image/webp" else "image/jpeg",
            width = targetWidth,
            height = targetHeight,
        )
    }
}.getOrNull()

/** A power of two that leaves the raster at or above the send edge after decoding. */
private fun sampleFor(longest: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= SEND_IMAGE_MAX_EDGE) sample *= 2
    return sample
}

/**
 * The first quality that fits the byte ceiling, or the smallest one when none does.
 *
 * Transparency decides the codec: JPEG has no alpha channel, so re-encoding a transparent
 * PNG as JPEG would paint every transparent pixel black. `WEBP` is the deprecated spelling
 * rather than `WEBP_LOSSY` on purpose — the latter is an API 30 constant, and a device on
 * 28 or 29 would fail to resolve the field.
 */
@Suppress("DEPRECATION")
private fun encodeWithinLimits(bitmap: Bitmap, alpha: Boolean): ByteArray? {
    val format = if (alpha) Bitmap.CompressFormat.WEBP else Bitmap.CompressFormat.JPEG
    var smallest: ByteArray? = null
    for (quality in SEND_IMAGE_QUALITIES) {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        val candidate = out.toByteArray()
        if (candidate.size <= SEND_IMAGE_MAX_BYTES) return candidate
        val current = smallest
        if (current == null || candidate.size < current.size) smallest = candidate
    }
    return smallest
}
