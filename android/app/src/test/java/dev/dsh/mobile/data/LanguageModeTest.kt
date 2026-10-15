package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What the language setting promises, checked on the JVM.
 *
 * The rules here are the ones a mistake would turn into a silent bug rather than
 * a crash: a stored value that no longer matches an enum entry, a tile that
 * answers a different question than the one it asks, or a phone whose language
 * this app does not ship being told the wrong thing about what it is showing.
 */
class LanguageModeTest {

    @Test
    fun `an unknown or absent stored value follows the phone`() {
        assertEquals(LanguageMode.System, LanguageMode.fromId(null))
        assertEquals(LanguageMode.System, LanguageMode.fromId(""))
        assertEquals(LanguageMode.System, LanguageMode.fromId("de"))
        assertEquals(LanguageMode.System, LanguageMode.fromId("SYSTEM"))
    }

    @Test
    fun `every entry round-trips through its id`() {
        LanguageMode.entries.forEach { mode ->
            assertEquals(mode, LanguageMode.fromId(mode.id))
        }
    }

    @Test
    fun `only the languages with a tag select a locale`() {
        assertNull(LanguageMode.System.locale)
        assertEquals(Locale.ITALIAN, LanguageMode.Italian.locale)
        assertEquals(Locale.ENGLISH, LanguageMode.English.locale)
    }

    @Test
    fun `the tag is the resource qualifier, so a folder is a language`() {
        // `values-it` and `values-en` are what the two tags resolve to, and the
        // build's resourceConfigurations list names the same two: a mismatch
        // would ship a language whose strings are stripped from the APK.
        val tags = LanguageMode.entries.mapNotNull { it.tag }
        assertEquals(listOf("it", "en"), tags)
    }

    @Test
    fun `italian is offered by name even though it is the base language`() {
        // The base language has no `values-it` folder, and selecting it is still
        // a real choice: it pins the app against a phone set to English.
        val italian = LanguageMode.entries.single { it.id == "it" }
        assertEquals("it", italian.tag)
        assertNotNull(italian.locale)
    }
}

/**
 * What tapping a language tile does, checked without an activity.
 *
 * The recreation is the mechanism, not a detail: nothing on screen can change
 * language without it, and asking for one when nothing changed is a visible
 * flicker. Both halves of that are decided here.
 */
class LanguageChoiceTest {

    @Test
    fun `choosing the language already in force rebuilds nothing`() {
        LanguageMode.entries.forEach { mode ->
            val choice = applyLanguage(stored = mode, chosen = mode)
            assertEquals(mode, choice.mode)
            assertFalse("$mode was already in force", choice.recreate)
        }
    }

    @Test
    fun `changing the language rebuilds the app`() {
        val choice = applyLanguage(stored = LanguageMode.System, chosen = LanguageMode.English)
        assertEquals(LanguageMode.English, choice.mode)
        assertTrue("a language that is on screen cannot change without a rebuild", choice.recreate)
    }

    @Test
    fun `going back to the phone's language rebuilds even when it looks the same`() {
        // A phone set to English, pinned to English, then handed back to the
        // system: the strings do not change, but the promise does — and the
        // stored value has to follow the promise.
        val choice = applyLanguage(stored = LanguageMode.English, chosen = LanguageMode.System)
        assertEquals(LanguageMode.System, choice.mode)
        assertTrue(choice.recreate)
    }

    @Test
    fun `the tile that is ticked is the choice that was stored, not the language drawn`() {
        val choice = inForce(stored = LanguageMode.System, locales = listOf("en-GB", "it-IT"))
        assertEquals(LanguageMode.System, choice)
    }

    @Test
    fun `a phone set to english resolves to english`() {
        assertEquals("en", resolvedTag(listOf("en-GB"), supported = listOf("it", "en")))
    }

    @Test
    fun `a phone set to a language this app does not ship resolves to nothing`() {
        // Not to Italian: the app *would* draw Italian, and that is exactly what
        // the settings row says in words instead of pointing at a tile that is
        // not the reason.
        assertNull(resolvedTag(listOf("fr-FR", "de-DE"), supported = listOf("it", "en")))
    }

    @Test
    fun `a region is not a language`() {
        assertEquals("it", resolvedTag(listOf("it-CH"), supported = listOf("it", "en")))
        assertEquals("en", resolvedTag(listOf("en-US", "it-IT"), supported = listOf("it", "en")))
    }

    @Test
    fun `the first language the app ships wins`() {
        // `toLanguageTags` puts the user's own order first, and a phone whose
        // first choice is French is still asking for English before Italian if
        // English comes second in its list.
        assertEquals("en", resolvedTag(listOf("fr-FR", "en-GB", "it-IT"), supported = listOf("it", "en")))
    }

    @Test
    fun `the drawn tag names the mode unless the mode follows the phone`() {
        val supported = listOf("it", "en")
        assertEquals("en", drawnTag(LanguageMode.English, listOf("it-IT"), supported))
        assertEquals("it", drawnTag(LanguageMode.Italian, listOf("en-US"), supported))
        assertEquals("en", drawnTag(LanguageMode.System, listOf("en-US"), supported))
        assertNull(drawnTag(LanguageMode.System, listOf("ja-JP"), supported))
    }
}
