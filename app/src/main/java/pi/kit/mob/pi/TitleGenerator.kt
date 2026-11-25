/*
 * A conversation's name, requested once from pi itself.
 *
 * ## Why pi, and why `--print`
 *
 * pi has no title command: a session stays unnamed until something calls
 * `set_session_name`, and its own picker falls back to the first prompt. The request
 * still has to reach whatever model the conversation is using, and pi already knows
 * every provider's endpoint, protocol, authentication and response shape. A second
 * hand-written provider layer would cover the common OpenAI and Anthropic envelopes
 * and guess the rest; the first version did exactly that and returned an empty answer
 * on a reasoning model whose thinking consumed the output budget before it printed.
 *
 * `pi --print` is pi's own one-shot mode: one short process, one final answer, no
 * interactive UI and no session. `--thinking off` makes the request cost a few tokens,
 * `--no-session` keeps it out of the history list, and the launcher closes every
 * ambient resource door (tools, extensions, skills, prompt templates, AGENTS.md, MCP)
 * so an installed extension cannot turn a title into another agent run. The price is
 * one Node start per new conversation; the feature is optional and runs in the
 * background.
 *
 * The instruction is DSH's first-prompt title policy, because that policy is the one
 * measured to produce stable, short, plain titles: return one line in the messages'
 * language, about five words or ten CJK characters, with no Markdown, quotes, prefix
 * or explanation; a best-effort title when there is little to name. The output parser
 * follows the same rules and clips the result to one line and a fixed budget.
 *
 * It runs **once per conversation**, from the first prompt a fresh conversation sends,
 * and only while the title switch is on. A failed request is not retried: the point of
 * the feature is a cheap convenience, and a provider that refused once is not made
 * cheaper by asking again. A name the reader set is never overwritten — every write
 * checks that the session is still unnamed.
 */
package pi.kit.mob.pi

/** The one prompt a fresh conversation may title itself from, or null. */
internal fun firstPromptText(state: ConversationState, freshPath: String, enabled: Boolean): String? {
    if (!enabled || state.sessionFile != freshPath || !state.sessionName.isNullOrBlank()) return null
    // The item count is the whole rule: one prompt in the transcript is the opening
    // prompt. The turn record is bookkeeping the reducer attaches on its own schedule;
    // requiring `turns.singleOrNull()?.promptKey` added a second condition that says
    // nothing about whether this is the reader's first message.
    val prompt = state.items.filterIsInstance<ChatItem.User>().singleOrNull() ?: return null
    return parsePromptExtras(prompt.text).displayText
        .trim()
        .take(TITLE_MAX_INPUT_CHARS)
        .takeIf { value -> value.any { it.isLetterOrDigit() || it.isSurrogate() } }
}

/**
 * The instructions, taken from DSH's first-prompt title provider.
 *
 * "Use the language of the messages" is what keeps a Chinese conversation from
 * getting an English title, and the length targets are the two units a short title
 * is measured in: words for alphabetic languages, characters for CJK.
 */
internal fun titleSystemPrompt(): String = listOf(
    "Create a concise title for an AI assistant conversation from the supplied human messages.",
    "Return only the title on one line, in plain text of natural language, with no quotes, prefix, explanation, Markdown, XML, or terminal control codes. No code is allowed.",
    "Use the language of the messages.",
    "Aim for about 5 words in non-CJK languages or 10 CJK characters.",
    "If the messages give little to name, still return a short best-effort title, such as Greeting, instead of explaining.",
).joinToString("\n")

/**
 * One safe title line from whatever pi printed.
 *
 * The first non-empty line is the title; a model that ignores the one-line instruction
 * puts the title first and commentary after it. Wrapping asterisk emphasis is removed,
 * a `Title:` prefix is dropped, quotes are trimmed, controls are removed and whitespace
 * is collapsed. The result is capped so a model that answers with a paragraph cannot
 * become a drawer row.
 */
internal fun normalizeTitle(raw: String): String {
    val line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    var text = line
    text = text.replace(Regex("^(?i)(title|标题|タイトル)\\s*[:：]\\s*"), "")
    val emphasis = Regex("^(\\*{1,3})(\\S(?:.*\\S)?)\\1$").find(text)
    if (emphasis != null) text = emphasis.groupValues[2]
    text = text.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』')
    text = text
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return clipTitle(text)
}

/** A code-point prefix within both the character and the byte budget. */
private fun clipTitle(text: String): String {
    if (text.codePointCount(0, text.length) <= TITLE_CHAR_LIMIT &&
        text.toByteArray(Charsets.UTF_8).size <= TITLE_BYTE_LIMIT
    ) return text

    var index = 0
    var chars = 1
    var bytes = TITLE_ELLIPSIS.toByteArray(Charsets.UTF_8).size
    while (index < text.length && chars < TITLE_CHAR_LIMIT) {
        val codePoint = text.codePointAt(index)
        val encoded = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + encoded > TITLE_BYTE_LIMIT) break
        index += Character.charCount(codePoint)
        bytes += encoded
        chars++
    }
    val prefix = text.substring(0, index).trimEnd()
    val lastSpace = prefix.lastIndexOf(' ')
    val end = if (text.getOrNull(index)?.isWhitespace() == false && lastSpace >= prefix.length * 3 / 4) {
        prefix.substring(0, lastSpace)
    } else {
        prefix
    }
    return end + TITLE_ELLIPSIS
}

/**
 * The first prompt is the opening question, not the transcript. Two thousand
 * characters is several screens of prose and still a short request.
 */
private const val TITLE_MAX_INPUT_CHARS = 2_000
private const val TITLE_CHAR_LIMIT = 40
private const val TITLE_BYTE_LIMIT = 80
private const val TITLE_ELLIPSIS = "…"
