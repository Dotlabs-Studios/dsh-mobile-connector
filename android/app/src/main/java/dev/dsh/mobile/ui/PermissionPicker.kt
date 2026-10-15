package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.PermissionOption
import dev.dsh.mobile.data.PermissionPreset
import dev.dsh.mobile.data.hostLabel
import dev.dsh.mobile.data.preset

/**
 * The icon for one preset.
 *
 * The shape of the lock is the point: it says what the agent may do without
 * reading the label, and it is the only warning the user gets before granting
 * something that cannot be taken back once the agent has acted.
 *
 * @param option the preset.
 * @returns the icon.
 */
private fun permissionIcon(option: PermissionOption): ImageVector = when (option.preset) {
    PermissionPreset.ReadOnly -> Icons.Outlined.Lock
    PermissionPreset.WorkspaceWrite -> Icons.Outlined.Edit
    PermissionPreset.FullAccess -> Icons.Outlined.LockOpen
    // Auto sets no sandbox of its own — a reviewer decides instead — so no lock
    // shape describes it, and a value this app cannot word has even less to go on.
    PermissionPreset.Auto, PermissionPreset.Other -> Icons.Outlined.WarningAmber
}

/**
 * The title for one preset.
 *
 * The app words the presets the harness ships, because the host's labels are
 * English and this app is not. A value it does not know keeps the host's own
 * label, and the host's raw value when it wrote no label either.
 *
 * @param option the preset.
 * @returns the title to show.
 */
@Composable
private fun permissionTitle(option: PermissionOption): String = when (option.preset) {
    PermissionPreset.ReadOnly -> stringResource(R.string.perm_preset_read_only)
    PermissionPreset.WorkspaceWrite -> stringResource(R.string.perm_preset_workspace_write)
    PermissionPreset.FullAccess -> stringResource(R.string.perm_preset_full_access)
    PermissionPreset.Auto -> stringResource(R.string.perm_preset_auto)
    PermissionPreset.Other -> option.hostLabel
}

/**
 * What a preset means, in the app's own words.
 *
 * Falls back to the host's description for a preset this app does not know, and
 * to nothing when the host wrote none: an invented sentence about a preset whose
 * knobs cannot be seen would be worse than silence, which is why this returns
 * null rather than a comforting placeholder.
 *
 * @param option the preset.
 * @returns the explanation, or null when there is nothing honest to say.
 */
@Composable
private fun permissionDetail(option: PermissionOption): String? = when (option.preset) {
    PermissionPreset.ReadOnly -> stringResource(R.string.perm_detail_read_only)
    PermissionPreset.WorkspaceWrite -> stringResource(R.string.perm_detail_workspace_write)
    PermissionPreset.FullAccess -> stringResource(R.string.perm_detail_full_access)
    // Auto is the one preset the host may explain better than this app can, so
    // its own sentence wins when it wrote one.
    PermissionPreset.Auto -> option.description ?: stringResource(R.string.perm_detail_auto)
    PermissionPreset.Other -> option.description
}

/**
 * The short label for the chip beside the composer.
 *
 * A chat the host reported no preset for is not running under one, so the chip
 * says what it opens rather than naming a permission the session may not have.
 *
 * @param option the preset in force, when known.
 * @returns the label.
 */
@Composable
private fun permissionChipLabel(option: PermissionOption?): String {
    if (option == null) return stringResource(R.string.perm_chip_none)
    return when (option.preset) {
        PermissionPreset.ReadOnly -> stringResource(R.string.perm_preset_read_only)
        PermissionPreset.WorkspaceWrite -> stringResource(R.string.perm_chip_workspace_write)
        PermissionPreset.FullAccess -> stringResource(R.string.perm_preset_full_access)
        PermissionPreset.Auto -> stringResource(R.string.perm_preset_auto)
        PermissionPreset.Other -> option.hostLabel
    }
}

/**
 * The permission selector: what the agent may do in this chat.
 *
 * This is the harness's Permissions selector, and the reason it is worth having
 * on a phone is that the alternative is walking to the PC — but it is also the
 * only control here that widens what the agent is allowed to do, so the
 * consequence is written on every row rather than implied by its name. "Full
 * access" is not a synonym for "fast": it bundles a sandbox with no boundaries
 * and an approval policy of `never`, which means the agent stops asking.
 *
 * Granting it goes through the same acknowledgement the desktop demands, for the
 * same reason: a security-relevant change made by a stray tap is not a feature.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionPicker(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val catalog = viewModel.permissionCatalog
    // Held here rather than in the view model: an unconfirmed intention is
    // view state, and losing it when the sheet closes is the correct outcome.
    var granting by remember { mutableStateOf<PermissionOption?>(null) }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closePermissions() },
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SheetHeader(
                title = stringResource(R.string.perm_title),
                subtitle = stringResource(R.string.perm_subtitle),
                leading = {
                    IconBadge(
                        icon = Icons.Outlined.Shield,
                        tint = palette.accent,
                        background = palette.accentContainer,
                        size = 40.dp,
                        iconSize = 20.dp,
                    )
                },
            )

            catalog?.defaultPreset?.let { preset ->
                val default = catalog.optionFor(preset)
                if (default != null) {
                    // The default for *new* sessions is a different knob, in the
                    // PC's settings; saying so avoids the reasonable assumption
                    // that changing this chat changes the next one too.
                    Text(
                        text = stringResource(R.string.perm_default_note, permissionTitle(default)),
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
                    )
                }
            }

            viewModel.permissionError?.let { message ->
                ProblemBanner(message = textResource(message), onDismiss = { viewModel.closePermissions() })
            }

            when {
                catalog == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = textResourceOrNull(viewModel.permissionError) ?: stringResource(R.string.perm_none),
                        style = HarnessType.secondary,
                        color = palette.textSecondary,
                    )
                }

                else -> catalog.options.forEach { option ->
                    PermissionRow(
                        option = option,
                        current = option.value == catalog.current,
                        // The host owns what a preset does; when it cannot say,
                        // the row says that instead of inventing a promise.
                        detail = permissionDetail(option),
                        busy = viewModel.switchingPermission,
                        onChoose = {
                            if (option.grantsWithoutAsking) granting = option else viewModel.choosePermission(option.value)
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    granting?.let { option ->
        FullAccessConfirmation(
            onCancel = { granting = null },
            onConfirm = {
                granting = null
                viewModel.choosePermission(option.value)
            },
        )
    }
}

/**
 * One preset row.
 *
 * @param option the preset.
 * @param current whether the chat is already under it.
 * @param detail what it means, when anything honest can be said.
 * @param busy whether a switch is in flight.
 * @param onChoose switches to this preset.
 */
@Composable
private fun PermissionRow(
    option: PermissionOption,
    current: Boolean,
    detail: String?,
    busy: Boolean,
    onChoose: () -> Unit,
) {
    val palette = LocalHarness.current
    val risky = option.grantsWithoutAsking
    val shape = RoundedCornerShape(HarnessRadius.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .then(
                if (current) {
                    Modifier
                        .background(palette.accentContainer)
                        .border(1.dp, palette.accent.copy(alpha = 0.6f), shape)
                } else {
                    Modifier
                        .background(palette.surfaceSunken)
                        .border(0.5.dp, palette.borderSubtle, shape)
                },
            )
            .clickable(enabled = !busy && !current, onClick = onChoose)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = permissionIcon(option),
                tint = when {
                    risky -> palette.warningText
                    current -> palette.accent
                    else -> palette.textSecondary
                },
                background = when {
                    risky -> palette.warning.copy(alpha = 0.16f)
                    current -> palette.surface
                    else -> palette.surface
                },
                size = 32.dp,
                iconSize = 17.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = permissionTitle(option),
                style = HarnessType.body,
                fontWeight = FontWeight.Medium,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (current) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.perm_in_use),
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        detail?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(text = text, style = HarnessType.secondary, color = palette.textTertiary)
        }
        if (risky) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.perm_no_approval),
                style = HarnessType.caption,
                fontWeight = FontWeight.SemiBold,
                color = palette.warningText,
                modifier = Modifier
                    .clip(RoundedCornerShape(HarnessRadius.pill))
                    .background(palette.warning.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The acknowledgement the desktop demands before granting Full access.
 *
 * The wording is the harness's own, translated: full access reduces the
 * confirmation steps and lets more happen directly, including sensitive
 * operations, file changes and external commands. The button stays disabled
 * until the box is ticked, because the point of the dialog is that somebody read
 * it.
 *
 * @param onCancel abandons the switch.
 * @param onConfirm grants it.
 */
@Composable
private fun FullAccessConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalHarness.current
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        shape = RoundedCornerShape(HarnessRadius.panel),
        icon = {
            IconBadge(
                icon = Icons.Outlined.WarningAmber,
                tint = palette.warningText,
                background = palette.warning.copy(alpha = 0.16f),
                size = 44.dp,
                iconSize = 22.dp,
            )
        },
        title = { Text(stringResource(R.string.perm_full_access_title), style = androidx.compose.material3.MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.perm_full_access_body),
                    style = HarnessType.secondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(HarnessRadius.sm))
                        .clickable { acknowledged = !acknowledged },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        colors = CheckboxDefaults.colors(checkedColor = palette.accent, uncheckedColor = palette.textTertiary),
                    )
                    Text(
                        text = stringResource(R.string.perm_full_access_ack),
                        style = HarnessType.secondary,
                        color = palette.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = acknowledged) {
                Text(
                    text = stringResource(R.string.perm_full_access_confirm),
                    fontWeight = FontWeight.SemiBold,
                    color = if (acknowledged) palette.warningText else palette.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.perm_cancel), color = palette.textSecondary) }
        },
    )
}

/**
 * The access chip beside the composer.
 *
 * Coloured by consequence rather than by state: a chat running with full access
 * says so at a glance, in the place the user is about to type into.
 *
 * @param option the preset in force, when the host reported one.
 * @param enabled whether the picker can be opened at all.
 * @param onClick opens the picker.
 */
@Composable
fun PermissionChip(option: PermissionOption?, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalHarness.current
    val risky = option?.grantsWithoutAsking == true
    HarnessChip(
        label = permissionChipLabel(option),
        onClick = onClick,
        enabled = enabled,
        leadingIcon = when (option?.preset) {
            PermissionPreset.ReadOnly -> Icons.Outlined.Lock
            PermissionPreset.FullAccess -> Icons.Outlined.LockOpen
            PermissionPreset.WorkspaceWrite -> Icons.Outlined.Edit
            else -> Icons.Outlined.Lock
        },
        tint = when {
            risky -> palette.warningText
            option == null -> palette.textTertiary
            else -> palette.textSecondary
        },
        background = if (risky) palette.warning.copy(alpha = 0.16f) else null,
    )
}
