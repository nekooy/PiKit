package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.ui.chat.ToolFamily
import pi.kit.mob.ui.chat.toolFamilyOf
import pi.kit.mob.ui.chat.toolKindLabel

/**
 * How a tool's wire name becomes the word a step row shows.
 *
 * The mapping is not cosmetic: `TodoWrite` read as an edit, or `web_search` read as a
 * search, would put the wrong word on the row the reader scans to find out what the
 * agent spent its time doing. The cases below are the names pi and its extensions
 * actually send, plus the unknown spelling a third-party MCP server can add.
 */
class ToolFamilyTest {

    private fun family(name: String) = toolFamilyOf(name)

    @Test
    fun `the tools pi sends land in their families`() {
        assertEquals(ToolFamily.Shell, family("bash"))
        assertEquals(ToolFamily.Read, family("read"))
        assertEquals(ToolFamily.Read, family("ls"))
        assertEquals(ToolFamily.Read, family("find"))
        assertEquals(ToolFamily.Edit, family("edit"))
        assertEquals(ToolFamily.Edit, family("write"))
        assertEquals(ToolFamily.Edit, family("apply_patch"))
        assertEquals(ToolFamily.Search, family("grep"))
        assertEquals(ToolFamily.Search, family("glob"))
        assertEquals(ToolFamily.Web, family("web_fetch"))
        assertEquals(ToolFamily.Agent, family("TaskOutput"))
        assertEquals(ToolFamily.Todo, family("TodoWrite"))
        assertEquals(ToolFamily.Other, family("mcp__weather__forecast"))
    }

    @Test
    fun `the order of the tests keeps compound names honest`() {
        // `TodoWrite` contains "write" and `web_search` contains "search"; the
        // more specific family has to win, which is what pins the branch order.
        assertEquals(ToolFamily.Todo, family("TodoWrite"))
        assertEquals(ToolFamily.Web, family("web_search"))
        assertEquals(ToolFamily.Agent, family("subagent"))
        assertEquals(ToolFamily.Shell, family("execute"))
    }

    @Test
    fun `a known family speaks the interface's language and an unknown one keeps its name`() {
        val chinese = stringsFor(Lang.CHINESE).chat
        val english = stringsFor(Lang.ENGLISH).chat
        assertEquals("终端", toolKindLabel(ToolFamily.Shell, "bash", stringsFor(Lang.CHINESE)))
        assertEquals("读取", toolKindLabel(ToolFamily.Read, "read", stringsFor(Lang.CHINESE)))
        assertEquals("Terminal", toolKindLabel(ToolFamily.Shell, "bash", stringsFor(Lang.ENGLISH)))
        assertEquals("weather", toolKindLabel(ToolFamily.Other, "weather", stringsFor(Lang.ENGLISH)))
        // The fallback is the catalog's word, not a blank cell.
        assertEquals(chinese.toolFallback, toolKindLabel(ToolFamily.Other, "", stringsFor(Lang.CHINESE)))
        assertEquals(english.toolFallback, toolKindLabel(ToolFamily.Other, " ", stringsFor(Lang.ENGLISH)))
    }
}
