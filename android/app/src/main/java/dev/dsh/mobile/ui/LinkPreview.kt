package dev.dsh.mobile.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.PreviewResponse

/**
 * The link preview: what the harness reads when the phone taps a link.
 *
 * The harness's own client opens chat links in an embedded browser tab; a phone
 * has no such tab, so the page is fetched where the harness's fetch policy
 * already lives and served here as its words. What this sheet shows is therefore
 * a reading of the page, not the page — scripts never ran, and anything they
 * would have drawn is absent. The system browser stays one explicit tap away for
 * the pages that need to be the page.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPreviewSheet(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val url = viewModel.previewUrl ?: return
    val preview = viewModel.preview
    // Held here because it is view state about the last gesture, not about the
    // preview: it clears with the sheet and never outlives a dismiss.
    var browserRefused by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeLink() },
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            // The address is the one thing the reader should never have to take
            // on trust: it stays visible above whatever the page turned out to
            // be titled.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Filled.Language,
                    tint = palette.accent,
                    background = palette.accentContainer,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = preview?.title ?: hostOf(url),
                        style = HarnessType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = url,
                        style = HarnessType.codeBlock,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { viewModel.retryPreview() }, enabled = !viewModel.previewLoading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.preview_retry),
                        tint = palette.textSecondary,
                    )
                }
                IconButton(onClick = { openInBrowser(context, url) { browserRefused = true } }) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.preview_browser),
                        tint = palette.textSecondary,
                    )
                }
                HarnessIconTarget(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.preview_close),
                    background = palette.surfaceSunken,
                    onClick = { viewModel.closeLink() },
                )
            }

            if (browserRefused) {
                Text(
                    text = stringResource(R.string.preview_no_browser),
                    style = HarnessType.caption,
                    color = palette.warningText,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            when {
                viewModel.previewLoading -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = stringResource(R.string.preview_reading),
                            style = HarnessType.secondary,
                            color = palette.textSecondary,
                        )
                    }
                }

                viewModel.previewError != null -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                text = textResourceOrNull(viewModel.previewError).orEmpty(),
                                style = HarnessType.secondary,
                                color = palette.warningText,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = { viewModel.retryPreview() }) {
                                    Text(stringResource(R.string.preview_retry), color = palette.accent)
                                }
                                TextButton(onClick = { openInBrowser(context, url) { browserRefused = true } }) {
                                    Text(stringResource(R.string.preview_browser), color = palette.textSecondary)
                                }
                            }
                        }
                    }
                }

                preview != null -> PreviewBody(preview)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The page's own words.
 *
 * @param preview what the host served.
 */
@Composable
private fun PreviewBody(preview: PreviewResponse) {
    val palette = LocalHarness.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        preview.description?.let { description ->
            Text(
                text = description,
                style = HarnessType.secondary,
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        SelectionContainer {
            if (preview.fromHtml) {
                Text(
                    text = preview.text,
                    style = HarnessType.body.copy(lineHeight = 24.sp),
                    color = palette.textPrimary,
                )
            } else {
                Text(
                    text = preview.text,
                    style = HarnessType.codeBlock.copy(fontFamily = FontFamily.Monospace),
                    color = palette.textPrimary,
                )
            }
        }
        if (preview.truncated) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.preview_truncated),
                style = HarnessType.caption,
                color = palette.textTertiary,
            )
        }
    }
}

/**
 * The host of one address, for the moment before a title arrives.
 *
 * @param url the address.
 * @returns the host, or the address itself when it has none to show.
 */
private fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url

/**
 * Hand one address to the system browser.
 *
 * This is the sheet's explicit escape hatch, kept as a deliberate second tap:
 * the page a reader trusts and the page an agent cited are not always the same
 * thing, and the browser gets to be the judge of cookies and scripts.
 *
 * @param context the current context.
 * @param url the address to open.
 * @param refused invoked when nothing on the device will take it.
 */
private fun openInBrowser(context: android.content.Context, url: String, refused: () -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) {
        refused()
    }
}
