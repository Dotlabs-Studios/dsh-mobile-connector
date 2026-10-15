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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.data.MdBlock
import dev.dsh.mobile.data.MdInline
import dev.dsh.mobile.data.parseInline
import dev.dsh.mobile.data.parseMarkdown

/**
 * The markdown heading scale, sized against the 15sp body: three real steps
 * for the levels a reply actually uses, and body-sized bold for the rest.
 */
private val HeadingStyles = listOf(
    TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
    TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
)

/** How far one level of list nesting indents its text. */
private val LIST_INDENT = 16.dp

/**
 * Render a message body as markdown, the way the harness renders one.
 *
 * @param text the message body.
 * @param color the colour of ordinary prose.
 * @param modifier layout for the body.
 * @param onOpenLink what a tapped link does. The harness opens chat links in
 *   its in-app browser; a phone has no such browser, so this asks the host to
 *   read the page instead — the tap never reaches the phone's own browser.
 * @param fill whether the body takes the full width it is offered. A reply on
 *   the canvas does; a bubble does not, so a short message gets a short bubble.
 */
@Composable
fun MarkdownBody(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onOpenLink: ((String) -> Unit)? = null,
    fill: Boolean = true,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(if (fill) modifier.fillMaxWidth() else modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> BlockView(block, color, onOpenLink) }
    }
}

/**
 * Draw one parsed block.
 *
 * @param block the block.
 * @param color the colour of ordinary prose.
 * @param onOpenLink what a tapped link does, when there is one.
 */
@Composable
private fun BlockView(block: MdBlock, color: Color, onOpenLink: ((String) -> Unit)?) {
    val palette = LocalHarness.current
    when (block) {
        is MdBlock.Heading -> Text(
            text = annotated(parseInline(block.text), color, onOpenLink),
            style = HeadingStyles[(block.level - 1).coerceIn(0, HeadingStyles.lastIndex)],
            color = palette.textPrimary,
            modifier = Modifier.padding(top = if (block.level <= 3) 8.dp else 2.dp),
        )

        is MdBlock.Paragraph -> Text(
            text = annotated(parseInline(block.text), color, onOpenLink),
            style = HarnessType.body,
        )

        is MdBlock.CodeBlock -> CodeBlock(block.language, block.title, block.code)

        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item ->
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(LIST_INDENT * item.depth))
                    Text(
                        // An ordered item keeps the number the author wrote, so a
                        // list that starts at 3 or repeats a number reads as the
                        // author meant it to.
                        text = item.number?.let { "$it." } ?: "•",
                        style = HarnessType.body,
                        fontWeight = if (item.number != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item.number != null) palette.textTertiary else palette.accent,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        text = annotated(parseInline(item.text), color, onOpenLink),
                        style = HarnessType.body,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MdBlock.Quote -> Text(
            // The rail is painted rather than laid out: a rule that has to be as
            // tall as its text is a question about intrinsic measurement, and
            // that question has no safe answer once the content can contain
            // anything a lazy layout builds.
            text = annotated(parseInline(block.text), palette.textSecondary, onOpenLink),
            style = HarnessType.body,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = palette.accent.copy(alpha = 0.5f),
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
                .padding(start = 14.dp),
        )

        is MdBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(0.5.dp)
                .background(palette.border),
        )

        is MdBlock.Table -> TableView(block, color, onOpenLink)
    }
}

/**
 * Draw a table.
 *
 * Columns share the width equally rather than sizing to their content: on a
 * phone a content-sized table is usually wider than the screen, and an evenly
 * split one that wraps is more readable than one that scrolls sideways.
 *
 * @param table the table.
 * @param color the colour of ordinary prose.
 * @param onOpenLink what a tapped link does, when there is one.
 */
@Composable
private fun TableView(table: MdBlock.Table, color: Color, onOpenLink: ((String) -> Unit)?) {
    val palette = LocalHarness.current
    HarnessSurface(
        modifier = Modifier.fillMaxWidth(),
        background = palette.surface,
        border = palette.border,
        radius = HarnessRadius.md,
    ) {
        Column {
            Box(Modifier.background(palette.codeBlockBanner)) {
                TableRow(table.header, color, palette, header = true, onOpenLink)
            }
            table.rows.forEach { row ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(palette.border),
                )
                TableRow(row, color, palette, header = false, onOpenLink)
            }
        }
    }
}

/**
 * Draw one table row.
 *
 * @param cells the row's cells.
 * @param color the colour of ordinary prose.
 * @param palette the current palette.
 * @param header whether this is the header row.
 * @param onOpenLink what a tapped link does, when there is one.
 */
@Composable
private fun TableRow(
    cells: List<String>,
    color: Color,
    palette: HarnessPalette,
    header: Boolean,
    onOpenLink: ((String) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        cells.forEach { cell ->
            Text(
                text = annotated(parseInline(cell), if (header) palette.textPrimary else color, onOpenLink),
                style = if (header) {
                    HarnessType.secondary.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    HarnessType.secondary
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        }
    }
}

/**
 * Turn inline runs into styled text.
 *
 * @param runs the parsed runs.
 * @param base the colour of ordinary prose.
 * @param onOpenLink what a tapped link does, when there is one.
 * @return the annotated string.
 */
@Composable
private fun annotated(
    runs: List<MdInline>,
    base: Color,
    onOpenLink: ((String) -> Unit)? = null,
): AnnotatedString {
    val palette = LocalHarness.current
    return remember(runs, base, palette, onOpenLink) {
        buildAnnotatedString { appendRuns(runs, base, palette, onOpenLink) }
    }
}

/**
 * Append one level of inline runs, and recurse into the ones that wrap others.
 *
 * @param runs the runs at this level.
 * @param base the colour of ordinary prose.
 * @param palette the current palette.
 * @param onOpenLink what a tapped link does, when there is one.
 */
private fun AnnotatedString.Builder.appendRuns(
    runs: List<MdInline>,
    base: Color,
    palette: HarnessPalette,
    onOpenLink: ((String) -> Unit)? = null,
) {
    runs.forEach { run ->
        when (run) {
            is MdInline.Text -> withStyle(SpanStyle(color = base)) { append(run.text) }

            is MdInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (palette.isDark) Color(0xFFE6C8FF) else Color(0xFF5B3CC4),
                    background = palette.inlineCode,
                ),
            ) {
                append(run.text)
            }

            is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendRuns(run.children, base, palette, onOpenLink)
            }

            is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendRuns(run.children, base, palette, onOpenLink)
            }

            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendRuns(run.children, base, palette, onOpenLink)
            }

            // A link looks like one and behaves like one — but the tap is
            // answered by a preview the host reads, not by the phone's browser.
            // An address an agent wrote is exactly as trustworthy as the agent,
            // and the host's fetch policy is where that judgement already lives.
            is MdInline.Link -> if (onOpenLink != null) {
                val open = onOpenLink
                withLink(
                    LinkAnnotation.Clickable(
                        tag = run.target,
                        styles = TextLinkStyles(
                            SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline),
                        ),
                    ) { open(run.target) },
                ) {
                    append(run.label)
                }
            } else {
                withStyle(
                    SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline),
                ) {
                    append(run.label)
                }
            }
        }
    }
}
