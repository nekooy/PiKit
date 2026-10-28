/*
 * The furniture every settings page is built from — the Miuix half of it.
 *
 * Settings is the one destination where a *group* of peer rows is the whole page
 * (ARCHITECTURE §13), and Miuix already names every piece of that: `SmallTitle` is a
 * group's label, `Card` is its frame, and the `*Preference` components are the rows.
 * This file is therefore deliberately thin. It holds only what the library's
 * vocabulary does not cover — a value in a row's trailing slot, a paragraph inside a
 * card, a field with a supporting line, a confirmation in the error role, a swatch,
 * an avatar — and every one of those is drawn with Miuix's own text styles and colour
 * roles rather than with a palette of its own.
 *
 * ## What is gone, and why nothing here replaces it row by row
 *
 * The previous vocabulary (`PiRow`, `PiGroup`, `PiSectionHeader`, `PiRowDivider`,
 * `PiSwitchRow`, `PiChoiceRow`) drew a row as *mark + title + value + chevron*, and a
 * group as a rounded frame with a hairline between rows. Miuix's rows have neither a
 * leading mark nor a hairline: a group is a card whose rows touch, and the separation
 * is the card's own surface, which is why the pages no longer interleave dividers. The
 * marks were dropped rather than re-illustrated — Miuix's icon set has no glyph for
 * several of the concepts these pages name (a model profile, a relay protocol, an API
 * key), and re-deciding a glyph per row is a new visual vocabulary rather than a
 * migration onto the library's.
 *
 * ## Rows are the library's, not this file's
 *
 * A page builds its rows out of `BasicComponent`, `ArrowPreference`,
 * `SwitchPreference`, `RadioButtonPreference`, `SliderPreference` and `TextField`
 * directly. There is no settings-specific row wrapper any more: one over
 * `BasicComponent` would have to re-expose the library's parameters one at a time, and
 * the two would drift apart on the first change to either.
 *
 */
package pi.kit.mob.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pi.kit.mob.R
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * A row's trailing value, in the quiet role Miuix gives a summary.
 *
 * Muted is what a value the *app* reports is drawn in; [emphasised] is a value the user
 * chose, which has to be tellable from one pi would have used anyway without a second
 * row saying it. [monospace] is for a version, a revision or a number — a machine string
 * read back against another machine string.
 *
 * One line and elided: a settings row's trailing slot takes its natural width out of
 * the title's share, so a value that grew to three lines would push the label it
 * belongs to off the row. The pages hand paths and commands in as a row's *summary*
 * instead, which has the full width under the title.
 */
@Composable
fun SettingsValue(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    emphasised: Boolean = false,
    danger: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
        color = when {
            danger -> MiuixTheme.colorScheme.error
            emphasised -> MiuixTheme.colorScheme.onSurface
            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
        },
        fontFamily = if (monospace) FontFamily.Monospace else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A group whose label carries a fact about the group: a count, a version.
 *
 * `PiSection` — and `SmallTitle` under it — is a label and nothing else, and the one
 * page that needs more is the model form, whose "Models" heading also states how many
 * are listed. The alternative was a row inside the group repeating the number, which
 * reads as a model called "2 configurations".
 *
 * The label is drawn with `SmallTitleDefaults.InsideMargin` and
 * `MiuixTheme.textStyles.subtitle` — the same 28/8 inset and the same face `SmallTitle`
 * uses — so this is that component with a trailing slot rather than a second heading
 * style. The frame under the label is [PiSection]'s, taken with no title of its own:
 * one group frame in the app rather than a second copy of it that would drift on the
 * first change to either.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (trailing == null) {
            SmallTitle(text = title)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SmallTitleDefaults.InsideMargin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = trailing,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        PiSection(content = content)
    }
}

/**
 * A paragraph inside a card, for the sentence a row cannot carry.
 *
 * Miuix's rows hold a title and a one-line summary; a settings page sometimes has to
 * explain a mechanism rather than name a value, and that text is prose — it wraps, and
 * it belongs inside the card so that what it explains is the thing it sits under.
 * Drawn in the summary colour because it is explanation, not a control, and in the
 * error colour when what it explains is a thing that cannot be taken back.
 */
@Composable
fun SettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = if (danger) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
    )
}

/**
 * Prose outside a card, on the column the group labels use.
 *
 * The licence and the credits are two paragraphs of their own under their own headings,
 * and a card around a paragraph whose only content is that paragraph is a frame with
 * nothing to frame. 28dp is `SmallTitle`'s own inset, so a paragraph and the heading
 * over it start on the same column. Extra room above it, because a paragraph that
 * begins on the line the heading ended leaves the heading reading as part of the
 * sentence.
 */
@Composable
fun SettingsParagraph(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 12.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * A settings field: Miuix's own, with its supporting line under it.
 *
 * The field carries 4dp above and below and no horizontal padding of its own, so a run
 * of fields sits inside the group rather than edge to edge and two stacked fields stay
 * 8dp apart (their own outlines separate them, and a hairline beside a field would be a
 * boundary too many). [supporting] is the sentence under the box: Miuix's `TextField`
 * has a floating label and no supporting slot, and that sentence is help for *this*
 * field, so it stays with it.
 *
 * [placeholder] is drawn under the box while the field is empty, beside [supporting]
 * rather than instead of it: a call site that passes both wants the sentence about the
 * field *and* an example of what goes in it, and the version that drew one or the other
 * silently dropped every example the model form passes.
 *
 * [password] exists because an API key must not be drawn in the clear; everything else
 * about the two fields is the same, which is why this is a parameter rather than a
 * second field at each call site.
 *
 * ## The gutter is the library's, not this wrapper's
 *
 * `TextFieldDefaults.InsideMargin` is 16dp on each side and
 * `BasicComponentDefaults.InsideMargin` is 16dp, so a field with no gutter of its own
 * starts its label on the same column a row starts its title — inside a `Card` inset by
 * 12dp both land at 28dp from the page edge, and inside a sheet (24dp) both land at 40.
 * This used to add a 12dp gutter on top, which put every field 12dp to the right of the
 * rows it sat among, and it was removed for that reason.
 *
 * **What came back is a different thing, and it is about the outline rather than the
 * text.** An edge-to-edge field ran its *box* to within 12dp of the window, so the two
 * side walls of a full-width group's field were 24dp apart with the card's fill in
 * between and nothing else — measured, the widest control on the page and the closest
 * to its edge, which read as a field that had been stretched rather than placed. The
 * box is therefore inset by [FIELD_INSET] and the inside margin is reduced by exactly
 * the same amount, so the *label* stays on the group's column while the *outline*
 * stops 18dp from the window. The pair is the point: either half alone moves the text.
 */
private val FIELD_INSET = 6.dp

/**
 * A settings field: Miuix's own, with its supporting line under it.
 *
 * The field carries 4dp above and below and [FIELD_INSET] on each side, so a run of
 * fields sits inside the group rather than edge to edge and two stacked fields stay 8dp
 * apart (their own outlines separate them, and a hairline beside a field would be a
 * boundary too many). [supporting] is the sentence under the box: Miuix's `TextField`
 * has a floating label and no supporting slot, and that sentence is help for *this*
 * field, so it stays with it.
 *
 * [placeholder] is drawn under the box while the field is empty, beside [supporting]
 * rather than instead of it: a call site that passes both wants the sentence about the
 * field *and* an example of what goes in it, and the version that drew one or the other
 * silently dropped every example the model form passes.
 *
 * [password] exists because an API key must not be drawn in the clear; everything else
 * about the two fields is the same, which is why this is a parameter rather than a
 * second field at each call site.
 *
 * The focus ring is off here and everywhere else a field is drawn — `piFieldColors`
 * is the pair that does it, and its KDoc says why the ring cannot simply be hidden.
 */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    password: Boolean = false,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation? = null,
) {
    Column(modifier.fillMaxWidth()) {
        TextField(
            cornerRadius = PiCorner.radius,
            value = value,
            onValueChange = onValueChange,
            label = label,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = if (monospace) {
                MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
            } else {
                MiuixTheme.textStyles.main
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = when {
                visualTransformation != null -> visualTransformation
                password -> PasswordVisualTransformation()
                else -> VisualTransformation.None
            },
            // The library's focus ring is off, and its caret is stated instead — see
            // `piFieldColors`'s KDoc for why the two are one decision.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            // The box is pulled in from the card's edge and its text is pulled back
            // out by the same amount, so the *label* keeps the column every other row
            // in the group starts on while the *outline* stops short of the card's own
            // edge. Both halves are needed: an inset without the compensating inside
            // margin moves the text off the column, which is the mistake this wrapper's
            // KDoc records from the last time it had a gutter of its own.
            //
            // The group's rows begin 28dp from the page edge — `PiSection`'s 12dp card
            // inset plus the library's 16dp inside margin — and so does this label at
            // 6 + 10.
            insideMargin = DpSize(
                TextFieldDefaults.InsideMargin.width - FIELD_INSET,
                TextFieldDefaults.InsideMargin.height,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FIELD_INSET)
                .padding(vertical = 4.dp),
        )
        // Both lines, when both are given: `supporting` explains the field and
        // `placeholder` shows an example of its content, and the version that drew the
        // placeholder only in the supporting slot's absence silently dropped every
        // example the model form passes (its name, its base URL, pi's fallback number).
        val hint = when {
            value.isNotBlank() -> supporting
            else -> listOfNotNull(supporting, placeholder).joinToString(" ").ifEmpty { null }
        }
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * A message with one acknowledgement, and an optional body of lines under it.
 *
 * The settings pages' shape for an *event* that came back wrong — announced, then
 * dismissed — rather than a strip that stays on the page until something replaces it.
 *
 * The sentence is *not* the dialog's `summary`: the library draws a summary with no
 * height bound (`DialogContentLayout` measures it as one `Text`), so a pi stderr of ten
 * lines pushed the acknowledgement off the bottom of the panel and left a dialog that
 * could not be closed by its own button. It is the body's first block here, bounded and
 * scrolled like [lines], and the acknowledgement sits under both.
 *
 * [lines] is the installer's own output, one line per file a walk could not rewrite:
 * there is no ceiling on that count, so it is bounded too.
 *
 * The acknowledgement is [PiDialogAnswers]' single-answer form, which is the app's one
 * dialog-button shape. It used to pass a transparent container on the argument that a
 * filled answer beside the dialog's own card reads as a second panel; what that produced
 * was an acknowledgement that looked disabled next to the filled answers on the page
 * behind it, and two different weights for the same control depending on which page
 * raised the dialog.
 */
@Composable
fun SettingsAlert(
    show: Boolean,
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    lines: List<String> = emptyList(),
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        onDismissRequest = onConfirm,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ALERT_BODY_MAX)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            if (lines.isNotEmpty()) {
                // Miuix's own `Card`, not a rounded box of this file's: 16dp is the
                // library's corner radius and `surfaceContainer` its panel role, and a
                // second pair of each beside them read as a different kind of surface.
                Card(
                    cornerRadius = PiCorner.radius,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            // Bounded, then scrolled inside the bound: a walk reports one
                            // line per file it could not rewrite and there is no ceiling
                            // on that count.
                            .fillMaxWidth()
                            .heightIn(max = ALERT_LINES_MAX)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            PiDialogAnswers(
                dismiss = confirm,
                onDismiss = onConfirm,
            )
        }
    }
}

/** How tall a dialog's error lines may get before they scroll: fifteen lines. */
private val ALERT_LINES_MAX = 240.dp

/** How tall a dialog's sentence may get before it scrolls: about ten lines. */
private val ALERT_BODY_MAX = 240.dp

/** The spinner a run shows in a row: 20dp, the library's own circular size. */
private val WORKING_SIZE = 20.dp

/**
 * A colour a settings row offers, as a circle.
 *
 * The ring is the *selection* and the fill is the colour, which is why a selected
 * swatch is drawn inside a larger circle of `primary` rather than with a tick over it:
 * eight of these in a row are read as a palette, and a tick on top of one of them hides
 * the colour it is on.
 */
@Composable
fun SettingsSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * One speaker's mark: a picture, a word, a silhouette, or the app's own mark.
 *
 * The four fallbacks are ordered by what the user actually chose, which is why a
 * picture wins over a label and a label over a glyph: a label is up to three characters
 * the user typed for this circle, and it says which speaker this is more precisely than
 * any icon can. [ink] is decided by the background's luminance — white on a dark swatch,
 * black on a light one — because the eight avatar colours range from a deep red to an
 * amber and a fixed ink is unreadable at one end.
 *
 * ## Why the picture is decoded here rather than loaded
 *
 * PiKit has no image-loading dependency, and this is the one picture in the app: a
 * `BitmapFactory` decode off the main thread is smaller than the dependency would be,
 * and it reads the same file the reader picked rather than a cache with its own
 * eviction to reason about. It is **downscaled on the way in** — the file is whatever
 * the user picked, and a 12-megapixel photo is 48 MB as an `ARGB_8888` bitmap drawn
 * into a 40dp circle — so `inSampleSize` is solved from the header's own bounds until
 * the longest side is near [AVATAR_DECODE_PX]. A path that no longer resolves decodes
 * to null and costs the picture and nothing else.
 */
@Composable
fun SettingsAvatar(
    color: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    imagePath: String? = null,
    mark: Boolean = false,
    size: Dp = 28.dp,
    /** Null scales the label to [size]; the 96dp preview passes `title1` instead. */
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
                style = if (labelStyle == null) {
                    MiuixTheme.textStyles.body2.copy(fontSize = (size.value * 0.38f).sp)
                } else {
                    labelStyle
                },
                color = ink,
                maxLines = 1,
                // Elided rather than clipped: the label is capped at three characters
                // but a large font scale still overflows, and a glyph cut in half reads
                // as a rendering fault where an ellipsis reads as "there is more".
                overflow = TextOverflow.Ellipsis,
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_GLYPH_SHARE),
            )

            mark -> Icon(
                painter = painterResource(R.drawable.ic_pi_mark),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size * AVATAR_MARK_SHARE),
            )
        }
    }
}

/**
 * How much of the circle a mark takes.
 *
 * Two numbers rather than one, and the pair is a measurement rather than taste: an icon
 * set's box is not its ink. A silhouette glyph draws between 4 and 20 of its 24 units, so
 * at 62% of the circle its ink is 41% of the diameter, while `ic_pi_mark.xml`'s viewport
 * **is** the glyph's bounding box — the same 62% drew an ink twice the silhouette's, and
 * the agent's avatar read as a logo with a ring of colour around it. 0.46 puts the
 * mark's ink at the silhouette's.
 */
private const val AVATAR_GLYPH_SHARE = 0.62f
private const val AVATAR_MARK_SHARE = 0.46f

/** The longest side a decoded avatar is allowed to be, in pixels: 40dp at 3x. */
private const val AVATAR_DECODE_PX = 160

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

/**
 * What a run in flight says inside a row: a spinner, and optionally the word for it.
 *
 * [label] is null in a row whose own control the spinner replaces — a spinner where a
 * button was is that button saying it is busy — and named in a strip under a group,
 * which has no control to replace.
 */
@Composable
fun SettingsWorking(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(size = WORKING_SIZE)
        if (label != null) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * One action at the end of a row: "restart", "run", "ask".
 *
 * Miuix's `TextButton` with this app's own padding, and the padding is the whole point:
 * the library's default inside margin (16dp horizontal, 13dp vertical) around a 40dp
 * minimum makes a control that is nearly as wide as the value it sits beside, so three
 * of them on one page read as a toolbar of buttons rather than as each row's own verb.
 * 12/6 keeps the 40dp touch target and gives the label back to the row.
 *
 * There is no destructive variant. Every action that cannot be taken back is a question
 * first (`PiConfirm(destructive = true)`), and the confirming word of a question is
 * where the error role belongs — a row action that is itself red would be a second,
 * weaker copy of that warning.
 */
@Composable
fun SettingsAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        cornerRadius = PiCorner.radius,
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

/**
 * One choice a [SettingsPickerSheet] offers.
 *
 * [description] is the line under the label, and it is optional: a picker of three
 * workflow names needs to say what each one does, while a picker of thirty provider ids
 * needs the line to say what the id costs or needs. [id] is both the identity and the
 * sort key the caller builds the list with; the label is what is drawn, because a list of
 * raw ids would be a list of words in nobody's language.
 */
data class SettingsPick(
    val id: String,
    val label: String,
    val description: String? = null,
)

/**
 * A list of choices, in Miuix's sheet.
 *
 * The app's one selection control: a row on the page states the current value, and the
 * list is one tap behind it. There is no dropdown here on purpose —
 * `OverlayDropdownPreference` needs the page's popup host and draws a small anchored
 * panel, which is right for three alternatives and wrong for the thirty-odd provider ids
 * this same sheet is used for. One shape for both, so a picker is recognisable wherever
 * it appears.
 *
 * The rows are `RadioButtonPreference`s when the list *has* a current value, and
 * `ArrowPreference`s when it does not: the radio is Miuix's mark for "one of these is in
 * effect", which is the question the provider, workflow and API-type pickers ask, while
 * the add-an-option and fetched-model lists ask "which of these do I want" and a column of
 * unselected radios answered that with the other question's mark. [searchHint] adds a
 * filter field above the list, for a list of *things that exist* rather than a handful of
 * alternatives.
 *
 * An empty filter result draws no card at all. That is a gap rather than a decision: the
 * catalog has no "no matches" member on `Strings.Settings` — the sentence belongs to
 * `Strings.Files`, which is the file list's — and inventing one here is not this file's
 * call; the alternative was a card with nothing in it, which reads as a rendering fault.
 */
@Composable
fun SettingsPickerSheet(
    show: Boolean,
    title: String,
    options: List<SettingsPick>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    footnote: String? = null,
    searchHint: String? = null,
) {
    // Keyed on `show`, so a sheet opened again starts with the whole list rather than with
    // the last search still hiding it. The filter is a question asked *now* — it is not a
    // preference, and a picker that remembered it would look like a list that had lost rows.
    var query by remember(show) { mutableStateOf("") }
    val needle = query.trim()
    val shown = if (needle.isEmpty()) {
        options
    } else {
        options.filter { option ->
            option.label.contains(needle, ignoreCase = true) ||
                option.id.contains(needle, ignoreCase = true) ||
                option.description?.contains(needle, ignoreCase = true) == true
        }
    }

    PiSheet(show = show, title = title, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            if (searchHint != null) {
                SettingsTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = searchHint,
                )
            }
            if (shown.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), cornerRadius = PiCorner.radius) {
                    // The list scrolls *inside* the card, so the card's own rounded clip is
                    // what the rows are cut by: a scroll modifier on the card itself would
                    // put the viewport outside the surface, and the corners would scroll
                    // away with the content.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Bounded, then scrolled: the sheet caps its own height at the
                            // status bar, and a list taller than that is clipped rather than
                            // scrollable unless it scrolls itself.
                            .heightIn(max = PICKER_LIST_MAX)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        shown.forEach { option ->
                            // A radio only when the list *has* a current value. The sheet
                            // is used for two different questions: "which of these is in
                            // effect" (the provider, the workflow, the API type) and
                            // "which of these do I want to add" (the extension's options,
                            // a fetched model), where `selectedId` is null and a column of
                            // unselected radios says the second thing with the first
                            // question's mark. The row that opens something is
                            // `ArrowPreference`, so the add lists use that.
                            if (selectedId == null) {
                                ArrowPreference(
                                    title = option.label,
                                    summary = option.description,
                                    onClick = { onPick(option.id) },
                                )
                            } else {
                                RadioButtonPreference(
                                    title = option.label,
                                    summary = option.description,
                                    selected = option.id == selectedId,
                                    onClick = { onPick(option.id) },
                                )
                            }
                        }
                    }
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

/** How tall a picker's list may get before it scrolls: about eight rows. */
private val PICKER_LIST_MAX = 420.dp

/**
 * A page's own action: the foot of a form, the pair under a list.
 *
 * [primary] is Miuix's filled-primary button — the one action a page is asking the reader
 * to take — and the default is its secondary fill. One primary per screen is the rule the
 * old design system stated and this keeps: a form's foot has Save primary and Cancel
 * secondary, and a list's foot has no primary at all.
 *
 * The inside margin is the library's own 16/13; it is delegated rather than restated
 * because a filled button's padding is the button's shape, unlike [SettingsAction]'s,
 * which sits inside a row and has to leave the row's width for its own label.
 */
@Composable
fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    Button(
        cornerRadius = PiCorner.radius,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

