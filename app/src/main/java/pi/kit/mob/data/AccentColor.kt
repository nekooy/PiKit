package pi.kit.mob.data

import androidx.compose.ui.graphics.Color

/**
 * The interface's accent: the hue the library's focus, selection and action controls
 * are drawn in.
 *
 * ## The same eight hues as the avatars
 *
 * This enum used to be [AvatarColor] itself — one set of seeds fed both the scheme and
 * the discs — and the two are still the same palette on purpose. An accent is a hue
 * the reader recognises from a swatch, and the avatar's discs are the only colour
 * picker the app has ever drawn; a second, slightly different set of eight hues would
 * be a second vocabulary for one question.
 *
 * What stays different is what each is *for*. [AvatarColor] is content: a disc is red
 * in both schemes the way a photograph is. This enum is a scheme role: its seed is
 * turned into the light and dark accent roles by `PiKitTheme`, so the same seed is a
 * medium blue on the light page and the library's slightly deeper one on the dark
 * page.
 *
 * ## Default
 *
 * [BLUE] is [DEFAULT] and is the library's own accent, so a fresh install is drawn in
 * exactly the blue Miuix ships. Choosing it explicitly is the same answer: the theme
 * keeps the library's roles whole rather than deriving a near-copy of them.
 *
 * The enum's order is the order the swatches are offered in, and [code] is what is
 * written to preferences; an absent or unrecognised code resolves to [DEFAULT].
 */
enum class AccentColor(
    /** The avatar colour with the same seed and the same code. */
    val avatar: AvatarColor,
) {
    BLUE(AvatarColor.BLUE),
    INDIGO(AvatarColor.INDIGO),
    TEAL(AvatarColor.TEAL),
    GREEN(AvatarColor.GREEN),
    AMBER(AvatarColor.AMBER),
    RED(AvatarColor.RED),
    PINK(AvatarColor.PINK),
    PURPLE(AvatarColor.PURPLE),
    ;

    /** What is written to preferences. */
    val code: String get() = avatar.code

    /** The one colour every accent role is derived from; also the disc when following. */
    val seed: Color get() = avatar.seed

    companion object {
        val DEFAULT = BLUE

        fun fromCode(code: String?): AccentColor =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
