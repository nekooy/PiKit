package pi.kit.mob.data

import androidx.compose.ui.graphics.Color

/**
 * The app's accent: one seed per choice, from which the scheme's own roles are
 * derived.
 *
 * ## Why a seed and not two hand-written palettes per colour
 *
 * `PiColor` writes the neutral ramp out in full, and that is deliberate — the
 * surfaces have to stay quiet and close together, which is a judgement call per
 * step. The *accent* is not: it is a hue, and every role it needs (the fill, the
 * on-colour, the container pair, the inverse and the tint) is a function of that
 * hue's lightness. Eight choices times two schemes times six roles is ninety-six
 * hand-tuned values whose only job is to stay in the same relationship to one
 * another, and the ninety-sixth is the one that drifts. So each choice here is one
 * colour, and `PiColor.accentScheme` derives the rest at a fixed set of lightness
 * steps — the same steps for every hue, which is what makes the choices
 * interchangeable rather than merely available.
 *
 * ## What is *not* derived
 *
 * The *lightness* of every role, and the error pair. A hue is a preference; a
 * contrast ratio is not, and the tonal ramp is what the transcript and the terminal
 * are read on. A failure stays red in every scheme for the same reason — it is the
 * one colour that has to mean the same thing whatever was chosen.
 *
 * The `tertiary` role *is* derived, as the seed's hue rotated a little: it is what
 * `PiTone.Live` draws (a turn that is streaming, a process that is running), so it
 * has to stay tellable from the accent — but as a quieter neighbour of it rather
 * than as a reserved coral. `PiColor.accentScheme` carries the reasoning.
 *
 * ## Default
 *
 * [BLUE] is [DEFAULT], so a fresh install is blue and the enum's order is the
 * order the swatches are offered in. [INDIGO] is the violet-indigo the app shipped
 * with before the choice existed, kept as a choice rather than as the default
 * because it is the one a user migrating from that build will look for.
 */
enum class ThemeColor(
    /** What is written to preferences; an unrecognised code resolves to [DEFAULT]. */
    val code: String,
    /** The one colour every role of this accent is derived from. */
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

        fun fromCode(code: String?): ThemeColor =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
