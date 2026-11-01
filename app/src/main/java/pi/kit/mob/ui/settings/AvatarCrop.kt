/*
 * The avatar crop window: a circular viewport the picture is dragged and zoomed
 * under, and the arithmetic that turns what it shows back into source pixels.
 *
 * A picture arrives at whatever shape the camera or the download produced, and an
 * avatar is a circle drawn from a square, so the step between the two is a *choice of
 * what the circle shows*. The picture is dragged and zoomed under a circular window
 * and the square that window inscribes is what gets stored.
 *
 * ## What the square one got wrong
 *
 * Two reports — "it cannot zoom" and "the proportions break when a picture goes in" —
 * turned out to be one cause. The picture was a child of the crop frame and asked for
 * `Modifier.size(width × cover × zoom)`, but `size` *enforces the incoming
 * constraints*, and the frame hands a child a maximum of its own side. So at every
 * zoom the bitmap was measured back down to the frame and `ContentScale.FillBounds`
 * stretched it to that square: a 3:4 portrait lost a quarter of its height, and the
 * pinch changed a number nothing drew. The offset limit was worked out from the size
 * that had been *asked for* rather than the size that was granted, so a drag moved a
 * frame-sized picture off centre and left the backdrop showing behind it.
 *
 * The picture is now drawn by `Canvas` with `drawImage` and an explicit destination
 * rectangle, which no parent constraint can touch: one scale for both axes, so the
 * aspect ratio is the source's at every zoom, and a destination free to be larger than
 * the window.
 *
 * ## The mask is a circle, because the avatar is one
 *
 * The frame used to be a rounded square, so the reader composed inside a shape the
 * transcript never draws. The circle is the shape that will be shown, the outside is
 * dimmed rather than cut away — the rest of the picture is still there to compose
 * against — and the ring marks the exact edge.
 *
 * ## Three ways to zoom, because one of them will not be found
 *
 * A pinch is two fingers on a control inside a dialog, and a dialog is the place a
 * gesture is most likely to be lost. The slider under the window is the same zoom on
 * one finger and is visible without being tried; a double tap is the shortcut everyone
 * already knows. All three write the same two numbers, so they cannot disagree.
 */

package pi.kit.mob.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pi.kit.mob.locales.Strings
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The crop's arithmetic, with no Android type in it.
 *
 * Every number the window draws and every number the stored picture is cut by comes
 * from here, which is what makes "the circle shows exactly what is kept" a property of
 * one function rather than a promise two copies of the same code keep by accident.
 *
 * The window is a **square of side `frame`** with a circle inscribed in it, so a single
 * number describes its size. The picture is drawn at one scale `s` for both axes —
 * `s = cover × zoom`, where `cover = max(frame / width, frame / height)` is the scale at
 * which the picture exactly covers the frame — and its centre sits at
 * `offset` from the frame's centre.
 */
internal object AvatarCropGeometry {

    /** The picture at least covers the circle: there is never a wedge with nothing in it. */
    const val MIN_ZOOM = 1f

    /**
     * Four, because past it the window is showing interpolation rather than the source's
     * own pixels. The decoded source keeps its shorter side at 1024px or more, so at 4×
     * the square that is stored is 256–512px — around the ~290px the editor's own 96dp
     * preview draws at on a 3× screen, and well above the transcript's 34dp mark.
     */
    const val MAX_ZOOM = 4f

    /** The scale and the drawn size of the picture in a frame of side [frame]. */
    data class Placement(val scale: Float, val size: Size)

    fun placement(frame: Float, width: Int, height: Int, zoom: Float): Placement {
        val cover = maxOf(frame / width, frame / height)
        val scale = cover * zoom
        return Placement(scale, Size(width * scale, height * scale))
    }

    /**
     * [candidate] moved back until the picture still covers the frame.
     *
     * Half the overhang is the limit in each direction: past it a corner of the window
     * shows the background instead of the picture, which is a crop with nothing to keep.
     */
    fun clampOffset(candidate: Offset, frame: Float, drawn: Size): Offset {
        val maxX = ((drawn.width - frame) / 2f).coerceAtLeast(0f)
        val maxY = ((drawn.height - frame) / 2f).coerceAtLeast(0f)
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    /**
     * The offset that keeps the point under [centroid] under [centroid] after a zoom by
     * [factor].
     *
     * [centroid] is measured from the frame's centre. The picture's centre moves from
     * `o` to `c − (c − o) × factor`: what is under the fingers stays under the fingers,
     * which is the difference between a pinch that feels attached to the picture and one
     * that pulls it towards the middle of the window.
     */
    fun zoomAround(offset: Offset, centroid: Offset, factor: Float): Offset =
        centroid - (centroid - offset) * factor

    /** A square of the source, in source pixels. */
    data class Square(val left: Int, val top: Int, val side: Int)

    /**
     * The square the circle shows, in the source's own pixels.
     *
     * The window is `frame` wide and the picture is drawn at [placement]'s scale, so the
     * window's own width is `frame / scale` source pixels across; the centre is the
     * picture's centre moved by the offset divided by the same scale. Clamped to the
     * bitmap because a crop that ran off the edge throws rather than producing a slightly
     * smaller square, and rounded only at the end — the preview is a guide, the bitmap is
     * the answer.
     */
    fun sourceSquare(width: Int, height: Int, frame: Float, zoom: Float, offset: Offset): Square {
        val scale = placement(frame, width, height, zoom).scale
        val side = (frame / scale).roundToInt().coerceIn(1, minOf(width, height))
        val centreX = width / 2f - offset.x / scale
        val centreY = height / 2f - offset.y / scale
        val left = (centreX - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (centreY - side / 2f).roundToInt().coerceIn(0, height - side)
        return Square(left, top, side)
    }
}

/**
 * What the crop window is currently showing.
 *
 * The state is an object rather than three values in the composable because the gesture
 * handlers *write* it: a lambda that captured a `Float` would keep writing against the
 * value it was created with — the classic pinch that snaps back on the second event —
 * while a property backed by a `MutableState` is read at the moment it is used.
 *
 * [frame] is set by the stage once it has been laid out. Nothing is drawn differently
 * for it being zero, but the gestures are ignored until it is known, because every
 * limit is expressed in its pixels.
 */
@Stable
internal class AvatarCropState(
    /** The source's width, in pixels. */
    private val width: Int,
    /** And its height. */
    private val height: Int,
) {
    var zoom by mutableFloatStateOf(AvatarCropGeometry.MIN_ZOOM)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The stage's side in pixels; zero until it has been measured. */
    var frame by mutableIntStateOf(0)

    private fun drawnAt(zoom: Float): Size =
        AvatarCropGeometry.placement(frame.toFloat(), width, height, zoom).size

    private fun stageCentre(): Offset = Offset(frame / 2f, frame / 2f)

    /**
     * One step of a drag or a pinch.
     *
     * [centroid] and [pan] are the gesture's own numbers: both in stage-local pixels.
     * The zoom is clamped *before* the offset is worked out, so a pinch that has reached
     * the limit slides the picture instead of moving it in the direction it could not
     * zoom towards.
     */
    fun transform(centroid: Offset, pan: Offset, factor: Float) {
        if (frame == 0) return
        val from = zoom
        val to = (from * factor).coerceIn(AvatarCropGeometry.MIN_ZOOM, AvatarCropGeometry.MAX_ZOOM)
        val moved = AvatarCropGeometry.zoomAround(offset, centroid - stageCentre(), to / from) + pan
        zoom = to
        offset = AvatarCropGeometry.clampOffset(moved, frame.toFloat(), drawnAt(to))
    }

    /** The slider's step: a zoom about the window's centre, which is what the thumb implies. */
    fun zoomTo(value: Float) {
        if (frame == 0) return
        zoom = value.coerceIn(AvatarCropGeometry.MIN_ZOOM, AvatarCropGeometry.MAX_ZOOM)
        offset = AvatarCropGeometry.clampOffset(offset, frame.toFloat(), drawnAt(zoom))
    }

    /**
     * A double tap: back to the whole picture, or in on the point that was tapped.
     *
     * Out is a *reset* — the zoom and the position both — because the reader who taps out
     * is asking to see the whole picture again, and a picture left off-centre at 1× is
     * still a crop they did not ask for. In is anchored on the tap, which is the point
     * they were looking at.
     */
    fun toggle(centroid: Offset) {
        if (frame == 0) return
        if (zoom > AvatarCropGeometry.MIN_ZOOM + 0.01f) {
            zoom = AvatarCropGeometry.MIN_ZOOM
            offset = Offset.Zero
            return
        }
        val factor = DOUBLE_TAP_ZOOM / zoom
        val moved = AvatarCropGeometry.zoomAround(offset, centroid - stageCentre(), factor)
        zoom = DOUBLE_TAP_ZOOM
        offset = AvatarCropGeometry.clampOffset(moved, frame.toFloat(), drawnAt(DOUBLE_TAP_ZOOM))
    }

    /** The square the circle is showing, in the source's pixels. */
    fun square(): AvatarCropGeometry.Square =
        AvatarCropGeometry.sourceSquare(width, height, frame.toFloat(), zoom, offset)
}

/**
 * The crop window: the stage, one zoom row, and the two answers.
 *
 * [show] is passed through rather than the window being raised inside an `if`, because
 * a Miuix window animates out through that flag: removing the composable closes it with
 * its content already gone. The caller keeps the last picture for exactly that reason.
 */
@Composable
internal fun AvatarCropDialog(
    picture: Bitmap,
    show: Boolean,
    text: Strings,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val image = remember(picture) { picture.asImageBitmap() }
    val state = remember(picture) { AvatarCropState(picture.width, picture.height) }

    WindowDialog(
        show = show,
        title = text.settings.avatarCropTitle,
        summary = text.settings.avatarCropHint,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(Modifier.fillMaxWidth()) {
            CropStage(
                state = state,
                image = image,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(PiCorner.radius)),
            )
            ZoomRow(state = state, text = text)
            PiDialogAnswers(
                dismiss = text.common.cancel,
                onDismiss = onDismiss,
                confirm = text.settings.avatarCropUse,
                onConfirm = {
                    // The frame is measured before the window is tappable, so this guard
                    // only ever fires for a picture that never reached the screen.
                    if (state.frame > 0) {
                        val square = state.square()
                        onConfirm(
                            Bitmap.createBitmap(
                                picture,
                                square.left,
                                square.top,
                                square.side,
                                square.side,
                            ),
                        )
                    }
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * The picture under its circular mask, and the gestures that move it.
 *
 * The whole thing is one `Canvas`: the bitmap, the dimmed outside and the ring are three
 * draws in one draw pass, so a drag never re-measures anything — it only re-draws.
 *
 * Both gesture detectors are attached to the same node. They coexist because neither
 * consumes the down event: the tap detector waits for an up it never gets once the
 * transform detector starts consuming movement, and a double tap therefore survives a
 * stage that can also be dragged.
 */
@Composable
private fun CropStage(
    state: AvatarCropState,
    image: ImageBitmap,
    modifier: Modifier = Modifier,
) {
    val backdrop = MiuixTheme.colorScheme.surfaceContainer
    Canvas(
        modifier = modifier
            .onSizeChanged { state.frame = it.width }
            .pointerInput(image) {
                detectTransformGestures { centroid, pan, factor, _ ->
                    state.transform(centroid, pan, factor)
                }
            }
            .pointerInput(image) {
                detectTapGestures(onDoubleTap = { state.toggle(it) })
            },
    ) {
        val side = size.width
        drawRect(backdrop)

        val drawn = AvatarCropGeometry.placement(side, image.width, image.height, state.zoom).size
        drawImage(
            image = image,
            dstOffset = IntOffset(
                ((side - drawn.width) / 2f + state.offset.x).roundToInt(),
                ((size.height - drawn.height) / 2f + state.offset.y).roundToInt(),
            ),
            dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )

        // Everything outside the circle, dimmed rather than cut: the rest of the picture
        // is still there to compose against, and a circle cut out of a photo reads as a
        // hole in the window instead of as the part that will be kept.
        val outside = Path().apply {
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(center = size.center, radius = side / 2f))
            fillType = PathFillType.EvenOdd
        }
        drawPath(outside, SCRIM)

        drawCircle(
            color = RING,
            radius = side / 2f - RING_WIDTH.toPx() / 2f,
            style = Stroke(RING_WIDTH.toPx()),
        )
    }
}

/**
 * The zoom, on one finger.
 *
 * It reads and writes the same two numbers the pinch does, so a reader who never tries a
 * pinch still gets the zoom — and one who pinches sees the thumb move with them.
 */
@Composable
private fun ZoomRow(state: AvatarCropState, text: Strings) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.settings.avatarCropZoom,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Slider(
            value = state.zoom,
            onValueChange = { state.zoomTo(it) },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            valueRange = AvatarCropGeometry.MIN_ZOOM..AvatarCropGeometry.MAX_ZOOM,
        )
        Text(
            text = zoomLabel(state.zoom),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * The zoom as a multiplier, one decimal.
 *
 * Built from integers rather than `String.format`, because a locale whose decimal mark
 * is a comma would draw `1,5×` beside a slider whose every other number is a percentage
 * with a full stop.
 */
private fun zoomLabel(zoom: Float): String {
    val tenths = (zoom * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10}×"
}

/** How dark the part of the picture outside the circle is drawn. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/** The ring's colour, over any picture: the scrim already makes the edge a step. */
private val RING = Color.White.copy(alpha = 0.9f)

/** Thick enough to be seen on a 3x screen, thin enough not to eat the picture. */
private val RING_WIDTH = 1.5.dp

/** Where a double tap lands when it is not going back out. */
private const val DOUBLE_TAP_ZOOM = 2.5f
