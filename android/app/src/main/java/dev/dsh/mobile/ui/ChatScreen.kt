package dev.dsh.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.Block
import dev.dsh.mobile.data.Entry
import dev.dsh.mobile.data.OutgoingFile
import dev.dsh.mobile.data.OutgoingImage
import dev.dsh.mobile.data.PermissionOption
import dev.dsh.mobile.data.TranscriptItem
import dev.dsh.mobile.data.attachmentName
import dev.dsh.mobile.data.buildTranscript
import dev.dsh.mobile.data.parseQuestionAnswers
import dev.dsh.mobile.data.parseQuestions
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The conversation screen: transcript plus composer.
 *
 * The layout is a modern AI chat's: the operator's own words sit in a soft
 * bubble on the right, while an assistant reply is text on the canvas at full
 * width — a reply is a document to read, not a message to glance at. Tool calls
 * are compact cards. The composer floats at the bottom as one rounded surface
 * with its controls inside it.
 *
 * @param viewModel the app state holder, already holding an open session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel) {
    // Polling is owned by [AppViewModel.onForeground]: the list and the chat loops
    // share one visibility flag, because a per-screen stop-effect only ever paused
    // the screen that happened to be composed.

    val palette = LocalHarness.current
    val context = LocalContext.current
    val session = viewModel.openSession
    val sessionId = session?.id
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lightbox by remember { mutableStateOf<ImageRef?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.stagePhotoFrom(context, it) }
    }

    // Any file, several at a time: the picker is the system's, and the app has
    // no business narrowing what a chat can be given. Each one is uploaded as
    // it arrives, so a large file starts travelling while the next is chosen.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> viewModel.stageFileFrom(context, uri) }
    }

    val items = remember(viewModel.entries) { buildTranscript(viewModel.entries) }
    val liveApprovals = viewModel.approvalsFor(sessionId)
    // An approval whose log entry has not arrived yet — the newest page may
    // predate it — still has to be answerable, so anything the host is holding
    // but the transcript does not show gets its own block.
    val shownCallIds = items.filterIsInstance<TranscriptItem.Approval>().mapNotNull { it.callId }.toSet()
    val unshownApprovals = liveApprovals.filter { it.callId == null || it.callId !in shownCallIds }

    // The same for questions, except that their card is drawn from the tool call
    // itself: an `ask_user_question` call carries the questions and the choices,
    // so only the *live* handle has to come from the host.
    val liveQuestions = viewModel.questionsFor(sessionId)
    val askedCallIds = items.filterIsInstance<TranscriptItem.ToolStep>()
        .filter { it.name == "ask_user_question" }
        .mapNotNull { it.callId }
        .toSet()
    val unshownQuestions = liveQuestions.filter { it.callId == null || it.callId !in askedCallIds }

    /**
     * Whether the transcript is pinned to its newest entry.
     *
     * It starts pinned, and only the reader can unpin it. Deriving this from the
     * scroll position instead is wrong in a way that shows up as a bug: an
     * appended message also moves the bottom away, so the view would conclude the
     * reader had scrolled off and stop following — at the one moment following
     * matters.
     */
    var followTail by remember(sessionId) { mutableStateOf(true) }

    // A drag is the reader taking control. A programmatic scroll produces no drag
    // interaction, which is exactly the distinction needed: "the view moved" is
    // not "the reader moved it", and only the second should unpin.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) followTail = false
    }

    // Coming to rest at the bottom is a request to follow again; coming to rest
    // anywhere else leaves the pin where the drag put it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            // The first emission is the initial state, not a scroll; reacting to
            // it would unpin the view on open, exactly when it should be pinned.
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect { if (!listState.canScrollForward) followTail = true }
    }

    // A chat opens at its end. The newest message is the one being answered, and
    // landing on the first message of a long history is not a view of the
    // conversation — it is a view of its archive, thousands of entries away from
    // anything that matters.
    LaunchedEffect(sessionId, items.isNotEmpty()) {
        if (items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex)
            followTail = true
        }
    }

    // While pinned, keep the newest message at the bottom. Items grow after their
    // first measurement — a decoded image, a wrapped code block, a table — so one
    // scroll at open lands short of an end that has since moved. Every growth
    // re-arms `canScrollForward`, and that is the signal to close the gap.
    LaunchedEffect(listState, followTail, items.size) {
        if (!followTail) return@LaunchedEffect
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                if (canScrollForward && followTail && items.isNotEmpty()) {
                    listState.animateScrollToItem(items.lastIndex)
                }
            }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            ChatTopBar(
                title = session?.displayTitle ?: stringResource(R.string.chat_title),
                // Where this chat actually runs on the PC: the one fact that
                // decides which project it belongs to.
                subtitle = session?.cwd ?: stringResource(
                    if (viewModel.running) R.string.state_running else R.string.state_idle,
                ),
                running = viewModel.running,
                onBack = { viewModel.closeSession() },
                onNewElsewhere = { viewModel.openPicker() },
            )
        },
        bottomBar = {
            Composer(
                draft = viewModel.draft,
                onDraftChange = { viewModel.draft = it },
                staged = viewModel.stagedPhotos,
                onUnstage = { index -> viewModel.unstagePhoto(index) },
                onPickPhoto = { picker.launch("image/*") },
                stagedFiles = viewModel.stagedFiles,
                uploadingFiles = viewModel.uploadingFiles,
                onUnstageFile = { index -> viewModel.unstageFile(index) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                running = viewModel.running,
                sending = viewModel.sending,
                modelLabel = viewModel.currentModel?.model,
                onPickModel = { viewModel.openModelPicker() },
                permission = viewModel.permissionFor(sessionId),
                permissionBusy = viewModel.switchingPermission,
                onPickPermission = { viewModel.openPermissions() },
                onSend = { steer ->
                    // Photos ride along inline so the harness admits them as
                    // real image content for the model. The draft is cleared by
                    // the view model once the host has accepted the message.
                    viewModel.sendStaged(steer)
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            viewModel.problem?.let { problem ->
                ProblemBanner(message = textResource(problem.message), onDismiss = { viewModel.dismissProblem() })
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    viewModel.entriesLoading && viewModel.entries.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.accent, strokeWidth = 3.dp)
                        }
                    }

                    items.isEmpty() && unshownApprovals.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(R.string.empty_messages),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (viewModel.hasOlder) {
                                item(key = "load-older") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (viewModel.loadingOlder) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent)
                                        } else {
                                            HarnessOutlineButton(
                                                label = stringResource(R.string.chat_load_older),
                                                tint = palette.accent,
                                                onClick = { viewModel.loadOlder() },
                                            )
                                        }
                                    }
                                }
                            }
                            items(items, key = { "${it::class.simpleName}-${it.seq}" }) { item ->
                                when (item) {
                                    is TranscriptItem.Message -> MessageItem(
                                        entry = item.entry,
                                        sessionId = sessionId,
                                        viewModel = viewModel,
                                        onOpenImage = { refs, index -> lightbox = refs.getOrNull(index) },
                                    )

                                    is TranscriptItem.ToolStep -> {
                                        // An `ask_user_question` call is a question,
                                        // not a tool invocation to disclose: its
                                        // arguments *are* the choices, and burying
                                        // them in a collapsed row hides the one
                                        // thing the agent stopped to ask for.
                                        val asked = if (item.name == "ask_user_question") {
                                            remember(item.seq, item.arguments) { parseQuestions(item.arguments) }
                                        } else {
                                            emptyList()
                                        }
                                        if (asked.isEmpty()) {
                                            ToolCard(
                                                step = item,
                                                token = viewModel.bearerToken(),
                                                imageUrl = { id -> sessionId?.let { viewModel.imageUrl(it, id) }.orEmpty() },
                                                onOpenImage = { refs, index -> lightbox = refs.getOrNull(index) },
                                            )
                                        } else {
                                            val live = liveQuestions.firstOrNull { pending ->
                                                pending.callId != null && pending.callId == item.callId
                                            }
                                            QuestionCard(
                                                questions = asked,
                                                answered = viewModel.localAnswer(item.callId, live?.id)
                                                    ?: remember(item.seq, item.output) { parseQuestionAnswers(item.output) },
                                                answerable = live != null,
                                                expired = live?.expired == true,
                                                busy = live != null && viewModel.answeringQuestion == live.id,
                                                onAnswer = { answers -> live?.let { viewModel.answerQuestion(it, answers) } },
                                            )
                                        }
                                    }

                                    is TranscriptItem.Approval -> {
                                        val live = liveApprovals.firstOrNull { candidate ->
                                            (candidate.callId != null && candidate.callId == item.callId) ||
                                                candidate.id == item.approvalId
                                        }
                                        ApprovalCard(
                                            toolName = item.toolName,
                                            prompt = live?.prompt ?: item.asked.reason.orEmpty(),
                                            // A verdict the host has recorded wins;
                                            // otherwise the one this app just sent
                                            // stands in until the log catches up.
                                            outcome = if (live != null) {
                                                null
                                            } else {
                                                item.outcome ?: viewModel.localVerdict(item.callId, item.approvalId)
                                            },
                                            answerable = live != null,
                                            busy = live != null && viewModel.answeringApproval == live.id,
                                            onAllow = { live?.let { viewModel.answerApproval(it, "allowed-once") } },
                                            onReject = { live?.let { viewModel.answerApproval(it, "rejected") } },
                                        )
                                    }

                                    is TranscriptItem.ToolGroup -> ToolGroupCard(
                                        group = item,
                                        token = viewModel.bearerToken(),
                                        imageUrl = { id -> sessionId?.let { viewModel.imageUrl(it, id) }.orEmpty() },
                                        onOpenImage = { refs, index -> lightbox = refs.getOrNull(index) },
                                    )

                                    is TranscriptItem.Deliverable -> DeliverableCard(
                                        deliverable = item,
                                        busy = viewModel.deliveryBusy,
                                        notice = { index ->
                                            sessionId?.let { viewModel.deliveryNotices[deliveryKey(it, item.seq, index)] }
                                        },
                                        onOpen = { file -> viewModel.openDelivery(sessionId, item.seq, file) },
                                        onSave = { file ->
                                            sessionId?.let { chat ->
                                                viewModel.saveDelivery(context, chat, item.seq, file.index, file.path)
                                            }
                                        },
                                    )

                                    is TranscriptItem.Notice -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = item.entry.reason ?: stringResource(R.string.chat_notice_event),
                                            style = HarnessType.caption,
                                            color = palette.textTertiary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(HarnessRadius.pill))
                                                .background(palette.surfaceSunken)
                                                .padding(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            if (unshownApprovals.isNotEmpty()) {
                                item(key = "approvals") {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        unshownApprovals.forEach { approval ->
                                            ApprovalCard(
                                                toolName = approval.toolName,
                                                prompt = approval.prompt,
                                                outcome = null,
                                                answerable = true,
                                                busy = viewModel.answeringApproval == approval.id,
                                                onAllow = { viewModel.answerApproval(approval, "allowed-once") },
                                                onReject = { viewModel.answerApproval(approval, "rejected") },
                                            )
                                        }
                                    }
                                }
                            }
                            if (unshownQuestions.isNotEmpty()) {
                                item(key = "questions") {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        unshownQuestions.forEach { pending ->
                                            QuestionCard(
                                                questions = pending.questions,
                                                answered = viewModel.localAnswer(pending.callId, pending.id).orEmpty(),
                                                answerable = true,
                                                expired = pending.expired,
                                                busy = viewModel.answeringQuestion == pending.id,
                                                onAnswer = { answers -> viewModel.answerQuestion(pending, answers) },
                                            )
                                        }
                                    }
                                }
                            }
                            if (viewModel.running) {
                                item(key = "working") {
                                    WorkingIndicator(
                                        startedAt = viewModel.turnStartedAt,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Shown only while the view is unpinned: at the bottom the
                // button would cover the newest message to offer a trip to where
                // the reader already is.
                if (!followTail && items.isNotEmpty()) {
                    ScrollToBottomButton(
                        onClick = {
                            followTail = true
                            scope.launch { listState.animateScrollToItem(items.lastIndex) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    if (viewModel.modelPickerOpen) ModelPicker(viewModel)
    if (viewModel.permissionsOpen) PermissionPicker(viewModel)
    viewModel.previewUrl?.let { LinkPreviewSheet(viewModel) }
    viewModel.fileRef?.let { FileSheet(viewModel) }
    viewModel.deliveryRef?.let { DeliverySheet(viewModel) }

    lightbox?.let { image ->
        ImageLightbox(image = image, token = viewModel.bearerToken(), onDismiss = { lightbox = null })
    }
}

/**
 * The chat's top bar: back, the chat's name and where it runs, and a way to start
 * a chat somewhere else.
 *
 * The model is not repeated here: it lives in the composer, next to the access
 * preset, where it is chosen.
 *
 * @param title the chat's name.
 * @param subtitle the folder it runs in on the PC.
 * @param running whether a turn is in flight, which the subtitle's mark shows.
 * @param onBack returns to the chat list.
 * @param onNewElsewhere opens the new-chat picker.
 */
@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    running: Boolean,
    onBack: () -> Unit,
    onNewElsewhere: () -> Unit,
) {
    val palette = LocalHarness.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HarnessIconTarget(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                description = stringResource(R.string.chat_back),
                tint = palette.textPrimary,
                size = 40.dp,
                iconSize = 22.dp,
                onClick = onBack,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        RunningRing(palette.warning, 9.dp)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = subtitle,
                        style = HarnessType.codeBlock.copy(fontSize = HarnessType.caption.fontSize, lineHeight = HarnessType.caption.lineHeight),
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            HarnessIconTarget(
                icon = Icons.Outlined.FolderOpen,
                description = stringResource(R.string.chat_new_elsewhere),
                background = palette.surface,
                bordered = true,
                size = 40.dp,
                onClick = onNewElsewhere,
            )
        }
        HarnessDivider(subtle = true)
    }
}

/**
 * One transcript message.
 *
 * @param entry the message.
 * @param sessionId the authorized session, needed to build image URLs.
 * @param viewModel the app state holder, for the bearer token.
 * @param onOpenImage invoked with the gallery and the index the user tapped.
 */
@Composable
private fun MessageItem(
    entry: Entry,
    sessionId: String?,
    viewModel: AppViewModel,
    onOpenImage: (List<ImageRef>, Int) -> Unit,
) {
    val palette = LocalHarness.current
    val isUser = entry.role == "user"
    val text = entry.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
    val reasoning = entry.content.filter { it.type == "reasoning" }.mapNotNull { it.text }.joinToString("\n")
    val images = imageRefs(entry.content, sessionId, viewModel)
    // A file block is a handle the model was told about, and the reader gets the
    // same one line: the name the store kept and the bytes it stored.
    val files = entry.content.filter { it.type == "file" }

    if (isUser) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .clip(UserBubbleShape)
                    .background(palette.userBubble)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (text.isNotBlank()) {
                    MarkdownBody(text, palette.textPrimary, onOpenLink = { viewModel.openLink(it) }, fill = false)
                }
                if (images.isNotEmpty()) {
                    AttachmentGallery(images, viewModel.bearerToken(), alignEnd = true, onOpen = { index ->
                        onOpenImage(images, index)
                    })
                }
                files.forEach { block ->
                    FileChip(attachmentName(block.name), block.bytes?.let { humanBytes(it) })
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reasoning.isNotBlank()) ReasoningRow(reasoning)
        if (text.isNotBlank()) MarkdownBody(text, palette.textPrimary, onOpenLink = { viewModel.openLink(it) })
        if (images.isNotEmpty()) {
            AttachmentGallery(images, viewModel.bearerToken(), onOpen = { index -> onOpenImage(images, index) })
        }
        files.forEach { block ->
            FileChip(attachmentName(block.name), block.bytes?.let { humanBytes(it) })
        }
        if (entry.interrupted == true) {
            Text(
                text = stringResource(R.string.chat_interrupted),
                style = HarnessType.caption,
                fontWeight = FontWeight.SemiBold,
                color = palette.warningText,
                modifier = Modifier
                    .clip(RoundedCornerShape(HarnessRadius.pill))
                    .background(palette.warning.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** The user bubble: round everywhere but the corner that points at the composer. */
private val UserBubbleShape = RoundedCornerShape(
    topStart = HarnessRadius.xl,
    topEnd = HarnessRadius.xl,
    bottomEnd = HarnessRadius.xs,
    bottomStart = HarnessRadius.xl,
)

/**
 * A file a message carried, as a small labelled pill.
 *
 * @param name the name the store kept.
 * @param size the stored size, when known.
 */
@Composable
private fun FileChip(name: String, size: String?) {
    val palette = LocalHarness.current
    Row(
        Modifier
            .clip(RoundedCornerShape(HarnessRadius.md))
            .background(if (palette.isDark) palette.surfaceElevated else palette.surface)
            .border(0.5.dp, palette.border, RoundedCornerShape(HarnessRadius.md))
            .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = Icons.Outlined.Description,
            tint = palette.accent,
            background = palette.accentContainer,
            size = 28.dp,
            iconSize = 16.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = name,
                style = HarnessType.secondary,
                fontWeight = FontWeight.Medium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            size?.let { Text(text = it, style = HarnessType.caption, color = palette.textTertiary) }
        }
    }
}

/**
 * The harness's reasoning row: a collapsed line that opens the model's thinking.
 *
 * The desktop shows one row and keeps the text behind it, so a long chain of
 * thought does not push the answer off the screen.
 *
 * @param text the reasoning text.
 */
@Composable
private fun ReasoningRow(text: String) {
    val palette = LocalHarness.current
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "reasoning-chevron")
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(HarnessRadius.pill))
                .background(palette.controlFill)
                .clickable { open = !open }
                .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_reasoning),
                style = HarnessType.caption,
                fontWeight = FontWeight.SemiBold,
                color = palette.textSecondary,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (open) R.string.chat_collapse else R.string.chat_expand),
                tint = palette.textTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(turn),
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                text = text,
                style = HarnessType.secondary,
                color = palette.textTertiary,
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp, bottom = 2.dp)
                    .drawBehind {
                        drawLine(
                            color = palette.border,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    .padding(start = 12.dp),
            )
        }
    }
}

/**
 * The image blocks of one entry, resolved to authorized routes.
 *
 * @param content the entry's content blocks.
 * @param sessionId the authorized session.
 * @param viewModel the app state holder, for the image route.
 * @return the images, in order.
 */
private fun imageRefs(content: List<Block>, sessionId: String?, viewModel: AppViewModel): List<ImageRef> {
    if (sessionId == null) return emptyList()
    return content.filter { it.type == "image" && it.attachmentId != null }.map { block ->
        ImageRef(
            url = viewModel.imageUrl(sessionId, block.attachmentId!!),
            width = block.width,
            height = block.height,
            name = block.name,
        )
    }
}

/**
 * One staged attachment, above the composer.
 *
 * A file that is still uploading wears the same chip with a spinner in place of
 * the remove button: it is already part of the message being written, and
 * hiding it until the bytes land is how a slow upload looks like a lost file.
 *
 * @param label the name, and the size when it is known.
 * @param uploading true while the bytes are still travelling.
 * @param onRemove removes it, or null when there is nothing to remove yet.
 */
@Composable
private fun AttachmentChip(label: String, uploading: Boolean = false, onRemove: (() -> Unit)?) {
    val palette = LocalHarness.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(HarnessRadius.pill))
            .background(palette.surface)
            .border(0.5.dp, palette.border, RoundedCornerShape(HarnessRadius.pill))
            .padding(start = 10.dp, end = 4.dp),
    ) {
        Icon(
            Icons.Rounded.AttachFile,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = HarnessType.caption, fontWeight = FontWeight.Medium, color = palette.textSecondary, maxLines = 1)
        if (uploading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 7.dp)
                    .size(13.dp),
                strokeWidth = 1.5.dp,
                color = palette.accent,
            )
        } else if (onRemove != null) {
            HarnessIconTarget(
                icon = Icons.Rounded.Close,
                description = stringResource(R.string.chat_remove),
                tint = palette.textTertiary,
                size = 26.dp,
                iconSize = 14.dp,
                onClick = onRemove,
            )
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * The message composer, drawn as the harness's single rounded card.
 *
 * @param draft current text.
 * @param onDraftChange text change callback.
 * @param staged photos waiting to be sent with the next message.
 * @param onUnstage removes one staged photo by index.
 * @param onPickPhoto opens the image picker.
 * @param stagedFiles files already stored on the host, waiting for the message.
 * @param uploadingFiles names of files whose upload is still running.
 * @param onUnstageFile removes one staged file by index.
 * @param onPickFile opens the system file picker.
 * @param running whether the session currently has a turn in flight.
 * @param sending whether a send is already in flight.
 * @param modelLabel the current model's name, or null when unknown.
 * @param onPickModel opens the model picker.
 * @param permission the access preset in force, or null when the host reported none.
 * @param permissionBusy whether a preset switch is in flight.
 * @param onPickPermission opens the permission picker.
 * @param onSend sends the message, with steer true when the user chose to interrupt.
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    staged: List<OutgoingImage>,
    onUnstage: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    stagedFiles: List<OutgoingFile>,
    uploadingFiles: List<String>,
    onUnstageFile: (Int) -> Unit,
    onPickFile: () -> Unit,
    running: Boolean,
    sending: Boolean,
    modelLabel: String?,
    onPickModel: () -> Unit,
    permission: PermissionOption?,
    permissionBusy: Boolean,
    onPickPermission: () -> Unit,
    onSend: (Boolean) -> Unit,
) {
    val palette = LocalHarness.current
    var steer by remember { mutableStateOf(false) }
    // A message cannot leave while a file is still travelling: what it would
    // carry is a receipt, and a receipt that does not exist yet is not a send
    // that can be retried — it is one that would silently lose the file. Nor
    // while a send is already in flight: a second tap would post the same spent
    // receipts again.
    val sendable = (draft.isNotBlank() || staged.isNotEmpty() || stagedFiles.isNotEmpty()) &&
        uploadingFiles.isEmpty() && !sending

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        // The queue-or-steer choice only matters when there is something to send,
        // so it appears with the message instead of occupying fifty points above
        // every running turn's composer.
        if (running && (draft.isNotBlank() || staged.isNotEmpty() || stagedFiles.isNotEmpty())) {
            // What a message does while a turn is in flight is a real choice,
            // so it is shown as one: both answers visible, one selected.
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.chat_queue_behind_turn),
                    stringResource(R.string.chat_steer_interrupt),
                ),
                selected = if (steer) 1 else 0,
                onSelect = { index -> steer = index == 1 },
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }

        if (staged.isNotEmpty() || stagedFiles.isNotEmpty() || uploadingFiles.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                staged.forEachIndexed { index, photo ->
                    AttachmentChip(
                        label = attachmentName(photo.name).take(18),
                        onRemove = { onUnstage(index) },
                    )
                }
                stagedFiles.forEachIndexed { index, file ->
                    AttachmentChip(
                        // The size rides the name through the separator resource, so
                        // a language that writes it the other way round can.
                        label = attachmentName(file.name).take(18) +
                            stringResource(R.string.chat_attachment_size, humanBytes(file.bytes)),
                        onRemove = { onUnstageFile(index) },
                    )
                }
                uploadingFiles.forEach { name ->
                    AttachmentChip(label = attachmentName(name).take(18), uploading = true, onRemove = null)
                }
            }
        }

        // One card holding the input and its controls: the shape a chat app's
        // composer has, floating over the transcript rather than docked to it.
        HarnessSurface(
            modifier = Modifier.fillMaxWidth(),
            background = if (palette.isDark) palette.surfaceElevated else palette.surface,
            border = palette.border,
            radius = HarnessRadius.panel,
            elevation = 10.dp,
        ) {
            Column(Modifier.padding(top = 14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 14.dp, bottom = 6.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.composer_hint),
                            style = HarnessType.body,
                            color = palette.textCaption,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = TextStyle(
                            color = palette.textPrimary,
                            fontSize = HarnessType.body.fontSize,
                            lineHeight = HarnessType.body.lineHeight,
                        ),
                        cursorBrush = SolidColor(palette.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The controls share whatever width the send button leaves:
                    // on a narrow phone the model's name shortens before anything
                    // is pushed off the edge.
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        HarnessIconTarget(
                            icon = Icons.Outlined.AddPhotoAlternate,
                            description = stringResource(R.string.action_attach),
                            tint = palette.textSecondary,
                            size = 36.dp,
                            iconSize = 21.dp,
                            onClick = onPickPhoto,
                        )
                        // Beside the photo, not behind a menu: a file is the second
                        // thing anyone wants to hand an agent, and the two are the
                        // same gesture — pick something, then send it.
                        HarnessIconTarget(
                            icon = Icons.Rounded.AttachFile,
                            description = stringResource(R.string.chat_attach_file),
                            tint = palette.textSecondary,
                            size = 36.dp,
                            iconSize = 21.dp,
                            onClick = onPickFile,
                        )
                        Spacer(Modifier.width(2.dp))
                        HarnessChip(
                            label = modelLabel ?: stringResource(R.string.chat_model_fallback),
                            onClick = onPickModel,
                            leadingIcon = Icons.Outlined.AutoAwesome,
                            trailingIcon = Icons.Rounded.KeyboardArrowDown,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(4.dp))
                        // What the agent is allowed to do sits next to what it will
                        // answer with: the two choices are made together, and the one
                        // with consequences should not be two screens away.
                        PermissionChip(
                            option = permission,
                            enabled = !permissionBusy,
                            onClick = onPickPermission,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SendButton(enabled = sendable, onClick = { onSend(steer) })
                }
            }
        }
    }
}

/**
 * The send button: a round brand-gradient target with an upward arrow.
 *
 * @param enabled whether there is anything to send.
 * @param onClick sends.
 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalHarness.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (enabled) Modifier.background(palette.brandGradient) else Modifier.background(palette.surfaceSunken),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(R.string.action_send),
            tint = if (enabled) palette.onAccent else palette.textCaption,
            modifier = Modifier.size(22.dp),
        )
    }
}
