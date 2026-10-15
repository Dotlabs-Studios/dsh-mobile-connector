package dev.dsh.mobile.data

import java.util.Locale

/**
 * Which language the app speaks.
 *
 * The same shape as [ThemeMode], and for the same reason: it is a choice the
 * phone client owns, it is read synchronously while the first frame is being
 * built, and it is stored under a stable id that is never shown.
 *
 * Italian is the **base** language of the app and lives in the default
 * `res/values` folder rather than in `res/values-it`, so a phone set to a
 * language this app has no translation for falls back to Italian — the language
 * the app was written in — instead of to raw resource names. Every other
 * language is an overlay folder (`values-en` for English), and adding one is a
 * `<locale>` line in `res/xml/locales_config.xml`, a folder, and one entry here.
 *
 * The entry carries no words, only the tag: the name a reader sees is a resource,
 * because it is part of a sentence in whatever language is in force, and the
 * segment that selects a language says it in the language it selects — which is
 * the one case where those two differ, and the reason the picker maps the modes
 * to resources rather than reading a label off the enum.
 *
 * @property id the tag stored on disk, and the Android resource tag it selects:
 *   `"it"` and `"en"` are also `values-it` and `values-en`. [System] has no
 *   qualifier of its own, because the phone's own locale *is* the answer.
 * @property tag the BCP 47 tag of the language, or null to follow the phone.
 */
enum class LanguageMode(
    /** The value stored on disk; stable, and never shown. */
    val id: String,
    /** The locale tag this mode selects, or null to keep the phone's own. */
    val tag: String?,
) {
    /**
     * Whatever the phone is set to.
     *
     * Not a third language but the absence of a choice: it is what a fresh
     * install does, and it is the only mode that can never be wrong on a phone
     * whose language this app does not ship.
     */
    System("system", null),

    /**
     * Italian, the language the app is written in and the base of every
     * translation.
     *
     * Selecting it explicitly is not the same as [System] on a phone set to
     * English, which is exactly why it is offered: someone who prefers the
     * original wording can pin it whatever the phone does.
     */
    Italian("it", "it"),

    /** English. */
    English("en", "en");

    /**
     * The locale this mode selects, or null for [System].
     *
     * Resolved through [Locale.forLanguageTag] so a future entry written as
     * `pt-BR` needs no change here.
     */
    val locale: Locale? get() = tag?.let { Locale.forLanguageTag(it) }

    companion object {
        /**
         * Read a stored mode, tolerating anything.
         *
         * An unknown or absent value falls back to [System] rather than
         * throwing: this value comes from a preferences file a person can edit,
         * and a typo there must not stop the app from starting.
         *
         * @param id the stored id, or null.
         * @return the matching mode, or [System].
         */
        fun fromId(id: String?): LanguageMode = entries.firstOrNull { it.id == id } ?: System
    }
}
