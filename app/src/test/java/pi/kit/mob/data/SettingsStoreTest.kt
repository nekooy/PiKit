package pi.kit.mob.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import pi.kit.mob.locales.Lang
import java.io.File
import java.nio.file.Files

/**
 * The `update()` round trip: what is written is what is published.
 *
 * The two halves name every field separately — the preference write and the
 * [SettingsStore.update] publish call — and one that is written but never
 * published is a control that snaps back off while the file says on. That is how
 * the first switch of this shape shipped: it could not be turned on in place, and
 * the value only reached the snapshot on the next cold start, which reads the
 * file. The sweep below sets every preference field to a non-default value, so an
 * omission fails here instead of on a phone.
 */
class SettingsStoreTest {

    @Test
    fun `update publishes the flag it just wrote`() {
        val store = newStore()

        val updated = store.update { it.copy(openNewOnLaunch = false) }

        assertFalse("the returned snapshot carries the value", updated.openNewOnLaunch)
        assertFalse("the published snapshot carries the value", store.read().openNewOnLaunch)
        assertFalse("observers see the value", store.settings.value.openNewOnLaunch)
    }

    @Test
    fun `a store rebuilt from the same preferences reads the flag back`() {
        val preferences = FakeSharedPreferences()
        val files = tempDir()

        SettingsStore(FakeContext(preferences, files))
            .update { it.copy(openNewOnLaunch = false) }
        val rebuilt = SettingsStore(FakeContext(preferences, files))

        assertFalse(rebuilt.read().openNewOnLaunch)
    }

    @Test
    fun `every preference field survives an update round trip`() {
        val store = newStore()
        val wanted = PiSettings(
            thinkingLevel = "high",
            availableThinkingLevels = listOf("low", "high"),
            availableThinkingLevelsFor = "deepseek-v4-pro",
            workingDir = "/tmp/work",
            language = Lang.JAPANESE,
            openNewOnLaunch = false,
            themeMode = ThemeMode.DARK,
            fontSize = FontSize.XL,
            launcherIcon = LauncherIcon.DARK,
            safetyExtension = false,
            showAvatars = false,
            aiAvatarLabel = "Pi",
            aiAvatarImage = "avatar.png",
            aiAvatarColor = AvatarColor.RED,
            userAvatarLabel = "Me",
            userAvatarImage = "",
            userAvatarColor = AvatarColor.PURPLE,
        )

        val resolved = store.update { wanted }

        assertEquals(wanted, resolved)
        assertEquals(wanted, store.read())
        assertEquals(wanted, store.settings.value)
    }

    @Test
    fun `a fresh install reads 100 per cent, at the size the ladder called 95`() {
        val store = newStore()

        assertEquals(FontSize.M, store.read().fontSize)
        assertEquals("the row prints the fourth step", 100, FontSize.DEFAULT.percent)
        // The property the re-base exists for: what the row calls 100% draws what the
        // ladder's own labels called 95%, so no label had to move.
        assertEquals(
            FontSize.S.scale,
            FontSize.DEFAULT.scale * TEXT_SCALE_BASELINE,
            1e-6f,
        )
    }

    @Test
    fun `the step a retired build rewrote is put back once`() {
        val preferences = FakeSharedPreferences()
        // What an install that ran that build carries: the default rewritten to `xs`,
        // and the mark it left to say it had done so.
        preferences.edit().putString("font_size", "xs").putBoolean("font_size_rebased", true).apply()
        val files = tempDir()

        val corrected = SettingsStore(FakeContext(preferences, files))

        assertEquals("the un-chosen step is undone", FontSize.M, corrected.read().fontSize)
        assertEquals("and written back", "m", preferences.getString("font_size", null))
        assertFalse("the mark is gone", preferences.getBoolean("font_size_rebased", false))

        // And it stays undone: the next read has no mark and a step it must respect.
        preferences.edit().putString("font_size", "xs").apply()
        assertEquals(FontSize.XS, SettingsStore(FakeContext(preferences, files)).read().fontSize)
    }

    @Test
    fun `a step chosen on that build is left alone`() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("font_size", "s").putBoolean("font_size_rebased", true).apply()

        val store = SettingsStore(FakeContext(preferences, tempDir()))

        assertEquals(FontSize.S, store.read().fontSize)
        assertEquals("s", preferences.getString("font_size", null))
        assertFalse(preferences.getBoolean("font_size_rebased", false))
    }

    @Test
    fun `a stored text size is read as it is`() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("font_size", "l").apply()

        val store = SettingsStore(FakeContext(preferences, tempDir()))

        assertEquals(FontSize.L, store.read().fontSize)
        assertEquals("l", preferences.getString("font_size", null))
    }

    private fun newStore(): SettingsStore =
        SettingsStore(FakeContext(FakeSharedPreferences(), tempDir()))

    private fun tempDir(): File = Files.createTempDirectory("settings-store-test").toFile()
}

/**
 * Only what [SettingsStore] asks of a [Context]: the preference file and the
 * directory the profile document lives in. Everything else on the wrapper is
 * never reached by the store's constructor or `update`.
 */
private class FakeContext(
    private val preferences: SharedPreferences,
    private val filesDir: File,
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    override fun getFilesDir(): File = filesDir
}

/**
 * The preference file, in a map — the same shape `BackupArchiveTest` fakes for
 * its own `PreferenceStore`, here for the platform interface so the real
 * `SettingsStore` runs against it unchanged.
 */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = put(key, REMOVED)

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (key, value) ->
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            pending.clear()
            clear = false
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }
    }
}

/** Marks a key for removal in [FakeSharedPreferences]' editor. */
private val REMOVED = Any()
