package pi.kit.mob.ui.kit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Which of the conversation's two speakers a circle is drawn for.
 *
 * It is the speaker rather than a flag on the composable because the two built-in
 * figures are the *page's* decision, not the call site's: the agent's is the app's
 * own mark and the reader's is a person, and those two must stay different (two
 * speakers falling back to one figure is a transcript with one speaker) while both
 * staying the same everywhere they are drawn. [PiAvatar] is where that lives, so a
 * new call site cannot pick a third answer.
 */
enum class AvatarSpeaker {
    /** The agent: the app's own Pi mark when the reader has not named it. */
    AGENT,

    /** The reader: the person figure when they have not named themselves. */
    READER,
}

/**
 * One speaker's mark: a disc in the colour the reader chose, holding their picture,
 * their label, or the speaker's built-in figure.
 *
 * ## Why this is one composable and not two
 *
 * The same circle is drawn in the transcript, on the personalization page's two rows
 * and in that page's editor, and it was drawn by two copies of this code — one in
 * `ChatScreen` and one in `SettingsComponents` — until they disagreed: the reader's
 * fallback was `ContactsCircle` over a message and `Contacts` on the settings row,
 * so the reader's own avatar read as a person inside a second circle in the
 * conversation and as a plain person where they chose it. The report was "用户头像实际
 * 显示的为何和设置页不一样，以设置页为准".
 *
 * A picture, a label and a figure are three answers to one question ("who is this"),
 * so the order they are tried in is stated once, here: **picture, then label, then
 * the figure**, and the settings page's figure is the reader's (the page that lets
 * them replace it is the one that decides what it is when they have not).
 *
 * ## The two shares, and why they differ
 *
 * They were one number (0.62) until the Pi mark was looked at beside the silhouette
 * it shares a slot with: a framework icon's box is not its ink. A person glyph draws
 * between 4 and 20 of its 24 units, so at 62% of the circle its *ink* is
 * 0.62 × 16/24 = **41%** of the diameter; `ic_pi_mark.xml`'s viewport **is** the
 * glyph's bounding box — the path runs 29.9→67.5 in x and 40→77.6 in y of a 37.6-unit
 * viewport — so the same 62% drew an ink twice the silhouette's and the agent's
 * avatar read as a logo with a ring of colour around it rather than as a mark in a
 * circle.
 *
 * [AVATAR_MARK_SHARE] is therefore set to the silhouette's *ink* (0.46 × 34dp = about
 * 16dp of a 34dp circle) rather than to its box. It is still the heavier of the two at
 * equal ink, because the mark is a solid blocky figure where the silhouette is an
 * outline of one, which is what keeps the two speakers tellable apart at a glance.
 *
 * ## The ink is the circle's, not the theme's
 *
 * [color] is the reader's choice and may be any hue at any lightness, so the only
 * thing that can be known about it is how bright it is: the label and the figure are
 * black on a light disc and white on a dark one, which is what [luminance] is asked.
 *
 * @param label the reader's short label for this speaker, or null for the figure.
 * @param imagePath an absolute path to a picture this app owns ([pi.kit.mob.data.AvatarStore]'s
 *   copy), or null. A path that no longer resolves costs the picture and nothing else:
 *   the avatar falls back to the label or the figure exactly as if none had been chosen.
 * @param size the disc's diameter. 28dp on a settings row, 34dp over a message, 96dp
 *   in the editor.
 * @param labelStyle the label's style; null scales it to [size], which is what makes
 *   the same label legible on a 28dp row and on a 96dp preview.
 */
@Composable
fun PiAvatar(
    speaker: AvatarSpeaker,
    color: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    imagePath: String? = null,
    size: Dp = 28.dp,
    labelStyle: TextStyle? = null,
) {
    val ink = if (color.luminance() > 0.5f) Color.Black else Color.White
    val picture = rememberAvatarBitmap(imagePath)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when {
            picture != null -> Image(
                bitmap = picture,
                contentDescription = null,
                // Crop rather than fit: a portrait photo in a circle should fill it,
                // and the middle of the picture is what survives the crop.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            label != null -> Text(
                text = label,
                // Scaled to the circle rather than fixed: `body2` is 14sp, which is
                // more than half of the 28dp row avatar, so the three-character label
                // the field allows was already wider than the disc it sits in. The
                // editor passes `title1` for its 96dp preview; a call site that passes
                // nothing gets the row size.
                style = labelStyle
                    ?: MiuixTheme.textStyles.body2.copy(fontSize = (size.value * LABEL_SHARE).sp),
                color = ink,
                maxLines = 1,
                // Elided rather than clipped: the label is capped at three characters
                // but a large font scale still overflows, and a glyph cut in half reads
                // as a rendering fault where an ellipsis reads as "there is more".
                overflow = TextOverflow.Ellipsis,
            )

            speaker == AvatarSpeaker.AGENT -> Icon(
                painter = painterResource(R.drawable.ic_pi_mark),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_MARK_SHARE),
            )

            else -> Icon(
                imageVector = MiuixIcons.Contacts,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_GLYPH_SHARE),
            )
        }
    }
}

/** The label's size as a share of the disc: 0.38 × 34dp is 13sp, a two-character fit. */
private const val LABEL_SHARE = 0.38f

/** How much of the circle the person figure takes; see [PiAvatar]'s KDoc. */
private const val AVATAR_GLYPH_SHARE = 0.62f

/** And how much the Pi mark takes: the same *ink*, not the same box. */
private const val AVATAR_MARK_SHARE = 0.46f

/** The longest side a decoded avatar is allowed to be, in pixels. */
private const val AVATAR_DECODE_PX = 240

/**
 * The custom avatar picture at [path], decoded once and downscaled to avatar size.
 *
 * ## Why not an image loader
 *
 * PiKit has no image-loading dependency, and this is the one picture in the app: a
 * `BitmapFactory` decode off the main thread in a `produceState` is smaller than the
 * dependency would be, and it is the same file the reader picked rather than a cache
 * with its own eviction to reason about.
 *
 * The decode is **downscaled** on the way in. The file is whatever the user picked —
 * a 12-megapixel photo is 48 MB as an `ARGB_8888` bitmap, drawn into a circle no
 * larger than 96dp — so `inSampleSize` is solved from the file's own bounds until the
 * longest side is near [AVATAR_DECODE_PX]. The bounds pass reads the header only,
 * which is why it is two `decodeFile` calls rather than one.
 */
@Composable
private fun rememberAvatarBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (
                    bounds.outWidth / (sample * 2) >= AVATAR_DECODE_PX &&
                    bounds.outHeight / (sample * 2) >= AVATAR_DECODE_PX
                ) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
}
