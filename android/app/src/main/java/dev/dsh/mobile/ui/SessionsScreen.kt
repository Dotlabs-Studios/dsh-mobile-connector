package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ApprovalRequest
import dev.dsh.mobile.data.BrowseIntent
import dev.dsh.mobile.data.CHATS_BEFORE_MORE
import dev.dsh.mobile.data.ChatSlice
import dev.dsh.mobile.data.ChatStatus
import dev.dsh.mobile.data.DirEntry
import dev.dsh.mobile.data.SessionSummary
import dev.dsh.mobile.data.Workspace
import dev.dsh.mobile.data.browseIntent
import dev.dsh.mobile.data.browseParent
import dev.dsh.mobile.data.lastSegment
import dev.dsh.mobile.data.sectionKey
import dev.dsh.mobile.data.visibleChats
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The chat list: every conversation the harness knows, including the ones
 * started on the PC, grouped into the projects the PC groups them into.
 *
 * Each project is a heading over one rounded card of chats, the inset-grouped
 * layout a phone reader already knows from messaging apps: the heading says
 * where the chats run, the card keeps a long list scannable, and a row's leading
 * avatar carries its state so the list can be read at a glance.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(viewModel: AppViewModel) {
    // Polling is owned by [AppViewModel.onForeground], not by this screen: with a
    // chat open this composable is not on screen, so a stop-effect here never ran
    // and the list loop kept polling behind a chat that had already paused.

    val palette = LocalHarness.current

    // Search is debounced: the field used to fire a full list request — four of
    // them, before the pulse route — on every keystroke, and a fast typist could
    // watch older results land on top of newer ones.
    var typedFilter by remember { mutableStateOf(viewModel.sessionFilter) }
    LaunchedEffect(typedFilter) {
        if (typedFilter == viewModel.sessionFilter) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        viewModel.sessionFilter = typedFilter
        viewModel.refreshSessions()
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            SessionsHeader(
                host = viewModel.pairedHost,
                healthy = viewModel.problem == null,
                onRefresh = { viewModel.refreshSessions() },
                onSettings = { viewModel.openSettings() },
            )
        },
        floatingActionButton = {
            HarnessButton(
                label = stringResource(R.string.action_new_session),
                onClick = { viewModel.openPicker() },
                icon = Icons.Rounded.Add,
                large = true,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(HarnessRadius.pill),
                        ambientColor = palette.brandStart,
                        spotColor = palette.brandStart,
                    ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            HarnessTextField(
                value = typedFilter,
                onValueChange = { typedFilter = it },
                placeholder = stringResource(R.string.session_search),
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            viewModel.problem?.let { problem ->
                // An expired or revoked token has exactly one fix, so the banner
                // offers it instead of leaving the user to hunt for the unpair
                // control in the settings. Anything else is a link that may simply
                // have come back, so it offers the read that proves it.
                val stale = problem.code == "unauthorized"
                ProblemBanner(
                    message = textResource(problem.message),
                    onDismiss = { viewModel.dismissProblem() },
                    actionLabel = stringResource(if (stale) R.string.session_reconnect else R.string.action_retry),
                    onAction = if (stale) ({ viewModel.unpair() }) else ({ viewModel.retryNow() }),
                )
            }

            // Approvals for every chat, not just the open one: an unanswered
            // approval blocks the agent on the PC, and the user may well be
            // looking at this screen when it is raised.
            if (viewModel.approvals.isNotEmpty()) {
                ApprovalsStrip(viewModel)
            }

            val groups = viewModel.sessionGroups
            val total = viewModel.sessions.size

            // The projects usually arrive a beat after the chats, and a lazy list
            // keeps its first visible item by key: left alone, it would follow the
            // old first group down and open with the real first one scrolled half
            // out of view. Until the reader touches the list, it stays at the top.
            val listState = rememberLazyListState()
            val touched by listState.interactionSource.collectIsDraggedAsState()
            var reading by remember { mutableStateOf(false) }
            LaunchedEffect(touched) { if (touched) reading = true }
            val groupKeys = groups.map { sectionKey(it.workspaceId) }
            LaunchedEffect(groupKeys) { if (!reading) listState.scrollToItem(0) }

            when {
                total == 0 && viewModel.sessionsLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = palette.accent, strokeWidth = 3.dp)
                    }
                }

                total == 0 && groups.none { it.workspace != null } -> {
                    EmptyState(
                        title = stringResource(R.string.empty_sessions),
                        message = stringResource(R.string.empty_sessions_hint),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
                    ) {
                        items(groups, key = { sectionKey(it.workspaceId) }) { group ->
                            val key = sectionKey(group.workspaceId)
                            val collapsed = key in viewModel.collapsedWorkspaces
                            val expanded = key in viewModel.expandedWorkspaces
                            Column(Modifier.animateItem()) {
                                WorkspaceHeader(
                                    title = group.title,
                                    // A folded group is a heading and nothing else: the
                                    // path and its chats are what the fold is for, and
                                    // the count in the heading still says how much is
                                    // in there.
                                    path = if (collapsed) null else group.path,
                                    isProject = group.workspace != null,
                                    count = group.sessions.size,
                                    collapsed = collapsed,
                                    onToggle = { viewModel.toggleWorkspace(key) },
                                    onRemove = group.workspace?.let { workspace ->
                                        { viewModel.askRemoveWorkspace(workspace) }
                                    },
                                )
                                if (!collapsed) {
                                    // An open card is a working set, not the archive:
                                    // it shows the newest few and offers the tail, so
                                    // a project with forty chats does not push the next
                                    // project off the screen.
                                    val slice = visibleChats(group.sessions, expanded)
                                    val toggle = chatSliceToggleLabel(slice, expanded)
                                    HarnessSurface(
                                        modifier = Modifier.fillMaxWidth(),
                                        background = palette.surface,
                                        border = palette.borderSubtle,
                                        radius = HarnessRadius.xl,
                                    ) {
                                        if (group.sessions.isEmpty()) {
                                            EmptyGroupRow(
                                                text = if (group.workspace == null) {
                                                    stringResource(R.string.session_empty_here)
                                                } else {
                                                    stringResource(R.string.session_empty_project)
                                                },
                                                onStart = group.workspace?.let { workspace ->
                                                    { viewModel.startChatIn(workspace.path) }
                                                },
                                            )
                                        } else {
                                            Column {
                                                slice.visible.forEachIndexed { index, session ->
                                                    if (index > 0) {
                                                        HarnessDivider(Modifier.padding(start = 66.dp), subtle = true)
                                                    }
                                                    SessionRow(session, unread = viewModel.isUnread(session)) { viewModel.open(session) }
                                                }
                                                toggle?.let { label ->
                                                    if (slice.visible.isNotEmpty()) {
                                                        HarnessDivider(Modifier.padding(start = 66.dp), subtle = true)
                                                    }
                                                    ChatSliceRow(
                                                        label = label,
                                                        expanded = expanded,
                                                        onClick = { viewModel.toggleExpandedWorkspace(key) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    viewModel.workspaceToRemove?.let { workspace ->
        RemoveWorkspaceDialog(workspace = workspace, viewModel = viewModel)
    }

    if (viewModel.pickerOpen) {
        NewChatPicker(viewModel)
    }

    if (viewModel.settingsOpen) {
        SettingsSheet(viewModel)
    }
}

/**
 * The screen's large title, with the PC it is talking to and its two actions.
 *
 * @param host the paired address, when there is one.
 * @param healthy whether the last exchange with the PC went through.
 * @param onRefresh reloads the list.
 * @param onSettings opens the settings sheet.
 */
@Composable
private fun SessionsHeader(
    host: String?,
    healthy: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val palette = LocalHarness.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.headlineMedium, color = palette.textPrimary)
            host?.let { address ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (healthy) palette.success else palette.warning),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = address.removePrefix("http://").removePrefix("https://"),
                        style = HarnessType.caption,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HarnessIconTarget(
            icon = Icons.Rounded.Refresh,
            description = stringResource(R.string.session_refresh),
            background = palette.surface,
            bordered = true,
            size = 40.dp,
            onClick = onRefresh,
        )
        Spacer(Modifier.width(8.dp))
        HarnessIconTarget(
            icon = Icons.Outlined.Settings,
            description = stringResource(R.string.label_settings),
            background = palette.surface,
            bordered = true,
            size = 40.dp,
            onClick = onSettings,
        )
    }
}

/**
 * The centred message an empty screen shows, under the app's mark.
 *
 * @param title the headline.
 * @param message what to do next.
 * @param modifier layout, which owns the available space.
 */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    Column(
        modifier.padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = HarnessType.secondary,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One project's heading: the fold, the name, the count, and the way to remove it.
 *
 * The whole heading is the fold target rather than a chevron beside it, because on
 * a list this long the heading is what a thumb aims at. The remove control is
 * deliberately *not* part of that target: one tap would otherwise both fold a
 * project and open a question about deleting it, and folding is the gesture people
 * make without looking.
 *
 * @param title the project's name.
 * @param path the folder on the PC, shown while the project is open.
 * @param isProject false for the trailing bucket of chats no project owns.
 * @param count how many chats are inside it.
 * @param collapsed whether its chats are folded away.
 * @param onToggle invoked when the heading is tapped.
 * @param onRemove invoked when the remove control is used, or null where there is
 *   nothing to remove.
 */
@Composable
private fun WorkspaceHeader(
    title: String,
    path: String?,
    isProject: Boolean,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val palette = LocalHarness.current
    val turn by animateFloatAsState(if (collapsed) -90f else 0f, label = "fold")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(HarnessRadius.md))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = if (isProject) Icons.Outlined.Folder else Icons.Outlined.Inbox,
            tint = if (isProject) palette.accent else palette.textTertiary,
            background = if (isProject) palette.accentContainer else palette.surfaceSunken,
            size = 30.dp,
            iconSize = 17.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            path?.let {
                Text(
                    text = it,
                    style = HarnessType.codeBlock.copy(fontSize = HarnessType.caption.fontSize, lineHeight = HarnessType.caption.lineHeight),
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(HarnessRadius.pill))
                .background(palette.surfaceSunken)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (count == 0) stringResource(R.string.session_count_empty) else "$count",
                style = HarnessType.caption,
                fontWeight = FontWeight.SemiBold,
                color = palette.textTertiary,
            )
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) {
                stringResource(R.string.session_group_open, title)
            } else {
                stringResource(R.string.session_group_close, title)
            },
            tint = palette.textTertiary,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(22.dp)
                .rotate(turn),
        )
        if (onRemove != null) {
            HarnessIconTarget(
                icon = Icons.Outlined.DeleteOutline,
                description = stringResource(R.string.workspace_remove, title),
                tint = palette.textCaption,
                size = 32.dp,
                iconSize = 18.dp,
                onClick = onRemove,
            )
        }
    }
}

/**
 * The label of the row that opens or folds one project's tail, or null when there
 * is nothing to open and nothing to fold.
 *
 * It is written here and not in `data/Workspaces.kt` because it is words: what the
 * data layer decides is the cut — `ChatSlice`, and how many chats it holds back —
 * and this reads that answer rather than recomputing it, so the number on the row
 * and the number of rows above it cannot disagree.
 *
 * The count is in the label because the heading's own count is the project's
 * total: a reader who folded the projects to get an index still has to know how
 * much a tap is about to unfold. It is spelled out rather than left as "+7"
 * because it is a sentence a thumb is aiming at, not a badge.
 *
 * Both directions come from one place so the row cannot be drawn when it would do
 * nothing: a project of three chats has no tail, and a card that is already open
 * with nothing left over — the project lost chats while it was open — must not
 * offer to fold a list that is already complete. An opened card also keeps the way
 * back where the way in was, rather than leaving the reader to fold the whole
 * project to get the tidy list back: one gesture should not cost two.
 *
 * @param slice the cut the card is drawing.
 * @param expanded whether the card is currently showing all of them.
 * @param limit how many stay visible when it is not.
 * @return the label, or null for a card with nothing to toggle.
 */
@Composable
private fun chatSliceToggleLabel(
    slice: ChatSlice,
    expanded: Boolean,
    limit: Int = CHATS_BEFORE_MORE,
): String? = when {
    slice.hasMore -> pluralStringResource(R.plurals.session_slice_more, slice.hidden, slice.hidden)
    expanded && slice.visible.size > limit -> stringResource(R.string.session_slice_fewer, limit)
    else -> null
}

/**
 * The row that opens one project's tail, or folds it back to its newest chats.
 *
 * It sits at the end of the card, under a divider of its own, because it belongs
 * to the list above it rather than being a row of it: same width and same tap
 * target as a chat, but centred and in the accent colour, so it reads as the end
 * of the list rather than as a chat called "Vedi le altre 7 chat".
 *
 * @param label what the row says, which is what decides which way it goes.
 * @param expanded whether the card is currently showing everything, which picks
 *   the arrow that matches where the tap leads.
 * @param onClick invoked when the row is tapped.
 */
@Composable
private fun ChatSliceRow(label: String, expanded: Boolean, onClick: () -> Unit) {
    val palette = LocalHarness.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = HarnessType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = palette.accent,
        )
    }
}

/**
 * What an empty project's card holds: a sentence and a way to start.
 *
 * @param text the sentence.
 * @param onStart starts a chat in this project, when it is one.
 */
@Composable
private fun EmptyGroupRow(text: String, onStart: (() -> Unit)?) {
    val palette = LocalHarness.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = HarnessType.secondary,
            color = palette.textTertiary,
            modifier = Modifier.weight(1f),
        )
        onStart?.let { start ->
            HarnessOutlineButton(label = stringResource(R.string.session_new_here), icon = Icons.Rounded.Add, onClick = start)
        }
    }
}

/**
 * The question one tap on the remove control asks.
 *
 * It is a dialog and not a swipe or a long press for the usual reason — the action
 * is not undoable from the phone — and it is *worded* carefully for a less usual
 * one: the host's route unregisters the project and never touches the disk, so a
 * confirmation that read like "delete these files" would be a lie about the
 * filesystem, which is the kind of lie a person acts on.
 *
 * @param workspace the project about to leave the list.
 * @param viewModel the app state holder, which owns the answer.
 */
@Composable
private fun RemoveWorkspaceDialog(workspace: Workspace, viewModel: AppViewModel) {
    val palette = LocalHarness.current
    AlertDialog(
        onDismissRequest = { viewModel.cancelRemoveWorkspace() },
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        shape = RoundedCornerShape(HarnessRadius.panel),
        icon = {
            IconBadge(
                icon = Icons.Outlined.DeleteOutline,
                tint = palette.error,
                background = palette.error.copy(alpha = 0.12f),
                size = 44.dp,
                iconSize = 22.dp,
            )
        },
        title = {
            Text(
                stringResource(R.string.workspace_remove_question, workspace.displayTitle),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        },
        text = { Text(stringResource(R.string.workspace_remove_detail), style = HarnessType.secondary) },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmRemoveWorkspace() },
                enabled = !viewModel.removingWorkspace,
            ) {
                Text(stringResource(R.string.workspace_remove_confirm), color = palette.error, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { viewModel.cancelRemoveWorkspace() },
                enabled = !viewModel.removingWorkspace,
            ) {
                Text(stringResource(R.string.workspace_remove_cancel), color = palette.textSecondary)
            }
        },
    )
}

/**
 * The approvals the host is holding, one card each.
 *
 * @param viewModel the app state holder.
 */
@Composable
private fun ApprovalsStrip(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(palette.warning),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.session_approvals_waiting,
                    viewModel.approvals.size,
                    viewModel.approvals.size,
                ).uppercase(),
                style = HarnessType.overline,
                color = palette.warningText,
            )
        }
        viewModel.approvals.forEach { approval: ApprovalRequest ->
            val session = viewModel.sessions.firstOrNull { it.id == approval.sessionId }
            ApprovalCard(
                toolName = approval.toolName,
                prompt = listOfNotNull(session?.displayTitle, approval.prompt.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                outcome = null,
                answerable = true,
                busy = viewModel.answeringApproval == approval.id,
                onAllow = { viewModel.answerApproval(approval, "allowed-once") },
                onReject = { viewModel.answerApproval(approval, "rejected") },
            )
        }
    }
}

/**
 * One chat row: an avatar carrying the chat's state, the title, and when it last
 * moved.
 *
 * The state badge sits on the avatar because it is what the list is scanned for:
 * which chat is working, which one finished, which one is sitting on an error.
 * Running keeps a moving ring rather than a dot — two marks for one fact would
 * leave the reader comparing them to find out which one is current.
 *
 * @param session the session to render.
 * @param unread whether something happened in this chat since it was last read.
 * @param onOpen invoked when the row is tapped.
 */
@Composable
private fun SessionRow(session: SessionSummary, unread: Boolean = false, onOpen: () -> Unit) {
    val palette = LocalHarness.current
    val status = session.status
    val detail = buildList {
        // The project heading already names the folder for grouped chats, so the
        // row repeats it only when nothing else identifies it.
        if (session.workspaceId == null) lastSegment(session.cwd)?.let { add(it) }
        // "in corso" is the badge's own news. A status the host did not report
        // keeps the old wording, because that is still true and the row would
        // otherwise lose the fact entirely.
        if (status == null && session.running) add(stringResource(R.string.state_running))
        if (session.blank) add(stringResource(R.string.state_blank))
        if (session.origin == "subagent") add(stringResource(R.string.state_subagent))
    }.joinToString("  ·  ")

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionAvatar(
            status = status?.takeIf { it.showsDot },
            subagent = session.origin == "subagent",
            blank = session.blank,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.displayTitle,
                    style = HarnessType.body,
                    fontWeight = FontWeight.Medium,
                    color = if (session.blank) palette.textSecondary else palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // A chat with something new since the last look carries one dot;
                // the status badge already says what happened, this says that the
                // reader has not seen it.
                if (unread) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = formatTime(session.updatedAt),
                    style = HarnessType.caption,
                    color = palette.textCaption,
                    maxLines = 1,
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = HarnessType.caption,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A chat's avatar: a tinted tile with a glyph, and the state badge on its corner.
 *
 * @param status the state to badge, or null for none.
 * @param subagent whether the chat was started by another agent.
 * @param blank whether the chat has no messages yet.
 */
@Composable
private fun SessionAvatar(status: ChatStatus?, subagent: Boolean, blank: Boolean) {
    val palette = LocalHarness.current
    Box(Modifier.size(40.dp)) {
        IconBadge(
            icon = if (subagent) Icons.Outlined.AccountTree else Icons.Outlined.ChatBubbleOutline,
            tint = if (blank) palette.textTertiary else palette.accent,
            background = if (blank) palette.surfaceSunken else palette.accentContainer,
            size = 40.dp,
            iconSize = 19.dp,
        )
        if (status != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
                contentAlignment = Alignment.Center,
            ) {
                ChatStatusDot(status)
            }
        }
    }
}

/**
 * The words one state is read aloud as.
 *
 * They are resources, not properties of [ChatStatus], because they are sentences
 * and the data layer does not write sentences. They say what happened rather than
 * what colour the dot is: a reader who cannot see the colour learns nothing from
 * "rosso", and a dot is read at a glance and never checked.
 *
 * @param status the state to name.
 */
@Composable
private fun chatStatusLabel(status: ChatStatus): String = stringResource(
    when (status) {
        ChatStatus.Running -> R.string.status_running
        ChatStatus.Completed -> R.string.status_completed
        ChatStatus.Failed -> R.string.status_failed
        ChatStatus.Stopped -> R.string.status_stopped
        ChatStatus.Idle -> R.string.status_idle
    },
)

/**
 * The dot that says how a chat's last turn ended.
 *
 * The three colours are the palette's own state tokens — success, warn, error —
 * rather than three chosen here, so a theme change moves them with everything
 * else. The two neutral states are drawn as a grey dot ([Idle]) or as a hollow
 * ring ([Stopped]): "interrotta" is not a failure and must not borrow the red that
 * means one.
 *
 * @param status the state to draw.
 */
@Composable
private fun ChatStatusDot(status: ChatStatus) {
    val palette = LocalHarness.current
    val color = when (status) {
        ChatStatus.Running -> palette.warning
        ChatStatus.Completed -> palette.success
        ChatStatus.Failed -> palette.error
        ChatStatus.Stopped -> palette.textCaption
        ChatStatus.Idle -> palette.textCaption
    }
    // A dot says nothing by itself, so the row's state is spelled out for a
    // screen reader next to the glyph.
    val description = stringResource(R.string.session_chat_status, chatStatusLabel(status))
    Box(
        modifier = Modifier
            .size(12.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (status == ChatStatus.Running) {
            // The one state that is a *process* rather than an outcome keeps the
            // moving ring: it is the difference between "this finished a minute
            // ago" and "this is being written right now".
            RunningRing(color, 11.dp)
        } else {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .then(
                        if (status == ChatStatus.Stopped) {
                            Modifier.border(1.5.dp, color, CircleShape)
                        } else {
                            Modifier.background(color)
                        },
                    ),
            )
        }
    }
}

/**
 * The new-chat flow: pick a project, or walk the PC's folders and make one.
 *
 * Both faces end in the same place — a host directory — because that is what
 * decides where the new chat runs and which project it lands in.
 *
 * @param viewModel the app state holder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatPicker(viewModel: AppViewModel) {
    val palette = LocalHarness.current
    BackHandler { viewModel.closePicker() }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closePicker() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            SheetHeader(
                title = stringResource(R.string.action_new_session),
                subtitle = stringResource(R.string.session_new_note),
            )

            SegmentedControl(
                options = listOf(
                    stringResource(R.string.session_projects),
                    stringResource(R.string.session_browse_pc),
                ),
                selected = if (viewModel.pickerTab == PickerTab.Recent) 0 else 1,
                onSelect = { index -> viewModel.pickerTab(if (index == 0) PickerTab.Recent else PickerTab.Browse) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )

            viewModel.pickerError?.let { message ->
                ProblemBanner(message = textResource(message), onDismiss = { /* cleared by the next action */ })
            }

            when (viewModel.pickerTab) {
                // Both faces claim the sheet's remaining height: a browse level
                // or a project list that only filled a strip would force the user
                // to scroll a list that looks like it ends after four rows.
                PickerTab.Recent -> RecentProjects(viewModel, Modifier.weight(1f))
                PickerTab.Browse -> BrowsePane(viewModel, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The projects the PC already knows, plus a way into the folder tree.
 * @param viewModel the app state holder.
 * @param modifier layout from the sheet, which owns the remaining height.
 */
@Composable
private fun RecentProjects(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    val projects = viewModel.workspaces
    Column(modifier.fillMaxWidth()) {
        if (projects.isEmpty()) {
            Text(
                text = stringResource(R.string.session_no_projects),
                style = HarnessType.body,
                color = palette.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(projects, key = { it.workspaceId }) { workspace ->
                    WorkspaceRow(workspace = workspace, onClick = { viewModel.startChatIn(workspace.path) })
                }
            }
        }
        HarnessOutlineButton(
            label = stringResource(R.string.session_browse_pc),
            icon = Icons.Outlined.FolderOpen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) { viewModel.pickerTab(PickerTab.Browse) }
    }
}

/**
 * One project row in the picker.
 * @param workspace the project.
 * @param onClick invoked when the row is tapped.
 */
@Composable
private fun WorkspaceRow(workspace: Workspace, onClick: () -> Unit) {
    val palette = LocalHarness.current
    HarnessRow(
        title = workspace.displayTitle,
        detail = workspace.path,
        monoDetail = true,
        onClick = onClick,
        leading = {
            IconBadge(
                icon = Icons.Outlined.Folder,
                tint = palette.accent,
                background = palette.accentContainer,
                size = 38.dp,
                iconSize = 19.dp,
            )
        },
        trailing = {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textCaption, modifier = Modifier.size(20.dp))
        },
    )
}

/**
 * The primary button's label, which says what the tap will do — including the
 * folder it is about to make, because a name in the field changes the action.
 *
 * Which of the three sentences applies is [BrowseIntent], decided in
 * `data/Folders.kt`; the words themselves are resources, so they are named here.
 *
 * @param intent the decided intent, or null before a level has loaded.
 */
@Composable
private fun browseLabel(intent: BrowseIntent?): String = when (intent) {
    is BrowseIntent.CreateThenHere -> stringResource(R.string.browse_create_and_start, intent.name)
    is BrowseIntent.EnterExisting -> stringResource(R.string.browse_start_in, intent.name)
    else -> stringResource(R.string.browse_start_here)
}

/**
 * The PC's folder tree, one level at a time.
 *
 * Nothing here builds a host path: every row carries the absolute path the host
 * reported, and the host's own breadcrumbs are the way back up. That is what
 * keeps a phone from having to know about `C:\` versus `/`.
 *
 * @param viewModel the app state holder.
 * @param modifier layout from the sheet, which owns the remaining height.
 */
@Composable
private fun BrowsePane(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val palette = LocalHarness.current
    val listing = viewModel.browsing
    val primary = browseIntent(listing, viewModel.folderDraft)
    // A volume root has no level above it, and the arrow says so by being out of
    // reach rather than by ignoring the tap: a control that looks live and does
    // nothing is what "the up button doesn't work" is made of. The way out of a
    // root is the drive crumbs, which the host reports on every level.
    val parent = browseParent(listing)

    Column(modifier.fillMaxWidth()) {
        HarnessSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            background = palette.surfaceSunken,
            border = palette.borderSubtle,
            radius = HarnessRadius.md,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HarnessIconTarget(
                    icon = Icons.Rounded.ArrowUpward,
                    description = if (parent == null) {
                        stringResource(R.string.session_up_none)
                    } else {
                        stringResource(R.string.session_up_one)
                    },
                    enabled = parent != null,
                    size = 32.dp,
                    iconSize = 18.dp,
                    onClick = { viewModel.browseUp() },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = listing?.path ?: "…",
                    style = HarnessType.codeBlock,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (viewModel.browsingLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(14.dp),
                        strokeWidth = 2.dp,
                        color = palette.accent,
                    )
                }
            }
        }

        listing?.crumbs?.takeIf { it.isNotEmpty() }?.let { crumbs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = palette.textCaption,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = if (index == 0) crumb.name.trimEnd('\\', '/').ifBlank { crumb.path } else crumb.name,
                        style = HarnessType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(HarnessRadius.pill))
                            .background(palette.accentContainer)
                            .clickable { viewModel.openBrowse(crumb.path) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        HarnessTextField(
            value = viewModel.folderDraft,
            onValueChange = { viewModel.editFolderDraft(it) },
            placeholder = stringResource(R.string.session_new_folder),
            leadingIcon = Icons.Outlined.CreateNewFolder,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            trailing = {
                HarnessIconTarget(
                    icon = Icons.Rounded.Add,
                    description = stringResource(R.string.session_create_folder),
                    tint = palette.accent,
                    background = palette.accentContainer,
                    size = 32.dp,
                    iconSize = 18.dp,
                ) {
                    viewModel.createFolder()
                }
            },
        )

        listing?.let { level ->
            // The action sits above the list, not at its end: on a level with
            // hundreds of folders, a button that only appears after scrolling
            // past all of them is a button nobody finds.
            HarnessButton(
                label = browseLabel(primary),
                onClick = { viewModel.startChatHere() },
                enabled = !viewModel.pickerBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(level.entries, key = { it.path }) { entry ->
                    FolderRow(entry, onClick = { viewModel.openBrowse(entry.path) })
                }
                if (level.entries.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.session_no_subfolders),
                            style = HarnessType.secondary,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                if (level.truncated) {
                    item {
                        Text(
                            text = stringResource(R.string.session_list_truncated),
                            style = HarnessType.caption,
                            color = palette.textTertiary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One folder row.
 * @param entry the directory the host reported.
 * @param onClick invoked when the row is tapped.
 */
@Composable
private fun FolderRow(entry: DirEntry, onClick: () -> Unit) {
    val palette = LocalHarness.current
    HarnessRow(
        title = entry.name,
        detail = null,
        onClick = onClick,
        leading = {
            IconBadge(
                icon = if (entry.drive) Icons.Outlined.Storage else Icons.Outlined.Folder,
                tint = palette.textSecondary,
                background = palette.surfaceSunken,
                size = 34.dp,
                iconSize = 18.dp,
            )
        },
        trailing = {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.textCaption, modifier = Modifier.size(20.dp))
        },
    )
}

/**
 * Render an epoch-millisecond timestamp as a short local label.
 *
 * @param epochMillis the host timestamp.
 * @return a time for today, otherwise a date.
 */
private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val now = System.currentTimeMillis()
    val sameDay = (now - epochMillis) < 24L * 60 * 60 * 1000
    val formatter = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateInstance(DateFormat.SHORT)
    return formatter.format(Date(epochMillis))
}

/** How long the search field waits after the last keystroke before asking the host. */
private const val SEARCH_DEBOUNCE_MS = 300L
