package dev.dsh.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dsh.mobile.R
import dev.dsh.mobile.data.SessionStore
import dev.dsh.mobile.watch.WatchService

/**
 * The watch-mode switch and the one permission it needs.
 *
 * Turning it on asks for the notification permission first, because a service that
 * holds a connection and cannot say anything is worse than no service: the note
 * under the switch says what to fix when the system refuses. The switch is drawn
 * from the stored value, so it reflects what will happen on the next launch rather
 * than what the tap hoped for.
 */
@Composable
internal fun WatchSection() {
    val context = LocalContext.current
    val palette = LocalHarness.current
    var enabled by remember { mutableStateOf(SessionStore(context).watchMode) }
    var allowed by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        // The connection is still worth holding when notifications are blocked —
        // the app can read the feed's state — but saying so is the note's job.
        SessionStore(context).watchMode = true
        enabled = true
        WatchService.start(context)
    }

    HarnessSectionLabel(stringResource(R.string.settings_section_alerts), Modifier.padding(horizontal = 16.dp))
    HarnessSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        background = palette.surfaceSunken,
        border = palette.borderSubtle,
        radius = HarnessRadius.lg,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.NotificationsActive,
                tint = palette.accent,
                background = palette.accentContainer,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.watch_title),
                    style = HarnessType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                )
                Text(
                    text = stringResource(R.string.watch_note),
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    when {
                        !next -> {
                            SessionStore(context).watchMode = false
                            enabled = false
                            WatchService.stop(context)
                        }

                        !allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)

                        else -> {
                            SessionStore(context).watchMode = true
                            enabled = true
                            WatchService.start(context)
                        }
                    }
                },
            )
        }
    }
    if (enabled && !allowed) {
        Text(
            text = stringResource(R.string.watch_no_permission),
            style = HarnessType.caption,
            color = palette.warningText,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
}

/**
 * Whether this app may post notifications.
 *
 * @param context any context.
 * @return true on releases that have no runtime permission, or when it was granted.
 */
private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
