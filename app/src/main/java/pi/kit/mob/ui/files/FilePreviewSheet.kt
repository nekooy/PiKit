/*
 * The preview a file row opens: what the file is, and the two things that can be
 * done with it.
 *
 * ## A sheet, and why it is still one
 *
 * The page is a file manager, but a preview is not a page: it is one file, read once,
 * and the way out of it is the scrim, the back gesture or a downward drag — the same
 * three a picker has. So it is a `Sheet` body on the app's one modal layer, not a
 * third level of navigation with its own back arrow.
 *
 * ## What a file manager shows that a text box did not
 *
 * The first version was a monospace block with the file's text in it, and two things
 * were wrong with that for anything that is not a source file. A picture — the most
 * likely thing to tap in this tab — came out as `Binary file (48.2 KB)`, and a file
 * with a size and a date worth knowing said neither. This body therefore has:
 *
 *  - **an image, drawn**, when the suffix says the bytes are a picture;
 *  - the kind, the size and the modified time, in the title's second line;
 *  - the text, for a text file, in the same monospace well as before;
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
 */
package pi.kit.mob.ui.files

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.clipEntryFor
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.LocalToastHost
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiSheetActions
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetTitle
import java.io.File

/**
 * The sheet's body for [entry].
 *
 * The title carries the facts a file manager puts on a preview — the name, then the
 * kind, the size and the modified time — because a file the agent wrote is usually
 * being looked at to answer "when did this change".
 */
@Composable
internal fun FilePreviewSheet(entry: FileEntry) {
    val text = strings
    val context = LocalContext.current
    val host = LocalSheetHost.current
    val toasts = LocalToastHost.current
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
    val subtitle = listOfNotNull(
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

    Column(Modifier.fillMaxWidth()) {
        PiSheetTitle(title = entry.name, subtitle = subtitle)

        // `fill = false`: the body takes the height it needs and no more, which is
        // what makes a long text scroll inside the sheet instead of pushing the action
        // row off the bottom of the panel.
        Box(Modifier.weight(1f, fill = false)) {
            PiSheetList {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        if (drawn != null) {
                            FileImage(drawn)
                        } else {
                            // No picture: either not an image at all, or one this
                            // device cannot decode — in which case the text body's
                            // sentence about a binary file is the right answer.
                            PreviewBody(body)
                        }
                    }
                }
            }
        }

        // Two actions, both about the file rather than about the sheet: a sheet is
        // dismissed four ways that need no control, so a *Close* would be a fifth
        // (§13). `PiSheetActions` end-aligns them, which puts the one real action under
        // the reader's thumb.
        PiSheetActions {
            PiButton(
                text = text.files.copyPath,
                onClick = {
                    // Gated on the sheet still being open for the same reason every
                    // control in a sheet is: the panel keeps drawing while it leaves,
                    // and a second tap must not copy after the reader dismissed it.
                    if (!host.isOpen) return@PiButton
                    scope.launch {
                        clipboard.setClipEntry(clipEntryFor(entry.file.absolutePath))
                        toasts.show(text.files.pathCopied)
                    }
                },
                kind = PiButtonKind.Outlined,
                size = PiButtonSize.Small,
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            )
            PiButton(
                text = text.files.openWith,
                onClick = {
                    // See `openExternally`: the failure is a toast rather than a
                    // silent no-op, because the button visibly did nothing.
                    if (host.isOpen && !openExternally(context, entry.file)) {
                        toasts.show(text.files.openFailed)
                    }
                },
                kind = PiButtonKind.Filled,
                size = PiButtonSize.Small,
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                },
            )
        }
    }
}

/**
 * The text, or the reason there is none.
 *
 * The minimum height is the sheet's own promise that a one-line file still looks like
 * a preview rather than like a sheet that failed to load, and the ceiling is what
 * keeps a 64 KB file from covering the window: the text scrolls inside it.
 */
@Composable
private fun PreviewBody(body: String?) {
    val current = body
    if (current == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            PiLoading()
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = current,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The picture itself, decoded once at sheet size. */
@Composable
private fun FileImage(bitmap: ImageBitmap) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
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

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
