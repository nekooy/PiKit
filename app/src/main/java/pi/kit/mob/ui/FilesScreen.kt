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
 * The page's shape, top to bottom, is the one a listing needs and a settings page
 * does not:
 *
 *  - **A location bar** under the header: the path as Miuix's own breadcrumb trail —
 *    capsules that scroll sideways instead of collapsing. A path is navigation, not
 *    a subtitle, and a path you can tap is worth more than one you can only read.
 *  - **A toolbar**: a filter field and a sort menu, directly above the rows they act
 *    on, in the shape a page's own tools take rather than a settings group.
 *  - **A listing**: one card per entry — a glyph tile, the name, the kind and date
 *    under it, and one right-aligned column holding the number the row is about
 *    (bytes for a file, how many entries for a folder).
 *  - **A hold on a row** for what can be done to that one file — copy its path, hand
 *    it to another app — rather than a trailing button on every row, which is the
 *    column the size wants.
 *
 * ## Why a row is a card
 *
 * The listing used to be a bare table with no frame at all, and the reason was that
 * a card built for eight settings rows makes a directory of two thousand entries
 * look like a panel. Miuix changes what the frame is *for*: `BasicComponent`, the
 * library's settings row, carries a title, a summary and one click, and this page
 * needs a click **and** a hold — the hold is the only way to reach one file's
 * actions without opening it. `Card` is the container in this library that takes
 * both, and taking both is what lets the row be a Miuix component rather than a
 * hand-rolled `Row` that only looks like one.
 *
 * So the frame is per row and thin: a 12dp page inset and a 2dp seam. The seam is
 * also what keeps a long listing scannable — each row is one object with one
 * outline, which is the reading the frame is meant to give at this density.
 *
 * ## Why entering a folder is a page swap
 *
 * It is the one move in the app that replaces the whole screen with another one: the
 * path changes, the rows are new, and the way back is the same action — so it moves
 * the way a second level moves everywhere else (§9.1). Only the *listing* travels,
 * not the two strips above it: the header's title is the folder's own name and
 * updates in place as the listing slides, which is what the platform's document UI
 * does too.
 *
 * ## Back walks up the filesystem, to its root
 *
 * The tab is a root, so it draws no back arrow in its bar (`PiPage`'s arrow belongs
 * to a pushed page) — the location bar and the up button are its visible navigation,
 * and the back gesture follows them.
 *
 * The walk ends at the filesystem's root, not at `$HOME`. It used to stop at `$HOME`:
 * the home directory's parent is the app's private data directory, and the argument for
 * refusing that step was that this page is a view of the agent's home rather than of the
 * whole filesystem. The argument does not survive the listing it produces — `$HOME`
 * holds `~/.pi` and the agent's workspace, and a reader who has just been shown
 * `~/.pi` cannot be told that the directory containing it is not their business. The
 * gate is therefore `parentFile != null`: the walk goes up until there is nothing
 * above it, and the Home button and the `~` crumb are the way back to the page's own
 * root, which is where a page that wants to be rooted can be rooted without lying
 * about the tree.
 *
 * [`crumbs`] has the matching rule for a path that is not under `$HOME`: it draws the
 * absolute trail from `/`, because one unscrollable crumb per directory above the root
 * would leave the location bar with no way back down.
 */
package pi.kit.mob.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.files.Crumb
import pi.kit.mob.ui.files.FileEntry
import pi.kit.mob.ui.files.FilePreviewSheet
import pi.kit.mob.ui.files.FileSort
import pi.kit.mob.ui.files.IMAGE_SUFFIXES
import pi.kit.mob.ui.files.arrange
import pi.kit.mob.ui.files.crumbs
import pi.kit.mob.ui.files.pathDepth
import pi.kit.mob.ui.files.fileKind
import pi.kit.mob.ui.files.humanSize
import pi.kit.mob.ui.files.openExternally
import pi.kit.mob.ui.files.readDirectory
import pi.kit.mob.ui.files.stampFor
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiTone
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.io.File
import java.util.Locale
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * The tab.
 *
 * State is four values: where the reader is, which way the last move went, what the
 * filter says, and how the list is ordered. Everything else — the entries, the sizes,
 * the dates — is read from the directory [current] names. The preview is a fifth:
 * Miuix's sheet is a window of its own, so the page that opens it owns the state
 * instead of pushing a body into a layer above the whole app.
 */
@Composable
fun FilesScreen(session: PiAgentSession) {
    val text = strings
    val home = remember { session.env.home }
    val toasts = LocalPiToast.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
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
    // The file whose preview is up. Two values rather than one nullable entry: the
    // sheet keeps drawing for the length of its exit, and a null entry would empty
    // the body while the panel slides away.
    var preview by remember { mutableStateOf<FileEntry?>(null) }
    var previewUp by remember { mutableStateOf(false) }

    fun navigate(to: File) {
        // The slide's direction is the sign of the depth difference, counted from the
        // filesystem's root rather than from `$HOME`: a move above the home directory
        // is still a move, and counting from `$HOME` made every one of them read as a
        // move down.
        forward = to.pathDepth() > current.pathDepth()
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
            preview = entry
            previewUp = true
        }
    }

    fun copyPath(entry: FileEntry) {
        scope.launch {
            clipboard.setClipEntry(clipEntryFor(entry.file.absolutePath))
            toasts?.show(text.files.pathCopied)
        }
    }

    fun openWith(entry: FileEntry) {
        // `openExternally` returns the failure instead of throwing it, so a file the
        // provider cannot reach says so rather than the button doing nothing.
        if (!openExternally(context, entry.file)) toasts?.show(text.files.openFailed)
    }

    // The tab root draws no bar arrow; the gesture follows the up button instead. Both
    // walk until there is nowhere left to go — the filesystem's root — rather than
    // stopping at `$HOME`. The page's own root is still one tap away, on the Home
    // button and the `~` segment of the location bar, so the reader can leave and come
    // back; refusing the step *up* only meant a directory above the home directory was
    // unreachable from a listing that had just shown its contents.
    BackHandler(enabled = current.parentFile != null) {
        current.parentFile?.let(::navigate)
    }

    PiPage(
        // The four tab roots left-align their titles and subtitles. The bar's right
        // side is where three or four action icons live, and the library centres a
        // title between the navigation icon and those actions — so a root with four
        // actions has its title pushed off centre by their width. See `PiPage`'s
        // *Why a root can be left-aligned*.
        alignStart = true,
        // The folder's own name, not the tab's: on a file manager the title is the
        // place you are standing in, and "Files" over a folder called `workspace` is
        // the one fact the page already knows better.
        title = if (current == home) text.header.files else current.name,
        // `orEmpty()` rather than letting the null through: the count is unknown while
        // a folder is being listed, and a subtitle line that appeared and disappeared
        // once per folder moved the whole page by its height twice. A blank subtitle
        // reserves the line and says nothing in it — see `PiPage`'s *Null and blank are
        // two different answers*.
        subtitle = itemCount?.let { text.files.itemCount(it) }.orEmpty(),
        actions = {
            IconButton(
                onClick = { navigate(home) },
                enabled = current != home,
            ) {
                Icon(MiuixIcons.Home, contentDescription = text.files.home)
            }
            IconButton(
                // Up one level, until the filesystem's root — see the header. `$HOME`'s
                // parent is the app's private data directory, and walking into it is
                // the point: the runtime, the session files and the terminal's own
                // home all live there, and a listing that showed `~/.pi` while
                // refusing to show the directory above it was hiding its own subject.
                onClick = { current.parentFile?.let(::navigate) },
                enabled = current.parentFile != null,
            ) {
                // The arrow of Miuix's own basic set, turned through a right angle at
                // the call site: "up one level" is a direction this library never
                // names, and borrowing the back chevron for it would draw the
                // platform's *back* on a page whose back is the up.
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = text.files.parent,
                    modifier = Modifier.rotate(-90f),
                )
            }
        },
    ) { body ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(body),
        ) {
            // The toolbar is **above** the location bar, and that order is the point.
            //
            // A file manager's own layout puts the path first, on the argument that the
            // path is the page's subject. It is — but it is also the thing the reader
            // already knows, because they tapped a folder to get here and the header
            // above says its name. The filter is the control they came to *use*, and it
            // was sitting under the breadcrumb, under the page's title, under its
            // subtitle and under the header bar itself: measured on the emulator, its
            // top edge landed 190dp into the content rectangle, which is a control a
            // thumb has to reach for rather than a toolbar at the top of the page.
            //
            // The breadcrumb loses nothing by moving down: it stays directly above the
            // rows it describes, which is where a path is most useful — "the folder
            // whose contents you are looking at".
            ListToolbar(
                query = query,
                onQueryChange = { query = it },
                sort = sort,
                onSortChange = { sort = it },
                text = text,
            )
            LocationBar(
                trail = crumbs(home, current),
                onOpen = ::navigate,
            )

            // Only the listing travels; the two strips above it are the page's own
            // furniture and stay put (§9.1). The count is reported only by the page
            // being shown — the outgoing one is still composed for the length of the
            // slide, and its count would arrive last.
            AnimatedContent(
                targetState = current,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    // A third of the width, not the whole of it: the page's furniture
                    // above the listing does not move, so the incoming rows slide under
                    // it rather than pushing a whole screen across.
                    val step = if (forward) 1 else -1
                    (slideInHorizontally { width -> step * width / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -step * width / 3 } + fadeOut())
                },
                label = "folder",
            ) { directory ->
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
                    onOpenWith = ::openWith,
                )
            }
        }
    }

    // One sheet, opened from this page's own state. `title = null` and the body's own
    // heading instead: the subject of a preview is the file's name, and a sheet title
    // above it would say the same thing twice.
    PiSheet(
        show = previewUp,
        title = null,
        onDismiss = { previewUp = false },
    ) {
        preview?.let { FilePreviewSheet(entry = it) }
    }
}

/**
 * One directory's body: the loading state, the two kinds of nothing, or the rows.
 *
 * A composable of its own, keyed on the directory, because the swap above draws two of
 * these at once for the length of the slide: the listing has to be a function of the
 * directory it belongs to, or the outgoing page would show the incoming page's files
 * on the way out. That is also why the read lives *here* rather than in the screen —
 * a single read keyed on the screen's state would be replaced the instant the move
 * started, and both pages would then draw the new folder.
 *
 * The two nothings are said differently — a folder with nothing in it, and a filter
 * that matched nothing in a folder that has something — because they call for
 * different actions: one is a place, the other is a query the reader can clear.
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
    onOpenWith: (FileEntry) -> Unit,
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val visible = remember(rows, sort, query) { arrange(rows, sort, query) }

    when {
        rows.isEmpty() -> FilesNote(title = text.files.empty, body = text.files.emptyBody)

        visible.isEmpty() -> FilesNote(
            title = text.files.noMatches(query.trim()),
            body = text.files.noMatchesBody,
        )

        else -> DirectoryTable(
            rows = visible,
            text = text,
            onOpen = onOpen,
            onCopyPath = onCopyPath,
            onOpenWith = onOpenWith,
        )
    }
}

/**
 * The path, as Miuix's breadcrumb trail.
 *
 * The component is the library's own capsule strip: it scrolls sideways when a deep
 * path is wider than a phone instead of collapsing segments, and it scrolls to the
 * highlighted one whenever the trail changes — the last segment is where the reader
 * is, and a bar that opened showing `~ / storage` while the page showed something
 * eight levels down would be a bar about a different folder.
 *
 * [BreadcrumbBar] caps a segment at 160dp by default, which at the interface's body
 * size is about ten CJK characters: a folder named longer than that was ellipsised in
 * the bar *and* in the header, so its name was readable in full nowhere on the page.
 * The cap is lifted here and the strip is allowed to be as wide as the path is — the
 * bar already scrolls, so a long name costs a swipe rather than the name. This is the
 * behaviour the pre-Miuix location bar had, where every segment was a label in a
 * scrolling row.
 */
@Composable
private fun LocationBar(
    trail: List<Crumb>,
    onOpen: (File) -> Unit,
) {
    BreadcrumbBar(
        items = trail.map { crumb ->
            BreadcrumbItem(path = crumb.target.absolutePath, text = crumb.label)
        },
        onItemClick = { index ->
            // The segment being shown is the page's subject, not a way out of it; its
            // ancestors are the way back.
            if (index != trail.lastIndex) trail.getOrNull(index)?.let { onOpen(it.target) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        itemMaxWidth = Dp.Infinity,
    )
}

/**
 * The page's tools, directly above the rows they act on.
 *
 * A filter field and a sort button, and nothing else: a file manager's toolbar is
 * about *this listing*, and every control in it has to be answerable by one of the
 * rows below.
 *
 * The field is a plain `TextField` rather than the library's `SearchBar`, and the
 * reason is what the two are shaped for: `SearchBar` expands into a results surface
 * of its own, which would cover the very list this field narrows in place. What is
 * wanted here is the capsule's look and its clear button, both of which the field
 * carries on its own.
 *
 * The sort control is a floating menu at its own button — the library's
 * `OverlayIconDropdownMenu` (§7.1) — because three orders are a question with three
 * answers, not a page.
 */
@Composable
private fun ListToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    text: Strings,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            cornerRadius = PiCorner.radius,
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            label = text.files.filterHint,
            useLabelAsPlaceholder = true,
            singleLine = true,
            // No focus ring: see `piFieldColors`. The field already reads as focused
            // because its label has floated and its caret is in it.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            leadingIcon = {
                Icon(
                    imageVector = MiuixIcons.Search,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            },
            trailingIcon = {
                // Drawn only when there is something to clear: an always-present cross
                // is a target that does nothing on an empty field.
                if (query.isNotEmpty()) {
                    ClearFieldButton(
                        onClick = { onQueryChange("") },
                        contentDescription = text.common.clear,
                    )
                }
            },
        )
        OverlayIconDropdownMenu(
            entry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = text.files.sortName,
                        selected = sort == FileSort.Name,
                        onClick = { onSortChange(FileSort.Name) },
                    ),
                    DropdownItem(
                        text = text.files.sortModified,
                        selected = sort == FileSort.Modified,
                        onClick = { onSortChange(FileSort.Modified) },
                    ),
                    DropdownItem(
                        text = text.files.sortSize,
                        selected = sort == FileSort.Size,
                        onClick = { onSortChange(FileSort.Size) },
                    ),
                ),
            ),
        ) {
            Icon(MiuixIcons.Sort, contentDescription = text.files.sort)
        }
    }
}

/**
 * The listing itself: a column of row cards.
 *
 * The rows are lazy, so the listing reports the height of what it laid out when the
 * content fits and fills when there is something to scroll to. The page's body is a
 * `Column` whose last child takes the rest of the window, which is what a listing
 * wants — a column of rows that ends where the page ends.
 */
@Composable
private fun DirectoryTable(
    rows: List<FileEntry>,
    text: Strings,
    onOpen: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
    onOpenWith: (FileEntry) -> Unit,
) {
    LazyColumn(
        // The elastic effect is the *kit's* overscroll modifier, not the one
        // `LazyColumn` installs for itself.
        //
        // That is the fix for "a folder with three files does not bounce the way one
        // with thirty does", and the cause is in Compose rather than here: `Scrollable`
        // gates its overscroll effect on
        // `canScrollForward || canScrollBackward || effect.isInProgress`, so a list whose
        // content fits the viewport dispatches nothing to the effect and a drag at either
        // end is simply swallowed. `Modifier.overScrollVertical()` is a nested-scroll
        // connection above the list instead: it receives whatever the list could not
        // consume — which for a short list is the whole drag — and translates the content
        // by its damped distance. The two do not double up: while the list *can* scroll,
        // its own effect consumes the remainder, so this node is handed zero.
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        // The gap above the navigation bar as content padding rather than as a band of
        // empty page: the last row scrolls past it, and at rest it is the space that
        // keeps the last row off the bar.
        contentPadding = PaddingValues(bottom = PAGE_BOTTOM),
        // The seam between two row cards, at the kit's row gap — the same number the
        // conversation history states, because the two lists are read one after the
        // other and were spaced by the same too-tight figure. 2dp is tighter than the
        // gap inside a row, so two rows read as one panel with a line through it.
        verticalArrangement = Arrangement.spacedBy(PiCorner.rowGap),
    ) {
        items(rows, key = { entry -> entry.file.absolutePath }) { entry ->
            FileRow(
                entry = entry,
                text = text,
                onOpen = { onOpen(entry) },
                onCopyPath = { onCopyPath(entry) },
                onOpenWith = { onOpenWith(entry) },
            )
        }
    }
}

/**
 * One row of the listing: a glyph tile, the name, the kind and date under it, and the
 * size at the trailing edge.
 *
 * The trailing column is the *number* the row is about — bytes for a file, how many
 * entries for a folder — and it is monospace so a column of them has one right edge.
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
    onCopyPath: () -> Unit,
    onOpenWith: () -> Unit,
) {
    // The menu is the row's own state: it is anchored on this card, it dies with the
    // row, and a row that scrolls out of the list with its menu open takes the menu
    // with it (a screen-level "which row is open" would survive the scroll and reopen
    // on the way back).
    var actionsUp by remember(entry.file) { mutableStateOf(false) }
    val (tileColor, glyphColor) = tileToneOf(entry)

    Box {
        Card(
            cornerRadius = PiCorner.radius,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                // The hold announces itself: `Card` installs the long click but not a
                // label for it, and a screen reader has to be told what the gesture is
                // for — the row's actions, said out loud.
                .semantics {
                    onLongClick(label = text.files.actions) {
                        actionsUp = true
                        true
                    }
                },
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = onOpen,
            onLongPress = { actionsUp = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A tile rather than a bare glyph: at 40dp in a tinted container the
                // kind of a row is readable before its name is, which is the whole
                // reason a file manager spends a column on it. The silhouette is the
                // library's squircle, so the tile is cut from the same shape as the
                // card behind it.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .squircleSurface(color = tileColor, cornerRadius = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = glyphOf(entry),
                        contentDescription = null,
                        tint = glyphColor,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                        // Two lines: a file name is how a row is told apart from its
                        // neighbour, and a one-line cap ellipsised the suffix that does
                        // the telling (`report-2024` vs `report-2025`).
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaOf(entry, text),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // The size column. A folder's number is its children, a file's is its
                // bytes, and a link gets neither: `length()` on a link is the target's
                // path length on some filesystems and zero on others, which is a
                // number that means nothing either way.
                val size = when {
                    entry.isLink -> null
                    entry.isDirectory -> entry.children?.let { text.files.items(it) }
                    else -> humanSize(entry.size)
                }
                Text(
                    text = size.orEmpty(),
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // End-aligned inside a fixed column: measured on the emulator, a
                    // left-aligned one put `1 项` and `0 项` at the column's start, so
                    // the numbers were ragged on the right and the column read as text
                    // with a gap after it rather than as a column.
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        // A *minimum*, not a fixed width: the column is measured for the
                        // default text size and the app's seven-step text-size setting
                        // multiplies `fontScale`, so a fixed box that fits ten characters
                        // at 1.0 ellipsises the tenth at the largest step. `widthIn` lets
                        // the box grow with the type while the row keeps a floor, and the
                        // numbers still share one right edge because the column sits at
                        // the row's end.
                        .widthIn(min = SIZE_COLUMN)
                        .semantics { if (size != null) contentDescription = size },
                )
            }
        }

        // What can be done to this one row, as a floating menu at the row that was
        // held — two short actions on one file do not cover the list to ask their
        // question (§7.1). It hangs off the row's trailing edge, where a row's actions
        // live. The gate inside each item is what keeps a second tap — aimed at the
        // page behind a menu that is already leaving — from copying twice.
        OverlayDropdownPopup(
            entry = DropdownEntry(
                items = buildList {
                    add(
                        DropdownItem(
                            text = text.files.copyPath,
                            icon = { modifier ->
                                Icon(
                                    imageVector = MiuixIcons.Copy,
                                    contentDescription = null,
                                    modifier = modifier,
                                )
                            },
                            onClick = {
                                if (actionsUp) {
                                    actionsUp = false
                                    onCopyPath()
                                }
                            },
                        ),
                    )
                    if (!entry.isDirectory) {
                        // A file additionally offers **Open with**, which the preview
                        // carries too: the reader who has found the file in the listing
                        // should not have to open a preview to hand it to another app.
                        add(
                            DropdownItem(
                                text = text.files.openWith,
                                icon = { modifier ->
                                    Icon(
                                        imageVector = MiuixIcons.Forward,
                                        contentDescription = null,
                                        modifier = modifier,
                                    )
                                },
                                onClick = {
                                    if (actionsUp) {
                                        actionsUp = false
                                        onOpenWith()
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
            show = actionsUp,
            onDismiss = { actionsUp = false },
            onDismissFinished = {},
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
        )
    }
}

/**
 * The clear button a filter field carries, shared by this page and the history page.
 *
 * It is a library `IconButton` and not a `Box` with a `clickable` around an 18dp glyph,
 * which is what both fields had: the glyph was the whole touch target, so the one
 * control a full field needs was the smallest thing on the page. `IconButton` brings
 * Miuix's own 40dp minimum and its press feedback, and `MiuixIcons.Clear` is the
 * library's mark for a field — the same pairing the storage page's remove control uses.
 *
 * The caller decides when it exists: an always-present cross is a target that does
 * nothing on an empty field.
 */
@Composable
internal fun ClearFieldButton(
    onClick: () -> Unit,
    contentDescription: String,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MiuixIcons.Clear,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
    }
}

/** The empty state: a heading and the sentence that says what is missing. */
@Composable
private fun FilesNote(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                textAlign = TextAlign.Center,
            )
        }
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
 * A row's glyph, by what the file is rather than only whether it is a directory.
 *
 * A listing where every file wears the same sheet-of-paper mark asks the reader to
 * read the *name* to tell anything apart; the six kinds — code and text, image,
 * archive, video, audio, sheet — cover what a phone user steps into this tab to find,
 * and everything else keeps the paper. Every mark is the library's own, and each is
 * the closest the set comes to its kind: `Notes` is a page of lines and stands for
 * code, `GridView` is a table of cells and stands for a sheet, `Backup` is a stack and
 * stands for an archive, `Play` and `Music` say video and sound. A document and a
 * plain file share the paper because the set has exactly one page mark between them —
 * the row's second line is what tells a `pdf` from a file with no extension at all.
 *
 * The extension's first letter is not a glyph either: a row of one-letter marks reads
 * as a bespoke alphabet nobody agreed on.
 */
private fun glyphOf(entry: FileEntry): ImageVector {
    if (entry.isLink) return MiuixIcons.Link
    if (entry.isDirectory) return MiuixIcons.Folder
    return when (entry.file.extension.lowercase(Locale.ROOT)) {
        in CODE_SUFFIXES -> MiuixIcons.Notes
        in IMAGE_SUFFIXES -> MiuixIcons.Image
        in ARCHIVE_SUFFIXES -> MiuixIcons.Backup
        in VIDEO_SUFFIXES -> MiuixIcons.Play
        in AUDIO_SUFFIXES -> MiuixIcons.Music
        in SHEET_SUFFIXES -> MiuixIcons.GridView
        else -> MiuixIcons.File
    }
}

/**
 * The tile's colours, by kind.
 *
 * A folder is the page's navigation and takes the accent's container; a picture or a
 * video takes the live tone so a long listing can be scanned by colour before it is
 * read. Everything else stays neutral — six tones on one page is a palette, and this
 * is a list.
 */
@Composable
private fun tileToneOf(entry: FileEntry): Pair<Color, Color> {
    val suffix = entry.file.extension.lowercase(Locale.ROOT)
    return when {
        entry.isDirectory -> MiuixTheme.colorScheme.primaryContainer to
            MiuixTheme.colorScheme.onPrimaryContainer

        suffix in IMAGE_SUFFIXES || suffix in VIDEO_SUFFIXES ->
            PiTone.liveContainer() to PiTone.live()

        else -> MiuixTheme.colorScheme.surfaceContainerHigh to
            MiuixTheme.colorScheme.onSurfaceContainerHigh
    }
}

/**
 * Written by a programmer, or by a tool a programmer runs, or about one.
 *
 * The image kinds are not here: [IMAGE_SUFFIXES] lives beside the preview that decides
 * whether the sheet can draw a file, because a glyph and a tone that promise a picture
 * over a preview that shows text is the one disagreement a listing must not have.
 */
private val CODE_SUFFIXES = setOf(
    "kt", "kts", "java", "py", "js", "ts", "mjs", "cjs", "sh", "json", "yaml", "yml",
    "toml", "xml", "html", "css", "md", "txt", "log", "ini", "conf", "gradle",
)

/** One thing that unpacks into several. */
private val ARCHIVE_SUFFIXES = setOf("zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst")

private val VIDEO_SUFFIXES = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")

private val AUDIO_SUFFIXES = setOf("mp3", "m4a", "wav", "ogg", "flac", "opus", "aac")

private val SHEET_SUFFIXES = setOf("csv", "tsv", "xls", "xlsx", "ods")

/**
 * The width of the size column at the default text size — its floor, not its size.
 *
 * The column is the one thing on a row that has to line up with the column above and
 * below it, so it gets a floor rather than a wrap-to-content width: the numbers end on
 * one x, which is the only thing that makes a column of sizes scannable. The floor is
 * measured for the longest string the column can hold, in the interface's monospace at
 * the body size: ten characters — `10240.0 MB` for a file over ten gibibytes (the size
 * formatter has no unit above MB) and `1234 items` for a folder with four digits of
 * children. The monospace advance is 0.6 em, so ten characters at 14sp need 84dp; 88
 * leaves room for the glyph rounding and for a locale whose count reads one glyph
 * longer (`12345 項目`).
 *
 * It is a minimum because the type is not fixed: the app's text-size setting
 * multiplies `fontScale`, and at the largest step the same ten characters are more than
 * twice as wide. A fixed 88dp box ellipsised the tenth character there — the very
 * failure this number exists to prevent — so the box grows with the type and only the
 * *floor* is written down.
 */
private val SIZE_COLUMN = 88.dp

/**
 * The gap under the last row.
 *
 * The page's own furniture, not the bar's height: the bar is the root scaffold's, and
 * this is the space that keeps a row from being read against its edge.
 */
private val PAGE_BOTTOM = 16.dp
