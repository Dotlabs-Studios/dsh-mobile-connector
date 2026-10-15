package dev.dsh.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dsh.mobile.data.languageOf
import dev.dsh.mobile.data.withLanguage
import dev.dsh.mobile.ui.AppViewModel
import dev.dsh.mobile.ui.ChatScreen
import dev.dsh.mobile.ui.DshMobileTheme
import dev.dsh.mobile.ui.PairingScreen
import dev.dsh.mobile.ui.Screen
import dev.dsh.mobile.ui.SessionsScreen

/** The single activity hosting the whole app. */
class MainActivity : ComponentActivity() {
    /**
     * Pins the language before anything can be read from this context.
     *
     * This is the only moment Android allows it: a `Context`'s resources are
     * fixed when the context is made, so a language chosen in the settings
     * cannot be applied by changing a variable later — [AppViewModel.chooseLanguage]
     * recreates the activity instead, and this runs again and reads the new
     * choice.
     *
     * It has to happen *here* rather than in `onCreate`, because the activity's
     * own theme, its label in the launcher's task switcher, and every
     * `getString` made while the first frame is built all read through this
     * context. Setting it afterwards would leave the first frame in the old
     * language, which is the flash this is meant to avoid.
     *
     * @param base the context Android built from the phone's own configuration.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withLanguage(languageOf(base)))
    }

    /**
     * Re-applies the content when the phone's own configuration changes.
     *
     * Only [dev.dsh.mobile.data.LanguageMode.System] can see this happen — the
     * other two modes hold a locale of their own and are immune to it — and for
     * that mode the phone's new language is the answer. The activity is not
     * recreated, so the composition has to be rebuilt by hand or the change
     * would only land on the next launch.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show()
    }

    /** Builds the composition. Called once per configuration, not once per launch. */
    private fun show() {
        enableEdgeToEdge()
        setContent {
            // The view model is created before the theme is applied, because the
            // stored theme decides the colours of the first frame. Reading it a
            // moment later would flash the system palette on every launch, which
            // is exactly what someone who chose "Scuro" would notice most.
            val viewModel: AppViewModel = viewModel()
            val dark = viewModel.themeMode.isDark(isSystemInDarkTheme())
            // The bars' icons follow the app's theme rather than the phone's:
            // someone who chose "Chiaro" on a dark phone would otherwise get
            // white clock and battery glyphs on a white canvas.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            DshMobileTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (viewModel.screen) {
                        Screen.Pairing -> PairingScreen(viewModel)
                        Screen.Sessions -> SessionsScreen(viewModel)
                        Screen.Chat -> {
                            BackHandler(enabled = true) { viewModel.closeSession() }
                            ChatScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}
