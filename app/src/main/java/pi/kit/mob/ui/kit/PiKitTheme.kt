package pi.kit.mob.ui.kit

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.ThemeMode
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
 */
object PiTone {

    @Composable
    fun live(): androidx.compose.ui.graphics.Color =
        MiuixTheme.colorScheme.onTertiaryContainer

    @Composable
    fun liveContainer(): androidx.compose.ui.graphics.Color =
        MiuixTheme.colorScheme.tertiaryContainer

    @Composable
    fun error(): androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.error

    @Composable
    fun errorContainer(): androidx.compose.ui.graphics.Color =
        MiuixTheme.colorScheme.errorContainer
}
