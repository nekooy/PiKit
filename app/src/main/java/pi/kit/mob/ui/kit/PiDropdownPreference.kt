package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A settings row whose value opens a **glass** panel over the page.
 *
 * This is the app's replacement for Miuix's `OverlayDropdownPreference`, and the reason is
 * the same one every menu in this app has (§13, *Menus*): the library's overlay preference
 * draws its panel from the pre-OS4 `DropdownDefaults.dropdownColors()` — an opaque
 * `surfaceContainer` rectangle with 16dp corners, no blur, no stroke and no shadow. On a
 * page whose bar, cards and dialogs are OS4 glass, that panel is the one opaque rectangle
 * left, and it is raised by the rows a reader touches most on a settings page.
 *
 * The library ships the OS4 half as `GlassDropdownPreference`; this wrapper exists for
 * three things it does not do on its own:
 *
 * - **It supplies the backdrop.** `GlassDropdownPreference` takes a `backdrop` parameter
 *   and cannot read `LocalPiBackdrop` itself, so every call site would otherwise have to
 *   remember the local — and a call site that passed `null` would silently get the opaque
 *   fill, which is the thing this wrapper exists to avoid.
 * - **It states the panel's corner.** `PiCorner.popup` (24dp) rather than the library's
 *   `GlassDropdownDefaults.CornerRadius`, so a dropdown's panel and a menu's are cut the
 *   same way.
 * - **It is one shape to reach for.** `docs/UI.md`'s vocabulary names this row, not the
 *   library's, which is what keeps a new settings page from picking the opaque one by
 *   accident.
 *
 * The row itself is the library's `BasicComponent` at its own metrics — a settings row is
 * a preference row, not a popup row, so [PiMenuRow] would be the wrong shape here.
 *
 * @param items the alternatives, in the order they open.
 * @param selectedIndex the one in effect.
 * @param title the row's own title.
 * @param summary the line under it, if the row has one.
 * @param onSelectedIndexChange called with the picked index; the caller writes the
 *   setting and buzzes, because the row reports the choice rather than making it.
 * @param maxHeight the panel's own bound, when a caller needs one smaller than the
 *   library's [GlassPopupDefaults.MaxHeight]. The app passes `null` everywhere: a handful
 *   of alternatives fits, and the panel scrolls inside the library's bound.
 */
@Composable
fun PiDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    maxHeight: Dp? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    // `maxHeight` is applied through the sizing the panel is measured with, because the
    // library's preference takes `GlassPopupSizing` rather than a bare `Dp`: the bound and
    // the window margin are one object, and passing half of it would be a panel clamped
    // to a margin nobody chose.
    val sizing = if (maxHeight == null) {
        GlassPopupSizing()
    } else {
        GlassPopupSizing(maxHeight = maxHeight)
    }
    GlassDropdownPreference(
        onSelectedIndexChange = onSelectedIndexChange,
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        // The page's own recording. `null` below API 33 (and on a GPU without the shader),
        // where the library draws its opaque fill — the same gate every other glass call
        // site in the app passes through.
        backdrop = LocalPiBackdrop.current,
        modifier = modifier,
        enabled = enabled,
        showValue = showValue,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        sizing = sizing,
        onExpandedChange = onExpandedChange,
    )
}

/**
 * A settings row whose value opens a glass panel, with a summary **per option**.
 *
 * The plain form above shows the selected option's *name* at the row's end, which is the
 * library's shape and the right one when the names speak for themselves. A row whose
 * options need a sentence each cannot use it: the sentence would have to live in the row's
 * own summary, where it would describe whichever option happens to be in effect and change
 * as the reader picks.
 *
 * The library has no glass *preference* that carries those summaries, so the row is composed
 * here — [PiMenuRow] for each option at the popup metrics — but its panel is the library's
 * own `GlassDropdownPopup` ([PiDropdownPopup]), not the anchored menu a control's actions
 * open. The two forms of this row are then one panel with one motion: the alternatives grow
 * out of the value cell along the library's arc and the value fades under them, exactly as
 * the plain form's do. A full-size fade was tried here and is what this replaced — beside
 * the language row's arc, the summarised row's panel read as having no animation at all,
 * because a fade over a panel that is already at its final size is not motion.
 *
 * @param options the alternatives, each with the text the row shows and the sentence under
 *   it.
 * @param selectedIndex the one in effect.
 * @param onSelectedIndexChange called with the picked index.
 */
@Composable
fun <T> PiDropdownPreference(
    options: List<PiDropdownOption<T>>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: (@Composable () -> Unit)? = null,
) {
    // Two anchors for one row, because the library asks two questions: the value cell is
    // where the panel grows from and what it hides (`glassPopupAnchorValue`), while the row's
    // own bounds are the placement the library falls back to before that cell is measured.
    val row = rememberPiMenuAnchor()
    val value = rememberGlassPopupAnchor()
    var expanded by remember { mutableStateOf(false) }
    val current = options.getOrNull(selectedIndex)
    val actualEnabled = enabled && options.isNotEmpty()
    val actionColor = if (actualEnabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
    val haptics = LocalPiHaptics.current

    // The row is the library's own preference component, because a settings row is a
    // preference row: `BasicComponent` at its metrics, with the value and the chevron in
    // the end-actions slot — the same two pieces `OverlayDropdownPreference` draws, so the
    // two forms of this row are the same row.
    BasicComponent(
        modifier = modifier,
        title = title,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = {
            Row(
                // The panel's collapsed endpoint is the value cell rather than the whole
                // row, which is what the library's own dropdown does: the panel then hangs
                // off the answer it is about instead of off the row's far end, and the value
                // and chevron fade out under the panel that replaces them.
                modifier = Modifier
                    .piMenuAnchor(row)
                    .glassPopupAnchorValue(value),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current != null) {
                    Text(
                        text = current.label,
                        modifier = Modifier.padding(end = 8.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = actionColor,
                        textAlign = TextAlign.End,
                    )
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
        },
        onClick = {
            if (actualEnabled) {
                expanded = !expanded
                // The library's own dropdown performs `ContextClick` here, and it is the
                // same moment: a row's panel opening is a state that flips and stays
                // flipped until it is dismissed.
                if (expanded) haptics.toggle(on = true)
            }
        },
        role = Role.DropdownList,
        holdDownState = expanded,
        enabled = actualEnabled,
    )

    // A row that leaves the page takes its panel with it: the registration belongs to this
    // call site, so the composition that owns the row owns the popup.
    if (!actualEnabled) return
    PiDropdownPopup(
        show = expanded,
        onDismissRequest = { expanded = false },
        anchor = value,
        bounds = row.bounds,
    ) {
        options.forEachIndexed { index, option ->
            PiMenuRow(
                title = option.label,
                summary = option.summary,
                selected = index == selectedIndex,
                // The buzz is the row's (a tap that changes the answer); the caller only
                // writes the setting.
                onClick = {
                    expanded = false
                    if (index != selectedIndex) onSelectedIndexChange(index)
                },
            )
        }
    }
}

/**
 * One alternative in the summarised form of [PiDropdownPreference].
 *
 * [label] is what the row shows and the panel repeats; [summary] is the sentence under it
 * in the panel. A row's own summary is a different thing — it describes the row, not the
 * option — which is why this type exists rather than a bare `Pair`.
 */
class PiDropdownOption<T>(
    val value: T,
    val label: String,
    val summary: String? = null,
)
