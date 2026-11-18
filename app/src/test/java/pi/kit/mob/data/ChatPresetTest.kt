package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.ui.components.chatPresetName
import pi.kit.mob.ui.components.chatPresetSummary

/**
 * The preset's identity and its two labels.
 *
 * The store is a SharedPreferences document and needs a device, but these three answers
 * are pure: what a preset's blank parts mean, what the chip calls it, and what the menu
 * row says it sends. They are the parts a reader actually sees, so they are pinned here
 * rather than left to a screenshot.
 */
class ChatPresetTest {

    private val personalization = PiSettings(
        aiAvatarName = "Kit",
        aiAvatarImage = "/files/avatars/ai-1.png",
        aiAvatarColor = AvatarColor.PURPLE,
    )

    @Test
    fun `a blank preset inherits every part of the personalization identity`() {
        val identity = ChatPreset(id = "default").identity(personalization)

        assertEquals("Kit", identity.name)
        assertEquals("/files/avatars/ai-1.png", identity.image)
        assertEquals(AvatarColor.PURPLE, identity.color)
    }

    @Test
    fun `a preset's own identity wins part by part`() {
        val preset = ChatPreset(
            id = "translator",
            name = "Translator",
            avatarColor = AvatarColor.TEAL.code,
            avatarImage = "/files/avatars/preset-translator-1.png",
        )

        val identity = preset.identity(personalization)

        assertEquals("Translator", identity.name)
        assertEquals("/files/avatars/preset-translator-1.png", identity.image)
        assertEquals(AvatarColor.TEAL, identity.color)
    }

    @Test
    fun `an unnamed color-only preset keeps its colour and inherits the name`() {
        // The switch writes a blank colour; a name is a text field and may be left blank
        // independently. The two inherit separately because they are two decisions.
        val preset = ChatPreset(id = "quiet", avatarColor = "")

        val identity = preset.identity(personalization)

        assertEquals("Kit", identity.name)
        assertEquals(AvatarColor.PURPLE, identity.color)
        assertNull(preset.color)
    }

    @Test
    fun `the chip falls back to the mode's own word when nothing names the persona`() {
        val text = stringsFor(Lang.ENGLISH)
        val bare = PiSettings()

        assertEquals(text.settings.modeChat, chatPresetName(ChatPreset(id = "a"), bare, text))
        assertEquals("Translator", chatPresetName(ChatPreset(id = "a", name = "Translator"), bare, text))
        assertEquals("Kit", chatPresetName(ChatPreset(id = "a"), personalization, text))
    }

    @Test
    fun `a sentinel prompt says what it sends instead of showing a token`() {
        val text = stringsFor(Lang.ENGLISH)

        assertEquals(
            text.settings.presetPromptTime,
            chatPresetSummary(ChatPreset(id = "a", prompt = CHAT_TIME_PLACEHOLDER), text),
        )
        assertEquals(
            text.settings.presetPromptEmpty,
            chatPresetSummary(ChatPreset(id = "a", prompt = "   \n"), text),
        )
        assertEquals(
            "You translate carefully.",
            chatPresetSummary(
                ChatPreset(id = "a", prompt = "You translate carefully.\nSecond line."),
                text,
            ),
        )
    }
}
