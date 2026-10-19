package dev.dsh.mobile.data

import android.content.Context
import android.provider.Settings

/**
 * Persists the single pairing this app holds: the host address, the device
 * token, and the label shown on the harness side.
 *
 * SharedPreferences is enough here — the value set is three short strings and
 * it must be readable synchronously during the first composition.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

    /** The harness base URL, for example `http://192.168.1.20:4380`. */
    var host: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    /** The bearer token issued at pairing. */
    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** The device label sent when pairing. */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE, null) ?: defaultDeviceName()
        set(value) = prefs.edit().putString(KEY_DEVICE, value).apply()

    /**
     * Which palette the app draws with.
     *
     * Read synchronously during the first composition, because it decides the
     * colours of the very first frame: resolving it a moment later would flash
     * the wrong theme on every launch.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromId(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.id).apply()

    /**
     * Which language the app speaks.
     *
     * Read twice on every launch, and both times before anything can be drawn:
     * once from [languageOf] while the activity is still wrapping its own
     * context — because the locale a `Context` carries is fixed at that moment
     * and cannot be changed afterwards — and once by the settings sheet, which
     * has to show the current choice.
     */
    var language: LanguageMode
        get() = LanguageMode.fromId(prefs.getString(KEY_LANGUAGE, null))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.id).apply()

    /**
     * The projects folded away in the chat list, by section key.
     *
     * Remembered on the device and not for the session: closing a project is a
     * decision about *this phone's list* — a dozen open projects are a wall, and
     * the fold is what brings them back to an index — so having it undone by every
     * app restart is what makes folding feel like it does not stick. The keys are
     * the host's project ids plus `ungrouped` ([sectionKey]), so a fold survives a
     * restart and means nothing on another host; a key whose project has since
     * left the list is simply never matched, and costs one short string.
     *
     * The set is copied on the way out because SharedPreferences hands back a set
     * it still owns: a caller that wrote into it would be mutating the store's own
     * instance behind its back.
     */
    var collapsedWorkspaces: Set<String>
        get() = prefs.getStringSet(KEY_COLLAPSED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_COLLAPSED, value).apply()

    /**
     * Whether the watch service should run while the app is closed.
     *
     * Persisted because it is a decision about this phone, made once: the service
     * has to be restarted by the next launch, and a toggle that forgot itself
     * would leave the user believing they were being watched over when they were
     * not.
     */
    var watchMode: Boolean
        get() = prefs.getBoolean(KEY_WATCH, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCH, value).apply()

    /** Whether a usable pairing exists. */
    val isPaired: Boolean
        get() = !host.isNullOrBlank() && !token.isNullOrBlank()

    /**
     * Drop the pairing, keeping this phone's own choices.
     *
     * The folds go with it, because they are host project ids and mean nothing on
     * another host. The theme and the language stay: they are decisions about
     * *this phone*, not about the harness it happened to be paired with, and an
     * unpair repainting the app and switching its language was never what anyone
     * meant by it.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE)
            .remove(KEY_COLLAPSED)
            .remove(KEY_WATCH)
            .apply()
    }

    /**
     * Build a stable, human-recognisable default label for this phone.
     *
     * @return the manufacturer and model when available, so the harness device
     *   list is readable.
     */
    private fun defaultDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER
            .replaceFirstChar { it.uppercase() }
            .trim()
        val model = android.os.Build.MODEL.trim()
        return when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model.ifBlank { "Android" }
            else -> "$manufacturer $model"
        }
    }

    /**
     * An identifier that survives reinstalls of nothing in particular, used
     * only to keep the paired device name stable across app restarts.
     */
    fun installationId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_COLLAPSED = "collapsed-workspaces"
        const val KEY_WATCH = "watch-mode"
    }
}

/**
 * The stored language, read before there is anything to read it with.
 *
 * `Activity.attachBaseContext` is the one moment Android lets an app say which
 * locale its own `Context`, and therefore every `getString` behind it, will
 * speak — and it happens *before* `onCreate`, so there is no ViewModel and no
 * composition yet. This opens the same preferences file the rest of the app
 * uses and answers the one question that moment needs.
 *
 * @param context any context; only its package is used.
 * @return the language the user chose, or [LanguageMode.System] when never chosen.
 */
fun languageOf(context: Context): LanguageMode {
    val prefs = context.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)
    return LanguageMode.fromId(prefs.getString("language", null))
}

/**
 * The same context, told which language to speak, or the context itself when the
 * phone's own language is the answer.
 *
 * Wrapping is done by `createConfigurationContext`, which returns a context whose
 * resources resolve against the locale given — the mechanism Android's own
 * per-app language setting uses. [LanguageMode.System] deliberately does **not**
 * wrap: the phone's configuration already says the right thing, and copying it
 * would only risk losing a qualifier the system had set that this code does not
 * know about.
 *
 * @param mode the chosen language.
 * @return the context to base the activity on.
 */
fun Context.withLanguage(mode: LanguageMode): Context {
    val locale = mode.locale ?: return this
    val configuration = android.content.res.Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/**
 * Normalise what the user typed into a usable base URL.
 *
 * @param raw the address as typed, with or without scheme and port.
 * @param defaultPort the plugin's default listener port.
 * @return a base URL without a trailing slash.
 */
fun normalizeHost(raw: String, defaultPort: Int = 4380): String {
    var value = raw.trim()
    if (value.isEmpty()) return ""
    if (!value.startsWith("http://") && !value.startsWith("https://")) {
        value = "http://$value"
    }
    val withoutTrailing = value.trimEnd('/')
    val afterScheme = withoutTrailing.substringAfter("://")
    val hasPort = afterScheme.contains(':') && afterScheme.substringAfterLast(':').toIntOrNull() != null
    return if (hasPort) withoutTrailing else "$withoutTrailing:$defaultPort"
}
