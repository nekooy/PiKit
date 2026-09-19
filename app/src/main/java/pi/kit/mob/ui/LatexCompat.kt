/*
 * The LaTeX JLaTeXMath cannot typeset, rewritten into LaTeX it can.
 *
 * ## Why the fallback is a rewrite rather than the formula's source
 *
 * A formula the renderer refuses is drawn as its own LaTeX source, which is the right last resort
 * and a bad first one: the reader is a person reading an answer, and `\ce{H2O}` in the middle of a
 * sentence reads as a defect. The report that made this file exist is "某些情况没渲染", which was
 * eight constructs out of a sweep of forty common ones, and it splits into three kinds — none of
 * which is a *mathematical* failure:
 *
 *  - **Numbering and labels** — `\tag{1}`, `\label{eq:1}`, `\nonumber`, `\notag`. They are
 *    cross-reference machinery for a paper, they draw nothing, and dropping them leaves the
 *    formula exactly right.
 *  - **A command with another spelling** — `\color{red}{x}` is `\textcolor{red}{x}`, which
 *    JLaTeXMath has. This is aliased in the macro table rather than rewritten where it can be, so
 *    the formula is typeset from its own source; the rewrite is the fallback for the first
 *    formula, before the macro table is loaded.
 *  - **A decoration** — `\cancel{x}`, `\hcancel{x}`, `\sout{x}`, `\xout{x}` strike a term out, and
 *    `\ce{H2O}` is mhchem chemistry. Neither exists here. Striking out is *not* mathematics, so
 *    the content is kept and the decoration is lost: `x` and `\text{H2O}`. That is a visible
 *    difference and it is a deliberate one — the alternative is that the reader sees the source.
 *
 * `\oiint` and `\oiiint` are left alone on purpose: they are single glyphs the renderer's fonts do
 * not have, and any rewrite would be an invented picture of a surface integral.
 *
 * ## Why the aliasing is separate
 *
 * `MacroInfo.Commands` is public, so a missing name can be pointed at an existing macro and the
 * formula needs no rewriting at all. That only works once JLaTeXMath has loaded its predefined
 * commands — which happens when the first formula is *parsed*, i.e. after the first call to
 * `typeset` — so the alias is installed at the end of the first typesetting and the textual
 * rewrite covers whatever arrives before it.
 */
package pi.kit.mob.ui

import android.util.Log
import org.scilab.forge.jlatexmath.MacroInfo

/** Commands that draw nothing: dropped with the group that follows them. */
private val DROPPED = setOf("tag", "label", "nonumber", "notag", "hfill", "hspace")

/** Commands that decorate their argument: the argument is kept, the decoration is dropped. */
private val UNWRAPPED = setOf("cancel", "hcancel", "sout", "xout", "mathclap", "phantom")

/** Commands whose name is the only thing wrong with them. */
private val RENAMED = mapOf("ce" to "text", "color" to "textcolor")

internal object LatexCompat {

    /**
     * Points a name JLaTeXMath does not know at a macro it does.
     *
     * Called after the first formula has been parsed, which is the earliest moment the predefined
     * command table is populated. Failure is silent because this is an optimisation: a formula
     * that arrives before it runs is rewritten textually instead, and one that arrives after it is
     * typeset from its own source.
     */
    fun installAliases() {
        runCatching {
            val commands = MacroInfo.Commands
            if (commands["color"] == null) {
                commands["textcolor"]?.let { commands["color"] = it }
            }
        }.onFailure { Log.w(TAG, "could not install the LaTeX command aliases", it) }
    }

    /**
     * [body] with the three kinds of unsupported construct above rewritten, or [body] itself when
     * there was nothing to rewrite.
     *
     * A hand-written scan rather than a regular expression, for the reason `Markdown.kt` records:
     * a pattern the JVM accepts and Android's ICU rejects fails a class initialiser and takes
     * every formula with it.
     */
    fun rewrite(body: String): String {
        if (!body.contains('\\')) return body
        val out = StringBuilder(body.length)
        var index = 0
        var changed = false
        while (index < body.length) {
            val char = body[index]
            if (char != '\\') {
                out.append(char)
                index++
                continue
            }
            val nameStart = index + 1
            var nameEnd = nameStart
            while (nameEnd < body.length && body[nameEnd].isLetter()) nameEnd++
            val name = body.substring(nameStart, nameEnd)
            val groupStart = groupAt(body, nameEnd)
            when {
                name in DROPPED -> {
                    changed = true
                    index = if (groupStart >= 0) groupEnd(body, groupStart) else nameEnd
                }

                name in UNWRAPPED && groupStart >= 0 -> {
                    changed = true
                    out.append(body, groupStart, groupEnd(body, groupStart))
                    index = groupEnd(body, groupStart)
                }

                RENAMED.containsKey(name) -> {
                    changed = true
                    out.append('\\').append(RENAMED.getValue(name))
                    index = nameEnd
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return if (changed) out.toString() else body
    }

    /** The index of a `{` at or after [from], skipping nothing but spaces, or -1. */
    private fun groupAt(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index] == ' ') index++
        return if (index < text.length && text[index] == '{') index else -1
    }

    /** The index just past the `{…}` group opening at [open], counting nesting, or [open]. */
    private fun groupEnd(text: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return open
    }
}

/** The one tag every log line in this app carries. */
private const val TAG = "PiKit"
