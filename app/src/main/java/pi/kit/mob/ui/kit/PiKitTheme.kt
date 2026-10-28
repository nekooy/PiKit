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
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * The interface's theme: Miuix's own engine, seeded from the reader's choice.
 *
 * ## One seed in, a palette out
 *
 * A Miuix palette is derived from a *key colour* by the same material-colour
 * code Android 12 uses for the wallpaper, so the app owns exactly one decision —
 * which hue — and the library owns the hundred and forty roles that decision
 * implies. That is why [PiSettings.themeColor] is a seed and not two palettes per
 * choice: the same arrangement the previous theme arrived at by hand (a hue at
 * fixed lightness steps) is what a `ThemeController` does natively, for every
 * role, including the neutrals and the containers.
 *
 * ## Dynamic colour is the same switch, with no seed
 *
 * [PiSettings.themeDynamic] asks for the wallpaper instead of the accent. It is
 * expressed as "no key colour", which makes the controller read the platform's
 * own palette (Android 12+) and fall back to its built-in scheme below that —
 * there is no separate code path here to keep in step with the platform.
 *
 * ## Mode
 *
 * [ThemeMode] is folded into the controller's own mode rather than read above the
 * theme in `MainActivity`: the library already knows how to follow the system
 * (`MonetSystem`), and having one authority for light/dark is what keeps a
 * dialog's window — which is a second window — from disagreeing with the page
 * behind it.
 */
@Composable
fun PiKitMiuixTheme(
    settings: PiSettings,
    content: @Composable () -> Unit,
) {
    val keyColor = if (settings.themeDynamic) null else settings.themeColor.seed
    val mode = when (settings.themeMode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.DARK -> ColorSchemeMode.MonetDark
    }
    val controller = remember(mode, keyColor) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = keyColor,
            // The default palette style and specification are the library's, and
            // deliberately not tuned here: this app has no business re-deciding
            // what a Miuix page looks like, which is the whole reason it is drawn
            // with one.
            colorSpec = ThemeColorSpec.Spec2021,
            paletteStyle = ThemePaletteStyle.TonalSpot,
        )
    }

    // `enableEdgeToEdge()` makes the app responsible for its own bar icons, and
    // which colour they have to be is a property of the theme rather than of a
    // page. Read from the same resolved dark state the palette uses, so a dialog's
    // separate window cannot disagree with the page behind it — and so this stays
    // one rule when the Material theme that used to do it is deleted.
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
 * The accent roles the transcript and the terminal inherit.
 *
 * Miuix's [top.yukonga.miuix.kmp.theme.Colors] has no role for "live" — the one
 * colour that means *a turn is streaming* — so the app names it once and draws it
 * from the palette's secondary pair, which is the seed's own hue, quieter than
 * `primary` and tellable from it. Everything else a screen needs already has a
 * name in the scheme; this is the single exception, kept here rather than repeated
 * at the two call sites that draw it.
 */
object PiTone {

    @Composable
    fun live(): androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.secondary

    @Composable
    fun liveContainer(): androidx.compose.ui.graphics.Color =
        MiuixTheme.colorScheme.secondaryContainer

    @Composable
    fun error(): androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.error

    @Composable
    fun errorContainer(): androidx.compose.ui.graphics.Color =
        MiuixTheme.colorScheme.errorContainer
}
