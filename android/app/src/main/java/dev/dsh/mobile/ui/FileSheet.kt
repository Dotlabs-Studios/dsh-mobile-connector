package dev.dsh.mobile.ui

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
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.HostFile

/**
 * A file an agent linked, read from the PC's own disk.
 *
 * The other half of the reading sheet. A link is not always an address — a reply
 * that just created `LORE-bozza.md` links it exactly like a page, and sending that
 * to the host's web fetcher produced *not a valid URL: LORE-bozza.md*, which was
 * the fetcher being right about a reference that was never a URL. Here the host
 * resolves the reference the way the agent meant it — an absolute path as it
 * stands, a bare name next to the chat that printed it — and serves the file's own
 * text, bounded.
 *
 * Markdown is rendered rather than shown, because the transcript already renders
 * it and a linked note is the same text: reading `**Attenzione**` where the author
 * wrote an emphasis is what makes a document look like a markup dump. Everything
 * else keeps its characters exactly as they are on disk — a source file, a config
 * and a diff are *read*, and reinterpreting their punctuation would hide the very
 * thing the reader opened them for.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSheet(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reference = viewModel.fileRef ?: return
    val file = viewModel.file

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeFile() },
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
                    icon = Icons.Filled.Description,
                    tint = palette.accent,
                    background = palette.accentContainer,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // The name is the title once the host has answered; before
                    // that the reference is all there is, and it is what was
                    // tapped, so it is the honest thing to show.
                    Text(
                        text = file?.name?.takeIf { it.isNotBlank() } ?: reference,
                        style = HarnessType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The resolved path, which is how a *relative* link becomes
                    // legible: it says which file on which disk was meant.
                    Text(
                        text = file?.path?.takeIf { it.isNotBlank() } ?: reference,
                        style = HarnessType.codeBlock,
                        color = palette.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (file != null) {
                        val meta = buildList {
                            add(humanBytes(file.bytes))
                            if (file.truncated) add(stringResource(R.string.file_meta_cut_short))
                            file.line?.let { add(stringResource(R.string.file_meta_line, it)) }
                        }
                        Text(
                            text = meta.joinToString(" · "),
                            style = HarnessType.caption,
                            color = palette.textCaption,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = { viewModel.retryFile() }, enabled = !viewModel.fileLoading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.file_read_again),
                        tint = palette.textSecondary,
                    )
                }
                HarnessIconTarget(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.file_close),
                    background = palette.surfaceSunken,
                    onClick = { viewModel.closeFile() },
                )
            }

            when {
                viewModel.fileLoading -> Box(
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
                            text = stringResource(R.string.file_reading),
                            style = HarnessType.secondary,
                            color = palette.textSecondary,
                        )
                    }
                }

                viewModel.fileError != null -> Column(
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
                                text = textResourceOrNull(viewModel.fileError).orEmpty(),
                                style = HarnessType.secondary,
                                color = palette.warningText,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row {
                                TextButton(onClick = { viewModel.retryFile() }) {
                                    Text(stringResource(R.string.file_retry), color = palette.accent)
                                }
                            }
                        }
                    }
                }

                file != null -> FileBody(file, onOpenLink = { viewModel.openLink(it) })
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The file's own content.
 *
 * @param file what the host served.
 * @param onOpenLink what a link inside the file does: a document that links
 *   another document is the normal case, and the tap has to keep working one
 *   level down or the reader is a dead end.
 */
@Composable
private fun FileBody(file: HostFile, onOpenLink: (String) -> Unit) {
    FileTextBody(
        text = file.text,
        markdown = file.isMarkdown,
        truncated = file.truncated,
        onOpenLink = onOpenLink,
    )
}

/**
 * One file's text, drawn the way its own kind asks for.
 *
 * Shared with the delivery sheet, and deliberately so: a markdown note an agent
 * *linked* and a markdown note an agent *delivered* are the same document, and a
 * reader who learns what a rendered table looks like in one of them should not
 * meet the raw pipes in the other.
 *
 * @param text the content, as the host served it.
 * @param markdown whether to render it rather than show its characters.
 * @param truncated whether the host cut the text at its own bound.
 * @param onOpenLink what a link inside the text does.
 */
@Composable
internal fun FileTextBody(text: String, markdown: Boolean, truncated: Boolean, onOpenLink: (String) -> Unit) {
    val palette = LocalHarness.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        if (markdown) {
            MarkdownBody(text = text, color = palette.textPrimary, onOpenLink = onOpenLink)
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    style = HarnessType.codeBlock.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                    color = palette.textPrimary,
                )
            }
        }
        if (truncated) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.file_truncated),
                style = HarnessType.caption,
                color = palette.textTertiary,
            )
        }
    }
}
