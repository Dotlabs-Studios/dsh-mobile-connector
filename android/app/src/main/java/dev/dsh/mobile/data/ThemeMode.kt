package dev.dsh.mobile.data

import androidx.annotation.StringRes
import dev.dsh.mobile.R

/**
 * Which palette the app draws with.
 *
 * The desktop GUI has no such choice — it follows the operating system — but a
 * phone is often used in the dark next to a bright PC, and the harness's palette
 * is defined for both themes, so offering the choice costs nothing and is the
 * one setting people actually look for.
 */
enum class ThemeMode(
    /** The value stored on disk; stable, and never shown. */
    val id: String,
    /** The name shown in the settings sheet, as a resource: it is a word. */
    @StringRes val label: Int,
) {
    /** Follow the phone's own light/dark setting. */
    System("system", R.string.appearance_system),

    /** Always the light palette. */
    Light("light", R.string.appearance_light),

    /** Always the dark palette. */
    Dark("dark", R.string.appearance_dark);

    /** Whether this mode draws the dark palette, given the phone's own choice. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

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
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}
