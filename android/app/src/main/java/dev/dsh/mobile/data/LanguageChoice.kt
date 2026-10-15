package dev.dsh.mobile.data

import java.util.Locale

/**
 * The consequences of the language row the user just tapped.
 *
 * Two questions live here, and they are not the same one. *Which tile is
 * ticked* is answered by [inForce]: a phone set to French, with the app never
 * told anything, is drawing Italian — the base language — while `System` is what
 * is in force, so all three tiles are unticked and the row says which language
 * the phone's own setting resolved to. *What has to happen now* is answered by
 * [applyLanguage], and it is only ever one thing: rebuild the activity on a
 * context that speaks the new language. An Android `Context` cannot change the
 * locale of its own resources once it exists, so a language choice that is
 * supposed to be visible on the screen cannot be applied by writing a variable.
 *
 * @property mode the mode now in force, after the tap.
 * @property recreate whether the activity has to be rebuilt to honour it.
 */
data class LanguageChoice(val mode: LanguageMode, val recreate: Boolean)

/**
 * What choosing [chosen] means, given what the app is drawing with right now.
 *
 * The recreation is skipped when nothing changed, because it is a visible event
 * — the sheet closes, the list is rebuilt, the scroll position is where it was —
 * and paying it for a tap that asks for the language already on screen would be
 * a flicker for nothing.
 *
 * @param stored the mode read from the store, which is what the app is drawing
 *   with right now.
 * @param chosen the tile the user tapped.
 * @return the mode now in force and whether the app has to be rebuilt.
 */
fun applyLanguage(stored: LanguageMode, chosen: LanguageMode): LanguageChoice =
    LanguageChoice(chosen, recreate = stored != chosen)

/**
 * The mode to tick, given what is stored and what the phone is set to.
 *
 * `System` is ticked even when the phone's language happens to be the same as a
 * language this app also offers by name: those are different promises — "follow
 * the phone" and "always English" — and a tile that lights up for the wrong one
 * of them would be showing the answer to a question nobody asked.
 *
 * @param stored the mode read from the store.
 * @param locales the phone's own locales, best first. Unused today, and taken
 *   anyway: a future `Automatic` entry that follows the phone's *region* would
 *   need it, and the caller already has it in hand.
 * @return the mode to draw as selected.
 */
@Suppress("UNUSED_PARAMETER")
fun inForce(stored: LanguageMode, locales: List<String>): LanguageMode = stored

/**
 * The language a phone set to [locales] would actually be shown.
 *
 * This is what the settings row puts under "Sistema": on a phone set to French
 * the answer is Italian — the base language, and the reason it is the base — and
 * saying so on the screen is the difference between a considered fallback and an
 * app that looks like it ignored the setting.
 *
 * @param locales the phone's own locales, best first, as
 *   `LocaleList.toLanguageTags()` reports them.
 * @param supported the tags this app ships, the base language included.
 * @return the tag of the language that would be drawn, or null when nothing in
 *   [locales] is one this app ships.
 */
fun resolvedTag(locales: List<String>, supported: List<String>): String? {
    for (tag in locales) {
        val language = Locale.forLanguageTag(tag).language
        if (language.isEmpty()) continue
        supported.firstOrNull { Locale.forLanguageTag(it).language == language }?.let { return it }
    }
    return null
}

/**
 * The tag a chosen mode would draw, which is the mode's own unless it follows
 * the phone.
 *
 * @param mode the mode in force.
 * @param locales the phone's own locales, best first.
 * @param supported the tags this app ships, the base language included.
 * @return the tag of the language that would be drawn, or null when unknown.
 */
fun drawnTag(mode: LanguageMode, locales: List<String>, supported: List<String>): String? =
    mode.tag ?: resolvedTag(locales, supported)
