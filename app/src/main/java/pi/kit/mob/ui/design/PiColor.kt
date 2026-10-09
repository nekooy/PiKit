/*
 * PiKit's palette, on Material 3's expressive tonal system.
 *
 * ## The direction
 *
 * The app is a pocket Linux with an agent attached, so the surfaces have to stay
 * quiet enough that a terminal and a transcript can be the loudest things on the
 * screen, while the chrome still has to feel like something rather than like a
 * settings form. That is what the scheme below is: cool neutrals carrying very low
 * chroma, and one saturated accent. The accent is the *user's* choice
 * (`ThemeColor`) and blue by default; the violet-indigo written into the two
 * schemes below is the app's own shipped accent, which is also what
 * `ThemeColor.INDIGO` derives its roles from.
 *
 * ## Why three roles and not two
 *
 * `primary` is the accent and it is the app's own voice — selection, the active
 * tab, primary actions, section names, the launcher mark. `secondary` is that same
 * hue desaturated, so a chip or a supporting container can sit next to a primary
 * fill without competing with it. `tertiary` is the role that carries meaning
 * rather than decoration: it is what `PiTone.Live` draws — a turn that is
 * streaming, an agent that is working, a process that is running — and it is the
 * accent's hue rotated a little, so the "live" tone is a quieter neighbour of the
 * accent rather than a colour from outside the scheme. A colour with a single job
 * is a colour a reader learns, which is the whole of the expressive colour tactic
 * ("use contrast to emphasize the main takeaway") applied to one thing at a time;
 * the job is the same, the hue is the user's.
 *
 * ## Why the surfaces are a strict ramp
 *
 * M3 expresses hierarchy with surface *tone* rather than with shadow, and the
 * app leans on that: content sits on `surface`, a card or a raised group sits on
 * `surfaceContainerLow`, the chrome behind a page title sits on
 * `surfaceContainer`, and an inset or a pressed row sits on one of the two above
 * that. The five values are close by design — the instruction in the shape
 * guidance not to over-round information-dense components has a colour twin,
 * which is that a step that reads as a *different panel* rather than as a step
 * makes every list look like a stack of unrelated slabs.
 *
 * Both schemes are written out in full rather than derived at run time: dynamic
 * colour is deliberately not used, because the terminal's own sixteen colours and
 * the transcript's syntax tinting are fixed, and a wallpaper-derived palette
 * could land them on top of each other.
 *
 * ## The hue is the choice; the lightness is not
 *
 * What a user has an opinion about is the accent (`ThemeColor`), so
 * `accentScheme` takes the two literals below and moves their *hue*: the ramp and
 * the outlines keep the lightness and the very low chroma they were designed with,
 * and the accent roles are re-derived at a fixed set of lightness steps. The
 * `primary`, `secondary` and `tertiary` values written into [PiLightScheme] and
 * [PiDarkScheme] are therefore never what a screen is drawn with — they are what
 * the app would be without the preference, and `ThemeColor.INDIGO` is that same hue
 * re-derived.
 *
 * What deliberately does **not** move is the lightness ramp and the error roles.
 * The ramp is what the transcript and the terminal are read on, and a preference
 * that moved it would change how the app's own content reads; a failure is red in
 * every scheme, because it is the one colour that has to mean the same thing
 * whatever the user picked.
 */
package pi.kit.mob.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

internal val PiLightScheme: ColorScheme = lightColorScheme(
    // Violet-indigo: the app's own voice.
    primary = Color(0xFF5A4FCF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF1B0F5E),
    inversePrimary = Color(0xFFC6BFFF),

    // Slate-violet: supports primary without competing with it.
    secondary = Color(0xFF5C5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E0F9),
    onSecondaryContainer = Color(0xFF191A2C),

    // Coral: live state, and nothing else.
    tertiary = Color(0xFFA23E28),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF3B0A00),

    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE5E0EC),
    onSurfaceVariant = Color(0xFF48454E),
    surfaceTint = Color(0xFF5A4FCF),

    surfaceBright = Color(0xFFFCF8FF),
    surfaceDim = Color(0xFFDDD8E3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1ECF7),
    surfaceContainerHigh = Color(0xFFECE6F1),
    surfaceContainerHighest = Color(0xFFE6E0EB),

    inverseSurface = Color(0xFF302F37),
    inverseOnSurface = Color(0xFFF3EFF7),

    // The fixed roles keep their contrast when a container inverts, which is what
    // makes the live tone legible on the terminal's own background. Their values are
    // placeholders: `accentScheme` derives them from the chosen hue (they are the
    // same in both schemes by definition).
    primaryFixed = Color(0xFFE4DFFF),
    primaryFixedDim = Color(0xFFC6BFFF),
    onPrimaryFixed = Color(0xFF1B0F5E),
    onPrimaryFixedVariant = Color(0xFF4234A8),
    secondaryFixed = Color(0xFFE2E0F9),
    secondaryFixedDim = Color(0xFFC6C4DC),
    onSecondaryFixed = Color(0xFF191A2C),
    onSecondaryFixedVariant = Color(0xFF444559),
    tertiaryFixed = Color(0xFFFFDBD0),
    tertiaryFixedDim = Color(0xFFFFB59D),
    onTertiaryFixed = Color(0xFF3B0A00),
    onTertiaryFixedVariant = Color(0xFF7D2C10),

    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4CF),
    scrim = Color(0xFF000000),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val PiDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2B1C87),
    primaryContainer = Color(0xFF4234A8),
    onPrimaryContainer = Color(0xFFE4DFFF),
    inversePrimary = Color(0xFF5A4FCF),

    secondary = Color(0xFFC6C4DC),
    onSecondary = Color(0xFF2E3042),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE2E0F9),

    tertiary = Color(0xFFFFB59D),
    onTertiary = Color(0xFF5D1800),
    tertiaryContainer = Color(0xFF7F2C11),
    onTertiaryContainer = Color(0xFFFFDBD0),

    background = Color(0xFF131218),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131218),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF48454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    surfaceTint = Color(0xFFC6BFFF),

    surfaceBright = Color(0xFF39373F),
    surfaceDim = Color(0xFF131218),
    surfaceContainerLowest = Color(0xFF0D0C12),
    surfaceContainerLow = Color(0xFF1B1A21),
    surfaceContainer = Color(0xFF1F1E26),
    surfaceContainerHigh = Color(0xFF2A2832),
    surfaceContainerHighest = Color(0xFF35323E),

    inverseSurface = Color(0xFFE5E1E9),
    inverseOnSurface = Color(0xFF302F37),

    primaryFixed = Color(0xFFE4DFFF),
    primaryFixedDim = Color(0xFFC6BFFF),
    onPrimaryFixed = Color(0xFF1B0F5E),
    onPrimaryFixedVariant = Color(0xFF4234A8),
    secondaryFixed = Color(0xFFE2E0F9),
    secondaryFixedDim = Color(0xFFC6C4DC),
    onSecondaryFixed = Color(0xFF191A2C),
    onSecondaryFixedVariant = Color(0xFF444559),
    tertiaryFixed = Color(0xFFFFDBD0),
    tertiaryFixedDim = Color(0xFFFFB59D),
    onTertiaryFixed = Color(0xFF3B0A00),
    onTertiaryFixedVariant = Color(0xFF7D2C10),

    outline = Color(0xFF948F9A),
    outlineVariant = Color(0xFF48454E),
    scrim = Color(0xFF000000),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/*
 * The scheme, derived from one seed.
 *
 * ## Two halves, and the second one is why the first looked wrong
 *
 * Deriving only the accent left the *neutral ramp* violet — the app's old
 * identity — while the accent was the user's choice. Blue buttons on lavender
 * surfaces is the worst of both: the greys read as dirty rather than as quiet,
 * because a low-chroma colour next to a saturated one is read as *that* colour
 * drained, not as a neutral. So the ramp is re-hued here too: every neutral role
 * keeps its own lightness and its own (very low) chroma and takes the seed's hue.
 * A surface is then a blue-grey under a blue accent and a green-grey under a green
 * one, which is what the palette did before the choice existed — 0.4.1's greys are
 * `#F8F9FE` through `#EDEEF2`, all roughly hue 210, the same family as its
 * `#35597F` primary. [retinted] is that move, and it is the difference between a
 * scheme that *wears* its accent and one that *is* its accent.
 *
 * ## The accent roles, at fixed lightness
 *
 * Each accent role is the seed's hue at a fixed lightness: 0.45 for a light
 * scheme's primary, 0.90 for its container, 0.16 for the ink on that container,
 * and the dark scheme's mirror images (0.78, 0.30, 0.90, 0.16). Fixing lightness
 * rather than contrast is what makes eight hues interchangeable: a yellow held at
 * the blue's contrast ratio is a brown, and a palette whose roles move per hue is
 * a palette where one choice is legible and another is not. The saturation is the
 * seed's own, clamped to 0.35–0.9, and each container is a *fraction* of it — 0.70
 * for a container, 0.50 for a secondary surface — so a vivid seed gives a tinted
 * container rather than a poster.
 *
 * ## Tertiary is the accent's neighbour, not a reserved coral
 *
 * The role used to be fixed coral, on the theory that *live state* deserved a hue
 * of its own. What that produced in practice was an orange patch in an otherwise
 * blue interface — a working pill and an agent mark coloured by something the user
 * had not chosen and could not change. It is now the seed's hue rotated 14° at a
 * lower saturation: still a distinct tone from `primary` (which is what the role is
 * for — `PiTone.Live` has to be tellable from `PiTone.Accent`), no longer a colour
 * from outside the scheme. 0.4.1's own tertiary was `#3F6373` against a `#35597F`
 * primary, which is the same relationship: a neighbouring hue, quieter.
 */
internal fun accentScheme(base: ColorScheme, seed: Color, dark: Boolean): ColorScheme {
    val (hue, seedSaturation, _) = seed.toHsl()
    val s = seedSaturation.coerceIn(0.35f, 0.9f)
    val neighbouring = hue - 14f

    // The fixed roles are the same in both schemes by definition — "fixed" is what
    // they are — so they are computed once.
    val fixed = base.copy(
        primaryFixed = hsl(hue, s * 0.60f, 0.93f),
        primaryFixedDim = hsl(hue, s * 0.70f, 0.83f),
        onPrimaryFixed = hsl(hue, s * 0.72f, 0.15f),
        onPrimaryFixedVariant = hsl(hue, s * 0.72f, 0.32f),
        secondaryFixed = hsl(hue, s * 0.45f, 0.93f),
        secondaryFixedDim = hsl(hue, s * 0.45f, 0.84f),
        onSecondaryFixed = hsl(hue, s * 0.55f, 0.13f),
        onSecondaryFixedVariant = hsl(hue, s * 0.50f, 0.33f),
        tertiaryFixed = hsl(neighbouring, s * 0.45f, 0.92f),
        tertiaryFixedDim = hsl(neighbouring, s * 0.50f, 0.82f),
        onTertiaryFixed = hsl(neighbouring, s * 0.50f, 0.13f),
        onTertiaryFixedVariant = hsl(neighbouring, s * 0.50f, 0.32f),
    )

    return if (dark) {
        fixed.retinted(hue).copy(
            primary = hsl(hue, s * 0.75f, 0.78f),
            onPrimary = hsl(hue, s * 0.80f, 0.16f),
            primaryContainer = hsl(hue, s * 0.55f, 0.30f),
            onPrimaryContainer = hsl(hue, s * 0.70f, 0.90f),
            inversePrimary = hsl(hue, s, 0.45f),
            surfaceTint = hsl(hue, s * 0.75f, 0.78f),
            secondary = hsl(hue, s * 0.25f, 0.76f),
            onSecondary = hsl(hue, s * 0.30f, 0.20f),
            secondaryContainer = hsl(hue, s * 0.28f, 0.26f),
            onSecondaryContainer = hsl(hue, s * 0.35f, 0.89f),
            tertiary = hsl(neighbouring, s * 0.45f, 0.76f),
            onTertiary = hsl(neighbouring, s * 0.50f, 0.18f),
            tertiaryContainer = hsl(neighbouring, s * 0.40f, 0.25f),
            onTertiaryContainer = hsl(neighbouring, s * 0.45f, 0.88f),
        )
    } else {
        fixed.retinted(hue).copy(
            primary = hsl(hue, s, 0.45f),
            onPrimary = Color.White,
            primaryContainer = hsl(hue, s * 0.70f, 0.90f),
            onPrimaryContainer = hsl(hue, s * 0.72f, 0.16f),
            inversePrimary = hsl(hue, s * 0.70f, 0.72f),
            surfaceTint = hsl(hue, s, 0.45f),
            secondary = hsl(hue, s * 0.33f, 0.42f),
            onSecondary = Color.White,
            secondaryContainer = hsl(hue, s * 0.50f, 0.91f),
            onSecondaryContainer = hsl(hue, s * 0.55f, 0.13f),
            tertiary = hsl(neighbouring, s * 0.40f, 0.35f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(neighbouring, s * 0.50f, 0.885f),
            onTertiaryContainer = hsl(neighbouring, s * 0.50f, 0.13f),
        )
    }
}

/**
 * The scheme's neutrals in the seed's hue: same lightness, same chroma, new hue.
 *
 * Every role the accent does not own — the five surface steps, the two outline
 * greys, the on-colours, the inverses — passes through here. The error roles do
 * not: a failure is red in every scheme, and re-hueing it would make the one colour
 * in the app that means "this broke" a matter of preference.
 *
 * A role that is a pure grey or a pure white (saturation 0) comes back unchanged,
 * which is what keeps `surfaceContainerLowest` exactly white in the light scheme
 * rather than white-with-a-tint.
 */
private fun ColorScheme.retinted(hue: Float): ColorScheme = copy(
    background = background.rehued(hue),
    onBackground = onBackground.rehued(hue),
    surface = surface.rehued(hue),
    onSurface = onSurface.rehued(hue),
    surfaceVariant = surfaceVariant.rehued(hue),
    onSurfaceVariant = onSurfaceVariant.rehued(hue),
    surfaceBright = surfaceBright.rehued(hue),
    surfaceDim = surfaceDim.rehued(hue),
    surfaceContainerLowest = surfaceContainerLowest.rehued(hue),
    surfaceContainerLow = surfaceContainerLow.rehued(hue),
    surfaceContainer = surfaceContainer.rehued(hue),
    surfaceContainerHigh = surfaceContainerHigh.rehued(hue),
    surfaceContainerHighest = surfaceContainerHighest.rehued(hue),
    inverseSurface = inverseSurface.rehued(hue),
    inverseOnSurface = inverseOnSurface.rehued(hue),
    outline = outline.rehued(hue),
    outlineVariant = outlineVariant.rehued(hue),
)

private fun Color.rehued(hue: Float): Color {
    val (_, saturation, lightness) = toHsl()
    return hsl(hue, saturation, lightness)
}

/** One colour's hue, saturation and lightness, each in 0..1 apart from the hue in degrees. */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        red -> 60f * (((green - blue) / d) % 6f)
        green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }
    return Triple(if (h < 0f) h + 360f else h, s.coerceIn(0f, 1f), l)
}

/** The inverse of [toHsl]: a colour from a hue in degrees and two 0..1 factors. */
private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val h = (((hue % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(r + m, g + m, b + m, 1f)
}
