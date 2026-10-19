package dev.dsh.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.LanguageMode
import dev.dsh.mobile.data.PairedDevice
import dev.dsh.mobile.data.SessionStore
import dev.dsh.mobile.data.ThemeMode
import dev.dsh.mobile.data.drawnTag
import dev.dsh.mobile.data.inForce
import dev.dsh.mobile.watch.WatchService
import java.text.DateFormat
import java.util.Date

/**
 * The settings sheet: appearance, and how to walk away from the PC.
 *
 * It holds exactly what a phone client owns — the palette, and the pairing —
 * and none of the harness's own settings, which belong on the desktop where
 * they can be seen against the thing they configure.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Asked when the sheet opens, not polled: this is a question you ask before
    // lending the phone, not one that changes while you look at it.
    LaunchedEffect(Unit) { viewModel.loadDevices() }
    var pendingRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    var addressOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeSettings() },
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SheetHeader(title = stringResource(R.string.label_settings))

            HarnessSectionLabel(stringResource(R.string.settings_section_appearance), Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    ThemeTile(
                        mode = mode,
                        selected = viewModel.themeMode == mode,
                        onClick = { viewModel.chooseTheme(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_theme_note),
                style = HarnessType.caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )

            HarnessSectionLabel(stringResource(R.string.settings_language), Modifier.padding(horizontal = 16.dp))
            LanguagePicker(viewModel, Modifier.padding(horizontal = 20.dp))
            Text(
                text = languageNote(viewModel.language),
                style = HarnessType.caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )

            HarnessSectionLabel(stringResource(R.string.settings_section_connection), Modifier.padding(horizontal = 16.dp))
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
                        icon = Icons.Outlined.Computer,
                        tint = palette.accent,
                        background = palette.accentContainer,
                        size = 40.dp,
                        iconSize = 20.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (viewModel.pairedHost != null) {
                                stringResource(R.string.settings_connected)
                            } else {
                                stringResource(R.string.settings_not_connected)
                            },
                            style = HarnessType.body,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                        )
                        viewModel.pairedHost?.let { host ->
                            Text(
                                text = host,
                                style = HarnessType.codeBlock.copy(fontSize = HarnessType.caption.fontSize),
                                color = palette.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    viewModel.pairedHost?.let {
                        // The PC can move without the pairing changing, so the
                        // address is editable on its own instead of being welded
                        // to an unpair-and-pair-again.
                        TextButton(onClick = { addressOpen = true }) {
                            Text(
                                text = stringResource(R.string.settings_change_address),
                                style = HarnessType.caption,
                                color = palette.accent,
                            )
                        }
                    }
                }
            }

            WatchSection(viewModel)

            HarnessSectionLabel(stringResource(R.string.settings_section_devices), Modifier.padding(horizontal = 16.dp))
            Text(
                text = stringResource(R.string.settings_devices_note),
                style = HarnessType.caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (viewModel.devicesLoading && viewModel.devices.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = palette.accent,
                        strokeWidth = 2.dp,
                    )
                }
            }
            viewModel.devicesError?.let { error ->
                Text(
                    text = textResource(error),
                    style = HarnessType.caption,
                    color = palette.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            viewModel.devices.forEach { device ->
                DeviceRow(device = device, onRevoke = { pendingRevoke = device })
            }

            HarnessOutlineButton(
                label = stringResource(R.string.action_unpair),
                icon = Icons.Outlined.LinkOff,
                tint = palette.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                onClick = {
                    viewModel.closeSettings()
                    viewModel.unpair()
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // Revoking is the one action here that cannot be undone from the phone, so it
    // asks first and says what happens to the device it is aimed at.
    pendingRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = { Text(stringResource(R.string.devices_revoke_title, device.displayName)) },
            text = {
                Text(
                    stringResource(
                        if (device.current) R.string.devices_revoke_self_body else R.string.devices_revoke_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevoke = null
                        viewModel.revokeDevice(device.id)
                    },
                ) {
                    Text(stringResource(R.string.action_revoke), color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.action_cancel), color = palette.textSecondary)
                }
            },
        )
    }

    // Changing the address is not a new pairing: the token names this device in
    // the harness's table, and the PC is free to move.
    if (addressOpen) {
        var address by remember(viewModel.pairedHost) { mutableStateOf(viewModel.pairedHost.orEmpty()) }
        AlertDialog(
            onDismissRequest = { addressOpen = false },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = { Text(stringResource(R.string.settings_address_title)) },
            text = {
                Column {
                    HarnessTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = stringResource(R.string.pair_host_example),
                        keyboardType = KeyboardType.Uri,
                        filled = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_address_note),
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (viewModel.changeHost(address)) addressOpen = false }) {
                    Text(stringResource(R.string.action_save), color = palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { addressOpen = false }) {
                    Text(stringResource(R.string.action_cancel), color = palette.textSecondary)
                }
            },
        )
    }
}

/**
 * One paired phone, with the one action that matters on it.
 *
 * @param device the device as the host listed it.
 * @param onRevoke asks for confirmation before revoking.
 */
@Composable
private fun DeviceRow(device: PairedDevice, onRevoke: () -> Unit) {
    val palette = LocalHarness.current
    HarnessSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        background = palette.surfaceSunken,
        border = palette.borderSubtle,
        radius = HarnessRadius.lg,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.Smartphone,
                tint = if (device.current) palette.accent else palette.textTertiary,
                background = if (device.current) palette.accentContainer else palette.surface,
                size = 34.dp,
                iconSize = 17.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.displayName,
                        style = HarnessType.body,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.current) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_device_current),
                            style = HarnessType.caption,
                            color = palette.accent,
                        )
                    }
                }
                Text(
                    text = if (device.lastSeenAt > 0L) {
                        stringResource(
                            R.string.settings_device_last_seen,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(device.lastSeenAt)),
                        )
                    } else {
                        stringResource(R.string.settings_device_never)
                    },
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            HarnessOutlineButton(
                label = stringResource(R.string.action_revoke),
                tint = palette.error,
                onClick = onRevoke,
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

/**
 * The language choice: the phone's own, Italian, or English.
 *
 * A segmented control rather than three tiles, because these are not three
 * *appearances* to compare — they are three answers to one question, and the
 * question is which language the app should speak. Each segment is written in
 * the language it selects, which is the only label that stays true whichever
 * language is in force: "Italiano" is not translated into English, and
 * "English" is not translated into Italian.
 *
 * Tapping one that is not the current choice rebuilds the activity. It has to:
 * the strings are resolved from the `Context` the activity was built on, and a
 * `Context` cannot change the language of its own resources once it exists. The
 * rebuild is invisible — the sheet is redrawn open, in the new language — and it
 * is the same mechanism Android uses for its own per-app language setting.
 *
 * @param viewModel the app state holder.
 * @param modifier layout for the control.
 */
@Composable
private fun LanguagePicker(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.findActivity()
    // The phone's own languages, best first: what "Sistema" resolves to, and what
    // the sheet has to name when the app is following it.
    val locales = LocalConfiguration.current.locales.toLanguageTags().split(",").filter { it.isNotBlank() }
    val options = LanguageMode.entries
    val selected = options.indexOf(inForce(viewModel.language, locales))

    SegmentedControl(
        options = options.map { languageLabel(it) },
        selected = selected,
        onSelect = { index ->
            val chosen = options[index]
            if (viewModel.chooseLanguage(chosen)) activity?.recreate()
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The activity behind a composition's context.
 *
 * `LocalContext.current` is not an activity: Android wraps one in a
 * `ContextThemeWrapper`, and a theme applied by Compose or by the manifest puts
 * another layer on top. A bare `as? Activity` therefore answers null — silently,
 * which is how a language choice gets stored and then never applied, with the
 * screen keeping the old language while the note underneath it describes the new
 * one. Walking the wrappers is what makes "ask the screen to rebuild itself"
 * actually reach the screen.
 *
 * @return the activity, or null in a composition that has none (a preview, a test).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What one language segment says.
 *
 * @param mode the mode.
 * @return its label, written in the language it selects.
 */
@Composable
private fun languageLabel(mode: LanguageMode): String = when (mode) {
    LanguageMode.System -> stringResource(R.string.language_system_label)
    LanguageMode.Italian -> stringResource(R.string.language_it_label)
    LanguageMode.English -> stringResource(R.string.language_en_label)
}

/**
 * The line under the language control: what the phone's own setting resolves to.
 *
 * The ticked segment already says which choice is in force, so the useful thing
 * left to say is *which language that comes out as* — and on a phone set to a
 * language this app does not ship, that is not a detail. It is being shown
 * Italian, because Italian is the base language and the fallback, and a note
 * that quietly promised otherwise would be wrong in exactly the case the setting
 * is easiest to misread.
 *
 * @param mode the language in force.
 * @return the sentence to draw.
 */
@Composable
private fun languageNote(mode: LanguageMode): String {
    if (mode != LanguageMode.System) return stringResource(R.string.settings_language_note)
    val locales = LocalConfiguration.current.locales.toLanguageTags().split(",").filter { it.isNotBlank() }
    val drawn = drawnTag(mode, locales, LanguageMode.entries.mapNotNull { it.tag })
        ?: return stringResource(R.string.language_follow_phone_unknown)
    return stringResource(R.string.language_follow_phone, languageName(drawn))
}

/**
 * One language, named in the language the app is speaking.
 *
 * "Italiano" is the name of a language in Italian, and "Italian" is the same name
 * in English; a sentence that says which language is in force is part of one or
 * the other, so the name follows the sentence rather than standing on its own.
 * That is why these two are separate resources from the segments above, which are
 * deliberately written in the language each one selects.
 *
 * @param tag the tag to name.
 * @return the name, or the tag itself for a language with no name here yet.
 */
@Composable
private fun languageName(tag: String): String = when (tag) {
    "it" -> stringResource(R.string.language_it_name)
    "en" -> stringResource(R.string.language_en_name)
    else -> tag
}

/**
 * The icon that stands for one palette choice.
 *
 * @param mode the mode.
 * @return its glyph.
 */
private fun iconOf(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.System -> Icons.Outlined.SettingsBrightness
    ThemeMode.Light -> Icons.Outlined.LightMode
    ThemeMode.Dark -> Icons.Outlined.DarkMode
}

/**
 * One appearance choice, as a tile with a miniature of the palette it selects.
 *
 * The miniature is the point of the tile: three words could describe any app,
 * but a tiny chat drawn in the actual canvas, bubble and text colours is this
 * theme, and it makes the choice legible without tapping through it. "Sistema"
 * shows both halves, because it is both.
 *
 * @param mode the mode this tile selects.
 * @param selected whether it is the current one.
 * @param onClick invoked when the tile is used.
 * @param modifier layout, which carries the tile's share of the row.
 */
@Composable
private fun ThemeTile(mode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = LocalHarness.current
    val shape = RoundedCornerShape(HarnessRadius.lg)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) current.accentContainer else current.surfaceSunken)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) current.accent else current.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The miniature's edge comes from the current theme rather than from the
        // palette it previews: a border drawn in the previewed palette would be
        // invisible exactly when it is needed, which is a dark chip on a dark sheet.
        Box(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(HarnessRadius.md))
                .border(0.5.dp, current.border, RoundedCornerShape(HarnessRadius.md)),
        ) {
            when (mode) {
                ThemeMode.Light -> MiniChat(HarnessLight, Modifier.fillMaxSize())
                ThemeMode.Dark -> MiniChat(HarnessDark, Modifier.fillMaxSize())
                ThemeMode.System -> Row(Modifier.fillMaxSize()) {
                    MiniChat(HarnessLight, Modifier.weight(1f).fillMaxHeight())
                    MiniChat(HarnessDark, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconOf(mode),
                contentDescription = null,
                tint = if (selected) current.accent else current.textTertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(mode.label),
                style = HarnessType.caption,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) current.accent else current.textSecondary,
            )
        }
    }
}

/**
 * A tiny chat in one palette: an assistant line, and a user bubble.
 *
 * @param preview the palette to draw.
 * @param modifier layout for the miniature.
 */
@Composable
private fun MiniChat(preview: HarnessPalette, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(preview.background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.75f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(preview.textPrimary.copy(alpha = 0.7f)),
        )
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(preview.textCaption),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(preview.userBubble),
            )
        }
    }
}
