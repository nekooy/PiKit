package pi.kit.mob.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.locales.Lang
import java.io.File
import java.nio.file.Files

/**
 * The `update()` round trip: what is written is what is published.
 *
 * The two halves name every field separately — the preference write and the
 * [SettingsStore.update] publish call — and one that is written but never
 * published is a control that snaps back off while the file says on. That is
 * how `themeDynamic` shipped: the switch could not be turned on in place, and
 * the value only reached the snapshot on the next cold start, which reads the
 * file. The sweep below sets every preference field to a non-default value, so
 * an omission fails here instead of on a phone.
 */
class SettingsStoreTest {

    @Test
    fun `update publishes the flag it just wrote`() {
        val store = newStore()

        val updated = store.update { it.copy(themeDynamic = true) }

        assertTrue("the returned snapshot carries the value", updated.themeDynamic)
        assertTrue("the published snapshot carries the value", store.read().themeDynamic)
        assertTrue("observers see the value", store.settings.value.themeDynamic)
    }

    @Test
    fun `a store rebuilt from the same preferences reads the flag back`() {
        val preferences = FakeSharedPreferences()
        val files = tempDir()

        SettingsStore(FakeContext(preferences, files))
            .update { it.copy(themeDynamic = true) }
        val rebuilt = SettingsStore(FakeContext(preferences, files))

        assertTrue(rebuilt.read().themeDynamic)
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
            themeColor = ThemeColor.TEAL,
            themeDynamic = true,
            fontSize = FontSize.XL,
            launcherIcon = LauncherIcon.DARK,
            safetyExtension = false,
            showAvatars = false,
            aiAvatarLabel = "Pi",
            aiAvatarImage = "avatar.png",
            aiAvatarColor = ThemeColor.RED,
            userAvatarLabel = "Me",
            userAvatarImage = "",
            userAvatarColor = ThemeColor.PURPLE,
        )

        val resolved = store.update { wanted }

        assertEquals(wanted, resolved)
        assertEquals(wanted, store.read())
        assertEquals(wanted, store.settings.value)
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
