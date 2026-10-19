package dev.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R

/**
 * What this link actually is, on one screen.
 *
 * The list is the answer to the two questions a broken connection raises: is the PC
 * there (latency, address), and is the plugin on it new enough for what this build
 * expects (`apiVersion`). The copy button exists because the useful place for this
 * report is usually a chat with somebody else.
 *
 * @param viewModel the app state holder, read for the health answer.
 * @param onDismiss closes the dialog.
 */
@Composable
internal fun DiagnosticsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalHarness.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { viewModel.refreshHealth() }

    val health = viewModel.health
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val rows = buildList {
        add(stringResource(R.string.diagnostics_app) to appVersion)
        add(
            stringResource(R.string.diagnostics_plugin) to (
                health?.let {
                    stringResource(
                        R.string.diagnostics_server_version,
                        it.pluginVersion.ifBlank { "?" },
                        it.apiVersion,
                    )
                } ?: stringResource(R.string.diagnostics_unavailable)
                ),
        )
        add(stringResource(R.string.diagnostics_address) to (viewModel.pairedHost ?: "—"))
        viewModel.healthLatencyMs?.let { add(stringResource(R.string.diagnostics_latency) to stringResource(R.string.diagnostics_latency_value, it)) }
        health?.let {
            add(stringResource(R.string.diagnostics_uptime) to stringResource(R.string.diagnostics_uptime_value, it.uptimeSeconds / 60))
            add(
                stringResource(R.string.diagnostics_queues) to stringResource(
                    R.string.diagnostics_queue_value,
                    it.pendingApprovals,
                    it.pendingQuestions,
                ),
            )
        }
    }
    val report = rows.joinToString("\n") { (label, value) -> "$label: $value" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text(stringResource(R.string.diagnostics_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(text = label, style = HarnessType.caption, color = palette.textTertiary)
                        Text(
                            text = value,
                            style = HarnessType.body,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary,
                        )
                    }
                }
                viewModel.healthError?.let { error ->
                    Text(text = textResource(error), style = HarnessType.caption, color = palette.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text(stringResource(R.string.component_copy), color = palette.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = palette.textSecondary)
            }
        },
    )
}
