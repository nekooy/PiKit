package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PiConfigSnapshot
import pi.kit.mob.data.ProfileAddressing
import pi.kit.mob.locales.ProviderNetworkFailure
import javax.net.ssl.SSLHandshakeException

class ProfileRoutesTest {
    private val profile = ModelProfile(id = "official", provider = "deepseek", apiKey = "demo", modelId = "model-x")

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a poisoned cached route cannot move an official model`() {
        val bundled = json("""{"id":"model-x","api":"openai-completions","baseUrl":"https://api.deepseek.com"}""")
        val stale = json("""{"id":"model-x","api":"openai-completions","baseUrl":"https://relay.example/v1"}""")
        val entry = isolatedNativeProfile(profile, mapOf("model-x" to bundled), mapOf("model-x" to stale))!!
        val model = (entry["models"] as JsonArray).single() as JsonObject
        val id = ProfileAddressing.runtimeProviderId(profile)
        val loaded = ProfileRoutes(PiConfigSnapshot(profiles = listOf(profile)), mapOf(id to entry))
        val route = loaded.route(profile)!!
        assertEquals("https://api.deepseek.com", route.baseUrl)
        assertTrue(route.matches(JsonObject(model + ("provider" to kotlinx.serialization.json.JsonPrimitive(id)))))
        assertFalse(route.matches(JsonObject(stale + ("provider" to kotlinx.serialization.json.JsonPrimitive(id)))))
        assertFalse(route.copy(api = "anthropic-messages").matches(JsonObject(model + ("provider" to kotlinx.serialization.json.JsonPrimitive(id)))))
        assertTrue(loaded.canUse(profile))
        assertFalse(loaded.canUse(profile.copy(apiKey = "rotated")))
        assertFalse(loaded.canUse(profile.copy(baseUrl = "https://relay.example/v1")))
        assertFalse(loaded.canUse(profile.copy(modelId = "missing")))
    }

    @Test
    fun `an uncatalogued id is not invented for an ambiguous provider`() {
        val first = json("""{"api":"openai-completions","baseUrl":"https://api.example/v1"}""")
        val second = json("""{"api":"anthropic-messages","baseUrl":"https://api.example"}""")
        assertNull(isolatedNativeProfile(profile, mapOf("first" to first, "second" to second), emptyMap()))
    }

    @Test
    fun `network diagnosis distinguishes trust and name problems without echoing details`() {
        val trust = SSLHandshakeException("Trust anchor for certification path not found")
        assertEquals(ProviderNetworkFailure.Certificate, providerNetworkFailure(trust))
        assertEquals(ProviderNetworkFailure.Hostname, providerNetworkFailure("ERR_TLS_CERT_ALTNAME_INVALID"))
        assertEquals(ProviderNetworkFailure.Dns, providerNetworkFailure("getaddrinfo ENOTFOUND host"))
        assertEquals(ProviderNetworkFailure.Timeout, providerNetworkFailure("UND_ERR_CONNECT_TIMEOUT"))
        assertEquals(ProviderNetworkFailure.Connection, providerNetworkFailure("Connection error."))
        assertEquals(401, providerAuthStatus("401 body with reflected credential"))
        assertNull(providerAuthStatus("model has 401 tokens"))
        assertEquals("relay.example", endpointHost("https://relay.example:8443/private?key=secret"))
        assertNull(endpointHost("https://user:secret@relay.example/v1"))
    }
}
