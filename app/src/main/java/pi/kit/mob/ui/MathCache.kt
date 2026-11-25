/*
 * Typesetting a formula, off the UI thread.
 *
 * ## Which renderer, and why it changed
 *
 * This file used to hold a `latex-renderer` measurer — KaTeX's own fonts and KaTeX's own layout
 * metrics — and a single worker that measured one formula at a time. The renderer was the right
 * choice on paper: it is the only Android typesetter that carries a `MATH` table's constants, and
 * a fraction's axis, an integral's size variants and a stretchy delimiter all live in that table.
 *
 * What it also carried was **21.7 ms per formula**, measured on this project's emulator against a
 * 300-block reply: 142 formulas took 3078 ms on the worker, every landed result redrew its block,
 * and the reply showed LaTeX source for seconds before it settled. A probe that drew the same
 * document with the mathematics switched off put the whole of the difference there — the measurer
 * was constructed in 0 ms, the per-block token walk cost 0 ms, and `renderInline` cost 0 ms.
 *
 * The renderer that replaced it is `ru.noties:jlatexmath-android` — a port of JLaTeXMath, and the
 * one Operit's chat draws with. Same emulator, same 14 sp, measured the same way: **422 µs mean,
 * 307 µs p50, 1.5 ms worst** to lay a formula out and **183 µs p50** to draw one, with all twenty
 * of the constructs a reply actually uses rendering — including `\begin{aligned}`, `pmatrix`,
 * `cases`, `\text{}`, `\binom`, `\sqrt[3]`, `\mathbb{}` and `\underset`. That is a factor of about
 * fifty, and it is the whole of the reason a formula-heavy reply was slow.
 *
 * The two renderers disagree about one visible thing: JLaTeXMath sets Computer
 * Modern, so a formula is spaced and hinted slightly differently from the KaTeX metrics this
 * project used before. That is a change of voice, not a loss of correctness, and it is recorded in
 * ARCHITECTURE §12.2 with the rest of the trade.
 *
 * ## What a formula is here
 *
 * A [Formula] is a drawable plus the three numbers the text layout needs: its width, its height,
 * and its **depth** — how far the formula's baseline sits above the drawable's bottom. The depth
 * is what places a stacked fraction or an integral on the line's baseline instead of on its
 * bottom edge, and `TeXIcon.getIconDepth()` is where it comes from.
 *
 * **The drawable is kept rather than a rasterised bitmap, and the reason is memory.** A bitmap per
 * formula is 20–235 KB, a reply can hold hundreds of formulas, and a `Column` composes all of them
 * at once — so a single long answer would hold tens of megabytes of pixels that are mostly
 * off-screen. The vector drawable costs 183 µs to draw and holds kilobytes. A rasterised cache
 * keyed by what is *visible* is the optimisation to reach for if a frame table ever asks for it;
 * it is not what this is, and the measurement that would justify it is not in hand.
 *
 * ## Why the work is off the UI thread
 *
 * Because it is 0.4 ms today and was 21.7 ms yesterday, and because a reply's formulas all become
 * visible at once. Removing the *cost* is the real fix; moving the work is what keeps a slow
 * device or a pathological formula from blocking a frame. Both are here, and neither is enough on
 * its own: the measurement still has to be off-thread, but it now finishes a whole reply's worth
 * of formulas in the time it used to take to do three of them.
 *
 * A formula that has not been typeset yet is drawn as its own source — `\frac{a}{b}` — and the
 * measurement is queued. When the worker finishes, the entry lands in a `mutableStateMapOf`,
 * which *is* the mechanism that redraws it: reading that map during composition is what registers
 * the dependency, so pushing a value recomposes the block that asked for it.
 *
 * ## Why the worker is a single thread
 *
 * `MathDrawable.build(...)` writes to JLaTeXMath's own static macro and font
 * caches — `MacroInfo.Commands`, `TeXFormula`'s predefined set — which are plain maps. One thread
 * is what keeps that safe, and at 0.4 ms a formula it costs nothing to keep.
 */
package pi.kit.mob.ui

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.scilab.forge.jlatexmath.TeXConstants
import java.util.Collections

/**
 * One typeset formula: the drawable and the geometry the surrounding text needs.
 *
 * The drawable is **not** drawn by this object — `MathView.kt` does that, because a drawable
 * cannot be drawn outside a `DrawScope` — and it is shared between every block that uses the same
 * formula, which is safe because a `MathDrawable` is immutable once built and only its
 * bounds are set before a draw, on the one thread that draws.
 */
internal class Formula(
    val drawable: MathDrawable,
    /** Width in pixels, including the padding the builder was given. */
    val widthPx: Int,
    /** Height in pixels, including the padding. */
    val heightPx: Int,
    /**
     * How far the formula's baseline is above the drawable's bottom, in pixels.
     *
     * The baseline of a formula is not its bottom: `\frac{1}{2}` hangs below the line it is
     * written on. An inline placeholder is centred on the text's centre (`MathView.kt`), and this
     * depth is what lets the drawable be offset inside that box until the formula's own baseline
     * lands on the line's.
     */
    val depthPx: Int,
)

/**
 * What a formula is typeset with: the size in pixels, the ink it is drawn in, and whether it is a
 * display formula.
 *
 * [display] is not a drawing preference: it selects `TeXConstants.STYLE_DISPLAY` or
 * `TeXConstants.STYLE_TEXT` at typeset time, and the two are different sizes for the same body —
 * `\frac{1}{2}` at 17sp / 44.6px is 106px tall in display style against 70px in text style. It is
 * part of [MathCache]'s key because the same formula at the same size is two different boxes.
 */
@androidx.compose.runtime.Immutable
internal data class MathSpec(val sizePx: Float, val ink: Int, val display: Boolean = false)

/**
 * The formulas that have been typeset, and the worker that typesets the rest.
 *
 * A singleton because a formula is worth sharing across every block in every reply that uses it —
 * the `a_i` of a long sum, the `\frac` of a derivation — and a per-composition cache would throw
 * those away with the composition that built them.
 */
internal object MathCache {

    /**
     * Typeset formulas, keyed by the LaTeX body, the size, the ink and the TeX style.
     *
     * A `SnapshotStateMap` rather than a plain map: a read of it during composition is what makes
     * a later write recompose the block that asked. Nothing else in here is observable by Compose,
     * which is why it is the only mutable state in the file.
     */
    private val measured: SnapshotStateMap<String, Formula> = mutableStateMapOf()

    /** The formulas already queued or typeset, so a recomposition does not queue twice. */
    private val requested: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * A counter per cache key, bumped when that formula's measurement lands.
     *
     * This exists because reading [measured] *inside* the `remember` that builds a block's
     * annotated text is not enough: that read happens while the value is being computed, so it
     * registers no dependency and the block keeps the annotated text it already built — every
     * formula stays drawn as its own source forever.
     *
     * A single global counter fixes that and is *much worse*: it becomes a key of every block's
     * `remember`, so each of a reply's measurements recomposed all of its blocks — measured,
     * `99th percentile 2050 ms`. Per key means only the block that asked for that formula redraws.
     */
    private val versions: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /**
     * One thread for every measurement. JLaTeXMath keeps its macro and font caches in statics, so
     * a second worker would be a race for `MacroInfo.Commands` rather than a speed-up.
     */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** The key a formula is cached and queued under. */
    private fun key(body: String, spec: MathSpec) =
        "$body\u0000${spec.sizePx}\u0000${spec.ink}\u0000${spec.display}"

    /**
     * The combined version of the formulas in one block, for that block's `remember` key.
     *
     * Read in composition. The sum rather than a list because the key only has to *change*, not to
     * mean anything.
     */
    fun versionOf(keys: List<String>): Int = keys.sumOf { versions[it] ?: 0 }

    /** The cache keys of every formula in one block, for [versionOf]. */
    fun keysFor(source: String, spec: MathSpec): List<String> =
        buildList { collectFormulas(source, this) }.map { key(it, spec) }

    /**
     * The state-read of the formulas in [source] at [spec], for a caller that memoises something
     * derived from them.
     *
     * The table needs this and nothing else does: it memoises a *column-width* measurement keyed
     * on the rendered cells, and a cell rendered from a cold cache is the formula's own source —
     * so without this the table would keep drawing `$\frac{1}{2}$` in a cell forever, even after
     * the formula landed.
     */
    fun versionOf(source: String, spec: MathSpec): Int =
        versionOf(buildList { collectFormulas(source, this) }.map { key(it, spec) })

    /**
     * The typeset formula for [body], or null if it has not been typeset yet.
     *
     * Reading this during composition is deliberate: it is what makes a later write recompose the
     * caller.
     */
    fun get(body: String, spec: MathSpec): Formula? = measured[key(body, spec)]

    /**
     * Queues [body] unless it is already queued or typeset.
     *
     * Called for every formula a block finds, including the ones that are already cached — the
     * check is what makes that cheap.
     */
    fun request(body: String, spec: MathSpec) {
        val cacheKey = key(body, spec)
        if (measured.containsKey(cacheKey)) return
        if (!requested.add(cacheKey)) return
        worker.launch {
            val outcome = runCatching { typeset(body, spec) }
            // On failure the key stays in `requested` and nothing lands in the cache: the reader
            // sees the formula's own source, and a formula JLaTeXMath refuses is not retried on
            // every frame. `displayFormula`'s source-preserving fallback is the same behaviour.
            // The log line is what makes that visible: a construct the renderer does not know is
            // otherwise indistinguishable from a formula this app forgot to ask for.
            outcome.getOrNull()?.let { formula ->
                measured[cacheKey] = formula
                versions[cacheKey] = (versions[cacheKey] ?: 0) + 1
            } ?: Log.w("PiKit", "formula not typeset, drawn as its source: $body", outcome.exceptionOrNull())
        }
    }

    /**
     * Typesets every formula in [text], without waiting for a composition to ask.
     *
     * The point is to fill the cache *before* the reply is first drawn, so the common case —
     * opening a saved conversation — draws formulas rather than their source. Because a formula's
     * TeX style is part of its cache key, a reply that holds display formulas needs this called
     * once with each [MathSpec.display] value; `MarkdownText` does exactly that. Everything here
     * is cheap string work; the typesetting itself happens on the worker.
     */
    fun prefetch(text: String, spec: MathSpec) {
        // The tokeniser is linear and pure, and `containsInlineMath` runs it; calling it again per
        // formula would be the quadratic mistake this file's own chapter warns about, so the walk
        // is done once here.
        val bodies = buildList { collectFormulas(text, this) }
        bodies.forEach { request(it, spec) }
    }

    /**
     * Pays JLaTeXMath's one-time cost — parsing its predefined formulas and loading the first font
     * — while no formula is on screen.
     *
     * It is about 10 ms, and it is paid on whichever thread first calls [typeset]. Without this
     * that thread is the worker with a reply's worth of formulas behind it, which is harmless, and
     * the *first* formula's arrival would be late by that amount, which is visible on a short
     * reply.
     */
    fun warm(spec: MathSpec) {
        worker.launch { runCatching { typeset(WARM_UP, spec) } }
    }

    /** A formula with a fraction, a radical, a limit and a script: the four layout paths. */
    private const val WARM_UP = "\\frac{1}{\\sqrt{2\\pi}} \\lim_{n \\to \\infty} a_{n}^{2}"
}

/** The gap the builder puts around a formula, in pixels, on each side. */
private const val PAD = 2

/**
 * Lays a formula out with JLaTeXMath and reads the geometry back out of it.
 *
 * Runs on the worker and touches no UI state, which is the point: the builder is where the 0.4 ms
 * goes, and the numbers it returns are all the text layout needs.
 */
private fun typeset(body: String, spec: MathSpec): Formula {
    // Rows first: `&` and `\\` outside an environment are not multi-line mathematics to
    // the renderer, they are a parse error (the report "多行公式不显示"). `wrapRows` is
    // the writer's missing `\begin{aligned}`; the rewrites below are for constructs the
    // renderer does not know at all.
    val prepared = LatexCompat.wrapRows(body)
    // The first formula is also what loads JLaTeXMath's predefined command table, which is what
    // the aliases in `LatexCompat` need; installing them once, here, is the earliest they can work.
    val drawable = runCatching { buildDrawable(prepared, spec) }.getOrElse { failure ->
        val rewritten = LatexCompat.rewrite(prepared)
        if (rewritten == prepared) throw failure
        Log.i("PiKit", "formula rewritten to typeset it: $body  ->  $rewritten")
        buildDrawable(rewritten, spec)
    }
    LatexCompat.installAliases()
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    // The padding is applied *inside* the icon, so the formula is drawn PAD pixels above the
    // drawable's bottom as well as PAD inside its left and right edges: a depth read from the
    // icon's own bottom without the padding would leave every formula PAD pixels too high on the
    // line. This is the geometry the library's builder produced, unchanged — the drawable only
    // draws it.
    val depth = (drawable.depthPx() + PAD).coerceIn(0, height)
    return Formula(drawable = drawable, widthPx = width, heightPx = height, depthPx = depth)
}

/**
 * One formula, laid out by JLaTeXMath, at the size, ink and TeX style the caller asked for.
 *
 * Split out of [typeset] so that a body the renderer refuses can be rewritten and built again
 * without the geometry below being written twice.
 */
private fun buildDrawable(body: String, spec: MathSpec): MathDrawable =
    MathDrawable.build(
        body = body,
        sizePx = spec.sizePx,
        ink = spec.ink,
        padding = PAD,
        style = if (spec.display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT,
    )

/**
 * Every LaTeX body in [text], by the same tokeniser the renderer uses.
 *
 * Declared here rather than reusing `tokenizeInline` directly because that walk is in
 * `Markdown.kt` and private to the block renderer; this is the one place that needs the bodies
 * without the tokens.
 */
private fun collectFormulas(text: String, into: MutableList<String>) {
    tokenizeInline(text).forEach { token -> token.collectMath(into) }
}

private fun InlineToken.collectMath(into: MutableList<String>) {
    when (this) {
        is InlineToken.Math -> into += unescapeMath(source)
        is InlineToken.Styled -> children.forEach { it.collectMath(into) }
        is InlineToken.Link -> children.forEach { it.collectMath(into) }
        else -> Unit
    }
}
