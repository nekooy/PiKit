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
 *  - [ChatPictureCache] holds **the picture the model received** for every picture the
 *    app has sent, named by [ChatImages.keyOf] of the base64 pi echoes and written from
 *    that same base64. It is a cache in the app's own files directory rather than in the
 *    session file, which the app does not own.
 *  - [PromptPictures] draws the keys a prompt carries: one picture at its own aspect,
 *    several as a grid, and the old count line when none of them can be read.
 *  - [PictureViewer] is the full window a tap opens, shared with the composer's own
 *    attachments: one picture at a time, swiped between, pinched into, dragged away.
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

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.ChatImages
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.SessionTranscript
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.PiGlass
import pi.kit.mob.ui.kit.PiTone
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * One picture the viewer can draw, and where it was drawn before the tap.
 *
 * A value rather than the file, because the viewer is opened from a row inside the
 * transcript and outlives it: the row can be scrolled out and recomposed while the window
 * is up, and a path would then have to be re-resolved from a key that no longer has a row
 * to belong to.
 *
 * Three sources answer the same question — "what is this picture" — because the viewer is
 * opened from two places: a prompt's copy in the app's cache ([key]), and a picture still
 * in the composer, which is the picked document ([uri]) and nothing this app owns.
 * [preview] is what the tile was already drawing: the first frame of the flight, so a
 * picture opens out of its own thumbnail rather than out of a spinner, and [origin] is
 * where that tile was on screen.
 */
internal data class PictureSource(
    val key: String? = null,
    val uri: Uri? = null,
    val preview: ImageBitmap? = null,
    val origin: Rect? = null,
)

/**
 * A set of pictures and the one a tap opened.
 *
 * The whole list travels rather than the tapped picture: a prompt's pictures are looked at
 * one after another, and the viewer swipes between them without asking the transcript for
 * another target.
 */
internal data class PictureTarget(val pictures: List<PictureSource>, val start: Int)

/**
 * Where a node is on the *screen*, in pixels.
 *
 * The flight crosses two windows — the activity's and the viewer's own — so neither a
 * root-relative nor a window-relative rectangle can be compared with the other. A view's
 * position on screen plus a coordinate's position in the root is the one pair both windows
 * agree on (`localToScreen` is newer than the Compose version this app builds against).
 */
internal fun LayoutCoordinates.screenRect(view: View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    val topLeft = localToRoot(Offset.Zero)
    val left = topLeft.x + location[0]
    val top = topLeft.y + location[1]
    return Rect(left, top, left + size.width, top + size.height)
}

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
 * ## The copy is the sent picture, not a thumbnail of it
 *
 * The bytes written here are the bytes the request carried. `SendImage.kt` has already
 * fitted and encoded the picture, and pi's own resize is a pass-through at that size, so
 * writing the payload verbatim is what keeps one picture instead of two approximations of
 * it: the viewer shows the model's picture, not a differently scaled copy of the same
 * source. A tile and the viewer both read that file at their own sample size, so the
 * phone never holds a three-thousand-pixel bitmap to draw a small square.
 *
 * All of it runs off the main thread; the only main-thread work is a `File.exists()`
 * per visible tile, which is what decides between frames and the count line.
 */
internal object ChatPictureCache {

    /**
     * The directory's budget: past it, the oldest copies are the ones that go.
     *
     * Entries are now up to 3000 px on the long side rather than 1024, which is roughly
     * nine times the pixels; 256 MB holds about two hundred and fifty of them, and a
     * pruned copy comes back from the session file (§7.3).
     */
    private const val MAX_BYTES = 256L * 1024 * 1024

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
     * Writes the sent bytes for [key] unless they are already there.
     *
     * The existence check comes first and is the whole cost of a repeated send: the same
     * screenshot attached twice is one file. Nothing is decoded here — the bytes are
     * already the request's own, and re-encoding them would put the cache back into the
     * business of drawing a second picture. A picture this device cannot later decode
     * still writes, and the row draws the count line when the tile cannot read it.
     */
    fun ensure(root: File, key: String, base64: String) {
        val target = file(root, key)
        if (target.isFile) return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        val written = runCatching {
            val folder = directory(root).apply { mkdirs() }
            // Written under a temporary name and moved into place: a tile decoding the
            // target while it is half-written would draw a broken picture, and the
            // rename is the only atomic step a plain directory offers.
            val staging = File(folder, "$key$STAGING_SUFFIX")
            staging.outputStream().use { it.write(bytes) }
            if (staging.renameTo(target)) {
                prune(root)
                true
            } else {
                staging.delete()
                false
            }
        }.getOrDefault(false)
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
        decodeFile(source, edge)
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

    private const val DIRECTORY = "chat-pictures"
    private const val SUFFIX = ".jpg"
    private const val STAGING_SUFFIX = ".part"
}

/**
 * The picture [source] names, decoded for a box [edge] pixels on its longest side.
 *
 * Two sources and one answer, so the viewer does not care where its pictures came from:
 * a prompt's copy is a file in the cache, a composer attachment is a document this app
 * has only been handed a URI for. Both are read on `Dispatchers.IO` by the caller.
 */
internal fun loadPicture(context: Context, source: PictureSource, edge: Int): ImageBitmap? {
    source.key?.let { return ChatPictureCache.load(context.filesDir, it, edge) }
    val uri = source.uri ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, edge) }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }?.asImageBitmap()
    }.onFailure { Log.w(TAG, "could not read a picked picture", it) }.getOrNull()
}

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

/** Decodes [file] for a box [edge] pixels on its longest side. */
private fun decodeFile(file: File, edge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, edge) }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
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

    // Each tile reports the frame it is drawn in and the bitmap it already holds, so the
    // target a tap builds knows where *every* picture lives — not only the one tapped.
    // The close flight aims at the picture on screen at that moment, which after a swipe
    // is not the one the viewer was opened on.
    val known = remember(keys) { mutableStateMapOf<Int, PictureSource>() }
    val open = { index: Int ->
        onOpen(
            PictureTarget(
                pictures = keys.indices.map { i ->
                    known[i] ?: PictureSource(key = keys[i])
                },
                start = index,
            ),
        )
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
                onOpen = { source ->
                    known[0] = source
                    open(0)
                },
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
                        val index = rowIndex * PICTURES_PER_ROW + column
                        PictureTile(
                            key = key,
                            side = side,
                            text = text,
                            onOpen = { source ->
                                known[index] = source
                                open(index)
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
    onOpen: (PictureSource) -> Unit,
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
        onOpen = { origin ->
            onOpen(PictureSource(key = key, preview = bitmap, origin = origin))
        },
    )
}

/** One square of the grid: cropped, because it identifies the picture rather than shows it. */
@Composable
private fun PictureTile(
    key: String,
    side: Dp,
    text: Strings,
    onOpen: (PictureSource) -> Unit,
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
        onOpen = { origin ->
            onOpen(PictureSource(key = key, preview = picture, origin = origin))
        },
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
    onOpen: (Rect?) -> Unit,
) {
    val shape = RoundedCornerShape(PICTURE_CORNER)
    val view = LocalView.current
    // The node's coordinates rather than a rectangle: a rectangle copied while the page was
    // still sliding in behind the drawer is a rectangle the flight would start from, and the
    // position has to be asked for at the moment of the tap.
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier
            .size(width, height)
            .onGloballyPositioned { coordinates = it }
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
                    .clickable(
                        onClickLabel = text.chat.imageOpen,
                        onClick = { onOpen(coordinates?.screenRect(view)) },
                    ),
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
 * ## The picture is what opens, not the window
 *
 * The first version of this was a black window that faded in over the page and a tap that
 * closed it; the second added pinch and a back disc. Both opened the viewer *around* the
 * picture, and that is the part a phone's own gallery does differently: the picture grows
 * out of the square the reader tapped, with the tile's corner radius easing to none, and
 * dragging it down puts it back. So the window is still a platform one — Miuix's
 * `WindowDialog` is a card by construction and would leave a strip of the conversation
 * around the photograph (its panel takes the status bar's inset and `outsideMargin`) —
 * but its ground starts transparent and fades in, its content is one picture drawn in the
 * tile's own rectangle on the first frame ([PictureFlight]), and a downward drag carries
 * the picture with the finger while the ground and the chrome fall away behind it.
 *
 * ## Why a tap shows and hides the chrome, and what closes
 *
 * A gallery viewer is a full-screen look, so the chrome is furniture that appears on a
 * tap and leaves on the next one; closing is the ✕, the back gesture, the back key, or a
 * downward drag past [PictureFlight.DISMISS_DISTANCE_DP]. The first version closed on any
 * tap, which made zoom impossible to add; the chrome is now the deliberate way out and a
 * stray touch costs nothing.
 *
 * ## The chrome's two rows
 *
 * Top: the close disc at the leading edge and the position in the middle — the middle
 * because it is a caption for the set rather than a control, and a counter in a corner
 * competes with the photograph's own edge. Bottom: **Save** and **Share**, the two things
 * a reader does with a picture they did not draw, in the same 42dp scrim discs as the
 * header's marks. The scrim, not the page's capsule fill, because the ground behind any
 * photograph is arbitrary and a white capsule disappeared over a white screenshot (§13).
 *
 * ## What it is decoded at, and why the tile is the first frame
 *
 * A page is decoded at [VIEW_PIXELS] — the largest picture the app ever sends — on
 * `Dispatchers.IO`, and released when it leaves composition: four pictures held at that
 * size are 26 MB of bitmaps for the one on screen. Until the full copy lands the page
 * draws the tile's own bitmap, so opening a picture is never a black frame with a spinner
 * in the middle of it; a copy that is *gone* is the one state that has nothing to draw,
 * and it says so.
 *
 * The viewer is **driven by `show`**, like every other window in the app (§13): the target
 * is held while the flight plays out, and the composable is only torn down when the exit
 * has finished.
 */
@Composable
internal fun PictureViewer(
    show: Boolean,
    target: PictureTarget,
    text: Strings,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    if (target.pictures.isEmpty()) return
    val pager = rememberPagerState(
        initialPage = target.start.coerceIn(0, target.pictures.lastIndex),
    ) { target.pictures.size }
    var chrome by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(show) }
    // 0 = the tile's rectangle, 1 = the fitted picture. Open and close are the same
    // number played in opposite directions, which is what makes the exit land on the tile.
    val flight = remember { Animatable(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableStateOf(Size.Zero) }
    var rootOnScreen by remember { mutableStateOf(Offset.Zero) }
    var decoded by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    // What a save or a share has to say, said here rather than through the page's toast:
    // the toast is drawn in the activity's window and this window is above it, so a toast
    // raised from here is a sentence nobody sees.
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current

    val dismissDistance = with(density) { PictureFlight.DISMISS_DISTANCE_DP.dp.toPx() }
    val dismissVelocity = with(density) { PictureFlight.DISMISS_VELOCITY_DP.dp.toPx() }

    val page = pager.currentPage.coerceIn(0, target.pictures.lastIndex)
    val current = target.pictures[page]
    val drawn = decoded?.takeIf { it.first == page }?.second ?: current.preview

    LaunchedEffect(show) {
        if (show) {
            visible = true
            chrome = true
            drag = 0f
            flight.snapTo(0f)
            flight.animateTo(1f, tween(VIEWER_ENTER_MILLIS, easing = SinOutEasing))
        } else if (visible) {
            flight.animateTo(0f, tween(VIEWER_EXIT_MILLIS, easing = SinOutEasing))
            // The last frame has to be *drawn* before the window goes. `animateTo` returns
            // during the frame it lands in, so removing the content right after it skips
            // the frame at rest: the picture the reader last sees is the previous one, a
            // few per cent larger than the tile it is supposed to land on, and the tile
            // replacing it is the step that reads as the picture jumping once it is back in
            // the message. Two frames, because the first is the one that composes it.
            withFrameNanos { }
            withFrameNanos { }
            visible = false
            onDismissFinished()
        }
    }
    if (!visible) return

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(VIEWER_NOTICE_MILLIS)
            notice = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Inside the dialog's content, and only here: `LocalView` read in `PictureViewer`'s
        // own body is the *activity's* view, whose parent is a `ComposeView` rather than
        // this window's `DialogWindowProvider` — the window lookup answered null and the
        // window's dim was never touched.
        val dialogView = LocalView.current
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, dialogView) {
            // The window's own dim is the one thing that made closing flash: it sits behind
            // this window and over the page, so the page is about 40% dark for as long as
            // the viewer is up, whatever this composable draws — and the frame the window is
            // removed is the frame the page jumps back to full brightness. The viewer's
            // black ground is this composable's, and it follows the finger; the platform's
            // dim has nothing to add and everything to break.
            window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
            // The app's theme sets the bar icons through the activity's window, and this is
            // a second one: over the viewer's black ground they have to be light, or the
            // light scheme draws near-black icons on a photograph.
            val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                // The window is the dialog's, not the activity's: the activity's own bars
                // and their icons come back with it.
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { frame = it.toSize() }
                .onGloballyPositioned { rootOnScreen = it.screenRect(dialogView).topLeft }
                .pointerInput(page, frame) {
                    // One finger, unzoomed and vertical: the pager takes horizontal drags
                    // and a zoomed page consumes its own pans, so anything that reaches
                    // this loop unconsumed and moves down is the dismissal gesture.
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // A change somebody else consumed — the pager swiping, or the
                            // page panning while zoomed — is not this loop's gesture.
                            if (change.isConsumed || !change.pressed) break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!dragging &&
                                abs(change.position.y - down.position.y) > slop
                            ) {
                                dragging = true
                            }
                            if (dragging) {
                                drag = (drag + change.positionChange().y).coerceAtLeast(0f)
                                change.consume()
                            }
                        }
                        if (dragging) {
                            val velocity = tracker.calculateVelocity().y
                            if (
                                PictureFlight.dismisses(
                                    drag = drag,
                                    velocity = velocity,
                                    distance = dismissDistance,
                                    velocityLimit = dismissVelocity,
                                )
                            ) {
                                onDismiss()
                            }
                            scope.launch {
                                animate(
                                    initialValue = drag,
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        VIEWER_EXIT_MILLIS,
                                        easing = SinOutEasing,
                                    ),
                                ) { value, _ -> drag = value }
                            }
                        }
                    }
                }
                .background(
                    Color.Black.copy(
                        alpha = PictureFlight.groundAlpha(flight.value, drag, dismissDistance),
                    ),
                ),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The flight draws the picture itself until it has landed, so the
                        // pager's copy would be a second picture a few pixels off it.
                        alpha = if (flight.value >= 1f) 1f else 0f
                        translationY = drag
                    },
            ) { index ->
                PicturePage(
                    source = target.pictures[index],
                    text = text,
                    onLoaded = { decoded = index to it },
                    onToggleChrome = { chrome = !chrome },
                )
            }

            FlightPicture(
                bitmap = drawn,
                origin = current.origin,
                frame = frame,
                rootOnScreen = rootOnScreen,
                progress = flight.value,
                drag = drag,
            )

            val chromeAlpha = PictureFlight.chromeAlpha(flight.value, drag, dismissDistance)
            val haptics = LocalPiHaptics.current
            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = chromeAlpha },
                enter = fadeIn(tween(VIEWER_CHROME_MILLIS)),
                exit = fadeOut(tween(VIEWER_CHROME_MILLIS)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = VIEWER_CHROME_INSET, vertical = 8.dp)
                        .height(VIEWER_MARK_SIZE),
                    contentAlignment = Alignment.Center,
                ) {
                    ViewerMark(
                        icon = MiuixIcons.Close,
                        label = text.chat.imageClose,
                        onClick = {
                            haptics.tap()
                            onDismiss()
                        },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    if (target.pictures.size > 1) {
                        Box(
                            modifier = Modifier
                                .height(VIEWER_MARK_SIZE)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(PiGlass.fill())
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = text.chat.picturePosition(page + 1, target.pictures.size),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = chromeAlpha },
                enter = fadeIn(tween(VIEWER_CHROME_MILLIS)),
                exit = fadeOut(tween(VIEWER_CHROME_MILLIS)),
            ) {
                Row(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = VIEWER_ACTIONS_INSET),
                    horizontalArrangement = Arrangement.spacedBy(VIEWER_ACTION_GAP),
                ) {
                    ViewerMark(
                        icon = MiuixIcons.Share,
                        label = text.chat.imageShare,
                        onClick = {
                            haptics.tap()
                            val shared = sharePicture(context, current)
                            if (!shared) notice = text.chat.imageShareFailed
                        },
                    )
                    ViewerMark(
                        icon = MiuixIcons.Download,
                        label = text.chat.imageSave,
                        onClick = {
                            haptics.tap()
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    savePicture(context, current)
                                }
                                notice = if (saved) {
                                    text.chat.imageSaved
                                } else {
                                    text.chat.imageSaveFailed
                                }
                            }
                        },
                    )
                }
            }

            // The save's answer, in the app's own toast capsule — `PiGlass.fill()` and the
            // theme's ink, at the toast's padding. It is a sentence the window says itself,
            // because a toast belongs to the window underneath this one.
            notice?.let { message ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = VIEWER_ACTIONS_INSET + VIEWER_MARK_SIZE + 12.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(PiGlass.fill())
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * One of the viewer's chrome marks: the header's 42dp glass disc, with its glyph.
 *
 * The same object the conversation page's header draws — `PiGlass.fill()`, `onSurface`
 * ink, a 22dp glyph — because a floating control is one object in this app whatever page
 * it floats over (§13). The first version of the viewer used a dark scrim instead, on the
 * argument that a photograph is not a page; the result was a second family of controls the
 * reader had to learn for one screen. Over a white screenshot the capsule's edge merges
 * with the picture and the glyph is what the reader aims at, which is the same trade the
 * header makes over a light page.
 */
@Composable
private fun ViewerMark(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(VIEWER_MARK_SIZE)
            .clip(CircleShape)
            .background(PiGlass.fill())
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(VIEWER_GLYPH_SIZE),
        )
    }
}

/**
 * The picture on its way in or out of the tile, drawn in screen space.
 *
 * It is a `Canvas` rather than a scaled `Image` because two rectangles and a source window
 * are what the flight needs (`PictureFlight`), and a layer transform would scale the clip
 * along with the picture, which is the one thing a shared element must not do.
 */
@Composable
private fun FlightPicture(
    bitmap: ImageBitmap?,
    origin: Rect?,
    frame: Size,
    rootOnScreen: Offset,
    progress: Float,
    drag: Float,
) {
    if (progress >= 1f || bitmap == null) return
    if (frame.width <= 0f || frame.height <= 0f) return
    val image = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    val fitted = PictureFlight.centred(PictureZoom.fitted(image, frame), frame)
    if (fitted.width <= 0f || fitted.height <= 0f) return
    // The tile's rectangle is recorded against the activity's window; this canvas is the
    // viewer's, so the difference between the two windows' origins is taken out here.
    val local = origin?.let {
        Rect(
            it.left - rootOnScreen.x,
            it.top - rootOnScreen.y,
            it.right - rootOnScreen.x,
            it.bottom - rootOnScreen.y,
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        // The finger's own offset is part of the flight, not a separate transform: a viewer
        // closed by dragging takes over from the pager exactly where the finger left the
        // picture, and the distance it was dragged is then travelled back to the tile. Left
        // out, the first frame of that exit snapped the picture up by the drag — 350px for a
        // 350px drag — before it started to shrink.
        val rect = PictureFlight
            .rect(local, fitted, progress)
            .translate(0f, drag)
        if (rect.width < 1f || rect.height < 1f) return@Canvas
        // The picture keeps its own shape and *covers* the rectangle, which the clip then
        // trims: at the tile that is the crop the tile was already showing, at the fitted
        // rectangle nothing is trimmed because the two shapes have met.
        val scale = maxOf(rect.width / image.width, rect.height / image.height)
        val width = image.width * scale
        val height = image.height * scale
        val corner = PICTURE_CORNER.toPx() * (1f - progress)
        clipPath(
            Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner))) },
        ) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(
                    (rect.center.x - width / 2f).toInt(),
                    (rect.center.y - height / 2f).toInt(),
                ),
                dstSize = IntSize(width.toInt(), height.toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/**
 * One page of the viewer: the picture, its zoom, and the gesture that owns the finger.
 *
 * The gesture is one loop rather than a `transformable` beside a pager: whether a drag
 * belongs to this page or to the pager is exactly the question, and only the page knows
 * whether it is zoomed. A single finger on an unzoomed picture is left unconsumed, so the
 * pager's own detector takes a horizontal drag and the viewer's dismissal loop takes a
 * vertical one; the moment a second finger lands, or a pan begins while the picture is
 * zoomed, every change this loop sees is consumed — including a pinch that ends back at
 * fit, so the release cannot turn into a page swipe or a close.
 */
@Composable
private fun PicturePage(
    source: PictureSource,
    text: Strings,
    onLoaded: (ImageBitmap) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val generation by ChatPictureCache.generation.collectAsState()
    val load by produceState<PictureLoad>(PictureLoad.Loading, source, generation) {
        val bitmap = withContext(Dispatchers.IO) { loadPicture(context, source, VIEW_PIXELS) }
        value = bitmap?.let(PictureLoad::Ready) ?: PictureLoad.Missing
    }
    val ready = (load as? PictureLoad.Ready)?.bitmap
    // The tile's own bitmap stands in until the full copy is decoded: opening a picture is
    // never a black frame with a spinner in the middle of it.
    val shown = ready ?: source.preview
    LaunchedEffect(ready) { ready?.let(onLoaded) }
    var frame by remember { mutableStateOf(Size.Zero) }
    var scale by remember(source) { mutableFloatStateOf(PictureZoom.MIN_SCALE) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val fitted = remember(shown, frame) {
        shown?.let { PictureZoom.fitted(Size(it.width.toFloat(), it.height.toFloat()), frame) }
            ?: Size.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { frame = it.toSize() }
            .pointerInput(shown, frame) {
                val drawn = shown ?: return@pointerInput
                if (frame.width <= 0f || frame.height <= 0f) return@pointerInput
                val fittedOnPage = PictureZoom.fitted(
                    Size(drawn.width.toFloat(), drawn.height.toFloat()),
                    frame,
                )
                val centre = Offset(frame.width / 2f, frame.height / 2f)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val multi = pressed >= 2
                        // The pager's drag, not ours: one finger on a picture that is not
                        // magnified has nothing to pan.
                        if (!multi && !claimed && PictureZoom.isSettled(scale)) continue
                        claimed = true
                        val next = (scale * event.calculateZoom())
                            .coerceIn(PictureZoom.MIN_SCALE, PictureZoom.MAX_SCALE)
                        offset = PictureZoom.zoomAround(
                            offset = offset,
                            centroid = event.calculateCentroid(useCurrent = true) - centre,
                            factor = if (scale == 0f) 1f else next / scale,
                        ) + event.calculatePan()
                        scale = next
                        offset = PictureZoom.clampOffset(offset, frame, fittedOnPage, scale)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .pointerInput(shown, frame) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { point ->
                        if (shown == null || fitted.width <= 0f || fitted.height <= 0f) {
                            return@detectTapGestures
                        }
                        val centre = Offset(frame.width / 2f, frame.height / 2f)
                        val targetScale = if (PictureZoom.isSettled(scale)) {
                            PictureZoom.DOUBLE_TAP_SCALE
                        } else {
                            PictureZoom.MIN_SCALE
                        }
                        val targetOffset = if (PictureZoom.isSettled(targetScale)) {
                            Offset.Zero
                        } else {
                            PictureZoom.clampOffset(
                                offset = PictureZoom.zoomAround(
                                    offset = offset,
                                    centroid = point - centre,
                                    factor = targetScale / scale,
                                ),
                                frame = frame,
                                fitted = fitted,
                                scale = targetScale,
                            )
                        }
                        val fromScale = scale
                        val fromOffset = offset
                        scope.launch {
                            animate(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = tween(VIEWER_ZOOM_MILLIS, easing = SinOutEasing),
                            ) { done, _ ->
                                scale = fromScale + (targetScale - fromScale) * done
                                offset = lerp(fromOffset, targetOffset, done)
                            }
                            scale = targetScale
                            offset = targetOffset
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            shown != null -> Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )

            load is PictureLoad.Loading -> CircularProgressIndicator()

            // A page whose copy is gone is a state the viewer draws, like the tile's count
            // line: the ground is black in both schemes, so the sentence takes the white the
            // old counter used rather than the theme's ink, which is near-black in the light
            // scheme.
            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Photos,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = VIEWER_INK,
                )
                Text(
                    text = text.chat.imageUnavailable,
                    style = MiuixTheme.textStyles.body2,
                    color = VIEWER_INK,
                )
            }
        }
    }
}

/** What one page of the viewer has to draw: the picture, the wait, or the miss. */
private sealed interface PictureLoad {
    data object Loading : PictureLoad
    data class Ready(val bitmap: ImageBitmap) : PictureLoad
    data object Missing : PictureLoad
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

/**
 * A chrome mark's box in the viewer, the same 42dp the conversation page's header uses.
 *
 * The conversation and the viewer are the two pages drawn entirely in floating chrome, and
 * a reader who has learned the header's discs should not find a second size for the same
 * object over a picture.
 */
private val VIEWER_MARK_SIZE = 42.dp

/**
 * The glyph inside a chrome disc: the header's 22dp mark, at the disc's own centre.
 *
 * 22 rather than the 24 this viewer used first: the header's circles are the object the
 * viewer's are now, and a glyph a size larger is a second weight of the same control.
 */
private val VIEWER_GLYPH_SIZE = 22.dp

/**
 * The ink the viewer's own sentences take, on the black ground it draws in both schemes.
 *
 * It cannot be the theme's `onSurface`: that is near-black in the light scheme, and the
 * ground here is black in *both* — the one place the app deliberately leaves the page's
 * surface (`§7.3`). White at 72% reads the same in either scheme and stays quieter than the
 * picture, which is the subject.
 */
private val VIEWER_INK = Color.White.copy(alpha = 0.72f)

/** How far the chrome sits from the window's edges, outside the status bar's inset. */
private val VIEWER_CHROME_INSET = 12.dp

/** How far the two foot actions sit above the navigation bar. */
private val VIEWER_ACTIONS_INSET = 20.dp

/** Between the two foot actions. */
private val VIEWER_ACTION_GAP = 12.dp

/** The flight's clock, longer in than out — the same asymmetry a page's motion has. */
private const val VIEWER_ENTER_MILLIS = 220
private const val VIEWER_EXIT_MILLIS = 180

/** How long a tap takes to show or hide the chrome: a state, not a transition to watch. */
private const val VIEWER_CHROME_MILLIS = 140

/** How long a double tap's zoom takes, on the page's own clock rather than the window's. */
private const val VIEWER_ZOOM_MILLIS = 220

/** How long the save's answer stays on screen: a sentence, not a state. */
private const val VIEWER_NOTICE_MILLIS = 1600L

/** How many tiles one row holds. Four pictures is the composer's cap: two rows of two. */
private const val PICTURES_PER_ROW = 2

/** The pixels a picture is decoded for in the transcript: a 150dp tile at 3.5x density. */
private const val TILE_PIXELS = 512

/** The pixels the viewer decodes for: the largest picture the app ever sends. */
private const val VIEW_PIXELS = SEND_IMAGE_MAX_EDGE

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
