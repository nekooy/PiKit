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
 *  - [PictureViewer] is the full window a tap opens: one picture at a time, swiped
 *    between or pinched into, with Miuix's floating chrome over a black ground.
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

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.ChatImages
import pi.kit.mob.pi.ImageAttachment
import pi.kit.mob.pi.SessionTranscript
import pi.kit.mob.ui.kit.PiTone
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
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
 * ## Why the window is the platform's, and the contents are Miuix's
 *
 * Miuix ships no picture viewer, and a viewer is not one of its dialogs: `WindowDialog` is
 * a card by construction — it keeps the status bar out of its panel (`DialogContentLayout`
 * pads the panel by the status bar's inset), insets it from the window's edges at
 * `DialogDefaults.outsideMargin`, and cuts it with the screen's own corner radius. Those
 * are right for a question and wrong for a photograph, because the strip above the panel
 * would show the page the reader came from. So this is the one surface in the app drawn in
 * a platform `Dialog` — `decorFitsSystemWindows = false`, so the black ground reaches the
 * window's last pixel — and everything *on* it is the app's Miuix vocabulary: floating
 * capsules at the same 42dp the conversation page's header wears, the library's type and
 * glyphs, its progress indicator, and its sine-out clock. The capsules take a dark scrim
 * rather than the page's own fill, and the reason is on [VIEWER_CHROME_FILL]: the page is
 * not what they float over.
 *
 * It is drawn from the current still, not a platform dialog that appears and disappears;
 * `Dialog` is created for the first frame and torn down after the exit, which reads as a
 * window opening rather than as a page being replaced.
 *
 * ## Why it can be zoomed, and what the gestures stay out of
 *
 * A picture that fills the screen at `ContentScale.Fit` is a picture read at the size the
 * phone chose, not a look at its detail: a screenshot's small type is unreadable until it is
 * magnified. Pinch zooms about the fingers and a double tap toggles between fitted and
 * [PictureZoom.DOUBLE_TAP_SCALE]; while the picture is zoomed, one finger pans it inside its
 * frame. The pager keeps its own horizontal drag until the picture is zoomed *and* the
 * one-finger drag is claimed by this page — a gesture this page consumes is one the pager
 * cancels, which is how swiping between pictures and panning one picture can share the same
 * finger ([PictureZoom] has the arithmetic).
 *
 * ## The chrome, and why a tap does not close it
 *
 * The old viewer closed on any tap, which made zoom impossible to add and made a mis-tap on
 * a photograph end the look. It now draws the app's floating capsules — a back disc at the
 * leading edge and, for a prompt with several pictures, the position in a pill at the
 * trailing one — and a tap anywhere else shows or hides them. Closing is the back disc, the
 * platform's back gesture, or the dialog's own dismissal, and every one of those is a
 * deliberate exit rather than a stray touch. The system's bar icons are made light while the
 * dialog is up: the ground behind them is black in both schemes, and the app's own light
 * scheme would otherwise draw near-black icons on it.
 *
 * The copy on disk is decoded per page, at viewer size, and released when the page leaves
 * the composition: four pictures of a prompt are 26 MB of bitmaps if all four are held, and
 * only one is ever looked at.
 */
@Composable
internal fun PictureViewer(
    show: Boolean,
    target: PictureTarget,
    text: Strings,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    if (target.keys.isEmpty()) return
    val pager = rememberPagerState(
        initialPage = target.start.coerceIn(0, target.keys.lastIndex),
    ) { target.keys.size }
    var chrome by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(show) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(show) {
        if (show) {
            visible = true
            chrome = true
            progress.animateTo(1f, tween(VIEWER_ENTER_MILLIS, easing = SinOutEasing))
        } else if (visible) {
            progress.animateTo(0f, tween(VIEWER_EXIT_MILLIS, easing = SinOutEasing))
            visible = false
            onDismissFinished()
        }
    }
    if (!visible) return

    // The dialog window is a second window, and the app's theme sets the bar icons through
    // the activity's. A viewer is the one surface that wants the bars out of the way: the
    // ground behind them is a photograph, and a white status bar on a white screenshot
    // (or a near-black one on a dark photo) is unreadable whichever ink it takes. They are
    // hidden rather than tinted, and the system brings them back transiently on a swipe —
    // the gallery convention, and the only answer that does not depend on what is on screen.
    // The activity's own flags are untouched and come back with it when the dialog closes.
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // The window is the dialog's, not the activity's: it is going away, and the
            // activity's own bars come back with it.
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The window opens as a surface rather than as a cut: it fades in while
                    // growing the last few per cent, which is the library's own dialog idiom
                    // seen from the other side of the window.
                    val current = progress.value
                    alpha = current
                    val scale = VIEWER_ENTER_SCALE + (1f - VIEWER_ENTER_SCALE) * current
                    scaleX = scale
                    scaleY = scale
                }
                .background(Color.Black),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                PicturePage(
                    key = target.keys[page],
                    text = text,
                    onToggleChrome = { chrome = !chrome },
                )
            }

            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(VIEWER_CHROME_MILLIS)),
                exit = fadeOut(tween(VIEWER_CHROME_MILLIS)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = VIEWER_CHROME_INSET, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(VIEWER_MARK_SIZE),
                        backgroundColor = VIEWER_CHROME_FILL,
                        cornerRadius = VIEWER_MARK_SIZE / 2,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = text.common.back,
                            tint = Color.White,
                            modifier = Modifier.size(VIEWER_GLYPH_SIZE),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (target.keys.size > 1) {
                        Box(
                            modifier = Modifier
                                .height(VIEWER_MARK_SIZE)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(VIEWER_CHROME_FILL)
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = text.chat.picturePosition(
                                    pager.currentPage + 1,
                                    target.keys.size,
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One page of the viewer: the picture, its zoom, and the gesture that owns the finger.
 *
 * The gesture is one loop rather than a `transformable` beside a pager: whether a drag
 * belongs to this page or to the pager is exactly the question, and only the page knows
 * whether it is zoomed. A single finger on an unzoomed picture is left unconsumed, so the
 * pager's own detector takes it; the moment a second finger lands, or a pan begins while
 * the picture is zoomed, every change this loop sees is consumed and the pager stands down
 * for the rest of the gesture — including a pinch that ends back at fit, so the release
 * cannot turn into a page swipe.
 */
@Composable
private fun PicturePage(
    key: String,
    text: Strings,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val generation by ChatPictureCache.generation.collectAsState()
    val load by produceState<PictureLoad>(PictureLoad.Loading, key, generation) {
        val bitmap = withContext(Dispatchers.IO) {
            ChatPictureCache.load(context.filesDir, key, VIEW_PIXELS)
        }
        value = bitmap?.let(PictureLoad::Ready) ?: PictureLoad.Missing
    }
    val bitmap = (load as? PictureLoad.Ready)?.bitmap
    var frame by remember(key) { mutableStateOf(Size.Zero) }
    var scale by remember(key) { mutableFloatStateOf(PictureZoom.MIN_SCALE) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val fitted = remember(bitmap, frame) {
        bitmap?.let { PictureZoom.fitted(Size(it.width.toFloat(), it.height.toFloat()), frame) }
            ?: Size.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { frame = it.toSize() }
            .pointerInput(load, frame) {
                val drawn = (load as? PictureLoad.Ready)?.bitmap ?: return@pointerInput
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
            .pointerInput(load, frame) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { point ->
                        if (bitmap == null || fitted.width <= 0f || fitted.height <= 0f) {
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
        when (val current = load) {
            is PictureLoad.Ready -> Image(
                bitmap = current.bitmap,
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

            PictureLoad.Loading -> CircularProgressIndicator()

            // A page whose copy is gone is a state the viewer draws, like the tile's count
            // line: the ground is black in both schemes, so the sentence takes the white the
            // old counter used rather than the theme's ink, which is near-black in the light
            // scheme.
            PictureLoad.Missing -> Column(
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

/** The glyph inside a chrome capsule: the header's 22–24dp step, at the disc's own centre. */
private val VIEWER_GLYPH_SIZE = 24.dp

/**
 * The ink the viewer's own sentences take, on the black ground it draws in both schemes.
 *
 * It cannot be the theme's `onSurface`: that is near-black in the light scheme, and the
 * ground here is black in *both* — the one place the app deliberately leaves the page's
 * surface (`§7.3`). White at 72% reads the same in either scheme and stays quieter than the
 * picture, which is the subject.
 */
private val VIEWER_INK = Color.White.copy(alpha = 0.72f)

/**
 * A chrome capsule's fill, over the picture rather than over the page.
 *
 * The app's floating controls are filled with `PiGlass.fill()` — the page's own raised
 * surface — because a page scrolls underneath them and a tinted capsule would let it show
 * through (§13). A picture does nothing of the kind, and the same fill is exactly wrong
 * here: in the light scheme it is white, so a white screenshot hid the capsule, the disc and
 * the counter outright (measured on the viewer's first device pass). The ground behind the
 * chrome is an arbitrary photograph, so the fill is a dark scrim with white ink — the one
 * treatment that is legible over *any* picture, and the one every gallery uses.
 */
private val VIEWER_CHROME_FILL = Color.Black.copy(alpha = 0.38f)

/** How far the chrome sits from the window's edges, outside the status bar's inset. */
private val VIEWER_CHROME_INSET = 12.dp

/** The window's fade-and-grow, longer in than out — the same asymmetry a page's motion has. */
private const val VIEWER_ENTER_MILLIS = 180
private const val VIEWER_EXIT_MILLIS = 160

/** How long a tap takes to show or hide the chrome: a state, not a transition to watch. */
private const val VIEWER_CHROME_MILLIS = 140

/** A double tap's zoom, on the page's own clock rather than the window's. */
private const val VIEWER_ZOOM_MILLIS = 220

/** The scale the window starts at, before its opening animation has grown it to 1. */
private const val VIEWER_ENTER_SCALE = 0.94f

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
