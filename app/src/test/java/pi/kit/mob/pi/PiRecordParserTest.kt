package pi.kit.mob.pi

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below are copied from live pi 0.85.1 RPC captures, so these tests
 * pin the client to observed wire behaviour rather than to prose docs (the two
 * disagree in several places).
 */
class PiRecordParserTest {

    @Test
    fun `parses a successful response envelope`() {
        val record = PiRecordParser.parse(
            """{"id":"1","type":"response","command":"get_state","success":true,"data":{"isStreaming":false}}""",
        )
        val response = record as PiRecord.Response
        assertEquals("1", response.id)
        assertEquals("get_state", response.command)
        assertTrue(response.success)
        assertEquals(false, response.data!!.jsonObject["isStreaming"]!!.jsonPrimitive.content.toBoolean())
        assertNull(response.error)
    }

    /** `data` is omitted entirely, not null, when a command has no payload. */
    @Test
    fun `parses a response with no data key`() {
        val response = PiRecordParser.parse(
            """{"id":"2","type":"response","command":"prompt","success":true}""",
        ) as PiRecord.Response
        assertNull(response.data)
        assertTrue(response.success)
    }

    @Test
    fun `parses an error response`() {
        val response = PiRecordParser.parse(
            """{"id":"n5","type":"response","command":"set_model","success":false,"error":"Model not found: nope/nope"}""",
        ) as PiRecord.Response
        assertEquals(false, response.success)
        assertEquals("Model not found: nope/nope", response.error)
    }

    /** A parse failure response carries no `id` at all. */
    @Test
    fun `parses the parse-error response which has no id`() {
        val response = PiRecordParser.parse(
            """{"type":"response","command":"parse","success":false,"error":"Failed to parse command: bad"}""",
        ) as PiRecord.Response
        assertNull(response.id)
        assertEquals("parse", response.command)
    }

    @Test
    fun `parses an event and keeps its type`() {
        val event = PiRecordParser.parse(
            """{"type":"message_update","usage":{"input":100},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello "}}""",
        ) as PiRecord.Event
        assertEquals("message_update", event.type)
        assertEquals(
            "Hello ",
            event.raw["assistantMessageEvent"]!!.jsonObject["delta"]!!.jsonPrimitive.content,
        )
    }

    /** Undocumented but really emitted: session_info_changed. */
    @Test
    fun `parses undocumented session_info_changed event`() {
        val event = PiRecordParser.parse(
            "{\"type\":\"session_info_changed\",\"name\":\"ls\u2028sep\u2029here\"}",
        ) as PiRecord.Event
        assertEquals("session_info_changed", event.type)
        assertTrue(event.raw.str("name")!!.contains('\u2028'))
    }

    @Test
    fun `parses a select dialog request`() {
        val request = PiRecordParser.parse(
            """{"type":"extension_ui_request","id":"24d122a3","method":"select","title":"Allow tool?","options":["Allow","Block"],"timeout":8000}""",
        ) as PiRecord.UiRequest
        assertEquals("select", request.method)
        assertEquals("Allow tool?", request.title)
        assertEquals(listOf("Allow", "Block"), request.options)
        assertEquals(8000L, request.timeoutMs)
        assertTrue(request.expectsReply)
    }

    @Test
    fun `parses an editor dialog which never carries a timeout`() {
        val request = PiRecordParser.parse(
            """{"type":"extension_ui_request","id":"e5bde50c","method":"editor","title":"Edit","prefill":"line 1\nline 2"}""",
        ) as PiRecord.UiRequest
        assertEquals("editor", request.method)
        assertNull(request.timeoutMs)
        assertTrue(request.expectsReply)
    }

    @Test
    fun `fire and forget ui methods do not expect a reply`() {
        val notify = PiRecordParser.parse(
            """{"type":"extension_ui_request","id":"a","method":"notify","message":"hi","notifyType":"warning"}""",
        ) as PiRecord.UiRequest
        assertEquals(false, notify.expectsReply)
        assertEquals("warning", notify.notifyType)

        val status = PiRecordParser.parse(
            """{"type":"extension_ui_request","id":"b","method":"setStatus","statusKey":"probe","statusText":"running"}""",
        ) as PiRecord.UiRequest
        assertEquals(false, status.expectsReply)
        assertEquals("probe", status.statusKey)
    }

    /** Status/widget clearing is signalled by omitting the text key. */
    @Test
    fun `parses a status clear where statusText is absent`() {
        val request = PiRecordParser.parse(
            """{"type":"extension_ui_request","id":"c","method":"setStatus","statusKey":"probe"}""",
        ) as PiRecord.UiRequest
        assertNull(request.statusText)
    }

    /** The docs get this wrong; the real shape is sourceInfo, not location/path. */
    @Test
    fun `parses get_commands response shape used by the implementation`() {
        val response = PiRecordParser.parse(
            """{"id":"r4","type":"response","command":"get_commands","success":true,"data":{"commands":[{"name":"llama","description":"Manage","source":"extension","sourceInfo":{"path":"<inline:llama.cpp>","source":"inline","scope":"temporary","origin":"top-level"}}]}}""",
        ) as PiRecord.Response
        val command = response.data!!.jsonObject["commands"]!!.let {
            (it as kotlinx.serialization.json.JsonArray)[0].jsonObject
        }
        assertEquals("llama", command["name"]!!.jsonPrimitive.content)
        assertEquals(
            "<inline:llama.cpp>",
            command["sourceInfo"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tolerates unknown fields in a model object`() {
        val response = PiRecordParser.parse(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"claude-sonnet-4-5","provider":"anthropic","compat":{"supportsStrictTools":true},"brandNewField":42},"thinkingLevel":"medium"}}""",
        ) as PiRecord.Response
        val model = response.data!!.jsonObject["model"]!!.jsonObject
        assertEquals("anthropic", model["provider"]!!.jsonPrimitive.content)
    }

    @Test
    fun `treats a bare null record as malformed without throwing`() {
        // Sending `null` to pi crashes it; receiving one is likewise invalid.
        assertTrue(PiRecordParser.parse("null") is PiRecord.Malformed)
    }

    @Test
    fun `treats non-object JSON and garbage as malformed`() {
        assertTrue(PiRecordParser.parse("not json at all") is PiRecord.Malformed)
        assertTrue(PiRecordParser.parse("42") is PiRecord.Malformed)
        assertTrue(PiRecordParser.parse("[1,2]") is PiRecord.Malformed)
        assertTrue(PiRecordParser.parse("{\"no\":\"type\"}") is PiRecord.Malformed)
    }

    @Test
    fun `ui request missing id or method is malformed`() {
        assertTrue(PiRecordParser.parse("""{"type":"extension_ui_request","method":"select"}""") is PiRecord.Malformed)
        assertTrue(PiRecordParser.parse("""{"type":"extension_ui_request","id":"x"}""") is PiRecord.Malformed)
    }
}
