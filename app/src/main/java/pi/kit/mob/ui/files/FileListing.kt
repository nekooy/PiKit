/*
 * What a directory listing is, and how it is arranged.
 *
 * Split out of the screen because the rules here are the ones a file manager is
 * judged by, and every one of them is a pure function of a list: folders before
 * files, name order ignoring case, the two orders that are not name, the filter, the
 * breadcrumb trail, and the two forms a timestamp takes. None of that needs a `File`
 * on disk or a composition, so it is testable on the JVM (`FileListingTest`) where
 * the screen is not.
 *
 * ## Metadata is read once, not once per comparison
 *
 * A `List<File>` sorted by size calls `length()` on every comparison — O(n log n)
 * `stat` calls through FUSE on Android, on a directory that may hold thousands of
 * entries. So [readDirectory] is the only place that touches the filesystem, and it
 * does so once per entry, on IO; everything after it works on [FileEntry] values.
 *
 * ## Folders are counted, files are measured
 *
 * A directory's own `length()` is meaningless, and its "size" would be either a lie
 * or a walk of the whole tree. What a folder *has* is children, so a folder carries
 * [FileEntry.children] — one `readdir` of it, done in the same pass — and a file
 * carries its byte count. A link gets neither count: its target may be a tree on
 * another filesystem, and walking it to answer "how big is this link" is exactly the
 * work the page exists to avoid.
 */
package pi.kit.mob.ui.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.nio.file.Files as NioFiles

/** One row of a listing: a file, a folder, or a link to either. */
internal class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modifiedAt: Long,
    /** How many entries a real folder holds; null for a file and for a link. */
    val children: Int?,
)

/**
 * The three orders a listing can be in.
 *
 * Name is the default and the only one that is *stable* in the way a reader expects
 * — two files created a second apart keep a fixed relative position — which is why
 * it is first. Modified and Size answer the two questions a listing is usually
 * opened to answer: what changed last, and what is taking the space.
 */
internal enum class FileSort {
    Name,
    Modified,
    Size,
}

/**
 * Reads [directory] into entries, with every fact a row shows.
 *
 * A directory that cannot be read and one that is empty both come back as an empty
 * list, which is what `listFiles()` gives the page either way; the page says so in
 * one sentence rather than trying to tell them apart.
 *
 * Symlinks are reported, not followed. `File.isDirectory` *does* follow a link, so a
 * link to a folder is drawn as a folder — that is what the reader wants to know —
 * while [FileEntry.isLink] is what stops the page from counting the target's
 * children and from claiming the link's own size is the target's.
 */
internal fun readDirectory(directory: File): List<FileEntry> {
    val children = directory.listFiles() ?: return emptyList()
    return children.map { file ->
        val link = runCatching { NioFiles.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        val isDirectory = file.isDirectory
        FileEntry(
            file = file,
            name = file.name,
            isDirectory = isDirectory,
            isLink = link,
            size = if (isDirectory) 0L else file.length(),
            modifiedAt = file.lastModified(),
            children = if (isDirectory && !link) file.listFiles()?.size else null,
        )
    }
}

/**
 * [entries], filtered by [query] and sorted by [sort].
 *
 * **Folders come first whatever the order**, which is the one rule every file
 * manager shares and the one a comparator is most likely to lose: with folders mixed
 * into a size order, the directory you navigate through moves every time a file in
 * it grows, and the listing stops being a place and becomes a list of sizes.
 *
 * The filter is a case-insensitive substring of the name — not a glob and not a
 * search of file contents: it narrows the folder that is already on screen, and
 * anything cleverer would have to say which of those things it did.
 */
internal fun arrange(entries: List<FileEntry>, sort: FileSort, query: String): List<FileEntry> {
    val needle = query.trim()
    val matching = if (needle.isEmpty()) {
        entries
    } else {
        entries.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val order = when (sort) {
        FileSort.Name -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
        FileSort.Modified -> compareByDescending<FileEntry> { it.modifiedAt }
            .thenBy { it.name.lowercase(Locale.ROOT) }

        FileSort.Size -> compareByDescending<FileEntry> { it.size }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    }
    return matching.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(order))
}

/**
 * One segment of the location bar: the word shown, and the folder it goes to.
 *
 * A `data class` rather than a plain one because the trail is a `LaunchedEffect` key
 * in the location bar — it scrolls to the segment being shown whenever the trail
 * changes — and two trails that name the same folders have to compare equal. With
 * identity equality every recomposition restarted that scroll, which fought the
 * reader's own horizontal swipe.
 */
internal data class Crumb(val label: String, val target: File)

/**
 * The trail from [home] to [current], first segment first.
 *
 * The first segment is always `~` — it is a path sigil rather than a word, so it is
 * not in the catalogs — and the last is the folder being shown. A [current] that is
 * not inside [home] is drawn as one segment holding its absolute path rather than as
 * a trail that walks up through the app's private directories: the page is rooted at
 * `$HOME`, and nothing it can reach is outside it.
 */
internal fun crumbs(home: File, current: File): List<Crumb> {
    if (current == home) return listOf(Crumb("~", home))
    if (!current.absolutePath.startsWith(home.absolutePath + File.separator)) {
        return listOf(Crumb(current.absolutePath, current))
    }
    val chain = generateSequence(current) { it.parentFile }
        .takeWhile { it != home }
        .toList()
        .asReversed()
    return listOf(Crumb("~", home)) + chain.map { Crumb(it.name, it) }
}

/**
 * How many directories below [root] this one is.
 *
 * The direction of the page slide is the sign of the difference between two of these.
 * Counting the platform's separator rather than a hard-coded `/`, because the unit
 * tests run on Windows: with `'/'` every directory answered zero there, which is the
 * one answer that makes the slide direction constant. Counting separators rather than
 * comparing path lengths, because the two agree on every path that can be reached — a
 * child's path is its parent's plus one component — and only one of them is the
 * reason.
 */
internal fun File.depthUnder(root: File): Int {
    val prefix = root.absolutePath.trimEnd(File.separatorChar)
    return absolutePath.removePrefix(prefix).count { it == File.separatorChar }
}

/**
 * A byte count in the one form a listing shows.
 *
 * Base 1024 with a decimal, and the locale's own separator: a file manager's size
 * column is a number the reader compares against another number, and the exponential
 * forms a debugger prints are not that.
 */
internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * When [modifiedAt] was, in the width a row's second line has.
 *
 * Three forms rather than one, which is the platform's own rule for a file list:
 * today is a time, earlier this year is a day, anything older is a year. A full
 * `yyyy-MM-dd HH:mm` on every row would be 16 characters of a 411dp screen spent on
 * four digits nobody reads twice.
 */
internal fun stampFor(modifiedAt: Long, now: Long): String {
    if (modifiedAt <= 0L) return ""
    val then = Calendar.getInstance().apply { timeInMillis = modifiedAt }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val sameDay = sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        sameYear -> "MM-dd"
        else -> "yyyy-MM-dd"
    }
    // Built per call rather than cached: `Locale.getDefault()` is what the interface
    // switch changes, and a formatter remembered across that change would keep
    // writing last language's numerals and date order.
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(modifiedAt))
}

/**
 * The kind column's word for an entry: a folder, a link, an extension, or a file.
 *
 * The extension is uppercased because that is the kind column of every file browser
 * there has ever been, and a file with no extension says `File` rather than leaving
 * the line empty — an empty word reads as a row that failed to load.
 */
internal fun fileKind(
    file: File,
    isDirectory: Boolean,
    isLink: Boolean,
    folder: String,
    link: String,
    plain: String,
): String = when {
    isLink -> link
    isDirectory -> folder
    file.extension.isNotBlank() -> file.extension.uppercase(Locale.ROOT)
    else -> plain
}

/** Whether this entry's bytes are a picture the preview sheet can draw. */
internal val FileEntry.isImage: Boolean
    get() = !isDirectory && file.extension.lowercase(Locale.ROOT) in IMAGE_SUFFIXES

/**
 * Suffixes `BitmapFactory` decodes: the ones a phone can also open with another app.
 *
 * The one image list in the app: the preview reads it through [isImage], and the
 * listing reads the same set for the row's image glyph and its live tone. It used to
 * exist twice — a copy in the screen carried `svg` and this one did not — so an `.svg`
 * row wore a picture's mark and a picture's colour over a preview that showed text:
 * the row promised something the sheet could not do. `.svg` is therefore *not* an image
 * here; its row takes the paper mark and the neutral tone, and the row's second line
 * still says `SVG`, which is the honest pair. A vector this build learns to draw joins
 * this set, and the glyph and the tone follow it without a second list to drift.
 */
internal val IMAGE_SUFFIXES =
    setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

