package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the stored theme choice.
 *
 * The value comes from a preferences file a person can edit, so the interesting
 * cases are the ones where it is wrong: a typo there must not stop the app from
 * starting, and must not silently pick the opposite of what was asked for.
 */
class ThemeModeTest {

    @Test
    fun `every stored id reads back as its own mode`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromId(mode.id))
        }
    }

    @Test
    fun `an absent choice follows the phone`() {
        assertEquals(ThemeMode.System, ThemeMode.fromId(null))
    }

    @Test
    fun `an unrecognised value falls back instead of failing`() {
        // The failure this guards against is a crash on launch after a hand
        // edit, or a value from a future version being read as "light".
        assertEquals(ThemeMode.System, ThemeMode.fromId("scuro"))
        assertEquals(ThemeMode.System, ThemeMode.fromId(""))
        assertEquals(ThemeMode.System, ThemeMode.fromId("DARK"))
    }

    @Test
    fun `the system mode follows the phone in both directions`() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
    }

    @Test
    fun `an explicit mode ignores the phone's own choice`() {
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
        assertTrue(ThemeMode.Dark.isDark(systemDark = true))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertFalse(ThemeMode.Light.isDark(systemDark = false))
    }

    @Test
    fun `the stored ids are stable and distinct`() {
        assertEquals(setOf("system", "light", "dark"), ThemeMode.entries.map { it.id }.toSet())
    }
}
