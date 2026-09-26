package pi.kit.mob.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a backup archive writes, and where a restore is allowed to put it.
 *
 * These are the rules that cost data when they are wrong, and every one of them is
 * path arithmetic or a JSON shape — so they are JVM tests over a temporary directory,
 * and `BackupArchive` takes its preferences and its paths as values for exactly this
 * reason. Two things are deliberately not here, and both are device facts rather
 * than omissions: `SharedPreferences` itself, and a symlink inside a tree, which
 * `SafeDeleteTest` records the same way.
 */
class BackupArchiveTest {

    @get:Rule
    val temporary = TemporaryFolder()

    // --------------------------------------------------------------- set-up

    /** A source "device": every path a category reads, and a preference file behind each. */
    private class Device(val root: File, val preferences: FakePreferences = FakePreferences()) {
        val paths: BackupPaths
        val archive: BackupArchive

        init {
            val filesDir = File(root, "files")
            paths = BackupPaths(
                filesDir = filesDir,
                piConfigDir = File(filesDir, "home/.pi/agent"),
                sessionDir = File(filesDir, "pi-sessions"),
                workspace = File(filesDir, "home/workspace"),
                exportDir = File(filesDir, "home/export"),
            )
            paths.piConfigDir.mkdirs()
            paths.sessionDir.mkdirs()
            paths.workspace.mkdirs()
            paths.exportDir.mkdirs()
            archive = BackupArchive(preferences, paths)
        }

        /** One artifact per category, so a category that packs nothing is visible. */
        fun populate() {
            preferences.files["pikit_settings"] = mutableMapOf("theme_mode" to "dark")
            preferences.files["pikit_storage"] = mutableMapOf("roots" to setOf("downloads"))
            preferences.files["pikit_session_pins"] = mutableMapOf("pinned" to setOf("one.jsonl"))
            paths.configFile.writeText("""{"profiles":[{"id":"a","apiKey":"sk-live"}],"activeProfileId":"a"}""")
            File(paths.piConfigDir, "models.json").writeText("""{"providers":{"acme":{}}}""")
            File(paths.piConfigDir, "settings.json").writeText("""{"defaultTools":[]}""")
            File(paths.piConfigDir, "web-search.json").writeText("""{"braveApiKey":"brv-secret"}""")
            paths.agentsPromptFile.writeText("be terse\n")
            File(paths.sessionDir, "one.jsonl").writeText("""{"type":"message"}""" + "\n")
            File(paths.workspace, "main.kt").writeText("fun main() {}\n")
            File(paths.exportDir, "talk.html").writeText("<html></html>\n")
        }

        fun export(
            into: File,
            categories: Set<BackupCategory> = BackupCategory.entries.toSet(),
            includeApiKeys: Boolean = true,
        ): Int = into.outputStream().use { archive.write(it, categories, includeApiKeys) }
    }

    private fun device(name: String, preferences: FakePreferences = FakePreferences()): Device =
        Device(temporary.newFolder(name), preferences).also { it.populate() }

    /** One entry of the zip, as text — what a user would see opening the file. */
    private fun entryText(zip: File, name: String): String? = ZipFile(zip).use { file ->
        val entry = file.getEntry(name) ?: return@use null
        file.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `a restore puts every category back where it came from`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        val target = device("target", preferences = FakePreferences())
        target.paths.sessionDir.listFiles()?.forEach { it.delete() }
        File(target.paths.workspace, "main.kt").delete()
        File(target.paths.exportDir, "talk.html").delete()

        val staging = File(target.root, "staging").apply { mkdirs() }
        val manifest = target.archive.manifest(zip)
        assertNotNull("the archive should carry a manifest", manifest)

        val extraction = target.archive.extract(zip, manifest!!.included, staging)
        for ((name, document) in extraction.documents) {
            target.archive.applyPreferences(name, document)
        }
        target.archive.applyAll(staging, manifest.included)

        val restored = target.preferences.files
        assertEquals("dark", restored["pikit_settings"]?.get("theme_mode"))
        assertEquals(setOf("downloads"), restored["pikit_storage"]?.get("roots"))
        assertEquals(setOf("one.jsonl"), restored["pikit_session_pins"]?.get("pinned"))
        assertTrue(target.paths.configFile.readText().contains("sk-live"))
        assertTrue(File(target.paths.piConfigDir, "models.json").readText().contains("acme"))
        assertEquals("be terse\n", target.paths.agentsPromptFile.readText())
        assertEquals("""{"type":"message"}""" + "\n", File(target.paths.sessionDir, "one.jsonl").readText())
        assertEquals("fun main() {}\n", File(target.paths.workspace, "main.kt").readText())
        assertEquals("<html></html>\n", File(target.paths.exportDir, "talk.html").readText())
    }

    @Test
    fun `every category packs something`() {
        val source = device("source")

        for (category in BackupCategory.entries) {
            val zip = File(source.root, "${category.id}.zip")
            val written = source.export(zip, categories = setOf(category))

            // One entry is the manifest; a category that packs only the manifest is a
            // tick box on the page that does nothing, which reads as a backup that
            // silently skipped it.
            assertTrue("'$category' packed nothing but its manifest", written > 1)
            assertNotNull(
                "the manifest should list '$category'",
                entryText(zip, BACKUP_MANIFEST_ENTRY)?.takeIf { it.contains("\"${category.id}\"") },
            )
        }
    }

    @Test
    fun `the manifest records what was ticked`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS, BackupCategory.WORKSPACE))

        val manifest = source.archive.manifest(zip)

        assertEquals(setOf(BackupCategory.MODELS, BackupCategory.WORKSPACE), manifest?.included)
        assertEquals(BackupManifest().format, manifest?.format)
        assertEquals(true, manifest?.apiKeys)
    }

    @Test
    fun `a category in the archive that this build does not know is dropped`() {
        val read = BackupManifest(categories = listOf("settings", "telepathy")).included

        assertEquals(setOf(BackupCategory.SETTINGS), read)
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun `an entry that climbs out of the staging directory is not written`() {
        val root = temporary.newFolder("root")
        val device = Device(root)
        val staging = File(root, "staging").apply { mkdirs() }
        val zip = File(root, "nasty.zip")

        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
            out.write("""{"format":1,"categories":["conversations"]}""".toByteArray())
            out.closeEntry()
            for (name in listOf("conversations/../../escaped.txt", "/absolute.txt")) {
                out.putNextEntry(ZipEntry(name))
                out.write("should not land".toByteArray())
                out.closeEntry()
            }
        }

        val extraction = device.archive.extract(zip, setOf(BackupCategory.CONVERSATIONS), staging)

        assertEquals("nothing should have been unpacked", 0, extraction.entries)
        assertFalse("the zip wrote outside the staging directory", File(root, "escaped.txt").exists())
        assertFalse("an absolute entry name was honoured", File(root, "absolute.txt").exists())
    }

    @Test
    fun `a file that is not a backup is refused`() {
        val root = temporary.newFolder("root")
        val device = Device(root)
        val zip = File(root, "not-ours.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }

        assertNull(device.archive.manifest(zip))
    }

    @Test
    fun `an archive from a newer format is refused`() {
        val root = temporary.newFolder("root")
        val device = Device(root)
        val zip = File(root, "newer.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
            out.write("""{"format":${BACKUP_FORMAT + 1},"categories":["settings"]}""".toByteArray())
            out.closeEntry()
        }

        assertNull(device.archive.manifest(zip))
    }

    // ------------------------------------------------ what is not restored

    @Test
    fun `an unselected category is not unpacked`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.CONVERSATIONS, BackupCategory.WORKSPACE))

        val target = device("target")
        File(target.paths.workspace, "main.kt").delete()
        val staging = File(target.root, "staging").apply { mkdirs() }
        target.archive.extract(zip, setOf(BackupCategory.CONVERSATIONS), staging)
        target.archive.applyAll(staging, setOf(BackupCategory.CONVERSATIONS))

        assertTrue(File(target.paths.sessionDir, "one.jsonl").isFile)
        assertFalse("the workspace was not selected", File(target.paths.workspace, "main.kt").exists())
    }

    @Test
    fun `a preference of an unselected category is not applied`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.CONVERSATIONS, BackupCategory.SETTINGS))

        val target = device("target")
        // Both preference files are in the archive; only the pins are asked for.
        target.preferences.files["pikit_settings"]?.put("theme_mode", "light")
        target.preferences.files["pikit_storage"]?.put("roots", setOf("shared"))
        val staging = File(target.root, "staging").apply { mkdirs() }
        val extraction = target.archive.extract(zip, setOf(BackupCategory.CONVERSATIONS), staging)
        for ((name, document) in extraction.documents) {
            target.archive.applyPreferences(name, document)
        }

        assertEquals(
            "the pins travel with the conversations",
            setOf("one.jsonl"),
            target.preferences.files["pikit_session_pins"]?.get("pinned"),
        )
        assertEquals(
            "a preference of a category that was not selected was applied anyway",
            "light",
            target.preferences.files["pikit_settings"]?.get("theme_mode"),
        )
        assertEquals(
            "the storage grant belongs to SETTINGS, which was not selected",
            setOf("shared"),
            target.preferences.files["pikit_storage"]?.get("roots"),
        )
    }

    @Test
    fun `the conversation that was open last is not in the archive`() {
        val source = device("source")
        source.preferences.files["pikit_agent_session"] = mutableMapOf("last_session" to "/x/one.jsonl")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        assertNull(
            "where the reader happened to be is not something a backup carries",
            entryText(zip, "prefs/pikit_agent_session.json"),
        )
    }

    @Test
    fun `a restore replaces the file of that name and deletes nothing else`() {
        val source = device("source")
        File(source.paths.workspace, "main.kt").writeText("from the archive\n")
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.WORKSPACE))

        val target = device("target")
        File(target.paths.workspace, "main.kt").writeText("the current one\n")
        File(target.paths.workspace, "only-here.txt").writeText("a day's work\n")

        val staging = File(target.root, "staging").apply { mkdirs() }
        target.archive.extract(zip, setOf(BackupCategory.WORKSPACE), staging)
        target.archive.applyAll(staging, setOf(BackupCategory.WORKSPACE))

        assertEquals("from the archive\n", File(target.paths.workspace, "main.kt").readText())
        assertTrue(
            "a restore must not delete a file the archive does not carry",
            File(target.paths.workspace, "only-here.txt").isFile,
        )
    }

    // ------------------------------------------------------------- redaction

    @Test
    fun `a profile's key comes out and nothing else does`() {
        val source = device("source")
        source.paths.configFile.writeText(
            """
            {
              "profiles": [
                {"id": "a", "apiKey": "sk-secret", "models": ["m1", "m2"], "baseUrl": "https://x/v1"},
                {"id": "b", "apiKey": "sk-other"}
              ],
              "activeProfileId": "a"
            }
            """.trimIndent(),
        )
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        val document = entryText(zip, "pi/pikit-config.json")
        assertNotNull("the profile file should be in the archive", document)
        assertFalse("a key survived", document!!.contains("sk-secret"))
        assertFalse("a key survived", document.contains("sk-other"))
        assertTrue("the models were lost", document.contains("m1"))
        assertTrue("the endpoint was lost", document.contains("https://x/v1"))
        assertTrue("the second profile was lost", document.contains("\"b\""))
        assertFalse("the manifest should say the keys are not in it", manifestSaysKeys(zip))
    }

    @Test
    fun `a search provider's key comes out and the other options do not`() {
        val source = device("source")
        File(source.paths.piConfigDir, "web-search.json").writeText(
            """{"braveApiKey":"brv-secret","fetch":{"timeout":9000},"xaiApiToken":"xai-secret"}""",
        )
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        val document = entryText(zip, "pi/web-search.json")
        assertNotNull(document)
        assertFalse("a key survived", document!!.contains("brv-secret"))
        assertFalse("a key survived", document.contains("xai-secret"))
        assertTrue("an option was lost", document.contains("9000"))
        // The name stays and the value goes, which is what the extension's own
        // schema expects: an absent key means "not configured", and so does "".
        assertTrue("the field name should still be there", document.contains("braveApiKey"))
    }

    /**
     * The common case, and the reason redaction is not simply always on: with the
     * keys kept, a file is copied byte for byte — so a comment somebody wrote into it
     * survives, which a round trip through this app's JSON encoder would lose.
     */
    @Test
    fun `a file the user edited keeps its bytes when the keys are kept`() {
        val source = device("source")
        val original = "{\n  // mine\n  \"braveApiKey\": \"brv-secret\"\n}\n"
        File(source.paths.piConfigDir, "web-search.json").writeText(original)
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = true)

        assertEquals(original, entryText(zip, "pi/web-search.json"))
        assertTrue("the manifest should say the keys are in it", manifestSaysKeys(zip))
    }

    private fun manifestSaysKeys(zip: File): Boolean =
        entryText(zip, BACKUP_MANIFEST_ENTRY)
            ?.let { Json.parseToJsonElement(it).jsonObject["apiKeys"]?.jsonPrimitive?.booleanOrNull }
            ?: false

    private companion object {
        /** 2023-11-14T22:13:20Z — far enough from now that "the extraction's time" cannot pass for it. */
        const val FIXTURE_DATE = 1_700_000_000_000L

        /** A zip's own resolution; see the date tests. */
        const val FIXTURE_SLACK = 2_000L
    }

    // ---------------------------------------------------------- file dates

    /**
     * A conversation's date is its file's, and the history list is sorted and dated by
     * it. An archive that restored every transcript with the mtime of the extraction
     * turned a year of conversations into a list timestamped a minute ago, in whatever
     * order the walk happened to visit the directory — which is what a restore of any
     * size produced, because nothing carried the date across.
     */
    @Test
    fun `a restored conversation keeps its own date`() {
        val source = device("source")
        val original = File(source.paths.sessionDir, "old.jsonl")
        original.writeText("""{"type":"message"}""" + "\n")
        assertTrue("could not date the fixture", original.setLastModified(FIXTURE_DATE))

        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.CONVERSATIONS))

        val target = device("target")
        val staging = File(target.root, "staging").apply { mkdirs() }
        target.archive.extract(zip, setOf(BackupCategory.CONVERSATIONS), staging)
        target.archive.applyAll(staging, setOf(BackupCategory.CONVERSATIONS))

        val restored = File(target.paths.sessionDir, "old.jsonl")
        assertTrue("nothing was restored", restored.isFile)
        // Two seconds of room: a zip stores a time in two-second steps and both ends
        // convert through the local zone, so with one machine at both ends the only
        // loss is the halves.
        assertEquals(FIXTURE_DATE.toDouble(), restored.lastModified().toDouble(), FIXTURE_SLACK.toDouble())
    }

    @Test
    fun `a restored workspace file keeps its own date`() {
        val source = device("source")
        val original = File(source.paths.workspace, "main.kt")
        assertTrue("could not date the fixture", original.setLastModified(FIXTURE_DATE))

        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.WORKSPACE))

        val target = device("target")
        val staging = File(target.root, "staging").apply { mkdirs() }
        target.archive.extract(zip, setOf(BackupCategory.WORKSPACE), staging)
        target.archive.applyAll(staging, setOf(BackupCategory.WORKSPACE))

        assertEquals(
            FIXTURE_DATE.toDouble(),
            File(target.paths.workspace, "main.kt").lastModified().toDouble(),
            FIXTURE_SLACK.toDouble(),
        )
    }

    // ------------------------------------------------- preferences, by type

    @Test
    fun `a preference keeps its type across the round trip`() {
        val document = BackupArchive.encodePreferences(
            mapOf(
                "text" to "medium",
                "flag" to true,
                "count" to 7,
                "wide" to 4_294_967_297L,
                "fraction" to 1.5f,
                "names" to setOf("b", "a"),
            ),
        )

        val decoded = BackupArchive.decodePreferences(Json.parseToJsonElement(document).jsonObject)

        assertEquals(PrefValue.Text("medium"), decoded["text"])
        assertEquals(PrefValue.Flag(true), decoded["flag"])
        // `int` and `long` are separate arms: `1` is a valid value for both, and
        // `getInt` on a `putLong` throws into whoever reads it next.
        assertEquals(PrefValue.Number(7), decoded["count"])
        assertEquals(PrefValue.Wide(4_294_967_297L), decoded["wide"])
        assertEquals(PrefValue.Fraction(1.5f), decoded["fraction"])
        assertEquals(PrefValue.TextSet(setOf("a", "b")), decoded["names"])
    }

    @Test
    fun `an excluded preference key is not in the document at all`() {
        val document = BackupArchive.encodePreferences(
            mapOf("api_key" to "sk-secret", "theme_mode" to "dark"),
            excluded = setOf("api_key"),
        )

        val decoded = BackupArchive.decodePreferences(Json.parseToJsonElement(document).jsonObject)
        assertFalse("the secret is in the archive", document.contains("sk-secret"))
        assertFalse(decoded.containsKey("api_key"))
        assertEquals(PrefValue.Text("dark"), decoded["theme_mode"])
    }

    @Test
    fun `a value the platform cannot store is skipped rather than stringified`() {
        val document = BackupArchive.encodePreferences(mapOf("odd" to listOf("a"), "ok" to "yes"))

        val decoded = BackupArchive.decodePreferences(Json.parseToJsonElement(document).jsonObject)
        assertFalse("a List is not a SharedPreferences type", decoded.containsKey("odd"))
        assertEquals(PrefValue.Text("yes"), decoded["ok"])
    }

    @Test
    fun `an unreadable preference document decodes to nothing rather than throwing`() {
        val decoded = BackupArchive.decodePreferences(
            Json.parseToJsonElement("""{"a":{"type":"int","value":"not a number"},"b":7}""").jsonObject,
        )

        assertTrue(decoded.isEmpty())
    }

    /**
     * The name the picker suggests, which is also the only thing a user has to tell
     * two backups apart once they are both in a Downloads folder.
     */
    @Test
    fun `the suggested name carries the date and the extension`() {
        val name = BackupArchive.suggestedName(now = 1_767_225_600_000L)

        assertTrue("got '$name'", name.startsWith("pikit-backup-"))
        assertTrue("got '$name'", name.endsWith(".zip"))
        assertEquals("yyyyMMdd-HHmm", 13, name.removePrefix("pikit-backup-").removeSuffix(".zip").length)
    }
}

/**
 * The preference files, in a map.
 *
 * Only what `BackupArchive` asks of `SharedPreferences`: the values of one file, and
 * a write per key. The type each value is written back as is the one
 * `decodePreferences` decided, so a round trip through this fake is the same round
 * trip the real store makes — which is the part worth having without a device.
 */
private class FakePreferences(
    initial: Map<String, Map<String, Any>> = emptyMap(),
) : PreferenceStore {

    val files: MutableMap<String, MutableMap<String, Any>> =
        initial.mapValues { (_, values) -> values.toMutableMap() }.toMutableMap()

    override fun read(name: String): Map<String, *> = files[name].orEmpty()

    override fun apply(name: String, values: Map<String, PrefValue>) {
        val target = files.getOrPut(name) { mutableMapOf() }
        values.forEach { (key, value) ->
            target[key] = when (value) {
                is PrefValue.Text -> value.value
                is PrefValue.Flag -> value.value
                is PrefValue.Number -> value.value
                is PrefValue.Wide -> value.value
                is PrefValue.Fraction -> value.value
                is PrefValue.TextSet -> value.value
            }
        }
    }
}
