package pi.kit.mob.ui.components

import pi.kit.mob.data.CHAT_TIME_PLACEHOLDER
import pi.kit.mob.data.ChatPreset
import pi.kit.mob.data.PiSettings
import pi.kit.mob.locales.Strings

/**
 * The name a chat preset is shown under.
 *
 * A preset's own name when it has one, the personalization page's agent name when it does
 * not, and the mode's own word when neither exists — a chip and a menu row both need a
 * label, and an empty one would be a control with no text. The same chain is stated for
 * the transcript by `ChatPreset.identity`, so the chip, the row and the answer's avatar
 * cannot disagree about who is speaking.
 */
internal fun chatPresetName(preset: ChatPreset, settings: PiSettings, text: Strings): String =
    preset.name.ifBlank { settings.aiAvatarName }.ifBlank { text.settings.modeChat }

/**
 * What a preset sends, in one line.
 *
 * The default is the `{{time}}` token alone, which reads as a placeholder rather than as
 * a prompt — so the two answers that deserve words of their own (an empty document, and
 * the time alone) get them, and everything else is the document's first line, which is
 * usually the sentence that opened it. The full text is one tap away in the editor; this
 * is the menu row's "which of these is the translator".
 */
internal fun chatPresetSummary(preset: ChatPreset, text: Strings): String {
    val prompt = preset.prompt.trim()
    return when {
        prompt.isEmpty() -> text.settings.presetPromptEmpty
        prompt == CHAT_TIME_PLACEHOLDER -> text.settings.presetPromptTime
        else -> prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }
}
