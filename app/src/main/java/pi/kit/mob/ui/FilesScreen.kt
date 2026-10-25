/*
 * The Files tab: a file manager over the agent's home directory.
 *
 * ## What this page is, and what it deliberately is not
 *
 * It is **read-only** — writes go through the agent or the terminal, because the
 * question this page exists to answer is "what did the agent actually change?", and
 * a half file manager with no undo is a worse tool for that than a browser with a
 * good one. Read-only is a statement about the *verbs*, not about the layout.
 *
 * The first version was built out of the settings pages' vocabulary — a card holding
 * a list of peer rows — and that is the wrong shape for a directory. Settings is a
 * set of *controls*: each row is a decision with a value beside it. A listing is a
 * *table of objects*: each row is a name with a size and a date, and the page has to
 * say where you are and let you move. So:
 *
 *  - **A location bar** under the header: the path as a trail of tappable segments.
 *    The header's subtitle is not the path any more — a path is not a subtitle, it is
 *    navigation, and a path you can tap is worth more than a path you can only read.
 *  - **A toolbar**: a filter field and a sort control, directly above the rows they
 *    act on, in the shape a page's own tools take rather than a settings group.
 *  - **A table**: a glyph tile, the name, the kind and the date under it, and one
 *    right-aligned column holding the number the row is about — bytes for a file, how
 *    many entries for a folder. No card around the list and no hairline under every
 *    row: a directory of two thousand entries is scanned, not read, and the frame
 *    that makes eight settings rows one object makes a listing look like a panel.
 *  - **A hold on a row** for what can be done to that one file — copy its path, open
 *    it in another app — rather than a trailing button on every row, which is the
 *    column the size wants.
 *
 * ## Why entering a folder is a page swap
 *
 * It is the one move in the app that replaces the whole screen with another one: the
 * path changes, the rows are new, and the way back is the same action — so it moves
 * the way a second level moves everywhere else (§9.1). Only the *listing* travels,
 * not the bar: the header's title is the folder's own name and updates in place as the
 * listing slides, which is what the platform's document UI does too.
 *
 * ## Back walks up the filesystem
 *
 * `PageBackHandler` and not `BackHandler` — a preview is a sheet over this page and
 * the gesture belongs to it first. At the top of the tree there is no parent, so back
 * from there leaves the tab as it always did.
 */
package pi.kit.mob.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.LocalToastHost
import pi.kit.mob.ui.components.MenuAnchor
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.PiMenuRow
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.components.menuAnchor
import pi.kit.mob.ui.components.rememberMenuAnchor
import pi.kit.mob.ui.components.showMenu
import pi.kit.mob.ui.design.PiEmptyState
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPageSwap
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSearchField
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.files.Crumb
import pi.kit.mob.ui.files.FileEntry
import pi.kit.mob.ui.files.FilePreviewSheet
import pi.kit.mob.ui.files.FileSort
import pi.kit.mob.ui.files.arrange
import pi.kit.mob.ui.files.crumbs
import pi.kit.mob.ui.files.depthUnder
import pi.kit.mob.ui.files.fileKind
import pi.kit.mob.ui.files.humanSize
import pi.kit.mob.ui.files.openExternally
import pi.kit.mob.ui.files.readDirectory
import pi.kit.mob.ui.files.stampFor
import java.io.File
import java.util.Locale

/**
 * The tab.
 *
 * State is four values: where the reader is, which way the last move went, what the
 * filter says, and how the list is ordered. Everything else — the entries, the sizes,
 * the dates — is read from the directory [current] names.
 */
@Composable
fun FilesScreen(session: PiAgentSession) {
    val text = strings
    val home = remember { session.env.home }
    val sheets = LocalSheetHost.current
    val toasts = LocalToastHost.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(home) }
    // Which way the last move went. A child is deeper than its parent by definition,
    // so comparing two depths is the whole rule; going home from three levels down is
    // one long slide backwards, which is what it is.
    var forward by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FileSort.Name) }
    // How many entries the folder on screen holds, reported up by the listing. Held
    // here because the header's subtitle is the page's own furniture and the listing
    // is a child of the swap — see [Listing] for why the read cannot happen up here.
    var itemCount by remember { mutableStateOf<Int?>(null) }

    fun navigate(to: File) {
        forward = to.depthUnder(home) > current.depthUnder(home)
        current = to
        // A filter is a statement about the folder it was typed in. Carrying it into
        // the next folder opens a listing that is silently hiding rows.
        query = ""
        // And the old folder's count is not the new folder's: the header says nothing
        // until the new listing has been read, rather than the previous number sitting
        // under the new title for the length of the slide.
        itemCount = null
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            navigate(entry.file)
        } else {
            sheets.show(
                Sheet(key = "files-preview:${entry.file.absolutePath}") {
                    FilePreviewSheet(entry)
                },
            )
        }
    }

    fun copyPath(entry: FileEntry) {
        scope.launch {
            clipboard.setClipEntry(clipEntryFor(entry.file.absolutePath))
            toasts.show(text.files.pathCopied)
        }
    }

    PageBackHandler(enabled = current.parentFile != null) {
        current.parentFile?.let(::navigate)
    }

    PiScaffold(
        // The folder's own name, not the tab's: on a file manager the title is the
        // place you are standing in, and "Files" over a folder called `workspace` is
        // the one fact the page already knows better.
        title = if (current == home) text.header.files else current.name,
        subtitle = itemCount?.let { text.files.itemCount(it) },
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
        Column(body) {
            LocationBar(
                trail = crumbs(home, current),
                onOpen = ::navigate,
            )
            ListToolbar(
                query = query,
                onQueryChange = { query = it },
                sort = sort,
                onSortChange = { sort = it },
                text = text,
            )

            // Only the listing travels; the two strips above it are the page's own
            // furniture and stay put (§9.1). The count is reported only by the page
            // being shown — the outgoing one is still composed for the length of the
            // slide, and its count would arrive last.
            PiPageSwap(
                key = current,
                forward = forward,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                val directory = page as File
                Listing(
                    directory = directory,
                    sort = sort,
                    query = query,
                    text = text,
                    onLoaded = if (directory == current) {
                        { itemCount = it }
                    } else {
                        null
                    },
                    onOpen = ::open,
                    onCopyPath = ::copyPath,
                )
            }
        }
    }
}

/**
 * One directory's body: the loading state, the two kinds of nothing, or the table.
 *
 * A composable of its own, keyed on the directory, because `PiPageSwap` draws two of
 * these at once for the length of the slide: the listing has to be a function of the
 * directory it belongs to, or the outgoing page would show the incoming page's files
 * on the way out. That is also why the read lives *here* rather than in the screen —
 * a single read keyed on the screen's state would be replaced the instant the move
 * started, and both pages would then draw the new folder.
 *
 * The two nothings are said differently — a folder with nothing in it, and a filter
 * that matched nothing in a folder that has something — because they call for
 * different actions: one is a place, the other is a query the reader can clear. The
 * first version drew one sentence for both, and drew *nothing at all* for a folder it
 * could not read.
 */
@Composable
private fun Listing(
    directory: File,
    sort: FileSort,
    query: String,
    text: Strings,
    onLoaded: ((Int) -> Unit)?,
    onOpen: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
) {
    // Null until the directory has been read: an empty list here would draw the
    // *empty folder* state for a frame on every step into a directory — a claim about
    // the folder, made before anything looked at it.
    val entries by produceState<List<FileEntry>?>(initialValue = null, directory) {
        value = withContext(Dispatchers.IO) { readDirectory(directory) }
    }
    val rows = entries

    if (onLoaded != null) {
        LaunchedEffect(rows) { rows?.let { onLoaded(it.size) } }
    }

    if (rows == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PiLoading() }
        return
    }
    val visible = remember(rows, sort, query) { arrange(rows, sort, query) }

    when {
        rows.isEmpty() -> PiEmptyState(title = text.files.empty, body = text.files.emptyBody)

        visible.isEmpty() -> PiEmptyState(
            title = text.files.noMatches(query.trim()),
            body = text.files.noMatchesBody,
        )

        else -> DirectoryTable(
            rows = visible,
            text = text,
            onOpen = onOpen,
            onCopyPath = onCopyPath,
        )
    }
}

/**
 * The path, as a trail of segments.
 *
 * Horizontally scrollable because a deep path is wider than a phone, and scrolled to
 * its end whenever the trail changes — the last segment is where the reader is, and a
 * bar that opened showing `~ / storage` while the page showed something eight levels
 * down would be a bar about a different folder.
 */
@Composable
private fun LocationBar(
    trail: List<Crumb>,
    onOpen: (File) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(trail) {
        if (trail.isNotEmpty()) state.animateScrollToItem(trail.lastIndex)
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(trail, key = { _, crumb -> crumb.target.absolutePath }) { index, crumb ->
            val last = index == trail.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = crumb.label,
                    style = MaterialTheme.typography.labelLarge,
                    // The segment being shown is the page's subject; its ancestors
                    // are the way back. One weight apart is the whole difference.
                    color = if (last) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .clip(PiShapes.small)
                        .then(if (last) Modifier else Modifier.clickable { onOpen(crumb.target) })
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The page's tools, directly above the rows they act on.
 *
 * A filter field and a sort button, and nothing else: a file manager's toolbar is
 * about *this listing*, and every control in it has to be answerable by one of the
 * rows below. The sort control is a floating menu at its own button — the same control
 * the terminal's session switcher uses (§7.1) — because three orders are a question
 * with three answers, not a page.
 */
@Composable
private fun ListToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    text: Strings,
) {
    val sheets = LocalSheetHost.current
    val anchor = rememberMenuAnchor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PiSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = text.files.filterHint,
            clearContentDescription = text.common.clear,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                sheets.showMenu(
                    key = "files-sort",
                    anchor = anchor,
                    alignEnd = true,
                ) {
                    SortMenu(sort = sort, text = text, onSortChange = onSortChange)
                }
            },
            modifier = Modifier.menuAnchor(anchor),
        ) {
            Icon(Icons.Filled.Sort, contentDescription = text.files.sort)
        }
    }
}

/** The three orders, as a menu at the sort button. */
@Composable
private fun SortMenu(
    sort: FileSort,
    text: Strings,
    onSortChange: (FileSort) -> Unit,
) {
    val host = LocalSheetHost.current
    // Dismissed first, then applied: the layer keeps drawing for the length of its
    // exit, and a row that ran after the menu was gone would be a tap on the page
    // behind it (`SessionsScreen`'s `sheetAction` is the same gate).
    fun choose(order: FileSort): () -> Unit = {
        if (host.isOpen) {
            host.dismiss()
            onSortChange(order)
        }
    }

    PiMenuRow(
        label = text.files.sortName,
        selected = sort == FileSort.Name,
        onClick = choose(FileSort.Name),
    )
    PiMenuRow(
        label = text.files.sortModified,
        selected = sort == FileSort.Modified,
        onClick = choose(FileSort.Modified),
    )
    PiMenuRow(
        label = text.files.sortSize,
        selected = sort == FileSort.Size,
        onClick = choose(FileSort.Size),
    )
}

/**
 * The listing itself: a table of rows, with no frame around it.
 *
 * `wrapContentHeight` is not applied here and does not need to be: the rows are
 * lazy, so the list reports the height of what it laid out when the content fits and
 * fills only when there is something to scroll to. The page's body is a `Column` whose
 * last child takes the rest of the window, which is what a table wants — a column of
 * rows that ends where the page ends, rather than a card that ends where the last row
 * does.
 */
@Composable
private fun DirectoryTable(
    rows: List<FileEntry>,
    text: Strings,
    onOpen: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
) {
    val sheets = LocalSheetHost.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The navigation bar's height as content padding rather than as a band of
        // empty page: the last row scrolls past it, and at rest it is the gap that
        // keeps the last row off the bar (`PiPageBottom`).
        contentPadding = PaddingValues(bottom = PiPageBottom),
    ) {
        itemsIndexed(rows, key = { _, entry -> entry.file.absolutePath }) { _, entry ->
            FileRow(
                entry = entry,
                text = text,
                onOpen = { onOpen(entry) },
                onActions = { anchor ->
                    sheets.showMenu(
                        key = "file-actions:${entry.file.absolutePath}",
                        anchor = anchor,
                        alignEnd = true,
                    ) {
                        FileActionsMenu(
                            entry = entry,
                            text = text,
                            onCopyPath = { onCopyPath(entry) },
                        )
                    }
                },
            )
        }
    }
}

/**
 * One row of the table: a glyph tile, the name, the kind and date under it, and the
 * size at the trailing edge.
 *
 * The trailing column is the *number* the row is about — bytes for a file, how many
 * entries for a folder — and it is monospace so a column of them has one right edge.
 * It is deliberately not a `PiRow`: a settings row names a control and puts a value or
 * a chevron at its end, and this one is a line of a table.
 *
 * A hold opens the row's actions; the tap is the row's own — a folder opens, a file
 * previews. Nothing in the row is a second tap target, which is why there is no
 * trailing button: the size column is what a file manager puts there.
 */
@Composable
private fun FileRow(
    entry: FileEntry,
    text: Strings,
    onOpen: () -> Unit,
    onActions: (MenuAnchor) -> Unit,
) {
    val anchor = rememberMenuAnchor()
    val tone = toneOf(entry)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            // Clipped to the row's radius before the ripple, so the press outline is
            // a rounded rectangle inside the page rather than a full-bleed band.
            .clip(PiShapes.row)
            .menuAnchor(anchor)
            .combinedClickable(
                onLongClickLabel = text.files.actions,
                onLongClick = { onActions(anchor) },
                onClick = onOpen,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A tile rather than a bare glyph: at 40dp in a tinted container the kind of
        // a row is readable before its name is, which is the whole reason a file
        // manager spends a column on it.
        Surface(
            color = tone.container(MaterialTheme.colorScheme),
            shape = PiShapes.small,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = glyphOf(entry),
                    contentDescription = null,
                    tint = tone.onContainer(MaterialTheme.colorScheme),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                // Two lines: a file name is how a row is told apart from its
                // neighbour, and a one-line cap ellipsised the suffix that does the
                // telling (`report-2024` vs `report-2025`).
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaOf(entry, text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // The size column. A folder's number is its children, a file's is its bytes,
        // and a link gets neither: `length()` on a link is the target's path length on
        // some filesystems and zero on others, which is a number that means nothing
        // either way.
        val size = when {
            entry.isLink -> null
            entry.isDirectory -> entry.children?.let { text.files.items(it) }
            else -> humanSize(entry.size)
        }
        Text(
            text = size.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // End-aligned inside a fixed column: measured on the emulator, a
            // left-aligned one put `1 项` and `0 项` at the column's start, so the
            // numbers were ragged on the right and the column read as text with a gap
            // after it rather than as a column.
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(SIZE_COLUMN)
                .semantics { if (size != null) contentDescription = size },
        )
    }
}

/**
 * The second line: what kind of thing this is, and when it changed.
 *
 * Two facts in one line rather than two columns, because the size column is the one
 * fact worth aligning and a phone's width is not a desktop's: kind and date are read
 * once, in passing, where the size is compared row to row.
 */
private fun metaOf(entry: FileEntry, text: Strings): String {
    val kind = fileKind(
        file = entry.file,
        isDirectory = entry.isDirectory,
        isLink = entry.isLink,
        folder = text.files.folder,
        link = text.files.link,
        plain = text.files.file,
    )
    val stamp = stampFor(entry.modifiedAt, System.currentTimeMillis())
    return if (stamp.isEmpty()) kind else "$kind · $stamp"
}

/**
 * What can be done to one row, without opening it.
 *
 * A floating menu at the row that was held, on the layer's anchored half — two short
 * actions on one file do not cover the list to ask their question (§7.1). A file
 * additionally offers **Open with**, which the preview sheet also carries: the reader
 * who has found the file in the listing should not have to open a preview to hand it
 * to another app.
 */
@Composable
private fun FileActionsMenu(
    entry: FileEntry,
    text: Strings,
    onCopyPath: () -> Unit,
) {
    val host = LocalSheetHost.current
    val toasts = LocalToastHost.current
    val context = LocalContext.current

    fun run(action: () -> Unit): () -> Unit = {
        if (host.isOpen) {
            host.dismiss()
            action()
        }
    }

    PiMenuRow(
        label = text.files.copyPath,
        leading = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
        onClick = run(onCopyPath),
    )
    if (!entry.isDirectory) {
        PiMenuRow(
            label = text.files.openWith,
            leading = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = run {
                if (!openExternally(context, entry.file)) toasts.show(text.files.openFailed)
            },
        )
    }
}

/**
 * A row's glyph, by what the file is rather than only whether it is a directory.
 *
 * A listing where every file wears the same sheet-of-paper mark asks the reader to
 * read the *name* to tell anything apart; seven kinds — code, image, archive, video,
 * audio, sheet, document — cover what a phone user steps into this tab to find, and
 * everything else keeps the paper. The extension's first letter is not a glyph: a row
 * of one-letter marks reads as a bespoke alphabet nobody agreed on.
 */
private fun glyphOf(entry: FileEntry): ImageVector {
    if (entry.isLink) return Icons.Filled.Link
    if (entry.isDirectory) return Icons.Filled.Folder
    return when (entry.file.extension.lowercase(Locale.ROOT)) {
        in CODE_SUFFIXES -> Icons.Filled.Code
        in IMAGE_SUFFIXES -> Icons.Filled.Image
        in ARCHIVE_SUFFIXES -> Icons.Filled.Archive
        in VIDEO_SUFFIXES -> Icons.Filled.VideoFile
        in AUDIO_SUFFIXES -> Icons.Filled.AudioFile
        in SHEET_SUFFIXES -> Icons.Filled.TableChart
        in DOCUMENT_SUFFIXES -> Icons.Filled.PictureAsPdf
        else -> Icons.Filled.Description
    }
}

/**
 * The tile's colour, by kind.
 *
 * A folder is the page's navigation and takes the accent's container; a picture or a
 * video takes the live tone so a long listing can be scanned by colour before it is
 * read. Everything else stays neutral — six tones on one page is a palette, and this
 * is a list.
 */
private fun toneOf(entry: FileEntry): PiTone = when {
    entry.isDirectory -> PiTone.Accent
    entry.file.extension.lowercase(Locale.ROOT) in IMAGE_SUFFIXES ||
        entry.file.extension.lowercase(Locale.ROOT) in VIDEO_SUFFIXES -> PiTone.Live
    else -> PiTone.Neutral
}

/** Written by a programmer, or by a tool a programmer runs, or about one. */
private val CODE_SUFFIXES = setOf(
    "kt", "kts", "java", "py", "js", "ts", "mjs", "cjs", "sh", "json", "yaml", "yml",
    "toml", "xml", "html", "css", "md", "txt", "log", "ini", "conf", "gradle",
)

/** Drawn by the preview sheet, and openable by any phone. */
private val IMAGE_SUFFIXES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "svg")

/** One thing that unpacks into several. */
private val ARCHIVE_SUFFIXES = setOf("zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst")

private val VIDEO_SUFFIXES = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")

private val AUDIO_SUFFIXES = setOf("mp3", "m4a", "wav", "ogg", "flac", "opus", "aac")

private val SHEET_SUFFIXES = setOf("csv", "tsv", "xls", "xlsx", "ods")

private val DOCUMENT_SUFFIXES = setOf("pdf", "doc", "docx", "odt", "ppt", "pptx", "epub")

/**
 * The width of the size column.
 *
 * Wide enough for `999.9 MB` in the app's monospace at the default text size, and
 * fixed rather than wrap-to-content so every row's number ends on the same x — which
 * is the only thing that makes a column of sizes scannable.
 */
private val SIZE_COLUMN = 76.dp
