/*
 * PiKit's palette, on Material 3's expressive tonal system.
 *
 * ## The direction
 *
 * The app is a pocket Linux with an agent attached, so the surfaces have to stay
 * quiet enough that a terminal and a transcript can be the loudest things on the
 * screen, while the chrome still has to feel like something rather than like a
 * settings form. That is what the scheme below is: cool violet-tinted neutrals
 * carrying very low chroma, one saturated violet-indigo as the identity, and one
 * warm coral kept in reserve.
 *
 * ## Why three roles and not two
 *
 * `primary` is violet-indigo and it is the app's own voice — selection, the
 * active tab, primary actions, the launcher mark. `secondary` is a desaturated
 * slate-violet, so a chip or a supporting container can sit next to a primary
 * fill without competing with it. `tertiary` is the one that carries meaning
 * rather than decoration: **coral is reserved for live state** — a turn that is
 * streaming, an agent that is working, a process that is running. A colour with a
 * single job is a colour a reader learns, which is the whole of the expressive
 * colour tactic ("use contrast to emphasize the main takeaway") applied to one
 * thing at a time.
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
 */
package pi.kit.mob.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

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
    // makes the coral legible on the terminal's own background.
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
