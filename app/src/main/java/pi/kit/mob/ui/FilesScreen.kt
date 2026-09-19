package pi.kit.mob.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.PageHeader
import pi.kit.mob.ui.components.PageSwap
import pi.kit.mob.ui.components.Sheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A read-only browser over the agent's own filesystem, rooted at `$HOME`.
 *
 * Scope is deliberately narrow: it is here to answer "what did the agent
 * actually change?" without needing a second app, not to be a general file
 * manager. Writes go through the agent or the terminal.
 *
 * ## Why entering a folder is a page swap
 *
 * It is the one move in the app that replaces the whole screen with another one
 * and had no animation at all: the list cut from one directory to the next. It is
 * the Files tab's second level in every sense — the header's path changes, the
 * rows are new, and the way back is the same action row — so it moves the way a
 * second level moves everywhere else in the app.
 *
 * Only the *list* travels, not the header. Unlike the Settings pages, this page's
 * title does not change (it is always "Files"); what changes is the path under
 * it, and sliding an unchanged title out and back in would be motion without a
 * reason. The header's own path label updates in place, which is also what the
 * platform's document UI does.
 */
@Composable
fun FilesScreen(session: PiAgentSession) {
    val text = strings
    val home = remember { session.env.home }
    var current by remember { mutableStateOf(home) }
    val sheets = LocalSheetHost.current

    // Which way the last move went. A child is deeper than its parent by
    // definition, so comparing two depths is the whole rule; going home from
    // three levels down is one long slide backwards, which is what it is.
    var forward by remember { mutableStateOf(true) }

    fun navigate(to: File) {
        forward = to.depthUnder(home) > current.depthUnder(home)
        current = to
    }

    // The back gesture walks *up* the filesystem, exactly like the parent arrow in
    // the header, rather than leaving the app. A browser whose back gesture closes
    // the app from three directories down is the report this answers — on a phone
    // the gesture is the primary way back, and "back" on a listing means the
    // listing above it. At the top of the tree it is disabled (there is no parent
    // to go to), so back from there still leaves the tab as it always did.
    //
    // `PageBackHandler` and not `BackHandler`: a file preview is a sheet over this
    // page, and the gesture belongs to it first.
    PageBackHandler(enabled = current.parentFile != null) {
        current.parentFile?.let(::navigate)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = text.header.files,
            subtitle = relativeTo(home, current),
            actions = {
                IconButton(
                    onClick = { navigate(home) },
                    enabled = current != home,
                ) {
                    Icon(Icons.Filled.Home, contentDescription = text.files.home)
                }
                IconButton(
                    onClick = { current.parentFile?.let(::navigate) },
                    enabled = current.parentFile != null,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = text.files.parent)
                }
            },
        )

        PageSwap(
            key = current,
            forward = forward,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            DirectoryList(
                directory = page as File,
                text = text,
                onOpen = { file ->
                    if (file.isDirectory) {
                        navigate(file)
                    } else {
                        sheets.show(
                            Sheet(key = "files-preview:${file.absolutePath}") {
                                PreviewSheet(file = file, text = text)
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * One directory's entries.
 *
 * A composable of its own, keyed on the directory, because `PageSwap` draws two
 * of these at once for the length of the slide: the listing has to be a function
 * of the directory it belongs to, or the outgoing page would show the incoming
 * page's files on the way out.
 */
@Composable
private fun DirectoryList(
    directory: File,
    text: Strings,
    onOpen: (File) -> Unit,
) {
    val entries by produceState<List<File>>(initialValue = emptyList(), directory) {
        value = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .orEmpty()
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.absolutePath }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(file) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (file.isDirectory) {
                        Icons.Filled.Folder
                    } else {
                        Icons.Filled.Description
                    },
                    contentDescription = null,
                )
                Text(
                    file.name,
                    modifier = Modifier.weight(1f),
                    // Two lines, not one: a file the agent wrote can be
                    // named after what it did — a 60-character name is
                    // ordinary — and the name is this row's whole content.
                    // The name already holds the weighted column, so the
                    // size beside it gives up width first; the second line
                    // is what the name gets when even that is not enough.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!file.isDirectory) {
                    Text(
                        humanSize(file.length()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * The preview, in the app's modal layer.
 *
 * A dialog with a plain `Text` inside cannot be scrolled, so anything longer
 * than the screen was unreadable — which is most of what is worth previewing.
 * The sheet also leaves room for the action that actually gets the file out of
 * the app.
 *
 * Text is selected from the file on IO; the body scrolls, and "Open with" hands
 * the file to whatever else on the device claims it through a `content://` URI.
 *
 * A body for the layer rather than a sheet of its own: the panel, the grabber,
 * the scrim and the drag all belong to `SheetLayer`, and this is what goes inside
 * it. It is the one sheet in the app that is not a list, which is why the layer
 * takes a composable rather than a row spec.
 */
@Composable
private fun PreviewSheet(file: File, text: Strings) {
    val context = LocalContext.current
    val host = LocalSheetHost.current
    var body by remember(file) { mutableStateOf<String?>(null) }
    // Whether the last "Open with" tap could hand the file over. Kept here rather
    // than logged, because the user is standing in front of a button that did
    // nothing and deserves to be told why — see [openExternally].
    var openFailed by remember(file) { mutableStateOf(false) }

    LaunchedEffect(file) {
        body = withContext(Dispatchers.IO) { readPreview(file, text) }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    humanSize(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = host::dismiss) {
                Icon(Icons.Filled.Close, contentDescription = text.files.close)
            }
        }

        HorizontalDivider()

        val current = body
        if (current == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            }
        } else {
            // The body takes the space left over after the header and the
            // action row, and scrolls inside it: `weight` on a Column child
            // is what makes the text scrollable instead of pushing the
            // buttons off the sheet.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 160.dp, max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    current,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()
        if (openFailed) {
            Text(
                text.files.openFailed,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = host::dismiss) { Text(text.files.close) }
            Box(Modifier.weight(1f))
            Button(onClick = { openFailed = !openExternally(context, file) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(text.files.openWith, Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Hands the file to another app, and says so when it cannot.
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` is required: the URI points into this app's
 * private directory, which the receiving app cannot read on its own. The
 * chooser is explicit rather than a bare `ACTION_VIEW` so that a file with no
 * viewer still shows a list (and Android's own "no apps can do this" message)
 * instead of throwing `ActivityNotFoundException` at the user.
 *
 * ## Why the failure is returned rather than swallowed
 *
 * This used to be `FileProvider.getUriForFile(...) ?: return`, and that `return`
 * was the whole bug report: with shared storage switched on, tapping **Open with**
 * on a file under the `storage` links did nothing whatsoever — no chooser, no
 * error. The provider resolves a file's *canonical* path, and `~/storage/shared` is
 * a symlink to `/storage/emulated/0`, so the file was outside every configured
 * root and the call threw. The roots are fixed in `res/xml/file_paths.xml`; this
 * returns a failure anyway, because a silent no-op is the worst possible answer
 * from a button.
 *
 * @return true when a chooser was actually shown.
 */
private fun openExternally(context: Context, file: File): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.onFailure {
        Log.w(TAG, "no FileProvider root covers ${file.absolutePath}", it)
    }.getOrNull() ?: return false

    val mime = mimeTypeOf(file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // The chooser always exists, so this does not throw for "no viewer" — the
    // fallback is for an OEM build that has taken it away, and it keeps the failure
    // path honest rather than pretending the first attempt worked.
    return runCatching {
        context.startActivity(
            Intent.createChooser(view, file.name).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send)),
        )
    }.recoverCatching {
        context.startActivity(Intent.createChooser(send, file.name))
    }.isSuccess
}

private fun mimeTypeOf(file: File): String {
    val extension = file.extension.lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private const val PREVIEW_LIMIT_BYTES = 64 * 1024

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"

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

private fun relativeTo(root: File, file: File): String {
    val rootPath = root.absolutePath
    return if (file.absolutePath == rootPath) "~" else "~/" + file.absolutePath.removePrefix("$rootPath/")
}

/**
 * How many directories below [root] this one is.
 *
 * The direction of the page slide is the sign of the difference between two of
 * these. Counting separators rather than comparing path lengths because the two
 * agree on every path that can be reached — a child's path is its parent's plus
 * one component — and only one of them is the reason.
 */
private fun File.depthUnder(root: File): Int =
    absolutePath.removePrefix(root.absolutePath).count { it == '/' }

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
