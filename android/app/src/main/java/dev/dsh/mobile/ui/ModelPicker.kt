package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ModelInfo

/**
 * The model picker: everything this PC is configured to answer with.
 *
 * The list is the desktop picker's own catalog, so the phone offers the same
 * choices instead of a second, drifting idea of what is installed. A model's
 * capability badges — vision, context window — are shown only when the host
 * could resolve them, because an unknown capability is not a missing one.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedRoute by remember { mutableStateOf<String?>(null) }
    val chosen = viewModel.currentModel

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeModelPicker() },
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SheetHeader(
                title = stringResource(R.string.model_title),
                subtitle = stringResource(R.string.model_subtitle),
                leading = {
                    IconBadge(
                        icon = Icons.Outlined.AutoAwesome,
                        tint = palette.accent,
                        background = palette.accentContainer,
                        size = 40.dp,
                        iconSize = 20.dp,
                    )
                },
            ) {
                HarnessIconTarget(
                    icon = Icons.Rounded.Refresh,
                    description = stringResource(R.string.model_refresh),
                    background = palette.surfaceSunken,
                    onClick = { viewModel.refreshModels() },
                )
            }

            // The harness records a per-session choice as the deployment default
            // too, so this is not a phone-local setting and saying otherwise
            // would be a lie the user only discovers on the PC.
            Text(
                text = stringResource(R.string.model_note),
                style = HarnessType.caption,
                color = palette.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            viewModel.modelError?.let { message ->
                ProblemBanner(message = textResource(message), onDismiss = { viewModel.closeModelPicker() })
            }

            val catalog = viewModel.modelCatalog
            when {
                catalog == null && viewModel.modelsLoading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 3.dp)
                }

                catalog == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HarnessOutlineButton(label = stringResource(R.string.model_retry), tint = palette.accent, onClick = { viewModel.refreshModels() })
                }

                catalog.isEmpty -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.model_empty),
                        style = HarnessType.body,
                        color = palette.textSecondary,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                ) {
                    items(catalog.groups, key = { it.id }) { group ->
                        HarnessSectionLabel(group.displayName, Modifier.padding(horizontal = 4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.models.forEach { model ->
                            val route = "${model.provider}/${model.id}"
                            val isCurrent = chosen != null && chosen.provider == model.provider && chosen.model == model.id
                            ModelRow(
                                model = model,
                                current = isCurrent,
                                effort = if (isCurrent) chosen?.reasoningEffort else null,
                                busy = viewModel.switchingModel,
                                expanded = expandedRoute == route,
                                onExpand = { expandedRoute = if (expandedRoute == route) null else route },
                                onChoose = { effort -> viewModel.chooseModel(model, effort) },
                            )
                        }
                        }
                    }
                    catalog.failures.forEach { failure ->
                        item(key = "failure-${failure.id}") {
                            Text(
                                text = stringResource(
                                    R.string.model_failure,
                                    failure.name.ifBlank { failure.id },
                                    failure.message,
                                ),
                                style = HarnessType.caption,
                                color = palette.warningText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                    catalog.default?.let { selected ->
                        item(key = "default") {
                            val effort = selected.reasoningEffort
                            Text(
                                // The effort is part of the sentence rather than a
                                // suffix glued to it, so the two forms are two
                                // resources: a language is free to put it first.
                                text = if (effort == null) {
                                    stringResource(R.string.model_default, selected.route)
                                } else {
                                    stringResource(R.string.model_default_reasoning, selected.route, effort)
                                },
                                style = HarnessType.caption,
                                color = palette.textTertiary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One model row, with its capability badges and its effort chooser.
 *
 * @param model the catalog row.
 * @param current whether the session is already using it.
 * @param effort the effort in force for this model, when it is the current one.
 * @param busy whether a switch is in flight.
 * @param expanded whether the effort chooser is open.
 * @param onExpand toggles the chooser.
 * @param onChoose switches to this model, with the given effort.
 */
@Composable
private fun ModelRow(
    model: ModelInfo,
    current: Boolean,
    effort: String?,
    busy: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    val palette = LocalHarness.current
    val efforts = model.reasoning?.efforts.orEmpty()

    val shape = RoundedCornerShape(HarnessRadius.lg)
    Column(
        Modifier
            .fillMaxWidth()
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
            .clickable(enabled = !busy) {
                // A model that accepts several reasoning efforts gets a second
                // step, because choosing one silently would hide a real choice.
                if (efforts.isEmpty()) onChoose(null) else onExpand()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = HarnessType.body,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.provider,
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                    )
                    model.contextWindow?.let { window ->
                        Text(
                            text = " · " + formatContext(window),
                            style = HarnessType.caption,
                            color = palette.textTertiary,
                        )
                    }
                    if (model.acceptsImages) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = stringResource(R.string.model_accepts_images),
                            tint = palette.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            if (current) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.model_in_use),
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            } else if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.accent)
            }
        }

        if (expanded && efforts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                efforts.forEach { option ->
                    val selected = option.id == (effort ?: model.reasoning?.defaultEffort)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HarnessRadius.pill))
                            .then(
                                if (selected) {
                                    Modifier.background(palette.brandGradient)
                                } else {
                                    Modifier
                                        .background(palette.surface)
                                        .border(0.5.dp, palette.border, RoundedCornerShape(HarnessRadius.pill))
                                },
                            )
                            .clickable(enabled = !busy) { onChoose(option.id) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = option.name.ifBlank { option.id },
                            style = HarnessType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) palette.onAccent else palette.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Render a context window the way a person reads it.
 *
 * The word after the number is a word and not a code: English writes "512
 * tokens" where Italian writes "512 token", which is why the count goes through
 * a plural even though the two Italian forms of it read the same.
 *
 * @param window the token count.
 * @return a short label such as `128k`.
 */
@Composable
private fun formatContext(window: Long): String =
    if (window >= 1000) {
        stringResource(R.string.model_context_k, window / 1000)
    } else {
        pluralStringResource(R.plurals.model_context_tokens, window.toInt(), window)
    }
