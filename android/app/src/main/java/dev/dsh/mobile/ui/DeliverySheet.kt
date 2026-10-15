package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R

/**
 * One file an agent delivered, as the phone can actually use it.
 *
 * A delivery is not a link and not an attachment: the agent said *this is what I
 * made for you*, and the desktop answers that by opening the file natively. A
 * phone has no such shortcut, so this sheet is where the file becomes usable —
 * the reader sees what the host found (the resolved absolute path, the size, and
 * the text when there is text), and the three things a phone can do with a file
 * sit above it: open it with whichever app handles the format, save it to the
 * Download folder, or hand it to another app.
 *
 * Which of those is offered follows from the content rather than from the file
 * name: a delivered spreadsheet has no text to show here, and its rows are
 * therefore *Apri con un'app*, *Scarica* and *Condividi* ([R.string.delivery_action_open],
 * [R.string.delivery_action_save] and [R.string.delivery_action_share]), while a
 * delivered note is already readable in place and keeps only the two that move it.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliverySheet(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val ref = viewModel.deliveryRef ?: return
    val file = viewModel.deliveryFile
    val index = ref.declared.index
    val key = deliveryKey(ref.sessionId, ref.seq, index)
    val notice = viewModel.deliveryNotices[key]
    val busy = viewModel.deliveryBusy?.takeIf {
        it.sessionId == ref.sessionId && it.seq == ref.seq && it.index == index
    }
    // The declaration is what the reader tapped, so it is what the header says
    // until — and only until — the host's own answer arrives.
    val name = file?.displayName ?: ref.declared.name
    val path = file?.path?.takeIf { it.isNotBlank() } ?: ref.declared.path
    val description = file?.description?.takeIf { it.isNotBlank() } ?: ref.declared.description

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeDelivery() },
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Outlined.FilePresent,
                    tint = palette.accent,
                    background = palette.accentContainer,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = HarnessType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = path,
                        style = HarnessType.codeBlock,
                        color = palette.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildList {
                        file?.let { add(humanBytes(it.bytes)) }
                        if (file?.binary == true) add(stringResource(R.string.delivery_meta_not_text))
                        if (file?.truncated == true) add(stringResource(R.string.delivery_meta_cut_short))
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta.joinToString(" · "),
                            style = HarnessType.caption,
                            color = palette.textCaption,
                            maxLines = 1,
                        )
                    }
                }
                HarnessIconTarget(
                    icon = Icons.Filled.Refresh,
                    description = stringResource(R.string.delivery_ask_again),
                    enabled = !viewModel.deliveryLoading,
                    onClick = { viewModel.retryDelivery() },
                )
                HarnessIconTarget(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.delivery_close),
                    background = palette.surfaceSunken,
                    onClick = { viewModel.closeDelivery() },
                )
            }

            if (description != null) {
                Text(
                    text = description,
                    style = HarnessType.secondary,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            if (file != null && viewModel.deliveryError == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!file.readable) {
                        HarnessOutlineButton(
                            label = stringResource(R.string.delivery_action_open),
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            enabled = busy == null,
                            onClick = {
                                viewModel.openDeliveryExternally(
                                    context,
                                    ref.sessionId,
                                    ref.seq,
                                    index,
                                    file.path.ifBlank { ref.declared.path },
                                )
                            },
                        )
                    }
                    HarnessOutlineButton(
                        label = stringResource(R.string.delivery_action_save),
                        icon = Icons.Outlined.FileDownload,
                        enabled = busy == null,
                        onClick = {
                            viewModel.saveDelivery(
                                context,
                                ref.sessionId,
                                ref.seq,
                                index,
                                file.path.ifBlank { ref.declared.path },
                            )
                        },
                    )
                    HarnessOutlineButton(
                        label = stringResource(R.string.delivery_action_share),
                        icon = Icons.Outlined.Share,
                        enabled = busy == null,
                        onClick = {
                            viewModel.shareDelivery(
                                context,
                                ref.sessionId,
                                ref.seq,
                                index,
                                file.path.ifBlank { ref.declared.path },
                            )
                        },
                    )
                    if (busy != null) {
                        RunningRing(palette.accent, 14.dp)
                    }
                }
            }

            if (notice != null) {
                Text(
                    text = notice.text,
                    style = HarnessType.caption,
                    color = if (notice.ok) palette.success else palette.warningText,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            when {
                viewModel.deliveryLoading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.delivery_opening),
                            style = HarnessType.secondary,
                            color = palette.textSecondary,
                        )
                    }
                }

                viewModel.deliveryError != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(HarnessRadius.lg))
                            .background(palette.warning.copy(alpha = 0.10f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column {
                            Text(
                                text = textResourceOrNull(viewModel.deliveryError).orEmpty(),
                                style = HarnessType.secondary,
                                color = palette.warningText,
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { viewModel.retryDelivery() }) {
                                Text(stringResource(R.string.delivery_retry), color = palette.accent)
                            }
                        }
                    }
                }

                file != null && file.readable -> FileTextBody(
                    text = file.text,
                    markdown = file.isMarkdown,
                    truncated = file.truncated,
                    onOpenLink = { viewModel.openLink(it) },
                )

                file != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.delivery_binary_body),
                        style = HarnessType.secondary,
                        color = palette.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
