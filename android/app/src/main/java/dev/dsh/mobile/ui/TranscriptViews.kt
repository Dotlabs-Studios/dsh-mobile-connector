package dev.dsh.mobile.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.DeclaredFile
import dev.dsh.mobile.data.ApprovalPreview
import dev.dsh.mobile.data.DiffKind
import dev.dsh.mobile.data.DiffLine
import dev.dsh.mobile.data.QuestionAnswer
import dev.dsh.mobile.data.QuestionItem
import dev.dsh.mobile.data.QuestionOption
import dev.dsh.mobile.data.TranscriptItem
import dev.dsh.mobile.data.cardSummary
import dev.dsh.mobile.data.diffTotals
import dev.dsh.mobile.data.editPreview
import dev.dsh.mobile.data.lineDiff
import dev.dsh.mobile.data.prettyArguments
import kotlinx.coroutines.delay

/** The brand blue the working indicator is written in. */
private val HarnessPalette.workingText: Color
    get() = if (isDark) Color(0xFF9AAEFF) else Color(0xFF3A55D9)

/** The violet the working indicator's sweep carries, the other end of the brand gradient. */
private val HarnessPalette.workingShimmer: Color
    get() = if (isDark) Color(0xFFD2C6FF) else Color(0xFF8A5CFA)

/**
 * The family one tool belongs to, and what it is called.
 *
 * The icon is here and the words are not: an icon is the same in every language,
 * while the title beside it is a resource id — one for the card that names a
 * single family, one for the plural a group counts in. Keeping them apart is what
 * lets a group of three calls read "3 comandi" in Italian and "3 commands" in
 * English without either language being spelled in this file.
 *
 * @property icon the glyph drawn in the card's badge.
 * @property title the resource naming one of these.
 * @property count the plural resource a group counts in.
 */
private enum class ToolFamily(
    val icon: ImageVector,
    @StringRes val title: Int,
    @PluralsRes val count: Int,
) {
    Command(Icons.Outlined.Terminal, R.string.tool_family_command, R.plurals.tool_count_command),
    Read(Icons.Outlined.Description, R.string.tool_family_read, R.plurals.tool_count_read),
    Write(Icons.AutoMirrored.Outlined.NoteAdd, R.string.tool_family_write, R.plurals.tool_count_write),
    Edit(Icons.Outlined.Edit, R.string.tool_family_edit, R.plurals.tool_count_edit),
    Search(Icons.Outlined.Search, R.string.tool_family_search, R.plurals.tool_count_search),
    Code(Icons.Outlined.Code, R.string.tool_family_code, R.plurals.tool_count_code),
    Web(Icons.Outlined.Public, R.string.tool_family_web, R.plurals.tool_count_web),
    Subagent(Icons.Outlined.AccountTree, R.string.tool_family_subagent, R.plurals.tool_count_subagent),
    Task(Icons.Outlined.Checklist, R.string.tool_family_task, R.plurals.tool_count_task),
    // `present` is not an image tool: what it produces is a file the user is
    // meant to open, and its card is the delivery card below.
    Delivery(Icons.Outlined.FilePresent, R.string.tool_family_delivery, R.plurals.tool_count_delivery),
    Image(Icons.Outlined.Image, R.string.tool_family_image, R.plurals.tool_count_image),
    Other(Icons.Outlined.AutoAwesome, R.string.tool_family_other, R.plurals.tool_count_other),
}

/**
 * The family one tool name belongs to.
 *
 * The harness maps a tool to a family and shows a localized title beside it; a
 * phone that showed only the raw wire name would be unreadable, since one
 * capability arrives under several names (`pwsh`, `bash`, `shell`).
 *
 * @param name the tool name as the harness recorded it.
 * @return the family, whose words come from the resources.
 */
private fun familyOf(name: String): ToolFamily = when (name.lowercase()) {
    "pwsh", "bash", "shell", "cmd", "terminal", "run" -> ToolFamily.Command
    "read", "view", "cat", "open" -> ToolFamily.Read
    "write", "create", "save" -> ToolFamily.Write
    "edit", "str_replace", "str_replace_editor", "patch" -> ToolFamily.Edit
    "grep", "glob", "search", "find" -> ToolFamily.Search
    "run_code", "code" -> ToolFamily.Code
    "web_search", "web_fetch" -> ToolFamily.Web
    "subagent", "workflow", "agent" -> ToolFamily.Subagent
    "todo_write", "todo" -> ToolFamily.Task
    "present" -> ToolFamily.Delivery
    "read_image" -> ToolFamily.Image
    else -> ToolFamily.Other
}

/**
 * One tool invocation, as a compact card.
 *
 * A collapsed card carries the family badge, the family title, and the one
 * argument that identifies the call — the file, the command, the pattern — and
 * expands into an IN/OUT card, or into a diff when the call was a replace-style
 * edit. That structure is what makes a transcript readable: one command and its
 * output belong to one card, not to two rows.
 *
 * @param step the paired call and result.
 * @param token the bearer token, for any image the tool produced.
 * @param imageUrl builds the authorized route for one attachment id.
 * @param onOpenImage invoked with the gallery and the index the user tapped.
 * @param modifier layout for the card.
 * @param nested true inside a group's card, where a second card edge would only
 *   add noise: the step is drawn as a row instead.
 */
@Composable
fun ToolCard(
    step: TranscriptItem.ToolStep,
    token: String?,
    imageUrl: (String) -> String,
    onOpenImage: (List<ImageRef>, Int) -> Unit,
    modifier: Modifier = Modifier,
    nested: Boolean = false,
) {
    val palette = LocalHarness.current
    val family = familyOf(step.name)
    var expanded by remember(step.seq) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "tool-chevron")

    val args = step.arguments
    val preview = remember(args) { editPreview(args) }
    val diff = remember(preview) { preview?.let { lineDiff(it.oldText, it.newText) }.orEmpty() }
    val totals = remember(diff) { diffTotals(diff) }
    // A delivery's arguments *are* the files it names, and the card below lists
    // them: repeating the array here would be the transcript saying the same
    // thing twice, once of them as JSON.
    val summary = remember(step.name, args) { if (step.name == "present") null else cardSummary(step.name, args) }

    // The summary is replaced with the first line of the output once a call has
    // failed: on a failure the reason matters more than the target.
    val errorLine = if (step.isError) step.output.lineSequence().firstOrNull { it.isNotBlank() } else null
    val line = errorLine ?: summary
    val shape = RoundedCornerShape(if (nested) HarnessRadius.sm else HarnessRadius.md)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (nested) {
                    Modifier
                } else {
                    Modifier
                        .background(palette.surface)
                        .border(0.5.dp, palette.borderSubtle, shape)
                },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = if (nested) 6.dp else 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step.running) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    RunningRing(palette.workingText, 13.dp)
                }
            } else {
                IconBadge(
                    icon = family.icon,
                    tint = if (step.isError) palette.error else palette.accent,
                    background = if (step.isError) palette.error.copy(alpha = 0.12f) else palette.accentContainer,
                    size = 26.dp,
                    iconSize = 15.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            val familyTitle = stringResource(family.title)
            if (step.running) {
                ShimmerText(
                    text = familyTitle,
                    style = HarnessType.secondary.copy(fontWeight = FontWeight.SemiBold),
                    base = palette.textPrimary,
                    highlight = palette.accent,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            } else {
                Text(
                    text = familyTitle,
                    style = HarnessType.secondary,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            }
            if (line != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line,
                    style = HarnessType.secondary,
                    color = if (step.isError) palette.error else palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (!totals.isEmpty) {
                Spacer(Modifier.width(8.dp))
                Text(text = "+${totals.added}", style = HarnessType.code, color = palette.success)
                Spacer(Modifier.width(4.dp))
                Text(text = "-${totals.removed}", style = HarnessType.code, color = palette.error)
            }
            step.durationMs?.let { millis ->
                Spacer(Modifier.width(8.dp))
                Text(text = formatDuration(millis), style = HarnessType.caption, color = palette.textCaption)
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.tool_collapse else R.string.tool_expand),
                tint = palette.textTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(turn),
            )
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (nested) 6.dp else 10.dp, end = if (nested) 6.dp else 10.dp, bottom = 10.dp),
            ) {
                if (diff.isNotEmpty()) {
                    DiffView(diff, path = preview?.filePath)
                } else {
                    val sections = buildList {
                        prettyArguments(args)?.let { add(IoSections("IN", it)) }
                        if (step.output.isNotBlank()) add(IoSections("OUT", step.output, isError = step.isError))
                    }
                    if (sections.isNotEmpty()) IoCard(sections)
                }
                if (step.images.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val refs = step.images.mapNotNull { block ->
                        block.attachmentId?.let { id ->
                            ImageRef(
                                url = imageUrl(id),
                                width = block.width,
                                height = block.height,
                                name = block.name,
                            )
                        }
                    }
                    if (refs.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.tool_images_produced),
                            style = HarnessType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textTertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        AttachmentGallery(refs, token, onOpen = { index -> onOpenImage(refs, index) })
                    }
                }
                step.files.forEach { file ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.tool_attachment,
                            file.name ?: stringResource(R.string.tool_attachment_unnamed),
                            file.bytes?.let { stringResource(R.string.tool_attachment_size, humanBytes(it)) }.orEmpty(),
                        ),
                        style = HarnessType.secondary,
                        color = palette.textSecondary,
                    )
                }
            }
        } else if (step.images.isNotEmpty()) {
            // A photo a tool produced *is* its result, not an appendix to it, so
            // it is drawn whether or not the card is open. Tucking it behind the
            // disclosure is how a reader looks straight at a screenshot tool's
            // row and concludes there are no images anywhere.
            val refs = remember(step.seq, step.images) { toolImages(step, imageUrl) }
            if (refs.isNotEmpty()) {
                AttachmentGallery(
                    images = refs,
                    token = token,
                    modifier = Modifier.padding(start = if (nested) 42.dp else 46.dp, end = 10.dp, bottom = 10.dp),
                    tileOnly = true,
                    onOpen = { index -> onOpenImage(refs, index) },
                )
            }
        }
    }
}

/**
 * Resolve a tool's image blocks to authorized routes.
 *
 * @param step the tool card.
 * @param imageUrl builds the authorized route for one attachment id.
 * @return the images, in order.
 */
private fun toolImages(step: TranscriptItem.ToolStep, imageUrl: (String) -> String): List<ImageRef> =
    step.images.mapNotNull { block ->
        block.attachmentId?.let { id ->
            ImageRef(
                url = imageUrl(id),
                width = block.width,
                height = block.height,
                name = block.name,
            )
        }
    }

/**
 * A run of consecutive tool calls, folded into one accordion.
 *
 * The rule for starting open is the harness's: a live, stopped or failed turn's
 * process stays open, and a finished one folds. So the work in progress is
 * visible without a tap, the history is not, and a reader who wants either can
 * say so — their choice sticks for that run.
 *
 * @param group the calls.
 * @param token the bearer token, for any image a tool produced.
 * @param imageUrl builds the authorized route for one attachment id.
 * @param onOpenImage invoked with the gallery and the index the user tapped.
 * @param modifier layout for the group.
 */
@Composable
fun ToolGroupCard(
    group: TranscriptItem.ToolGroup,
    token: String?,
    imageUrl: (String) -> String,
    onOpenImage: (List<ImageRef>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHarness.current
    val pinnedOpen = group.running || group.failed
    var chosen by remember(group.seq) { mutableStateOf<Boolean?>(null) }
    val expanded = chosen ?: pinnedOpen
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "group-chevron")
    val shape = RoundedCornerShape(HarnessRadius.md)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(0.5.dp, if (group.failed) palette.error.copy(alpha = 0.35f) else palette.borderSubtle, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { chosen = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group.running) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    RunningRing(palette.workingText, 13.dp)
                }
            } else {
                IconBadge(
                    icon = Icons.Outlined.Layers,
                    tint = palette.textSecondary,
                    background = palette.surfaceSunken,
                    size = 26.dp,
                    iconSize = 15.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = groupSummary(group.steps),
                style = HarnessType.secondary,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (group.failed) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.tool_steps_failed),
                    tint = palette.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            group.durationMs?.let { millis ->
                Spacer(Modifier.width(8.dp))
                Text(text = formatDuration(millis), style = HarnessType.caption, color = palette.textCaption)
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.tool_collapse else R.string.tool_expand),
                tint = palette.textTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(turn),
            )
        }

        if (expanded) {
            HarnessDivider(subtle = true)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                group.steps.forEach { step ->
                    ToolCard(
                        step = step,
                        token = token,
                        imageUrl = imageUrl,
                        onOpenImage = onOpenImage,
                        nested = true,
                    )
                }
            }
        }
    }
}

/**
 * The files one agent declared with `present`, drawn as a card of their own.
 *
 * This is where a delivery stops being a line in a log and becomes a file the
 * reader can do something with. Each row names the file, keeps the sentence the
 * agent wrote beside it, and shows the declared path — which is how a *relative*
 * declaration stays legible even before the host has resolved it. Tapping the row
 * asks the host what the declaration means; the trailing glyph saves it straight
 * to the phone's Download folder, because that is the one action a reader wants
 * without a second screen.
 *
 * The card is drawn even while the chat is still working: a delivery is durable
 * evidence a file was handed over, and it does not become true only when the turn
 * ends.
 *
 * @param deliverable the item, carrying the declarations.
 * @param busy the delivery action running right now, whoever started it.
 * @param notice what a finished action left beside one of these files.
 * @param onOpen invoked with the file whose row was tapped.
 * @param onSave invoked with the file whose save glyph was tapped.
 * @param modifier layout for the card.
 */
@Composable
fun DeliverableCard(
    deliverable: TranscriptItem.Deliverable,
    busy: DeliveryBusy?,
    notice: (Int) -> DeliveryNotice?,
    onOpen: (DeclaredFile) -> Unit,
    onSave: (DeclaredFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHarness.current
    val shape = RoundedCornerShape(HarnessRadius.md)
    val files = deliverable.files

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(0.5.dp, palette.accent.copy(alpha = 0.30f), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.FilePresent,
                tint = palette.accent,
                background = palette.accentContainer,
                size = 26.dp,
                iconSize = 15.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (files.size == 1) R.string.deliverable_title_one else R.string.deliverable_title_many,
                ),
                style = HarnessType.secondary.copy(fontWeight = FontWeight.SemiBold),
                color = palette.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // Only worth a count when there is more than one: "1 artefatto" beside
            // a single row is a number the reader can already see.
            if (files.size > 1) {
                Text(text = "${files.size}", style = HarnessType.caption, color = palette.textCaption)
            }
        }

        files.forEachIndexed { position, file ->
            if (position > 0) HarnessDivider(subtle = true)
            DeliverableRow(
                file = file,
                busy = busy,
                notice = notice(file.index),
                onOpen = { onOpen(file) },
                onSave = { onSave(file) },
            )
        }
    }
}

/**
 * One declared file inside a delivery card.
 *
 * @param file the declaration.
 * @param busy the running action, when it is running on this file.
 * @param notice what the last action on this file did, if anything.
 * @param onOpen what a tap on the row does.
 * @param onSave what a tap on the save glyph does.
 */
@Composable
private fun DeliverableRow(
    file: DeclaredFile,
    busy: DeliveryBusy?,
    notice: DeliveryNotice?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val palette = LocalHarness.current
    val working = busy != null && busy.index == file.index

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !working, onClick = onOpen)
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = HarnessType.secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                file.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = HarnessType.caption,
                        color = palette.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = file.path,
                    style = HarnessType.codeBlock,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (working) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    RunningRing(palette.workingText, 14.dp)
                }
            } else {
                HarnessIconTarget(
                    icon = Icons.Outlined.FileDownload,
                    description = stringResource(R.string.deliverable_save, file.name),
                    tint = palette.accent,
                    onClick = onSave,
                )
            }
        }
        if (notice != null && !working) {
            Text(
                text = notice.text,
                style = HarnessType.caption,
                color = if (notice.ok) palette.success else palette.warningText,
                modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * One line describing what a group of calls did.
 *
 * "3 comandi" answers the reader's question — did it read, change or run
 * something — where "3 passi" only says how many rows are hidden. The wording of
 * each family comes from the resources, so the sentence is built the way the
 * language in force builds sentences: the parts are joined by a resource too,
 * because a list's separator is a word ("e", "and") and not a glyph.
 *
 * @param steps the calls in the group.
 * @return the summary.
 */
@Composable
private fun groupSummary(steps: List<TranscriptItem.ToolStep>): String {
    val counts = LinkedHashMap<ToolFamily, Int>()
    steps.forEach { step ->
        val family = familyOf(step.name)
        counts[family] = (counts[family] ?: 0) + 1
    }
    val parts = counts.map { (family, count) ->
        if (count == 1) stringResource(family.title).lowercase() else countedFamily(family, count)
    }
    val joined = when (parts.size) {
        0 -> stringResource(R.string.tool_group_steps)
        1 -> parts.single()
        else -> stringResource(
            R.string.tool_group_and,
            parts.dropLast(1).joinToString(", "),
            parts.last(),
        )
    }
    return joined.replaceFirstChar { it.uppercase() }
}

/**
 * One family with a count in front of it, in the plural the language asks for.
 *
 * `pluralStringResource` cannot be called from inside a `map`, since the lambda
 * it would run in is not composable — so the plural is resolved here, once per
 * family, where it can be.
 *
 * @param family the family to count.
 * @param count how many of them.
 * @return the counted label.
 */
@Composable
private fun countedFamily(family: ToolFamily, count: Int): String =
    pluralStringResource(family.count, count, count)

/**
 * Draw a unified diff the way the harness draws it.
 *
 * One column, `+`/`-` prefixes, a coloured marker bar down the left of each
 * changed line, and a `⋯` where lines were folded away.
 *
 * @param lines the diff to draw.
 * @param path the file the diff belongs to, shown as a header when known.
 * @param modifier layout for the block.
 */
@Composable
fun DiffView(lines: List<DiffLine>, path: String?, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    HarnessSurface(modifier = modifier.fillMaxWidth(), background = palette.codeBlock, radius = HarnessRadius.md) {
        Column(Modifier.padding(bottom = 6.dp)) {
            path?.let {
                Text(
                    text = it,
                    style = HarnessType.codeBlock,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.codeBlockBanner)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            lines.forEach { line ->
                val background = when (line.kind) {
                    DiffKind.Added -> palette.diffAdded
                    DiffKind.Removed -> palette.diffRemoved
                    else -> Color.Transparent
                }
                val marker = when (line.kind) {
                    DiffKind.Added -> palette.success
                    DiffKind.Removed -> palette.error
                    else -> Color.Transparent
                }
                val textColor = when (line.kind) {
                    DiffKind.Added -> palette.success
                    DiffKind.Removed -> palette.error
                    DiffKind.Gap -> palette.textTertiary
                    DiffKind.Context -> palette.textSecondary
                }
                val prefix = when (line.kind) {
                    DiffKind.Added -> "+ "
                    DiffKind.Removed -> "- "
                    else -> "  "
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(background),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(19.dp)
                            .background(marker),
                    )
                    Text(
                        text = if (line.kind == DiffKind.Gap) "⋯" else prefix + line.text,
                        style = HarnessType.codeBlock,
                        color = textColor,
                        softWrap = true,
                        modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 1.dp, bottom = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * The harness's permission card: what the agent wants to run, and the two
 * answers the harness accepts.
 *
 * The vocabulary is deliberately two buttons wide. `allowed-once` is the only
 * grant the harness has, so there is no "always allow" here — inventing one
 * would promise a persistence the harness does not implement.
 *
 * @param toolName the tool asking.
 * @param prompt the host's own sentence, when it wrote one.
 * @param outcome the verdict already recorded, or null while the request is open.
 * @param answerable whether the host still holds the request, so it can be answered here.
 * @param busy whether this app is answering it right now.
 * @param preview the command or file the call is about, when the transcript holds it.
 * @param onAllow grants one execution.
 * @param onReject refuses it.
 * @param modifier layout for the card.
 */
@Composable
fun ApprovalCard(
    toolName: String,
    prompt: String,
    outcome: String?,
    answerable: Boolean,
    busy: Boolean,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    preview: ApprovalPreview? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHarness.current
    val settledGrant = outcome == "allowed-once"
    val waiting = outcome == null && answerable
    HarnessSurface(
        modifier = modifier.fillMaxWidth(),
        background = palette.surface,
        border = when {
            // The warning edge is a call to act, so it is shown only while the
            // request really can be answered here.
            waiting -> palette.warning.copy(alpha = 0.55f)
            settledGrant -> palette.success.copy(alpha = 0.4f)
            else -> palette.border
        },
        radius = HarnessRadius.lg,
        elevation = if (waiting) 4.dp else 0.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Outlined.Lock,
                    tint = if (outcome == null) palette.warningText else palette.textTertiary,
                    background = if (outcome == null) palette.warning.copy(alpha = 0.16f) else palette.surfaceSunken,
                    size = 32.dp,
                    iconSize = 17.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.approval_asking, toolName),
                    style = HarnessType.secondary,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (prompt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(text = prompt, style = HarnessType.secondary, color = palette.textSecondary)
            }
            // What is actually being approved: the command about to run, or the
            // file and text about to be written. Without it the two buttons ask a
            // question the card has not stated.
            if (preview != null) {
                preview.command?.let { command ->
                    Spacer(Modifier.height(10.dp))
                    ApprovalCode(text = command, palette = palette)
                }
                preview.path?.let { path ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = path,
                        style = HarnessType.codeBlock.copy(fontSize = HarnessType.caption.fontSize),
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview.body?.let { body ->
                        Spacer(Modifier.height(6.dp))
                        ApprovalCode(text = body, palette = palette)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                waiting -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HarnessButton(
                        label = if (busy) stringResource(R.string.approval_sending) else stringResource(R.string.approval_allow_once),
                        enabled = !busy,
                        onClick = onAllow,
                        modifier = Modifier.weight(1f),
                    )
                    HarnessOutlineButton(
                        label = stringResource(R.string.approval_reject),
                        enabled = !busy,
                        onClick = onReject,
                        modifier = Modifier.heightIn(min = 42.dp),
                    )
                }

                outcome == null -> Text(
                    // The host is no longer holding it: the turn ended, or the
                    // harness restarted. Saying so beats showing buttons that
                    // would answer nothing.
                    text = stringResource(R.string.approval_expired),
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                )

                settledGrant -> StatusPill(
                    text = stringResource(R.string.approval_granted_once),
                    color = palette.success,
                    icon = Icons.Rounded.Check,
                )

                else -> {
                    // The host has settled it, so all that is left is the
                    // verdict: `rejected` is the harness's own word for a
                    // refusal, and any other code is one this app cannot spell
                    // out, so it is shown as it came. The absent outcome was
                    // taken by the two branches above.
                    val verdict = outcome.orEmpty()
                    StatusPill(
                        text = if (verdict == "rejected") {
                            stringResource(R.string.approval_rejected)
                        } else {
                            stringResource(R.string.approval_not_granted, verdict)
                        },
                        color = palette.error,
                        icon = Icons.Rounded.Close,
                    )
                }
            }
        }
    }
}

/**
 * One monospaced block inside an approval card.
 *
 * Capped in lines rather than cut in text: a command or a file body can be longer
 * than the card, and the reader needs the beginning — the part that says what will
 * happen — without the transcript turning into a diff viewer.
 *
 * @param text the command or the text about to be written.
 * @param palette the active palette.
 */
@Composable
private fun ApprovalCode(text: String, palette: HarnessPalette) {
    HarnessSurface(
        modifier = Modifier.fillMaxWidth(),
        background = palette.surfaceSunken,
        border = palette.borderSubtle,
        radius = HarnessRadius.md,
    ) {
        Text(
            text = text,
            style = HarnessType.codeBlock,
            color = palette.textPrimary,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/**
 * A settled verdict, as a small tinted pill.
 *
 * @param text the verdict.
 * @param color its state colour.
 * @param icon its glyph.
 */
@Composable
private fun StatusPill(text: String, color: Color, icon: ImageVector) {
    Row(
        Modifier
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(color.copy(alpha = 0.12f))
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text = text, style = HarnessType.caption, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/**
 * The card for a question an agent asked, with its choices as real buttons.
 *
 * An `ask_user_question` call arrived as a generic tool row whose summary was the
 * names of its own arguments — `(question, options)` — so the choices the agent
 * was offering were invisible and unpressable. They are the point of the call:
 * the agent stopped and asked precisely because it needs one of them.
 *
 * The answer is keyed by each question's id and carries the option **label**,
 * which is what the harness matched on. An index would answer a different
 * question the moment a model reordered its list.
 *
 * @param questions the questions in the set.
 * @param answered the labels already chosen, per question id; empty while asking.
 * @param answerable whether the host still holds the question, so it can be
 *   answered here at all.
 * @param expired whether the harness's own window has closed, so the answer will
 *   arrive as a follow-up rather than as the reply to the tool call.
 * @param busy whether this app is sending the answer right now.
 * @param onAnswer invoked with one entry per question when the reader submits.
 * @param modifier layout for the card.
 */
@Composable
fun QuestionCard(
    questions: List<QuestionItem>,
    answered: Map<String, List<String>>,
    answerable: Boolean,
    expired: Boolean,
    busy: Boolean,
    onAnswer: (List<QuestionAnswer>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (questions.isEmpty()) return
    val palette = LocalHarness.current
    val settled = answered.isNotEmpty()
    // A single question with a single answer needs no second step: the tap *is*
    // the answer, which is what a reader expects from a list of choices.
    val immediate = answerable && questions.size == 1 && !questions.single().multiSelect
    var chosen by remember(questions, answered) { mutableStateOf(answered) }

    /** Record one pick, and submit it at once when one tap is the whole answer. */
    fun pick(question: QuestionItem, label: String) {
        val current = chosen[question.id].orEmpty()
        val next = when {
            question.multiSelect -> if (label in current) current - label else current + label
            else -> listOf(label)
        }
        chosen = chosen + (question.id to next)
        if (immediate && !busy) {
            onAnswer(listOf(QuestionAnswer(id = question.id, selected = listOf(label))))
        }
    }

    HarnessSurface(
        modifier = modifier.fillMaxWidth(),
        background = palette.surface,
        border = when {
            settled -> palette.success.copy(alpha = 0.35f)
            answerable -> palette.accent.copy(alpha = 0.5f)
            else -> palette.border
        },
        radius = HarnessRadius.lg,
        elevation = if (answerable && !settled) 4.dp else 0.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tone = when {
                    settled -> palette.success
                    answerable -> palette.accent
                    else -> palette.textTertiary
                }
                IconBadge(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    tint = tone,
                    background = if (answerable || settled) tone.copy(alpha = 0.14f) else palette.surfaceSunken,
                    size = 32.dp,
                    iconSize = 17.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        settled -> stringResource(R.string.question_card_settled)
                        answerable -> stringResource(R.string.question_card_asking)
                        else -> stringResource(R.string.question_card_plain)
                    },
                    style = HarnessType.secondary,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (expired && !settled && answerable) {
                    Text(text = stringResource(R.string.question_late), style = HarnessType.caption, fontWeight = FontWeight.SemiBold, color = palette.warningText)
                }
            }

            questions.forEachIndexed { index, question ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(10.dp))
                question.title?.let { title ->
                    Text(
                        text = title.uppercase(),
                        style = HarnessType.overline,
                        color = palette.textTertiary,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = question.question,
                    style = HarnessType.body,
                    color = palette.textPrimary,
                )
                question.detail?.let { detail ->
                    Spacer(Modifier.height(2.dp))
                    Text(text = detail, style = HarnessType.secondary, color = palette.textSecondary)
                }
                if (question.multiSelect) {
                    Text(
                        text = stringResource(R.string.question_multi),
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                    )
                }
                Spacer(Modifier.height(8.dp))

                val pickedHere = chosen[question.id].orEmpty()
                question.options.forEach { option ->
                    ChoiceRow(
                        option = option,
                        selected = option.label in pickedHere,
                        // Once answered, the choices are a record rather than a
                        // control: leaving them tappable would invite a second
                        // answer the harness will refuse.
                        enabled = !settled && answerable && !busy,
                        onClick = { pick(question, option.label) },
                    )
                }
                if (question.options.isEmpty() && !settled && answerable) {
                    Text(
                        text = stringResource(R.string.question_no_options),
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                    )
                }
            }

            if (settled) {
                Spacer(Modifier.height(10.dp))
                StatusPill(
                    // The labels are joined with a separator rather than a word:
                    // a list of answers reads the same in every language.
                    text = stringResource(
                        R.string.question_answer_prefix,
                        answered.values.flatten().joinToString(" · "),
                    ),
                    color = palette.success,
                    icon = Icons.Rounded.Check,
                )
            } else if (answerable && !immediate) {
                Spacer(Modifier.height(12.dp))
                HarnessButton(
                    label = if (busy) stringResource(R.string.question_sending) else stringResource(R.string.question_send_choice),
                    enabled = !busy && chosen.values.any { it.isNotEmpty() },
                    onClick = {
                        onAnswer(
                            questions.map { question ->
                                QuestionAnswer(id = question.id, selected = chosen[question.id].orEmpty())
                            },
                        )
                    },
                )
            } else if (!answerable) {
                // The exchange this belonged to is over, so the options are shown
                // as the record of what was offered and nothing pretends to be
                // pressable.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.question_not_waiting),
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                )
            }

            if (expired && !settled && answerable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.question_moved_on),
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                )
            }
        }
    }
}

/**
 * One choice, as a row the reader can press.
 *
 * @param option the choice.
 * @param selected whether it is currently picked.
 * @param enabled whether it can still be picked.
 * @param onClick invoked when it is pressed.
 */
@Composable
private fun ChoiceRow(
    option: QuestionOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalHarness.current
    val shape = RoundedCornerShape(HarnessRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(palette.accentContainer)
                        .border(1.dp, palette.accent.copy(alpha = 0.6f), shape)
                } else {
                    Modifier.border(0.5.dp, palette.border, shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) {
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, palette.textCaption, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.cleanLabel,
                    style = HarnessType.body,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (enabled || selected) palette.textPrimary else palette.textSecondary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.recommended) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HarnessRadius.pill))
                            .background(palette.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.question_recommended),
                            style = HarnessType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accent,
                        )
                    }
                }
            }
            option.description?.let { description ->
                Spacer(Modifier.height(2.dp))
                Text(text = description, style = HarnessType.secondary, color = palette.textTertiary)
            }
        }
    }
}

/**
 * The working indicator: a pill with an animated ring and a shimmering label.
 *
 * @param startedAt when the turn began, or null when the host did not say.
 * @param modifier layout for the indicator.
 */
@Composable
fun WorkingIndicator(startedAt: Long?, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    // The elapsed seconds tick on their own: this row is the only signal that
    // the agent is still moving, and a frozen number reads as a stalled one.
    val now by produceState(initialValue = System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val elapsed = startedAt?.let { ((now - it) / 1000L).coerceAtLeast(0L) }
    val label = if (elapsed == null) {
        stringResource(R.string.chat_working)
    } else {
        stringResource(R.string.chat_working_since, elapsed)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(palette.accentContainer)
            .padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RunningRing(palette.workingText, 14.dp)
        Spacer(Modifier.width(8.dp))
        ShimmerText(
            text = label,
            style = HarnessType.secondary.copy(fontWeight = FontWeight.SemiBold),
            base = palette.workingText,
            highlight = palette.workingShimmer,
        )
    }
}

/**
 * A dismissible failure banner shared by every screen.
 *
 * @param message the failure text.
 * @param onDismiss invoked when the user closes it.
 * @param actionLabel optional button label for a recovery action.
 * @param onAction invoked when that action is chosen.
 */
@Composable
fun ProblemBanner(
    message: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalHarness.current
    HarnessSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        background = palette.error.copy(alpha = if (palette.isDark) 0.14f else 0.08f),
        border = palette.error.copy(alpha = 0.3f),
        radius = HarnessRadius.md,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = palette.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = HarnessType.secondary,
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = palette.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onDismiss) { Text("OK", color = palette.textSecondary) }
        }
    }
}

/**
 * Render a byte count the way the harness labels a file.
 *
 * @param bytes the size.
 * @return a short human label.
 */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} kB"
    else -> "$bytes B"
}

/**
 * Render a millisecond duration the way the harness labels a finished step.
 *
 * @param millis the duration.
 * @return a short label.
 */
fun formatDuration(millis: Long): String = when {
    millis >= 60_000L -> "${millis / 60_000L}m ${(millis % 60_000L) / 1000L}s"
    millis >= 1000L -> "${millis / 1000L}.${(millis % 1000L) / 100}s"
    else -> "${millis}ms"
}
