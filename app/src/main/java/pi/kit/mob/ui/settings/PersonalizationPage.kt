package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiAppBarScroll
import pi.kit.mob.ui.design.PiBadge
import pi.kit.mob.ui.design.PiChoiceRow
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiSwitchRow
import pi.kit.mob.ui.design.PiTone
import pi.kit.mob.ui.design.PiNote
import kotlin.math.roundToInt

/**
 * Cold-start conversation behaviour, the interface theme, the text size and the
 * launcher icon.
 *
 * Four settings that are all "how the app feels when I open it" and none of which
 * is about the agent: which conversation a launch lands in, how large its text is,
 * which palette that text is drawn in, and which of the two marks the home screen
 * shows. They share a page because a page each would be four rows deep — the same
 * mistake the old "Advanced" group made.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * ## The theme and the icon are rows of choices, the size is a panel
 *
 * All three appearance settings used to be a row that opened a picker sheet. Two of
 * them are a *set of names* — three palettes, two marks — and a set of names is
 * better read at once than one at a time behind a scrim: the three palettes as
 * [PiChoiceRow]s in a group mean the choice is visible, the current one is filled and
 * marked, and picking one re-themes the very rows that were just tapped. That is the
 * live preview the old sheet hid behind its own scrim. The text size is the one that
 * stays a panel, because a *range* is not a set of names (see [FontSizeSheet]).
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
        scrollBehavior = PiAppBarScroll.Pinned,
    ) { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding),
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

            PiSectionHeader(text.settings.launcherIcon)
            PiGroup {
                // Black first, because that is the mark the app ships with and the
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
            PiNote(text.settings.launcherIconFootnote)

            PiNote(text.settings.personalizationNote)
        }
    }
}

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
    // The step under the finger. Keyed on the committed one, so a change made
    // anywhere else — a restore, a rebuild — lands in the slider, and so that the
    // draft after a release is the value that was released.
    var draft by remember(current) { mutableFloatStateOf(current.ordinal.toFloat()) }
    // The last step this sheet wrote. The sheet body is built once when it opens
    // and `current` is that moment's snapshot, so comparing a later release
    // against it would refuse to write the step the user just left. This is the
    // sheet's own answer to "did I already send this".
    var committed by remember(current) { mutableStateOf(current) }
    val shown = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]

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
                value = draft,
                onValueChange = { draft = it },
                // Snapped here as well as by the slider: `steps` fixes the *positions*
                // the thumb may take, and the ordinal is what the enum is indexed by —
                // a rounded float is the same statement twice rather than a second
                // source of truth, and a value one step off would be a preference the
                // user never chose.
                onValueChangeFinished = {
                    val chosen = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]
                    if (chosen != committed) {
                        committed = chosen
                        onChange(chosen)
                    }
                },
                valueRange = 0f..steps.lastIndex.toFloat(),
                // Material3 counts the ticks *between* the ends, so seven positions is
                // five of them.
                steps = steps.size - 2,
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
