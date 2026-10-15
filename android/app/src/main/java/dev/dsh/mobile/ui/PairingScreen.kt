package dev.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R

/**
 * First-run screen: point the app at a harness and claim its pairing code.
 *
 * @param viewModel the app state holder.
 */
@Composable
fun PairingScreen(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 76.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.pairing_subtitle),
            style = HarnessType.body,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        HarnessSurface(
            modifier = Modifier.fillMaxWidth(),
            background = palette.surface,
            border = palette.borderSubtle,
            radius = HarnessRadius.xl,
            elevation = 8.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                FieldLabel(stringResource(R.string.field_host))
                HarnessTextField(
                    value = viewModel.hostInput,
                    onValueChange = { viewModel.hostInput = it },
                    placeholder = stringResource(R.string.pair_host_example),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    leadingIcon = Icons.Outlined.Computer,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                FieldLabel(stringResource(R.string.field_code))
                HarnessTextField(
                    value = viewModel.codeInput,
                    onValueChange = { viewModel.codeInput = it },
                    placeholder = stringResource(R.string.pair_code_hint),
                    imeAction = ImeAction.Next,
                    leadingIcon = Icons.Outlined.Key,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                FieldLabel(stringResource(R.string.field_device))
                HarnessTextField(
                    value = viewModel.deviceInput,
                    onValueChange = { viewModel.deviceInput = it },
                    placeholder = stringResource(R.string.pair_device_example),
                    imeAction = ImeAction.Done,
                    leadingIcon = Icons.Outlined.Smartphone,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (viewModel.pairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = palette.accent,
                        )
                    } else {
                        HarnessButton(
                            label = stringResource(R.string.action_pair),
                            onClick = { viewModel.pair() },
                            large = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                viewModel.pairingError?.let { message ->
                    Spacer(Modifier.height(14.dp))
                    HarnessSurface(
                        modifier = Modifier.fillMaxWidth(),
                        background = palette.error.copy(alpha = if (palette.isDark) 0.14f else 0.08f),
                        border = palette.error.copy(alpha = 0.3f),
                        radius = HarnessRadius.md,
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = palette.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = textResource(message),
                                style = HarnessType.secondary,
                                color = palette.textPrimary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Hint(
            icon = Icons.Outlined.Key,
            text = stringResource(R.string.pair_code_log),
        )
        Spacer(Modifier.height(10.dp))
        Hint(
            icon = Icons.Outlined.Public,
            text = stringResource(R.string.pair_tailscale),
        )
    }
}

/**
 * The label above a form field.
 *
 * @param text the label.
 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = HarnessType.caption,
        fontWeight = FontWeight.SemiBold,
        color = LocalHarness.current.textSecondary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/**
 * One line of help under the form, with its glyph.
 *
 * @param icon the glyph.
 * @param text the help.
 */
@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val palette = LocalHarness.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = HarnessType.caption, color = palette.textTertiary)
    }
}
