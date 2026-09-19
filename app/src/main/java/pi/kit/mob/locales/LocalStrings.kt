package pi.kit.mob.locales

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/**
 * The catalog for the language the user picked.
 *
 * A composition local rather than a resource lookup because the app's text was
 * written as Kotlin literals from the start; keeping it that way means one place
 * to look for a string and no `Resources` plumbing through composables that do
 * not otherwise need a `Context`.
 */
val LocalStrings = compositionLocalOf<Strings> { stringsFor(Lang.DEFAULT) }

/** Shorthand for `LocalStrings.current`. */
val strings: Strings
    @Composable
    @ReadOnlyComposable
    get() = LocalStrings.current

/** The catalog for [lang]. */
fun stringsFor(lang: Lang): Strings = when (lang) {
    Lang.ENGLISH -> EnglishStrings
    Lang.CHINESE -> ChineseStrings
    Lang.JAPANESE -> JapaneseStrings
}

/**
 * Applies [lang] to the default JVM locale.
 *
 * `String.format` and `SimpleDateFormat` read the default locale, which is what
 * a restart of the process would normally pick up from the system. Changing the
 * interface language has to move it too, or a date under a Chinese interface
 * would still be formatted in English.
 */
fun applyLocale(lang: Lang) {
    Locale.setDefault(Locale.forLanguageTag(lang.code))
}

/** The current language, read from `SettingsStore` and published by the root. */
val LocalLanguage = compositionLocalOf { Lang.DEFAULT }
