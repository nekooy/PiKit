/*
 * PiKit's shape vocabulary.
 *
 * ## The scale is Material's, unmodified
 *
 * The ten steps from the M3 corner-radius scale — none 0, extra small 4, small 8,
 * medium 12, large 16, large increased 20, extra large 28, extra large increased
 * 32, extra extra large 48, and full — are the off-the-shelf answers, and there is
 * no reason to invent a different one: a product that keeps the scale gets every
 * component's default corner right for free and only has to decide *which* step a
 * given surface uses.
 *
 * ## Two voices, and the tension between them
 *
 * The expressive shape guidance asks for shape to be a communication tool rather
 * than decoration, and names two moves that this app uses deliberately:
 *
 *  - **Information-dense surfaces stay square-ish.** The same guidance warns
 *    against large or full corners on information-dense components, because a big
 *    radius eats the content at the corners. The transcript's tool rows, the
 *    settings rows, the file rows and the terminal's chrome are all dense, so they
 *    sit at [row] (12dp) and [card] (16dp) — one step at a time, never a pill.
 *  - **Action surfaces are fully round.** Buttons, chips, badges, the navigation
 *    bar's selection indicator and the composer's send control are all
 *    [pill]. Rounding an action is what makes it read as an action rather than as
 *    another row, which is exactly the "unexpected moment by switching between
 *    square and fully rounded shapes" the guidance describes.
 *
 * The tension is load-bearing, so a new surface has to pick a side rather than
 * land in the middle: a 24dp radius on a list row is neither dense nor an action,
 * and it reads as neither.
 *
 * ## One mark, used once
 *
 * [MaterialShapes]'s thirty-five polygons are decorative, and the guidance is
 * explicit that abstract shapes should be used sparingly. PiKit therefore spends
 * its one licence on a single mark — [agent] — which is the shape the agent's own
 * avatar, its working indicator and the empty-transcript medallion are all cut
 * from. It appears nowhere else: a second abstract silhouette elsewhere on the
 * same screen turns the first one into wallpaper.
 */
package pi.kit.mob.ui.design

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The corner steps, by name.
 *
 * A holder rather than a bare `Shapes` instance because call sites ask for a
 * *purpose* — "the row outline", "the action shape" — and the step that satisfies
 * it is a decision that should be made once, in one file.
 */
object PiShapes {

    /** 4dp. Checkbox and radio corners, badges, the smallest insets. */
    val extraSmall: CornerBasedShape = RoundedCornerShape(4.dp)

    /** 8dp. Chips' inner edges, menu items' inner corners, small marks. */
    val small: CornerBasedShape = RoundedCornerShape(8.dp)

    /** 12dp. A row's press outline, and any dense surface that needs a step. */
    val row: CornerBasedShape = RoundedCornerShape(12.dp)

    /** 16dp. Cards, text fields, and the group a set of rows is drawn inside. */
    val card: CornerBasedShape = RoundedCornerShape(16.dp)

    /** 20dp. A card that carries a number or a single fact prominently. */
    val largeIncreased: CornerBasedShape = RoundedCornerShape(20.dp)

    /** 28dp. Sheets, dialogs, and the composer's own container. */
    val panel: CornerBasedShape = RoundedCornerShape(28.dp)

    /** 32dp. The hero surfaces: an empty state's medallion, the setup screen. */
    val hero: CornerBasedShape = RoundedCornerShape(32.dp)

    /** 48dp. A full-bleed decorative block. */
    val extraExtraLarge: CornerBasedShape = RoundedCornerShape(48.dp)

    /**
     * Fully round. Every action, and the marker that says which destination a
     * navigation bar item is.
     *
     * `percent = 50` rather than a large radius in dp: the guidance's "full" is
     * half of the *shorter side of the container*, so a fixed radius would be a
     * pill on a 32dp icon button and a rounded rectangle on a 96dp extended FAB.
     */
    val pill: Shape = RoundedCornerShape(percent = 50)

    /** A sheet's top corners, square where the panel meets the window's edge. */
    val sheetTop: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /**
     * The app's one abstract silhouette: the agent's mark.
     *
     * A seven-sided cookie — an even-lobed rounded star. Seven rather than four or
     * twelve is the count that still reads as a distinct outline at 20dp, which is
     * the smallest place it is drawn; at twelve it is indistinguishable from a
     * circle and at four it is a square.
     */
    val agent: Shape
        @Composable @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        get() = MaterialShapes.Cookie7Sided.toShape()

    /** The shape a working indicator morphs between: soft, so it never looks sharp. */
    val live: Shape
        @Composable @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        get() = MaterialShapes.SoftBurst.toShape()

    /** An empty state's medallion, one size up from the agent mark. */
    val medallion: Shape
        @Composable @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        get() = MaterialShapes.Cookie9Sided.toShape()

    /** A burst, for the one place a count or a status needs an emphatic frame. */
    val spark: Shape
        @Composable @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        get() = MaterialShapes.Burst.toShape()
}

/** Material's own eight slots, filled from the scale above. */
internal val PiThemeShapes: Shapes = Shapes(
    extraSmall = PiShapes.extraSmall,
    small = PiShapes.small,
    medium = PiShapes.row,
    large = PiShapes.card,
    extraLarge = PiShapes.panel,
    largeIncreased = PiShapes.largeIncreased,
    extraLargeIncreased = PiShapes.hero,
    extraExtraLarge = PiShapes.extraExtraLarge,
)

/**
 * The radius an inner surface needs to look concentric with its container.
 *
 * The shape guidance's optical-roundness rule: `outer radius - padding = inner
 * radius`. Two nested rounded rectangles at the *same* radius read as unbalanced —
 * the inner one looks rounder than the outer — and this is the one line that fixes
 * it. Used wherever a row sits inside a card with a known inset.
 */
fun opticalRadius(outer: Dp, padding: Dp): Dp = (outer - padding).coerceAtLeast(0.dp)
