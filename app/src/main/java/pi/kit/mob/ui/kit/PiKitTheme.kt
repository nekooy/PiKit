package pi.kit.mob.ui.kit

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.ThemeMode
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * The interface's theme: the library's palette, whole, and the reader's light/dark
 * choice.
 *
 * ## Why there is no accent here
 *
 * The app used to derive its palette from a chosen hue: a `ThemeColor` seed went
 * into a `ThemeController` as its `keyColor`, and the library's material-colour
 * engine turned that one colour into a hundred and forty roles. It worked, and it
 * was still the wrong thing to own — the palette a Miuix screen is designed against
 * is *this* palette, the one the library ships, and a derived scheme is a different
 * one wearing its component shapes. The accent picker and the wallpaper switch are
 * therefore gone, and this function passes no seed and no colour spec: the
 * controller's own `lightColorScheme()` and `darkColorScheme()` are the interface.
 *
 * What that buys is the thing the rewrite was for: a page assembled from library
 * components is drawn in the colours those components were drawn for. What it
 * costs is the one thing a seed gave — a hue of one's own — and the app no longer
 * has a reason to want it.
 *
 * ## Mode
 *
 * [ThemeMode] is the controller's `isDark` override over `ColorSchemeMode.System`,
 * rather than a choice between three modes: `System` already means "follow the
 * platform", and an override of `null`/`false`/`true` says the same thing about the
 * other two without a second code path to keep in step. One controller, one
 * authority for light/dark, which is what keeps a dialog's own window — a second
 * window with its own composition — from disagreeing with the page behind it.
 */
@Composable
fun PiKitMiuixTheme(
    settings: PiSettings,
    content: @Composable () -> Unit,
) {
    val mode = when (settings.themeMode) {
        ThemeMode.SYSTEM -> null
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val controller = remember(mode) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.System,
            isDark = mode,
        )
    }

    // `enableEdgeToEdge()` makes the app responsible for its own bar icons, and
    // which colour they have to be is a property of the theme rather than of a
    // page. Read from the same resolved dark state the palette uses, so a dialog's
    // separate window cannot disagree with the page behind it.
    val dark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }

    MiuixTheme(controller = controller, content = content)
}

/**
 * The two states the palette has no role for, named once.
 *
 * Miuix's scheme is a *component* palette: every role in it answers "what colour is
 * this control", and the two questions this app keeps asking are not about
 * controls — *is this still running* and *did this fail*. Both are drawn from roles
 * the library does own, so nothing here invents a colour:
 *
 * - **Live** is the tertiary pair — the light blue wash with the accent's own ink.
 *   It used to be the `secondary` pair, which was a hue back when the scheme was
 *   derived from a seed; in the library's own scheme `secondary` is a grey, so the
 *   pill's dot and label would have been grey on grey. The tertiary pair is the
 *   palette's quiet blue, which is exactly what "in progress, not a result" is.
 * - **Error** is the scheme's error pair, re-exported so a call site says *danger*
 *   rather than naming a role.
 * - **Inset** is the fill a *quotation* sits in — a code span, a fenced block, a
 *   chip on the reader's own card — and it is the one role in this object that is
 *   a wash rather than a pair, for the reason its own KDoc gives.
 */
object PiTone {

    @Composable
    fun live(): Color = MiuixTheme.colorScheme.onTertiaryContainer

    @Composable
    fun liveContainer(): Color = MiuixTheme.colorScheme.tertiaryContainer

    @Composable
    fun error(): Color = MiuixTheme.colorScheme.error

    @Composable
    fun errorContainer(): Color = MiuixTheme.colorScheme.errorContainer

    /**
     * The fill behind quoted material: a code span, a fenced block, a chip.
     *
     * ## Why this is a wash of the surface's own ink
     *
     * A code block and an inline code span are drawn on *three* different
     * backgrounds — the answer card's `surfaceContainer`, the manual's page
     * `background`, and the reader's own `secondaryContainer` prompt — and a single
     * palette role cannot be a step away from all three. Measured on the library's
     * own light scheme, `secondaryContainer` (#F0F0F0) is a 15-point step away from
     * the white card and a 7-point step away from the #F7F7F7 page, so the same
     * block read as a well in the transcript and as a smudge in the manual; in the
     * dark scheme `surfaceContainerHigh` *is* the card's own #242424 and the block
     * disappeared outright.
     *
     * A wash of `onSurface` at one alpha answers all three at once, because it is
     * not a colour but a *contrast*: black at 10% over white is #E6E6E6, over the
     * #F7F7F7 page it is #DEDEDE, and over the tinted prompt #D8D8D8 — the same
     * relative step in each case, which is what "the same depth everywhere" means.
     * In the dark scheme the same 10% of a near-white ink lightens #242424 to
     * #3D3D3D and black to #181818, again by the same step. The library's palette
     * supplies both the ink and the alpha is this file's one number.
     */
    @Composable
    fun inset(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = INSET_ALPHA)

    /**
     * The fill behind the reader's own message: the accent, mixed into the card colour.
     *
     * ## Why an accent mix rather than a container role
     *
     * The prompt bubble is the one object on the transcript that is not a card in the
     * palette's sense: it is the reader's half of the conversation, and the reader's
     * half is read as *blue* in every chat app the page is read beside. The palette's
     * blue containers are `primaryContainer` (#5D9BFF light, #338FE4 dark) and
     * `tertiaryContainer` (#EAF2FF, #2B3B54) — the first is a filled control that
     * wants a white knockout ink, and the prompt must keep the same ink as the answer
     * beside it; the second is so pale in the light scheme that it is nearly the page.
     *
     * So the fill is composed: the palette's `surfaceContainer` (the colour an answer
     * card is) with `primary` mixed into it, at a share chosen from how much the base
     * can absorb — [PROMPT_SHARE_LIGHT] on white, [PROMPT_SHARE_DARK] on the dark
     * scheme's #242424. That lands at #DEEBFF and #25416C: one pale blue, one navy,
     * both unmistakably the accent's hue and both legible in `onSurface` (15:1 and
     * 7.4:1).
     *
     * ## Why not a translucent wash
     *
     * It was `primary.copy(alpha = 0.14f)` — the same mechanism as [inset] — and it is
     * transparent over *the page*, which is `surface`: #F7F7F7 in the light scheme and
     * **black** in the dark one. 14% of #277AF7 over black is #061122, a shade that is
     * not blue at all until it is beside something blacker; the bubble disappeared into
     * the page it was drawn on. A fill has to be relative to its *content* colour rather
     * than to the darkest surface the palette has, and the base is therefore the card —
     * the colour the reader compares it with.
     */
    @Composable
    fun prompt(): Color {
        val base = MiuixTheme.colorScheme.surfaceContainer
        val share = if (base.luminance() < DARK_BASE_LUMINANCE) {
            PROMPT_SHARE_DARK
        } else {
            PROMPT_SHARE_LIGHT
        }
        return lerp(base, MiuixTheme.colorScheme.primary, share)
    }
}

/**
 * How much of the accent the reader's own message is mixed into the card colour.
 *
 * The two shares are one idea rather than two settings: the same *visual* weight of blue
 * on a white base and on a near-black one. 16% of #3482FF into white is #DEEBFF — plainly
 * blue, still pale enough that the answer beside it stays the brighter card; 34% of
 * #277AF7 into #242424 is #25416C — a navy that reads as the reader's half at a glance
 * without becoming the brightest thing on a black page.
 */
private const val PROMPT_SHARE_LIGHT = 0.16f
private const val PROMPT_SHARE_DARK = 0.34f

/**
 * Where a base counts as dark, for the two shares above.
 *
 * 0.2 is between the light scheme's white card (1.0) and its #F7F7F7 page (0.93) on one
 * side, and the dark scheme's #242424 card (0.017) on the other — no base the palette
 * offers is near the boundary, so this is a test of *which scheme* rather than a taste
 * threshold. It is not the luminance comparison the chip's KDoc rejects: that one asked
 * how far apart two roles were, this one asks how much accent a base can absorb.
 */
private const val DARK_BASE_LUMINANCE = 0.2f

/**
 * How much of the surface's own ink a quotation's fill is washed with.
 *
 * 10% is the smallest step that reads as a *well* on the lightest surface the app
 * draws (the #F7F7F7 page, where it lands at #DEDEDE) rather than as a JPEG
 * artifact, and it is still light enough that a code block does not become a dark
 * panel in the light scheme. The step it leaves on the dark scheme's #242424 card
 * (#3D3D3D) is the same one, which is the point of a wash.
 */
private const val INSET_ALPHA = 0.10f

/**
 * The corner radius every surface in the app is cut with.
 *
 * Miuix owns the radius — cards, fields and buttons are 16dp squircles and the
 * app used to state none of them (§13). This is the one exception, and it is a
 * single step rather than a scale: every one of the library's 16dp surfaces is
 * drawn at [radius], so a card, a field and a button are still the same corner as
 * each other, which is the property the library's own constant was providing.
 *
 * 20dp rather than 16: at the 12dp page gutter the cards sit on, a 16dp corner is
 * more than the gutter and the frame starts to read as a rectangle with its edges
 * softened, while a 20dp one reads as a rounded card. It is one number and not a
 * set because a second step (a denser row, a larger panel) would need a rule for
 * which is which, and this app has no such rule to state.
 */
object PiCorner {
    val radius: Dp = 20.dp

    /**
     * The seam between two cards in a list.
     *
     * Two lists draw rows as cards — the conversation history and the file listing —
     * and both used 2dp, which is less than the gap *inside* one of those rows and
     * therefore reads as one panel with a line drawn through it rather than as a
     * column of separate objects. 6dp is still a seam and not a space between
     * sections: the grouping is carried by the row's own fill and outline, and the
     * gap only has to be wide enough that two outlines are seen as two.
     *
     * Stated once because the two lists are read one after the other in the same
     * app and were spaced by the same wrong number independently.
     */
    val rowGap: Dp = 6.dp
}

/**
 * The colours a text field is drawn in: the library's, with the focus ring off.
 *
 * Miuix's `TextField` animates a 2dp border in `colors.borderColor` — `primary`
 * by default — when it takes the focus. On a page whose fields are the only blue
 * rectangles, that ring is the loudest thing on the screen for as long as the
 * reader is typing, and it was reported as a blue frame around every input in the
 * app.
 *
 * The ring cannot simply be hidden: the library draws it as a *border*, and its
 * width is a property of the component. What can be chosen is its colour, so it
 * animates from the fill to the fill — 2dp of the field's own background — and the
 * focus is then shown by the caret and by the label's own animation, which is what
 * a field has instead of a ring.
 *
 * `cursorBrush` therefore cannot be left to its default either: it *is*
 * `colors.borderColor`, so a caret in the field's background colour would be
 * invisible. [piFieldCursor] is the pair to this function and every call site
 * passes both — see the KDoc there.
 *
 * [background] is for the one field that is not drawn on the library's own well: the
 * composer's, whose box is a container that holds three controls beside the text, so
 * the field itself paints nothing and the ring has to animate to *that* — transparent
 * — rather than to `secondaryContainer`, which would flash a grey rectangle over the
 * container's own fill.
 */
@Composable
fun piFieldColors(
    background: Color = MiuixTheme.colorScheme.secondaryContainer,
): TextFieldColors = TextFieldDefaults.textFieldColors(
    backgroundColor = background,
    // The ring animates to the same colour it rests on, which is what turns it off
    // without the library having a switch for it.
    borderColor = background,
)

/**
 * The caret, at the accent, for a field drawn with [piFieldColors].
 *
 * The pair exists because the library derives the caret's brush from the border's
 * colour: hiding the ring without stating the caret is a field whose caret cannot
 * be seen, which is a worse bug than the ring was.
 */
@Composable
fun piFieldCursor(): Brush = SolidColor(MiuixTheme.colorScheme.primary)
