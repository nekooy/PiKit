/*
 * PiKit's own marks.
 *
 * The app uses Material icons for almost everything, because they are drawn on a
 * 24dp grid with a consistent optical weight and cost nothing to add. The five
 * exceptions are the model mark, the thinking mark, the command mark, the copy mark and
 * its confirmation, kept here rather than inlined at a call site so that the settings
 * row, the profile's model list, the composer's chip and the two copy buttons cannot
 * drift apart.
 *
 * `ic_model.xml` holds the geometry of one and says where it comes from and why the
 * model places stopped borrowing `SmartToy` and `Hub`; `ic_thinking.xml` holds the
 * other and says why the thinking places stopped borrowing `Psychology`.
 *
 * [Commands] is Lucide's `command`, not Tabler's `code`: the reader picked it from a sheet of
 * candidates rendered at the size the app draws them, and `ic_commands.xml` records why.
 */
package pi.kit.mob.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import pi.kit.mob.R

object PiIcons {

    /**
     * The model mark: a stack of three layers.
     *
     * A `vectorResource` rather than a hand-built `ImageVector` because the paths
     * are arcs, and the resource parser is the one piece of the platform that
     * already reads them exactly. It is cached by the resource system, so the
     * three call sites cost one parse between them.
     */
    val Model: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_model)

    /**
     * The thinking mark: a brain.
     *
     * Used by every control that says "the model is thinking": the reasoning row on
     * a message, the thinking-level chip above the composer, and the thinking-level
     * row on the model page. Before this there were three call sites of
     * `Icons.Filled.Psychology`, which is a head with a gear — see the drawable for
     * why that is the wrong picture, and why a second custom vector was worth it.
     */
    val Thinking: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_thinking)

    /**
     * The command mark: ⌘, for the composer's command-list button.
     *
     * `res/drawable/ic_commands.xml`, which is Lucide's `command` (ISC), unmodified — one
     * closed path with four 3-unit corner loops, at the library's own stroke weight.
     *
     * ## Why ⌘ and not `</>`
     *
     * The button opens a list of *commands*, and ⌘ is the glyph that says so; `</>` says
     * "code". Both were rendered at the sizes the app draws and offered to the reader, and
     * this is the one chosen — which matters more than any reasoning here, because five
     * hand-drawn `</>` attempts preceded it and all five were rejected.
     *
     * It is also the sturdier shape at 21dp. A `</>` is three strokes whose diagonal passes
     * close to the two it sits between, so its parts crowd each other at small sizes; this
     * is a single closed figure with nothing to collide with.
     *
     * ## Two mistakes worth not repeating
     *
     * An earlier note in this file claimed Material3's `Icon` zeroes a stroked vector's
     * `strokeLineWidth`, so `Icons.Filled.Code` drew nothing. **That was false** — `Icon`
     * calls `rememberVectorPainter(ImageVector, …)`, the overload with no stroke arguments,
     * and the glyph is drawn at the width its `ImageVector` carries.
     *
     * And what the reader was actually seeing, across four builds, was a drawable that
     * **was not in the APK**: the hand-drawn replacement failed to compile — an XML comment
     * held a double hyphen, which XML forbids — and a build-output filter hid AAPT's error,
     * so every "fixed" build installed a cached older APK. `unzip -l app-debug.apk | grep
     * ic_commands` is the check that settles it, and it is in `AGENTS.md` now.
     */
    val Commands: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_commands)

    /**
     * The copy mark: two rounded sheets, the back one peeking at the top right.
     *
     * `res/drawable/ic_copy.xml`, whose geometry the **reader supplied** as an SVG after four
     * rounds of candidates failed to find it: every icon set this project looked at draws the back
     * sheet at the top *left* (Lucide, Tabler, Feather, Heroicons, Ionicons) or hangs a folded
     * corner off the front one (Font Awesome, Heroicons `document-duplicate`). The drawable's own
     * comment records both that and the Apache-2.0 figure it is equivalent to.
     *
     * The lesson is in `AGENTS.md`: three of those four rounds answered a question the reader had
     * not asked — first six *containers* for a copy glyph, then sets of glyphs picked by this
     * assistant rather than by them. **A report about how something looks is a request for a
     * picture of the thing wanted, not for a menu.**
     */
    val Copy: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_copy)

    /**
     * The confirmation mark: shown in [Copy]'s place for a moment after a copy.
     *
     * `res/drawable/ic_check.xml`, which is Lucide's `check` (ISC), at Lucide's own stroke weight —
     * about the weight of [Copy]'s sheet walls at the size the app draws it. Font Awesome's filled
     * tick was tried beside the sheets and was visibly heavier than the glyph it replaced.
     */
    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_check)
}

