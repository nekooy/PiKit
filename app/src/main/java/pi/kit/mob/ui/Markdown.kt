package pi.kit.mob.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.components.CopyButton

/**
 * A deliberately small Markdown renderer for agent output.
 *
 * A full CommonMark implementation would be a large dependency, and almost all
 * of what an agent emits is a narrow subset: prose, fenced code, inline code,
 * headings, lists, quotes, tables and the occasional link. Those are handled here
 * directly so the app stays free of a Markdown library. What *is* implemented is
 * written down construct by construct on [parseMarkdown] and [tokenizeInline],
 * because "a small renderer" is only an honest description if the boundary is
 * somewhere a reader can find it.
 *
 * Mathematics is the one addition that is not CommonMark: a model writing about
 * mathematics emits `$…$` and `$$…$$` as a matter of course, and drawn literally
 * they are unreadable. It is also the one part of this file's job that is *not*
 * hand-written — `MathView.kt` hands a formula to JLaTeXMath, because
 * the geometry a formula needs lives in the font's OpenType `MATH` table, which
 * Android's public text API does not expose (ARCHITECTURE §12). This file decides
 * what is a block and which span of a paragraph is a formula; the renderer decides
 * what it looks like.
 *
 * Anything unrecognised is rendered as plain text rather than dropped, so a
 * construct this parser does not know about degrades instead of disappearing.
 *
 * ## One walk, not two
 *
 * Emphasis and mathematics are found by a **single** tokeniser ([tokenizeInline])
 * and drawn by a single pass ([renderInline]), and that is a correction rather
 * than a tidy-up. The previous version cut each paragraph at its formulas first and
 * ran the emphasis parser over the pieces, so an emphasis that *wrapped* a formula
 * reached the parser as two halves — `**b ` and ` c**` — and neither half held a
 * pair of delimiters. CommonMark does not open an emphasis without one, so
 * `**b $x$ c**` drew its asterisks literally; that was a documented limit of the
 * design and a visible bug to a reader. With the tokeniser owning both, the
 * emphasis run stays open across the formula and the formula is simply inline
 * content inside a bold span.
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    /**
     * A run of list items.
     *
     * A run is **one** block rather than a row per item: the spacing between two
     * items of the same list (2dp) is not the spacing between two blocks (8dp), and
     * a renderer that only ever sees one item cannot tell the two apart — which is
     * what made a five-item list read as five paragraphs. The block also carries
     * whether the run is ordered and what number it starts at, so a `3.` in the
     * middle of a list is drawn as the author's `3.` rather than renumbered.
     */
    data class ListBlock(
        val items: List<Item>,
        val ordered: Boolean,
        /** Only meaningful when [ordered]: the number the first item was written with. */
        val start: Int = 1,
    ) : MdBlock {

        /**
         * One item's text and how deep it sits.
         *
         * [depth] is the nesting level: 0 is the outer list, 1 is a list inside it.
         * It comes from the item's own indentation, so `- a` above `  - b` puts the
         * second at depth 1 whatever the marker's width.
         */
        data class Item(
            val text: String,
            val depth: Int = 0,
            /** Null for a plain item; the checkbox state for a task item. */
            val checked: Boolean? = null,
        )
    }

    /**
     * A block quote, holding the blocks inside it.
     *
     * Nested blocks rather than one string: a quote may hold several paragraphs, a
     * list, a code block or another quote, and flattening it to a line of text is
     * what made a quoted list draw as a quoted sentence. `>` is stripped one level
     * deep and the remainder is parsed by the same function, so all of those work
     * without a second implementation.
     */
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class Code(val language: String, val body: String) : MdBlock

    /**
     * A display formula: `$$…$$`, `\[…\]` or a `\begin{…}` environment.
     *
     * Inline mathematics is not a block. It is inline content inside the paragraph
     * it appears in, so `$x^2$` in the middle of a sentence keeps the sentence's
     * line breaking — see [renderInline].
     */
    data class Formula(val body: String) : MdBlock

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val aligns: List<MdAlign>,
    ) : MdBlock

    data object Rule : MdBlock
}

internal enum class MdAlign { Start, Center, End }

/**
 * A reply, block by block.
 *
 * ## Why this is a plain `Column`, and must stay one
 *
 * A reply is **one** item of the transcript's own `LazyColumn`. A `LazyColumn`
 * *here* looked like the obvious fix for "打开含许多公式的对话会非常卡"，because it
 * would measure only the blocks near the viewport — and it is a crash, twice over,
 * which is what makes this a comment rather than a style preference.
 *
 * `MarkdownText` is not only drawn inside the transcript. `ManualPage` draws it
 * inside `SettingsBody`, which is a `Column(verticalScroll)`, and so does
 * `FilesScreen`'s preview. A vertically scrollable container measured inside
 * another one is measured with `Constraints.Infinity` on the main axis, and
 * `LazyColumn` refuses that outright:
 *
 * ```
 * java.lang.IllegalStateException: Vertically scrollable component was measured
 * with an infinity maximum height constraints, which is disallowed.
 * ```
 *
 * That is the reader's "打开设置页用户手册点击也会闪退" — the manual is Markdown and
 * the page body scrolls — and the same fault on the transcript's own path, where a
 * second scrollbar inside a row also fights the transcript for every drag. The
 * saving a lazy list bought was real but it was measured in one place (the tall
 * reply) and paid for in another (a page that cannot be opened at all).
 *
 * ## Where the scroll cost actually went instead
 *
 * The expensive part of a block used to be a *formula*, and there are two kinds of them:
 *
 *  - **Inline**, inside a paragraph, where the placeholder has to be typeset before the
 *    surrounding sentence can be broken into lines. Those go through `rememberMathInline`, which
 *    asks `MathCache` rather than doing the work, so a block that draws before its formulas are
 *    ready shows their source and is redrawn when they land.
 *  - **Display**, a block of their own — a paragraph whose whole content is one placeholder, for
 *    the reasons in [MdBlockView]'s own note.
 *
 * **The formula is no longer the cost.** `MathCache`'s worker typesets one in 0.35 ms, so a
 * 150-formula reply fills its cache in about 50 ms; what is left of a slow first frame is the
 * layout of the blocks themselves — a `Text`, a `Row`, a divider — and Compose has to measure all
 * of them, because a `Column` here cannot know which are off screen. That is the `Column` note
 * above, and the numbers are in ARCHITECTURE §12.
 */
/**
 * A reply, block by block.
 *
 * ## Every container this is drawn in, and why it is a plain `Column`
 *
 * A `LazyColumn` here is the obvious fix for a long reply and it has now been tried
 * **twice**, and it threw
 * `IllegalStateException: Vertically scrollable component was measured with an infinity
 * maximum height constraints` **both times** — once from `ManualPage`, which draws this
 * inside `SettingsBody`'s `Column(verticalScroll)`, and once from the transcript, which
 * one would expect to supply a bounded height because an answer is an item of a
 * `LazyColumn`. Read from `logcat` on the second attempt rather than assumed: the
 * transcript does not, because the item is wrapped in `SelectionContainer` and the bubble,
 * and the constraint that arrives is infinite.
 *
 * So: **do not make this a lazy list.** A caller cannot be trusted to have a bounded
 * height, and the failure is a crash rather than a slow frame. What pays for a long reply
 * instead is [MathCache]: every formula's measurement is moved off the UI thread, so the
 * frame that draws a reply does none of the 17 ms-per-formula work. See that file for the
 * numbers.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val style = inlineStyle()
    val blocks = remember(text) { parseMarkdown(text) }
    // Fill the formula cache before the blocks are drawn, on a worker, so the common case —
    // opening a saved conversation — draws formulas rather than their source. Keyed on the
    // text, so it runs once per reply rather than once per frame.
    val spec = mathSpec(bodyStyle().fontSize)
    LaunchedEffect(text, spec) { MathCache.prefetch(text, spec) }
    // One subcomposition for the whole reply, because the width every formula is fitted to is the
    // *reply's* width and not each block's — see `LocalFormulaWidth`. A `BoxWithConstraints` per
    // paragraph would say the same thing at a cost per block, and a formula inside a paragraph
    // cannot be measured any other way: a placeholder's size is fixed before the line is known.
    BoxWithConstraints(modifier) {
        val lineWidth = with(LocalDensity.current) { maxWidth.toPx() }
        CompositionLocalProvider(LocalFormulaWidth provides lineWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                blocks.forEach { block -> MdBlockView(block, style) }
            }
        }
    }
}

/** 8dp: the gap between two blocks of a reply. */
private val BLOCK_GAP = 8.dp

/**
 * The colours one block's inline runs are drawn with.
 *
 * Passed down rather than read from `MaterialTheme` inside the token walk so that
 * the *walk* stays pure — which is what lets the JVM suite assert on the tokens —
 * and so that a bubble can supply its own answer: the prompt's `primaryContainer`
 * needs a code tint that contrasts with the fill behind it, and the answer's
 * surface needs a different one.
 *
 * [codeBackground] is `Color.Unspecified` for "no fill". The table measurement
 * uses that: a filled span in every cell of a measured grid reads as a grid of
 * errors, and the measurement only wants the widths.
 */
@Immutable
internal data class InlineStyle(
    val linkColor: Color,
    val codeBackground: Color = Color.Unspecified,
)

/** The answer's own inline colours: a code span filled against the page. */
@Composable
internal fun inlineStyle(): InlineStyle = InlineStyle(
    linkColor = MaterialTheme.colorScheme.primary,
    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
)

/**
 * The style an answer's prose is drawn in.
 *
 * `bodyMedium` with a taller line box than the theme's own 20sp. The theme has no
 * `Typography` override, so 20sp is Material 3's default for 14sp text — a 1.43
 * ratio, which is below the 1.5 that WCAG's visual-presentation criterion asks for
 * within a paragraph, and which reads as cramped for running prose on a phone.
 * 22sp is 1.57, and it is set *here* rather than in the theme because it is a
 * property of a block of agent prose and not of the application's type scale: a
 * settings row at 22sp would be a different mistake.
 */
@Composable
private fun bodyStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)

/**
 * One parsed block.
 *
 * A separate composable rather than an inline `when` so that each block keeps
 * its own memoised inline text: the emphasis spans depend only on the block's
 * own text, so a scroll does not rebuild them for every paragraph on screen.
 */
@Composable
private fun MdBlockView(block: MdBlock, style: InlineStyle) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh

    when (block) {
        is MdBlock.Paragraph -> {
            val rendered = rememberMathInline(block.text, style, bodyStyle())
            Text(rendered.text, style = bodyStyle(), inlineContent = rendered.inlineContent)
        }

        is MdBlock.Heading -> {
            val textStyle = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            val rendered = rememberMathInline(block.text, style, textStyle)
            Text(
                rendered.text,
                style = textStyle,
                fontWeight = FontWeight.SemiBold,
                inlineContent = rendered.inlineContent,
            )
        }

        is MdBlock.ListBlock -> MarkdownList(block, style)

        is MdBlock.Quote -> MarkdownQuote(block, style)

        is MdBlock.Code -> CodeBlock(block, codeBackground)

        is MdBlock.Formula -> {
            // A display formula is a *paragraph* whose whole content is one inline
            // content, and every part of that is load-bearing:
            //
            //  - **It is a `Text` with a placeholder**, the same mechanism an inline
            //    formula uses, so the transcript's `SelectionContainer` treats it like
            //    any other run of text. Drawing it as its own composable — which is what
            //    this was — leaves it outside the selection's layout, so a drag across
            //    it selects nothing and it cannot be copied. That is the report
            //    "单独成段的公式仍无法选中复制".
            //  - **The selection highlight comes with it.** A selection is drawn from
            //    the `TextLayoutResult` of the text it crosses, so a formula inside the
            //    text is highlighted by the same pass that highlights the words around
            //    it; a composable beside the text has no highlight to draw. That is the
            //    second half of the report — "行内公式和代码虽然能在选中复制的里面，但是没有被
            //    选中的高亮效果".
            //  - **`softWrap = false` and a horizontal parent**, so a formula wider than
            //    the screen is clipped at the gutter rather than broken across two
            //    lines: a wrapped formula is not the formula any more. The parent is
            //    what makes it reachable — see `ScrollableFormula`. `TextAlign.Center`
            //    inside a `fillMaxWidth` box is what centres a formula narrower than the
            //    screen, which `Arrangement.Center` used to do when this was a
            //    composable and which no longer applies.
            //
            // The delimiters are **put back**, and that is the whole of why this works
            // rather than a formality: `MdBlock.Formula` holds the body with its
            // `$$`/`\[`/`\begin{}` markers already stripped (see `displayFormula`), and the
            // inline path decides what is mathematics by looking for a delimiter. Handed the
            // bare body it sees prose, and a display formula draws as its own LaTeX source —
            // which is exactly what the first version of this did.
            //
            // The measurement is off the UI thread (`MathCache`), so this draws the
            // formula when it is ready and its own source until then. Deferring the *draw*
            // as well was tried and is gone: with the measurement already deferred it bought
            // nothing but a scrollbar that settled late.
            val rendered = rememberMathInline("$$${block.body}$$", style, bodyStyle(), display = true)
            ScrollableFormula {
                Text(
                    rendered.text,
                    style = bodyStyle(),
                    textAlign = TextAlign.Center,
                    softWrap = false,
                    inlineContent = rendered.inlineContent,
                )
            }
        }

        is MdBlock.Table -> MarkdownTable(block, style)

        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

/**
 * A run of list items.
 *
 * Every item's marker sits in the same column, which is what makes a list read as
 * a list rather than as a ragged stack of sentences. The column is a fixed
 * [LIST_MARKER_WIDTH], which is the compromise a Compose `Column` forces: the
 * honest width is the widest marker *in this list* — `10.` is wider than `1.` —
 * and measuring it would mean a `SubcomposeLayout` for a number this cheap. 26dp
 * holds `10.` and both checkbox glyphs at `bodyMedium`; a wider column pushes the
 * item's text away from its marker for no gain, and a narrower one wraps `10.` onto
 * two lines. Nested items are indented by [LIST_INDENT] per level.
 *
 * The checkbox of a task item is the marker, so a checklist lines up with the
 * bullets beside it instead of starting a column of its own.
 */
@Composable
private fun MarkdownList(block: MdBlock.ListBlock, style: InlineStyle) {
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            val marker = when {
                item.checked == true -> "\u2611"
                item.checked == false -> "\u2610"
                block.ordered -> "${block.start + index}."
                else -> "\u2022"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        // 2dp, not the 8dp between two blocks: two items of one list
                        // are one thing, and the block gap made a five-item list
                        // read as five paragraphs.
                        top = if (index == 0) 0.dp else LIST_ITEM_GAP,
                        start = (item.depth.coerceAtMost(MAX_LIST_DEPTH) * LIST_INDENT).dp,
                    ),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = marker,
                    style = bodyStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(LIST_MARKER_WIDTH),
                )
                Box(Modifier.weight(1f)) {
                    val rendered = rememberMathInline(item.text, style, bodyStyle())
                    Text(
                        text = rendered.text,
                        style = bodyStyle(),
                        textDecoration = if (item.checked == true) {
                            TextDecoration.LineThrough
                        } else {
                            null
                        },
                        color = if (item.checked == true) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.Unspecified
                        },
                        inlineContent = rendered.inlineContent,
                    )
                }
            }
        }
    }
}

/** The marker column of a list — see [MarkdownList] for why it is fixed. */
private val LIST_MARKER_WIDTH = 26.dp

/** 2dp between two items of the same list — see [MarkdownList]. */
private val LIST_ITEM_GAP = 2.dp

/** One nesting level's indent, in dp. */
private const val LIST_INDENT = 18

/** Nesting past this depth is drawn at the deepest indent rather than off the edge. */
private const val MAX_LIST_DEPTH = 5

/**
 * A block quote: a left rule and the blocks inside it.
 *
 * A left rule reads better than italicising, which loses the visual grouping when
 * several lines are quoted. `IntrinsicSize.Min` on the row is what makes the rule as
 * tall as the quoted content, however many blocks that turns out to be.
 */
@Composable
private fun MarkdownQuote(block: MdBlock.Quote, style: InlineStyle) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
        ) {
            block.blocks.forEach { inner -> MdBlockView(inner, style) }
        }
    }
}

/**
 * A display formula's line, scrollable sideways when it is wider than the transcript.
 *
 * ## Why this is a scroll box and not just a box
 *
 * A formula is a single line of notation, so it must not wrap ([`softWrap = false`
 * at the call site][MdBlockView]), and a formula wider than the screen therefore has
 * to be reachable some other way. `Modifier.horizontalScroll` is what makes it
 * reachable — "长的公式直接显示不全" is the report that it was not, and the version
 * before this one had disabled the scroll deliberately, on the argument that a drag
 * across a formula should extend the selection rather than pan it.
 *
 * That argument was **wrong about which composable owns the drag**, and the version
 * that acted on it was worse than the problem. A `horizontalScroll` only claims a
 * horizontal drag, and the transcript does not scroll horizontally, so a *vertical*
 * drag — which is how a selection is dragged across a line — was never the gesture
 * in dispute. What the disabled scroll actually bought was nothing, and what it cost
 * was every formula that does not fit.
 *
 * ## What a drag on a formula does now, and why that is the better trade
 *
 * A horizontal drag that starts on a formula pans it; a long-press and drag selects.
 * Compose resolves that the same way the platform's own text views do: the
 * long-press is claimed by the `SelectionContainer` first, and a plain drag goes to
 * the scroller. The cost is that a reader who starts a selection *exactly* on an
 * over-wide formula and drags horizontally pans it instead — and the formula is
 * inside the selection either way, so the selection they get by starting one
 * character earlier is the selection they wanted.
 */
@Composable
private fun ScrollableFormula(content: @Composable () -> Unit) {
    val state = rememberScrollState()
    val fade = MaterialTheme.colorScheme.surface
    val thumb = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(state)
            // A formula wider than the screen is panned, and **nothing used to say so**: the report
            // "当一行的公式过长时，无明显暗示能够滑动查看完整内容". Two hints now, because one is not
            // enough on a dark background: a gradient that fades the last few dp on whichever side
            // has more to show, and a pill at the bottom that reports how much of the formula is on
            // screen and where the reader is in it. The pill is drawn, not a scrollbar — there is no
            // `HorizontalScrollbar` on Android, and a real one would want the drag gestures the
            // scroller already owns.
            .drawWithContent {
                drawContent()
                val faded = EDGE_FADE.toPx()
                if (state.canScrollForward) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, fade),
                            startX = size.width - faded,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - faded, 0f),
                        size = Size(faded, size.height),
                    )
                }
                if (state.canScrollBackward) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(fade, Color.Transparent),
                            startX = 0f,
                            endX = faded,
                        ),
                        size = Size(faded, size.height),
                    )
                    }
                if (state.maxValue > 0) {
                    val track = size.width * 0.9f
                    val left = size.width * 0.05f
                    val thumbWidth = (track * (size.width / (size.width + state.maxValue)))
                        .coerceAtLeast(MIN_THUMB)
                    val at = left + (track - thumbWidth) * (state.value.toFloat() / state.maxValue)
                    drawRoundRect(
                        color = fade.copy(alpha = 0.85f),
                        topLeft = Offset(left, size.height - THUMB_HEIGHT.toPx()),
                        size = Size(track, THUMB_HEIGHT.toPx()),
                        cornerRadius = CornerRadius(THUMB_HEIGHT.toPx() / 2f),
                    )
                    drawRoundRect(
                        color = thumb.copy(alpha = 0.6f),
                        topLeft = Offset(at, size.height - THUMB_HEIGHT.toPx()),
                        size = Size(thumbWidth, THUMB_HEIGHT.toPx()),
                        cornerRadius = CornerRadius(THUMB_HEIGHT.toPx() / 2f),
                    )
                }
            },
    ) {
        content()
    }
}

/** How wide the hint's gradient is, at each end of a formula that has more to show. */
private val EDGE_FADE = 18.dp

/** The scroll indicator's thickness, and the shortest thumb it will draw. */
private val THUMB_HEIGHT = 3.dp
private const val MIN_THUMB = 24f

/**
 * Styled text as a clipboard entry.
 *
 * `LocalClipboard` deals in `ClipEntry`, which wraps the platform's `ClipData`, so a
 * plain-text copy has to build the one-entry `ClipData` the platform understands.
 * The label is what the clipboard's own UI shows for the entry; it is the app's name
 * rather than the text, because a label of the text would be the text twice.
 */
internal fun clipEntryFor(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("PiKit", text))

@Composable
private fun CodeBlock(block: MdBlock.Code, background: Color) {
    // `LocalClipboard`, not the deprecated `LocalClipboardManager`: the replacement
    // API is suspend, because the platform clipboard is a binder call, so the copy
    // needs a scope to run in. The launch is fire-and-forget on purpose — there is
    // nothing to report if the clipboard refuses, and the button's feedback is the
    // text being on the clipboard when it is pasted.
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val text = strings
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language.ifBlank { text.chat.codeBlockFallback },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // The same control as the copy button under a message — one composable, `CopyButton`,
            // because the two are the same action and were two different-looking buttons.
            CopyButton(
                onClick = { scope.launch { clipboard.setClipEntry(clipEntryFor(block.body)) } },
                contentDescription = text.chat.copyCode,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(8.dp))
                // Long lines scroll rather than wrap: wrapped code is much harder
                // to read than clipped code.
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            // Monospace and uncoloured. A highlighter used to be here and was removed
            // on request: it cannot cover every language an agent writes a fence for,
            // and a wrong colour a reader cannot tell from a right one is worse than
            // no colour at all. The fence's own name is still shown, which is the part
            // that helps — it says what the block is.
            Text(
                block.body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Renders a pipe table.
 *
 * Compose has no table widget, so this is a stack of rows with cells. The two
 * decisions here are both about a table that is wider than the screen:
 *
 *  - **The rounded outline clips its contents.** `border(…, shape)` draws the
 *    outline; it does not clip what is inside it. The header row's own fill is a
 *    rectangle, so at the top two corners it painted *past* the rounded border —
 *    two square colour blocks sticking out either side of a rounded corner,
 *    which is the "colour blocks outside the table" a reader sees first.
 *    `clip(shape)` before the border is what makes the fill stop at the corner.
 *  - **A table too wide to hold its columns scrolls sideways.** The old layout
 *    gave every column a weight derived from a character count, so a table with
 *    a handful of columns was squeezed until every cell wrapped: the rows grew to
 *    ten lines and the table stopped reading as a table. The columns are measured
 *    now. If the widths the columns actually want fit the screen the table fills
 *    it and prose wraps as before; if they do not, no amount of squeezing leaves
 *    a readable column, and the table keeps its natural width inside a
 *    horizontal scroll.
 *
 * The measurement is a `TextMeasurer` on the cells' own annotated strings rather
 * than a character count, because the width of `缓存命中` and the width of
 * `context` are not related to how many characters they have, which is exactly
 * the mistake the weights made.
 *
 * A cell is rendered by the same [renderInline] a paragraph uses — with the formula
 * measurer attached — so a formula inside a table is measured and drawn like one
 * anywhere else. It used to be `buildInline`, which flattens `$x$` to its literal
 * source: a reply about a model's dimensions routinely puts formulas in the cells of
 * exactly this table, and a reader shown `$\frac{a}{b}$` where a fraction belongs is
 * looking at a bug.
 *
 * The pixel widths are measured once and then converted, because a *share* of the
 * screen is only known inside the constraints — see [MAX_COLUMN_SHARE] for why
 * there is a ceiling at all.
 */
@Composable
private fun MarkdownTable(block: MdBlock.Table, style: InlineStyle) {
    val columns = maxOf(
        block.header.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columns == 0) return

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = MaterialTheme.typography.labelLarge
    val cellStyle = MaterialTheme.typography.bodySmall

    // The style the widths are measured in: no code fill, because a filled span in
    // every cell reads as a grid of errors and only the widths are wanted here.
    val measureStyle = InlineStyle(linkColor = style.linkColor)

    // One rendered cell per (row, column), measured with the formula renderer's
    // placeholders so a column holding a fraction is as wide as the fraction rather
    // than as wide as its source. Built *outside* the `remember` below: the maths
    // measurer is a composable, and a `remember` lambda is not.
    val headerCells = block.header.map { rememberMathInline(it, measureStyle, headerStyle, codeChips = false) }
    val rowCells = block.rows.map { row ->
        row.map { rememberMathInline(it, measureStyle, cellStyle, codeChips = false) }
    }

    // The cells' rendered text changes when a formula of theirs lands in the cache, and
    // `rememberMathInline` returns a new `MathInline` when it does — but this `remember` was
    // keyed only on the word lists, so the width it computed from a cold cache was kept.
    // Reading each cell's formula version in composition is what re-runs it: without this a
    // table keeps drawing `$\frac{1}{2}$` in a cell after the formula has been measured.
    val cellVersions = block.header.sumOf { MathCache.versionOf(it, mathSpec(headerStyle.fontSize)) } +
        block.rows.sumOf { row ->
            row.sumOf { MathCache.versionOf(it, mathSpec(cellStyle.fontSize)) }
        }

    // A table cell is typeset at the *cell's* font size, which is not the size `MarkdownText`
    // prefetched for — so its formulas have to be asked for at their own size or they are never
    // typeset at all and the cell shows its source forever. The size is per cell style, so the
    // header's formulas and the body's are two different entries rather than one.
    val headerSpec = mathSpec(headerStyle.fontSize)
    val cellSpec = mathSpec(cellStyle.fontSize)
    LaunchedEffect(block, headerSpec, cellSpec) {
        block.header.forEach { MathCache.prefetch(it, headerSpec) }
        block.rows.forEach { row -> row.forEach { MathCache.prefetch(it, cellSpec) } }
    }

    val measured = remember(block, style, headerStyle, cellStyle, headerCells, rowCells, cellVersions) {
        List(columns) { column ->
            var widest = 0
            fun measure(cell: MathInline?, textStyle: TextStyle) {
                if (cell == null) return
                val width = measurer.measure(
                    text = cell.text,
                    style = textStyle,
                    maxLines = 1,
                    placeholders = cell.placeholders,
                ).size.width
                if (width > widest) widest = width
            }
            measure(headerCells.getOrNull(column), headerStyle)
            rowCells.forEach { row -> measure(row.getOrNull(column), cellStyle) }
            widest
        }
    }

    val weights = remember(block, columns) {
        List(columns) { column ->
            val longest = (block.rows.mapNotNull { it.getOrNull(column) } + block.header.getOrNull(column).orEmpty())
                .maxOfOrNull { it.length } ?: 1
            // The floor keeps a four-letter header such as "File" from being
            // squeezed into wrapping, which a purely proportional weight does
            // whenever one column holds long paths.
            longest.coerceIn(7, 20)
        }
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(8.dp)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth
        val cap = (available * MAX_COLUMN_SHARE).coerceAtLeast(MIN_COLUMN_WIDTH)
        // What each column wants, capped: the ceiling is what keeps an unbroken
        // paragraph in a cell from making one column a thousand dp wide.
        val natural = measured.map { pixels ->
            (with(density) { pixels.toDp() } + CELL_PADDING).coerceIn(MIN_COLUMN_WIDTH, cap)
        }
        val naturalTotal = natural.fold(0.dp) { sum, width -> sum + width }
        // The screen holds the table when either the columns fit as they are, or
        // every column still clears the floor once they are all squeezed in
        // proportion. A column below that floor holds four characters and ceases
        // to be a column, which is when scrolling beats squeezing.
        val fits = naturalTotal <= available || natural.all { width ->
            width * (available / naturalTotal) >= MIN_COLUMN_WIDTH
        }

        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
        ) {
            Column(
                Modifier
                    .then(
                        if (fits) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.width(naturalTotal)
                        },
                    )
                    .clip(shape)
                    .border(1.dp, borderColor, shape),
            ) {
                TableRow(
                    cells = headerCells,
                    columns = columns,
                    widths = if (fits) null else natural,
                    weights = weights,
                    aligns = block.aligns,
                    header = true,
                )
                rowCells.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = borderColor)
                    TableRow(
                        cells = row,
                        columns = columns,
                        widths = if (fits) null else natural,
                        weights = weights,
                        aligns = block.aligns,
                        header = false,
                    )
                }
            }
        }
    }
}

/**
 * One row of a table.
 *
 * [widths] is null when the table fills the screen — the cells then take their
 * weight of the width and prose wraps — and a fixed width per column when the
 * table is wider than the screen and scrolls.
 */
@Composable
private fun TableRow(
    cells: List<MathInline>,
    columns: Int,
    widths: List<Dp>?,
    weights: List<Int>,
    aligns: List<MdAlign>,
    header: Boolean,
) {
    val textStyle = if (header) {
        MaterialTheme.typography.labelLarge
    } else {
        MaterialTheme.typography.bodySmall
    }

    Row(
        modifier = Modifier
            .then(
                if (widths == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.width(widths.fold(0.dp) { sum, width -> sum + width })
                },
            )
            .background(
                if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Unspecified,
            ),
    ) {
        for (column in 0 until columns) {
            // Memoised like every other block's text (`MdBlockView` does the same):
            // a table is rebuilt whenever the `remember(text)` in `MarkdownText`
            // misses, which is once per streamed token, and without this every cell's
            // `AnnotatedString` was rebuilt on each of those passes. The cell is the
            // *same* object the width was measured from, so the drawn text and the
            // measured text cannot drift.
            val cell = cells.getOrNull(column) ?: EMPTY_INLINE
            Text(
                text = cell.text,
                style = textStyle,
                fontWeight = if (header) FontWeight.SemiBold else null,
                textAlign = when (aligns.getOrNull(column)) {
                    MdAlign.Center -> TextAlign.Center
                    MdAlign.End -> TextAlign.End
                    else -> TextAlign.Start
                },
                inlineContent = cell.inlineContent,
                modifier = Modifier
                    .then(
                        if (widths == null) {
                            Modifier.weight(weights.getOrElse(column) { 3 }.toFloat())
                        } else {
                            Modifier.width(widths[column])
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Every inline-content placeholder this run declares, with its real size.
 *
 * A table column's width has to account for a formula, and a formula is a
 * *placeholder* in the annotated string whose size lives in the
 * `InlineTextContent` map rather than in the alternative text beside it.
 * `TextMeasurer.measure` wants the placeholders as a list, and this is how that
 * list is rebuilt from the map: `appendInlineContent(key, alternateText)` tags a
 * one-character range with [INLINE_CONTENT_TAG], and the range's `item` is the key
 * naming the content.
 */
internal val MathInline.placeholders: List<AnnotatedString.Range<Placeholder>>
    get() = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length).mapNotNull { range ->
        inlineContent[range.item]?.let { content ->
            AnnotatedString.Range(content.placeholder, range.start, range.end)
        }
    }

/**
 * The annotation tag `appendInlineContent` writes.
 *
 * Spelled out rather than reached through a helper because the helper that would
 * replace it — `resolveInlineContent` — is `internal` to
 * `androidx.compose.foundation`, and this app is not in that module. The string
 * itself is what the tag has been since inline content was introduced, and the
 * failure mode if it ever changed is a table column measured one character wide
 * rather than a crash.
 */
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** The empty cell, shared by every missing column of a ragged row. */
private val EMPTY_INLINE = MathInline(AnnotatedString(""), emptyMap())

/** A cell's own horizontal padding, both sides together. */
private val CELL_PADDING = 16.dp

/**
 * The narrowest column worth having.
 *
 * 72dp holds about nine characters of `bodySmall` — a header such as `Size` and
 * a value such as `1.2 MB`, which is a column. Below it a cell wraps every word
 * and the table reads as a paragraph of vertically stacked words.
 */
private val MIN_COLUMN_WIDTH = 72.dp

/**
 * The most of the screen one column may claim.
 *
 * Without a ceiling a single narrow column beside one holding a paragraph gets the
 * paragraph's *whole* single-line width — measured into the thousands of dp for a
 * hundred-character sentence — and a two-column table becomes a table you scroll
 * through for six screens instead of one that fits.
 *
 * Three fifths, and that number is about the *scroll* rather than the column: a
 * table wide enough to scroll shows its first column in full and the beginning of
 * the second, which is what tells a reader the thing moves sideways. Measured on
 * the emulator with a six-column table of sentences, 0.9 gave 1.1 columns per
 * screen — a table read one column at a time, with nothing on screen to say there
 * was a second — and 0.6 gives about 1.7, so the neighbouring column's text is
 * always partly visible.
 *
 * It is a share rather than a dp value for the same reason the settings rows cap
 * their value column by share: the split has to survive a narrower phone and a
 * larger font scale.
 */
private const val MAX_COLUMN_SHARE = 0.6f

// ---------------------------------------------------------------- block level

/**
 * A fence's opening line: the marker, the language, and whatever else the author
 * wrote after it.
 *
 * The language is the part that matters — it is what the block's own label shows —
 * and an info string with more than a language in it (` ```js title="a" `, which is
 * what an editor's "copy as markdown" produces) is captured and ignored rather than
 * failing to be a fence at all. A fence that fails to match is not a fence: its
 * body becomes a paragraph and its backticks become inline code, which is a much
 * worse answer than a fence with the wrong label.
 */
private val FENCE = Regex("^\\s*(```+|~~~+)\\s*(\\S*).*$")

/** The exact delimiter, used to look for the fence's own closer. */
private val FENCE_EXACT = Regex("^\\s*(```+|~~~+)\\s*$")
private val ATX_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val SETEXT_UNDERLINE = Regex("^\\s{0,3}(=+|-{2,})\\s*$")
private val BULLET = Regex("^( *)([-*+])( +)(.*)$")
private val ORDERED = Regex("^( *)(\\d{1,9})([.)])( +)(.*)$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
private val FOOTNOTE_DEFINITION = Regex("^\\s{0,3}\\[\\^([^]\\s]+)]:?\\s*(.*)$")
private val LINK_DEFINITION = Regex("^\\s{0,3}\\[([^]]+)]:\\s*(\\S+)(?:\\s+\"([^\"]*)\")?\\s*$")

/**
 * The opening of a `\begin{aligned}` and the `\end{aligned}` that closes it.
 *
 * Read with `indexOf` rather than a regex, and not for speed:
 * `Regex("^\\\\begin\\{(\\w+\\*?)}")` is accepted by the JVM and rejected by
 * Android's own ICU engine, which fails the whole class initialiser — every
 * Markdown block in the app threw `ExceptionInInitializerError` the moment a reply
 * started arriving. A device test caught it; the unit tests on the JVM could not.
 */
private const val ENVIRONMENT_OPEN = "\\begin{"

private const val ENVIRONMENT_CLOSE = "\\end{"

/** The environment a line opens, or null when it does not open one. */
private fun environmentOf(line: String): String? {
    if (!line.startsWith(ENVIRONMENT_OPEN)) return null
    val end = line.indexOf('}', ENVIRONMENT_OPEN.length)
    if (end < 0) return null
    return line.substring(ENVIRONMENT_OPEN.length, end).trim().ifEmpty { null }
}

/** A display formula read out of the message, and where to carry on from. */
private class FormulaSource(val body: String, val next: Int)

/**
 * A formula that occupies lines of its own, or null.
 *
 * Three spellings, because three are what models write: `$$…$$` (on one line or
 * fenced across several), `\[…\]`, and a `\begin{…}` environment. The environment
 * matters more than it looks — `\begin{aligned}` and `\begin{cases}` are how a
 * multi-line derivation is written, and the renderer lays those rows out itself
 * (the `aligned`, `gather`, `cases` and `matrix` environments are all supported).
 */
private fun displayFormula(lines: List<String>, index: Int): FormulaSource? {
    val line = lines[index].trim()
    return when {
        line.startsWith("$$") -> collect(lines, index, "$$", line.removePrefix("$$"))
        line.startsWith("\\[") -> collect(lines, index, "\\]", line.removePrefix("\\["))
        environmentOf(line) != null -> environment(lines, index)
        else -> null
    }
}

/**
 * Reads a formula fenced by [close] until the fence.
 *
 * An unclosed formula takes the rest of the message rather than being dropped: a
 * reply is rendered while it is still streaming, and for the second or two before
 * the closing fence arrives the formula is genuinely all there is.
 */
private fun collect(lines: List<String>, index: Int, close: String, first: String): FormulaSource {
    val closeAt = first.indexOf(close)
    if (closeAt >= 0) return FormulaSource(first.substring(0, closeAt).trim(), index + 1)

    val body = StringBuilder(first.trim())
    var next = index + 1
    while (next < lines.size) {
        val candidate = lines[next].trim()
        val end = candidate.indexOf(close)
        if (end >= 0) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(candidate.substring(0, end).trim())
            return FormulaSource(body.toString().trim(), next + 1)
        }
        if (body.isNotEmpty()) body.append('\n')
        body.append(candidate)
        next++
    }
    return FormulaSource(body.toString().trim(), next)
}

/** The body of a `\begin{env} … \end{env}`, without the markers. */
private fun environment(lines: List<String>, index: Int): FormulaSource? {
    val opening = lines[index].trim()
    val name = environmentOf(opening) ?: return null
    val closing = "$ENVIRONMENT_CLOSE$name}"

    val body = StringBuilder(opening.substringAfter('}', "").trim())
    var next = index + 1
    while (next < lines.size) {
        val candidate = lines[next].trim()
        val end = candidate.indexOf(closing)
        if (end >= 0) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(candidate.substring(0, end).trim())
            return FormulaSource(body.toString().trim(), next + 1)
        }
        if (body.isNotEmpty()) body.append('\n')
        body.append(candidate)
        next++
    }
    return FormulaSource(body.toString().trim(), next)
}

/**
 * The `|---|:--:|` line under a table header.
 *
 * Deliberately permissive about the outer pipes because agents emit both
 * `| a | b |` and `a | b`.
 */
private val TABLE_DELIMITER =
    Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

/**
 * Everything a reply's block structure is read from.
 *
 * ## What is a block
 *
 * Fenced code (```` ``` ```` and `~~~`), ATX headings (`#` … `######`), setext
 * headings (`===`/`---` under a line), bullet lists (`-`/`*`/`+`), ordered lists
 * (`1.`/`1)`), task items (`- [x]`), block quotes (`>`, nested, holding further
 * blocks), thematic breaks (`---`, `***`, `___`), pipe tables, display formulas
 * and paragraphs.
 *
 * Two things are read *out* of the stream rather than drawn:
 *
 * - **A link reference definition** (`[label]: url "title"`). Its line is removed
 *   and its url is applied to every `[label]`, `[label][]` and `[label][id]` in the
 *   text below it, by [applyReferenceLinks] — see that function for why the
 *   substitution happens at parse time and not at draw time.
 * - **A footnote definition** (`[^label]: …`). The definition is removed, and no
 *   footnote list is drawn: a reply cites a paper, it does not footnote it, and the
 *   `[^1]` markers seen in practice are references the author expected to resolve to
 *   something this app cannot fetch. The *marker* is therefore left as ordinary
 *   text, which is the honest degradation — it is what the model wrote.
 *
 * ## What is not, on purpose
 *
 * - **Indented code blocks.** Four leading spaces are how a model indents a
 *   *nested* list, which is a far more common thing in agent output than an
 *   indented code block; treating the indent as code turned a nested list into a
 *   grey box of its own source.
 * - **Raw HTML.** It is drawn as the text it is. Nothing here renders HTML, and
 *   silently swallowing a `<div>` would hide that.
 * - **List items holding blocks.** An item's text is a string with a `depth`, so an
 *   item cannot hold a code block or a table. Recursing would make `depth` a
 *   property of the parser rather than of the item, and an item holding a table is
 *   not something an agent writes.
 *
 * ## Soft breaks and CJK
 *
 * A line break inside a paragraph is a *soft* break: CommonMark renders it as a
 * space. For Chinese and Japanese that space is wrong — those scripts have no
 * word spaces, so a body wrapped to a phone's width came out with a visible gap
 * at every wrap point ("已 安装的软件包", "折叠成 你消息下方"), which reads as a
 * typo rather than as wrapping. The space is therefore put back only when
 * [needsSpaceBetween] says the break crossed a real word boundary.
 *
 * A *hard* break — two trailing spaces or a trailing backslash — is kept as a `\n`
 * in the paragraph's text, because it is the author saying "this break is the
 * point": it is how a model writes an address or a list of steps inside a
 * paragraph, and `Text` turns the `\n` into the line it asked for.
 *
 * ## Recursion
 *
 * A block quote's contents are parsed by this same function with `>` stripped one
 * level, so a quoted list, a quoted code block and a quote inside a quote all work
 * without a second implementation. A quote does **not** re-run the definition lift,
 * which is deliberate: a `[a]: b` inside a quote is a use of the reference, not a
 * second definition of it.
 */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val definitions = HashMap<String, String>()

    val lines = text.split('\n').map { line ->
        LINK_DEFINITION.matchEntire(line)?.let { match ->
            definitions[match.groupValues[1].lowercase()] = match.groupValues[2]
            ""
        } ?: FOOTNOTE_DEFINITION.matchEntire(line)?.let { "" } ?: line
    }

    val blocks = parseBlocks(lines, definitions)
    return if (definitions.isEmpty()) blocks else applyReferenceLinks(blocks, definitions)
}

/**
 * Rewrites every link reference in [blocks] into the inline spelling it means.
 *
 * `[docs]`, `[docs][]` and `[see this][docs]` all become `[see this](url)`, using
 * the definitions that were lifted out of the stream. The substitution happens
 * **here**, at parse time, rather than in the token walk, and the reason is a
 * property of the block types rather than a preference: a `MdBlock` carries its
 * text and nothing else, so a definition map handed to [tokenizeInline] would have
 * to be threaded through `renderInline`, `rememberMathInline` and every caller of
 * both — for a construct that is resolved once, before any of them run.
 *
 * A reference with no definition is left exactly as it was written. That is the
 * whole difference between a reference and a bracket: `[1]` in prose about a list
 * is a `[1]`, and a renderer that turned it into something else would be inventing
 * a link the author did not write.
 *
 * The one thing this gets wrong, and it is a known limit rather than a bug: a
 * `[label]` inside an inline code span is rewritten too, because finding code spans
 * is the tokeniser's job and this runs before it. `docs/[label]` in a sentence
 * about reference links therefore becomes a link. The workaround is a definition
 * that does not exist, which is also what CommonMark requires of an author writing
 * a literal reference.
 */
private fun applyReferenceLinks(
    blocks: List<MdBlock>,
    definitions: Map<String, String>,
): List<MdBlock> = blocks.map { block ->
    when (block) {
        is MdBlock.Paragraph -> block.copy(text = resolveReferences(block.text, definitions))
        is MdBlock.Heading -> block.copy(text = resolveReferences(block.text, definitions))
        is MdBlock.ListBlock -> block.copy(
            items = block.items.map { it.copy(text = resolveReferences(it.text, definitions)) },
        )

        is MdBlock.Quote -> block.copy(blocks = applyReferenceLinks(block.blocks, definitions))
        is MdBlock.Table -> block.copy(
            header = block.header.map { resolveReferences(it, definitions) },
            rows = block.rows.map { row -> row.map { resolveReferences(it, definitions) } },
        )

        is MdBlock.Code, is MdBlock.Formula, MdBlock.Rule -> block
    }
}

/**
 * One line's reference links, as inline links.
 *
 * Read with indexes rather than a regular expression for the same reason the
 * tokeniser is: a `]` inside a label and a definition whose id contains a bracket
 * are both legal, and a pattern that tries to describe them is a pattern that is
 * wrong about one of them.
 */
private fun resolveReferences(line: String, definitions: Map<String, String>): String {
    var index = 0
    val out = StringBuilder(line.length)
    while (index < line.length) {
        val open = line.indexOf('[', index)
        if (open < 0) {
            out.append(line, index, line.length)
            break
        }
        out.append(line, index, open)
        // `![alt]` is an image and `\[` is an escape: neither is a reference.
        if (open > 0 && (line[open - 1] == '!' || line[open - 1] == '\\')) {
            out.append('[')
            index = open + 1
            continue
        }
        val close = matchingBracket(line, open, line.length)
        if (close < 0) {
            out.append(line, open, line.length)
            break
        }
        val label = line.substring(open + 1, close)
        val next = line.getOrNull(close + 1)
        // `[a](b)` and `[a][b]` are already links; `[a][b]` is a reference only when
        // `b` names a definition.
        val resolved = when (next) {
            '(' -> null
            '[' -> {
                val end = line.indexOf(']', close + 2)
                if (end < 0) {
                    null
                } else {
                    val id = line.substring(close + 2, end).ifEmpty { label }
                    definitions[id.lowercase()]?.let { url -> url to end + 1 }
                }
            }

            else -> definitions[label.lowercase()]?.let { url -> url to close + 1 }
        }
        if (resolved == null) {
            out.append(line, open, close + 1)
            index = close + 1
        } else {
            out.append('[').append(label).append("](").append(resolved.first).append(')')
            index = resolved.second
        }
    }
    return out.toString()
}

/**
 * [parseMarkdown] with the link definitions lifted out.
 *
 * Split out because a block quote's contents are parsed by the same rules and
 * against the same definitions, but must not re-run the lift over text it has
 * already been run on.
 */
private fun parseBlocks(lines: List<String>, definitions: Map<String, String>): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()

    /**
     * Whether the last accumulated line ended with a hard break.
     *
     * The break belongs to the *line*, and it is therefore deferred until the next
     * line arrives rather than written as a `\n` straight away. Writing it straight
     * away would leave a trailing newline at the end of a paragraph whose last line
     * was broken, and the flag costs nothing.
     */
    var hardBreak = false

    fun flushParagraph() {
        val text = paragraph.toString().trimEnd('\n')
        if (text.isNotBlank()) blocks += MdBlock.Paragraph(text)
        paragraph.setLength(0)
        hardBreak = false
    }

    /**
     * Adds one source line to the paragraph being accumulated.
     *
     * The newline is part of the paragraph's own text rather than a block of its
     * own: `Text` is what turns it into the line the author asked for, and there is
     * no block that means "a line break" — a `Paragraph` holding one is the same
     * picture and one fewer case for the renderer.
     */
    fun appendWrapped(line: String) {
        // `\` is a hard break; a trailing `\` is not part of the text, which is what
        // makes a bare backslash the whole of a break.
        val broken = line.endsWith("  ") || line.endsWith("\\")
        val body = line.trimEnd().let { if (it.endsWith("\\")) it.dropLast(1).trimEnd() else it }
        if (body.isEmpty() && paragraph.isEmpty()) {
            hardBreak = hardBreak || broken
            return
        }
        if (paragraph.isNotEmpty()) {
            when {
                hardBreak -> paragraph.append('\n')
                paragraph.last() == '\n' -> Unit
                needsSpaceBetween(paragraph, body) -> paragraph.append(' ')
            }
        }
        paragraph.append(body)
        hardBreak = broken
    }

    /**
     * Continues the list item above with a wrapped line.
     *
     * Markdown lets a list item's text run onto following lines without
     * indenting them, and both agent output and this app's own manual are
     * written that way. Treating such a line as a fresh paragraph flushed it to
     * the left margin and the item appeared to break in half.
     *
     * It never applies while a paragraph is open, and that check is the whole
     * reason this is not three lines long: after a list, a blank line and a new
     * paragraph, the *last block* is still the list, so every wrapped line of
     * that paragraph was appended to the item above — and the paragraph kept only
     * its first line. Measured on this app's own manual: section 1 ended with the
     * sentence "The first launch unpacks about 200 MB and asks once about file
     * access…" split across item 5 and the paragraph after it.
     */
    fun continueList(line: String): Boolean {
        val body = line.trim()
        if (body.isEmpty()) return false
        if (paragraph.isNotEmpty()) return false
        val list = blocks.lastOrNull() as? MdBlock.ListBlock ?: return false
        if (list.items.isEmpty()) return false
        val last = list.items.last()
        blocks[blocks.lastIndex] = list.copy(
            items = list.items.dropLast(1) + last.copy(text = joinWrapped(last.text, body)),
        )
        return true
    }

    var index = 0
    // A blank line ends the item, so a paragraph after a list stays a paragraph.
    var previousLineBlank = true
    while (index < lines.size) {
        val line = lines[index]

        val fence = FENCE.find(line)
        if (fence != null) {
            flushParagraph()
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2]
            val body = StringBuilder()
            index++
            while (index < lines.size) {
                val candidate = lines[index]
                val closing = FENCE_EXACT.find(candidate)
                // A fence closes only on its own kind and at least as long.
                if (closing != null && closing.groupValues[1][0] == marker[0] &&
                    closing.groupValues[1].length >= marker.length
                ) {
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(candidate)
                index++
            }
            blocks += MdBlock.Code(language, body.toString())
            index++
            continue
        }

        // A display formula. Checked before the table because a formula is the
        // stronger claim: `$$…$$` is mathematics even when the line below it is a
        // row of `---` and would otherwise be read as a table's delimiter.
        val formula = displayFormula(lines, index)
        if (formula != null) {
            flushParagraph()
            blocks += MdBlock.Formula(formula.body)
            index = formula.next
            continue
        }

        // A table is only a table if a delimiter row follows the header, which
        // is what keeps an ordinary sentence containing a pipe from being
        // mistaken for one.
        if (line.contains('|') && index + 1 < lines.size &&
            TABLE_DELIMITER.matches(lines[index + 1]) &&
            !lines[index + 1].isBlank()
        ) {
            flushParagraph()
            val header = splitTableRow(line)
            val aligns = splitTableRow(lines[index + 1]).map { cell ->
                val left = cell.startsWith(":")
                val right = cell.endsWith(":")
                when {
                    left && right -> MdAlign.Center
                    right -> MdAlign.End
                    else -> MdAlign.Start
                }
            }
            val rows = ArrayList<List<String>>()
            index += 2
            while (index < lines.size) {
                val row = lines[index]
                if (row.isBlank() || !row.contains('|')) break
                rows += splitTableRow(row)
                index++
            }
            blocks += MdBlock.Table(header, rows, aligns)
            continue
        }

        val bullet = BULLET.find(line)
        val ordered = ORDERED.find(line)
        when {
            line.isBlank() -> {
                flushParagraph()
            }

            // A quote gathers every following `>` line — blank ones included, as
            // long as the `>` is still there — and parses what is inside it.
            line.trimStart().startsWith(">") -> {
                flushParagraph()
                val quoted = ArrayList<String>()
                while (index < lines.size) {
                    val candidate = lines[index]
                    val stripped = candidate.trimStart()
                    if (stripped == ">") {
                        quoted += ""
                    } else if (stripped.startsWith(">")) {
                        quoted += stripped.drop(1).removePrefix(" ")
                    } else if (candidate.isBlank() && index + 1 < lines.size &&
                        lines[index + 1].trimStart().startsWith(">")
                    ) {
                        // A lazy blank line between two quoted paragraphs belongs
                        // to the quote, which is what makes a two-paragraph quote
                        // one quote rather than two.
                        quoted += ""
                    } else {
                        break
                    }
                    index++
                }
                blocks += MdBlock.Quote(parseBlocks(quoted, definitions))
                continue
            }

            // Setext, and it is checked *before* the thematic break on purpose: an
            // `===` or `---` line directly under a paragraph is that paragraph's
            // underline, and CommonMark gives the heading precedence over the rule.
            // The order matters for `Title\n---`, which is a second-level heading
            // and not a paragraph followed by a rule.
            paragraph.isNotEmpty() && !paragraph.contains('\n') &&
                SETEXT_UNDERLINE.matches(line) -> {
                val level = if (line.trim().startsWith("=")) 1 else 2
                blocks += MdBlock.Heading(level, paragraph.toString().trim())
                paragraph.setLength(0)
            }

            RULE.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            ATX_HEADING.matches(line) -> {
                flushParagraph()
                val match = ATX_HEADING.find(line)!!
                blocks += MdBlock.Heading(
                    match.groupValues[1].length,
                    match.groupValues[2].trim(),
                )
            }

            bullet != null -> {
                flushParagraph()
                blocks += MdBlock.ListBlock(
                    items = listOf(itemOf(bullet.groupValues[4].trim(), indentOf(line))),
                    ordered = false,
                )
            }

            ordered != null -> {
                flushParagraph()
                blocks += MdBlock.ListBlock(
                    items = listOf(itemOf(ordered.groupValues[5].trim(), indentOf(line))),
                    ordered = true,
                    start = ordered.groupValues[2].toIntOrNull() ?: 1,
                )
            }

            else -> {
                if (!previousLineBlank && continueList(line)) {
                    // Appended to the item above.
                } else {
                    appendWrapped(line)
                }
            }
        }
        previousLineBlank = line.isBlank()
        index++
    }
    flushParagraph()
    return mergeListRuns(blocks)
}

/**
 * One list item, with its checkbox — if it has one — read out of its text.
 *
 * The checkbox is stripped here rather than at the renderer because the marker is
 * the item's *kind*: `- [x] done` and `- done` are different items, and a renderer
 * that had to look inside the text to find out which would be a second parser.
 */
private fun itemOf(body: String, depth: Int): MdBlock.ListBlock.Item {
    val task = TASK.find(body)
    return if (task != null) {
        MdBlock.ListBlock.Item(
            text = task.groupValues[2].trim(),
            depth = depth,
            checked = task.groupValues[1] != " ",
        )
    } else {
        MdBlock.ListBlock.Item(text = body, depth = depth)
    }
}

/**
 * How deep a list item sits.
 *
 * Two spaces per level, which is what a model writes and what this app's own manual
 * is wrapped to. Four spaces is the CommonMark rule for a *nested item under a
 * bullet*, and it is also the rule for an indented code block — the two cannot both
 * be honoured by a renderer that does not track the enclosing list's marker width,
 * and the nested list is what actually appears in agent output. A tab counts as
 * four columns because that is what a tab stop is.
 */
private fun indentOf(line: String): Int {
    var columns = 0
    for (character in line) {
        if (character == ' ') columns++
        else if (character == '\t') columns += 4
        else break
    }
    return columns / 2
}

/**
 * Joins the adjacent list blocks the loop above emitted one item at a time.
 *
 * A run is one block because the spacing *inside* it is not the spacing between two
 * blocks — see [MdBlock.ListBlock]. Two runs separated by a blank line stay two
 * blocks, which is what makes a loose list look loose. A bullet run and an ordered
 * run are never merged, because their markers are not the same kind of thing.
 */
private fun mergeListRuns(blocks: List<MdBlock>): List<MdBlock> {
    val merged = ArrayList<MdBlock>(blocks.size)
    for (block in blocks) {
        val previous = merged.lastOrNull()
        if (block is MdBlock.ListBlock && previous is MdBlock.ListBlock &&
            previous.ordered == block.ordered
        ) {
            merged[merged.lastIndex] = previous.copy(items = previous.items + block.items)
        } else {
            merged += block
        }
    }
    return merged
}

/**
 * Whether a word space belongs at a soft line break between [before] and [after].
 *
 * False only between two CJK glyphs — the scripts that have no word spaces. The
 * ranges are the ones `tools/reflow-manual.py` and `tools/check-locales.py` use for
 * the same question, so the manual's own fixed-point check and what the reader sees
 * cannot disagree: CJK radicals through unified ideographs and kana (`2E80–9FFF`),
 * compatibility ideographs (`F900–FAFF`) and fullwidth forms including CJK
 * punctuation (`FF00–FFEF`).
 *
 * Inline markers (`**`, `` ` ``, `~~`) carry no glyph, so they are looked *through*:
 * a wrap that lands between `设置` and `**。` — or between `权限。` and `**拒绝` —
 * crossed no word boundary at all, and putting a space there drew one inside the
 * emphasis run or between the bold word and the full stop before it. Both spellings
 * were in this app's own manual until this rule was added.
 */
internal fun needsSpaceBetween(before: CharSequence, after: CharSequence): Boolean {
    var left = before.length - 1
    while (left >= 0 && before[left] in INLINE_MARKERS) left--
    var right = 0
    while (right < after.length && after[right] in INLINE_MARKERS) right++
    if (left < 0 || right >= after.length) return true
    // CJK punctuation takes no space on either side, whatever is beside it: a break
    // after `、` or before `。` drew a gap in the middle of an enumeration
    // (`` `ESC`、`TAB`、 `C-C` ``) and before a full stop, which is never what the
    // author wrote. Latin punctuation is left alone — English puts a space after a
    // full stop, and this rule must not eat it.
    if (isCjkPunctuation(before[left]) || isCjkPunctuation(after[right])) return false
    return !(isCjkLike(before[left]) && isCjkLike(after[right]))
}

/** One glyph of the CJK writing system, or its fullwidth punctuation. */
internal fun isCjkLike(c: Char): Boolean =
    c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

/**
 * CJK punctuation: the marks that never take a space around them.
 *
 * The fullwidth block is listed by the ranges that are punctuation rather than
 * letters or digits, because `Ａ` and `１` are fullwidth *words* and are already
 * covered by [isCjkLike].
 */
internal fun isCjkPunctuation(c: Char): Boolean = when (c.code) {
    0x3000, 0x3001, 0x3002 -> true
    in 0x3008..0x3011 -> true
    in 0x3014..0x301F -> true
    0x2014, 0x2026 -> true
    in 0xFF01..0xFF0F -> true
    in 0xFF1A..0xFF20 -> true
    in 0xFF3B..0xFF40 -> true
    in 0xFF5B..0xFF65 -> true
    else -> false
}

/** Characters that render as nothing: markdown's inline delimiters. */
private const val INLINE_MARKERS = "*`~"

/** [first] and [second] with the space a soft break removed put back, or not. */
internal fun joinWrapped(first: String, second: String): String {
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first
    return if (needsSpaceBetween(first, second)) "$first $second" else first + second
}

/**
 * Splits one table row into cells.
 *
 * `\|` is an escaped pipe and stays inside the cell rather than dividing it,
 * which matters because shell pipelines are common in agent output.
 */
internal fun splitTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)

    val cells = ArrayList<String>()
    val current = StringBuilder()
    var index = 0
    while (index < body.length) {
        val char = body[index]
        if (char == '\\' && index + 1 < body.length && body[index + 1] == '|') {
            current.append('|')
            index += 2
            continue
        }
        if (char == '|') {
            cells += current.toString().trim()
            current.setLength(0)
            index++
            continue
        }
        current.append(char)
        index++
    }
    cells += current.toString().trim()
    return cells
}

// --------------------------------------------------------------- inline level

/**
 * One piece of an inline run, as the tokeniser found it.
 *
 * A tree rather than a flat list, because emphasis nests: `**a *b* c**` is a bold
 * run holding a literal and an italic run, and a flat list of `(offset, style)`
 * cannot express that without a stack at draw time. The delimiters are **gone** at
 * this point — what is here is what is drawn.
 */
internal sealed interface InlineToken {

    /** Literal text, escapes already resolved. */
    data class Text(val text: String) : InlineToken

    /** A run drawn in [style], holding its own tokens. */
    data class Styled(val style: SpanStyle, val children: List<InlineToken>) : InlineToken

    /** `` `code` ``: monospace, and the only run whose spaces are the author's. */
    data class Code(val text: String) : InlineToken

    /** `[label](url)`, or a reference spelling that resolved. */
    data class Link(val url: String, val children: List<InlineToken>) : InlineToken

    /**
     * A formula, with the delimiters still on.
     *
     * [source] is the *whole* span including its delimiters, and it is what the
     * builder declares as the placeholder's alternative text. That is not
     * cosmetic: `TextMeasurer` is handed the same annotated string, so the
     * placeholder's alternative text is what gives it a width when the formula is
     * measured as part of a table column. The renderer is handed [unescapeMath] of
     * this — its own delimiters are Markdown, not mathematics.
     */
    data class Math(val source: String) : InlineToken
}

/**
 * The inline tokeniser: escapes, code spans, links, emphasis, strikethrough and
 * mathematics, in one forward pass.
 *
 * ## The rules
 *
 * - **An escape.** A backslash in front of one of [ESCAPABLE] draws that character
 *   and nothing else; in front of anything else it is part of the text, so
 *   `C:\Users` keeps its backslash while `\$5` loses it. A backslash at the end of a
 *   line is a **hard break**, and is emitted as a newline.
 * - **A code span.** Runs of backticks, ending at a run of the same length — so
 *   `` `a ` b` `` is `a ` b`, and a single unmatched backtick is literal. One
 *   leading and trailing space is stripped when there is other content, which is
 *   what makes `` ` ` `` a space rather than nothing. The contents are *not*
 *   parsed further, so `` `**a**` `` is the literal `**a**`.
 * - **Emphasis.** `**` and `__` are strong, `*` and `_` are emphasis, `~~` is
 *   strikethrough. The opening delimiter must not be followed by a space and the
 *   closing one must not be preceded by one; without both rules `2 * 3 * 4` is an
 *   italic `3`. A run with no closer is literal text. Emphasis nests, and — the
 *   point of doing this in one pass — so does an emphasis that wraps a formula.
 * - **Links.** `[label](url "title")`, and the reference spellings — though those
 *   are rewritten to the inline spelling by [applyReferenceLinks] before this walk
 *   sees them, so what arrives here is one form. `<url>` and `<mailto:…>` are
 *   autolinks. A bare `https://…` in running text is linkified, because an agent
 *   emitting a url means it to be tappable and `[label](url)` is not how it writes
 *   one.
 * - **An image.** `![alt](url)` is drawn as its alternative text with a picture
 *   mark. Nothing here fetches anything, and the alternative text is exactly what
 *   the author wrote for a reader who cannot see the picture.
 * - **Mathematics.** `$…$`, `\(…\)` and an inline `$$…$$`. The rules that keep a
 *   price out of the renderer are [inlineMathEnd]'s.
 *
 * What is deliberately absent: HTML entities (`&amp;` is drawn as written), inline
 * HTML, hard breaks written as `<br>`, and the `[^note]` footnote marker, which is
 * left as text because the definition it would point at was dropped — see
 * [parseMarkdown].
 *
 * The walk is one pass with no backtracking and no regex, so a pathological line
 * costs its own length and cannot recurse. It is also the reason an emphasis may
 * wrap a formula: the formula is a token inside the emphasis run rather than a cut
 * in the string.
 */
internal fun tokenizeInline(
    text: String,
    definitions: Map<String, String> = emptyMap(),
): List<InlineToken> = tokenize(text, 0, text.length, definitions, 0)

/** How deep emphasis may nest before the walk stops looking for more. */
private const val MAX_INLINE_DEPTH = 12

private fun tokenize(
    text: String,
    from: Int,
    to: Int,
    definitions: Map<String, String>,
    depth: Int,
): List<InlineToken> {
    val tokens = ArrayList<InlineToken>()
    val literal = StringBuilder()

    fun flush() {
        if (literal.isNotEmpty()) {
            tokens += InlineToken.Text(literal.toString())
            literal.setLength(0)
        }
    }

    var index = from
    while (index < to) {
        val char = text[index]

        // ---- escape, `\(` and `\[` ----------------------------------------
        if (char == '\\') {
            val next = text.getOrNull(index + 1)
            // A formula on its own line was already taken by `displayFormula`, so what reaches
            // here is one written *inside* a sentence: "由 \[E = mc^{2}\] 可知". It is drawn inline
            // — the line it is written on is the line it belongs to — because the alternative is
            // what this did before, which was to print the brackets and the backslashes.
            val close = when (next) {
                '(' -> "\\)"
                '[' -> "\\]"
                else -> null
            }
            if (close != null && depth < MAX_INLINE_DEPTH) {
                val end = text.indexOf(close, index + 2)
                if (end in (index + 2) until to) {
                    flush()
                    tokens += InlineToken.Math(text.substring(index, end + 2))
                    index = end + 2
                    continue
                }
            }
            when {
                next == null || index + 1 >= to -> {
                    literal.append('\\')
                    index++
                }
                // A backslash at the end of the line is a hard break, not a
                // character: that is the whole of its meaning there.
                next == '\n' -> {
                    literal.append('\n')
                    index += 2
                }
                next in ESCAPABLE -> {
                    literal.append(next)
                    index += 2
                }
                else -> {
                    literal.append('\\')
                    index++
                }
            }
            continue
        }

        // ---- code span -----------------------------------------------------
        if (char == '`') {
            var run = 0
            while (index + run < to && text[index + run] == '`') run++
            val fence = "`".repeat(run)
            val close = text.indexOf(fence, index + run)
            if (close < 0 || close + run > to) {
                literal.append(text, index, index + run)
                index += run
            } else {
                flush()
                var body = text.substring(index + run, close)
                // One space at each end is stripped, and only when there is
                // something other than spaces inside: `` ` ` `` is a space.
                if (body.length > 2 && body.startsWith(" ") && body.endsWith(" ") &&
                    body.any { it != ' ' }
                ) {
                    body = body.substring(1, body.length - 1)
                }
                tokens += InlineToken.Code(body)
                index = close + run
            }
            continue
        }

        // ---- mathematics ---------------------------------------------------
        if (char == '$' && depth < MAX_INLINE_DEPTH) {
            val span = dollarMathAt(text, index, to)
            if (span != null) {
                flush()
                tokens += InlineToken.Math(span.first)
                index = span.second
                continue
            }
        }

        // ---- image, drawn as its alternative text --------------------------
        if (char == '!' && text.getOrNull(index + 1) == '[') {
            val label = matchingBracket(text, index + 1, to)
            if (label > index + 1) {
                val destination = destinationAfter(text, label + 1, to)
                if (destination != null) {
                    flush()
                    // No image is fetched — this app draws no remote content — so
                    // what is left is the alternative text, which is exactly what
                    // the author wrote for a reader who cannot see the picture.
                    tokens += InlineToken.Text("\uD83D\uDDBC " + text.substring(index + 2, label))
                    index = destination.second
                    continue
                }
            }
        }

        // ---- link ----------------------------------------------------------
        if (char == '[') {
            val label = matchingBracket(text, index, to)
            if (label > index) {
                val resolved = resolveLink(text, index, label, to, definitions)
                if (resolved != null) {
                    flush()
                    tokens += InlineToken.Link(
                        resolved.first,
                        tokenize(text, index + 1, label, definitions, depth + 1),
                    )
                    index = resolved.second
                    continue
                }
            }
        }

        // ---- autolink ------------------------------------------------------
        if (char == '<') {
            val close = text.indexOf('>', index + 1)
            if (close in (index + 1) until to) {
                val body = text.substring(index + 1, close)
                val url = autolinkUrl(body)
                if (url != null) {
                    flush()
                    tokens += InlineToken.Link(
                        url,
                        listOf(InlineToken.Text(body.removePrefix("mailto:"))),
                    )
                    index = close + 1
                    continue
                }
            }
        }

        // ---- strikethrough --------------------------------------------------
        if (char == '~' && text.startsWith("~~", index) && depth < MAX_INLINE_DEPTH) {
            val end = closingRun(text, index + 2, to, "~~")
            if (end > 0) {
                flush()
                tokens += InlineToken.Styled(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    tokenize(text, index + 2, end, definitions, depth + 1),
                )
                index = end + 2
                continue
            }
        }

        // ---- emphasis --------------------------------------------------------
        if ((char == '*' || char == '_') && depth < MAX_INLINE_DEPTH) {
            val strong = text.startsWith("$char$char", index)
            val open = index + if (strong) 2 else 1
            val closer = if (strong) "$char$char" else char.toString()
            // The opening delimiter must be followed by a non-space, and the
            // closing one preceded by one: without both rules `2 * 3 * 4` is an
            // italic `3`.
            if (open < to && !text[open].isWhitespace()) {
                val end = closingRun(text, open, to, closer)
                if (end > open) {
                    flush()
                    tokens += InlineToken.Styled(
                        if (strong) {
                            SpanStyle(fontWeight = FontWeight.Bold)
                        } else {
                            SpanStyle(fontStyle = FontStyle.Italic)
                        },
                        tokenize(text, open, end, definitions, depth + 1),
                    )
                    index = end + closer.length
                    continue
                }
            }
        }

        // ---- a bare url -------------------------------------------------------
        if (char == 'h' && depth < MAX_INLINE_DEPTH) {
            val url = bareUrlAt(text, index, to)
            if (url != null) {
                flush()
                tokens += InlineToken.Link(url.first, listOf(InlineToken.Text(url.first)))
                index = url.second
                continue
            }
        }

        // ---- a hard break written as two spaces at the end of a line --------
        if (char == '\n') {
            var spaces = 0
            var back = literal.length - 1
            while (back >= 0 && literal[back] == ' ') {
                spaces++
                back--
            }
            if (spaces >= 2) {
                literal.setLength(literal.length - spaces)
                literal.append('\n')
            } else {
                literal.append(' ')
            }
            index++
            continue
        }

        literal.append(char)
        index++
    }
    flush()
    return tokens
}

/**
 * The `$…$` formula opening at [start], as `(source, endExclusive)`, or null.
 *
 * A `$` that does not open a formula is left to the caller as literal text, which
 * is what keeps a price a price — see [inlineMathEnd].
 */
private fun dollarMathAt(text: String, start: Int, to: Int): Pair<String, Int>? {
    if (text.startsWith("$$", start)) {
        val end = findUnescaped(text, "$$", start + 2, to)
        return if (end in (start + 2) until to) {
            text.substring(start, end + 2) to end + 2
        } else {
            null
        }
    }
    val end = inlineMathEnd(text, start)
    return if (end in (start + 1) until to) {
        text.substring(start, end + 1) to end + 1
    } else {
        null
    }
}

/**
 * Where the `$$…$$` opening at [start] ends, or -1.
 *
 * Read with [isEscaped] rather than by skipping `\\` pairs, because the caller has
 * already decided this is a `$$` and only needs to know where its twin is; a
 * `\$$` is a literal dollar followed by a delimiter rather than three delimiters.
 */
private fun findUnescaped(text: String, target: String, from: Int, to: Int): Int {
    var index = from
    while (index <= to - target.length) {
        if (text.startsWith(target, index) && !isEscaped(text, index)) return index
        index++
    }
    return -1
}

/** Whether the character at [index] is preceded by an odd number of backslashes. */
private fun isEscaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        backslashes++
        cursor--
    }
    return backslashes % 2 == 1
}

/**
 * Where the copy of [target] that closes a run opened at [from] is, or -1.
 *
 * "Closes" means the run is preceded by a non-space and is not escaped. The search
 * is forward from [from] and stops at the end of the text, so a run with no closer
 * leaves the delimiter as literal text rather than swallowing the rest of the
 * reply.
 */
private fun closingRun(text: String, from: Int, to: Int, target: String): Int {
    var index = from
    while (index <= to - target.length) {
        if (text.startsWith(target, index) && index > 0 && !text[index - 1].isWhitespace() &&
            !isEscaped(text, index)
        ) {
            return index
        }
        index++
    }
    return -1
}

/** The index of the `]` matching the `[` at [open], honouring nesting, or -1. */
private fun matchingBracket(text: String, open: Int, to: Int): Int {
    var depth = 0
    var index = open
    while (index < to) {
        when {
            text.startsWith("\\[", index) || text.startsWith("\\]", index) -> index += 2
            text[index] == '[' -> {
                depth++
                index++
            }
            text[index] == ']' -> {
                depth--
                if (depth == 0) return index
                index++
            }
            else -> index++
        }
    }
    return -1
}

/**
 * The `(url "title")` after a `]` at [at], or null.
 *
 * The angle-bracket spelling `<url>` is accepted because that is how a url holding
 * a space or a parenthesis has to be written.
 */
private fun destinationAfter(text: String, at: Int, to: Int): Pair<String, Int>? {
    if (at >= to || text[at] != '(') return null
    var index = at + 1
    while (index < to && text[index] == ' ') index++
    val url: String
    if (index < to && text[index] == '<') {
        val close = text.indexOf('>', index + 1)
        if (close < 0 || close >= to) return null
        url = text.substring(index + 1, close)
        index = close + 1
    } else {
        var depth = 0
        val start = index
        var end = -1
        while (index < to) {
            val char = text[index]
            if (char == '\\') {
                index += 2
                continue
            }
            if (char == '(') depth++
            if (char == ')') {
                if (depth == 0) {
                    end = index
                    break
                }
                depth--
            }
            if (char.isWhitespace()) {
                end = index
                break
            }
            index++
        }
        if (end < 0) return null
        url = text.substring(start, end)
        index = end
    }
    while (index < to && text[index] == ' ') index++
    if (index < to && (text[index] == '"' || text[index] == '\'')) {
        val quote = text[index]
        val close = text.indexOf(quote, index + 1)
        if (close > 0 && close < to) index = close + 1
    }
    while (index < to && text[index] == ' ') index++
    if (index >= to || text[index] != ')') return null
    return url to index + 1
}

/**
 * A link at `[` [open] with its `]` at [close], resolved to a url.
 *
 * Four spellings, in this order: `[label](url)`, `[label][id]`, `[label][]` and a
 * bare `[label]` that matches a definition by its own text. A `[label]` with no
 * definition is left as text — that is the whole difference between a reference and
 * a bracket.
 */
private fun resolveLink(
    text: String,
    open: Int,
    close: Int,
    to: Int,
    definitions: Map<String, String>,
): Pair<String, Int>? {
    destinationAfter(text, close + 1, to)?.let { return it }

    if (close + 1 < to && text[close + 1] == '[') {
        val end = text.indexOf(']', close + 2)
        if (end < 0 || end >= to) return null
        val id = text.substring(close + 2, end).ifEmpty { text.substring(open + 1, close) }
        val url = definitions[id.lowercase()] ?: return null
        return url to end + 1
    }

    val url = definitions[text.substring(open + 1, close).lowercase()] ?: return null
    return url to close + 1
}

/** `http://…` or `mailto:…` inside `<…>`, or null for anything else in brackets. */
private fun autolinkUrl(body: String): String? = when {
    body.startsWith("http://") || body.startsWith("https://") -> body
    body.startsWith("mailto:") && body.length > "mailto:".length -> body
    else -> null
}

/**
 * A url written as itself in running text, and where it ends.
 *
 * Trailing punctuation is not part of the url — "see https://x.dev/a." ends at the
 * `.` — and a closing bracket is kept only while the url has one still open, which
 * is the rule every editor's linkifier arrives at. A url that is not preceded by a
 * word boundary is not one: `xhttps://y` is an identifier, not a link.
 */
private fun bareUrlAt(text: String, index: Int, to: Int): Pair<String, Int>? {
    val scheme = when {
        text.startsWith("https://", index) -> "https://"
        text.startsWith("http://", index) -> "http://"
        else -> return null
    }
    val before = text.getOrNull(index - 1)
    if (before != null && (before.isLetterOrDigit() || before == '/' || before == '@' || before == '.')) {
        return null
    }
    var end = index + scheme.length
    while (end < to && !text[end].isWhitespace() && text[end] !in "<>\"'`") end++
    var trimmed = end
    while (trimmed > index + scheme.length && text[trimmed - 1] in ".,;:!?)]}'\"") {
        // A `)` is part of the url while the url has one still open.
        if (text[trimmed - 1] == ')') {
            val opened = text.substring(index, trimmed).count { it == '(' }
            val closed = text.substring(index, trimmed).count { it == ')' }
            if (opened >= closed) break
        }
        trimmed--
    }
    if (trimmed <= index + scheme.length) return null
    return text.substring(index, trimmed) to trimmed
}

/**
 * Where the inline formula opening at [start] ends, or -1 when there is none.
 *
 * `$…$` is only mathematics when it looks like mathematics: a closing `$` on the
 * same line and a non-empty body. A body padded with spaces — `$ x $`, which is how
 * TeX is usually written and how a model writes it when it is thinking about a paper
 * rather than about Markdown — is mathematics too, **when it looks like mathematics**;
 * that qualifier is what keeps a price a price. "it costs $5 and $10" must not become
 * the formula `5 and `, and the body there holds no `\`, `^`, `_` or brace to mistake
 * it for one, while `$ P(A_i\mid B) $` holds three.
 *
 * The padded form was rejected outright until a reader pasted a formula written as
 * `…P(B\mid A_j)} $` and watched it draw as its own LaTeX source, wrapped like prose —
 * the report "这种换行的仍然没渲染", which looked like a line-breaking bug and was a
 * delimiter rule.
 *
 * `internal` rather than private because the same rule decides where a formula
 * *starts* when `MathView` walks a paragraph looking for one to measure: two
 * answers to "is this a formula" is how `$5` becomes a formula in one half of the
 * pipeline and prose in the other.
 */
internal fun inlineMathEnd(text: String, start: Int): Int {
    var index = start + 1
    if (index >= text.length || text[index] == '$') return -1
    while (index < text.length) {
        when (text[index]) {
            // `\$` inside a formula is a literal dollar, and the closing one is
            // the next unescaped `$`.
            '\\' -> index += 2
            '$' -> {
                val body = text.substring(start + 1, index)
                if (body.isBlank()) return -1
                val padded = body.first().isWhitespace() || body.last().isWhitespace()
                return if (padded && !looksLikeMath(body)) -1 else index
            }
            '\n' -> return -1
            else -> index++
        }
    }
    return -1
}

/**
 * Whether [body] is unmistakably a formula rather than a price.
 *
 * Deliberately narrow, and it is the *padded* case only — an unpadded body is mathematics by the
 * older rule and never reaches this. A formula a model pads with spaces is one it wrote from a
 * paper rather than from Markdown, and those carry a `\command`, a script (`^`/`_`), a group
 * (`{}`) or a relation (`=`); a sentence about money carries none of them, whatever the spaces
 * around its dollars. `$ P(A) = 1 $` is mathematics on the `=`, and "it costs $ 5 and $ 10" is a
 * sentence on there being nothing at all.
 *
 * The boundary this leaves is a padded body with no operator and no markup — `$ x $` — which stays
 * prose. That is the right way round: a formula shown as its source is legible and merely ugly,
 * while a price typeset as mathematics is a sentence that has been mangled.
 */
private fun looksLikeMath(body: String): Boolean {
    body.forEachIndexed { index, char ->
        when (char) {
            '^', '_', '{', '}', '=', '+', '<', '>' -> return true
            '\\' -> if (body.getOrNull(index + 1)?.isLetter() == true) return true
        }
    }
    return false
}

/** The punctuation CommonMark lets a backslash escape. */
private const val ESCAPABLE = "\\`*_{}[]()#+-.!<>|~$"

// ------------------------------------------------------------------- building

/**
 * The tokens drawn as an `AnnotatedString`, with every formula and — when the caller
 * can supply one — every code span replaced by an inline content.
 *
 * [inlineContent] is asked for each formula's [unescapeMath] body and returns the
 * measured content, or null to draw the formula's own source instead. A null is
 * not an error case: it is what a caller that is only *measuring* — a table column,
 * or a test with no renderer — passes, and it is also what the renderer returns for
 * a formula it refuses. Appending a placeholder the map does not hold throws inside
 * `Text`, so "the caller said no" and "the renderer said no" have to end the same
 * way, and they do: the source text goes back.
 *
 * [code] is the same contract for a code span, and it exists because **Compose has
 * no rounded background for a text span**: `SpanStyle.background` is a plain colour
 * fill with no corner radius, and ui-text 1.10 has no `BackgroundStyle` to supply
 * one. So the fill has to be a *composable* — a `Box` with a shape behind the text —
 * which means the span becomes an inline content like a formula: measured first,
 * then declared as a placeholder. A null (and the default) keeps the span a plain
 * `SpanStyle`, which is what the table measurement wants: a filled chip in every
 * cell of a measured grid reads as a grid of errors, and only the widths are wanted
 * there.
 */
internal fun renderInline(
    text: String,
    style: InlineStyle,
    method: ((body: String, source: String) -> InlineTextContent?)? = null,
    definitions: Map<String, String> = emptyMap(),
    code: ((source: String) -> InlineTextContent?)? = null,
): MathInline {
    val content = LinkedHashMap<String, InlineTextContent>()
    var inline = 0
    val builder = buildAnnotatedString {
        drawTokens(
            tokenizeInline(text, definitions),
            style,
            content,
            method,
            code,
            key = { inline++ },
        )
    }
    return MathInline(builder, content)
}

/**
 * Draws a token list into the enclosing `AnnotatedString.Builder`.
 *
 * A free function taking the builder as its receiver rather than a class holding
 * one: a nested run has to be drawn inside the *same* builder between a style push
 * and its pop, which `withStyle` on the receiver does exactly, and a wrapper object
 * would have to hand its own builder back out to do it.
 *
 * [key] is a function rather than a counter because the placeholder's name is
 * generated where it is used, and the builder — not the caller — is what knows how
 * many inline contents have been drawn so far. It is shared between the formulas and
 * the code chips so the two cannot collide on a name.
 */
private fun AnnotatedString.Builder.drawTokens(
    tokens: List<InlineToken>,
    style: InlineStyle,
    content: MutableMap<String, InlineTextContent>,
    method: ((body: String, source: String) -> InlineTextContent?)?,
    code: ((source: String) -> InlineTextContent?)?,
    key: () -> Int,
) {
    for (token in tokens) {
        when (token) {
            is InlineToken.Text -> append(token.text)

            is InlineToken.Code -> {
                val chip = code?.invoke(token.text)
                if (chip == null) {
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = style.codeBackground),
                    ) {
                        append(token.text)
                    }
                } else {
                    val id = "code${key()}"
                    // The alternative text is the code itself, which is what a screen
                    // reader announces and what a `TextMeasurer` weighs the placeholder
                    // by if it is ever measured without the map.
                    appendInlineContent(id, token.text)
                    content[id] = chip
                }
            }

            is InlineToken.Styled -> withStyle(token.style) {
                drawTokens(token.children, style, content, method, code, key)
            }

            is InlineToken.Link -> withLink(
                LinkAnnotation.Url(
                    url = token.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = style.linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                drawTokens(token.children, style, content, method, code, key)
            }

            is InlineToken.Math -> {
                val measured = method?.invoke(unescapeMath(token.source), token.source)
                if (measured == null) {
                    append(token.source)
                } else {
                    val id = "math${key()}"
                    // The alternative text is the source, delimiters included: it is
                    // what a screen reader announces and what a `TextMeasurer` uses
                    // for the placeholder's width.
                    appendInlineContent(id, token.source)
                    content[id] = measured
                }
            }
        }
    }
}

/**
 * The two delimiters of a `$…$`, `$$…$$`, `\(…\)` or `\[…\]` span removed.
 *
 * The delimiters are the *Markdown* markup, not the mathematics: the renderer is
 * handed `\pi r^2`, never `$\pi r^2$`. `\[…\]` was missing here while the tokeniser
 * could already produce it for a formula written inside a sentence, and the renderer
 * was therefore handed the brackets — which it parses as an error and draws as the
 * formula's source, the failure "某些情况没渲染" looked like.
 *
 * Trimmed as well, because TeX's own brackets are written `\[ x \]` with the spaces
 * inside while `$…$` may not have them at all: math mode ignores a space, so this
 * changes nothing the renderer sees and keeps the four spellings' bodies alike.
 */
internal fun unescapeMath(source: String): String = (when {
    source.startsWith("\\(") -> source.removePrefix("\\(").removeSuffix("\\)")
    source.startsWith("\\[") -> source.removePrefix("\\[").removeSuffix("\\]")
    source.startsWith("$$") -> source.removePrefix("$$").removeSuffix("$$")
    source.startsWith("$") -> source.removePrefix("$").removeSuffix("$")
    else -> source
}).trim()

/**
 * Whether a fragment contains any mathematics at all.
 *
 * The cheap question a caller asks before it is willing to build a renderer. It
 * runs the tokeniser, which is linear, rather than a scan of its own — a second
 * rule for "is this a formula" is how `$5` becomes a formula in one half of the
 * pipeline and prose in the other.
 */
internal fun containsInlineMath(source: String): Boolean =
    tokenizeInline(source).any { it.containsMath() }

private fun InlineToken.containsMath(): Boolean = when (this) {
    is InlineToken.Math -> true
    is InlineToken.Styled -> children.any { it.containsMath() }
    is InlineToken.Link -> children.any { it.containsMath() }
    else -> false
}

/**
 * Whether a fragment contains any inline code, which is the question
 * [rememberMathInline] asks before it pays for a `TextMeasurer`.
 *
 * The same shape as [containsInlineMath] and for the same reason: the tokeniser is
 * the one authority on what is code, so a second, cheaper rule — "does it contain a
 * backtick" — would disagree with it exactly where the answer matters, on an escaped
 * backtick or a fenced block's worth of them.
 */
internal fun containsInlineCode(source: String): Boolean =
    tokenizeInline(source).any { it.containsCode() }

private fun InlineToken.containsCode(): Boolean = when (this) {
    is InlineToken.Code -> true
    is InlineToken.Styled -> children.any { it.containsCode() }
    is InlineToken.Link -> children.any { it.containsCode() }
    else -> false
}
