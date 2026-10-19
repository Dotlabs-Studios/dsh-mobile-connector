package dev.dsh.mobile.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Asks for the phone's own lock before an action that changes what the agent may do.
 *
 * The confirmation is the platform's device-credential prompt — PIN, pattern,
 * password, or the biometric that guards them — rather than a bespoke biometric
 * dialog: it is the same secret the phone already trusts, it works on every release
 * this app supports, and it needs no new dependency or permission. A phone with no
 * lock configured has nothing to confirm with, and there the action proceeds: an app
 * that refused would be unusable on exactly the phones whose owner chose not to lock
 * them.
 */
class DeviceConfirmation internal constructor(
    private val ask: (title: String, description: String, onConfirmed: () -> Unit) -> Unit,
) {
    /**
     * Show the prompt and run [onConfirmed] when the owner proved they are there.
     *
     * @param title the prompt's title, already spelled.
     * @param description one line about what is being confirmed.
     * @param onConfirmed the action to run on success (or when there is no lock).
     */
    fun confirm(title: String, description: String, onConfirmed: () -> Unit) = ask(title, description, onConfirmed)
}

/**
 * Build the confirmation handle for the current composition.
 *
 * @return a handle whose [DeviceConfirmation.confirm] shows the system prompt.
 */
@Composable
fun rememberDeviceConfirmation(): DeviceConfirmation {
    val context = LocalContext.current
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    val ask: (String, String, () -> Unit) -> Unit = { title, description, onConfirmed ->
        val intent: Intent? = keyguard?.createConfirmDeviceCredentialIntent(title, description)
        if (intent == null) {
            onConfirmed()
        } else {
            pending = onConfirmed
            launcher.launch(intent)
        }
    }
    return remember { DeviceConfirmation(ask) }
}
