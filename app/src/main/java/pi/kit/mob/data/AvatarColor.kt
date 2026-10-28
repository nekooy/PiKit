package pi.kit.mob.data

import androidx.compose.ui.graphics.Color

/**
 * The eight colours a transcript avatar may be drawn in.
 *
 * ## Why this is no longer the interface's accent
 *
 * It used to be: the same enum seeded the palette's whole accent ramp, and the
 * personalization page offered it as swatches under a "theme colour" heading. The
 * interface now takes Miuix's own palette whole — the library's `lightColorScheme()`
 * and `darkColorScheme()` — so there is no accent for a user to choose, and this
 * enum has one job left: the disc behind an avatar's label or picture.
 *
 * That is a *content* colour, not a scheme role. It has to stay tellable from the
 * seven beside it and from the surface it sits on, and it deliberately does not
 * follow the light/dark switch — a red avatar is red in both, the way a photograph
 * is. Which is also why these are hand-picked: a derivation from one seed would
 * give eight shades of one hue, and eight shades are not eight choices.
 *
 * [BLUE] is [DEFAULT] so a fresh install's agent avatar is the app's own blue, and
 * the enum's order is the order the swatches are offered in.
 */
enum class AvatarColor(
    /** What is written to preferences; an unrecognised code resolves to [DEFAULT]. */
    val code: String,
    /** The disc's fill. */
    val seed: Color,
) {
    BLUE("blue", Color(0xFF1A73E8)),
    INDIGO("indigo", Color(0xFF5A4FCF)),
    TEAL("teal", Color(0xFF00897B)),
    GREEN("green", Color(0xFF2E7D32)),
    AMBER("amber", Color(0xFFB26A00)),
    RED("red", Color(0xFFC62828)),
    PINK("pink", Color(0xFFC2185B)),
    PURPLE("purple", Color(0xFF6A3AB2)),
    ;

    companion object {
        val DEFAULT = BLUE

        fun fromCode(code: String?): AvatarColor =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
