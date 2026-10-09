package pi.kit.mob.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiAppBarScroll
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiEmptyState
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPageSwap
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiSheetActions
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetRow
import pi.kit.mob.ui.design.PiSheetTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * A read-only browser over the agent's own filesystem, rooted at `$HOME`.
 *
 * Scope is deliberately narrow: it is here to answer "what did the agent
 * actually change?" without needing a second app, not to be a general file
 * manager. Writes go through the agent or the terminal.
 *
 * ## The page is the path
 *
 * Where the app bar's title is a fact that never changes ("Files"), the bar's
 * subtitle is the one that does, and it is the path the reader is standing in.
 * That is what this page has to say about itself, so it says it under the title
 * rather than in a band of its own — the browser's whole state is one string.
 *
 * ## Why entering a folder is a page swap
 *
 * It is the one move in the app that replaces the whole screen with another one
 * and had no animation at all: the list cut from one directory to the next. It is
 * the Files tab's second level in every sense — the path changes, the rows are
 * new, and the way back is the same action — so it moves the way a second level
 * moves everywhere else in the app.
 *
 * Only the *listing* travels, not the bar. Unlike the Settings pages, this page's
 * title does not change (it is always "Files"); what changes is the path under
 * it, and sliding an unchanged title out and back in would be motion without a
 * reason. The path updates in place as the listing slides, which is also what the
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
    // the app bar, rather than leaving the app. A browser whose back gesture closes
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

    PiScaffold(
        title = text.header.files,
        subtitle = relativeTo(home, current),
        // Collapsing rather than pinned: what the bar holds is the path, and the
        // path is one line of a listing the reader wants the height back for as
        // soon as they start scrolling it.
        scrollBehavior = PiAppBarScroll.Collapsing,
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
    ) { body ->
        PiPageSwap(
            key = current,
            forward = forward,
            modifier = body,
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
 * A composable of its own, keyed on the directory, because `PiPageSwap` draws two
 * of these at once for the length of the slide: the listing has to be a function
 * of the directory it belongs to, or the outgoing page would show the incoming
 * page's files on the way out.
 *
 * ## The rows are inset, and the hairline follows them
 *
 * The rows were drawn edge to edge with a full-bleed hairline, 16dp from the screen
 * instead of the 24dp a row's text sits at everywhere else, and the tap target had
 * square corners where every other row in the app has the row radius. The
 * conversations list was the same flat list and the same complaint — "a full-bleed
 * hairline that did not match the settings pages the rest of the app reads as one
 * with" — so this is that fix applied to the last list that had not had it.
 */
@Composable
private fun DirectoryList(
    directory: File,
    text: Strings,
    onOpen: (File) -> Unit,
) {
    // Null until the directory has been read. `listFiles()` runs on IO and a
    // listing has no rows to show in the meantime, so an empty list here would
    // draw the *empty folder* state for a frame or two on every step into a
    // directory — a claim about the folder, made before anything looked at it.
    val entries by produceState<List<File>?>(initialValue = null, directory) {
        value = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .orEmpty()
        }
    }

    val rows = entries
    if (rows == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PiLoading() }
        return
    }

    if (rows.isEmpty()) {
        // An empty directory and an unreadable one draw the same thing here, which
        // is why the sentence says only that there is nothing to show: the list is
        // `listFiles()`'s answer either way, and a folder the user cannot read is
        // usually one they did not mean to open. Before this the page was blank —
        // no list, no message, nothing to say whether the folder was empty or the
        // app was broken.
        PiEmptyState(title = text.files.empty, body = text.files.emptyBody)
        return
    }

    // The listing is one surface, the way every other set of peer rows in the app
    // is: a directory's entries share a frame, so the list has a top and a bottom
    // rather than trailing off into the page. It is a `Surface` around the
    // `LazyColumn` rather than a `PiGroup` because a group holds a plain `Column`
    // — the frame has to be outside the scrolling container when the rows are
    // lazy, and a directory of a few thousand entries is exactly why they are.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = PiShapes.card,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(rows, key = { _, file -> file.absolutePath }) { index, file ->
                FileRow(file = file, text = text, onOpen = { onOpen(file) })
                // Between two rows only: a hairline after the last one would be
                // drawn along the group's own bottom edge, which is the rule every
                // other list here follows for the same reason.
                if (index < rows.lastIndex) {
                    PiRowDivider()
                }
            }
        }
    }
}

/**
 * One entry: its name, what kind of thing it is, and — for a file — how big.
 *
 * Three facts rather than one, because a browser's row is a *table* line: the
 * name is what the reader is looking for, the kind is what tells two names that
 * start the same way apart, and the size is the one number a file has. A
 * directory gets no trailing number: `length()` on one is meaningless, and a
 * directory's size would be either a lie or a directory walk.
 */
@Composable
private fun FileRow(
    file: File,
    text: Strings,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 12dp of page inset, then the row's own padding, so the press
            // outline is a rounded rectangle inside the page — the row radius
            // every other row in the app is clipped to, where this list used to
            // be a full-bleed band with square corners.
            .padding(horizontal = 12.dp)
            .clip(PiShapes.row)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        // 16dp, the gap every other `PiRow` in the app uses: it is what puts this
        // row's text at the 64dp a `PiRowDivider` is inset to, so the hairlines
        // under a listing start in the same place as the ones under a settings group.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = if (file.isDirectory) {
                Icons.Filled.Folder
            } else {
                Icons.Filled.Description
            },
            contentDescription = null,
            // A directory is the page's navigation rather than content, so it
            // carries the secondary role and the files stay neutral: a listing of
            // one kind of glyph in one colour is a listing where the reader's eye
            // has nothing to catch on.
            tint = if (file.isDirectory) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                // Two lines: a file name is how the row is told apart from
                // its neighbour, and a one-line cap ellipsised the suffix that
                // does the telling (`report-2024` vs `report-2025`).
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kindOf(file, text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!file.isDirectory) {
            Text(
                humanSize(file.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What kind of thing a row is, in one word or one extension.
 *
 * A directory says so; a file with an extension is named by it in capitals, which
 * is the kind column of every file browser there has ever been; and a file with
 * no extension says `File` rather than leaving the line empty, because an empty
 * second line reads as a row that failed to load.
 */
private fun kindOf(file: File, text: Strings): String = when {
    file.isDirectory -> text.files.folder
    file.extension.isNotBlank() -> file.extension.uppercase(Locale.ROOT)
    else -> text.files.file
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
 * it. It is the one sheet in the app that is not a menu of choices — a preview is
 * one file, so its body is one block of monospace text and the only two things to
 * do with it are reading it and handing it on. That is why its one row comes
 * *after* the text: the sheet is read first and acted on second, and the action
 * row under it holds the way out.
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
        PiSheetTitle(
            title = file.name,
            subtitle = "${kindOf(file, text)} · ${humanSize(file.length())}",
        )

        // The body takes the height it needs and no more (`fill = false`), which
        // is what makes the text scroll inside the sheet instead of pushing the
        // row and the action under it off the bottom of the panel: the weight
        // caps the list at the space left over after the title and the action
        // row, and the body's own ceiling is the 520dp below.
        Box(Modifier.weight(1f, fill = false)) {
            PiSheetList {
                item {
                    val current = body
                    if (current == null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            PiLoading()
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
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
                }
                item {
                    PiSheetRow(
                        label = text.files.openWith,
                        leading = {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        },
                        // Gated on the sheet still being open for the same reason
                        // every control in a sheet is: the panel keeps drawing
                        // while it leaves, and a second tap must not hand the file
                        // to another app after the reader has dismissed the
                        // preview.
                        onClick = { if (host.isOpen) openFailed = !openExternally(context, file) },
                    )
                }
            }
        }

        if (openFailed) {
            PiNotice(
                text = text.files.openFailed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        PiSheetActions {
            PiButton(
                text = text.files.close,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
                onClick = host::dismiss,
            )
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
