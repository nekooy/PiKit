/*
 * The machinery a prompt carries besides the sentence the reader typed.
 *
 * This lives in `pi` rather than in the chat page because the shapes it parses are pi's:
 * `[Image: original … displayed at …]` is pi's note, and the address header is the one this
 * app writes for a file attachment. Three callers need the split — the transcript, whose
 * chip is the file-path block and whose viewer shows a note beside its picture, the picture
 * viewer, whose info disc reads the note, and the session list, which must not title a
 * conversation after either.
 */
package pi.kit.mob.pi

/**
 * What a prompt carries besides the words the reader typed.
 *
 * File paths and the `[Image: …]` notes pi stamps on a picture are *machinery*:
 * they travel with the prompt so the model can open the file or map coordinates,
 * and they are not prose the reader wrote. They used to sit in the bubble as
 * plain text — the paths as a trailing list, the image note as a bracketed
 * English sentence in the middle of the message — which is the report that the
 * bubble is "a wall of paths". Parsed out here, the paths are one chip that opens
 * a sheet; an image note belongs to its picture and is shown behind the viewer's
 * info disc, not as a count on the sentence the reader asked.
 */
internal data class PromptExtras(
    val displayText: String,
    val filePaths: List<String> = emptyList(),
    val imageNotes: List<String> = emptyList(),
) {
    val hasExtras: Boolean get() = filePaths.isNotEmpty() || imageNotes.isNotEmpty()
}

/**
 * `[Image: original 1200x2670, displayed at 899x2000. Multiply coordinates by …]`
 * and its cousins. pi writes these in English whatever the interface language;
 * the `图像`/`画像` alternates are there so a note that was translated does not
 * leak into the bubble either.
 */
private val IMAGE_NOTE_REGEX = Regex("""\[(?:Image|图像|画像)[^\]]*\]""")

/** One attachment path as the prompt prints it: an absolute path with no spaces. */
private val ABSOLUTE_PATH_REGEX = Regex("""^/\S+$""")

/**
 * The heading each catalog puts above a file attachment's paths.
 *
 * Matched by the three strings the app itself writes, not by a shape: a reader
 * who typed a sentence ending in a colon and a path on the next line would
 * otherwise lose that sentence to the parser. A session restored in another
 * language still matches, because all three are checked.
 */
private val ADDRESS_HEADERS = setOf(
    "以下文件已就绪，请按路径读取：",
    "These files are ready; read them by path:",
    "次のファイルは準備できました。パスで読んでください：",
)

/**
 * Splits a stored prompt into the sentence the reader wrote and the machinery
 * around it.
 *
 * Image notes are removed wherever they sit. The file-path block is the app's
 * own `addressHeader` followed by one absolute path per line — at the front of
 * the prompt now, at the end of one written before that change — and the header
 * goes with the paths, so the display text is only what the reader typed.
 */
internal fun parsePromptExtras(raw: String): PromptExtras {
    if (raw.isBlank()) return PromptExtras("")

    val imageNotes = mutableListOf<String>()
    val withoutNotes = IMAGE_NOTE_REGEX.replace(raw) { match ->
        imageNotes += match.value.trim()
        ""
    }
        // The notes leave holes; collapse the blank runs they opened so the
        // display text does not grow a ragged edge around every picture.
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")

    val lines = withoutNotes.lines()
    val remove = BooleanArray(lines.size)
    val filePaths = mutableListOf<String>()

    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() in ADDRESS_HEADERS) {
            remove[i] = true
            var j = i + 1
            while (j < lines.size && ABSOLUTE_PATH_REGEX.matches(lines[j].trim())) {
                filePaths += lines[j].trim()
                remove[j] = true
                j++
            }
            // One blank line on each side of the block is the separator, not
            // the reader's paragraph spacing.
            if (j < lines.size && lines[j].isBlank()) remove[j] = true
            if (i > 0 && lines[i - 1].isBlank()) remove[i - 1] = true
            i = j
        } else {
            i++
        }
    }

    val displayText = lines.filterIndexed { index, _ -> !remove[index] }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    return PromptExtras(displayText, filePaths, imageNotes)
}
