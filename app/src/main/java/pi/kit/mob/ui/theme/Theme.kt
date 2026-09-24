package pi.kit.mob.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * PiKit's palette.
 *
 * The light scheme is the reference design: a blue-grey band behind the status
 * bar and the page title ([surfaceContainer]), near-white content
 * ([surface]), and a slightly darker grey for the navigation bar and the
 * gesture strip below it ([surfaceContainerHighest]). Those three greys are
 * deliberately close in value — the header, the content and the navigation bar
 * have to read as one surface with two gentle steps, not as three panels.
 *
 * The dark scheme is derived from the same hues rather than being a second
 * design: it is what a user in system dark mode sees, and it has to keep the
 * same structure so the page headers and the navigation bar still line up.
 */
private val PiKitLight = lightColorScheme(
    primary = Color(0xFF35597F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E4F4),
    onPrimaryContainer = Color(0xFF10293F),
    inversePrimary = Color(0xFF9CC4E4),

    secondary = Color(0xFF4E6B87),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F2),
    onSecondaryContainer = Color(0xFF101F2C),

    tertiary = Color(0xFF3F6373),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E6F0),
    onTertiaryContainer = Color(0xFF132731),

    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF1A1F26),
    surface = Color(0xFFF8F9FE),
    onSurface = Color(0xFF1A1F26),
    surfaceVariant = Color(0xFFE4E9EF),
    onSurfaceVariant = Color(0xFF4A545F),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F9),
    // The page header band, also painted behind the status bar.
    surfaceContainer = Color(0xFFDCE3EB),
    surfaceContainerHigh = Color(0xFFE6EBF1),
    // The navigation bar and the gesture strip beneath it.
    surfaceContainerHighest = Color(0xFFEDEEF2),

    outline = Color(0xFF8B96A3),
    outlineVariant = Color(0xFFC7D0DA),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val PiKitDark = darkColorScheme(
    primary = Color(0xFF9CC4E4),
    onPrimary = Color(0xFF0A2A44),
    primaryContainer = Color(0xFF2A4A66),
    onPrimaryContainer = Color(0xFFCFE3F5),
    inversePrimary = Color(0xFF35597F),

    secondary = Color(0xFFB6C4D2),
    onSecondary = Color(0xFF1F2A34),
    secondaryContainer = Color(0xFF33414F),
    onSecondaryContainer = Color(0xFFDCE4EC),

    tertiary = Color(0xFFA8CBDC),
    onTertiary = Color(0xFF0B2029),
    tertiaryContainer = Color(0xFF27404C),
    onTertiaryContainer = Color(0xFFC4E3F1),

    background = Color(0xFF0F1319),
    onBackground = Color(0xFFE2E6EB),
    surface = Color(0xFF0F1319),
    onSurface = Color(0xFFE2E6EB),
    surfaceVariant = Color(0xFF3A434E),
    onSurfaceVariant = Color(0xFFB2BCC7),

    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF141922),
    surfaceContainer = Color(0xFF1A2028),
    surfaceContainerHigh = Color(0xFF222A34),
    surfaceContainerHighest = Color(0xFF171C23),

    outline = Color(0xFF6B7683),
    outlineVariant = Color(0xFF333C46),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * PiKit's corner radii.
 *
 * Material3's stock `medium` is 12dp and a 16dp step was still read as almost
 * square; the radii below are one notch further. A settings section card and its
 * rows now share a 22dp corner, so the press ripple and the card outline stop
 * looking like two different controls. Small keeps a tighter radius for chips
 * and grabbers; large and extraLarge step up from it toward the sheet corner.
 */
private val PiKitShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun PiKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PiKitDark else PiKitLight,
        shapes = PiKitShapes,
        content = content,
    )
}
