package pi.kit.mob.ui.kit

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * The interface's theme: the library's palette, one page step deeper, and the reader's
 * light/dark choice.
 *
 * ## Why there is no accent here
 *
 * The app used to derive its palette from a chosen hue: a `ThemeColor` seed went
 * into a `ThemeController` as its `keyColor`, and the library's material-colour
 * engine turned that one colour into a hundred and forty roles. It worked, and it
 * was still the wrong thing to own — the palette a Miuix screen is designed against
 * is *this* palette, the one the library ships, and a derived scheme is a different
 * one wearing its component shapes. The accent picker and the wallpaper switch are
 * therefore gone, and this function passes no seed and no colour spec.
 *
 * What that buys is the thing the rewrite was for: a page assembled from library
 * components is drawn in the colours those components were drawn for. What it
 * costs is the one thing a seed gave — a hue of one's own — and the app no longer
 * has a reason to want it.
 *
 * ## The light scheme's two groups
 *
 * The light scheme mirrors the dark one's two groups rather than inventing separate
 * roles: the history ground and the answer card are both [LIGHT_CARD] (`#F7F7F7`), the
 * page behind them is [LIGHT_PAGE] (`#F0F0F0`), and the floating controls are
 * [surfaceContainerHighest] — overridden to white here — so a chip, capsule or jump
 * button is the brighter white group. The library's light palette cannot express that
 * by itself: `surfaceContainerHighest` is `#E8E8E8`, a grey, while `surfaceContainer`
 * and `background` are both pure white. The dark scheme already has the two groups —
 * `#242424` for the cards and history ground, `#2D2D2D` for the floating controls —
 * so it is untouched.
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
            lightColors = lightColorScheme(
                surface = LIGHT_PAGE,
                background = LIGHT_CARD,
                surfaceContainer = LIGHT_CARD,
                surfaceContainerHighest = Color.White,
                secondaryContainer = LIGHT_FIELD,
                // The library's own #F0F0F0 is *this app's page*, so every filled
                // secondary control — a group's "new configuration", a form's Cancel,
                // the fetch beside Add — drew its label on a rectangle of its own page
                // and disappeared. See `LIGHT_CONTROL`.
                secondaryVariant = LIGHT_CONTROL,
                disabledSecondaryVariant = LIGHT_CONTROL,
            ),
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
    // A page whose own bottom edge is dark in both schemes — the terminal is the one —
    // needs the light navigation-bar icons even in the light theme, or the handle is
    // drawn in the app's dark ink on the shell's black. The flag is a preference rather
    // than an imperative call into the window controller so that the theme stays the
    // one owner of that controller: a page raises it while it is composed, and the
    // `SideEffect` below combines it with the palette's own answer on every pass,
    // including a pass caused by a theme change while the page is still on screen.
    val darkBars = remember { mutableStateOf(false) }
    val barsForPage = darkBars.value
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark && !barsForPage
        }
    }

    CompositionLocalProvider(LocalDarkNavigationBars provides darkBars) {
        // The haptics are provided by the theme rather than by a page, because a window
        // dialog is a second composition: a value the page provided would be missing in
        // exactly the window whose buttons need it. See `PiHaptics.kt`.
        PiHapticHost {
            MiuixTheme(controller = controller, content = content)
        }
    }
}

/**
 * Whether the page on screen paints its own navigation strip dark, so the theme can
 * choose light bar icons.
 *
 * Held by the theme rather than written by a page into the window controller: the
 * controller is one object for the window, and two owners of it — the theme and every
 * page — cannot both be right after a theme change. A page creates its effect:
 * [DarkNavigationBar].
 */
private val LocalDarkNavigationBars = staticCompositionLocalOf { mutableStateOf(false) }

/**
 * Takes the light navigation-bar icons for as long as this page is composed.
 *
 * The terminal page is the only caller: its shell, its key bar and its banner are black
 * in both schemes, and the key bar paints the navigation strip black as its own bottom
 * padding (see `ExtraKeysRow`), so the gesture handle has to be light there even in the
 * light theme. On leaving, the theme's own answer is applied again by the same
 * `SideEffect` that read the flag.
 */
@Composable
fun DarkNavigationBar() {
    val dark = LocalDarkNavigationBars.current
    DisposableEffect(dark) {
        dark.value = true
        onDispose { dark.value = false }
    }
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
/**
 * The material a floating control is drawn from: OS4 glass over the page's backdrop.
 *
 * Miuix 0.9.5-beta02's `miuix-glass` makes a real backdrop blur possible: [rememberPiBackdrop]
 * records the page's own drawing into a `LayerBackdrop`, and [glassPanel] samples it
 * behind a shape — the page's cards and text show through the material rather than a
 * flat colour pretending to be one. [LocalPiBackdrop] carries that layer down to the
 * controls; a page that never provides one (a control outside a page) gets the
 * opaque fallback instead.
 *
 * ## The fallback is still a fill, and why
 *
 * `isRuntimeShaderSupported()` is false below API 33 and on a device whose GPU cannot
 * run the blur shader. The material then falls back to [fill], the role that was the
 * whole of the old design: `surfaceContainerHighest`, white in the light scheme and
 * `#2D2D2D` in the dark one. That is the same two-group palette the cards use, so a
 * fallback control is still a step above the page rather than a tint of it.
 *
 * ## A tint was rejected, again
 *
 * The old design tried white at 80/90/100% in light and 14/20/24% in dark; the light
 * control ended opaque while the dark one stayed translucent, and a row scrolling
 * underneath showed through the capsule. The glass panel has the same rule: its body
 * is the blurred *backdrop*, not a wash of the surface colour, and its fallback is
 * opaque. A translucent fill would be neither.
 *
 * ## The material is the one the bar's buttons use
 *
 * `glassPanel` with no `material` draws the blurred backdrop alone. Over a page with
 * text and cards that is a legible piece of glass, but over an empty page it is the
 * page colour: the conversation header's leading `GlassIconButton` and its trailing
 * `PiGlass.panel` were then two different materials, measured at rgb(239) and
 * rgb(230) on the light scheme. [material] is now passed to both kinds of control —
 * `GlassTopAppBar`'s buttons, every `GlassIconButton` on the chat page, and
 * `PiGlass.panel` — so every capsule, chip and field is cut from one body. On the
 * empty light conversation `puredThinGlass` measured rgb(230) against the rgb(240)
 * page, i.e. darker than the page and reading as a hole; the popup material at 28dp
 * measures rgb(249) on the same page, a visible nine-step lift.
 */
object PiGlass {

    /**
     * The soft edge under a floating control: small enough not to halo a 32dp chip.
     *
     * `GlassIconButton` takes the same value for the header's leading disc, so the two
     * ends of one row are the same object rather than one shadowed capsule beside one
     * flat disc.
     */
    val floatingShadow: GlassShadow = GlassShadow(
        color = Color.Black.copy(alpha = 0.04f),
        offsetX = 0f,
        offsetY = 1f,
        radius = 10f,
        dispersion = 0.5f,
    )

    /** The fill used when the device has no backdrop renderer. */
    @Composable
    fun fill(): Color = MiuixTheme.colorScheme.surfaceContainerHighest

    /**
     * The body every PiKit glass control is cut from.
     *
     * Miuix's `puredThinGlass` is tuned for a bar over a page with text under it: its two
     * white layers move a flat `#F0F0F0` page by only a few RGB steps, and an empty
     * conversation — the one screen with nothing scrolling under the header or composer —
     * therefore drew its capsules and chips as the page itself. The popup material is the
     * library's own stronger mix (60% white in both colour layers) for a control that has
     * to stand as an object without text behind it; the blur is reduced from the popup's
     * 60dp to [FLOATING_BLUR_RADIUS] because these controls are small and sit over a
     * live transcript, where 60dp of blur is spread and expensive without becoming more
     * legible.
     */
    @Composable
    fun material(): GlassMaterial {
        val dark = MiuixTheme.colorScheme.background.luminance() < DARK_BASE_LUMINANCE
        return remember(dark) {
            GlassMaterials.popupViewGlass(dark).copy(blurRadius = FLOATING_BLUR_RADIUS)
        }
    }

    /**
     * A shape cut from the page's glass.
     *
     * The fallback is the old opaque fill clipped to [shape], so every call site keeps a
     * visible control on hardware without the shader. The stroke is the OS4 bloom edge
     * at the small radius, picked for the theme the same way Miuix's own buttons do.
     */
    @Composable
    fun panel(shape: Shape, alpha: Float = 1f): Modifier {
        val dark = MiuixTheme.colorScheme.background.luminance() < DARK_BASE_LUMINANCE
        return Modifier.glassPanel(
            backdrop = LocalPiBackdrop.current,
            shape = shape.toGlassShape(),
            alpha = alpha,
            material = material(),
            // The material supplies the body, so the bar's refraction pass is not a
            // second one: GlassIconButton draws through `glass(shading = false)`.
            shading = false,
            stroke = GlassEdgeStrokes.forTheme(
                isDark = dark,
                light = GlassEdgeStrokes.SmallLight,
                dark = GlassEdgeStrokes.SmallDark,
            ),
            // A floating control is small; the panel shadow's 64-unit spread is the
            // bar/panel shadow and reads as a halo around a 32dp chip. This is the
            // same silhouette at a floating-control radius.
            shadow = floatingShadow,
            fallback = Modifier.clip(shape).background(fill()),
        )
    }
}

/** The same surface as [PiGlass.panel], in a modifier chain. */
@Composable
fun Modifier.piGlass(shape: Shape, alpha: Float = 1f): Modifier =
    this.then(PiGlass.panel(shape, alpha))

/**
 * The backdrop a page's glass controls sample.
 *
 * `null` outside a page that provides one, or on a device without runtime shaders;
 * [PiGlass.panel] reads that as "draw the opaque fallback".
 */
val LocalPiBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Records this subtree into a backdrop the page's glass controls can sample.
 *
 * The layer is created only when the device supports the shader: `rememberLayerBackdrop`
 * allocates a render target and keeps a copy of every frame, which is not a cost to pay
 * on hardware whose `glassPanel` would ignore it. The base surface is drawn first so a
 * page that does not paint its own background still gives the glass the page colour
 * rather than transparent black.
 */
@Composable
fun rememberPiBackdrop(base: Color = MiuixTheme.colorScheme.surface): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    return rememberLayerBackdrop {
        drawRect(base)
        drawContent()
    }
}

/** The glass renderer's equivalent of a Compose [Shape]. */
private fun Shape.toGlassShape(): GlassShape = when (this) {
    is GlassShape -> this
    is RoundedCornerShape -> GlassShape(topStart, topEnd, bottomEnd, bottomStart)
    else -> GlassShape(PiCorner.radius)
}

object PiTone {

    @Composable
    fun live(): Color = MiuixTheme.colorScheme.onTertiaryContainer

    @Composable
    fun liveContainer(): Color = MiuixTheme.colorScheme.tertiaryContainer

    /**
     * The ink of a finished-and-unread dot.
     *
     * The palette has an accent, an error pair and greys, and no green: "done" was never
     * a state this interface drew. The drawer needs it now, as the counterpart of the
     * accent's "answering", so it is two fixed greens rather than a role — one that
     * carries on white (4.6:1) and one that carries on black (7.1:1).
     */
    @Composable
    fun done(): Color = if (MiuixTheme.colorScheme.surface.luminance() < DARK_BASE_LUMINANCE) {
        Color(0xFF4CD07E)
    } else {
        Color(0xFF1FA855)
    }

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
     * `surface`, and the reader's own `secondaryContainer` prompt — and a single
     * palette role cannot be a step away from all three. Measured on the library's
     * own light scheme, `secondaryContainer` (#F0F0F0) is a 15-point step away from
     * the white card and the same colour as the app's `#F0F0F0` page, so the same
     * block read as a well in the transcript and disappeared in the manual; in the
     * dark scheme `surfaceContainerHigh` *is* the card's own #242424 and the block
     * disappeared outright.
     *
     * A wash of `onSurface` at one alpha answers all three at once, because it is
     * not a colour but a *contrast*: black at 10% over white is #E6E6E6, over the
     * `#F0F0F0` page it is #D8D8D8, and over the tinted prompt #D8D8D8 — the same
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
     * transparent over *the page*, which is `surface`: #F0F0F0 in the light scheme and
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
 * 0.2 is between the light scheme's white card (1.0) and its `#F0F0F0` page (0.87) on
 * one side, and the dark scheme's #242424 card (0.017) on the other — no base the
 * palette offers is near the boundary, so this is a test of *which scheme* rather than
 * a taste threshold. It is not the luminance comparison the chip's KDoc rejects: that
 * one asked how far apart two roles were, this one asks how much accent a base can
 * absorb.
 */
private const val DARK_BASE_LUMINANCE = 0.2f

/**
 * The blur radius a floating control's glass is cut with.
 *
 * Miuix's popup material ships at 60dp for a menu-sized panel; a 32dp chip and a 42dp
 * capsule do not need that reach, and the composer can hold six of them over a live
 * transcript. 28dp keeps the frost visible while the blur stays a small surface's cost.
 */
private val FLOATING_BLUR_RADIUS = 28.dp

/**
 * The light scheme's page: `#F0F0F0`, one step below Miuix's `#F7F7F7` page.
 *
 * It is the field behind the answer cards and the history drawer. The card and drawer
 * ground share [LIGHT_CARD] so the two read as one group, and the floating controls take
 * white from [surfaceContainerHighest]. The dark scheme already has that two-group
 * arrangement — #242424 for cards and history, #2D2D2D for floating controls — so it is
 * untouched.
 */
private val LIGHT_PAGE = Color(0xFFF0F0F0)

/** The light scheme's answer-card and history-ground group: `#F7F7F7`. */
private val LIGHT_CARD = Color(0xFFF7F7F7)

/** The light scheme's field fill: a step below the card group, `#E8E8E8`. */
private val LIGHT_FIELD = Color(0xFFE8E8E8)

/**
 * The light scheme's secondary-control fill: `#E6E6E6`, one step below the page.
 *
 * The library fills a secondary `Button` and `TextButton` with `secondaryVariant`, which
 * its own scheme sets to `#F0F0F0` — one step below *its* page (`#F7F7F7`). This app
 * overrides the page to `#F0F0F0` and the card to `#F7F7F7`, so the library's fill became
 * the page exactly: the history page's "new configuration" row, the model form's Cancel
 * and Fetch, a preset's Restore, and a dialog answer whose panel was near the page all
 * drew a label with no visible button under it. `#E6E6E6` is the library's own `secondary`
 * grey, so it is a colour the palette already has rather than a new one; it sits 10 steps
 * below the page and 17 below the card, and the scheme's `onSecondaryVariant` ink
 * (`#303030`) is 10:1 on it.
 *
 * The disabled fill moves with it, and to the same value: the library's disabled secondary
 * (`#F2F2F2`) is *lighter* than this app's page, so a disabled button would have vanished
 * in the one state that most needs a shape. A disabled secondary control keeps its fill and
 * fades its ink instead — `disabledOnSecondaryVariant` — which is also how the capsule
 * controls read.
 */
private val LIGHT_CONTROL = Color(0xFFE6E6E6)

/**
 * How much of the surface's own ink a quotation's fill is washed with.
 *
 * 10% is the smallest step that reads as a *well* on the lightest surface the app
 * draws (the `#F0F0F0` page, where it lands at `#D8D8D8`) rather than as a JPEG
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
     * The file listing draws one card per entry, and two adjacent cards need a gap wider
     * than the gap *inside* a row or the column reads as one panel with a line drawn
     * through it. 6dp is still a seam and not a space between sections: the grouping is
     * carried by the row's own fill and outline, and the gap only has to be wide enough
     * that two outlines are seen as two.
     *
     * The conversation history is the other list beside it and is *not* this: its rows are
     * flat rather than cards, so it spaces them at 2dp — there is no outline to separate.
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
