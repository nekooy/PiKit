/*
 * Drawing a typeset formula, with the one bug the library's own drawable has.
 *
 * ## Why this file exists
 *
 * A formula is typeset by `ru.noties:jlatexmath-android` — `MathCache.kt` says why that renderer
 * and what it costs — and the library ships a drawable for drawing the result,
 * `ru.noties.jlatexmath.JLatexMathDrawable`. It is not used here, because its drawing leaves a
 * formula hollow the moment a rule is drawn before a glyph.
 *
 * `ru.noties.jlatexmath.awt.AndroidGraphics2D` keeps **one** `android.graphics.Paint` for a whole
 * formula. `draw(Line2D)`, `draw(Rectangle2D)`, `draw(RoundRectangle2D)` and `drawArc` set that
 * Paint to `Paint.Style.STROKE`, and `drawChars` — which is what every glyph box ends at, both
 * `CharBox` and the `TextLayout` inside `JavaFontRenderingBox`'s `\text{}` — hands the Paint to
 * `Canvas.drawText` in whatever style it was last left in. Nothing restores FILL, and the library
 * never does. `\boxed` is JLaTeXMath's `FramedBox`, which draws its frame before its content, so
 * every glyph after the frame is stroked: the text inside the box is an outline of itself. Any
 * construct that draws a rule before its own text has the same defect; an array's `\hline` does
 * not, because that box draws its rules after its rows (measured — ARCHITECTURE §12.2).
 *
 * So the typeset icon is drawn here instead, through a `Graphics2D` that remembers whether the
 * shared Paint is in STROKE and resets it to FILL before the next text. That fixes the defect for
 * every construct rather than for `\boxed`, which is the only way to fix it at all: the library
 * cannot be edited (it is a pinned external dependency), and a rewrite of `\boxed` into something
 * that draws no frame would delete the construct's whole meaning.
 *
 * ## What is replicated, and why that is safe
 *
 * The rest of `JLatexMathDrawable` is drawable packaging, not typesetting: the icon is scaled down
 * to the bounds only when it does not fit, it is centred vertically, it is drawn left-aligned, and
 * its own `Paint` is set up by `AndroidGraphics2D`. [formulaScale] is that arithmetic, byte for
 * byte: `min(bounds / icon)` when either axis overflows, `(size * scale + 0.5f).toInt()` for the
 * drawn size, and the remainder split above and below. The icon itself is built by the same
 * library calls the library's drawable makes — `TeXFormula.createTeXIcon(style, size)`, with the
 * style the caller's `MathSpec` chose: `STYLE_TEXT` for an inline formula and `STYLE_DISPLAY` for
 * one that owns its block — `setForeground`, and `setInsets` with the same padding — so `MathCache`'s
 * geometry contract (`widthPx`/`heightPx`/`depthPx`) and the 0.42 ms typesetting measurement are
 * unchanged. The style choice is the fix for the measured defect: every formula used to be built in
 * display style, which made `\frac{1}{2}` 106px tall at 17sp where the text style's fraction is 70px
 * and is what a 26sp prose line is built around.
 *
 * The drawable stays immutable once built and only its bounds are set before a draw, which is what
 * lets one entry of the cache be drawn by every block that uses that formula. `setAlpha` and
 * `setColorFilter` are no-ops for the same reason the library's are: the ink is fixed at typeset
 * time, because JLaTeXMath draws into the caller's canvas and has no notion of a theme.
 */
package pi.kit.mob.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import ru.noties.jlatexmath.awt.Insets
import ru.noties.jlatexmath.awt.geom.Line2D
import ru.noties.jlatexmath.awt.geom.Rectangle2D
import ru.noties.jlatexmath.awt.geom.RoundRectangle2D
import kotlin.math.min

internal class MathDrawable private constructor(
    private val icon: TeXIcon,
    private val graphics: FillingGraphics2D = FillingGraphics2D(),
) : Drawable() {

    private val iconWidth: Int = icon.getIconWidth()
    private val iconHeight: Int = icon.getIconHeight()

    /**
     * How far the formula's baseline sits above the icon's bottom, in pixels.
     *
     * This is `TeXIcon.getIconDepth()`: `MathCache` adds the padding it passed to [build] to it,
     * because the icon's own depth already includes the bottom inset.
     */
    fun depthPx(): Int = icon.getIconDepth()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val placement = formulaScale(iconWidth, iconHeight, bounds.width(), bounds.height())
        val save = canvas.save()
        try {
            if (placement.dy != 0) canvas.translate(0f, placement.dy.toFloat())
            if (placement.scale != 1f) canvas.scale(placement.scale, placement.scale)
            graphics.setCanvas(canvas)
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun getIntrinsicWidth(): Int = iconWidth

    override fun getIntrinsicHeight(): Int = iconHeight

    /**
     * Opaque, like the library's drawable: the ink is drawn, not blended by the caller.
     *
     * `Drawable.getOpacity` is deprecated in the platform and is still abstract, so it has to be
     * answered; nothing in this app asks a formula's drawable for its opacity.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    /** Fixed at typeset time: a formula's ink is not tinted, it is re-typeset (ARCHITECTURE §12.2). */
    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    internal companion object {
        /**
         * Lays [body] out at [sizePx], in [ink], with [padding] on each side, using the TeX [style].
         *
         * The construction is the library's own, in the library's own order: a `TeXFormula`, an
         * icon at [style] — `STYLE_TEXT` for an inline formula, `STYLE_DISPLAY` for a display one —
         * the size the caller asked for, its foreground colour, then the insets that carry the
         * padding. `setInsets` is the one-argument overload on purpose — it is the one
         * `JLatexMathDrawable` calls, and it adds the renderer's own vertical padding on top of the
         * caller's, which is what the geometry `MathCache` reads back was measured with.
         */
        fun build(body: String, sizePx: Float, ink: Int, padding: Int, style: Int): MathDrawable {
            val icon = TeXFormula(body).createTeXIcon(style, sizePx)
            icon.setForeground(Color(ink))
            icon.setInsets(Insets(padding, padding, padding, padding))
            return MathDrawable(icon)
        }
    }
}

/**
 * How the icon is fitted into the box the drawable is given, in the library's arithmetic.
 *
 * Pure, so that the packaging this file replicates from `JLatexMathDrawable` can be pinned on the
 * JVM: the scale is 1 unless an axis overflows, the drawn size is rounded rather than truncated,
 * and what is left over is split evenly above and below.
 */
internal data class FormulaScale(
    /** The uniform scale the icon is drawn at. */
    val scale: Float,
    /** The icon's width at that scale, rounded. */
    val width: Int,
    /** The icon's height at that scale, rounded. */
    val height: Int,
    /** The offset from the top of the bounds at which the icon starts. */
    val dy: Int,
)

/** The arithmetic `JLatexMathDrawable.draw` applies to a bounds box, read out of its bytecode. */
internal fun formulaScale(
    iconWidth: Int,
    iconHeight: Int,
    boundsWidth: Int,
    boundsHeight: Int,
): FormulaScale {
    // An unset or empty bounds draws nothing, and a zero axis in the division below would make the
    // scale NaN rather than zero. `MathView` always sets a real box; this is the defence for a
    // caller that does not.
    if (iconWidth <= 0 || iconHeight <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
        return FormulaScale(scale = 0f, width = 0, height = 0, dy = 0)
    }
    val scale = if (iconWidth > boundsWidth || iconHeight > boundsHeight) {
        min(boundsWidth.toFloat() / iconWidth, boundsHeight.toFloat() / iconHeight)
    } else {
        1f
    }
    val width = (iconWidth * scale + 0.5f).toInt()
    val height = (iconHeight * scale + 0.5f).toInt()
    return FormulaScale(scale = scale, width = width, height = height, dy = (boundsHeight - height) / 2)
}

/**
 * The state of the library's one shared `Paint`, as far as glyphs care about it.
 *
 * `AndroidGraphics2D` sets `Paint.Style.STROKE` for the four line/arc/outline calls and
 * `Paint.Style.FILL` for the three fill calls, and never restores the style afterwards; `drawChars`
 * does not touch it at all. So the only thing needed to draw a glyph correctly is to know whether
 * a stroke has happened since the last fill, and to ask for one fill before the next text if so.
 *
 * A separate class rather than three booleans in [FillingGraphics2D] because it is the whole
 * decision and it is testable on the JVM: no Android type appears in it.
 */
internal class PaintStyleGuard {

    /** True once a stroke operation has left the shared Paint in STROKE and nothing has filled since. */
    private var stroking = false

    /** A line, arc, rectangle outline or round rectangle was drawn: the Paint is STROKE now. */
    fun onStrokeOp() {
        stroking = true
    }

    /** A filled rectangle or arc was drawn: the Paint is FILL again, without help. */
    fun onFillOp() {
        stroking = false
    }

    /**
     * True exactly once after a stroke, when the next glyphs need a zero-sized fill to put the
     * Paint back into FILL.
     *
     * Consuming the state rather than merely reading it is what keeps the fix to one extra canvas
     * call per run of text after a rule, instead of one per glyph: a formula with no rule in it
     * pays nothing, and the glyphs of a `\boxed`'s content pay once for the frame.
     */
    fun takeGlyphReset(): Boolean {
        if (!stroking) return false
        stroking = false
        return true
    }
}

/**
 * The library's `Graphics2D`, with the shared Paint's style put back before every run of glyphs.
 *
 * Every mutator that changes `Paint.Style` is overridden to record it, and [drawChars] asks the
 * guard whether the text about to be drawn needs a fill first. The fill itself is a rectangle of no
 * size at the origin: it sets the style and paints no pixel. This is the whole fix — it covers
 * `\boxed` and every other construct that draws a line before its text, without the app holding a
 * list of which those are.
 */
internal class FillingGraphics2D : AndroidGraphics2D() {

    private val style = PaintStyleGuard()

    /** The zero-sized rectangle whose fill restores `Paint.Style.FILL` and draws nothing. */
    private val empty = Rectangle2D.Float(0f, 0f, 0f, 0f)

    override fun draw(rect: Rectangle2D.Float) {
        style.onStrokeOp()
        super.draw(rect)
    }

    override fun draw(line: Line2D.Float) {
        style.onStrokeOp()
        super.draw(line)
    }

    override fun draw(round: RoundRectangle2D.Float) {
        style.onStrokeOp()
        super.draw(round)
    }

    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        style.onStrokeOp()
        super.drawArc(x, y, width, height, startAngle, arcAngle)
    }

    override fun fill(rect: Rectangle2D.Float) {
        style.onFillOp()
        super.fill(rect)
    }

    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        style.onFillOp()
        super.fillArc(x, y, width, height, startAngle, arcAngle)
    }

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        style.onFillOp()
        super.fillRect(x, y, width, height)
    }

    override fun drawChars(chars: CharArray, offset: Int, length: Int, x: Int, y: Int) {
        // Glyphs are filled text, never an outline. `drawChars` in the library sets the typeface
        // and size from the current font but not the style, so a line drawn earlier in the same
        // formula would leave these glyphs stroked — the defect this class exists for.
        if (style.takeGlyphReset()) super.fill(empty)
        super.drawChars(chars, offset, length, x, y)
    }
}
