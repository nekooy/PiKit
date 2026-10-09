package pi.kit.mob.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.data.AI_AVATAR_DEFAULT_LABEL
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeColor
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiAvatar
import pi.kit.mob.ui.design.PiBadge
import pi.kit.mob.ui.design.PiChoiceRow
import pi.kit.mob.ui.design.PiDot
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiSwatch
import pi.kit.mob.ui.design.PiSwitchRow
import pi.kit.mob.ui.design.PiTextField
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.design.PiValueRow
import kotlin.math.roundToInt

/**
 * Cold-start conversation behaviour, the interface theme and its accent, the text
 * size and the launcher icon.
 *
 * Five settings that are all "how the app feels when I open it" and none of which
 * is about the agent: which conversation a launch lands in, how large its text is,
 * which palette that text is drawn in, which colour the app's actions and section
 * names are drawn in, and which of the two marks the home screen shows. They share
 * a page because a page each would be five rows deep — the same mistake the old
 * "Advanced" group made.
 *
 * The theme and the accent are two questions rather than one — light/dark *and*
 * hue — so they are two groups: the mode is three rows of names, the accent is a
 * row of swatches. `ThemeColor` says why the accent is derived from one seed
 * instead of being two hand-written palettes per colour.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * ## The theme is rows of names, the accent is swatches, the size is a panel
 *
 * All four appearance settings used to be a row that opened a picker sheet. Three of
 * them are a *set* — three palettes, eight colours, two marks — and a set is better
 * read at once than one at a time behind a scrim: the modes and the marks as
 * [PiChoiceRow]s mean the choice is visible, the current one is filled and marked,
 * and picking one re-themes the very rows that were just tapped. That is the live
 * preview the old sheet hid behind its own scrim. The accent is the one of the three
 * drawn as a row of `PiSwatch`es rather than as rows of names, because a colour is
 * recognised before it is read and eight rows of colour names would be the longest
 * group on the page for the least text. The text size is the one that stays a panel,
 * because a *range* is not a set of names (see [FontSizeSheet]).
 *
 * The icon's choice is the one on this page whose effect the app does not draw:
 * `applyLauncherIcon` turns a manifest alias on and the other off, and the system
 * paints what the launcher reads. Its footnote is about the home screen rather
 * than about the icon, because that is the part a user notices.
 */
@Composable
internal fun PersonalizationPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val settings by session.settingsStore.settings.collectAsState()
    val sheets = LocalSheetHost.current

    PiScaffold(
        title = text.settings.personalization,
        subtitle = text.settings.personalizationSubtitle,
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding)
                .padding(top = 8.dp, bottom = PiPageBottom),
        ) {
            PiSectionHeader(text.settings.personalizationConversation)
            PiGroup {
                PiSwitchRow(
                    title = text.settings.openNewOnLaunch,
                    subtitle = text.settings.openNewOnLaunchSubtitle,
                    leading = {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    checked = settings.openNewOnLaunch,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(openNewOnLaunch = on) }
                    },
                )
            }

            PiSectionHeader(text.settings.personalizationAppearance)
            PiGroup {
                PiRow(
                    title = text.settings.fontSize,
                    subtitle = text.settings.fontSizeSubtitle,
                    leading = {
                        Icon(
                            Icons.Filled.FormatSize,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    // The step and the chevron together: `PiRow` draws its chevron
                    // only when the trailing slot is empty, and this row both reports
                    // the step and opens the sheet that changes it.
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PiBadge("${settings.fontSize.percent}%", tone = PiTone.Accent)
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        sheets.show(
                            Sheet(key = "font-size") {
                                FontSizeSheet(
                                    current = settings.fontSize,
                                    onChange = { chosen ->
                                        session.settingsStore.update { it.copy(fontSize = chosen) }
                                    },
                                    text = text,
                                )
                            },
                        )
                    },
                )
            }

            PiSectionHeader(text.settings.theme)
            PiGroup {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) PiRowDivider(inset = false)
                    PiChoiceRow(
                        title = themeModeLabel(mode, text),
                        selected = settings.themeMode == mode,
                        onSelect = {
                            session.settingsStore.update { it.copy(themeMode = mode) }
                        },
                    )
                }
            }

            // The accent, as swatches rather than as rows of names: a colour is
            // recognised before it is read, and eight rows of colour names would be
            // the longest group on the page for the least text. The row above them
            // is what names the current one — the swatches have no room for a label
            // and `PiSwatch`'s ring cannot say *which* colour it is, only that one
            // is chosen.
            PiSectionHeader(text.settings.themeColor)
            PiGroup {
                PiValueRow(
                    title = text.settings.themeColor,
                    subtitle = text.settings.themeColorSubtitle,
                    value = text.settings.themeColorName(settings.themeColor.code),
                    valueTone = PiTone.Accent,
                    leading = { PiDot(color = settings.themeColor.seed, size = 20.dp) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Scrollable as a safety rather than as the normal case:
                        // eight 38dp swatches and their gaps are 332dp, which fits
                        // the narrowest phone this app targets, and a larger font
                        // scale or a longer translation cannot push them off the
                        // edge because the row scrolls instead of clipping.
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThemeColor.entries.forEach { color ->
                        PiSwatch(
                            color = color.seed,
                            selected = settings.themeColor == color,
                            onClick = {
                                session.settingsStore.update { it.copy(themeColor = color) }
                            },
                            size = 38.dp,
                        )
                    }
                }
            }

            PiSectionHeader(text.settings.launcherIcon)
            PiGroup {
                // White first, because that is the mark the app ships with and the
                // one the manifest declares enabled.
                LauncherIcon.entries.forEachIndexed { index, icon ->
                    if (index > 0) PiRowDivider(inset = false)
                    PiChoiceRow(
                        title = launcherIconLabel(icon, text),
                        selected = settings.launcherIcon == icon,
                        onSelect = {
                            session.settingsStore.update { it.copy(launcherIcon = icon) }
                        },
                    )
                }
            }

            // The transcript's two speakers, as names. A switch over the pair rather
            // than one per side: they are one feature — "draw who is talking" — and a
            // reader who wants them off wants both off.
            PiSectionHeader(text.settings.avatars)
            PiGroup {
                PiSwitchRow(
                    title = text.settings.showAvatars,
                    subtitle = text.settings.showAvatarsSubtitle,
                    checked = settings.showAvatars,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(showAvatars = on) }
                    },
                )
                PiRowDivider()
                AvatarRow(
                    title = text.settings.aiAvatar,
                    label = settings.aiAvatarLabel.ifBlank { AI_AVATAR_DEFAULT_LABEL },
                    icon = null,
                    color = settings.aiAvatarColor.seed,
                    onClick = {
                        sheets.show(
                            Sheet(key = "ai-avatar") {
                                AvatarSheet(
                                    title = text.settings.aiAvatar,
                                    // The stored label, blank included: the field edits
                                    // the *stored* word, and the preview shows the
                                    // fallback so a blank field is not a blank circle.
                                    label = settings.aiAvatarLabel,
                                    fallbackLabel = AI_AVATAR_DEFAULT_LABEL,
                                    fallbackIcon = null,
                                    color = settings.aiAvatarColor,
                                    onChangeLabel = { value ->
                                        session.settingsStore.update {
                                            it.copy(aiAvatarLabel = value)
                                        }
                                    },
                                    onChangeColor = { chosen ->
                                        session.settingsStore.update {
                                            it.copy(aiAvatarColor = chosen)
                                        }
                                    },
                                    text = text,
                                )
                            },
                        )
                    },
                )
                PiRowDivider()
                AvatarRow(
                    title = text.settings.userAvatar,
                    // Blank draws the silhouette — the reader's default mark.
                    label = settings.userAvatarLabel.ifBlank { null },
                    icon = Icons.Filled.Person,
                    color = settings.userAvatarColor.seed,
                    onClick = {
                        sheets.show(
                            Sheet(key = "user-avatar") {
                                AvatarSheet(
                                    title = text.settings.userAvatar,
                                    label = settings.userAvatarLabel,
                                    fallbackLabel = null,
                                    fallbackIcon = Icons.Filled.Person,
                                    color = settings.userAvatarColor,
                                    onChangeLabel = { value ->
                                        session.settingsStore.update {
                                            it.copy(userAvatarLabel = value)
                                        }
                                    },
                                    onChangeColor = { chosen ->
                                        session.settingsStore.update {
                                            it.copy(userAvatarColor = chosen)
                                        }
                                    },
                                    text = text,
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * One avatar's row: its name, and a small copy of the mark the row edits.
 *
 * The mark is the row's *value*, with the app's own chevron beside it — the shape
 * every picker on the page has — so the row shows what is set and says that tapping
 * changes it. `PiRow` draws its chevron only when the trailing slot is empty, hence
 * the one drawn here.
 */
@Composable
private fun AvatarRow(
    title: String,
    label: String?,
    icon: ImageVector?,
    color: Color,
    onClick: () -> Unit,
) {
    PiRow(
        title = title,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PiAvatar(
                    color = color,
                    label = label,
                    icon = icon,
                    size = AVATAR_ROW_PREVIEW,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * The avatar editor: a large preview, the label field, and the eight colours.
 *
 * Saves as it is typed and on a swatch tap, like the working directory and the theme
 * accent — neither restarts anything, and the sheet is dismissed by the scrim, the
 * drag and back, so a second "done" control would be the third of three. The label is
 * capped at [AVATAR_LABEL_MAX] characters: it is drawn in a circle the size of a
 * thumbnail, where a fourth character is already an ellipsis.
 */
@Composable
private fun AvatarSheet(
    title: String,
    label: String,
    fallbackLabel: String?,
    fallbackIcon: ImageVector?,
    color: ThemeColor,
    onChangeLabel: (String) -> Unit,
    onChangeColor: (ThemeColor) -> Unit,
    text: Strings,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        PiSheetTitle(title = title)
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            PiAvatar(
                color = color.seed,
                label = label.ifBlank { fallbackLabel },
                icon = fallbackIcon,
                size = AVATAR_PREVIEW_SIZE,
                labelStyle = MaterialTheme.typography.headlineSmall,
            )
        }
        PiTextField(
            value = label,
            onValueChange = { value -> onChangeLabel(value.take(AVATAR_LABEL_MAX)) },
            label = text.settings.avatarLabel,
            supportingText = text.settings.avatarLabelHint,
            singleLine = true,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = text.settings.avatarColor,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeColor.entries.forEach { entry ->
                PiSwatch(
                    color = entry.seed,
                    selected = entry == color,
                    onClick = { onChangeColor(entry) },
                    size = 38.dp,
                )
            }
        }
    }
}

/** The most characters an avatar label holds: a third is already an ellipsis. */
private const val AVATAR_LABEL_MAX = 3

/** The small avatar on a settings row, and the large one in its editor. */
private val AVATAR_ROW_PREVIEW = 24.dp
private val AVATAR_PREVIEW_SIZE = 88.dp

/**
 * The text size's sheet: a title, the seven-step slider, and the percentage.
 *
 * ## Why this one is still a panel
 *
 * A size is a *range*: re-expressing it as seven tappable numbers would throw away the
 * one thing a slider is for, and the alternatives are worth seeing side by side while
 * the finger is on the track. So the range is the one control on the page that opens a
 * panel, and the panel carries the slider. Keeping the slider open under the row
 * instead — which is what the first version of this page did, and then briefly expanded
 * on the row — was rejected: it made this control a different shape from its neighbours
 * before anything was touched. The note under the track is the catalog's own subtitle,
 * because the sheet is where the "let go to apply" rule is read.
 *
 * ## The drag is not applied until it is released, and that is a measurement
 *
 * The first version applied each step as the finger passed it — which does not work,
 * because applying it re-measures the entire interface. Measured on the emulator,
 * dragging across the whole track with nine `input motionevent MOVE`s advanced
 * **one** step (80% → 90%): the first change re-laid the page out under the pointer
 * and the gesture stopped being delivered. A single MOVE of the same total distance
 * moved four steps (90% → 130%), which is what shows the mapping is fine and the
 * interruption is the live update. So the drag moves a local draft — the thumb, the
 * ticks and the percentage, all of which are the sheet's own — and the step is
 * committed on release, which is one re-measure per gesture instead of one per pixel.
 * The sheet stays open so the step can be adjusted again; the scrim and a downward
 * swipe dismiss it.
 *
 * ## The labels
 *
 * The two ends are the same glyph at the smallest and the largest style rather than
 * the words "small" and "large": it is a sample of what the slider does, it needs no
 * translation, and it grows with the choice — the ends are `sp` like everything else,
 * so they redraw at the new scale as soon as the step is committed. The value and
 * the sheet's percentage show the step as a percentage of the app's own sizes, which
 * is the one number a reader can check against nothing at all: `100%` is what a fresh
 * install has.
 */
@Composable
private fun FontSizeSheet(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val steps = FontSize.entries
    // The slider's own state, built once. Two things about the state-based `Slider`
    // are what the first version got wrong, and both made the thumb refuse to move:
    //
    //  - `rememberSliderState` is `rememberSaveable` keyed on *all* of its arguments,
    //    so passing the value under the finger rebuilt the state every frame and the
    //    drag lost its accumulated offset; the opened step is its initial value and
    //    must not be fed back in.
    //  - when the state-based `Slider` has an `onValueChange` it hands the snapped
    //    value to the caller and does *not* write it into the state itself, so the
    //    callback has to do it (`onValueChange = { state.value = it }` below).
    val state = rememberSliderState(
        current.ordinal.toFloat(),
        steps.size - 2,
        0f..steps.lastIndex.toFloat(),
    )
    // The last step this sheet wrote. The sheet body is built once when it opens and
    // `current` is that moment's snapshot, so comparing a later release against it
    // would refuse to write the step the user just left. This is the sheet's own
    // answer to "did I already send this".
    var committed by remember(current) { mutableStateOf(current) }
    val shown = steps[state.value.roundToInt().coerceIn(0, steps.lastIndex)]

    // 48dp under the last line: this sheet is short — title, percentage, the
    // track, one note — so the slider's block sits against the panel's bottom
    // edge and reads as cut off. 16 and then 32 both left it cramped; the extra
    // air is what a control wants under it, not list padding.
    Column(Modifier.fillMaxWidth().padding(bottom = 48.dp)) {
        PiSheetTitle(title = text.settings.fontSize)
        Text(
            text = "${shown.percent}%",
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A fixed-width column for each end, so the two samples read as the ends
            // of one track and the track keeps the same width as the thumb moves.
            Text(
                text = "A",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                // The `SliderState` overload: this release deprecates the value-based
                // one. The value, the tick count and the range are the three the
                // state carries, passed positionally for the reason `PiInputs`
                // records — this release's two `rememberSliderState` overloads name
                // their third parameter differently, and only its type is stable
                // between them. Material3 counts the ticks *between* the ends, so
                // seven positions is five of them.
                state = state,
                // The state does not move itself when this callback is present: the
                // library hands the snapped value to the caller and nothing else, so
                // the write-back here is what moves the thumb. Omitting it — leaving
                // the value in a `draft` outside the state, as the first version did —
                // is what left the thumb stuck.
                onValueChange = { state.value = it },
                // The step is committed on release, not per tick: applying it
                // re-measures the whole interface, and doing that under the finger
                // is what made the drag stutter. See the sheet's own notes.
                onValueChangeFinished = {
                    val chosen = steps[state.value.roundToInt().coerceIn(0, steps.lastIndex)]
                    if (chosen != committed) {
                        committed = chosen
                        onChange(chosen)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "A",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = text.settings.fontSizeSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
    }
}

/**
 * A theme's name in the interface language.
 *
 * The three are `[PiChoiceRow]`s rather than a connected group of three short labels:
 * the Japanese `システムに合わせる` is nine characters in a third of a row, which is an
 * ellipsis in a segmented control and a whole label on a row of its own. The enum's
 * own order is the order they are offered in.
 */
private fun themeModeLabel(mode: ThemeMode, text: Strings): String = when (mode) {
    ThemeMode.SYSTEM -> text.settings.themeSystem
    ThemeMode.LIGHT -> text.settings.themeLight
    ThemeMode.DARK -> text.settings.themeDark
}

/**
 * A launcher mark's name in the interface language.
 *
 * Named by their colours rather than by "light" and "dark", because the two words the
 * theme rows above use already mean the interface palette, and the icon does not
 * follow it — a user on the light theme can have the black icon.
 */
private fun launcherIconLabel(icon: LauncherIcon, text: Strings): String = when (icon) {
    LauncherIcon.DARK -> text.settings.launcherIconBlack
    LauncherIcon.LIGHT -> text.settings.launcherIconWhite
}
