package dev.dsh.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.data.TextRef

/**
 * One [TextRef], spelled, inside a composition.
 *
 * This is the other half of the handover [TextRef] describes: the view model
 * decides *which* sentence, and this reads it out of the resource table in the
 * language the app is set to. A composable re-runs it whenever the
 * configuration changes, which is what makes a sheet and the screen behind it
 * swap language together rather than on the next launch.
 *
 * A [TextRef.Raw] is returned as it stands, because there is nothing to
 * translate: it is what the host said when it failed, and this app has no
 * business rewording another program's error.
 *
 * The configuration is read on purpose, and only for its side effect: it is what
 * subscribes this call to a language change. A message resolved for an argument
 * that never changes would otherwise be read once and kept, and the screen would
 * show the previous language until something else forced a redraw.
 *
 * @param ref the message to draw.
 * @return the message, in the language in force.
 */
@Composable
@ReadOnlyComposable
fun textResource(ref: TextRef): String {
    LocalConfiguration.current
    return when (ref) {
        is TextRef.Raw -> ref.text
        is TextRef.Res ->
            if (ref.args.isEmpty()) {
                stringResource(ref.id)
            } else {
                stringResource(ref.id, *ref.args.toTypedArray())
            }
    }
}

/**
 * The same, for a message that may not be there.
 *
 * @param ref the message, or null.
 * @return the message, or null when there was none to draw.
 */
@Composable
@ReadOnlyComposable
fun textResourceOrNull(ref: TextRef?): String? {
    val present = ref ?: return null
    return textResource(present)
}
