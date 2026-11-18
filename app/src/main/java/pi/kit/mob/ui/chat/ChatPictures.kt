/*
 * A prompt's pictures, drawn.
 *
 * ## What this replaces
 *
 * The transcript said `2 张图片` where the reader's own screenshots were. The line was
 * not lazy: pi echoes an attached picture back inside the user message it persists, as
 * base64, and the count was the transcript's way of never holding it (a four-megabyte
 * photo is five and a half megabytes of encoded text per turn, re-measured on every
 * frame of a scroll — `ChatItem.User` has the measurement). But a question with a
 * picture in it is not readable as "1 image": this page is where the reader checks what
 * they actually sent, and the picture *was* the question.
 *
 * ## The three parts, and why they are three
 *
 *  - [ChatPictureCache] holds **one small copy** of every picture the app has sent,
 *    named by [ChatImages.keyOf] of the base64 pi echoes, decoded once to
 *    [ChatPictureCache.DISPLAY_EDGE] pixels. It is a cache in the app's own files
 *    directory rather than in the session file, which the app does not own.
 *  - [PromptPictures] draws the keys a prompt carries: one picture at its own aspect,
 *    several as a grid, and the old count line when none of them can be read.
 *  - [PictureViewer] is the full window a tap opens: one picture at a time, swiped
 *    between, dismissed by a tap.
 *
 * The picture is never carried in the transcript itself; the address is 40 characters
 * and survives the conversation being closed, restored, pruned and re-hydrated.
 *
 * ## Why the cache is written at send time
 *
 * The one moment the bytes are certain is the send: the app has just encoded them. So
 * the copy is written there (the same `Dispatchers.IO` block that builds the payload),
 * and the row pi echoes a moment later finds its file already in place. A conversation
 * restored from disk has no such moment — the file may predate this cache, or its entry
 * may have been pruned — so [ChatPictureCache.hydrate] walks the session file once and
 * fills in what the transcript is asking for. That scan is why the fallback line stays:
 * a phone whose storage was cleaned still has the conversation, and a count is a truer
 * answer than four empty frames.
 */
package pi.kit.mob.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.ChatImages
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.SessionTranscript
import pi.kit.mob.ui.kit.PiTone
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.min

/**
 * The pictures one prompt carries, and the one a tap on it should open first.
 *
 * A value rather than the file, because the viewer is opened from a row inside the
 * transcript and outlives it: the row can be scrolled out and recomposed while the
 * window is up, and a path would then have to be re-resolved from a key that no longer
 * has a row to belong to.
 */
internal data class PictureTarget(val keys: List<String>, val start: Int)

/**
 * The app's own copy of every picture it has sent.
 *
 * ## The cache is a cache
 *
 * Nothing in it is the record: the session file carries the bytes, the transcript
 * carries the name, and this directory is what makes drawing one cheap. So it is
 * outside the backup (nothing in it would be missed that the session file could not
 * give back), it is pruned on a budget, and every read is `File`-shaped rather than a
 * promise — a missing entry draws the count line, never an error.
 *
 * ## One decode, one file
 *
 * The copy is decoded to [DISPLAY_EDGE] on the longest side and written as JPEG: a
 * 4 MB photo becomes about 120 KB, which is what makes a hundred prompts of pictures
 * cost ten megabytes rather than four hundred. The tile and the viewer both read that
 * one file at their own sample size, so the phone never holds a twelve-megapixel
 * bitmap to draw a 96dp square.
 *
 * All of it runs off the main thread; the only main-thread work is a `File.exists()`
 * per visible tile, which is what decides between frames and the count line.
 */
internal object ChatPictureCache {

    /**
     * The longest side of the copy kept on disk, in pixels.
     *
     * 1024 is the archive of a 1080p phone's viewer: at that size a picture is sharp on
     * the screen it is shown on, and the copy is small enough (120 KB) that keeping
     * every picture of every conversation is a rounding error in the app's data.
     */
    const val DISPLAY_EDGE = 1024

    /** What one copy is written at. 82 is where a downscaled photo stops showing JPEG. */
    private const val QUALITY = 82

    /** The directory's budget: past it, the oldest copies are the ones that go. */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Bumped whenever a file appears.
     *
     * A tile that found no file drew the count line or a placeholder, and it has to try
     * again when a hydration pass fills the cache in behind it. Cheap and global: one
     * integer, read by the rows that draw pictures and by nothing else.
     */
    val generation = MutableStateFlow(0)

    /** `files/chat-pictures` — where every copy lives, named by key. */
    fun directory(root: File): File = File(root, DIRECTORY)

    /** The copy of the picture [key] names, whether or not it exists. */
    fun file(root: File, key: String): File = File(directory(root), "$key$SUFFIX")

    /**
     * Saves the picture the app is about to send, and answers its key.
     *
     * Called from the send path, on `Dispatchers.IO`, in the same block that encodes the
     * payload — so the row pi echoes back finds its picture already on disk.
     */
    fun store(root: File, image: ImageAttachment): String {
        val key = ChatImages.keyOf(image.base64)
        ensure(root, key, image.base64)
        return key
    }

    /**
     * Writes the copy for [key] unless it is already there.
     *
     * The existence check comes first and is the whole cost of a repeated send: the same
     * screenshot attached twice is one file. A picture this device cannot decode — an
     * HEIC on an old Android, a corrupt download — writes nothing, which the row draws
     * as the count line rather than as an error.
     */
    fun ensure(root: File, key: String, base64: String) {
        val target = file(root, key)
        if (target.isFile) return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        val copy = downscale(bytes) ?: return
        val written = runCatching {
            val folder = directory(root).apply { mkdirs() }
            // Written under a temporary name and moved into place: a tile decoding the
            // target while it is half-written would draw a broken picture, and the
            // rename is the only atomic step a plain directory offers.
            val staging = File(folder, "$key$STAGING_SUFFIX")
            staging.outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (staging.renameTo(target)) {
                prune(root)
                true
            } else {
                staging.delete()
                false
            }
        }.getOrDefault(false)
        copy.recycle()
        if (written) generation.update { it + 1 }
    }

    /**
     * The copy [key] names, decoded for a box [edge] pixels on its longest side.
     *
     * Read off the main thread by every caller. A missing or unreadable file is null:
     * the picture is a cache entry, and a cache miss is a state the page draws, not a
     * failure it reports.
     */
    fun load(root: File, key: String, edge: Int): ImageBitmap? = runCatching {
        val source = file(root, key)
        if (!source.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, edge) }
        BitmapFactory.decodeFile(source.absolutePath, options)?.asImageBitmap()
    }.onFailure { Log.w(TAG, "could not read a cached picture", it) }.getOrNull()

    /**
     * Fills the cache in from the session file, for the pictures [wanted] names.
     *
     * A conversation written before this cache existed — or one whose entries were
     * pruned — carries keys the transcript can draw no picture for. The bytes are still
     * in the session file, so the file is read once, every picture in it is named the
     * same way the transcript named it, and the ones that are wanted and missing are
     * written. The scan stops as soon as everything wanted has been found.
     *
     * Runs on the caller's IO dispatcher: it parses megabytes of base64.
     */
    suspend fun hydrate(root: File, session: File, wanted: Set<String>) {
        if (wanted.isEmpty() || !session.isFile) return
        val missing = wanted.filterTo(HashSet()) { !file(root, it).isFile }
        if (missing.isEmpty()) return
        withContext(Dispatchers.IO) {
            SessionTranscript.forEachPromptPicture(session) { base64, _ ->
                val key = ChatImages.keyOf(base64)
                if (missing.remove(key)) ensure(root, key, base64)
                missing.isNotEmpty()
            }
        }
    }

    /**
     * Keeps the directory under [MAX_BYTES], oldest first.
     *
     * A budget rather than a count, because pictures are not one size; the order is the
     * modification time, which is when the copy was written, and the record the reader
     * cares about is the session file rather than this directory — a pruned picture is
     * re-derived the next time its conversation is opened.
     */
    private fun prune(root: File) {
        val files = directory(root).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { oldest ->
            if (total <= MAX_BYTES) return
            val length = oldest.length()
            if (oldest.delete()) total -= length
        }
    }

    /**
     * The decoded picture, no larger than [edge] on its longest side.
     *
     * Sampled on the way in and then scaled once: a phone camera's 12 megapixels is 48 MB
     * as an `ARGB_8888` bitmap, and sampling alone lands between 1024 and 2048 — close
     * enough that the exact scale is one cheap pass over a small bitmap rather than a
     * second decode of a large one.
     */
    private fun downscale(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sampled = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, DISPLAY_EDGE) },
        ) ?: return@runCatching null
        val longest = maxOf(sampled.width, sampled.height)
        if (longest <= DISPLAY_EDGE) return@runCatching sampled
        val share = DISPLAY_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * share).toInt().coerceAtLeast(1),
            (sampled.height * share).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== sampled) sampled.recycle()
        scaled
    }.getOrNull()

    /** A power of two that leaves [bounds] at or above [edge] pixels on its longest side. */
    private fun sampleFor(bounds: BitmapFactory.Options, edge: Int): Int {
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= edge ||
            bounds.outHeight / (sample * 2) >= edge
        ) {
            sample *= 2
        }
        return sample
    }

    private const val DIRECTORY = "chat-pictures"
    private const val SUFFIX = ".jpg"
    private const val STAGING_SUFFIX = ".part"
}

/**
 * The pictures a prompt carried: drawn, or counted when none of them can be.
 *
 * One picture is drawn at its own aspect inside a box — a screenshot is taller than a
 * photo and squaring either one would hide the part that was asked about — and two or
 * more are square tiles in a two-column grid, cropped, because a set of images is
 * *identified* by them and one is *read*. The count line survives only for the case it
 * was always right for: a prompt whose pictures the app cannot produce.
 */
@Composable
internal fun PromptPictures(
    keys: List<String>,
    count: Int,
    text: Strings,
    onOpen: (PictureTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation by ChatPictureCache.generation.collectAsState()
    val root = context.filesDir
    // A stat per picture, in composition. It is the one check that cannot be deferred:
    // whether the card is a picture or a line of text decides its height, and a frame
    // of the wrong one is a visible jump. Four files, once per row per generation.
    val readable = remember(keys, generation) {
        keys.count { ChatPictureCache.file(root, it).isFile }
    }

    if (readable == 0) {
        PictureCount(count = count, text = text, modifier = modifier)
        return
    }

    // The width the pictures may use is the card's own measure — the cap comes from the
    // `BoxWithConstraints` around the card, so it is already on the constraints here. It
    // is read rather than requested: `fillMaxWidth` inside this card would become the
    // card's *minimum* width (the trap `PromptBody` documents), so every frame is sized
    // from a number and never asks for one.
    BoxWithConstraints(modifier) {
        val available = maxWidth.value
        if (available <= 0f) return@BoxWithConstraints

        if (keys.size == 1) {
            SinglePicture(
                key = keys[0],
                text = text,
                available = available,
                onOpen = { onOpen(PictureTarget(keys, 0)) },
            )
            return@BoxWithConstraints
        }

        // Two columns, as wide as the card allows and no wider than a comfortable tile:
        // four pictures at a phone's measure are a 2×2 block the size of the prompt.
        val side = minOf((available - PICTURE_GAP.value) / PICTURES_PER_ROW, PICTURE_TILE.value)
            .coerceAtLeast(MIN_PICTURE_TILE)
            .dp
        Column(verticalArrangement = Arrangement.spacedBy(PICTURE_GAP)) {
            keys.chunked(PICTURES_PER_ROW).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(PICTURE_GAP)) {
                    row.forEachIndexed { column, key ->
                        PictureTile(
                            key = key,
                            side = side,
                            text = text,
                            onOpen = {
                                onOpen(PictureTarget(keys, rowIndex * PICTURES_PER_ROW + column))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The lone picture, at its own shape.
 *
 * The frame is decided by the decoded picture rather than by a constant: it takes the
 * card's measure and gives the height that follows, up to [PICTURE_MAX_HEIGHT] — a
 * landscape photo is a wide, short block and a portrait screenshot is a tall one of about
 * two thirds the width. It used to be capped at a 208 × 156 dp box, and a 9:16 screenshot
 * came out 88 dp wide: a thumbnail of the thing the reader was asking about, in a card
 * three times its width because the sentence beside it was longer. While the copy is
 * still being read the frame is the 4:3 box a phone photo usually fits, which is one
 * frame of the right order of size.
 */
@Composable
private fun SinglePicture(
    key: String,
    text: Strings,
    available: Float,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val generation by ChatPictureCache.generation.collectAsState()
    val picture by produceState<ImageBitmap?>(null, key, generation) {
        value = withContext(Dispatchers.IO) {
            ChatPictureCache.load(context.filesDir, key, TILE_PIXELS)
        }
    }
    val bitmap = picture
    // While the copy is still being read the frame is a 4:3 box — the shape a phone photo
    // usually has — built by the same rule as the real one, so the placeholder and the
    // picture it is replaced by differ in shape and never in bounds.
    val box = pictureBox(
        width = bitmap?.width ?: 4,
        height = bitmap?.height ?: 3,
        maxWidth = available,
        maxHeight = PICTURE_MAX_HEIGHT.value,
    )
    PictureFrame(
        bitmap = bitmap,
        width = box.first.dp,
        height = box.second.dp,
        text = text,
        modifier = Modifier,
        crop = false,
        onOpen = onOpen,
    )
}

/** One square of the grid: cropped, because it identifies the picture rather than shows it. */
@Composable
private fun PictureTile(
    key: String,
    side: Dp,
    text: Strings,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val generation by ChatPictureCache.generation.collectAsState()
    val picture by produceState<ImageBitmap?>(null, key, generation) {
        value = withContext(Dispatchers.IO) {
            ChatPictureCache.load(context.filesDir, key, TILE_PIXELS)
        }
    }
    PictureFrame(
        bitmap = picture,
        width = side,
        height = side,
        text = text,
        modifier = Modifier,
        crop = true,
        onOpen = onOpen,
    )
}

/**
 * The frame both shapes wear: the picture, the corner, and the press.
 *
 * A picture with no bitmap yet is the inset wash with the photo glyph on it — the same
 * treatment the composer's attachment tile gives a file it cannot draw, so the two
 * places a picture can appear agree about what "not yet" looks like.
 */
@Composable
private fun PictureFrame(
    bitmap: ImageBitmap?,
    width: Dp,
    height: Dp,
    text: Strings,
    modifier: Modifier,
    crop: Boolean,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(PICTURE_CORNER)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(PiTone.inset()),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Icon(
                imageVector = MiuixIcons.Photos,
                contentDescription = text.chat.imageUnavailable,
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = text.chat.imageOpen,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = text.chat.imageOpen, onClick = onOpen),
            )
        }
    }
}

/**
 * The line the card draws when it has pictures it cannot draw: `2 张图片`.
 *
 * The same glyph and label as before the pictures were drawn, kept because it is still
 * the honest answer for a session whose cache entries are gone — a count says what was
 * sent, where an empty frame would look like a defect.
 */
@Composable
private fun PictureCount(count: Int, text: Strings, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = MiuixIcons.Photos,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            // Furniture rather than prose: the count of pictures the reader attached is a
            // fact about the message, not part of the sentence they typed, so it takes
            // the muted ink and the words under it take the card's own.
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = text.chat.attachmentCount(count),
            modifier = Modifier.padding(start = 4.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * The window a tap on a picture opens.
 *
 * Full screen and black, which is the one place in this app that is not the page's own
 * surface: the picture is the subject and everything else is frame. A tap anywhere
 * closes it and the back gesture is the platform's, so there is no button — a viewer is
 * one decision and it is "done". Several pictures are swiped between rather than listed,
 * and the counter is the only chrome.
 *
 * The copy on disk is decoded per page, at viewer size, and released when the page
 * leaves the composition: four pictures of a prompt are 26 MB of bitmaps if all four are
 * held, and only one is ever on screen.
 */
@Composable
internal fun PictureViewer(target: PictureTarget, text: Strings, onDismiss: () -> Unit) {
    if (target.keys.isEmpty()) return
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(
                initialPage = target.start.coerceIn(0, target.keys.lastIndex),
            ) { target.keys.size }

            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val key = target.keys[page]
                val picture by produceState<ImageBitmap?>(null, key) {
                    value = withContext(Dispatchers.IO) {
                        ChatPictureCache.load(context.filesDir, key, VIEW_PIXELS)
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = text.common.back,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    picture?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (target.keys.size > 1) {
                Text(
                    text = "${pager.currentPage + 1} / ${target.keys.size}",
                    // The one piece of chrome: white on the picture's own black, far
                    // enough from the gesture strip to clear it.
                    color = Color.White.copy(alpha = 0.7f),
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = PICTURE_COUNTER_INSET),
                )
            }
        }
    }
}

/**
 * The height a lone picture may take, in dp.
 *
 * The width is the card's own measure, so this is the one bound: 300dp is about a third
 * of a 914dp-tall phone, which is a picture that is read rather than a wall that pushes
 * the reader's own sentence off the screen. A 9:16 screenshot lands at roughly 169 × 300.
 */
private val PICTURE_MAX_HEIGHT = 300.dp

/**
 * A grid tile's edge: half the card's measure, and no wider than this.
 *
 * Four pictures at 150dp and two gaps are a 304dp block, which is the prompt card's cap
 * on a 1080p phone — so a set of pictures reads as one block the size of the message
 * rather than as four thumbnails in a corner.
 */
private val PICTURE_TILE = 150.dp

/** A floor for a tile, so a window mid-resize never asks for a zero-width box. */
private const val MIN_PICTURE_TILE = 64f

/** Between two tiles: the seam of a set, not the air between two messages. */
private val PICTURE_GAP = 4.dp

/**
 * The pictures' corner: the dense step, not the card's.
 *
 * `PiCorner.radius` is 20dp and belongs to a card — a frame whose subject is a paragraph.
 * A picture is a small information tile, and the app already has one number for those:
 * the composer's attachment chip rounds at 8dp, and the same picture is drawn in both
 * places, so a second value here would be the same square with two corners.
 */
private val PICTURE_CORNER = 8.dp

/** How far the counter sits above the window's foot. */
private val PICTURE_COUNTER_INSET = 36.dp

/** How many tiles one row holds. Four pictures is the composer's cap: two rows of two. */
private const val PICTURES_PER_ROW = 2

/** The pixels a picture is decoded for in the transcript: a 150dp tile at 3.5x density. */
private const val TILE_PIXELS = 512

/** The pixels the viewer decodes for: a 1080p phone's long side. */
private const val VIEW_PIXELS = 1280

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"

/**
 * The width and height, in dp, that hold a [width] × [height] picture inside the box.
 *
 * One edge is always filled: a picture is never drawn smaller than the frame allows, only
 * reshaped, so a wide panorama and a tall screenshot both fill their side. Pure, because
 * the arithmetic is what decides whether a picture is cropped or squashed and it is
 * cheaper to pin than to look at.
 */
internal fun pictureBox(
    width: Int,
    height: Int,
    maxWidth: Float,
    maxHeight: Float,
): Pair<Float, Float> {
    if (width <= 0 || height <= 0) return maxWidth to maxWidth * 0.75f
    val ratio = width.toFloat() / height
    val heightAtFullWidth = maxWidth / ratio
    return if (heightAtFullWidth <= maxHeight) {
        maxWidth to heightAtFullWidth
    } else {
        min(maxHeight * ratio, maxWidth) to maxHeight
    }
}
