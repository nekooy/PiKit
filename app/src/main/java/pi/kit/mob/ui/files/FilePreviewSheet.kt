/*
 * The preview a file row opens: what the file is, and the two things that can be
 * done with it.
 *
 * ## A sheet, and why it is still one
 *
 * The page is a file manager, but a preview is not a page: it is one file, read once,
 * and the way out of it is the scrim, the back gesture or a downward drag — the same
 * three a picker has. So this is the body of Miuix's window bottom sheet, raised by
 * `FilesScreen` from its own state, not a third level of navigation with its own back
 * arrow.
 *
 * ## What a file manager shows that a text box did not
 *
 * The first version was a monospace block with the file's text in it, and two things
 * were wrong with that for anything that is not a source file. A picture — the most
 * likely thing to tap in this tab — came out as `Binary file (48.2 KB)`, and a file
 * with a size and a date worth knowing said neither. This body therefore has:
 *
 *  - **an image, drawn**, when the suffix says the bytes are a picture;
 *  - the kind, the size and the modified time, in the heading's second line;
 *  - the text, for a text file, in a monospace well;
 *  - **Copy path**, beside **Open with** — the one action a file manager is asked
 *    for on a phone, and the one that makes the path in this page useful at all: a
 *    path that can be copied is a path that can be pasted into the chat page, which
 *    is the only other place in this app that takes one.
 *
 * ## The image is decoded once, downscaled
 *
 * There is no image loader in this app (see `PiFeedback.kt`), and there is no reason
 * for one here: `BitmapFactory` reads the header for the bounds, solves a sample
 * factor for a [PREVIEW_IMAGE_EDGE]-pixel box, and decodes once — off the main
 * thread, in a `produceState` keyed on the file. A 12-megapixel photo is 48 MB as an
 * `ARGB_8888` bitmap and about 3 MB at the size the sheet can show, and the sheet is
 * the only place in the app that would have paid for it.
 *
 * ## The body is capped against the window, not against a constant
 *
 * Miuix's bottom sheet caps itself at the window's height minus the top inset and does
 * not scroll: everything the body draws has to fit above the two action buttons, or
 * those buttons are pushed off the sheet. A portrait photo is the case that does it —
 * a 3:4 picture at the width of a phone is taller than the sheet — and a fixed body
 * cap of 420dp is no better on a phone held sideways, where the window is 360dp tall.
 * So the cap is solved from [LocalWindowInfo] the same way the delete dialog's is:
 * a share of the window, with the old 420dp as its ceiling on a tall one.
 */
package pi.kit.mob.ui.files

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.clipEntryFor
import pi.kit.mob.ui.kit.LocalPiToast
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * The sheet's body for [entry].
 *
 * The heading carries the facts a file manager puts on a preview — the name, then the
 * kind, the size and the modified time — because a file the agent wrote is usually
 * being looked at to answer "when did this change". It is drawn here rather than as
 * the sheet's own `title`, which takes one string: a preview's two lines are one
 * block, and a single-line title would either drop the facts or run them into the
 * name.
 */
@Composable
internal fun FilePreviewSheet(entry: FileEntry) {
    val text = strings
    val context = LocalContext.current
    val toasts = LocalPiToast.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val kind = fileKind(
        file = entry.file,
        isDirectory = entry.isDirectory,
        isLink = entry.isLink,
        folder = text.files.folder,
        link = text.files.link,
        plain = text.files.file,
    )
    val stamp = stampFor(entry.modifiedAt, System.currentTimeMillis())
    val facts = listOfNotNull(
        kind,
        humanSize(entry.size).takeIf { !entry.isDirectory },
        stamp.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    var body by remember(entry.file) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.file) {
        body = withContext(Dispatchers.IO) { readPreview(entry.file, text) }
    }

    // Decoded here rather than inside `FileImage`, because the two bodies are one
    // decision: a picture that was drawn must not be followed by the text body's
    // "this file is 119.1 KB; preview is limited to 64 KB" — which is what the first
    // version did for every image over the limit, and it read as a failure under a
    // picture that had just been displayed.
    val picture by produceState<ImageBitmap?>(initialValue = null, entry.file) {
        if (entry.isImage) value = withContext(Dispatchers.IO) { decodePreview(entry.file) }
    }
    val drawn = picture

    // The room the body may take: the sheet caps itself at the window height and does
    // not scroll, so the body has to leave the heading and the two buttons inside it.
    // A dialog/sheet window is full-screen, so this is the window the sheet is drawn in.
    val bodyHeight = minOf(
        PREVIEW_BODY_HEIGHT,
        LocalWindowInfo.current.containerDpSize.height * PREVIEW_BODY_SHARE,
    )

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = entry.name,
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
                // Two lines, like the row that opened this: the suffix is what tells
                // one export of a file from the next, and it is at the end.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = facts,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (drawn != null) {
            FileImage(drawn, bodyHeight)
        } else {
            // No picture: either not an image at all, or one this device cannot
            // decode — in which case the text body's sentence about a binary file is
            // the right answer.
            PreviewBody(body, bodyHeight)
        }

        // Two actions, both about the file rather than about the sheet: a sheet is
        // dismissed four ways that need no control, so a *Close* would be a fifth
        // (§13). The primary one is a filled button and sits last, under the reader's
        // thumb; the path is the secondary answer and keeps the quiet treatment.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = text.files.copyPath,
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(clipEntryFor(entry.file.absolutePath))
                        toasts?.show(text.files.pathCopied)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    // See `openExternally`: the failure is a toast rather than a
                    // silent no-op, because the button visibly did nothing.
                    if (!openExternally(context, entry.file)) {
                        toasts?.show(text.files.openFailed)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = text.files.openWith, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * The text, or the reason there is none.
 *
 * The minimum height is the sheet's own promise that a one-line file still looks like
 * a preview rather than like a sheet that failed to load, and the ceiling is what
 * keeps a 64 KB file from covering the window: the text scrolls inside it. The sheet's
 * own column does not scroll, so the ceiling is what keeps the two action buttons on
 * screen — see the header for why it comes from the window rather than from a constant.
 */
@Composable
private fun PreviewBody(body: String?, maxHeight: Dp) {
    val current = body
    if (current == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = current,
            fontFamily = FontFamily.Monospace,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The picture itself, decoded once at sheet size.
 *
 * Drawn into a box that is width-bound and height-capped: a portrait photo is taller
 * than the sheet, so the cap is the picture's own bound as well as the body's, and
 * `ContentScale.Fit` letterboxes it inside rather than letting it push the action row
 * off the bottom. Without the cap the sheet grew to the picture and the two buttons
 * went with the part that was cut.
 */
@Composable
private fun FileImage(bitmap: ImageBitmap, maxHeight: Dp) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/**
 * Decodes [file] at about [PREVIEW_IMAGE_EDGE] pixels on its longest side.
 *
 * Two passes because the first reads the header only: `inSampleSize` has to be a
 * power of two, so it is solved from the file's own bounds rather than guessed from
 * its byte count. A file that is not a decodable image returns null and the sheet
 * draws only the text body — which is what an `.svg` or a corrupt `.png` deserves
 * rather than an error about a preview.
 */
private fun decodePreview(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= PREVIEW_IMAGE_EDGE ||
        bounds.outHeight / (sample * 2) >= PREVIEW_IMAGE_EDGE
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.onFailure { Log.w(TAG, "could not decode ${file.absolutePath}", it) }.getOrNull()

/**
 * The file's text, or the sentence that says why it is not that.
 *
 * Three refusals and one read, in the order they cost: a folder is not a file, a file
 * over [PREVIEW_LIMIT_BYTES] is not read at all (the page is read-only, and 64 KB is
 * where a phone-sized sheet stops being a preview), and a NUL byte in the first 64 KB
 * is what "binary" means here — a cheap test that a `.zip` and a `.png` both fail and a
 * UTF-8 source file passes.
 */
private fun readPreview(file: File, text: Strings): String = runCatching {
    if (!file.isFile) return@runCatching text.files.notAFile
    if (file.length() > PREVIEW_LIMIT_BYTES) {
        return@runCatching text.files.tooLarge(humanSize(file.length()))
    }
    val bytes = file.readBytes()
    if (bytes.any { it == 0.toByte() }) {
        return@runCatching text.files.binary(humanSize(file.length()))
    }
    bytes.toString(Charsets.UTF_8)
}.getOrElse { text.files.unreadable(it.message.orEmpty()) }

private const val PREVIEW_LIMIT_BYTES = 64 * 1024

/** The box an image preview is decoded into; the sheet is about 411dp wide. */
private const val PREVIEW_IMAGE_EDGE = 1024

/**
 * The body's height on a tall window, and the share of a short one it may take.
 *
 * 420dp was this body's fixed ceiling, measured on a portrait phone where the sheet is
 * about 800dp tall: heading, body and two buttons fit inside the sheet's own
 * `windowHeight - topInset` bound. A landscape phone is ~360dp tall, so the same body
 * has to yield — just under half the window leaves the heading, the two buttons and the
 * sheet's drag handle their places.
 */
private val PREVIEW_BODY_HEIGHT = 420.dp

/** The share of a short window the preview body may take. */
private const val PREVIEW_BODY_SHARE = 0.45f

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
