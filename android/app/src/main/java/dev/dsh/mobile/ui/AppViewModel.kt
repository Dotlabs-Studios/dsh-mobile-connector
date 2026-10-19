package dev.dsh.mobile.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.R
import dev.dsh.mobile.data.ApiException
import dev.dsh.mobile.data.ApprovalRequest
import dev.dsh.mobile.data.BrowseIntent
import dev.dsh.mobile.data.ChatSource
import dev.dsh.mobile.data.ChatSync
import dev.dsh.mobile.data.DeclaredFile
import dev.dsh.mobile.data.DeliveredFile
import dev.dsh.mobile.data.DirEntry
import dev.dsh.mobile.data.DirListing
import dev.dsh.mobile.data.Entry
import dev.dsh.mobile.data.HostFile
import dev.dsh.mobile.data.LanguageMode
import dev.dsh.mobile.data.LinkTarget
import dev.dsh.mobile.data.MAX_STAGED_FILES
import dev.dsh.mobile.data.MessagePage
import dev.dsh.mobile.data.MobileApi
import dev.dsh.mobile.data.ModelCatalog
import dev.dsh.mobile.data.ModelInfo
import dev.dsh.mobile.data.ModelSelection
import dev.dsh.mobile.data.OutgoingFile
import dev.dsh.mobile.data.OutgoingImage
import dev.dsh.mobile.data.PairedDevice
import dev.dsh.mobile.data.PermissionCatalog
import dev.dsh.mobile.data.PreviewResponse
import dev.dsh.mobile.data.PermissionOption
import dev.dsh.mobile.data.PendingQuestion
import dev.dsh.mobile.data.QuestionAnswer
import dev.dsh.mobile.data.SessionStore
import dev.dsh.mobile.data.TextRef
import dev.dsh.mobile.data.ThemeMode
import dev.dsh.mobile.data.attachmentName
import dev.dsh.mobile.data.browseIntent
import dev.dsh.mobile.data.browseParent
import dev.dsh.mobile.data.classifyLink
import dev.dsh.mobile.data.createdDirectory
import dev.dsh.mobile.data.sectionKey
import dev.dsh.mobile.data.visibleTranscript
import dev.dsh.mobile.data.SessionSummary
import dev.dsh.mobile.data.Workspace
import dev.dsh.mobile.data.applyLanguage
import dev.dsh.mobile.data.normalizeHost
import dev.dsh.mobile.data.withLanguage
import dev.dsh.mobile.watch.WatchService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The top-level destination the app is showing. */
enum class Screen { Pairing, Sessions, Chat }

/**
 * Which face of the new-chat picker is showing.
 *
 * `Recent` is the fast path — the projects the PC already groups chats into —
 * and `Browse` is the PC's own folder tree for a directory the list does not
 * have yet.
 */
enum class PickerTab { Recent, Browse }

/** One chat list section: a PC project, or the chats no project owns. */
data class SessionGroup(
    val workspaceId: String?,
    /** Null for the trailing bucket of chats whose directory has no project. */
    val workspace: Workspace?,
    val title: String,
    val path: String?,
    val sessions: List<SessionSummary>,
)

/**
 * Why the app cannot talk to its host right now, when it cannot.
 *
 * The message is a [TextRef] rather than a string for the same reason the code
 * above it is a code: the layer that decides *which* failure this is has no
 * business knowing which language the reader speaks, and the layer that draws
 * it has no business guessing what the host meant.
 *
 * @property code the machine-readable reason, as the host or transport named it.
 * @property message the sentence to show.
 */
data class ConnectionProblem(val code: String, val message: TextRef)

/**
 * The coordinates of one declared delivery, which is how the host resolves it.
 *
 * The path is deliberately *not* part of this: what the phone holds is the
 * declaration an agent wrote, and what the host resolves is the file that
 * declaration means. Sending the coordinates back is what keeps the two from
 * drifting apart.
 *
 * @property sessionId the chat the delivery belongs to.
 * @property seq the sequence of the `deliverables/presented` event.
 * @property declared the file as the agent declared it, index included.
 */
data class DeliveryRef(val sessionId: String, val seq: Int, val declared: DeclaredFile)

/**
 * One delivery action in flight.
 *
 * Keyed by the file it is working on rather than held as a bare flag, because
 * the card shows the spinner on the row that was tapped: two rows of the same
 * delivery can be saved one after the other, and a spinner on the wrong one is
 * how a user learns to distrust the card.
 */
data class DeliveryBusy(
    val sessionId: String,
    val seq: Int,
    val index: Int,
    /** `open`, `download` or `share`, for the label beside the spinner. */
    val action: String,
)

/** What one finished delivery action left behind, and how it went. */
data class DeliveryNotice(val text: String, val ok: Boolean)

/** Identifies one delivered file across the card, the sheet, and its notices. */
internal fun deliveryKey(sessionId: String, seq: Int, index: Int): String = "$sessionId#$seq#$index"

/**
 * Owns pairing, the session list, and the open conversation.
 *
 * Freshness comes from short-interval polling rather than a socket: the mobile
 * surface is read-mostly, the payloads are small, and polling survives the
 * phone sleeping, changing networks, and reconnecting to Tailscale without a
 * bespoke reconnect state machine.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)

    var screen by mutableStateOf(if (store.host != null && store.token != null) Screen.Sessions else Screen.Pairing)
        private set

    var hostInput by mutableStateOf(store.host ?: "")
    var codeInput by mutableStateOf("")
    var deviceInput by mutableStateOf(store.deviceName)

    /**
     * The message being composed.
     *
     * Held here rather than in the screen because the send is asynchronous: the
     * composer used to clear its own copy the moment the tap happened, so a
     * refusal the host answered a second later (`writer-held`, a dropped link)
     * left the user with an empty box and a message that never arrived. The text
     * is cleared only once the host has accepted it, and it survives rotation
     * while it is being composed.
     */
    var draft by mutableStateOf("")

    /** Whether a send is in flight, so the composer can refuse a second tap. */
    var sending by mutableStateOf(false)
        private set

    var pairing by mutableStateOf(false)
        private set
    var pairingError by mutableStateOf<TextRef?>(null)
        private set

    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /**
     * The last `updatedAt` this phone has seen for each chat, by session id.
     *
     * Kept beside the list because it is what the list's unread dot is decided from,
     * and persisted so a chat read yesterday is not new again after a restart.
     */
    var seenAt by mutableStateOf(store.seenAt)
        private set

    /** When the seen map was last written, so a running turn does not write per poll. */
    private var seenWriteAt = 0L
    var sessionsLoading by mutableStateOf(false)
        private set
    var sessionFilter by mutableStateOf("")
    var includeSubagents by mutableStateOf(false)

    /**
     * The PC's projects, as the harness registry reports them.
     *
     * Kept beside the session list rather than fetched by the screen: the list
     * groups by project, and one poll keeps both fresh together. A host too old
     * to report projects yields an empty list, and the app falls back to the
     * flat chat list it had before.
     */
    var workspaces by mutableStateOf<List<Workspace>>(emptyList())
        private set

    /** Whether the harness answered the project list at all. */
    var workspacesAvailable by mutableStateOf(false)
        private set

    /**
     * Projects whose chats are folded away, by section key ([sectionKey]).
     *
     * The list is a scan surface and a working set at once: with a dozen projects
     * the reader is looking for one of them, and the others are in the way. The fold
     * is the app's own state rather than the host's — a collapsed group is a fact
     * about this phone's list, not about the project — but it is *persisted*, in
     * [SessionStore], because a project closed to get the others out of the way is
     * a decision that has to survive the app being closed: a fold that undoes
     * itself at every launch is a fold nobody makes twice.
     */
    var collapsedWorkspaces by mutableStateOf(store.collapsedWorkspaces)
        private set

    /**
     * Projects whose card is showing every chat rather than only the newest few.
     *
     * Deliberately *not* persisted, unlike the fold: opening the tail is a look at
     * one project right now, and a list that reopened tomorrow with every row of
     * eighty chats on screen would be exactly the wall the slice exists to take
     * away. The fold is the durable decision; this is the exception made to it, and
     * making it again costs one tap.
     */
    var expandedWorkspaces by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * The project whose removal is being confirmed, if any.
     *
     * Removing a project is one tap in a list of many, so the tap asks first and
     * the answer goes through here: the dialog owns the question, and the state
     * lives beside the list it changes.
     */
    var workspaceToRemove by mutableStateOf<Workspace?>(null)
        private set

    /** Whether a removal is in flight, so its buttons can disable. */
    var removingWorkspace by mutableStateOf(false)
        private set

    /** Whether the new-chat picker is open. */
    var pickerOpen by mutableStateOf(false)
        private set
    var pickerTab by mutableStateOf(PickerTab.Recent)
        private set
    var pickerBusy by mutableStateOf(false)
        private set
    var pickerError by mutableStateOf<TextRef?>(null)
        private set

    /** The PC directory level the picker is showing, once the user browses. */
    var browsing by mutableStateOf<DirListing?>(null)
        private set
    var browsingLoading by mutableStateOf(false)
        private set

    /**
     * Which browse request is the current one.
     *
     * A tap that arrives while another level is still loading must not be
     * dropped: on a slow link the first level's own load is exactly the moment
     * someone tries the arrow, and a tap the app silently ignores is
     * indistinguishable from a broken button. So the newest request wins, and
     * this counter is what keeps an answer that arrives late from drawing a
     * level nobody asked for last.
     */
    private var browseRequest = 0

    /**
     * The folder name typed in the picker, if any.
     *
     * Held here rather than in the composable for two reasons: a create that the
     * host refuses must not eat the name the user typed, and the primary button
     * reads it to say what it is about to do — the same gesture that creates the
     * folder starts the chat inside it.
     */
    var folderDraft by mutableStateOf("")
        private set

    var openSession by mutableStateOf<SessionSummary?>(null)
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var entriesLoading by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var problem by mutableStateOf<ConnectionProblem?>(null)
        private set

    /**
     * The models the PC can answer with, as its own picker sees them.
     *
     * Read on demand rather than on every poll: the catalog can be slow to build
     * — the host resolves each model's context window and modalities from its
     * LLM registry — and it does not change on its own.
     */
    var modelCatalog by mutableStateOf<ModelCatalog?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelPickerOpen by mutableStateOf(false)
        private set
    var modelError by mutableStateOf<TextRef?>(null)
        private set
    var switchingModel by mutableStateOf(false)
        private set

    /**
     * The model this app last set for the open chat.
     *
     * Held locally because the host reports a *deployment* default, not a
     * per-session choice: after a switch the two agree, but before one the only
     * honest label is "the PC's default".
     */
    var chosenModel by mutableStateOf<ModelSelection?>(null)
        private set

    /**
     * The permission presets this harness offers, and where the open chat stands.
     *
     * Fetched when a chat opens rather than polled: the catalog is fixed for the
     * process and the current preset only changes when somebody changes it, which
     * the app itself is the only remote party to do.
     */
    var permissionCatalog by mutableStateOf<PermissionCatalog?>(null)
        private set

    /** Whether the permission picker is open. */
    var permissionsOpen by mutableStateOf(false)
        private set

    /** Whether a preset switch is in flight. */
    var switchingPermission by mutableStateOf(false)
        private set

    /** Why the last permission read or switch failed. */
    var permissionError by mutableStateOf<TextRef?>(null)
        private set

    /**
     * The link whose preview sheet is open.
     *
     * The sheet's lifetime is this value: closing it forgets the content, and
     * opening another link replaces both — a reader never sees two previews at
     * once, and never the previous page under a new address.
     */
    var previewUrl by mutableStateOf<String?>(null)
        private set

    /** The preview the host served for [previewUrl]. */
    var preview by mutableStateOf<PreviewResponse?>(null)
        private set

    /** Whether the preview fetch is in flight. */
    var previewLoading by mutableStateOf(false)
        private set

    /** Why the last preview failed, in the reader's own words. */
    var previewError by mutableStateOf<TextRef?>(null)
        private set

    /**
     * The file whose sheet is open, exactly as the link wrote it.
     *
     * A link is a page or a file, and the two are different readers: the page's
     * sheet shows what the host's fetcher made of a URL, this one shows a file's
     * own text. Kept as the *reference* rather than a resolved path, because a
     * relative one only means something next to the chat it was printed in, and
     * the host is the side that knows where that is.
     */
    var fileRef by mutableStateOf<String?>(null)
        private set

    /** The file the host served for [fileRef]. */
    var file by mutableStateOf<HostFile?>(null)
        private set

    /** Whether the file read is in flight. */
    var fileLoading by mutableStateOf(false)
        private set

    /** Why the last file read failed, in the reader's own words. */
    var fileError by mutableStateOf<TextRef?>(null)
        private set

    /**
     * The delivery whose sheet is open, if any.
     *
     * Held as the *declaration* rather than as a path or as its bytes: what the
     * agent handed over is a claim about a file, the host is the side that
     * resolves it, and everything the sheet shows — the name, the resolved path,
     * the text, the download — comes from asking with these coordinates.
     */
    var deliveryRef by mutableStateOf<DeliveryRef?>(null)
        private set

    /** What the host found for [deliveryRef]. */
    var deliveryFile by mutableStateOf<DeliveredFile?>(null)
        private set

    /** Whether the host is still being asked about [deliveryRef]. */
    var deliveryLoading by mutableStateOf(false)
        private set

    /** Why the last delivery read failed, in the reader's own words. */
    var deliveryError by mutableStateOf<TextRef?>(null)
        private set

    /** The delivery action running right now, if one is. */
    var deliveryBusy by mutableStateOf<DeliveryBusy?>(null)
        private set

    /**
     * What the last action on each delivered file did.
     *
     * Keyed by the file, so a card row can report the outcome of the save that
     * was started from that row — and so a sheet opened afterwards still says
     * where the file went.
     */
    var deliveryNotices by mutableStateOf<Map<String, DeliveryNotice>>(emptyMap())
        private set

    /**
     * Approvals the harness is waiting on.
     *
     * Polled rather than streamed, like everything else here: an approval has to
     * survive a phone that slept through the moment it was raised, and a poll
     * answers "what is still waiting" instead of "what happened".
     */
    var approvals by mutableStateOf<List<ApprovalRequest>>(emptyList())
        private set

    /** The approval currently being answered, so its buttons can disable. */
    var answeringApproval by mutableStateOf<String?>(null)
        private set

    /**
     * Whether the host has already been found to lack the approval route.
     *
     * Held so the one useful message about it is shown once rather than on every
     * poll, and so a later success can clear it.
     */
    private var approvalsRouteMissing by mutableStateOf(false)

    /**
     * Questions the harness is waiting on.
     *
     * Polled with the rest, and for the same reason: a question has to survive a
     * phone that slept through the moment it was asked.
     */
    var pendingQuestions by mutableStateOf<List<PendingQuestion>>(emptyList())
        private set

    /** The question set currently being answered, so its options can disable. */
    var answeringQuestion by mutableStateOf<String?>(null)
        private set

    /** Whether the host has already been found to lack the question route. */
    private var questionsRouteMissing by mutableStateOf(false)

    /**
     * What this app just answered, keyed by call id when there is one.
     *
     * The tool's result carries the answers and reaches the phone on the next
     * poll; until then the card would fall back to offering choices the reader
     * has already made. Keyed by question id inside, because that is what an
     * answer is addressed to.
     */
    private val localAnswers = mutableStateMapOf<String, Map<String, List<String>>>()

    /**
     * Verdicts this app has just submitted, keyed by call id when there is one.
     *
     * The host records the outcome as a `approval/decided` event, but that only
     * reaches the phone on the next poll. Without this, the card would fall back
     * to "no longer waiting" for a second or two after the user tapped — telling
     * them their answer had been discarded at the exact moment it was accepted.
     */
    private val localVerdicts = mutableStateMapOf<String, String>()

    /**
     * Photos staged for the next message.
     *
     * They stay in memory as encoded bytes and ride along with the prompt,
     * because the harness admits inline image parts as real image content —
     * a pre-stored file reference would reach the model as an opaque handle.
     */
    val stagedPhotos = mutableStateListOf<OutgoingImage>()

    /**
     * Files staged for the next message, already stored on the host.
     *
     * The bytes are not here: they were committed when they were picked, and
     * what waits is the receipt that names them. A receipt belongs to one chat
     * and is spent by one message, so this list is cleared with the session.
     */
    val stagedFiles = mutableStateListOf<OutgoingFile>()

    /** Names of the files whose upload is still running, in pick order. */
    var uploadingFiles by mutableStateOf<List<String>>(emptyList())
        private set

    private var api: MobileApi? = null

    /**
     * The transcript state machine. Recreated when the pairing changes so a new
     * host never inherits the previous host's held window.
     */
    private var sync: ChatSync = ChatSync(noSource)

    private var listJob: Job? = null
    private var chatJob: Job? = null
    private var oldestSeq: Int? = null

    /**
     * Wrap a client as the sync machine's read side.
     *
     * @param client the API to read through, or null when unpaired.
     * @return a source that refuses with a transport failure when unpaired.
     */
    private fun sourceOf(client: MobileApi?): ChatSource = object : ChatSource {
        override suspend fun messages(
            sessionId: String,
            beforeSeq: Int?,
            afterSeq: Int?,
            limit: Int,
        ): MessagePage {
            val bound = client ?: throw ApiException("transport", "no host connected")
            return bound.messages(sessionId, beforeSeq = beforeSeq, afterSeq = afterSeq, limit = limit)
        }
    }

    init {
        if (store.isPaired) {
            val client = MobileApi(store.host!!, store.token)
            api = client
            sync = ChatSync(sourceOf(client))
            startSessionPolling()
        }
    }

    /** The base URL currently in use, for display in settings. */
    val pairedHost: String? get() = store.host

    /**
     * Point the app at a different address for the same harness.
     *
     * The token is not tied to an address: it names a device in the harness's own
     * table, and the PC can move (DHCP, a new router, a tailnet address) without
     * the pairing changing. Saving a new address is therefore a rebuild of the
     * client, not a new pairing — and it is what replaces "unpair and pair again"
     * as the way out of an address that stopped answering.
     *
     * @param raw the address as typed, with or without scheme and port.
     * @return false when the address is empty, so the dialog can stay open.
     */
    fun changeHost(raw: String): Boolean {
        val host = normalizeHost(raw)
        if (host.isBlank()) {
            problem = ConnectionProblem("host-required", TextRef.of(R.string.error_host_required))
            return false
        }
        store.host = host
        hostInput = host
        val token = store.token ?: return true
        val client = MobileApi(host, token)
        api = client
        sync = ChatSync(sourceOf(client))
        listFailures = 0
        chatFailures = 0
        entries = emptyList()
        oldestSeq = null
        hasOlder = false
        if (openSession != null) {
            pauseChatPolling()
            resumeChatPolling()
        }
        viewModelScope.launch { loadSessions() }
        return true
    }

    /** Whether the settings sheet is open. */
    var settingsOpen by mutableStateOf(false)
        private set

    /**
     * The phones paired with this PC, as the host lists them.
     *
     * Read when the settings sheet opens rather than on every poll: this is a
     * question you ask before lending the phone, not one that changes while you
     * watch.
     */
    var devices by mutableStateOf<List<PairedDevice>>(emptyList())
        private set
    var devicesLoading by mutableStateOf(false)
        private set
    var devicesError by mutableStateOf<TextRef?>(null)
        private set

    /** Read the paired devices for the settings sheet. */
    fun loadDevices() {
        val client = api ?: return
        if (devicesLoading) return
        devicesLoading = true
        viewModelScope.launch {
            try {
                devices = client.devices()
                devicesError = null
            } catch (error: ApiException) {
                // An older host has no device route at all; saying so is better
                // than an empty list that reads as "no phones paired".
                devicesError = if (error.status == 404) TextRef.of(R.string.devices_load_failed) else describe(error)
            } finally {
                devicesLoading = false
            }
        }
    }

    /**
     * Revoke one paired device.
     *
     * Revoking *this* device is how the app unpairs: the token is dead the moment
     * the host answers, so the phone detaches itself and says why.
     *
     * @param deviceId the id the device list reported.
     */
    fun revokeDevice(deviceId: String) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                val result = client.revokeDevice(deviceId)
                devices = devices.filterNot { it.id == deviceId }
                if (result.self) {
                    val host = hostInput
                    unpair()
                    hostInput = host
                    pairingError = TextRef.of(R.string.pairing_self_revoked)
                }
            } catch (error: ApiException) {
                devicesError = describe(error)
            }
        }
    }

    /**
     * Whether a row has something the reader has not seen.
     *
     * A chat that is open is never unread: the reader is looking at it, and marking
     * it on every poll would only make the dot disappear from the row behind the
     * one being read.
     *
     * @param session the row to ask about.
     */
    fun isUnread(session: SessionSummary): Boolean =
        session.id != openSession?.id && session.updatedAt > (seenAt[session.id] ?: 0L)

    /**
     * Record that the open chat has been read, using the host's own timestamp.
     *
     * Called after every list read rather than on every keystroke of state: the value
     * only means something once the host has told us what the newest `updatedAt` is,
     * and that arrives with the list.
     */
    private fun markOpenSeen() {
        val session = openSession ?: return
        val row = sessions.firstOrNull { it.id == session.id } ?: return
        if (row.updatedAt <= 0L || seenAt[session.id] == row.updatedAt) return
        val next = seenAt + (session.id to row.updatedAt)
        seenAt = next
        val now = System.currentTimeMillis()
        if (now - seenWriteAt > SEEN_WRITE_MS) {
            store.seenAt = next
            seenWriteAt = now
        }
    }

    /** The palette the app draws with, as the user chose it. */
    var themeMode by mutableStateOf(store.themeMode)
        private set

    /**
     * Whether approvals and full-access grants ask for the phone's lock first.
     *
     * A phone that is unlocked in someone's hand can approve a shell command and
     * grant *full access* in three taps; this is the one control that makes the
     * phone's own lock part of that decision.
     */
    var confirmActions by mutableStateOf(store.confirmActions)
        private set

    /**
     * Turn the lock confirmation on or off, and remember it.
     * @param enabled the new choice.
     */
    fun chooseConfirmActions(enabled: Boolean) {
        confirmActions = enabled
        store.confirmActions = enabled
    }

    /**
     * The language the app speaks, as the user chose it.
     *
     * Read once at construction rather than on every composition, because it is
     * only ever changed through [chooseLanguage], and that call rebuilds the
     * whole activity — a value that cannot go stale on screen does not need
     * watching.
     */
    var language by mutableStateOf(store.language)
        private set

    /** Open the settings sheet. */
    fun openSettings() {
        settingsOpen = true
    }

    /** Close the settings sheet. */
    fun closeSettings() {
        settingsOpen = false
    }

    /**
     * Choose the palette and remember it.
     *
     * Applied and persisted in the same call: the theme is the one setting whose
     * effect is immediate and visible, so a value that survived a restart but
     * did not repaint would be the worst of both.
     *
     * @param mode the chosen mode.
     */
    fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        store.themeMode = mode
    }

    /**
     * Choose the language, remember it, and say whether the app must be rebuilt.
     *
     * The store is written here and nowhere else, which is what makes the choice
     * survive the restart that applying it requires. Nothing is drawn
     * differently in this method: a `Context`'s resources cannot change language
     * once the context exists, so the new strings can only appear after the
     * activity is rebuilt on top of one that speaks them — and the rebuild is
     * the caller's, because only an activity can ask for it.
     *
     * @param mode the chosen language.
     * @return true when the activity has to be recreated to honour the choice.
     */
    fun chooseLanguage(mode: LanguageMode): Boolean {
        val choice = applyLanguage(store.language, mode)
        store.language = choice.mode
        language = choice.mode
        return choice.recreate
    }

    /**
     * Pair with the harness using the code shown in its log.
     */
    fun pair() {
        val host = normalizeHost(hostInput)
        if (host.isBlank()) {
            pairingError = TextRef.of(R.string.error_host_required)
            return
        }
        val code = codeInput.trim()
        if (code.isBlank()) {
            pairingError = TextRef.of(R.string.error_code_required)
            return
        }
        pairing = true
        pairingError = null
        viewModelScope.launch {
            try {
                val anonymous = MobileApi(host, null)
                val response = anonymous.pair(code, deviceInput.trim().ifBlank { store.deviceName })
                store.host = host
                store.token = response.token
                store.deviceName = deviceInput.trim().ifBlank { store.deviceName }
                val authed = MobileApi(host, response.token)
                api = authed
                sync = ChatSync(sourceOf(authed))
                problem = null
                codeInput = ""
                screen = Screen.Sessions
                startSessionPolling()
            } catch (error: ApiException) {
                pairingError = when (error.code) {
                    "pairing-refused" -> TextRef.of(R.string.error_pairing_refused)
                    "pairing-closed" -> TextRef.of(R.string.error_pairing_used)
                    "pairing-expired" -> TextRef.of(R.string.error_pairing_expired)
                    "rate-limited" -> TextRef.of(R.string.error_pairing_rate_limited)
                    "loopback-only" -> TextRef.of(R.string.error_pairing_used)
                    "transport" -> TextRef.of(R.string.error_pairing_unreachable, host)
                    else -> TextRef.of(error) ?: TextRef.of(R.string.error_pairing_failed)
                }
            } catch (error: Exception) {
                pairingError = TextRef.of(error) ?: TextRef.of(R.string.error_pairing_failed)
            } finally {
                pairing = false
            }
        }
    }

    /** Forget the current pairing and return to the pairing screen. */
    fun unpair() {
        pausePolling()
        pauseChatPolling()
        // A watch with no pairing has nothing to watch: the service would sit in a
        // reconnect loop against a host that now answers 401.
        WatchService.stop(getApplication())
        store.clear()
        api = null
        sync = ChatSync(noSource)
        sessions = emptyList()
        workspaces = emptyList()
        workspacesAvailable = false
        pickerOpen = false
        browsing = null
        folderDraft = ""
        entries = emptyList()
        openSession = null
        stagedPhotos.clear()
        stagedFiles.clear()
        uploadingFiles = emptyList()
        problem = null
        approvals = emptyList()
        answeringApproval = null
        localVerdicts.clear()
        modelCatalog = null
        modelPickerOpen = false
        modelError = null
        chosenModel = null
        permissionCatalog = null
        permissionsOpen = false
        permissionError = null
        screen = Screen.Pairing
    }

    /** Open a session and start following its transcript. */
    fun open(session: SessionSummary) {
        openSession = session
        sync.reset()
        entries = emptyList()
        // A delivery, a link and a file all belong to the chat they were tapped
        // in: their sheets are addressed to that session, so opening another chat
        // closes them rather than showing one chat's reader over another's log.
        closeDelivery()
        closeLink()
        closeFile()
        oldestSeq = null
        hasOlder = false
        running = session.running
        // A model choice belongs to the chat it was made in, so opening another
        // one must not carry the previous chat's label across.
        chosenModel = null
        modelError = null
        permissionCatalog = null
        permissionError = null
        // A staged file is addressed to the chat it was uploaded for: its
        // receipt is bound to that session and refused by any other, so
        // carrying it into a second chat would offer a send that cannot work.
        stagedFiles.clear()
        uploadingFiles = emptyList()
        // The chip in the app bar names the model that will answer, so the
        // catalog is fetched when a chat opens rather than only when the picker
        // does: an empty chip is the one thing that would make the model
        // selection look like it had not taken effect.
        if (modelCatalog == null) loadModels()
        viewModelScope.launch { loadPermissions(session.id) }
        screen = Screen.Chat
        pauseChatPolling()
        resumeChatPolling()
    }

    /** Close the open conversation and return to the list. */
    fun closeSession() {
        // Marked before the session is let go: after this line there is no open
        // chat to attribute the read to.
        markOpenSeen()
        store.seenAt = seenAt
        pauseChatPolling()
        openSession = null
        entries = emptyList()
        stagedPhotos.clear()
        stagedFiles.clear()
        closeDelivery()
        closeLink()
        closeFile()
        screen = Screen.Sessions
    }

    /**
     * Stop following the open conversation.
     *
     * The chat polls faster than the list, so it is the one worth pausing the
     * moment the app is not visible.
     */
    fun pauseChatPolling() {
        chatJob?.cancel()
        chatJob = null
    }

    /**
     * Resume following the open conversation, re-reading it first.
     *
     * The transcript may have moved while the app was away, so this reloads the
     * newest page instead of trying to catch up from the held cursor.
     */
    fun resumeChatPolling() {
        val session = openSession ?: return
        if (api == null || chatJob != null || !foreground) return
        chatJob = viewModelScope.launch {
            loadNewest()
            while (isActive) {
                delay(chatDelay())
                refresh()
            }
        }
    }

    /** Re-read the session list immediately. */
    fun refreshSessions() {
        viewModelScope.launch { loadSessions() }
    }

    /**
     * Create a session on the host and open it.
     *
     * @param cwd the PC directory the chat runs in, or null for the host's own
     *   default. A directory inside a project makes the new chat appear under
     *   that project both here and in the desktop sidebar.
     */
    fun newSession(cwd: String? = null) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                val created = client.createSession(cwd)
                loadSessions()
                val summary = sessions.firstOrNull { it.id == created.sessionId }
                    ?: SessionSummary(id = created.sessionId, title = blankSessionTitle(), cwd = cwd)
                open(summary)
            } catch (error: ApiException) {
                problem = ConnectionProblem(error.code, TextRef.of(error) ?: TextRef.of(R.string.error_no_host))
            }
        }
    }

    /**
     * The chat list split into its projects.
     *
     * Order is the host registry's own, except that projects with recent
     * activity lead: on the phone the question is "what was I just doing", not
     * "which project did I create first". Projects with no chat are still
     * listed — an empty project is exactly what a freshly browsed directory
     * produces, and it has to be reachable to start a chat in it.
     */
    val sessionGroups: List<SessionGroup>
        get() {
            val byWorkspace = sessions.filter { it.workspaceId != null }.groupBy { it.workspaceId }
            val ungrouped = sessions.filter { it.workspaceId == null }
            val groups = workspaces.map { workspace ->
                SessionGroup(
                    workspaceId = workspace.workspaceId,
                    workspace = workspace,
                    title = workspace.displayTitle,
                    path = workspace.path,
                    sessions = byWorkspace[workspace.workspaceId].orEmpty(),
                )
            }
            val ordered = groups.sortedByDescending { group -> group.sessions.maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE }
            if (ungrouped.isEmpty()) return ordered
            return ordered + SessionGroup(
                workspaceId = null,
                workspace = null,
                title = blankSectionTitle(),
                path = null,
                sessions = ungrouped,
            )
        }

    /** Open the new-chat picker, starting on whichever face is useful. */
    fun openPicker() {
        pickerError = null
        folderDraft = ""
        pickerTab = if (workspaces.isEmpty()) PickerTab.Browse else PickerTab.Recent
        pickerOpen = true
        if (pickerTab == PickerTab.Browse) openBrowse(null)
    }

    /** Close the new-chat picker, keeping the browsed level for next time. */
    fun closePicker() {
        pickerOpen = false
        pickerError = null
        folderDraft = ""
    }

    /** Track the folder name the user is typing in the picker. */
    fun editFolderDraft(value: String) {
        folderDraft = value
    }

    /** Switch the picker between the project list and the PC's folder tree. */
    fun pickerTab(tab: PickerTab) {
        pickerTab = tab
        pickerError = null
        if (tab == PickerTab.Browse && browsing == null) openBrowse(null)
    }

    /**
     * Refresh the PC's projects without waiting for the next poll.
     */
    fun refreshWorkspaces() {
        viewModelScope.launch { loadWorkspaces() }
    }

    /**
     * Fold one project's chats away, or open them again.
     *
     * The section key is what is remembered, not its position: the list is sorted
     * by recency and reordered by every turn on the PC, so a folded project that
     * was remembered by index would open the wrong one on the next poll. The same
     * key is what goes to disk, which is what makes the fold outlive the app.
     *
     * @param section the section whose heading was tapped, from [sectionKey].
     */
    fun toggleWorkspace(section: String) {
        val next = if (section in collapsedWorkspaces) {
            collapsedWorkspaces - section
        } else {
            collapsedWorkspaces + section
        }
        setFolds(next)
    }

    /**
     * Show every chat of one project, or go back to its newest few.
     *
     * @param section the section whose card was asked, from [sectionKey].
     */
    fun toggleExpandedWorkspace(section: String) {
        expandedWorkspaces = if (section in expandedWorkspaces) {
            expandedWorkspaces - section
        } else {
            expandedWorkspaces + section
        }
    }

    /**
     * Replace the folded set — on screen and on disk together.
     *
     * One function rather than two calls at each of the two places that change a
     * fold, because the two halves must not drift: a fold the screen honours and
     * the disk never hears about opens itself again at the next launch, which is
     * the exact bug the persistence is there to fix.
     *
     * A section that has just been folded is also dropped from the open cards: its
     * card is off screen, so the exception made to it is stale by definition, and
     * keeping it would unfold the whole project the next time the heading was
     * tapped — a fold that produces the thing the fold was hiding.
     *
     * @param next the folded set to both draw and remember.
     */
    private fun setFolds(next: Set<String>) {
        collapsedWorkspaces = next
        expandedWorkspaces = expandedWorkspaces - next
        store.collapsedWorkspaces = next
    }

    /**
     * Ask before removing one project from the list.
     *
     * @param workspace the project the tap named.
     */
    fun askRemoveWorkspace(workspace: Workspace) {
        workspaceToRemove = workspace
    }

    /** Dismiss the removal question, which is the safe answer. */
    fun cancelRemoveWorkspace() {
        if (removingWorkspace) return
        workspaceToRemove = null
    }

    /**
     * Remove the project the question named, and re-read both lists.
     *
     * The host's route unregisters the project and leaves the disk alone, so the
     * only thing that changes here is grouping: the chats come back ungrouped, and
     * the group that owned them disappears. Both lists are read again rather than
     * edited locally, because which chats lose their group is the host's answer.
     */
    fun confirmRemoveWorkspace() {
        val client = api ?: return
        val target = workspaceToRemove ?: return
        if (removingWorkspace) return
        removingWorkspace = true
        viewModelScope.launch {
            try {
                client.deleteWorkspace(target.workspaceId)
                setFolds(collapsedWorkspaces - sectionKey(target.workspaceId))
                workspaceToRemove = null
                loadWorkspaces()
                loadSessions()
            } catch (error: ApiException) {
                problem = ConnectionProblem(error.code, describeWorkspace(error))
                workspaceToRemove = null
            } finally {
                removingWorkspace = false
            }
        }
    }

    /**
     * Turn a removal failure into something readable on a phone.
     *
     * @param error the failure the host or transport reported.
     * @return the text shown in the banner.
     */
    private fun describeWorkspace(error: ApiException): TextRef = when {
        error.code == "not-found" || error.status == 404 -> TextRef.of(R.string.error_project_gone)
        error.code == "unavailable" -> TextRef.of(R.string.error_projects_unavailable)
        error.code == "transport" -> describe(error)
        else -> TextRef.of(R.string.error_project_remove_failed, error.message.orEmpty())
    }

    /**
     * Descend into one folder of the PC, or start from the host account's home.
     *
     * @param path an absolute host path already reported by the host, or null.
     */
    fun openBrowse(path: String?) {
        val client = api ?: return
        val request = ++browseRequest
        browsingLoading = true
        pickerError = null
        viewModelScope.launch {
            try {
                val level = client.browse(path)
                if (request == browseRequest) browsing = level
            } catch (error: ApiException) {
                if (request == browseRequest) pickerError = browseMessage(error)
            } finally {
                if (request == browseRequest) browsingLoading = false
            }
        }
    }

    /**
     * Step up one level, as the host's own ancestry reports it.
     *
     * The decision lives in [browseParent] so it can be checked without a phone:
     * it is the whole of whether this does anything, and the version that took
     * "the crumb above the current one" was reading the leading drive shortcuts
     * as parents — on a volume root it stepped to a *different* volume, or
     * nowhere at all.
     */
    fun browseUp() {
        browseParent(browsing)?.let { openBrowse(it) }
    }

    /**
     * Create a folder inside the level the picker is showing and descend into it.
     *
     * The name is taken from the field rather than passed in, so a refusal leaves
     * it where the user typed it instead of emptying the field they have to retry.
     */
    fun createFolder() {
        val client = api ?: return
        val listing = browsing ?: return
        val name = folderDraft.trim()
        if (name.isEmpty()) {
            pickerError = TextRef.of(R.string.error_folder_name_required)
            return
        }
        if (pickerBusy) return
        pickerBusy = true
        pickerError = null
        viewModelScope.launch {
            try {
                val after = client.createDirectory(listing.path, name)
                // The answer is the *parent's* refreshed level, so drawing it
                // would leave the picker one step above the folder just made —
                // and the next tap would start the chat there. Enter the child,
                // falling back to the parent when it cannot be read back: the
                // folder exists either way, and that level already lists it.
                val created = createdDirectory(after)
                browsing = if (created == null) after else (enter(client, created) ?: after)
                folderDraft = ""
            } catch (error: ApiException) {
                pickerError = browseMessage(error)
            } finally {
                pickerBusy = false
            }
        }
    }

    /**
     * The browse pane's primary action: start a chat in the level on screen, or —
     * when a folder name is typed — in the folder that name creates.
     *
     * Both live on one button because that is how the screen reads: the field sits
     * an inch above it, and a name typed there is meant for the chat that is about
     * to start, not for a second tap on the small create icon.
     */
    fun startChatHere() {
        val client = api ?: return
        val intent = browseIntent(browsing, folderDraft) ?: return
        if (pickerBusy) return
        pickerBusy = true
        pickerError = null
        viewModelScope.launch {
            try {
                val target = when (intent) {
                    is BrowseIntent.Here -> intent.path
                    // A folder by that name is already there, and asking for a
                    // chat in it is what this tap means either way.
                    is BrowseIntent.EnterExisting -> intent.path
                    is BrowseIntent.CreateThenHere -> {
                        val after = client.createDirectory(intent.parent, intent.name)
                        val created = createdDirectory(after)
                        // Leave the picker inside the new folder, so reopening it
                        // shows the place the chat was started in.
                        browsing = if (created == null) after else (enter(client, created) ?: after)
                        folderDraft = ""
                        created ?: intent.parent
                    }
                }
                adoptAndOpen(client, target)
            } catch (error: ApiException) {
                pickerError = browseMessage(error)
            } finally {
                pickerBusy = false
            }
        }
    }

    /**
     * Adopt a PC directory as a project and start a chat in it.
     *
     * The adoption is idempotent on the host, so choosing a directory that
     * already has a project reuses it instead of failing.
     *
     * @param path an absolute host directory the host itself reported.
     */
    fun startChatIn(path: String) {
        val client = api ?: return
        if (pickerBusy) return
        val chosen = path.trim()
        if (chosen.isEmpty()) return
        pickerBusy = true
        pickerError = null
        viewModelScope.launch {
            try {
                adoptAndOpen(client, chosen)
            } catch (error: ApiException) {
                pickerError = browseMessage(error)
            } finally {
                pickerBusy = false
            }
        }
    }

    /**
     * Read the level of a directory the host has just reported, or null when it
     * cannot be read back.
     *
     * A create's own answer is the parent's level, and a level that cannot be
     * entered is not worth an error banner when the caller already holds one that
     * shows the new folder.
     *
     * @param client the host client.
     * @param path an absolute directory the host itself reported.
     */
    private suspend fun enter(client: MobileApi, path: String): DirListing? = try {
        client.browse(path)
    } catch (error: ApiException) {
        Log.w(TAG, "cannot enter ${path}: ${error.code}")
        null
    }

    /**
     * Adopt a directory as a project and open the chat created inside it.
     *
     * @param client the host client.
     * @param path an absolute host directory.
     */
    private suspend fun adoptAndOpen(client: MobileApi, path: String) {
        val workspace = client.createWorkspace(path)
        loadWorkspaces()
        // Create *by project*, not by directory: the host then records
        // the chat in the project's account, so it shows up in the
        // desktop sidebar too instead of only on the phone.
        val created = client.createSession(cwd = workspace.path.ifBlank { path }, workspaceId = workspace.workspaceId)
        pickerOpen = false
        folderDraft = ""
        loadSessions()
        val summary = sessions.firstOrNull { it.id == created.sessionId }
            ?: SessionSummary(
                id = created.sessionId,
                title = blankSessionTitle(),
                cwd = workspace.path,
                workspaceId = workspace.workspaceId,
                workspaceTitle = workspace.title,
            )
        open(summary)
    }

    /**
     * Turn a browse failure into something readable on a phone.
     * @param error the failure the host or transport reported.
     * @return the text shown in the picker.
     */
    private fun browseMessage(error: ApiException): TextRef = when (error.code) {
        "directory-unreadable" -> TextRef.of(R.string.error_folder_unreadable)
        "directory-exists" -> TextRef.of(R.string.error_folder_exists)
        "directory-create-failed" -> TextRef.of(R.string.error_folder_create_failed, error.message.orEmpty())
        "workspace/invalid-path" -> TextRef.of(R.string.error_folder_invalid)
        "unavailable" -> TextRef.of(R.string.error_projects_unavailable)
        "transport" -> TextRef.of(R.string.error_transport_short)
        "unauthorized" -> TextRef.of(R.string.error_unauthorized)
        // The host prepares a session's directory with a recursive mkdir, and on
        // Windows that call fails on a unit root — `EPERM … mkdir 'K:\'` — which
        // is the one place this picker can offer that the host cannot run a chat
        // in. Its own words say what went wrong and nothing about what to do, so
        // they are replaced rather than shown.
        else -> when {
            error.message.contains("failed to ensure project directory") ->
                TextRef.of(R.string.error_folder_drive_root)
            error.message.contains("is not a single directory name") ->
                TextRef.of(R.string.error_folder_illegal_name)
            else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
        }
    }

    /**
     * Send the open session's staged photos, staged files, and composed text.
     *
     * The text lives in [draft] and is cleared only once the host has accepted the
     * message: the composer used to clear its own copy the instant the tap
     * happened, so a refusal answered a second later — `writer-held`, a link that
     * dropped — cost the user every word they had just written. [sending] keeps a
     * second tap from re-posting receipts the first tap already spent.
     *
     * @param steer true to steer the running turn instead of queueing.
     */
    fun sendStaged(steer: Boolean) {
        val client = api ?: return
        val session = openSession ?: return
        if (sending) {
            Log.i(TAG, "send ignored: one is already in flight")
            return
        }
        val photos = stagedPhotos.toList()
        val files = stagedFiles.toList()
        val body = draft.trim()
        if (body.isEmpty() && photos.isEmpty() && files.isEmpty()) {
            Log.i(TAG, "send ignored: nothing to send")
            return
        }
        if (uploadingFiles.isNotEmpty()) {
            problem = ConnectionProblem("file-uploading", TextRef.of(R.string.error_file_uploading))
            return
        }
        sending = true
        Log.i(TAG, "sending textChars=${body.length} photos=${photos.size} files=${files.size} steer=$steer session=${session.id}")
        viewModelScope.launch {
            try {
                client.send(session.id, body, images = photos, files = files, steer = steer)
                Log.i(TAG, "send accepted by host")
                // The host has it: only now may the composer forget it.
                draft = ""
                stagedPhotos.clear()
                stagedFiles.clear()
                delay(250)
                refresh()
            } catch (error: ApiException) {
                Log.w(TAG, "send failed code=${error.code} status=${error.status} message=${error.message}")
                problem = ConnectionProblem(
                    error.code,
                    when {
                        // The harness grants one writer per session. When the
                        // desktop GUI holds it, the phone cannot append, and the
                        // user needs to know why rather than seeing a raw error.
                        error.code.endsWith("writer-held") ->
                            TextRef.of(R.string.error_writer_held)
                        error.code.endsWith("agent-busy") -> TextRef.of(R.string.error_agent_busy)
                        else -> describe(error)
                    },
                )
            } catch (error: Exception) {
                Log.w(TAG, "send failed with an unexpected error", error)
                problem = ConnectionProblem("internal", TextRef.of(error) ?: TextRef.of(R.string.error_send_failed))
            } finally {
                sending = false
            }
        }
    }

    /**
     * Build the authorized raw-image URL for one transcript attachment.
     *
     * @param sessionId the session that references the image.
     * @param attachmentId the durable attachment id.
     */
    fun imageUrl(sessionId: String, attachmentId: String): String =
        api?.imageUrl(sessionId, attachmentId) ?: ""

    /** The bearer token, so the image loader can authorize its requests. */
    fun bearerToken(): String? = store.token

    /**
     * Stage a photo for the next message.
     *
     * @param name the display name of the picked file.
     * @param mediaType the sniffed image media type.
     * @param base64 canonical base64 bytes.
     */
    fun stagePhoto(name: String, mediaType: String, base64: String) {
        if (stagedPhotos.size >= MAX_STAGED_PHOTOS) {
            onPhotoRejected(TextRef.of(R.string.error_too_many_photos, MAX_STAGED_PHOTOS))
            return
        }
        stagedPhotos.add(OutgoingImage(base64 = base64, mediaType = mediaType, name = name))
        Log.i(TAG, "staged photo name=$name type=$mediaType base64Chars=${base64.length} staged=${stagedPhotos.size}")
    }

    /**
     * Remove one staged photo.
     *
     * @param index its position in the staged list.
     */
    fun unstagePhoto(index: Int) {
        if (index in stagedPhotos.indices) stagedPhotos.removeAt(index)
    }

    /**
     * Surface a photo that could not be staged, without leaving the composer.
     *
     * @param message the reason shown in the problem banner.
     */
    fun onPhotoRejected(message: TextRef) {
        problem = ConnectionProblem("photo-rejected", message)
    }

    /**
     * Upload one picked file for the open chat and stage its receipt.
     *
     * The upload starts here rather than at send time because the harness
     * requires the bytes to be stored before a prompt can name them: what the
     * message carries is a receipt, and a receipt exists only after the store
     * answered. It also puts the slow part where it can be seen — the chip
     * shows the file while it is still travelling.
     *
     * @param name the display name the picker reported.
     * @param bytes the exact file bytes.
     */
    fun stageFile(name: String, bytes: ByteArray) {
        val client = api ?: return
        val session = openSession ?: return
        if (stagedFiles.size + uploadingFiles.size >= MAX_STAGED_FILES) {
            onFileRejected(TextRef.of(R.string.error_too_many_files, MAX_STAGED_FILES))
            return
        }
        uploadingFiles = uploadingFiles + name
        Log.i(TAG, "uploading file name=$name bytes=${bytes.size} session=${session.id}")
        viewModelScope.launch {
            try {
                val stored = client.uploadFile(session.id, name, bytes)
                // The store's own leaf name is what the chip shows: it is the
                // name the model will be told, separators and all removed.
                stagedFiles.add(
                    OutgoingFile(
                        receiptId = stored.receiptId,
                        name = attachmentName(stored.name.ifBlank { name }),
                        bytes = if (stored.bytes > 0) stored.bytes else bytes.size.toLong(),
                    ),
                )
                Log.i(TAG, "staged file name=${stored.name} bytes=${stored.bytes} staged=${stagedFiles.size}")
            } catch (error: ApiException) {
                Log.w(TAG, "file upload failed code=${error.code} status=${error.status} message=${error.message}")
                onFileRejected(fileMessage(error))
            } finally {
                uploadingFiles = uploadingFiles - name
            }
        }
    }

    /**
     * Remove one staged file.
     * @param index its position in the staged list.
     */
    fun unstageFile(index: Int) {
        if (index in stagedFiles.indices) stagedFiles.removeAt(index)
    }

    /**
     * Surface a file that could not be attached, without leaving the composer.
     * @param message the reason shown in the problem banner.
     */
    fun onFileRejected(message: TextRef) {
        problem = ConnectionProblem("file-rejected", message)
    }

    /**
     * Turn a staging failure into something readable on a phone.
     * @param error the failure the host or transport reported.
     */
    private fun fileMessage(error: ApiException): TextRef = when {
        error.code == "file-too-large" -> TextRef.of(R.string.error_file_too_large)
        // The receipt belongs to one chat and to the agent that was live when it
        // was minted, so a chat the PC no longer holds cannot take its files.
        error.code.endsWith("attachment-invalid") -> TextRef.of(R.string.error_receipt_stale)
        error.code.endsWith("not-found") -> TextRef.of(R.string.error_chat_closed)
        error.code == "unavailable" -> TextRef.of(R.string.error_files_unavailable)
        error.code == "transport" -> TextRef.of(R.string.error_transport_short)
        else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
    }

    /** Pull one older page into the transcript. */
    fun loadOlder() {
        val client = api ?: return
        val session = openSession ?: return
        val before = oldestSeq ?: return
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                // The host answers `nextBeforeSeq` even for an empty page, because
                // the newest window can be all bookkeeping: without a cursor from
                // it, the history before that window would be unreachable for good.
                var cursor = before
                var page = client.messages(session.id, beforeSeq = cursor, limit = 40)
                if (page.entries.isEmpty() && page.hasMore && page.nextBeforeSeq != null && page.nextBeforeSeq != cursor) {
                    cursor = page.nextBeforeSeq
                    page = client.messages(session.id, beforeSeq = cursor, limit = 40)
                }
                val older = page.entries
                if (older.isNotEmpty()) {
                    sync.replaceWindow(
                        next = older + sync.entries,
                        oldestOverride = older.first().seq,
                        hasOlder = page.hasMore,
                    )
                    applySync()
                } else {
                    // Tell the state machine, not just the visible copy: the next
                    // poll writes this value back from it, which is why a missing
                    // page used to reappear as a button every few seconds.
                    sync.markNoOlder()
                    applySync()
                }
            } catch (error: ApiException) {
                problem = ConnectionProblem(error.code, describe(error))
            } finally {
                loadingOlder = false
            }
        }
    }

    /**
     * Turn a transport or host failure into something a person can act on.
     *
     * OkHttp's own words — `failed to connect to /100.64.0.1 (port 4380) from
     * /192.168.1.31 (port 57688) after 10000ms` — name an address, an ephemeral
     * local port and a timeout. The reader can use none of the three. What they
     * need is which address was tried and what usually causes the failure, and
     * that is the one thing the exception cannot say.
     *
     * @param error the failure the client reported.
     * @return the sentence shown in the banner.
     */
    private fun describe(error: ApiException): TextRef = when (error.code) {
        "transport" -> TextRef.of(
            R.string.error_connection,
            store.host ?: localized().getString(R.string.error_nowhere),
        )
        "unauthorized" -> TextRef.of(R.string.error_unauthorized)
        else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
    }

    /** Dismiss the current connection problem banner. */
    fun dismissProblem() {
        problem = null
    }

    /**
     * The model label to show for the open chat.
     *
     * A choice made here wins; otherwise the PC's own default is the honest
     * answer, because that is what the next request will actually use.
     */
    val currentModel: ModelSelection?
        get() = chosenModel ?: modelCatalog?.default

    /**
     * When the running turn began, taken from the log's own turn marker.
     *
     * The harness records a `turn/start` event with its timestamp, so the
     * elapsed time the indicator shows is the host's number rather than one this
     * screen invented when it started watching. A host and a phone can disagree
     * about the clock, though, and a fabricated "20000 hours" is worse than no
     * number at all — so an implausible span reports nothing.
     */
    val turnStartedAt: Long?
        get() {
            if (!running) return null
            val started = entries.lastOrNull { it.kind == "turn-start" }?.time ?: return null
            val elapsed = System.currentTimeMillis() - started
            return started.takeIf { elapsed in 0..MAX_PLAUSIBLE_TURN_MS }
        }

    /**
     * What this app answered for one approval, before the host's log catches up.
     *
     * @param callId the approval's call id, when it has one.
     * @param approvalId the harness's own approval id.
     * @return the verdict this app submitted, or null.
     */
    fun localVerdict(callId: String?, approvalId: String?): String? {
        callId?.let { localVerdicts[it]?.let { verdict -> return verdict } }
        approvalId?.let { localVerdicts[it]?.let { verdict -> return verdict } }
        return null
    }

    /** Open the model picker, loading the catalog the first time. */
    fun openModelPicker() {
        modelError = null
        modelPickerOpen = true
        if (modelCatalog == null) loadModels()
    }

    /** Close the model picker without choosing. */
    fun closeModelPicker() {
        modelPickerOpen = false
        modelError = null
    }

    /** Re-read the model catalog, for a refresh control. */
    fun refreshModels() {
        loadModels()
    }

    /**
     * Read the host's model catalog once.
     *
     * A failure is shown inside the picker rather than as a global banner: the
     * picker is where the user asked the question, and a catalog that cannot be
     * read does not make the rest of the app unusable.
     */
    private fun loadModels() {
        val client = api ?: return
        if (modelsLoading) return
        modelsLoading = true
        viewModelScope.launch {
            try {
                modelCatalog = client.models()
                modelError = null
            } catch (error: ApiException) {
                modelError = when (error.code) {
                    "transport" -> TextRef.of(R.string.error_transport_short)
                    "unauthorized" -> TextRef.of(R.string.error_unauthorized)
                    // The likeliest cause by far: the plugin file is new but the
                    // harness read it once at boot, so the route does not exist
                    // yet. Saying so beats showing the protocol's own words.
                    "not-found", "http-404" ->
                        TextRef.of(R.string.error_models_route_missing)
                    "unavailable" -> TextRef.of(R.string.error_models_unavailable)
                    else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
                }
            } finally {
                modelsLoading = false
            }
        }
    }

    /**
     * Point the open chat at one model.
     *
     * The host applies the change to the next request and records it as the
     * deployment default as well — the same thing the desktop picker does — so
     * the picker says so instead of pretending the choice is private to the
     * phone.
     *
     * @param model the chosen catalog row.
     * @param reasoningEffort the effort to use, or null to leave it to the host.
     */
    fun chooseModel(model: ModelInfo, reasoningEffort: String?) {
        val client = api ?: return
        val sessionId = openSession?.id
        if (sessionId == null) {
            modelError = TextRef.of(R.string.error_model_needs_chat)
            return
        }
        if (switchingModel) return
        switchingModel = true
        modelError = null
        viewModelScope.launch {
            try {
                chosenModel = client.selectModel(sessionId, model.provider, model.id, reasoningEffort)
                modelPickerOpen = false
                // The switch is written as the deployment default too, so the
                // catalog's own idea of "current" has just changed.
                modelCatalog = runCatching { client.models() }.getOrNull() ?: modelCatalog
            } catch (error: ApiException) {
                modelError = when (error.code) {
                    "session/model-unavailable" ->
                        TextRef.of(R.string.error_model_unavailable)
                    "session/writer-held" ->
                        TextRef.of(R.string.error_model_writer_held)
                    "transport" -> TextRef.of(R.string.error_model_transport)
                    "unauthorized" -> TextRef.of(R.string.error_unauthorized)
                    else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
                }
            } finally {
                switchingModel = false
            }
        }
    }

    /** The approvals waiting on one chat, newest first. */
    fun approvalsFor(sessionId: String?): List<ApprovalRequest> =
        if (sessionId == null) emptyList() else approvals.filter { it.sessionId == sessionId }

    /** The questions waiting on one chat. */
    fun questionsFor(sessionId: String?): List<PendingQuestion> =
        if (sessionId == null) emptyList() else pendingQuestions.filter { it.sessionId == sessionId }

    /**
     * The permission preset one chat is running under.
     *
     * @param sessionId the chat.
     * @returns the option the host reported, or null when it reported none.
     */
    fun permissionFor(sessionId: String?): PermissionOption? {
        val catalog = permissionCatalog ?: return null
        if (sessionId == null || catalog.sessionId != sessionId) return null
        return catalog.optionFor(catalog.current)
    }

    /** Open the permission picker. */
    fun openPermissions() {
        permissionError = null
        permissionsOpen = true
        viewModelScope.launch { loadPermissions(openSession?.id, force = true) }
    }

    /** Close the permission picker. */
    fun closePermissions() {
        permissionsOpen = false
    }

    /**
     * Switch the open chat to another permission preset.
     *
     * This is the one control in the app that changes what the agent is *allowed*
     * to do rather than what it is doing, so the failures are worth separating:
     * the host can refuse a preset outright (Auto runs an admission check), and a
     * refusal is not the same as a switch that did not arrive.
     *
     * @param preset the option's `value` from the host's catalog.
     */
    fun choosePermission(preset: String) {
        val client = api ?: return
        val session = openSession ?: return
        if (switchingPermission) return
        val option = permissionCatalog?.optionFor(preset)
        if (option == null) {
            permissionError = TextRef.of(R.string.error_permission_unknown, preset)
            return
        }
        switchingPermission = true
        viewModelScope.launch {
            try {
                val selection = client.selectPermission(session.id, preset)
                permissionCatalog = permissionCatalog?.copy(sessionId = session.id, current = selection.current)
                permissionError = null
                permissionsOpen = false
            } catch (error: ApiException) {
                permissionError = when (error.code) {
                    "permission/no-session" ->
                        TextRef.of(R.string.error_permission_session_gone)
                    "permission/unavailable" ->
                        TextRef.of(R.string.error_permission_unavailable)
                    "permission/refused" -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
                    "transport" -> describe(error)
                    else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
                }
            } finally {
                switchingPermission = false
            }
        }
    }

    /**
     * Read the permission catalog and the open chat's preset.
     *
     * Failures are swallowed into [permissionError] rather than the shared problem
     * banner: an old harness without the route must not put a warning over the
     * conversation, and the picker is the only place this matters.
     *
     * @param sessionId the chat to report the current preset for.
     * @param force reread even when the catalog already describes this session.
     */
    private suspend fun loadPermissions(sessionId: String?, force: Boolean = false) {
        val client = api ?: return
        if (!force && permissionCatalog?.sessionId == sessionId && sessionId != null) return
        try {
            permissionCatalog = client.permissions(sessionId)
            permissionError = null
        } catch (error: ApiException) {
            permissionError = when {
                error.code == "permission/unavailable" -> TextRef.of(R.string.error_permission_unavailable)
                error.code == "not-found" || error.status == 404 ->
                    TextRef.of(R.string.error_permissions_route_missing)
                error.code == "transport" -> describe(error)
                else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
            }
        }
    }

    /**
     * Open one tapped link, whichever of the two things it is.
     *
     * A link in a reply is not always an address: an agent that just wrote a file
     * links it the same way it links a page, and `[LORE-bozza.md](LORE-bozza.md)`
     * sent to the web reader answers *not a valid URL* for a file sitting on the
     * harness's own disk. [classifyLink] decides which reader is meant, and a
     * blank link is ignored rather than refused: links arrive inside model
     * output, and a malformed one is not worth a reader's attention.
     *
     * @param raw the link's target, as the author of the message wrote it.
     */
    fun openLink(raw: String) {
        when (val target = classifyLink(raw)) {
            null -> return
            is LinkTarget.Web -> openPreview(target.url)
            is LinkTarget.File -> openFile(target.reference)
        }
    }

    /**
     * Open one address in the reading sheet.
     *
     * @param url the address to preview.
     */
    private fun openPreview(url: String) {
        if (previewUrl == url && (previewLoading || preview != null)) return
        closeFile()
        closeDelivery()
        previewUrl = url
        viewModelScope.launch { loadPreview(url) }
    }

    /** Close the preview sheet, forgetting what it showed. */
    fun closeLink() {
        previewUrl = null
        preview = null
        previewError = null
        previewLoading = false
    }

    /** Ask for the open link again. */
    fun retryPreview() {
        val target = previewUrl ?: return
        viewModelScope.launch { loadPreview(target) }
    }

    /**
     * Open one file the agent linked, read from the PC's own disk.
     *
     * @param reference the reference as written: an absolute path, a `file:` URL,
     *   or a name relative to the chat on screen.
     */
    private fun openFile(reference: String) {
        if (fileRef == reference && (fileLoading || file != null)) return
        closeLink()
        closeDelivery()
        fileRef = reference
        viewModelScope.launch { loadFile(reference) }
    }

    /** Close the file sheet, forgetting what it showed. */
    fun closeFile() {
        fileRef = null
        file = null
        fileError = null
        fileLoading = false
    }

    /** Ask for the open file again. */
    fun retryFile() {
        val target = fileRef ?: return
        viewModelScope.launch { loadFile(target) }
    }

    /**
     * Read one file through the host and keep the result for the sheet.
     *
     * The chat on screen travels with the request because it is what a relative
     * reference is relative *to*. Each failure keeps its own sentence, for the
     * same reason the preview's do: "the file is not there" and "this harness
     * predates the route" call for different next steps.
     *
     * @param reference the file reference to read.
     */
    private suspend fun loadFile(reference: String) {
        val client = api ?: return
        fileLoading = true
        fileError = null
        file = null
        try {
            file = client.file(reference, openSession?.id)
        } catch (error: ApiException) {
            fileError = when {
                error.code == "file/not-found" ->
                    TextRef.of(R.string.error_file_gone, error.message.orEmpty())
                error.code == "file/not-a-file" ->
                    TextRef.of(R.string.error_path_is_folder)
                error.code == "file/binary" ->
                    TextRef.of(R.string.error_not_text)
                error.code == "file/no-session" ->
                    TextRef.of(R.string.error_relative_no_session)
                error.code == "file/invalid" ->
                    TextRef.of(R.string.error_path_refused, error.message.orEmpty())
                error.code == "not-found" || error.status == 404 ->
                    TextRef.of(R.string.error_files_route_missing)
                error.code == "transport" -> describe(error)
                else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
            }
        } finally {
            fileLoading = false
        }
    }

    /**
     * Open one file an agent declared as delivered.
     *
     * A delivery is not a link: the agent did not merely mention a file, it said
     * *this is what I made for you*. The tap therefore asks the host what that
     * declaration resolves to — name, absolute path, size, and whether there is
     * any text to read — and the sheet decides from the answer whether to show
     * the file or to offer to hand it to another app.
     *
     * @param sessionId the chat on screen, which is what a relative declaration
     *   is relative to.
     * @param seq the sequence of the delivery event.
     * @param declared the declared file, whose index is part of the address.
     */
    fun openDelivery(sessionId: String?, seq: Int, declared: DeclaredFile) {
        val chat = sessionId ?: return
        val open = deliveryRef
        if (open != null && open.sessionId == chat && open.seq == seq && open.declared.index == declared.index &&
            (deliveryLoading || deliveryFile != null)
        ) {
            return
        }
        closeLink()
        closeFile()
        deliveryRef = DeliveryRef(chat, seq, declared)
        viewModelScope.launch { loadDelivery() }
    }

    /** Close the delivery sheet, forgetting what it showed. */
    fun closeDelivery() {
        deliveryRef = null
        deliveryFile = null
        deliveryError = null
        deliveryLoading = false
    }

    /** Ask the host about the open delivery again. */
    fun retryDelivery() {
        if (deliveryRef == null) return
        viewModelScope.launch { loadDelivery() }
    }

    /**
     * Read one delivery's description through the host.
     *
     * Each failure keeps its own sentence, like the file reader's, because each
     * suggests a different next step: a delivery the log no longer reaches is
     * not the same news as a file the PC has since moved, and neither is the
     * same as a harness whose plugin predates the route.
     */
    private suspend fun loadDelivery() {
        val client = api ?: return
        val ref = deliveryRef ?: return
        deliveryLoading = true
        deliveryError = null
        deliveryFile = null
        try {
            deliveryFile = client.delivery(ref.sessionId, ref.seq, ref.declared.index)
        } catch (error: ApiException) {
            deliveryError = deliveryMessage(error)
        } finally {
            deliveryLoading = false
        }
    }

    /**
     * Stream one delivered file's bytes into a stream the caller owns.
     *
     * Kept on the view model rather than in the screen because the two things it
     * needs — the live token and the open client — live here, and the caller
     * (saving to Download, opening with another app, sharing) differs only in
     * what the stream is.
     *
     * @param sessionId the chat the delivery belongs to.
     * @param seq the sequence of the delivery event.
     * @param index the file's position in that event.
     * @param into receives the response body, on a background dispatcher.
     */
    suspend fun streamDelivery(sessionId: String, seq: Int, index: Int, into: (java.io.InputStream) -> Unit) {
        val client = api ?: throw ApiException("no-host", "no PC connected")
        client.downloadDelivery(sessionId, seq, index, into)
    }

    /** Record that one delivery action started, so the card can show it. */
    fun startDeliveryAction(sessionId: String, seq: Int, index: Int, action: String) {
        deliveryBusy = DeliveryBusy(sessionId, seq, index, action)
    }

    /** Record that the running delivery action is over. */
    fun endDeliveryAction() {
        deliveryBusy = null
    }

    /**
     * Keep what one delivery action did, against the file it did it to.
     *
     * @param sessionId the chat the delivery belongs to.
     * @param seq the sequence of the delivery event.
     * @param index the file's position in that event.
     * @param text the sentence shown beside the file.
     * @param ok whether it worked, which decides how it is painted.
     */
    fun noteDelivery(sessionId: String, seq: Int, index: Int, text: String, ok: Boolean) {
        deliveryNotices = deliveryNotices + (deliveryKey(sessionId, seq, index) to DeliveryNotice(text, ok))
    }

    /**
     * Turn a delivery failure into something readable on a phone.
     *
     * @param error the failure the host or the transport reported.
     */
    fun deliveryMessage(error: ApiException): TextRef = when {
        error.code == "delivery/not-found" ->
            TextRef.of(R.string.error_delivery_gone)
        error.code == "file/not-found" ->
            TextRef.of(R.string.error_delivered_file_gone, error.message.orEmpty())
        error.code == "file/not-a-file" ->
            TextRef.of(R.string.error_delivery_is_folder)
        error.code == "file/too-large" ->
            TextRef.of(R.string.error_delivery_too_large)
        error.code == "file/no-session" || error.code == "file/invalid" ->
            TextRef.of(R.string.error_delivery_no_path, error.message.orEmpty())
        error.code == "not-found" || error.status == 404 ->
            TextRef.of(R.string.error_deliveries_route_missing)
        error.code == "transport" -> describe(error)
        else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
    }

    /**
     * Read one link through the host and keep the result for the sheet.
     *
     * A failed preview is not the app's failure: the host refused the address,
     * or the page is not text, or the plugin predates the route. Each keeps its
     * own sentence because each suggests a different next step — retrying a
     * timeout makes sense, retrying an image does not.
     *
     * @param url the link to read.
     */
    private suspend fun loadPreview(url: String) {
        val client = api ?: return
        previewLoading = true
        previewError = null
        preview = null
        try {
            preview = client.preview(url)
        } catch (error: ApiException) {
            previewError = when {
                error.code == "preview/blocked" ->
                    TextRef.of(R.string.error_preview_blocked, error.message.orEmpty())
                error.code == "preview/timeout" ->
                    TextRef.of(R.string.error_preview_timeout)
                error.code == "preview/unsupported" ->
                    TextRef.of(R.string.error_preview_unsupported)
                error.code == "preview/too-large" ->
                    TextRef.of(R.string.error_preview_too_large)
                error.code == "preview/unavailable" ->
                    TextRef.of(R.string.error_preview_unavailable)
                error.code == "not-found" || error.status == 404 ->
                    TextRef.of(R.string.error_previews_route_missing)
                error.code == "transport" -> describe(error)
                else -> TextRef.of(error) ?: TextRef.of(R.string.error_no_host)
            }
        } finally {
            previewLoading = false
        }
    }

    /**
     * What this app answered for one question set, before the host's log catches up.
     *
     * @param callId the question's tool call id, when it has one.
     * @param questionId the harness's own question-set id.
     * @return the chosen labels, or null.
     */
    fun localAnswer(callId: String?, questionId: String?): Map<String, List<String>>? {
        callId?.let { localAnswers[it]?.let { chosen -> return chosen } }
        questionId?.let { localAnswers[it]?.let { chosen -> return chosen } }
        return null
    }

    /**
     * Answer one question set on the harness's behalf.
     *
     * The answer is keyed by each question's own id and carries the option
     * **labels** the author wrote, which is what the harness matched its options
     * by — an index would answer a different question the moment a model reordered
     * its list.
     *
     * @param pending the question set being answered.
     * @param answers one entry per question.
     */
    fun answerQuestion(pending: PendingQuestion, answers: List<QuestionAnswer>) {
        val client = api ?: return
        if (answeringQuestion != null) return
        if (answers.none { !it.isEmpty }) {
            problem = ConnectionProblem("question/empty", TextRef.of(R.string.error_answer_required))
            return
        }
        answeringQuestion = pending.id
        viewModelScope.launch {
            try {
                client.answerQuestion(pending.id, answers)
                pendingQuestions = pendingQuestions.filterNot { it.id == pending.id }
                // Remember it locally so the card shows the choice immediately
                // instead of offering buttons the reader has already pressed.
                localAnswers[pending.callId ?: pending.id] = answers.associate { it.id to it.selected }
                problem = null
            } catch (error: ApiException) {
                when (error.code) {
                    "question/settled" -> {
                        // Somebody else answered, or the agent moved on.
                        pendingQuestions = pendingQuestions.filterNot { it.id == pending.id }
                        problem = ConnectionProblem(
                            error.code,
                            TextRef.of(R.string.error_question_settled),
                        )
                    }
                    "transport" -> problem = ConnectionProblem(error.code, describe(error))
                    else -> problem = ConnectionProblem(error.code, TextRef.of(error) ?: TextRef.of(R.string.error_no_host))
                }
            } finally {
                answeringQuestion = null
            }
        }
    }

    /**
     * Answer one approval on the harness's behalf.
     *
     * The card is dropped locally the moment the host accepts the answer, so the
     * buttons stop asking before the next poll confirms it. Losing the race to
     * the desktop is normal, not a failure: the harness settles an approval
     * exactly once and reports a later answer as a conflict.
     *
     * @param approval the pending approval being answered.
     * @param outcome `allowed-once` to grant it, `rejected` to refuse it.
     */
    fun answerApproval(approval: ApprovalRequest, outcome: String) {
        val client = api ?: return
        if (answeringApproval != null) return
        answeringApproval = approval.id
        viewModelScope.launch {
            try {
                client.answerApproval(approval.id, outcome)
                approvals = approvals.filterNot { it.id == approval.id }
                // Hold the answer locally until the host's own log reports it,
                // so the card never contradicts the tap that just happened.
                localVerdicts[approval.callId ?: approval.id] = outcome
                problem = null
            } catch (error: ApiException) {
                if (error.code == "approval/settled") {
                    // Somebody else — the PC, or another phone — got there first.
                    approvals = approvals.filterNot { it.id == approval.id }
                    problem = ConnectionProblem(
                        error.code,
                        TextRef.of(R.string.error_approval_settled),
                    )
                } else {
                    problem = ConnectionProblem(error.code, TextRef.of(error) ?: TextRef.of(R.string.error_no_host))
                }
            } finally {
                answeringApproval = null
            }
        }
    }

    /**
     * Read the pending questions once.
     *
     * Failures are swallowed for the same reason approval failures are: a host
     * without the route must not produce a banner on every poll. The missing
     * route itself is worth saying once, because otherwise an old plugin is
     * indistinguishable from a host with nothing to ask.
     */
    private suspend fun loadQuestions() {
        val client = api ?: return
        try {
            pendingQuestions = client.questions()
            questionsRouteMissing = false
        } catch (error: ApiException) {
            if (error.status == 401) return
            val missing = error.code == "not-found" || error.status == 404
            if (missing && !questionsRouteMissing) {
                questionsRouteMissing = true
                problem = ConnectionProblem(
                    error.code,
                    TextRef.of(R.string.error_questions_route_missing),
                )
            }
        }
    }

    /**
     * Read the pending approvals once.
     *
     * A failure is swallowed unless nothing has ever been read: a host that does
     * not expose approvals must not produce a banner on every poll, and the chat
     * is perfectly usable without that route.
     *
     * The one failure worth announcing is a **missing route**, and only the first
     * time it is seen. Left silent, an old plugin is indistinguishable from a
     * host with nothing waiting — the user would wait for a card that can never
     * arrive. Announced on every poll, it would be noise.
     */
    private suspend fun loadApprovals() {
        val client = api ?: return
        try {
            approvals = client.approvals()
            approvalsRouteMissing = false
        } catch (error: ApiException) {
            if (error.status == 401) return
            val missing = error.code == "not-found" || error.status == 404
            if (missing && !approvalsRouteMissing) {
                approvalsRouteMissing = true
                problem = ConnectionProblem(
                    error.code,
                    TextRef.of(R.string.error_approvals_route_missing),
                )
            }
            // Otherwise keep the last known list rather than flapping to empty
            // on one dropped request: an approval card that vanishes and returns
            // is worse than one that lingers a poll too long.
        }
    }

    /** Begin polling the session list for as long as the app is on screen. */
    private fun startSessionPolling() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            while (isActive) {
                loadSessions()
                delay(listDelay())
            }
        }
    }

    /**
     * Whether the app is on screen.
     *
     * Polling follows this rather than each screen's own lifecycle. With a chat
     * open the list screen is not composed, so its stop-effect never fired — the
     * list loop kept waking the radio every four seconds behind a chat that had
     * already paused its own. One owner for both loops is the only version with
     * no hole in it.
     */
    private var foreground = true

    /** Consecutive failed list polls, for the backoff. */
    private var listFailures = 0

    /** Consecutive failed chat polls, for the backoff. */
    private var chatFailures = 0

    /** Called when the app comes to the screen: start what should be running. */
    fun onForeground() {
        foreground = true
        resumePolling()
        if (openSession != null) resumeChatPolling()
    }

    /** Called when the app leaves the screen: stop both loops. */
    fun onBackground() {
        foreground = false
        pausePolling()
        pauseChatPolling()
    }

    /**
     * Stop polling the session list.
     *
     * Without this the app keeps waking the radio while sitting in the background,
     * spending battery and data on a list nobody is looking at.
     */
    fun pausePolling() {
        listJob?.cancel()
        listJob = null
    }

    /**
     * Restart polling, if a pairing exists and the app is visible.
     *
     * Safe to call repeatedly: the chat job is owned by the chat screen's own
     * open/close, so this only concerns the list.
     */
    fun resumePolling() {
        if (api != null && foreground && listJob == null) startSessionPolling()
    }

    /**
     * How long to wait before the next list poll.
     *
     * Four seconds while a turn is running — the interval at which a dot or an
     * approval appears to move — and fifteen when nothing is, because an idle
     * harness answers the same list every time and the phone pays for it. A
     * failing poll backs off towards a minute instead of hammering a link that is
     * down.
     */
    private fun listDelay(): Long = when {
        listFailures > 0 -> minOf(FAILURE_CAP_MS, LIST_POLL_MS shl minOf(listFailures, 4))
        sessions.any { it.running } -> LIST_POLL_MS
        else -> LIST_IDLE_MS
    }

    /**
     * How long to wait before the next chat poll.
     *
     * Same shape as [listDelay]: fast while the agent is working, slower when the
     * turn is over, and backing off on failure.
     */
    private fun chatDelay(): Long = when {
        chatFailures > 0 -> minOf(FAILURE_CAP_MS, CHAT_POLL_MS shl minOf(chatFailures, 4))
        running -> CHAT_POLL_MS
        else -> CHAT_IDLE_MS
    }

    /** Read the session list once, tolerating a transient failure. */
    private suspend fun loadSessions() {
        val client = api ?: return
        sessionsLoading = true
        try {
            // One request carries the four reads this screen polls for. A host that
            // predates the route answers 404 and is served by the four below.
            if (loadPulse(client)) {
                listFailures = 0
                return
            }
            sessions = client.sessions(includeSubagents = includeSubagents, query = sessionFilter)
            if (sessions.any { it.running }) problem = null
            listFailures = 0
            markOpenSeen()
        } catch (error: ApiException) {
            listFailures += 1
            if (error.status == 401) {
                problem = ConnectionProblem(error.code, TextRef.of(R.string.error_unauthorized))
            } else if (sessions.isEmpty()) {
                problem = ConnectionProblem(error.code, describe(error))
            }
        } finally {
            sessionsLoading = false
        }
        // The project list travels with the chat list so the grouping a person
        // sees on the PC is the grouping they see here. A host that reports no
        // projects is not an error: the list simply stays flat.
        loadWorkspaces()
        // Approvals ride the same poll. They are the one thing that must not sit
        // unread: an unanswered approval blocks the agent on the PC.
        loadApprovals()
        loadQuestions()
    }

    /**
     * Apply one pulse answer, or report that this host has no pulse route.
     *
     * @param client the API to read through.
     * @return true when the answer was applied; false when the route is missing and
     *   the caller should fall back to the four separate reads.
     */
    private suspend fun loadPulse(client: MobileApi): Boolean = try {
        val pulse = client.pulse(includeSubagents, sessionFilter)
        sessions = pulse.sessions
        workspaces = pulse.workspaces
        workspacesAvailable = pulse.workspacesAvailable
        approvals = pulse.approvals
        pendingQuestions = pulse.questions
        approvalsRouteMissing = false
        questionsRouteMissing = false
        if (sessions.any { it.running }) problem = null
        markOpenSeen()
        true
    } catch (error: ApiException) {
        if (error.status == 404) false else throw error
    }

    /**
     * Read the PC's project list once.
     *
     * A failure here never becomes a banner: it degrades the list to its flat
     * form, which is still usable, and a banner for a missing grouping would
     * only be noise on every poll.
     */
    private suspend fun loadWorkspaces() {
        val client = api ?: return
        try {
            val listed = client.workspaces()
            workspaces = listed
            workspacesAvailable = true
        } catch (error: ApiException) {
            if (error.status == 401) return
            workspacesAvailable = false
        }
    }

    /**
     * Read the newest history page for the open session.
     *
     * The append/replace rules live in [ChatSync], which is Android-free and
     * therefore unit tested; this only mirrors its result into Compose state.
     */
    private suspend fun loadNewest() {
        val client = api ?: return
        val session = openSession ?: return
        entriesLoading = true
        try {
            sync.loadNewest(session.id)
            applySync()
            running = sessions.firstOrNull { it.id == session.id }?.running ?: running
            // A chat whose host has gone away must say so: an empty transcript
            // otherwise reads as "this chat has no messages".
            sync.lastError?.let { failure -> problem = ConnectionProblem(failure.code, describe(failure)) }
            if (sync.lastError == null) problem = null
        } finally {
            entriesLoading = false
        }
    }

    /** Pick up whatever the agent appended since the last poll. */
    private suspend fun refresh() {
        val client = api ?: return
        val session = openSession ?: return
        sync.refresh(session.id)
        applySync()
        running = sessions.firstOrNull { it.id == session.id }?.running ?: running
        // Feeds the poll backoff: a chat whose host is unreachable must be retried
        // less and less often, not twenty-four times a minute.
        if (sync.lastError == null) chatFailures = 0 else chatFailures += 1
        // The chat poll is faster than the list poll, so it is also the faster
        // way to notice an approval or a question raised mid-turn.
        loadApprovals()
        loadQuestions()
        when {
            sync.lastError == null -> problem = null
            sync.lastError?.status == 401 ->
                problem = ConnectionProblem("unauthorized", TextRef.of(R.string.error_unauthorized))
            else -> sync.lastError?.let { failure -> problem = ConnectionProblem(failure.code, describe(failure)) }
        }
    }

    /** Mirror the sync state machine into the observable Compose state. */
    private fun applySync() {
        // The full window still drives paging; only what is shown is filtered,
        // so hiding the system prompt cannot make "load older" skip a page.
        entries = visibleTranscript(sync.entries)
        oldestSeq = sync.oldestSeq
        hasOlder = sync.hasOlder
    }

    /**
     * The title of a chat the host has not listed yet.
     *
     * A chat this app has just created lives on the host a moment before it
     * appears in the list, and the row has to say something: the app's own word
     * for it is a resource, because it is a word a person reads.
     *
     * The context is the *localized* one rather than the application's: only the
     * activity is wrapped with the chosen locale (see `attachBaseContext`), so
     * `getApplication().getString` answered in the phone's language no matter what
     * the app had been told to speak — which is how an Italian list grew a "No
     * project" heading.
     */
    private fun blankSessionTitle(): String = localized().getString(R.string.session_untitled)

    /** The heading the chats no project owns are grouped under. */
    private fun blankSectionTitle(): String = localized().getString(R.string.session_ungrouped)

    /**
     * The application context carrying the language the user chose.
     *
     * [withLanguage] returns the context unchanged when the choice is `System`,
     * so this costs a configuration copy only for the two explicit languages.
     */
    private fun localized(): Context = getApplication<Application>().withLanguage(store.language)

    private companion object {
        const val TAG = "DshMobile"
        const val LIST_POLL_MS = 4_000L
        const val LIST_IDLE_MS = 15_000L
        const val CHAT_POLL_MS = 2_500L
        const val CHAT_IDLE_MS = 10_000L

        /** Ceiling for the failure backoff: a minute is enough to stop hammering. */
        const val FAILURE_CAP_MS = 60_000L

        /** How often the seen map may be written while a chat is open. */
        const val SEEN_WRITE_MS = 15_000L

        /**
         * The longest turn span the indicator is willing to print.
         *
         * A day is far past any real turn, so anything beyond it means the two
         * clocks disagree rather than that the agent has been working that long.
         */
        const val MAX_PLAUSIBLE_TURN_MS = 24L * 60L * 60L * 1000L

        /** Matches the mobile API's per-message photo cap. */
        const val MAX_STAGED_PHOTOS = 8

        /** A source for an unpaired app; every read fails as a transport loss. */
        val noSource: ChatSource = object : ChatSource {
            override suspend fun messages(
                sessionId: String,
                beforeSeq: Int?,
                afterSeq: Int?,
                limit: Int,
            ): MessagePage = throw ApiException("transport", "no host connected")
        }
    }
}
