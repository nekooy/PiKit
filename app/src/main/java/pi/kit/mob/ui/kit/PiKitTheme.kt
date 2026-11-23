package pi.kit.mob.ui.kit

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import pi.kit.mob.data.AccentColor
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
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.abs

/**
 * The interface's theme: the library's palette, one page step deeper, the reader's
 * light/dark choice, and one hue of their own.
 *
 * ## What the accent moves, and what it leaves alone
 *
 * A `ThemeColor` seed used to go into a `ThemeController` as its `keyColor`, and the
 * library's material-colour engine derived all hundred and forty roles from it. That
 * was the wrong thing to own: a seeded tonal-spot scheme paints the neutrals as
 * tinted greys and the containers at tones tuned for Material's screens, so a page
 * was no longer drawn in the colours its components were designed for.
 *
 * The accent is back, with a narrower job. [accentRoles] derives only the roles the
 * accent owns — the primary pair and its containers, the disabled fills, the
 * tertiary container that draws *live*, and the slider's key points — at the
 * library's own lightness and saturation weights, and every neutral, error and
 * surface role stays exactly what `lightColorScheme()` / `darkColorScheme()` said.
 * [AccentColor.BLUE] is the default and is not derived at all: the library's blue is
 * returned whole, so a reader who never opens the swatches sees the palette the
 * rewrite settled on.
 *
 * The derivation is HSL on purpose. The one judgement a hue needs is its chroma;
 * lightness is what makes a filled button readable under `onPrimary`, and the
 * numbers below are the library's own light/dark pair (#3482FF / #277AF7) stated as
 * relationships rather than copied as literals.
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
    val lightColors = remember(settings.accentColor) { appLightScheme(settings.accentColor) }
    val darkColors = remember(settings.accentColor) { appDarkScheme(settings.accentColor) }
    val controller = remember(mode, lightColors, darkColors) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.System,
            lightColors = lightColors,
            darkColors = darkColors,
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
    val lightStatusBars = remember { mutableStateOf(false) }
    val barsForPage = darkBars.value
    val lightStatusForPage = lightStatusBars.value
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark && !lightStatusForPage
            bars.isAppearanceLightNavigationBars = !dark && !barsForPage
        }
    }

    CompositionLocalProvider(
        LocalDarkNavigationBars provides darkBars,
        LocalLightStatusBars provides lightStatusBars,
    ) {
        // The haptics are provided by the theme rather than by a page, because a window
        // dialog is a second composition: a value the page provided would be missing in
        // exactly the window whose buttons need it. See `PiHaptics.kt`.
        PiHapticHost {
            MiuixTheme(controller = controller, content = content)
        }
    }
}

/**
 * The light scheme: the library's roles, the app's two surface groups, and the accent.
 *
 * The surface roles are applied *after* the accent so a hue can never move the page,
 * card or control groups — the step that made the light scheme two groups is the one
 * thing the accent is not allowed to touch.
 */
private fun appLightScheme(accent: AccentColor): Colors =
    lightColorScheme()
        .withAccent(accent, dark = false)
        .copy(
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
        )

/**
 * The dark scheme: the library's, with the accent's roles when one was chosen.
 *
 * The dark palette already has the two surface groups (the page and cards share
 * `#242424`, the floating controls are `#2D2D2D`), so there is nothing to override.
 */
private fun appDarkScheme(accent: AccentColor): Colors =
    darkColorScheme().withAccent(accent, dark = true)

/**
 * Replaces the roles the accent owns and nothing else.
 *
 * [AccentColor.BLUE] returns the scheme unchanged: the library's blue *is* the
 * default accent, and deriving a near-copy of it would move the app's default
 * appearance for no answer the reader asked for.
 */
private fun Colors.withAccent(accent: AccentColor, dark: Boolean): Colors {
    if (accent == AccentColor.BLUE) return this
    val roles = accentRoles(accent.seed, dark)
    return copy(
        primary = roles.primary,
        onPrimary = roles.onPrimary,
        primaryVariant = roles.primaryVariant,
        onPrimaryVariant = roles.onPrimaryVariant,
        disabledPrimary = roles.disabledPrimary,
        disabledOnPrimary = roles.disabledOnPrimary,
        disabledPrimaryButton = roles.disabledPrimaryButton,
        disabledOnPrimaryButton = roles.disabledOnPrimaryButton,
        disabledPrimarySlider = roles.disabledPrimarySlider,
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = roles.onPrimaryContainer,
        tertiaryContainer = roles.tertiaryContainer,
        onTertiaryContainer = roles.onTertiaryContainer,
        tertiaryContainerVariant = roles.tertiaryContainer,
        sliderKeyPoint = roles.sliderKeyPoint,
        sliderKeyPointForeground = roles.sliderKeyPointForeground,
    )
}

/** The accent's own roles, after [accentRoles] has derived them for one scheme. */
private data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryVariant: Color,
    val onPrimaryVariant: Color,
    val disabledPrimary: Color,
    val disabledOnPrimary: Color,
    val disabledPrimaryButton: Color,
    val disabledOnPrimaryButton: Color,
    val disabledPrimarySlider: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val sliderKeyPoint: Color,
    val sliderKeyPointForeground: Color,
)

/**
 * One seed's accent roles for one scheme.
 *
 * ## The lightness is the library's, the hue is the reader's
 *
 * The default light/dark pair is HSL(217°, 100%, 60%) and HSL(216°, 93%, 56%) — the
 * same hue, saturation and a lightness three points apart. Every other accent keeps
 * its own hue and uses the same lightness, with its saturation clamped so a nearly
 * grey seed (green) still reads as a colour and a fully saturated one (teal) does
 * not turn into a neon sign.
 *
 * The container, disabled and live roles are fixed steps of that hue rather than
 * Material's tonal palette: the point is that the library's component shapes keep
 * the weights they were drawn with, so only the chroma moves.
 *
 * ## The ink is chosen, not pinned
 *
 * Miuix's own blue is dark enough for white, but an amber or a green at the same
 * lightness is not. [inkOn] asks the fill's luminance and returns the side of the
 * contrast pair that is legible, which is the same rule `PiAvatar` uses for its
 * discs.
 */
private fun accentRoles(seed: Color, dark: Boolean): AccentRoles {
    val (hue, sourceSaturation, _) = seed.toHsl()
    val saturation = sourceSaturation.coerceIn(0.45f, 0.85f)
    // The library's own light/dark weights: #3482FF is L60, #277AF7 is L56.
    val primary = hsl(hue, saturation, if (dark) 0.56f else 0.60f)

    return if (dark) {
        val container = hsl(hue, saturation * 0.55f, 0.45f)
        AccentRoles(
            primary = primary,
            onPrimary = inkOn(primary),
            primaryVariant = hsl(hue, saturation, 0.50f),
            onPrimaryVariant = hsl(hue, saturation * 0.45f, 0.80f),
            disabledPrimary = hsl(hue, saturation * 0.35f, 0.30f),
            disabledOnPrimary = hsl(hue, saturation * 0.25f, 0.50f),
            disabledPrimaryButton = hsl(hue, saturation * 0.35f, 0.30f),
            disabledOnPrimaryButton = hsl(hue, saturation * 0.25f, 0.55f),
            disabledPrimarySlider = hsl(hue, saturation * 0.35f, 0.38f),
            primaryContainer = container,
            onPrimaryContainer = inkOn(container),
            tertiaryContainer = hsl(hue, saturation * 0.45f, 0.26f),
            onTertiaryContainer = hsl(hue, saturation * 0.70f, 0.78f),
            sliderKeyPoint = primary.copy(alpha = 0.50f),
            sliderKeyPointForeground = primary,
        )
    } else {
        val container = hsl(hue, saturation * 0.70f, 0.68f)
        AccentRoles(
            primary = primary,
            onPrimary = inkOn(primary),
            primaryVariant = primary,
            onPrimaryVariant = hsl(hue, saturation * 0.35f, 0.84f),
            disabledPrimary = hsl(hue, saturation * 0.40f, 0.88f),
            disabledOnPrimary = hsl(hue, saturation * 0.20f, 0.96f),
            disabledPrimaryButton = hsl(hue, saturation * 0.40f, 0.88f),
            disabledOnPrimaryButton = hsl(hue, saturation * 0.30f, 0.72f),
            disabledPrimarySlider = hsl(hue, saturation * 0.40f, 0.82f),
            primaryContainer = container,
            onPrimaryContainer = inkOn(container),
            tertiaryContainer = hsl(hue, saturation * 0.45f, 0.94f),
            onTertiaryContainer = hsl(hue, saturation * 0.85f, 0.42f),
            sliderKeyPoint = primary.copy(alpha = 0.30f),
            sliderKeyPointForeground = primary,
        )
    }
}

/** The ink a filled accent control carries; see [accentRoles]'s KDoc. */
private fun inkOn(background: Color): Color =
    if (background.luminance() > 0.35f) Color.Black else Color.White

/** One colour's hue in degrees and its saturation, the two inputs an accent keeps. */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return Triple(0f, 0f, lightness)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return Triple(if (hue < 0f) hue + 360f else hue, saturation.coerceIn(0f, 1f), lightness)
}

/** The inverse of [toHsl]. */
private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = (((hue % 360f) + 360f) % 360f) / 60f
    val second = chroma * (1f - abs(sector % 2f - 1f))
    val (r, g, b) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val match = lightness - chroma / 2f
    return Color(r + match, g + match, b + match, 1f)
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
 * The status-bar twin of [LocalDarkNavigationBars], for the setup field.
 *
 * The setup screen is the one full-screen surface that is near-black in both schemes
 * and is returned before the root Scaffold: in the light theme the theme's own answer
 * is dark status-bar ink, which disappears on `SETUP_BACKGROUND`. The flag is raised
 * by [LightStatusBar] for as long as that screen is composed.
 */
private val LocalLightStatusBars = staticCompositionLocalOf { mutableStateOf(false) }

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
 * Takes the light status-bar icons for the dark setup surface.
 *
 * The setup screen is dark in both themes and sits above the page Scaffold, so it has
 * no page to set the polarity for it. Pair it with [DarkNavigationBar] when the same
 * screen paints its own bottom edge dark; the two flags are independent because the
 * terminal needs only the navigation half.
 */
@Composable
fun LightStatusBar() {
    val light = LocalLightStatusBars.current
    DisposableEffect(light) {
        light.value = true
        onDispose { light.value = false }
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
 * - **Live** is the tertiary pair — a pale wash of the accent with the accent's own
 *   ink. It used to be the `secondary` pair, which is a grey in the library's own
 *   scheme (a live pill on it was grey on grey); the accent derivation fills the
 *   tertiary container, so "in progress" is a quieter neighbour of the reader's own
 *   hue rather than a second colour. Under the default accent it is the library's
 *   pale blue.
 * - **Error** is the scheme's error pair, re-exported so a call site says *danger*
 *   rather than naming a role.
 * - **Inset** is the fill a *quotation* sits in — a code span, a fenced block, a
 *   chip on the reader's own card — and it is the one role in this object that is
 *   a wash rather than a pair, for the reason its own KDoc gives.
 */
/**
 * The material a floating control is drawn from: OS4 glass over the page's backdrop.
 *
 * Miuix 0.9.5-beta03's `miuix-glass` makes a real backdrop blur possible: [rememberPiBackdrop]
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
     * The fill of a card laid on a glass sheet or dialog.
     *
     * A `Card` defaults to `surfaceContainer`, and a glass panel's body is very nearly
     * that colour: measured on the dark scheme, the search-provider sheet drew its panel
     * at rgb(37) and the card of rows at rgb(36) — one step, so the list had no visible
     * frame and the sheet read as one flat surface. The ramp has no other role to reach
     * for: in the dark scheme `background`, `surfaceContainer` and `surfaceContainerHigh`
     * are all #242424, which is the same trap §13 records for every fill inside a
     * container. A wash of the surface's own ink is contrast rather than a fixed colour,
     * so it is the same relative step on the glass in either scheme; 4% is the value
     * Mishka's own `GlassBottomSheet` cards use (`SpeedDetailSheet`), and it puts the
     * dark card at rgb(45) on the rgb(37) panel and the light one at rgb(227) on
     * a panel of about rgb(235) — the group step a card has on a page, without becoming a second
     * panel inside the sheet.
     */
    @Composable
    fun cardOnGlass(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = CARD_ON_GLASS_ALPHA)

    /**
     * The fill behind quoted material: a code span, a fenced block, a chip.
     *
     * ## Why this is a wash of the surface's own ink
     *
     * A code block and an inline code span are drawn on *three* different
     * backgrounds — the answer card's `surfaceContainer`, a page's `surface`, and the
     * reader's own `secondaryContainer` prompt — and a single
     * palette role cannot be a step away from all three. Measured on the library's
     * own light scheme, `secondaryContainer` (#F0F0F0) is a 15-point step away from
     * the white card and the same colour as the app's `#F0F0F0` page, so the same
     * block read as a well in the transcript and disappeared on a #F0F0F0 page; in the
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
     * The fill of a secondary control on a floating glass surface.
     *
     * A settings card is `#F7F7F7` and the light scheme's `secondaryVariant` fill
     * (`LIGHT_CONTROL`, `#E6E6E6`) is a clear step below it. A glass dialog or sheet
     * body is not a card: the material's white mix over the page measures around
     * rgb(223), and `#E6E6E6` is then *lighter* than the panel and only seven steps
     * away — a button that reads as a word with no rectangle under it. A 15% wash of
     * the surface ink lands around rgb(190) on that panel and the same relative step
     * in the dark scheme, so a secondary answer on glass keeps the control shape
     * every page's secondary button already has. The primary and destructive fills
     * stay `primary` and `error`; this role is only the neutral one.
     */
    @Composable
    fun control(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = CONTROL_ALPHA)

    /**
     * The fill behind the reader's own message: the accent, mixed into the card colour.
     *
     * ## Why an accent mix rather than a container role
     *
     * The prompt bubble is the one object on the transcript that is not a card in the
     * palette's sense: it is the reader's half of the conversation, and the reader's
     * half is read as *their own hue* in every chat app the page is read beside. The
     * palette's containers are `primaryContainer` (a filled control that wants a
     * knockout ink, not the answer's ink) and `tertiaryContainer` (so pale in the
     * light scheme that it is nearly the page), so neither can be the bubble.
     *
     * The fill is composed: the palette's `surfaceContainer` (the colour an answer
     * card is) with `primary` mixed into it, at a share chosen from how much the base
     * can absorb — [PROMPT_SHARE_LIGHT] on white, [PROMPT_SHARE_DARK] on the dark
     * scheme's #242424. Under the default blue that lands at #DEEBFF and #25416C: one
     * pale blue, one navy, both unmistakably the accent's hue and both legible in
     * `onSurface` (15:1 and 7.4:1). Another accent gives the same two steps of its own
     * hue.
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
 * How much of the surface ink a secondary control on glass is washed with.
 *
 * The dialog/sheet panel's glass body measures rgb(223) in the light scheme; 15%
 * of the surface ink puts the control at rgb(190), the same relative step the
 * library's `secondaryVariant` gives a button on a `#F7` card.
 */
private const val CONTROL_ALPHA = 0.15f

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
 * How much of the surface ink a card on glass is washed with.
 *
 * Four percent is Mishka's own figure for a card inside `GlassBottomSheet`; it is small
 * enough that the card stays a group on the panel rather than a raised rectangle, and
 * large enough that the one-step dark ramp (`surfaceContainer` #242424 against the
 * panel's measured rgb(37)) becomes a visible group edge.
 */
private const val CARD_ON_GLASS_ALPHA = 0.04f

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

    /**
     * The corner of a floating menu.
     *
     * Not [radius], and the difference is the library's: Miuix cuts a *popup* panel at
     * 24dp — `GlassPopupDefaults.CornerRadius`, `GlassDropdownDefaults.CornerRadius` and
     * the dropdown preference's own panel all state it — while a card, field or button is
     * 16dp and the app draws those at [radius]. A menu is a surface that floats *over*
     * the page rather than one of its rows, and OS4 draws that family one step rounder so
     * a panel reads as an object laid on the page instead of as another card.
     *
     * It is a second constant rather than a scale for the same reason [radius] is one
     * number: the two values are the library's two, and the app only decides which family
     * a surface belongs to.
     */
    val popup: Dp = 24.dp
}

/**
 * The minimum height of a Miuix field that draws its placeholder through `label`.
 *
 * The library's label is a 17sp [Text] with the platform's font padding — a 23dp line
 * box at font scale 1 — while `BasicTextField` lays the typed line out at 19dp. The
 * label only takes part in the decoration's measurement while the field is empty, so
 * the field shrinks by 4dp when the first character replaces it: measured 182px → 171px
 * on the composer and 110px → 102px on the drawer search, both at 2× density.
 *
 * The floor is `24.sp`: the library's label measured 23dp on the drawer's field and
 * 24.5dp in a glass dialog, so 24sp absorbs most of the sub-pixel rounding without
 * visibly growing the field. `sp`, not `dp`, so the text-size preference scales the
 * floor with the label it stands in for. Apply it to every `TextField`/`GlassTextField`
 * that passes `label` with `useLabelAsPlaceholder = true`.
 */
@Composable
fun Modifier.piFieldHeightForPlaceholder(
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
): Modifier {
    val labelHeight = with(LocalDensity.current) { 24.sp.toDp() }
    return heightIn(min = labelHeight + insideMargin.height * 2)
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
