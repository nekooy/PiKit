package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAddressingTest {
    private val official = ModelProfile(
        id = "official",
        provider = PiProvider.DEEPSEEK.id,
        apiKey = "official-key",
        modelId = "deepseek-flash",
        models = listOf("deepseek-flash", "deepseek-v4-pro"),
    )
    private val relay = official.copy(id = "relay", apiKey = "relay-key", baseUrl = "https://relay.example/v1")

    @Test
    fun `same model on official and relay has different routes and isolated credential variables`() {
        val snapshot = PiConfigSnapshot(listOf(official, relay), official.id)
        val environment = ProfileAddressing.providerEnvironment(snapshot)

        assertNotEquals(ProfileAddressing.runtimeProviderId(official), ProfileAddressing.runtimeProviderId(relay))
        assertNotEquals(official.provider, ProfileAddressing.runtimeProviderId(official))
        assertEquals(environment, ProfileAddressing.providerEnvironment(snapshot.copy(profiles = listOf(relay, official))))
        assertEquals(official.apiKey, environment[ProfileAddressing.keyVariable(official)])
        assertEquals(relay.apiKey, environment[ProfileAddressing.keyVariable(relay)])
        assertFalse(PiProvider.DEEPSEEK.envVar in environment)
    }

    @Test
    fun `changing endpoint invalidates a shell's old credential variable`() {
        val changed = official.copy(baseUrl = relay.baseUrl)
        val old = ProfileAddressing.providerEnvironment(PiConfigSnapshot(listOf(official), official.id))

        assertNotEquals(ProfileAddressing.runtimeProviderId(official), ProfileAddressing.runtimeProviderId(changed))
        assertFalse(ProfileAddressing.keyVariable(changed) in old)
        assertEquals(ProfileAddressing.runtimeProviderId(official), ProfileAddressing.runtimeProviderId(official.copy(apiKey = "new-key")))
    }

    @Test
    fun `picker cannot recreate a deleted profile or choose a removed model`() {
        val edited = official.copy(apiKey = "new-key", models = listOf("deepseek-flash"))
        val snapshot = PiConfigSnapshot(listOf(edited, relay), relay.id)
        assertNull(profileSelection(snapshot, "deleted", relay.modelId))
        assertNull(profileSelection(snapshot, official.id, "deepseek-v4-pro"))
        val selected = profileSelection(snapshot, official.id, official.modelId)!!
        assertEquals(edited.apiKey, selected.activeProfile?.apiKey)
        assertTrue(selected.profiles.contains(relay))
    }
}
