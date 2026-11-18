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
import pi.kit.mob.env.AgentContext

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
            val home = File(filesDir, "home")
            paths = BackupPaths(
                filesDir = filesDir,
                piConfigDir = File(home, ".pi/agent"),
                sessionDir = File(filesDir, "pi-sessions"),
                workspace = File(home, "workspace"),
                exportDir = File(home, "export"),
                home = home,
            )
            paths.piConfigDir.mkdirs()
            paths.sessionDir.mkdirs()
            paths.workspace.mkdirs()
            paths.exportDir.mkdirs()
            archive = BackupArchive(preferences, paths)
        }

        /**
         * One artifact per root, so a category or a tree that packs nothing is visible.
         *
         * The list is the app's inventory in miniature: the preference files, the files
         * pi reads, the files the app derives (which must *not* travel), pi's directory
         * beyond those four, the avatars, the transcripts, the workspace and the exports.
         */
        fun populate() {
            preferences.files["pikit_settings"] = mutableMapOf("theme_mode" to "dark")
            preferences.files["pikit_storage"] = mutableMapOf("prompted" to true)
            preferences.files["pikit_power"] = mutableMapOf("asked" to true)
            preferences.files[WorkspaceStore.PREFS] =
                mutableMapOf("added" to """["${File(paths.home, "projects").path}"]""")
            preferences.files["pikit_session_pins"] = mutableMapOf("pinned" to setOf("one.jsonl"))
            preferences.files[SessionModeStore.PREFS] = mutableMapOf(
                File(paths.sessionDir, "one.jsonl").path to "chat",
                // The per-conversation web-access flag lives in the same file under a
                // prefixed key; the whole file travels, so the key family needs no entry.
                "web:${File(paths.sessionDir, "one.jsonl").path}" to false,
            )
            preferences.files[ChatPresetStore.PREFS] = mutableMapOf(
                "presets" to """[{"id":"default","name":"Tutor","prompt":"be a tutor"}]""",
                "default" to "default",
                "selections" to """{"${File(paths.sessionDir, "one.jsonl").path}":"default"}""",
            )
            paths.configFile.writeText("""{"profiles":[{"id":"a","apiKey":"sk-live"}],"activeProfileId":"a"}""")
            File(paths.piConfigDir, "models.json").writeText("""{"providers":{"acme":{}}}""")
            File(paths.piConfigDir, "settings.json").writeText("""{"defaultTools":[]}""")
            File(paths.piConfigDir, "web-search.json").writeText("""{"braveApiKey":"brv-secret"}""")
            // The two halves, not the composed file: `AGENTS.md` is derived, and an
            // archive that carried it would restore a document whose halves the app
            // would then have to guess at.
            paths.agentsAuthorFile.writeText("be terse\n")
            paths.agentsUserFile.writeText("use tabs\n")
            // The composed document and the files the runtime image installs: all three
            // are derived, and a restore that put them back would undo the build.
            File(paths.piConfigDir, AgentContext.FILE_NAME).writeText("composed\n")
            File(paths.piConfigDir, "models-store.json").writeText("""{"providers":{}}""")
            File(paths.piConfigDir, "extensions").mkdirs()
            File(paths.piConfigDir, "extensions/pikit-permissions.ts").writeText("// image\n")
            File(paths.piConfigDir, "extensions/pi-safety-guard.ts").writeText("// retired\n")
            // What a user — or a newer pi — puts in pi's directory. No entry names it.
            File(paths.piConfigDir, "extensions/mine.ts").writeText("export default 1\n")
            File(paths.piConfigDir, "presets.json").writeText("""{"presets":["terse"]}""")
            // The two avatar pictures, one per speaker.
            paths.avatarDir.mkdirs()
            File(paths.avatarDir, "ai-1700000000000.png").writeBytes(byteArrayOf(1, 2, 3))
            File(paths.avatarDir, "user-1700000000001.png").writeBytes(byteArrayOf(4, 5, 6))
            // A chat preset's picture uses its own slot id in the same directory, which is
            // what makes the whole-directory entry carry it without knowing the names.
            File(paths.avatarDir, "preset-default-1700000000002.png").writeBytes(byteArrayOf(7, 8, 9))
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
        target.paths.avatarDir.listFiles().orEmpty().forEach { it.delete() }
        File(target.paths.piConfigDir, "extensions/mine.ts").delete()
        File(target.paths.piConfigDir, "presets.json").delete()

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
        assertEquals(true, restored["pikit_storage"]?.get("prompted"))
        assertEquals(true, restored["pikit_power"]?.get("asked"))
        assertEquals(setOf("one.jsonl"), restored["pikit_session_pins"]?.get("pinned"))
        assertEquals(
            "the mode each conversation was started in travels with its transcript",
            "chat",
            restored[SessionModeStore.PREFS]?.get(File(source.paths.sessionDir, "one.jsonl").path),
        )
        assertTrue(
            "the chat personas are settings and must survive",
            restored[ChatPresetStore.PREFS]?.get("presets").toString().contains("Tutor"),
        )
        assertEquals(
            "a second per-conversation record in the same file travels with it",
            false,
            restored[SessionModeStore.PREFS]?.get("web:${File(source.paths.sessionDir, "one.jsonl").path}"),
        )
        assertTrue(target.paths.configFile.readText().contains("sk-live"))
        assertTrue(File(target.paths.piConfigDir, "models.json").readText().contains("acme"))
        assertEquals("be terse\n", target.paths.agentsAuthorFile.readText())
        assertEquals("use tabs\n", target.paths.agentsUserFile.readText())
        assertEquals("""{"type":"message"}""" + "\n", File(target.paths.sessionDir, "one.jsonl").readText())
        assertEquals("fun main() {}\n", File(target.paths.workspace, "main.kt").readText())
        assertEquals("<html></html>\n", File(target.paths.exportDir, "talk.html").readText())
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(target.paths.avatarDir, "ai-1700000000000.png").readBytes().toList())
        assertEquals(
            "a chat preset's picture is in the same directory and travels the same way",
            byteArrayOf(7, 8, 9).toList(),
            File(target.paths.avatarDir, "preset-default-1700000000002.png").readBytes().toList(),
        )
        assertEquals("export default 1\n", File(target.paths.piConfigDir, "extensions/mine.ts").readText())
        assertEquals("""{"presets":["terse"]}""", File(target.paths.piConfigDir, "presets.json").readText())
    }

    @Test
    fun `a second workspace travels by its path and comes back without being listed`() {
        val root = temporary.newFolder("multi")
        val sourceHome = File(root, "source/files/home")
        val builtIn = File(sourceHome, "workspace").apply { mkdirs() }
        val extra = File(sourceHome, "projects/app").apply { mkdirs() }
        File(builtIn, "main.kt").writeText("fun main() {}\n")
        File(extra, "app.kt").writeText("fun app() {}\n")

        val sourcePaths = BackupPaths(
            filesDir = File(root, "source/files"),
            piConfigDir = File(sourceHome, ".pi/agent").apply { mkdirs() },
            sessionDir = File(root, "source/files/pi-sessions").apply { mkdirs() },
            workspace = builtIn,
            exportDir = File(sourceHome, "export").apply { mkdirs() },
            home = sourceHome,
            workspaces = listOf(extra),
        )
        val zip = File(root, "out.zip")
        BackupArchive(FakePreferences(), sourcePaths)
            .write(zip.outputStream(), setOf(BackupCategory.WORKSPACE), includeApiKeys = true)

        // The entry names the path it came from, relative to `$HOME`: that is the only
        // spelling a restore on another install could still decode.
        assertNotNull(entryText(zip, "workspaces/projects/app/app.kt"))

        // A target that has never heard of that workspace — the state a fresh install is
        // in — still puts the file back where it was.
        val targetHome = File(root, "target/files/home")
        val targetBuiltIn = File(targetHome, "workspace").apply { mkdirs() }
        val targetPaths = BackupPaths(
            filesDir = File(root, "target/files"),
            piConfigDir = File(targetHome, ".pi/agent").apply { mkdirs() },
            sessionDir = File(root, "target/files/pi-sessions").apply { mkdirs() },
            workspace = targetBuiltIn,
            exportDir = File(targetHome, "export").apply { mkdirs() },
            home = targetHome,
        )
        val target = BackupArchive(FakePreferences(), targetPaths)
        val staging = File(root, "staging").apply { mkdirs() }
        val manifest = target.manifest(zip)!!
        target.applyAll(staging.apply { target.extract(zip, manifest.included, this) }, manifest.included)

        assertEquals("fun app() {}\n", File(targetHome, "projects/app/app.kt").readText())
        assertEquals("fun main() {}\n", File(targetBuiltIn, "main.kt").readText())
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

    // -------------------------------------------------------------- inventory

    /**
     * Pi's directory is carried whole, minus what is derived.
     *
     * The whitelist this replaces carried four names: a user who installed their own pi
     * extension — or a newer pi that reads one more file there — lost it on a reinstall
     * with no error anywhere, which is the failure a backup must not have. The derived
     * half is the other direction: the composed `AGENTS.md` is rebuilt from its halves,
     * the catalogue cache is re-downloaded, and the two extension copies are the runtime
     * image's.
     */
    @Test
    fun `everything else in pi's directory travels with the models`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        assertNotNull(
            "a user's own pi extension should travel",
            entryText(zip, "pi/agent/extensions/mine.ts"),
        )
        assertNotNull(
            "a file nobody listed should travel — a new pi config needs no entry",
            entryText(zip, "pi/agent/presets.json"),
        )
        assertNotNull(
            "the known files keep the entry names older archives use",
            entryText(zip, "pi/models.json"),
        )
        assertNull(
            "the file is carried once, by its own entry",
            entryText(zip, "pi/agent/models.json"),
        )

        for (derived in listOf(
            "pi/agent/${AgentContext.FILE_NAME}",
            "pi/agent/models-store.json",
            "pi/agent/extensions/pikit-permissions.ts",
            "pi/agent/extensions/pi-safety-guard.ts",
        )) {
            assertNull("'$derived' is derived and must not travel", entryText(zip, derived))
        }
        // The two halves travel under AGENT_PROMPT, which carries the source rather than
        // the composed document.
        assertNull("the halves are carried by AGENT_PROMPT", entryText(zip, "pi/agent/${AgentContext.AUTHOR_FILE_NAME}"))
        assertNotNull(entryText(zip, "agent/${AgentContext.AUTHOR_FILE_NAME}"))
    }

    /**
     * A derived file is withheld on the way back in, not only on the way out.
     *
     * An archive written by an older build — or one crafted to replace the permission
     * policy — names files this build derives. Restoring them would put back the retired
     * guard that judges with a stricter policy nothing can switch off (§14), or an older
     * `pikit-permissions.ts` that lasts until the next content update.
     */
    @Test
    fun `an archive cannot restore a derived pi file`() {
        val root = temporary.newFolder("crafted")
        val device = Device(root)
        val permission = File(device.paths.piConfigDir, "extensions/pikit-permissions.ts")
        permission.parentFile?.mkdirs()
        permission.writeText("// the policy this build installs\n")
        val composed = File(device.paths.piConfigDir, AgentContext.FILE_NAME)
        composed.writeText("composed by this build\n")

        val zip = File(root, "crafted.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
            out.write("""{"format":1,"categories":["models"]}""".toByteArray())
            out.closeEntry()
            val entries = mapOf(
                "pi/agent/${AgentContext.FILE_NAME}" to "hacked composed text\n",
                "pi/agent/extensions/pikit-permissions.ts" to "// downgraded policy\n",
                "pi/agent/extensions/pi-safety-guard.ts" to "// the retired guard\n",
                "pi/agent/extensions/mine.ts" to "the user's extension\n",
            )
            for ((name, body) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }

        val staging = File(root, "staging").apply { mkdirs() }
        val extraction = device.archive.extract(zip, setOf(BackupCategory.MODELS), staging)
        val moved = device.archive.applyAll(staging, setOf(BackupCategory.MODELS))

        assertEquals("three of the four entries are derived", 1, moved)
        assertEquals("the composed document is this build's", "composed by this build\n", composed.readText())
        assertEquals(
            "the permission policy belongs to the runtime image",
            "// the policy this build installs\n",
            permission.readText(),
        )
        assertFalse(
            "the retired guard must never come back",
            File(device.paths.piConfigDir, "extensions/pi-safety-guard.ts").exists(),
        )
        assertEquals(
            "a file that is not derived still lands",
            "the user's extension\n",
            File(device.paths.piConfigDir, "extensions/mine.ts").readText(),
        )
        assertEquals("the archive carried four files", 4, extraction.entries)
    }

    /**
     * The preference half of the inventory.
     *
     * Written as the complete set rather than one assertion per key: the failure this
     * catches is the *next* store — a new `SharedPreferences` file that the app writes
     * and `BackupArchive` was not told about — and the deliberate absences are pinned by
     * name so a later reader can tell "forgotten" from "decided".
     */
    @Test
    fun `every preference file the app owns is carried or named as absent`() {
        val source = device("source")
        // The two non-preference records: a deadline and where the reader happened to be.
        source.preferences.files["pikit_model_catalogue"] = mutableMapOf("due_at" to 1L)
        source.preferences.files["pikit_agent_session"] = mutableMapOf("last_session" to "/x")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        val carried = ZipFile(zip).use { file ->
            file.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("prefs/") }
                .map { it.removePrefix("prefs/").removeSuffix(".json") }
                .toSet()
        }
        assertEquals(
            "a new SharedPreferences file belongs in PREFERENCE_FILES and in this list",
            setOf(
                "pikit_settings",
                "pikit_storage",
                "pikit_power",
                WorkspaceStore.PREFS,
                ChatPresetStore.PREFS,
                "pikit_session_pins",
                SessionModeStore.PREFS,
            ),
            carried,
        )

        for (absent in listOf("pikit_model_catalogue", "pikit_agent_session")) {
            assertNull("'$absent' is deliberately absent — see BackupArchive", entryText(zip, "prefs/$absent.json"))
        }
    }

    /**
     * Every entry an archive writes has a destination this build knows.
     *
     * An entry whose name no category owns is unpacked into staging and then dropped in
     * silence: the restore reports the entries it extracted and the data is simply not
     * there. Counting the move is what makes a new entry prefix fail the suite instead.
     */
    @Test
    fun `every entry an archive writes has a place to land`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        val target = device("target", FakePreferences())
        val staging = File(target.root, "staging").apply { mkdirs() }
        val manifest = target.archive.manifest(zip)!!
        val extraction = target.archive.extract(zip, manifest.included, staging)
        val moved = target.archive.applyAll(staging, manifest.included)
        val fileEntries = ZipFile(zip).use { file ->
            file.entries().asSequence()
                .count { !it.isDirectory && it.name != BACKUP_MANIFEST_ENTRY && !it.name.startsWith("prefs/") }
        }

        assertEquals("an entry with no destination is data the restore drops", fileEntries, moved)
        assertEquals(
            "the extraction count is preference documents plus staged files",
            fileEntries + extraction.documents.size,
            extraction.entries,
        )
        assertTrue("the fixture should carry preference documents", extraction.documents.isNotEmpty())
    }

    /**
     * An install with nothing on it is a legal source and a legal destination.
     *
     * The backup page can be opened before any category has ever written a file — a fresh
     * install, or one whose workspace is empty — and a restore into that state must
     * create the directories the archive names rather than failing on a `mkdirs` that had
     * no parent.
     */
    @Test
    fun `a fresh install with no files exports and restores without breaking`() {
        val root = temporary.newFolder("fresh")
        val filesDir = File(root, "files")
        val home = File(filesDir, "home")
        // Deliberately not created: none of these directories exists on a device that
        // has not launched the agent yet.
        val emptyPaths = BackupPaths(
            filesDir = filesDir,
            piConfigDir = File(home, ".pi/agent"),
            sessionDir = File(filesDir, "pi-sessions"),
            workspace = File(home, "workspace"),
            exportDir = File(home, "export"),
            home = home,
        )
        val archive = BackupArchive(FakePreferences(), emptyPaths)
        val zip = File(root, "empty.zip")
        archive.write(zip.outputStream(), BackupCategory.entries.toSet(), includeApiKeys = true)

        val manifest = archive.manifest(zip)
        assertNotNull("an archive with no files is still an archive", manifest)
        val staging = File(root, "staging").apply { mkdirs() }
        val extraction = archive.extract(zip, manifest!!.included, staging)
        for ((name, document) in extraction.documents) {
            archive.applyPreferences(name, document)
        }
        assertEquals("nothing was packed but the manifest and the documents", 0, archive.applyAll(staging, manifest.included))
    }

    /** A restore creates the directories it names, for the same reason. */
    @Test
    fun `a restore into an install with no directories creates them`() {
        val source = device("source")
        val zip = File(source.root, "out.zip")
        source.export(zip)

        val root = temporary.newFolder("empty-target")
        val filesDir = File(root, "files")
        val home = File(filesDir, "home")
        val targetPaths = BackupPaths(
            filesDir = filesDir,
            piConfigDir = File(home, ".pi/agent"),
            sessionDir = File(filesDir, "pi-sessions"),
            workspace = File(home, "workspace"),
            exportDir = File(home, "export"),
            home = home,
        )
        val target = BackupArchive(FakePreferences(), targetPaths)
        val staging = File(root, "staging").apply { mkdirs() }
        val manifest = target.manifest(zip)!!
        target.extract(zip, manifest.included, staging)
        target.applyAll(staging, manifest.included)

        assertEquals("fun main() {}\n", File(home, "workspace/main.kt").readText())
        assertEquals(
            """{"type":"message"}""" + "\n",
            File(filesDir, "pi-sessions/one.jsonl").readText(),
        )
        assertEquals(
            "export default 1\n",
            File(home, ".pi/agent/extensions/mine.ts").readText(),
        )
        assertEquals("<html></html>\n", File(home, "export/talk.html").readText())
    }

    /**
     * A workspace outside `$HOME` is not carried, and that is a documented limit rather
     * than a silent half-restore: the entry name is the path relative to `$HOME`, which
     * is the only spelling another install can decode, and an absolute-path entry would
     * let an archive write anywhere the app can reach. The list still travels
     * (`pikit_workspaces`), so the folder reappears in the menu — on the same phone its
     * files are still there.
     */
    @Test
    fun `a workspace outside home is left out of the archive`() {
        val root = temporary.newFolder("outside")
        val filesDir = File(root, "files")
        val home = File(filesDir, "home")
        val inside = File(home, "projects/app").apply { mkdirs() }
        val outside = File(root, "elsewhere/project").apply { mkdirs() }
        File(inside, "app.kt").writeText("fun app() {}\n")
        File(outside, "other.kt").writeText("fun other() {}\n")
        val paths = BackupPaths(
            filesDir = filesDir,
            piConfigDir = File(home, ".pi/agent").apply { mkdirs() },
            sessionDir = File(filesDir, "pi-sessions").apply { mkdirs() },
            workspace = File(home, "workspace").apply { mkdirs() },
            exportDir = File(home, "export").apply { mkdirs() },
            home = home,
            workspaces = listOf(inside, outside),
        )
        val zip = File(root, "out.zip")
        BackupArchive(FakePreferences(), paths).write(
            zip.outputStream(),
            setOf(BackupCategory.WORKSPACE),
            includeApiKeys = true,
        )

        assertNotNull(entryText(zip, "workspaces/projects/app/app.kt"))
        assertNull(
            "an absolute path has no home-relative entry name",
            entryText(zip, "workspaces/elsewhere/project/other.kt"),
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

    /**
     * A literal key the user pasted into `models.json` is a key, and it comes out.
     *
     * The recipe used to be "not redacted at all" on the argument that the file names the
     * *environment variable* that holds the key — which is right for the line PiKit
     * writes (`apiKey: "$PIKIT_KEY_…"`), and wrong for a line the user wrote by hand in a
     * file pi's own documentation invites them to edit. The reference is kept and the
     * literal is blanked, because a blanked reference is a file the custom endpoint
     * cannot resolve while protecting nothing.
     */
    @Test
    fun `a literal key in models json comes out and a reference stays`() {
        val source = device("source")
        val original = """
            {"providers":{"acme":{"apiKey":"sk-literal"},"relay":{"apiKey":"${'$'}PIKIT_KEY_a"}},
             "models":{"custom":{"apiKey":"sk-model-literal"},"kept":{"name":"m"}}}
        """.trimIndent()
        File(source.paths.piConfigDir, "models.json").writeText(original)
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        val document = entryText(zip, "pi/models.json")
        assertNotNull(document)
        assertFalse("a literal provider key survived", document!!.contains("sk-literal"))
        assertFalse("a literal model key survived", document.contains("sk-model-literal"))
        assertTrue(
            "an environment reference is not a secret and must survive",
            document.contains("${'$'}PIKIT_KEY_a"),
        )
        assertTrue("the provider was lost", document.contains("acme"))
        assertTrue("the model entry was lost", document.contains("kept"))

        // The switch is symmetric: with the keys kept, the file is untouched.
        val kept = File(source.root, "kept.zip")
        source.export(kept, categories = setOf(BackupCategory.MODELS), includeApiKeys = true)
        assertEquals(original, entryText(kept, "pi/models.json"))
    }

    /**
     * A document with nothing to strip is copied byte for byte.
     *
     * Re-encoding is the price of redaction, and it is paid only when a redaction
     * actually takes something out: a `web-search.json` the user formatted themselves
     * used to come back pretty-printed on every archive written with the keys excluded,
     * which is the reformatting the "copied verbatim" half of the design promises not to
     * do.
     */
    @Test
    fun `a file with nothing to strip keeps its bytes`() {
        val source = device("source")
        val original = """{"workflow":"none","fetch":{"timeout":9000}}"""
        File(source.paths.piConfigDir, "web-search.json").writeText(original)
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        assertEquals(original, entryText(zip, "pi/web-search.json"))
    }

    /**
     * A credential inside an array is still a credential.
     *
     * The old walk descended into objects only, so a provider list — which is how the
     * web-access extension arranges the keys the settings page offers — was carried
     * untouched. A profile is an element of `profiles` in `pikit-config.json` too, which
     * is the one file the object-only walk was written for.
     */
    @Test
    fun `a key inside an array is stripped as well`() {
        val source = device("source")
        File(source.paths.piConfigDir, "web-search.json").writeText(
            """{"providers":[{"name":"brave","braveApiKey":"brv-nested"}]}""",
        )
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        val document = entryText(zip, "pi/web-search.json")
        assertNotNull(document)
        assertFalse("a key inside an array survived", document!!.contains("brv-nested"))
        assertTrue("the entry itself was lost", document.contains("\"brave\""))
    }

    /**
     * An option that is not a credential survives the redaction.
     *
     * The web-access page's fake-IP exemption is `ssrf.allowRanges`, an array of CIDRs —
     * its name contains neither `Key` nor `Token`, and a suffix rule that became a
     * keyword search later would quietly strip the one setting a phone behind a TUN
     * proxy needs. Its `[]` (off) is a value rather than an absence, so it has to come
     * back as a value.
     */
    @Test
    fun `a non-secret web-search option survives the keys being stripped`() {
        val source = device("source")
        File(source.paths.piConfigDir, "web-search.json").writeText(
            """{"braveApiKey":"brv-secret","ssrf":{"allowRanges":["198.18.0.0/15"]}}""",
        )
        val zip = File(source.root, "out.zip")
        source.export(zip, categories = setOf(BackupCategory.MODELS), includeApiKeys = false)

        val document = entryText(zip, "pi/web-search.json")
        assertNotNull(document)
        assertFalse("the key survived", document!!.contains("brv-secret"))
        assertTrue("the fake-IP exemption was lost", document.contains("allowRanges"))
        assertTrue("the range itself was lost", document.contains("198.18.0.0/15"))
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
