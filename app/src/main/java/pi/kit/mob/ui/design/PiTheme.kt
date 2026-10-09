/*
 * The one place the app's theme is assembled.
 *
 * ## Why `MaterialExpressiveTheme` and not `MaterialTheme`
 *
 * `MaterialTheme`'s newer overload takes a `MotionScheme` and this one is named
 * for what it is. The difference is not cosmetic: `MaterialExpressiveTheme` also
 * registers `LocalUsingExpressiveTheme`, which is what switches the *components*
 * over — the flexible app bars, the button groups, the flexible navigation bar and
 * the shape-morphing indicators all read it to decide whether to draw their
 * expressive form or their baseline one. Setting a motion scheme and leaving that
 * local false gives an app that animates like M3 Expressive and looks like M3.
 *
 * ## Typography: the emphasized scale, and no bundled font
 *
 * Material's expressive type scale is thirty styles — the fifteen baseline ones
 * plus an emphasized variant of each, which is the same size and line height at a
 * heavier variable weight. The library ships both, so [MaterialTheme.typography]
 * already carries `displayLargeEmphasized` through `labelSmallEmphasized` and
 * nothing here has to define them.
 *
 * The app deliberately does **not** bundle a brand typeface. Material's own
 * default is the platform's, the app's text is overwhelmingly machine output —
 * a transcript, a terminal, file paths — where the reader's familiarity with the
 * system face is worth more than a distinctive one, and the expressive hierarchy
 * this app needs is carried by the emphasized styles and by size contrast rather
 * than by a font. A bundled face would also be a megabyte of APK and a licence to
 * keep in step, for a difference the app's own content would drown out.
 *
 * ## System bars
 *
 * `enableEdgeToEdge()` makes the app responsible for its own bar icons, and which
 * colour they have to be is a property of the theme, not of a page. It is handled
 * here rather than at each screen because the alternative — every page remembering
 * to say it — is the bug where the clock is invisible on exactly one screen.
 */
package pi.kit.mob.ui.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * PiKit's theme.
 *
 * [darkTheme] is passed in rather than read here: the preference lives in the
 * app's settings, and `isSystemInDarkTheme()` is only the right answer for
 * `ThemeMode.SYSTEM`. The default is that same answer so a preview or a test can
 * call this with one argument.
 */
@Composable
fun PiKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) PiDarkScheme else PiLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            // Dark icons on a light scheme's background, light icons on a dark one.
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        // The expressive scheme product-wide. The guidance is that most motion in
        // a product should share one scheme; the alternative — expressive for
        // hero moments and standard elsewhere — was rejected because this app's
        // most-used motion *is* its hero motion (a sheet arriving, a turn
        // starting), and mixing the two makes the same gesture feel different in
        // two places that should feel the same.
        motionScheme = MotionScheme.expressive(),
        shapes = PiThemeShapes,
        content = content,
    )
}
