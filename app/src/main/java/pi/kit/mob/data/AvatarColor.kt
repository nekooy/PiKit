package pi.kit.mob.data

import androidx.compose.ui.graphics.Color

/**
 * The eight colours a transcript avatar may be drawn in.
 *
 * ## A content colour, and the accent's palette
 *
 * A disc is *content*, not a scheme role: it has to stay tellable from the seven
 * beside it and from the surface it sits on, and it deliberately does not follow the
 * light/dark switch — a red avatar is red in both, the way a photograph is. Which is
 * also why these are hand-picked: a derivation from one seed would give eight shades
 * of one hue, and eight shades are not eight choices.
 *
 * The same eight seeds are the interface's accent choices ([AccentColor]). An accent
 * is a hue rather than a disc, so the theme fills in its light and dark roles from
 * one seed; the discs stay exactly these colours in both schemes.
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

        /**
         * The stored colour, or null when the reader has never chosen one.
         *
         * The settings' two avatar colours use this rather than [fromCode]: an absent
         * or blank value is the "follow the accent colour" state, which is not the
         * same as having chosen [DEFAULT].
         */
        fun fromCodeOrNull(code: String?): AvatarColor? =
            code?.takeIf { it.isNotBlank() }
                ?.let { value -> entries.firstOrNull { it.code == value } }
    }
}
